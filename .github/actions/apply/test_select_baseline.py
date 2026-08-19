#!/usr/bin/env python3
"""Tests for select-baseline.py.

Pure stdlib (unittest) — no third-party deps so the test runs anywhere the
action runs. Run directly:

    python3 .github/actions/apply/test_select_baseline.py -v

Two layers. The selection *rule* is exercised against a synthetic ancestry
oracle, and the git plumbing around it against real repositories built in a
temp dir — the plumbing is where this can silently regress, because a wrong
answer there doesn't fail, it just quietly picks the branch tip again.

The script under test has a hyphen in its filename, so we load it via
importlib rather than a normal import.
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("select_baseline", _HERE / "select-baseline.py")
sb = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(sb)


class ParseBaselineSourceTest(unittest.TestCase):
    def test_reads_the_source_commit(self):
        self.assertEqual(
            sb.parse_baseline_source("Update preview baselines from 59ad0d1f"),
            "59ad0d1f",
        )

    def test_accepts_a_full_sha(self):
        full = "59ad0d1f" + "0" * 32
        self.assertEqual(
            sb.parse_baseline_source(f"Update preview baselines from {full}"), full
        )

    def test_history_refresh_commits_are_not_baselines(self):
        # `Update preview history from …` shares the baseline's tree but is a
        # follow-up commit, not a publication. Selecting one would pin Before
        # to a commit whose renders belong to the *previous* baseline.
        self.assertIsNone(
            sb.parse_baseline_source("Update preview history from 59ad0d1f")
        )

    def test_unrelated_subjects_are_ignored(self):
        self.assertIsNone(sb.parse_baseline_source("Preview renders for PR #38"))
        self.assertIsNone(sb.parse_baseline_source(""))


class SelectBaselineTest(unittest.TestCase):
    """The rule: newest published baseline that is *in* this PR's base."""

    ENTRIES = [("bl4", "m4"), ("bl3", "m3"), ("bl1", "m1")]

    def _ancestors(self, *shas):
        return lambda source: source in shas

    def test_picks_the_tip_when_the_tip_is_in_the_base(self):
        picked = sb.select_baseline(self.ENTRIES, self._ancestors("m4", "m3", "m1"))
        self.assertEqual(picked, ("bl4", "m4"))

    def test_walks_back_past_a_baseline_the_pr_does_not_contain(self):
        # The wear-m3-catalog#38 shape: main moved on after the PR branched, so
        # the tip baseline holds previews this render was never going to
        # produce. Diffing against it reports them as deletions.
        picked = sb.select_baseline(self.ENTRIES, self._ancestors("m3", "m1"))
        self.assertEqual(picked, ("bl3", "m3"))

    def test_falls_back_to_the_tip_when_nothing_qualifies(self):
        # None means "keep the branch tip" — the historical behaviour, which is
        # what this replaces, so it can never be a regression.
        self.assertIsNone(sb.select_baseline(self.ENTRIES, self._ancestors()))

    def test_empty_history_selects_nothing(self):
        self.assertIsNone(sb.select_baseline([], self._ancestors("m1")))


class SelectBaselineGitTest(unittest.TestCase):
    """End-to-end against real repositories.

    Fixture mirrors the failure this exists for: `main` is four commits, the
    baseline branch published from m1, m2 and m4 (m3's push was skipped as
    unchanged, or hadn't finished rendering), and the PR renders on m3. The
    only defensible Before is the m2 baseline — the tip belongs to a future the
    PR has never seen, and m1 is further behind than it needs to be.
    """

    @classmethod
    def setUpClass(cls):
        cls.root = Path(tempfile.mkdtemp())
        cls.env = {
            **os.environ,
            "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com",
        }
        cls.remote = cls.root / "remote.git"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(cls.remote)], check=True)

        work = cls.root / "work"
        cls._git("init", "-q", "-b", "main", str(work), cwd=cls.root)
        cls.main_shas = []
        for i in range(4):
            (work / f"f{i}.txt").write_text(f"{i}\n")
            cls._git("add", "-A", cwd=work)
            cls._git("commit", "-qm", f"main commit {i}", cwd=work)
            cls.main_shas.append(cls._out("rev-parse", "HEAD", cwd=work))
        m1, m2, m3, m4 = cls.main_shas

        # The PR: a feature commit off m3, then the merge ref GitHub builds for
        # it — first parent main, second parent the PR head.
        cls._git("checkout", "-q", "-b", "feature", m3, cwd=work)
        (work / "feature.txt").write_text("x\n")
        cls._git("add", "-A", cwd=work)
        cls._git("commit", "-qm", "the PR's own change", cwd=work)
        cls.pr_head = cls._out("rev-parse", "HEAD", cwd=work)
        cls._git("checkout", "-q", "-b", "prmerge", m3, cwd=work)
        cls._git("merge", "-q", "--no-ff", "-m", "Merge feature", cls.pr_head, cwd=work)

        # The baseline branch: an orphan line of commits whose *subjects* are
        # the only record of which main commit each was rendered from, with a
        # history-refresh commit interleaved exactly as the publisher writes it.
        cls._git("checkout", "-q", "--orphan", "compose-preview/main", cwd=work)
        cls._git("rm", "-rq", "--cached", ".", cwd=work)
        for path in work.glob("*.txt"):
            path.unlink()
        cls.baselines = {}
        for label, source in (("m1", m1), ("m2", m2), ("m4", m4)):
            (work / "baselines.json").write_text(json.dumps({label: source}))
            (work / "renders").mkdir(exist_ok=True)
            (work / "renders" / f"{label}.png").write_text(label)
            cls._git("add", "-A", cwd=work)
            cls._git("commit", "-qm", f"Update preview baselines from {source[:8]}", cwd=work)
            cls.baselines[label] = cls._out("rev-parse", "HEAD", cwd=work)
            cls._git("commit", "-q", "--allow-empty",
                     "-m", f"Update preview history from {source[:8]}", cwd=work)

        cls._git("push", "-q", str(cls.remote), "main", "feature", "prmerge",
                 "compose-preview/main", cwd=work)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.root, ignore_errors=True)

    @classmethod
    def _git(cls, *args, cwd):
        subprocess.run(["git", *args], cwd=str(cwd), check=True,
                       env=cls.env, capture_output=True)

    @classmethod
    def _out(cls, *args, cwd) -> str:
        return subprocess.run(["git", *args], cwd=str(cwd), check=True, env=cls.env,
                              capture_output=True, text=True).stdout.strip()

    def _clone(self, name, branch, *, depth=None) -> Path:
        dest = self.root / name
        cmd = ["git", "clone", "-q", "--branch", branch]
        # `--depth` is silently ignored for a path clone, which would leave the
        # shallow tests testing nothing; `file://` makes git take the transport
        # path and honour it (and, as a side effect, single-branch — the same
        # narrow refspec a real checkout has).
        source = str(self.remote)
        if depth:
            cmd += ["--depth", str(depth)]
            source = f"file://{self.remote}"
        cmd += [source, str(dest)]
        subprocess.run(cmd, check=True, env=self.env, capture_output=True)
        if depth:
            self.assertEqual(self._out("rev-parse", "--is-shallow-repository", cwd=dest), "true")
        return dest

    def _select(self, cwd, *, base_sha="") -> tuple[str | None, dict | None, str]:
        out_sha = cwd / "_baseline_commit"
        out_skew = cwd / "_baseline_skew.json"
        proc = subprocess.run(
            ["python3", str(_HERE / "select-baseline.py"),
             "--branch", "compose-preview/main",
             "--base-branch", "main",
             "--base-sha", base_sha,
             "--out-sha", str(out_sha), "--out-skew", str(out_skew)],
            cwd=str(cwd), env=self.env, capture_output=True, text=True,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        sha = out_sha.read_text().strip() if out_sha.exists() else None
        skew = json.loads(out_skew.read_text()) if out_skew.exists() else None
        return sha, skew, proc.stderr

    def test_merge_ref_checkout_picks_the_newest_baseline_in_the_prs_base(self):
        clone = self._clone("merge-ref", "prmerge")
        sha, skew, _ = self._select(clone)
        self.assertEqual(sha, self.baselines["m2"])
        # m3 is the base; m2 is the newest baseline in it. One commit of gap.
        self.assertEqual(skew["target"], self.main_shas[2])
        self.assertEqual(skew["drift"], 1)

    def test_the_selected_baselines_renders_are_local_afterwards(self):
        # `pipelines/compose.sh` runs `git archive <ref> renders` right after
        # this, from a shallow checkout. Fetching the baseline branch blobless
        # would satisfy selection and leave those trees behind a lazy promisor
        # fetch — whose failure mode is an empty baseline and a comment
        # claiming every preview is new. Assert the blobs are actually here.
        clone = self._clone("archive", "feature", depth=1)
        sha, _, _ = self._select(clone, base_sha=self.main_shas[2])
        self.assertEqual(sha, self.baselines["m2"])
        archived = subprocess.run(
            ["git", "-c", "remote.origin.promisor=false", "archive", sha, "renders"],
            cwd=str(clone), check=True, capture_output=True, env=self.env)
        self.assertIn(b"renders/m2.png", archived.stdout)

    def test_shallow_checkout_still_resolves_ancestry(self):
        # A `pull_request` checkout is depth-1: without deepening, every
        # ancestry question answers "no" and the tip wins by default.
        clone = self._clone("shallow", "feature", depth=1)
        sha, skew, _ = self._select(clone, base_sha=self.main_shas[2])
        self.assertEqual(sha, self.baselines["m2"])
        self.assertEqual(skew["drift"], 1)

    def test_a_base_with_no_gap_reports_no_skew(self):
        clone = self._clone("exact", "feature", depth=1)
        sha, skew, _ = self._select(clone, base_sha=self.main_shas[1])
        self.assertEqual(sha, self.baselines["m2"])
        self.assertEqual(skew["drift"], 0)

    def test_unknown_base_writes_nothing_and_succeeds(self):
        # Caller falls back to the branch tip. Never a hard failure: a preview
        # comment is worth more than a precise one that never posts.
        clone = self._clone("no-base", "feature", depth=1)
        sha, skew, stderr = self._select(clone, base_sha="0" * 40)
        self.assertIsNone(sha)
        self.assertIsNone(skew)
        self.assertIn("falling back", stderr)


if __name__ == "__main__":
    unittest.main()
