"""Inspect minified DEX, rather than ProGuard source, for Gson's runtime schema.

Uses the Android SDK dexdump annotation output. The SDK performs DEX decoding;
this parser only groups its class/field/annotation records.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

TOKEN = "Lcom/google/gson/reflect/TypeToken;"
PREFIX = "Lcom/pdfchemy/app/logic/"
SCHEMA = {
    "PageModification": "pageIndex rotationDegrees isDeleted drawings textAnnotations stamps redactions",
    "DrawingPoint": "x y",
    "DrawingPath": "id points color strokeWidth isHighlighter",
    "TextAnnotation": "id text xRatio yRatio fontSize textColor backgroundColor",
    "StampAnnotation": "id type xRatio yRatio scale rotation",
    "StampType": "APPROVED CONFIDENTIAL DRAFT PAID REJECTED FINAL URGENT",
    "RedactionBox": "pageIndex xRatio yRatio widthRatio heightRatio normalizedRect overlayLabel",
    "BatesConfig": "enabled prefix suffix startNumber digits",
    "StampConfig": "templateText position fontSize marginPt startFromPage batesConfig",
    "HeaderFooterPosition": "HEADER_LEFT HEADER_CENTER HEADER_RIGHT FOOTER_LEFT FOOTER_CENTER FOOTER_RIGHT",
    "ImageAnalysis": "width height originalSizeBytes mimeType formatName isSupported validationStatus validationMessage alternativeSuggestion estimatedCompressedBytes estimatedSavingsPercent qualityLoss",
    "ImageValidationStatus": "ALLOWED WARNING_ALREADY_COMPRESSED DENIED_UNSUPPORTED_FORMAT DENIED_CORRUPT DENIED_TOO_SMALL",
    "PerceivedQualityLoss": "NEGLIGIBLE MINIMAL MODERATE SIGNIFICANT",
    "ImageCompressionResult": "success originalSize compressedSize width height format error",
    "TextMatchOccurrence": "pageIndex matchedText snippet bounds fontSize",
    "FindReplaceSummary": "totalMatches pagesAffected occurrences",
    "EmbeddedImageInfo": "id pageIndex resourceName width height format thumbnailBitmap",
}
COLLECTIONS = {
    ("PageModification", "drawings"): "DrawingPath",
    ("PageModification", "textAnnotations"): "TextAnnotation",
    ("PageModification", "stamps"): "StampAnnotation",
    ("PageModification", "redactions"): "RedactionBox",
    ("DrawingPath", "points"): "DrawingPoint",
    ("FindReplaceSummary", "occurrences"): "TextMatchOccurrence",
}


def parse_class_dump(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    sections = {}
    matches = list(re.finditer(r"(?m)^Class #(\d+)[^\n]*\n", text))
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        sections.setdefault(match.group(1), []).append(text[match.end():end])
    result = {}
    for parts in sections.values():
        block = "\n".join(parts)
        name = re.search(r"Class descriptor\s*:\s*'([^']+)'", block)
        if not name:
            continue
        superclass = re.search(r"Superclass\s*:\s*'([^']+)'", block)
        class_annotations = re.search(r"Annotations on class\n(.*?)(?=\nAnnotations on |\n\s*Class descriptor|\Z)", block, re.S)
        signature = None
        if class_annotations:
            value = re.search(r"Ldalvik/annotation/Signature; value=\{([^\n]*)\}", class_annotations.group(1))
            if value:
                signature = "".join(re.findall(r'"([^"\n]*)"', value.group(1)))
        fields = re.search(r"Static fields\s*-\n(.*?)\n\s*Direct methods\s*-", block, re.S)
        names = set(re.findall(r"\bname\s*:\s*'([^']+)'", fields.group(1))) if fields else set()
        field_signatures = {}
        for match in re.finditer(r"Annotations on field #\d+ '([^']+)'\n(.*?)(?=\nAnnotations on |\n\s*Class descriptor|\Z)", block, re.S):
            value = re.search(r"Ldalvik/annotation/Signature; value=\{([^\n]*)\}", match.group(2))
            if value:
                field_signatures[match.group(1)] = "".join(re.findall(r'"([^"\n]*)"', value.group(1)))
        result[name.group(1)] = dict(superclass=superclass.group(1) if superclass else None,
                                    signature=signature, fields=names, field_signatures=field_signatures)
    return result


def validate_contract(classes):
    def require(condition, message):
        if not condition:
            raise RuntimeError(message)
    require(TOKEN in classes, "Minified APK removed or renamed Gson TypeToken; release editor export cannot recover its generic type")
    require(classes[TOKEN]["signature"], "Gson TypeToken generic signature missing from minified DEX")
    expected = (re.escape(TOKEN[:-1]) + r"<Ljava/util/Map<\+?Ljava/lang/Integer;\+?" +
                re.escape(PREFIX + "PageModification;>;>;"))
    tokens = [name for name, row in classes.items()
              if row["superclass"] == TOKEN and re.fullmatch(expected, row["signature"] or "")]
    require(len(tokens) >= 2, "Both editor export TypeToken subclasses must retain Map<Integer, PageModification> signatures")
    for model, fields in SCHEMA.items():
        name = PREFIX + model + ";"
        require(name in classes, "Gson wire model missing from minified DEX: " + model)
        missing = set(fields.split()) - classes[name]["fields"]
        require(not missing, "Gson wire fields renamed/removed for " + model + ": " + ", ".join(sorted(missing)))
    for (model, field), element in COLLECTIONS.items():
        signature = classes[PREFIX + model + ";"]["field_signatures"].get(field, "")
        expected = r"Ljava/util/List<\+?" + re.escape(PREFIX + element + ";>;")
        require(re.fullmatch(expected, signature), "Gson collection signature missing for " + model + "." + field)
    return dict(status="PASS", editor_type_tokens=sorted(tokens), preserved_wire_models=len(SCHEMA),
                preserved_collection_signatures=len(COLLECTIONS))


def inspect_apk(apk):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    executable = "dexdump.exe" if os.name == "nt" else "dexdump"
    candidates = sorted((Path(sdk) / "build-tools").glob("*/" + executable), reverse=True) if sdk else []
    tool = str(candidates[0]) if candidates else shutil.which(executable)
    if not tool:
        raise RuntimeError("Android SDK dexdump is required for the minified Gson contract check")
    temp_root = Path(__file__).resolve().parents[1] / "build/tmp"
    temp_root.mkdir(parents=True, exist_ok=True)
    classes = {}
    with tempfile.TemporaryDirectory(prefix="gson-dex-", dir=temp_root) as temporary:
        directory = Path(temporary).resolve()
        assert directory.is_relative_to(temp_root.resolve())
        with zipfile.ZipFile(apk) as archive:
            names = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
            if not names:
                raise RuntimeError("APK contains no DEX files")
            for name in names:
                path = directory / name
                path.write_bytes(archive.read(name))
                dump = subprocess.run([tool, "-a", "-n", str(path)], capture_output=True, check=True,
                                      timeout=120).stdout.decode("utf-8", errors="replace")
                classes.update(parse_class_dump(dump))
    report = validate_contract(classes)
    report["apk_sha256"] = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    print(json.dumps(inspect_apk(parser.parse_args().apk), indent=2))
