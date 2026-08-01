package ee.schimke.composeai.renderer

internal object RenderPreviewPermutations {
  const val SYSTEM_PROPERTY: String = "composeai.preview.permutations"

  private const val ACCESSIBILITY = "accessibility"
  private const val UI_MODE_TYPE_NORMAL = 0x01
  private const val UI_MODE_NIGHT_MASK = 0x30
  private const val UI_MODE_NIGHT_YES = 0x20

  fun patternsFrom(read: (String) -> String? = System::getProperty): List<String> =
    read(SYSTEM_PROPERTY)
      ?.split(',', ';')
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.distinct() ?: emptyList()

  fun expand(previews: List<RenderPreviewEntry>, values: List<String>): List<RenderPreviewEntry> {
    if (values.none { it.equals(ACCESSIBILITY, ignoreCase = true) }) return previews
    return previews.flatMap { preview ->
      if (preview.params.kind != PreviewKind.COMPOSE) listOf(preview)
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

  private fun RenderPreviewEntry.accessibilityVariant(
    idSuffix: String,
    outputTag: String,
    params: RenderPreviewParams,
  ): RenderPreviewEntry =
    copy(
      id = id + idSuffix,
      params = params,
      captures = captures.map { it.withOutputTag(outputTag) },
      dataProducts = dataProducts.map { it.withOutputTag(outputTag) },
    )

  private fun RenderPreviewCapture.withOutputTag(tag: String): RenderPreviewCapture =
    copy(renderOutput = insertRenderTag(renderOutput, tag))

  private fun RenderPreviewArtifact.withOutputTag(tag: String): RenderPreviewArtifact =
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
