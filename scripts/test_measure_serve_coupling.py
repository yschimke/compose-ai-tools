#!/usr/bin/env python3
"""Unit tests for measure-serve-coupling.py's classifier.

Pure stdlib (unittest). Run: python3 scripts/test_measure_serve_coupling.py -v

The classifier is the whole value of the script — if it and issue #3824
disagree, the gate stops meaning anything. These cases pin the rules the issue
states in prose: which files count, which side each counted file lands on
(before AND after the moves the issue asks for), what makes a PR "crossing",
and which paths are the load-bearing "deep" tier.
"""

import importlib.util
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "measure_serve_coupling", _HERE / "measure-serve-coupling.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class CountedFiles(unittest.TestCase):
    def test_source_and_config_count(self):
        for path in (
            "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHttpServer.kt",
            "cli/build.gradle.kts",
            "cli/serve-web/src/components/RcLanes.ts",
            "deploy/image/Dockerfile.properties",
            "vscode-extension/preview-harness/serve-lanes.spec.mjs",
            "gradle/libs.versions.toml",
            "scripts/run-daemon.sh",
        ):
            self.assertTrue(mod.is_counted(path), path)

    def test_docs_ci_renders_and_site_never_count(self):
        """Excluded deliberately: cheap to duplicate, would flatter the result."""
        for path in (
            "docs/serve/SESSION-VIEWER-PROTOCOL.md",
            "docs/public-preview-server.md",
            "docs/serve/whatever.json",
            ".github/workflows/serve-lanes-e2e.yml",
            "renders/lit-viewer/README.md",
            "site/index.html",
            "README.md",
        ):
            self.assertFalse(mod.is_counted(path), path)

    def test_images_and_unknown_extensions_do_not_count(self):
        for path in (
            "cli/src/main/resources/ee/schimke/composeai/cli/serve/logo.png",
            "deploy/image/Dockerfile",
            "cli/src/main/kotlin/Foo.java",
        ):
            self.assertFalse(mod.is_counted(path), path)


class Sides(unittest.TestCase):
    def test_serve_today(self):
        for path in (
            "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHost.kt",
            "cli/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeHostTest.kt",
            "cli/src/main/resources/ee/schimke/composeai/cli/serve/viewer.css",
            "cli/src/main/kotlin/ee/schimke/composeai/cli/ServeCommand.kt",
            "cli/serve-web/src/components/RcLanes.ts",
            "deploy/preview.coo.ee/service.yaml",
        ):
            self.assertEqual(mod.side_of(path), mod.SERVE, path)

    def test_serve_after_the_moves_3824_asks_for(self):
        """The series must stay continuous across the extraction."""
        for path in (
            "preview-server/server/src/main/kotlin/ServeHost.kt",
            "preview-server/preview-harness/serve-lanes.spec.mjs",
            "cli/serve/src/main/kotlin/ServeHost.kt",
        ):
            self.assertEqual(mod.side_of(path), mod.SERVE, path)

    def test_misfiled_serve_harness_counts_as_serve_not_extension(self):
        for path in (
            "vscode-extension/preview-harness/serve-lanes.spec.mjs",
            "vscode-extension/preview-harness/pages-snapshot.spec.mjs",
            "vscode-extension/preview-harness/playground.spec.mjs",
            "vscode-extension/preview-harness/fixtures/pages/serve-viewer.html",
        ):
            self.assertEqual(mod.side_of(path), mod.SERVE, path)

    def test_extension_source_is_still_extension(self):
        for path in (
            "vscode-extension/src/extension.ts",
            "vscode-extension/preview-harness/snapshot.spec.mjs",
            "vscode-extension/package.json",
        ):
            self.assertEqual(mod.side_of(path), mod.EXTENSION, path)

    def test_everything_else_is_core(self):
        for path in (
            "daemon/core/src/main/kotlin/Protocol.kt",
            "renderers/desktop/build.gradle.kts",
            "cli/src/main/kotlin/ee/schimke/composeai/cli/CliFlags.kt",
            "gradle/libs.versions.toml",
        ):
            self.assertEqual(mod.side_of(path), mod.CORE, path)


class DeepTier(unittest.TestCase):
    def test_deep_paths(self):
        for path in (
            "daemon/core/src/main/kotlin/Protocol.kt",
            "daemon/android/src/main/kotlin/Foo.kt",
            "renderers/desktop/src/main/kotlin/Foo.kt",
            "renderer-xr-client/build.gradle.kts",
            "gradle-plugin/src/main/kotlin/Foo.kt",
            "data/layoutinspector/connector/src/main/kotlin/Foo.kt",
            "render-session/api/src/main/kotlin/Foo.kt",
        ):
            self.assertTrue(mod.is_deep(path), path)

    def test_shallow_paths(self):
        for path in (
            "data/layoutinspector/core/src/main/kotlin/Foo.kt",
            "cli/build.gradle.kts",
            "scripts/run-daemon.sh",
            "samples/android/build.gradle.kts",
        ):
            self.assertFalse(mod.is_deep(path), path)


def pr(files, subject="feat: x", date="2026-08-01T00:00:00+00:00"):
    return mod.Pr("abc123", subject, date, files)


class Crossing(unittest.TestCase):
    def test_serve_only_pr_does_not_cross(self):
        p = pr(["cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHost.kt"])
        self.assertTrue(p.touches(mod.SERVE))
        self.assertFalse(p.crosses(mod.SERVE))

    def test_serve_plus_core_crosses(self):
        p = pr(
            [
                "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHost.kt",
                "daemon/core/src/main/kotlin/Protocol.kt",
            ]
        )
        self.assertTrue(p.crosses(mod.SERVE))
        self.assertTrue(p.crosses_deep(mod.SERVE))

    def test_uncounted_companion_file_does_not_make_a_crossing(self):
        """A serve PR that also edits docs/CI is still a serve-only PR."""
        p = pr(
            [
                "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHost.kt",
                "docs/public-preview-server.md",
                ".github/workflows/serve-lanes-e2e.yml",
                "renders/lit-viewer/README.md",
            ]
        )
        self.assertFalse(p.crosses(mod.SERVE))

    def test_shallow_crossing_is_not_deep(self):
        p = pr(
            [
                "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHost.kt",
                "cli/src/main/kotlin/ee/schimke/composeai/cli/CliFlags.kt",
            ]
        )
        self.assertTrue(p.crosses(mod.SERVE))
        self.assertFalse(p.crosses_deep(mod.SERVE))

    def test_harness_move_removes_a_crossing(self):
        """The single biggest tidy-up 3824 measured: 28% of apparent traffic."""
        today = pr(
            [
                "cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHost.kt",
                "vscode-extension/preview-harness/serve-lanes.spec.mjs",
            ]
        )
        self.assertFalse(today.crosses(mod.SERVE))  # already classified as serve


class Aggregate(unittest.TestCase):
    def setUp(self):
        self.prs = [
            pr(["cli/src/main/kotlin/ee/schimke/composeai/cli/serve/A.kt"],
               date="2026-08-01T00:00:00+00:00"),
            pr(["cli/src/main/kotlin/ee/schimke/composeai/cli/serve/B.kt",
                "daemon/core/src/main/kotlin/P.kt"],
               date="2026-08-08T00:00:00+00:00"),
            pr(["renderers/desktop/src/main/kotlin/R.kt"],
               date="2026-08-15T00:00:00+00:00"),
        ]

    def test_counts_and_rates(self):
        s = mod.measure(self.prs, mod.SERVE)
        self.assertEqual(s["prs"], 3)
        self.assertEqual(s["touching"], 2)
        self.assertEqual(s["crossing"], 1)
        self.assertEqual(s["deep"], 1)
        self.assertAlmostEqual(s["weeks"], 2.0, places=3)
        self.assertAlmostEqual(s["pct_of_all"], 100 / 3, places=3)
        self.assertAlmostEqual(s["pct_of_touching"], 50.0, places=3)
        self.assertAlmostEqual(s["deep_per_week"], 0.5, places=3)

    def test_gate_reads_the_thresholds_from_3824(self):
        s = mod.measure(self.prs, mod.SERVE)
        conds = mod.gate(s)
        self.assertEqual([c["id"] for c in conds], [2, 2, 3])
        self.assertFalse(conds[0]["pass"])  # 33% of all PRs, target 5%
        self.assertFalse(conds[1]["pass"])  # 50% of touching, target 15%
        self.assertTrue(conds[2]["pass"])  # 0.5 deep/wk, target 2/wk

    def test_low_volume_caveat_flags_the_3856_blind_spot(self):
        s = mod.measure(self.prs, mod.SERVE)
        self.assertTrue(mod.gate(s)[1]["caveat_low_volume"])


class GateExitStatus(unittest.TestCase):
    """`--gate` is an exit-status contract, not an output format (PR #4512 review).

    A CI consumer piping `--json --gate` must not be told a red window succeeded.
    """

    def red(self):
        return mod.measure(
            [pr(["cli/src/main/kotlin/ee/schimke/composeai/cli/serve/A.kt",
                 "daemon/core/src/main/kotlin/P.kt"])],
            mod.SERVE,
        )

    def green(self):
        return mod.measure([pr(["README.md"])] * 100, mod.SERVE)

    def test_red_serve_gate_is_not_green(self):
        self.assertFalse(mod.gate_is_green(self.red()))

    def test_gate_flag_is_honoured_for_json_output(self):
        self.assertFalse(mod.gate_green({mod.SERVE: self.red()}, want_gate=True))

    def test_without_the_flag_nothing_gates(self):
        self.assertTrue(mod.gate_green({mod.SERVE: self.red()}, want_gate=False))

    def test_a_run_that_never_measured_serve_cannot_fail_the_serve_gate(self):
        self.assertTrue(mod.gate_green({mod.EXTENSION: self.red()}, want_gate=True))

    def test_clean_window_passes(self):
        self.assertTrue(mod.gate_green({mod.SERVE: self.green()}, want_gate=True))


class ReleaseBots(unittest.TestCase):
    def test_release_please_subjects_are_excluded(self):
        self.assertTrue(mod.RELEASE_SUBJECT.match("chore(main): release 1.2.3"))
        self.assertFalse(mod.RELEASE_SUBJECT.match("chore: release notes tidy"))


if __name__ == "__main__":
    unittest.main()
