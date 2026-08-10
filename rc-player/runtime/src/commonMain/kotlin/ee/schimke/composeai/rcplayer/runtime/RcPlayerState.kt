package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBooleanConstant
import ee.schimke.composeai.rcplayer.protocol.RcClickArea
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcComponentValue
import ee.schimke.composeai.rcplayer.protocol.RcConditionalOperations
import ee.schimke.composeai.rcplayer.protocol.RcDataMapLookup
import ee.schimke.composeai.rcplayer.protocol.RcDebugMessage
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHapticFeedback
import ee.schimke.composeai.rcplayer.protocol.RcHapticType
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIdList
import ee.schimke.composeai.rcplayer.protocol.RcIdLookup
import ee.schimke.composeai.rcplayer.protocol.RcIdMap
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcImpulseStart
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLongConstant
import ee.schimke.composeai.rcplayer.protocol.RcLoopOperation
import ee.schimke.composeai.rcplayer.protocol.RcMatrixConstant
import ee.schimke.composeai.rcplayer.protocol.RcMatrixExpression
import ee.schimke.composeai.rcplayer.protocol.RcMatrixVectorMath
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPathData
import ee.schimke.composeai.rcplayer.protocol.RcPathExpression
import ee.schimke.composeai.rcplayer.protocol.RcRootContentBehavior
import ee.schimke.composeai.rcplayer.protocol.RcRootContentDescription
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextFromFloat
import ee.schimke.composeai.rcplayer.protocol.RcTextLength
import ee.schimke.composeai.rcplayer.protocol.RcTextLookup
import ee.schimke.composeai.rcplayer.protocol.RcTextLookupInt
import ee.schimke.composeai.rcplayer.protocol.RcTextMerge
import ee.schimke.composeai.rcplayer.protocol.RcTextSubtext
import ee.schimke.composeai.rcplayer.protocol.RcTextTransform
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueStringChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcWakeIn
import ee.schimke.composeai.rcplayer.trace.RcTraceCategory
import ee.schimke.composeai.rcplayer.trace.rcTrace

private const val MAX_LOOP_ITERATIONS = 10_000

/** Final component geometry in Remote Compose's pixel coordinate space. */
public data class RcComponentGeometry(
  val width: Float,
  val height: Float,
  val localX: Float,
  val localY: Float,
  val rootX: Float,
  val rootY: Float,
)

/** Typed runtime values for the CMP player; independent from AndroidX `RemoteContext`. */
public class RcPlayerState(
  public val document: RcDocument,
  namedValues: Map<String, RcNamedValue> = emptyMap(),
  private val eventSink: (RcPlayerEvent) -> Unit = {},
  private val onInvalidated: () -> Unit = {},
  private val effectSink: (RcPlayerEffect) -> Unit = {},
  private val timeSource: RcTimeSource = RcTimeSource.System,
) {
  private val floats = mutableMapOf<Int, Float>()
  private val componentValues =
    document.operations.filterIsInstance<RcComponentValue>().distinct().groupBy { it.componentId }
  private val componentGeometries = mutableMapOf<Int, RcComponentGeometry>()
  private val componentContentWidths = mutableMapOf<Int, Float>()
  private val componentContentHeights = mutableMapOf<Int, Float>()
  private val colors = mutableMapOf<Int, Int>()
  private val baseTexts = mutableMapOf<Int, String>()
  private val textOverrides = mutableMapOf<Int, String>()
  private val texts = mutableMapOf<Int, String>()
  private val integers = mutableMapOf<Int, Int>()
  private val booleans = mutableMapOf<Int, Boolean>()
  private val longs = mutableMapOf<Int, Long>()
  private val basePaths = mutableMapOf<Int, RcPathData>()
  private val paths = mutableMapOf<Int, RcPathData>()
  private val variableNames = mutableMapOf<String, RcNamedVariable>()
  private val matrices = mutableMapOf<Int, RcMatrixConstant>()
  private val computedMatrices = mutableMapOf<Int, FloatArray>()
  private val idLists = mutableMapOf<Int, RcIdList>()
  private val floatLists = mutableMapOf<Int, RcFloatList>()
  private val dynamicFloatLists = mutableMapOf<Int, FloatArray>()
  private val idMaps = mutableMapOf<Int, RcIdMap>()
  private val bitmaps = mutableMapOf<Int, RcBitmapData>()
  private val floatExpressionEvaluator = RcFloatExpressionEvaluator(::floatArray)
  private val pathExpressionGenerator = RcPathExpressionGenerator(floatExpressionEvaluator)
  private val floatExpressionRuntimes = mutableMapOf<Int, RcFloatExpressionRuntime>()
  private val floatExpressions =
    document.operations.filterIsInstance<RcFloatExpression>().associateBy { it.id }
  private val integerExpressions =
    document.operations.filterIsInstance<RcIntegerExpression>().associateBy { it.outId }
  private val impulseTimelines = mutableListOf<Pair<RcImpulseStart, RcImpulseTimeline>>()
  private val conditionalPreviousValues =
    mutableListOf<Pair<RcConditionalOperations, Pair<Float, Float>>>()
  private val documentLoadTimeMillis = timeSource.currentTimeMillis()
  private var frameTimeSeconds: Float = 0f
  private var frameEpochMillis: Long = documentLoadTimeMillis

  public val animationTimeSeconds: Float
    get() = frameTimeSeconds

  public val rootContentBehavior: RcRootContentBehavior? =
    document.operations.filterIsInstance<RcRootContentBehavior>().lastOrNull()

  public val rootContentDescription: String?
    get() =
      document.operations
        .filterIsInstance<RcRootContentDescription>()
        .lastOrNull()
        ?.textId
        ?.takeUnless { it == 0 }
        ?.let(texts::get)

  /** AndroidX replaces an equal click-area registration before inserting its latest bounds. */
  public val clickAreas: List<RcClickArea>
    get() = buildList {
      document.operations.filterIsInstance<RcClickArea>().forEach { area ->
        removeAll {
          it.id == area.id &&
            text(it.contentDescriptionId) == text(area.contentDescriptionId) &&
            text(it.metadataId) == text(area.metadataId)
        }
        add(area)
      }
    }

  init {
    for (operation in document.operations) {
      when (operation) {
        is RcFloatConstant -> floats[operation.id] = operation.value.value
        is RcColorConstant -> colors[operation.id] = operation.argb
        is RcTextData -> baseTexts[operation.id] = operation.text
        is RcIntegerConstant -> integers[operation.id] = operation.value
        is RcBooleanConstant -> booleans[operation.id] = operation.value
        is RcLongConstant -> longs[operation.id] = operation.value
        is RcPathData -> basePaths[operation.id] = operation
        is RcMatrixConstant -> matrices[operation.id] = operation
        is RcIdList -> idLists[operation.id] = operation
        is RcFloatList -> floatLists[operation.id] = operation
        is RcIdMap -> idMaps[operation.id] = operation
        is RcBitmapData -> bitmaps[operation.imageId] = operation
        is RcNamedVariable -> variableNames[operation.name] = operation
        else -> Unit
      }
    }
    componentValues.values.flatten().forEach { if (it.valueId !in floats) floats[it.valueId] = 0f }
    document.operations.filterIsInstance<RcDynamicFloatList>().forEach(::applyDataOperation)
    document.operations.filterIsInstance<RcTouchExpression>().forEach { operation ->
      if (operation.id !in floats) floats[operation.id] = resolve(operation.defaultValue)
    }
    namedValues.forEach { (name, value) -> setNamedValue(name, value) }
    beginFrame()
  }

  public fun resolve(word: RcFloatWord): Float = word.referencedId?.let { floats[it] } ?: word.value

  public fun color(id: Int): Int = colors[id] ?: 0

  public fun text(id: Int): String? = texts[id]

  public fun path(id: Int): RcPathData? = paths[id]

  public fun setPath(id: Int, path: RcPathData) {
    paths[id] = path
  }

  public fun beginFrame(
    timeSeconds: Float = 0f,
    epochMillis: Long = timeSource.currentTimeMillis(),
  ): Unit =
    rcTrace(RcTraceCategory.FRAME, "rc:beginFrame") {
      frameTimeSeconds = timeSeconds
      frameEpochMillis = epochMillis
      paths.clear()
      paths.putAll(basePaths)
      texts.clear()
      texts.putAll(baseTexts)
      texts.putAll(textOverrides)
      computedMatrices.clear()
    }

  /** Evaluates AndroidX `TimeAttribute.paint` against one wall-clock snapshot for this frame. */
  public fun applyTimeAttribute(operation: RcTimeAttribute) {
    val type = operation.type.executionValue
    val now = timeSource.snapshot(frameEpochMillis)
    val selectedMillis = longs[operation.timeId] ?: frameEpochMillis
    val selected =
      if (selectedMillis == frameEpochMillis) now else timeSource.snapshot(selectedMillis)
    val deltaMillis =
      when (type) {
        0,
        1,
        2 -> selected.epochMillis - now.epochMillis
        3,
        4,
        5 -> {
          val argumentId =
            requireNotNull(operation.argumentIds.firstOrNull()) {
              "TimeAttribute type $type requires one argument id"
            }
          selected.epochMillis -
            requireNotNull(longs[argumentId]) { "Missing TimeAttribute long argument $argumentId" }
        }
        else -> 0L
      }
    val value =
      when (type) {
        0,
        3 -> deltaMillis.toFloat() * 0.001f
        1,
        4 -> (deltaMillis.toDouble() * 0.001 / 60.0).toFloat()
        2,
        5 -> (deltaMillis.toDouble() * 0.001 / 3600.0).toFloat()
        6 -> selected.second.toFloat()
        7 -> selected.minute.toFloat()
        8 -> selected.hour.toFloat()
        9 -> selected.dayOfMonth.toFloat()
        10 -> (selected.month - 1).toFloat()
        11 -> (selected.isoDayOfWeek - 1).toFloat()
        12 -> selected.year.toFloat()
        14 -> (selected.epochMillis - documentLoadTimeMillis).toFloat() * 0.001f
        15 -> selected.dayOfYear.toFloat()
        else -> return
      }
    setFloat(operation.outId, value)
  }

  public fun requestWakeIn(operation: RcWakeIn) {
    effectSink(RcPlayerEffect.WakeIn(resolve(operation.seconds)))
  }

  /** Emits AndroidX `DebugMessage.apply` as a typed host diagnostic. */
  public fun emitDebugMessage(operation: RcDebugMessage) {
    eventSink(
      RcPlayerEvent.DebugMessage(
        message = text(operation.textId) ?: "null",
        value = resolve(operation.value),
        flags = operation.flags,
      )
    )
  }

  /** Evaluates AndroidX `ConditionalOperations.paint`, including its stateful CHANGED predicate. */
  public fun evaluateConditional(operation: RcConditionalOperations): Boolean {
    val left = resolve(operation.left)
    val right = resolve(operation.right)
    return when (operation.type) {
      RcConditionalOperations.EQUAL -> left == right
      RcConditionalOperations.NOT_EQUAL -> left != right
      RcConditionalOperations.LESS_THAN -> left < right
      RcConditionalOperations.LESS_THAN_OR_EQUAL -> left <= right
      RcConditionalOperations.GREATER_THAN -> left > right
      RcConditionalOperations.GREATER_THAN_OR_EQUAL -> left >= right
      RcConditionalOperations.CHANGED -> {
        val previous = conditionalPreviousValues.firstOrNull { it.first === operation }?.second
        val changed =
          if (previous == null) left != 0f || right != 0f
          else previous.first != left || previous.second != right
        conditionalPreviousValues.removeAll { it.first === operation }
        conditionalPreviousValues += operation to (left to right)
        changed
      }
      else -> false
    }
  }

  /**
   * Runs AndroidX's ascending exclusive-upper-bound loop with a hard resource bound.
   *
   * Java's loop condition is always `index < until`; negative steps therefore only produce zero
   * iterations unless they would enter an infinite loop. Dynamic zero/non-finite steps are caught
   * here because AndroidX only validates literal values while decoding.
   */
  public fun forEachLoopValue(operation: RcLoopOperation, block: (Float) -> Unit) {
    val from = resolve(operation.from)
    val until = resolve(operation.until)
    val step = resolve(operation.step)
    require(from.isFinite() && until.isFinite() && step.isFinite()) {
      "Loop bounds and step must be finite"
    }
    if (!(from < until)) return
    require(step > 0f) { "Loop step must be positive when from < until" }
    var value = from
    var iterations = 0
    while (value < until) {
      require(iterations++ < MAX_LOOP_ITERATIONS) { "Loop exceeds $MAX_LOOP_ITERATIONS iterations" }
      if (operation.indexVariableId != 0) setFloat(operation.indexVariableId, value)
      block(value)
      val next = value + step
      require(next > value) { "Loop step does not advance the index" }
      value = next
    }
  }

  /**
   * Evaluates one immutable impulse container while keeping Java's per-instance initial-pass bit.
   */
  public fun evaluateImpulse(operation: RcImpulseStart): RcImpulsePhase {
    val timeline =
      impulseTimelines.firstOrNull { (candidate) -> candidate === operation }?.second
        ?: RcImpulseTimeline().also { impulseTimelines += operation to it }
    val startAt = resolve(operation.startAt)
    val phase = timeline.evaluate(frameTimeSeconds, startAt, resolve(operation.duration))
    when (phase) {
      RcImpulsePhase.WAITING -> effectSink(RcPlayerEffect.WakeIn(startAt - frameTimeSeconds))
      RcImpulsePhase.INITIALIZE,
      RcImpulsePhase.PROCESS -> effectSink(RcPlayerEffect.NextFrame)
      RcImpulsePhase.IDLE -> Unit
    }
    return phase
  }

  public fun applyTextOperation(operation: RcOperation) {
    when (operation) {
      is RcTextMerge -> {
        val value =
          requireNotNull(texts[operation.leftId]) + requireNotNull(texts[operation.rightId])
        require(value.length <= 16 * 1024) { "TextMerge output exceeds AndroidX's 16K limit" }
        texts[operation.outId] = value
      }
      is RcTextLength ->
        setFloat(operation.outId, requireNotNull(texts[operation.textId]).length.toFloat())
      is RcTextSubtext -> {
        val value = requireNotNull(texts[operation.textId])
        val start = resolve(operation.start).toInt()
        val length = resolve(operation.length).toInt()
        texts[operation.outId] =
          if (length == -1) value.substring(start, value.length)
          else value.substring(start, start + length)
      }
      is RcTextTransform -> {
        val value = requireNotNull(texts[operation.textId])
        val start = resolve(operation.start).toInt()
        val length = resolve(operation.length).toInt()
        val selected =
          if (length == -1) value.substring(start, value.length)
          else value.substring(start, start + length)
        texts[operation.outId] =
          when (operation.operation) {
            1 -> selected.lowercase()
            2 -> selected.uppercase()
            3 -> selected.trim()
            4 -> selected.capitalizeWords()
            5 -> selected.capitalizeFirstCharacter()
            else -> selected
          }
      }
      is RcTextFromFloat ->
        texts[operation.outId] =
          RcTextFormatter.format(
            resolve(operation.value),
            operation.digitsBefore,
            operation.digitsAfter,
            operation.flags,
          )
      is RcTextLookup -> {
        val id = requireNotNull(idLists[operation.listId]).ids[resolve(operation.index).toInt()]
        texts[operation.outId] = requireNotNull(texts[id])
      }
      is RcTextLookupInt -> {
        val id =
          requireNotNull(idLists[operation.listId]).ids[requireNotNull(integers[operation.indexId])]
        texts[operation.outId] = requireNotNull(texts[id])
      }
      else -> error("Not a runtime text operation: ${operation.opcode}")
    }
  }

  public fun applyDataOperation(operation: RcOperation) {
    when (operation) {
      is RcIdLookup ->
        integers[operation.outId] =
          requireNotNull(idLists[operation.listId]).ids[resolve(operation.index).toInt()]
      is RcDataMapLookup -> {
        val key = requireNotNull(texts[operation.keyTextId])
        val entry =
          requireNotNull(idMaps[operation.mapId]).entries.firstOrNull { it.name == key }
            ?: error("Missing AndroidX data-map key '$key'")
        when (entry.type) {
          RcIdMap.TYPE_STRING -> texts[operation.outId] = requireNotNull(texts[entry.id])
          RcIdMap.TYPE_INT -> integers[operation.outId] = requireNotNull(integers[entry.id])
          RcIdMap.TYPE_FLOAT -> floats[operation.outId] = requireNotNull(floats[entry.id])
          RcIdMap.TYPE_LONG -> integers[operation.outId] = requireNotNull(longs[entry.id]).toInt()
          RcIdMap.TYPE_BOOLEAN ->
            integers[operation.outId] = if (requireNotNull(booleans[entry.id])) 1 else 0
          else -> error("Unknown AndroidX data-map type ${entry.type}")
        }
      }
      is RcDynamicFloatList -> {
        val length = resolve(operation.length).toInt()
        require(length in 0..2_000) {
          "Dynamic float-list length $length is outside AndroidX's 0..2000 range"
        }
        val existing = dynamicFloatLists[operation.id]
        if (existing == null || existing.size != length) {
          dynamicFloatLists[operation.id] = FloatArray(length)
        }
      }
      is RcUpdateDynamicFloatList -> {
        val values = dynamicFloatLists[operation.listId]
        if (values != null) {
          val index = resolve(operation.index).toInt()
          if (index in values.indices) values[index] = resolve(operation.value)
        }
      }
      else -> error("Not a runtime data operation: ${operation.opcode}")
    }
  }

  public fun applyIntegerExpression(operation: RcIntegerExpression) {
    integers[operation.outId] =
      RcIntegerExpressionEvaluator.evaluate(operation) { id -> integers[id] ?: 0 }
  }

  public fun applyImageAttribute(operation: RcImageAttribute) {
    val bitmap =
      requireNotNull(bitmaps[operation.imageId]) { "Missing bitmap ${operation.imageId}" }
    setFloat(
      operation.outId,
      when (operation.type) {
        RcImageAttribute.IMAGE_WIDTH -> bitmap.width.toFloat()
        RcImageAttribute.IMAGE_HEIGHT -> bitmap.height.toFloat()
        else -> error("Unknown AndroidX image attribute ${operation.type}")
      },
    )
  }

  public fun applyColorAttribute(operation: RcColorAttribute) {
    val color = color(operation.colorId)
    val red = ((color shr 16) and 0xff) / 255f
    val green = ((color shr 8) and 0xff) / 255f
    val blue = (color and 0xff) / 255f
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    setFloat(
      operation.outId,
      when (operation.type) {
        RcColorAttribute.COLOR_HUE -> {
          var sector =
            when {
              maximum == minimum -> 0f
              maximum == red -> ((green - blue) / delta) % 6f
              maximum == green -> (blue - red) / delta + 2f
              else -> (red - green) / delta + 4f
            }
          var hue = (sector * 60f) % 360f
          if (hue < 0f) hue += 360f
          hue / 360f
        }
        RcColorAttribute.COLOR_SATURATION -> if (maximum == minimum) 0f else delta / maximum
        RcColorAttribute.COLOR_BRIGHTNESS -> maximum
        RcColorAttribute.COLOR_RED -> red
        RcColorAttribute.COLOR_GREEN -> green
        RcColorAttribute.COLOR_BLUE -> blue
        RcColorAttribute.COLOR_ALPHA -> ((color shr 24) and 0xff) / 255f
        else -> error("Unknown AndroidX color attribute ${operation.type}")
      },
    )
  }

  public fun applyColorExpression(operation: RcColorExpression) {
    val result =
      when (operation.mode) {
        in RcColorExpression.COLOR_COLOR_INTERPOLATE..RcColorExpression.ID_ID_INTERPOLATE -> {
          val first = if (operation.mode and 1 != 0) color(operation.first) else operation.first
          val second = if (operation.mode and 2 != 0) color(operation.second) else operation.second
          RcColorEvaluator.interpolate(first, second, resolve(RcFloatWord(operation.third)))
        }
        RcColorExpression.HSV_MODE ->
          RcColorEvaluator.hsv(
            operation.modeAndAlpha shr 16,
            resolve(RcFloatWord(operation.first)),
            resolve(RcFloatWord(operation.second)),
            resolve(RcFloatWord(operation.third)),
          )
        RcColorExpression.ARGB_MODE ->
          RcColorEvaluator.argb(
            (operation.modeAndAlpha shr 16) / 1024f,
            resolve(RcFloatWord(operation.first)),
            resolve(RcFloatWord(operation.second)),
            resolve(RcFloatWord(operation.third)),
          )
        RcColorExpression.IDARGB_MODE ->
          RcColorEvaluator.argb(
            resolve(RcFloatWord(0x7fc00000 or (operation.modeAndAlpha ushr 16))),
            resolve(RcFloatWord(operation.first)),
            resolve(RcFloatWord(operation.second)),
            resolve(RcFloatWord(operation.third)),
          )
        else -> error("Unknown AndroidX color-expression mode ${operation.mode}")
      }
    setColor(operation.outId, result)
  }

  public fun applyColorTheme(operation: RcColorTheme, requestedTheme: Int) {
    setColor(
      operation.outId,
      if (requestedTheme == RcTheme.LIGHT) operation.lightModeFallback
      else operation.darkModeFallback,
    )
  }

  public fun floatList(id: Int): RcFloatList? = floatLists[id]

  public fun floatValues(id: Int): FloatArray? =
    dynamicFloatLists[id]?.copyOf() ?: floatLists[id]?.values?.map(::resolve)?.toFloatArray()

  public fun setDynamicFloatValues(id: Int, values: FloatArray) {
    val target = requireNotNull(dynamicFloatLists[id]) { "Missing dynamic float list $id" }
    require(target.size == values.size) {
      "Dynamic float list $id has ${target.size} values; layout compute requires ${values.size}"
    }
    values.copyInto(target)
  }

  /**
   * Applies the state operations a layout container carries alongside its child components.
   *
   * AndroidX executes a document's operations in order as it walks the layout, so a `CoreText`
   * reading id 70 sees the `TEXT_LOOKUP_INT` that published it two operations earlier. The layout
   * tree keeps components and drops everything else, so without this replay those ids were never
   * computed and the text rendered empty. [beginFrame] resets derived text every frame, so this
   * must run per frame rather than once at load.
   *
   * Anything that is not a state operation — draw commands, modifiers, actions, nested containers —
   * is left to the renderer that owns it.
   */
  public fun applyContentStateOperations(children: List<RcLinkedNode>) {
    children.forEach { child ->
      when (val operation = (child as? RcLinkedNode.Operation)?.operation) {
        // Float producers run before the text operations that reference them: a TEXT_FROM_FLOAT
        // reading an id no expression has computed resolves to the reference's own NaN bits and
        // formats as garbage. Wire order is the document's order, so one pass suffices.
        is RcFloatExpression -> applyFloatExpression(operation)
        is RcIntegerExpression -> applyIntegerExpression(operation)
        is RcColorExpression -> applyColorExpression(operation)
        is RcColorAttribute -> applyColorAttribute(operation)
        is RcIdLookup,
        is RcDataMapLookup -> applyDataOperation(operation)
        is RcTextMerge,
        is RcTextLength,
        is RcTextSubtext,
        is RcTextTransform,
        is RcTextFromFloat,
        is RcTextLookup,
        is RcTextLookupInt -> applyTextOperation(operation)
        else -> Unit
      }
    }
  }

  public fun executeLayoutCompute(children: List<RcLinkedNode>) {
    children.forEach { child ->
      val operation =
        (child as? RcLinkedNode.Operation)?.operation
          ?: error("Nested containers are not supported inside LayoutCompute")
      when (operation) {
        is RcFloatExpression -> applyFloatExpression(operation)
        is RcIntegerExpression -> applyIntegerExpression(operation)
        is RcDynamicFloatList,
        is RcUpdateDynamicFloatList,
        is RcIdLookup,
        is RcDataMapLookup -> applyDataOperation(operation)
        is RcTextMerge,
        is RcTextLength,
        is RcTextSubtext,
        is RcTextTransform,
        is RcTextFromFloat,
        is RcTextLookup,
        is RcTextLookupInt -> applyTextOperation(operation)
        is RcColorExpression -> applyColorExpression(operation)
        is RcImageAttribute -> applyImageAttribute(operation)
        is RcColorAttribute -> applyColorAttribute(operation)
        is RcMatrixExpression -> applyMatrixExpression(operation)
        is RcMatrixVectorMath -> applyMatrixVectorMath(operation)
        is RcPathExpression -> applyPathExpression(operation)
        is RcFloatConstant,
        is RcColorConstant,
        is RcTextData,
        is RcIntegerConstant,
        is RcBooleanConstant,
        is RcLongConstant,
        is RcPathData,
        is RcMatrixConstant,
        is RcIdList,
        is RcFloatList,
        is RcIdMap,
        is RcBitmapData,
        is RcNamedVariable -> Unit // Immutable declarations were loaded when the state was created.
        else -> error("Opcode ${operation.opcode} cannot execute inside LayoutCompute")
      }
    }
  }

  public fun evaluateLayoutCompute(block: RcLayoutComputeBlock, bounds: FloatArray): FloatArray {
    require(bounds.size == 6) {
      "LayoutCompute requires x, y, width, height, parent width, parent height"
    }
    setDynamicFloatValues(block.operation.boundsId, bounds)
    executeLayoutCompute(block.children)
    return requireNotNull(floatValues(block.operation.boundsId)) {
      "Missing layout-compute bounds list ${block.operation.boundsId}"
    }
  }

  private fun floatArray(id: Int): FloatArray? =
    dynamicFloatLists[id] ?: floatLists[id]?.values?.map(::resolve)?.toFloatArray()

  public fun applyPathExpression(operation: RcPathExpression) {
    setPath(operation.id, pathExpressionGenerator.generate(operation, ::resolve))
  }

  public fun applyFloatExpression(operation: RcFloatExpression) {
    val runtime =
      floatExpressionRuntimes.getOrPut(operation.id) {
        RcFloatExpressionRuntime(operation, ::floatArray)
      }
    setFloat(operation.id, runtime.evaluate(frameTimeSeconds, ::resolve))
  }

  public fun applyMatrixVectorMath(operation: RcMatrixVectorMath) {
    val matrix = matrixValues(operation.matrixId)
    val input = operation.inputs.map(::resolve)
    val output = FloatArray(operation.outputs.size)
    if (operation.type == 0) {
      output.indices.forEach { row ->
        var result = matrix[3 + row * 4]
        input.indices.forEach { column -> result += matrix[column + row * 4] * input[column] }
        output[row] = result
      }
    } else {
      val input4 = FloatArray(4).also { it[3] = 1f }
      input.forEachIndexed { index, value -> input4[index] = value }
      val output4 = FloatArray(4)
      output4.indices.forEach { row ->
        input4.indices.forEach { column ->
          output4[row] += matrix[column + row * 4] * input4[column]
        }
      }
      output.indices.forEach { output[it] = output4[it] / output4[3] }
    }
    operation.outputs.forEachIndexed { index, id -> setFloat(id, output[index]) }
  }

  public fun applyMatrixExpression(operation: RcMatrixExpression) {
    computedMatrices[operation.id] = RcMatrixEvaluator.evaluate(operation.expression, ::resolve)
  }

  public fun matrixValues(id: Int): FloatArray {
    computedMatrices[id]?.let {
      return it.copyOf()
    }
    val source = requireNotNull(matrices[id]) { "Missing matrix $id" }
    return FloatArray(16).also { matrix ->
      matrix[0] = 1f
      matrix[5] = 1f
      matrix[10] = 1f
      matrix[15] = 1f
      when (source.values.size) {
        16 -> source.values.forEachIndexed { index, value -> matrix[index] = resolve(value) }
        9 -> {
          val values = source.values.map(::resolve)
          matrix[0] = values[0]
          matrix[1] = values[1]
          matrix[3] = values[2]
          matrix[4] = values[3]
          matrix[5] = values[4]
          matrix[6] = values[5]
          matrix[8] = values[6]
          matrix[9] = values[7]
          matrix[10] = values[8]
          matrix[11] = 0f
          matrix[12] = 0f
          matrix[13] = 0f
          matrix[14] = 0f
          matrix[15] = 1f
        }
        else -> error("AndroidX matrix ${source.id} has ${source.values.size} values")
      }
    }
  }

  public fun namedVariable(name: String): RcNamedVariable? = variableNames[name]

  public fun executeClick(block: RcClickActionBlock) {
    executeActions(
      block.children,
      invalidateAfterChanges = true,
      containerName = "ClickModifier(${block.type})",
    )
  }

  /** Dispatches every legacy click area containing the point, matching `CoreDocument.onClick`. */
  public fun executeClickAreasAt(x: Float, y: Float): Int =
    rcTrace(RcTraceCategory.INPUT, "rc:clickAreaHitTest") { executeClickAreasAtUnchecked(x, y) }

  private fun executeClickAreasAtUnchecked(x: Float, y: Float): Int {
    var dispatched = 0
    clickAreas.forEach { area ->
      if (
        x >= resolve(area.left) &&
          x < resolve(area.right) &&
          y >= resolve(area.top) &&
          y < resolve(area.bottom)
      ) {
        executeClickArea(area)
        dispatched++
      }
    }
    return dispatched
  }

  public fun executeClickArea(area: RcClickArea) {
    eventSink(RcPlayerEvent.HostActionMetadata(area.id, text(area.metadataId).orEmpty()))
  }

  public fun executeTouch(block: RcTouchActionBlock) {
    executeActions(
      block.children,
      invalidateAfterChanges = true,
      containerName =
        "Touch${block.type.name.lowercase().replaceFirstChar(Char::uppercase)}Modifier",
    )
  }

  /** AndroidX runs these during paint; values affect later paint operations in the same frame. */
  public fun executeRunAction(children: List<RcLinkedNode>) {
    executeActions(children, invalidateAfterChanges = false, containerName = "RunAction")
  }

  private fun executeActions(
    children: List<RcLinkedNode>,
    invalidateAfterChanges: Boolean,
    containerName: String,
  ): Unit =
    rcTrace(RcTraceCategory.INPUT, "rc:actions") {
      executeActionsUnchecked(children, invalidateAfterChanges, containerName)
    }

  private fun executeActionsUnchecked(
    children: List<RcLinkedNode>,
    invalidateAfterChanges: Boolean,
    containerName: String,
  ) {
    var changed = false
    children.forEach { child ->
      val operation =
        (child as? RcLinkedNode.Operation)?.operation
          ?: error("Nested containers are not supported inside $containerName")
      when (operation) {
        is RcHostAction -> eventSink(RcPlayerEvent.HostAction(operation.actionId))
        is RcHostMetadataAction ->
          eventSink(
            RcPlayerEvent.HostActionMetadata(
              operation.actionId,
              text(operation.metadataTextId).orEmpty(),
            )
          )
        is RcHostNamedAction ->
          eventSink(
            RcPlayerEvent.HostNamedAction(
              requireNotNull(text(operation.nameTextId)) {
                "Missing host action name text ${operation.nameTextId}"
              },
              resolveHostActionValue(operation.value),
            )
          )
        is RcHapticFeedback -> performHapticFeedback(operation)
        // AndroidX applies nested TextData while inflating ClickModifier, then ignores it during
        // onClick. RcPlayerState already loaded the flat document's text before layout.
        is RcTextData -> Unit
        is RcValueIntegerChangeAction -> {
          setInteger(operation.targetValueId, operation.value)
          changed = true
        }
        is RcValueIntegerExpressionChangeAction -> {
          val expressionId = operation.expressionId.toInt()
          val expression =
            requireNotNull(integerExpressions[expressionId]) {
              "Missing integer action expression ${operation.expressionId}"
            }
          setInteger(
            operation.targetValueId.toInt(),
            RcIntegerExpressionEvaluator.evaluate(expression) { id -> integers[id] ?: 0 },
          )
          changed = true
        }
        is RcValueStringChangeAction -> {
          setText(
            operation.targetValueId,
            requireNotNull(text(operation.valueId)) { "Missing action text ${operation.valueId}" },
          )
          changed = true
        }
        is RcValueFloatChangeAction -> {
          setFloat(operation.targetValueId, resolve(operation.value))
          changed = true
        }
        is RcValueFloatExpressionChangeAction -> {
          val expression =
            requireNotNull(floatExpressions[operation.expressionId]) {
              "Missing float action expression ${operation.expressionId}"
            }
          val runtime =
            floatExpressionRuntimes.getOrPut(expression.id) {
              RcFloatExpressionRuntime(expression, ::floatArray)
            }
          setFloat(operation.targetValueId, runtime.evaluate(frameTimeSeconds, ::resolve))
          changed = true
        }
        else -> error("Opcode ${operation.opcode} cannot execute inside $containerName")
      }
    }
    if (changed && invalidateAfterChanges) onInvalidated()
  }

  /** Dispatches a player-local effect without exposing it as a host action callback. */
  public fun performHapticFeedback(operation: RcHapticFeedback) {
    require(operation.type.wireValue >= 0) {
      "AndroidX haptic feedback type must be non-negative: ${operation.type.wireValue}"
    }
    effectSink(RcPlayerEffect.HapticFeedback(operation.type))
  }

  private fun resolveHostActionValue(value: RcHostNamedActionValue): RcHostActionValue =
    when (value) {
      RcHostNamedActionValue.None -> RcHostActionValue.None
      is RcHostNamedActionValue.FloatValue ->
        RcHostActionValue.FloatValue(
          requireNotNull(floats[value.valueId]) { "Missing host action float ${value.valueId}" }
        )
      is RcHostNamedActionValue.IntegerValue ->
        RcHostActionValue.IntegerValue(
          requireNotNull(integers[value.valueId]) { "Missing host action integer ${value.valueId}" }
        )
      is RcHostNamedActionValue.TextValue ->
        RcHostActionValue.TextValue(
          requireNotNull(text(value.valueId)) { "Missing host action text ${value.valueId}" }
        )
      is RcHostNamedActionValue.FloatListValue ->
        RcHostActionValue.FloatListValue(
          requireNotNull(floatValues(value.valueId)) {
              "Missing host action float list ${value.valueId}"
            }
            .toList()
        )
    }

  public fun integer(id: Int): Int? = integers[id]

  public fun boolean(id: Int): Boolean? = booleans[id]

  public fun long(id: Int): Long? = longs[id]

  public fun setFloat(id: Int, value: Float) {
    floats[id] = value
  }

  public fun hasComponentValues(componentId: Int): Boolean = componentId in componentValues

  /**
   * Publishes geometry after placement. The callback fires only when an exposed float changes, so
   * the first layout schedules one settling pass and stable geometry cannot form a measure loop.
   */
  public fun publishComponentGeometry(componentId: Int, geometry: RcComponentGeometry): Boolean {
    componentGeometries[componentId] = geometry
    return publishComponentValues(componentId, geometry)
  }

  /** Supplies the scrollable content extent used by alpha16 CONTENT_WIDTH/CONTENT_HEIGHT. */
  public fun publishComponentContentSize(
    componentId: Int,
    width: Float? = null,
    height: Float? = null,
  ): Boolean {
    width?.let { componentContentWidths[componentId] = it }
    height?.let { componentContentHeights[componentId] = it }
    return componentGeometries[componentId]?.let { publishComponentValues(componentId, it) }
      ?: false
  }

  private fun publishComponentValues(componentId: Int, geometry: RcComponentGeometry): Boolean {
    var changed = false
    componentValues[componentId].orEmpty().forEach { binding ->
      val value =
        when (binding.type) {
          RcComponentValue.WIDTH -> geometry.width
          RcComponentValue.HEIGHT -> geometry.height
          RcComponentValue.LOCAL_X -> geometry.localX
          RcComponentValue.LOCAL_Y -> geometry.localY
          RcComponentValue.ROOT_X -> geometry.rootX
          RcComponentValue.ROOT_Y -> geometry.rootY
          RcComponentValue.CONTENT_WIDTH -> componentContentWidths[componentId] ?: geometry.width
          RcComponentValue.CONTENT_HEIGHT -> componentContentHeights[componentId] ?: geometry.height
          else -> error("Unknown ComponentValue type ${binding.type}")
        }
      if (floats[binding.valueId]?.toRawBits() != value.toRawBits()) {
        floats[binding.valueId] = value
        changed = true
      }
    }
    if (changed) {
      // ComponentValue is a variable source in AndroidX. Expressions listening to it are refreshed
      // before the settling draw, including expressions used by layout modifiers rather than a
      // CanvasOperations block.
      document.operations.filterIsInstance<RcFloatExpression>().forEach(::applyFloatExpression)
      onInvalidated()
    }
    return changed
  }

  public fun setColor(id: Int, argb: Int) {
    colors[id] = argb
  }

  public fun setText(id: Int, value: String) {
    textOverrides[id] = value
    texts[id] = value
  }

  public fun setInteger(id: Int, value: Int) {
    integers[id] = value
  }

  public fun setLong(id: Int, value: Long) {
    longs[id] = value
  }

  public fun setNamedValue(name: String, value: RcNamedValue) {
    val variable = requireNotNull(variableNames[name]) { "Unknown named variable '$name'" }
    when {
      variable.type == RcNamedVariable.STRING_TYPE && value is RcNamedValue.Text ->
        setText(variable.id, value.value)
      variable.type == RcNamedVariable.FLOAT_TYPE && value is RcNamedValue.FloatValue ->
        floats[variable.id] = value.value
      variable.type == RcNamedVariable.COLOR_TYPE && value is RcNamedValue.Color ->
        colors[variable.id] = value.argb
      variable.type == RcNamedVariable.INT_TYPE && value is RcNamedValue.Integer ->
        integers[variable.id] = value.value
      variable.type == RcNamedVariable.LONG_TYPE && value is RcNamedValue.LongValue ->
        longs[variable.id] = value.value
      else ->
        throw IllegalArgumentException(
          "Named variable '$name' has AndroidX type ${variable.type}, incompatible with ${value::class.simpleName}"
        )
    }
  }
}

public fun RcOperation.isLayoutComputeExecutable(): Boolean =
  when (this) {
    is RcFloatExpression,
    is RcIntegerExpression,
    is RcDynamicFloatList,
    is RcUpdateDynamicFloatList,
    is RcIdLookup,
    is RcDataMapLookup,
    is RcTextMerge,
    is RcTextLength,
    is RcTextSubtext,
    is RcTextTransform,
    is RcTextFromFloat,
    is RcTextLookup,
    is RcTextLookupInt,
    is RcColorExpression,
    is RcImageAttribute,
    is RcColorAttribute,
    is RcMatrixExpression,
    is RcMatrixVectorMath,
    is RcPathExpression,
    is RcFloatConstant,
    is RcColorConstant,
    is RcTextData,
    is RcIntegerConstant,
    is RcBooleanConstant,
    is RcLongConstant,
    is RcPathData,
    is RcMatrixConstant,
    is RcIdList,
    is RcFloatList,
    is RcIdMap,
    is RcBitmapData,
    is RcNamedVariable -> true
    else -> false
  }

private fun String.capitalizeWords(): String =
  buildString(length) {
    var nextIsUpper = true
    for (character in this@capitalizeWords) {
      if (character.isWhitespace()) {
        nextIsUpper = true
        append(character)
      } else if (nextIsUpper) {
        append(character.titlecaseChar())
        nextIsUpper = false
      } else {
        append(character)
      }
    }
  }

private fun String.capitalizeFirstCharacter(): String {
  val index = indexOfFirst { !it.isWhitespace() }
  if (index < 0) return this
  return substring(0, index) + this[index].titlecaseChar() + substring(index + 1)
}

public sealed interface RcNamedValue {
  public data class Text(val value: String) : RcNamedValue

  public data class FloatValue(val value: Float) : RcNamedValue

  public data class Color(val argb: Int) : RcNamedValue

  public data class Integer(val value: Int) : RcNamedValue

  public data class LongValue(val value: Long) : RcNamedValue
}

public sealed interface RcPlayerEvent {
  public data class HostAction(val actionId: Int) : RcPlayerEvent

  public data class HostActionMetadata(val actionId: Int, val metadata: String) : RcPlayerEvent

  public data class HostNamedAction(val name: String, val value: RcHostActionValue) : RcPlayerEvent

  /** AndroidX prints this to stdout; CMP exposes it without imposing a platform logging API. */
  public data class DebugMessage(val message: String, val value: Float, val flags: Int) :
    RcPlayerEvent
}

/** Effects fulfilled by the local CMP host rather than forwarded to the embedding application. */
public sealed interface RcPlayerEffect {
  public data class HapticFeedback(val type: RcHapticType) : RcPlayerEffect

  public data class WakeIn(val seconds: Float) : RcPlayerEffect

  public data object NextFrame : RcPlayerEffect
}

public sealed interface RcHostActionValue {
  public data object None : RcHostActionValue

  public data class FloatValue(val value: Float) : RcHostActionValue

  public data class IntegerValue(val value: Int) : RcHostActionValue

  public data class TextValue(val value: String) : RcHostActionValue

  /** Immutable snapshot: later document list mutations cannot change an already emitted event. */
  public data class FloatListValue(val value: List<Float>) : RcHostActionValue
}
