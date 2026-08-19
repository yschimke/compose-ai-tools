package ee.schimke.composeai.rcplayer.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
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
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcAlignByModifier
import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcClickArea
import ee.schimke.composeai.rcplayer.protocol.RcClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcConditionalOperations
import ee.schimke.composeai.rcplayer.protocol.RcDataMapLookup
import ee.schimke.composeai.rcplayer.protocol.RcDebugMessage
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
import ee.schimke.composeai.rcplayer.protocol.RcFontData
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerAttribute
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHapticFeedback
import ee.schimke.composeai.rcplayer.protocol.RcHapticType
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightInModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcIdLookup
import ee.schimke.composeai.rcplayer.protocol.RcIdOperation
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcImpulseProcess
import ee.schimke.composeai.rcplayer.protocol.RcImpulseStart
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation
import ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute
import ee.schimke.composeai.rcplayer.protocol.RcLoopOperation
import ee.schimke.composeai.rcplayer.protocol.RcMarqueeModifier
import ee.schimke.composeai.rcplayer.protocol.RcMatrixExpression
import ee.schimke.composeai.rcplayer.protocol.RcMatrixFromPath
import ee.schimke.composeai.rcplayer.protocol.RcMatrixVectorMath
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOffsetModifier
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcPathAppend
import ee.schimke.composeai.rcplayer.protocol.RcPathCombine
import ee.schimke.composeai.rcplayer.protocol.RcPathCommands
import ee.schimke.composeai.rcplayer.protocol.RcPathCreate
import ee.schimke.composeai.rcplayer.protocol.RcPathData
import ee.schimke.composeai.rcplayer.protocol.RcPathExpression
import ee.schimke.composeai.rcplayer.protocol.RcPathTween
import ee.schimke.composeai.rcplayer.protocol.RcRippleModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootContentBehavior
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcScrollModifier
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
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTransform2
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcWakeIn
import ee.schimke.composeai.rcplayer.protocol.RcWidthInModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.protocol.RcZIndexModifier
import ee.schimke.composeai.rcplayer.runtime.RcAnimationTimeline
import ee.schimke.composeai.rcplayer.runtime.RcClickActionBlock
import ee.schimke.composeai.rcplayer.runtime.RcClickActionType
import ee.schimke.composeai.rcplayer.runtime.RcComponentGeometry
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import ee.schimke.composeai.rcplayer.runtime.RcImpulsePhase
import ee.schimke.composeai.rcplayer.runtime.RcLayoutModifiers
import ee.schimke.composeai.rcplayer.runtime.RcLayoutNode
import ee.schimke.composeai.rcplayer.runtime.RcLayoutTree
import ee.schimke.composeai.rcplayer.runtime.RcLinkedNode
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEffect
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import ee.schimke.composeai.rcplayer.runtime.RcScrollBlock
import ee.schimke.composeai.rcplayer.runtime.RcTouchActionBlock
import ee.schimke.composeai.rcplayer.runtime.RcTouchActionType
import ee.schimke.composeai.rcplayer.runtime.RcTouchExpressionRuntime
import ee.schimke.composeai.rcplayer.runtime.androidXMarqueeOffset
import ee.schimke.composeai.rcplayer.runtime.visibilityTransform
import ee.schimke.composeai.rcplayer.trace.RcTraceCategory
import ee.schimke.composeai.rcplayer.trace.rcTrace
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

@Composable
public fun RcComposePlayer(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  theme: RcPlayerTheme = RcPlayerTheme.System,
  namedValues: SnapshotStateMap<String, RcNamedValue> = rememberRcNamedValues(),
  onEvent: (RcPlayerEvent) -> Unit = {},
  typefaces: RcTypefaceLoader = RcTypefaceLoader.Default,
  systemColors: (name: String) -> Color? = { null },
) {
  val document = remember(bytes) { RcDocumentCodec.decode(bytes) }
  RcComposePlayer(document, modifier, theme, namedValues, onEvent, typefaces, systemColors)
}

@Composable
public fun RcComposePlayer(
  document: RcDocument,
  modifier: Modifier = Modifier,
  theme: RcPlayerTheme = RcPlayerTheme.System,
  namedValues: SnapshotStateMap<String, RcNamedValue> = rememberRcNamedValues(),
  onEvent: (RcPlayerEvent) -> Unit = {},
  typefaces: RcTypefaceLoader = RcTypefaceLoader.Default,
  systemColors: (name: String) -> Color? = { null },
) {
  // Resolve once, at the only place that can: `RcPlayerTheme.System` is a question for the host,
  // and everything below this point — section gating, and every `ColorTheme` selection — needs a
  // concrete answer. See [rcResolveSystemTheme] for why leaving it unresolved is not a neutral
  // default.
  RcComposePlayerResolved(
    document,
    modifier,
    theme.resolve(),
    namedValues,
    onEvent,
    typefaces,
    systemColors,
  )
}

@Composable
private fun RcComposePlayerResolved(
  document: RcDocument,
  modifier: Modifier,
  theme: Int,
  namedValues: SnapshotStateMap<String, RcNamedValue>,
  onEvent: (RcPlayerEvent) -> Unit,
  typefaces: RcTypefaceLoader,
  systemColors: (name: String) -> Color?,
) {
  val latestEventSink by rememberUpdatedState(onEvent)
  val latestSystemColors by rememberUpdatedState(systemColors)
  val latestHapticFeedback by rememberUpdatedState(LocalHapticFeedback.current)
  var invalidationVersion by remember { mutableIntStateOf(0) }
  var wakeIntervalSeconds by remember(document) { mutableStateOf<Float?>(null) }
  var nextFrameRequestVersion by remember(document) { mutableIntStateOf(0) }
  // Keyed on the document alone. `namedValues` is deliberately *not* in the key: a host rebuilding
  // an equal map in a parent recomposition would otherwise construct a fresh `RcPlayerState` and
  // discard running animation timelines, mid-drag touch state and every variable a document action
  // had changed — the same hazard the `systemColors` comment below describes, on the one API a host
  // uses to drive a live document. Changes are applied incrementally instead; see the
  // `LaunchedEffect` under this block.
  // The exact map handed to the constructor, kept so the bridge below knows what the state
  // actually holds rather than re-reading the holder. Computed in its own `remember` immediately
  // before the state's, so both see the same snapshot of `namedValues`.
  val seededNamedValues = remember(document) { namedValues.toMap() }
  val state =
    remember(document) {
      RcPlayerState(
        document,
        // Seeded once, from whatever the map holds when this document is first composed.
        seededNamedValues,
        eventSink = { latestEventSink(it) },
        onInvalidated = { invalidationVersion += 1 },
        effectSink = { effect ->
          when (effect) {
            is RcPlayerEffect.HapticFeedback ->
              latestHapticFeedback.performAndroidXHaptic(effect.type)
            is RcPlayerEffect.WakeIn -> {
              val current = wakeIntervalSeconds
              if (!effect.seconds.isNaN() && (current == null || effect.seconds < current)) {
                wakeIntervalSeconds = effect.seconds
              }
            }
            RcPlayerEffect.NextFrame -> nextFrameRequestVersion += 1
          }
        },
        // Read through `latestSystemColors`, never captured directly: a host's lookup is usually a
        // capturing lambda, so a parent recomposition hands us a fresh instance. Keying the state
        // on it would rebuild `RcPlayerState` — discarding variables an action changed,
        // touch-expression state and running animation timelines — because a colour callback that
        // resolves the same palette happened to be reallocated.
        //
        // The `Color` -> ARGB conversion happens here, at the module boundary: `RcPlayerState`
        // lives in `:rc-player-runtime`, which has no Compose UI dependency, and packed ARGB really
        // is the wire value there. See [toRcArgb].
        systemColorLookup = { name -> latestSystemColors(name)?.toRcArgb() },
      )
    }
  // Apply host edits to the live state instead of rebuilding it. `setNamedValue` already applied a
  // single value incrementally against `variableNames`, type-checked against the AndroidX variable
  // type; nothing on the public path called it. A removal means "stop overriding this", which needs
  // the pre-override value, so `clearNamedValue` is its inverse (see `RcPlayerState`).
  //
  // Errors are deliberately not caught: an unknown name or a type mismatch threw from the
  // `RcPlayerState` constructor before this change, and a host that names a variable the document
  // does not have should still hear about it rather than watch the value quietly not apply.
  //
  // `appliedNamedValues` tracks what the *state* holds, not what the holder holds, and is
  // remembered on `state` so it survives the effect restarting. Two things need that:
  //
  //  * It is seeded from `seededNamedValues` — the map the constructor actually received — rather
  //    than from a fresh read of the holder. A host that writes between the state's construction
  //    and this effect starting would otherwise make the first emission compare equal to a value
  //    the state never saw, and the player would stay stale until that entry moved again.
  //  * The effect is keyed on the holder's identity as well as `state`, so a parent that swaps in a
  //    *different* `SnapshotStateMap` for the same document is followed instead of leaving the
  //    collector subscribed to the detached old one. Restarting the collector does not rebuild
  //    `RcPlayerState`, which is the whole point of this bridge; because `appliedNamedValues`
  //    outlives the restart, the new holder is diffed against what the state really has, so
  //    entries the old holder had and the new one does not are cleared rather than left applied.
  val appliedNamedValues = remember(state) { seededNamedValues.toMutableMap() }
  LaunchedEffect(state, namedValues) {
    snapshotFlow { namedValues.toMap() }
      .collect { current ->
        if (current == appliedNamedValues) return@collect
        (appliedNamedValues.keys - current.keys).forEach(state::clearNamedValue)
        current.forEach { (name, value) ->
          if (appliedNamedValues[name] != value) state.setNamedValue(name, value)
        }
        appliedNamedValues.clear()
        appliedNamedValues.putAll(current)
        // `setNamedValue` writes into the state's maps without going through the action path, so
        // nothing else tells the draw layer a value moved.
        invalidationVersion += 1
      }
  }
  val needsContinuousFrames =
    remember(document) {
      document.operations.filterIsInstance<RcFloatExpression>().any { it.animation != null } ||
        document.operations.any {
          it is RcMarqueeModifier || (it is RcTimeAttribute && it.type.requiresContinuousFrames)
        }
    }
  var frameNanos by remember { mutableLongStateOf(0L) }
  var frameOriginNanos by remember(document) { mutableLongStateOf(Long.MIN_VALUE) }
  val recordFrame: (Long) -> Unit = { nanos ->
    if (frameOriginNanos == Long.MIN_VALUE) frameOriginNanos = nanos
    frameNanos = nanos - frameOriginNanos
  }
  LaunchedEffect(document) { withFrameNanos(recordFrame) }
  LaunchedEffect(needsContinuousFrames) {
    if (needsContinuousFrames) {
      while (true) {
        withFrameNanos(recordFrame)
      }
    }
  }
  LaunchedEffect(nextFrameRequestVersion) {
    if (nextFrameRequestVersion > 0) {
      withFrameNanos(recordFrame)
      invalidationVersion += 1
    }
  }
  LaunchedEffect(wakeIntervalSeconds) {
    val seconds = wakeIntervalSeconds ?: return@LaunchedEffect
    val delayMillis =
      if (!seconds.isFinite()) Int.MAX_VALUE.toLong()
      else (seconds * 1_000f).toLong().coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())
    if (delayMillis > 0L) delay(delayMillis)
    withFrameNanos(recordFrame)
    wakeIntervalSeconds = null
    invalidationVersion += 1
  }
  val linkedDocument = remember(document) { RcDocumentLinker.link(document) }
  val layout = remember(linkedDocument) { RcLayoutTree.build(linkedDocument) }
  LaunchedEffect(linkedDocument, layout) {
    // Layout rendering consumes paint operations through component content rather than walking
    // the document root. AndroidX still applies root-level diagnostics during document execution.
    if (layout != null) {
      linkedDocument.operations.forEach { node ->
        ((node as? RcLinkedNode.Operation)?.operation as? RcDebugMessage)?.let(
          state::emitDebugMessage
        )
      }
    }
  }
  val images = remember(document) { decodeInlineImages(document) }
  val fonts = remember(document) { decodeInlineFonts(document) }
  val textMeasurer = rememberTextMeasurer()
  // Subscribe *composition* to invalidations, not just the draw layer below.
  //
  // `rootContentDescription` and the legacy click areas are read here, during composition, and both
  // can be backed by a named text — so a host write, or a document action, changes what a screen
  // reader should announce. Every such mutation raises `invalidationVersion`, but until this read
  // existed the only composition that observed it was the `layout != null` branch further down; on
  // a legacy canvas document `invalidationVersion` was read solely inside a `drawWithContent`
  // lambda, which redraws without recomposing. The label went stale while the pixels were right —
  // invisible to a pixel test, which is why `RcNamedValueSemanticsTest` asserts through the
  // semantics tree.
  //
  // It also fixes an ordering hazard that has nothing to do with accessibility: when the *host*
  // swaps in a different named-value holder, the recomposition that swap triggers runs before the
  // bridge below has applied the new values, so a composition-time read of player state would
  // otherwise show the previous holder's overrides and never be revisited.
  //
  // Cheap, because `invalidationVersion` is event-driven — actions, wake-ins, next-frame requests,
  // host writes — and not bumped per frame; continuous animation drives `frameNanos` instead.
  invalidationVersion
  val semanticsModifier =
    state.rootContentDescription?.let { description ->
      modifier.semantics { contentDescription = description }
    } ?: modifier
  val interactiveModifier =
    semanticsModifier.applyAndroidXClickAreas(
      areas = state.clickAreas,
      state = state,
      documentWidth = document.header.width.coerceAtLeast(1).toFloat(),
      documentHeight = document.header.height.coerceAtLeast(1).toFloat(),
      rootContentBehavior = state.rootContentBehavior,
    )
  val redrawModifier = interactiveModifier.drawWithContent {
    invalidationVersion // Subscribe the draw layer to action and WakeIn invalidations.
    drawContent()
  }
  if (layout != null) {
    // Layout variables (visibility, dimensions, offsets, and constraints) are read during
    // composition/measurement rather than painting, so action mutations must invalidate this
    // branch as well as the draw layer.
    invalidationVersion
    state.beginFrame(frameNanos / 1_000_000_000f)
    // beginFrame resets derived text to the document's literals, so the ids the layout's own data
    // operations publish must be recomputed before this same composition measures and draws.
    state.applyLayoutContentStateOperations(linkedDocument.operations, theme)
    LookaheadScope {
      CompositionLocalProvider(
        LocalRcLookaheadScope provides this,
        LocalRcLayoutVersion provides invalidationVersion,
        LocalRcFonts provides fonts,
        LocalRcTypefaces provides typefaces,
      ) {
        RenderLayoutNode(
          node = layout,
          modifier = redrawModifier,
          state = state,
          textMeasurer = textMeasurer,
          images = images,
          theme = theme,
        )
      }
    }
  } else
    Canvas(redrawModifier) {
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
        rcTrace(RcTraceCategory.FRAME, "rc:drawRoot") {
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
}

@Composable
private fun RenderLayoutNode(
  node: RcLayoutNode,
  modifier: Modifier = Modifier,
  forceGone: Boolean = false,
  state: RcPlayerState,
  textMeasurer: TextMeasurer,
  images: Map<Int, ImageBitmap>,
  theme: Int,
) {
  val layoutVersion = LocalRcLayoutVersion.current
  val lookaheadScope = LocalRcLookaheadScope.current
  val fontFamilies = LocalRcFonts.current
  val typefaces = LocalRcTypefaces.current
  val visibility =
    if (forceGone) {
      0
    } else if (layoutVersion == Int.MIN_VALUE) {
      error("unreachable layout invalidation version")
    } else {
      node.modifiers.visibility?.let { androidXVisibility(state.integer(it.visibilityId) ?: 0) }
        ?: 1
    }
  val boundsModifier =
    if (node is RcLayoutNode.Content || lookaheadScope == null) {
      modifier
    } else {
      modifier.animateRcBounds(
        lookaheadScope,
        node.modifiers.animationSpec ?: DefaultRcAnimationSpec,
      )
    }
  val animatedVisibility =
    if (node is RcLayoutNode.Content) {
      RcAnimatedVisibility(visibility != 0, if (visibility == 2) modifier.alpha(0f) else modifier)
    } else {
      animateRcVisibility(visibility, node.modifiers.animationSpec, boundsModifier)
    }
  val geometryIds = node.geometryComponentIds()
  if (!animatedVisibility.shouldRender) {
    if (geometryIds.any(state::hasComponentValues)) {
      Layout(Modifier.trackComponentGeometry(geometryIds, state)) { _, _ -> layout(0, 0) {} }
    }
    return
  }
  val effectiveModifier = animatedVisibility.modifier.trackComponentGeometry(geometryIds, state)
  when (node) {
    is RcLayoutNode.Root ->
      Box(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          geometryIds,
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
          geometryIds,
          fillMissingDimensions = true,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        )
      ) {
        node.content
          ?.operations
          ?.takeIf { it.isNotEmpty() }
          ?.let { operations ->
            Canvas(Modifier.fillMaxSize()) {
              rcTrace(RcTraceCategory.FRAME, "rc:drawCanvas") {
                drawOperations(
                  operations,
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
        rcTrace(RcTraceCategory.FRAME, "rc:drawCanvas") {
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
      }
    is RcLayoutNode.Box ->
      Box(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          geometryIds,
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
      val spacingDp = state.dpTypedDp(state.resolve(node.operation.spacedBy), density)
      val spacing = with(density) { spacingDp.roundToPx() }
      val rowModifier =
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          geometryIds,
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
        val hasWeightedChildren =
          node.content.children.any { child ->
            child.modifiers.width?.type == RcDimensionType.WEIGHT &&
              child.modifiers.visibility?.let {
                androidXVisibility(state.integer(it.visibilityId) ?: 0) != 0
              } != false
          }
        Row(
          rowModifier,
          horizontalArrangement =
            RcHorizontalArrangement(
              node.operation.horizontalPositioning,
              visualSpacing = spacingDp,
              // AndroidX measures weighted children from all remaining row space, then adds the
              // configured gaps while positioning. Compose normally reserves those gaps before
              // distributing weight, which makes every weighted child too narrow.
              spacing = if (hasWeightedChildren) 0.dp else spacingDp,
            ),
          verticalAlignment = rowAlignment(node.operation.verticalPositioning),
        ) {
          node.content.children.forEach { child ->
            RenderLayoutNode(
              child,
              modifier = rowWeightModifier(child, state),
              state = state,
              textMeasurer = textMeasurer,
              images = images,
              theme = theme,
            )
          }
        }
      }
    }
    is RcLayoutNode.Column -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val spacing = state.dpTypedDp(state.resolve(node.operation.spacedBy), density)
      Column(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          geometryIds,
          fillMissingDimensions = false,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        ),
        verticalArrangement = RcVerticalArrangement(node.operation.verticalPositioning, spacing),
        horizontalAlignment = columnAlignment(node.operation.horizontalPositioning),
      ) {
        node.content.children.forEach { child ->
          RenderLayoutNode(
            child,
            modifier = columnWeightModifier(child, state),
            state = state,
            textMeasurer = textMeasurer,
            images = images,
            theme = theme,
          )
        }
      }
    }
    is RcLayoutNode.Flow -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val spacing = state.dpTypedDp(state.resolve(node.operation.spacedBy), density)
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          geometryIds,
          fillMissingDimensions = false,
          node.canvasOperations,
          textMeasurer,
          images,
          theme,
        ),
        horizontalArrangement =
          RcHorizontalArrangement(node.operation.horizontalPositioning, spacing),
        verticalArrangement = RcVerticalArrangement(node.operation.verticalPositioning, 0.dp),
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
    is RcLayoutNode.State -> {
      val selected = state.integer(node.operation.indexId) ?: 0
      val contentVisibility =
        node.content.modifiers.visibility?.let {
          androidXVisibility(state.integer(it.visibilityId) ?: 0)
        } ?: 1
      Box(
        effectiveModifier.applyComponentModifiers(
          node.modifiers,
          state,
          geometryIds,
          fillMissingDimensions = false,
          canvasOperations = null,
          textMeasurer,
          images,
          theme,
        )
      ) {
        val renderChildren: @Composable () -> Unit = {
          node.content.children.forEachIndexed { index, child ->
            if (index == selected && contentVisibility != 0) {
              key(child.componentId) {
                RenderLayoutNode(
                  child,
                  state = state,
                  textMeasurer = textMeasurer,
                  images = images,
                  theme = theme,
                )
              }
            } else {
              RenderLayoutNode(
                child,
                forceGone = true,
                state = state,
                textMeasurer = textMeasurer,
                images = images,
                theme = theme,
              )
            }
          }
        }
        if (contentVisibility == 0) {
          Layout(Modifier.trackComponentGeometry(listOf(node.content.componentId), state)) { _, _ ->
            layout(0, 0) {}
          }
          renderChildren()
        } else {
          Box(Modifier.trackComponentGeometry(listOf(node.content.componentId), state)) {
            renderChildren()
          }
        }
      }
    }
    is RcLayoutNode.CollapsibleRow -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      RcCollapsibleLayout(
        children = node.content.children,
        orientation = RcCollapseOrientation.Horizontal,
        mainPositioning = node.operation.horizontalPositioning,
        crossPositioning = node.operation.verticalPositioning,
        spacing = state.dpTypedPixels(state.resolve(node.operation.spacedBy), density).roundToInt(),
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            geometryIds,
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
        spacing = state.dpTypedPixels(state.resolve(node.operation.spacedBy), density).roundToInt(),
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            geometryIds,
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
          geometryIds,
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
            geometryIds,
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
            fontFamily = resolveFontFamily(operation.fontFamilyId, state, fontFamilies, typefaces),
            textAlign = operation.composeTextAlign(),
          ),
        overflow = operation.composeTextOverflow(),
        maxLines = androidXMaxLines(operation.overflow, operation.maxLines),
      )
    }
    is RcLayoutNode.CoreText -> {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val properties = node.resolvedStyle
      val fontSize = state.resolve(properties.floatProperty(5, 36f))
      val lineHeightAdd = state.resolve(properties.floatProperty(13, 0f))
      val lineHeightMultiplier = state.resolve(properties.floatProperty(14, 1f))
      val fontStyle = properties.intProperty(6, 0)
      val fontWeight =
        state.resolve(properties.floatProperty(7, 400f)).roundToInt().coerceIn(1, 1000)
      val boldWeight = if (fontStyle and 1 != 0) 700 else fontWeight
      val colorId = properties.intProperty(4, -1)
      val autosize = properties.booleanProperty(22, false)
      val minFontSize = properties.floatProperty(25, -1f).let(state::resolve)
      val maxFontSize = properties.floatProperty(26, -1f).let(state::resolve)
      val resolvedMaxFontSize = if (maxFontSize > 0f) maxFontSize else 400f
      val resolvedMinFontSize =
        minOf(if (minFontSize > 0f) minFontSize else 4f, resolvedMaxFontSize)
      // Font-variation axes (properties 20/21) — a variable font's `wght` / `wdth` / … instance.
      // The tags arrive as text ids and the values may be document floats, so both are resolved
      // through the player state before they are paired up.
      val variationSettings =
        fontVariationSettings(
          axisTags = properties.intArrayProperty(CORE_TEXT_FONT_AXIS_TAGS).map { state.text(it) },
          axisValues =
            properties.floatArrayProperty(CORE_TEXT_FONT_AXIS_VALUES).map { state.resolve(it) },
        )
      BasicText(
        text = state.text(node.operation.textId).orEmpty(),
        modifier =
          effectiveModifier.applyComponentModifiers(
            node.modifiers,
            state,
            geometryIds,
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
            fontSize = with(density) { fontSize.toSp() },
            letterSpacing =
              with(density) { state.resolve(properties.floatProperty(12, 0f)).toSp() },
            lineHeight =
              if (lineHeightAdd == 0f && lineHeightMultiplier == 1f) TextUnit.Unspecified
              else if (autosize)
                (lineHeightMultiplier + lineHeightAdd / fontSize.coerceAtLeast(0.0001f)).em
              else with(density) { (fontSize * lineHeightMultiplier + lineHeightAdd).toSp() },
            fontWeight = FontWeight(boldWeight),
            fontStyle = if (fontStyle and 2 != 0) FontStyle.Italic else FontStyle.Normal,
            fontFamily =
              resolveFontFamily(
                properties.intProperty(8, -1),
                state,
                fontFamilies,
                typefaces,
                withWeightAxis(variationSettings, boldWeight),
              ),
            textAlign =
              if (properties.intProperty(17, 0) == 1) TextAlign.Justify
              else androidXTextAlign(properties.intProperty(9, RcTextLayout.ALIGN_LEFT)),
            lineBreak =
              when (properties.intProperty(15, 0)) {
                1 -> LineBreak.Paragraph
                2 -> LineBreak.Heading
                else -> LineBreak.Simple
              },
            hyphens = if (properties.intProperty(16, 0) > 0) Hyphens.Auto else Hyphens.None,
            textDecoration =
              when {
                properties.booleanProperty(18, false) && properties.booleanProperty(19, false) ->
                  TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                  )
                properties.booleanProperty(18, false) -> TextDecoration.Underline
                properties.booleanProperty(19, false) -> TextDecoration.LineThrough
                else -> TextDecoration.None
              },
          ),
        overflow = androidXTextOverflow(properties.intProperty(10, RcTextLayout.OVERFLOW_CLIP)),
        maxLines =
          androidXMaxLines(
            properties.intProperty(10, RcTextLayout.OVERFLOW_CLIP),
            properties.intProperty(11, Int.MAX_VALUE),
          ),
        autoSize =
          if (autosize)
            TextAutoSize.StepBased(
              minFontSize = with(density) { resolvedMinFontSize.toSp() },
              maxFontSize = with(density) { resolvedMaxFontSize.toSp() },
              stepSize = with(density) { 0.5f.toSp() },
            )
          else null,
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
            geometryIds,
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

private data class RcAnimatedVisibility(val shouldRender: Boolean, val modifier: Modifier)

private val LocalRcLookaheadScope = compositionLocalOf<LookaheadScope?> { null }
private val LocalRcLayoutVersion = compositionLocalOf { 0 }
private val LocalRcFonts = compositionLocalOf<Map<Int, FontFamily>> { emptyMap() }
private val LocalRcTypefaces = compositionLocalOf<RcTypefaceLoader> { RcTypefaceLoader.Empty }

private val DefaultRcAnimationSpec =
  RcAnimationSpec(
    animationId = -1,
    motionDurationMillis = RcFloatWord.literal(300f),
    motionEasingType = 1,
    visibilityDurationMillis = RcFloatWord.literal(300f),
    visibilityEasingType = 1,
    enterAnimation = RcLayoutAnimation.FadeIn,
    exitAnimation = RcLayoutAnimation.FadeOut,
  )

@Composable
private fun animateRcVisibility(
  targetVisibility: Int,
  operation: RcAnimationSpec?,
  modifier: Modifier,
): RcAnimatedVisibility {
  val spec = operation ?: DefaultRcAnimationSpec
  val maxDurationMillis =
    maxOf(spec.motionDurationMillis.value, spec.visibilityDurationMillis.value).takeIf {
      it.isFinite() && it > 0f
    } ?: 0f
  val timeline = remember(spec) { RcAnimationTimeline(spec) }
  val elapsedMillis = remember(spec) { Animatable(maxDurationMillis) }
  var previousVisibility by remember(spec) { mutableIntStateOf(targetVisibility) }
  var animationTarget by remember(spec) { mutableIntStateOf(targetVisibility) }
  val pending = animationTarget != targetVisibility
  val fromVisibility = if (pending) animationTarget else previousVisibility
  val elapsed = if (pending) 0f else elapsedMillis.value
  val progress = timeline.progress(elapsed)
  val entering = fromVisibility == 0 && targetVisibility == 1
  val exiting = fromVisibility == 1 && targetVisibility == 0
  val transitioning = (pending || !progress.isDone) && (entering || exiting)

  LaunchedEffect(spec, targetVisibility) {
    if (animationTarget == targetVisibility) return@LaunchedEffect
    previousVisibility = animationTarget
    animationTarget = targetVisibility
    elapsedMillis.snapTo(0f)
    if (spec.isEnabled && maxDurationMillis > 0f) {
      elapsedMillis.animateTo(
        maxDurationMillis,
        tween(maxDurationMillis.roundToInt(), easing = LinearEasing),
      )
    } else {
      elapsedMillis.snapTo(maxDurationMillis)
    }
  }

  // AndroidX INVISIBLE participates in measure/layout exactly like VISIBLE, but skips paint.
  // It does not run the GONE visibility transition.
  if (targetVisibility == 2) return RcAnimatedVisibility(true, modifier.alpha(0f))

  val shouldRender =
    when (targetVisibility) {
      1 -> true
      else -> transitioning && exiting
    }
  if (!shouldRender) return RcAnimatedVisibility(false, modifier)
  if (!transitioning || !spec.isEnabled) return RcAnimatedVisibility(true, modifier)

  val transform = spec.visibilityTransform(entering, progress.visibility)
  val transformed =
    modifier
      .graphicsLayer {
        alpha = if (transform.paintsContent) transform.alpha else 0f
        scaleX = transform.scale
        scaleY = transform.scale
        rotationZ = transform.rotationDegrees
        transformOrigin = TransformOrigin.Center
      }
      .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val parentWidth =
          if (constraints.maxWidth == androidx.compose.ui.unit.Constraints.Infinity) {
            placeable.width
          } else {
            constraints.maxWidth
          }
        val parentHeight =
          if (constraints.maxHeight == androidx.compose.ui.unit.Constraints.Infinity) {
            placeable.height
          } else {
            constraints.maxHeight
          }
        val x = (transform.translationX * parentWidth).roundToInt()
        val y = (transform.translationY * parentHeight).roundToInt()
        layout(
          width = if (exiting && isLookingAhead) 0 else placeable.width,
          height = if (exiting && isLookingAhead) 0 else placeable.height,
        ) {
          placeable.placeRelative(x, y)
        }
      }
  return RcAnimatedVisibility(true, transformed)
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
    // AndroidPaintContext maps this alignment field to ALIGN_NORMAL. CoreText's property 17 is
    // the independent switch that enables inter-word/inter-character justification.
    RcTextLayout.ALIGN_JUSTIFY -> TextAlign.Start
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
    RcTextLayout.OVERFLOW_START_ELLIPSIS -> TextOverflow.StartEllipsis
    RcTextLayout.OVERFLOW_MIDDLE_ELLIPSIS -> TextOverflow.MiddleEllipsis
    else -> error("Unsupported AndroidX text overflow $value")
  }

private fun androidXMaxLines(overflow: Int, maxLines: Int): Int =
  if (
    maxLines > 1 &&
      (overflow == RcTextLayout.OVERFLOW_CLIP || overflow == RcTextLayout.OVERFLOW_VISIBLE)
  ) {
    Int.MAX_VALUE
  } else {
    maxLines
  }

private fun List<RcTextStyleProperty>.intProperty(id: Int, default: Int): Int =
  filterIsInstance<RcTextStyleProperty.IntValue>().lastOrNull { it.id == id }?.value ?: default

private fun List<RcTextStyleProperty>.floatProperty(id: Int, default: Float): RcFloatWord =
  filterIsInstance<RcTextStyleProperty.FloatValue>().lastOrNull { it.id == id }?.value
    ?: RcFloatWord.literal(default)

private fun List<RcTextStyleProperty>.booleanProperty(id: Int, default: Boolean): Boolean =
  filterIsInstance<RcTextStyleProperty.BooleanValue>().lastOrNull { it.id == id }?.value ?: default

private fun List<RcTextStyleProperty>.intArrayProperty(id: Int): List<Int> =
  filterIsInstance<RcTextStyleProperty.IntArrayValue>().lastOrNull { it.id == id }?.values.orEmpty()

private fun List<RcTextStyleProperty>.floatArrayProperty(id: Int): List<RcFloatWord> =
  filterIsInstance<RcTextStyleProperty.FloatArrayValue>()
    .lastOrNull { it.id == id }
    ?.values
    .orEmpty()

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

private class RcHorizontalArrangement(
  private val positioning: Int,
  private val visualSpacing: Dp,
  override val spacing: Dp = visualSpacing,
) : Arrangement.Horizontal {
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
      visualSpacing.roundToPx(),
      reverse = layoutDirection == LayoutDirection.Rtl,
      outPositions = outPositions,
    )
  }
}

private class RcVerticalArrangement(private val positioning: Int, override val spacing: Dp) :
  Arrangement.Vertical {
  override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
    arrangeLinear(
      totalSize,
      sizes,
      positioning,
      spacing.roundToPx(),
      reverse = false,
      outPositions = outPositions,
    )
  }
}

/** AndroidX fields that are pixels in LEGACY/PIXELS documents and dp in DP documents. */
internal fun rcDpTypedPixels(value: Float, density: Float, densityBehavior: Int): Float =
  if (densityBehavior == RcHeader.DENSITY_BEHAVIOR_DP) value * density else value

private fun RcPlayerState.dpTypedPixels(value: Float, density: Density): Float =
  rcDpTypedPixels(value, density.density, document.header.densityBehavior)

private fun RcPlayerState.dpTypedDp(value: Float, density: Density): Dp =
  with(density) { dpTypedPixels(value, density).toDp() }

/** DimensionIn is the exception: AndroidX treats LEGACY and DP as dp, PIXELS as pixels. */
internal fun rcDimensionConstraintDp(value: Float, density: Float, densityBehavior: Int): Float =
  if (densityBehavior == RcHeader.DENSITY_BEHAVIOR_PIXELS) value / density else value

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
  geometryComponentIds: List<Int>,
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
  if (fillMissingDimensions && modifiers.width == null) result = result.fillMaxWidth()
  if (fillMissingDimensions && modifiers.height == null) result = result.fillMaxHeight()
  var appliedWidth = false
  var appliedHeight = false
  var appliedCanvasOperations = false
  fun applyCanvasOperations(modifier: Modifier): Modifier {
    val operations = canvasOperations ?: return modifier
    appliedCanvasOperations = true
    return modifier.drawWithContent {
      rcTrace(RcTraceCategory.FRAME, "rc:drawCanvas") {
        drawOperations(
          operations,
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
  }
  var scrollApplied = false
  var operationIndex = 0
  while (operationIndex < modifiers.ordered.size) {
    if (modifiers.scrollPosition == operationIndex) {
      modifiers.scroll?.let {
        result = result.applyAndroidXScroll(it, state, geometryComponentIds)
        scrollApplied = true
      }
    }
    val operation = modifiers.ordered[operationIndex]
    // AndroidX paints CanvasOperations at the component's full bounds, temporarily undoing the
    // content-padding inset. Put the Compose draw wrapper outside the first padding modifier even
    // when the wire DrawContent marker follows it.
    if (operation is RcPaddingModifier && !appliedCanvasOperations) {
      result = applyCanvasOperations(result)
    }
    result =
      when (operation) {
        is RcWidthModifier ->
          if (appliedWidth) result
          else {
            appliedWidth = true
            result.applyWidth(operation, state, density)
          }
        is RcHeightModifier ->
          if (appliedHeight) result
          else {
            appliedHeight = true
            result.applyHeight(operation, state, density)
          }
        is RcPaddingModifier -> {
          var left = 0f
          var top = 0f
          var right = 0f
          var bottom = 0f
          var next = operationIndex
          while (next < modifiers.ordered.size) {
            if (next > operationIndex && modifiers.scrollPosition == next) break
            val padding = modifiers.ordered[next] as? RcPaddingModifier ?: break
            left += state.dpTypedPixels(state.resolve(padding.left), density)
            top += state.dpTypedPixels(state.resolve(padding.top), density)
            right += state.dpTypedPixels(state.resolve(padding.right), density)
            bottom += state.dpTypedPixels(state.resolve(padding.bottom), density)
            next++
          }
          operationIndex = next - 1
          result.rcPaddingPixels(left = left, top = top, right = right, bottom = bottom)
        }
        is RcOffsetModifier ->
          result.offset {
            IntOffset(
              state.dpTypedPixels(state.resolve(operation.x), density).roundToInt(),
              state.dpTypedPixels(state.resolve(operation.y), density).roundToInt(),
            )
          }
        is RcZIndexModifier -> result.zIndex(state.resolve(operation.value))
        is RcBackgroundModifier,
        is RcBorderModifier,
        is RcClipRectModifier,
        is RcRoundedClipRectModifier,
        is RcRippleModifier -> result.applyPaintDecorator(operation, state)
        is RcGraphicsLayerModifier ->
          if (operation == modifiers.graphicsLayer) result.applyGraphicsLayer(operation, state)
          else result
        is RcMarqueeModifier -> result.applyAndroidXMarquee(operation, state)
        is RcNoArg ->
          if (
            operation.opcode == RcOpcodes.MODIFIER_DRAW_CONTENT &&
              !appliedCanvasOperations &&
              modifiers.ordered.drop(operationIndex + 1).none { it is RcPaddingModifier }
          ) {
            applyCanvasOperations(result)
          } else result
        else -> result
      }
    operationIndex++
  }
  if (!scrollApplied) {
    modifiers.scroll?.let { result = result.applyAndroidXScroll(it, state, geometryComponentIds) }
  }
  if (!appliedCanvasOperations) {
    result = applyCanvasOperations(result)
  }
  if (modifiers.clicks.any { it.type != RcClickActionType.CLICK }) {
    result = result.applyAndroidXMultiClick(modifiers.clicks, state)
  } else if (modifiers.clicks.isNotEmpty()) {
    result = result.clickable { modifiers.clicks.forEach(state::executeClick) }
  }
  if (modifiers.touchActions.isNotEmpty()) {
    result = result.applyAndroidXTouchActions(modifiers, state)
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

/**
 * AndroidX keeps padding in physical pixels until measure, then rounds the combined inset on each
 * axis. Compose's Dp padding rounds each edge independently, which adds a pixel whenever both wire
 * edges end in .5 (for example 31.5px + 31.5px must occupy 63px, not 64px).
 */
private fun Modifier.rcPaddingPixels(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
): Modifier = layout { measurable, constraints ->
  val safeLeft = left.coerceAtLeast(0f)
  val safeTop = top.coerceAtLeast(0f)
  val horizontal = rcCombinedPaddingPixels(left, right)
  val vertical = rcCombinedPaddingPixels(top, bottom)
  val placeable =
    measurable.measure(constraints.offset(horizontal = -horizontal, vertical = -vertical))
  val width = constraints.constrainWidth(placeable.width + horizontal)
  val height = constraints.constrainHeight(placeable.height + vertical)
  layout(width, height) { placeable.placeRelative(safeLeft.roundToInt(), safeTop.roundToInt()) }
}

internal fun rcCombinedPaddingPixels(first: Float, second: Float): Int =
  (first.coerceAtLeast(0f) + second.coerceAtLeast(0f)).roundToInt()

private fun Modifier.applyAndroidXClickAreas(
  areas: List<RcClickArea>,
  state: RcPlayerState,
  documentWidth: Float,
  documentHeight: Float,
  rootContentBehavior: RcRootContentBehavior?,
): Modifier =
  if (areas.isEmpty()) this
  else then(RcClickAreasElement(areas, state, documentWidth, documentHeight, rootContentBehavior))

private data class RcClickAreasElement(
  val areas: List<RcClickArea>,
  val state: RcPlayerState,
  val documentWidth: Float,
  val documentHeight: Float,
  val rootContentBehavior: RcRootContentBehavior?,
) : ModifierNodeElement<RcClickAreasNode>() {
  override fun create(): RcClickAreasNode =
    RcClickAreasNode(areas, state, documentWidth, documentHeight, rootContentBehavior)

  override fun update(node: RcClickAreasNode) {
    node.areas = areas
    node.state = state
    node.documentWidth = documentWidth
    node.documentHeight = documentHeight
    node.rootContentBehavior = rootContentBehavior
    node.invalidateSemantics()
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "androidXClickAreas"
  }
}

private class RcClickAreasNode(
  var areas: List<RcClickArea>,
  var state: RcPlayerState,
  var documentWidth: Float,
  var documentHeight: Float,
  var rootContentBehavior: RcRootContentBehavior?,
) :
  Modifier.Node(),
  PointerInputModifierNode,
  SemanticsModifierNode,
  CompositionLocalConsumerModifierNode {
  private var pressed = false
  private var longPressed = false
  private var downPosition = Offset.Zero
  private var pendingClickPosition = Offset.Zero
  private var waitingForSecondClick = false
  private var longPressJob: Job? = null
  private var singleClickJob: Job? = null

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
    if (pass != PointerEventPass.Main) return
    val down = pointerEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
    if (!pressed && down != null) {
      pressed = true
      longPressed = false
      downPosition = down.position
      longPressJob?.cancel()
      longPressJob = coroutineScope.launch {
        delay(currentValueOf(LocalViewConfiguration).longPressTimeoutMillis)
        if (pressed) {
          pressed = false
          longPressed = true
          waitingForSecondClick = false
          singleClickJob?.cancel()
        }
      }
    }
    if (
      pressed &&
        pointerEvent.changes.any {
          it.pressed &&
            (it.position - downPosition).getDistance() >
              currentValueOf(LocalViewConfiguration).touchSlop
        }
    ) {
      pressed = false
      longPressJob?.cancel()
    }
    if (pressed && pointerEvent.changes.isNotEmpty() && pointerEvent.changes.all { !it.pressed }) {
      pressed = false
      longPressJob?.cancel()
      val up = pointerEvent.changes.firstOrNull { it.changedToUpIgnoreConsumed() }
      if (!longPressed && up != null) completeClick(up.position, bounds)
    }
  }

  override fun onCancelPointerInput() {
    pressed = false
    longPressJob?.cancel()
  }

  override fun onDetach() {
    pressed = false
    waitingForSecondClick = false
    longPressJob?.cancel()
    singleClickJob?.cancel()
  }

  override fun SemanticsPropertyReceiver.applySemantics() {
    val descriptions = areas.mapNotNull { state.text(it.contentDescriptionId) }
    if (descriptions.isNotEmpty()) contentDescription = descriptions.joinToString(", ")
    role = Role.Button
    areas.firstOrNull()?.let { first ->
      onClick {
        state.executeClickArea(first)
        true
      }
    }
    customActions = areas.map { area ->
      CustomAccessibilityAction(
        label = state.text(area.contentDescriptionId).orEmpty(),
        action = {
          state.executeClickArea(area)
          true
        },
      )
    }
  }

  private fun completeClick(position: Offset, bounds: IntSize) {
    if (waitingForSecondClick) {
      waitingForSecondClick = false
      singleClickJob?.cancel()
      return
    }
    waitingForSecondClick = true
    val transform =
      computeRootTransform(
        documentWidth = documentWidth,
        documentHeight = documentHeight,
        viewportWidth = bounds.width.toFloat(),
        viewportHeight = bounds.height.toFloat(),
        behavior = rootContentBehavior,
      )
    pendingClickPosition =
      Offset(
        (position.x - transform.translateX) / transform.scaleX,
        (position.y - transform.translateY) / transform.scaleY,
      )
    singleClickJob = coroutineScope.launch {
      delay(currentValueOf(LocalViewConfiguration).doubleTapTimeoutMillis)
      if (waitingForSecondClick) {
        waitingForSecondClick = false
        state.executeClickAreasAt(pendingClickPosition.x, pendingClickPosition.y)
      }
    }
  }
}

/** AndroidX player-view's 21-entry haptic table mapped to the portable CMP vocabulary. */
internal fun HapticFeedback.performAndroidXHaptic(type: RcHapticType) {
  val composeType =
    when (type.wireValue % 21) {
      0 -> null
      1 -> HapticFeedbackType.LongPress
      2 -> HapticFeedbackType.VirtualKey
      3 -> HapticFeedbackType.KeyboardTap
      4 -> HapticFeedbackType.SegmentTick
      5 -> HapticFeedbackType.ContextClick
      6,
      7 -> HapticFeedbackType.KeyboardTap
      8 -> HapticFeedbackType.VirtualKey
      9 -> HapticFeedbackType.TextHandleMove
      10 -> HapticFeedbackType.GestureThresholdActivate
      11 -> HapticFeedbackType.GestureEnd
      12 -> HapticFeedbackType.Confirm
      13 -> HapticFeedbackType.Reject
      14 -> HapticFeedbackType.ToggleOn
      15 -> HapticFeedbackType.ToggleOff
      16 -> HapticFeedbackType.GestureThresholdActivate
      17 -> HapticFeedbackType.GestureEnd
      18 -> HapticFeedbackType.GestureThresholdActivate
      19 -> HapticFeedbackType.SegmentTick
      20 -> HapticFeedbackType.SegmentFrequentTick
      else -> error("Negative AndroidX haptic type ${type.wireValue}")
    }
  if (composeType != null) performHapticFeedback(composeType)
}

@Composable
private fun Modifier.applyAndroidXMultiClick(
  blocks: List<RcClickActionBlock>,
  state: RcPlayerState,
): Modifier {
  val hapticFeedback = LocalHapticFeedback.current
  return then(RcMultiClickElement(blocks, state, hapticFeedback))
}

private data class RcMultiClickElement(
  val blocks: List<RcClickActionBlock>,
  val state: RcPlayerState,
  val hapticFeedback: HapticFeedback,
) : ModifierNodeElement<RcMultiClickNode>() {
  override fun create(): RcMultiClickNode = RcMultiClickNode(blocks, state, hapticFeedback)

  override fun update(node: RcMultiClickNode) {
    node.blocks = blocks
    node.state = state
    node.hapticFeedback = hapticFeedback
    node.invalidateSemantics()
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "androidXMultiClick"
  }
}

private class RcMultiClickNode(
  var blocks: List<RcClickActionBlock>,
  var state: RcPlayerState,
  var hapticFeedback: HapticFeedback,
) :
  Modifier.Node(),
  PointerInputModifierNode,
  DrawModifierNode,
  SemanticsModifierNode,
  CompositionLocalConsumerModifierNode {
  private var pressed = false
  private var longPressDispatched = false
  private var downPosition = Offset.Zero
  private var waitingForSecondClick = false
  private var longPressJob: Job? = null
  private var singleClickJob: Job? = null
  private val rippleColorProgress = Animatable(1f)
  private val rippleRadiusProgress = Animatable(1f)

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
    if (pass != PointerEventPass.Main) return
    val down = pointerEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
    if (!pressed && down != null) {
      pressed = true
      longPressDispatched = false
      downPosition = down.position
      longPressJob?.cancel()
      if (longActions.isNotEmpty()) {
        longPressJob = coroutineScope.launch {
          delay(currentValueOf(LocalViewConfiguration).longPressTimeoutMillis)
          if (pressed) {
            pressed = false
            longPressDispatched = true
            waitingForSecondClick = false
            singleClickJob?.cancel()
            startRipple()
            dispatch(longActions)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
          }
        }
      }
    }
    if (
      pressed &&
        pointerEvent.changes.any {
          it.pressed &&
            (it.position - downPosition).getDistance() >
              currentValueOf(LocalViewConfiguration).touchSlop
        }
    ) {
      pressed = false
      longPressJob?.cancel()
    }
    if (pressed && pointerEvent.changes.isNotEmpty() && pointerEvent.changes.all { !it.pressed }) {
      pressed = false
      longPressJob?.cancel()
      if (!longPressDispatched && pointerEvent.changes.all { it.changedToUpIgnoreConsumed() }) {
        completeClick()
      }
    }
  }

  override fun onCancelPointerInput() {
    pressed = false
    longPressJob?.cancel()
  }

  override fun onDetach() {
    longPressJob?.cancel()
    singleClickJob?.cancel()
    pressed = false
    waitingForSecondClick = false
  }

  override fun ContentDrawScope.draw() {
    drawContent()
    if (rippleColorProgress.value < 1f || rippleRadiusProgress.value < 1f) {
      val color = lerp(Color(0xb4fafafa.toInt()), Color(0x00c8c8c8), rippleColorProgress.value)
      val radius = maxOf(size.width, size.height) * rippleRadiusProgress.value
      clipRect { drawCircle(color = color, radius = radius, center = downPosition) }
    }
  }

  override fun SemanticsPropertyReceiver.applySemantics() {
    role = Role.Button
    onClick {
      startRipple()
      dispatch(singleActions)
      performSingleHaptic()
      true
    }
    if (longActions.isNotEmpty()) {
      onLongClick {
        startRipple()
        dispatch(longActions)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        true
      }
    }
  }

  private fun completeClick() {
    if (doubleActions.isEmpty()) {
      startRipple()
      dispatch(singleActions)
      performSingleHaptic()
    } else if (waitingForSecondClick) {
      waitingForSecondClick = false
      singleClickJob?.cancel()
      startRipple()
      dispatch(doubleActions)
      hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    } else {
      waitingForSecondClick = true
      singleClickJob = coroutineScope.launch {
        delay(currentValueOf(LocalViewConfiguration).doubleTapTimeoutMillis)
        if (waitingForSecondClick) {
          waitingForSecondClick = false
          startRipple()
          dispatch(singleActions)
          performSingleHaptic()
        }
      }
    }
  }

  private fun dispatch(actions: List<RcClickActionBlock>) {
    actions.forEach(state::executeClick)
  }

  private fun performSingleHaptic() {
    if (singleActions.any { it.type == RcClickActionType.SINGLE }) {
      hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    }
  }

  private fun startRipple() {
    val easing = CubicBezierEasing(.4f, 0f, .2f, 1f)
    coroutineScope.launch {
      rippleColorProgress.snapTo(0f)
      rippleColorProgress.animateTo(1f, tween(durationMillis = 1_000, easing = easing))
    }
    coroutineScope.launch {
      rippleRadiusProgress.snapTo(0f)
      rippleRadiusProgress.animateTo(1f, tween(durationMillis = 500, easing = easing))
    }
  }

  private val singleActions: List<RcClickActionBlock>
    get() = blocks.filter {
      it.type == RcClickActionType.CLICK || it.type == RcClickActionType.SINGLE
    }

  private val longActions: List<RcClickActionBlock>
    get() = blocks.filter { it.type == RcClickActionType.LONG }

  private val doubleActions: List<RcClickActionBlock>
    get() = blocks.filter { it.type == RcClickActionType.DOUBLE }
}

private fun Modifier.applyAndroidXTouchActions(
  modifiers: RcLayoutModifiers,
  state: RcPlayerState,
): Modifier = then(RcTouchActionsElement(modifiers.touchActions, state))

private data class RcTouchActionsElement(
  val actions: List<RcTouchActionBlock>,
  val state: RcPlayerState,
) : ModifierNodeElement<RcTouchActionsNode>() {
  override fun create(): RcTouchActionsNode = RcTouchActionsNode(actions, state)

  override fun update(node: RcTouchActionsNode) {
    node.actions = actions
    node.state = state
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "androidXTouchActions"
  }
}

private class RcTouchActionsNode(var actions: List<RcTouchActionBlock>, var state: RcPlayerState) :
  Modifier.Node(), PointerInputModifierNode {
  private var pressed: Boolean = false

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
    if (pass != PointerEventPass.Main) return
    if (!pressed && pointerEvent.changes.any { it.changedToDownIgnoreConsumed() }) {
      pressed = true
      dispatch(RcTouchActionType.DOWN)
    }
    if (pressed && pointerEvent.changes.isNotEmpty() && pointerEvent.changes.all { !it.pressed }) {
      pressed = false
      if (pointerEvent.changes.all { it.changedToUpIgnoreConsumed() }) {
        dispatch(RcTouchActionType.UP)
      } else {
        dispatch(RcTouchActionType.CANCEL)
      }
    }
  }

  override fun onCancelPointerInput() {
    if (pressed) {
      pressed = false
      dispatch(RcTouchActionType.CANCEL)
    }
  }

  private fun dispatch(type: RcTouchActionType) {
    actions.filter { it.type == type }.forEach(state::executeTouch)
  }
}

@Composable
private fun Modifier.applyAndroidXMarquee(
  operation: RcMarqueeModifier,
  state: RcPlayerState,
): Modifier {
  val localDensity = androidx.compose.ui.platform.LocalDensity.current
  val density = localDensity.density
  val timeSeconds = state.animationTimeSeconds
  return clipToBounds().layout { measurable, constraints ->
    val placeable =
      measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
      )
    val viewportWidth =
      if (constraints.maxWidth == androidx.compose.ui.unit.Constraints.Infinity) placeable.width
      else constraints.maxWidth
    val width = constraints.constrainWidth(viewportWidth)
    val height = constraints.constrainHeight(placeable.height)
    val contentWidth = placeable.width + state.dpTypedPixels(operation.spacing.value, localDensity)
    val distance = (contentWidth - width).coerceAtLeast(0f)
    val offset =
      androidXMarqueeOffset(
        overflowDistance = distance,
        density = density,
        velocity = operation.velocity.value,
        initialDelayMillis = operation.initialDelayMillis.value,
        timeSeconds = timeSeconds,
      )
    layout(width, height) { placeable.placeWithLayer(0, 0) { translationX = offset } }
  }
}

@Composable
private fun Modifier.applyAndroidXScroll(
  block: RcScrollBlock,
  state: RcPlayerState,
  geometryComponentIds: List<Int> = emptyList(),
): Modifier {
  val operation = block.operation
  val touch =
    block.children
      .filterIsInstance<RcLinkedNode.Operation>()
      .mapNotNull { it.operation as? ee.schimke.composeai.rcplayer.protocol.RcTouchExpression }
      .singleOrNull()
  val initialPosition = state.resolve(operation.position).takeIf(Float::isFinite)?.roundToInt() ?: 0
  val scrollState = rememberScrollState(initialPosition.coerceAtLeast(0))
  val touchRuntime = touch?.let { remember(it) { RcTouchExpressionRuntime(it) } }

  LaunchedEffect(scrollState, operation, state) {
    snapshotFlow { scrollState.value to scrollState.maxValue }
      .collect { (position, maximum) ->
        operation.position.referencedId?.let { state.setFloat(it, position.toFloat()) }
        operation.max.referencedId?.let { state.setFloat(it, maximum.toFloat()) }
        operation.notchMax.referencedId?.let {
          state.setFloat(it, (maximum + scrollState.viewportSize).toFloat())
        }
        val contentExtent = (maximum + scrollState.viewportSize).toFloat()
        geometryComponentIds.forEach { componentId ->
          if (operation.direction == RcScrollModifier.VERTICAL) {
            state.publishComponentContentSize(componentId, height = contentExtent)
          } else {
            state.publishComponentContentSize(componentId, width = contentExtent)
          }
        }
      }
  }
  LaunchedEffect(scrollState, touchRuntime, touch, state) {
    var wasScrolling = false
    snapshotFlow { scrollState.isScrollInProgress }
      .collect { scrolling ->
        if (scrolling) {
          wasScrolling = true
        } else if (wasScrolling && touchRuntime != null) {
          wasScrolling = false
          val target =
            touchRuntime
              .stopTarget(
                currentValue = scrollState.value.toFloat(),
                minimum = 0f,
                maximum = scrollState.maxValue.toFloat(),
                resolve = state::resolve,
              )
              .roundToInt()
              .coerceIn(0, scrollState.maxValue)
          if (target != scrollState.value) scrollState.animateScrollTo(target)
        }
      }
  }

  return if (operation.direction == RcScrollModifier.VERTICAL) {
    verticalScroll(scrollState)
  } else {
    horizontalScroll(scrollState)
  }
}

private fun RcLayoutNode.geometryComponentIds(): List<Int> =
  when (this) {
    is RcLayoutNode.Root ->
      listOf(componentId) + children.filterIsInstance<RcLayoutNode.Content>().map { it.componentId }
    is RcLayoutNode.Canvas -> listOfNotNull(componentId, content?.componentId)
    is RcLayoutNode.CanvasContent -> listOf(componentId)
    is RcLayoutNode.Box -> listOf(componentId, content.componentId)
    is RcLayoutNode.Row -> listOf(componentId, content.componentId)
    is RcLayoutNode.Column -> listOf(componentId, content.componentId)
    is RcLayoutNode.Flow -> listOf(componentId, content.componentId)
    is RcLayoutNode.State -> listOf(componentId)
    is RcLayoutNode.CollapsibleRow -> listOf(componentId, content.componentId)
    is RcLayoutNode.CollapsibleColumn -> listOf(componentId, content.componentId)
    is RcLayoutNode.FitBox -> listOf(componentId, content.componentId)
    is RcLayoutNode.Content -> emptyList() // Its layout manager publishes the wrapper geometry.
    is RcLayoutNode.Image -> listOfNotNull(componentId, contentComponentId)
    is RcLayoutNode.Text -> listOfNotNull(componentId, contentComponentId)
    is RcLayoutNode.CoreText -> listOfNotNull(componentId, contentComponentId)
  }

private fun Modifier.trackComponentGeometry(
  componentIds: List<Int>,
  state: RcPlayerState,
): Modifier {
  val tracked = componentIds.distinct().filter(state::hasComponentValues)
  if (tracked.isEmpty()) return this
  return onGloballyPositioned { coordinates ->
    val local = coordinates.positionInParent()
    val root = coordinates.positionInRoot()
    val geometry =
      RcComponentGeometry(
        width = coordinates.size.width.toFloat(),
        height = coordinates.size.height.toFloat(),
        localX = local.x,
        localY = local.y,
        rootX = root.x,
        rootY = root.y,
      )
    tracked.forEach { state.publishComponentGeometry(it, geometry) }
  }
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
      applyWidthRange(
        state.dimensionConstraintDp(state.resolve(operation.minimum), density),
        state.dimensionConstraintDp(state.resolve(operation.maximum), density),
      )
    is RcHeightInModifier ->
      applyHeightRange(
        state.dimensionConstraintDp(state.resolve(operation.minimum), density),
        state.dimensionConstraintDp(state.resolve(operation.maximum), density),
      )
    is RcDimensionConstraintsModifier ->
      when (operation.type) {
        RcDimensionConstraintsModifier.HORIZONTAL ->
          applyWidthRange(
            state.dimensionConstraintDp(state.resolve(operation.minimum), density),
            state.dimensionConstraintDp(state.resolve(operation.maximum), density),
          )
        RcDimensionConstraintsModifier.VERTICAL ->
          applyHeightRange(
            state.dimensionConstraintDp(state.resolve(operation.minimum), density),
            state.dimensionConstraintDp(state.resolve(operation.maximum), density),
          )
        RcDimensionConstraintsModifier.REQUIRED_HORIZONTAL ->
          applyWidthRange(
            state.dimensionConstraintDp(state.resolve(operation.minimum), density),
            state.dimensionConstraintDp(state.resolve(operation.maximum), density),
            required = true,
          )
        RcDimensionConstraintsModifier.REQUIRED_VERTICAL ->
          applyHeightRange(
            state.dimensionConstraintDp(state.resolve(operation.minimum), density),
            state.dimensionConstraintDp(state.resolve(operation.maximum), density),
            required = true,
          )
        else -> this
      }
    else -> this
  }

private fun RcPlayerState.dimensionConstraintDp(value: Float, density: Density): Float =
  if (value == -1f) value
  else rcDimensionConstraintDp(value, density.density, document.header.densityBehavior)

private fun Modifier.applyWidthRange(
  minimum: Float,
  maximum: Float,
  required: Boolean = false,
): Modifier {
  val min = minimum.dp
  val max = maximum.dp
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
  required: Boolean = false,
): Modifier {
  val min = minimum.dp
  val max = maximum.dp
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

@Composable
private fun Modifier.applyPaintDecorator(
  operation: ee.schimke.composeai.rcplayer.protocol.RcOperation,
  state: RcPlayerState,
): Modifier {
  val localDensity = androidx.compose.ui.platform.LocalDensity.current
  return when (operation) {
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
        val borderWidth =
          state.borderWidthPixels(operation.borderWidth, localDensity).coerceAtLeast(0f)
        val corner =
          state
            .dpTypedPixels(state.resolve(operation.roundedCorner), localDensity)
            .coerceAtLeast(0f)
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
    RcRippleModifier -> applyAndroidXRipple()
    RcClipRectModifier ->
      drawWithContent {
        val contentScope = this
        clipRect { contentScope.drawContent() }
      }
    is RcRoundedClipRectModifier ->
      drawWithContent {
        val topStart = state.dpTypedPixels(state.resolve(operation.topStart), localDensity)
        val topEnd = state.dpTypedPixels(state.resolve(operation.topEnd), localDensity)
        val bottomStart = state.dpTypedPixels(state.resolve(operation.bottomStart), localDensity)
        val bottomEnd = state.dpTypedPixels(state.resolve(operation.bottomEnd), localDensity)
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
}

private fun RcPlayerState.borderWidthPixels(word: RcFloatWord, density: Density): Float {
  val version = document.header.version
  val atLeastV7 = version.major > 1 || (version.major == 1 && version.minor >= 1)
  return if (document.header.densityBehavior == RcHeader.DENSITY_BEHAVIOR_LEGACY && !atLeastV7) {
    resolve(word) * density.density
  } else {
    dpTypedPixels(resolve(word), density)
  }
}

@Composable
private fun Modifier.applyAndroidXRipple(): Modifier {
  val hapticFeedback = LocalHapticFeedback.current
  val colorProgress = remember { Animatable(1f) }
  val radiusProgress = remember { Animatable(1f) }
  val animationScope = rememberCoroutineScope()
  var origin by remember { mutableStateOf(Offset.Zero) }
  val standard = CubicBezierEasing(.4f, 0f, .2f, 1f)
  return drawWithContent {
      drawContent()
      val color = lerp(Color(0xb4fafafa.toInt()), Color(0x00c8c8c8), colorProgress.value)
      val radius = maxOf(size.width, size.height) * radiusProgress.value
      val scope = this
      clipRect { scope.drawCircle(color = color, radius = radius, center = origin) }
    }
    .pointerInput(Unit) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        origin = down.position
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        animationScope.launch {
          colorProgress.snapTo(0f)
          colorProgress.animateTo(1f, tween(durationMillis = 1_000, easing = standard))
        }
        animationScope.launch {
          radiusProgress.snapTo(0f)
          radiusProgress.animateTo(1f, tween(durationMillis = 500, easing = standard))
        }
      }
    }
}

private fun Modifier.applyWidth(
  width: RcWidthModifier,
  state: RcPlayerState,
  density: Density,
): Modifier =
  when (width.type) {
    RcDimensionType.EXACT -> width(with(density) { state.resolve(width.value).toDp() })
    RcDimensionType.EXACT_DP -> width(state.resolve(width.value).dp)
    RcDimensionType.FILL,
    RcDimensionType.FILL_PARENT_MAX_WIDTH -> fillMaxWidth()
    // WRAP is the *absence* of a size modifier — Compose already sizes a component to its content,
    // which is how AndroidX's own embedded player implements it too. INTRINSIC_MIN/MAX fall here
    // as well, and those genuinely are unimplemented; `composeSupportReport` draws that line, so
    // the two cases stay distinguishable even though the modifier chain treats them alike.
    else -> this
  }

private fun RowScope.rowWeightModifier(node: RcLayoutNode, state: RcPlayerState): Modifier {
  val width = node.modifiers.width
  return if (width?.type == RcDimensionType.WEIGHT) {
    Modifier.weight(state.resolve(width.value).coerceAtLeast(Float.MIN_VALUE))
  } else {
    Modifier
  }
}

private fun ColumnScope.columnWeightModifier(node: RcLayoutNode, state: RcPlayerState): Modifier {
  val height = node.modifiers.height
  return if (height?.type == RcDimensionType.WEIGHT) {
    Modifier.weight(state.resolve(height.value).coerceAtLeast(Float.MIN_VALUE))
  } else {
    Modifier
  }
}

private fun Modifier.applyHeight(
  height: RcHeightModifier,
  state: RcPlayerState,
  density: Density,
): Modifier =
  when (height.type) {
    RcDimensionType.EXACT -> height(with(density) { state.resolve(height.value).toDp() })
    RcDimensionType.EXACT_DP -> height(state.resolve(height.value).dp)
    RcDimensionType.FILL,
    RcDimensionType.FILL_PARENT_MAX_HEIGHT -> fillMaxHeight()
    // See `applyWidth`: WRAP is Compose's default sizing, INTRINSIC_MIN/MAX are the unimplemented
    // pair the support report names.
    else -> this
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
  var brush: Brush? = null
  var colorFilter: ColorFilter? = null
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
          RcOpcodes.CONDITIONAL_OPERATIONS -> {
            val conditional = node.operation as RcConditionalOperations
            if (state.evaluateConditional(conditional)) {
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
            }
          }
          RcOpcodes.LOOP_START -> {
            val loop = node.operation as RcLoopOperation
            state.forEachLoopValue(loop) {
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
            }
          }
          RcOpcodes.IMPULSE_START -> {
            val impulse = node.operation as RcImpulseStart
            val process =
              (node.children.lastOrNull() as? RcLinkedNode.Container)?.takeIf {
                it.operation === RcImpulseProcess
              }
            when (state.evaluateImpulse(impulse)) {
              RcImpulsePhase.INITIALIZE ->
                drawOperations(
                  if (process == null) node.children else node.children.dropLast(1),
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
              RcImpulsePhase.PROCESS ->
                process?.let {
                  drawOperations(
                    it.children,
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
                }
              RcImpulsePhase.WAITING,
              RcImpulsePhase.IDLE -> Unit
            }
          }
          RcOpcodes.IMPULSE_PROCESS ->
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
      is RcHapticFeedback -> state.performHapticFeedback(operation)
      is RcTimeAttribute -> state.applyTimeAttribute(operation)
      is RcWakeIn -> state.requestWakeIn(operation)
      is RcDebugMessage -> state.emitDebugMessage(operation)
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
  rcTrace(RcTraceCategory.DOCUMENT, "rc:decodeImages") { decodeInlineImagesUncounted(document) }

private fun decodeInlineImagesUncounted(document: RcDocument): Map<Int, ImageBitmap> =
  document.operations
    .filterIsInstance<RcBitmapData>()
    .mapNotNull { bitmap ->
      if (bitmap.encoding != RcBitmapData.ENCODING_INLINE) null
      else runCatching { bitmap.imageId to decodeInlineImage(bitmap) }.getOrNull()
    }
    .toMap()

private fun decodeInlineFonts(document: RcDocument): Map<Int, FontFamily> =
  rcTrace(RcTraceCategory.DOCUMENT, "rc:decodeFonts") { decodeInlineFontsUncounted(document) }

private fun decodeInlineFontsUncounted(document: RcDocument): Map<Int, FontFamily> =
  document.operations
    .filterIsInstance<RcFontData>()
    .mapNotNull { font ->
      runCatching { font.fontId to FontFamily(Font("remote-compose-${font.fontId}", font.data)) }
        .getOrNull()
    }
    .toMap()

/**
 * The [FontFamily] a text op's `fontFamilyId` names, instanced at [settings] when the host holds
 * the face's bytes.
 *
 * [settings] are the document's font-variation axes. They can only be applied to a host face
 * ([RcFontFaces] keeps the bytes for exactly this reason) — a generic family or an inline
 * `FontData` resolves to a `FontFamily` whose faces are already built, so for those the axes are
 * dropped rather than approximated. That is a substitution the render shows honestly; approximating
 * `wdth` by scaling would not be.
 *
 * The naming rules themselves live in [rcResolveTypeface], which is shared so every host gets them
 * rather than reimplementing them. All this adds is reading the recorded name out of player state.
 */
private fun resolveFontFamily(
  fontFamilyId: Int,
  state: RcPlayerState,
  embeddedFonts: Map<Int, FontFamily>,
  typefaces: RcTypefaceLoader,
  settings: FontVariation.Settings? = null,
): FontFamily =
  rcResolveTypeface(state.text(fontFamilyId), fontFamilyId, embeddedFonts, typefaces, settings)

/**
 * The font-variation axes a `CoreText` style declares: property 20 is a list of *text ids* naming
 * the axis tags (`wght`, `wdth`, …) and property 21 the matching values, which may themselves be
 * document floats rather than literals. Empty when the style names none.
 *
 * Extracted (and pure) so the pairing rule — an axis counts only when both its tag and its value
 * are present — is unit-testable without a document.
 */
private const val CORE_TEXT_FONT_AXIS_TAGS = 20

private const val CORE_TEXT_FONT_AXIS_VALUES = 21

internal fun fontVariationSettings(
  axisTags: List<String?>,
  axisValues: List<Float?>,
): FontVariation.Settings? {
  val axes = axisTags.mapIndexedNotNull { index, tag ->
    val value = axisValues.getOrNull(index) ?: return@mapIndexedNotNull null
    tag?.takeIf { it.isNotBlank() }?.let { FontVariation.Setting(it, value) }
  }
  return if (axes.isEmpty()) null else FontVariation.Settings(*axes.toTypedArray())
}

/**
 * [settings] with the style's weight added as a `wght` axis, unless the document named `wght`
 * itself.
 *
 * `TextStyle.fontWeight` only picks *between* registered faces, so a family carrying one variable
 * file registered at 400 — which is what a `fonts.json` default role usually is — renders every
 * weight at 400: a `CoreText` asking for Medium came out visibly lighter than the reference, which
 * rasterizes with a real 500 face. Naming the axis is what actually moves a variable font, and on a
 * static face an axis the file does not define is ignored by the font engine, so this is a no-op
 * for families that select by weight in the ordinary way.
 *
 * An explicit `wght` from the document wins: a specimen sweeping the axis is naming the value it
 * wants, and the style weight beside it is only there so a non-variable fallback picks a face.
 */
internal fun withWeightAxis(
  settings: FontVariation.Settings?,
  weight: Int,
): FontVariation.Settings? {
  val existing = settings?.settings.orEmpty()
  if (existing.any { it.axisName == "wght" }) return settings
  val axes = existing + FontVariation.weight(weight.coerceIn(1, 1000))
  return FontVariation.Settings(*axes.toTypedArray())
}

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
    rcTrace(RcTraceCategory.FRAME, "rc:measureText") {
      textMeasurer.measure(
        text,
        style,
        layoutDirection = if (operation.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
      )
    }
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
    rcTrace(RcTraceCategory.FRAME, "rc:measureText") {
      textMeasurer.measure(
        text,
        style,
        layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
      )
    }
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
    val layout =
      rcTrace(RcTraceCategory.FRAME, "rc:measureText") { textMeasurer.measure(segment, style) }
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
    supportsLength = false,
  )

private fun DrawScope.measureTextOperation(
  outId: Int,
  textId: Int,
  type: Int,
  state: RcPlayerState,
  paint: RcPaintState,
  textMeasurer: TextMeasurer,
  supportsLength: Boolean = true,
) {
  val text = state.text(textId).orEmpty()
  val layout =
    rcTrace(RcTraceCategory.FRAME, "rc:measureText") {
      textMeasurer.measure(text, textStyle(paint))
    }
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
  selectTextMeasurement(type, left, top, right, bottom, text.length, supportsLength)?.let { value ->
    state.setFloat(outId, value)
  }
}

/**
 * Selects the value written by AndroidX's text measurement operations.
 *
 * `TextMeasure` (155) only defines selectors 0..5; `TextAttribute` additionally defines selector 6
 * for string length. AndroidX leaves the destination untouched for an unknown selector, hence the
 * nullable result.
 */
internal fun selectTextMeasurement(
  type: Int,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  textLength: Int,
  supportsLength: Boolean = true,
): Float? =
  when (type and 0xff) {
    0 -> right - left
    1 -> bottom - top
    2 -> left
    3 -> right
    4 -> top
    5 -> bottom
    6 -> if (supportsLength) textLength.toFloat() else null
    else -> null
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
  drawRcPath(trimmed, paint)
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
      drawRcPath(pathForId(operation.id, state, computedPaths), paint)
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

private fun DrawScope.drawRcPath(path: Path, paint: RcPaintState) {
  val brush = paint.brush
  if (brush == null) {
    drawPath(
      path = path,
      color = paint.composeColor(),
      style = paint.style(),
      colorFilter = paint.colorFilter,
      blendMode = paint.blendMode,
    )
  } else {
    drawPath(
      path = path,
      brush = brush,
      alpha = paint.alpha,
      style = paint.style(),
      colorFilter = paint.colorFilter,
      blendMode = paint.blendMode,
    )
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
    RcOpcodes.DRAW_RECT -> {
      val topLeft = Offset(a, b)
      val size = Size(c - a, d - b)
      val brush = paint.brush
      if (brush == null) {
        drawRect(
          paint.composeColor(),
          topLeft,
          size,
          style = paint.style(),
          colorFilter = paint.colorFilter,
          blendMode = paint.blendMode,
        )
      } else {
        drawRect(
          brush,
          topLeft,
          size,
          alpha = paint.alpha,
          style = paint.style(),
          colorFilter = paint.colorFilter,
          blendMode = paint.blendMode,
        )
      }
    }
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
    RcOpcodes.MATRIX_SCALE -> drawContext.transform.scale(a, b, rcMatrixPivot(c, d))
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
    RcOpcodes.MATRIX_ROTATE -> drawContext.transform.rotate(a, rcMatrixPivot(b, c))
  }
}

/** AndroidX encodes an omitted matrix pivot as NaN in the first pivot coordinate. */
internal fun rcMatrixPivot(x: Float, y: Float): Offset =
  if (x.isNaN()) Offset.Zero else Offset(x, y)

private fun DrawScope.draw6(operation: RcDraw6, paint: RcPaintState, state: RcPlayerState) {
  val a = state.resolve(operation.first)
  val b = state.resolve(operation.second)
  val c = state.resolve(operation.third)
  val d = state.resolve(operation.fourth)
  val e = state.resolve(operation.fifth)
  val f = state.resolve(operation.sixth)
  when (operation.opcode) {
    RcOpcodes.DRAW_ROUND_RECT -> {
      val topLeft = Offset(a, b)
      val size = Size(c - a, d - b)
      val cornerRadius = CornerRadius(e, f)
      val brush = paint.brush
      if (brush == null) {
        drawRoundRect(
          paint.composeColor(),
          topLeft,
          size,
          cornerRadius,
          style = paint.style(),
          colorFilter = paint.colorFilter,
          blendMode = paint.blendMode,
        )
      } else {
        drawRoundRect(
          brush,
          topLeft,
          size,
          cornerRadius,
          alpha = paint.alpha,
          style = paint.style(),
          colorFilter = paint.colorFilter,
          blendMode = paint.blendMode,
        )
      }
    }
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
      9 -> {
        val shaderId = operation.words[index++]
        check(shaderId == 0) { "Shader id $shaderId is not implemented by the CMP backend" }
        state.brush = null
      }
      11 -> index = applyGradient(operation.words, index, command, state, values)
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
      13 -> {
        state.colorFilter =
          ColorFilter.tint(Color(operation.words[index++]), blendMode(command ushr 16))
      }
      20 -> {
        state.colorFilter =
          ColorFilter.tint(
            Color(values.color(operation.words[index++])),
            blendMode(command ushr 16),
          )
      }
      21 -> state.colorFilter = null
      23 -> {
        val count = command ushr 16
        repeat(count) {
          val axis = operation.words[index++]
          val value =
            values.resolve(
              ee.schimke.composeai.rcplayer.protocol.RcFloatWord(operation.words[index++])
            )
          when (axis) {
            FONT_AXIS_WEIGHT -> state.fontWeight = FontWeight(value.roundToInt().coerceIn(1, 1000))
            FONT_AXIS_ITALIC ->
              state.fontStyle = if (value >= 0.5f) FontStyle.Italic else FontStyle.Normal
            FONT_AXIS_SLANT ->
              state.fontStyle = if (value != 0f) FontStyle.Italic else FontStyle.Normal
          }
        }
      }
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

private fun applyGradient(
  words: List<Int>,
  startIndex: Int,
  command: Int,
  state: RcPaintState,
  values: RcPlayerState,
): Int {
  var index = startIndex
  val descriptor = words[index++]
  val colorCount = descriptor and 0xff
  val colorIdMask = descriptor ushr 16
  val colors =
    List(colorCount) { colorIndex ->
      val word = words[index++]
      Color(if (colorIdMask and (1 shl colorIndex) != 0) values.color(word) else word)
    }
  val stopCount = words[index++]
  val stops = List(stopCount) { values.resolve(RcFloatWord(words[index++])) }
  fun coordinate(): Float = values.resolve(RcFloatWord(words[index++]))
  state.brush =
    when (command ushr 16) {
      0 -> {
        val start = Offset(coordinate(), coordinate())
        val end = Offset(coordinate(), coordinate())
        val tileMode = gradientTileMode(words[index++])
        if (stops.isEmpty()) Brush.linearGradient(colors, start, end, tileMode)
        else
          Brush.linearGradient(
            *stops.zip(colors).map { it.first to it.second }.toTypedArray(),
            start = start,
            end = end,
            tileMode = tileMode,
          )
      }
      1 -> {
        val center = Offset(coordinate(), coordinate())
        val radius = coordinate()
        val tileMode = gradientTileMode(words[index++])
        if (stops.isEmpty()) Brush.radialGradient(colors, center, radius, tileMode)
        else
          Brush.radialGradient(
            *stops.zip(colors).map { it.first to it.second }.toTypedArray(),
            center = center,
            radius = radius,
            tileMode = tileMode,
          )
      }
      2 -> {
        val center = Offset(coordinate(), coordinate())
        if (stops.isEmpty()) Brush.sweepGradient(colors, center)
        else
          Brush.sweepGradient(
            *stops.zip(colors).map { it.first to it.second }.toTypedArray(),
            center = center,
          )
      }
      else -> error("Gradient type ${command ushr 16} is not implemented")
    }
  return index
}

private fun gradientTileMode(value: Int): TileMode =
  when (value) {
    1 -> TileMode.Repeated
    2 -> TileMode.Mirror
    3 -> TileMode.Decal
    else -> TileMode.Clamp
  }

private const val FONT_AXIS_WEIGHT = 0x77676874 // wght
private const val FONT_AXIS_ITALIC = 0x6974616c // ital
private const val FONT_AXIS_SLANT = 0x736c6e74 // slnt

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
