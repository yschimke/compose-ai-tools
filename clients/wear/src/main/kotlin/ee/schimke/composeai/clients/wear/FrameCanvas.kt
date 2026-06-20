package ee.schimke.composeai.clients.wear

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import ee.schimke.composeai.clients.InputEvent
import ee.schimke.composeai.clients.StreamFrame

/**
 * Watch-shaped frame surface: paints the latest [StreamFrame] fit-to-screen, forwards taps as
 * `click` in image-natural pixels, and — the wear-native bit — forwards rotary-bezel / crown turns
 * as [InputEvent.rotaryScroll] so scrolling the remote composition works from the watch.
 */
@Composable
fun FrameCanvas(frame: StreamFrame?, modifier: Modifier = Modifier, onInput: (InputEvent) -> Unit) {
  var viewSize by remember { mutableStateOf(IntSize.Zero) }
  val bitmap: ImageBitmap? = remember(frame) { frame?.let { decodeFrame(it) } }
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

  Box(
    modifier
      .onSizeChanged { viewSize = it }
      .onRotaryScrollEvent {
        onInput(InputEvent.rotaryScroll(it.verticalScrollPixels))
        true
      }
      .focusRequester(focusRequester)
      .focusable()
      .pointerInput(frame?.seq, viewSize) {
        detectTapGestures { offset ->
          val f = frame ?: return@detectTapGestures
          if (viewSize.width == 0 || viewSize.height == 0) return@detectTapGestures
          val (px, py) =
            InputEvent.scalePointer(
              offset.x,
              offset.y,
              viewSize.width.toFloat(),
              viewSize.height.toFloat(),
              f.widthPx,
              f.heightPx,
            )
          onInput(InputEvent.click(px, py))
        }
      }
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = "Live preview frame",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    }
  }
}

internal fun decodeFrame(frame: StreamFrame): ImageBitmap? =
  runCatching { BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)?.asImageBitmap() }
    .getOrNull()
