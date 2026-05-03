package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.InspectableValue
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
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorProduct
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Producer for `compose/semantics`, a compact SemanticsNode projection for inspector clients. */
object ComposeSemanticsDataProducer {
  const val KIND: String = ComposeSemanticsProduct.KIND
  const val SCHEMA_VERSION: Int = ComposeSemanticsProduct.SCHEMA_VERSION
  const val FILE: String = ComposeSemanticsProduct.FILE

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

typealias ComposeSemanticsPayload =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
typealias ComposeSemanticsNode = ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
typealias LayoutInspectorPayload =
  ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
typealias LayoutInspectorNode = ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
typealias LayoutInspectorBounds = ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
typealias LayoutInspectorSize = ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
typealias LayoutInspectorConstraints =
  ee.schimke.composeai.data.layoutinspector.LayoutInspectorConstraints
typealias LayoutInspectorModifier =
  ee.schimke.composeai.data.layoutinspector.LayoutInspectorModifier

/** Producer for `layout/inspector`, backed by Compose's RootForTest/LayoutNode tree. */
object LayoutInspectorDataProducer {
  const val KIND: String = LayoutInspectorProduct.KIND
  const val SCHEMA_VERSION: Int = LayoutInspectorProduct.SCHEMA_VERSION
  const val FILE: String = LayoutInspectorProduct.FILE

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    previewContext: PreviewContext,
  ) {
    val root = previewContext.inspection.rootForTest as? RootForTest ?: return
    val layoutRoot = root.semanticsOwner.unmergedRootSemanticsNode.layoutNodeOrNull() ?: return
    val sources = LayoutSourceIndex(previewContext.inspection.slotTables)
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val payload =
      LayoutInspectorPayload(root = layoutRoot.toLayoutInspectorNode(rootCoordinates = null, sources))
    previewDir.resolve(FILE).writeText(
      json.encodeToString(LayoutInspectorPayload.serializer(), payload)
    )
  }

  private fun Any.toLayoutInspectorNode(
    rootCoordinates: LayoutCoordinates?,
    sources: LayoutSourceIndex,
  ): LayoutInspectorNode {
    val coordinates = call("getCoordinates") as? LayoutCoordinates
    val rootCoords = rootCoordinates ?: coordinates
    val bounds = coordinates.boundsIn(rootCoords)
    val nodeId = semanticsId()?.toString() ?: identityId()
    val source = sources.sourceFor(this)
    val children = childrenLayoutNodes().map { it.toLayoutInspectorNode(rootCoords, sources) }
    return LayoutInspectorNode(
      nodeId = nodeId,
      component = source?.component ?: componentFallback(),
      source = source?.source,
      sourceInfo = source?.sourceInfo,
      bounds = bounds,
      size = LayoutInspectorSize(width = intProperty("getWidth"), height = intProperty("getHeight")),
      constraints = constraints(),
      placed = booleanProperty("isPlaced", default = true),
      attached = booleanProperty("isAttached", default = true),
      zIndex = zIndex(),
      modifiers = modifierInfo(rootCoords),
      children = children,
    )
  }

  private fun Any.childrenLayoutNodes(): List<Any> =
    sequenceOf("getZSortedChildren", "getChildren\$ui_release", "getFoldedChildren\$ui_release")
      .mapNotNull { call(it) as? Iterable<*> }
      .firstOrNull()
      ?.filterNotNull()
      ?: emptyList()

  private fun Any.modifierInfo(rootCoordinates: LayoutCoordinates?): List<LayoutInspectorModifier> =
    (call("getModifierInfo") as? Iterable<*>)
      ?.filterIsInstance<ModifierInfo>()
      ?.mapNotNull { info -> info.toWireModifier(rootCoordinates) }
      ?: emptyList()

  private fun ModifierInfo.toWireModifier(
    rootCoordinates: LayoutCoordinates?
  ): LayoutInspectorModifier? {
    val inspectable = modifier as? InspectableValue
    val name = inspectable?.nameFallback?.takeIf { it.isNotBlank() } ?: modifier.javaClass.simpleName
    val value = inspectable?.valueOverride?.wireValue()
    val properties =
      inspectable
        ?.inspectableElements
        ?.associate { it.name to it.value.wireValue() }
        .orEmpty()
    return LayoutInspectorModifier(
      name = name,
      value = value,
      properties = properties,
      bounds = coordinates.boundsIn(rootCoordinates),
    )
  }

  private fun Any.constraints(): LayoutInspectorConstraints? {
    val delegate = call("getLayoutDelegate\$ui_release") ?: return null
    val constraints = delegate.call("getLastConstraints-DWUhwKw") ?: return null
    val raw = constraintsLong(constraints) ?: return null
    val minWidth = constraintsStatic("getMinWidth-impl", raw) ?: return null
    val minHeight = constraintsStatic("getMinHeight-impl", raw) ?: return null
    val maxWidth = constraintsStatic("getMaxWidth-impl", raw)
    val maxHeight = constraintsStatic("getMaxHeight-impl", raw)
    val infinity =
      Class.forName("androidx.compose.ui.unit.Constraints").getField("Infinity").getInt(null)
    return LayoutInspectorConstraints(
      minWidth = minWidth,
      maxWidth = maxWidth?.takeIf { it != infinity },
      minHeight = minHeight,
      maxHeight = maxHeight?.takeIf { it != infinity },
    )
  }

  private fun Any.zIndex(): Float? {
    val delegate = call("getLayoutDelegate\$ui_release")
    val measure = delegate?.call("getMeasurePassDelegate\$ui_release")
    return (measure?.call("getZIndex\$ui_release") as? Float)?.takeIf { it != 0f }
  }

  private fun Any.componentFallback(): String =
    call("getMeasurePolicy")?.javaClass?.name?.substringAfterLast('.')?.substringBefore('$')
      ?: javaClass.simpleName

  private fun LayoutCoordinates?.boundsIn(
    rootCoordinates: LayoutCoordinates?
  ): LayoutInspectorBounds =
    if (this == null || rootCoordinates == null) {
      LayoutInspectorBounds(0, 0, 0, 0)
    } else {
      val rect =
        try {
          rootCoordinates.localBoundingBoxOf(this, clipBounds = false)
        } catch (_: Throwable) {
          null
        }
      LayoutInspectorBounds(
        left = rect?.left?.roundToInt() ?: 0,
        top = rect?.top?.roundToInt() ?: 0,
        right = rect?.right?.roundToInt() ?: 0,
        bottom = rect?.bottom?.roundToInt() ?: 0,
      )
    }

  private fun Any.semanticsId(): Int? = call("getSemanticsId") as? Int

  private fun Any.intProperty(name: String): Int = call(name) as? Int ?: 0

  private fun Any.booleanProperty(name: String, default: Boolean): Boolean =
    call(name) as? Boolean ?: default

  private fun Any.identityId(): String =
    "${javaClass.name}@${System.identityHashCode(this).toString(16)}"

  private fun constraintsLong(value: Any): Long? =
    when (value) {
      is Long -> value
      else -> value.call("unbox-impl") as? Long
    }

  private fun constraintsStatic(name: String, raw: Long): Int? =
    runCatching {
        Class.forName("androidx.compose.ui.unit.Constraints")
          .getMethod(name, java.lang.Long.TYPE)
          .invoke(null, raw) as Int
      }
      .getOrNull()

  private fun Any?.wireValue(): String = when (this) {
    null -> "null"
    is String -> this
    is Number,
    is Boolean -> toString()
    else -> toString()
  }

  private fun Any.call(name: String): Any? =
    runCatching {
        val method = javaClass.findZeroArgMethod(name) ?: return null
        method.isAccessible = true
        method.invoke(this)
      }
      .getOrNull()

  private fun Class<*>.findZeroArgMethod(name: String): Method? {
    var current: Class<*>? = this
    while (current != null) {
      current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let {
        return it
      }
      current = current.superclass
    }
    return methods.firstOrNull { it.name == name && it.parameterCount == 0 }
  }

  private fun Any.layoutNodeOrNull(): Any? =
    call("getLayoutNode\$ui_release") ?: call("getLayoutInfo")

  private data class LayoutSource(
    val component: String,
    val source: String?,
    val sourceInfo: String?,
  )

  private class LayoutSourceIndex(slotTables: List<Any>) {
    private val byNode = java.util.IdentityHashMap<Any, LayoutSource>()

    init {
      slotTables
        .asSequence()
        .filterIsInstance<CompositionData>()
        .flatMap { it.compositionGroups.asSequence() }
        .flatMap { it.flattenGroups().asSequence() }
        .forEach { group ->
          val node = group.node ?: return@forEach
          val sourceInfo = group.sourceInfo
          if (sourceInfo != null) {
            byNode[node] = LayoutSource(
              component = sourceInfo.componentName() ?: node.javaClass.simpleName,
              source = sourceInfo.sourceLocation(),
              sourceInfo = sourceInfo,
            )
          }
        }
    }

    fun sourceFor(node: Any): LayoutSource? = byNode[node]
  }

  private fun CompositionGroup.flattenGroups(): List<CompositionGroup> =
    listOf(this) + compositionGroups.flatMap { it.flattenGroups() }

  private fun String.componentName(): String? =
    Regex("""C\(([^)]+)\)""").find(this)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

  private fun String.sourceLocation(): String? {
    val file = Regex("""([A-Za-z0-9_./-]+\.kt)""").find(this)?.groupValues?.getOrNull(1)
    val line = Regex("""(?::|@)(\d+)""").find(this)?.groupValues?.getOrNull(1)
    return when {
      file != null && line != null -> "${file.substringAfterLast('/')}:$line"
      file != null -> file.substringAfterLast('/')
      else -> null
    }
  }
}

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
        displayName = "Compose semantics",
        facets = listOf(DataProductFacet.STRUCTURED),
        mediaTypes = listOf("application/json"),
        sampling = SamplingPolicy.End,
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

/** Registry side for `layout/inspector`; reads the latest JSON artefact from disk. */
class LayoutInspectorDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = LayoutInspectorDataProducer.KIND,
        schemaVersion = LayoutInspectorDataProducer.SCHEMA_VERSION,
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
    if (kind != LayoutInspectorDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (!inline) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = LayoutInspectorDataProducer.SCHEMA_VERSION,
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
        schemaVersion = LayoutInspectorDataProducer.SCHEMA_VERSION,
        payload = payload,
      )
    )
  }

  override fun attachmentsFor(
    previewId: String,
    kinds: Set<String>,
  ): List<DataProductAttachment> {
    if (LayoutInspectorDataProducer.KIND !in kinds) return emptyList()
    val file = fileFor(previewId)
    if (!file.exists()) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = LayoutInspectorDataProducer.KIND,
        schemaVersion = LayoutInspectorDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    )
  }

  private fun fileFor(previewId: String): File =
    rootDir.resolve(previewId).resolve(LayoutInspectorDataProducer.FILE)
}
