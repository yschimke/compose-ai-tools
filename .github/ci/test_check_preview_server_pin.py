#!/usr/bin/env python3
"""Tests for the preview-server pin resolution gate.

The gate's value is that it fires on the one case that broke `compose-preview serve` for every
documented install (#5183) — a pin whose release carries no server distribution — and stays quiet
otherwise. Both halves are worth pinning: a gate that never fires is the bug it exists to prevent,
and one that goes red because api.github.com hiccuped gets disabled.

Hermetic: the online half runs against a fake `urlopen`. The real API is what the CI job calls.
"""

from __future__ import annotations

import io
import json
import unittest
import urllib.error
from unittest import mock

import check_preview_server_pin as gate


class PinParsing(unittest.TestCase):
    def test_reads_the_pin(self):
        self.assertEqual(gate.pin_from('composeai-preview-server-dist = "3.0.0"\n'), "3.0.0")

    def test_reads_the_pin_among_neighbours(self):
        catalog = (
            'composeai-contracts = "2.5.0"\n'
            "# a comment mentioning composeai-preview-server-dist\n"
            'composeai-preview-server-dist = "3.1.0"\n'
            'rcplayers = "1.57.0"\n'
        )
        self.assertEqual(gate.pin_from(catalog), "3.1.0")

    def test_absent_pin_is_none(self):
        self.assertIsNone(gate.pin_from('composeai-contracts = "2.5.0"\n'))

    def test_does_not_match_the_library_pin(self):
        # The whole point of the split: `composeai-preview-serve` names the published jar `:cli`
        # compiles against, and sits a few lines above this pin in the same catalog. Gating that
        # version against GitHub Release assets would check the wrong thing, and would fail the
        # moment a server-only release moved the two apart - which is the case the split exists
        # to allow.
        self.assertIsNone(gate.pin_from('composeai-preview-serve = "3.2.0"\n'))

    def test_does_not_match_the_library_coordinate(self):
        # The `[libraries]` entry of the same name is not a version assignment.
        self.assertIsNone(
            gate.pin_from(
                "composeai-preview-serve = { module = "
                '"ee.schimke.composeai:compose-preview-serve", '
                'version.ref = "composeai-preview-serve" }\n'
            )
        )

    def test_reads_the_real_catalog(self):
        # Guards the split at the level that actually matters: whatever the regex says, the pin
        # this gate reads must exist in the committed catalog. A rename on either side that
        # nobody propagated here makes `current_pin()` raise rather than silently gate nothing.
        self.assertRegex(gate.current_pin(), gate.VERSION_RE)


class VersionShape(unittest.TestCase):
    def test_releases_are_accepted(self):
        for v in ("3.0.0", "0.1.2", "10.20.30", "3.1.0-rc.1"):
            with self.subTest(v=v):
                self.assertRegex(v, gate.VERSION_RE)

    def test_snapshots_and_placeholders_are_rejected(self):
        # Each of these downloads as a 404, which is a `serve` that cannot start.
        for v in ("3.0.0-SNAPSHOT", "latest", "main", "v3.0.0", "3.0", ""):
            with self.subTest(v=v):
                self.assertNotRegex(v, gate.VERSION_RE)


class RepoAgreement(unittest.TestCase):
    def test_reads_the_constant(self):
        src = 'internal const val PREVIEW_SERVER_REPO = "yschimke/compose-preview-server"\n'
        self.assertEqual(gate.repo_from(src), "yschimke/compose-preview-server")

    def test_absent_constant_is_none(self):
        self.assertIsNone(gate.repo_from('internal const val BUNDLE_VERSION = "1.2.3"\n'))

    def test_the_real_cli_constant_matches_what_this_gate_checks(self):
        # The load-bearing one: verifying a repository the CLI does not download from proves
        # nothing, so the gate's own copy is asserted against the source of truth.
        self.assertEqual(gate.declared_repo(), gate.SERVER_REPO)


class AssetName(unittest.TestCase):
    def test_matches_the_provisioner_derivation(self):
        # Byte-for-byte what `ServerDistributionProvision.assetName` builds for each
        # `ReleasedDistribution`, and what compose-preview-server's release workflow uploads.
        self.assertEqual(
            gate.asset_names("3.0.0"),
            ["compose-preview-server-3.0.0.tar.gz", "compose-preview-mcp-3.0.0.tar.gz"],
        )


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
            self.assertEqual(gate.fetch_release_assets("o/r", "3.0.0"), {"a.tar.gz", "b.tar.gz"})

    def test_a_release_with_no_assets_is_an_empty_set_not_none(self):
        # Distinct from "no such Release", and reported differently: one says cut the release, the
        # other says re-attach the distribution.
        with mock.patch.object(gate.urllib.request, "urlopen", return_value=_response({})):
            self.assertEqual(gate.fetch_release_assets("o/r", "3.0.0"), set())

    def test_404_is_a_verdict_not_an_error(self):
        with mock.patch.object(gate.urllib.request, "urlopen", side_effect=_http_error(404)):
            self.assertIsNone(gate.fetch_release_assets("o/r", "3.0.0"))

    def test_rate_limit_and_server_errors_are_unanswered(self):
        for code in (403, 429, 500, 502):
            with self.subTest(code=code):
                with mock.patch.object(
                    gate.urllib.request, "urlopen", side_effect=_http_error(code)
                ):
                    with self.assertRaises(gate.Unanswered):
                        gate.fetch_release_assets("o/r", "3.0.0")

    def test_transport_failure_is_unanswered(self):
        with mock.patch.object(
            gate.urllib.request, "urlopen", side_effect=urllib.error.URLError("dns")
        ):
            with self.assertRaises(gate.Unanswered):
                gate.fetch_release_assets("o/r", "3.0.0")

    def test_a_token_is_sent_when_present_and_omitted_when_not(self):
        seen = []

        def capture(req, timeout=None):
            seen.append(dict(req.header_items()))
            return _response({"assets": []})

        with mock.patch.object(gate.urllib.request, "urlopen", side_effect=capture):
            gate.fetch_release_assets("o/r", "3.0.0", token="t0ken")
            gate.fetch_release_assets("o/r", "3.0.0", token=None)
        self.assertEqual(seen[0].get("Authorization"), "Bearer t0ken")
        self.assertNotIn("Authorization", seen[1])


class EndToEnd(unittest.TestCase):
    """`main()` against the repository's real catalog and Version.kt, with the API faked."""

    def _run(self, argv, **patch):
        with mock.patch.object(gate.sys, "argv", ["check_preview_server_pin.py", *argv]):
            with mock.patch.object(gate, "fetch_release_assets", **patch):
                return gate.main()

    def test_the_committed_pin_passes_offline(self):
        with mock.patch.object(gate.sys, "argv", ["x", "--offline"]):
            self.assertEqual(gate.main(), 0)

    def test_a_release_carrying_both_distributions_passes(self):
        pin = gate.current_pin()
        self.assertEqual(self._run([], return_value=set(gate.asset_names(pin))), 0)

    def test_a_release_carrying_only_the_server_fails(self):
        # The shape #5176 introduced: `serve` would work and `mcp serve` would exit with an
        # installation hint for an archive nobody attached. One pin, both archives, or neither.
        pin = gate.current_pin()
        self.assertEqual(self._run([], return_value={gate.asset_names(pin)[0]}), 1)

    def test_a_missing_release_fails(self):
        self.assertEqual(self._run([], return_value=None), 1)

    def test_a_release_without_the_distribution_fails(self):
        # The exact #5183 shape at the release level: the tag exists, the thing `serve` fetches
        # does not.
        self.assertEqual(self._run([], return_value={"checksums.txt"}), 1)

    def test_another_versions_distribution_does_not_count(self):
        self.assertEqual(self._run([], return_value=set(gate.asset_names("0.0.1"))), 1)

    def test_extra_assets_are_ignored(self):
        pin = gate.current_pin()
        assets = set(gate.asset_names(pin)) | {"checksums.txt"}
        self.assertEqual(self._run([], return_value=assets), 0)

    def test_an_unanswered_api_passes(self):
        # The deliberate soft spot: no answer is not a failed answer.
        self.assertEqual(self._run([], side_effect=gate.Unanswered("offline")), 0)

    def test_a_bad_pin_fails_before_any_network_call(self):
        with mock.patch.object(gate, "current_pin", return_value="3.0.0-SNAPSHOT"):
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
