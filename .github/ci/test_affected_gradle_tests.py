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


if __name__ == "__main__":
    unittest.main(verbosity=2)
