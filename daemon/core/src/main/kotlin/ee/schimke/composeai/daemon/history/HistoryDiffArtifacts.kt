package ee.schimke.composeai.daemon.history

/**
 * Naming + location convention for `history/diff mode = "pixel"` marked-diff PNGs (H5,
 * issue #1873).
 *
 * Single source of truth shared by the writer (the JSON-RPC `history/diff` handler) and the cleaner
 * ([LocalFsHistorySource.prune]) so the two never drift on where artefacts live or how they're
 * named. Artefacts are a **regenerable cache**: any diff can be recomputed from its two archived
 * frames, so prune deletes a diff PNG as soon as either referenced entry ages out — they must not
 * outlive the history they describe.
 *
 * Layout: `<historyDir>/<sanitisedPreviewDir>/.diffs/<fromId>__<toId>.png` (ids sanitised). The
 * `.diffs` subdir is dot-prefixed so the per-preview sidecar resolution never trips over it.
 */
object HistoryDiffArtifacts {

  /** Per-preview subdirectory holding marked-diff PNGs. Dot-prefixed so sidecar scans skip it. */
  const val DIFFS_DIR_NAME: String = ".diffs"

  /** Filesystem-safe form of a history entry id for use in a diff filename. */
  fun sanitiseId(id: String): String = id.replace(NON_FILENAME, "_")

  /** Diff filename for the ordered pair (`from`, `to`): `<from>__<to>.png` (ids sanitised). */
  fun fileName(fromId: String, toId: String): String =
    "${sanitiseId(fromId)}__${sanitiseId(toId)}.png"

  /**
   * True when [diffFileName] (a `.diffs/` entry) references the already-sanitised entry id
   * [sanitisedEntryId] on either side of its `<from>__<to>.png` name — i.e. the diff should be
   * pruned when that entry is. Robust against ids that themselves contain `_` (it anchors on the
   * `__` separator's start/end rather than splitting).
   */
  fun referencesEntry(diffFileName: String, sanitisedEntryId: String): Boolean {
    if (!diffFileName.endsWith(".png")) return false
    val stem = diffFileName.removeSuffix(".png")
    return stem.startsWith("${sanitisedEntryId}__") || stem.endsWith("__$sanitisedEntryId")
  }

  private val NON_FILENAME = Regex("[^A-Za-z0-9._-]")
}
