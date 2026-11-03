#!/usr/bin/env python3
"""Tests for host-natives/index.py, hermetic: Maven and downloads are faked."""

from __future__ import annotations

import copy
import hashlib
import io
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

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
        with tempfile.TemporaryDirectory() as tmp:
            dist = Path(tmp)
            props = (
                "version=1.0.0\nplatform=macos-universal\n"
                "graphics-path.library=libandroidx.graphics.path.dylib\n"
                "graphics-path.version=1.0.1\n"
            )
            data = tarball(
                {"host-natives.properties": props, "libandroidx.graphics.path.dylib": "x"}
            )
            (dist / "host-natives-macos-universal-1.0.0.tar.gz").write_bytes(data)
            added = index.add_release(idx, dist, "host-natives-v1.0.0")
        self.assertEqual(len(added), 2, added)
        library = next(l for l in idx["libraries"] if l["library"] == "androidx.graphics.path")
        build = library["builds"][0]
        self.assertEqual(build["version"], "1.0.1")
        self.assertEqual(set(build["platforms"]), {"macos-arm64", "macos-x86_64"})
        self.assertEqual(
            build["platforms"]["macos-arm64"]["url"],
            "https://github.com/yschimke/compose-ai-tools/releases/download/"
            "host-natives-v1.0.0/host-natives-macos-universal-1.0.0.tar.gz",
        )
        self.assertEqual(index.validate(idx), [])
        self.assertIn("libandroidx.graphics.path.dylib", index.archive_names(data, "x.tar.gz"))


if __name__ == "__main__":
    unittest.main()
