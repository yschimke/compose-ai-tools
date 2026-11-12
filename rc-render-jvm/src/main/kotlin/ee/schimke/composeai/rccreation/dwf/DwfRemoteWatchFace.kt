package ee.schimke.composeai.rccreation.dwf

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import ee.schimke.composeai.rccreation.common.CoreDocument
import ee.schimke.composeai.rccreation.common.ImageScaling
import ee.schimke.composeai.rccreation.common.Profile
import ee.schimke.composeai.rccreation.common.RcProfiles
import ee.schimke.composeai.rccreation.common.RemoteDocumentWriter
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteDrawScope
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.path.RemotePath
import ee.schimke.composeai.rccreation.compose.shaders.RemoteBrush
import ee.schimke.composeai.rccreation.compose.shaders.RemoteShaderBrush
import ee.schimke.composeai.rccreation.compose.shaders.linearGradient
import ee.schimke.composeai.rccreation.compose.shaders.radialGradient
import ee.schimke.composeai.rccreation.compose.shaders.sweepGradient
import ee.schimke.composeai.rccreation.compose.state.RemoteBitmapFont
import ee.schimke.composeai.rccreation.compose.state.RemoteBlendMode
import ee.schimke.composeai.rccreation.compose.state.RemoteBlendModeColorFilter
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.RemotePaint
import ee.schimke.composeai.rccreation.compose.state.RemotePaintingStyle
import ee.schimke.composeai.rccreation.compose.state.RemotePathFillType
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.RemoteStrokeCap
import ee.schimke.composeai.rccreation.compose.state.asRemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.clamp
import ee.schimke.composeai.rccreation.compose.state.cos
import ee.schimke.composeai.rccreation.compose.state.lerp
import ee.schimke.composeai.rccreation.compose.state.max
import ee.schimke.composeai.rccreation.compose.state.min
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.sin
import ee.schimke.composeai.rccreation.compose.state.toRad
import ee.schimke.composeai.rccreation.compose.text.RemoteTypeface
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextAnchored

/**
 * Emits a parsed [DwfDocument] (Declarative Watch Face / Watch Face Format XML) as a
 * `@RemoteComposable` scene backed by live Remote Compose clock, sensor, configuration, ambient,
 * and complication expressions.
 */
@Composable
@RemoteComposable
public fun DwfWatchFace(
  document: DwfDocument,
  resources: DwfResourceResolver = DwfResourceResolver.Empty,
  options: DwfRenderOptions = DwfRenderOptions(),
  modifier: RemoteModifier = RemoteModifier.fillMaxSize(),
) {
  val compiler =
    DwfExpressionCompiler(document = document, resources = resources, options = options)
  val renderer = DwfCanvasRenderer(dwfDoc = document, resources = resources, options = options)
  RemoteCanvas(modifier) { renderer.renderWatchFace(this, compiler) }
}

/** Parses [xml] as Watch Face Format XML and emits it as a `@RemoteComposable` scene. */
@Composable
@RemoteComposable
public fun DwfWatchFace(
  xml: String,
  resources: DwfResourceResolver = DwfResourceResolver.Empty,
  options: DwfRenderOptions = DwfRenderOptions(),
  modifier: RemoteModifier = RemoteModifier.fillMaxSize(),
) {
  val document = DwfParser.parse(xml)
  DwfWatchFace(
    document = document,
    resources = resources,
    options = options,
    modifier = modifier,
  )
}

private val DwfCaptureProfile: Profile =
  Profile(
    CoreDocument.DOCUMENT_API_LEVEL,
    RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
  ) { info, _, _ ->
    RemoteDocumentWriter(info.width, info.height, info.densityBehavior)
  }

/**
 * Captures a parsed [DwfDocument] into serialized `.rc` wire bytes at 1:1 watch-face pixel density
 * (`160 dpi`, where `1 dp == 1 px` in `<WatchFace width="..." height="...">` coordinates).
 */
public suspend fun captureDwfRemoteDocumentBytes(
  document: DwfDocument,
  resources: DwfResourceResolver = DwfResourceResolver.Empty,
  options: DwfRenderOptions = DwfRenderOptions(),
  encodePng: ((ImageBitmap) -> ByteArray?)? = ::encodeDwfPng,
): ByteArray {
  val displayInfo =
    RemoteCreationDisplayInfo(
      document.width.coerceAtLeast(1),
      document.height.coerceAtLeast(1),
      160,
    )
  return captureCommonRemoteDocument(
    creationDisplayInfo = displayInfo,
    profile = DwfCaptureProfile,
    encodePng = encodePng,
  ) {
    DwfWatchFace(document = document, resources = resources, options = options)
  }
}

/** Parses Watch Face Format [xml] and captures it into serialized `.rc` wire bytes. */
public suspend fun captureDwfRemoteDocumentBytes(
  xml: String,
  resources: DwfResourceResolver = DwfResourceResolver.Empty,
  options: DwfRenderOptions = DwfRenderOptions(),
  encodePng: ((ImageBitmap) -> ByteArray?)? = ::encodeDwfPng,
): ByteArray =
  captureDwfRemoteDocumentBytes(
    document = DwfParser.parse(xml),
    resources = resources,
    options = options,
    encodePng = encodePng,
  )

/**
 * Captures a parsed [DwfDocument] and decodes it into an [RcDocument] ready for `RcComposePlayer`.
 */
public suspend fun captureDwfRemoteDocument(
  document: DwfDocument,
  resources: DwfResourceResolver = DwfResourceResolver.Empty,
  options: DwfRenderOptions = DwfRenderOptions(),
  encodePng: ((ImageBitmap) -> ByteArray?)? = ::encodeDwfPng,
): RcDocument =
  RcDocumentCodec.decode(
    captureDwfRemoteDocumentBytes(
      document = document,
      resources = resources,
      options = options,
      encodePng = encodePng,
    ),
  )

/**
 * Parses Watch Face Format [xml] and captures it into an [RcDocument] ready for `RcComposePlayer`.
 */
public suspend fun captureDwfRemoteDocument(
  xml: String,
  resources: DwfResourceResolver = DwfResourceResolver.Empty,
  options: DwfRenderOptions = DwfRenderOptions(),
  encodePng: ((ImageBitmap) -> ByteArray?)? = ::encodeDwfPng,
): RcDocument =
  captureDwfRemoteDocument(
    document = DwfParser.parse(xml),
    resources = resources,
    options = options,
    encodePng = encodePng,
  )

private class DwfCanvasRenderer(
  private val dwfDoc: DwfDocument,
  private val resources: DwfResourceResolver,
  private val options: DwfRenderOptions,
) {
  private val remoteBitmaps = HashMap<String, RemoteImageBitmap?>()
  private val bitmapFonts = HashMap<String, CompiledBitmapFont?>()

  private data class CompiledBitmapFont(
    val remoteFont: RemoteBitmapFont,
    val nominalHeight: Float,
    val avgGlyphWidth: Float,
    val hasMultiCharWords: Boolean,
  )

  private fun resolveRemoteBitmap(name: String): RemoteImageBitmap? {
    val key = normalizeDwfResourceKey(name)
    if (remoteBitmaps.containsKey(key)) return remoteBitmaps[key]
    val decoded = resources.resolveImage(name)?.asRemoteImageBitmap()
    remoteBitmaps[key] = decoded
    return decoded
  }

  private fun resolveBitmapFont(family: String): CompiledBitmapFont? {
    if (bitmapFonts.containsKey(family)) return bitmapFonts[family]
    val spec = dwfDoc.bitmapFonts.firstOrNull { it.name == family }
    if (spec == null || spec.glyphs.isEmpty()) {
      bitmapFonts[family] = null
      return null
    }
    var anyImage = false
    var maxH = 1f
    var widthSum = 0f
    var hasWords = false
    val glyphs =
      spec.glyphs.map { g ->
        val bmp = resolveRemoteBitmap(g.resource)
        if (bmp != null) anyImage = true
        if (g.height > maxH) maxH = g.height
        widthSum += g.width.coerceAtLeast(1f)
        if (g.text.length > 1) hasWords = true
        RemoteBitmapFont.Glyph(
          g.text,
          bmp,
          0,
          0,
          0,
          0,
          g.width.toInt().coerceAtLeast(1).toShort(),
          g.height.toInt().coerceAtLeast(1).toShort(),
        )
      }
    val avgW = (widthSum / spec.glyphs.size.coerceAtLeast(1)).coerceAtLeast(1f)
    val compiled =
      if (anyImage) {
        CompiledBitmapFont(
          remoteFont = RemoteBitmapFont(glyphs),
          nominalHeight = maxH,
          avgGlyphWidth = avgW,
          hasMultiCharWords = hasWords,
        )
      } else {
        null
      }
    bitmapFonts[family] = compiled
    return compiled
  }

  fun renderWatchFace(scope: RemoteDrawScope, compiler: DwfExpressionCompiler) =
    with(scope) {
      val docW = dwfDoc.width.coerceAtLeast(1).toFloat()
      val docH = dwfDoc.height.coerceAtLeast(1).toFloat()
      val sx = width / docW.rf
      val sy = height / docH.rf

      scale(sx, sy, RemoteOffset(0f.rf, 0f.rf)) {
        val saveCount = remoteCanvas.save()
        if (options.applyWatchShapeClip) {
          when (dwfDoc.clipShape) {
            DwfClipShape.CIRCLE -> remoteCanvas.clipPath(buildOvalPath(0f, 0f, docW, docH))
            DwfClipShape.RECTANGLE -> {
              if (dwfDoc.cornerRadiusX > 0f || dwfDoc.cornerRadiusY > 0f) {
                remoteCanvas.clipPath(
                  buildRoundRectPath(
                    0f,
                    0f,
                    docW,
                    docH,
                    dwfDoc.cornerRadiusX,
                    dwfDoc.cornerRadiusY,
                  ),
                )
              } else {
                remoteCanvas.clipRect(0f.rf, 0f.rf, docW.rf, docH.rf)
              }
            }
            DwfClipShape.NONE -> {}
          }
        }

        // Background fill (honoring <Scene backgroundColor="..."> + transforms + ambient variants).
        val bgRaw = dwfDoc.scene.backgroundColor ?: "#FF000000"
        val bgColor =
          compiler.resolveTransformedColor(
            baseColorRaw = bgRaw,
            targetName = "backgroundColor",
            transforms = dwfDoc.scene.transforms,
            variants = dwfDoc.scene.variants,
            fallbackArgb = 0xFF000000.toInt(),
          )
        val bgPaint = RemotePaint {
          isAntiAlias = true
          style = RemotePaintingStyle.Fill
          color = bgColor
        }
        remoteCanvas.drawRect(0f.rf, 0f.rf, docW.rf, docH.rf, bgPaint)

        renderNodeList(
          nodes = dwfDoc.scene.children,
          compiler = compiler,
          inheritedAlphaFactor = 1f.rf,
          inheritedTint = null,
        )
        remoteCanvas.restoreToCount(saveCount)
      }
    }

  private fun RemoteDrawScope.renderNodeList(
    nodes: List<DwfSceneNode>,
    compiler: DwfExpressionCompiler,
    inheritedAlphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
  ) {
    for (node in nodes) {
      renderNode(node, compiler, inheritedAlphaFactor, inheritedTint)
    }
  }

  private fun RemoteDrawScope.renderNode(
    node: DwfSceneNode,
    compiler: DwfExpressionCompiler,
    inheritedAlphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
  ) {
    when (node) {
      is DwfSceneNode.Group ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          _,
          _ ->
          val maskNodes =
            node.children.filter { child ->
              when (child) {
                is DwfSceneNode.PartImage -> child.geometry.renderMode == DwfRenderMode.MASK
                is DwfSceneNode.PartDraw -> child.geometry.renderMode == DwfRenderMode.MASK
                else -> false
              }
            }
          if (maskNodes.isNotEmpty()) {
            val maskSave = remoteCanvas.save()
            for (maskNode in maskNodes) {
              buildMaskClipPath(maskNode)?.let { remoteCanvas.clipPath(it) }
            }
            renderNodeList(node.children, compiler, alphaFactor, tint)
            remoteCanvas.restoreToCount(maskSave)
          } else {
            renderNodeList(node.children, compiler, alphaFactor, tint)
          }
        }
      is DwfSceneNode.PartDraw ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          w,
          h ->
          val sceneBgArgb =
            parseDwfColorArgb(dwfDoc.scene.backgroundColor ?: "#FF000000", resources)
              ?: 0xFF000000.toInt()
          for ((shapeIdx, shape) in node.shapes.withIndex()) {
            val isCutoutShape =
              shapeIdx > 0 &&
                shape.strokes.isEmpty() &&
                shape.weightedStrokes.isEmpty() &&
                shape.fills.isNotEmpty() &&
                shape.fills.all { fill ->
                  fill.gradient == null &&
                    fill.transforms.isEmpty() &&
                    fill.variants.isEmpty() &&
                    parseDwfColorArgb(fill.color, resources) == sceneBgArgb
                }
            val effectiveAlpha = if (isCutoutShape) 1f.rf else alphaFactor
            renderDrawShape(shape, compiler, effectiveAlpha, tint, w, h)
          }
        }
      is DwfSceneNode.PartText ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          w,
          h ->
          node.text?.let { renderRectangularText(it, compiler, alphaFactor, tint, w, h) }
          node.textCircular?.let { renderCircularText(it, compiler, alphaFactor, tint) }
        }
      is DwfSceneNode.PartImage ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          w,
          h ->
          val resName = node.resource ?: node.resourceChoices.firstOrNull()
          if (resName != null) {
            val compImage = compiler.resolveComplicationImageResource(resName)
            if (compImage != null) {
              val (targetRes, isPresent) = compImage
              if (targetRes.isNotBlank()) {
                drawConditionally(isPresent) {
                  renderImageOrVector(targetRes, alphaFactor, tint, w, h)
                }
              }
            } else {
              renderImageOrVector(resName, alphaFactor, tint, w, h)
            }
          }
        }
      is DwfSceneNode.PartAnimatedImage ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          w,
          h ->
          val resName = node.thumbnail ?: node.resource
          if (resName != null) {
            renderImageOrVector(resName, alphaFactor, tint, w, h)
          }
        }
      is DwfSceneNode.AnalogClock ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          _,
          _ ->
          renderNodeList(node.children, compiler, alphaFactor, tint)
          for (hand in node.hands) {
            renderClockHand(hand, compiler, alphaFactor, tint)
          }
        }
      is DwfSceneNode.DigitalClock ->
        withNodeGeometry(node.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          clockW,
          clockH ->
          for (tt in node.timeTexts) {
            renderTimeText(tt, compiler, alphaFactor, tint, clockW, clockH)
          }
        }
      is DwfSceneNode.Condition -> {
        val scoped = compiler.withScope(localExpressions = node.expressions)
        var noneMatchedYet: RemoteBoolean = RemoteBoolean(true)
        for (branch in node.branches) {
          if (branch.expression != null) {
            val cond = scoped.compileBoolean(branch.expression)
            val active = noneMatchedYet.and(cond)
            drawConditionally(active) {
              renderNodeList(branch.children, scoped, inheritedAlphaFactor, inheritedTint)
            }
            noneMatchedYet = noneMatchedYet.and(cond.not())
          } else {
            drawConditionally(noneMatchedYet) {
              renderNodeList(branch.children, scoped, inheritedAlphaFactor, inheritedTint)
            }
            noneMatchedYet = RemoteBoolean(false)
          }
        }
      }
      is DwfSceneNode.ListConfigurationSwitch -> {
        val configState = compiler.listConfigurationState(node.id)
        for (optBranch in node.options) {
          val idx = compiler.listOptionIndex(node.id, optBranch.id)
          drawConditionally(configState.isEqualTo(RemoteInt(idx))) {
            renderNodeList(optBranch.children, compiler, inheritedAlphaFactor, inheritedTint)
          }
        }
      }
      is DwfSceneNode.BooleanConfigurationSwitch -> {
        val boolState = compiler.booleanConfigurationState(node.id)
        if (node.trueChildren.isNotEmpty()) {
          drawConditionally(boolState) {
            renderNodeList(node.trueChildren, compiler, inheritedAlphaFactor, inheritedTint)
          }
        }
        if (node.falseChildren.isNotEmpty()) {
          drawConditionally(boolState.not()) {
            renderNodeList(node.falseChildren, compiler, inheritedAlphaFactor, inheritedTint)
          }
        }
      }
      is DwfSceneNode.ComplicationSlot -> {
        val scoped = compiler.withScope(complicationSlotId = node.slotId)
        withNodeGeometry(node.geometry, scoped, inheritedAlphaFactor, inheritedTint) {
          alphaFactor,
          tint,
          _,
          _ ->
          val typeState = scoped.complicationTypeState(node.slotId)
          var anyMatched = RemoteBoolean(false)
          for (variant in node.complications) {
            val code = DwfVariables.complicationTypeCode(variant.type)
            val cond = typeState.isEqualTo(RemoteInt(code))
            drawConditionally(cond) { renderNodeList(variant.children, scoped, alphaFactor, tint) }
            anyMatched = anyMatched.or(cond)
          }
          // Fallback: if the host passes a complication type code for which this watch face only
          // authored a different non-EMPTY layout, render the first non-EMPTY variant rather than
          // leaving the slot blank when non-EMPTY.
          val fallbackVariant =
            node.complications.firstOrNull { !it.type.equals("EMPTY", ignoreCase = true) }
          if (fallbackVariant != null) {
            val notEmpty = typeState.isNotEqualTo(RemoteInt(DwfVariables.COMPLICATION_TYPE_EMPTY))
            drawConditionally(anyMatched.not().and(notEmpty)) {
              renderNodeList(fallbackVariant.children, scoped, alphaFactor, tint)
            }
          }
        }
      }
      is DwfSceneNode.Reference -> {}
    }
  }

  private fun RemoteDrawScope.withNodeGeometry(
    geom: DwfNodeGeometry,
    compiler: DwfExpressionCompiler,
    inheritedAlphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    block:
      RemoteDrawScope.(
        alphaFactor: RemoteFloat,
        tint: RemoteColor?,
        width: RemoteFloat,
        height: RemoteFloat,
      ) -> Unit,
  ) {
    if (geom.renderMode == DwfRenderMode.MASK) return

    var x = compiler.resolveTransformedFloat(geom.x, "x", geom.transforms, geom.variants)
    var y = compiler.resolveTransformedFloat(geom.y, "y", geom.transforms, geom.variants)
    val w = compiler.resolveTransformedFloat(geom.width, "width", geom.transforms, geom.variants)
    val h = compiler.resolveTransformedFloat(geom.height, "height", geom.transforms, geom.variants)
    val pivotXRatio =
      compiler.resolveTransformedFloat(geom.pivotX, "pivotX", geom.transforms, geom.variants)
    val pivotYRatio =
      compiler.resolveTransformedFloat(geom.pivotY, "pivotY", geom.transforms, geom.variants)
    var angle =
      compiler.resolveTransformedFloat(geom.angle, "angle", geom.transforms, geom.variants)
    var scaleX =
      compiler.resolveTransformedFloat(geom.scaleX, "scaleX", geom.transforms, geom.variants)
    var scaleY =
      compiler.resolveTransformedFloat(geom.scaleY, "scaleY", geom.transforms, geom.variants)
    var rawAlpha =
      compiler.resolveTransformedFloat(
        geom.alpha.toFloat(),
        "alpha",
        geom.transforms,
        geom.variants,
      )

    geom.gyro?.let { gyro ->
      val normX = clamp(compiler.accelAngleX / 45f.rf, (-1f).rf, 1f.rf)
      val normY = clamp(compiler.accelAngleY / 45f.rf, (-1f).rf, 1f.rf)
      if (gyro.x != 0f) x += normX * gyro.x.rf
      if (gyro.y != 0f) y += normY * gyro.y.rf
      if (gyro.angle != 0f) angle += normX * gyro.angle.rf
      if (gyro.scaleX != 0f) scaleX += normX * gyro.scaleX.rf
      if (gyro.scaleY != 0f) scaleY += normY * gyro.scaleY.rf
      if (gyro.alpha != 0f) rawAlpha += normX * gyro.alpha.rf
    }

    val localAlphaFactor = clamp(rawAlpha / 255f.rf, 0f.rf, 1f.rf) * inheritedAlphaFactor
    val nodeTint =
      geom.tintColor?.let {
        compiler.resolveTransformedColor(
          baseColorRaw = it,
          targetName = "tintColor",
          transforms = geom.transforms,
          variants = geom.variants,
        )
      } ?: inheritedTint

    val constAlpha = localAlphaFactor.constantValueOrNull
    if (constAlpha != null && constAlpha <= 0.001f) return

    val visible = localAlphaFactor.isGreaterThan(0.001f.rf)
    val drawBody: RemoteDrawScope.() -> Unit = {
      val pivot = RemoteOffset(pivotXRatio * w, pivotYRatio * h)
      translate(x, y) {
        val hasRotation = angle.constantValueOrNull != 0f
        val hasScale = scaleX.constantValueOrNull != 1f || scaleY.constantValueOrNull != 1f
        when {
          hasRotation && hasScale ->
            rotate(angle, pivot) {
              scale(scaleX, scaleY, pivot) { block(localAlphaFactor, nodeTint, w, h) }
            }
          hasRotation -> rotate(angle, pivot) { block(localAlphaFactor, nodeTint, w, h) }
          hasScale -> scale(scaleX, scaleY, pivot) { block(localAlphaFactor, nodeTint, w, h) }
          else -> block(localAlphaFactor, nodeTint, w, h)
        }
      }
    }

    if (constAlpha != null) {
      drawBody()
    } else {
      drawConditionally(visible) { drawBody() }
    }
  }

  private fun RemoteDrawScope.renderClockHand(
    hand: DwfClockHand,
    compiler: DwfExpressionCompiler,
    inheritedAlphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
  ) {
    // Standard WFF semantics: second hand hides in ambient mode unless explicitly given an
    // AMBIENT alpha > 0 variant.
    val hasExplicitAmbientVisible =
      hand.geometry.variants.any {
        it.mode.equals("AMBIENT", ignoreCase = true) &&
          it.target.equals("alpha", ignoreCase = true) &&
          (it.value.toFloatOrNull() ?: 1f) > 0f
      }
    val defaultHandAngle =
      when (hand.kind) {
        DwfClockHand.HandKind.HOUR ->
          compiler.compileFloat("([HOUR_0_11] * 30) + ([MINUTE] * 0.5) + ([SECOND] / 120)")
        DwfClockHand.HandKind.MINUTE -> compiler.compileFloat("([MINUTE] * 6) + ([SECOND] * 0.1)")
        DwfClockHand.HandKind.SECOND ->
          if (hand.sweepFrequencyHz != null) {
            compiler.compileFloat("[SECOND_MILLISECOND] * 6")
          } else {
            compiler.compileFloat("[SECOND] * 6")
          }
      }
    val hasExplicitAngleTransform =
      hand.geometry.transforms.any { it.target.equals("angle", ignoreCase = true) }

    val drawHand: RemoteDrawScope.() -> Unit = {
      withNodeGeometry(hand.geometry, compiler, inheritedAlphaFactor, inheritedTint) {
        alphaFactor,
        tint,
        w,
        h ->
        val pivotXRatio =
          compiler.resolveTransformedFloat(
            hand.geometry.pivotX,
            "pivotX",
            hand.geometry.transforms,
            hand.geometry.variants,
          )
        val pivotYRatio =
          compiler.resolveTransformedFloat(
            hand.geometry.pivotY,
            "pivotY",
            hand.geometry.transforms,
            hand.geometry.variants,
          )
        val pivot = RemoteOffset(pivotXRatio * w, pivotYRatio * h)
        val drawContent: RemoteDrawScope.() -> Unit = {
          val drewResource =
            hand.resource?.let { resName ->
              tryRenderImageOrVector(resName, alphaFactor, tint, w, h)
            } ?: false
          if (hand.children.isNotEmpty()) {
            renderNodeList(hand.children, compiler, alphaFactor, tint)
          } else if (!drewResource) {
            renderFallbackHand(hand.kind, alphaFactor, tint, w, h, pivot)
          }
        }
        if (hasExplicitAngleTransform) {
          drawContent()
        } else {
          rotate(defaultHandAngle, pivot) { drawContent() }
        }
      }
    }

    if (hand.kind == DwfClockHand.HandKind.SECOND && !hasExplicitAmbientVisible) {
      drawConditionally(compiler.isAmbient.not()) { drawHand() }
    } else {
      drawHand()
    }
  }

  private fun RemoteDrawScope.renderFallbackHand(
    kind: DwfClockHand.HandKind,
    alphaFactor: RemoteFloat,
    tint: RemoteColor?,
    w: RemoteFloat,
    h: RemoteFloat,
    pivot: RemoteOffset,
  ) {
    val defaultArgb =
      when (kind) {
        DwfClockHand.HandKind.HOUR -> 0xFFFFFFFF.toInt()
        DwfClockHand.HandKind.MINUTE -> 0xFFE8EAED.toInt()
        DwfClockHand.HandKind.SECOND -> 0xFFFF5252.toInt()
      }
    val baseColor = tint ?: RemoteColor(defaultArgb)
    val finalColor = applyAlpha(baseColor, alphaFactor)
    val radius = max(w / 2f.rf, 1.5f.rf)
    val paint = RemotePaint {
      isAntiAlias = true
      style = RemotePaintingStyle.Fill
      color = finalColor
    }
    remoteCanvas.drawRoundRect(0f.rf, 0f.rf, w, h, radius, radius, paint)
    // Hub cap around pivot
    remoteCanvas.drawCircle(pivot.x, pivot.y, max(w * 0.75f.rf, 4f.rf), paint)
  }

  private fun RemoteDrawScope.renderDrawShape(
    shape: DwfDrawShape,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    parentW: RemoteFloat,
    parentH: RemoteFloat,
  ) {
    for (fill in shape.fills) {
      val paint =
        buildFillPaint(fill, compiler, alphaFactor, inheritedTint, RemoteSize(parentW, parentH))
      drawPrimitive(shape.primitive, compiler, paint)
    }
    for (stroke in shape.strokes) {
      if (
        stroke.gradient == null &&
          shape.weightedElements.isNotEmpty() &&
          shape.primitive is DwfDrawPrimitive.Arc
      ) {
        renderWeightedArc(
          shape.primitive,
          stroke,
          shape.weightedElements,
          compiler,
          alphaFactor,
          inheritedTint,
        )
      } else {
        val paint =
          buildStrokePaint(
            stroke,
            compiler,
            alphaFactor,
            inheritedTint,
            RemoteSize(parentW, parentH),
          )
        drawPrimitive(shape.primitive, compiler, paint)
      }
    }
    if (shape.primitive is DwfDrawPrimitive.Arc) {
      for (ws in shape.weightedStrokes) {
        renderWeightedStrokeArc(shape.primitive, ws, compiler, alphaFactor, inheritedTint)
      }
    }
  }

  private fun RemoteDrawScope.renderWeightedStrokeArc(
    arc: DwfDrawPrimitive.Arc,
    ws: DwfWeightedStroke,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
  ) {
    val resolved = compiler.resolveWeightedStroke(ws)
    if (resolved.colors.isEmpty()) return
    val cx = compiler.resolveTransformedFloat(arc.centerX, "centerX", arc.transforms, arc.variants)
    val cy = compiler.resolveTransformedFloat(arc.centerY, "centerY", arc.transforms, arc.variants)
    val w = compiler.resolveTransformedFloat(arc.width, "width", arc.transforms, arc.variants)
    val h = compiler.resolveTransformedFloat(arc.height, "height", arc.transforms, arc.variants)
    val left = cx - (w / 2f.rf)
    val top = cy - (h / 2f.rf)
    val right = cx + (w / 2f.rf)
    val bottom = cy + (h / 2f.rf)
    val startDwf =
      compiler.resolveTransformedFloat(arc.startAngle, "startAngle", arc.transforms, arc.variants)
    val endDwf =
      compiler.resolveTransformedFloat(arc.endAngle, "endAngle", arc.transforms, arc.variants)
    val totalSweep = computeArcSweep(startDwf, endDwf, arc.direction)
    val thickness =
      compiler.resolveTransformedFloat(ws.thickness, "thickness", ws.transforms, ws.variants)
    val rcStart = startDwf - 90f.rf

    if (resolved.interpolate && resolved.colors.size >= 2) {
      val steps = 32
      val stepSweep = totalSweep / steps.toFloat().rf
      val overlap = if (arc.direction == DwfArcDirection.CLOCKWISE) 0.45f.rf else (-0.45f).rf
      val spanCount = resolved.colors.size - 1
      for (i in 0 until steps) {
        val t = (i + 0.5f) / steps.toFloat()
        val scaled = t * spanCount
        val segIdx = scaled.toInt().coerceIn(0, spanCount - 1)
        val localT = (scaled - segIdx).coerceIn(0f, 1f)
        val segColor =
          inheritedTint ?: lerp(resolved.colors[segIdx], resolved.colors[segIdx + 1], localT.rf)
        val paint = RemotePaint {
          isAntiAlias = true
          style = RemotePaintingStyle.Stroke
          strokeWidth = thickness
          strokeCap = RemoteStrokeCap.Butt
          color = applyAlpha(segColor, alphaFactor)
        }
        val segStart = rcStart + (stepSweep * i.toFloat().rf)
        val segLen = if (i < steps - 1) stepSweep + overlap else stepSweep
        remoteCanvas.drawArc(left, top, right, bottom, segStart, segLen, false, paint)
      }
      if (ws.cap == DwfStrokeCap.ROUND) {
        val rx = w / 2f.rf
        val ry = h / 2f.rf
        val capRadius = thickness / 2f.rf
        val startRad = toRad(rcStart)
        val endRad = toRad(rcStart + totalSweep)
        val startCol = applyAlpha(inheritedTint ?: resolved.colors.first(), alphaFactor)
        val endCol = applyAlpha(inheritedTint ?: resolved.colors.last(), alphaFactor)
        val startCapPaint = RemotePaint {
          isAntiAlias = true
          style = RemotePaintingStyle.Fill
          color = startCol
        }
        val endCapPaint = RemotePaint {
          isAntiAlias = true
          style = RemotePaintingStyle.Fill
          color = endCol
        }
        remoteCanvas.drawCircle(
          cx + rx * cos(startRad),
          cy + ry * sin(startRad),
          capRadius,
          startCapPaint,
        )
        remoteCanvas.drawCircle(
          cx + rx * cos(endRad),
          cy + ry * sin(endRad),
          capRadius,
          endCapPaint,
        )
      }
      return
    }

    val weights =
      resolved.colors.indices.map { idx -> max(resolved.weights.getOrElse(idx) { 1f.rf }, 0f.rf) }
    val totalWeight = max(weights.reduce { a, b -> a + b }, 0.0001f.rf)
    var cursor = rcStart
    for ((idx, col) in resolved.colors.withIndex()) {
      val segSweep = totalSweep * (weights[idx] / totalWeight)
      val baseCol = inheritedTint ?: col
      val paint = RemotePaint {
        isAntiAlias = true
        style = RemotePaintingStyle.Stroke
        strokeWidth = thickness
        strokeCap = toRemoteStrokeCap(ws.cap)
        color = applyAlpha(baseCol, alphaFactor)
      }
      remoteCanvas.drawArc(left, top, right, bottom, cursor, segSweep, false, paint)
      cursor += segSweep
    }
  }

  private fun RemoteDrawScope.renderWeightedArc(
    arc: DwfDrawPrimitive.Arc,
    stroke: DwfStroke,
    elements: List<DwfWeightedElement>,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
  ) {
    val cx = compiler.resolveTransformedFloat(arc.centerX, "centerX", arc.transforms, arc.variants)
    val cy = compiler.resolveTransformedFloat(arc.centerY, "centerY", arc.transforms, arc.variants)
    val w = compiler.resolveTransformedFloat(arc.width, "width", arc.transforms, arc.variants)
    val h = compiler.resolveTransformedFloat(arc.height, "height", arc.transforms, arc.variants)
    val left = cx - (w / 2f.rf)
    val top = cy - (h / 2f.rf)
    val right = cx + (w / 2f.rf)
    val bottom = cy + (h / 2f.rf)
    val startDwf =
      compiler.resolveTransformedFloat(arc.startAngle, "startAngle", arc.transforms, arc.variants)
    val endDwf =
      compiler.resolveTransformedFloat(arc.endAngle, "endAngle", arc.transforms, arc.variants)
    val totalSweep = computeArcSweep(startDwf, endDwf, arc.direction)
    val thickness =
      compiler.resolveTransformedFloat(
        stroke.thickness,
        "thickness",
        stroke.transforms,
        stroke.variants,
      )
    val weights = elements.map { el ->
      max(compiler.resolveTransformedFloat(el.weight, "weight", el.transforms, emptyList()), 0f.rf)
    }
    val totalWeight = max(weights.reduce { a, b -> a + b }, 0.0001f.rf)
    var cursor = startDwf - 90f.rf
    for ((idx, el) in elements.withIndex()) {
      val segSweep = totalSweep * (weights[idx] / totalWeight)
      val baseCol = inheritedTint ?: compiler.compileColor(el.color)
      val paint = RemotePaint {
        isAntiAlias = true
        style = RemotePaintingStyle.Stroke
        strokeWidth = thickness
        strokeCap = toRemoteStrokeCap(stroke.cap)
        color = applyAlpha(baseCol, alphaFactor)
      }
      remoteCanvas.drawArc(left, top, right, bottom, cursor, segSweep, false, paint)
      cursor += segSweep
    }
  }

  private fun RemoteDrawScope.drawPrimitive(
    prim: DwfDrawPrimitive,
    compiler: DwfExpressionCompiler,
    paint: RemotePaint,
  ) {
    when (prim) {
      is DwfDrawPrimitive.Line -> {
        val sx =
          compiler.resolveTransformedFloat(prim.startX, "startX", prim.transforms, prim.variants)
        val sy =
          compiler.resolveTransformedFloat(prim.startY, "startY", prim.transforms, prim.variants)
        val ex = compiler.resolveTransformedFloat(prim.endX, "endX", prim.transforms, prim.variants)
        val ey = compiler.resolveTransformedFloat(prim.endY, "endY", prim.transforms, prim.variants)
        remoteCanvas.drawLine(sx, sy, ex, ey, paint)
      }
      is DwfDrawPrimitive.Rectangle -> {
        val x = compiler.resolveTransformedFloat(prim.x, "x", prim.transforms, prim.variants)
        val y = compiler.resolveTransformedFloat(prim.y, "y", prim.transforms, prim.variants)
        val w =
          compiler.resolveTransformedFloat(prim.width, "width", prim.transforms, prim.variants)
        val h =
          compiler.resolveTransformedFloat(prim.height, "height", prim.transforms, prim.variants)
        remoteCanvas.drawRect(x, y, x + w, y + h, paint)
      }
      is DwfDrawPrimitive.RoundRectangle -> {
        val x = compiler.resolveTransformedFloat(prim.x, "x", prim.transforms, prim.variants)
        val y = compiler.resolveTransformedFloat(prim.y, "y", prim.transforms, prim.variants)
        val w =
          compiler.resolveTransformedFloat(prim.width, "width", prim.transforms, prim.variants)
        val h =
          compiler.resolveTransformedFloat(prim.height, "height", prim.transforms, prim.variants)
        val rx =
          compiler.resolveTransformedFloat(
            prim.cornerRadiusX,
            "cornerRadiusX",
            prim.transforms,
            prim.variants,
          )
        val ry =
          compiler.resolveTransformedFloat(
            prim.cornerRadiusY,
            "cornerRadiusY",
            prim.transforms,
            prim.variants,
          )
        remoteCanvas.drawRoundRect(x, y, x + w, y + h, rx, ry, paint)
      }
      is DwfDrawPrimitive.Ellipse -> {
        val x = compiler.resolveTransformedFloat(prim.x, "x", prim.transforms, prim.variants)
        val y = compiler.resolveTransformedFloat(prim.y, "y", prim.transforms, prim.variants)
        val w =
          compiler.resolveTransformedFloat(prim.width, "width", prim.transforms, prim.variants)
        val h =
          compiler.resolveTransformedFloat(prim.height, "height", prim.transforms, prim.variants)
        remoteCanvas.drawOval(x, y, x + w, y + h, paint)
      }
      is DwfDrawPrimitive.Arc -> {
        val cx =
          compiler.resolveTransformedFloat(prim.centerX, "centerX", prim.transforms, prim.variants)
        val cy =
          compiler.resolveTransformedFloat(prim.centerY, "centerY", prim.transforms, prim.variants)
        val w =
          compiler.resolveTransformedFloat(prim.width, "width", prim.transforms, prim.variants)
        val h =
          compiler.resolveTransformedFloat(prim.height, "height", prim.transforms, prim.variants)
        val startDwf =
          compiler.resolveTransformedFloat(
            prim.startAngle,
            "startAngle",
            prim.transforms,
            prim.variants,
          )
        val endDwf =
          compiler.resolveTransformedFloat(
            prim.endAngle,
            "endAngle",
            prim.transforms,
            prim.variants,
          )
        val rcStart = startDwf - 90f.rf
        val sweep = computeArcSweep(startDwf, endDwf, prim.direction)
        val left = cx - (w / 2f.rf)
        val top = cy - (h / 2f.rf)
        val right = cx + (w / 2f.rf)
        val bottom = cy + (h / 2f.rf)
        val useCenter = paint.style == RemotePaintingStyle.Fill
        remoteCanvas.drawArc(left, top, right, bottom, rcStart, sweep, useCenter, paint)
      }
    }
  }

  private fun computeArcSweep(
    startDwf: RemoteFloat,
    endDwf: RemoteFloat,
    direction: DwfArcDirection,
  ): RemoteFloat {
    val sc = startDwf.constantValueOrNull
    val ec = endDwf.constantValueOrNull
    if (sc != null && ec != null) {
      val cw =
        if (ec >= sc) {
          ec - sc
        } else {
          (ec + 360f) - sc
        }
      return (if (direction == DwfArcDirection.CLOCKWISE) cw else -(360f - cw)).rf
    }
    return if (direction == DwfArcDirection.CLOCKWISE) {
      endDwf.isLessThan(startDwf).select((endDwf + 360f.rf) - startDwf, endDwf - startDwf)
    } else {
      startDwf.isLessThan(endDwf).select(-((startDwf + 360f.rf) - endDwf), -(startDwf - endDwf))
    }
  }

  private fun RemoteDrawScope.buildFillPaint(
    fill: DwfFill,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    boundsSize: RemoteSize,
  ): RemotePaint {
    val baseColor =
      inheritedTint
        ?: compiler.resolveTransformedColor(
          baseColorRaw = fill.color,
          targetName = "color",
          transforms = fill.transforms,
          variants = fill.variants,
        )
    val finalColor = applyAlpha(baseColor, alphaFactor)
    val brush = fill.gradient?.let { buildGradientBrush(it, compiler, alphaFactor) }
    val p = RemotePaint {
      isAntiAlias = true
      style = RemotePaintingStyle.Fill
      color = finalColor
    }
    if (brush != null) {
      with(brush) { applyTo(p, boundsSize) }
    }
    return p
  }

  private fun RemoteDrawScope.buildStrokePaint(
    stroke: DwfStroke,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    boundsSize: RemoteSize,
  ): RemotePaint {
    val baseColor =
      inheritedTint
        ?: compiler.resolveTransformedColor(
          baseColorRaw = stroke.color,
          targetName = "color",
          transforms = stroke.transforms,
          variants = stroke.variants,
        )
    val finalColor = applyAlpha(baseColor, alphaFactor)
    val widthVal =
      compiler.resolveTransformedFloat(
        stroke.thickness,
        "thickness",
        stroke.transforms,
        stroke.variants,
      )
    val brush = stroke.gradient?.let { buildGradientBrush(it, compiler, alphaFactor) }
    val p = RemotePaint {
      isAntiAlias = true
      style = RemotePaintingStyle.Stroke
      strokeWidth = widthVal
      strokeCap = toRemoteStrokeCap(stroke.cap)
      color = finalColor
    }
    if (brush != null) {
      with(brush) { applyTo(p, boundsSize) }
    }
    return p
  }

  private fun buildGradientBrush(
    gradient: DwfGradient,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
  ): RemoteShaderBrush? {
    val colors = gradient.colors.map { applyAlpha(compiler.compileColor(it), alphaFactor) }
    if (colors.size < 2) return null
    val stops =
      gradient.positions
        ?.takeIf { it.size == colors.size }
        ?.mapIndexed { idx, pos -> pos.rf to colors[idx] }
        ?.toTypedArray()

    return when (gradient) {
      is DwfGradient.Linear -> {
        val sx =
          compiler.resolveTransformedFloat(
            gradient.startX,
            "startX",
            gradient.transforms,
            emptyList(),
          )
        val sy =
          compiler.resolveTransformedFloat(
            gradient.startY,
            "startY",
            gradient.transforms,
            emptyList(),
          )
        val ex =
          compiler.resolveTransformedFloat(gradient.endX, "endX", gradient.transforms, emptyList())
        val ey =
          compiler.resolveTransformedFloat(gradient.endY, "endY", gradient.transforms, emptyList())
        val start = RemoteOffset(sx, sy)
        val end = RemoteOffset(ex, ey)
        if (stops != null) {
          RemoteBrush.linearGradient(*stops, start = start, end = end)
        } else {
          RemoteBrush.linearGradient(colors = colors, start = start, end = end)
        }
      }
      is DwfGradient.Radial -> {
        val cx =
          compiler.resolveTransformedFloat(
            gradient.centerX,
            "centerX",
            gradient.transforms,
            emptyList(),
          )
        val cy =
          compiler.resolveTransformedFloat(
            gradient.centerY,
            "centerY",
            gradient.transforms,
            emptyList(),
          )
        val r =
          max(
            compiler.resolveTransformedFloat(
              gradient.radius,
              "radius",
              gradient.transforms,
              emptyList(),
            ),
            0.1f.rf,
          )
        val center = RemoteOffset(cx, cy)
        if (stops != null) {
          RemoteBrush.radialGradient(*stops, center = center, radius = r)
        } else {
          RemoteBrush.radialGradient(colors = colors, center = center, radius = r)
        }
      }
      is DwfGradient.Sweep -> {
        val cx =
          compiler.resolveTransformedFloat(
            gradient.centerX,
            "centerX",
            gradient.transforms,
            emptyList(),
          )
        val cy =
          compiler.resolveTransformedFloat(
            gradient.centerY,
            "centerY",
            gradient.transforms,
            emptyList(),
          )
        val center = RemoteOffset(cx, cy)
        if (stops != null) {
          RemoteBrush.sweepGradient(*stops, center = center)
        } else {
          RemoteBrush.sweepGradient(colors = colors, center = center)
        }
      }
    }
  }

  private fun RemoteDrawScope.renderRectangularText(
    spec: DwfTextSpec,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    boxW: RemoteFloat,
    boxH: RemoteFloat,
  ) {
    val bmpUsage = spec.bitmapFont
    val fontSpec =
      spec.font ?: DwfFontSpec(size = bmpUsage?.size ?: 24f, color = bmpUsage?.color ?: "#FFFFFFFF")
    val elements =
      when {
        spec.font != null && spec.font.elements.isNotEmpty() -> spec.font.elements
        bmpUsage != null && bmpUsage.elements.isNotEmpty() -> bmpUsage.elements
        else -> spec.elements
      }
    val rawTextState = compiler.compileTextElements(elements)
    val textState =
      if (spec.ellipsis) {
        applyEllipsisIfNeeded(rawTextState, fontSpec.size, boxW)
      } else {
        rawTextState
      }
    if (bmpUsage != null) {
      val compiledBmpFont = resolveBitmapFont(bmpUsage.family)
      if (compiledBmpFont != null) {
        renderBitmapFontString(
          textState = textState,
          bmpFont = compiledBmpFont,
          targetSize = bmpUsage.size,
          colorRaw = bmpUsage.color,
          align = spec.align,
          compiler = compiler,
          alphaFactor = alphaFactor,
          inheritedTint = inheritedTint,
          boxW = boxW,
          boxH = boxH,
        )
        return
      }
    }
    drawStyledTextInBox(
      text = textState,
      fontSpec = fontSpec,
      align = spec.align,
      compiler = compiler,
      alphaFactor = alphaFactor,
      inheritedTint = inheritedTint,
      boxW = boxW,
      boxH = boxH,
    )
  }

  private fun RemoteDrawScope.renderTimeText(
    tt: DwfTimeText,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    clockW: RemoteFloat,
    clockH: RemoteFloat,
  ) {
    val textState = compiler.compileTimeTextFormat(tt.format, tt.hourFormat)
    val hasOwnBounds =
      tt.geometry.width > 0f ||
        tt.geometry.height > 0f ||
        tt.geometry.x != 0f ||
        tt.geometry.y != 0f
    if (hasOwnBounds) {
      withNodeGeometry(tt.geometry, compiler, alphaFactor, inheritedTint) {
        localAlpha,
        localTint,
        w,
        h ->
        val effW = if (tt.geometry.width > 0f) w else clockW
        val effH = if (tt.geometry.height > 0f) h else clockH
        renderTimeTextBody(tt, textState, compiler, localAlpha, localTint, effW, effH)
      }
    } else {
      renderTimeTextBody(tt, textState, compiler, alphaFactor, inheritedTint, clockW, clockH)
    }
  }

  private fun RemoteDrawScope.renderTimeTextBody(
    tt: DwfTimeText,
    textState: RemoteString,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    boxW: RemoteFloat,
    boxH: RemoteFloat,
  ) {
    val bmpUsage = tt.bitmapFont
    if (bmpUsage != null) {
      val compiledBmpFont = resolveBitmapFont(bmpUsage.family)
      if (compiledBmpFont != null) {
        renderBitmapFontString(
          textState = textState,
          bmpFont = compiledBmpFont,
          targetSize = bmpUsage.size,
          colorRaw = bmpUsage.color,
          align = tt.align,
          compiler = compiler,
          alphaFactor = alphaFactor,
          inheritedTint = inheritedTint,
          boxW = boxW,
          boxH = boxH,
        )
        return
      }
    }
    drawStyledTextInBox(
      text = textState,
      fontSpec = tt.font,
      align = tt.align,
      compiler = compiler,
      alphaFactor = alphaFactor,
      inheritedTint = inheritedTint,
      boxW = boxW,
      boxH = boxH,
    )
  }

  private fun RemoteDrawScope.renderBitmapFontString(
    textState: RemoteString,
    bmpFont: CompiledBitmapFont,
    targetSize: Float,
    colorRaw: String?,
    align: DwfHorizontalAlign,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    boxW: RemoteFloat,
    boxH: RemoteFloat,
  ) {
    val scaleFactor = (targetSize / bmpFont.nominalHeight.coerceAtLeast(1f)).coerceAtLeast(0.05f)
    val unscaledW =
      if (textState.constantValueOrNull != null) {
        bmpFont.remoteFont.measureWidth(textState, 0f.rf)
      } else if (bmpFont.hasMultiCharWords) {
        textState.isEmpty.select(0f.rf, bmpFont.avgGlyphWidth.rf)
      } else {
        textState.length.toRemoteFloat() * bmpFont.avgGlyphWidth.rf
      }
    val measuredW = unscaledW * scaleFactor.rf
    val xPos =
      when (align) {
        DwfHorizontalAlign.START -> 0f.rf
        DwfHorizontalAlign.CENTER -> (boxW - measuredW) / 2f.rf
        DwfHorizontalAlign.END -> boxW - measuredW
      }
    val yPos = (boxH - targetSize.rf) / 2f.rf
    val tintColor = inheritedTint ?: colorRaw?.let { compiler.compileColor(it) }
    val paint = RemotePaint {
      isAntiAlias = true
      color = applyAlpha(RemoteColor(0xFFFFFFFF.toInt()), alphaFactor)
      if (tintColor != null) {
        colorFilter =
          RemoteBlendModeColorFilter(applyAlpha(tintColor, alphaFactor), RemoteBlendMode.SrcIn)
      }
    }
    translate(xPos, yPos) {
      scale(scaleFactor.rf, scaleFactor.rf, RemoteOffset(0f.rf, 0f.rf)) {
        remoteCanvas.drawBitmapFontTextRun(
          textState,
          bmpFont.remoteFont,
          0,
          -1,
          0f.rf,
          0f.rf,
          0f.rf,
          paint,
        )
      }
    }
  }

  private fun RemoteDrawScope.drawStyledTextInBox(
    text: RemoteString,
    fontSpec: DwfFontSpec,
    align: DwfHorizontalAlign,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
    boxW: RemoteFloat,
    boxH: RemoteFloat,
  ) {
    val sizePx =
      compiler.resolveTransformedFloat(
        fontSpec.size,
        "size",
        fontSpec.transforms,
        fontSpec.variants,
      )
    val baseColor =
      inheritedTint
        ?: compiler.resolveTransformedColor(
          baseColorRaw = fontSpec.color,
          targetName = "color",
          transforms = fontSpec.transforms,
          variants = fontSpec.variants,
        )
    val finalColor = applyAlpha(baseColor, alphaFactor)
    val paint = buildTextPaint(fontSpec, sizePx, finalColor)

    val (anchorX, panX) =
      when (align) {
        DwfHorizontalAlign.START -> 0f.rf to (-1f).rf
        DwfHorizontalAlign.CENTER -> (boxW / 2f.rf) to 0f.rf
        DwfHorizontalAlign.END -> boxW to 1f.rf
      }
    val anchorY = boxH / 2f.rf
    val panY = 0f.rf
    val flags = if (text.constantValueOrNull == null) RcDrawTextAnchored.MEASURE_EVERY_TIME else 0
    if (fontSpec.outlineColor != null && fontSpec.outlineWidth > 0f) {
      val outBase = compiler.compileColor(fontSpec.outlineColor)
      val outColor = applyAlpha(outBase, alphaFactor)
      val outPaint = buildTextOutlinePaint(fontSpec, sizePx, outColor, fontSpec.outlineWidth.rf)
      remoteCanvas.drawAnchoredText(text, anchorX, anchorY, panX, panY, flags, outPaint)
    }
    remoteCanvas.drawAnchoredText(text, anchorX, anchorY, panX, panY, flags, paint)
  }

  private fun buildMaskClipPath(node: DwfSceneNode): RemotePath? {
    when (node) {
      is DwfSceneNode.PartDraw -> {
        val ox = node.geometry.x
        val oy = node.geometry.y
        val shape = node.shapes.firstOrNull()?.primitive ?: return null
        return when (shape) {
          is DwfDrawPrimitive.Ellipse ->
            buildOvalPath(
              ox + shape.x,
              oy + shape.y,
              ox + shape.x + shape.width,
              oy + shape.y + shape.height,
            )
          is DwfDrawPrimitive.RoundRectangle ->
            buildRoundRectPath(
              ox + shape.x,
              oy + shape.y,
              ox + shape.x + shape.width,
              oy + shape.y + shape.height,
              shape.cornerRadiusX,
              shape.cornerRadiusY,
            )
          is DwfDrawPrimitive.Rectangle ->
            buildRoundRectPath(
              ox + shape.x,
              oy + shape.y,
              ox + shape.x + shape.width,
              oy + shape.y + shape.height,
              0f,
              0f,
            )
          else -> null
        }
      }
      is DwfSceneNode.PartImage -> {
        val resName = node.resource ?: node.resourceChoices.firstOrNull() ?: return null
        val bmp = resources.resolveImage(resName) ?: return null
        val pm = runCatching { bmp.toPixelMap() }.getOrNull() ?: return null
        if (pm.width <= 0 || pm.height <= 0) return null
        val dstX = node.geometry.x
        val dstY = node.geometry.y
        val dstW = if (node.geometry.width > 0f) node.geometry.width else pm.width.toFloat()
        val dstH = if (node.geometry.height > 0f) node.geometry.height else pm.height.toFloat()
        val sx = dstW / pm.width.toFloat()
        val sy = dstH / pm.height.toFloat()
        val step = (pm.height / 120).coerceAtLeast(1)
        val leftPts = ArrayList<Pair<Float, Float>>()
        val rightPts = ArrayList<Pair<Float, Float>>()
        var row = 0
        while (row < pm.height) {
          var firstX = -1
          var lastX = -1
          for (col in 0 until pm.width) {
            if (pm[col, row].alpha >= 0.45f) {
              if (firstX == -1) firstX = col
              lastX = col
            }
          }
          if (firstX >= 0) {
            val y = dstY + (row + 0.5f) * sy
            leftPts += (dstX + firstX * sx) to y
            rightPts += (dstX + (lastX + 1) * sx) to y
          }
          row += step
        }
        if (rightPts.size < 3) return null
        return RemotePath().apply {
          moveTo(rightPts[0].first, rightPts[0].second)
          for (i in 1 until rightPts.size) {
            lineTo(rightPts[i].first, rightPts[i].second)
          }
          for (i in leftPts.indices.reversed()) {
            lineTo(leftPts[i].first, leftPts[i].second)
          }
          close()
        }
      }
      else -> return null
    }
  }

  private fun RemoteDrawScope.renderCircularText(
    spec: DwfTextCircularSpec,
    compiler: DwfExpressionCompiler,
    alphaFactor: RemoteFloat,
    inheritedTint: RemoteColor?,
  ) {
    val fontSpec = spec.font ?: DwfFontSpec()
    val elements =
      if (spec.font != null && spec.font.elements.isNotEmpty()) spec.font.elements
      else spec.elements
    val text = compiler.compileTextElements(elements)
    val cx =
      compiler.resolveTransformedFloat(spec.centerX, "centerX", spec.transforms, spec.variants)
    val cy =
      compiler.resolveTransformedFloat(spec.centerY, "centerY", spec.transforms, spec.variants)
    val w = compiler.resolveTransformedFloat(spec.width, "width", spec.transforms, spec.variants)
    val h = compiler.resolveTransformedFloat(spec.height, "height", spec.transforms, spec.variants)
    val radius = (w + h) / 4f.rf
    val startDwf =
      compiler.resolveTransformedFloat(
        spec.startAngle,
        "startAngle",
        spec.transforms,
        spec.variants,
      )
    val endDwf =
      compiler.resolveTransformedFloat(spec.endAngle, "endAngle", spec.transforms, spec.variants)

    // For CENTER alignment on an arc [startAngle..endAngle], anchor at the midpoint angle.
    val anchorAngleDwf =
      when (spec.align) {
        DwfHorizontalAlign.START -> startDwf
        DwfHorizontalAlign.CENTER ->
          if (spec.startAngle == 0f && spec.endAngle == 360f) startDwf
          else (startDwf + endDwf) / 2f.rf
        DwfHorizontalAlign.END -> endDwf
      }
    val rcAngle = anchorAngleDwf - 90f.rf

    val sizePx =
      compiler.resolveTransformedFloat(
        fontSpec.size,
        "size",
        fontSpec.transforms,
        fontSpec.variants,
      )
    val baseColor =
      inheritedTint
        ?: compiler.resolveTransformedColor(
          baseColorRaw = fontSpec.color,
          targetName = "color",
          transforms = fontSpec.transforms,
          variants = fontSpec.variants,
        )
    val paint = buildTextPaint(fontSpec, sizePx, applyAlpha(baseColor, alphaFactor))
    val alignment =
      when (spec.align) {
        DwfHorizontalAlign.START -> RemoteDrawScope.CircularAlignment.Start
        DwfHorizontalAlign.CENTER -> RemoteDrawScope.CircularAlignment.Center
        DwfHorizontalAlign.END -> RemoteDrawScope.CircularAlignment.End
      }
    val placement =
      if (spec.direction == DwfArcDirection.CLOCKWISE) {
        RemoteDrawScope.CircularPlacement.Outside
      } else {
        RemoteDrawScope.CircularPlacement.Inside
      }
    drawTextOnCircle(
      text,
      cx,
      cy,
      radius,
      rcAngle,
      0f.rf,
      paint,
      alignment,
      placement,
    )
  }

  private fun applyEllipsisIfNeeded(
    text: RemoteString,
    fontSizePx: Float,
    boxW: RemoteFloat,
  ): RemoteString {
    val widthConst = boxW.constantValueOrNull ?: return text
    if (widthConst <= 0f || fontSizePx <= 0f) return text
    val maxChars = kotlin.math.floor(widthConst / (fontSizePx * 0.45f)).toInt()
    if (maxChars < 4) return text
    val keepChars = (maxChars - 2).coerceAtLeast(1)
    text.constantValueOrNull?.let { constStr ->
      return if (constStr.length > maxChars) {
        (constStr.substring(0, keepChars) + "...").rs
      } else {
        text
      }
    }
    val safeEnd = min(text.length.toRemoteFloat(), keepChars.toFloat().rf).toRemoteInt()
    val truncated = text.substring(0, safeEnd) + "...".rs
    return text.length.isGreaterThan(RemoteInt(maxChars)).select(truncated, text)
  }

  private fun resolveTypeface(fontSpec: DwfFontSpec): RemoteTypeface {
    val weightInt = parseFontWeight(fontSpec.weight)
    val italic = fontSpec.slant.equals("ITALIC", ignoreCase = true)
    val resolvedFamily =
      when (fontSpec.family.trim().uppercase()) {
        "SYNC_TO_DEVICE",
        "DEFAULT",
        "SANS-SERIF",
        "SANS_SERIF",
        "ROBOTO",
        "ROBOTO_FLEX",
        "ROBOTO FLEX",
        "" -> "default"
        "MONOSPACE" -> "monospace"
        "SERIF" -> "serif"
        else ->
          resources.resolveFontFamily(fontSpec.family) ?: normalizeDwfResourceKey(fontSpec.family)
      }
    return RemoteTypeface.Named(resolvedFamily, weightInt, italic)
  }

  private fun buildTextPaint(
    fontSpec: DwfFontSpec,
    sizePx: RemoteFloat,
    finalColor: RemoteColor,
  ): RemotePaint {
    val tf = resolveTypeface(fontSpec)
    return RemotePaint {
      isAntiAlias = true
      style = RemotePaintingStyle.Fill
      color = finalColor
      textSize = sizePx
      typeface = tf
    }
  }

  private fun buildTextOutlinePaint(
    fontSpec: DwfFontSpec,
    sizePx: RemoteFloat,
    finalColor: RemoteColor,
    strokeWidthPx: RemoteFloat,
  ): RemotePaint {
    val tf = resolveTypeface(fontSpec)
    return RemotePaint {
      isAntiAlias = true
      style = RemotePaintingStyle.Stroke
      strokeWidth = strokeWidthPx
      color = finalColor
      textSize = sizePx
      typeface = tf
    }
  }

  private fun RemoteDrawScope.renderImageOrVector(
    resName: String,
    alphaFactor: RemoteFloat,
    tint: RemoteColor?,
    dstW: RemoteFloat,
    dstH: RemoteFloat,
  ) {
    tryRenderImageOrVector(resName, alphaFactor, tint, dstW, dstH)
  }

  private fun RemoteDrawScope.tryRenderImageOrVector(
    resName: String,
    alphaFactor: RemoteFloat,
    tint: RemoteColor?,
    dstW: RemoteFloat,
    dstH: RemoteFloat,
  ): Boolean {
    val bmp = resolveRemoteBitmap(resName)
    if (bmp != null) {
      val paint = RemotePaint {
        isAntiAlias = true
        color = applyAlpha(RemoteColor(0xFFFFFFFF.toInt()), alphaFactor)
        if (tint != null) {
          colorFilter =
            RemoteBlendModeColorFilter(applyAlpha(tint, alphaFactor), RemoteBlendMode.SrcIn)
        }
      }
      remoteCanvas.drawScaledBitmap(
        bmp,
        0f.rf,
        0f.rf,
        bmp.width,
        bmp.height,
        0f.rf,
        0f.rf,
        dstW,
        dstH,
        ImageScaling.SCALE_FILL_BOUNDS,
        1f.rf,
        null,
        paint,
      )
      return true
    }
    val vec =
      resources.resolveVectorDrawable(resName) ?: resolveBuiltinDwfComplicationVector(resName)
    if (vec != null) {
      renderVectorDrawable(vec, alphaFactor, tint, dstW, dstH)
      return true
    }
    return false
  }

  private fun RemoteDrawScope.renderVectorDrawable(
    vec: DwfVectorDrawable,
    alphaFactor: RemoteFloat,
    overrideTint: RemoteColor?,
    dstW: RemoteFloat,
    dstH: RemoteFloat,
  ) {
    val sx = dstW / vec.viewportWidth.coerceAtLeast(0.001f).rf
    val sy = dstH / vec.viewportHeight.coerceAtLeast(0.001f).rf
    val effectiveAlpha = alphaFactor * vec.alpha.rf
    val effectiveTint = overrideTint ?: vec.tintColor?.let(::RemoteColor)
    scale(sx, sy, RemoteOffset(0f.rf, 0f.rf)) {
      for (node in vec.nodes) {
        renderVectorNode(node, effectiveAlpha, effectiveTint)
      }
    }
  }

  private fun RemoteDrawScope.renderVectorNode(
    node: DwfVectorDrawable.Node,
    alphaFactor: RemoteFloat,
    tint: RemoteColor?,
  ) {
    when (node) {
      is DwfVectorDrawable.Node.GroupNode -> {
        val pivot = RemoteOffset(node.pivotX.rf, node.pivotY.rf)
        translate(node.translateX.rf, node.translateY.rf) {
          rotate(node.rotation.rf, pivot) {
            scale(node.scaleX.rf, node.scaleY.rf, pivot) {
              for (child in node.children) {
                renderVectorNode(child, alphaFactor, tint)
              }
            }
          }
        }
      }
      is DwfVectorDrawable.Node.PathNode -> {
        val rcPath = runCatching { RemotePath(node.pathData) }.getOrNull() ?: return
        val fillType =
          if (node.evenOddFill) RemotePathFillType.EvenOdd else RemotePathFillType.NonZero
        if (node.fillColor != null) {
          val baseCol = tint ?: RemoteColor(node.fillColor)
          val fillPaint = RemotePaint {
            isAntiAlias = true
            style = RemotePaintingStyle.Fill
            color = applyAlpha(baseCol, alphaFactor * node.fillAlpha.rf)
          }
          remoteCanvas.drawPath(rcPath, fillType, fillPaint)
        }
        if (node.strokeColor != null && node.strokeWidth > 0f) {
          val baseCol = tint ?: RemoteColor(node.strokeColor)
          val strokePaint = RemotePaint {
            isAntiAlias = true
            style = RemotePaintingStyle.Stroke
            strokeWidth = node.strokeWidth.rf
            strokeCap = toRemoteStrokeCap(node.strokeLineCap)
            color = applyAlpha(baseCol, alphaFactor * node.strokeAlpha.rf)
          }
          remoteCanvas.drawPath(rcPath, fillType, strokePaint)
        }
      }
    }
  }

  private fun applyAlpha(base: RemoteColor, alphaFactor: RemoteFloat): RemoteColor {
    val constFactor = alphaFactor.constantValueOrNull
    if (constFactor != null && constFactor >= 0.999f) return base
    val constArgb = base.constantValueOrNull
    if (constArgb != null && constFactor != null) {
      val origA = (constArgb ushr 24) and 0xFF
      val newA = (origA * constFactor).toInt().coerceIn(0, 255)
      return RemoteColor((newA shl 24) or (constArgb and 0x00FFFFFF))
    }
    return base.copy(alpha = clamp(base.alpha * alphaFactor, 0f.rf, 1f.rf))
  }

  private fun toRemoteStrokeCap(cap: DwfStrokeCap): RemoteStrokeCap =
    when (cap) {
      DwfStrokeCap.BUTT -> RemoteStrokeCap.Butt
      DwfStrokeCap.ROUND -> RemoteStrokeCap.Round
      DwfStrokeCap.SQUARE -> RemoteStrokeCap.Square
    }

  private fun parseFontWeight(raw: String): Int {
    raw.trim().toIntOrNull()?.let {
      return it.coerceIn(100, 900)
    }
    return when (raw.trim().uppercase()) {
      "THIN" -> 100
      "ULTRA_LIGHT",
      "EXTRA_LIGHT" -> 200
      "LIGHT" -> 300
      "NORMAL",
      "REGULAR" -> 400
      "MEDIUM" -> 500
      "SEMI_BOLD",
      "DEMI_BOLD" -> 600
      "BOLD" -> 700
      "ULTRA_BOLD",
      "EXTRA_BOLD" -> 800
      "BLACK",
      "HEAVY" -> 900
      else -> 400
    }
  }

  private fun buildOvalPath(left: Float, top: Float, right: Float, bottom: Float): RemotePath {
    val k = 0.5522848f
    val rx = (right - left) / 2f
    val ry = (bottom - top) / 2f
    val cx = left + rx
    val cy = top + ry
    return RemotePath().apply {
      moveTo(cx, top)
      cubicTo(cx + rx * k, top, right, cy - ry * k, right, cy)
      cubicTo(right, cy + ry * k, cx + rx * k, bottom, cx, bottom)
      cubicTo(cx - rx * k, bottom, left, cy + ry * k, left, cy)
      cubicTo(left, cy - ry * k, cx - rx * k, top, cx, top)
      close()
    }
  }

  private fun buildRoundRectPath(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radiusX: Float,
    radiusY: Float,
  ): RemotePath {
    val k = 0.5522848f
    val rx = radiusX.coerceIn(0f, (right - left) / 2f)
    val ry = radiusY.coerceIn(0f, (bottom - top) / 2f)
    return RemotePath().apply {
      moveTo(left + rx, top)
      lineTo(right - rx, top)
      cubicTo(right - rx * (1f - k), top, right, top + ry * (1f - k), right, top + ry)
      lineTo(right, bottom - ry)
      cubicTo(right, bottom - ry * (1f - k), right - rx * (1f - k), bottom, right - rx, bottom)
      lineTo(left + rx, bottom)
      cubicTo(left + rx * (1f - k), bottom, left, bottom - ry * (1f - k), left, bottom - ry)
      lineTo(left, top + ry)
      cubicTo(left, top + ry * (1f - k), left + rx * (1f - k), top, left + rx, top)
      close()
    }
  }
}
