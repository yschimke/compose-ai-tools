package ee.schimke.composeai.fakeemulator

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/** Logical display geometry the fake emulator advertises (ADB `wm size`, gRPC display config). */
data class DisplaySize(val width: Int, val height: Int, val densityDpi: Int = 420)

/**
 * One display frame. [png] is the encoded image; [seq] is monotonic per source so consumers can
 * drop stale frames. This is the unit both the ADB `screencap` path and the gRPC screenshot stream
 * serve.
 */
class EmulatorFrame(val width: Int, val height: Int, val png: ByteArray, val seq: Long)

/**
 * The emulator's "screen". Pull the latest frame ([latest], used by `screencap` / `getScreenshot`)
 * or [subscribe] to pushed frames (used by the gRPC `streamScreenshot` video lane). Implementations
 * must be safe for concurrent calls.
 *
 * `:fake-emulator-core` ships the in-memory [MutableFrameSource]; `:fake-emulator` ships a
 * RenderSession-backed source that republishes daemon `streamFrame`s as [EmulatorFrame]s.
 */
interface FrameSource {
  val display: DisplaySize

  /** The most recent frame, or `null` if none has been produced yet. */
  fun latest(): EmulatorFrame?

  /**
   * Register [sink] for subsequent frames. The current [latest] (if any) is delivered synchronously
   * before returning so a new subscriber paints immediately. Closing the returned handle detaches.
   */
  fun subscribe(sink: (EmulatorFrame) -> Unit): AutoCloseable
}

/** Thread-safe in-memory [FrameSource]. Producers call [push]; consumers pull/subscribe. */
class MutableFrameSource(override val display: DisplaySize) : FrameSource {
  @Volatile private var current: EmulatorFrame? = null
  private val sinks = CopyOnWriteArrayList<(EmulatorFrame) -> Unit>()

  fun push(frame: EmulatorFrame) {
    current = frame
    for (sink in sinks) runCatching { sink(frame) }
  }

  /** Convenience: encode [png] as the next frame using [display]'s geometry. */
  fun push(png: ByteArray, seq: Long) {
    push(EmulatorFrame(display.width, display.height, png, seq))
  }

  override fun latest(): EmulatorFrame? = current

  override fun subscribe(sink: (EmulatorFrame) -> Unit): AutoCloseable {
    sinks.add(sink)
    current?.let { runCatching { sink(it) } }
    return AutoCloseable { sinks.remove(sink) }
  }
}

/**
 * Tiny placeholder PNGs for the "no preview launched yet" / fallback screen. AWT works headless.
 */
object PlaceholderImage {
  fun solidPng(width: Int, height: Int, argb: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.color = java.awt.Color(argb, true)
      g.fillRect(0, 0, width, height)
    } finally {
      g.dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }
}
