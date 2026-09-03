#!/usr/bin/env python3
"""Tests for the install action's version resolver.

Covers the offline modes only — `latest` hits the GitHub releases API and is
exercised end-to-end by `install-action-test.yml` instead.

The `pin` mode is the cross-entrypoint one (issue #3738): its precedence must
match the CLI's `VersionPinTest` and the extension's `versionPin.test.ts`, so
that one value in a consumer's repo means the same thing in all three places.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
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


class _FakeResponse:
    """Minimal stand-in for `urlopen`'s result: `json.load`-able, with `Link` headers."""

    def __init__(self, payload: object, link: str | None = None) -> None:
        self._body = json.dumps(payload).encode()
        self.headers = {"Link": link} if link else {}

    def read(self, *args: object) -> bytes:
        body, self._body = self._body, b""
        return body

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc: object) -> None:
        return None


def _release(tag: str, *, cli: bool = True, maven_ready: bool = True) -> dict:
    ver = tag.lstrip("v")
    assets = []
    if cli:
        assets.append(
            {"name": f"compose-preview-{ver}.tar.gz", "state": "uploaded", "size": 42}
        )
    if maven_ready:
        assets.append(
            {
                "name": f"compose-preview-maven-ready-{ver}.json",
                "state": "uploaded",
                "size": 7,
            }
        )
    return {"tag_name": tag, "draft": False, "prerelease": False, "assets": assets}


class LatestReleaseTests(ResolverTestCase):
    """`latest` means *latest usable*, not latest released (issue #5051).

    A release's CLI tarball is downloadable minutes before its Gradle plugin is
    resolvable, and every render dispatched in that window died with an error
    naming the consumer's project. `maven-readiness.yml` attaches a marker to the
    release once it has actually resolved the plugin from Central, so `latest`
    gates on that marker.
    """

    def _resolve(self, releases: list[dict]) -> tuple[str, str]:
        calls: list[str] = []

        def fake_urlopen(req: object, timeout: int = 0) -> _FakeResponse:
            calls.append(getattr(req, "full_url", ""))
            return _FakeResponse(releases)

        original = resolve_version.urllib.request.urlopen
        resolve_version.urllib.request.urlopen = fake_urlopen  # type: ignore[assignment]
        stderr = io.StringIO()
        try:
            with contextlib.redirect_stderr(stderr):
                version = resolve_version.latest_version()
        finally:
            resolve_version.urllib.request.urlopen = original  # type: ignore[assignment]
        return version, stderr.getvalue()

    def test_skips_a_release_whose_plugin_is_not_published_yet(self) -> None:
        version, err = self._resolve(
            [_release("v1.68.0", maven_ready=False), _release("v1.67.0")]
        )
        self.assertEqual(version, "1.67.0")
        self.assertIn("v1.68.0", err)
        self.assertIn("readiness marker", err)

    def test_takes_the_newest_ready_release_silently(self) -> None:
        version, err = self._resolve([_release("v1.68.0"), _release("v1.67.0")])
        self.assertEqual(version, "1.68.0")
        self.assertEqual(err, "")

    def test_still_skips_a_release_with_no_cli_tarball(self) -> None:
        version, err = self._resolve(
            [_release("v1.68.0", cli=False), _release("v1.67.0")]
        )
        self.assertEqual(version, "1.67.0")
        self.assertIn("CLI tarball not yet uploaded", err)

    def test_falls_back_when_no_release_carries_a_marker(self) -> None:
        # Pre-marker releases, or a readiness job that has been failing across all of them:
        # refusing to install anything would be worse than the behaviour this gate replaced.
        version, err = self._resolve(
            [_release("v1.68.0", maven_ready=False), _release("v1.67.0", maven_ready=False)]
        )
        self.assertEqual(version, "1.68.0")
        self.assertIn("no release", err)
        self.assertIn("Maven-readiness marker", err)


class ReleaseAssetPredicateTests(unittest.TestCase):
    def test_a_partially_uploaded_asset_does_not_count(self) -> None:
        release = {
            "tag_name": "v1.68.0",
            "assets": [
                {"name": "compose-preview-1.68.0.tar.gz", "state": "open", "size": 0},
                {
                    "name": "compose-preview-maven-ready-1.68.0.json",
                    "state": "open",
                    "size": 0,
                },
            ],
        }
        self.assertFalse(resolve_version._release_is_complete(release))
        self.assertFalse(resolve_version._release_is_maven_ready(release))

    def test_a_marker_for_another_version_does_not_count(self) -> None:
        release = _release("v1.68.0", maven_ready=False)
        release["assets"].append(
            {
                "name": "compose-preview-maven-ready-1.67.0.json",
                "state": "uploaded",
                "size": 7,
            }
        )
        self.assertTrue(resolve_version._release_is_complete(release))
        self.assertFalse(resolve_version._release_is_maven_ready(release))


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
