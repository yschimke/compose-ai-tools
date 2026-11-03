#!/usr/bin/env python3
"""Tests for host-natives/index.py, hermetic: Maven and downloads are faked."""

from __future__ import annotations

import copy
import hashlib
import io
import struct
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import index


def jar(*names: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for n in names:
            z.writestr(n, "x")
    return buf.getvalue()


def tarball(files: dict[str, str]) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name, text in files.items():
            data = text.encode()
            info = tarfile.TarInfo(f"./{name}")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


class TheCheckedInIndex(unittest.TestCase):
    def test_is_valid(self):
        self.assertEqual(index.validate(index.load()), [])


class Validate(unittest.TestCase):
    def test_reports_bad_entries(self):
        bad = {
            "schema": index.SCHEMA,
            "libraries": [
                {
                    "library": "x",
                    "artifact": "a:b",
                    "builds": [
                        {
                            "version": "1.0.0",
                            "platforms": {"beos": {"url": "http://x", "sha256": "no", "entry": ""}},
                        }
                    ],
                }
            ],
        }
        errors = index.validate(bad)
        self.assertEqual(len(errors), 4, errors)


class Versions(unittest.TestCase):
    def test_orders_prereleases_before_their_release(self):
        ordered = sorted(["2.7.1", "2.8.0-alpha03", "2.10.0", "2.8.0"], key=index.version_key)
        self.assertEqual(ordered, ["2.7.1", "2.8.0-alpha03", "2.8.0", "2.10.0"])


class Watch(unittest.TestCase):
    def test_adds_new_stable_versions_with_their_hash(self):
        idx = {
            "schema": index.SCHEMA,
            "libraries": [
                {
                    "library": "sqliteJni",
                    "artifact": "androidx.sqlite:sqlite-bundled",
                    "upstream": {
                        "repository": "https://maven.example",
                        "artifact": "androidx.sqlite:sqlite-bundled-jvm",
                        "since": "2.5.0",
                        "entries": {
                            "linux-x86_64": "natives/linux_x64/libsqliteJni.so",
                            "macos-x86_64": "natives/osx_x64/libsqliteJni.dylib",
                        },
                    },
                    "builds": [{"version": "2.5.0", "platforms": {}}],
                }
            ],
        }
        data = jar("natives/linux_x64/libsqliteJni.so")
        added = index.watch(
            idx,
            fetch_versions=lambda repo, art: ["2.4.0", "2.5.0", "2.6.0-alpha01", "2.6.0"],
            fetch_bytes=lambda url: data,
        )
        self.assertEqual(added, ["sqliteJni 2.6.0 (linux-x86_64)"])
        build = idx["libraries"][0]["builds"][-1]
        self.assertEqual(
            build["platforms"]["linux-x86_64"],
            {
                "url": "https://maven.example/androidx/sqlite/sqlite-bundled-jvm/2.6.0/"
                "sqlite-bundled-jvm-2.6.0.jar",
                "sha256": hashlib.sha256(data).hexdigest(),
                "entry": "natives/linux_x64/libsqliteJni.so",
            },
        )


class AddRelease(unittest.TestCase):
    def test_records_both_mac_architectures_from_the_universal_tarball(self):
        idx = copy.deepcopy(index.load())
        # Independent of what the checked-in index already lists for graphics-path.
        for library in idx["libraries"]:
            if library.get("release", {}).get("recipe") == "graphics-path":
                library["builds"] = []
        with tempfile.TemporaryDirectory() as tmp:
            dist = Path(tmp)
            props = (
                "recipe=graphics-path\ntag=host-natives-graphics-path-1.0.1\n"
                "platform=macos-universal\nlibrary=androidx.graphics.path\n"
                "artifact=androidx.graphics:graphics-path\nversion=1.0.1\n"
                "file=libandroidx.graphics.path.dylib\n"
            )
            data = tarball(
                {"host-natives.properties": props, "libandroidx.graphics.path.dylib": "x"}
            )
            (dist / "host-natives-graphics-path-1.0.1-macos-universal.tar.gz").write_bytes(data)
            added = index.add_release(idx, dist)
        self.assertEqual(len(added), 2, added)
        library = next(l for l in idx["libraries"] if l["library"] == "androidx.graphics.path")
        build = library["builds"][0]
        self.assertEqual(build["version"], "1.0.1")
        self.assertEqual(set(build["platforms"]), {"macos-arm64", "macos-x86_64"})
        self.assertEqual(
            build["platforms"]["macos-arm64"]["url"],
            "https://github.com/yschimke/compose-ai-tools/releases/download/"
            "host-natives-graphics-path-1.0.1/host-natives-graphics-path-1.0.1-macos-universal.tar.gz",
        )
        self.assertEqual(index.validate(idx), [])
        self.assertIn("libandroidx.graphics.path.dylib", index.archive_names(data, "x.tar.gz"))


def class_file(name: str, methods: list[tuple[int, str, str]]) -> bytes:
    """A minimal class file: [name] with [methods] as (access flags, name, descriptor)."""
    pool = [b""]

    def utf8(text: str) -> int:
        raw = text.encode()
        pool.append(b"\x01" + struct.pack(">H", len(raw)) + raw)
        return len(pool) - 1

    this = utf8(name)
    pool.append(b"\x07" + struct.pack(">H", this))
    this_class = len(pool) - 1
    body = struct.pack(">HHH", 0x21, this_class, 0) + struct.pack(">HH", 0, 0)
    body += struct.pack(">H", len(methods))
    for flags, method, descriptor in methods:
        body += struct.pack(">HHHH", flags, utf8(method), utf8(descriptor), 0)
    header = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(pool))
    return header + b"".join(pool[1:]) + body + struct.pack(">H", 0)


def aar(classes: dict[str, bytes], natives: tuple[str, ...] = ()) -> bytes:
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as z:
        for name, data in classes.items():
            z.writestr(name, data)
    outer = io.BytesIO()
    with zipfile.ZipFile(outer, "w") as z:
        z.writestr("classes.jar", inner.getvalue())
        for n in natives:
            z.writestr(n, "elf")
    return outer.getvalue()


class NativeApi(unittest.TestCase):
    def test_lists_only_native_methods(self):
        cls = class_file(
            "androidx/graphics/path/PathIteratorImpl",
            [(0x0100 | 0x0008, "internalPathIterator", "(Landroid/graphics/Path;IIIF)J"),
             (0x0001, "next", "()I")],
        )
        self.assertEqual(
            index.native_api(aar({"androidx/graphics/path/PathIteratorImpl.class": cls})),
            ["androidx.graphics.path.PathIteratorImpl.internalPathIterator"
             "(Landroid/graphics/Path;IIIF)J"],
        )


class WatchRecipes(unittest.TestCase):
    def library(self):
        return {
            "schema": index.SCHEMA,
            "libraries": [{
                "library": "androidx.graphics.path",
                "artifact": "androidx.graphics:graphics-path",
                "fixedIn": "1.1.0",
                "release": {"recipe": "graphics-path"},
                "builds": [{"version": "1.0.1", "platforms": {"linux-x86_64": {
                    "url": "https://x/a.tar.gz", "sha256": "a" * 64, "entry": "lib.so"}}}],
            }],
        }

    def test_same_api_reuses_the_build_and_a_changed_one_asks_for_a_release(self):
        v1 = aar({"A.class": class_file("A", [(0x0100, "n", "()V")])})
        v2 = aar({"A.class": class_file("A", [(0x0100, "n", "(I)V")])})
        archives = {"1.0.1": v1, "1.0.2": v1, "1.0.3": v2}
        idx = self.library()
        added, rebuild = index.watch_recipes(
            idx,
            fetch_versions=lambda repo, art: ["1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.1.0"],
            fetch_bytes=lambda url: archives[url.rsplit("/", 2)[-2]],
        )
        self.assertEqual(added, ["androidx.graphics.path 1.0.2: native API unchanged, reuses 1.0.1"])
        self.assertEqual(len(rebuild), 1, rebuild)
        self.assertIn("1.0.3", rebuild[0])
        builds = idx["libraries"][0]["builds"]
        self.assertEqual(builds[-1]["sameAs"], "1.0.1")
        self.assertEqual(builds[-1]["platforms"], builds[0]["platforms"])
        self.assertEqual(index.validate(idx), [])


class Survey(unittest.TestCase):
    def test_reads_a_zip_listing_from_its_tail(self):
        data = jar(*(f"dir/file{i}.txt" for i in range(50)))
        tail = data[-200:]
        names = index.zip_names_from(tail, lambda a, b: data[a : b + 1])
        self.assertEqual(len(names), 50)
        self.assertEqual(names[0], "dir/file0.txt")

    def test_finds_android_natives_and_the_host_sibling(self):
        archives = {
            "sqlite-bundled-android-2.7.1.aar": ["jni/arm64-v8a/libsqliteJni.so", "classes.jar"],
            "sqlite-bundled-jvm-2.7.1.jar": ["natives/linux_x64/libsqliteJni.so"],
            "sqlite-2.7.1.aar": ["classes.jar"],
        }
        rows = index.survey_group(
            "androidx.sqlite",
            {
                "sqlite": ["2.7.1"],
                "sqlite-bundled-android": ["2.7.1", "2.8.0-alpha03"],
                "sqlite-bundled-jvm": ["2.7.1"],
                "sqlite-bundled-iosarm64": ["2.7.1"],
            },
            names=lambda url: archives.get(url.rsplit("/", 1)[-1]),
        )
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["version"], "2.7.1")
        self.assertEqual(row["libraries"], ["sqliteJni"])
        self.assertEqual(row["host"]["artifact"], "androidx.sqlite:sqlite-bundled-jvm")
        text = index.render_survey(rows, index.load())
        self.assertIn("| `androidx.sqlite:sqlite-bundled-android` | 2.7.1 | `sqliteJni` |", text)


class Cache(unittest.TestCase):
    env = {"COO_MAVEN_URL": "https://cache.example/", "COO_MAVEN_USER": "u", "COO_MAVEN_TOKEN": "t"}

    def test_routes_public_maven_hosts_only_when_fully_configured(self):
        url, headers = index.via_cache(
            "https://dl.google.com/dl/android/maven2/androidx/x/maven-metadata.xml", self.env
        )
        self.assertEqual(url, "https://cache.example/google/androidx/x/maven-metadata.xml")
        self.assertTrue(headers["Authorization"].startswith("Basic "))
        self.assertEqual(
            index.via_cache("https://repo1.maven.org/maven2/a/b.jar", self.env)[0],
            "https://cache.example/central/a/b.jar",
        )
        self.assertIsNone(index.via_cache("https://github.com/x/releases/a.tar.gz", self.env))
        self.assertIsNone(
            index.via_cache("https://repo1.maven.org/maven2/a/b.jar", {"COO_MAVEN_URL": "x"})
        )

    def test_a_server_ignoring_range_still_answers(self):
        class Whole:
            status = 200

            def __init__(self, data):
                self.data = data

            def read(self):
                return self.data

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        data = bytes(range(100))
        with mock.patch.object(index, "_open", return_value=Whole(data)):
            self.assertEqual(index.fetch_range("https://x/a.jar", "10-19"), data[10:20])
            self.assertEqual(index.fetch_range("https://x/a.jar", "-5"), data)


def elf_with_build_id(build_id: bytes) -> bytes:
    """A minimal little-endian ELF64 whose one PT_NOTE holds an NT_GNU_BUILD_ID."""
    note = struct.pack("<III", 4, len(build_id), 3) + b"GNU\0" + build_id
    elf = bytearray(120 + len(note))
    elf[0:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<Q", elf, 0x20, 64)
    struct.pack_into("<HH", elf, 0x36, 56, 1)
    struct.pack_into("<IIQQQQQQ", elf, 64, 4, 0, 120, 0, 0, len(note), len(note), 4)
    elf[120:] = note
    return bytes(elf)


class BuildIds(unittest.TestCase):
    def test_reads_the_gnu_build_id(self):
        self.assertEqual(index.elf_build_id(elf_with_build_id(b"\xde\xad\xbe\xef")), "deadbeef")
        self.assertIsNone(index.elf_build_id(b"not an elf" * 10))

    def test_backfill_records_ids_once_including_none(self):
        idx = {
            "schema": index.SCHEMA,
            "libraries": [{
                "library": "conscrypt_jni",
                "artifact": "org.conscrypt:conscrypt-android",
                "android": {"repository": "https://m", "artifact": "org.conscrypt:conscrypt-android"},
                "builds": [{"version": "2.8.0", "platforms": {}},
                           {"version": "2.7.0", "platforms": {}, "androidBuildIds": []}],
            }],
        }
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as z:
            z.writestr("jni/arm64-v8a/libother.so", elf_with_build_id(b"\xff\xff\xff\xff"))
            z.writestr("jni/arm64-v8a/libconscrypt_jni.so", elf_with_build_id(b"\x01\x02\x03\x04"))
        archive = buffer.getvalue()
        fetched = []
        index.backfill_build_ids(idx, fetch_bytes=lambda url: fetched.append(url) or archive)
        builds = idx["libraries"][0]["builds"]
        self.assertEqual(builds[0]["androidBuildIds"], ["01020304"])
        self.assertEqual(len(fetched), 1, "2.7.0 is already recorded, as having none")


class Patterns(unittest.TestCase):
    library = {"libraryPattern": "zstd-jni-(.+)", "artifact": "com.github.luben:zstd-jni"}

    def test_a_pattern_matches_versioned_names(self):
        self.assertEqual(index.matches(self.library, "zstd-jni-1.5.7-9").group(1), "1.5.7-9")
        self.assertIsNone(index.matches(self.library, "zstd"))
        self.assertEqual(index.validate({"schema": index.SCHEMA, "libraries": [self.library]}), [])

    def test_watch_fills_the_version_into_entries_and_honours_stable(self):
        idx = {"schema": index.SCHEMA, "libraries": [dict(self.library, builds=[], upstream={
            "repository": "https://m", "artifact": "com.github.luben:zstd-jni",
            "since": "1.5.7-9", "stable": r"^\d+\.\d+\.\d+-\d+$",
            "entries": {"linux-x86_64": "linux/amd64/libzstd-jni-{version}.so"}})]}
        added = index.watch(
            idx,
            fetch_versions=lambda r, a: ["1.5.7-8", "1.5.7-10", "1.5.8-rc1"],
            fetch_bytes=lambda url: jar(f"linux/amd64/libzstd-jni-{url.split('/')[-2]}.so"),
        )
        self.assertEqual(added, ["zstd-jni-(.+) 1.5.7-10 (linux-x86_64)"])
        self.assertEqual(
            idx["libraries"][0]["builds"][0]["platforms"]["linux-x86_64"]["entry"],
            "linux/amd64/libzstd-jni-1.5.7-10.so",
        )

    def test_numeric_suffixes_sort_after_their_release(self):
        self.assertEqual(
            sorted(["1.5.7-10", "1.5.7", "1.5.7-9", "1.5.7-rc1"], key=index.version_key),
            ["1.5.7-rc1", "1.5.7", "1.5.7-9", "1.5.7-10"],
        )


if __name__ == "__main__":
    unittest.main()
