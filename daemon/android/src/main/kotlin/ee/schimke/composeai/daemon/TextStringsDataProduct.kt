package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
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
    val file = rootDir.resolve(previewId).resolve(ComposeSemanticsDataProducer.FILE)
    if (!file.exists()) return null
    val semantics =
      json.decodeFromString(ComposeSemanticsPayload.serializer(), file.readText())
    val params = previewIndex.byId(previewId)?.params
    val localeTag = params?.locale?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
    val fontScale = params?.fontScale ?: 1.0f
    val texts = buildList { collectTexts(semantics.root, localeTag, fontScale) }
    return TextStringsPayload(texts = texts)
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
          textSource =
            when (text) {
              node.layoutText -> "layout"
              semanticsText -> "semantics"
              node.editableText -> "editableText"
              else -> null
            },
          semanticsText = semanticsText,
          semanticsLabel = semanticsLabel,
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
    const val KIND: String = "text/strings"
    const val SCHEMA_VERSION: Int = 1
  }
}

@Serializable
data class TextStringsPayload(val texts: List<TextStringEntry>)

@Serializable
data class TextStringEntry(
  val text: String? = null,
  val textSource: String? = null,
  val semanticsText: String? = null,
  val semanticsLabel: String? = null,
  val editableText: String? = null,
  val inputText: String? = null,
  val nodeId: String,
  val boundsInScreen: String,
  val localeTag: String,
  val fontScale: Float,
)
