package ee.schimke.composeai.daemon

/**
 * Splits a **row-addressed** previewId — `<baseId>_<rowToken>` — back into the manifest's base id
 * plus the token naming one `@PreviewParameter` row (issue #3749).
 *
 * **Why the daemon has to do the splitting.** Discovery emits ONE entry per parameterized preview
 * function: it reads bytecode, so it can't instantiate the provider and has no idea how many values
 * it yields. The fan-out renderer *can* — it writes `<stem>_<label>.png` / `<stem>_PARAM_<idx>.png`
 * per value (see docs/RENDER_FILENAMES.md) — but those row ids exist nowhere in `previews.json`. So
 * the daemon's manifest carries base ids only, and until this existed a `renderNow` naming a row
 * ("…MyScreenPreview_Light_PARAM_4") died in `PreviewManifestRouter` with *no manifest entry for
 * previewId*, leaving a screen whose states come from a provider stuck on row 0 in `serve`,
 * `render_preview`, and the panel.
 *
 * The daemon is the layer that can close that gap: it holds the consumer classpath and already
 * enumerates the provider to take value 0. So a row id is resolved *here*, at routing time, against
 * the base ids the manifest does know — the row token is then handed to the render body, which
 * enumerates far enough to bind that row's value.
 *
 * **Row tokens are exactly the filename suffixes the fan-out writes**, minus the leading `_`:
 * either a derived label (`Dark`, `LongTitle`) or `PARAM_<idx>` when the label couldn't be derived
 * or collided. That's deliberate — what a caller reads off disk is what they can address, and an
 * index token is predictable without reading anything (an out-of-range `PARAM_<n>` answers with the
 * provider's actual row list, which is the discovery path until the daemon grows an enumeration
 * RPC). Matching a token to a value is the renderer's job (`PreviewParameterSupport.resolve(row =
 * …)`); this object only does the string-level split, which is why it can live in `:daemon:core`
 * and be shared by both routers.
 *
 * **Longest base wins.** Ids are `_`-joined and a multi-preview annotation already contributes its
 * own suffix, so `MyScreenPreview_Light` and `MyScreenPreview` can both be real manifest entries.
 * Splitting from the right and taking the FIRST (longest) prefix that is a parameterized entry
 * keeps `MyScreenPreview_Light_PARAM_4` addressing row 4 of the `_Light` variant rather than
 * reading `Light_PARAM_4` as a row token of the bare preview.
 */
object PreviewRowAddress {

  /** Prefix of the index-addressed row token — `PARAM_4` names provider value 4. */
  const val INDEX_PREFIX: String = "PARAM_"

  /** A previewId resolved against the manifest: which entry to render, and which row of it. */
  data class Split(val baseId: String, val row: String)

  /**
   * Resolves [previewId] as `<baseId>_<row>` where `baseId` satisfies [isParameterized], or `null`
   * when no split applies (the ordinary case — [previewId] is itself a manifest entry, or names
   * nothing the manifest knows).
   *
   * [isParameterized] must answer "is this a manifest entry that declares a `@PreviewParameter`
   * provider?". Gating on the provider — not merely on the id existing — is what stops an unrelated
   * id that happens to share a prefix from being mistaken for a row of its neighbour: a preview
   * with no provider has no rows, so nothing can be a row token of it.
   *
   * Callers check the exact id first; this is the fallback for a miss.
   */
  fun split(previewId: String, isParameterized: (String) -> Boolean): Split? {
    var cut = previewId.lastIndexOf('_')
    while (cut > 0) {
      val base = previewId.substring(0, cut)
      val row = previewId.substring(cut + 1)
      if (row.isNotEmpty() && isParameterized(base)) return Split(base, row)
      cut = previewId.lastIndexOf('_', cut - 1)
    }
    return null
  }

  /** The addressable id of one row: `<baseId>_<rowToken>`, matching the fan-out's filename stem. */
  fun rowId(baseId: String, row: String): String = "${baseId}_$row"

  /**
   * The index a `PARAM_<n>` token names, or `null` for a label token. Blank/negative/overflowing
   * spellings return `null` so they fall through to label matching and fail with the "no row named
   * …" diagnostic rather than an opaque parse error.
   */
  fun indexOf(row: String): Int? =
    if (row.startsWith(INDEX_PREFIX))
      row.removePrefix(INDEX_PREFIX).toIntOrNull()?.takeIf { it >= 0 }
    else null
}
