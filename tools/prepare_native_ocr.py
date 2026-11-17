"""Prepare pinned OCR sources without overwriting an existing prepared/user-modified tree.

Build with the repository Gradle wrapper, JDK 21 and JITPACK=true; see native/README.md.
All build/source output stays in the explicitly supplied external workspace.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "native/ocr-sources.json"


def run(*args: str, cwd: Path | None = None) -> str:
    return subprocess.check_output(list(args), cwd=cwd, text=True, encoding="utf-8").strip()


def replace(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    if old not in content:
        raise ValueError("Unexpected upstream configuration: " + str(path))
    path.write_text(content.replace(old, new), encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    sdk = args.sdk.resolve()
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    ndk = sdk / "ndk" / config["toolchain"]["ndk"]
    cpu_source = ndk / "sources/android/cpufeatures"
    for name in ("cpu-features.c", "cpu-features.h"):
        if not (cpu_source / name).is_file():
            raise ValueError("Missing pinned NDK CPU feature source: " + name)
    workspace.mkdir(parents=True, exist_ok=True)
    marker = workspace / "prepared-ocr.json"
    if marker.exists():
        raise ValueError("Workspace is already prepared; build it or use a fresh workspace")

    # Inspect every existing source before doing any source mutation.
    for name, source in config["sources"].items():
        target = workspace / name
        if target.exists():
            if run("git", "rev-parse", "HEAD", cwd=target) != source["revision"]:
                raise ValueError("Unexpected source revision: " + name)
            if run("git", "status", "--porcelain", cwd=target):
                raise ValueError("Refusing to overwrite modified source: " + name)
    for name, source in config["sources"].items():
        target = workspace / name
        if not target.exists():
            run("git", "init", str(target))
            run("git", "remote", "add", "origin", source["url"], cwd=target)
            run("git", "fetch", "--depth", "1", "origin", source["revision"], cwd=target)
            run("git", "checkout", "--detach", source["revision"], cwd=target)
        if run("git", "rev-parse", "HEAD", cwd=target) != source["revision"]:
            raise ValueError("Source revision mismatch: " + name)

    wrapper = workspace / "tesseract4android"
    patch = ROOT / config["patch"]
    run("git", "apply", "--check", str(patch), cwd=wrapper)
    cpp = wrapper / "tesseract4android/src/main/cpp"
    copies = {}
    for name in ("tesseract", "leptonica", "libpng"):
        relative = "src-" + config["sources"][name]["version"]
        destination = cpp / name / relative
        if destination.exists():
            raise ValueError("Refusing to overwrite existing source copy: " + str(destination))
        copies[name] = relative

    run("git", "apply", str(patch), cwd=wrapper)
    # Match the app floor; API >=23 enables Leptonica's fmemopen backend.
    replace(wrapper / "tesseract4android/build.gradle", "minSdk 21", f'minSdk {config["toolchain"]["min_sdk"]}')
    for name, relative in copies.items():
        shutil.copytree(workspace / name, cpp / name / relative, ignore=shutil.ignore_patterns(".git"))
    png = cpp / "libpng" / copies["libpng"]
    shutil.copyfile(png / "scripts/pnglibconf.h.prebuilt", png / "pnglibconf.h")
    cpu_target = cpp / "tesseract/cpufeatures"
    cpu_target.mkdir()
    for name in ("cpu-features.c", "cpu-features.h"):
        shutil.copyfile(cpu_source / name, cpu_target / name)

    replace(cpp / "libpng/CMakeLists.txt", 'set(SRC_PATH "src/")', f'set(SRC_PATH "{copies["libpng"]}/")')
    replace(cpp / "leptonica/CMakeLists.txt", '"src/src/"', f'"{copies["leptonica"]}/src/"')
    replace(cpp / "leptonica/CMakeLists.txt", '"../libpng/src/"', f'"../libpng/{copies["libpng"]}/"')
    replace(cpp / "tesseract/CMakeLists.txt", '"src/src/"', f'"{copies["tesseract"]}/src/"')
    replace(cpp / "tesseract/CMakeLists.txt", '"src/include/"', f'"{copies["tesseract"]}/include/"')
    replace(cpp / "tesseract/CMakeLists.txt", '"src/VERSION"', f'"{copies["tesseract"]}/VERSION"')
    replace(cpp / "tesseract/CMakeLists.txt", '"../leptonica/src/src/"', f'"../leptonica/{copies["leptonica"]}/src/"')
    replace(cpp / "CMakeLists.txt", "add_subdirectory(libjpeg)",
            "set_property(GLOBAL PROPERTY JOB_POOLS ocr_compile=2 ocr_link=1)\n"
            "set(CMAKE_JOB_POOL_COMPILE ocr_compile)\n"
            "set(CMAKE_JOB_POOL_LINK ocr_link)\n\nadd_subdirectory(libjpeg)")
    (wrapper / "local.properties").write_text("sdk.dir=" + sdk.as_posix() + "\n", encoding="utf-8")
    changed = [Path(line.split("\t")[-1]) for line in run("git", "diff", "--numstat", cwd=wrapper).splitlines()]
    hashes = {str(path).replace("\\", "/"): hashlib.sha256((wrapper / path).read_bytes()).hexdigest() for path in changed}
    receipt = dict(sources=config["sources"], toolchain=config["toolchain"],
                   patch_sha256=hashlib.sha256(patch.read_bytes()).hexdigest(),
                   recipe_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   changed_file_sha256=hashes,
                   cpu_features_sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in cpu_target.iterdir()})
    marker.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("Prepared pinned OCR sources at " + str(wrapper))


if __name__ == "__main__":
    main()
