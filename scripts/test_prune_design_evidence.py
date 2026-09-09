#!/usr/bin/env python3
"""Unit tests for prune-design-evidence.py.

Pure stdlib (unittest). Run: python3 scripts/test_prune_design_evidence.py -v

Each of the three ways a directory survives is pinned separately, because the failure mode of this
script is silent deletion of something that mattered. The age floor gets the most attention: it is
the rule that keeps an open PR's evidence, and it is the one that cannot be checked by reading the
tree — it needs real commit timestamps, so the fixtures build a real git repository.
"""

import importlib.util
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location(
    "prune_design_evidence", _HERE / "prune-design-evidence.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)

DAY = 86400


def git(repo: Path, *args: str, env_extra: dict | None = None) -> None:
    env = {
        "GIT_AUTHOR_NAME": "T",
        "GIT_AUTHOR_EMAIL": "t@example.com",
        "GIT_COMMITTER_NAME": "T",
        "GIT_COMMITTER_EMAIL": "t@example.com",
        "PATH": "/usr/bin:/bin",
        "HOME": str(repo),
    }
    if env_extra:
        env.update(env_extra)
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, env=env)


class PruneEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        for r in mod.DEFAULT_ROOTS:
            (self.repo / r).mkdir(parents=True)
        self.now = time.time()
        self.root = "docs/design/evidence"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def add_evidence(
        self,
        name: str,
        age_days: float,
        keep: bool = False,
        readme: str | None = None,
        root: str | None = None,
    ) -> None:
        d = self.repo / (root or self.root) / name
        d.mkdir()
        (d / "README.md").write_text(readme if readme is not None else f"# {name}\n")
        if keep:
            (d / "KEEP").write_text("a baseline nothing links to\n")
        stamp = time.strftime(
            "%Y-%m-%dT%H:%M:%S", time.gmtime(self.now - age_days * DAY)
        )
        git(self.repo, "add", "-A")
        git(
            self.repo,
            "commit",
            "-q",
            "-m",
            f"add {name}",
            env_extra={"GIT_AUTHOR_DATE": stamp, "GIT_COMMITTER_DATE": stamp},
        )

    def write_doc(self, body: str) -> None:
        (self.repo / "docs").mkdir(exist_ok=True)
        (self.repo / "docs" / "index.md").write_text(body)
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "doc")

    def classify(self, min_age_days: int = 90):
        return mod.classify(self.repo, min_age_days, now=self.now)

    def test_old_and_unreferenced_is_prunable(self) -> None:
        self.add_evidence("spent", age_days=200)
        prunable, kept = self.classify()
        self.assertEqual(prunable, [f"{self.root}/spent"])
        self.assertEqual(kept, {})

    def test_referenced_is_kept_however_old(self) -> None:
        self.add_evidence("cited", age_days=999)
        self.write_doc("see [it](design/evidence/cited/README.md)\n")
        prunable, kept = self.classify()
        self.assertEqual(prunable, [])
        self.assertEqual(kept[f"{self.root}/cited"], "referenced")

    def test_young_is_kept_even_when_unreferenced(self) -> None:
        """The open-PR case: the PR that adds evidence adds no reference to it."""
        self.add_evidence("in-flight", age_days=3)
        prunable, kept = self.classify()
        self.assertEqual(prunable, [])
        self.assertIn("3d old", kept[f"{self.root}/in-flight"])

    def test_age_floor_boundary(self) -> None:
        self.add_evidence("just-under", age_days=89)
        self.add_evidence("just-over", age_days=91)
        prunable, _ = self.classify(min_age_days=90)
        self.assertEqual(prunable, [f"{self.root}/just-over"])

    def test_keep_file_wins_over_age(self) -> None:
        self.add_evidence("baseline", age_days=999, keep=True)
        prunable, kept = self.classify()
        self.assertEqual(prunable, [])
        self.assertEqual(kept[f"{self.root}/baseline"], "KEEP file")

    def test_a_reference_from_source_counts(self) -> None:
        """Not only markdown — a test or a build file may cite a filmstrip."""
        self.add_evidence("live-press", age_days=400)
        (self.repo / "Probe.kt").write_text(
            "// the filmstrip docs/design/evidence/live-press/ is built from\n"
        )
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "src")
        prunable, kept = self.classify()
        self.assertEqual(prunable, [])
        self.assertEqual(kept[f"{self.root}/live-press"], "referenced")

    def test_a_reference_from_inside_the_tree_does_not_count(self) -> None:
        """Otherwise two spent directories citing each other would keep each other alive."""
        self.add_evidence("a", age_days=400, readme="see docs/design/evidence/b/README.md\n")
        self.add_evidence("b", age_days=400, readme="see renders/a/README.md\n", root="renders")
        prunable, _ = self.classify()
        self.assertEqual(prunable, [f"{self.root}/a", "renders/b"])

    def test_report_mode_is_a_gate_and_prune_mode_deletes(self) -> None:
        self.add_evidence("spent", age_days=200)
        d = self.repo / self.root / "spent"
        self.assertEqual(mod.main(["--repo", str(self.repo), "--quiet"]), 1)
        self.assertTrue(d.is_dir())
        self.assertEqual(mod.main(["--repo", str(self.repo), "--prune", "--quiet"]), 0)
        self.assertFalse(d.exists())
        self.assertEqual(mod.main(["--repo", str(self.repo), "--quiet"]), 0)

    def test_every_root_is_walked(self) -> None:
        """renders/ and docs/evidence/ hold the same kind of thing and were not covered at first."""
        self.add_evidence("in-renders", age_days=400, root="renders")
        self.add_evidence("in-docs", age_days=400, root="docs/evidence")
        self.add_evidence("in-design", age_days=400)
        prunable, _ = self.classify()
        self.assertEqual(
            sorted(prunable),
            sorted(["renders/in-renders", "docs/evidence/in-docs", f"{self.root}/in-design"]),
        )

    def test_same_name_in_two_trees_is_kept_apart(self) -> None:
        self.add_evidence("dup", age_days=400, root="renders")
        self.add_evidence("dup", age_days=400, root="docs/evidence")
        prunable, _ = self.classify()
        self.assertEqual(sorted(prunable), ["docs/evidence/dup", "renders/dup"])

    def test_missing_tree_is_not_an_error(self) -> None:
        empty = Path(self._tmp.name) / "no-evidence"
        empty.mkdir()
        git(empty, "init", "-q", "-b", "main")
        self.assertEqual(mod.classify(empty, 90, now=self.now), ([], {}))

    def test_a_root_that_does_not_exist_is_skipped(self) -> None:
        """Not every repository has all three trees."""
        import shutil as _sh

        _sh.rmtree(self.repo / "renders")
        self.add_evidence("spent", age_days=400)
        prunable, _ = self.classify()
        self.assertEqual(prunable, [f"{self.root}/spent"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
