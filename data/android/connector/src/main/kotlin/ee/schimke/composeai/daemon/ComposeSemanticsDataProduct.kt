package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnitType
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Producer for `compose/semantics`, a compact SemanticsNode projection for inspector clients. */
object ComposeSemanticsDataProducer {
  const val KIND: String = "compose/semantics"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "compose-semantics.json"

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(rootDir: File, previewId: String, root: SemanticsNode) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val payload = ComposeSemanticsPayload(root = root.toWireNode())
    previewDir.resolve(FILE).writeText(
      json.encodeToString(ComposeSemanticsPayload.serializer(), payload)
    )
  }

  private fun SemanticsNode.toWireNode(): ComposeSemanticsNode {
    val cfg = config
    val layout = cfg.layoutDetails()
    return ComposeSemanticsNode(
      nodeId = id.toString(),
      boundsInRoot = boundsInRoot.toWireBounds(),
      label = cfg.label(),
      text = cfg.renderedText(),
      layoutText = layout?.text,
      layoutFontSize = layout?.fontSize,
      layoutForegroundColor = layout?.foregroundColor,
      layoutBackgroundColor = layout?.backgroundColor,
      editableText = cfg.getOrNull(SemanticsProperties.EditableText)?.text,
      inputText = cfg.getOrNull(SemanticsProperties.InputText)?.text,
      role = cfg.getOrNull(SemanticsProperties.Role)?.toString(),
      testTag = cfg.getOrNull(SemanticsProperties.TestTag),
      mergeMode =
        when {
          cfg.isClearingSemantics -> "clearAndSet"
          cfg.isMergingSemanticsOfDescendants -> "mergeDescendants"
          else -> null
        },
      clickable = cfg.getOrNull(SemanticsActions.OnClick) != null,
      children = children.map { it.toWireNode() },
    )
  }

  private fun SemanticsConfiguration.label(): String? {
    getOrNull(SemanticsProperties.ContentDescription)
      ?.joinToString(" ")
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }
    return getOrNull(SemanticsProperties.Text)
      ?.joinToString(" ") { it.text }
      ?.takeIf { it.isNotBlank() }
  }

  private fun SemanticsConfiguration.renderedText(): String? =
    getOrNull(SemanticsProperties.Text)
      ?.joinToString(" ") { it.text }
      ?.takeIf { it.isNotBlank() }

  private fun SemanticsConfiguration.layoutDetails(): LayoutTextDetails? {
    val action = getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val results = mutableListOf<TextLayoutResult>()
    val ok =
      try {
        action(results)
      } catch (_: Throwable) {
        false
      }
    if (!ok && results.isEmpty()) return null
    val text =
      results
        .mapNotNull { it.layoutInput.text.text.takeIf { text -> text.isNotBlank() } }
        .distinct()
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
    val fontSize =
      results
        .map { it.layoutInput.style.fontSize }
        .filter { it.type == TextUnitType.Sp }
        .map { it.value }
        .distinct()
        .singleOrNull()
        ?.let { "${it}sp" }
    return LayoutTextDetails(
      text = text,
      fontSize = fontSize,
      foregroundColor =
        unambiguousColor(results.flatMap { it.textColors() })?.let(::colorToWireString),
      backgroundColor =
        unambiguousColor(results.flatMap { it.backgroundColors() })?.let(::colorToWireString),
    )
  }

  private fun TextLayoutResult.textColors(): List<Color> =
    buildList {
      add(layoutInput.style.color)
      layoutInput.text.spanStyles.forEach { add(it.item.color) }
    }

  private fun TextLayoutResult.backgroundColors(): List<Color> =
    buildList {
      add(layoutInput.style.background)
      layoutInput.text.spanStyles.forEach { add(it.item.background) }
    }

  private fun unambiguousColor(colors: List<Color>): Color? =
    colors.filter { it != Color.Unspecified }.distinct().singleOrNull()

  private fun colorToWireString(color: Color): String =
    "#${String.format(Locale.US, "%08X", color.toArgb())}"

  private data class LayoutTextDetails(
    val text: String?,
    val fontSize: String?,
    val foregroundColor: String?,
    val backgroundColor: String?,
  )

  private fun androidx.compose.ui.geometry.Rect.toWireBounds(): String =
    "${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}"
}

@Serializable
data class ComposeSemanticsPayload(val root: ComposeSemanticsNode)

@Serializable
data class ComposeSemanticsNode(
  val nodeId: String,
  val boundsInRoot: String,
  val label: String? = null,
  val text: String? = null,
  val layoutText: String? = null,
  val layoutFontSize: String? = null,
  val layoutForegroundColor: String? = null,
  val layoutBackgroundColor: String? = null,
  val editableText: String? = null,
  val inputText: String? = null,
  val role: String? = null,
  val testTag: String? = null,
  val mergeMode: String? = null,
  val clickable: Boolean = false,
  val children: List<ComposeSemanticsNode> = emptyList(),
)

/** Registry side for `compose/semantics`; reads the latest JSON artefact from disk. */
class ComposeSemanticsDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = ComposeSemanticsDataProducer.KIND,
        schemaVersion = ComposeSemanticsDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.PATH,
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
    if (kind != ComposeSemanticsDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (!inline) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = ComposeSemanticsDataProducer.SCHEMA_VERSION,
          path = file.absolutePath,
        )
      )
    }
    val payload: JsonObject =
      try {
        json.parseToJsonElement(file.readText()) as JsonObject
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = kind,
        schemaVersion = ComposeSemanticsDataProducer.SCHEMA_VERSION,
        payload = payload,
      )
    )
  }

  override fun attachmentsFor(
    previewId: String,
    kinds: Set<String>,
  ): List<DataProductAttachment> {
    if (ComposeSemanticsDataProducer.KIND !in kinds) return emptyList()
    val file = fileFor(previewId)
    if (!file.exists()) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = ComposeSemanticsDataProducer.KIND,
        schemaVersion = ComposeSemanticsDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    )
  }

  private fun fileFor(previewId: String): File =
    rootDir.resolve(previewId).resolve(ComposeSemanticsDataProducer.FILE)
}
