"""Release checks using committed fixtures and built artifacts; no store claims."""
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--artifact", action="store_true")
    artifact = args.parse_args().artifact
    corpus = ROOT / "app-host/src/androidTest/assets/vanguard-benign"
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    entries = {entry["file"]: entry for entry in manifest}
    files = {file.name for file in corpus.glob("*.pdf")}
    check(files == set(entries), "Every corpus PDF needs a manifest entry and provenance")
    check(len(files) >= 11, "Incomplete initial benign corpus")
    for filename, entry in entries.items():
        data = (corpus / filename).read_bytes()
        check(hashlib.sha256(data).hexdigest() == entry["sha256"], f"Fixture changed without manifest update: {filename}")
        check(data.startswith(b"%PDF-") and b"%%EOF" in data[-4096:], f"Invalid fixture header/EOF: {filename}")
        check(bool(entry["provenance"]), f"Missing provenance: {filename}")
    source = (ROOT / "app-host/src/main/java/com/pdfchemy/app/MainActivity.kt").read_text(encoding="utf-8")
    registry = source.split("sealed class Screen {", 1)[1].split("val ScreenSaver", 1)[0]
    screens = set(re.findall(r"(?:object|data class) (\w+).*?: Screen\(\)", registry))
    rows = json.loads((ROOT / "reports/release-candidate-2026-10-06/tool-smoke-matrix.json").read_text(encoding="utf-8"))
    check({row["screen"] for row in rows} == screens, "Smoke matrix does not match all user-facing Screen routes")
    check(len(rows) == len(screens), "Duplicate smoke matrix entries")
    for row in rows:
        check(all(row.get(field) for field in ("input", "operation", "output", "open_share", "cancel", "verification")), f"Incomplete checklist: {row['screen']}")
    gradle = (ROOT / "app-host/build.gradle.kts").read_text(encoding="utf-8")
    release = gradle.split("        release {", 1)[1].split("    compileOptions", 1)[0]
    check("isMinifyEnabled = true" in release and "isShrinkResources = true" in release, "Release shrinking disabled")
    check("ca-app-pub-3940256099942544" not in release, "Google test AdMob ID in release config")
    baseline = ET.parse(ROOT / "app-host/lint-localization-baseline.xml").getroot()
    ids = {issue.get("id") for issue in baseline.findall("issue")}
    check(ids <= {"MissingTranslation", "ExtraTranslation"}, "Non-localization errors hidden by lint baseline")
    report = dict(corpus_files=len(files), smoke_routes=len(rows), fixture_hashes="PASS", production_ad_ids="PASS", lint_baseline_issue_types=sorted(ids))
    if artifact:
        xml = ROOT / "app-host/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
        tree = ET.parse(xml).getroot()
        application = tree.find("application")
        check(application is not None, "Release application manifest missing")
        check(application.get(A + "debuggable", "false") == "false", "Release APK is debuggable")
        check(application.get(A + "testOnly", "false") == "false", "Release APK is testOnly")
        check(application.get(A + "allowBackup") == "false", "Document backup unexpectedly enabled")
        sdk = tree.find("uses-sdk")
        check(sdk.get(A + "minSdkVersion") == "24" and sdk.get(A + "targetSdkVersion") == "36", "Unexpected SDK configuration")
        check(tree.get(A + "versionCode") == "13" and tree.get(A + "versionName") == "2.0.6", "Unexpected RC version; deliberately update gate when incrementing")
        metadata = {node.get(A + "name"): node.get(A + "value") for node in application.findall("meta-data")}
        check(metadata.get("com.google.android.gms.ads.APPLICATION_ID") == "ca-app-pub-8945763551071628~8206577473", "Unexpected production AdMob app ID")
        check(metadata.get("firebase_analytics_collection_deactivated") == "true", "Analytics deactivation missing")
        for worker in ("com.pdfchemy.app.jail.PdfJailService", "com.pdfchemy.app.sandbox.PdfNativeRendererService"):
            node = next((node for node in application.findall("service") if node.get(A + "name") == worker), None)
            check(node is not None and node.get(A + "isolatedProcess") == "true" and node.get(A + "exported") == "false", f"Worker isolation changed: {worker}")
        exported = []
        for node in application:
            if node.get(A + "exported") == "true":
                name = node.get(A + "name")
                exported.append(name)
                check(name == "com.pdfchemy.app.MainActivity" or bool(node.get(A + "permission")), f"Unprotected exported component: {name}")
        permissions = {node.get(A + "name") for node in tree.findall("uses-permission")}
        check(not permissions & {"android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS"}, "Unexpected broad permission")
        apks = list((ROOT / "app-host/build/outputs/apk/release").glob("*.apk"))
        check(bool(apks), "Release APK missing; assembleRelease must actually run")
        apk = max(apks, key=lambda path: path.stat().st_mtime)
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            forbidden = [name for name in names if name.startswith("assets/") and any(part in name.lower() for part in ("vanguard-benign", "test_pdfs", "intentionally-damaged", "keystore", ".jks"))]
            check(not forbidden, f"Test/secret assets in release APK: {forbidden}")
            dex = b"".join(archive.read(name) for name in names if re.fullmatch(r"classes\d*\.dex", name))
            for marker in (b"Lcom/pdfchemy/app/security/OutputCommitProvider;", b"Lcom/pdfchemy/app/security/MutableInputProvider;", b"Lcom/pdfchemy/app/security/BenignPdfCompatibilityTest;"):
                check(marker not in dex, "Instrumentation class leaked into production DEX")
        report.update(version="2.0.6 (13)", sdk="24..36", release_apk=apk.name, artifact_hygiene="PASS", exported_components=exported)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
