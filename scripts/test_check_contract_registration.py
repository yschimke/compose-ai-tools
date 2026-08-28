#!/usr/bin/env python3
"""Tests for check-contract-registration.py.

The interesting cases are the two omissions that actually happened — a contract absent
from `ci-paths.json` (#4634, #4656) — because those are the ones that fail silently:
CI stays green and the probe simply never runs for the new module.

The `contractPackages` cases are here for the same reason. The seam checker only asks
whether a package serve *already imports* is covered, so a missing or wrong entry for a
module serve has not reached yet is invisible to it; and it never reads the values, so an
entry naming an artifact nothing publishes is invisible to everything.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import shutil
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location(
    "check_contract_registration", REPO / "scripts" / "check-contract-registration.py"
)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class Covered(unittest.TestCase):
    def test_exact_glob_covers_its_directory(self):
        self.assertTrue(mod.covered("common/image-crop", ["common/image-crop/**"]))

    def test_broader_glob_covers_a_nested_project(self):
        # `render-session/**` legitimately covers api/ and subprocess/; demanding one entry
        # per project would fail a correct file.
        self.assertTrue(mod.covered("render-session/api", ["render-session/**"]))
        self.assertTrue(mod.covered("render-session/subprocess", ["render-session/**"]))

    def test_a_sibling_prefix_does_not_count(self):
        # `common/io` must not be satisfied by `common/image-crop/**`.
        self.assertFalse(mod.covered("common/io", ["common/image-crop/**"]))

    def test_a_partial_name_does_not_count(self):
        # `bundle/format2` is not covered by `bundle/format/**`.
        self.assertFalse(mod.covered("bundle/format2", ["bundle/format/**"]))

    def test_a_selective_glob_is_not_whole_module_coverage(self):
        # Same directory prefix, but a change to the module's build file or a Java source under
        # it would not select the group — and other groups classify those paths, so the
        # classifier does not fail open. A partial glob reads as coverage without being it.
        self.assertFalse(mod.covered("daemon/core", ["daemon/core/**/*.kt"]))
        self.assertTrue(mod.covered("daemon/core", ["daemon/core/**"]))

    def test_no_globs_covers_nothing(self):
        self.assertFalse(mod.covered("common/io", []))


class ModulePackages(unittest.TestCase):
    def test_java_sources_are_scanned(self):
        # No contract module has Java sources today; the point is that adding one cannot open a
        # hole. A JVM consumer imports a Java declaration exactly like a Kotlin one.
        tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        main = tmp / "m" / "src" / "main" / "java" / "ee" / "x"
        main.mkdir(parents=True)
        (main / "T.java").write_text("package ee.x;\n\npublic final class T {}\n")
        self.assertEqual(mod.module_packages(tmp, "m"), {"ee.x"})


class RealTree(unittest.TestCase):
    def test_repo_is_registered(self):
        self.assertEqual(mod.check(REPO), 0)


class Omissions(unittest.TestCase):
    """Each case removes one registration from a copy of the repo and expects a failure."""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.root = self.tmp / "repo"
        self.root.mkdir()
        for rel in (
            mod.PROBE,
            mod.SHELL,
            mod.CI_PATHS,
            mod.SETTINGS,
            mod.ALLOWLIST,
        ):
            dst = self.root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(REPO / rel, dst)
        # the build files the checker reads artifact ids out of
        for path in mod.shell_projects(REPO):
            d = mod.project_dir(REPO, path)
            src = REPO / d / "build.gradle.kts"
            if src.is_file():
                dst = self.root / d / "build.gradle.kts"
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(src, dst)
            # One stub per declared package instead of the real sources: the checker only
            # reads `package` lines, and copying every module's main source set to run a
            # regex over it would make the fixture cost more than the gate it tests.
            # Every package, not just the roots — the key-ownership check resolves nested
            # entries like `…daemon.bta` against the module that declares them.
            for i, package in enumerate(sorted(mod.module_packages(REPO, d))):
                stub = self.root / d / "src" / "main" / "kotlin" / f"Stub{i}.kt"
                stub.parent.mkdir(parents=True, exist_ok=True)
                stub.write_text(f"package {package}\n")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_baseline_copy_passes(self):
        # Guards the fixture itself: if this failed, the cases below would pass for the
        # wrong reason.
        self.assertEqual(mod.check(self.root), 0)

    def test_missing_from_ci_paths_fails(self):
        p = self.root / mod.CI_PATHS
        data = json.loads(p.read_text())
        group = data["groups"][mod.CI_GROUP]
        data["groups"][mod.CI_GROUP] = [g for g in group if g != "common/image-crop/**"]
        p.write_text(json.dumps(data, indent=2) + "\n")
        self.assertEqual(mod.check(self.root), 1)

    def test_missing_from_probe_contracts_fails(self):
        p = self.root / mod.PROBE
        p.write_text(p.read_text().replace('    "common-image-crop",\n', "", 1))
        self.assertEqual(mod.check(self.root), 1)

    def test_missing_from_publish_list_fails(self):
        p = self.root / mod.SHELL
        p.write_text(p.read_text().replace('  ":common-image-crop"\n', "", 1))
        self.assertEqual(mod.check(self.root), 1)

    def _rewrite_allowlist(self, edit):
        p = self.root / mod.ALLOWLIST
        data = json.loads(p.read_text())
        edit(data["contractPackages"])
        p.write_text(json.dumps(data, indent=2) + "\n")

    def _failure(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = mod.check(self.root)
        self.assertEqual(code, 1)
        return buf.getvalue()

    def test_missing_from_contract_packages_fails(self):
        # The case the seam checker cannot see: serve does not import this package today, so
        # nothing else would notice the mapping had gone.
        self._rewrite_allowlist(lambda m: m.pop("ee.schimke.composeai.data.pseudolocale"))
        self.assertIn("has no entry for 'ee.schimke.composeai.data.pseudolocale'", self._failure())

    def test_contract_package_naming_an_unpublished_artifact_fails(self):
        self._rewrite_allowlist(
            lambda m: m.__setitem__("ee.schimke.composeai.imagecrop", "common-image-kropp")
        )
        self.assertIn("which no project in", self._failure())

    def test_a_nested_package_mapped_to_the_wrong_contract_fails(self):
        # The per-module check below only reaches a module's *root* packages, so a nested entry
        # is invisible to it: `daemon-core` reduces to `…daemon`, and the value check only asks
        # whether `daemon-client` exists at all. This is the key-ownership check instead.
        self._rewrite_allowlist(
            lambda m: m.__setitem__("ee.schimke.composeai.daemon.bta", "daemon-client")
        )
        self.assertIn(
            "maps 'ee.schimke.composeai.daemon.bta' to 'daemon-client', which declares no such "
            "package",
            self._failure(),
        )

    def test_contract_package_naming_the_wrong_contract_fails(self):
        # A parent entry swallowing a child module's package — `…daemon.client` reading as
        # `…daemon` — is coverage without correctness: the seam checker is satisfied while the
        # register credits the imports to the wrong artifact.
        self._rewrite_allowlist(lambda m: m.pop("ee.schimke.composeai.daemon.client"))
        self.assertIn(
            "resolves 'ee.schimke.composeai.daemon.client' (:daemon-client) to 'daemon-core'",
            self._failure(),
        )


if __name__ == "__main__":
    unittest.main()
