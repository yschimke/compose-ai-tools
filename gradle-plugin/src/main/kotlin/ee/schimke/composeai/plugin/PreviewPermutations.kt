package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewDataProduct
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewKind
import ee.schimke.composeai.discovery.PreviewParams

internal object PreviewPermutations {
  const val PROPERTY: String = "composePreview.permutations"
  const val SYSTEM_PROPERTY: String = "composeai.preview.permutations"
  const val ACCESSIBILITY: String = "accessibility"

  private const val UI_MODE_TYPE_NORMAL = 0x01
  private const val UI_MODE_NIGHT_MASK = 0x30
  private const val UI_MODE_NIGHT_YES = 0x20

  fun clean(values: List<String>): List<String> =
    values.flatMap { it.split(',', ';') }.map(String::trim).filter(String::isNotEmpty).distinct()

  fun expandsAccessibility(values: List<String>): Boolean =
    clean(values).any { it.equals(ACCESSIBILITY, ignoreCase = true) }

  fun expand(previews: List<PreviewInfo>, values: List<String>): List<PreviewInfo> {
    if (!expandsAccessibility(values)) return previews
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
