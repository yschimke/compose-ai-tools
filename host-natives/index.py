#!/usr/bin/env python3
"""Maintains `host-natives/index.json`, the index `compose-preview launch` reads at run time.

The CLI bundles no host libraries and no versions. At launch it fetches this file from `main`
(`HostNativesProvision`), matches the APK's native libraries and AndroidX versions against it, and
downloads the builds it names. Each build is one archive URL per platform, its sha256, and the path
of the library inside it. Two kinds of archive appear, both handled the same way:

- **Built here:** a recipe under `host-natives/<recipe>/`, released per LIBRARY version as
  `host-natives-<recipe>-<version>` by `host-natives.yml`, which runs `add-release` and opens the
  PR. A later library version whose native API is unchanged reuses that build: `watch` adds it
  pointing at the same archives, and reports the versions whose native API changed, which need a
  recipe bump (a new release) instead.
- **Upstream host builds:** a library's own JVM artifact on Maven, at the same version as its
  Android artifact (sqlite-bundled-jvm, ink-nativeloader-jvm, zstd-jni, JNA, Conscrypt's
  openjdk-uber, libsignal-client). `watch` adds each new stable version, and records the GNU build
  IDs of each version's Android libraries (`androidBuildIds`), by which the CLI recognises the
  exact build an APK carries.

`host-natives-watch.yml` runs `watch` and `survey` weekly and opens one PR.

Commands:

  index.py check [--online]     validate the index; --online downloads every archive, checks its
                                sha256 and that the library is inside it
  index.py watch                add new versions (downloads them to hash or compare them)
  index.py add-release --dist <dir>
                                record the host-natives tarballs in <dir>
  index.py survey [--out <md>]  the AndroidX menu: every AndroidX artifact whose AAR ships native
                                libraries, any upstream host build of it, and whether the index has
                                it; written to host-natives/ANDROIDX.md

Standard library only, so every workflow can run it with a bare `python3`.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import io
import json
import os
import re
import struct
import sys
import tarfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
INDEX = HERE / "index.json"
SURVEY = HERE / "ANDROIDX.md"
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
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
    """Order `1.2.3[-suffix]` as the CLI's `HostNativesProvision.compareVersions` does: a
    pre-release suffix (`-alpha03`, `-rc01`) before its release, a numeric one (zstd-jni's
    `1.5.7-9`) after it, and suffixes compared part by part, numbers as numbers."""
    head, _, suffix = version.partition("-")
    nums = tuple(int(p) if p.isdigit() else 0 for p in head.split("."))
    nums = nums + (0,) * (4 - len(nums))
    if not suffix:
        return nums, 1, ()
    parts = tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in re.split(r"[.-]", suffix))
    return nums, 2 if suffix[0].isdigit() else 0, parts


def matches(library: dict, name: str) -> re.Match | None:
    """Whether a load name is [library]'s; for a pattern, the match carries the version."""
    if "library" in library:
        return re.fullmatch(re.escape(library["library"]), name)
    return re.fullmatch(library["libraryPattern"], name)


def label(library: dict) -> str:
    return library.get("library") or library.get("libraryPattern", "?")


def elf_build_id(elf: bytes) -> str | None:
    """The GNU build ID of an ELF file, or None. It survives stripping, so it names the exact build
    an APK carries even after its packaging stripped symbols."""
    if len(elf) < 64 or elf[:4] != b"\x7fELF":
        return None
    is64, e = elf[4] == 2, "<" if elf[5] == 1 else ">"
    if is64:
        (phoff,) = struct.unpack_from(e + "Q", elf, 0x20)
        phentsize, phnum = struct.unpack_from(e + "HH", elf, 0x36)
    else:
        (phoff,) = struct.unpack_from(e + "I", elf, 0x1C)
        phentsize, phnum = struct.unpack_from(e + "HH", elf, 0x2A)
    for i in range(phnum):
        header = phoff + i * phentsize
        if struct.unpack_from(e + "I", elf, header)[0] != 4:  # PT_NOTE
            continue
        if is64:
            (offset,) = struct.unpack_from(e + "Q", elf, header + 8)
            (size,) = struct.unpack_from(e + "Q", elf, header + 32)
        else:
            (offset,) = struct.unpack_from(e + "I", elf, header + 4)
            (size,) = struct.unpack_from(e + "I", elf, header + 16)
        pos = offset
        while pos + 12 <= min(offset + size, len(elf)):
            namesz, descsz, kind = struct.unpack_from(e + "III", elf, pos)
            desc = pos + 12 + ((namesz + 3) & ~3)
            if kind == 3 and elf[pos + 12 : pos + 12 + namesz].rstrip(b"\0") == b"GNU":
                return elf[desc : desc + descsz].hex()
            pos = desc + ((descsz + 3) & ~3)
    return None


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
        name = label(library)
        where = f"library {name!r}"
        if ("library" in library) == ("libraryPattern" in library) or name in seen:
            errors.append(f"{where}: needs exactly one of library and libraryPattern, unique")
        seen.add(name)
        if "libraryPattern" in library:
            try:
                if re.compile(library["libraryPattern"]).groups != 1:
                    errors.append(f"{where}: libraryPattern needs one group, the version")
            except re.error as e:
                errors.append(f"{where}: libraryPattern: {e}")
        if not re.fullmatch(r"[\w.-]+:[\w.-]+", library.get("artifact", "")):
            errors.append(f"{where}: artifact must be group:name (its META-INF version marker)")
        for key, value in library.get("properties", {}).items():
            if not isinstance(value, str) or not re.fullmatch(r"[\w.-]+", key):
                errors.append(f"{where}: property {key!r} must map a name to a string")
        versions = set()
        for build in library.get("builds", []):
            v = build.get("version", "")
            if not re.fullmatch(r"\d+(\.\d+)*(-[\w.]+)?", v) or v in versions:
                errors.append(f"{where}: bad or duplicate build version {v!r}")
            versions.add(v)
            if any(not re.fullmatch(r"[0-9a-f]{8,80}", i) for i in build.get("androidBuildIds", [])):
                errors.append(f"{where} {v}: androidBuildIds must be lowercase hex")
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


# Public Maven hosts and the path each has on the private cache that coo.ee/env sandboxes configure
# (COO_MAVEN_URL, COO_MAVEN_USER, COO_MAVEN_TOKEN; the same routing as its Gradle init script).
# Only fetching goes through the cache: the index always records the public URL, which is what
# every user's CLI downloads.
CACHE_PATHS = {
    "https://dl.google.com/dl/android/maven2/": "google",
    "https://maven.google.com/": "google",
    "https://repo1.maven.org/maven2/": "central",
    "https://repo.maven.apache.org/maven2/": "central",
}


def via_cache(url: str, env=os.environ) -> tuple[str, dict[str, str]] | None:
    """[url] on the private Maven cache, with its auth header, or None when it is not configured or
    does not serve that host."""
    base = env.get("COO_MAVEN_URL", "").strip().rstrip("/")
    user = env.get("COO_MAVEN_USER", "").strip()
    token = env.get("COO_MAVEN_TOKEN", "").strip()
    if not (base and user and token):
        return None
    for prefix, path in CACHE_PATHS.items():
        if url.startswith(prefix):
            auth = base64.b64encode(f"{user}:{token}".encode()).decode()
            return f"{base}/{path}/{url[len(prefix):]}", {"Authorization": f"Basic {auth}"}
    return None


def _open(url: str, headers: dict[str, str], timeout: int):
    """Opens [url] through the cache when there is one, falling back to the public host on any
    failure but a 404, which the public host would answer the same way."""
    headers = {"User-Agent": "compose-preview-host-natives", **headers}
    cached = via_cache(url)
    if cached:
        cache_url, auth = cached
        try:
            req = urllib.request.Request(cache_url, headers={**headers, **auth})
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout)


def fetch(url: str) -> bytes:
    with _open(url, {}, 120) as resp:
        return resp.read()


def archive_names(data: bytes, url: str) -> set[str]:
    """Member names of a zip/jar or a tar.gz, with any leading `./` dropped."""
    if url.endswith((".tar.gz", ".tgz")):
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            return {m.name.removeprefix("./") for m in tar.getmembers() if m.isfile()}
    with zipfile.ZipFile(io.BytesIO(data)) as zip_file:
        return set(zip_file.namelist())


def check_online(index: dict) -> list[str]:
    """Downloads every archive once (keeping only its hash and listing, not its bytes: the index
    names some 100 MB jars) and checks each entry's sha256 and library path."""
    errors = []
    seen: dict[str, tuple[str, set[str]] | Exception] = {}
    for library in index["libraries"]:
        for build in library["builds"]:
            for platform, entry in build["platforms"].items():
                at = f"{label(library)} {build['version']} {platform}"
                url = entry["url"]
                if url not in seen:
                    try:
                        data = fetch(url)
                        seen[url] = (hashlib.sha256(data).hexdigest(), archive_names(data, url))
                    except Exception as e:  # noqa: BLE001 — any failure to fetch is the finding
                        seen[url] = e
                result = seen[url]
                if isinstance(result, Exception):
                    errors.append(f"{at}: could not fetch {url}: {result}")
                    continue
                sha, names = result
                if sha != entry["sha256"]:
                    errors.append(f"{at}: sha256 of {url} does not match")
                elif entry["entry"] not in names:
                    errors.append(f"{at}: {url} has no {entry['entry']}")
    return errors


def maven_url(repository: str, coordinate: str, version: str, ext: str = "jar") -> str:
    group, name = coordinate.split(":")
    return f"{repository}/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.{ext}"


def maven_versions(repository: str, coordinate: str) -> list[str]:
    group, name = coordinate.split(":")
    xml = fetch(f"{repository}/{group.replace('.', '/')}/{name}/maven-metadata.xml").decode()
    return re.findall(r"<version>([^<]+)</version>", xml)


def android_build_ids(library: dict, version: str, fetch_bytes=fetch) -> list[str]:
    """Build IDs of [library]'s Android libraries at [version], every ABI, from its `android`
    artifact: what the CLI matches an APK's copy against."""
    android = library.get("android")
    if not android:
        return []
    url = maven_url(android.get("repository", GOOGLE_MAVEN), android["artifact"], version,
                    android.get("ext", "aar"))
    ids = set()
    with zipfile.ZipFile(io.BytesIO(fetch_bytes(url))) as aar:
        for name in aar.namelist():
            if not (name.startswith("jni/") and name.endswith(".so")):
                continue
            load = name.rsplit("/", 1)[-1].removeprefix("lib").removesuffix(".so")
            if matches(library, load):
                build_id = elf_build_id(aar.read(name))
                if build_id:
                    ids.add(build_id)
    return sorted(ids)


def backfill_build_ids(index: dict, fetch_bytes=fetch) -> list[str]:
    """Records `androidBuildIds` on every build that has none yet (an empty list marks one whose
    Android libraries carry no build ID, so it is not fetched again)."""
    added = []
    for library in index["libraries"]:
        if not library.get("android"):
            continue
        for build in library["builds"]:
            if "androidBuildIds" in build:
                continue
            version = build["version"]
            try:
                build["androidBuildIds"] = android_build_ids(library, version, fetch_bytes)
            except Exception as e:  # noqa: BLE001
                print(f"{label(library)} {version}: no build IDs: {e}", file=sys.stderr)
                continue
            if build["androidBuildIds"]:
                added.append(f"{label(library)} {version}: {len(build['androidBuildIds'])} build IDs")
    return added


def watch(index: dict, fetch_versions=maven_versions, fetch_bytes=fetch) -> list[str]:
    """Add every new stable upstream version at or after `since`. Returns what was added.

    An upstream block names the host artifact, `entries` (the library's path in it per platform,
    `{version}` filled in), and optionally `stable`, a regex for which versions count as stable
    (zstd-jni's `1.5.7-9` are)."""
    added = []
    for library in index["libraries"]:
        upstream = library.get("upstream")
        if not upstream:
            continue
        have = {b["version"] for b in library["builds"]}
        since = version_key(upstream.get("since", "0"))
        stable = re.compile(upstream["stable"]) if "stable" in upstream else STABLE_RE
        for version in fetch_versions(upstream["repository"], upstream["artifact"]):
            if version in have or not stable.match(version) or version_key(version) < since:
                continue
            url = maven_url(upstream["repository"], upstream["artifact"], version)
            try:
                data = fetch_bytes(url)
            except Exception as e:  # noqa: BLE001
                print(f"{label(library)} {version}: skipped, {url}: {e}", file=sys.stderr)
                continue
            names = archive_names(data, url)
            sha = hashlib.sha256(data).hexdigest()
            platforms = {}
            for platform, template in upstream["entries"].items():
                entry = template.replace("{version}", version)
                if entry in names:
                    platforms[platform] = {"url": url, "sha256": sha, "entry": entry}
            if not platforms:
                print(f"{label(library)} {version}: no known entry in {url}", file=sys.stderr)
                continue
            library["builds"].append({"version": version, "platforms": platforms})
            added.append(f"{label(library)} {version} ({', '.join(sorted(platforms))})")
    return added


def add_release(index: dict, dist: Path) -> list[str]:
    """Record the recipe tarballs in [dist] (host-natives.yml's publish output).

    Each tarball's `host-natives.properties` names its recipe, release tag, platform, library
    version and file; the index library whose `release.recipe` matches gets a build at that version
    (replacing the platforms a `-r<revision>` rebuild supersedes)."""
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
        library = next(
            (
                lib
                for lib in index["libraries"]
                if lib.get("release", {}).get("recipe") == properties["recipe"]
            ),
            None,
        )
        if library is None:
            print(f"{tarball.name}: no index library has recipe {properties['recipe']}",
                  file=sys.stderr)
            continue
        version = properties["version"]
        url = f"https://github.com/{RELEASE_REPO}/releases/download/{properties['tag']}/{tarball.name}"
        build = next((b for b in library["builds"] if b["version"] == version), None)
        if build is None:
            build = {"version": version, "platforms": {}}
            library["builds"].append(build)
        build.pop("sameAs", None)
        sha = hashlib.sha256(data).hexdigest()
        for platform in RELEASE_PLATFORMS[properties["platform"]]:
            build["platforms"][platform] = {"url": url, "sha256": sha, "entry": properties["file"]}
            added.append(f"{label(library)} {version} {platform} from {properties['tag']}")
    return added


# --- Native API of an Android artifact -------------------------------------------------------------

def _native_methods(class_bytes: bytes) -> list[str]:
    """`Class.method(descriptor)` for each `native` method of one class file."""
    data = class_bytes
    pos = 8
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8: dict[int, str] = {}
    classes: dict[int, int] = {}
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (length,) = struct.unpack_from(">H", data, pos)
            utf8[i] = data[pos + 2 : pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
        elif tag == 7:
            (classes[i],) = struct.unpack_from(">H", data, pos)
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        else:
            raise ValueError(f"constant pool tag {tag}")
        i += 1
    _, this_class, _ = struct.unpack_from(">HHH", data, pos)
    pos += 6
    (interfaces,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * interfaces

    def skip_attributes(p: int) -> int:
        (n,) = struct.unpack_from(">H", data, p)
        p += 2
        for _ in range(n):
            (length,) = struct.unpack_from(">I", data, p + 2)
            p += 6 + length
        return p

    (fields,) = struct.unpack_from(">H", data, pos)
    pos += 2
    for _ in range(fields):
        pos = skip_attributes(pos + 6)
    (methods,) = struct.unpack_from(">H", data, pos)
    pos += 2
    owner = utf8[classes[this_class]].replace("/", ".")
    natives = []
    for _ in range(methods):
        flags, name, descriptor = struct.unpack_from(">HHH", data, pos)
        if flags & 0x0100:
            natives.append(f"{owner}.{utf8[name]}{utf8[descriptor]}")
        pos = skip_attributes(pos + 6)
    return natives


def native_api(archive: bytes) -> list[str]:
    """Every `native` method declared in an AAR's `classes.jar` (or a jar), sorted: the JNI surface
    a host build of the library has to provide."""
    with zipfile.ZipFile(io.BytesIO(archive)) as outer:
        if "classes.jar" in outer.namelist():
            archive = outer.read("classes.jar")
    natives = []
    with zipfile.ZipFile(io.BytesIO(archive)) as jar:
        for name in jar.namelist():
            if name.endswith(".class") and not name.startswith("META-INF/"):
                natives.extend(_native_methods(jar.read(name)))
    return sorted(natives)


def watch_recipes(index: dict, fetch_versions=None, fetch_bytes=fetch) -> tuple[list[str], list[str]]:
    """New stable versions of built-here libraries, below `fixedIn`: one whose native API matches
    the build it would otherwise fall back to is added reusing that build (`sameAs`); one whose API
    changed is reported, since it needs a recipe bump and a new release."""
    fetch_versions = fetch_versions or maven_versions
    added, rebuild = [], []
    for library in index["libraries"]:
        release = library.get("release")
        if not release or not library["builds"]:
            continue
        android = library.get("android", {})
        repository = android.get("repository", GOOGLE_MAVEN)
        coordinate = android.get("artifact", library["artifact"])
        have = {b["version"] for b in library["builds"]}
        fixed = library.get("fixedIn")
        newest = max(version_key(b["version"]) for b in library["builds"])
        api_cache: dict[str, list[str]] = {}

        def api(version: str) -> list[str]:
            if version not in api_cache:
                api_cache[version] = native_api(
                    fetch_bytes(maven_url(repository, coordinate, version, "aar"))
                )
            return api_cache[version]

        for version in sorted(fetch_versions(repository, coordinate), key=version_key):
            key = version_key(version)
            if version in have or not STABLE_RE.match(version) or key < newest:
                continue
            if fixed and key >= version_key(fixed):
                continue
            base = max(
                (b for b in library["builds"] if version_key(b["version"]) < key),
                key=lambda b: version_key(b["version"]),
            )
            base_version = base.get("sameAs", base["version"])
            try:
                same = api(version) == api(base_version)
            except Exception as e:  # noqa: BLE001
                print(f"{label(library)} {version}: skipped: {e}", file=sys.stderr)
                continue
            if same:
                library["builds"].append(
                    {"version": version, "sameAs": base_version, "platforms": base["platforms"]}
                )
                added.append(
                    f"{label(library)} {version}: native API unchanged, reuses {base_version}"
                )
            else:
                rebuild.append(
                    f"{label(library)} {version}: native API differs from {base_version}; "
                    f"bump host-natives/{release['recipe']}/recipe.properties to release a build"
                )
    return added, rebuild


# --- The AndroidX survey -----------------------------------------------------------------------------

# KMP targets that publish klibs rather than JVM or Android archives.
KLIB_SUFFIXES = re.compile(
    r"-(ios|tvos|watchos|macos|linux|mingw|js|wasm|androidnative)[\w-]*$"
)
NATIVE_RE = re.compile(r"\.(so|dylib|dll|jnilib)$")


def fetch_range(url: str, spec: str) -> bytes | None:
    """Bytes [spec] (`-n` for the last n, or `a-b`) of [url]; None when it 404s. A server that
    ignores Range (the private cache does) answers 200 with the whole file: the tail of that is the
    whole file, which `zip_names_from` reads as is, and `a-b` is sliced out of it."""
    try:
        with _open(url, {"Range": f"bytes={spec}"}, 120) as resp:
            data = resp.read()
            if resp.status == 206 or spec.startswith("-"):
                return data
            start, end = (int(n) for n in spec.split("-"))
            return data[start : end + 1]
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def zip_names_from(tail: bytes, read_range) -> list[str]:
    """Entry names of a zip from its last bytes [tail], reading the central directory with
    [read_range] (`start, end` inclusive, relative to the archive) when it starts before them."""
    eocd = tail.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("no end of central directory")
    entries, size, offset = struct.unpack_from("<HII", tail, eocd + 10)
    total = offset + size + (len(tail) - eocd)
    start_in_tail = offset - (total - len(tail))
    directory = (
        tail[start_in_tail : start_in_tail + size]
        if start_in_tail >= 0
        else read_range(offset, offset + size - 1)
    )
    names, pos = [], 0
    for _ in range(entries):
        if directory[pos : pos + 4] != b"PK\x01\x02":
            break
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", directory, pos + 28)
        names.append(directory[pos + 46 : pos + 46 + name_len].decode("utf-8", "replace"))
        pos += 46 + name_len + extra_len + comment_len
    return names


def remote_zip_names(url: str) -> list[str] | None:
    """Entry names of a remote zip, from two ranged requests at most; None when it 404s."""
    tail = fetch_range(url, "-65536")
    if tail is None:
        return None
    return zip_names_from(tail, lambda a, b: fetch_range(url, f"{a}-{b}") or b"")


def survey_group(group: str, artifacts: dict[str, list[str]], names=remote_zip_names) -> list[dict]:
    """The artifacts of one AndroidX group whose newest stable (else newest) AAR ships natives."""
    rows = []
    for name, versions in artifacts.items():
        if KLIB_SUFFIXES.search(name) or name.endswith(("-jvm", "-desktop", "-jvmstubs")):
            continue
        stable = [v for v in versions if STABLE_RE.match(v)]
        version = max(stable or versions, key=version_key)
        url = f"{GOOGLE_MAVEN}/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.aar"
        entries = names(url) or []
        libs = sorted({
            e.rsplit("/", 1)[-1].removeprefix("lib").removesuffix(".so")
            for e in entries
            if e.startswith("jni/") and e.endswith(".so")
        })
        if not libs:
            continue
        host = None
        base = name.removesuffix("-android")
        for sibling in (f"{base}-jvm", f"{base}-desktop"):
            if sibling not in artifacts:
                continue
            host_version = version if version in artifacts[sibling] else max(
                artifacts[sibling], key=version_key
            )
            jar = (
                f"{GOOGLE_MAVEN}/{group.replace('.', '/')}/{sibling}/{host_version}/"
                f"{sibling}-{host_version}.jar"
            )
            host_natives = sorted(e for e in (names(jar) or []) if NATIVE_RE.search(e))
            if host_natives:
                host = {"artifact": f"{group}:{sibling}", "version": host_version,
                        "natives": host_natives}
                break
        rows.append({"artifact": f"{group}:{name}", "version": version, "libraries": libs,
                     "host": host})
    return rows


def survey(fetch_text=lambda url: fetch(url).decode(), names=remote_zip_names) -> list[dict]:
    master = fetch_text(f"{GOOGLE_MAVEN}/master-index.xml")
    groups = re.findall(r"<(androidx\.[\w.]+)\s*/>", master)

    def one(group: str) -> list[dict]:
        xml = fetch_text(f"{GOOGLE_MAVEN}/{group.replace('.', '/')}/group-index.xml")
        artifacts = {
            m.group(1): m.group(2).split(",")
            for m in re.finditer(r'<([\w.-]+) versions="([^"]*)"', xml)
        }
        return survey_group(group, artifacts, names)

    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        rows = [row for rows in pool.map(one, groups) for row in rows]
    return sorted(rows, key=lambda r: r["artifact"])


def render_survey(rows: list[dict], index: dict) -> str:
    def covering(name: str) -> dict | None:
        return next((lib for lib in index["libraries"] if matches(lib, name)), None)

    out = [
        "# AndroidX native libraries",
        "",
        "Generated by `host-natives/index.py survey` (weekly, in `host-natives-watch.yml`); do not",
        "edit by hand. Every AndroidX artifact whose newest stable AAR (else newest) ships",
        "`jni/<abi>/*.so`: the menu of libraries `compose-preview launch` could serve a host build of",
        "through `index.json`.",
        "",
        "- **Host build:** an upstream `-jvm`/`-desktop` sibling that ships desktop libraries; add it",
        "  to the index with an `upstream` block.",
        "- **Index:** the library is in `index.json`, with its build count.",
        "- Neither: a recipe candidate if its code reaches the library off Android; see",
        "  `docs/design/SIMULATOR_NATIVE_LIBRARIES.md` for which ones do.",
        "",
        "| Artifact | Version | Libraries | Host build | Index |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in rows:
        host = row["host"]
        host_text = (
            f"`{host['artifact']}` {host['version']} ({len(host['natives'])} libraries)"
            if host else ""
        )
        index_text = ", ".join(
            f"{len(lib['builds'])} builds" for lib in map(covering, row["libraries"]) if lib
        )
        libs = ", ".join(f"`{lib}`" for lib in row["libraries"])
        out.append(
            f"| `{row['artifact']}` | {row['version']} | {libs} | {host_text} | {index_text} |"
        )
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--online", action="store_true")
    sub.add_parser("watch")
    release = sub.add_parser("add-release")
    release.add_argument("--dist", type=Path, required=True)
    survey_cmd = sub.add_parser("survey")
    survey_cmd.add_argument("--out", type=Path, default=SURVEY)
    args = ap.parse_args()

    index = load()
    errors = validate(index)
    for e in errors:
        print(f"::error::host-natives/index.json: {e}", file=sys.stderr)
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
        return 1
    if args.command == "survey":
        rows = survey()
        args.out.write_text(render_survey(rows, index))
        print(f"{len(rows)} AndroidX artifacts ship native libraries; wrote {args.out}")
        return 0
    if args.command == "watch":
        added = watch(index)
        recipe_added, rebuild = watch_recipes(index)
        added += recipe_added + backfill_build_ids(index)
        for line in rebuild:
            print(f"needs a rebuild: {line}")
    else:
        added = add_release(index, args.dist) + backfill_build_ids(index)
    save(index)
    for line in added:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
