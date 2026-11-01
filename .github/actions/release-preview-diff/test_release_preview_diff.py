"""Tests for release_preview_diff.py and release_notes.py."""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


notes_mod = load("release_notes")


def show(entries):
    return {"previews": [
        {"id": pid, "module": "app", "functionName": fn,
         "captures": [{"pngPath": f"r/{pid}.png", "sha256": sha}]}
        for pid, fn, sha in entries]}


class DiffTest(unittest.TestCase):
    def test_changed_new_removed_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            t = Path(tmp)
            for side, entries in {
                "base": [("a", "A", "1"), ("b", "B", "2"), ("gone", "Gone", "3")],
                "head": [("a", "A", "1"), ("b", "B", "9"), ("new", "New", "4")],
            }.items():
                (t / side / "r").mkdir(parents=True)
                for pid, _, _ in entries:
                    (t / side / "r" / f"{pid}.png").write_bytes(b"png-" + pid.encode())
                (t / f"{side}.json").write_text(json.dumps(show(entries)))
            subprocess.run([sys.executable, "-I", str(HERE / "release_preview_diff.py"),
                            str(t / "base.json"), str(t / "head.json"),
                            "--base-root", str(t / "base"), "--head-root", str(t / "head"),
                            "--base-tag", "v1.0.0", "--head-tag", "v1.1.0",
                            "--out", str(t / "out"), "--image-url-prefix", "@@IMAGES@@"],
                           check=True, capture_output=True)
            summary = json.loads((t / "out/summary.json").read_text())
            self.assertEqual((summary["changed"], summary["new"], summary["removed"],
                              summary["unchanged"]), (1, 1, 1, 1))
            diff = (t / "out/diff.md").read_text()
            self.assertIn("**`B`** (app)", diff)
            self.assertIn("**`New`** (app)", diff)
            self.assertNotIn("**`A`**", diff)


class NotesTest(unittest.TestCase):
    SUMMARY = {"changed": 2, "new": 1, "removed": 0}
    DIFF = "<!-- preview-diff -->\n## Preview Changes\n### Changed\n**`B`** (app)\n"

    def test_appends_and_replaces(self) -> None:
        once = notes_mod.notes("## Notes\n", self.SUMMARY, self.DIFF, "v1.0.0", "o/r")
        self.assertIn("Preview changes since v1.0.0: 2 changed, 1 new, 0 removed", once)
        self.assertIn("**`B`** (app)", once)
        self.assertNotIn("## Preview Changes", once)
        twice = notes_mod.notes(once, {"changed": 5, "new": 0, "removed": 1}, self.DIFF, "v1.0.0", "o/r")
        self.assertEqual(twice.count(notes_mod.START), 1)
        self.assertIn("5 changed", twice)
        self.assertTrue(twice.startswith("## Notes"))

    def test_too_long_names_the_branch(self) -> None:
        big = self.DIFF + ("x" * 130_000)
        out = notes_mod.notes("", self.SUMMARY, big, "v1.0.0", "o/r-out")
        self.assertIn("`compose-preview/releases` branch of o/r-out", out)
        self.assertLess(len(out), 1000)


if __name__ == "__main__":
    unittest.main()
