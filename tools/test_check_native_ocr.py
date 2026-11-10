"""Artifact-policy regressions using the real custom AAR and corrupted copies."""
from __future__ import annotations

import copy
import contextlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

from tools import check_native_ocr as policy


class NativeArtifactPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.artifact = Path(os.environ.get("PDFCHEMY_NATIVE_TEST_AAR", str(policy.ARTIFACT)))
        cls.config = json.loads(policy.SOURCE_FILE.read_text(encoding="utf-8"))
        cls.binary = policy.inspect(cls.artifact)

    def setUp(self):
        # Mock advisory responses are not live scan evidence.
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.temp = tempfile.TemporaryDirectory(prefix="pdfchemy-native-policy-")
        self.addCleanup(self.temp.cleanup)

    def mutate(self, changes=None, omit_prefix=None, extra=None):
        changes = changes or {}
        destination = Path(self.temp.name) / self.artifact.name
        with zipfile.ZipFile(self.artifact) as source, zipfile.ZipFile(destination, "w", zipfile.ZIP_STORED) as output:
            for info in source.infolist():
                if omit_prefix and info.filename.startswith(omit_prefix):
                    continue
                data = source.read(info.filename)
                if info.filename in changes:
                    data = changes[info.filename](data)
                output.writestr(info.filename, data)
            if extra:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    output.writestr(*extra)
        return destination

    @staticmethod
    def load_field(data, relative, fmt, value):
        changed = bytearray(data)
        table = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        for index in range(count):
            offset = table + index * entry_size
            if struct.unpack_from("<I", data, offset)[0] == 1:
                struct.pack_into(fmt, changed, offset + relative, value)
                return bytes(changed)
        raise AssertionError("Fixture contains no PT_LOAD")

    def test_current_artifact_satisfies_native_source_policy(self):
        policy.require_policy(self.binary, self.config["sources"])
        self.assertEqual(16, len(self.binary["libraries"]))

    def test_32_bit_four_kib_loads_remain_supported(self):
        entries = [entry for entry in self.binary["libraries"] if entry["elf_class"] == 1]
        self.assertEqual(8, len(entries))
        self.assertTrue(all(min(entry["load_alignments"]) == 4096 for entry in entries))
        policy.require_policy(self.binary, self.config["sources"])

    def test_64_bit_four_kib_alignment_is_rejected(self):
        artifact = self.mutate({"jni/x86_64/libtesseract.so": lambda data: self.load_field(data, 48, "<Q", 4096)})
        with self.assertRaisesRegex(ValueError, "64-bit load alignment"):
            policy.require_policy(policy.inspect(artifact), self.config["sources"])

    def test_wrong_architecture_cannot_hide_in_an_abi_directory(self):
        def wrong_machine(data):
            changed = bytearray(data)
            struct.pack_into("<H", changed, 18, 183)
            return bytes(changed)
        artifact = self.mutate({"jni/x86_64/libtesseract.so": wrong_machine})
        with self.assertRaisesRegex(ValueError, "architecture differs"):
            policy.inspect(artifact)

    def test_truncated_elf_is_rejected(self):
        artifact = self.mutate({"jni/x86_64/libtesseract.so": lambda data: data[:24]})
        with self.assertRaisesRegex(ValueError, "truncated"):
            policy.inspect(artifact)

    def test_out_of_bounds_program_header_table_is_rejected(self):
        def invalid_table(data):
            changed = bytearray(data)
            struct.pack_into("<Q", changed, 32, 0xffffffffffffffff)
            return bytes(changed)
        artifact = self.mutate({"jni/x86_64/libtesseract.so": invalid_table})
        with self.assertRaisesRegex(ValueError, "program header"):
            policy.inspect(artifact)

    def test_load_segment_cannot_exceed_the_file(self):
        artifact = self.mutate({"jni/x86_64/libtesseract.so": lambda data: self.load_field(data, 32, "<Q", 0xffffffffffffffff)})
        with self.assertRaisesRegex(ValueError, "load segment exceeds"):
            policy.inspect(artifact)

    def test_invalid_alignment_is_rejected(self):
        artifact = self.mutate({"jni/x86_64/libtesseract.so": lambda data: self.load_field(data, 48, "<Q", 3)})
        with self.assertRaisesRegex(ValueError, "Invalid native load segment alignment"):
            policy.inspect(artifact)

    def test_missing_abi_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing or unexpected"):
            policy.inspect(self.mutate(omit_prefix="jni/arm64-v8a/"))

    def test_unreviewed_native_dependency_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing or unexpected"):
            policy.inspect(self.mutate(extra=("jni/x86_64/libunexpected.so", b"unexpected native dependency")))

    def test_duplicate_zip_entry_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate AAR"):
            policy.inspect(self.mutate(extra=("R.txt", b"duplicate")))

    def test_missing_memory_initialization_export_is_rejected(self):
        artifact = self.mutate({"jni/x86_64/libtesseract.so": lambda data: data.replace(policy.MEMORY_SYMBOL, b"_" * len(policy.MEMORY_SYMBOL))})
        with self.assertRaisesRegex(ValueError, "in-memory JNI"):
            policy.inspect(artifact)

    def test_missing_java_memory_overload_is_rejected(self):
        def without_overload(data):
            output = io.BytesIO()
            with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output, "w") as jar:
                for info in source.infolist():
                    value = source.read(info.filename)
                    if info.filename.endswith("/TessBaseAPI.class"):
                        descriptor = b"([BLjava/lang/String;)Z"
                        self.assertIn(descriptor, value)
                        value = value.replace(descriptor, b"_" * len(descriptor))
                    jar.writestr(info.filename, value)
            return output.getvalue()
        with self.assertRaisesRegex(ValueError, "in-memory Java"):
            policy.inspect(self.mutate({"classes.jar": without_overload}))

    def test_old_engine_version_is_rejected(self):
        artifact = self.mutate({"jni/x86_64/libtesseract.so": lambda data: data.replace(b"5.5.4", b"5.5.1")})
        with self.assertRaisesRegex(ValueError, "expected 5.5.4"):
            policy.require_policy(policy.inspect(artifact), self.config["sources"])

    def test_old_native_api_floor_is_rejected(self):
        artifact = self.mutate({"AndroidManifest.xml": lambda data: data.replace(b'minSdkVersion="24"', b'minSdkVersion="21"')})
        with self.assertRaisesRegex(ValueError, "minimum API 24, observed 21"):
            policy.require_policy(policy.inspect(artifact), self.config["sources"])

    def test_missing_native_memory_backend_is_rejected(self):
        artifact = self.mutate({"jni/x86/libleptonica.so": lambda data: data.replace(b"fmemopen", b"_" * 8)})
        with self.assertRaisesRegex(ValueError, "memory-stream backend"):
            policy.require_policy(policy.inspect(artifact), self.config["sources"])

    def test_artifact_hash_covers_non_native_content(self):
        inventory = dict(schema_version=1, source_manifest_sha256=policy.canonical_digest(self.config), binary=self.binary)
        changed = policy.inspect(self.mutate({"R.txt": lambda data: data + b"\nunreviewed content\n"}))
        self.assertNotEqual(self.binary["sha256"], changed["sha256"])
        with self.assertRaisesRegex(ValueError, "differs from reviewed"):
            policy.require_inventory(inventory, changed, self.config)

    def test_source_policy_cannot_change_without_inventory_review(self):
        inventory = dict(schema_version=1, source_manifest_sha256=policy.canonical_digest(self.config), binary=self.binary)
        changed = copy.deepcopy(self.config)
        changed["sources"]["tesseract"]["revision"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "source policy"):
            policy.require_inventory(inventory, self.binary, changed)

    def test_empty_advisory_responses_query_each_pinned_git_component(self):
        with patch.object(policy.urllib.request, "urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"{}")) as request:
            policy.advisories(self.config["sources"])
        commits = {json.loads(call.args[0].data)["commit"] for call in request.call_args_list}
        self.assertEqual({self.config["sources"][name]["revision"] for name in ("tesseract", "leptonica", "libpng")}, commits)

    def test_recorded_build_recipe_cannot_change_silently(self):
        receipt = dict(sources=self.config["sources"], toolchain=self.config["toolchain"],
                       patch_sha256=policy.digest((policy.ROOT / self.config["patch"]).read_bytes()),
                       recipe_sha256="0" * 64)
        with self.assertRaisesRegex(ValueError, "tracked prepare_native_ocr"):
            policy.require_build_receipt(receipt, self.config)

    def test_build_provenance_survives_git_line_ending_conversion(self):
        receipt = dict(sources=self.config["sources"], toolchain=self.config["toolchain"],
                       recipe_sha256=policy.digest((policy.ROOT / "tools/prepare_native_ocr.py").read_bytes()))
        patch_bytes = (policy.ROOT / self.config["patch"]).read_bytes().replace(b"\r\n", b"\n")
        for data in (patch_bytes, patch_bytes.replace(b"\n", b"\r\n")):
            receipt["patch_sha256"] = policy.digest(data)
            policy.require_build_receipt(receipt, self.config)

    def test_build_provenance_rejects_modified_patch(self):
        receipt = dict(sources=self.config["sources"], toolchain=self.config["toolchain"],
                       recipe_sha256=policy.digest((policy.ROOT / "tools/prepare_native_ocr.py").read_bytes()),
                       patch_sha256="0" * 64)
        with self.assertRaisesRegex(ValueError, "tracked tesseract4android"):
            policy.require_build_receipt(receipt, self.config)

    def test_recorded_build_toolchain_must_match(self):
        with self.assertRaisesRegex(ValueError, "build inputs"):
            policy.require_build_receipt(dict(sources=self.config["sources"], toolchain={}), self.config)

    def test_unreviewed_advisory_is_not_suppressed(self):
        with patch.object(policy.urllib.request, "urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b'{"vulns":[{"id":"CVE-2099-0001"}]}')):
            with self.assertRaisesRegex(ValueError, "CVE-2099-0001"):
                policy.advisories(self.config["sources"])

    def test_incomplete_advisory_page_is_rejected(self):
        with patch.object(policy.urllib.request, "urlopen", return_value=io.BytesIO(b'{"vulns":[],"next_page_token":"more"}')):
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                policy.advisories(self.config["sources"])

    def test_invalid_advisory_schema_is_rejected(self):
        with patch.object(policy.urllib.request, "urlopen", return_value=io.BytesIO(b'{"vulns":"invalid"}')):
            with self.assertRaisesRegex(ValueError, "Invalid native advisory"):
                policy.advisories(self.config["sources"])


if __name__ == "__main__":
    unittest.main()
