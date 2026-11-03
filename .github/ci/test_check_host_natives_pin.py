#!/usr/bin/env python3
"""Tests for the `host-natives` pin gate, hermetic: the releases API is faked."""

from __future__ import annotations

import io
import json
import re
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import check_host_natives_pin as gate

REPO = Path(__file__).resolve().parents[2]


def _response(body: dict) -> mock.MagicMock:
    resp = mock.MagicMock()
    resp.__enter__.return_value = io.BytesIO(json.dumps(body).encode())
    return resp


class Pin(unittest.TestCase):
    def test_reads_the_pin_and_not_a_comment(self):
        catalog = '# host-natives = "9.9.9" in prose\nxr-composite = "2.0.2"\nhost-natives = "1.0.0"\n'
        self.assertEqual(gate.pin_from(catalog), "1.0.0")
        self.assertIsNone(gate.pin_from('xr-composite = "2.0.2"\n'))

    def test_the_real_catalog_pins_a_well_formed_version(self):
        self.assertRegex(gate.pin_from((REPO / gate.CATALOG).read_text()), gate.VERSION_RE)

    def test_the_cli_downloads_from_the_repository_this_gate_checks(self):
        version_kt = (REPO / "cli/src/main/kotlin/ee/schimke/composeai/cli/Version.kt").read_text()
        m = re.search(r'internal const val REPO = "([^"]+)"', version_kt)
        self.assertEqual(m.group(1), gate.RELEASE_REPO)


class Assets(unittest.TestCase):
    def test_names_match_the_build_script(self):
        self.assertEqual(
            gate.asset_name("1.0.0", "macos-universal"), "host-natives-macos-universal-1.0.0.tar.gz"
        )
        build = (REPO / "host-natives/build.sh").read_text()
        self.assertIn('archive="$build/dist/host-natives-$platform-$version.tar.gz"', build)

    def test_missing_lists_absent_tarballs_and_sums(self):
        published = {"host-natives-linux-x86_64-1.0.0.tar.gz"}
        self.assertEqual(
            gate.missing_assets(published, "1.0.0"),
            ["host-natives-macos-universal-1.0.0.tar.gz", "SHA256SUMS"],
        )


class Fetch(unittest.TestCase):
    def test_a_404_is_no_release(self):
        err = urllib.error.HTTPError("u", 404, "Not Found", {}, None)
        with mock.patch("urllib.request.urlopen", side_effect=err):
            self.assertIsNone(gate.fetch_release_assets("1.0.0"))

    def test_a_rate_limit_is_unanswered(self):
        err = urllib.error.HTTPError("u", 403, "rate limited", {}, None)
        with mock.patch("urllib.request.urlopen", side_effect=err):
            with self.assertRaises(gate.Unanswered):
                gate.fetch_release_assets("1.0.0")

    def test_asset_names_are_returned(self):
        body = {"assets": [{"name": "SHA256SUMS"}, {"name": "x.tar.gz"}]}
        with mock.patch("urllib.request.urlopen", return_value=_response(body)):
            self.assertEqual(gate.fetch_release_assets("1.0.0"), {"SHA256SUMS", "x.tar.gz"})


if __name__ == "__main__":
    unittest.main()
