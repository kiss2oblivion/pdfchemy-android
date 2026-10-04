"""Read/capture the existing Android UI; audit artifacts only, no app-code edits."""
import argparse
import json
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

ADB = r"E:\Android_SDK\platform-tools\adb.exe"
ROOT = Path(__file__).resolve().parent


def adb(*args, check=True):
    return subprocess.run([ADB, "-s", "emulator-5554", *map(str, args)],
                          capture_output=True, check=check, timeout=45)


parser = argparse.ArgumentParser()
parser.add_argument("--tap", nargs=2, type=int)
parser.add_argument("--swipe", nargs=5, type=int)
parser.add_argument("--key", type=int)
parser.add_argument("--text")
parser.add_argument("--capture", required=True)
parser.add_argument("--metrics", action="store_true")
args = parser.parse_args()
if args.tap:
    adb("shell", "input", "tap", *args.tap)
if args.swipe:
    adb("shell", "input", "swipe", *args.swipe)
if args.key:
    adb("shell", "input", "keyevent", args.key)
if args.text:
    adb("shell", "input", "text", args.text)
start = time.perf_counter()
remote_xml = f"/sdcard/pdfchemy-audit-{args.capture}.xml"
for attempt in range(3):
    dump = adb("shell", "uiautomator", "dump", remote_xml, check=False)
    if b"dumped to" in dump.stdout:
        break
    time.sleep(0.5)
else:
    raise RuntimeError(dump.stderr.decode(errors="replace") + dump.stdout.decode(errors="replace"))
xml = adb("exec-out", "cat", remote_xml).stdout
image = adb("exec-out", "screencap", "-p").stdout
folder = ROOT / "evidence"
folder.mkdir(exist_ok=True)
(folder / f"{args.capture}.xml").write_bytes(xml)
(folder / f"{args.capture}.png").write_bytes(image)
if args.metrics:
    for metric in ("gfxinfo", "meminfo"):
        data = adb("shell", "dumpsys", metric, "com.pdfchemy.app").stdout
        (folder / f"{args.capture}-{metric}.txt").write_bytes(data)
print(json.dumps({"capture": args.capture, "dump_seconds": round(time.perf_counter() - start, 3),
                  "dump_status": dump.stdout.decode(errors="replace").strip()}))
for node in ElementTree.fromstring(xml).iter("node"):
    if node.get("text") or node.get("content-desc"):
        print(json.dumps({key: node.get(key) for key in
                          ("text", "content-desc", "bounds", "clickable", "enabled", "selected")},
                         ensure_ascii=True))
