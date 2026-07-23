package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.PreviewSlotsPayload
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
  fun `renderFailed completes the wait immediately instead of sleeping out the budget`() {
    // Regression for the serve cold-render investigation: only `renderFinished` completed the
    // pending latch, so a preview whose render body threw (daemon sends `renderFailed` within
    // seconds) left the host sleeping out its ENTIRE render budget under renderLock — 180s per
    // broken-preview render on the CLI, 900s on the public server. Profiled on confetti-mobile:
    // this single behaviour was the whole "cold Android renders take minutes" symptom.
    lateinit var session: FakeRenderSession
    session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { _, _ -> session.emitFailed(previewId, "java.lang.NullPointerException") },
      )
    host(session).use { h ->
      val startedMs = System.currentTimeMillis()
      val outcome = h.render(previewId, PreviewOverrides())
      val tookMs = System.currentTimeMillis() - startedMs
      assertTrue(outcome is RenderOutcome.Failed, "expected Failed, got $outcome")
      assertTrue(
        outcome.reason.contains("NullPointerException"),
        "failure reason should carry the daemon's error message, got '${outcome.reason}'",
      )
      // The host is built with renderTimeoutSeconds = 30; anything near that means we slept out
      // the budget rather than completing on the failure event.
      assertTrue(tookMs < 10_000, "render should fail fast on renderFailed, took ${tookMs}ms")
      // A failed render proves nothing about warmth: the next render must still get the cold
      // budget, and a subsequent success must work unaffected.
      val ok =
        FakeRenderSession(newRenderRoot()).let { fresh ->
          host(fresh).use { it2 -> it2.render(previewId, PreviewOverrides()) }
        }
      assertTrue(ok is RenderOutcome.Ok)
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
  fun `a render backs off to Busy when the daemon lock is held, not blocking the render budget`() {
    // The host is built with renderTimeoutSeconds = 30. A cold render holding the per-daemon lock
    // for that long must NOT make a concurrent render block for the whole budget (which, on the
    // live server, pins a shared HTTP render slot and saturates the queue). It must back off to
    // Busy near the bounded wait instead.
    val firstHoldsLock = CountDownLatch(1)
    val release = CountDownLatch(1)
    val session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { call, emit ->
          if (call == 1) {
            // We're inside renderNow, i.e. under renderLock — signal, then block to model a slow
            // cold render holding the lock.
            firstHoldsLock.countDown()
            release.await(30, TimeUnit.SECONDS)
          }
          emit("png-$call".toByteArray())
        },
      )
    host(session).use { h ->
      val pool = Executors.newSingleThreadExecutor()
      try {
        // Thread A: grabs the lock and blocks in its render.
        pool.submit { h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT)) }
        assertTrue(firstHoldsLock.await(10, TimeUnit.SECONDS), "first render should take the lock")

        // Thread B (this thread): a DIFFERENT override, so no cache hit — it must contend for the
        // lock and back off rather than wait out the 30s budget.
        val startNs = System.nanoTime()
        val outcome = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertTrue(
          outcome is RenderOutcome.Busy,
          "a render blocked on the busy daemon must back off to Busy, got $outcome",
        )
        assertTrue(
          elapsedMs < 10_000,
          "Busy must return near the bounded wait (~2s), not the 30s budget; took ${elapsedMs}ms",
        )
      } finally {
        release.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
      }
    }
  }

  @Test
  fun `gesturesRenderable follows the daemon's advertised gesture capability`() {
    // An Android-style backend advertises "gestures" ⇒ the viewer offers the hint control.
    host(FakeRenderSession(newRenderRoot(), supportedOverrides = listOf("gestures"))).use { h ->
      assertTrue(h.gesturesRenderable, "gestures in supportedOverrides ⇒ renderable")
    }
    // A desktop-style backend advertises none ⇒ the control is gated off (would be a dead toggle).
    host(FakeRenderSession(newRenderRoot())).use { h ->
      assertFalse(h.gesturesRenderable, "no gesture capability ⇒ not renderable")
    }
  }

  @Test
  fun `hasSvgExport enables the daemon's figma-svg data products on open`() {
    // The daemon registers compose/figma-svg (+ -long) inactive; without this enable an
    // override-bearing .svg render fails "-32020 kind not advertised". Assert the host activates
    // them on open and advertises the SVG export.
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertTrue(h.hasSvgExport, "a figma-svg-capable daemon advertises SVG export")
      assertTrue(
        session.enabledExtensionIds.containsAll(
          listOf(ComposeFigmaSvgProduct.KIND, ComposeFigmaSvgProduct.KIND_LONG)
        ),
        "the host enables both figma-svg data products on open",
      )
    }
  }

  @Test
  fun `hasSvgExport is false when the daemon lacks figma-svg`() {
    // A backend without the figma-svg producer reports the ids as unknown; the host must then offer
    // no SVG export rather than dead-ending an override .svg in a 500.
    host(FakeRenderSession(newRenderRoot(), figmaSvgAvailable = false)).use { h ->
      assertFalse(h.hasSvgExport, "no figma-svg producer ⇒ no advertised SVG export")
    }
  }

  @Test
  fun `renderSvg short-circuits to NotFound when figma-svg is unavailable`() {
    // Without the producer the SVG render methods must NOT hit fetchData (which would 500 with
    // `-32020 kind not advertised`); they return NotFound (a 404) to match the advertised no-SVG
    // lane. Guards the Codex P2 on the availability gate.
    val session = FakeRenderSession(newRenderRoot(), figmaSvgAvailable = false)
    host(session).use { h ->
      assertEquals(
        SvgOutcome.NotFound,
        h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK)),
      )
      assertEquals(SvgOutcome.NotFound, h.renderScrollSvg(previewId, PreviewOverrides()))
      assertEquals(
        0,
        session.renderCount.get(),
        "no render/fetch is attempted for an SVG-less host",
      )
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
  fun `renderScrollSvg returns the full-page figma-svg-long export`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val out = h.renderScrollSvg(previewId, PreviewOverrides())
      assertTrue(out is SvgOutcome.Ok)
      assertEquals(
        "svg-long:$previewId:null:null:null",
        (out as SvgOutcome.Ok).svg.decodeToString(),
      )
      // The fetch carries the force flag + a serialized overrides bag (default here).
      val params = session.lastScrollFetchParams as JsonObject
      assertEquals(JsonPrimitive(true), params[DataFetchParams.PARAM_FORCE_RERENDER])
      assertNotNull(params[DataFetchParams.PARAM_OVERRIDES])
    }
  }

  @Test
  fun `renderScrollSvg serves identical requests from cache`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val a = h.renderScrollSvg(previewId, PreviewOverrides())
      val b = h.renderScrollSvg(previewId, PreviewOverrides())
      assertTrue(a is SvgOutcome.Ok && b is SvgOutcome.Ok)
      assertContentEquals(a.svg, b.svg)
      assertEquals(
        1,
        session.scrollFetchCount.get(),
        "identical overrides ⇒ one fetch, then cached",
      )
    }
  }

  @Test
  fun `renderScrollSvg is override-aware — distinct overrides re-render and don't collide in cache`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val dark = h.renderScrollSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      val light = h.renderScrollSvg(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
      assertTrue(dark is SvgOutcome.Ok && light is SvgOutcome.Ok)
      assertEquals(
        "svg-long:$previewId:DARK:null:null",
        (dark as SvgOutcome.Ok).svg.decodeToString(),
      )
      assertEquals(
        "svg-long:$previewId:LIGHT:null:null",
        (light as SvgOutcome.Ok).svg.decodeToString(),
      )
      assertEquals(2, session.scrollFetchCount.get(), "each distinct override re-renders")
      // Re-requesting the dark capsule is a cache hit — no third fetch, and its bytes are intact.
      val darkAgain = h.renderScrollSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertContentEquals(dark.svg, (darkAgain as SvgOutcome.Ok).svg)
      assertEquals(
        2,
        session.scrollFetchCount.get(),
        "the repeat dark request is served from cache",
      )
    }
  }

  @Test
  fun `renderScrollSvg 404s an unknown preview`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertTrue(h.renderScrollSvg("no.such.Preview", PreviewOverrides()) is SvgOutcome.NotFound)
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
  fun `renderSlots returns the declared dp-slot markers for the given overrides`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val out = h.renderSlots(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(out is SlotsOutcome.Ok)
      val payload =
        Json.decodeFromString(
          PreviewSlotsPayload.serializer(),
          (out as SlotsOutcome.Ok).json.decodeToString(),
        )
      assertEquals(previewId, payload.previewId)
      assertEquals(listOf("leadingIcon", "supporting"), payload.slots.map { it.name })
      assertEquals(SlotBounds(8, 8, 40, 40), payload.slots.first().bounds)
      assertEquals(32, payload.slots.first().width)
    }
  }

  @Test
  fun `renderSlots serves identical requests from cache`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      h.renderSlots(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      h.renderSlots(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertEquals(
        1,
        session.renderCount.get(),
        "second identical slots request must hit the cache",
      )
    }
  }

  @Test
  fun `renderSlots is NotFound for an unknown preview`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertTrue(h.renderSlots("nope", PreviewOverrides()) is SlotsOutcome.NotFound)
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
