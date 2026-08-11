package ee.schimke.composeai.rcplayer.compose

import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcConditionalOperations
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDataMapLookup
import ee.schimke.composeai.rcplayer.protocol.RcDebugMessage
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionCall
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFlowLayout
import ee.schimke.composeai.rcplayer.protocol.RcFontData
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerAttribute
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHapticFeedback
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcIdMap
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcImageLayout
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute
import ee.schimke.composeai.rcplayer.protocol.RcLoopOperation
import ee.schimke.composeai.rcplayer.protocol.RcMarqueeModifier
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperationInventory
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfile
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcScrollModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTextFromFloat
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextLookup
import ee.schimke.composeai.rcplayer.protocol.RcTextLookupInt
import ee.schimke.composeai.rcplayer.protocol.RcTextMeasure
import ee.schimke.composeai.rcplayer.protocol.RcTextMerge
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTextSubtext
import ee.schimke.composeai.rcplayer.protocol.RcTextTransform
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueStringChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.protocol.supportReport
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import ee.schimke.composeai.rcplayer.runtime.RcIntegerExpressionEvaluator
import ee.schimke.composeai.rcplayer.runtime.RcLayoutTree
import ee.schimke.composeai.rcplayer.runtime.RcLinkedNode
import ee.schimke.composeai.rcplayer.runtime.hasPortableVisibilityAnimation
import ee.schimke.composeai.rcplayer.runtime.isLayoutComputeExecutable

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

/**
 * Backend-specific coverage, including nested PaintBundle commands hidden behind one RC opcode.
 *
 * [allowExternalImagePlaceholders] treats host-backed bitmap encodings as intentionally blank. It
 * is useful for offline renderers that need to exercise the rest of a document without claiming
 * that an unreachable URL image was decoded.
 */
public fun RcDocument.composeSupportReport(
  profile: RcOperationProfile? = null,
  availableFontFamilies: Set<String> = emptySet(),
  allowExternalImagePlaceholders: Boolean = false,
): RcComposeSupportReport {
  val issues = mutableListOf<RcComposeSupportIssue>()
  val bitmapIds = operations.filterIsInstance<RcBitmapData>().mapTo(mutableSetOf()) { it.imageId }
  val fontIds = operations.filterIsInstance<RcFontData>().mapTo(mutableSetOf()) { it.fontId }
  val texts =
    operations.filterIsInstance<ee.schimke.composeai.rcplayer.protocol.RcTextData>().associate {
      it.id to it.text
    }
  val idMaps = operations.filterIsInstance<RcIdMap>().associateBy { it.id }
  // A text id is declared either by a literal DATA_TEXT or by a runtime operation that publishes a
  // string under its out id. Only the literals carry a value the report can inspect (font family
  // names, style properties), so `texts` stays literal-only while `textIds` covers both.
  val textIds =
    texts.keys +
      operations.mapNotNull { operation ->
        when (operation) {
          is RcTextMerge -> operation.outId
          is RcTextSubtext -> operation.outId
          is RcTextTransform -> operation.outId
          is RcTextFromFloat -> operation.outId
          is RcTextLookup -> operation.outId
          is RcTextLookupInt -> operation.outId
          // A data-map lookup writes `outId` to the store its selected entry's type names, so it
          // declares text only if the map can yield a string at all. The key is resolved at
          // runtime, so a map mixing string and non-string entries stays permissive: rejecting it
          // would refuse a document whose key selects the string.
          is RcDataMapLookup ->
            operation.outId.takeIf { _ ->
              idMaps[operation.mapId]?.entries?.any { it.type == RcIdMap.TYPE_STRING } ?: true
            }
          else -> null
        }
      }
  val colorIds =
    operations.filterIsInstance<ee.schimke.composeai.rcplayer.protocol.RcColorConstant>().mapTo(
      mutableSetOf()
    ) {
      it.id
    }
  val dynamicLists = operations.filterIsInstance<RcDynamicFloatList>().associateBy { it.id }
  val integerExpressionIds =
    operations.filterIsInstance<RcIntegerExpression>().map { it.outId }.toSet()
  val floatExpressionIds =
    operations
      .filterIsInstance<ee.schimke.composeai.rcplayer.protocol.RcFloatExpression>()
      .map { it.id }
      .toSet()
  supportReport().parseOnly.forEach { entry ->
    issues +=
      RcComposeSupportIssue(-1, entry.stableName, "operation is decoded but has no semantics")
  }
  if (profile != null) {
    operations.forEachIndexed { index, operation ->
      if (!profile.supports(operation.opcode)) {
        issues +=
          RcComposeSupportIssue(
            index,
            RcOperationInventory.byOpcode[operation.opcode]?.stableName
              ?: "Opcode${operation.opcode}",
            "operation is excluded from the ${profile.name} profile",
          )
      }
    }
  }
  operations.forEachIndexed { index, operation ->
    if (operation is RcAnimationSpec) {
      when {
        !operation.motionDurationMillis.value.isFinite() ||
          operation.motionDurationMillis.value < 0f ->
          issues +=
            RcComposeSupportIssue(
              index,
              "AnimationSpec",
              "motion duration ${operation.motionDurationMillis.value} is not supported",
            )
        !operation.visibilityDurationMillis.value.isFinite() ||
          operation.visibilityDurationMillis.value < 0f ->
          issues +=
            RcComposeSupportIssue(
              index,
              "AnimationSpec",
              "visibility duration ${operation.visibilityDurationMillis.value} is not supported",
            )
        operation.motionEasingType !in setOf(1, 2, 3, 4, 5, 6, 13, 14) ->
          issues +=
            RcComposeSupportIssue(
              index,
              "AnimationSpec",
              "motion easing ${operation.motionEasingType} requires parameters or is unknown",
            )
        operation.visibilityEasingType !in setOf(1, 2, 3, 4, 5, 6, 13, 14) ->
          issues +=
            RcComposeSupportIssue(
              index,
              "AnimationSpec",
              "visibility easing ${operation.visibilityEasingType} requires parameters or is unknown",
            )
        !operation.hasPortableVisibilityAnimation ->
          issues +=
            RcComposeSupportIssue(
              index,
              "AnimationSpec",
              "exit animation ${operation.exitAnimation.wireValue} requires ParticleAnimation",
            )
      }
    }
    if (operation is RcPaintData) {
      paintIssue(operation)?.let { detail ->
        issues += RcComposeSupportIssue(index, "PaintData", detail)
      }
    }
    if (operation is RcDebugMessage && operation.textId !in textIds) {
      issues +=
        RcComposeSupportIssue(index, "DebugMessage", "text id ${operation.textId} is not declared")
    }
    val supportedMeasurementTypes =
      when (operation) {
        is RcTextMeasure -> 0..5
        is RcTextAttribute -> 0..6
        else -> null
      }
    val measurementType =
      when (operation) {
        is RcTextMeasure -> operation.type and 0xff
        is RcTextAttribute -> operation.type and 0xff
        else -> null
      }
    if (
      measurementType != null &&
        supportedMeasurementTypes != null &&
        measurementType !in supportedMeasurementTypes
    ) {
      issues +=
        RcComposeSupportIssue(index, "TextMeasurement", "type $measurementType is not implemented")
    }
    if (operation is RcBitmapData) {
      when {
        operation.encoding != RcBitmapData.ENCODING_INLINE && !allowExternalImagePlaceholders ->
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
    if (operation is RcImageLayout) {
      if (operation.bitmapId !in bitmapIds) {
        issues +=
          RcComposeSupportIssue(
            index,
            "ImageLayout",
            "bitmap id ${operation.bitmapId} is not declared",
          )
      }
      if (operation.scaleType !in 0..7) {
        issues +=
          RcComposeSupportIssue(
            index,
            "ImageLayout",
            "scale type ${operation.scaleType} is not implemented",
          )
      }
    }
    if (operation is RcTextLayout) {
      when {
        operation.textId !in textIds ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              "text id ${operation.textId} is not declared",
            )
        operation.fontStyle !in 0..3 ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              "font style ${operation.fontStyle} is not implemented",
            )
        fontFamilyIssue(operation.fontFamilyId, texts, fontIds, availableFontFamilies) != null ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              requireNotNull(
                fontFamilyIssue(operation.fontFamilyId, texts, fontIds, availableFontFamilies)
              ),
            )
        operation.textAlign !in RcTextLayout.ALIGN_LEFT..RcTextLayout.ALIGN_END ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              "text alignment ${operation.textAlign} is not implemented",
            )
        operation.flags and RcTextLayout.FLAG_DYNAMIC_COLOR.inv() != 0 ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              "flags ${operation.flags} are not implemented",
            )
        operation.flags and RcTextLayout.FLAG_DYNAMIC_COLOR != 0 && operation.color !in colorIds ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              "dynamic color id ${operation.color} is not declared",
            )
        operation.overflow !in RcTextLayout.OVERFLOW_CLIP..RcTextLayout.OVERFLOW_MIDDLE_ELLIPSIS ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TextLayout",
              "overflow ${operation.overflow} is not implemented",
            )
        operation.maxLines <= 0 ->
          issues += RcComposeSupportIssue(index, "TextLayout", "maxLines must be positive")
      }
    }
    if (operation is RcCoreText) {
      when {
        operation.textId !in textIds ->
          issues +=
            RcComposeSupportIssue(index, "CoreText", "text id ${operation.textId} is not declared")
        else ->
          textStyleIssue(operation.properties, colorIds, texts, fontIds, availableFontFamilies)
            ?.let { detail -> issues += RcComposeSupportIssue(index, "CoreText", detail) }
      }
    }
    if (operation is RcTextStyle) {
      textStyleIssue(operation.properties, colorIds, texts, fontIds, availableFontFamilies)?.let {
        detail ->
        issues += RcComposeSupportIssue(index, "TextStyle", detail)
      }
    }
    if (operation is RcColorAttribute && operation.type !in 0..6) {
      issues +=
        RcComposeSupportIssue(index, "ColorAttribute", "type ${operation.type} is not implemented")
    }
    if (operation is RcColorExpression && operation.mode !in 0..6) {
      issues +=
        RcComposeSupportIssue(index, "ColorExpression", "mode ${operation.mode} is not implemented")
    }
    if (
      operation is RcBackgroundModifier &&
        operation.shapeType !in
          RcBackgroundModifier.SHAPE_RECTANGLE..RcBackgroundModifier.SHAPE_CIRCLE
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "BackgroundModifier",
          "shape type ${operation.shapeType} is not implemented",
        )
    }
    if (operation is RcConditionalOperations && operation.type !in 0..6) {
      issues +=
        RcComposeSupportIssue(
          index,
          "ConditionalOperations",
          "condition type ${operation.type} is not implemented",
        )
    }
    if (operation is RcLoopOperation) {
      val from = operation.from.takeIf { it.referencedId == null }?.value
      val step = operation.step.takeIf { it.referencedId == null }?.value
      val until = operation.until.takeIf { it.referencedId == null }?.value
      when {
        listOfNotNull(from, step, until).any { !it.isFinite() } ->
          issues += RcComposeSupportIssue(index, "LoopOperation", "literal values must be finite")
        step == 0f ->
          issues += RcComposeSupportIssue(index, "LoopOperation", "literal step cannot be zero")
        step != null && from != null && until != null && step < 0f && from < until ->
          issues +=
            RcComposeSupportIssue(
              index,
              "LoopOperation",
              "literal step is negative while from < until",
            )
        step != null &&
          from != null &&
          until != null &&
          from < until &&
          (until - from) / step > 10_000f ->
          issues +=
            RcComposeSupportIssue(index, "LoopOperation", "literal loop exceeds 10000 iterations")
      }
    }
    if (operation is RcGraphicsLayerModifier) {
      val supported =
        setOf(
          RcGraphicsLayerModifier.SCALE_X,
          RcGraphicsLayerModifier.SCALE_Y,
          RcGraphicsLayerModifier.ROTATION_X,
          RcGraphicsLayerModifier.ROTATION_Y,
          RcGraphicsLayerModifier.ROTATION_Z,
          RcGraphicsLayerModifier.TRANSFORM_ORIGIN_X,
          RcGraphicsLayerModifier.TRANSFORM_ORIGIN_Y,
          RcGraphicsLayerModifier.TRANSLATION_X,
          RcGraphicsLayerModifier.TRANSLATION_Y,
          RcGraphicsLayerModifier.SHADOW_ELEVATION,
          RcGraphicsLayerModifier.ALPHA,
          RcGraphicsLayerModifier.CAMERA_DISTANCE,
        )
      operation.attributes
        .firstOrNull { it.index !in supported || it !is RcGraphicsLayerAttribute.FloatValue }
        ?.let { attribute ->
          issues +=
            RcComposeSupportIssue(
              index,
              "GraphicsLayerModifier",
              "attribute ${attribute.index} is not implemented by the CMP graphics backend",
            )
        }
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
            RcDimensionType.WEIGHT,
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
            RcDimensionType.WEIGHT,
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
    if (operation is RcBoxLayout) {
      if (operation.horizontalPositioning !in 1..3) {
        issues +=
          RcComposeSupportIssue(
            index,
            "BoxLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "BoxLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
    }
    if (operation is RcFitBoxLayout) {
      if (operation.horizontalPositioning !in 1..3) {
        issues +=
          RcComposeSupportIssue(
            index,
            "FitBoxLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "FitBoxLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
    }
    if (operation is RcRowLayout) {
      if (operation.horizontalPositioning !in setOf(1, 2, 3, 6, 7, 8)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "RowLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "RowLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
    }
    if (operation is RcColumnLayout) {
      if (operation.horizontalPositioning !in 1..3) {
        issues +=
          RcComposeSupportIssue(
            index,
            "ColumnLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5, 6, 7, 8)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "ColumnLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
    }
    if (operation is RcFlowLayout) {
      if (operation.horizontalPositioning !in setOf(1, 2, 3, 6, 7, 8)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "FlowLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "FlowLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
      if (operation.maxItemsInEachRow <= 0) {
        issues +=
          RcComposeSupportIssue(
            index,
            "FlowLayout",
            "maxItemsInEachRow ${operation.maxItemsInEachRow} must be positive",
          )
      }
      if (operation.maxLines <= 0) {
        issues +=
          RcComposeSupportIssue(
            index,
            "FlowLayout",
            "maxLines ${operation.maxLines} must be positive",
          )
      }
    }
    if (operation is RcCollapsibleRowLayout) {
      if (operation.horizontalPositioning !in setOf(1, 2, 3, 6, 7, 8)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "CollapsibleRowLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "CollapsibleRowLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
    }
    if (operation is RcCollapsibleColumnLayout) {
      if (operation.horizontalPositioning !in 1..3) {
        issues +=
          RcComposeSupportIssue(
            index,
            "CollapsibleColumnLayout",
            "horizontal position ${operation.horizontalPositioning} is not implemented",
          )
      }
      if (operation.verticalPositioning !in setOf(2, 4, 5, 6, 7, 8)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "CollapsibleColumnLayout",
            "vertical position ${operation.verticalPositioning} is not implemented",
          )
      }
    }
    if (
      operation is RcCollapsiblePriorityModifier &&
        operation.orientation !in
          setOf(RcCollapsiblePriorityModifier.HORIZONTAL, RcCollapsiblePriorityModifier.VERTICAL)
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "CollapsiblePriorityModifierOperation",
          "orientation ${operation.orientation} is not implemented",
        )
    }
    if (operation is RcLayoutCompute) {
      if (operation.type !in setOf(RcLayoutCompute.MEASURE, RcLayoutCompute.POSITION)) {
        issues +=
          RcComposeSupportIssue(
            index,
            "LayoutComputeOperation",
            "type ${operation.type} is not implemented",
          )
      }
      if (operation.animateChanges) {
        issues +=
          RcComposeSupportIssue(
            index,
            "LayoutComputeOperation",
            "animated measure transitions are not implemented",
          )
      }
      val bounds = dynamicLists[operation.boundsId]
      when {
        bounds == null ->
          issues +=
            RcComposeSupportIssue(
              index,
              "LayoutComputeOperation",
              "bounds id ${operation.boundsId} is not a dynamic float list",
            )
        bounds.length.referencedId == null && bounds.length.value.toInt() != 6 ->
          issues +=
            RcComposeSupportIssue(
              index,
              "LayoutComputeOperation",
              "bounds list ${operation.boundsId} has length ${bounds.length.value.toInt()}, expected 6",
            )
      }
    }
    if (operation is RcAccessibilitySemantics) {
      if (operation.role !in -1..RcAccessibilitySemantics.ROLE_UNKNOWN) {
        issues +=
          RcComposeSupportIssue(index, "CoreSemantics", "role ${operation.role} is not implemented")
      }
      if (
        operation.mode !in RcAccessibilitySemantics.MODE_SET..RcAccessibilitySemantics.MODE_MERGE
      ) {
        issues +=
          RcComposeSupportIssue(index, "CoreSemantics", "mode ${operation.mode} is not implemented")
      }
      listOf(
          "content description" to operation.contentDescriptionId,
          "text" to operation.textId,
          "state description" to operation.stateDescriptionId,
        )
        .filter { (_, id) -> id != 0 && id !in textIds }
        .forEach { (name, id) ->
          issues +=
            RcComposeSupportIssue(index, "CoreSemantics", "$name text id $id is not declared")
        }
    }
    if (
      operation is RcScrollModifier &&
        operation.direction !in RcScrollModifier.VERTICAL..RcScrollModifier.HORIZONTAL
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "ScrollModifierOperation",
          "direction ${operation.direction} is not implemented",
        )
    }
    if (operation is RcMarqueeModifier) {
      listOf(
          "repeat delay" to operation.repeatDelayMillis.value,
          "initial delay" to operation.initialDelayMillis.value,
          "spacing" to operation.spacing.value,
          "velocity" to operation.velocity.value,
        )
        .filter { (_, value) -> !value.isFinite() }
        .forEach { (name, _) ->
          issues += RcComposeSupportIssue(index, "MarqueeModifierOperation", "$name must be finite")
        }
      if (operation.velocity.value <= 0f) {
        issues +=
          RcComposeSupportIssue(
            index,
            "MarqueeModifierOperation",
            "velocity must be greater than zero",
          )
      }
    }
    if (operation is RcTouchExpression) {
      when {
        operation.touchEffects != 0 ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TouchExpression",
              "touch effects ${operation.touchEffects} are not implemented",
            )
        operation.easingSpec.isNotEmpty() ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TouchExpression",
              "custom velocity easing is not implemented",
            )
        operation.stopMode !in
          RcTouchExpression.STOP_GENTLY..RcTouchExpression.STOP_NOTCHES_SINGLE_EVEN ->
          issues +=
            RcComposeSupportIssue(
              index,
              "TouchExpression",
              "stop mode ${operation.stopMode} is not implemented",
            )
        operation.stopMode in
          setOf(RcTouchExpression.STOP_NOTCHES_EVEN, RcTouchExpression.STOP_NOTCHES_SINGLE_EVEN) &&
          operation.stopSpec.isEmpty() ->
          issues += RcComposeSupportIssue(index, "TouchExpression", "even notches require a count")
      }
    }
    if (operation is RcValueStringChangeAction && operation.valueId !in textIds) {
      issues +=
        RcComposeSupportIssue(
          index,
          "ValueStringChangeActionOperation",
          "value text id ${operation.valueId} is not declared",
        )
    }
    if (operation is RcHostNamedAction && operation.nameTextId !in textIds) {
      issues +=
        RcComposeSupportIssue(
          index,
          "HostNamedActionOperation",
          "name text id ${operation.nameTextId} is not declared",
        )
    }
    if (operation is RcHostMetadataAction && operation.metadataTextId !in textIds) {
      issues +=
        RcComposeSupportIssue(
          index,
          "HostActionMetadataOperation",
          "metadata text id ${operation.metadataTextId} is not declared",
        )
    }
    if (
      operation is RcValueIntegerExpressionChangeAction &&
        operation.expressionId.toInt() !in integerExpressionIds
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "ValueIntegerExpressionChangeActionOperation",
          "integer expression id ${operation.expressionId} is not declared",
        )
    }
    if (
      operation is RcValueFloatExpressionChangeAction &&
        operation.expressionId !in floatExpressionIds
    ) {
      issues +=
        RcComposeSupportIssue(
          index,
          "ValueFloatExpressionChangeActionOperation",
          "float expression id ${operation.expressionId} is not declared",
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
        invalidLayoutComputeChild(linked.operations)?.let { operation ->
          issues +=
            RcComposeSupportIssue(
              -1,
              "LayoutComputeOperation",
              "nested opcode ${operation.opcode} cannot execute during layout",
            )
        }
        invalidActionChild(linked.operations, RcOpcodes.MODIFIER_CLICK)?.let { operation ->
          issues +=
            RcComposeSupportIssue(
              -1,
              "ClickModifierOperation",
              "nested opcode ${operation.opcode} is not a click action",
            )
        }
        invalidActionChild(linked.operations, RcOpcodes.MODIFIER_MULTI_CLICK)?.let { operation ->
          issues +=
            RcComposeSupportIssue(
              -1,
              "MultiClickModifier",
              "nested opcode ${operation.opcode} is not an action",
            )
        }
        listOf(
            RcOpcodes.MODIFIER_TOUCH_DOWN to "TouchDownModifierOperation",
            RcOpcodes.MODIFIER_TOUCH_UP to "TouchUpModifierOperation",
            RcOpcodes.MODIFIER_TOUCH_CANCEL to "TouchCancelModifierOperation",
          )
          .forEach { (opcode, name) ->
            invalidActionChild(linked.operations, opcode)?.let { operation ->
              issues +=
                RcComposeSupportIssue(
                  -1,
                  name,
                  "nested opcode ${operation.opcode} is not an action",
                )
            }
          }
        invalidActionChild(linked.operations, RcOpcodes.RUN_ACTION)?.let { operation ->
          issues +=
            RcComposeSupportIssue(
              -1,
              "RunActionOperation",
              "nested opcode ${operation.opcode} is not an action",
            )
        }
        invalidScrollChild(linked.operations)?.let { detail ->
          issues += RcComposeSupportIssue(-1, "ScrollModifierOperation", detail)
        }
        val layoutResult = runCatching { RcLayoutTree.build(linked) }
        layoutResult.exceptionOrNull()?.let {
          issues += RcComposeSupportIssue(-1, "LayoutStructure", it.message ?: "invalid")
        }
        layoutResult.getOrNull()?.let { layout ->
          if (hasUndispatchedAccessibilityClick(layout)) {
            issues +=
              RcComposeSupportIssue(
                -1,
                "CoreSemantics",
                "clickable semantics requires a ClickModifierOperation on the same component",
              )
          }
        }
        if (hasInvalidDrawContent(linked.operations)) {
          issues +=
            RcComposeSupportIssue(
              -1,
              "DrawContent",
              "operation must be inside CanvasOperations attached to a layout component",
            )
        }
      },
      onFailure = {
        issues += RcComposeSupportIssue(-1, "ContainerStructure", it.message ?: "invalid")
      },
    )
  return RcComposeSupportReport(issues)
}

private fun invalidScrollChild(nodes: List<RcLinkedNode>, insideScroll: Boolean = false): String? {
  nodes.forEach { node ->
    if (node is RcLinkedNode.Operation && node.operation is RcTouchExpression && !insideScroll) {
      return "TouchExpression is only implemented as a direct scroll child"
    }
    if (node is RcLinkedNode.Container && node.operation is RcScrollModifier) {
      val children = node.children.map { it.operation() }
      if (children.size != 1 || children.singleOrNull() !is RcTouchExpression) {
        return "container requires exactly one TouchExpression child"
      }
      invalidScrollChild(node.children, insideScroll = true)?.let {
        return it
      }
      return@forEach
    }
    if (node is RcLinkedNode.Container)
      invalidScrollChild(node.children, insideScroll)?.let {
        return it
      }
  }
  return null
}

private fun invalidActionChild(
  nodes: List<RcLinkedNode>,
  containerOpcode: Int,
): ee.schimke.composeai.rcplayer.protocol.RcOperation? {
  nodes.forEach { node ->
    if (node is RcLinkedNode.Container && node.operation.opcode == containerOpcode) {
      node.children.forEach { child ->
        val operation = (child as? RcLinkedNode.Operation)?.operation ?: return child.operation()
        if (
          operation !is RcHostAction &&
            operation !is RcHapticFeedback &&
            operation !is RcHostMetadataAction &&
            operation !is RcHostNamedAction &&
            operation !is ee.schimke.composeai.rcplayer.protocol.RcTextData &&
            operation !is RcValueIntegerChangeAction &&
            operation !is RcValueIntegerExpressionChangeAction &&
            operation !is RcValueStringChangeAction &&
            operation !is RcValueFloatChangeAction &&
            operation !is RcValueFloatExpressionChangeAction
        ) {
          return operation
        }
      }
    }
    if (node is RcLinkedNode.Container)
      invalidActionChild(node.children, containerOpcode)?.let {
        return it
      }
  }
  return null
}

private fun hasUndispatchedAccessibilityClick(
  node: ee.schimke.composeai.rcplayer.runtime.RcLayoutNode
): Boolean {
  if (node.modifiers.accessibility.any { it.clickable } && node.modifiers.clicks.isEmpty())
    return true
  val children =
    when (node) {
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Root -> node.children
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Content -> node.children
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Canvas -> listOfNotNull(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Box -> listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Row -> listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Column -> listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.Flow -> listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.State -> listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.CollapsibleRow -> listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.CollapsibleColumn ->
        listOf(node.content)
      is ee.schimke.composeai.rcplayer.runtime.RcLayoutNode.FitBox -> listOf(node.content)
      else -> emptyList()
    }
  return children.any(::hasUndispatchedAccessibilityClick)
}

private fun invalidLayoutComputeChild(
  nodes: List<RcLinkedNode>
): ee.schimke.composeai.rcplayer.protocol.RcOperation? {
  nodes.forEach { node ->
    if (node is RcLinkedNode.Container && node.operation is RcLayoutCompute) {
      node.children.forEach { child ->
        val operation = (child as? RcLinkedNode.Operation)?.operation ?: return child.operation()
        if (!operation.isLayoutComputeExecutable()) return operation
      }
    }
    if (node is RcLinkedNode.Container)
      invalidLayoutComputeChild(node.children)?.let {
        return it
      }
  }
  return null
}

private fun RcLinkedNode.operation(): ee.schimke.composeai.rcplayer.protocol.RcOperation =
  when (this) {
    is RcLinkedNode.Operation -> operation
    is RcLinkedNode.Container -> operation
  }

private fun textStyleIssue(
  properties: List<RcTextStyleProperty>,
  colorIds: Set<Int>,
  texts: Map<Int, String>,
  fontIds: Set<Int>,
  availableFontFamilies: Set<String>,
): String? {
  fun int(id: Int, default: Int): Int =
    properties.filterIsInstance<RcTextStyleProperty.IntValue>().lastOrNull { it.id == id }?.value
      ?: default
  fun float(id: Int, default: Float): ee.schimke.composeai.rcplayer.protocol.RcFloatWord =
    properties.filterIsInstance<RcTextStyleProperty.FloatValue>().lastOrNull { it.id == id }?.value
      ?: ee.schimke.composeai.rcplayer.protocol.RcFloatWord.literal(default)
  fun bool(id: Int, default: Boolean): Boolean =
    properties
      .filterIsInstance<RcTextStyleProperty.BooleanValue>()
      .lastOrNull { it.id == id }
      ?.value ?: default

  val colorId = int(4, -1)
  if (colorId != -1 && colorId !in colorIds) return "dynamic color id $colorId is not declared"
  val fontStyle = int(6, 0)
  if (fontStyle !in 0..3) return "font style $fontStyle is not implemented"
  val fontFamily = int(8, -1)
  fontFamilyIssue(fontFamily, texts, fontIds, availableFontFamilies)?.let {
    return it
  }
  val alignment = int(9, RcTextLayout.ALIGN_LEFT)
  if (alignment !in RcTextLayout.ALIGN_LEFT..RcTextLayout.ALIGN_END) {
    return "text alignment $alignment is not implemented"
  }
  val overflow = int(10, RcTextLayout.OVERFLOW_CLIP)
  if (overflow !in RcTextLayout.OVERFLOW_CLIP..RcTextLayout.OVERFLOW_MIDDLE_ELLIPSIS) {
    return "overflow $overflow is not implemented"
  }
  val maxLines = int(11, Int.MAX_VALUE)
  if (maxLines <= 0) return "maxLines must be positive"
  val breakStrategy = int(15, 0)
  if (breakStrategy !in 0..2) return "line break strategy $breakStrategy is not implemented"
  val hyphenation = int(16, 0)
  if (hyphenation !in 0..1) return "hyphenation frequency $hyphenation is not implemented"
  val justification = int(17, 0)
  if (justification !in 0..1) return "justification mode $justification is not implemented"
  val axes =
    properties.filterIsInstance<RcTextStyleProperty.IntArrayValue>().lastOrNull { it.id == 20 }
  val axisValues =
    properties.filterIsInstance<RcTextStyleProperty.FloatArrayValue>().lastOrNull { it.id == 21 }
  // Font-variation axes ARE implemented for layout text — the player resolves properties 20/21 into
  // a `FontVariation.Settings` and instances the host's face at them (see `fontVariationSettings`).
  // What is still a hard error is a *malformed* pair of arrays: tags and values are positional, so
  // unequal lengths mean the document cannot say which value belongs to which axis, and rendering
  // it would apply a silently wrong instance rather than a missing one.
  //
  // Axes on a family the host doesn't supply as bytes (a generic, or an inline `FontData`) are
  // dropped at render rather than rejected here: the text still draws in the right family, one
  // instance off. That is a substitution the audit records, not a document this lane cannot read.
  if (!axes?.values.isNullOrEmpty() || !axisValues?.values.isNullOrEmpty()) {
    if (axes?.values?.size != axisValues?.values?.size)
      return "font axis arrays have different sizes"
  }
  val flags = int(23, 0)
  if (flags != 0) return "flags $flags are not implemented"
  return null
}

private fun fontFamilyIssue(
  fontFamilyId: Int,
  texts: Map<Int, String>,
  embeddedFontIds: Set<Int>,
  availableFontFamilies: Set<String>,
): String? {
  if (fontFamilyId == -1) return null
  val family = texts[fontFamilyId] ?: return "font family name id $fontFamilyId is not declared"
  val normalized = family.lowercase().removePrefix("google:")
  val available = availableFontFamilies.mapTo(mutableSetOf()) { it.lowercase() }
  if (
    normalized !in setOf("default", "sans-serif", "serif", "monospace") &&
      normalized !in available &&
      fontFamilyId !in embeddedFontIds
  ) {
    return "custom font family $family ($fontFamilyId) has no DataFont"
  }
  return null
}

private fun hasInvalidDrawContent(
  nodes: List<RcLinkedNode>,
  hasLayoutComponent: Boolean = false,
  drawContentAvailable: Boolean = false,
): Boolean = nodes.any { node ->
  when (node) {
    is RcLinkedNode.Operation ->
      node.operation is RcNoArg &&
        node.operation.opcode == RcOpcodes.DRAW_CONTENT &&
        !drawContentAvailable
    is RcLinkedNode.Container -> {
      val component =
        hasLayoutComponent ||
          node.operation.opcode in
            setOf(
              RcOpcodes.LAYOUT_ROOT,
              RcOpcodes.LAYOUT_BOX,
              RcOpcodes.LAYOUT_ROW,
              RcOpcodes.LAYOUT_COLUMN,
              RcOpcodes.LAYOUT_FLOW,
              RcOpcodes.LAYOUT_COLLAPSIBLE_ROW,
              RcOpcodes.LAYOUT_COLLAPSIBLE_COLUMN,
              RcOpcodes.LAYOUT_CANVAS,
              RcOpcodes.LAYOUT_FIT_BOX,
            )
      val available =
        if (node.operation.opcode == RcOpcodes.CANVAS_OPERATIONS) component
        else drawContentAvailable
      hasInvalidDrawContent(node.children, component, available)
    }
  }
}

private fun paintIssue(paint: RcPaintData): String? {
  var index = 0
  while (index < paint.words.size) {
    val command = paint.words[index++]
    val type = command and 0xffff
    if (type == PAINT_FONT_AXIS && command ushr 16 !in 0..8) {
      return "font axis count ${command ushr 16} is invalid"
    }
    val gradientWords =
      if (type == PAINT_GRADIENT) {
        if (command ushr 16 !in 0..2) return "gradient type ${command ushr 16} is not implemented"
        if (index >= paint.words.size) return "paint command $type is truncated"
        val colorCount = paint.words[index] and 0xff
        if (colorCount !in 1..16) return "gradient color count $colorCount is invalid"
        val stopCountIndex = index + 1 + colorCount
        if (stopCountIndex >= paint.words.size) return "paint command $type is truncated"
        val stopCount = paint.words[stopCountIndex]
        if (stopCount != 0 && stopCount != colorCount) {
          return "gradient stop count $stopCount does not match $colorCount colors"
        }
        1 +
          colorCount +
          1 +
          stopCount +
          when (command ushr 16) {
            0 -> 5
            1 -> 4
            else -> 2
          }
      } else {
        null
      }
    val argumentWords =
      gradientWords
        ?: when (type) {
          PAINT_TEXT_SIZE,
          PAINT_COLOR,
          PAINT_STROKE_WIDTH,
          PAINT_ALPHA,
          PAINT_COLOR_ID,
          PAINT_TYPEFACE,
          PAINT_SHADER,
          PAINT_COLOR_FILTER,
          PAINT_COLOR_FILTER_ID -> 1
          PAINT_STROKE_CAP,
          PAINT_STYLE,
          PAINT_STROKE_JOIN,
          PAINT_BLEND_MODE,
          PAINT_CLEAR_COLOR_FILTER -> 0
          PAINT_FONT_AXIS -> (command ushr 16) * 2
          else -> return "paint command $type is not implemented"
        }
    if (index + argumentWords > paint.words.size) return "paint command $type is truncated"
    if (type == PAINT_STYLE && command ushr 16 !in 0..1) {
      return "paint style ${command ushr 16} is not implemented"
    }
    if (type == PAINT_BLEND_MODE && command ushr 16 !in 0..28) {
      return "blend mode ${command ushr 16} is not implemented"
    }
    if (type in setOf(PAINT_COLOR_FILTER, PAINT_COLOR_FILTER_ID) && command ushr 16 !in 0..28) {
      return "color filter mode ${command ushr 16} is not implemented"
    }
    if (type == PAINT_TYPEFACE && paint.words[index] !in 0..3) {
      return "font id ${paint.words[index]} is not implemented"
    }
    if (type == PAINT_SHADER && paint.words[index] != 0) {
      return "shader id ${paint.words[index]} is not implemented"
    }
    if (type == PAINT_FONT_AXIS) {
      for (axisIndex in 0 until (command ushr 16)) {
        val tag = paint.words[index + axisIndex * 2]
        if (tag !in SUPPORTED_FONT_AXES) return "font axis ${fontAxisName(tag)} is not implemented"
      }
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
private const val PAINT_SHADER = 9
private const val PAINT_GRADIENT = 11
private const val PAINT_ALPHA = 12
private const val PAINT_COLOR_FILTER = 13
private const val PAINT_STROKE_JOIN = 15
private const val PAINT_BLEND_MODE = 18
private const val PAINT_COLOR_ID = 19
private const val PAINT_COLOR_FILTER_ID = 20
private const val PAINT_TYPEFACE = 16
private const val PAINT_CLEAR_COLOR_FILTER = 21
private const val PAINT_FONT_AXIS = 23

private const val FONT_AXIS_WEIGHT = 0x77676874 // wght
private const val FONT_AXIS_ITALIC = 0x6974616c // ital
private const val FONT_AXIS_SLANT = 0x736c6e74 // slnt
private val SUPPORTED_FONT_AXES = setOf(FONT_AXIS_WEIGHT, FONT_AXIS_ITALIC, FONT_AXIS_SLANT)

private fun fontAxisName(tag: Int): String =
  buildString(4) {
    append((tag ushr 24).toChar())
    append((tag ushr 16 and 0xff).toChar())
    append((tag ushr 8 and 0xff).toChar())
    append((tag and 0xff).toChar())
  }
