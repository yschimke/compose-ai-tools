#!/usr/bin/env python3
"""Tests for the install action's version resolver.

Covers the offline modes only — `latest` hits the GitHub releases API and is
exercised end-to-end by `install-action-test.yml` instead.

The `pin` mode is the cross-entrypoint one (issue #3738): its precedence must
match the CLI's `VersionPinTest` and the extension's `versionPin.test.ts`, so
that one value in a consumer's repo means the same thing in all three places.
"""

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "resolve_version", Path(__file__).with_name("resolve-version.py")
)
assert _SPEC and _SPEC.loader
resolve_version = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(resolve_version)


class ResolverTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._cwd = os.getcwd()
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        os.chdir(self.root)
        for key in (
            "INPUT_VERSION",
            "CATALOG_PATH",
            "CATALOG_KEY",
            "PROPERTIES_PATH",
        ):
            os.environ.pop(key, None)

    def tearDown(self) -> None:
        os.chdir(self._cwd)
        self._tmp.cleanup()

    def write_properties(self, text: str) -> None:
        (self.root / "gradle.properties").write_text(text, encoding="utf-8")

    def write_catalog(self, text: str) -> None:
        (self.root / "gradle").mkdir(exist_ok=True)
        (self.root / "gradle" / "libs.versions.toml").write_text(
            text, encoding="utf-8"
        )


class PropertiesPinTests(ResolverTestCase):
    def test_reads_the_pin(self) -> None:
        self.write_properties("composePreview.version=1.2.3\n")
        self.assertEqual(resolve_version.properties_version(), "1.2.3")

    def test_tolerates_whitespace_colon_form_and_leading_v(self) -> None:
        self.write_properties("composePreview.version : v1.2.3   \n")
        self.assertEqual(resolve_version.properties_version(), "1.2.3")

    def test_ignores_a_commented_out_pin(self) -> None:
        self.write_properties("# composePreview.version=9.9.9\n")
        self.assertIsNone(resolve_version.properties_version())

    def test_empty_value_is_absent(self) -> None:
        self.write_properties("composePreview.version=\n")
        self.assertIsNone(resolve_version.properties_version())

    def test_missing_file_is_absent(self) -> None:
        self.assertIsNone(resolve_version.properties_version())

    def test_bare_whitespace_separator(self) -> None:
        # `key value` is a legal properties assignment that the CLI's
        # Properties.load reads; a parser that missed it would report an
        # actually-pinned project as unpinned — the skew this feature prevents.
        self.write_properties("composePreview.version 1.2.3\n")
        self.assertEqual(resolve_version.properties_version(), "1.2.3")

    def test_duplicate_assignments_take_the_last(self) -> None:
        # Properties.load resolves the last assignment; so must we.
        self.write_properties(
            "composePreview.version=1.0.0\ncomposePreview.version=2.0.0\n"
        )
        self.assertEqual(resolve_version.properties_version(), "2.0.0")

    def test_a_similarly_named_key_is_not_the_pin(self) -> None:
        self.write_properties("composePreview.versionCode=42\n")
        self.assertIsNone(resolve_version.properties_version())

    def test_other_keys_are_untouched(self) -> None:
        self.write_properties(
            "org.gradle.caching=true\ncomposePreview.variant=demoDebug\n"
        )
        self.assertIsNone(resolve_version.properties_version())


class PinPrecedenceTests(ResolverTestCase):
    def test_properties_wins_over_catalog(self) -> None:
        self.write_properties("composePreview.version=2.0.0\n")
        self.write_catalog('[versions]\ncomposePreviewCli = "1.0.5"\n')
        self.assertEqual(resolve_version.pin_version(), "2.0.0")

    def test_falls_back_to_catalog(self) -> None:
        self.write_catalog('[versions]\ncomposePreviewCli = "1.0.5"\n')
        self.assertEqual(resolve_version.pin_version(), "1.0.5")

    def test_catalog_lookup_is_scoped_to_versions_table(self) -> None:
        self.write_catalog(
            '[versions]\nagp = "9.1.1"\n\n[libraries]\ncomposePreviewCli = "nope"\n'
        )
        with self.assertRaises(SystemExit):
            resolve_version.pin_version()

    def test_unpinned_project_fails_rather_than_installing_latest(self) -> None:
        # A silent fallback to `latest` is precisely the skew (#1920) the pin
        # exists to prevent — the workflow asked for the project's pin.
        with self.assertRaises(SystemExit):
            resolve_version.pin_version()

    def test_a_malformed_catalog_does_not_mask_a_properties_pin(self) -> None:
        self.write_properties("composePreview.version=1.2.3\n")
        self.write_catalog("this is not { valid TOML [[[")
        self.assertEqual(resolve_version.pin_version(), "1.2.3")


class MainDispatchTests(ResolverTestCase):
    def test_literal_version_strips_leading_v(self) -> None:
        os.environ["INPUT_VERSION"] = "v1.2.3"
        with _capture() as out:
            resolve_version.main()
        self.assertEqual(out.value.strip(), "1.2.3")

    def test_pin_mode_reads_the_project_pin(self) -> None:
        self.write_properties("composePreview.version=1.2.3\n")
        os.environ["INPUT_VERSION"] = "pin"
        with _capture() as out:
            resolve_version.main()
        self.assertEqual(out.value.strip(), "1.2.3")

    def test_catalog_mode_still_requires_the_catalog(self) -> None:
        # `catalog` stays the narrow "read exactly this key" mode: a
        # gradle.properties pin must not satisfy it.
        self.write_properties("composePreview.version=1.2.3\n")
        os.environ["INPUT_VERSION"] = "catalog"
        with self.assertRaises(SystemExit):
            resolve_version.main()


class _capture:
    """Minimal stdout capture — the scripts print their answer."""

    def __init__(self) -> None:
        self.value = ""

    def __enter__(self) -> "_capture":
        import io
        import sys

        self._buf = io.StringIO()
        self._old = sys.stdout
        sys.stdout = self._buf
        return self

    def __exit__(self, *exc: object) -> None:
        import sys

        sys.stdout = self._old
        self.value = self._buf.getvalue()


if __name__ == "__main__":
    unittest.main(verbosity=2)
