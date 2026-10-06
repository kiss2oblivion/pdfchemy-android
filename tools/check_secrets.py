"""Full-history TruffleHog gate with exact reviewed historical fingerprints.

Verified findings, verification errors, new bytes/locations/commits and incomplete
scans always fail. Candidate values are never written to the CI log.
"""
import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
VERSION = "3.97.9"
ARCHIVES = {
    "Linux": ("linux_amd64", "40377e6572495412fb9ba0bc21c9401f73b72f1d2afd11b9931bc4a5ed622866", "trufflehog"),
    "Windows": ("windows_amd64", "7436c13a12f378738db1b65803b00fa3b816db3dc28084e52b9a377c6b12901d", "trufflehog.exe"),
}
ROOT = Path(__file__).resolve().parents[1]


def is_reviewed(record, reviewed):
    if record.get("Verified") is not False or record.get("VerificationError"):
        return False
    raw = record.get("Raw")
    source = record.get("SourceMetadata", {}).get("Data", {}).get("Git", {})
    if not isinstance(raw, str):
        return False
    digest = hashlib.sha256(raw.encode()).hexdigest()
    return any(
        record.get("DetectorName") == item["detector"]
        and record.get("DecoderName") == item["decoder"]
        and digest == item["rawSha256"]
        and {"path": source.get("file"), "commit": source.get("commit")} in item["sources"]
        for item in reviewed
    )


def scan_complete(returncode, logs, head):
    complete = any(item.get("msg") == "finished scanning" and item.get("trufflehog_version") == VERSION for item in logs)
    correct_head = any(item.get("msg") == "scanning repo" and item.get("head") == head for item in logs)
    errors = any(str(item.get("level", "")).startswith(("error", "fatal")) for item in logs)
    return not returncode and complete and correct_head and not errors


def install():
    suffix, expected, member_name = ARCHIVES[platform.system()]
    directory = Path(tempfile.mkdtemp(prefix="pdfchemy-secret-scanner-"))
    archive = directory / "scanner.tar.gz"
    url = f"https://github.com/trufflesecurity/trufflehog/releases/download/v{VERSION}/trufflehog_{VERSION}_{suffix}.tar.gz"
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=60) as response, archive.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != expected:
        raise ValueError("Scanner release SHA-256 mismatch")
    binary = directory / member_name
    with tarfile.open(archive) as bundle:
        member = bundle.getmember(member_name)
        if not member.isfile() or member.size > 400 * 1024 * 1024:
            raise ValueError("Unexpected scanner archive member")
        with bundle.extractfile(member) as source, binary.open("wb") as output:
            import shutil
            shutil.copyfileobj(source, output)
    binary.chmod(0o700)
    return binary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, help="Locally installed, release-checksum-verified scanner")
    args = parser.parse_args()
    binary = args.binary.resolve() if args.binary else install()
    reviewed = json.loads((ROOT / ".github/secret-scan-reviewed.json").read_text(encoding="utf-8"))["findings"]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    # TruffleHog's Windows file-URI handling resolves from the current drive.
    uri = "file://" + ROOT.as_posix()[2:] if os.name == "nt" else ROOT.as_uri()
    result = subprocess.run([str(binary), "git", uri, "--branch", "HEAD", "--json", "--no-update", "--concurrency", "2"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=600)
    logs = [json.loads(line) for line in result.stderr.splitlines() if line.strip()]
    if not scan_complete(result.returncode, logs, head):
        print("Secret scan failed or was incomplete; no findings are suppressed")
        return 2
    records = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    rejected = [record for record in records if not is_reviewed(record, reviewed)]
    for record in rejected:
        source = record.get("SourceMetadata", {}).get("Data", {}).get("Git", {})
        print(json.dumps({"detector": record.get("DetectorName"), "verified": record.get("Verified"), "verificationError": bool(record.get("VerificationError")), "path": source.get("file"), "commit": source.get("commit"), "line": source.get("line")}, ensure_ascii=False))
    print(f"TruffleHog {VERSION}: {len(records)} findings; {len(records) - len(rejected)} exact reviewed historical matches; {len(rejected)} rejected")
    return 1 if rejected else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        # Do not expose URLs or request contents from credential verifiers.
        print(f"Secret scan could not complete ({type(error).__name__})")
        sys.exit(2)
