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
        self.projects = [
            {"path": ":", "dir": str(self.root), "dependencies": [], "hasTestTask": False},
            {"path": ":core", "dir": str(self.root / "core"), "dependencies": [], "hasTestTask": True},
            {"path": ":app", "dir": str(self.root / "app"), "dependencies": [":core"], "hasTestTask": True},
        ]
        self.config = {"ignorePaths": ["docs/**"], "globalPaths": ["gradle/**"]}

    def tearDown(self):
        self.temp.cleanup()

    def test_changed_project_includes_dependent_tests(self):
        self.assertEqual(
            mod.resolve(["core/src/Core.kt"], self.config, self.projects, self.root),
            ":app:test :core:test",
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
