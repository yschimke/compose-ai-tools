package ee.schimke.composeai.daemon.history

/**
 * Sanitises a `previewId` into a filesystem-safe directory name.
 *
 * Pinned convention from the legacy `HistorizePreviewsTask.sanitize` (removed in PR #311 but the
 * shape of the on-disk archive is preserved for forward consistency — see HISTORY.md § "On-disk
 * schema"): characters that satisfy [Char.isLetterOrDigit] OR are one of `.`, `_`, `-` are kept
 * verbatim; everything else collapses to `_`. Empty input yields the literal `_`.
 *
 * Stable across processes, locale-independent (the Kotlin `Char.isLetterOrDigit` extension
 * delegates to `Character.isLetterOrDigit(int)` which is Unicode-defined, not locale-dependent).
 */
internal object PreviewIdSanitiser {
  fun sanitise(previewId: String): String {
    if (previewId.isEmpty()) return "_"
    val builder = StringBuilder(previewId.length)
    for (c in previewId) {
      builder.append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
    }
    return builder.toString()
  }
}
