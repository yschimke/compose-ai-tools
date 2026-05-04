# Preview history

The daemon archives every render to disk: a PNG, a sibling sidecar JSON,
and an append-only `index.jsonl`. Clients (VS Code, MCP, the harness)
read this archive over JSON-RPC to ask "what did this preview look like
an hour ago?", "did the bytes change?", and "diff against `main`."

The legacy Gradle-side `HistorizePreviewsTask` was removed in PR #311.
The daemon is now the only writer.

## On-disk schema

```
<historyDir>/                                 # default $projectDir/.compose-preview-history
├── index.jsonl                               # append-only log of every entry
├── <sanitised-preview-id>/
│   ├── 20260430-101234-a1b2c3d4.png
│   ├── 20260430-101234-a1b2c3d4.json         # sidecar metadata
│   └── ...
```

Filename: `<utc-timestamp>-<short-hash>.{png,json}` where the hash is
the first 8 hex chars of the PNG's SHA-256. Collision-free across
renders inside the same second.

### Sidecar metadata

```ts
{
  "id": "20260430-101234-a1b2c3d4",
  "previewId": "com.example.RedSquare",
  "module": ":samples:android",
  "timestamp": "2026-04-30T10:12:34Z",

  "pngHash": "a1b2c3d4e5f6...",   // full SHA-256 hex
  "pngSize": 4218,
  "pngPath": "20260430-101234-a1b2c3d4.png",  // relative to this sidecar

  "producer": "daemon",            // "daemon" | "gradle" | "manual"
  "trigger": "fileChanged",         // see § Trigger taxonomy
  "triggerDetail": { "kind": "source", "path": "/abs/path/Foo.kt" },

  "source": {                       // filled in by the reader, not the writer
    "kind": "fs",                   // "fs" | "git" | "http"
    "id": "fs:/abs/.compose-preview-history"
  },

  "worktree": {
    "path": "/home/yuri/workspace/compose-ai-tools",
    "id": "main",
    "agentId": null                 // populated when COMPOSEAI_AGENT_ID is set
  },
  "git": {
    "branch": "agent/foo",          // null in detached HEAD
    "commit": "6af6b8c1...",
    "shortCommit": "6af6b8c",
    "dirty": true,
    "remote": "https://github.com/yschimke/compose-ai-tools"
  },

  "renderTookMs": 234,
  "metrics": { "heapAfterGcMb": 312, "nativeHeapMb": 540, "sandboxAgeRenders": 17, "sandboxAgeMs": 81234 },

  "previewMetadata": {              // frozen at render time
    "displayName": "Red square",
    "group": "buttons",
    "sourceFile": "/abs/path/Foo.kt",
    "config": "phone-portrait"
  },

  "previousId": "20260430-101207-9f8e7d6c",
  "deltaFromPrevious": { "pngHashChanged": true, "diffPx": 142, "ssim": null }
}
```

`index.jsonl` carries the same fields minus `previewMetadata`. Opened
in `O_APPEND` mode — each line is under POSIX `PIPE_BUF` so writes are
atomic.

### Dedup-by-hash on write

Two-tier ladder:

1. **Skip-on-most-recent-match.** If the absolute newest existing entry
   for this `previewId` has the same `pngHash`, the writer returns
   `WriteResult.SKIPPED_DUPLICATE` and writes nothing. No PNG, no
   sidecar, no index line, no `historyAdded` notification.
2. **Pointer-on-any-match.** If the new bytes match an earlier entry
   (but not the most recent), the new sidecar's `pngPath` points at
   the older PNG and the bytes aren't re-written. Sidecar + index line
   still land — A → B → A keeps three entries.

The most-recent-hash check walks the on-disk listing, not an in-memory
cache, so it survives daemon restart.

### Trigger taxonomy

| trigger | meaning |
|---|---|
| `initial` | first render after daemon start |
| `renderNow` | explicit `renderNow` request |
| `fileChanged` | source/resource/classpath edit |
| `discoveryUpdated` | preview newly discovered or metadata changed |
| `setVisible` | preview entered the visible set |
| `recycleResume` | sandbox recycle re-rendered visible previews |
| `manual` | external trigger (CLI flag, MCP `render_preview`) |

`triggerDetail` is per-trigger free-form; consumers ignore unknown
shapes.

## Layer 2 — JSON-RPC API

### `history/list`

```ts
params: { previewId?, since?, until?, limit?, cursor? }
result: { entries: HistoryEntry[]; nextCursor?: string; totalCount: number }
```

Filters `branch`, `branchPattern`, `commit`, `worktreePath`, `agentId`,
`sourceKind`, `sourceId` are also accepted. Newest first.

### `history/read`

```ts
params: { id: string; inline?: boolean }
result: { entry: HistoryEntry; previewMetadata: PreviewInfoDto; pngPath: string; pngBytes?: string }
```

Local clients use `pngPath`; remote clients pass `inline: true` for
base64 bytes.

### `history/diff` (§ H3)

```ts
params: { from: string; to: string; mode?: "metadata" | "pixel" }
result: {
  pngHashChanged: boolean;
  diffPx?: number; ssim?: number; diffPngPath?: string;  // pixel mode only
  fromMetadata: HistoryEntry;
  toMetadata: HistoryEntry;
}
```

`mode = "metadata"` (default) is hash-compare + sidecar diff.
`mode = "pixel"` (H5, not yet implemented) writes a marked-diff PNG to
`<historyDir>/<previewId>/.diffs/`.

Mismatched previews → `HistoryDiffMismatch (-32011)`. Missing entry →
`HistoryEntryNotFound (-32010)`.

### `history/prune`

```ts
params: { maxEntriesPerPreview?, maxAgeDays?, maxTotalSizeBytes?, dryRun? }
result: { removedEntries: string[]; freedBytes: number; sourceResults: { ... } }
```

See `HistoryPruneConfig`. Only writable sources participate.

### Notifications

- `historyAdded({ entry })` — emitted on every successful write.
- `historyPruned({ removedIds, freedBytes, reason })` — emitted after
  each non-empty prune pass.

## What this PR lands § H1

The daemon mains (`daemon-android`, `daemon-desktop`) accept
`composeai.daemon.historyDir` as a system property; null disables
history entirely. When unset, they default to
`<repoRoot>/.compose-preview-history` (CWD as the fallback when the
daemon can't resolve a repo root).

## Concurrency model

The daemon is the only writer. Filenames are collision-free; the index
uses `O_APPEND` writes that are atomic for sub-`PIPE_BUF` lines.

## Provenance trust

A consumer reading directly from the filesystem might see a sidecar
that doesn't match its PNG (e.g. user `mv`-ed the PNG out from under
the daemon). Reader integrity check: `sha256(pngBytes) ==
sidecar.pngHash`. Mismatch → entry is treated as corrupted and dropped
from listings. Self-heals on next prune. Truncated index lines are
skipped; missing sidecars drop the entry. Symlinks are followed once.

## Pruning policy

Defaults read from sysprops on the spawned daemon JVM:

| knob | default | sysprop |
|---|---|---|
| `maxEntriesPerPreview` | 50 | `composeai.daemon.history.maxEntriesPerPreview` |
| `maxAgeDays` | 14 | `composeai.daemon.history.maxAgeDays` |
| `maxTotalSizeBytes` | 500 MB | `composeai.daemon.history.maxTotalSizeBytes` |
| `autoPruneIntervalMs` | 1 h | `composeai.daemon.history.autoPruneIntervalMs` |

Setting any knob to `0` or negative disables it. When **every** knob is
`≤ 0` the auto-prune scheduler never starts.

Pruning order: age → per-preview count → total size. Pruning never
drops the most recent entry per preview (diff requires at least one
prior entry).

The index rewrite is tempfile-then-atomic-rename. Sidecar / PNG deletes
are best-effort; orphans self-heal. Multiple sidecars may share one PNG
(dedup-by-hash) — the PNG is only deleted when the last referencing
sidecar is removed; `freedBytes` only counts entries whose PNG actually
went away.

`GitRefHistorySource` and any read-only backend skip pruning entirely
(cleanup is the producer's concern).

## History sources

`HistorySource` is the read/write seam:

```kotlin
interface HistorySource {
  val id: String
  suspend fun list(filter: HistoryFilter): HistoryListPage
  suspend fun read(entryId: String): HistoryReadResult?
  suspend fun supportsWrites(): Boolean
  suspend fun write(entry: HistoryEntry, png: ByteArray)
  fun watch(): Flow<HistoryEvent>
}
```

The daemon holds an ordered list. Reads merge across all configured
sources; writes go to the first writable source.

### `LocalFsHistorySource`

The default `.compose-preview-history/` directory. Read-write.
`watch()` is `WatchService` with a polling fallback.

### `GitRefHistorySource`

Mirrors render output to a parallel git ref (e.g.
`refs/heads/preview/<branch>`). Each render burst is one commit;
`HistoryEntry.git.commit` records which working-tree commit produced
the render.

Configured via `composeai.daemon.gitRefHistory` (comma-separated full
ref names). Empty / unset means no `GitRefHistorySource`. Suggested
default: `refs/heads/preview/main`.

When the configured ref is missing locally (`git rev-parse --verify`
fails — common in greenfield repos), the source emits a one-time
warn-level log message via the daemon log channel, then degrades:
`list` returns empty, `read` returns null. `history/diff` against a
missing ref entry returns `HistoryEntryNotFound (-32010)`.

Sync modes: `READ_ONLY` (default, landed); `WRITE_LOCAL` (debounced
local commits, no push); `WRITE_PUSH` (also `git push`). The daemon
doesn't manage credentials — `WRITE_PUSH` requires a credential helper
or SSH key in standard locations.

## Layering

History is a Layer 2 (daemon) JSON-RPC surface. Layer 3 (MCP) maps it
and handles cross-worktree multiplexing. Layer 1 (Gradle) does not
read or write history — the legacy writer was removed.

Cross-worktree merging happens above Layer 2 (in MCP, or in the
editor) — never inside a daemon.

## MCP mapping

URI scheme: `compose-preview-history://<module>/<previewFqn>/<entryId>`
— distinct from the live `compose-preview://` scheme. `resources/list`
returns recent entries (default cap 50). `resources/read` returns the
PNG via the standard `blob` field, calling `history/read` with
`inline: true`.

Tools: `history_list`, `history_diff`. `history_prune` is intentionally
NOT exposed as an MCP tool — pruning is a host-process concern, not an
agent affordance.
