package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An edit rebuilt the classes behind a session: [ServeRenderHost.sourcesChanged] must make the next
 * request render them, not serve what the old classes drew.
 */
class ServeRenderHostSourcesChangedTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-host-reload").toFile().also { it.deleteOnExit() }

  private fun host(session: FakeRenderSession): ServeRenderHost =
    ServeRenderHost(session, listOf(ServePreview(previewId, "Red")), renderTimeoutSeconds = 30)

  @Test
  fun `the daemon loads the new classes and the next request renders again`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val before = h.render(previewId, PreviewOverrides())
      assertTrue(before is RenderOutcome.Ok)
      h.render(previewId, PreviewOverrides())
      assertEquals(1, session.renderCount.get(), "unchanged sources are served from the cache")

      assertTrue(h.sourcesChanged(listOf("/src/Red.kt", "/src/Theme.kt")))

      assertEquals(
        listOf("/src/Red.kt" to FileKind.SOURCE, "/src/Theme.kt" to FileKind.SOURCE),
        session.fileChanges,
      )
      val after = h.render(previewId, PreviewOverrides())
      assertTrue(after is RenderOutcome.Ok)
      assertEquals(2, session.renderCount.get(), "the cached render of the old classes is gone")
    }
  }

  @Test
  fun `unknown paths still swap the classes`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertTrue(h.sourcesChanged(emptyList()))
      assertEquals(1, session.fileChanges.size)
      assertEquals(FileKind.SOURCE, session.fileChanges.single().second)
    }
  }

  @Test
  fun `a fault in the old classes no longer latches the lane`() {
    // A linkage fault trips the breaker for good, but the fix is often the very edit being loaded.
    val broken = AtomicBoolean(true)
    lateinit var session: FakeRenderSession
    session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { _, emit ->
          if (broken.get()) session.emitFailed(previewId, "NoClassDefFoundError: com/example/Gone")
          else emit(byteArrayOf(1, 2, 3))
        },
      )
    host(session).use { h ->
      assertTrue(h.render(previewId, PreviewOverrides()) is RenderOutcome.Failed)
      assertNotNull(h.renderFailureLatch(previewId, PreviewOverrides()))

      broken.set(false)
      h.sourcesChanged(listOf("/src/Red.kt"))

      assertNull(h.renderFailureLatch(previewId, PreviewOverrides()))
      assertTrue(h.render(previewId, PreviewOverrides()) is RenderOutcome.Ok)
    }
  }

  @Test
  fun `a held stream reopens on the new classes and keeps its watcher`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val frames = CopyOnWriteArrayList<StreamFrameParams>()
      val handle =
        assertNotNull(
          h.subscribeStream(previewId, PreviewOverrides(), null, null, null) { frames += it }
        )
      val before = assertNotNull(session.lastFrameStreamId)

      h.sourcesChanged(listOf("/src/Red.kt"))

      assertEquals(2, session.streamStarts.get(), "the upstream is restarted")
      assertEquals(listOf(before), session.streamStops)
      val after = assertNotNull(session.lastFrameStreamId)
      assertNotEquals(before, after)
      assertEquals(1, h.activeStreamCount())
      session.emitStreamFrame(after, seq = 0, payloadBase64 = "AAAA")
      assertTrue(frames.any { it.payloadBase64 == "AAAA" }, "the same watcher sees new frames")

      handle.close()
      assertEquals(listOf(before, after), session.streamStops)
      assertFalse(h.activeStreamCount() > 0)
    }
  }
}
