#!/usr/bin/env python3

import importlib.util
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("path_scope", HERE / "path-scope.py")
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class PathScopeTest(unittest.TestCase):
    def setUp(self):
        self.config = {
            "ignorePaths": ["docs/**", "**/*.md"],
            "globalPaths": ["settings.gradle.kts"],
            "groups": {
                "jvm": ["**/*.kt", "**/*.gradle.kts"],
                "js": ["**/*.ts", "**/package*.json"],
            },
        }

    def test_unions_matching_groups(self):
        self.assertEqual(
            mod.decide(["cli/src/Main.kt", "vscode-extension/src/main.ts"], self.config),
            {"jvm": True, "js": True},
        )

    def test_ignored_only_disables_all(self):
        self.assertEqual(
            mod.decide(["docs/ci.md", "gradle-plugin/README.md"], self.config),
            {"jvm": False, "js": False},
        )

    def test_unknown_fails_open(self):
        self.assertEqual(
            mod.decide(["new-language/source.xyz"], self.config),
            {"jvm": True, "js": True},
        )

    def test_global_fails_open(self):
        self.assertEqual(
            mod.decide(["settings.gradle.kts"], self.config),
            {"jvm": True, "js": True},
        )

    def test_empty_fails_open(self):
        self.assertEqual(mod.decide([], self.config), {"jvm": True, "js": True})


class RepositoryConfigsTest(unittest.TestCase):
    def load(self, name):
        return json.loads((HERE / name).read_text())

    def test_vscode_only_selects_javascript_codeql(self):
        result = mod.decide(
            ["vscode-extension/src/extension.ts"], self.load("codeql-paths.json")
        )
        self.assertEqual(
            result,
            {"java_kotlin": False, "javascript_typescript": True, "actions": False},
        )

    def test_vscode_only_skips_gradle_ci_groups(self):
        result = mod.decide(
            ["vscode-extension/src/extension.ts"], self.load("ci-paths.json")
        )
        self.assertFalse(any(result.values()))

    def test_driver_pin_bump_runs_only_the_actions_validator(self):
        # The export-driver pin bump is opened unattended after every release and
        # is three lines of a data file. It matched no group and no ignore rule
        # when it was introduced, which put it on the unknown-path fail-open route
        # and ran the entire build suite on a routine release bump.
        result = mod.decide(
            [".github/design-artifacts-driver-pin.txt"], self.load("ci-paths.json")
        )
        self.assertEqual(
            [group for group, on in result.items() if on], ["actions_tests"]
        )

    def test_cli_change_selects_cli_and_affected_module_tests(self):
        result = mod.decide(
            ["cli/src/main/kotlin/ee/schimke/composeai/cli/Commands.kt"],
            self.load("ci-paths.json"),
        )
        self.assertTrue(result["build_cli"])
        self.assertTrue(result["module_unit_tests"])
        self.assertFalse(result["renderer_android_tests"])
        self.assertFalse(result["build_samples"])

    def test_android_baseline_runs_only_android_harness(self):
        result = mod.decide(
            ["daemon/harness/baselines/android/s1/red-square.png"],
            self.load("daemon-job-paths.json"),
        )
        self.assertEqual(
            result,
            {"desktop_fake": False, "desktop_real": False, "android_real": True},
        )

    def test_playground_harness_runs_only_playground_lane(self):
        result = mod.decide(
            ["vscode-extension/preview-harness/playground.spec.mjs"],
            self.load("serve-lanes-paths.json"),
        )
        self.assertEqual(
            result,
            {"desktop": False, "android": False, "bundle": False, "playground": True},
        )

    def test_bundle_upload_harness_runs_only_bundle_lane(self):
        result = mod.decide(
            ["vscode-extension/preview-harness/bundle-upload.spec.mjs"],
            self.load("serve-lanes-paths.json"),
        )
        self.assertEqual(
            result,
            {"desktop": False, "android": False, "bundle": True, "playground": False},
        )

    def test_wasm_distribution_resources_run_the_rc_player_jobs(self):
        # `wasmPlayerDist` syncs these two paths straight into the shipped player (see
        # rc-player/wasm/build.gradle.kts), so a font swap or a fonts.json edit changes production
        # pixels. Without them in `rc_player_tests` the CMP/Wasm parity and frame-pacing jobs both
        # skip on exactly the change most likely to move a parity number.
        for changed in (
            "samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/fonts.json",
            "samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/RobotoFlex.ttf",
            "samples/cmp-wasm-catalog/src/wasmJsMain/resources/js-joda.esm.js",
        ):
            with self.subTest(changed=changed):
                result = mod.decide([changed], self.load("ci-paths.json"))
                self.assertTrue(result["rc_player_tests"])

    def test_other_wasm_catalog_sources_do_not_run_the_rc_player_jobs(self):
        # Scoped to what the distribution actually copies: the catalog's own Kotlin is not a player
        # input, and pulling the whole module in would run these jobs on every catalog edit.
        result = mod.decide(
            ["samples/cmp-wasm-catalog/src/wasmJsMain/kotlin/App.kt"],
            self.load("ci-paths.json"),
        )
        self.assertFalse(result["rc_player_tests"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
