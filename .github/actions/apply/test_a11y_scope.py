#!/usr/bin/env python3

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("a11y_scope", HERE / "a11y-scope.py")
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class A11yScopeTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        manifest = self.root / "catalog/build/compose-previews/previews.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(
            json.dumps(
                {
                    "module": "catalog",
                    "previews": [
                        {"id": "BasicDialog", "sourceFile": "src/main/kotlin/Dialog.kt"},
                        {"id": "BasicDialog_Dark", "sourceFile": "src/main/kotlin/Dialog.kt"},
                        {"id": "Button", "sourceFile": "src/main/kotlin/Button.kt"},
                        {"id": "Escape", "sourceFile": "../other/src/Escape.kt"},
                    ],
                }
            )
        )
        self.changed = self.root / "_changed_previews.json"

    def test_visual_diff_and_source_changes_are_unioned(self):
        self.changed.write_text(json.dumps([{"previewId": "Button", "module": "catalog"}]))
        ids = mod.select(self.changed, {"catalog/src/main/kotlin/Dialog.kt"}, self.root)
        self.assertEqual(ids, ["Button", "BasicDialog", "BasicDialog_Dark"])

    def test_no_change_selects_nothing(self):
        # An empty selection is a real answer: the caller skips the a11y run.
        self.changed.write_text("[]\n")
        self.assertEqual(mod.select(self.changed, {"README.md"}, self.root), [])

    def test_a_missing_visual_diff_is_not_an_empty_one(self):
        # No compose pipeline in this job: the caller falls back to the module scope rather than
        # skipping previews whose render changed.
        self.assertIsNone(mod.select(self.changed, {"catalog/src/main/kotlin/Dialog.kt"}, self.root))

    def test_a_source_path_leaving_its_module_never_matches(self):
        self.changed.write_text("[]")
        self.assertEqual(mod.select(self.changed, {"other/src/Escape.kt"}, self.root), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
