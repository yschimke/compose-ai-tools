#!/usr/bin/env python3

import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "affected_gradle_tests", HERE / "affected-gradle-tests.py"
)
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class AffectedGradleTestsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "core").mkdir()
        (self.root / "app").mkdir()
        (self.root / "kmp").mkdir()
        self.projects = [
            {"path": ":", "dir": str(self.root), "dependencies": [], "jvmTestTasks": []},
            {
                "path": ":core",
                "dir": str(self.root / "core"),
                "dependencies": [],
                "jvmTestTasks": ["test"],
                # A published module: `abiValidation()` gives it `checkKotlinAbi`.
                "hasAbiCheck": True,
            },
            {"path": ":app", "dir": str(self.root / "app"), "dependencies": [":core"], "jvmTestTasks": ["test"]},
            # A Kotlin Multiplatform module: its JVM tests are `jvmTest` and it registers no
            # `test` task at all, which is exactly the shape that read as "no tests".
            {
                "path": ":kmp",
                "dir": str(self.root / "kmp"),
                "dependencies": [],
                "jvmTestTasks": ["jvmTest"],
            },
        ]
        self.config = {"ignorePaths": ["docs/**"], "globalPaths": ["gradle/**"]}

    def tearDown(self):
        self.temp.cleanup()

    def test_changed_project_includes_dependent_tests(self):
        # `:core` publishes an ABI, so a change to it runs the ABI gate as well as the tests.
        # `:app` does not, and must NOT be handed a task it has no such thing as — naming one
        # fails the whole Gradle invocation rather than that single task.
        self.assertEqual(
            mod.resolve(["core/src/Core.kt"], self.config, self.projects, self.root),
            ":app:test :core:checkKotlinAbi :core:test",
        )

    def test_abi_gate_runs_for_the_module_whose_sources_changed(self):
        # The regression this exists for: a module wires `checkKotlinAbi` into `check`, CI runs
        # `test`, and `test` does not imply `check` — so the dump goes stale and lands on main.
        # Three did in one day (#4673, #4685, #4716) before this resolver named the task.
        tasks = mod.resolve(["core/src/Core.kt"], self.config, self.projects, self.root).split()
        self.assertIn(":core:checkKotlinAbi", tasks)

    def test_a_project_without_abi_validation_is_never_given_the_task(self):
        tasks = mod.resolve(["app/src/App.kt"], self.config, self.projects, self.root).split()
        self.assertEqual(tasks, [":app:test"])

    def test_a_multiplatform_module_runs_its_jvmTest(self):
        # The regression this exists for: a KMP module names its JVM tests `jvmTest` and has no
        # `test` task, so the graph said "no tests", this resolver emitted nothing, and
        # `Module Unit Tests` skipped — while the module's path in `ci-paths.json` made it look
        # covered. `:rc-player-compose` had 25 test files that had never run (#4819).
        self.assertEqual(
            mod.resolve(["kmp/src/Main.kt"], self.config, self.projects, self.root),
            ":kmp:jvmTest",
        )

    def test_a_module_is_never_given_a_test_task_it_lacks(self):
        # Same trap as `checkKotlinAbi`: naming a task a project does not have fails the entire
        # Gradle invocation, not just that task.
        tasks = mod.resolve(["app/src/App.kt"], self.config, self.projects, self.root).split()
        self.assertEqual(tasks, [":app:test"])
        self.assertNotIn(":app:jvmTest", tasks)

    def test_a_module_with_both_runs_both(self):
        # A module carrying a JVM target beside a plain `test` gets both; neither substitutes
        # for the other, and running one would silently skip the other's suite.
        projects = [dict(p) for p in self.projects]
        both = next(p for p in projects if p["path"] == ":kmp")
        both["jvmTestTasks"] = ["test", "jvmTest"]
        self.assertEqual(
            mod.resolve(["kmp/src/Main.kt"], self.config, projects, self.root),
            ":kmp:jvmTest :kmp:test",
        )

    def test_a_graph_without_the_field_still_resolves_test(self):
        # Fail-open, like every other unknown in this script: a graph produced before
        # `jvmTestTasks` existed must not silently resolve to no tests at all.
        legacy = [
            {"path": ":core", "dir": str(self.root / "core"), "dependencies": [], "hasTestTask": True}
        ]
        self.assertEqual(
            mod.resolve(["core/src/Core.kt"], self.config, legacy, self.root),
            ":core:test",
        )

    def test_a_named_jvm_target_runs_its_test_task(self):
        # A KMP JVM target can be NAMED: `jvm("desktop")` produces `desktopTest` and no
        # `jvmTest`. `:samples:design-catalog-m3-shared` is that shape and is reachable from
        # `:slot-preview-runtime` through the reverse closure, so it was being selected and then
        # handed no task at all.
        projects = [dict(p) for p in self.projects]
        named = next(p for p in projects if p["path"] == ":kmp")
        named["jvmTestTasks"] = ["desktopTest"]
        self.assertEqual(
            mod.resolve(["kmp/src/Main.kt"], self.config, projects, self.root),
            ":kmp:desktopTest",
        )

    def test_docs_only_skips(self):
        self.assertEqual(mod.resolve(["docs/x.md"], self.config, self.projects, self.root), "none")

    def test_global_path_runs_full(self):
        self.assertEqual(mod.resolve(["gradle/libs.versions.toml"], self.config, self.projects, self.root), "full")

    def test_unknown_path_runs_full(self):
        self.assertEqual(mod.resolve(["new-root/x.kt"], self.config, self.projects, self.root), "full")


class ModuleUnitTestsGateTest(unittest.TestCase):
    """`none` is a real output of this script, and ci.yml has to honour it.

    `path-scope.py` fails open on paths it doesn't recognise and switches the Module Unit Tests
    job on, but this script reads a different config where the same path may be ignored — so the
    job can be enabled with nothing to run. Without the gate below it invoked `gradlew none` and
    failed the PR. Asserted as text so the test needs no YAML parser.
    """

    def test_the_full_branch_names_the_multiplatform_test_tasks(self):
        """`full` is not the scoped path, and it was missing the same tasks.

        Every non-PR event (push to main, the nightly cron) and every global-path PR takes the
        `full` branch, which invokes unqualified task names. `test` alone matches only projects
        that HAVE a `test` task — which a KMP module does not — so six modules' JVM suites were
        absent from a run that calls itself full. Naming them here is the same mechanism
        `checkKotlinAbi` already relies on.
        """
        ci = (HERE.parents[1] / ".github" / "workflows" / "ci.yml").read_text()
        self.assertIn("./gradlew test jvmTest desktopTest checkKotlinAbi --continue", ci)

    def test_ci_gates_module_unit_tests_on_a_non_none_task_list(self):
        ci = (HERE.parents[1] / ".github" / "workflows" / "ci.yml").read_text()
        self.assertIn("needs.module-test-scope.outputs.tasks != 'none'", ci)

    def test_module_unit_tests_consumes_the_resolved_task_list(self):
        """The gate and the task list must come from the same job.

        `Resolve affected Gradle tests` lives in its own job so it stops blocking the ~11
        jobs that never read it. That split is only safe while the `if:` gate above and
        the `TEST_TASKS` the job actually runs both read `module-test-scope` — if one is
        left pointing at a stale producer, the job passes its gate and then invokes a task
        list it never resolved.
        """
        ci = (HERE.parents[1] / ".github" / "workflows" / "ci.yml").read_text()
        self.assertIn(
            "TEST_TASKS: ${{ github.event_name == 'pull_request' "
            "&& needs.module-test-scope.outputs.tasks || 'full' }}",
            ci,
        )
        self.assertIn("needs: [changes, module-test-scope]", ci)
        self.assertNotIn("module_test_tasks", ci)

    def test_non_pr_events_keep_module_coverage_without_a_resolver_hop(self):
        """`main` and the nightly cron must not queue a job just to be told `full`.

        On non-PR events `path-scope.py` enables every group, so gating the resolver on
        `module_unit_tests` alone would schedule it on every push — and since Module Unit
        Tests `needs:` it, post-merge coverage would sit behind a second runner queue to
        compute a constant. The resolver is therefore `pull_request`-only, which makes
        `!cancelled()` load-bearing on the consumer: without it, the skipped resolver
        would skip post-merge and nightly module tests outright.
        """
        ci = (HERE.parents[1] / ".github" / "workflows" / "ci.yml").read_text()
        scope_gate = "if: ${{ github.event_name == 'pull_request' && needs.changes.outputs.module_unit_tests == 'true'"
        self.assertIn(scope_gate, ci)
        self.assertIn("!cancelled()", ci)
        self.assertIn("needs.changes.result == 'success'", ci)
        self.assertIn("needs.module-test-scope.result != 'failure'", ci)
        self.assertIn(
            "(github.event_name != 'pull_request' || needs.module-test-scope.outputs.tasks != 'none')",
            ci,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
