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
            mod.decide(["cli/src/Main.kt", "cli/serve-web/src/main.ts"], self.config),
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

    def test_typescript_only_selects_javascript_codeql(self):
        result = mod.decide(
            ["cli/serve-web/src/live/framePainter.ts"], self.load("codeql-paths.json")
        )
        self.assertEqual(
            result,
            {"java_kotlin": False, "javascript_typescript": True, "actions": False},
        )

    # `test_vscode_only_skips_gradle_ci_groups` lived here. It asserted that a
    # TypeScript-only change wakes no group costing a JDK, a Gradle daemon or a runner
    # minute — a real property, protecting a real cost, while `vscode-extension/**` was
    # the repo's TypeScript and was on every ignorePaths list.
    #
    # It is gone rather than repointed because the extension took its subject with it
    # (yschimke/compose-preview-vscode). The only TypeScript left here is
    # `cli/serve-web/**`, which lives *inside* `cli/` and so correctly wakes `build_cli`
    # and `module_unit_tests`. Repointing the test at it would have asserted the
    # opposite of the truth and passed only by weakening the claim to nothing.
    #
    # If a TypeScript surface outside the Gradle module tree returns, restore this.

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
            ["preview-server/preview-harness/playground.spec.mjs"],
            self.load("serve-lanes-paths.json"),
        )
        self.assertEqual(
            result,
            {"desktop": False, "android": False, "bundle": False, "playground": True},
        )

    def test_bundle_upload_harness_runs_only_bundle_lane(self):
        result = mod.decide(
            ["preview-server/preview-harness/bundle-upload.spec.mjs"],
            self.load("serve-lanes-paths.json"),
        )
        self.assertEqual(
            result,
            {"desktop": False, "android": False, "bundle": True, "playground": False},
        )

    def test_serve_harness_manifests_survive_an_ignore_of_the_harness_tree(self):
        # Every lane job runs `npm ci` and then its `harness:*` script out of the serve harness's
        # own manifest, so a Playwright bump or a renamed lane script lands entirely in these two
        # files, which match none of the per-lane globs.
        #
        # Today they still run every lane without being listed, because `decide` fails open on an
        # unrecognised path — so asserting that on the config as it stands would pass with the
        # globalPaths entries removed, and guard nothing. What the entries actually buy is
        # survival of an ignore: `ci-paths.json` legitimately carries
        # `preview-server/preview-harness/**` in its ignorePaths (harness edits skip the Gradle
        # CI), and mirroring that here would take these manifests off fail-open and select no lane
        # at all. globalPaths is checked before ignorePaths, so the entries hold. Pin that.
        config = self.load("serve-lanes-paths.json")
        config["ignorePaths"] = [
            *config.get("ignorePaths", []),
            "preview-server/preview-harness/**",
        ]
        for changed in (
            "preview-server/preview-harness/package.json",
            "preview-server/preview-harness/package-lock.json",
        ):
            with self.subTest(changed=changed):
                self.assertEqual(
                    mod.decide([changed], config),
                    {
                        "desktop": True,
                        "android": True,
                        "bundle": True,
                        "playground": True,
                    },
                )

    @staticmethod
    def missing_literal_paths(config):
        """Literal (non-glob) paths in a lane config that do not exist in the tree.

        A glob is a pattern over paths that may not exist yet — a lane fixture nobody has written
        — so only exact paths are resolved.
        """
        root = HERE.parents[1]
        literal = []
        for value in config.values():
            entries = value if isinstance(value, list) else sum(value.values(), [])
            literal += [e for e in entries if not any(c in e for c in "*?[")]
        return literal, sorted({p for p in literal if not (root / p).exists()})

    def test_every_literal_path_in_the_serve_lane_config_exists(self):
        """A renamed file silently stops selecting its lane (PR #4587 review).

        The lane config pins two Robolectric bundle sources by exact path rather than by glob,
        because they are the only files outside `serve` that the Android and playground lanes
        depend on. An exact path is a fact about the tree that nothing checked: when
        `AndroidBundleLaunch.kt` moved modules, and again when it changed package directory, the
        entries kept matching nothing at all. That does not fail — it just quietly stops waking
        the lanes on exactly the changes they exist to cover. Both mistakes were made here, one
        PR apart.
        """
        literal, missing = self.missing_literal_paths(self.load("serve-lanes-paths.json"))
        self.assertTrue(literal, "no literal paths in serve-lanes-paths.json to check")
        self.assertEqual(
            missing,
            [],
            "serve-lanes-paths.json names path(s) that do not exist; a moved or renamed file "
            "stops selecting its lane silently: " + ", ".join(missing),
        )

    def test_the_missing_path_guard_actually_fires(self):
        """The guard above must fail on a stale path, or it guards nothing.

        Uses the exact path this PR had to fix — the one `AndroidBundleLaunch.kt` carried between
        the module move and the package rename.
        """
        stale = "bundle/format/src/main/kotlin/ee/schimke/composeai/cli/AndroidBundleLaunch.kt"
        real = "bundle/format/src/main/kotlin/ee/schimke/composeai/bundle/AndroidBundleLaunch.kt"
        # A synthetic config, not the real one: if the real config were itself broken, injecting a
        # path into it would make this pass for the wrong reason and mask the failure above.
        literal, missing = self.missing_literal_paths(
            {"globalPaths": [real, "bundle/format/**"], "lanes": {"android": [stale]}}
        )
        self.assertEqual(sorted(literal), sorted([real, stale]), "glob was not skipped")
        self.assertEqual(missing, [stale])

    def test_the_workflow_and_the_lane_config_pin_the_same_bundle_sources(self):
        """Two files carry these paths; only one of them is JSON anything can validate.

        `serve-lanes-e2e.yml`'s own `paths:` filters decide whether the workflow runs at all, and
        `serve-lanes-paths.json` decides which lanes it then selects. A path fixed in one and not
        the other is worse than a path wrong in both: the workflow starts and picks no lane.
        """
        import re as _re

        root = HERE.parents[1]
        workflow = (root / ".github/workflows/serve-lanes-e2e.yml").read_text()
        in_workflow = set(_re.findall(r'"(bundle/format/\S+?\.kt)"', workflow))
        config = self.load("serve-lanes-paths.json")
        in_config = {
            e
            for value in config.values()
            for e in (value if isinstance(value, list) else sum(value.values(), []))
            if e.startswith("bundle/format/") and e.endswith(".kt")
        }
        self.assertTrue(in_config, "lane config pins no bundle-format sources")
        self.assertEqual(in_workflow, in_config)

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


    def test_contract_module_change_runs_the_preview_server_probe(self):
        # `preview-server/` is a separate Gradle build that nothing in the root build includes, so
        # this job is the only thing that notices when a contract module stops resolving for it.
        # A change to a contract must reach it (issue #3824).
        for changed in (
            "daemon/protocol/src/main/kotlin/ee/schimke/composeai/daemon/protocol/DaemonLaunchDescriptor.kt",
            "render-session/subprocess/build.gradle.kts",
            "preview-server/contract-probe/build.gradle.kts",
            "scripts/check-preview-server-contracts.sh",
        ):
            with self.subTest(changed=changed):
                result = mod.decide([changed], self.load("ci-paths.json"))
                self.assertTrue(result["preview_server_contracts"])

    def test_unrelated_change_skips_the_preview_server_probe(self):
        result = mod.decide(
            ["samples/android/src/main/kotlin/App.kt"], self.load("ci-paths.json")
        )
        self.assertFalse(result["preview_server_contracts"])


    def test_every_contract_project_reaches_the_probe_job(self):
        """Three lists name the contracts; nothing tied the third to the other two (PR #4512).

        `contracts` in the probe's build file and `CONTRACT_PROJECTS` in the driver script are
        checked against each other by the build itself — an unpublished contract simply fails to
        resolve. The CI path group is not: adding a contract without a path covering its source
        means a later PR touching only that module skips `preview-server-contracts`, the sole job
        that publishes it and resolves it from the separate build.

        Asks the real classifier rather than re-implementing glob matching, so a covering pattern
        like `render-session/**` counts for `:render-session-api` exactly as CI would treat it.
        """
        import re as _re

        config = self.load("ci-paths.json")
        root = HERE.parents[1]
        script = (root / "scripts/check-preview-server-contracts.sh").read_text()
        block = script.split("CONTRACT_PROJECTS=(", 1)[1].split(")", 1)[0]
        projects = _re.findall(r'"(:[^"]+)"', block)
        self.assertTrue(projects, "could not parse CONTRACT_PROJECTS")

        settings = (root / "settings.gradle.kts").read_text()
        unreachable = []
        for project in projects:
            remap = _re.search(
                _re.escape(f'project("{project}").projectDir = file("') + r'([^"]+)"', settings
            )
            directory = remap.group(1) if remap else project.lstrip(":").replace(":", "/")
            changed = f"{directory}/src/main/kotlin/Probe.kt"
            if not mod.decide([changed], config)["preview_server_contracts"]:
                unreachable.append(f"{project} ({directory})")
        self.assertEqual(
            unreachable,
            [],
            "contract project(s) whose sources do not schedule preview-server-contracts: "
            + ", ".join(unreachable),
        )

    def test_a_contract_added_without_a_path_is_caught(self):
        """The guard above must actually fail when the group is missing a contract."""
        config = self.load("ci-paths.json")
        stripped = json.loads(json.dumps(config))
        stripped["groups"]["preview_server_contracts"] = [
            p for p in stripped["groups"]["preview_server_contracts"] if "daemon/core" not in p
        ]
        self.assertFalse(
            mod.decide(
                ["daemon/core/src/main/kotlin/Probe.kt"], stripped
            )["preview_server_contracts"]
        )

if __name__ == "__main__":
    unittest.main(verbosity=2)
