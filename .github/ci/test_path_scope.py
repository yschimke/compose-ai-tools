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


if __name__ == "__main__":
    unittest.main(verbosity=2)
