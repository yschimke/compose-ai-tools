# Preview history — design

> **Status:** design only. Capturing the on-disk schema, JSON-RPC surface,
> and consumer mappings so the daemon, the existing Gradle path, the
> MCP server, and the VS Code extension can all agree on what "history"
> means before any of them ship code.

## What this is

Today, the Gradle plugin already archives rendered PNGs into a
`.compose-preview-history/` directory when `composePreview.historyEnabled = true`.
Layout is simple — a folder per preview, files named
`yyyyMMdd-HHmmss.png`, dedup'd by SHA-256 against the latest entry.
See [HistorizePreviewsTask.kt](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/HistorizePreviewsTask.kt).

When the daemon runs, it produces renders too — many more, much faster.
A consumer (VS Code, an MCP-aware agent, a future CI diff tool) wants
to ask:

- "What did this preview look like an hour ago?"
- "Did the last save change the rendered bytes, or just the source?"
- "Show me the diff between this render and the version on `main`."
- "Which previews changed pixels in the last 10 saves?"

This doc pins how that surface looks: storage schema (extending the
existing dir without breaking it), JSON-RPC API on the daemon,
mappings to MCP resources and the VS Code panel UI, pruning policy.

## Goals & non-goals

**Goals**:

1. One on-disk archive per module *per worktree*. Both the Gradle path
   and the daemon write to it. Both VS Code and MCP read from it.
2. **Cross-worktree browsability.** Two agents on separate worktrees,
   each running their own daemon for the same module, produce two
   archives. A consumer (the MCP server, or VS Code with multi-root
   workspaces) can merge them into one timeline keyed by branch +
   commit + worktree path. See § Branches and worktrees.
3. Append-only entries. The archive is a log, not mutable state.
4. Layering ([LAYERING.md](LAYERING.md)) preserved: history is a
   Layer 2 (daemon) JSON-RPC surface. Layer 3 (MCP) maps it and
   handles cross-worktree multiplexing. Layer 1 (Gradle) writes to
   the same dir but doesn't import Layer 2.
5. Bounded growth: configurable pruning by count, age, total size.
6. Survives daemon restart and `./gradlew clean` (the dir already
   lives outside `build/`).
7. Every history entry is independently meaningful — no entry depends
   on a separate index file to be readable.

**Non-goals**:

1. **Not a render cache.** The daemon doesn't serve renders from
   history to satisfy `renderNow`. History is observation, not
   substitution. (Caching is a separate concern; if it lands later,
   it's a different code path.)
2. **Not cross-module.** Each module owns its own history dir.
   Consumers that span modules iterate per module, the way they
   already do today.
3. **Not a baseline-image golden test.** The plugin already has
   `:samples:android-screenshot-test` for that. History archives
   what *did* render; baselines pin what *should* render. Two
   different jobs.
4. **Not retroactive.** Existing PNG-only entries (pre-design) keep
   working in degraded mode (no sidecar metadata) but new entries
   land with the full schema below.

## On-disk schema

### Directory layout

```
<historyDir>/                                 # default $projectDir/.compose-preview-history
├── index.jsonl                               # append-only log of every entry
├── <sanitised-preview-id>/                   # one folder per preview
│   ├── 20260430-101234-a1b2c3d4.png          # the rendered bytes
│   ├── 20260430-101234-a1b2c3d4.json         # sidecar metadata (one per png)
│   ├── 20260430-101307-9f8e7d6c.png
│   ├── 20260430-101307-9f8e7d6c.json
│   └── ...
└── ...
```

Filename shape: `<utc-timestamp>-<short-hash>.{png,json}`. The
timestamp is `yyyyMMdd-HHmmss` (mirrors today's Gradle layout); the
short hash is the first 8 hex chars of the PNG's SHA-256. Together
they're collision-free across renders that happen inside the same
second.

### Sidecar metadata schema

Every `.png` lands with a sibling `.json`. Content:

```ts
{
  // Identity
  "id": "20260430-101234-a1b2c3d4",            // matches the filename stem
  "previewId": "com.example.RedSquare",         // the previewId from previews.json
  "module": ":samples:android",                 // module project path
  "timestamp": "2026-04-30T10:12:34Z",          // ISO 8601 UTC

  // Bytes
  "pngHash": "a1b2c3d4e5f6...",                 // full SHA-256 hex
  "pngSize": 4218,                              // bytes
  "pngPath": "20260430-101234-a1b2c3d4.png",   // relative to this sidecar

  // Provenance — who rendered this
  "producer": "daemon",                         // "daemon" | "gradle" | "manual"
  "trigger": "fileChanged",                     // see § Trigger taxonomy
  "triggerDetail": {
    "kind": "source",
    "path": "/abs/path/Foo.kt"
  },

  // Provenance — which storage backend this entry was read from
  // (See § History sources. Filled in by the reader, not the writer.)
  "source": {
    "kind": "fs",                                // "fs" | "git" | "http"
    "id": "fs:/home/yuri/.../.compose-preview-history"
  },

  // Provenance — where it was rendered (see § Branches and worktrees)
  "worktree": {
    "path": "/home/yuri/workspace/compose-ai-tools",  // absolute worktree root
    "id": "main",                                       // optional human label; defaults to basename
    "agentId": null                                      // optional; populated when COMPOSEAI_AGENT_ID env is set
  },
  "git": {
    "branch": "agent/preview-daemon-streamAB",         // current branch; null in detached HEAD
    "commit": "6af6b8c1d4e2f7a8b9c0d1e2f3a4b5c6d7e8f9a0",
    "shortCommit": "6af6b8c",
    "dirty": true,                                      // true when working tree has uncommitted changes
    "remote": "https://github.com/yschimke/compose-ai-tools"  // for cross-checkout repo identity
  },

  // Render context
  "renderTookMs": 234,
  "metrics": {                                  // mirror of RenderFinished.metrics
    "heapAfterGcMb": 312,
    "nativeHeapMb": 540,
    "sandboxAgeRenders": 17,
    "sandboxAgeMs": 81234
  },

  // Preview metadata snapshot — frozen at render time so future
  // discovery changes don't rewrite history
  "previewMetadata": {
    "displayName": "Red square",
    "group": "buttons",
    "sourceFile": "/abs/path/Foo.kt",
    "config": "phone-portrait"
  },

  // Optional — only present if previous entry exists
  "previousId": "20260430-101207-9f8e7d6c",
  "deltaFromPrevious": {                        // empty when bytes are identical
    "pngHashChanged": true,
    "diffPx": 142,                              // populated lazily; null until computed
    "ssim": null
  }
}
```

### `index.jsonl`

One JSON object per line, append-only. Same fields as the sidecar
minus `previewMetadata` (callers wanting that hit the sidecar
directly). Exists so consumers can scan "everything that happened
across all previews" without walking the whole tree.

Concurrency: the index is opened in `O_APPEND` mode; each line is
under POSIX `PIPE_BUF` so writes are atomic. Readers seeing torn
lines on macOS edge cases skip them — the per-preview sidecars are
the source of truth, the index is the convenience.

### Greenfield — no legacy data

The legacy `HistorizePreviewsTask` (Gradle-side PNG-only writer) was removed in PR #311 along with
the unused VS Code Preview History panel. H1 daemon writes are the only writer, so the Layer 2
reader does NOT have to tolerate PNG-only entries — every entry on disk has a sibling sidecar
because the daemon wrote both atomically.

## Trigger taxonomy

The `trigger` field documents *why* the daemon (or Gradle) rendered
this preview. Vocabulary:

| trigger              | who emits | meaning |
|----------------------|-----------|---------|
| `initial`            | daemon    | first render after daemon start |
| `renderNow`          | daemon    | explicit `renderNow` request from a client |
| `fileChanged`        | daemon    | source/resource/classpath edit triggered the render |
| `discoveryUpdated`   | daemon    | preview was newly discovered or its metadata changed |
| `setVisible`         | daemon    | preview entered the visible set (B2.5+ — focus-driven) |
| `recycleResume`      | daemon    | sandbox recycle re-rendered visible previews |
| `gradleTask`         | —         | reserved (legacy `HistorizePreviewsTask` was removed in PR #311); not currently emitted |
| `manual`             | either    | external trigger (CLI flag, MCP `render_preview` tool) |

`triggerDetail` is a free-form object whose shape is per-trigger
(e.g. `fileChanged` carries `{kind, path}`; `gradleTask` carries
`{taskName, buildId}`). Consumers ignore unknown shapes.

## Branches and worktrees

The user's multi-agent workflow puts two (or more) agents on separate
git worktrees of the same repo, each with its own daemon, each
rendering previews independently. The history feature must support
browsing across those worktrees as one timeline keyed by branch and
commit, not as N disjoint archives.

### Storage stays per-worktree

Each worktree owns its `.compose-preview-history/` (today's behaviour
— and the simple thing). The daemon embedded in worktree A writes to
A's archive; the daemon in worktree B writes to B's. No shared state
on disk, no cross-process locks, no centralised dir to garbage-
collect when a worktree is removed.

Provenance fields above (`worktree.path`, `git.branch`, `git.commit`,
`git.dirty`, `git.remote`) are stamped on every entry. The remote URL
is the cross-checkout identity: two worktrees of the same repo carry
the same `git.remote` value, so the MCP server (or any consumer) can
discover that two archives belong to the same project.

### Cross-worktree merging is consumer-side

The Layer 2 daemon doesn't reach across worktrees — that would
violate "each daemon is parent-PID-bound, stdio-only, no
cross-process state." Multi-worktree browsing happens *above* the
daemon, in the consumer:

```
                 ┌─ MCP server (or VS Code) ─┐
                 │  merges + filters by      │
                 │  branch / worktree / time │
                 └──┬─────────────────┬──────┘
                    │                 │
        ┌───────────▼────┐   ┌────────▼──────┐
        │  Daemon A      │   │  Daemon B     │
        │  worktree A    │   │  worktree B   │
        │  branch:main   │   │  branch:agent │
        └────────────────┘   └───────────────┘
```

The consumer holds a registry of known worktrees (from VS Code's
multi-root workspace, or the MCP `DaemonSupervisor`'s registered
modules) and merges the streams. Merge key: `(git.commit, previewId,
timestamp)` — newest first across all sources, deduped on full
`pngHash`.

### Initialize-time provenance

The daemon resolves git provenance once at startup (or lazily on
first render). It runs:

- `git rev-parse --show-toplevel` → `worktree.path`
- `git symbolic-ref --short HEAD` → `git.branch` (null on detached HEAD)
- `git rev-parse HEAD` → `git.commit`
- `git status --porcelain` non-empty → `git.dirty = true`
- `git remote get-url origin` → `git.remote`

Resolution failure (no `.git`, no `origin`, etc.) populates the
fields with nulls and continues. History still works without git
provenance; cross-worktree merging just degrades to per-worktree
browsing.

The daemon refreshes `git.branch` and `git.commit` on every render
(cheap — single `git rev-parse` invocation on the worktree the daemon
already lives in). `git.dirty` is also re-checked per render —
watching a single bit flip when the user saves is exactly the signal
a consumer wants. `git.remote` is captured once.

### Branch in the URI scheme

MCP resource URIs encode branch + commit so a single URI is
unambiguous across worktrees:

```
compose-preview-history://samples-android/com.example.RedSquare
                          /<branch>@<shortCommit>
                          /20260430-101234-a1b2c3d4
```

When the same preview was rendered on `main` and on
`agent/preview-daemon-streamAB`, an MCP client sees both; when it
asks "show me history for `RedSquare`", it gets entries from both
branches interleaved by timestamp, with branch labels.

URI parsing is forward-compat: legacy URIs without a `<branch>@<sha>`
segment resolve to "any branch" (cross-branch listing).

### Diff across branches

`history/diff` extends naturally:

- `from = "<entryId on main>"`, `to = "<entryId on agent/foo>"` —
  pixel diff of the same preview between two branches.
- `mode: "branch-tip"` shortcut — `from = current branch's most
  recent entry for previewId X`, `to = main's most recent entry for
  previewId X`. Daemon resolves both ends from its own knowledge of
  the consumer-supplied worktree set.

This is the load-bearing feature for "did my agent's edits change
how this preview renders?". Today the agent has to manually compare
PNGs between worktrees; with this it's one RPC call.

### Worktree IDs

`worktree.id` is a human label, not a primary key. Defaults to the
worktree dir's basename (`compose-ai-tools` for the main checkout,
`agent-a8a1bfe8` for an agent worktree). Users override via
`composePreview { history { worktreeId = "main" } }` or via the
`COMPOSEAI_WORKTREE_ID` environment variable.

The primary key for a render is `(git.commit, git.dirty,
worktree.path, timestamp)` — cryptographically unambiguous. The
`worktree.id` is just for the UI.

### Agent attribution

When the daemon is spawned by an automated agent (Claude Code's
agent harness, a CI bot, etc.), the agent can self-identify via the
`COMPOSEAI_AGENT_ID` environment variable. This populates
`worktree.agentId` on every entry the daemon writes. Consumers can
filter — "show me only renders triggered by agent X" — without
having to introspect process trees.

This is opt-in. Empty / null values are fine.

### Worktree-aware listing

`history/list` (Layer 2) gains optional filters:

```ts
params: {
  ...
  branch?: string;                // exact match; "main" / "agent/foo"
  branchPattern?: string;          // regex
  commit?: string;                 // exact short or long sha
  worktreePath?: string;           // absolute path filter
  agentId?: string;                // worktree.agentId filter
}
```

These all live in the per-worktree archive — the daemon never
crosses worktree boundaries. The consumer-side merge layer applies
the same filters across multiple daemons' responses.

## History sources

History needs to be pluggable across more than just the local
filesystem. The user surfaced two motivating cases that point at the
same shape:

1. **Cross-worktree (above).** Each worktree has its own local-FS
   archive; the consumer merges across them at read time.
2. **Git-tracked render history.** A repo can publish a `preview/main`
   (or `preview/<branch>`) ref containing every rendered PNG +
   sidecar as a tree. Every clone of the repo gets the full history
   for free; PRs can link to "the rendered version of this preview
   before vs after"; CI can push render snapshots after every merge.

Other sources fall out naturally: a CI artifact bucket, an HTTP
mirror, an S3 prefix. They all return the same `HistoryEntry`
shape. We pick a `HistorySource` interface now so any of them can
be added without re-shaping the consumer side.

### `HistorySource` interface (Layer 2, in `:daemon:core`)

```kotlin
interface HistorySource {
  val id: String                   // stable identifier ("fs:/abs/path", "git:preview/main", "ci:gh-actions")
  suspend fun list(filter: HistoryFilter): HistoryListPage
  suspend fun read(entryId: String): HistoryReadResult?       // null = not in this source
  suspend fun supportsWrites(): Boolean                        // FS=true; git/HTTP=false
  suspend fun write(entry: HistoryEntry, png: ByteArray)       // throws if !supportsWrites()
  fun watch(): Flow<HistoryEvent>                              // pushes added/removed; sources that can't watch return emptyFlow()
}
```

The daemon's `historyManager` holds an ordered list of sources.
Reads merge across all configured sources; writes go to the first
writable source (typically the local-FS one). Sources are
configured in the launch descriptor and propagate via the Layer 1
DSL (see § Layer 1 — Gradle path).

### Built-in sources

#### `LocalFsHistorySource`

Today's `.compose-preview-history/` directory. Default. Always
configured. Read-write.

- `id`: `fs:<absoluteHistoryDir>`
- `watch()` returns a flow backed by `WatchService` (or polling
  fallback) on the per-preview folders.

#### `GitRefHistorySource` — the `preview/main` idea

```kotlin
GitRefHistorySource(
  repoRoot: Path,                                  // worktree the source is rooted in
  refPattern: String = "refs/heads/preview/{branch}", // e.g. preview/main, preview/agent/foo
  trackingBranch: String = "main",                  // which working branch this preview ref mirrors
  syncMode: GitSyncMode = GitSyncMode.READ_ONLY,    // see below
)
```

Storage convention on the ref:

```
preview/main (ref)                      one commit per render burst
└── tree
    ├── <previewId>/
    │   ├── 20260430-101234-a1b2c3d4.png
    │   └── 20260430-101234-a1b2c3d4.json
    └── _index.jsonl                    aggregate of every entry under this commit
```

Each render burst (e.g. one save → N renders in a minute) is one
commit on the `preview/<branch>` ref, message body = the same JSON
the consumer would have built from sidecars. Old commits stay
reachable; the ref itself only ever moves forward.

`HistoryEntry.git.commit` stamped at render time tells the consumer
which working-tree commit produced this render. The
`preview/<branch>` ref is a *parallel* history that mirrors `<branch>`
without being its parent.

**Sync modes**:

- `READ_ONLY` (default — landed in H10-read) — consumer-side merging only. The daemon reads from
  `git show preview/main:<previewId>/<file>`. Writes go to the local-FS source as usual. Clones
  get whatever the remote has; nothing is pushed automatically.

  **Configuration (H10-read landing).** The daemon picks up read-only refs from the
  `composeai.daemon.gitRefHistory` system property — comma-separated full ref names, e.g.
  `refs/heads/preview/main,refs/heads/preview/agent/foo`. Empty / unset means no
  `GitRefHistorySource`s are constructed. Default suggestion for the common case:
  `composeai.daemon.gitRefHistory=refs/heads/preview/main`.

  **Ref-missing behaviour.** When the configured ref isn't present locally
  (`git rev-parse --verify <ref>` returns non-zero — common in greenfield repos because CI doesn't
  push `preview/main` yet), the source emits a one-time warn-level message via the daemon's log
  channel:

  ```
  GitRefHistorySource: ref '<ref>' is not present locally.
    Hint: populate it by fetching from a remote (e.g. `git fetch origin <ref>:<ref>`)
    or set up CI to push render history on each merge to <branch>.
    Until then, main-history comparison will not be available.
  ```

  After the warning the source degrades gracefully — `list` returns an empty page, `read` returns
  null. The daemon does NOT fail; the consumer just sees "no main-history available" and
  `history/diff` against a missing ref entry returns `HistoryEntryNotFound (-32010)`.
- `WRITE_LOCAL` — the daemon writes a commit to the local
  `preview/<branch>` ref after each render burst (debounced, e.g.
  every 30s). No network. Pushes are user-driven.
- `WRITE_PUSH` — like `WRITE_LOCAL` plus `git push` on the same
  debounce. Requires the daemon's `git push` to authenticate; the
  daemon doesn't manage credentials, so this only works when the
  user has a credential helper or SSH key in the standard locations.
  Off by default; user opts in when they accept the implications.

**Garbage collection**: a render burst commits binary blobs into the
ref. Storage grows. Mitigations:

- Git-LFS support — `*.png filter=lfs` on the `preview/*` refs.
  Sidecars stay inline (small JSON), PNGs go to LFS storage.
- Squash-old-history — daemon-side periodic `git rebase --root` on
  the `preview/<branch>` ref to merge entries older than N days into
  one "archived" commit. Consumer reads stay consistent because the
  entry IDs are based on commit-blob content hash, not on the
  history shape.
- Or just shallow-clone the ref — `git fetch --depth=1
  preview/main` keeps the working set small for casual readers.

**Cross-checkout sharing.** This is the load-bearing benefit: an
agent on worktree A renders a preview, the daemon writes to A's
local-FS source AND to A's `preview/agent/foo` ref. A user on
worktree B fetches the ref (`git fetch origin preview/agent/foo`)
and sees A's renders without any out-of-band coordination. The
existing remote machinery (review tools, push policies, branch
protection) all just work.

#### `HttpMirrorHistorySource` (sketch — not phase-1)

```kotlin
HttpMirrorHistorySource(
  baseUrl: String,                  // e.g. https://compose-previews.s3.amazonaws.com/repo-foo/
  authHeader: String? = null,       // optional bearer / basic
)
```

Reads `<baseUrl>/index.jsonl` for listing, `<baseUrl>/<entryId>.png`
for bytes. Read-only. Useful for CI artifact servers, S3 prefixes
fronted by CloudFront, internal mirrors. Out of scope for the
initial design but the interface accommodates it.

### Source configuration

Layer 1 DSL gains source declarations:

```kotlin
composePreview {
  history {
    enabled = true
    dir = file(".compose-preview-history")     // legacy → LocalFsHistorySource

    // optional additional sources
    git {
      ref = "preview/main"                      // pattern uses {branch} for branch name
      sync = GitSyncMode.WRITE_LOCAL
      lfs = true                                // attribute pngs to git-lfs
    }
    httpMirror("https://ci.example.com/previews/repo-foo/") {
      authHeader = providers.environmentVariable("PREVIEW_MIRROR_TOKEN")
    }
  }
}
```

The Gradle plugin emits each source's config in the daemon launch
descriptor; the daemon constructs `HistorySource` instances on
startup. New sources land as new `HistorySource` implementations
without any change to the consumer-facing API.

### Wire-format effects

Every `HistoryEntry` carries the source it came from:

```ts
{
  ...
  "source": {
    "kind": "fs" | "git" | "http",
    "id": "fs:/abs/path" | "git:preview/main@<sha>" | "http:https://...",
    // kind-specific extras
    "gitRef": "preview/main",
    "gitCommit": "abc123..."
  },
  ...
}
```

(This generalises the earlier `source: "daemon" | "gradle" | "manual"`
field — that becomes `producer` instead, since `source` now describes
the storage backend not the renderer.)

`history/list` filters can address sources:

```ts
params: {
  ...
  sourceKind?: "fs" | "git" | "http";
  sourceId?: string;        // exact source id match
}
```

### Ordering semantics

When a single render lands in multiple sources (LocalFs + git
WRITE_LOCAL), the consumer dedups on full `pngHash + previewId +
git.commit`. Source priority for "which entry to surface as
canonical" is:

1. The first writable source (typically LocalFsHistorySource) —
   freshest, present immediately after render.
2. Git refs — present after the debounced commit.
3. HTTP mirrors — present after CI / cron sync.

The other sources are still listable; they just aren't the default
"most recent" entry when the same render exists in multiple places.

## Layer 2 — JSON-RPC API

The daemon exposes the following methods on the existing JSON-RPC
channel. All are read-only or write-via-render — there's no
separate "write to history" RPC; history entries appear as a side
effect of `renderNow` / discovery / save-loop activity.

### `history/list` (request, client → daemon)

```ts
params: {
  previewId?: string;            // filter to one preview; default = all
  since?: string;                 // ISO timestamp lower bound
  until?: string;                 // ISO timestamp upper bound
  limit?: number;                 // default 50, max 500
  cursor?: string;                // opaque pagination token from a previous response
}

result: {
  entries: HistoryEntry[];        // newest first
  nextCursor?: string;            // present when more available
  totalCount: number;             // matching entries before pagination
}
```

`HistoryEntry` is the sidecar JSON above, minus the embedded
`previewMetadata` (caller pulls that from `history/read` if needed).

### `history/read` (request, client → daemon)

```ts
params: { id: string }            // entry id (the filename stem)
result: {
  entry: HistoryEntry;
  previewMetadata: PreviewInfoDto;
  pngPath: string;                // absolute path; daemon writes here, client reads
  pngBytes?: string;              // base64; included only when client opts in via params
}
```

Two modes — path-based and bytes-based. Local clients (VS Code on
the same host) prefer paths. Remote MCP clients want bytes. The
daemon picks based on a request param `inline: boolean`.

### `history/diff` (request, client → daemon)

```ts
params: { from: string; to: string; mode?: "metadata" | "pixel" }
result: {
  pngHashChanged: boolean;
  diffPx?: number;                 // pixel-different count; populated when mode=="pixel"
  ssim?: number;                   // structural similarity; populated when mode=="pixel"
  diffPngPath?: string;            // path to a marked-diff PNG; populated when mode=="pixel"
  fromMetadata: HistoryEntry;
  toMetadata: HistoryEntry;
}
```

`mode = "metadata"` (default) is cheap: hash compare + sidecar diff.
`mode = "pixel"` runs a real pixel comparison and writes a diff PNG
into a sibling `.diffs/` subfolder for the consumer to read.

### `history/prune` (request, client → daemon)

```ts
params: {
  maxEntriesPerPreview?: number;
  maxAgeDays?: number;
  maxSizeBytes?: number;
  dryRun?: boolean;
}
result: {
  removedEntries: string[];        // ids
  freedBytes: number;
}
```

Manual prune trigger. Auto-prune runs on a configurable interval
(default 1h) using the same logic with config defaults. The dry-run
mode lets a consumer ask "what would auto-prune do?" without
mutating the archive.

### `historyAdded` (notification, daemon → client)

```ts
params: { entry: HistoryEntry }
```

Emitted whenever a render lands a new entry on disk. Mirrors the
shape of `discoveryUpdated`. Clients that subscribe avoid polling.

The `pngHash` field lets a subscriber decide cheaply whether the
bytes are different from the previous render — if not, skip
re-fetching.

### `historyPruned` (notification, daemon → client)

```ts
params: { removedIds: string[]; freedBytes: number; reason: "auto" | "manual" }
```

Lets clients invalidate any cached references to entries that just
disappeared.

### Error codes

- `HistoryEntryNotFound` (-32010) — `history/read` or `history/diff` referenced a missing id.
- `HistoryDiffMismatch` (-32011) — `history/diff` received two entries from different previews (pixel diff would be meaningless).

## Layer 3 — MCP mapping

(See [MCP.md](MCP.md) and [MCP-KOTLIN.md](MCP-KOTLIN.md) for the
underlying MCP server design.)

### Resources

Each history entry maps to one MCP resource:

```jsonc
{
  "uri": "compose-preview-history://samples-android/com.example.RedSquare/20260430-101234-a1b2c3d4",
  "name": "RedSquare @ 2026-04-30 10:12:34",
  "title": "Red square — 2026-04-30 10:12:34Z",
  "mimeType": "image/png",
  "size": 4218,
  "annotations": {
    "audience": ["user", "assistant"],
    "priority": 0.4,
    "lastModified": "2026-04-30T10:12:34Z"
  }
}
```

URI scheme: `compose-preview-history://<module>/<previewFqn>/<entryId>`.
Distinct from the live-preview `compose-preview://` scheme so
clients can disambiguate "current state" from "snapshot".

`resources/list` with no filter returns recent entries across all
previews (default cap: 50, configurable). With a `?previewId=…`
query parameter (encoded into the URI cursor), returns history for
one preview.

`resources/read` returns the PNG via the standard `blob` field
(base64). Calls `history/read` under the hood with `inline: true`.

### Subscriptions

- `resources/subscribe` with the live `compose-preview://` URI — the
  client receives `notifications/resources/updated` for that preview
  AND `notifications/resources/list_changed` whenever a new history
  entry lands for it. Consumers can decide whether to re-render or
  walk back through history.
- `resources/subscribe` with a `compose-preview-history://` URI —
  immutable resource; the server may return an error or just
  silently no-op the subscription. v1 prefers no-op for forward
  compat. (History entries are append-only; an existing entry's
  bytes never change.)

### Tools

- `history_list({previewId?, since?, limit?})` — proxy for
  `history/list`. Mostly redundant with `resources/list`, but lets an
  agent ask without traversing the resource list.
- `history_diff({from, to, mode?})` — proxy for `history/diff`.
  Returns the diff sidecar and a `compose-preview-history-diff://…`
  URI the agent can `resources/read` for the diff PNG.

`history_prune` is **not** exposed as an MCP tool. Pruning is a
host-process concern; an MCP client shouldn't be able to delete bytes
from the archive.

## VS Code integration

### Scope guidance — v1 is single-preview only

Until the workflow proves itself, the panel **shows the timeline for
exactly one preview at a time** — the one whose card the user clicked
the history button on. No cross-preview aggregated view. No "everything
that happened across the module" feed. Reasons:

- Reduces UI surface (no filters, no preview-selector dropdown).
- Reduces wire load — `history/list` is called with a `previewId`
  filter, not unbounded.
- The cross-preview view is genuinely speculative — it's not clear yet
  what shape the user actually wants when they're looking at "history
  for this whole module." Single-preview is the cheap, obviously
  useful thing to ship first.

Cross-preview / module-wide views are a follow-up once we have user
signal on the per-preview flow.

### Per-preview panel (v1)

A "Preview History" drawer on each preview card:

- Scrollable timeline for the one preview, newest first
- Each row: thumbnail + timestamp + trigger reason + bytes-changed indicator
- Click to expand: full PNG + metadata table
- Select two rows + "Diff" button → runs `history/diff(mode:"pixel")`,
  shows the marked diff PNG inline
- "Open in Editor" reveals the source file at `previewMetadata.sourceFile`

Storage assumptions:
- Daemon running → calls `history/list` over the existing JSON-RPC.
- Daemon not running → falls back to direct filesystem read of
  `<historyDir>/index.jsonl` + sidecars. Same data; no daemon
  required to browse historical state.

The VS Code extension does NOT mutate history. Pruning happens
daemon-side or via the Gradle path's pruning hook (see § Pruning).

## Concurrency model

The daemon is the only writer. The legacy Gradle-side `HistorizePreviewsTask` was removed in
PR #311; there is no second writer to coordinate with.

- **Filenames are collision-free.** Timestamp + 8-hex-of-SHA gives
  practical uniqueness. The dedup-by-SHA logic on top suppresses
  re-writing identical bytes for the same preview.
- **Index `O_APPEND` writes.** Atomic for sub-`PIPE_BUF` lines, which
  the schema fits comfortably.
- **Reader-side robustness.** Readers tolerate truncated index lines
  (skip), missing sidecars (drop the entry from listings — self-healing on next prune), and
  symlinked entries (follow once).

## Pruning policy

Configured via the existing `composePreview { … }` DSL plus a new
nested block:

```kotlin
composePreview {
  history {
    enabled = true                 // existing
    dir = file(".history")         // existing
    maxEntriesPerPreview = 50      // new; default 50
    maxAgeDays = 14                // new; default 14
    maxTotalSizeBytes = 500_000_000 // new; default 500 MB
    autoPruneIntervalMin = 60      // new; daemon-only; default 60min
  }
}
```

Pruning order (run in sequence, each pass refreshes):

1. **Age**: drop entries older than `maxAgeDays`.
2. **Per-preview count**: keep newest `maxEntriesPerPreview` per
   preview folder.
3. **Total size**: if the archive still exceeds `maxTotalSizeBytes`,
   drop oldest entries across all previews until under threshold.

Pruning never drops the most recent entry per preview, even if it
violates a size cap — the diff feature requires at least one prior
entry to be useful.

The daemon emits `historyPruned` after each auto-prune. Manual
prunes (via `history/prune`) emit one notification with the full
removed set.

## Layering rules

(Restating [LAYERING.md](LAYERING.md) in history-specific terms.)

| Module | Reads history? | Writes history? | Imports |
|---|---|---|---|
| `:gradle-plugin` (Layer 1) | no | no (legacy writer removed in PR #311) | nothing daemon-side |
| `:daemon:core` (Layer 2) | yes (`history/list`, `history/read`, `history/diff`) | yes (per render) | none beyond Messages.kt |
| `:daemon:android`, `:daemon:desktop` (Layer 2) | no — they call into `:daemon:core` `JsonRpcServer` | no | `:daemon:core` |
| `:daemon:mcp` (Layer 3) | yes — JSON-RPC client of Layer 2; merges across worktrees consumer-side | no | `:daemon:core` (types only) |
| VS Code extension | yes — JSON-RPC client of Layer 2, OR direct filesystem | no | nothing daemon-side |

The daemon is the only writer to its own LocalFs source. Git / HTTP
sources are Layer 2 plug-ins that don't escape the daemon's process.
Cross-worktree merging happens above Layer 2 — at the MCP server or
in the editor — never inside a daemon.

## File-format versioning

The sidecar JSON has an implicit version-zero shape (no `schemaVersion`
field). Future schema changes:

1. **Additive fields** — readers ignore unknown keys (`ignoreUnknownKeys`).
   No version bump needed.
2. **Breaking changes** — add an explicit `schemaVersion: 2` field;
   readers without v2 awareness fall back to "skip this entry".

The `index.jsonl` follows the same rule. Any breaking change writes
a sentinel header line `{"schemaVersion": 2}` at the top of a fresh
index; old indices stay readable as v0.

## Phasing

| Phase | Scope | Owner | Depends on |
|---|---|---|---|
| ~~**H0**~~ | ~~Sidecar schema + index.jsonl emission from `HistorizePreviewsTask`~~ | — | dropped — legacy task removed in PR #311; greenfield write path is daemon-only |
| **H1** | Daemon writes sidecar + index entry per render | Layer 2 | — |
| **H2** | `history/list` + `history/read` + `historyAdded` notification | Layer 2 | H1 |
| ~~**H3**~~ | ~~`history/diff` (metadata mode)~~ | Layer 2 | H2 — landed |
| **H4** | Auto-prune + `historyPruned` notification | Layer 2 | H2 |
| **H5** | `history/diff` (pixel mode + diff PNG) | Layer 2 | H3 |
| **H6** | MCP resource + tool mapping | Layer 3 | H2 + `:daemon:mcp` phase 2 |
| **H7** | VS Code Preview History panel | extension | H2 |
| **H8** | Cross-source provenance UI ("rendered by daemon", "rendered by Gradle") | extension | H7 |
| **H9** | `HistorySource` interface + multi-source merging in `historyManager` | Layer 2 | H2 — landed (H1+H2 already shipped the interface; H10-read shipped multi-source merging) |
| ~~**H10a**~~ | ~~`GitRefHistorySource` (`READ_ONLY` mode) — read from `preview/<branch>` refs~~ | Layer 2 | H9 — landed |
| **H10b** | Layer 1 plumbing — gradle plugin emits `composeai.daemon.gitRefHistory` in the daemon launch descriptor + DSL | Layer 1 | H10a |
| **H11** | `GitRefHistorySource` `WRITE_LOCAL` — debounced commit on render burst | Layer 2 | H10a |
| **H12** | `WRITE_PUSH` mode + git-LFS support + squash-old-history GC | Layer 2 | H11 |
| **H13** | `HttpMirrorHistorySource` — read-only CI / S3 mirror | Layer 2 | H9 |
| **H14** | Cross-worktree merge in MCP `DaemonSupervisor` (multi-worktree timeline) | Layer 3 | H6 |

H1+H2 are independently useful and ship together as the first daemon-side history landing. H3 +
H10-read landed in the second history PR (`history/diff` metadata mode + read-only
`GitRefHistorySource` for `preview/<branch>` refs). H10b (gradle plugin emitting the sysprop)
splits out as a separate Layer 1 task because the daemon-side read surface is independently
useful — agents and ad-hoc launches set the sysprop manually until the plugin path lands.

## Open questions

1. **Per-render config qualifier in entry id.** A `@Preview(device = "Pixel 5")`
   and `@Preview(device = "Pixel 7 Pro")` of the same composable produce
   two entries; today's Gradle layout uses sanitised previewId which
   already disambiguates. Daemon-side, the previewId already encodes
   the qualifier. **Decision: no change; previewId is enough.**

2. **Diff PNG storage.** Pixel diffs produce a third PNG. Should they
   live in `<historyDir>/<previewId>/.diffs/` (per-preview) or a
   global `<historyDir>/.diffs/` (cross-preview)? **Recommend
   per-preview** — easier to garbage-collect when a preview is
   removed.

3. **Index format.** JSONL vs SQLite. JSONL is simpler, append-only,
   greppable. SQLite supports indexed range queries on timestamp and
   preview, scales better past ~10k entries per module. **Recommend
   JSONL for v1**; revisit if any module crosses 10k entries
   regularly.

4. **Provenance trust.** A consumer reading directly from the
   filesystem might see a sidecar that doesn't match its PNG (e.g.
   user `mv`-ed the PNG out from under the daemon). The reader's
   integrity check is `sha256(pngBytes) == sidecar.pngHash` —
   mismatch → entry is treated as corrupted, dropped from listings.
   Self-healing on next prune.

5. **Multi-module history index.** The MCP server might multiplex
   across modules ([MCP-KOTLIN.md](MCP-KOTLIN.md) `DaemonSupervisor`).
   Should `compose-preview-history://` URIs cross modules? **Yes**
   — the URI encodes module-id explicitly. The MCP server walks each
   supervised daemon's `history/list` and merges.

6. **Retention of `previewMetadata` snapshot.** When a preview is
   renamed, do its old history entries get the old name or the new
   name in `previewMetadata.displayName`? **Old name** — the snapshot
   is frozen at render time. The current name lives in `previews.json`.

7. **Git-LFS as the default for `WRITE_LOCAL`?** PNG sizes (a few KB
   each, but accumulating fast) are right at the threshold where LFS
   pays off. **Recommend off by default**, on by config — LFS adds a
   server-side dependency that not every consumer can satisfy. Repos
   that already use LFS will set it; greenfield use cases should pick
   shallow-clone or squash-old-history first.

8. **`preview/<branch>` ref naming when `<branch>` contains slashes.**
   Today's agent branch convention is `agent/foo-bar`; a
   `preview/agent/foo-bar` ref is one segment. Fine — git refs allow
   slashes. **Decision: pass through verbatim.** A consumer wanting a
   flat namespace can override `refPattern` to use `_`-encoded
   branches.

9. **What if two daemons in two worktrees both have
   `WRITE_LOCAL` + `preview/main`?** They'd both produce commits on
   the same ref. Local-only conflicts resolve via merge-on-fetch (git
   handles it). For `WRITE_PUSH` the second pusher loses; the daemon
   surfaces a `historyPushFailed` notification and retries on the
   next render burst. **Recommendation: WRITE_PUSH defaults to
   per-worktree refs (`preview/<branch>` mirrors the working branch,
   not always `preview/main`)** — agents on `agent/foo` push to
   `preview/agent/foo`, never collide with `preview/main`.

10. **`preview_main` user shorthand.** The user's framing suggested
    `preview_main` as a single canonical history branch. The design
    above generalises to `preview/<branch>` with `<branch>` =
    whatever's currently checked out. The two are reconciled by
    pointing the `refPattern` at a literal: `refPattern = "refs/heads/preview_main"`
    gives single-branch behaviour. The pattern default is
    per-branch; users with a "one history branch for the whole repo"
    mental model override.

## Cross-references

- [DESIGN.md](DESIGN.md) — daemon architecture; history is a Layer 2
  surface.
- [PROTOCOL.md](PROTOCOL.md) — daemon JSON-RPC. New methods listed
  above land here when implemented.
- [LAYERING.md](LAYERING.md) — module-boundary rules. History rows
  added to the integration-seams table.
- [MCP.md](MCP.md), [MCP-KOTLIN.md](MCP-KOTLIN.md) — MCP server.
  Phase H6 is a follow-up to MCP phase 2.
- [HistorizePreviewsTask.kt](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/HistorizePreviewsTask.kt) —
  the existing Gradle history writer. H0 extends this.
- [PreviewExtension.kt](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/PreviewExtension.kt) —
  current `historyEnabled` / `historyDir` DSL. The new
  `history { … }` block extends this.
