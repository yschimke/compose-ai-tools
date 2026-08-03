package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcAlignByModifier
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDataMapLookup
import ee.schimke.composeai.rcplayer.protocol.RcDimensionConstraintsModifier
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcDraw3
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcDraw6
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmap
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmapInt
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmapScaled
import ee.schimke.composeai.rcplayer.protocol.RcDrawText
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextAnchored
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextOnPath
import ee.schimke.composeai.rcplayer.protocol.RcDrawTweenPath
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionCall
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerAttribute
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeightInModifier
import ee.schimke.composeai.rcplayer.protocol.RcIdLookup
import ee.schimke.composeai.rcplayer.protocol.RcIdOperation
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute
import ee.schimke.composeai.rcplayer.protocol.RcMatrixExpression
import ee.schimke.composeai.rcplayer.protocol.RcMatrixFromPath
import ee.schimke.composeai.rcplayer.protocol.RcMatrixVectorMath
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOffsetModifier
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcPathAppend
import ee.schimke.composeai.rcplayer.protocol.RcPathCombine
import ee.schimke.composeai.rcplayer.protocol.RcPathCommands
import ee.schimke.composeai.rcplayer.protocol.RcPathCreate
import ee.schimke.composeai.rcplayer.protocol.RcPathData
import ee.schimke.composeai.rcplayer.protocol.RcPathExpression
import ee.schimke.composeai.rcplayer.protocol.RcPathTween
import ee.schimke.composeai.rcplayer.protocol.RcRootContentBehavior
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTextFromFloat
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextLength
import ee.schimke.composeai.rcplayer.protocol.RcTextLookup
import ee.schimke.composeai.rcplayer.protocol.RcTextLookupInt
import ee.schimke.composeai.rcplayer.protocol.RcTextMeasure
import ee.schimke.composeai.rcplayer.protocol.RcTextMerge
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTextSubtext
import ee.schimke.composeai.rcplayer.protocol.RcTextTransform
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.protocol.RcTransform2
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcWidthInModifier
import ee.schimke.composeai.rcplayer.protocol.RcZIndexModifier
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import ee.schimke.composeai.rcplayer.runtime.RcLayoutModifiers
import ee.schimke.composeai.rcplayer.runtime.RcLayoutNode
import ee.schimke.composeai.rcplayer.runtime.RcLayoutTree
import ee.schimke.composeai.rcplayer.runtime.RcLinkedNode
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

@Composable
public fun RcComposePlayer(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  theme: Int = RcTheme.UNSPECIFIED,
  namedValues: Map<String, RcNamedValue> = emptyMap(),
  onEvent: (RcPlayerEvent) -> Unit = {},
) {
  val document = remember(bytes) { RcDocumentCodec.decode(bytes) }
  RcComposePlayer(document, modifier, theme, namedValues, onEvent)
}

@Composable
public fun RcComposePlayer(
  document: RcDocument,
  modifier: Modifier = Modifier,
  theme: Int = RcTheme.UNSPECIFIED,
  namedValues: Map<String, RcNamedValue> = emptyMap(),
  onEvent: (RcPlayerEvent) -> Unit = {},
) {
  val latestEventSink by rememberUpdatedState(onEvent)
  var invalidationVersion by remember { mutableIntStateOf(0) }
  val state =
    remember(document, namedValues) {
      RcPlayerState(
        document,
        namedValues,
        eventSink = { latestEventSink(it) },
        onInvalidated = { invalidationVersion += 1 },
      )
    }
  invalidationVersion // Subscribe composition to local action mutations.
  val hasAnimatedFloats =
    remember(document) {
      document.operations.filterIsInstance<RcFloatExpression>().any { it.animation != null }
    }
  var frameNanos by remember { mutableLongStateOf(0L) }
  LaunchedEffect(hasAnimatedFloats) {
    if (hasAnimatedFloats) {
      val start = withFrameNanos { it }
      while (true) {
        withFrameNanos { frameNanos = it - start }
      }
    }
  }
  val linkedDocument = remember(document) { RcDocumentLinker.link(document) }
  val layout = remember(linkedDocument) { RcLayoutTree.build(linkedDocument) }
  val images = remember(document) { decodeInlineImages(document) }
  val textMeasurer = rememberTextMeasurer()
  val semanticsModifier =
    state.rootContentDescription?.let { description ->
      modifier.semantics { contentDescription = description }
    } ?: modifier
  if (layout != null) {
    SideEffect { state.beginFrame(frameNanos / 1_000_000_000f) }
    RenderLayoutNode(
      node = layout,
      modifier = semanticsModifier,
      state = state,
      textMeasurer = textMeasurer,
      images = images,
      theme = theme,
    )
  } else
    Canvas(semanticsModifier) {
      val width = document.header.width.coerceAtLeast(1)
      val height = document.header.height.coerceAtLeast(1)
      val rootTransform =
        computeRootTransform(
          documentWidth = width.toFloat(),
          documentHeight = height.toFloat(),
          viewportWidth = size.width,
          viewportHeight = size.height,
          behavior = state.rootContentBehavior,
        )
      withTransform({
        translate(rootTransform.translateX, rootTransform.translateY)
        scale(rootTransform.scaleX, rootTransform.scaleY, Offset.Zero)
      }) {
        state.beginFrame(frameNanos / 1_000_000_000f)
        drawOperations(
          linkedDocument.operations,
          state,
          RcPaintState(),
          mutableMapOf(),
          textMeasurer,
          images,
          RcFloatFunctionRuntime(),
          theme,
          filterTheme = true,
        )
      }
    }
}

@Composable
private fun RenderLayoutNode(
  node: RcLayoutNode,
  modifier: Modifier = Modifier,
  state: RcPlayerState,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  theme: Int,
) {
  val visibility =
    node.modifiers.visibility?.let { androidXVisibility(state.integer(it.visibilityId) ?: 0) } ?: 1
  if (visibility == 0) return
  val effectiveModifier = if (visibility == 2) modifier.alpha(0f) else modifier
  when (node) {
    is RcLayoutNode.Root ->
      Box(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = true,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        )
      ) {
        node.children.forEach {
          RenderLayoutNode(
            it,
            state = state,
            textMeasurer = textMeasurer,
            images = images,
            theme = theme,
          )
        }
      }
    is RcLayoutNode.Content -> {
      val content: @Composable () -> Unit = {
        node.children.forEach {
          RenderLayoutNode(
            it,
            state = state,
            textMeasurer = textMeasurer,
            images = images,
            theme = theme,
          )
        }
      }
      if (visibility == 2) Box(effectiveModifier) { content() } else content()
    }
    is RcLayoutNode.Canvas ->
      Box(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = true,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        )
      ) {
        node.content?.let {
          RenderLayoutNode(
            it,
            state = state,
            textMeasurer = textMeasurer,
            images = images,
            theme = theme,
          )
        }
      }
    is RcLayoutNode.CanvasContent ->
      Canvas(Modifier.fillMaxSize()) {
        drawOperations(
          node.operations,
          state,
          RcPaintState(),
          mutableMapOf(),
          textMeasurer,
          images,
          RcFloatFunctionRuntime(),
          theme,
          filterTheme = true,
        )
      }
    is RcLayoutNode.Box ->
      Box(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = false,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        ),
        contentAlignment =
          boxAlignment(node.operation.horizontalPositioning, node.operation.verticalPositioning),
      ) {
        RenderLayoutNode(
          node.content,
          state = state,
          textMeasurer = textMeasurer,
          images = images,
          theme = theme,
        )
      }
    is RcLayoutNode.Row -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val spacing = with(density) { state.resolve(node.operation.spacedBy).dp.roundToPx() }
      val rowModifier =
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = false,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        )
      if (node.content.children.any { it.modifiers.alignBy != null }) {
        RcAlignedRow(
          children = node.content.children,
          horizontalPositioning = node.operation.horizontalPositioning,
          verticalPositioning = node.operation.verticalPositioning,
          spacing = spacing,
          modifier = rowModifier,
          state = state,
          textMeasurer = textMeasurer,
          images = images,
          theme = theme,
        )
      } else {
        Row(
          rowModifier,
          horizontalArrangement =
            RcHorizontalArrangement(node.operation.horizontalPositioning, spacing),
          verticalAlignment = rowAlignment(node.operation.verticalPositioning),
        ) {
          RenderLayoutNode(
            node.content,
            state = state,
            textMeasurer = textMeasurer,
            images = images,
            theme = theme,
          )
        }
      }
    }
    is RcLayoutNode.Column -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val spacing = with(density) { state.resolve(node.operation.spacedBy).dp.roundToPx() }
      Column(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = false,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        ),
        verticalArrangement = RcVerticalArrangement(node.operation.verticalPositioning, spacing),
        horizontalAlignment = columnAlignment(node.operation.horizontalPositioning),
      ) {
        RenderLayoutNode(
          node.content,
          state = state,
          textMeasurer = textMeasurer,
          images = images,
          theme = theme,
        )
      }
    }
    is RcLayoutNode.Flow -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val spacing = with(density) { state.resolve(node.operation.spacedBy).dp.roundToPx() }
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = false,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        ),
        horizontalArrangement =
          RcHorizontalArrangement(node.operation.horizontalPositioning, spacing),
        verticalArrangement = RcVerticalArrangement(node.operation.verticalPositioning, 0),
        itemVerticalAlignment = rowAlignment(node.operation.verticalPositioning),
        maxItemsInEachRow = node.operation.maxItemsInEachRow,
        maxLines = node.operation.maxLines,
      ) {
        RenderLayoutNode(
          node.content,
          state = state,
          textMeasurer = textMeasurer,
          images = images,
          theme = theme,
        )
      }
    }
    is RcLayoutNode.CollapsibleRow -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      RcCollapsibleLayout(
        children = node.content.children,
        orientation = RcCollapseOrientation.Horizontal,
        mainPositioning = node.operation.horizontalPositioning,
        crossPositioning = node.operation.verticalPositioning,
        spacing = with(density) { state.resolve(node.operation.spacedBy).dp.roundToPx() },
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            fillMissingDimensions = false,
            node.canvasOperations,
            textMeasurer,
            images,
            theme,
          ),
        state = state,
        textMeasurer = textMeasurer,
        images = images,
        theme = theme,
      )
    }
    is RcLayoutNode.CollapsibleColumn -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      RcCollapsibleLayout(
        children = node.content.children,
        orientation = RcCollapseOrientation.Vertical,
        mainPositioning = node.operation.verticalPositioning,
        crossPositioning = node.operation.horizontalPositioning,
        spacing = with(density) { state.resolve(node.operation.spacedBy).dp.roundToPx() },
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            fillMissingDimensions = false,
            node.canvasOperations,
            textMeasurer,
            images,
            theme,
          ),
        state = state,
        textMeasurer = textMeasurer,
        images = images,
        theme = theme,
      )
    }
    is RcLayoutNode.Image -> {
      val image = images[node.operation.bitmapId]
      val density = androidx.compose.ui.platform.LocalDensity.current
      var imageModifier =
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          fillMissingDimensions = false,
          canvasOperations = null,
          textMeasurer,
          images,
          theme,
        )
      if (image != null && node.modifiers.width == null) {
        imageModifier = imageModifier.width(with(density) { image.width.toDp() })
      }
      if (image != null && node.modifiers.height == null) {
        imageModifier = imageModifier.height(with(density) { image.height.toDp() })
      }
      Canvas(imageModifier) {
        if (image == null) return@Canvas
        val scaled =
          computeImageScaling(
            0f,
            0f,
            image.width.toFloat(),
            image.height.toFloat(),
            0f,
            0f,
            size.width,
            size.height,
            node.operation.scaleType,
            1f,
          ) ?: return@Canvas
        clipRect(0f, 0f, size.width, size.height) {
          drawImage(
            image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(scaled.left.toInt(), scaled.top.toInt()),
            dstSize =
              IntSize((scaled.right - scaled.left).toInt(), (scaled.bottom - scaled.top).toInt()),
            alpha = state.resolve(node.operation.alpha),
          )
        }
      }
    }
    is RcLayoutNode.Text -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val operation = node.operation
      val fontWeight = state.resolve(operation.fontWeight).roundToInt().coerceIn(1, 1000)
      val boldWeight = if (operation.fontStyle and 1 != 0) 700 else fontWeight
      BasicText(
        text = state.text(operation.textId).orEmpty(),
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            fillMissingDimensions = false,
            canvasOperations = null,
            textMeasurer,
            images,
            theme,
          ),
        style =
          TextStyle(
            color =
              Color(
                if (operation.flags and RcTextLayout.FLAG_DYNAMIC_COLOR != 0)
                  state.color(operation.color)
                else operation.color
              ),
            fontSize = (state.resolve(operation.fontSize) / density.density).sp,
            fontWeight = FontWeight(boldWeight),
            fontStyle = if (operation.fontStyle and 2 != 0) FontStyle.Italic else FontStyle.Normal,
            fontFamily = FontFamily.Default,
            textAlign = operation.composeTextAlign(),
          ),
        overflow = operation.composeTextOverflow(),
        maxLines = operation.maxLines,
      )
    }
    is RcLayoutNode.CoreText -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val properties = node.resolvedStyle
      val fontSize = state.resolve(properties.floatProperty(5, 36f))
      val fontStyle = properties.intProperty(6, 0)
      val fontWeight =
        state.resolve(properties.floatProperty(7, 400f)).roundToInt().coerceIn(1, 1000)
      val boldWeight = if (fontStyle and 1 != 0) 700 else fontWeight
      val colorId = properties.intProperty(4, -1)
      BasicText(
        text = state.text(node.operation.textId).orEmpty(),
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            fillMissingDimensions = false,
            canvasOperations = null,
            textMeasurer,
            images,
            theme,
          ),
        style =
          TextStyle(
            color =
              Color(
                if (colorId == -1) properties.intProperty(3, 0xff000000.toInt())
                else state.color(colorId)
              ),
            fontSize = (fontSize / density.density).sp,
            fontWeight = FontWeight(boldWeight),
            fontStyle = if (fontStyle and 2 != 0) FontStyle.Italic else FontStyle.Normal,
            fontFamily = FontFamily.Default,
            textAlign = androidXTextAlign(properties.intProperty(9, RcTextLayout.ALIGN_LEFT)),
          ),
        overflow = androidXTextOverflow(properties.intProperty(10, RcTextLayout.OVERFLOW_CLIP)),
        maxLines = properties.intProperty(11, Int.MAX_VALUE),
      )
    }
    is RcLayoutNode.FitBox -> {
      val alignment =
        boxAlignment(node.operation.horizontalPositioning, node.operation.verticalPositioning)
      Layout(
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            fillMissingDimensions = false,
            node.canvasOperations,
            textMeasurer,
            images,
            theme,
          ),
        content = {
          RenderLayoutNode(
            node.content,
            state = state,
            textMeasurer = textMeasurer,
            images = images,
            theme = theme,
          )
        },
      ) { measurables, constraints ->
        val availableWidth = constraints.maxWidth
        val availableHeight = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val selected = measurables.firstNotNullOfOrNull { measurable ->
          val intrinsicWidth = measurable.minIntrinsicWidth(availableHeight)
          val intrinsicHeight = measurable.minIntrinsicHeight(availableWidth)
          if (intrinsicWidth > availableWidth || intrinsicHeight > availableHeight)
            return@firstNotNullOfOrNull null
          measurable.measure(loose).takeIf {
            it.width <= availableWidth && it.height <= availableHeight
          }
        }
        val width =
          (selected?.width ?: constraints.minWidth).coerceIn(
            constraints.minWidth,
            constraints.maxWidth,
          )
        val height =
          (selected?.height ?: constraints.minHeight).coerceIn(
            constraints.minHeight,
            constraints.maxHeight,
          )
        layout(width, height) {
          selected?.let { placeable ->
            val offset =
              alignment.align(
                IntSize(placeable.width, placeable.height),
                IntSize(width, height),
                layoutDirection,
              )
            placeable.place(offset.x, offset.y)
          }
        }
      }
    }
  }
}

@Composable
private fun RcAlignedRow(
  children: List<RcLayoutNode>,
  horizontalPositioning: Int,
  verticalPositioning: Int,
  spacing: Int,
  modifier: Modifier,
  state: RcPlayerState,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  theme: Int,
) {
  Layout(
    content = {
      children.forEach { child -> RcLayoutChild(child, state, textMeasurer, images, theme) }
    },
    modifier = modifier,
  ) { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val placeables = measurables.map { it.measure(loose) }
    val widths = placeables.map { it.width }.toIntArray()
    val naturalWidth = widths.sum() + spacing * (widths.size - 1).coerceAtLeast(0)
    val width = constraints.constrainWidth(naturalWidth)
    val maximumChildHeight = placeables.maxOfOrNull { it.height } ?: 0
    val height = constraints.constrainHeight(maximumChildHeight)
    val xPositions =
      arrangeLinear(
        width,
        widths,
        horizontalPositioning,
        spacing,
        reverse = layoutDirection == LayoutDirection.Rtl,
      )
    val anchors = children.mapIndexed { index, child ->
      resolveAlignByAnchor(child.modifiers.alignBy, placeables[index], state)
    }
    val yPositions = alignByCrossPositions(height, maximumChildHeight, verticalPositioning, anchors)
    layout(width, height) {
      placeables.forEachIndexed { index, placeable ->
        placeable.place(xPositions[index], yPositions[index])
      }
    }
  }
}

@Composable
private fun RcLayoutChild(
  child: RcLayoutNode,
  state: RcPlayerState,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  theme: Int,
) {
  Layout(
    content = {
      RenderLayoutNode(
        child,
        state = state,
        textMeasurer = textMeasurer,
        images = images,
        theme = theme,
      )
    }
  ) { measurables, constraints ->
    val placeable =
      measurables.singleOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val alignmentLines =
      buildMap<AlignmentLine, Int> {
        placeable
          ?.get(FirstBaseline)
          ?.takeUnless { it == AlignmentLine.Unspecified }
          ?.let { put(FirstBaseline, it) }
        placeable
          ?.get(LastBaseline)
          ?.takeUnless { it == AlignmentLine.Unspecified }
          ?.let { put(LastBaseline, it) }
      }
    layout(placeable?.width ?: 0, placeable?.height ?: 0, alignmentLines = alignmentLines) {
      placeable?.place(0, 0)
    }
  }
}

private fun resolveAlignByAnchor(
  modifier: RcAlignByModifier?,
  placeable: androidx.compose.ui.layout.Placeable,
  state: RcPlayerState,
): Float =
  when (modifier?.line?.referencedId) {
    null -> modifier?.line?.value ?: 0f
    RcAlignByModifier.FIRST_BASELINE_ID ->
      placeable[FirstBaseline].takeUnless { it == AlignmentLine.Unspecified }?.toFloat() ?: 0f
    RcAlignByModifier.LAST_BASELINE_ID ->
      placeable[LastBaseline].takeUnless { it == AlignmentLine.Unspecified }?.toFloat() ?: 0f
    else -> state.resolve(modifier.line)
  }

/** AndroidX RowLayout aligns all children, including unanchored ones, to the maximum anchor. */
internal fun alignByCrossPositions(
  totalSize: Int,
  maximumChildSize: Int,
  verticalPositioning: Int,
  anchors: List<Float>,
): IntArray {
  val maximumAnchor = anchors.maxOrNull() ?: 0f
  val base =
    when (verticalPositioning) {
      4 -> 0f
      2 -> (totalSize - maximumChildSize) / 2f
      5 -> (totalSize - maximumChildSize).toFloat()
      else -> error("Unknown AndroidX row vertical position $verticalPositioning")
    }
  return IntArray(anchors.size) { index -> (base + maximumAnchor - anchors[index]).roundToInt() }
}

private enum class RcCollapseOrientation {
  Horizontal,
  Vertical,
}

@Composable
private fun RcCollapsibleLayout(
  children: List<RcLayoutNode>,
  orientation: RcCollapseOrientation,
  mainPositioning: Int,
  crossPositioning: Int,
  spacing: Int,
  modifier: Modifier,
  state: RcPlayerState,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  theme: Int,
) {
  Layout(
    content = {
      children.forEach { child ->
        // Keep one measurable per wire child even when its visibility modifier resolves to gone.
        RcLayoutChild(child, state, textMeasurer, images, theme)
      }
    },
    modifier = modifier,
  ) { measurables, constraints ->
    val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
    val placeables = measurables.map { it.measure(childConstraints) }
    val mainSizes = placeables.map {
      if (orientation == RcCollapseOrientation.Horizontal) it.width else it.height
    }
    val priorities = children.map { child ->
      val priority = child.modifiers.collapsiblePriority
      val expectedOrientation =
        if (orientation == RcCollapseOrientation.Horizontal) {
          RcCollapsiblePriorityModifier.HORIZONTAL
        } else {
          RcCollapsiblePriorityModifier.VERTICAL
        }
      if (priority?.orientation == expectedOrientation) state.resolve(priority.priority)
      else Float.MAX_VALUE
    }
    val maximumMain =
      if (orientation == RcCollapseOrientation.Horizontal) constraints.maxWidth
      else constraints.maxHeight
    val retained = selectCollapsibleChildren(mainSizes, priorities, maximumMain)
    val retainedIndices = retained.indices.filter { retained[it] }
    val retainedMainSizes = retainedIndices.map { mainSizes[it] }.toIntArray()
    val retainedCrossSize =
      retainedIndices.maxOfOrNull {
        if (orientation == RcCollapseOrientation.Horizontal) placeables[it].height
        else placeables[it].width
      } ?: 0
    val naturalMain =
      retainedMainSizes.sum() + spacing * (retainedMainSizes.size - 1).coerceAtLeast(0)
    val width =
      constraints.constrainWidth(
        if (orientation == RcCollapseOrientation.Horizontal) naturalMain else retainedCrossSize
      )
    val height =
      constraints.constrainHeight(
        if (orientation == RcCollapseOrientation.Horizontal) retainedCrossSize else naturalMain
      )
    val mainAvailable = if (orientation == RcCollapseOrientation.Horizontal) width else height
    val mainPositions =
      arrangeLinear(
        mainAvailable,
        retainedMainSizes,
        mainPositioning,
        spacing,
        reverse =
          orientation == RcCollapseOrientation.Horizontal && layoutDirection == LayoutDirection.Rtl,
      )
    val alignedCrossPositions =
      if (
        orientation == RcCollapseOrientation.Horizontal &&
          retainedIndices.any { children[it].modifiers.alignBy != null }
      ) {
        val retainedAnchors = retainedIndices.map { index ->
          resolveAlignByAnchor(children[index].modifiers.alignBy, placeables[index], state)
        }
        alignByCrossPositions(height, retainedCrossSize, crossPositioning, retainedAnchors)
      } else {
        null
      }
    layout(width, height) {
      retainedIndices.forEachIndexed { retainedIndex, childIndex ->
        val placeable = placeables[childIndex]
        val crossAvailable = if (orientation == RcCollapseOrientation.Horizontal) height else width
        val crossSize =
          if (orientation == RcCollapseOrientation.Horizontal) placeable.height else placeable.width
        val crossPosition =
          alignedCrossPositions?.get(retainedIndex)
            ?: arrangeLinear(
              crossAvailable,
              intArrayOf(crossSize),
              crossPositioning,
              spacing = 0,
              reverse =
                orientation == RcCollapseOrientation.Vertical &&
                  layoutDirection == LayoutDirection.Rtl,
            )[0]
        if (orientation == RcCollapseOrientation.Horizontal) {
          placeable.place(mainPositions[retainedIndex], crossPosition)
        } else {
          placeable.place(crossPosition, mainPositions[retainedIndex])
        }
      }
    }
  }
}

/** AndroidX alpha16 priority sort and first-overflow cutoff; spacing is deliberately excluded. */
internal fun selectCollapsibleChildren(
  mainSizes: List<Int>,
  priorities: List<Float>,
  maximumMain: Int,
): BooleanArray {
  require(mainSizes.size == priorities.size)
  val retained = BooleanArray(mainSizes.size)
  val ranked =
    mainSizes.indices.sortedWith { left, right -> (priorities[right] - priorities[left]).toInt() }
  var used = 0
  for (index in ranked) {
    if (used + mainSizes[index] > maximumMain) break
    retained[index] = true
    used += mainSizes[index]
  }
  return retained
}

private fun RcTextLayout.composeTextAlign(): TextAlign = androidXTextAlign(textAlign)

private fun androidXTextAlign(value: Int): TextAlign =
  when (value) {
    RcTextLayout.ALIGN_LEFT -> TextAlign.Left
    RcTextLayout.ALIGN_RIGHT -> TextAlign.Right
    RcTextLayout.ALIGN_CENTER -> TextAlign.Center
    RcTextLayout.ALIGN_JUSTIFY -> TextAlign.Justify
    RcTextLayout.ALIGN_START -> TextAlign.Start
    RcTextLayout.ALIGN_END -> TextAlign.End
    else -> error("Unknown AndroidX text alignment $value")
  }

private fun RcTextLayout.composeTextOverflow(): TextOverflow = androidXTextOverflow(overflow)

private fun androidXTextOverflow(value: Int): TextOverflow =
  when (value) {
    RcTextLayout.OVERFLOW_CLIP -> TextOverflow.Clip
    RcTextLayout.OVERFLOW_VISIBLE -> TextOverflow.Visible
    RcTextLayout.OVERFLOW_ELLIPSIS -> TextOverflow.Ellipsis
    else -> error("Unsupported AndroidX text overflow $value")
  }

private fun List<RcTextStyleProperty>.intProperty(id: Int, default: Int): Int =
  filterIsInstance<RcTextStyleProperty.IntValue>().lastOrNull { it.id == id }?.value ?: default

private fun List<RcTextStyleProperty>.floatProperty(id: Int, default: Float): RcFloatWord =
  filterIsInstance<RcTextStyleProperty.FloatValue>().lastOrNull { it.id == id }?.value
    ?: RcFloatWord.literal(default)

/** Mirrors Component.Visibility, including the override-bit precedence used by AndroidX. */
internal fun androidXVisibility(value: Int): Int =
  when {
    value and 32 == 32 -> 1
    value and 16 == 16 -> 0
    value and 64 == 64 -> 2
    value == 1 -> 1
    value == 2 -> 2
    else -> 0
  }

internal fun boxAlignment(horizontal: Int, vertical: Int): Alignment =
  when (horizontal to vertical) {
    1 to 4 -> Alignment.TopStart
    2 to 4 -> Alignment.TopCenter
    3 to 4 -> Alignment.TopEnd
    1 to 2 -> Alignment.CenterStart
    2 to 2 -> Alignment.Center
    3 to 2 -> Alignment.CenterEnd
    1 to 5 -> Alignment.BottomStart
    2 to 5 -> Alignment.BottomCenter
    3 to 5 -> Alignment.BottomEnd
    else -> error("Unknown AndroidX box alignment horizontal=$horizontal vertical=$vertical")
  }

internal fun rowAlignment(vertical: Int): Alignment.Vertical =
  when (vertical) {
    4 -> Alignment.Top
    2 -> Alignment.CenterVertically
    5 -> Alignment.Bottom
    else -> error("Unknown AndroidX row vertical position $vertical")
  }

internal fun columnAlignment(horizontal: Int): Alignment.Horizontal =
  when (horizontal) {
    1 -> Alignment.Start
    2 -> Alignment.CenterHorizontally
    3 -> Alignment.End
    else -> error("Unknown AndroidX column horizontal position $horizontal")
  }

private class RcHorizontalArrangement(private val positioning: Int, private val spacingPx: Int) :
  Arrangement.Horizontal {
  override fun Density.arrange(
    totalSize: Int,
    sizes: IntArray,
    layoutDirection: LayoutDirection,
    outPositions: IntArray,
  ) {
    arrangeLinear(
      totalSize,
      sizes,
      positioning,
      spacingPx,
      reverse = layoutDirection == LayoutDirection.Rtl,
      outPositions = outPositions,
    )
  }
}

private class RcVerticalArrangement(private val positioning: Int, private val spacingPx: Int) :
  Arrangement.Vertical {
  override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
    arrangeLinear(
      totalSize,
      sizes,
      positioning,
      spacingPx,
      reverse = false,
      outPositions = outPositions,
    )
  }
}

/** AndroidX RowLayout/ColumnLayout positioning, including its additive spacedBy behaviour. */
internal fun arrangeLinear(
  totalSize: Int,
  sizes: IntArray,
  positioning: Int,
  spacing: Int,
  reverse: Boolean,
  outPositions: IntArray = IntArray(sizes.size),
): IntArray {
  require(outPositions.size >= sizes.size)
  if (sizes.isEmpty()) return outPositions
  val childSize = sizes.sum().toFloat()
  val contentSize = childSize + spacing * (sizes.size - 1)
  var distributedGap = 0f
  var current =
    when (positioning) {
      1,
      4 -> 0f
      2 -> (totalSize - contentSize) / 2f
      3,
      5 -> totalSize - contentSize
      6 -> {
        if (sizes.size > 1) distributedGap = (totalSize - childSize) / (sizes.size - 1)
        if (sizes.size == 1) (totalSize - contentSize) / 2f else 0f
      }
      7 -> {
        distributedGap = (totalSize - childSize) / (sizes.size + 1)
        distributedGap
      }
      8 -> {
        distributedGap = (totalSize - childSize) / sizes.size
        distributedGap / 2f
      }
      else -> error("Unknown AndroidX linear position $positioning")
    }
  sizes.forEachIndexed { index, size ->
    val position = current.roundToInt()
    outPositions[index] = if (reverse) totalSize - position - size else position
    current += size + spacing
    if (positioning in 6..8) current += distributedGap
  }
  return outPositions
}

@Composable
private fun Modifier.applyComponentModifiers(
  modifiers: RcLayoutModifiers,
  state: RcPlayerState,
  fillMissingDimensions: Boolean,
  canvasOperations: List<RcLinkedNode>?,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  theme: Int,
): Modifier {
  val density = androidx.compose.ui.platform.LocalDensity.current
  var result =
    if (modifiers.layoutComputes.isEmpty()) this
    else {
      val computeBase =
        if (modifiers.layoutComputes.any { it.operation.type == RcLayoutCompute.MEASURE }) {
          this.clipToBounds()
        } else {
          this
        }
      computeBase.applyLayoutComputes(modifiers, state)
    }
  modifiers.dimensionConstraints.forEach { constraint ->
    result = result.applyDimensionConstraint(constraint, state, density)
  }
  result = result.applyWidth(modifiers, state, density, fillMissingDimensions)
  result = result.applyHeight(modifiers, state, density, fillMissingDimensions)
  modifiers.graphicsLayer?.let { result = result.applyGraphicsLayer(it, state) }
  modifiers.placementModifiers.forEach { placement ->
    result =
      when (placement) {
        is RcOffsetModifier ->
          result.offset {
            IntOffset(
              state.resolve(placement.x).roundToInt(),
              state.resolve(placement.y).roundToInt(),
            )
          }
        is RcZIndexModifier -> result.zIndex(state.resolve(placement.value))
        else -> result
      }
  }
  modifiers.paintDecorators.forEach { decorator ->
    result = result.applyPaintDecorator(decorator, state)
  }
  if (canvasOperations != null) {
    result = result.drawWithContent {
      drawOperations(
        canvasOperations,
        state,
        RcPaintState(),
        mutableMapOf(),
        textMeasurer,
        images,
        RcFloatFunctionRuntime(),
        theme,
        filterTheme = true,
        drawContent = { drawContent() },
      )
    }
  }
  modifiers.padding.forEach { padding ->
    result =
      result.padding(
        start = with(density) { state.resolve(padding.left).toDp() },
        top = with(density) { state.resolve(padding.top).toDp() },
        end = with(density) { state.resolve(padding.right).toDp() },
        bottom = with(density) { state.resolve(padding.bottom).toDp() },
      )
  }
  if (modifiers.clicks.isNotEmpty()) {
    result = result.clickable { modifiers.clicks.forEach(state::executeClick) }
  }
  modifiers.accessibility.forEach { semantics ->
    result =
      result.applyAccessibilitySemantics(
        semantics,
        state,
        hasClickAction = modifiers.clicks.isNotEmpty(),
      )
  }
  return result
}

private fun Modifier.applyAccessibilitySemantics(
  operation: RcAccessibilitySemantics,
  state: RcPlayerState,
  hasClickAction: Boolean,
): Modifier {
  val properties: SemanticsPropertyReceiver.() -> Unit = {
    operation.contentDescriptionId
      .takeUnless { it == 0 }
      ?.let { id -> state.text(id)?.let { contentDescription = it } }
    operation.textId
      .takeUnless { it == 0 }
      ?.let { id -> state.text(id)?.let { text = AnnotatedString(it) } }
    operation.stateDescriptionId
      .takeUnless { it == 0 }
      ?.let { id -> state.text(id)?.let { stateDescription = it } }
    androidXSemanticsRole(operation.role)?.let { role = it }
    if (!operation.enabled) disabled()
    if (operation.clickable && !hasClickAction) onClick { false }
  }
  return when (operation.mode) {
    RcAccessibilitySemantics.MODE_SET -> semantics(properties = properties)
    RcAccessibilitySemantics.MODE_CLEAR_AND_SET -> clearAndSetSemantics(properties)
    RcAccessibilitySemantics.MODE_MERGE ->
      semantics(mergeDescendants = true, properties = properties)
    else -> this
  }
}

internal fun androidXSemanticsRole(role: Int): Role? =
  when (role) {
    RcAccessibilitySemantics.ROLE_BUTTON -> Role.Button
    RcAccessibilitySemantics.ROLE_CHECKBOX -> Role.Checkbox
    RcAccessibilitySemantics.ROLE_SWITCH -> Role.Switch
    RcAccessibilitySemantics.ROLE_RADIO_BUTTON -> Role.RadioButton
    RcAccessibilitySemantics.ROLE_TAB -> Role.Tab
    RcAccessibilitySemantics.ROLE_IMAGE -> Role.Image
    RcAccessibilitySemantics.ROLE_DROPDOWN_LIST -> Role.DropdownList
    RcAccessibilitySemantics.ROLE_PICKER -> Role.ValuePicker
    RcAccessibilitySemantics.ROLE_CAROUSEL -> Role.Carousel
    else -> null
  }

private fun Modifier.applyLayoutComputes(
  modifiers: RcLayoutModifiers,
  state: RcPlayerState,
): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  val parentWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
  val parentHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
  var width = placeable.width
  var height = placeable.height
  var x = 0
  var y = 0
  modifiers.layoutComputes.forEach { block ->
    val values =
      state.evaluateLayoutCompute(
        block,
        floatArrayOf(
          x.toFloat(),
          y.toFloat(),
          width.toFloat(),
          height.toFloat(),
          parentWidth.toFloat(),
          parentHeight.toFloat(),
        ),
      )
    when (block.operation.type) {
      RcLayoutCompute.MEASURE -> {
        width = constraints.constrainWidth(values[2].roundToInt().coerceAtLeast(0))
        height = constraints.constrainHeight(values[3].roundToInt().coerceAtLeast(0))
      }
      RcLayoutCompute.POSITION -> {
        x = values[0].roundToInt()
        y = values[1].roundToInt()
      }
    }
  }
  layout(width, height) { placeable.placeRelative(x, y) }
}

private fun Modifier.applyGraphicsLayer(
  operation: RcGraphicsLayerModifier,
  state: RcPlayerState,
): Modifier {
  val values = operation.attributes.associateBy { it.index }
  fun float(index: Int, default: Float): Float =
    (values[index] as? RcGraphicsLayerAttribute.FloatValue)?.let { state.resolve(it.value) }
      ?: default
  return graphicsLayer {
    scaleX = float(RcGraphicsLayerModifier.SCALE_X, 1f)
    scaleY = float(RcGraphicsLayerModifier.SCALE_Y, 1f)
    rotationX = float(RcGraphicsLayerModifier.ROTATION_X, 0f)
    rotationY = float(RcGraphicsLayerModifier.ROTATION_Y, 0f)
    rotationZ = float(RcGraphicsLayerModifier.ROTATION_Z, 0f)
    transformOrigin =
      TransformOrigin(
        float(RcGraphicsLayerModifier.TRANSFORM_ORIGIN_X, 0f),
        float(RcGraphicsLayerModifier.TRANSFORM_ORIGIN_Y, 0f),
      )
    translationX = float(RcGraphicsLayerModifier.TRANSLATION_X, 0f)
    translationY = float(RcGraphicsLayerModifier.TRANSLATION_Y, 0f)
    shadowElevation = float(RcGraphicsLayerModifier.SHADOW_ELEVATION, 0f)
    alpha = float(RcGraphicsLayerModifier.ALPHA, 1f)
    cameraDistance = float(RcGraphicsLayerModifier.CAMERA_DISTANCE, 8f)
  }
}

private fun Modifier.applyDimensionConstraint(
  operation: ee.schimke.composeai.rcplayer.protocol.RcOperation,
  state: RcPlayerState,
  density: Density,
): Modifier =
  when (operation) {
    is RcWidthInModifier ->
      applyWidthRange(state.resolve(operation.minimum), state.resolve(operation.maximum), density)
    is RcHeightInModifier ->
      applyHeightRange(state.resolve(operation.minimum), state.resolve(operation.maximum), density)
    is RcDimensionConstraintsModifier ->
      when (operation.type) {
        RcDimensionConstraintsModifier.HORIZONTAL ->
          applyWidthRange(
            state.resolve(operation.minimum),
            state.resolve(operation.maximum),
            density,
          )
        RcDimensionConstraintsModifier.VERTICAL ->
          applyHeightRange(
            state.resolve(operation.minimum),
            state.resolve(operation.maximum),
            density,
          )
        RcDimensionConstraintsModifier.REQUIRED_HORIZONTAL ->
          applyWidthRange(
            state.resolve(operation.minimum),
            state.resolve(operation.maximum),
            density,
            required = true,
          )
        RcDimensionConstraintsModifier.REQUIRED_VERTICAL ->
          applyHeightRange(
            state.resolve(operation.minimum),
            state.resolve(operation.maximum),
            density,
            required = true,
          )
        else -> this
      }
    else -> this
  }

private fun Modifier.applyWidthRange(
  minimum: Float,
  maximum: Float,
  density: Density,
  required: Boolean = false,
): Modifier {
  val min = with(density) { minimum.toDp() }
  val max = with(density) { maximum.toDp() }
  return when {
    minimum == -1f && maximum == -1f -> this
    required && minimum == -1f -> requiredWidthIn(max = max)
    required && maximum == -1f -> requiredWidthIn(min = min)
    required -> requiredWidthIn(min = min, max = max)
    minimum == -1f -> widthIn(max = max)
    maximum == -1f -> widthIn(min = min)
    else -> widthIn(min = min, max = max)
  }
}

private fun Modifier.applyHeightRange(
  minimum: Float,
  maximum: Float,
  density: Density,
  required: Boolean = false,
): Modifier {
  val min = with(density) { minimum.toDp() }
  val max = with(density) { maximum.toDp() }
  return when {
    minimum == -1f && maximum == -1f -> this
    required && minimum == -1f -> requiredHeightIn(max = max)
    required && maximum == -1f -> requiredHeightIn(min = min)
    required -> requiredHeightIn(min = min, max = max)
    minimum == -1f -> heightIn(max = max)
    maximum == -1f -> heightIn(min = min)
    else -> heightIn(min = min, max = max)
  }
}

private fun Modifier.applyPaintDecorator(
  operation: ee.schimke.composeai.rcplayer.protocol.RcOperation,
  state: RcPlayerState,
): Modifier =
  when (operation) {
    is RcBackgroundModifier ->
      drawBehind {
        val color =
          if (operation.usesColorId) {
            Color(state.color(operation.colorId))
          } else {
            Color(
              red = state.resolve(operation.red),
              green = state.resolve(operation.green),
              blue = state.resolve(operation.blue),
              alpha = state.resolve(operation.alpha),
            )
          }
        when (operation.shapeType) {
          RcBackgroundModifier.SHAPE_RECTANGLE -> drawRect(color)
          RcBackgroundModifier.SHAPE_CIRCLE ->
            drawCircle(color, radius = minOf(size.width, size.height) / 2f)
        }
      }
    is RcBorderModifier ->
      drawBehind {
        val color =
          if (operation.usesColorId) {
            Color(state.color(operation.colorId))
          } else {
            Color(
              red = state.resolve(operation.red),
              green = state.resolve(operation.green),
              blue = state.resolve(operation.blue),
              alpha = state.resolve(operation.alpha),
            )
          }
        val borderWidth = state.resolve(operation.borderWidth).coerceAtLeast(0f)
        val corner = state.resolve(operation.roundedCorner).coerceAtLeast(0f)
        val halfSize = minOf(size.width, size.height) / 2f
        if (operation.wireVersion != 0 && borderWidth >= halfSize) {
          when (operation.shapeType) {
            RcBackgroundModifier.SHAPE_RECTANGLE -> drawRect(color)
            RcBackgroundModifier.SHAPE_CIRCLE -> drawCircle(color, radius = halfSize)
            else -> drawRoundRect(color, cornerRadius = CornerRadius(corner))
          }
        } else {
          val inset = if (operation.wireVersion == 0) 0f else borderWidth / 2f
          val stroke = Stroke(width = borderWidth)
          when (operation.shapeType) {
            RcBackgroundModifier.SHAPE_RECTANGLE ->
              drawRect(
                color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = stroke,
              )
            RcBackgroundModifier.SHAPE_CIRCLE ->
              drawCircle(color, radius = (halfSize - inset).coerceAtLeast(0f), style = stroke)
            else ->
              drawRoundRect(
                color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
                style = stroke,
              )
          }
        }
      }
    RcClipRectModifier ->
      drawWithContent {
        val contentScope = this
        clipRect { contentScope.drawContent() }
      }
    is RcRoundedClipRectModifier ->
      drawWithContent {
        val topStart = state.resolve(operation.topStart).coerceAtLeast(0f)
        val topEnd = state.resolve(operation.topEnd).coerceAtLeast(0f)
        val bottomStart = state.resolve(operation.bottomStart).coerceAtLeast(0f)
        val bottomEnd = state.resolve(operation.bottomEnd).coerceAtLeast(0f)
        val path =
          Path().apply {
            addRoundRect(
              RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = CornerRadius(topStart),
                topRightCornerRadius = CornerRadius(topEnd),
                bottomRightCornerRadius = CornerRadius(bottomEnd),
                bottomLeftCornerRadius = CornerRadius(bottomStart),
              )
            )
          }
        val contentScope = this
        clipPath(path) { contentScope.drawContent() }
      }
    else -> this
  }

private fun Modifier.applyWidth(
  modifiers: RcLayoutModifiers,
  state: RcPlayerState,
  density: Density,
  fillMissing: Boolean,
): Modifier =
  when (val width = modifiers.width) {
    null -> if (fillMissing) fillMaxWidth() else this
    else ->
      when (width.type) {
        RcDimensionType.EXACT -> width(with(density) { state.resolve(width.value).toDp() })
        RcDimensionType.EXACT_DP -> width(state.resolve(width.value).dp)
        RcDimensionType.FILL,
        RcDimensionType.FILL_PARENT_MAX_WIDTH -> fillMaxWidth()
        else -> this
      }
  }

private fun Modifier.applyHeight(
  modifiers: RcLayoutModifiers,
  state: RcPlayerState,
  density: Density,
  fillMissing: Boolean,
): Modifier =
  when (val height = modifiers.height) {
    null -> if (fillMissing) fillMaxHeight() else this
    else ->
      when (height.type) {
        RcDimensionType.EXACT -> height(with(density) { state.resolve(height.value).toDp() })
        RcDimensionType.EXACT_DP -> height(state.resolve(height.value).dp)
        RcDimensionType.FILL,
        RcDimensionType.FILL_PARENT_MAX_HEIGHT -> fillMaxHeight()
        else -> this
      }
  }

internal data class RcRootTransform(
  val scaleX: Float,
  val scaleY: Float,
  val translateX: Float,
  val translateY: Float,
)

/** AndroidX CoreDocument.computeScale/computeTranslate semantics for root canvas documents. */
internal fun computeRootTransform(
  documentWidth: Float,
  documentHeight: Float,
  viewportWidth: Float,
  viewportHeight: Float,
  behavior: RcRootContentBehavior?,
): RcRootTransform {
  if (behavior?.sizing != RcRootContentBehavior.SIZING_SCALE) {
    return RcRootTransform(1f, 1f, 0f, 0f)
  }
  val widthRatio = viewportWidth / documentWidth.coerceAtLeast(1f)
  val heightRatio = viewportHeight / documentHeight.coerceAtLeast(1f)
  val scale =
    when (behavior.mode) {
      RcRootContentBehavior.SCALE_INSIDE -> minOf(1f, widthRatio, heightRatio)
      RcRootContentBehavior.SCALE_FIT -> minOf(widthRatio, heightRatio)
      RcRootContentBehavior.SCALE_FILL_WIDTH -> widthRatio
      RcRootContentBehavior.SCALE_FILL_HEIGHT -> heightRatio
      RcRootContentBehavior.SCALE_CROP -> maxOf(widthRatio, heightRatio)
      else -> 1f
    }
  val scaleX = if (behavior.mode == RcRootContentBehavior.SCALE_FILL_BOUNDS) widthRatio else scale
  val scaleY = if (behavior.mode == RcRootContentBehavior.SCALE_FILL_BOUNDS) heightRatio else scale
  val contentWidth = documentWidth * scaleX
  val contentHeight = documentHeight * scaleY
  val translateX =
    when (behavior.alignment and 0xf0) {
      RcRootContentBehavior.ALIGNMENT_HORIZONTAL_CENTER -> (viewportWidth - contentWidth) / 2f
      RcRootContentBehavior.ALIGNMENT_END -> viewportWidth - contentWidth
      else -> 0f
    }
  val translateY =
    when (behavior.alignment and 0x0f) {
      RcRootContentBehavior.ALIGNMENT_VERTICAL_CENTER -> (viewportHeight - contentHeight) / 2f
      RcRootContentBehavior.ALIGNMENT_BOTTOM -> viewportHeight - contentHeight
      else -> 0f
    }
  return RcRootTransform(scaleX, scaleY, translateX, translateY)
}

private class RcPaintState {
  var color: Int = 0xff000000.toInt()
  var strokeWidth: Float = 1f
  var stroke: Boolean = false
  var strokeCap: StrokeCap = StrokeCap.Butt
  var strokeJoin: StrokeJoin = StrokeJoin.Miter
  var alpha: Float = 1f
  var blendMode: BlendMode = BlendMode.SrcOver
  var blendModeValue: Int = 3
  var textSize: Float = 16f
  var fontFamily: FontFamily = FontFamily.Default
  var fontWeight: FontWeight = FontWeight.Normal
  var fontStyle: FontStyle = FontStyle.Normal
  var fontType: Int = 0

  fun composeColor(): Color {
    val color = Color(color)
    return color.copy(alpha = color.alpha * alpha)
  }

  fun style() =
    if (stroke) Stroke(width = strokeWidth, cap = strokeCap, join = strokeJoin) else Fill
}

private class RcFloatFunctionRuntime {
  val definitions = mutableMapOf<Int, RcLinkedNode.Container>()
  val executing = mutableSetOf<Int>()
}

private fun DrawScope.drawOperations(
  operations: List<RcLinkedNode>,
  state: RcPlayerState,
  paint: RcPaintState,
  computedPaths: MutableMap<Int, Path>,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  functions: RcFloatFunctionRuntime,
  requestedTheme: Int,
  filterTheme: Boolean,
  drawContent: (() -> Unit)? = null,
) {
  var currentTheme = RcTheme.UNSPECIFIED
  for (node in operations) {
    if (node is RcLinkedNode.Container) {
      val functionDefinition = node.operation as? RcFloatFunctionDefine
      if (functionDefinition != null) {
        functions.definitions[functionDefinition.id] = node
        continue
      }
      if (!filterTheme || isThemeVisible(requestedTheme, currentTheme)) {
        when (node.operation.opcode) {
          RcOpcodes.CANVAS_OPERATIONS ->
            drawOperations(
              node.children,
              state,
              paint,
              computedPaths,
              textMeasurer,
              images,
              functions,
              requestedTheme,
              filterTheme = false,
              drawContent = drawContent,
            )
          RcOpcodes.RUN_ACTION -> state.executeRunAction(node.children)
          else -> error("Container opcode ${node.operation.opcode} is not renderable")
        }
      }
      continue
    }
    val operation = (node as RcLinkedNode.Operation).operation
    if (operation is RcTheme) {
      currentTheme = operation.theme
      continue
    }
    if (filterTheme && !isThemeVisible(requestedTheme, currentTheme)) continue
    when (operation) {
      is RcPaintData -> applyPaint(operation, paint, state)
      is RcDraw4 -> draw4(operation, paint, state)
      is RcDraw3 -> draw3(operation, paint, state)
      is RcDraw6 -> draw6(operation, paint, state)
      is RcTransform2 -> transform2(operation, state)
      is RcIdOperation -> drawIdOperation(operation, paint, state, computedPaths)
      is RcPathTween ->
        state.setPath(
          operation.outId,
          tweenPathData(
            operation.outId,
            operation.path1Id,
            operation.path2Id,
            state.resolve(operation.tween),
            state,
          ),
        )
      is RcPathCreate ->
        state.setPath(
          operation.id,
          RcPathData(
            operation.id,
            listOf(
              RcFloatWord(0x7fc00000 or RcPathCommands.MOVE),
              operation.startX,
              operation.startY,
            ),
          ),
        )
      is RcPathAppend -> {
        val firstCommand = operation.words.firstOrNull()?.referencedId
        if (firstCommand == RcPathCommands.RESET) {
          state.setPath(operation.id, RcPathData(operation.id, emptyList()))
        } else {
          val existing = state.path(operation.id)
          state.setPath(
            operation.id,
            RcPathData(
              existing?.idAndWinding ?: operation.id,
              existing.orEmptyWords() + operation.words,
            ),
          )
        }
      }
      is RcPathCombine -> {
        val first = pathForId(operation.path1Id, state, computedPaths)
        val second = pathForId(operation.path2Id, state, computedPaths)
        val pathOperation =
          when (operation.operation) {
            0 -> PathOperation.Difference
            1 -> PathOperation.Intersect
            2 -> PathOperation.ReverseDifference
            3 -> PathOperation.Union
            4 -> PathOperation.Xor
            else -> error("Unknown AndroidX path operation ${operation.operation}")
          }
        computedPaths[operation.outId] = Path().apply { op(first, second, pathOperation) }
      }
      is RcPathExpression -> state.applyPathExpression(operation)
      is RcFloatExpression -> state.applyFloatExpression(operation)
      is RcMatrixFromPath -> applyMatrixFromPath(operation, state, computedPaths)
      is RcMatrixVectorMath -> state.applyMatrixVectorMath(operation)
      is RcMatrixExpression -> state.applyMatrixExpression(operation)
      is RcTextMerge,
      is RcTextLength,
      is RcTextSubtext -> state.applyTextOperation(operation)
      is RcTextTransform -> state.applyTextOperation(operation)
      is RcTextFromFloat -> state.applyTextOperation(operation)
      is RcTextLookup -> state.applyTextOperation(operation)
      is RcTextLookupInt -> state.applyTextOperation(operation)
      is RcDataMapLookup -> state.applyDataOperation(operation)
      is RcIdLookup -> state.applyDataOperation(operation)
      is RcDynamicFloatList -> state.applyDataOperation(operation)
      is RcUpdateDynamicFloatList -> state.applyDataOperation(operation)
      is RcFloatFunctionCall -> {
        val definition =
          requireNotNull(functions.definitions[operation.functionId]) {
            "Missing float function ${operation.functionId}"
          }
        val descriptor = definition.operation as RcFloatFunctionDefine
        require(operation.arguments.size <= descriptor.parameterIds.size) {
          "Float function ${operation.functionId} received ${operation.arguments.size} arguments " +
            "for ${descriptor.parameterIds.size} parameters"
        }
        require(functions.executing.add(operation.functionId)) {
          "Recursive float function ${operation.functionId} is not allowed"
        }
        try {
          operation.arguments.forEachIndexed { index, argument ->
            state.setFloat(descriptor.parameterIds[index], state.resolve(argument))
          }
          drawOperations(
            definition.children,
            state,
            paint,
            computedPaths,
            textMeasurer,
            images,
            functions,
            requestedTheme,
            filterTheme = false,
            drawContent = drawContent,
          )
        } finally {
          functions.executing.remove(operation.functionId)
        }
      }
      is RcImageAttribute -> state.applyImageAttribute(operation)
      is RcColorAttribute -> state.applyColorAttribute(operation)
      is RcColorExpression -> state.applyColorExpression(operation)
      is RcColorTheme -> state.applyColorTheme(operation, requestedTheme)
      is RcIntegerExpression -> state.applyIntegerExpression(operation)
      is RcDrawText -> drawTextOperation(operation, state, paint, textMeasurer)
      is RcDrawTextAnchored -> drawTextAnchored(operation, state, paint, textMeasurer)
      is RcDrawTextOnPath -> drawTextOnPath(operation, state, paint, computedPaths, textMeasurer)
      is RcDrawBitmap -> drawBitmap(operation, state, paint, images)
      is RcDrawBitmapInt -> drawBitmapInt(operation, paint, images)
      is RcDrawBitmapScaled -> drawBitmapScaled(operation, state, paint, images)
      is RcTextMeasure -> measureTextOperation(operation, state, paint, textMeasurer)
      is RcTextAttribute ->
        measureTextOperation(
          operation.outId,
          operation.textId,
          operation.type,
          state,
          paint,
          textMeasurer,
        )
      is RcDrawTweenPath -> drawTweenPath(operation, paint, state)
      is RcNoArg ->
        when (operation.opcode) {
          RcOpcodes.MATRIX_SAVE -> drawContext.canvas.save()
          RcOpcodes.MATRIX_RESTORE -> drawContext.canvas.restore()
          RcOpcodes.DRAW_CONTENT -> drawContent?.invoke()
        }
      else -> Unit // Constants/data have already populated RcPlayerState.
    }
  }
}

private fun decodeInlineImages(document: RcDocument): Map<Int, ImageBitmap> =
  document.operations
    .filterIsInstance<RcBitmapData>()
    .mapNotNull { bitmap ->
      if (bitmap.encoding != RcBitmapData.ENCODING_INLINE) null
      else runCatching { bitmap.imageId to decodeInlineImage(bitmap) }.getOrNull()
    }
    .toMap()

private fun decodeInlineImage(bitmap: RcBitmapData): ImageBitmap =
  when (bitmap.type) {
    RcBitmapData.TYPE_PNG_8888,
    RcBitmapData.TYPE_PNG,
    RcBitmapData.TYPE_PNG_ALPHA_8 -> Image.makeFromEncoded(bitmap.data).toComposeImageBitmap()
    RcBitmapData.TYPE_RAW8888 -> {
      val rowBytes = bitmap.width * 4
      require(bitmap.data.size >= rowBytes * bitmap.height) { "Truncated RGBA bitmap" }
      Image.makeRaster(
          ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
          bitmap.data,
          rowBytes,
        )
        .toComposeImageBitmap()
    }
    RcBitmapData.TYPE_RAW8 -> {
      val rowBytes = bitmap.width
      require(bitmap.data.size >= rowBytes * bitmap.height) { "Truncated alpha bitmap" }
      Image.makeRaster(
          ImageInfo(bitmap.width, bitmap.height, ColorType.ALPHA_8, ColorAlphaType.UNPREMUL),
          bitmap.data,
          rowBytes,
        )
        .toComposeImageBitmap()
    }
    else -> error("Unknown AndroidX bitmap type ${bitmap.type}")
  }

private fun DrawScope.drawBitmap(
  operation: RcDrawBitmap,
  state: RcPlayerState,
  paint: RcPaintState,
  images: Map<Int, ImageBitmap>,
) {
  val image = images[operation.imageId] ?: return
  val left = state.resolve(operation.left)
  val top = state.resolve(operation.top)
  val width = state.resolve(operation.right) - left
  val height = state.resolve(operation.bottom) - top
  if (width == 0f || height == 0f) return
  withTransform({
    translate(left, top)
    scale(width / image.width, height / image.height, Offset.Zero)
  }) {
    drawImage(
      image = image,
      topLeft = Offset.Zero,
      alpha = paint.alpha,
      blendMode = paint.blendMode,
    )
  }
}

private fun DrawScope.drawBitmapInt(
  operation: RcDrawBitmapInt,
  paint: RcPaintState,
  images: Map<Int, ImageBitmap>,
) {
  val image = images[operation.imageId] ?: return
  drawBitmapRegion(
    image,
    operation.srcLeft,
    operation.srcTop,
    operation.srcRight,
    operation.srcBottom,
    operation.dstLeft,
    operation.dstTop,
    operation.dstRight,
    operation.dstBottom,
    paint,
  )
}

private fun DrawScope.drawBitmapScaled(
  operation: RcDrawBitmapScaled,
  state: RcPlayerState,
  paint: RcPaintState,
  images: Map<Int, ImageBitmap>,
) {
  val image = images[operation.imageId] ?: return
  val sl = state.resolve(operation.srcLeft)
  val st = state.resolve(operation.srcTop)
  val sr = state.resolve(operation.srcRight)
  val sb = state.resolve(operation.srcBottom)
  val dl = state.resolve(operation.dstLeft)
  val dt = state.resolve(operation.dstTop)
  val dr = state.resolve(operation.dstRight)
  val db = state.resolve(operation.dstBottom)
  val scaled =
    computeImageScaling(
      sl,
      st,
      sr,
      sb,
      dl,
      dt,
      dr,
      db,
      operation.scaleType,
      state.resolve(operation.scaleFactor),
    ) ?: return
  withTransform({ clipRect(dl, dt, dr, db) }) {
    drawBitmapRegion(
      image,
      sl.toInt(),
      st.toInt(),
      sr.toInt(),
      sb.toInt(),
      scaled.left.toInt(),
      scaled.top.toInt(),
      scaled.right.toInt(),
      scaled.bottom.toInt(),
      paint,
    )
  }
}

private fun DrawScope.drawBitmapRegion(
  image: ImageBitmap,
  srcLeft: Int,
  srcTop: Int,
  srcRight: Int,
  srcBottom: Int,
  dstLeft: Int,
  dstTop: Int,
  dstRight: Int,
  dstBottom: Int,
  paint: RcPaintState,
) {
  val srcWidth = srcRight - srcLeft
  val srcHeight = srcBottom - srcTop
  val dstWidth = dstRight - dstLeft
  val dstHeight = dstBottom - dstTop
  if (srcWidth <= 0 || srcHeight <= 0 || dstWidth == 0 || dstHeight == 0) return
  drawImage(
    image = image,
    srcOffset = IntOffset(srcLeft, srcTop),
    srcSize = IntSize(srcWidth, srcHeight),
    dstOffset = IntOffset(dstLeft, dstTop),
    dstSize = IntSize(dstWidth, dstHeight),
    alpha = paint.alpha,
    blendMode = paint.blendMode,
  )
}

internal data class RcScaledRect(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

/** Exact integer-centering arithmetic from AndroidX ImageScaling.adjustDrawToType. */
internal fun computeImageScaling(
  srcLeft: Float,
  srcTop: Float,
  srcRight: Float,
  srcBottom: Float,
  dstLeft: Float,
  dstTop: Float,
  dstRight: Float,
  dstBottom: Float,
  scaleType: Int,
  scaleFactor: Float,
): RcScaledRect? {
  val srcWidth = (srcRight - srcLeft).toInt()
  val srcHeight = (srcBottom - srcTop).toInt()
  if (srcWidth == 0 || srcHeight == 0) return null
  val dstWidth = (dstRight - dstLeft).toInt()
  val dstHeight = (dstBottom - dstTop).toInt()
  var width = dstWidth
  var height = dstHeight
  when (scaleType) {
    0 -> {
      width = srcWidth
      height = srcHeight
    }
    1 ->
      if (!(dstHeight > srcHeight && dstWidth > srcWidth)) {
        if (srcWidth.toFloat() * (dstBottom - dstTop) > (dstRight - dstLeft) * srcHeight) {
          height = dstWidth * srcHeight / srcWidth
        } else width = dstHeight * srcWidth / srcHeight
      } else {
        width = srcWidth
        height = srcHeight
      }
    2 -> height = dstWidth * srcHeight / srcWidth
    3 -> width = dstHeight * srcWidth / srcHeight
    4 ->
      if (srcWidth.toFloat() * (dstBottom - dstTop) > (dstRight - dstLeft) * srcHeight) {
        height = dstWidth * srcHeight / srcWidth
      } else width = dstHeight * srcWidth / srcHeight
    5 ->
      if (srcWidth.toFloat() * (dstBottom - dstTop) < (dstRight - dstLeft) * srcHeight) {
        height = dstWidth * srcHeight / srcWidth
      } else width = dstHeight * srcWidth / srcHeight
    6 -> Unit
    7 -> {
      width = (srcWidth * scaleFactor).toInt()
      height = (srcHeight * scaleFactor).toInt()
    }
    else -> error("Unknown AndroidX image scale type $scaleType")
  }
  val x = (dstWidth - width) / 2
  val y = (dstHeight - height) / 2
  return RcScaledRect(dstLeft + x, dstTop + y, dstLeft + x + width, dstTop + y + height)
}

private fun DrawScope.textStyle(paint: RcPaintState): TextStyle =
  TextStyle(
    color = paint.composeColor(),
    fontSize = (paint.textSize / density).sp,
    fontFamily = paint.fontFamily,
    fontWeight = paint.fontWeight,
    fontStyle = paint.fontStyle,
  )

private fun DrawScope.drawTextOperation(
  operation: RcDrawText,
  state: RcPlayerState,
  paint: RcPaintState,
  textMeasurer: TextMeasurer,
) {
  val source = state.text(operation.textId) ?: return
  val end =
    if (operation.end == -1 || operation.end > source.length) source.length else operation.end
  val text = source.substring(operation.start, end)
  val style = textStyle(paint)
  val layout =
    textMeasurer.measure(
      text,
      style,
      layoutDirection = if (operation.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    )
  drawText(
    textMeasurer = textMeasurer,
    text = text,
    topLeft = Offset(state.resolve(operation.x), state.resolve(operation.y) - layout.firstBaseline),
    style = style,
    blendMode = paint.blendMode,
  )
}

internal data class RcAnchoredTextPosition(val x: Float, val baselineY: Float)

internal fun computeAnchoredTextPosition(
  anchorX: Float,
  anchorY: Float,
  panX: Float,
  panY: Float,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  baselineRelative: Boolean,
): RcAnchoredTextPosition {
  val width = right - left
  val height = bottom - top
  val x = anchorX - width * (1f + panX) / 2f - left
  val y =
    if (panY.isNaN()) anchorY
    else anchorY - height * (1f - panY) / 2f + if (baselineRelative) height / 2f else -top
  return RcAnchoredTextPosition(x, y)
}

private fun DrawScope.drawTextAnchored(
  operation: RcDrawTextAnchored,
  state: RcPlayerState,
  paint: RcPaintState,
  textMeasurer: TextMeasurer,
) {
  val text = state.text(operation.textId) ?: return
  val style = textStyle(paint)
  val rtl = operation.flags and RcDrawTextAnchored.TEXT_RTL != 0
  val layout =
    textMeasurer.measure(
      text,
      style,
      layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    )
  val boxes = text.indices.map(layout::getBoundingBox)
  val left = boxes.minOfOrNull { it.left } ?: 0f
  val right = boxes.maxOfOrNull { it.right } ?: layout.size.width.toFloat()
  val top = (boxes.minOfOrNull { it.top } ?: 0f) - layout.firstBaseline
  val bottom =
    (boxes.maxOfOrNull { it.bottom } ?: layout.size.height.toFloat()) - layout.firstBaseline
  val position =
    computeAnchoredTextPosition(
      state.resolve(operation.x),
      state.resolve(operation.y),
      state.resolve(operation.panX),
      state.resolve(operation.panY),
      left,
      top,
      right,
      bottom,
      operation.flags and RcDrawTextAnchored.BASELINE_RELATIVE != 0,
    )
  drawText(
    textMeasurer = textMeasurer,
    text = text,
    topLeft = Offset(position.x, position.baselineY - layout.firstBaseline),
    style = style,
    blendMode = paint.blendMode,
  )
}

/** AndroidX-compatible glyph-centre placement implemented with Compose's cross-platform fonts. */
private fun DrawScope.drawTextOnPath(
  operation: RcDrawTextOnPath,
  state: RcPlayerState,
  paint: RcPaintState,
  computedPaths: Map<Int, Path>,
  textMeasurer: TextMeasurer,
) {
  val text = state.text(operation.textId).orEmpty()
  if (text.isEmpty()) return
  val path = pathForId(operation.pathId, state, computedPaths)
  val measure = org.jetbrains.skia.PathMeasure(path.asSkiaPath(), false)
  if (measure.length <= 0f) return
  drawTextOnPathWithCompose(
    text = text,
    measure = measure,
    horizontalOffset = state.resolve(operation.horizontalOffset),
    verticalOffset = state.resolve(operation.verticalOffset),
    paint = paint,
    textMeasurer = textMeasurer,
  )
}

/**
 * Compose text layout supplies the same bundled/fallback fonts on desktop and Wasm. The AndroidX
 * glyph-centre path placement rule is retained, and surrogate pairs are never split.
 */
private fun DrawScope.drawTextOnPathWithCompose(
  text: String,
  measure: org.jetbrains.skia.PathMeasure,
  horizontalOffset: Float,
  verticalOffset: Float,
  paint: RcPaintState,
  textMeasurer: TextMeasurer,
) {
  val style = textStyle(paint)
  var contourLength = measure.length
  var distance = horizontalOffset
  for (segment in unicodeScalars(text)) {
    val layout = textMeasurer.measure(segment, style)
    val advance = layout.size.width.toFloat()
    val center = distance + advance / 2f
    if (center > contourLength) {
      if (!measure.nextContour()) return
      contourLength = measure.length
      distance = 0f
    }
    val position = measure.getPosition(distance + advance / 2f)
    val tangent = measure.getTangent(distance + advance / 2f)
    if (position != null && tangent != null) {
      val composePosition = Offset(position.x, position.y)
      val placement =
        computePathTextPlacement(
          composePosition,
          Offset(tangent.x, tangent.y),
          advance,
          verticalOffset,
          layout.firstBaseline,
        )
      withTransform({ rotate(placement.angleDegrees, composePosition) }) {
        drawText(
          textMeasurer = textMeasurer,
          text = segment,
          topLeft = placement.topLeft,
          style = style,
          blendMode = paint.blendMode,
        )
      }
    }
    distance += advance
  }
}

internal data class RcPathTextPlacement(val topLeft: Offset, val angleDegrees: Float)

internal fun computePathTextPlacement(
  position: Offset,
  tangent: Offset,
  advance: Float,
  verticalOffset: Float,
  firstBaseline: Float,
): RcPathTextPlacement =
  RcPathTextPlacement(
    topLeft = Offset(position.x - advance / 2f, position.y + verticalOffset - firstBaseline),
    angleDegrees = atan2(tangent.y, tangent.x) * 180f / PI.toFloat(),
  )

private fun unicodeScalars(text: String): List<String> = buildList {
  var offset = 0
  while (offset < text.length) {
    val first = text[offset].code
    val length =
      if (
        first in 0xd800..0xdbff &&
          offset + 1 < text.length &&
          text[offset + 1].code in 0xdc00..0xdfff
      )
        2
      else 1
    add(text.substring(offset, offset + length))
    offset += length
  }
}

private fun DrawScope.measureTextOperation(
  operation: RcTextMeasure,
  state: RcPlayerState,
  paint: RcPaintState,
  textMeasurer: TextMeasurer,
) =
  measureTextOperation(
    operation.outId,
    operation.textId,
    operation.type,
    state,
    paint,
    textMeasurer,
  )

private fun DrawScope.measureTextOperation(
  outId: Int,
  textId: Int,
  type: Int,
  state: RcPlayerState,
  paint: RcPaintState,
  textMeasurer: TextMeasurer,
) {
  val text = state.text(textId).orEmpty()
  val layout = textMeasurer.measure(text, textStyle(paint))
  var left = 0f
  var right = layout.size.width.toFloat()
  var top = -layout.firstBaseline
  var bottom = layout.size.height - layout.firstBaseline
  if (text.isNotEmpty()) {
    val boxes = text.indices.map(layout::getBoundingBox)
    left = boxes.minOf { it.left }
    right = boxes.maxOf { it.right }
    top = boxes.minOf { it.top } - layout.firstBaseline
    bottom = boxes.maxOf { it.bottom } - layout.firstBaseline
  }
  val flags = type ushr 8
  if (flags and 0x04 != 0) {
    left = 0f
    right = layout.size.width.toFloat()
  } else if (flags and 0x01 != 0) {
    right = layout.size.width.toFloat() - left
  }
  if (flags and 0x02 != 0) {
    top = -layout.firstBaseline
    bottom = layout.size.height - layout.firstBaseline
  }
  val value = selectTextMeasurement(type, left, top, right, bottom, text.length)
  state.setFloat(outId, value)
}

internal fun selectTextMeasurement(
  type: Int,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  textLength: Int,
): Float =
  when (type and 0xff) {
    0 -> right - left
    1 -> bottom - top
    2 -> left
    3 -> right
    4 -> top
    5 -> bottom
    6 -> textLength.toFloat()
    else -> error("Unknown AndroidX text measurement ${type and 0xff}")
  }

private fun DrawScope.applyMatrixFromPath(
  operation: RcMatrixFromPath,
  state: RcPlayerState,
  computedPaths: Map<Int, Path>,
) {
  val path = pathForId(operation.pathId, state, computedPaths)
  val measure = PathMeasure().apply { setPath(path, forceClosed = false) }
  if (measure.length <= 0f) return
  // This modulo, and the currently unused vertical offset, intentionally match AndroidPaintContext.
  val distance = (measure.length * state.resolve(operation.percent)) % measure.length
  if (operation.flags and POSITION_MATRIX_FLAG != 0) {
    val position = measure.getPosition(distance)
    drawContext.transform.translate(position.x, position.y)
  }
  if (operation.flags and TANGENT_MATRIX_FLAG != 0) {
    val tangent = measure.getTangent(distance)
    val degrees = atan2(tangent.y, tangent.x) * 180f / PI.toFloat()
    drawContext.transform.rotate(degrees, Offset.Zero)
  }
}

private const val POSITION_MATRIX_FLAG = 0x01
private const val TANGENT_MATRIX_FLAG = 0x02

private fun RcPathData?.orEmptyWords(): List<RcFloatWord> = this?.words ?: emptyList()

private fun DrawScope.drawTweenPath(
  operation: RcDrawTweenPath,
  paint: RcPaintState,
  state: RcPlayerState,
) {
  val data =
    tweenPathData(-1, operation.path1Id, operation.path2Id, state.resolve(operation.tween), state)
  val path = buildPath(data, state)
  val start = state.resolve(operation.start)
  val stop = state.resolve(operation.stop)
  val trimmed = trimPath(path, start, stop)
  drawPath(
    path = trimmed,
    color = paint.composeColor(),
    style = paint.style(),
    blendMode = paint.blendMode,
  )
}

internal fun tweenPathData(
  outId: Int,
  path1Id: Int,
  path2Id: Int,
  tween: Float,
  state: RcPlayerState,
): RcPathData {
  val first = requireNotNull(state.path(path1Id)) { "Missing path $path1Id" }
  val second = requireNotNull(state.path(path2Id)) { "Missing path $path2Id" }
  if (tween == 0f) return first.copy(idAndWinding = outId)
  if (tween == 1f) return second.copy(idAndWinding = outId)
  require(first.words.size >= second.words.size) {
    "Path $path1Id has fewer words than path $path2Id"
  }
  val commandIndexes = pathCommandIndexes(first.words)
  val words =
    List(second.words.size) { index ->
      val firstWord = first.words[index]
      val secondWord = second.words[index]
      if (index in commandIndexes) {
        firstWord
      } else {
        val start = state.resolve(firstWord)
        val end = state.resolve(secondWord)
        RcFloatWord.literal(start + (end - start) * tween)
      }
    }
  return RcPathData(outId, words)
}

private fun pathCommandIndexes(words: List<RcFloatWord>): Set<Int> {
  val indexes = mutableSetOf<Int>()
  var index = 0
  while (index < words.size) {
    indexes += index
    when (words[index].referencedId) {
      RcPathCommands.MOVE -> index += 3
      RcPathCommands.LINE -> index += 5
      RcPathCommands.QUADRATIC -> index += 7
      RcPathCommands.CONIC -> index += 8
      RcPathCommands.CUBIC -> index += 9
      RcPathCommands.CLOSE,
      RcPathCommands.DONE -> index += 1
      else -> error("Path command at word $index is invalid")
    }
  }
  return indexes
}

private fun trimPath(path: Path, start: Float, stop: Float): Path {
  if (start <= 0f && stop >= 1f) return path
  val result = Path()
  if (start < stop) {
    val measure = PathMeasure().apply { setPath(path, forceClosed = false) }
    measure.getSegment(
      start.coerceAtLeast(0f) * measure.length,
      stop.coerceAtMost(1f) * measure.length,
      result,
      startWithMoveTo = true,
    )
  }
  return result
}

internal fun isThemeVisible(requestedTheme: Int, operationTheme: Int): Boolean =
  requestedTheme == RcTheme.UNSPECIFIED ||
    operationTheme == RcTheme.UNSPECIFIED ||
    operationTheme == requestedTheme

private fun DrawScope.drawIdOperation(
  operation: RcIdOperation,
  paint: RcPaintState,
  state: RcPlayerState,
  computedPaths: Map<Int, Path>,
) {
  when (operation.opcode) {
    RcOpcodes.DRAW_PATH -> {
      drawPath(
        path = pathForId(operation.id, state, computedPaths),
        color = paint.composeColor(),
        style = paint.style(),
        blendMode = paint.blendMode,
      )
    }
    RcOpcodes.CLIP_PATH -> {
      // AndroidX packs the path id in the low 20 bits and the Region.Op in the high byte.
      val pathId = operation.id and 0x000fffff
      val regionOp = operation.id shr 24
      drawContext.canvas.clipPath(
        pathForId(pathId, state, computedPaths),
        if (regionOp == 1) ClipOp.Difference else ClipOp.Intersect,
      )
    }
  }
}

private fun pathForId(id: Int, state: RcPlayerState, computedPaths: Map<Int, Path>): Path =
  computedPaths[id] ?: state.path(id)?.let { buildPath(it, state) } ?: error("Missing path $id")

/** Convert AndroidX's padded float-word path encoding without canonicalising command NaNs. */
private fun buildPath(data: RcPathData, state: RcPlayerState): Path {
  val path =
    Path().apply {
      fillType = if (data.winding == 1) PathFillType.EvenOdd else PathFillType.NonZero
    }
  var index = 0
  fun argument(): Float {
    if (index >= data.words.size) error("Truncated PathData ${data.id} at word $index")
    return state.resolve(data.words[index++])
  }
  fun skipLegacyPadding() {
    if (index + 2 > data.words.size) error("Truncated PathData ${data.id} legacy padding")
    index += 2
  }
  while (index < data.words.size) {
    val command =
      data.words[index++].referencedId
        ?: error("PathData ${data.id} command at word ${index - 1} is not NaN-encoded")
    when (command) {
      RcPathCommands.MOVE -> path.moveTo(argument(), argument())
      RcPathCommands.LINE -> {
        skipLegacyPadding()
        path.lineTo(argument(), argument())
      }
      RcPathCommands.QUADRATIC -> {
        skipLegacyPadding()
        path.quadraticTo(argument(), argument(), argument(), argument())
      }
      RcPathCommands.CONIC -> {
        skipLegacyPadding()
        path.conicToSkia(argument(), argument(), argument(), argument(), argument())
      }
      RcPathCommands.CUBIC -> {
        skipLegacyPadding()
        path.cubicTo(argument(), argument(), argument(), argument(), argument(), argument())
      }
      RcPathCommands.CLOSE -> path.close()
      RcPathCommands.DONE -> return path
      else -> error("PathData ${data.id} has unknown command $command")
    }
  }
  return path
}

/** Narrow platform seam for the one AndroidX path primitive absent from common Compose Path. */
internal expect fun Path.conicToSkia(x1: Float, y1: Float, x2: Float, y2: Float, weight: Float)

private fun DrawScope.draw4(operation: RcDraw4, paint: RcPaintState, state: RcPlayerState) {
  val a = state.resolve(operation.first)
  val b = state.resolve(operation.second)
  val c = state.resolve(operation.third)
  val d = state.resolve(operation.fourth)
  when (operation.opcode) {
    RcOpcodes.DRAW_RECT ->
      drawRect(
        paint.composeColor(),
        Offset(a, b),
        Size(c - a, d - b),
        style = paint.style(),
        blendMode = paint.blendMode,
      )
    RcOpcodes.DRAW_OVAL ->
      drawOval(
        paint.composeColor(),
        Offset(a, b),
        Size(c - a, d - b),
        style = paint.style(),
        blendMode = paint.blendMode,
      )
    RcOpcodes.DRAW_LINE ->
      drawLine(
        paint.composeColor(),
        Offset(a, b),
        Offset(c, d),
        strokeWidth = paint.strokeWidth,
        cap = paint.strokeCap,
        blendMode = paint.blendMode,
      )
    RcOpcodes.CLIP_RECT -> drawContext.canvas.clipRect(a, b, c, d)
    RcOpcodes.MATRIX_SCALE -> drawContext.transform.scale(a, b, Offset(c, d))
  }
}

private fun DrawScope.draw3(operation: RcDraw3, paint: RcPaintState, state: RcPlayerState) {
  val a = state.resolve(operation.first)
  val b = state.resolve(operation.second)
  val c = state.resolve(operation.third)
  when (operation.opcode) {
    RcOpcodes.DRAW_CIRCLE ->
      drawCircle(
        paint.composeColor(),
        c,
        Offset(a, b),
        style = paint.style(),
        blendMode = paint.blendMode,
      )
    RcOpcodes.MATRIX_ROTATE -> drawContext.transform.rotate(a, Offset(b, c))
  }
}

private fun DrawScope.draw6(operation: RcDraw6, paint: RcPaintState, state: RcPlayerState) {
  val a = state.resolve(operation.first)
  val b = state.resolve(operation.second)
  val c = state.resolve(operation.third)
  val d = state.resolve(operation.fourth)
  val e = state.resolve(operation.fifth)
  val f = state.resolve(operation.sixth)
  when (operation.opcode) {
    RcOpcodes.DRAW_ROUND_RECT ->
      drawRoundRect(
        paint.composeColor(),
        Offset(a, b),
        Size(c - a, d - b),
        CornerRadius(e, f),
        style = paint.style(),
        blendMode = paint.blendMode,
      )
    RcOpcodes.DRAW_ARC,
    RcOpcodes.DRAW_SECTOR ->
      drawArc(
        paint.composeColor(),
        e,
        f,
        useCenter = operation.opcode == RcOpcodes.DRAW_SECTOR,
        topLeft = Offset(a, b),
        size = Size(c - a, d - b),
        style = paint.style(),
        blendMode = paint.blendMode,
      )
  }
}

private fun DrawScope.transform2(operation: RcTransform2, state: RcPlayerState) {
  val a = state.resolve(operation.first)
  val b = state.resolve(operation.second)
  when (operation.opcode) {
    RcOpcodes.MATRIX_TRANSLATE -> drawContext.transform.translate(a, b)
    RcOpcodes.MATRIX_SKEW -> {
      val matrix =
        Matrix().apply {
          this[1, 0] = a
          this[0, 1] = b
        }
      drawContext.transform.transform(matrix)
    }
  }
}

private fun applyPaint(operation: RcPaintData, state: RcPaintState, values: RcPlayerState) {
  var index = 0
  while (index < operation.words.size) {
    val command = operation.words[index++]
    when (command and 0xffff) {
      1 ->
        state.textSize =
          values.resolve(
            ee.schimke.composeai.rcplayer.protocol.RcFloatWord(operation.words[index++])
          )
      4 -> state.color = operation.words[index++] // PaintBundle.COLOR
      5 ->
        state.strokeWidth =
          values.resolve(
            ee.schimke.composeai.rcplayer.protocol.RcFloatWord(operation.words[index++])
          )
      7 ->
        state.strokeCap =
          when (command ushr 16) {
            1 -> StrokeCap.Round
            2 -> StrokeCap.Square
            else -> StrokeCap.Butt
          }
      8 -> state.stroke = command ushr 16 == 1
      12 ->
        state.alpha =
          values
            .resolve(ee.schimke.composeai.rcplayer.protocol.RcFloatWord(operation.words[index++]))
            .coerceIn(0f, 1f)
      15 ->
        state.strokeJoin =
          when (command ushr 16) {
            1 -> StrokeJoin.Round
            2 -> StrokeJoin.Bevel
            else -> StrokeJoin.Miter
          }
      18 -> {
        state.blendModeValue = command ushr 16
        state.blendMode = blendMode(state.blendModeValue)
      }
      19 -> state.color = values.color(operation.words[index++])
      16 -> {
        val style = command ushr 16
        val fontType = operation.words[index++]
        state.fontType = fontType
        state.fontFamily =
          when (fontType) {
            0 -> FontFamily.Default
            1 -> FontFamily.SansSerif
            2 -> FontFamily.Serif
            3 -> FontFamily.Monospace
            else -> error("AndroidX font id $fontType is not implemented by the CMP backend")
          }
        state.fontWeight = FontWeight((style and 0x3ff).takeIf { it > 0 } ?: 400)
        state.fontStyle = if (style and 0x800 != 0) FontStyle.Italic else FontStyle.Normal
      }
      else -> error("Paint command ${command and 0xffff} is not implemented by the baseline player")
    }
  }
}

private fun blendMode(value: Int): BlendMode =
  when (value) {
    0 -> BlendMode.Clear
    1 -> BlendMode.Src
    2 -> BlendMode.Dst
    3 -> BlendMode.SrcOver
    4 -> BlendMode.DstOver
    5 -> BlendMode.SrcIn
    6 -> BlendMode.DstIn
    7 -> BlendMode.SrcOut
    8 -> BlendMode.DstOut
    9 -> BlendMode.SrcAtop
    10 -> BlendMode.DstAtop
    11 -> BlendMode.Xor
    12 -> BlendMode.Plus
    13 -> BlendMode.Modulate
    14 -> BlendMode.Screen
    15 -> BlendMode.Overlay
    16 -> BlendMode.Darken
    17 -> BlendMode.Lighten
    18 -> BlendMode.ColorDodge
    19 -> BlendMode.ColorBurn
    20 -> BlendMode.Hardlight
    21 -> BlendMode.Softlight
    22 -> BlendMode.Difference
    23 -> BlendMode.Exclusion
    24 -> BlendMode.Multiply
    25 -> BlendMode.Hue
    26 -> BlendMode.Saturation
    27 -> BlendMode.Color
    28 -> BlendMode.Luminosity
    else -> BlendMode.SrcOver
  }
