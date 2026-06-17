#!/usr/bin/env python3
"""Tests for check-skew.py (issue #1920 CLI/plugin skew guardrail).

Pure stdlib (unittest) — same shape as the lib/test_*.py suites. Run:

    python3 .github/actions/apply/test_check_skew.py -v
"""

from __future__ import annotations

import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SCRIPT = _HERE / "check-skew.py"
_SPEC = importlib.util.spec_from_file_location("check_skew", _HERE / "check-skew.py")
cs = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(cs)


class ParseReleaseTests(unittest.TestCase):
    def test_clean_release(self):
        self.assertEqual(cs.parse_release("0.15.9"), (0, 15, 9))
        self.assertEqual(cs.parse_release("v1.2.3"), (1, 2, 3))

    def test_ordering(self):
        self.assertLess(cs.parse_release("0.15.8"), cs.parse_release("0.15.9"))
        self.assertLess(cs.parse_release("0.9.0"), cs.parse_release("0.15.0"))
        self.assertLess(cs.parse_release("0.15.9"), cs.parse_release("1.0.0"))

    def test_snapshot_and_prerelease_skip(self):
        self.assertIsNone(cs.parse_release("0.15.9-SNAPSHOT"))
        self.assertIsNone(cs.parse_release("0.16.0-rc1"))
        self.assertIsNone(cs.parse_release("0.8.13-feature-x-abc123-SNAPSHOT"))

    def test_malformed_skip(self):
        self.assertIsNone(cs.parse_release(""))
        self.assertIsNone(cs.parse_release("latest"))
        self.assertIsNone(cs.parse_release("0.15"))


class CatalogDetectionTests(unittest.TestCase):
    def _catalog(self, body: str) -> str:
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        p = os.path.join(d, "libs.versions.toml")
        Path(p).write_text(body, encoding="utf-8")
        return p

    def test_version_ref(self):
        cat = self._catalog(
            """
[versions]
composePreviewPlugin = "0.15.8"

[plugins]
composePreview = { id = "ee.schimke.composeai.preview", version.ref = "composePreviewPlugin" }
"""
        )
        self.assertEqual(cs.plugin_version_from_catalog(cat), "0.15.8")

    def test_inline_version(self):
        cat = self._catalog(
            """
[plugins]
composePreview = { id = "ee.schimke.composeai.preview", version = "0.15.7" }
"""
        )
        self.assertEqual(cs.plugin_version_from_catalog(cat), "0.15.7")

    def test_string_form(self):
        cat = self._catalog(
            """
[plugins]
composePreview = "ee.schimke.composeai.preview:0.15.6"
"""
        )
        self.assertEqual(cs.plugin_version_from_catalog(cat), "0.15.6")

    def test_unrelated_plugins_ignored(self):
        cat = self._catalog(
            """
[versions]
agp = "8.13.0"

[plugins]
android = { id = "com.android.application", version.ref = "agp" }
"""
        )
        self.assertIsNone(cs.plugin_version_from_catalog(cat))

    def test_missing_file(self):
        self.assertIsNone(cs.plugin_version_from_catalog("/nonexistent/libs.versions.toml"))


class BuildScriptDetectionTests(unittest.TestCase):
    def _workspace(self) -> str:
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        return d

    def test_literal_kts(self):
        ws = self._workspace()
        mod = os.path.join(ws, "app")
        os.makedirs(mod)
        Path(os.path.join(mod, "build.gradle.kts")).write_text(
            'plugins {\n  id("ee.schimke.composeai.preview") version "0.15.8"\n}\n',
            encoding="utf-8",
        )
        self.assertEqual(cs.plugin_version_from_build_scripts(ws), "0.15.8")

    def test_groovy_single_quotes(self):
        ws = self._workspace()
        Path(os.path.join(ws, "build.gradle")).write_text(
            "plugins {\n  id 'ee.schimke.composeai.preview' version '0.14.0'\n}\n",
            encoding="utf-8",
        )
        self.assertEqual(cs.plugin_version_from_build_scripts(ws), "0.14.0")

    def test_commented_out_declaration_ignored(self):
        ws = self._workspace()
        Path(os.path.join(ws, "build.gradle.kts")).write_text(
            '// id("ee.schimke.composeai.preview") version "0.1.0"\nplugins {}\n',
            encoding="utf-8",
        )
        self.assertIsNone(cs.plugin_version_from_build_scripts(ws))

    def test_build_dir_skipped(self):
        ws = self._workspace()
        buildout = os.path.join(ws, "build")
        os.makedirs(buildout)
        Path(os.path.join(buildout, "build.gradle.kts")).write_text(
            'id("ee.schimke.composeai.preview") version "9.9.9"\n', encoding="utf-8"
        )
        self.assertIsNone(cs.plugin_version_from_build_scripts(ws))


class DetectPluginCliTests(unittest.TestCase):
    """The `detect-plugin` subcommand backs `cli-version: auto` resolution.

    Drives the script the same way action.yml does — `python3 check-skew.py
    detect-plugin` with WORKSPACE/CATALOG_PATH in the env — so the contract the
    action's bash relies on (bare version on stdout, empty + rc 0 when nothing
    is pinned) is locked in.
    """

    def _detect(self, workspace: str) -> tuple[int, str]:
        env = dict(os.environ)
        env["WORKSPACE"] = workspace
        proc = subprocess.run(
            [sys.executable, str(_SCRIPT), "detect-plugin"],
            capture_output=True,
            text=True,
            env=env,
        )
        return proc.returncode, proc.stdout.strip()

    def _ws_with_catalog(self, body: str) -> str:
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        os.makedirs(os.path.join(d, "gradle"))
        Path(os.path.join(d, "gradle", "libs.versions.toml")).write_text(
            body, encoding="utf-8"
        )
        return d

    def test_prints_catalog_plugin_version(self):
        ws = self._ws_with_catalog(
            '[plugins]\ncomposePreview = "ee.schimke.composeai.preview:0.15.8"\n'
        )
        rc, out = self._detect(ws)
        self.assertEqual(rc, 0)
        self.assertEqual(out, "0.15.8")

    def test_prints_literal_build_script_version(self):
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        Path(os.path.join(d, "build.gradle.kts")).write_text(
            'plugins {\n  id("ee.schimke.composeai.preview") version "0.14.0"\n}\n',
            encoding="utf-8",
        )
        rc, out = self._detect(d)
        self.assertEqual(rc, 0)
        self.assertEqual(out, "0.14.0")

    def test_no_pin_prints_nothing(self):
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        rc, out = self._detect(d)
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")


class MainTests(unittest.TestCase):
    def _run(self, env: dict[str, str]) -> tuple[int, str]:
        saved = dict(os.environ)
        os.environ.clear()
        os.environ.update(env)
        buf = io.StringIO()
        try:
            with redirect_stdout(buf):
                rc = cs.main()
        finally:
            os.environ.clear()
            os.environ.update(saved)
        return rc, buf.getvalue()

    def _ws_with_plugin(self, version: str) -> str:
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        os.makedirs(os.path.join(d, "gradle"))
        Path(os.path.join(d, "gradle", "libs.versions.toml")).write_text(
            f'[plugins]\ncomposePreview = "ee.schimke.composeai.preview:{version}"\n',
            encoding="utf-8",
        )
        return d

    def test_cli_newer_fails(self):
        ws = self._ws_with_plugin("0.15.8")
        rc, out = self._run(
            {"RESOLVED_CLI_VERSION": "0.15.9", "WORKSPACE": ws, "SKEW_MODE": "fail"}
        )
        self.assertEqual(rc, 1)
        self.assertIn("::error::", out)
        self.assertIn("0.15.9", out)
        self.assertIn("0.15.8", out)

    def test_cli_newer_warn_mode(self):
        ws = self._ws_with_plugin("0.15.8")
        rc, out = self._run(
            {"RESOLVED_CLI_VERSION": "0.15.9", "WORKSPACE": ws, "SKEW_MODE": "warn"}
        )
        self.assertEqual(rc, 0)
        self.assertIn("::warning::", out)

    def test_cli_newer_off_mode(self):
        ws = self._ws_with_plugin("0.15.8")
        rc, out = self._run(
            {"RESOLVED_CLI_VERSION": "0.15.9", "WORKSPACE": ws, "SKEW_MODE": "off"}
        )
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_aligned_ok(self):
        ws = self._ws_with_plugin("0.15.9")
        rc, out = self._run({"RESOLVED_CLI_VERSION": "0.15.9", "WORKSPACE": ws})
        self.assertEqual(rc, 0)
        self.assertNotIn("::error::", out)

    def test_cli_older_ok(self):
        ws = self._ws_with_plugin("0.16.0")
        rc, out = self._run({"RESOLVED_CLI_VERSION": "0.15.9", "WORKSPACE": ws})
        self.assertEqual(rc, 0)
        self.assertNotIn("::error::", out)

    def test_no_plugin_detected_silent(self):
        d = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(d, ignore_errors=True))
        rc, out = self._run({"RESOLVED_CLI_VERSION": "0.15.9", "WORKSPACE": d})
        self.assertEqual(rc, 0)
        self.assertNotIn("::error::", out)

    def test_snapshot_cli_skips(self):
        ws = self._ws_with_plugin("0.15.8")
        rc, out = self._run(
            {"RESOLVED_CLI_VERSION": "0.15.9-SNAPSHOT", "WORKSPACE": ws}
        )
        self.assertEqual(rc, 0)
        self.assertNotIn("::error::", out)

    def test_empty_cli_version_skips(self):
        ws = self._ws_with_plugin("0.15.8")
        rc, out = self._run({"RESOLVED_CLI_VERSION": "", "WORKSPACE": ws})
        self.assertEqual(rc, 0)
        self.assertNotIn("::error::", out)


if __name__ == "__main__":
    unittest.main()
