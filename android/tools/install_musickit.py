#!/usr/bin/env python3
"""Copy Apple's downloaded SDK libraries into the ignored local libs directory."""
import argparse
import hashlib
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk_directory", type=Path)
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "app" / "libs"
    names = ("musickitauth-release-1.1.2.aar", "mediaplayback-release-1.1.1.aar")
    sources = [args.sdk_directory / name for name in names]
    for source in sources:
        if not source.is_file():
            parser.error(f"Missing {source}. Extract Apple's Android-MusicKit-SDK-1.1.2 download first.")
    destination.mkdir(parents=True, exist_ok=True)
    for source in sources:
        shutil.copy2(source, destination / source.name)
        print(source.name, hashlib.sha256(source.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
