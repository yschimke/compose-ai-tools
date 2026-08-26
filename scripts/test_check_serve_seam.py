#!/usr/bin/env python3
"""Unit tests for check-serve-seam.py.

Pure stdlib (unittest). Run: python3 scripts/test_check_serve_seam.py -v

Two things are worth pinning: the scanner's notion of "crossing" (a serve file
importing its own package is not one, a fully-qualified CLI import is), and the
ratchet semantics — new crossings fail, *and* stale allowlist entries fail, so
the list cannot quietly stop describing the code.
"""

import importlib.util
import re
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


class CommentAndStringStripping(unittest.TestCase):
    """A regex cannot tell a comment from a string (PR #4512 review).

    `"Disallow: /*/p/"` is a real literal in `ServeHttpRoutingTest.kt`, and under the previous
    regex stripper it opened a phantom block comment that blanked ~9.7k characters of that file.
    Nothing was hidden by it in the end, but anything in the swallowed span would have been.
    """

    def test_a_slash_star_inside_a_string_does_not_open_a_comment(self):
        body = mod.strip_comments_and_strings(
            'val robots = "Disallow: /*/p/"\n'
            "val x = ee.schimke.composeai.cli.BundleReader\n"
        )
        self.assertIn("ee.schimke.composeai.cli.BundleReader", body)

    def test_real_block_comments_are_still_removed(self):
        body = mod.strip_comments_and_strings(
            "/* ee.schimke.composeai.cli.Hidden */ val x = ee.schimke.composeai.cli.Seen\n"
        )
        self.assertNotIn("Hidden", body)
        self.assertIn("Seen", body)

    def test_block_comments_nest_as_kotlin_says_they_do(self):
        body = mod.strip_comments_and_strings("/* a /* b */ ee.x.Hidden */ Seen\n")
        self.assertNotIn("Hidden", body)
        self.assertIn("Seen", body)

    def test_line_comments_are_removed_but_the_line_break_survives(self):
        body = mod.strip_comments_and_strings("// Hidden\nSeen\n")
        self.assertNotIn("Hidden", body)
        self.assertIn("Seen", body)
        self.assertEqual(body.count("\n"), 2)

    def test_raw_strings_are_blanked(self):
        body = mod.strip_comments_and_strings(
            'val q = ' + ('"' * 3) + "\nee.schimke.composeai.mcp.Hidden\n" + ('"' * 3) + "\nSeen\n"
        )
        self.assertNotIn("Hidden", body)
        self.assertIn("Seen", body)

    def test_an_escaped_apostrophe_does_not_end_a_char_literal(self):
        """`'\\''` is a valid Kotlin char literal (PR #4512 review).

        Honouring escapes only inside double quotes left the rest of the file in a phantom literal.
        The committed `WebEscaping.kt` has this literal at line 31, and it hid 73% of that file's
        code from the scanner (295 of 1082 non-space characters visible).
        """
        body = mod.strip_comments_and_strings(
            "val q = '\\''\nval x = ee.schimke.composeai.cli.BundleReader\n"
        )
        self.assertIn("ee.schimke.composeai.cli.BundleReader", body)

    def test_a_backslash_in_a_raw_string_is_literal(self):
        """Raw strings have no escapes, so a trailing backslash must not eat the terminator."""
        q = '"' * 3
        body = mod.strip_comments_and_strings(f"val s = {q}a\\{q}\nval x = ee.schimke.composeai.cli.BundleReader\n")
        self.assertIn("ee.schimke.composeai.cli.BundleReader", body)

    def test_the_real_escaping_file_stays_visible(self):
        path = (
            mod.REPO_ROOT
            / "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/WebEscaping.kt"
        )
        if not path.is_file():
            self.skipTest("fixture file moved")
        text = path.read_text(encoding="utf-8")
        visible = sum(
            1 for c in mod.strip_comments_and_strings(text) if not c.isspace()
        )
        self.assertGreater(visible, 900)  # 295 when the escape bug was live

    def test_an_escaped_quote_does_not_end_the_string(self):
        body = mod.strip_comments_and_strings('val s = "a\\"ee.schimke.composeai.mcp.Hidden"\nSeen\n')
        self.assertNotIn("Hidden", body)
        self.assertIn("Seen", body)

    def test_offsets_are_preserved_so_reported_lines_stay_true(self):
        src = 'val a = "/*"\nval b = 1\n'
        self.assertEqual(len(mod.strip_comments_and_strings(src)), len(src))

    def test_the_real_committed_file_is_not_swallowed(self):
        path = (
            mod.REPO_ROOT
            / "cli/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeHttpRoutingTest.kt"
        )
        if not path.is_file():  # the file may be renamed by a later refactor
            self.skipTest("fixture file moved")
        text = path.read_text(encoding="utf-8")
        survived = len(mod.strip_comments_and_strings(text).strip())
        # The old regex left ~111k of 125k; anything near that means the phantom comment is back.
        self.assertGreater(survived, 118_000)


class StringInterpolation(unittest.TestCase):
    """`${...}` is executable Kotlin, not text (PR #4512 review)."""

    def test_an_interpolated_qualified_reference_survives(self):
        body = mod.strip_comments_and_strings(
            'val s = "${ee.schimke.composeai.mcp.Factory.create()}"\n'
        )
        self.assertIn("ee.schimke.composeai.mcp.Factory", body)

    def test_the_surrounding_text_is_still_blanked(self):
        body = mod.strip_comments_and_strings('val s = "hidden ${ee.x.Kept} hidden"\n')
        self.assertNotIn("hidden", body)
        self.assertIn("ee.x.Kept", body)

    def test_nested_braces_inside_an_interpolation(self):
        body = mod.strip_comments_and_strings(
            'val s = "${list.map { ee.schimke.composeai.mcp.Factory.of(it) }}"\n'
        )
        self.assertIn("ee.schimke.composeai.mcp.Factory", body)

    def test_raw_strings_interpolate_too(self):
        q = '"' * 3
        body = mod.strip_comments_and_strings(f"val s = {q}a ${{ee.x.Kept}} b{q}\n")
        self.assertIn("ee.x.Kept", body)

    def test_a_bare_dollar_name_is_not_treated_as_an_opener(self):
        body = mod.strip_comments_and_strings('val s = "cost: $amount"\nSeen\n')
        self.assertNotIn("amount", body)
        self.assertIn("Seen", body)

    def test_a_forbidden_package_reached_through_interpolation_is_caught(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp) / "cli/src/main/kotlin/ee/schimke/composeai/cli/serve"
            root.mkdir(parents=True)
            (root / "A.kt").write_text(
                "package ee.schimke.composeai.cli.serve\n\n"
                'val s = "${ee.schimke.composeai.mcp.Factory.create()}"\n'
            )
            original, mod.REPO_ROOT = mod.REPO_ROOT, Path(tmp)
            try:
                hits = mod.forbidden_hits(["ee.schimke.composeai.mcp"])
            finally:
                mod.REPO_ROOT = original
        self.assertEqual(len(hits), 1)


class WriteMode(unittest.TestCase):
    """`--write` must not delete the data the check depends on (PR #4512 review)."""

    def test_rewrite_keeps_every_top_level_key(self):
        import json
        import shutil

        path = mod.ALLOWLIST
        before = json.loads(path.read_text(encoding="utf-8"))
        backup = path.with_suffix(".json.testbak")
        shutil.copy(path, backup)
        try:
            self.assertEqual(mod.check(write=True, allow_growth=False), 0)
            after = json.loads(path.read_text(encoding="utf-8"))
        finally:
            shutil.move(backup, path)
        self.assertEqual(set(before), set(after))
        for key in ("contractPackages", "unpublishedContracts", "forbiddenPackages", "_doc"):
            self.assertEqual(before[key], after[key], key)


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


class ReflectiveDependencies(unittest.TestCase):
    """Dependencies reached through a string literal (PR #4512 review).

    `UsageSourceFacts.kt` loads `:usage-source-psi` by class name in an isolated classloader. No
    import scan can see that, and the contract probe cannot either — it never stages the sidecar. It
    is the one category where these checks are blind by construction, so the register is manual and
    this test pins it against the source.
    """

    def test_each_recorded_literal_is_still_in_the_file_that_claims_it(self):
        for fqn, record in mod.load_allowlist()["reflectiveDependencies"].items():
            with self.subTest(fqn=fqn):
                path = mod.REPO_ROOT / record["file"]
                self.assertTrue(path.is_file(), f"{record['file']} is gone; update the register")
                self.assertIn(fqn, path.read_text(encoding="utf-8"))

    def test_serve_has_no_unrecorded_reflective_composeai_literals(self):
        """A new one must be recorded rather than discovered on the day of the split."""
        recorded = set(mod.load_allowlist()["reflectiveDependencies"])
        pattern = re.compile(r'"(ee\.schimke\.composeai\.[A-Za-z0-9_.]+)"')
        found = {}
        for source_set in mod.SOURCE_SETS:
            for path in mod.kotlin_files(mod.serve_root(source_set)):
                text = path.read_text(encoding="utf-8")
                for fqn in pattern.findall(text):
                    # serve's own classes and `:cli`'s are the seam register's business, not this
                    # one — a subprocess main class inside serve crosses no boundary.
                    if fqn.startswith(mod.CLI_PKG + "."):
                        continue
                    if fqn not in recorded and fqn[fqn.rfind(".") + 1 :][:1].isupper():
                        found.setdefault(fqn, set()).add(
                            path.relative_to(mod.REPO_ROOT).as_posix()
                        )
        self.assertEqual(
            found,
            {},
            "unrecorded reflective reference(s); add to reflectiveDependencies: "
            + str({k: sorted(v) for k, v in found.items()}),
        )

    def test_the_published_flag_matches_the_module(self):
        """If one gets published — good news — the register and the design doc must be updated."""
        modules = {
            "usage-source-psi": "usage-source-psi/build.gradle.kts",
            "third-party-rc-embedded-player-jvm": "third_party/rc-embedded-player-jvm/build.gradle.kts",
        }
        for record in mod.load_allowlist()["reflectiveDependencies"].values():
            build_file = modules.get(record["module"])
            if not build_file:
                continue
            with self.subTest(module=record["module"]):
                text = (mod.REPO_ROOT / build_file).read_text(encoding="utf-8")
                self.assertEqual(
                    "composeai.maven-publishing" in text, record["published"], record["module"]
                )


class MappingOwnership(unittest.TestCase):
    """A mapping must name the module that actually declares the type (PR #4512 review).

    Prefix matching alone accepted `ee.schimke.composeai.daemon.bta -> daemon-bta-host` purely
    because the package name resembled the module name. It does not: that package is declared by
    BOTH `:daemon:core` and `:daemon:bta-host`, and the two types serve imports from it
    (`BtaCompileSession`, `DiagnosticCollector`) are the `daemon-core` ones. The wrong mapping
    invented a split blocker that does not exist, and prefix matching would have hidden a genuinely
    new `daemon.*` package owned by a different module the same way.
    """

    @classmethod
    def setUpClass(cls):
        cls.artifacts = {}
        for build_file in mod.REPO_ROOT.rglob("build.gradle.kts"):
            if "/build/" in str(build_file):
                continue
            found = re.search(r'artifactId\s*=\s*"([^"]+)"', build_file.read_text(errors="ignore"))
            if found:
                cls.artifacts[build_file.parent] = found.group(1)

        cls.declared_by = {}
        for module_dir, artifact in cls.artifacts.items():
            src = module_dir / "src"
            if not src.is_dir():
                continue
            for kt in src.rglob("*.kt"):
                if "/test/" in str(kt):
                    continue
                text = kt.read_text(errors="ignore")
                package = re.search(r"^package\s+([\w.]+)", text, re.M)
                if not package:
                    continue
                for name in re.findall(
                    r"^(?:public |internal |abstract |sealed |open |data |value |fun )*"
                    r"(?:class|interface|object|typealias)\s+(\w+)",
                    text,
                    re.M,
                ):
                    cls.declared_by.setdefault(f"{package.group(1)}.{name}", set()).add(artifact)

    def mapped_module(self, package, mapping):
        best = ""
        for prefix in mapping:
            if (package == prefix or package.startswith(prefix + ".")) and len(prefix) > len(best):
                best = prefix
        return mapping.get(best)

    def test_each_imported_type_is_mapped_to_a_module_that_declares_it(self):
        mapping = mod.load_allowlist()["contractPackages"]
        mismatches = []
        for source_set in mod.SOURCE_SETS:
            for path in mod.kotlin_files(mod.serve_root(source_set)):
                for fqn in mod.IMPORT_ANY_RE.findall(path.read_text(encoding="utf-8")):
                    if fqn.startswith(mod.CLI_PKG + "."):
                        continue
                    owners = self.declared_by.get(fqn)
                    if not owners:  # a function or a type this crude scan misses
                        continue
                    want = self.mapped_module(mod.package_of(fqn), mapping)
                    if want not in owners:
                        mismatches.append(f"{fqn}: mapped to {want}, declared by {sorted(owners)}")
        self.assertEqual(mismatches, [], "\n".join(mismatches))

    def test_the_bta_types_come_from_daemon_core(self):
        """The specific error this test exists to prevent."""
        for name in ("BtaCompileSession", "DiagnosticCollector"):
            fqn = f"ee.schimke.composeai.daemon.bta.{name}"
            self.assertEqual(self.declared_by.get(fqn), {"daemon-core"}, fqn)

    def test_there_is_no_unpublished_blocker_today(self):
        self.assertEqual(mod.load_allowlist()["unpublishedContracts"], {})


class ContractCoverage(unittest.TestCase):
    """The probe's coordinate list is hand-maintained; serve's imports are not (PR #4512 review).

    Nothing tied the two together, so serve could start importing a new published module and the
    probe would resolve the same coordinates and pass while the extracted server's dependency floor
    had grown. These assertions are that tie.
    """

    def probe_contracts(self):
        kts = (
            mod.REPO_ROOT / "preview-server/contract-probe/build.gradle.kts"
        ).read_text(encoding="utf-8")
        block = kts.split("val contracts =", 1)[1].split(")", 1)[0]
        return set(re.findall(r'"([a-z0-9-]+)"', block))

    def test_every_package_serve_imports_is_accounted_for(self):
        self.assertEqual(mod.unmapped_contract_packages(mod.load_allowlist()), {})

    def test_every_mapped_module_is_a_probe_contract_or_a_recorded_blocker(self):
        allowlist = mod.load_allowlist()
        contracts = self.probe_contracts()
        unpublished = set(allowlist.get("unpublishedContracts", {}))
        for package, module in allowlist["contractPackages"].items():
            with self.subTest(package=package):
                self.assertTrue(
                    module in contracts or module in unpublished,
                    f"{package} maps to {module}, which is neither in the probe's `contracts` "
                    "nor recorded in `unpublishedContracts`",
                )

    def test_package_of_strips_the_type(self):
        self.assertEqual(
            mod.package_of("ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode"),
            "ee.schimke.composeai.data.layoutinspector",
        )

    def test_a_new_unmapped_package_is_reported(self):
        allowlist = mod.load_allowlist()
        trimmed = dict(allowlist)
        trimmed["contractPackages"] = {
            k: v
            for k, v in allowlist["contractPackages"].items()
            if k != "ee.schimke.composeai.data.theme"
        }
        self.assertIn(
            "ee.schimke.composeai.data.theme", mod.unmapped_contract_packages(trimmed)
        )

    def test_forbidden_packages_are_left_to_their_own_rule(self):
        """Otherwise a renderer import would be reported twice, with the vaguer message winning."""
        allowlist = dict(mod.load_allowlist())
        allowlist["contractPackages"] = {}
        reported = mod.unmapped_contract_packages(allowlist)
        for package in reported:
            self.assertFalse(package.startswith(tuple(allowlist["forbiddenPackages"])))


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
