package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBooleanConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDataMapLookup
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIdList
import ee.schimke.composeai.rcplayer.protocol.RcIdLookup
import ee.schimke.composeai.rcplayer.protocol.RcIdMap
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLongConstant
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
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueStringChangeAction

/** Typed runtime values for the CMP player; independent from AndroidX `RemoteContext`. */
public class RcPlayerState(
  public val document: RcDocument,
  namedValues: Map<String, RcNamedValue> = emptyMap(),
  private val eventSink: (RcPlayerEvent) -> Unit = {},
  private val onInvalidated: () -> Unit = {},
) {
  private val floats = mutableMapOf<Int, Float>()
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
  private var frameTimeSeconds: Float = 0f

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

  public fun beginFrame(timeSeconds: Float = 0f) {
    frameTimeSeconds = timeSeconds
    paths.clear()
    paths.putAll(basePaths)
    texts.clear()
    texts.putAll(baseTexts)
    texts.putAll(textOverrides)
    computedMatrices.clear()
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
}

public sealed interface RcHostActionValue {
  public data object None : RcHostActionValue

  public data class FloatValue(val value: Float) : RcHostActionValue

  public data class IntegerValue(val value: Int) : RcHostActionValue

  public data class TextValue(val value: String) : RcHostActionValue

  /** Immutable snapshot: later document list mutations cannot change an already emitted event. */
  public data class FloatListValue(val value: List<Float>) : RcHostActionValue
}
