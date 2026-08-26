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


class QualifiedReferences(unittest.TestCase):
    """A crossing written out in full is still a crossing (PR #4512 review).

    Kotlin does not require an import, so an import-only scanner reports a green seam while code
    reaches straight across it. Three real crossings in `:cli` were invisible until this landed.
    """

    def scan(self, body):
        with TemporaryDirectory() as tmp:
            f = Path(tmp) / "A.kt"
            f.write_text(body)
            return mod.imports_in(f)

    def test_qualified_call_counts_as_a_crossing(self):
        found = self.scan(
            "package ee.schimke.composeai.cli.serve\n\n"
            "val x = ee.schimke.composeai.cli.BundleReader.read(p)\n"
        )
        self.assertEqual([mod.short(f) for f in found], ["BundleReader"])

    def test_qualified_and_imported_land_on_one_allowlist_entry(self):
        self.assertEqual(
            mod.short("ee.schimke.composeai.cli.serve.ServeHost.Companion.of"),
            mod.short("ee.schimke.composeai.cli.serve.ServeHost"),
        )
        self.assertEqual(mod.short("ee.schimke.composeai.cli.BundleReader.read"), "BundleReader")

    def test_top_level_declaration_keeps_its_whole_path(self):
        self.assertEqual(mod.short("ee.schimke.composeai.cli.downscaleRaster"), "downscaleRaster")
        self.assertEqual(mod.short("ee.schimke.composeai.cli.serve.clampTo"), "serve.clampTo")

    def test_the_files_own_package_line_is_not_a_reference(self):
        self.assertEqual(self.scan("package ee.schimke.composeai.cli.serve\n"), [])

    def test_doc_references_do_not_count(self):
        """A KDoc link documents a relationship; it does not create one."""
        found = self.scan(
            "package ee.schimke.composeai.cli.serve\n\n"
            "/** See [ee.schimke.composeai.cli.BundleReader]. */\n"
            "// also ee.schimke.composeai.cli.BundleSigning\n"
            "val x = 1\n"
        )
        self.assertEqual(found, [])

    def test_forbidden_packages_are_caught_when_written_out(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp) / "cli/src/main/kotlin/ee/schimke/composeai/cli/serve"
            root.mkdir(parents=True)
            (root / "A.kt").write_text(
                "package ee.schimke.composeai.cli.serve\n\n"
                "val d = ee.schimke.composeai.mcp.DaemonLaunchDescriptor.parse(t)\n"
            )
            original, mod.REPO_ROOT = mod.REPO_ROOT, Path(tmp)
            try:
                hits = mod.forbidden_hits(["ee.schimke.composeai.mcp"])
            finally:
                mod.REPO_ROOT = original
        self.assertEqual(len(hits), 1)
        self.assertIn("qualified reference", hits[0])


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

    def test_forbidden_list_names_packages_that_exist(self):
        """A rule that names a nonexistent namespace enforces nothing (PR #4512 review).

        The first version of this list said `ee.schimke.composeai.gradle`, which no source file in
        the repo declares — so the gradle-plugin half of the "hard" rule was decorative, while
        `plugin.tooling` types are on the CLI's compile classpath through `:gradle-preview-driver`
        and reachable from serve today.
        """
        declared = set()
        for path in (mod.REPO_ROOT / "cli").rglob("*.kt"):
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.startswith("package "):
                    declared.add(line.split()[1])
                    break
        for path in (mod.REPO_ROOT / "gradle-plugin").rglob("*.kt"):
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.startswith("package "):
                    declared.add(line.split()[1])
                    break
        for forbidden in mod.load_allowlist()["forbiddenPackages"]:
            with self.subTest(package=forbidden):
                self.assertTrue(
                    any(p == forbidden or p.startswith(forbidden + ".") for p in declared)
                    # `renderer` / `mcp` live outside the two trees walked above; assert the
                    # gradle-plugin entry specifically, which is the one that was wrong.
                    or forbidden
                    in ("ee.schimke.composeai.renderer", "ee.schimke.composeai.mcp"),
                    f"{forbidden} names no package any source file declares",
                )

    def test_forbidden_packages_are_clean(self):
        self.assertEqual(mod.forbidden_hits(mod.load_allowlist()["forbiddenPackages"]), [])

    def test_the_bundle_cluster_is_on_the_list(self):
        """It is preparation item 5's whole justification — don't lose it silently."""
        entries = set(mod.load_allowlist()["main"]["cliInternalsUsedByServe"])
        self.assertTrue({"BundleReader", "BundleSigning"} & entries)


if __name__ == "__main__":
    unittest.main()
