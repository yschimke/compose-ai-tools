package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServeRenderHostTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-host").toFile().also { it.deleteOnExit() }

  private fun host(session: RenderSession): ServeRenderHost =
    ServeRenderHost(
      session = session,
      previews = listOf(ServePreview(previewId, "Red")),
      renderTimeoutSeconds = 30,
    )

  @Test
  fun `identical requests are served from cache after one render`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val first = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      val second = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(first is RenderOutcome.Ok)
      assertTrue(second is RenderOutcome.Ok)
      assertContentEquals(first.png, second.png)
      assertEquals(1, session.renderCount.get(), "second identical request must hit the cache")
    }
  }

  @Test
  fun `different overrides each render`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
      h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertEquals(2, session.renderCount.get())
    }
  }

  @Test
  fun `renderSvg returns the figma-svg for the given overrides`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val out = h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(out is SvgOutcome.Ok)
      assertEquals("svg:DARK:null:null", (out as SvgOutcome.Ok).svg.decodeToString())
    }
  }

  @Test
  fun `renderSvg serves identical requests from cache`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertEquals(1, session.renderCount.get(), "second identical SVG request must hit the cache")
    }
  }

  @Test
  fun `renderSvg is not stale when the png for those overrides is already cached`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      // Cache the dark PNG, then render light — the shared per-preview SVG file is now light's.
      h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
      // A dark SVG request must re-render dark, not return the shared file's stale light SVG.
      val out = h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(out is SvgOutcome.Ok)
      assertEquals("svg:DARK:null:null", (out as SvgOutcome.Ok).svg.decodeToString())
    }
  }

  @Test
  fun `renderSvg inlines hybrid figma-raster crops as data URIs`() {
    val session = FakeRenderSession(newRenderRoot(), hybridSvg = true)
    host(session).use { h ->
      val out = h.renderSvg(previewId, PreviewOverrides())
      assertTrue(out is SvgOutcome.Ok)
      val svg = (out as SvgOutcome.Ok).svg.decodeToString()
      val expected = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
      assertTrue(svg.contains("data:image/png;base64,$expected"), "raster inlined: $svg")
      assertTrue(!svg.contains("figma-raster/"), "no dangling external ref remains: $svg")
    }
  }

  @Test
  fun `concurrent identical requests coalesce to a single render`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val threads = 16
      val pool = Executors.newFixedThreadPool(threads)
      val start = CountDownLatch(1)
      val results = CopyOnWriteArrayList<RenderOutcome>()
      repeat(threads) {
        pool.submit {
          start.await()
          results.add(h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK)))
        }
      }
      start.countDown()
      pool.shutdown()
      assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "renders did not finish")

      assertEquals(threads, results.size)
      assertTrue(results.all { it is RenderOutcome.Ok })
      assertEquals(1, session.renderCount.get(), "identical concurrent renders must coalesce")
    }
  }

  @Test
  fun `unknown preview id is NotFound without rendering`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertEquals(RenderOutcome.NotFound, h.render("com.example.Missing", PreviewOverrides()))
      assertEquals(0, session.renderCount.get())
    }
  }

  @Test
  fun `a coalesced override render is retried until accepted, not failed`() {
    // The daemon coalesce-rejects an override-bearing render whose previewId is already in flight,
    // expecting the client to resubmit. ServeRenderHost must retry rather than surface a 500.
    val session = FakeRenderSession(newRenderRoot(), coalescedOverrideRejections = 2)
    host(session).use { h ->
      val outcome = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(outcome is RenderOutcome.Ok, "coalesced rejections must be retried until accepted")
      // 2 coalesced rejections + 1 accepted render = 3 renderNow calls.
      assertEquals(3, session.renderCount.get())
    }
  }

  @Test
  fun `a late renderFinished from a timed-out render does not corrupt the next render`() {
    // Render 1 emits nothing → it times out (the daemon still owes a late renderFinished). Render 2
    // (the daemon catching up) emits the timed-out render's STALE event first, then its own FRESH
    // event. The stale one must be drained, not cached/served under render 2's override key.
    val session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { call, emit ->
          if (call == 2) {
            emit("STALE".toByteArray())
            emit("FRESH".toByteArray())
          }
          // call 1: emit nothing → render times out
        },
      )
    ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(previewId, "Red")),
        renderTimeoutSeconds = 1,
      )
      .use { h ->
        val first = h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
        assertTrue(first is RenderOutcome.Failed, "first render should time out, got $first")

        val second = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
        assertTrue(second is RenderOutcome.Ok, "second render should succeed, got $second")
        assertEquals(
          "FRESH",
          second.png.decodeToString(),
          "stale event from the timed-out render must not be served for the new overrides",
        )
      }
  }

  @Test
  fun `a rejected render surfaces as Failed`() {
    val session = FakeRenderSession(newRenderRoot(), rejectAll = true)
    host(session).use { h ->
      val outcome = h.render(previewId, PreviewOverrides())
      assertTrue(outcome is RenderOutcome.Failed, "expected Failed, got $outcome")
    }
  }
}
