package ee.schimke.composeai.rcplayer.compose

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionCall
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcTextAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTextMeasure
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.protocol.supportReport
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import ee.schimke.composeai.rcplayer.runtime.RcIntegerExpressionEvaluator
import ee.schimke.composeai.rcplayer.runtime.RcLayoutTree

public data class RcComposeSupportIssue(
  val operationIndex: Int,
  val operation: String,
  val detail: String,
)

public data class RcComposeSupportReport(val issues: List<RcComposeSupportIssue>) {
  public val fullyRenderable: Boolean
    get() = issues.isEmpty()

  public fun requireFullyRenderable() {
    if (issues.isNotEmpty()) {
      throw IllegalArgumentException(
        "Document is not renderable by the CMP player: " +
          issues.joinToString { "${it.operation}[${it.operationIndex}]: ${it.detail}" }
      )
    }
  }
}

/** Backend-specific coverage, including nested PaintBundle commands hidden behind one RC opcode. */
public fun RcDocument.composeSupportReport(): RcComposeSupportReport {
  val issues = mutableListOf<RcComposeSupportIssue>()
  supportReport().parseOnly.forEach { entry ->
    issues +=
      RcComposeSupportIssue(-1, entry.stableName, "operation is decoded but has no semantics")
  }
  operations.forEachIndexed { index, operation ->
    if (operation is RcPaintData) {
      paintIssue(operation)?.let { detail ->
        issues += RcComposeSupportIssue(index, "PaintData", detail)
      }
    }
    val measurementType =
      when (operation) {
        is RcTextMeasure -> operation.type and 0xff
        is RcTextAttribute -> operation.type and 0xff
        else -> null
      }
    if (measurementType != null && measurementType !in 0..6) {
      issues +=
        RcComposeSupportIssue(index, "TextMeasurement", "type $measurementType is not implemented")
    }
    if (operation is RcBitmapData) {
      when {
        operation.encoding != RcBitmapData.ENCODING_INLINE ->
          issues +=
            RcComposeSupportIssue(
              index,
              "BitmapData",
              "encoding ${operation.encoding} requires an image host",
            )
        operation.type !in
          setOf(
            RcBitmapData.TYPE_PNG_8888,
            RcBitmapData.TYPE_PNG,
            RcBitmapData.TYPE_RAW8,
            RcBitmapData.TYPE_RAW8888,
            RcBitmapData.TYPE_PNG_ALPHA_8,
          ) ->
          issues +=
            RcComposeSupportIssue(index, "BitmapData", "type ${operation.type} is not implemented")
        operation.type == RcBitmapData.TYPE_RAW8 &&
          operation.data.size < operation.width * operation.height ->
          issues += RcComposeSupportIssue(index, "BitmapData", "raw alpha payload is truncated")
        operation.type == RcBitmapData.TYPE_RAW8888 &&
          operation.data.size < operation.width * operation.height * 4 ->
          issues += RcComposeSupportIssue(index, "BitmapData", "raw RGBA payload is truncated")
      }
    }
    if (operation is RcImageAttribute && operation.type !in 0..1) {
      issues +=
        RcComposeSupportIssue(index, "ImageAttribute", "type ${operation.type} is not implemented")
    }
    if (operation is RcColorAttribute && operation.type !in 0..6) {
      issues +=
        RcComposeSupportIssue(index, "ColorAttribute", "type ${operation.type} is not implemented")
    }
    if (operation is RcColorExpression && operation.mode !in 0..6) {
      issues +=
        RcComposeSupportIssue(index, "ColorExpression", "mode ${operation.mode} is not implemented")
    }
    if (operation is RcIntegerExpression) {
      RcIntegerExpressionEvaluator.validationError(operation)?.let { detail ->
        issues += RcComposeSupportIssue(index, "IntegerExpression", detail)
      }
    }
    if (
      operation is RcWidthModifier &&
        operation.type !in
          setOf(
            RcDimensionType.EXACT,
            RcDimensionType.FILL,
            RcDimensionType.EXACT_DP,
            RcDimensionType.FILL_PARENT_MAX_WIDTH,
          )
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "WidthModifier",
          "dimension type ${operation.type} is not implemented",
        )
    }
    if (
      operation is RcHeightModifier &&
        operation.type !in
          setOf(
            RcDimensionType.EXACT,
            RcDimensionType.FILL,
            RcDimensionType.EXACT_DP,
            RcDimensionType.FILL_PARENT_MAX_HEIGHT,
          )
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "HeightModifier",
          "dimension type ${operation.type} is not implemented",
        )
    }
  }
  val functions = operations.filterIsInstance<RcFloatFunctionDefine>().associateBy { it.id }
  operations.forEachIndexed { index, operation ->
    if (operation is RcFloatFunctionCall) {
      val definition = functions[operation.functionId]
      when {
        definition == null ->
          issues +=
            RcComposeSupportIssue(
              index,
              "FunctionCall",
              "function ${operation.functionId} is not defined",
            )
        operation.arguments.size > definition.parameterIds.size ->
          issues +=
            RcComposeSupportIssue(
              index,
              "FunctionCall",
              "${operation.arguments.size} arguments exceed ${definition.parameterIds.size} parameters",
            )
      }
    }
  }
  runCatching { RcDocumentLinker.link(this) }
    .fold(
      onSuccess = { linked ->
        runCatching { RcLayoutTree.build(linked) }
          .exceptionOrNull()
          ?.let { issues += RcComposeSupportIssue(-1, "LayoutStructure", it.message ?: "invalid") }
      },
      onFailure = {
        issues += RcComposeSupportIssue(-1, "ContainerStructure", it.message ?: "invalid")
      },
    )
  return RcComposeSupportReport(issues)
}

private fun paintIssue(paint: RcPaintData): String? {
  var index = 0
  while (index < paint.words.size) {
    val command = paint.words[index++]
    val type = command and 0xffff
    val argumentWords =
      when (type) {
        PAINT_TEXT_SIZE,
        PAINT_COLOR,
        PAINT_STROKE_WIDTH,
        PAINT_ALPHA,
        PAINT_COLOR_ID,
        PAINT_TYPEFACE -> 1
        PAINT_STROKE_CAP,
        PAINT_STYLE,
        PAINT_STROKE_JOIN,
        PAINT_BLEND_MODE -> 0
        else -> return "paint command $type is not implemented"
      }
    if (index + argumentWords > paint.words.size) return "paint command $type is truncated"
    if (type == PAINT_STYLE && command ushr 16 !in 0..1) {
      return "paint style ${command ushr 16} is not implemented"
    }
    if (type == PAINT_BLEND_MODE && command ushr 16 !in 0..28) {
      return "blend mode ${command ushr 16} is not implemented"
    }
    if (type == PAINT_TYPEFACE && paint.words[index] !in 0..3) {
      return "font id ${paint.words[index]} is not implemented"
    }
    index += argumentWords
  }
  return null
}

private const val PAINT_TEXT_SIZE = 1
private const val PAINT_COLOR = 4
private const val PAINT_STROKE_WIDTH = 5
private const val PAINT_STROKE_CAP = 7
private const val PAINT_STYLE = 8
private const val PAINT_ALPHA = 12
private const val PAINT_STROKE_JOIN = 15
private const val PAINT_BLEND_MODE = 18
private const val PAINT_COLOR_ID = 19
private const val PAINT_TYPEFACE = 16
