package ee.schimke.composeai.clients.mobile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import ee.schimke.composeai.clients.InputEvent
import ee.schimke.composeai.clients.StreamFrame

/**
 * Paints the latest streamed [StreamFrame] full-bleed (letterboxed-fit) and turns touches on it into
 * [InputEvent]s in the frame's image-natural pixel space, forwarding them through [onInput]. A tap →
 * `click`; a drag → `pointerDown` / `pointerMove`(s) / `pointerUp`, so the remote composition's
 * gesture pipeline sees a real drag. This is the surface that makes the stream feel like a local app.
 */
@Composable
fun FrameCanvas(frame: StreamFrame?, modifier: Modifier = Modifier, onInput: (InputEvent) -> Unit) {
  var viewSize by remember { mutableStateOf(IntSize.Zero) }
  val bitmap: ImageBitmap? = remember(frame) { frame?.let { decodeFrame(it) } }

  fun toFramePixels(x: Float, y: Float): Pair<Int, Int>? {
    val f = frame ?: return null
    if (viewSize.width == 0 || viewSize.height == 0) return null
    return InputEvent.scalePointer(
      x,
      y,
      viewSize.width.toFloat(),
      viewSize.height.toFloat(),
      f.widthPx,
      f.heightPx,
    )
  }

  Box(
    modifier
      .onSizeChanged { viewSize = it }
      .pointerInput(frame?.seq, viewSize) {
        detectTapGestures { offset ->
          toFramePixels(offset.x, offset.y)?.let { (px, py) -> onInput(InputEvent.click(px, py)) }
        }
      }
      .pointerInput(frame?.seq, viewSize) {
        // Track the last position so drag end/cancel can emit the matching pointerUp — without it a
        // remote component that captured the press stays stuck down until a session reset.
        var last: Pair<Int, Int>? = null
        fun up() {
          last?.let { (px, py) ->
            onInput(InputEvent(InputEvent.Kind.POINTER_UP, pixelX = px, pixelY = py))
          }
          last = null
        }
        detectDragGestures(
          onDragStart = { offset ->
            toFramePixels(offset.x, offset.y)?.let {
              last = it
              onInput(InputEvent(InputEvent.Kind.POINTER_DOWN, pixelX = it.first, pixelY = it.second))
            }
          },
          onDragEnd = { up() },
          onDragCancel = { up() },
        ) { change, _ ->
          toFramePixels(change.position.x, change.position.y)?.let {
            last = it
            onInput(InputEvent(InputEvent.Kind.POINTER_MOVE, pixelX = it.first, pixelY = it.second))
          }
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

/** Decode a frame's encoded bytes (PNG/WEBP) into an [ImageBitmap], or null if undecodable. */
internal fun decodeFrame(frame: StreamFrame): ImageBitmap? =
  runCatching { BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)?.asImageBitmap() }
    .getOrNull()
