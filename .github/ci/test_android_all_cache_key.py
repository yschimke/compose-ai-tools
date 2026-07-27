#!/usr/bin/env python3
"""Unit tests for android-all-cache-key.py's retention policy.

Pure stdlib (unittest). Run: python3 .github/ci/test_android_all_cache_key.py -v

The cases that matter are the two that cost real CI time when they regress:
a multi-SDK cell must keep every coordinate it fetched (up to the cap), and the
key must change if and only if the retained SET changes — a key that moves on an
unchanged cell writes a new ~600 MB entry every run, and a key that sticks after
a bump leaves the entry permanently incomplete.
"""

import importlib.util
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

_HERE = Path(__file__).resolve().parent

# Load android-all-cache-key.py as a module (hyphenated filename → importlib).
_spec = importlib.util.spec_from_file_location(
    "android_all_cache_key", _HERE / "android-all-cache-key.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


def _tree(root: Path, coordinates, with_jar=True):
    """Materialise coordinate dirs under [root], each carrying a dummy jar."""
    for name in coordinates:
        d = root / name
        d.mkdir(parents=True)
        if with_jar:
            (d / f"android-all-instrumented-{name}.jar").write_bytes(b"jar")


class Partition(unittest.TestCase):
    def test_keeps_the_highest_android_versions(self):
        keep, drop = mod.partition(
            [
                "14-robolectric-10818077-i7",
                "16-robolectric-13921718-i7",
                "15-robolectric-13954326-i7",
            ],
            cap=2,
        )
        self.assertEqual(
            ["16-robolectric-13921718-i7", "15-robolectric-13954326-i7"], keep
        )
        self.assertEqual(["14-robolectric-10818077-i7"], drop)

    def test_a_multi_sdk_cell_keeps_every_coordinate_under_the_cap(self):
        # The wear-os-samples case: the Gradle render path and the daemon path
        # resolve different SDKs, and dropping either re-downloads ~200 MB every
        # run while the key never changes.
        present = ["15-robolectric-13954326-i7", "16-robolectric-13921718-i7"]
        keep, drop = mod.partition(present, cap=3)
        self.assertCountEqual(present, keep)
        self.assertEqual([], drop)

    def test_same_version_orders_by_build_newest_first(self):
        keep, drop = mod.partition(
            ["16-robolectric-13921718-i7", "16-robolectric-14000000-i7"], cap=1
        )
        self.assertEqual(["16-robolectric-14000000-i7"], keep)
        self.assertEqual(["16-robolectric-13921718-i7"], drop)

    def test_an_unversioned_directory_is_dropped_first(self):
        keep, drop = mod.partition(["scratch", "16-robolectric-13921718-i7"], cap=1)
        self.assertEqual(["16-robolectric-13921718-i7"], keep)
        self.assertEqual(["scratch"], drop)


class CacheKey(unittest.TestCase):
    def test_a_single_coordinate_reads_plainly(self):
        self.assertEqual(
            "16-robolectric-13921718-i7", mod.cache_key(["16-robolectric-13921718-i7"])
        )

    def test_several_coordinates_collapse_to_a_short_stable_digest(self):
        names = ["16-robolectric-13921718-i7", "15-robolectric-13954326-i7"]
        key = mod.cache_key(names)
        self.assertTrue(key.startswith("2x-"), key)
        self.assertEqual(len("2x-") + 16, len(key))
        # Order-independent: the same SET must key the same, or a cell whose
        # directory listing shuffles writes a redundant entry.
        self.assertEqual(key, mod.cache_key(list(reversed(names))))

    def test_the_key_changes_when_the_retained_set_changes(self):
        before = mod.cache_key(["15-robolectric-13954326-i7", "16-robolectric-13921718-i7"])
        after = mod.cache_key(["16-robolectric-13921718-i7", "17-robolectric-14100000-i7"])
        self.assertNotEqual(before, after)


class Resolve(unittest.TestCase):
    def test_prunes_over_cap_directories_from_disk(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            _tree(
                root,
                [
                    "14-robolectric-10818077-i7",
                    "15-robolectric-13954326-i7",
                    "16-robolectric-13921718-i7",
                ],
            )

            key = mod.resolve(root, cap=2)

            self.assertEqual(
                ["15-robolectric-13954326-i7", "16-robolectric-13921718-i7"],
                sorted(d.name for d in root.iterdir()),
            )
            self.assertEqual(
                mod.cache_key(
                    ["15-robolectric-13954326-i7", "16-robolectric-13921718-i7"]
                ),
                key,
            )

    def test_keeps_everything_when_under_the_cap(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            present = ["15-robolectric-13954326-i7", "16-robolectric-13921718-i7"]
            _tree(root, present)

            key = mod.resolve(root, cap=3)

            self.assertCountEqual(present, [d.name for d in root.iterdir()])
            self.assertEqual(mod.cache_key(present), key)

    def test_a_jarless_directory_is_not_cached_or_keyed(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            _tree(root, ["16-robolectric-13921718-i7"], with_jar=False)

            self.assertEqual("", mod.resolve(root))

    def test_a_missing_root_yields_an_empty_key(self):
        with TemporaryDirectory() as tmp:
            self.assertEqual("", mod.resolve(Path(tmp) / "never-downloaded"))


if __name__ == "__main__":
    unittest.main()
