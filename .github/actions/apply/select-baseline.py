#!/usr/bin/env python3
"""Pick which baseline-branch commit a PR's preview diff compares against.

Background
----------
`pipelines/compose.sh` used to pin *Before* to whatever `compose-preview/main`
pointed at when the PR job happened to run. That tip is not the PR's base: a
baseline lands 5–20 minutes *after* the merge that produced it, and main keeps
moving while a PR sits open. So the diff was computed between two unrelated
points in history, and every preview the gap contains gets attributed to the
PR that happened to render inside it:

- baseline **behind** the PR's base — the merged PR's own New/Changed entries
  are replayed on the next PR to render (observed on wear-m3-catalog#24: a
  one-file change reported 16 new / 9 changed / 4 removed, the exact union of
  #20, #21 and #23, all merged 13 minutes earlier with their baseline still
  rendering).
- baseline **ahead** of the PR's base — previews that main gained after the PR
  branched are missing from the PR's render, so they surface as *Removed*
  (wear-m3-catalog#38's 36 "Removed" entries, none of which it deleted).

This script closes the second case outright and bounds the first: it walks the
baseline branch for the newest commit whose *source* main commit is an ancestor
of the PR's base, which is the closest published baseline the PR's render can
legitimately be compared against. Any remaining distance between that commit
and the PR's base is real, unavoidable skew — the baseline simply hasn't been
published yet — so it is measured here and reported in the comment rather than
silently folded into the diff.

Selection is best-effort by construction. Anything it can't establish — no
main history in a shallow checkout, an unparseable commit subject, a source
commit whose object isn't local — leaves the outputs unwritten, and the caller
falls back to the branch tip. It can therefore never be *worse* than the
behaviour it replaces.

Outputs (both optional, written only on success):
- ``--out-sha``  — the chosen baseline commit, one line. Consumed as the
  compare step's ``--base-ref``, so the Before images are pinned to it.
- ``--out-skew`` — JSON ``{selected, source, target, drift}`` describing the
  residual gap, passed to ``compare-previews.py compare --baseline-skew``.

Kept as pure functions plus a thin ``main`` so the selection rule is unit
tested against a synthetic ancestry oracle (`test_select_baseline.py`) without
building git repositories.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from typing import Callable, Iterable

# The subject `pipelines/compose.sh` writes for every baseline push, and the
# only channel that records which main commit a baseline was rendered from.
# Abbreviated to 8 in practice; accept the full range git might produce.
_BASELINE_SUBJECT = re.compile(r"^Update preview baselines from ([0-9a-f]{7,40})\b")


def parse_baseline_source(subject: str) -> str | None:
    """The main commit a baseline commit was rendered from, or None.

    The branch also carries `Update preview history from …` commits (the
    history.json refresh that follows each baseline push). Those share the
    baseline's tree but are not themselves baseline publications, so they must
    not be selectable — matching on the exact subject keeps them out.
    """
    m = _BASELINE_SUBJECT.match(subject.strip())
    return m.group(1) if m else None


def select_baseline(
    entries: Iterable[tuple[str, str]],
    is_ancestor: Callable[[str], bool],
) -> tuple[str, str] | None:
    """Newest entry whose source commit is an ancestor-or-equal of the target.

    ``entries`` is (baseline commit, source main commit), newest first — the
    order `git log` emits. Returns None when nothing qualifies, which the
    caller reads as "keep the tip".
    """
    for commit, source in entries:
        if is_ancestor(source):
            return commit, source
    return None


def _git(*args: str, cwd: str | None = None) -> tuple[int, str]:
    proc = subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True
    )
    return proc.returncode, proc.stdout.strip()


def _fetch(remote: str, ref: str, *, blobless: bool) -> bool:
    """Fetch `ref` with enough history to answer ancestry questions about it.

    Always through an explicit refspec: a single-branch clone carries a
    `remote.origin.fetch` covering only its own branch, so `git fetch origin
    <branch>` there lands in FETCH_HEAD and leaves `origin/<branch>` undefined.

    `blobless` splits the two refs this deals with, and the distinction is
    load-bearing:

    - The **base branch** is needed for commit topology alone, and a
      `pull_request` checkout is depth-1 — every parent sits behind the shallow
      graft, so `merge-base --is-ancestor` answers "no" for commits that plainly
      are ancestors. Deepen it, and skip the blobs, which are pure cost here.
    - The **baseline branch** is fetched whole. A plain fetch of a branch a
      shallow repo doesn't have yet already brings its complete history (the
      shallow boundary applies to the refs that were clamped, not to new ones),
      so there is nothing to deepen — and filtering it would leave the render
      trees behind a promisor fetch that `git archive` performs later, turning
      a lazy-fetch failure into an empty baseline and a comment claiming every
      preview is new.
    """
    refspec = f"+refs/heads/{ref}:refs/remotes/{remote}/{ref}"
    _, shallow_out = _git("rev-parse", "--is-shallow-repository")
    shallow = shallow_out == "true"
    attempts: list[list[str]] = []
    if blobless:
        if shallow:
            attempts.append(
                ["fetch", "--filter=blob:none", "--no-tags", "--unshallow", remote, refspec])
            attempts.append(["fetch", "--no-tags", "--deepen=2147483647", remote, refspec])
        attempts.append(["fetch", "--filter=blob:none", "--no-tags", remote, refspec])
    attempts.append(["fetch", "--no-tags", remote, refspec])
    for attempt in attempts:
        rc, _ = _git(*attempt)
        if rc == 0:
            return True
    return False


def _resolve(rev: str) -> str | None:
    rc, out = _git("rev-parse", "--verify", "-q", f"{rev}^{{commit}}")
    return out if rc == 0 and out else None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--branch", required=True, help="baseline branch, e.g. compose-preview/main")
    ap.add_argument("--base-branch", default="", help="the PR's base branch name (GITHUB_BASE_REF)")
    ap.add_argument("--base-sha", default="", help="the PR's base commit (pull_request.base.sha)")
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--out-sha", default="", help="file to write the chosen baseline commit to")
    ap.add_argument("--out-skew", default="", help="file to write the skew JSON to")
    args = ap.parse_args()

    def bail(reason: str) -> int:
        # Never fail the job: the caller's fallback (the branch tip) is exactly
        # the behaviour this script is an improvement on, not a dependency of.
        print(f"select-baseline: {reason}; falling back to the {args.branch} tip.",
              file=sys.stderr)
        return 0

    # The PR's base branch, deepened so ancestry is answerable at all. Both the
    # target and every baseline source commit live on it.
    if args.base_branch:
        _fetch(args.remote, args.base_branch, blobless=True)

    # What main state does this render actually contain? On a `pull_request`
    # run the workspace is the merge ref — main's tip merged with the PR head —
    # so its first parent is the exact main commit the render was built on, and
    # a better answer than `pull_request.base.sha`, which trails the merge ref
    # when the base branch moves between events. Fall back to the event's own
    # base sha when the checkout is the PR head rather than the merge ref.
    target = None
    if _resolve("HEAD^2"):
        target = _resolve("HEAD^1")
    if target is None and args.base_sha:
        target = _resolve(args.base_sha)
    if target is None:
        return bail("could not resolve the PR's base commit")

    if not _fetch(args.remote, args.branch, blobless=False):
        return bail(f"could not fetch {args.branch} history")

    rc, log = _git("log", "--format=%H %s", f"refs/remotes/{args.remote}/{args.branch}")
    if rc != 0 or not log:
        return bail(f"could not read {args.remote}/{args.branch} history")

    entries: list[tuple[str, str]] = []
    for line in log.splitlines():
        commit, _, subject = line.partition(" ")
        source = parse_baseline_source(subject)
        if source:
            entries.append((commit, source))
    if not entries:
        return bail(f"no baseline commits found on {args.branch}")

    def is_ancestor(source: str) -> bool:
        resolved = _resolve(source)
        if resolved is None:
            return False
        return subprocess.run(
            ["git", "merge-base", "--is-ancestor", resolved, target],
            capture_output=True,
        ).returncode == 0

    picked = select_baseline(entries, is_ancestor)
    if picked is None:
        return bail("no published baseline is an ancestor of this PR's base")
    commit, source = picked

    # Distance from the baseline we picked to the state the render was built
    # on. Non-zero means one of two things we can't tell apart from git alone:
    # commits whose baseline push was skipped as unchanged (harmless), or
    # commits whose baseline hasn't finished rendering (their preview changes
    # will show up in this PR's diff). Reported, not guessed at.
    rc, drift_out = _git("rev-list", "--count", f"{source}..{target}")
    drift = int(drift_out) if rc == 0 and drift_out.isdigit() else 0

    if commit == entries[0][0] and drift == 0:
        print(f"select-baseline: {args.branch} tip is exactly this PR's base; no skew.",
              file=sys.stderr)
    else:
        print(
            f"select-baseline: picked {commit[:8]} (rendered from {source[:8]}), "
            f"{drift} commit(s) behind this PR's base {target[:8]}; "
            f"branch tip is {entries[0][0][:8]}.",
            file=sys.stderr,
        )

    if args.out_sha:
        with open(args.out_sha, "w") as fh:
            fh.write(commit + "\n")
    if args.out_skew:
        with open(args.out_skew, "w") as fh:
            json.dump(
                {"selected": commit, "source": source, "target": target, "drift": drift},
                fh,
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
