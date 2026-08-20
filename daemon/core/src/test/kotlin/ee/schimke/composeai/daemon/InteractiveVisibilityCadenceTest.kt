package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.StreamCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `stream/visibility` throttles the **render** loop, not only the emit gate.
 *
 * The registry has always dropped frames for a stream nobody is looking at, but dropping them after
 * the render is most of the cost paid anyway: on the Android backend every tick of the live frame
 * loop is a Robolectric capture. [JsonRpcServer.interactiveFrameCadenceMs] therefore floors the
 * loop's own cadence at the stream's emit interval, so a hidden stream renders at its throttled fps
 * instead of four times a second.
 *
 * Driven directly, like [InteractiveIdleCadenceTest]: the cadence is a pure function of the loop's
 * state plus the registry's, so no daemon, sandbox or wall clock is involved.
 */
class InteractiveVisibilityCadenceTest {

  private object InertHost : RenderHost {
    override fun start() = Unit

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
      error("InertHost renders nothing")

    override fun shutdown(timeoutMs: Long) = Unit
  }

  private fun server(): JsonRpcServer =
    JsonRpcServer(
      input = java.io.ByteArrayInputStream(ByteArray(0)),
      output = java.io.ByteArrayOutputStream(),
      host = InertHost,
      daemonVersion = "test",
      onExit = {},
    )

  private fun JsonRpcServer.registerStream(maxFps: Int? = null): String {
    val id = streamRegistry.mintStreamId()
    streamRegistry.register(id, PREVIEW, StreamCodec.PNG, maxFps = maxFps)
    return id
  }

  @Test
  fun `a visible stream keeps the interactive cadence`() {
    val s = server()
    val stream = s.registerStream()
    assertEquals(
      JsonRpcServer.INTERACTIVE_FRAME_INTERVAL_MS,
      s.interactiveFrameCadenceMs(stream),
    )
  }

  @Test
  fun `hiding a stream slows the loop to the throttled fps`() {
    val s = server()
    val stream = s.registerStream()

    s.streamRegistry.setVisibility(stream, visible = false, fps = null)
    assertEquals(
      "the daemon's default throttle is 1 fps, so the loop must render once a second",
      1_000L,
      s.interactiveFrameCadenceMs(stream),
    )

    s.streamRegistry.setVisibility(stream, visible = false, fps = 2)
    assertEquals(500L, s.interactiveFrameCadenceMs(stream))

    s.streamRegistry.setVisibility(stream, visible = true, fps = null)
    assertEquals(
      JsonRpcServer.INTERACTIVE_FRAME_INTERVAL_MS,
      s.interactiveFrameCadenceMs(stream),
    )
  }

  @Test
  fun `a maxFps cap floors the loop too`() {
    // Rendering faster than the stream can ever emit is waste on exactly the same grounds as
    // rendering for a hidden one.
    val s = server()
    val stream = s.registerStream(maxFps = 2)
    assertEquals(500L, s.interactiveFrameCadenceMs(stream))
  }

  @Test
  fun `a stream the registry never saw is not floored`() {
    // `interactive/start` sessions have no frame stream and therefore no emit gate; they must keep
    // the cadence they had.
    val s = server()
    assertEquals(
      JsonRpcServer.INTERACTIVE_FRAME_INTERVAL_MS,
      s.interactiveFrameCadenceMs("no-such-stream"),
    )
  }

  private companion object {
    const val PREVIEW = "com.example.PreviewFn"
  }
}
