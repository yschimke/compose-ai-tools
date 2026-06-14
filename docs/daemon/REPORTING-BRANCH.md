# The reporting branch

A long-lived git ref (default `refs/heads/preview/main`) that accumulates
render **history** — images *and* data products — so users and agents can ask
"what did this preview look like, and what were its a11y findings, an hour /
a commit / a release ago?" It is the durable, pushable counterpart to the
local [`.compose-preview-history/`](HISTORY.md) archive.

Part of the report-history epic (#1866); this document is sub-issue #1868. The
writer that produces the branch is #1870; the per-file formats are the
published schemas in [`schema/`](../../schema/README.md) (#1867).

## Model: git is the log

The branch does **not** re-implement an append log on top of git. It stores
**stable, overwritten paths per preview** and lets git's own commit history be
the timeline:

```
refs/heads/preview/<source-branch>
├── manifest.json                         # current-state pointer (formatVersion + index)
└── <sanitised-preview-id>/
    ├── render.png                        # overwritten each render
    ├── entry.json                        # HistoryEntry sidecar (schema: history-entry)
    ├── a11y.json                         # optional — a11y/hierarchy payload, when captured
    ├── a11y-atf.json                     # optional — a11y/atf payload
    ├── semantics.json                    # optional — compose/semantics payload
    └── theme.json                        # optional — compose/theme payload
```

- **One commit per *changed* render.** A render whose tree is byte-identical to
  branch HEAD produces **no commit** — dedup is free, and an unchanged preview
  contributes nothing to the log.
- **History is a git walk.** The history of a preview's pixels is
  `git log -- <previewId>/render.png`; "an hour ago" is the blob at
  `git rev-list -1 --until=<t> <ref>`; a11y / semantics history is the same walk
  over the sibling files; a diff over time is two blobs.
- **No `index.jsonl` on the branch.** The growing append index that the local
  FS source keeps is the thing that turns multi-writer pushes into per-push
  merge conflicts; the branch deliberately omits it (see *Concurrency*).

### Why not mirror the FS layout?

The local FS source keys every render as `<timestamp>-<hash>.{png,json}` and
appends to `index.jsonl`. That is correct for a **single writer** with cheap
random-access reads. Ported to a **shared, pushed** branch it would mean every
render is a new immortal file plus a contended append to one line-oriented
index — unbounded growth and a conflict on every concurrent push. Git already
gives us versioning, dedup (identical blobs stored once), and pruning
(shallow / expire), so the branch uses them instead of duplicating them.

The two sources stay complementary:

| | local FS source | reporting branch |
|---|---|---|
| layout | `<timestamp>-<hash>` files + `index.jsonl` | overwritten per-preview paths |
| timeline | the index (append) | git commits |
| dedup | two-tier hash ladder | empty-diff → no commit |
| pruning | explicit (delete + rewrite index) | shallow / expire / re-orphan |
| writers | one (the daemon) | many (worktrees / agents / CI) |
| `entryId` | `<utc-timestamp>-<short-hash>` | `<shortCommit>:<previewId>` |

## `manifest.json`

A **derived, optional** cache so a reader can answer "what exists now"
without walking the tree — **not** authoritative and **never hand-merged**.
The source of truth is the committed tree itself: the current state is
`git ls-tree <ref> -- '*/entry.json'`, one entry per preview directory. The
writer regenerates the manifest from that tree, and on a push race **rebuilds
it from the post-rebase tree rather than merging it** — so it is not a
multi-writer contention point the way a shared append index would be (see
*Concurrency*). A reader that distrusts a stale manifest can ignore it and walk
the tree directly.

```jsonc
{
  "formatVersion": 1,                    // bumped on incompatible layout changes
  "generatedAt": "2026-04-30T10:12:34Z",
  "commit": "6af6b8c1…",                 // the source-tree commit this state was rendered from
  "sourceBranch": "main",
  "previews": [
    {
      "previewId": "com.example.OnboardingKt.WelcomeScreenPreview",
      "module": ":samples:android",
      "dir": "com.example.OnboardingKt.WelcomeScreenPreview",
      "pngHash": "a1b2c3d4…",
      "dataProducts": ["a11y/hierarchy", "compose/semantics", "compose/theme"]
    }
  ]
}
```

`formatVersion` versions the **branch layout**; each committed file independently
declares its own report `schemaVersion` (per its schema in [`schema/`](../../schema/README.md)).

## entryId ↔ (commit, preview)

A reporting-branch `entryId` is `<shortCommit>:<previewId>`. `history/read` and
`history/diff` resolve it to a commit + the preview's directory and read the
blobs at that commit. This is the only shape difference from the FS source's
`entryId`; both round-trip through the same JSON-RPC surface
([HISTORY.md § Layer 2](HISTORY.md)). `entry.json` records the producing
working-tree commit in `git.commit`, so "main at commit X" resolves to the
branch commit whose entries were rendered from X.

## Concurrency

Multiple worktrees / agents / a CI job may push to the same ref. Because each
preview owns its own paths:

- renders of **different** previews touch disjoint per-preview paths and
  **auto-merge**;
- only renders of the **same** preview race, and those resolve with
  fetch–rebase–retry with backoff (the writer's job, #1870).

The one file every render touches is the top-level `manifest.json` — but it is
**derived, not hand-merged**: a writer that loses a race regenerates it from
the post-rebase tree, so it never produces a content conflict the way a shared
line-oriented append index (`index.jsonl`) would. That is the contention this
layout exists to avoid. This mirrors the push-race handling `design-parity`
already uses for its baseline branch
(`packages/action/src/github/publish.ts`).

## Retention

Reporting-branch retention is **git-native** — shallow history, ref expiry, or
a periodic re-orphan with a depth cap — never file deletion (deleting a file
just adds a commit). The most recent state is always branch HEAD. The local FS
source keeps its own explicit [pruning policy](HISTORY.md#pruning-policy)
unchanged; read-only sources (including this branch when consumed read-only)
skip FS pruning entirely.

## Relationship to design-parity

`design-parity` publishes a sibling reporting branch (`design-parity/<branch>`)
carrying `verdict.json` + per-component `report.html`. Aligning its
`verdict.json` on a shared `formatVersion` / schema convention is tracked in
design-parity#71, so a single versioned history format spans both projects and
feeds the hosted "drift over time" surface (design-parity#13).

## Status

`GitRefHistorySource` now implements **`WRITE_LOCAL`** (#1870): it writes the
git-as-the-log layout above via git plumbing (a throwaway index +
`hash-object` / `read-tree` / `update-index` / `write-tree` / `commit-tree` /
`update-ref`, never touching the working tree), commits one change per render
with content-based skip-if-no-diff, and reads back the **current** branch state
(one entry per preview). Enable it with `composeai.daemon.gitRefHistorySyncMode=WRITE_LOCAL`
alongside `composeai.daemon.gitRefHistory=<ref>`. The skip-if-no-diff predicate matches
`LocalFsHistorySource`'s (pixels + structural semantics) so the two writable sources never
disagree on what counts as a duplicate. The reader also falls back to the legacy read-only
format (`_index.jsonl` + `<entryId>.{png,json}`) for refs that predate this layout.

Still to land (follow-ups):

- **`WRITE_PUSH`** — also `git push` the ref, with fetch–rebase–retry on a push
  race (needs a remote + credentials).
- **Commit-walk timeline read** — `list` / `read` over the full history of a
  preview (and the `<shortCommit>:<previewId>` entryId addressing), rather than
  only the current state. Spec'd here; tracked under #1868.
- **Burst debounce** — batch a render burst into one commit instead of one
  commit per render.
