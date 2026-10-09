"""Fetch and verify the exact compile-only JARs listed in libs/DEPENDENCIES.json."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LIBS = ROOT / "libs"
MANIFEST = json.loads((LIBS / "DEPENDENCIES.json").read_text(encoding="utf-8"))
MAX_ARTIFACT_BYTES = 128 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_target(filename: str) -> Path:
    target = (LIBS / filename).resolve()
    if target.parent != LIBS.resolve() or Path(filename).name != filename:
        raise ValueError(f"Unsafe dependency filename: {filename!r}")
    return target


def verify(path: Path, expected: str) -> None:
    actual = sha256(path)
    if actual.lower() != expected.lower():
        raise RuntimeError(f"SHA-256 mismatch for {path.name}: expected {expected}, got {actual}")


def download(url: str, target: Path, expected: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    request = Request(url, headers={"User-Agent": "PMWeather-Aeronautics-source-bootstrap/1.0"})
    try:
        with urlopen(request, timeout=60) as response:
            length = response.headers.get("Content-Length")
            if length and int(length) > MAX_ARTIFACT_BYTES:
                raise RuntimeError(f"Refusing oversized dependency: {target.name}")
            with tempfile.NamedTemporaryFile(prefix=target.name + ".", suffix=".part",
                                             dir=LIBS, delete=False) as output:
                temporary = Path(output.name)
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > MAX_ARTIFACT_BYTES:
                        raise RuntimeError(f"Refusing oversized dependency: {target.name}")
                    output.write(chunk)
        verify(temporary, expected)
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def extract_embedded(source: Path, entry: str, target: Path, expected: str) -> None:
    temporary: Path | None = None
    try:
        with zipfile.ZipFile(source) as archive:
            info = archive.getinfo(entry)
            if info.file_size > MAX_ARTIFACT_BYTES:
                raise RuntimeError(f"Refusing oversized embedded dependency: {entry}")
            with archive.open(info) as embedded, tempfile.NamedTemporaryFile(
                    prefix=target.name + ".", suffix=".part", dir=LIBS, delete=False) as output:
                temporary = Path(output.name)
                total = 0
                while chunk := embedded.read(1024 * 1024):
                    total += len(chunk)
                    if total > MAX_ARTIFACT_BYTES:
                        raise RuntimeError(f"Refusing oversized embedded dependency: {entry}")
                    output.write(chunk)
        verify(temporary, expected)
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def ensure_file(dependency: dict[str, str], source_files: dict[str, Path]) -> Path:
    target = safe_target(dependency["filename"])
    expected = dependency["sha256"]
    if target.is_file():
        try:
            verify(target, expected)
            return target
        except RuntimeError:
            target.unlink()

    source_filename = dependency.get("source_filename")
    if source_filename:
        source = source_files.get(source_filename)
        if source is None or not source.is_file():
            raise RuntimeError(f"Missing source JAR needed to extract {target.name}")
        extract_embedded(source, dependency["archive_entry"], target, expected)
    else:
        download(dependency["url"], target, expected)
    return target


def main() -> None:
    source_files: dict[str, Path] = {}
    for dependency in MANIFEST["compile_only_dependencies"]:
        if "source_filename" not in dependency:
            source_files[dependency["filename"]] = ensure_file(dependency, {})
    for dependency in MANIFEST["compile_only_dependencies"]:
        if "source_filename" in dependency:
            ensure_file(dependency, source_files)
    print("Verified compile-only dependencies:")
    for dependency in MANIFEST["compile_only_dependencies"]:
        path = safe_target(dependency["filename"])
        print(f"  {path.name}  sha256:{dependency['sha256']}")


if __name__ == "__main__":
    main()
