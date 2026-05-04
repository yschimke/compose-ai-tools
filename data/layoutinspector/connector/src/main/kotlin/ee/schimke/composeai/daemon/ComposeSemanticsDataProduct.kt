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
    val layoutRoot =
      ComposeLayoutInspector.inspect(
        rootSemanticsNode = root.semanticsOwner.unmergedRootSemanticsNode,
        slotTables = previewContext.inspection.slotTables,
      ) ?: return
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val payload = LayoutInspectorPayload(root = layoutRoot)
    previewDir.resolve(FILE).writeText(
      json.encodeToString(LayoutInspectorPayload.serializer(), payload)
    )
  }
}

/**
 * Domain facade for turning Compose's runtime layout tree into the stable layout-inspector wire
 * model.
 *
 * Callers should not know whether the implementation uses public APIs, internal Compose APIs, or
 * reflection. The public surface is the model we want: inspect a root semantics node and slot table
 * context, get a [LayoutInspectorNode].
 */
internal object ComposeLayoutInspector {
  fun inspect(rootSemanticsNode: Any, slotTables: List<Any>): LayoutInspectorNode? {
    val root = LayoutTreeAccess.rootLayoutNode(rootSemanticsNode) ?: return null
    val sources = LayoutSourceIndex(slotTables)
    return root.toWireNode(rootCoordinates = null, sources = sources)
  }

  private fun LayoutNodeFacade.toWireNode(
    rootCoordinates: LayoutCoordinates?,
    sources: LayoutSourceIndex,
  ): LayoutInspectorNode {
    val rootCoords = rootCoordinates ?: coordinates
    val source = sources.sourceFor(raw)
    val children = children.map { it.toWireNode(rootCoords, sources) }
    return LayoutInspectorNode(
      nodeId = semanticsId?.toString() ?: identityId,
      component = source?.component ?: componentFallback,
      source = source?.source,
      sourceInfo = source?.sourceInfo,
      bounds = coordinates.boundsIn(rootCoords),
      size = LayoutInspectorSize(width = width, height = height),
      constraints = constraints,
      placed = placed,
      attached = attached,
      zIndex = zIndex,
      modifiers = modifierInfo.mapNotNull { info -> info.toWireModifier(rootCoords) },
      children = children,
    )
  }

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

  private fun Any?.wireValue(): String = when (this) {
    null -> "null"
    is String -> this
    is Number,
    is Boolean -> toString()
    else -> toString()
  }

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
    val line =
      Regex("""@(?:\d+)?L(\d+)""").find(this)?.groupValues?.getOrNull(1)
        ?: Regex("""(?::|@)(\d+)""").find(this)?.groupValues?.getOrNull(1)
    return when {
      file != null && line != null -> "${file.substringAfterLast('/')}:$line"
      file != null -> file.substringAfterLast('/')
      else -> null
    }
  }

  private class LayoutNodeFacade(val raw: Any) {
    val coordinates: LayoutCoordinates?
      get() = LayoutTreeAccess.coordinates(raw)

    val semanticsId: Int?
      get() = LayoutTreeAccess.semanticsId(raw)

    val identityId: String = "${raw.javaClass.name}@${System.identityHashCode(raw).toString(16)}"

    val componentFallback: String
      get() = LayoutTreeAccess.measurePolicyName(raw) ?: raw.javaClass.simpleName

    val width: Int
      get() = LayoutTreeAccess.width(raw)

    val height: Int
      get() = LayoutTreeAccess.height(raw)

    val constraints: LayoutInspectorConstraints?
      get() = LayoutTreeAccess.constraints(raw)

    val placed: Boolean
      get() = LayoutTreeAccess.isPlaced(raw)

    val attached: Boolean
      get() = LayoutTreeAccess.isAttached(raw)

    val zIndex: Float?
      get() = LayoutTreeAccess.zIndex(raw)?.takeIf { it != 0f }

    val modifierInfo: List<ModifierInfo>
      get() = LayoutTreeAccess.modifierInfo(raw)

    val children: List<LayoutNodeFacade>
      get() = LayoutTreeAccess.children(raw).map(::LayoutNodeFacade)
  }

  /** Private adapter over Compose UI implementation details. */
  private object LayoutTreeAccess {
    fun rootLayoutNode(semanticsNode: Any): LayoutNodeFacade? =
      (call(semanticsNode, "getLayoutNode\$ui_release") ?: call(semanticsNode, "getLayoutInfo"))
        ?.let(::LayoutNodeFacade)

    fun coordinates(node: Any): LayoutCoordinates? = call(node, "getCoordinates") as? LayoutCoordinates

    fun semanticsId(node: Any): Int? = call(node, "getSemanticsId") as? Int

    fun width(node: Any): Int = call(node, "getWidth") as? Int ?: 0

    fun height(node: Any): Int = call(node, "getHeight") as? Int ?: 0

    fun isPlaced(node: Any): Boolean = call(node, "isPlaced") as? Boolean ?: true

    fun isAttached(node: Any): Boolean = call(node, "isAttached") as? Boolean ?: true

    fun modifierInfo(node: Any): List<ModifierInfo> =
      (call(node, "getModifierInfo") as? Iterable<*>)?.filterIsInstance<ModifierInfo>()
        ?: emptyList()

    fun children(node: Any): List<Any> =
      sequenceOf("getZSortedChildren", "getChildren\$ui_release", "getFoldedChildren\$ui_release")
        .mapNotNull { call(node, it) as? Iterable<*> }
        .firstOrNull()
        ?.filterNotNull()
        ?: emptyList()

    fun constraints(node: Any): LayoutInspectorConstraints? {
      val delegate = call(node, "getLayoutDelegate\$ui_release") ?: return null
      val constraints = call(delegate, "getLastConstraints-DWUhwKw") ?: return null
      val raw = constraintsLong(constraints) ?: return null
      val minWidth = constraintsValue("getMinWidth-impl", raw) ?: return null
      val minHeight = constraintsValue("getMinHeight-impl", raw) ?: return null
      val maxWidth = constraintsValue("getMaxWidth-impl", raw)
      val maxHeight = constraintsValue("getMaxHeight-impl", raw)
      val infinity = constraintsInfinity()
      return LayoutInspectorConstraints(
        minWidth = minWidth,
        maxWidth = maxWidth?.takeIf { it != infinity },
        minHeight = minHeight,
        maxHeight = maxHeight?.takeIf { it != infinity },
      )
    }

    fun zIndex(node: Any): Float? {
      val delegate = call(node, "getLayoutDelegate\$ui_release") ?: return null
      val measure = call(delegate, "getMeasurePassDelegate\$ui_release") ?: return null
      return call(measure, "getZIndex\$ui_release") as? Float
    }

    fun measurePolicyName(node: Any): String? =
      call(node, "getMeasurePolicy")?.javaClass?.name?.substringAfterLast('.')?.substringBefore('$')

    private fun constraintsLong(value: Any): Long? =
      when (value) {
        is Long -> value
        else -> call(value, "unbox-impl") as? Long
      }

    private fun constraintsValue(name: String, raw: Long): Int? =
      runCatching {
          Class.forName("androidx.compose.ui.unit.Constraints")
            .getMethod(name, java.lang.Long.TYPE)
            .invoke(null, raw) as Int
        }
        .getOrNull()

    private fun constraintsInfinity(): Int =
      Class.forName("androidx.compose.ui.unit.Constraints").getField("Infinity").getInt(null)

    private fun call(receiver: Any, name: String): Any? =
      runCatching {
          val method = receiver.javaClass.findZeroArgMethod(name) ?: return null
          method.isAccessible = true
          method.invoke(receiver)
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
