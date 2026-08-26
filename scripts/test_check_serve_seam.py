#!/usr/bin/env python3
"""Unit tests for check-serve-seam.py.

Pure stdlib (unittest). Run: python3 scripts/test_check_serve_seam.py -v

Two things are worth pinning: the scanner's notion of "crossing" (a serve file
importing its own package is not one, a fully-qualified CLI import is), and the
ratchet semantics — new crossings fail, *and* stale allowlist entries fail, so
the list cannot quietly stop describing the code.
"""

import importlib.util
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

_HERE = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "check_serve_seam", _HERE / "check-serve-seam.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class Imports(unittest.TestCase):
    def test_matches_cli_imports_only(self):
        with TemporaryDirectory() as tmp:
            f = Path(tmp) / "A.kt"
            f.write_text(
                "package ee.schimke.composeai.cli.serve\n\n"
                "import ee.schimke.composeai.cli.BundleReader\n"
                "import ee.schimke.composeai.cli.serve.ServeHost\n"
                "import ee.schimke.composeai.daemon.protocol.RenderRequest\n"
                "import okio.FileSystem\n"
            )
            self.assertEqual(
                mod.imports_in(f),
                [
                    "ee.schimke.composeai.cli.BundleReader",
                    "ee.schimke.composeai.cli.serve.ServeHost",
                ],
            )

    def test_short_strips_the_cli_package(self):
        self.assertEqual(mod.short("ee.schimke.composeai.cli.BundleReader"), "BundleReader")
        self.assertEqual(
            mod.short("ee.schimke.composeai.cli.serve.ServeHost"), "serve.ServeHost"
        )


class Ratchet(unittest.TestCase):
    def test_new_crossing_is_an_addition(self):
        added, stale = mod.diff({"BundleReader": {"a.kt"}, "New": {"b.kt"}}, ["BundleReader"])
        self.assertEqual(added, ["New"])
        self.assertEqual(stale, [])

    def test_removed_crossing_is_stale_and_must_be_pruned(self):
        added, stale = mod.diff({"BundleReader": {"a.kt"}}, ["BundleReader", "Gone"])
        self.assertEqual(added, [])
        self.assertEqual(stale, ["Gone"])

    def test_exact_match_is_clean(self):
        self.assertEqual(mod.diff({"A": {"a.kt"}}, ["A"]), ([], []))


class RealTree(unittest.TestCase):
    """The committed allowlist must describe the committed source."""

    def test_repo_is_green(self):
        self.assertEqual(mod.check(write=False, allow_growth=False), 0)

    def test_allowlist_has_both_directions_for_both_source_sets(self):
        allowlist = mod.load_allowlist()
        for source_set in mod.SOURCE_SETS:
            for direction in ("cliInternalsUsedByServe", "serveInternalsUsedByCli"):
                self.assertIn(direction, allowlist[source_set])

    def test_forbidden_packages_are_clean(self):
        self.assertEqual(mod.forbidden_hits(mod.load_allowlist()["forbiddenPackages"]), [])

    def test_the_bundle_cluster_is_on_the_list(self):
        """It is preparation item 5's whole justification — don't lose it silently."""
        entries = set(mod.load_allowlist()["main"]["cliInternalsUsedByServe"])
        self.assertTrue({"BundleReader", "BundleSigning"} & entries)


if __name__ == "__main__":
    unittest.main()
