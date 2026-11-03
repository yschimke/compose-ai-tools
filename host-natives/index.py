#!/usr/bin/env python3
"""Maintains `host-natives/index.json`, the index `compose-preview launch` reads at run time.

The CLI bundles no host libraries and no versions. At launch it fetches this file from `main`
(`HostNativesProvision`), matches the APK's native libraries and AndroidX versions against it, and
downloads the builds it names. Each build is one archive URL per platform, its sha256, and the path
of the library inside it. Two kinds of archive appear, both handled the same way:

- **Built here:** a `host-natives-v<VERSION>` release tarball from `host-natives.yml`. `add-release`
  records one, and the publish job runs it and opens the PR.
- **Upstream host builds:** a library's own JVM artifact on Maven, at the same version as its
  Android artifact (sqlite-bundled-jvm, ink-nativeloader-jvm). A library with an `upstream` block is
  watched: `watch` adds each new stable version, and `host-natives-watch.yml` runs it on a schedule
  and opens the PR.

Commands:

  index.py check [--online]     validate the index; --online downloads every archive, checks its
                                sha256 and that the library is inside it
  index.py watch                add new upstream versions (downloads them to hash them)
  index.py add-release --dist <dir> --tag <tag>
                                record a host-natives release whose tarballs are in <dir>

Standard library only, so every workflow can run it with a bare `python3`.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
INDEX = HERE / "index.json"
SCHEMA = "compose-preview-host-natives/v1"
RELEASE_REPO = "yschimke/compose-ai-tools"

# What `HostNativesProvision.platformToken` maps a host onto.
PLATFORMS = ("linux-x86_64", "linux-arm64", "macos-arm64", "macos-x86_64", "windows-x86_64")

# Which index platforms one host-natives tarball serves.
RELEASE_PLATFORMS = {
    "linux-x86_64": ("linux-x86_64",),
    "macos-universal": ("macos-arm64", "macos-x86_64"),
}

STABLE_RE = re.compile(r"^\d+(\.\d+)*$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def version_key(version: str) -> tuple:
    """Order `1.2.3[-suffix]`, a suffix before its release, like the CLI's `compareSemver`."""
    head, _, suffix = version.partition("-")
    nums = tuple(int(p) if p.isdigit() else 0 for p in head.split("."))
    return nums + (0,) * (4 - len(nums)), suffix == "", suffix


def load(path: Path = INDEX) -> dict:
    return json.loads(path.read_text())


def save(index: dict, path: Path = INDEX) -> None:
    for library in index["libraries"]:
        library["builds"].sort(key=lambda b: version_key(b["version"]))
    path.write_text(json.dumps(index, indent=2) + "\n")


def validate(index: dict) -> list[str]:
    """Shape errors; empty when the index is well-formed."""
    errors = []
    if index.get("schema") != SCHEMA:
        errors.append(f"schema must be {SCHEMA}")
    seen = set()
    for library in index.get("libraries", []):
        name = library.get("library")
        where = f"library {name!r}"
        if not name or name in seen:
            errors.append(f"{where}: missing or duplicate name")
        seen.add(name)
        if not re.fullmatch(r"[\w.-]+:[\w.-]+", library.get("artifact", "")):
            errors.append(f"{where}: artifact must be group:name (its META-INF version marker)")
        versions = set()
        for build in library.get("builds", []):
            v = build.get("version", "")
            if not re.fullmatch(r"\d+(\.\d+)*(-[\w.]+)?", v) or v in versions:
                errors.append(f"{where}: bad or duplicate build version {v!r}")
            versions.add(v)
            for platform, entry in build.get("platforms", {}).items():
                at = f"{where} {v} {platform}"
                if platform not in PLATFORMS:
                    errors.append(f"{at}: unknown platform")
                if not entry.get("url", "").startswith("https://"):
                    errors.append(f"{at}: url must be https")
                if not SHA_RE.match(entry.get("sha256", "")):
                    errors.append(f"{at}: sha256 must be 64 lowercase hex digits")
                if not entry.get("entry"):
                    errors.append(f"{at}: entry (the library's path in the archive) is required")
    return errors


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "compose-preview-host-natives"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def archive_names(data: bytes, url: str) -> set[str]:
    """Member names of a zip/jar or a tar.gz, with any leading `./` dropped."""
    if url.endswith((".tar.gz", ".tgz")):
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            return {m.name.removeprefix("./") for m in tar.getmembers() if m.isfile()}
    with zipfile.ZipFile(io.BytesIO(data)) as zip_file:
        return set(zip_file.namelist())


def check_online(index: dict) -> list[str]:
    errors = []
    cache: dict[str, bytes] = {}
    for library in index["libraries"]:
        for build in library["builds"]:
            for platform, entry in build["platforms"].items():
                at = f"{library['library']} {build['version']} {platform}"
                url = entry["url"]
                try:
                    data = cache.get(url) or cache.setdefault(url, fetch(url))
                except Exception as e:  # noqa: BLE001 — any failure to fetch is the finding
                    errors.append(f"{at}: could not fetch {url}: {e}")
                    continue
                if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                    errors.append(f"{at}: sha256 of {url} does not match")
                elif entry["entry"] not in archive_names(data, url):
                    errors.append(f"{at}: {url} has no {entry['entry']}")
    return errors


def maven_url(repository: str, coordinate: str, version: str, ext: str = "jar") -> str:
    group, name = coordinate.split(":")
    return f"{repository}/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.{ext}"


def maven_versions(repository: str, coordinate: str) -> list[str]:
    group, name = coordinate.split(":")
    xml = fetch(f"{repository}/{group.replace('.', '/')}/{name}/maven-metadata.xml").decode()
    return re.findall(r"<version>([^<]+)</version>", xml)


def watch(index: dict, fetch_versions=maven_versions, fetch_bytes=fetch) -> list[str]:
    """Add every new stable upstream version at or after `since`. Returns what was added."""
    added = []
    for library in index["libraries"]:
        upstream = library.get("upstream")
        if not upstream:
            continue
        have = {b["version"] for b in library["builds"]}
        since = version_key(upstream.get("since", "0"))
        for version in fetch_versions(upstream["repository"], upstream["artifact"]):
            if version in have or not STABLE_RE.match(version) or version_key(version) < since:
                continue
            url = maven_url(upstream["repository"], upstream["artifact"], version)
            try:
                data = fetch_bytes(url)
            except Exception as e:  # noqa: BLE001
                print(f"{library['library']} {version}: skipped, {url}: {e}", file=sys.stderr)
                continue
            names = archive_names(data, url)
            platforms = {
                platform: {
                    "url": url,
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "entry": entry,
                }
                for platform, entry in upstream["entries"].items()
                if entry in names
            }
            if not platforms:
                print(f"{library['library']} {version}: no known entry in {url}", file=sys.stderr)
                continue
            library["builds"].append({"version": version, "platforms": platforms})
            added.append(f"{library['library']} {version} ({', '.join(sorted(platforms))})")
    return added


def add_release(index: dict, dist: Path, tag: str) -> list[str]:
    """Record the tarballs in [dist] (host-natives.yml's publish output) under release [tag]."""
    added = []
    for tarball in sorted(dist.glob("host-natives-*.tar.gz")):
        data = tarball.read_bytes()
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            member = next(
                tar.extractfile(m)
                for m in tar.getmembers()
                if m.name.removeprefix("./") == "host-natives.properties"
            )
            properties = dict(
                line.split("=", 1) for line in member.read().decode().splitlines() if "=" in line
            )
        url = f"https://github.com/{RELEASE_REPO}/releases/download/{tag}/{tarball.name}"
        sha = hashlib.sha256(data).hexdigest()
        for library in index["libraries"]:
            key = library.get("release", {}).get("property")
            if not key or key not in properties:
                continue
            version = properties[f"{key.rsplit('.', 1)[0]}.version"]
            build = next((b for b in library["builds"] if b["version"] == version), None)
            if build is None:
                build = {"version": version, "platforms": {}}
                library["builds"].append(build)
            for platform in RELEASE_PLATFORMS[properties["platform"]]:
                build["platforms"][platform] = {"url": url, "sha256": sha, "entry": properties[key]}
                added.append(f"{library['library']} {version} {platform} from {tag}")
    return added


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--online", action="store_true")
    sub.add_parser("watch")
    release = sub.add_parser("add-release")
    release.add_argument("--dist", type=Path, required=True)
    release.add_argument("--tag", required=True)
    args = ap.parse_args()

    index = load()
    errors = validate(index)
    if args.command == "check":
        if not errors and args.online:
            errors = check_online(index)
        for e in errors:
            print(f"::error::host-natives/index.json: {e}", file=sys.stderr)
        if not errors:
            builds = sum(len(lib["builds"]) for lib in index["libraries"])
            print(f"ok: {len(index['libraries'])} libraries, {builds} builds")
        return 1 if errors else 0
    if errors:
        for e in errors:
            print(f"::error::host-natives/index.json: {e}", file=sys.stderr)
        return 1
    added = watch(index) if args.command == "watch" else add_release(index, args.dist, args.tag)
    save(index)
    for line in added:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
