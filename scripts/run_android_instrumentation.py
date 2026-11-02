"""Install without streaming and require a complete, passing native test run.

API 24's ddmlib install-write path can stall before executing any tests. This
runner uses adb's push-install path and runs the same unfiltered AndroidJUnitRunner
suite. adb's exit status alone is insufficient: failed tests can still return 0.
"""

import argparse
from pathlib import Path
import re
import subprocess
import sys


def validate_result(output: str) -> int:
    summaries = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    if len(summaries) != 1 or int(summaries[0]) <= 0:
        raise ValueError("Missing unique nonzero passing test summary")
    total = int(summaries[0])
    if re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|Process crashed", output):
        raise ValueError("Instrumentation reported failure or crash")
    if re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", output, re.MULTILINE) != ["-1"]:
        raise ValueError("Missing successful instrumentation completion")

    fields = {}
    started = {}
    completed = set()
    for line in output.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: ([^=]+)=(.*)", line)
        if match:
            fields[match[1]] = match[2]
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = line.partition(":")[2].strip()
            if code not in {"0", "1"}:
                raise ValueError("Test failed, skipped, or reported an unknown status")
            try:
                current = int(fields["current"])
                declared = int(fields["numtests"])
                identity = (fields["class"], fields["test"])
            except (KeyError, ValueError) as error:
                raise ValueError("Incomplete per-test status") from error
            if declared != total or not 1 <= current <= total or not all(identity):
                raise ValueError("Inconsistent declared test count or identity")
            if code == "1":
                if current in started or identity in started.values():
                    raise ValueError("Duplicate test start")
                started[current] = identity
            else:
                if started.get(current) != identity or current in completed:
                    raise ValueError("Test completion without a matching unique start")
                completed.add(current)
            fields = {}
    if fields or completed != set(range(1, total + 1)) or len(started) != total:
        raise ValueError("Instrumentation did not complete every declared test")
    return total


def run_logged(command: list[str], path: Path, timeout: int) -> str:
    print("Running: " + " ".join(command), flush=True)
    try:
        with path.open("w", encoding="utf-8") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    timeout=timeout, check=False)
    finally:
        if path.exists():
            print(path.read_text(encoding="utf-8", errors="replace"), flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed with exit code {result.returncode}: {path}")
    return path.read_text(encoding="utf-8", errors="replace")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]
    try:
        for label, apk in (("app", args.apk), ("test", args.test_apk)):
            if not apk.is_file():
                raise ValueError(f"Missing {label} APK: {apk}")
            installed = run_logged(adb + ["install", "--no-streaming", "-r", "-t", str(apk)],
                                   args.output / f"install-{label}.log", 600)
            if not re.search(r"^Success\s*$", installed, re.MULTILINE):
                raise ValueError(f"Installation did not report success: {label}")
        result = run_logged(adb + ["shell", "am", "instrument", "-w", "-r",
                                  "com.pdfchemy.app.test/androidx.test.runner.AndroidJUnitRunner"],
                            args.output / "instrumentation.log", 2400)
        count = validate_result(result)
        print(f"Verified complete native suite: {count} passed, zero failed/skipped", flush=True)
        return 0
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Native test gate failed: {error}", file=sys.stderr, flush=True)
        return 1
    finally:
        try:
            run_logged(adb + ["logcat", "-d", "-b", "crash"], args.output / "crash.log", 30)
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            print(f"Could not collect crash log: {error}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
