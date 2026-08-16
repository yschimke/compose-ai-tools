/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("RestrictedApiAndroidX")

package androidx.compose.remote.player.compose.embedded

import androidx.compose.remote.core.PaintContext
import androidx.compose.remote.core.RcPlatformServices
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.paint.PaintBundle

/**
 * A [PaintContext] that draws nothing, used to *evaluate* a value-producing
 * [ androidx.compose.remote.core.PaintOperation] in [GraphContext].
 *
 * Some ops publish their value from `paint(PaintContext)` rather than from `apply(RemoteContext)` —
 * `ColorAttribute` is the one that matters in practice: it reads
 * `paintContext.getContext().getColor(mColorId)`, decomposes it, and writes the channel back with
 * `loadFloat`. `PaintOperation.apply` forwards to `paint` only when the context reports
 * `ContextMode.PAINT` and hands back a non-null paint context, so calling `apply` on such an op
 * outside a draw pass is silently a no-op: it reads nothing and writes nothing, and the id it
 * produces resolves to 0. For a colour that is fully transparent, which is why a state-driven tint
 * disappeared from the embedded player while an adjacent literal one drew fine
 * (compose-ai-tools#3936).
 *
 * So the graph hands those ops a paint context whose *only* working member is [getContext] — every
 * drawing, clipping, matrix and layer call is dropped. The op reads through the [GraphContext] (so
 * Compose still discovers its inputs), writes through the [GraphContext] (so the write is captured
 * as the op's value instead of mutating the shared store), and its drawing side, if it has one,
 * goes nowhere. Evaluating a value must not paint; painting happens in the real draw pass, against
 * a real canvas.
 *
 * Text layout returns null and [getText] resolves through the context: an op that genuinely needs
 * to measure text cannot be evaluated this way, and gets nothing rather than a fabricated result.
 */
internal class GraphPaintContext(context: RemoteContext) : PaintContext(context) {

  override fun getText(id: Int): String? = mContext?.getText(id)

  override fun layoutComplexText(
    textId: Int,
    fontId: Int,
    fontStyle: Int,
    fontWeight: Int,
    fontFamilyId: Int,
    textAlign: Int,
    fontSize: Float,
    letterSpacing: Float,
    lineHeight: Float,
    width: Float,
    height: Float,
    maxLines: Int,
    overflow: Int,
    flags: Int,
    hinting: Boolean,
    ltr: Boolean,
    scaling: Int,
  ): RcPlatformServices.ComputedTextLayout? = null

  // Everything below is a draw/clip/matrix/layer instruction. Dropped by design — see the class
  // doc: this context exists to let an op compute, not to let it render.

  override fun drawBitmap(
    imageId: Int,
    srcLeft: Int,
    srcTop: Int,
    srcRight: Int,
    srcBottom: Int,
    dstLeft: Int,
    dstTop: Int,
    dstRight: Int,
    dstBottom: Int,
    cdId: Int,
  ) {}

  override fun drawBitmap(imageId: Int, left: Float, top: Float, right: Float, bottom: Float) {}

  override fun drawToBitmap(bitmapId: Int, mode: Int, color: Int) {}

  override fun scale(scaleX: Float, scaleY: Float) {}

  override fun translate(translateX: Float, translateY: Float) {}

  override fun drawArc(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    startAngle: Float,
    sweepAngle: Float,
  ) {}

  override fun drawSector(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    startAngle: Float,
    sweepAngle: Float,
  ) {}

  override fun drawCircle(centerX: Float, centerY: Float, radius: Float) {}

  override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {}

  override fun drawOval(left: Float, top: Float, right: Float, bottom: Float) {}

  override fun drawPath(id: Int, start: Float, end: Float) {}

  override fun drawRect(left: Float, top: Float, right: Float, bottom: Float) {}

  override fun drawRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radiusX: Float,
    radiusY: Float,
  ) {}

  override fun drawTextOnPath(textId: Int, pathId: Int, hOffset: Float, vOffset: Float) {}

  override fun getTextBounds(
    textId: Int,
    start: Int,
    end: Int,
    flags: Int,
    bounds: FloatArray,
  ) {}

  override fun drawTextRun(
    textId: Int,
    start: Int,
    end: Int,
    contextStart: Int,
    contextEnd: Int,
    x: Float,
    y: Float,
    rtl: Boolean,
  ) {}

  override fun drawComplexText(computedTextLayout: RcPlatformServices.ComputedTextLayout?) {}

  override fun drawTweenPath(
    path1Id: Int,
    path2Id: Int,
    tween: Float,
    start: Float,
    stop: Float,
  ) {}

  override fun tweenPath(out: Int, path1: Int, path2: Int, tween: Float) {}

  override fun combinePath(out: Int, path1: Int, path2: Int, operation: Byte) {}

  override fun matrixFromPath(pathId: Int, fraction: Float, offset: Float, flags: Int) {}

  override fun savePaint() {}

  override fun restorePaint() {}

  override fun replacePaint(paint: PaintBundle) {}

  override fun applyPaint(paint: PaintBundle) {}

  override fun matrixScale(scaleX: Float, scaleY: Float, centerX: Float, centerY: Float) {}

  override fun matrixTranslate(translateX: Float, translateY: Float) {}

  override fun matrixSkew(skewX: Float, skewY: Float) {}

  override fun matrixRotate(rotate: Float, pivotX: Float, pivotY: Float) {}

  override fun matrixSave() {}

  override fun matrixRestore() {}

  override fun clipRect(left: Float, top: Float, right: Float, bottom: Float) {}

  override fun clipPath(pathId: Int, regionOp: Int) {}

  override fun roundedClipRect(
    width: Float,
    height: Float,
    topStart: Float,
    topEnd: Float,
    bottomStart: Float,
    bottomEnd: Float,
  ) {}

  override fun startGraphicsLayer(w: Int, h: Int) {}

  override fun setGraphicsLayer(attributes: java.util.HashMap<Int, Any>) {}

  override fun endGraphicsLayer() {}

  override fun reset() {}
}
