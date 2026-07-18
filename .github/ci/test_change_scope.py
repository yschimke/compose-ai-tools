#!/usr/bin/env python3
"""Unit tests for change-scope.py's classifier.

Pure stdlib (unittest). Run: python3 .github/ci/test_change_scope.py -v

The `decide` cases use real changed-file sets from recent PRs so the config
and the classifier are pinned to actual project history: docs / samples /
vscode / scripts-only PRs must skip; anything touching the plugin / CLI /
renderers / daemon / data / build wiring must run.
"""

import importlib.util
import json
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parents[1]

# Load change-scope.py as a module (hyphenated filename → importlib).
_spec = importlib.util.spec_from_file_location(
    "change_scope", _HERE / "change-scope.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)

# Real ignore list from the committed shared config.
_CONFIG = json.loads((_HERE / "change-scope-safe-paths.json").read_text())
IGNORE = [mod.glob_to_regex(p) for p in _CONFIG["ignorePaths"]]


def decide(files):
    return mod.decide(files, IGNORE)


class DecideSkip(unittest.TestCase):
    """PRs whose every file is ignore-listed → skip the external matrix."""

    def test_docs_only(self):
        self.assertEqual(decide(["docs/AGENTS.md", "docs/SDK_COMPATIBILITY.md"]), "false")

    def test_readme_anywhere(self):
        # `**/*.md` matches a README nested inside an otherwise-relevant module.
        self.assertEqual(decide(["gradle-plugin/README.md"]), "false")

    def test_samples_only(self):
        # Integration checks out EXTERNAL repos, so this repo's samples can't feed it.
        self.assertEqual(
            decide(["samples/wear/src/main/kotlin/GestureGalleryPreview.kt"]), "false"
        )

    def test_docs_and_samples(self):
        self.assertEqual(
            decide(["docs/x.md", "samples/cmp-wasm-catalog/src/main/App.kt"]), "false"
        )

    def test_vscode_only(self):
        self.assertEqual(
            decide(["vscode-extension/src/preview.ts", "vscode-extension/package.json"]),
            "false",
        )

    def test_scripts_only(self):
        self.assertEqual(decide(["scripts/design-artifacts/compare_page.py"]), "false")

    def test_deploy_only(self):
        self.assertEqual(decide(["deploy/preview-host/Dockerfile"]), "false")

    def test_renders_and_docs(self):
        self.assertEqual(decide(["renders/samples/android/Foo.png", "docs/x.md"]), "false")


class DecideRun(unittest.TestCase):
    """Any file that can change the external builds → run the full matrix."""

    def test_gradle_plugin(self):
        self.assertEqual(decide(["gradle-plugin/src/main/kotlin/Plugin.kt"]), "true")

    def test_renderer(self):
        self.assertEqual(decide(["renderers/android/src/main/kotlin/R.kt"]), "true")

    def test_daemon(self):
        self.assertEqual(decide(["daemon/core/src/main/kotlin/D.kt"]), "true")

    def test_data_figma_svg(self):
        # data/** is deliberately NOT ignored (conservative: it's published).
        self.assertEqual(decide(["data/figma-svg/src/main/kotlin/Svg.kt"]), "true")

    def test_cli(self):
        # The CLI materialises the init script every integration job uses.
        self.assertEqual(decide(["cli/src/main/kotlin/InitScript.kt"]), "true")

    def test_common_io(self):
        # Direct dep of :daemon:harness — must run the daemon harness.
        self.assertEqual(decide(["common-io/src/main/kotlin/Io.kt"]), "true")

    def test_daemon_harness_baselines(self):
        # The harness's committed pixel baselines live under daemon/** (not the
        # top-level renders/**), so a baseline change must re-run the harness.
        self.assertEqual(decide(["daemon/harness/baselines/desktop/scene.png"]), "true")

    def test_build_wiring(self):
        self.assertEqual(decide(["settings.gradle.kts"]), "true")

    def test_version_catalog(self):
        self.assertEqual(decide(["gradle/libs.versions.toml"]), "true")

    def test_mixed_relevant_and_ignored(self):
        # One relevant file among ignored ones is enough to run.
        self.assertEqual(decide(["docs/x.md", "gradle-plugin/src/main/P.kt"]), "true")

    def test_unclassified_dir_runs(self):
        # A path not on the ignore list defaults to run (fail-open by design).
        self.assertEqual(decide(["schema/spatial-scene.schema.json"]), "true")

    def test_empty_diff_runs(self):
        self.assertEqual(decide([]), "true")


if __name__ == "__main__":
    unittest.main(verbosity=2)
