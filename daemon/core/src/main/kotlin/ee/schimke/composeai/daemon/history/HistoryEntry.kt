package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Sidecar metadata schema — see docs/daemon/HISTORY.md § "Sidecar metadata
// schema". The kotlinx-serialization JSON shape is the on-disk format the
// daemon writes (and that consumers read back via `history/list` and
// `history/read`) so any field added or renamed here is wire-format-visible.
// Schema versioning rules (HISTORY.md § "File-format versioning") allow new
// optional fields without a version bump; readers ignore unknowns.
// ---------------------------------------------------------------------------

/**
 * One history entry — a single render observation captured to disk by the daemon.
 *
 * Field-by-field cross-reference to HISTORY.md § "Sidecar metadata schema":
 * - **Identity** — [id], [previewId], [module], [timestamp].
 * - **Bytes** — [pngHash] (full hex SHA-256), [pngSize], [pngPath] (relative to the sidecar).
 * - **Provenance (producer)** — [producer] (always `"daemon"` for entries this server writes;
 *   `"gradle"` / `"manual"` reserved for future producers), [trigger], [triggerDetail].
 * - **Provenance (storage source)** — [source]. For H1 always `kind: "fs"`.
 * - **Provenance (worktree + git)** — [worktree], [git]. Resolved at daemon startup; refreshed
 *   per-render where cheap. Both are nullable so a non-git workspace still produces valid entries.
 * - **Render context** — [renderTookMs], [metrics] (mirrors `RenderFinishedParams.metrics`).
 * - **Preview metadata** — [previewMetadata]. Frozen at render time.
 * - **Optional delta** — [previousId] + [deltaFromPrevious]. The first render of a preview has
 *   `previousId == null` and `deltaFromPrevious == null`. H1 leaves the pixel-mode fields
 *   ([HistoryDelta.diffPx], [HistoryDelta.ssim]) as `null`; H5 will populate them.
 */
@Serializable
data class HistoryEntry(
  val id: String,
  val previewId: String,
  val module: String,
  val timestamp: String,
  val pngHash: String,
  val pngSize: Long,
  val pngPath: String,
  val producer: String,
  val trigger: String,
  val triggerDetail: JsonElement? = null,
  val source: HistorySourceInfo,
  val worktree: WorktreeInfo? = null,
  val git: GitInfo? = null,
  val renderTookMs: Long,
  val metrics: RenderMetrics? = null,
  val previewMetadata: PreviewMetadataSnapshot? = null,
  val previousId: String? = null,
  val deltaFromPrevious: HistoryDelta? = null,
)

/**
 * Storage-backend identity. `kind` is one of `"fs"`, `"git"`, `"http"` (HISTORY.md § "Wire-format
 * effects"); `id` is the source's stable identifier (e.g. `"fs:/abs/historyDir"`).
 */
@Serializable data class HistorySourceInfo(val kind: String, val id: String)

/**
 * Worktree provenance. `path` is the absolute worktree root; `id` is a human label (defaults to the
 * dir basename or `COMPOSEAI_WORKTREE_ID` env); `agentId` is the optional automated-agent
 * self-identifier from `COMPOSEAI_AGENT_ID`. All three are nullable so a non-git workspace still
 * produces a valid sidecar.
 */
@Serializable
data class WorktreeInfo(
  val path: String? = null,
  val id: String? = null,
  val agentId: String? = null,
)

/** Git provenance captured per-render. Any subfield may be null when resolution failed. */
@Serializable
data class GitInfo(
  val branch: String? = null,
  val commit: String? = null,
  val shortCommit: String? = null,
  val dirty: Boolean? = null,
  val remote: String? = null,
)

/**
 * Preview metadata snapshot — frozen at render time so future discovery changes don't rewrite
 * history. The current "live" metadata for a preview lives in the daemon's `PreviewIndex`.
 */
@Serializable
data class PreviewMetadataSnapshot(
  val displayName: String? = null,
  val group: String? = null,
  val sourceFile: String? = null,
  val config: String? = null,
)

/**
 * Diff against the previous entry of the same preview. H1 only carries the cheap hash-equality
 * signal; H5 will populate [diffPx] / [ssim] when pixel mode lands.
 */
@Serializable
data class HistoryDelta(
  val pngHashChanged: Boolean,
  val diffPx: Long? = null,
  val ssim: Double? = null,
)
