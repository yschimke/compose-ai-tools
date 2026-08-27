package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeRenderHostStreamTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-host-stream").toFile().also { it.deleteOnExit() }

  private fun host(session: FakeRenderSession): ServeRenderHost =
    ServeRenderHost(session, listOf(ServePreview(previewId, "Red")), renderTimeoutSeconds = 30)

  @Test
  fun `startStream returns null when the backend does not support streaming`() {
    val session = FakeRenderSession(newRenderRoot()) // streaming = false
    host(session).use { h -> assertNull(h.startStream(previewId, PreviewOverrides()) {}) }
  }

  @Test
  fun `startStream forwards frames for its own frameStreamId only`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val frames = CopyOnWriteArrayList<StreamFrameParams>()
      val handle = assertNotNull(h.startStream(previewId, PreviewOverrides()) { frames.add(it) })
      val fsid = assertNotNull(session.lastFrameStreamId)

      session.emitStreamFrame(fsid, seq = 0, payloadBase64 = "AAAA")
      session.emitStreamFrame("some-other-stream", seq = 1, payloadBase64 = "BBBB")

      assertEquals(1, frames.size, "only this stream's frames should be delivered")
      assertEquals(0L, frames[0].seq)
      handle.close()
    }
  }

  @Test
  fun `closing the handle stops the stream and unsubscribes`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val frames = CopyOnWriteArrayList<StreamFrameParams>()
      val handle = assertNotNull(h.startStream(previewId, PreviewOverrides()) { frames.add(it) })
      val fsid = assertNotNull(session.lastFrameStreamId)

      handle.input(InteractiveInputKind.CLICK, pixelX = 3, pixelY = 4)
      assertEquals(1, session.interactiveInputs.size)
      assertEquals(InteractiveInputKind.CLICK, session.interactiveInputs[0].kind)

      handle.close()
      assertEquals(listOf(fsid), session.streamStops)

      session.emitStreamFrame(fsid, seq = 9, payloadBase64 = "CCCC")
      assertTrue(frames.isEmpty(), "frames after close must not be delivered")
    }
  }

  @Test
  fun `unknown preview id yields no stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      assertNull(h.startStream("com.example.Missing", PreviewOverrides()) {})
    }
  }

  @Test
  fun `a keyframe emitted before stream-start returns is not lost`() {
    // The daemon's frame loop can emit the initial keyframe before stream/start's RPC response —
    // the listener must already be registered (and buffer it) so it isn't dropped.
    val session =
      FakeRenderSession(newRenderRoot(), streaming = true, emitKeyframeOnStart = "KEYFRAME")
    host(session).use { h ->
      val frames = CopyOnWriteArrayList<StreamFrameParams>()
      assertNotNull(h.startStream(previewId, PreviewOverrides()) { frames.add(it) })
      assertEquals(1, frames.size, "the pre-response keyframe must be replayed, not lost")
      assertEquals(0L, frames[0].seq)
    }
  }

  @Test
  fun `codec and maxFps are forwarded to stream start`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      assertNotNull(h.startStream(previewId, PreviewOverrides(), StreamCodec.WEBP, 30) {})
      assertEquals(StreamCodec.WEBP, session.lastCodec)
      assertEquals(30, session.lastMaxFps)
    }
  }

  @Test
  fun `no held session falls back (null) and stops the stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true, heldSession = false)
    host(session).use { h ->
      assertNull(h.startStream(previewId, PreviewOverrides()) {})
      assertEquals(1, session.streamStops.size, "the frameless stream must be torn down")
    }
  }
}
