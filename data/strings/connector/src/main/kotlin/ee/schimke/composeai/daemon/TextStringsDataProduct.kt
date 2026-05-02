package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.strings.TextStringsProduct
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Android `text/strings` producer backed by the default-mode Compose semantics artifact.
 *
 * This v1 preserves the literal text channels available from the semantics artifact:
 * [TextStringEntry.text] prefers Compose's `GetTextLayoutResult` text, then falls back to text
 * semantics / editable text. [TextStringEntry.semanticsLabel] is the accessibility label path
 * (`contentDescription` when present, otherwise text). Resource entry names are handled by the
 * separate `resources/used` product because Compose semantics receives text after resource
 * resolution.
 */
class TextStringsDataProductRegistry(
  private val rootDir: File,
  private val previewIndex: PreviewIndex,
) : DataProductRegistry {
  private val json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
  }
  private val latestRenderMetadata = ConcurrentHashMap<String, RenderTextMetadata>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload =
      try {
        payloadFor(previewId)
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      } ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(TextStringsPayload.serializer(), payload),
      )
    )
  }

  override fun onRender(previewId: String, result: RenderResult, overrides: PreviewOverrides?) {
    latestRenderMetadata[previewId] = metadataFor(previewId, overrides)
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload =
      try {
        payloadFor(previewId)
      } catch (_: Throwable) {
        null
      } ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(TextStringsPayload.serializer(), payload),
      )
    )
  }

  private fun payloadFor(previewId: String): TextStringsPayload? {
    val file = rootDir.resolve(previewId).resolve(ComposeSemanticsProduct.FILE)
    if (!file.exists()) return null
    val semantics =
      json.decodeFromString(ComposeSemanticsPayload.serializer(), file.readText())
    val metadata = latestRenderMetadata[previewId] ?: metadataFor(previewId, overrides = null)
    val texts = buildList { collectTexts(semantics.root, metadata.localeTag, metadata.fontScale) }
    return TextStringsPayload(texts = texts)
  }

  private fun metadataFor(previewId: String, overrides: PreviewOverrides?): RenderTextMetadata {
    val params = previewIndex.byId(previewId)?.params
    return RenderTextMetadata(
      localeTag =
        overrides?.localeTag?.takeIf { it.isNotBlank() }
          ?: params?.locale?.takeIf { it.isNotBlank() }
          ?: Locale.getDefault().toLanguageTag(),
      fontScale = overrides?.fontScale ?: params?.fontScale ?: 1.0f,
    )
  }

  private fun MutableList<TextStringEntry>.collectTexts(
    node: ComposeSemanticsNode,
    localeTag: String,
    fontScale: Float,
  ) {
    val semanticsText = node.text?.takeIf { it.isNotBlank() }
    val text =
      node.layoutText?.takeIf { it.isNotBlank() }
        ?: semanticsText
        ?: node.editableText?.takeIf { it.isNotBlank() }
    val semanticsLabel = node.label?.takeIf { it.isNotBlank() }
    if (text != null || semanticsLabel != null) {
      add(
        TextStringEntry(
          text = text,
          textSource = textSourceFor(text, node, semanticsText),
          semanticsText = semanticsText,
          semanticsLabel = semanticsLabel,
          fontSize = node.layoutFontSize?.takeIf { it.isNotBlank() },
          foregroundColor = node.layoutForegroundColor?.takeIf { it.isNotBlank() },
          backgroundColor = node.layoutBackgroundColor?.takeIf { it.isNotBlank() },
          editableText = node.editableText?.takeIf { it.isNotBlank() },
          inputText = node.inputText?.takeIf { it.isNotBlank() },
          nodeId = node.nodeId,
          boundsInScreen = node.boundsInRoot,
          localeTag = localeTag,
          fontScale = fontScale,
        )
      )
    }
    node.children.forEach { collectTexts(it, localeTag, fontScale) }
  }

  companion object {
    const val KIND: String = TextStringsProduct.KIND
    const val SCHEMA_VERSION: Int = TextStringsProduct.SCHEMA_VERSION
  }
}

private data class RenderTextMetadata(val localeTag: String, val fontScale: Float)

private fun textSourceFor(
  text: String?,
  node: ComposeSemanticsNode,
  semanticsText: String?,
): String? {
  if (text == null) return null
  return when (text) {
    node.layoutText?.takeIf { it.isNotBlank() } -> "layout"
    semanticsText -> "semantics"
    node.editableText?.takeIf { it.isNotBlank() } -> "editableText"
    else -> null
  }
}

typealias TextStringsPayload = ee.schimke.composeai.data.strings.TextStringsPayload

typealias TextStringEntry = ee.schimke.composeai.data.strings.TextStringEntry
