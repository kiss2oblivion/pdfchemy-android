"""Validate the custom OCR AAR and its independently tracked native source revisions."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE_FILE = ROOT / "native/ocr-sources.json"
INVENTORY_FILE = ROOT / "native/ocr-inventory.json"
ARTIFACT = ROOT / "pdf-jail/libs/tesseract4android-4.9.0-inmemory.aar"
ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}
LIBRARIES = {"libjpeg.so", "libleptonica.so", "libpngx.so", "libtesseract.so"}
MEMORY_SYMBOL = b"Java_com_googlecode_tesseract_android_TessBaseAPI_nativeInitMemory"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_digest(value: object) -> str:
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


def elf_loads(data: bytes) -> dict:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5:7] != b"\x01\x01":
        raise ValueError("Unsupported or truncated Android ELF")
    elf_class = data[4]
    if struct.unpack_from("<H", data, 16)[0] != 3:
        raise ValueError("Native library must be an ELF shared object")
    machine = struct.unpack_from("<H", data, 18)[0]
    if struct.unpack_from("<I", data, 20)[0] != 1:
        raise ValueError("Invalid ELF version")
    if elf_class == 1:
        phoff = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        expected_size = 32
    else:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        expected_size = 56
    header_size = 52 if elf_class == 1 else 64
    if entry_size != expected_size or not 0 < count <= 1024 or phoff < header_size or phoff + count * entry_size > len(data):
        raise ValueError("Invalid ELF program header table")
    alignments = []
    for index in range(count):
        offset = phoff + index * entry_size
        if struct.unpack_from("<I", data, offset)[0] != 1:
            continue
        if elf_class == 1:
            _, file_offset, virtual, _, size, memory_size, _, alignment = struct.unpack_from("<8I", data, offset)
        else:
            _, _, file_offset, virtual, _, size, memory_size, alignment = struct.unpack_from("<II6Q", data, offset)
        if alignment == 0 or alignment & (alignment - 1) or file_offset % alignment != virtual % alignment:
            raise ValueError("Invalid native load segment alignment")
        if size > memory_size or file_offset + size > len(data):
            raise ValueError("Native load segment exceeds the file")
        alignments.append(alignment)
    if not alignments:
        raise ValueError("Native ELF contains no load segments")
    return dict(elf_class=elf_class, machine=machine, load_alignments=alignments)


def inspect(artifact: Path) -> dict:
    if artifact.stat().st_size > 128 * 1024 * 1024:
        raise ValueError("Unexpected OCR AAR size")
    result = dict(artifact=artifact.name, sha256=digest(artifact.read_bytes()), libraries=[])
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        if sum(entry.file_size for entry in archive.infolist()) > 256 * 1024 * 1024:
            raise ValueError("Unexpected expanded OCR AAR size")
        if len(names) != len(set(names)):
            raise ValueError("Duplicate AAR entries")
        if archive.getinfo("AndroidManifest.xml").file_size > 128 * 1024:
            raise ValueError("Unexpected OCR manifest size")
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
        sdk = manifest.find("uses-sdk")
        if sdk is None:
            raise ValueError("Missing OCR minimum SDK declaration")
        result["min_sdk"] = int(sdk.attrib["{http://schemas.android.com/apk/res/android}minSdkVersion"])
        native_names = {name for name in names if name.startswith("jni/") and name.endswith(".so")}
        expected = {f"jni/{abi}/{library}" for abi in ABIS for library in LIBRARIES}
        if native_names != expected:
            raise ValueError("Missing or unexpected native ABI/library: " + repr(sorted(native_names ^ expected)))
        if archive.getinfo("classes.jar").file_size > 16 * 1024 * 1024:
            raise ValueError("Unexpected classes.jar size")
        classes = archive.read("classes.jar")
        result["classes_jar_sha256"] = digest(classes)
        with zipfile.ZipFile(io.BytesIO(classes)) as jar:
            if len(jar.namelist()) != len(set(jar.namelist())):
                raise ValueError("Duplicate classes.jar entries")
            if jar.getinfo("com/googlecode/tesseract/android/TessBaseAPI.class").file_size > 2 * 1024 * 1024:
                raise ValueError("Unexpected TessBaseAPI class size")
            api = jar.read("com/googlecode/tesseract/android/TessBaseAPI.class")
            if not all(value in api for value in (b"nativeInitMemory", b"([BLjava/lang/String;I)Z", b"([BLjava/lang/String;)Z")):
                raise ValueError("Custom in-memory Java model initialization API is missing")
        for name in sorted(native_names):
            if archive.getinfo(name).file_size > 64 * 1024 * 1024:
                raise ValueError("Unexpected native library size")
            data = archive.read(name)
            abi, library = name.split("/")[1:]
            elf = elf_loads(data)
            if (elf["elf_class"], elf["machine"]) != ABIS[abi]:
                raise ValueError("Native ELF architecture differs from its ABI directory: " + name)
            entry = dict(path=name, sha256=digest(data), size=len(data), **elf)
            if library == "libtesseract.so":
                if MEMORY_SYMBOL not in data:
                    raise ValueError("Custom in-memory JNI export is missing: " + abi)
                entry["observed_versions"] = sorted({x.decode() for x in re.findall(rb"(?<![0-9])5\.[0-9]+\.[0-9]+(?![0-9])", data)})
            elif library == "libpngx.so":
                entry["observed_versions"] = sorted({x.decode() for x in re.findall(rb"(?<![0-9])1\.6\.[0-9]+(?![0-9])", data)})
            elif library == "libleptonica.so":
                entry["memory_stream_symbols"] = all(b"\0" + symbol + b"\0" in data
                    for symbol in (b"fmemopen", b"open_memstream"))
            result["libraries"].append(entry)
    return result


def require_policy(binary: dict, sources: dict, expected_min_sdk: int = 24) -> None:
    required = {"libtesseract.so": sources["tesseract"]["version"], "libpngx.so": sources["libpng"]["version"]}
    failures = []
    if binary["min_sdk"] != expected_min_sdk:
        failures.append(f'Native wrapper must target minimum API {expected_min_sdk}, observed {binary["min_sdk"]}')
    for entry in binary["libraries"]:
        # Android's 16 KB compatibility requirement applies to the 64-bit ABIs.
        if entry["elf_class"] == 2 and min(entry["load_alignments"]) < 16384:
            failures.append(f'{entry["path"]}: 64-bit load alignment below 16 KB')
        library = entry["path"].split("/")[-1]
        if library == "libleptonica.so" and not entry.get("memory_stream_symbols"):
            failures.append(f'{entry["path"]}: native memory-stream backend symbols are missing')
        if library in required and entry.get("observed_versions") != [required[library]]:
            failures.append(f'{entry["path"]}: expected {required[library]}, observed {entry.get("observed_versions")}')
    if failures:
        raise ValueError("Native artifact policy rejected:\n" + "\n".join(failures))


def require_inventory(inventory: dict, binary: dict, config: dict) -> None:
    if inventory.get("schema_version") != 1 or inventory.get("source_manifest_sha256") != canonical_digest(config):
        raise ValueError("Native inventory does not match current source policy")
    if inventory.get("binary") != binary:
        raise ValueError("Native artifact differs from reviewed inventory")
    require_build_receipt(inventory.get("build", {}), config)


def require_build_receipt(receipt: dict, config: dict) -> None:
    if receipt.get("sources") != config["sources"] or receipt.get("toolchain") != config["toolchain"]:
        raise ValueError("Native build inputs differ from pinned source policy")
    expected = {"patch_sha256": ROOT / config["patch"], "recipe_sha256": ROOT / "tools/prepare_native_ocr.py"}
    for key, path in expected.items():
        # Receipts retain the exact build-time bytes. Git checkouts may convert
        # text LF/CRLF on Windows; accept only those equivalent text encodings.
        normalized = path.read_bytes().replace(b"\r\n", b"\n")
        accepted = {digest(normalized), digest(normalized.replace(b"\n", b"\r\n"))}
        if receipt.get(key) not in accepted:
            raise ValueError("Native build provenance differs from tracked " + path.name)


def advisories(sources: dict) -> None:
    findings = []
    for name in ("tesseract", "leptonica", "libpng"):
        revision = sources[name]["revision"]
        request = urllib.request.Request("https://api.osv.dev/v1/query",
            data=json.dumps(dict(commit=revision)).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=90) as response:
            body = json.load(response)
        if not isinstance(body, dict) or "error" in body or body.get("next_page_token") or (body and "vulns" not in body):
            raise ValueError("Incomplete native advisory response: " + name)
        records = body.get("vulns", [])
        if not isinstance(records, list) or any(not isinstance(record, dict) or not record.get("id") for record in records):
            raise ValueError("Invalid native advisory records: " + name)
        ids = [record["id"] for record in records if not record.get("withdrawn")]
        print(f"OSV Git {name} {revision}: {len(ids)} non-withdrawn findings")
        findings.extend(f"{name}: {identifier}" for identifier in ids)
    if findings:
        raise ValueError("Native source advisories require review:\n" + "\n".join(findings))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("inspect", "minimums", "record", "check"))
    parser.add_argument("--artifact", type=Path, default=ARTIFACT)
    parser.add_argument("--inventory", type=Path, default=INVENTORY_FILE)
    parser.add_argument("--build-receipt", type=Path)
    parser.add_argument("--offline", action="store_true", help="Validate artifact integrity only; skip live advisory queries")
    args = parser.parse_args()
    config = json.loads(SOURCE_FILE.read_text(encoding="utf-8"))
    binary = inspect(args.artifact)
    if args.mode == "inspect":
        print(json.dumps(binary, indent=2))
        return
    require_policy(binary, config["sources"], config["toolchain"]["min_sdk"])
    if args.mode == "minimums":
        print("Native ABI, API floor, memory backend, ELF alignment, custom JNI and version policy passed")
        return
    if args.mode == "record":
        if args.build_receipt is None:
            parser.error("record requires --build-receipt")
        receipt = json.loads(args.build_receipt.read_text(encoding="utf-8"))
        require_build_receipt(receipt, config)
        args.inventory.write_text(json.dumps(dict(schema_version=1, source_manifest_sha256=canonical_digest(config),
            binary=binary, build=receipt), indent=2) + "\n", encoding="utf-8")
        print("Recorded native artifact inventory: " + str(args.inventory))
        return
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    require_inventory(inventory, binary, config)
    print(f'Native artifact: API {binary["min_sdk"]}, four ABIs, 16 library hashes, memory backend, custom JNI and 64-bit 16 KB ELF loads verified')
    if not args.offline:
        advisories(config["sources"])
    else:
        print("Offline validation: live advisory queries were not run")
    print("Coverage: three upstream Git components; retained IJG libjpeg, NDK legacy shim and Android system libraries require separate review")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Native OCR check failed: {error}", file=sys.stderr)
        sys.exit(1)
