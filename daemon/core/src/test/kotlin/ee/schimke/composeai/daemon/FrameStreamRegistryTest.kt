package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.StreamCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the per-stream state machine that drives `composestream/1` emission. These cover the
 * four buttery-rendering invariants that make the new protocol smoother than the existing `<img
 * src=…>` swap path:
 * - dedup heartbeats save the encode + base64 round-trip when bytes don't change;
 * - the fps gate caps emit cadence so a slider drag doesn't drown the writer queue;
 * - visibility=false downshifts to keyframes-only without tearing down the held session;
 * - flipping visibility back to true marks the next emitted frame as a keyframe so the client's
 *   `requestAnimationFrame` painter has a fresh anchor on scroll-into-view.
 */
class FrameStreamRegistryTest {

  private val constantBytes = byteArrayOf(1, 2, 3, 4, 5)
  private val readerThatAlwaysWorks: (String) -> ByteArray? = { constantBytes }

  @Test
  fun negotiate_codec_downgrades_unsupported_to_png() {
    val registry =
      FrameStreamRegistry(
        pngBytesReader = readerThatAlwaysWorks,
        supportedCodecs = setOf(StreamCodec.PNG),
      )
    assertEquals(StreamCodec.PNG, registry.negotiateCodec(StreamCodec.WEBP))
    assertEquals(StreamCodec.PNG, registry.negotiateCodec(null))
  }

  @Test
  fun negotiate_codec_passes_through_supported() {
    val registry =
      FrameStreamRegistry(
        pngBytesReader = readerThatAlwaysWorks,
        supportedCodecs = setOf(StreamCodec.PNG, StreamCodec.WEBP),
      )
    assertEquals(StreamCodec.WEBP, registry.negotiateCodec(StreamCodec.WEBP))
  }

  @Test
  fun first_frame_after_register_is_keyframe_with_payload() {
    val registry = FrameStreamRegistry(clock = { 0L }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)

    val frames = registry.consumeForPreview("preview-A", "/x.png", "hash-1", 320, 480)

    assertEquals(1, frames.size)
    val f = frames.single()
    assertEquals("s1", f.frameStreamId)
    assertEquals(1L, f.seq)
    assertTrue("first frame must be flagged as a keyframe", f.keyframe)
    assertEquals(StreamCodec.PNG, f.codec)
    assertNotNull("first frame must carry payload bytes", f.payloadBase64)
  }

  @Test
  fun byte_identical_followup_emits_unchanged_heartbeat() {
    val registry = FrameStreamRegistry(clock = { 0L }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)
    registry.consumeForPreview("preview-A", "/x.png", "hash-A", 1, 1)

    val frames = registry.consumeForPreview("preview-A", "/x.png", "hash-A", 1, 1)

    assertEquals(1, frames.size)
    val f = frames.single()
    assertEquals(2L, f.seq)
    assertNull("dedup heartbeat must not carry a codec", f.codec)
    assertNull("dedup heartbeat must not carry a payload", f.payloadBase64)
    assertFalse(f.keyframe)
    assertFalse(f.final)
  }

  @Test
  fun fps_cap_drops_frames_inside_min_interval() {
    var now = 1000L
    val registry = FrameStreamRegistry(clock = { now }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = 10) // 100ms min interval

    // First frame fires.
    val a = registry.consumeForPreview("preview-A", "/x.png", "hash-1", 1, 1)
    assertEquals(1, a.size)

    // 50ms later — under the cap, must drop entirely.
    now += 50
    val b = registry.consumeForPreview("preview-A", "/x.png", "hash-2", 1, 1)
    assertTrue("frame inside the fps min-interval must be dropped, got $b", b.isEmpty())

    // 60ms more — total 110ms since first; over the cap, must emit.
    now += 60
    val c = registry.consumeForPreview("preview-A", "/x.png", "hash-3", 1, 1)
    assertEquals(1, c.size)
    assertEquals(2L, c.single().seq) // seq increments only on emitted frames
  }

  @Test
  fun visibility_off_throttles_to_one_fps_by_default() {
    var now = 0L
    val registry = FrameStreamRegistry(clock = { now }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)
    registry.consumeForPreview("preview-A", "/x.png", "h1", 1, 1) // first frame

    registry.setVisibility("s1", visible = false, fps = null)

    // 500ms later — under 1 fps cap, drop.
    now = 500
    val drop = registry.consumeForPreview("preview-A", "/x.png", "h2", 1, 1)
    assertTrue("hidden stream must drop sub-1Hz frame, got $drop", drop.isEmpty())

    // 1100ms later — over 1 fps cap, emit.
    now = 1100
    val emit = registry.consumeForPreview("preview-A", "/x.png", "h3", 1, 1)
    assertEquals(1, emit.size)
  }

  @Test
  fun visibility_returning_true_marks_next_frame_keyframe() {
    var now = 0L
    val registry = FrameStreamRegistry(clock = { now }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)
    registry.consumeForPreview("preview-A", "/x.png", "h1", 1, 1) // first keyframe

    now = 100
    val followup = registry.consumeForPreview("preview-A", "/x.png", "h2", 1, 1)
    assertFalse("subsequent frame is not a keyframe", followup.single().keyframe)

    registry.setVisibility("s1", visible = false, fps = null)
    registry.setVisibility("s1", visible = true, fps = null) // back into viewport

    now = 5000
    val onScrollBack = registry.consumeForPreview("preview-A", "/x.png", "h3", 1, 1)
    assertEquals(1, onScrollBack.size)
    assertTrue(
      "frame after scroll-back-into-view must be flagged keyframe so the client repaints",
      onScrollBack.single().keyframe,
    )
  }

  @Test
  fun stop_emits_final_frame_marker_then_idempotent() {
    val registry = FrameStreamRegistry(clock = { 0L }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)
    registry.consumeForPreview("preview-A", "/x.png", "h1", 1, 1)

    val finalFrame = registry.finalFrameOnStop("s1")
    assertNotNull(finalFrame)
    assertTrue("final marker must carry final=true", finalFrame!!.final)
    assertNull(finalFrame.codec)
    assertNull(finalFrame.payloadBase64)

    val unregistered = registry.unregister("s1")
    assertNotNull(unregistered)

    // Subsequent stop is a no-op on an unknown id.
    val secondStop = registry.finalFrameOnStop("s1")
    assertNull(secondStop)
    val secondUnregister = registry.unregister("s1")
    assertNull(secondUnregister)
  }

  @Test
  fun multiple_streams_on_same_preview_get_independent_seq_and_dedup() {
    val registry = FrameStreamRegistry(clock = { 0L }, pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)
    registry.register("s2", "preview-A", StreamCodec.PNG, maxFps = null)

    val frames = registry.consumeForPreview("preview-A", "/x.png", "h1", 1, 1)
    assertEquals(2, frames.size)
    val byStream = frames.associateBy { it.frameStreamId }
    assertEquals(1L, byStream["s1"]!!.seq)
    assertEquals(1L, byStream["s2"]!!.seq)
    // Each stream's first frame is a keyframe.
    assertTrue(byStream["s1"]!!.keyframe)
    assertTrue(byStream["s2"]!!.keyframe)
  }

  @Test
  fun unreadable_png_path_emits_heartbeat_not_error() {
    val registry =
      FrameStreamRegistry(
        clock = { 0L },
        pngBytesReader = { _ -> null }, // simulates file that vanished or stub host
      )
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)

    val frames = registry.consumeForPreview("preview-A", "/missing.png", "hash-x", 1, 1)
    assertEquals(1, frames.size)
    val f = frames.single()
    // No bytes available → degrade to heartbeat. The legacy renderFinished path still flows.
    assertNull(f.codec)
    assertNull(f.payloadBase64)
  }

  @Test
  fun has_streams_for_returns_false_for_unrelated_preview() {
    val registry = FrameStreamRegistry(pngBytesReader = readerThatAlwaysWorks)
    registry.register("s1", "preview-A", StreamCodec.PNG, maxFps = null)
    assertTrue(registry.hasStreamsFor("preview-A"))
    assertFalse(registry.hasStreamsFor("preview-B"))
    registry.unregister("s1")
    assertFalse(registry.hasStreamsFor("preview-A"))
  }
}
