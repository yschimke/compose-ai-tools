#!/usr/bin/env python3
"""Tests for the `xr-composite` pin resolution gate.

The gate's whole value is that it fires on the one case that otherwise degrades silently — a pin
naming a release that consumers cannot download — and stays quiet otherwise. Both halves are worth
pinning: a gate that never fires is the bug it exists to prevent, and one that goes red because
api.github.com hiccuped gets disabled.

The online half is exercised against a fake `urlopen` rather than the network, so these tests are
hermetic. The real API is what the CI job calls.
"""

from __future__ import annotations

import io
import json
import unittest
import urllib.error
from unittest import mock

import check_xr_composite_pin as gate


class PinParsing(unittest.TestCase):
    def test_reads_the_pin(self):
        self.assertEqual(gate.pin_from('xr-composite = "1.0.0"\n'), "1.0.0")

    def test_reads_the_pin_among_neighbours(self):
        catalog = (
            'xr-arcore-testing = "1.0.0-beta02"\n'
            "# a comment mentioning xr-composite\n"
            'xr-composite = "2.0.1"\n'
            'xr-glimmer = "1.0.0-alpha18"\n'
        )
        self.assertEqual(gate.pin_from(catalog), "2.0.1")

    def test_absent_pin_is_none(self):
        self.assertIsNone(gate.pin_from('xr-compose = "1.0.0-alpha17"\n'))

    def test_does_not_match_a_library_coordinate(self):
        # `[libraries]` entries also start with a name; only a bare version assignment counts.
        self.assertIsNone(
            gate.pin_from('xr-composite = { module = "x:y", version.ref = "xr-composite" }\n')
        )


class VersionShape(unittest.TestCase):
    def test_releases_are_accepted(self):
        for v in ("1.0.0", "0.1.2", "10.20.30", "1.0.0-rc.1"):
            with self.subTest(v=v):
                self.assertRegex(v, gate.VERSION_RE)

    def test_snapshots_and_placeholders_are_rejected(self):
        # Each of these downloads as a 404 and degrades into a silent "no composite".
        for v in ("1.0.0-SNAPSHOT", "latest", "main", "v1.0.0", "1.0", ""):
            with self.subTest(v=v):
                self.assertNotRegex(v, gate.VERSION_RE)


class RepoAgreement(unittest.TestCase):
    def test_reads_the_constant(self):
        src = 'internal const val XR_COMPOSITE_REPO = "yschimke/compose-preview-xr"\n'
        self.assertEqual(gate.repo_from(src), "yschimke/compose-preview-xr")

    def test_absent_constant_is_none(self):
        self.assertIsNone(gate.repo_from('internal const val BUNDLE_VERSION = "1.2.3"\n'))

    def test_the_real_cli_constant_matches_what_this_gate_checks(self):
        # The load-bearing one: verifying a repository the CLI does not download from proves
        # nothing, so the gate's own copy is asserted against the source of truth.
        self.assertEqual(gate.declared_repo(), gate.XR_REPO)


class AssetNames(unittest.TestCase):
    def test_matches_the_provisioner_derivation(self):
        # Byte-for-byte what `XrCompositeProvision.assetName` builds, and what compose-preview-xr's
        # release workflow packs. A mismatch here is a 404 nobody sees.
        self.assertEqual(
            gate.asset_name("1.0.0", "linux-x86_64"), "xr-composite-linux-x86_64-1.0.0.tar.gz"
        )

    def test_complete_release_has_nothing_missing(self):
        published = {gate.asset_name("1.0.0", p) for p in gate.PLATFORMS}
        self.assertEqual(gate.missing_assets(published, "1.0.0"), [])

    def test_partial_release_names_exactly_what_is_absent(self):
        published = {gate.asset_name("1.0.0", "linux-x86_64")}
        self.assertEqual(
            gate.missing_assets(published, "1.0.0"),
            [
                "xr-composite-macos-arm64-1.0.0.tar.gz",
                "xr-composite-windows-x86_64-1.0.0.tar.gz",
            ],
        )

    def test_assets_for_another_version_do_not_count(self):
        # The Release exists and is full, but of the previous version's tarballs — which is what a
        # re-tagged or hand-edited Release looks like.
        published = {gate.asset_name("0.9.0", p) for p in gate.PLATFORMS}
        self.assertEqual(len(gate.missing_assets(published, "1.0.0")), len(gate.PLATFORMS))

    def test_extra_assets_are_ignored(self):
        published = {gate.asset_name("1.0.0", p) for p in gate.PLATFORMS} | {"checksums.txt"}
        self.assertEqual(gate.missing_assets(published, "1.0.0"), [])


def _http_error(code: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError("https://api.github.com", code, "boom", {}, None)


def _response(payload: dict):
    ctx = mock.MagicMock()
    ctx.__enter__.return_value = io.BytesIO(json.dumps(payload).encode())
    ctx.__exit__.return_value = False
    return ctx


class FetchReleaseAssets(unittest.TestCase):
    def test_returns_asset_names(self):
        payload = {"assets": [{"name": "a.tar.gz"}, {"name": "b.tar.gz"}]}
        with mock.patch.object(gate.urllib.request, "urlopen", return_value=_response(payload)):
            self.assertEqual(gate.fetch_release_assets("o/r", "1.0.0"), {"a.tar.gz", "b.tar.gz"})

    def test_a_release_with_no_assets_is_an_empty_set_not_none(self):
        # Distinct from "no such Release", and reported differently: one says cut the release, the
        # other says re-attach the tarballs.
        with mock.patch.object(gate.urllib.request, "urlopen", return_value=_response({})):
            self.assertEqual(gate.fetch_release_assets("o/r", "1.0.0"), set())

    def test_404_is_a_verdict_not_an_error(self):
        with mock.patch.object(gate.urllib.request, "urlopen", side_effect=_http_error(404)):
            self.assertIsNone(gate.fetch_release_assets("o/r", "1.0.0"))

    def test_rate_limit_and_server_errors_are_unanswered(self):
        for code in (403, 429, 500, 502):
            with self.subTest(code=code):
                with mock.patch.object(
                    gate.urllib.request, "urlopen", side_effect=_http_error(code)
                ):
                    with self.assertRaises(gate.Unanswered):
                        gate.fetch_release_assets("o/r", "1.0.0")

    def test_transport_failure_is_unanswered(self):
        with mock.patch.object(
            gate.urllib.request, "urlopen", side_effect=urllib.error.URLError("dns")
        ):
            with self.assertRaises(gate.Unanswered):
                gate.fetch_release_assets("o/r", "1.0.0")

    def test_a_token_is_sent_when_present_and_omitted_when_not(self):
        seen = []

        def capture(req, timeout=None):
            seen.append(dict(req.header_items()))
            return _response({"assets": []})

        with mock.patch.object(gate.urllib.request, "urlopen", side_effect=capture):
            gate.fetch_release_assets("o/r", "1.0.0", token="t0ken")
            gate.fetch_release_assets("o/r", "1.0.0", token=None)
        self.assertEqual(seen[0].get("Authorization"), "Bearer t0ken")
        self.assertNotIn("Authorization", seen[1])


class EndToEnd(unittest.TestCase):
    """`main()` against the repository's real catalog and Version.kt, with the API faked."""

    def _run(self, argv, **patch):
        with mock.patch.object(gate.sys, "argv", ["check_xr_composite_pin.py", *argv]):
            with mock.patch.object(gate, "fetch_release_assets", **patch):
                return gate.main()

    def test_the_committed_pin_passes_offline(self):
        with mock.patch.object(gate.sys, "argv", ["x", "--offline"]):
            self.assertEqual(gate.main(), 0)

    def test_a_complete_release_passes(self):
        pin = gate.current_pin()
        assets = {gate.asset_name(pin, p) for p in gate.PLATFORMS}
        self.assertEqual(self._run([], return_value=assets), 0)

    def test_a_missing_release_fails(self):
        self.assertEqual(self._run([], return_value=None), 1)

    def test_a_partial_release_fails(self):
        pin = gate.current_pin()
        self.assertEqual(
            self._run([], return_value={gate.asset_name(pin, "linux-x86_64")}), 1
        )

    def test_an_unanswered_api_passes(self):
        # The deliberate soft spot: no answer is not a failed answer. compose-preview-xr's release
        # workflow refuses to finish a Release missing a tarball, so this is a second line of
        # defence rather than the only one.
        self.assertEqual(self._run([], side_effect=gate.Unanswered("offline")), 0)

    def test_a_bad_pin_fails_before_any_network_call(self):
        with mock.patch.object(gate, "current_pin", return_value="1.0.0-SNAPSHOT"):
            with mock.patch.object(gate, "fetch_release_assets") as fetch:
                with mock.patch.object(gate.sys, "argv", ["x"]):
                    self.assertEqual(gate.main(), 1)
        fetch.assert_not_called()

    def test_a_repo_mismatch_fails_before_any_network_call(self):
        with mock.patch.object(gate, "declared_repo", return_value="someone/else"):
            with mock.patch.object(gate, "fetch_release_assets") as fetch:
                with mock.patch.object(gate.sys, "argv", ["x"]):
                    self.assertEqual(gate.main(), 1)
        fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
