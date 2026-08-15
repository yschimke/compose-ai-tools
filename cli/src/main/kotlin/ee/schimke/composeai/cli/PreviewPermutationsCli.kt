package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode

internal object PreviewPermutationsCli {
  const val PROPERTY: String = "composePreview.permutations"
  private const val ACCESSIBILITY: String = "accessibility"
  private const val UI_MODE_TYPE_NORMAL = 0x01
  private const val UI_MODE_NIGHT_MASK = 0x30
  private const val UI_MODE_NIGHT_YES = 0x20

  fun clean(values: List<String>): List<String> =
    values.flatMap { it.split(',', ';') }.map(String::trim).filter(String::isNotEmpty).distinct()

  fun expandManifest(manifest: PreviewManifest, values: List<String>): PreviewManifest =
    manifest.copy(previews = expand(manifest.previews, values))

  /**
   * The previews [values] expands [previews] into, in render order. Internal rather than private so
   * [PreviewRenderScope] can ask "which ids will this one manifest entry actually produce?" without
   * synthesising a whole manifest around it.
   */
  fun expand(previews: List<PreviewInfo>, values: List<String>): List<PreviewInfo> {
    if (clean(values).none { it.equals(ACCESSIBILITY, ignoreCase = true) }) return previews
    return previews.flatMap { preview ->
      if (preview.params.kind != "COMPOSE") listOf(preview)
      else
        listOf(
          preview,
          preview.accessibilityVariant(
            idSuffix = "_dark",
            outputTag = "_dark",
            params = preview.params.copy(uiMode = nightUiMode(preview.params.uiMode)),
          ),
          preview.accessibilityVariant(
            idSuffix = "_rtl",
            outputTag = "_rtl",
            params = preview.params.copy(locale = "ar-XB"),
          ),
          preview.accessibilityVariant(
            idSuffix = "_fontscale-2x",
            outputTag = "_fontscale-2x",
            params = preview.params.copy(fontScale = 2.0f),
          ),
        )
    }
  }

  /**
   * The render overrides that turn [base] into [expanded] — `null` when [expanded] *is* the base,
   * or when it differs in nothing this can express.
   *
   * These permutations are synthesised client-side, so the daemon has never heard of `Foo_dark`:
   * `PreviewIndex.byId` resolves against the plugin-written `previews.json`. What it *does* accept
   * is a fetch for the declared `Foo` carrying [PreviewOverrides] in the params bag, which it
   * threads into the re-render. Reading the override back off the expanded params — rather than
   * mapping the id suffix — keeps this honest if [expand] ever grows an axis: the override is
   * derived from the same `PreviewParams` the render would have used (issue #3762).
   */
  fun overridesFor(base: PreviewInfo, expanded: PreviewInfo): PreviewOverrides? {
    if (expanded.id == base.id) return null
    val darkened = expanded.params.uiMode != base.params.uiMode && isNight(expanded.params.uiMode)
    val overrides =
      PreviewOverrides(
        uiMode = if (darkened) UiMode.DARK else null,
        localeTag = expanded.params.locale.takeIf { it != base.params.locale },
        fontScale = expanded.params.fontScale.takeIf { it != base.params.fontScale },
      )
    return overrides.takeIf { it.uiMode != null || it.localeTag != null || it.fontScale != null }
  }

  private fun isNight(uiMode: Int): Boolean = (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

  private fun PreviewInfo.accessibilityVariant(
    idSuffix: String,
    outputTag: String,
    params: PreviewParams,
  ): PreviewInfo =
    copy(
      id = id + idSuffix,
      params = params,
      captures = captures.map { it.withOutputTag(outputTag) },
      dataProducts = dataProducts.map { it.withOutputTag(outputTag) },
    )

  private fun Capture.withOutputTag(tag: String): Capture =
    copy(renderOutput = insertRenderTag(renderOutput, tag))

  private fun PreviewDataProduct.withOutputTag(tag: String): PreviewDataProduct =
    copy(output = insertRenderTag(output, tag))

  private fun insertRenderTag(path: String, tag: String): String {
    if (path.isEmpty()) return path
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.')
    return if (dot > slash) path.substring(0, dot) + tag + path.substring(dot) else path + tag
  }

  private fun nightUiMode(uiMode: Int): Int =
    (uiMode and UI_MODE_NIGHT_MASK.inv()) or UI_MODE_TYPE_NORMAL or UI_MODE_NIGHT_YES
}
