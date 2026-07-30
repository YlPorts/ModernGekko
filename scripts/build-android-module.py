#!/usr/bin/env python3
"""Build a ModernGekko static-recomp module for Android ARM64.

This script never downloads or bundles game data. It consumes an extracted legal
GameCube/Wii dump, a host-built DolRecomp executable, and an installed Android NDK.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

WII_MAGIC = 0x5D1C9EA3
GC_MAGIC = 0xC2339F3D


def run(command: list[str], *, cwd: Path | None = None) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def read_game(root: Path) -> tuple[str, str, Path]:
    boot = root / "sys" / "boot.bin"
    dol = root / "sys" / "main.dol"
    if not boot.is_file() or not dol.is_file():
        raise RuntimeError("game root must contain sys/boot.bin and sys/main.dol")

    data = boot.read_bytes()[:0x60]
    if len(data) < 0x60:
        raise RuntimeError("sys/boot.bin is malformed")

    game_id = data[:6].decode("ascii", errors="strict")
    if not game_id.isalnum():
        raise RuntimeError("boot.bin contains an invalid six-character disc ID")

    wii_magic = int.from_bytes(data[0x18:0x1C], "big")
    gc_magic = int.from_bytes(data[0x1C:0x20], "big")
    if wii_magic == WII_MAGIC:
        platform = "wii"
    elif gc_magic == GC_MAGIC:
        platform = "gamecube"
    else:
        raise RuntimeError("boot.bin is not a GameCube/Wii disc header")

    return game_id, platform, dol


def find_generated(parent: Path, game_id: str, platform: str) -> tuple[Path, str]:
    candidates = []
    if platform == "wii":
        candidates.append((parent / f"{game_id}_generated", game_id))
    candidates.append((parent / "generated", "generated"))

    for directory, stem in candidates:
        if (directory / f"{stem}.h").is_file():
            return directory, stem
    raise RuntimeError("DolRecomp did not produce a generated header")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build g<ID>_recomp.so for Android arm64-v8a"
    )
    parser.add_argument("game_root", type=Path, help="Extracted game root")
    parser.add_argument("--dolrecomp", type=Path, required=True, help="Host DolRecomp executable")
    parser.add_argument("--ndk", type=Path, required=True, help="Android NDK directory")
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1],
                        help="ModernGekko source checkout")
    parser.add_argument("--output", type=Path, default=Path("android-module-output"))
    parser.add_argument("--api", type=int, default=24)
    parser.add_argument("--jobs", type=int, default=max(1, os.cpu_count() or 1))
    parser.add_argument("--backend", choices=("c", "llvm"), default="c")
    args = parser.parse_args()

    source = args.source.resolve()
    game_root = args.game_root.resolve()
    dolrecomp = args.dolrecomp.resolve()
    ndk = args.ndk.resolve()
    output = args.output.resolve()

    if not dolrecomp.is_file():
        raise RuntimeError(f"DolRecomp executable not found: {dolrecomp}")
    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    if not toolchain.is_file():
        raise RuntimeError(f"Android NDK toolchain not found: {toolchain}")

    game_id, platform, dol = read_game(game_root)
    work = output / "work"
    generated_parent = work / "dolrecomp-output"
    module_build = work / "module-build"
    shutil.rmtree(work, ignore_errors=True)
    generated_parent.mkdir(parents=True, exist_ok=True)

    command = [str(dolrecomp), f"-j{args.jobs}", f"--backend={args.backend}"]
    if platform == "wii":
        command += ["--cpu", "broadway", str(dol), game_id, str(generated_parent)]
    else:
        command += ["--cpu", "gekko", "--gamecube", str(dol), str(generated_parent)]
    run(command)

    generated, stem = find_generated(generated_parent, game_id, platform)
    emitted_header = generated / f"{stem}.h"
    if emitted_header.name != "generated.h":
        shutil.copy2(emitted_header, generated / "generated.h")
    shutil.copy2(dol, generated / "main.dol")

    emitted_smc = generated / f"{stem}_smc.txt"
    normalized_smc = generated / "generated_smc.txt"
    if emitted_smc.is_file():
        shutil.copy2(emitted_smc, normalized_smc)
    else:
        normalized_smc.touch()

    cmake = shutil.which("cmake")
    ninja = shutil.which("ninja")
    if not cmake or not ninja:
        raise RuntimeError("cmake and ninja must be available in PATH")

    module_template = source / "vendor" / "dolphin" / "module-template"
    gxruntime = source / "vendor" / "dolphin" / "GXRuntime"
    chassis_abi = source / "vendor" / "dolphin" / "Source" / "Core" / "Core" / "PowerPC" / "StaticRecomp"
    for required in (module_template, gxruntime, chassis_abi):
        if not required.exists():
            raise RuntimeError(f"required RecompCore path is missing: {required}")

    run([
        cmake,
        "-S", str(module_template),
        "-B", str(module_build),
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        "-DANDROID_ABI=arm64-v8a",
        f"-DANDROID_PLATFORM=android-{args.api}",
        "-DANDROID_STL=c++_static",
        f"-DGAME_ID={game_id}",
        f"-DGENERATED_DIR={generated}",
        f"-DGXRUNTIME_DIR={gxruntime}",
        f"-DCHASSIS_ABI_DIR={chassis_abi}",
    ])
    run([cmake, "--build", str(module_build), "--parallel", str(args.jobs)])

    module_name = f"g{game_id}_recomp.so"
    built = module_build / module_name
    if not built.is_file():
        matches = list(module_build.rglob(module_name))
        if not matches:
            raise RuntimeError(f"module build did not produce {module_name}")
        built = matches[0]

    final_dir = output / "arm64-v8a"
    final_dir.mkdir(parents=True, exist_ok=True)
    final = final_dir / module_name
    shutil.copy2(built, final)
    print(f"\nBuilt Android module: {final}")
    print(f"Copy it to the app's StaticRecompModules directory for game {game_id}.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
