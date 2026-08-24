package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.PreviewSlots
import ee.schimke.composeai.data.layoutinspector.PreviewSlotsPayload
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import ee.schimke.composeai.data.theme.Material3ThemeProduct
import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeRenderHostTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-host").toFile().also { it.deleteOnExit() }

  private fun host(session: RenderSession): ServeRenderHost =
    ServeRenderHost(
      session = session,
      previews =
        listOf(
          ServePreview(previewId, "Red", dataProductKinds = setOf(ServeRenderHost.SCROLL_LONG_KIND))
        ),
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
  fun `a fatal linkage failure trips the breaker instead of being retried forever`() {
    // Issue #3448: an UnsatisfiedLinkError — a JVM linkage failure that cannot succeed on retry,
    // ever, for any input — was retried 3794 times in ~14 minutes. The daemon must be asked once.
    val linkage =
      "UnsatisfiedLinkError: 'long org.jetbrains.skia.PathBuilderKt.PathBuilder_nMakeFromPath(long)'"
    lateinit var session: FakeRenderSession
    session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { _, _ -> session.emitFailed(previewId, linkage) },
      )
    host(session).use { h ->
      assertTrue(h.hasLiveStream, "a healthy host advertises its live lane")
      assertTrue(h.degradations.isEmpty())

      val first = h.render(previewId, PreviewOverrides())
      assertTrue(first is RenderOutcome.Failed, "expected Failed, got $first")

      // Every subsequent render is answered from the breaker without touching the daemon.
      repeat(20) { i ->
        val outcome = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
        assertTrue(outcome is RenderOutcome.Failed, "render $i: expected Failed, got $outcome")
        assertTrue(
          outcome.reason.contains("UnsatisfiedLinkError"),
          "the caller must be told the real fault, not 'busy; retry shortly': ${outcome.reason}",
        )
      }
      assertEquals(1, session.renderCount.get(), "the daemon must be asked exactly once")

      // The lane stops advertising itself as live, publishes why, and latches every preview so the
      // HTTP layer answers terminally instead of sending the browser round the retry loop.
      assertFalse(h.hasLiveStream, "a broken lane must stop advertising live")
      val degradation = h.degradations.single()
      assertEquals(ServeDegradation.RENDER_LANE_BROKEN, degradation.code)
      assertTrue(degradation.detail.contains("UnsatisfiedLinkError"))
      assertNotNull(h.renderFailureLatch(previewId, PreviewOverrides()))

      val breaker = assertNotNull(h.renderBreaker())
      assertTrue(breaker.fatal)
      assertEquals(20, breaker.shortCircuitedRenders)
      val stats = assertNotNull(h.renderPerfStats())
      assertEquals(20, stats.shortCircuited, "refused renders are counted apart from failures")
      assertEquals(1, stats.failed, "the short-circuits must not inflate the failure count")
      assertNotNull(stats.breaker)
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
          listOf(
            ComposeFigmaSvgProduct.KIND,
            ComposeFigmaSvgProduct.KIND_LONG,
            ServeRenderHost.SCROLL_EXTENSION_ID,
          )
        ),
        "the host enables the viewport and full-page export products on open",
      )
      assertTrue(h.hasScrollExport, "the daemon advertises full-page PNG export")
      assertTrue(h.hasScrollExportFor(previewId), "the annotated preview offers full-page export")
    }
  }

  @Test
  fun `full-page export is hidden for a preview without long-scroll metadata`() {
    val session = FakeRenderSession(newRenderRoot())
    ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(previewId, "Red")),
        renderTimeoutSeconds = 30,
      )
      .use { h ->
        assertTrue(h.hasScrollExport, "the daemon supports the scroll producer")
        assertFalse(h.hasScrollExportFor(previewId), "the preview did not declare LONG capture")
      }
  }

  @Test
  fun `hasSvgExport is false when the daemon lacks figma-svg`() {
    // A backend without the figma-svg producer reports the ids as unknown; the host must then offer
    // no SVG export rather than dead-ending an override .svg in a 500.
    host(FakeRenderSession(newRenderRoot(), figmaSvgAvailable = false)).use { h ->
      assertFalse(h.hasSvgExport, "no figma-svg producer ⇒ no advertised SVG export")
      assertTrue(h.hasScrollExport, "full-page PNG remains available without figma-svg")
      assertTrue(
        h.renderScrollPng(previewId, PreviewOverrides()) is RenderOutcome.Ok,
        "PNG tall capture must not depend on the SVG producer",
      )
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
      assertEquals("svg:DARK:null:null", out.svg.decodeToString())
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
        out.svg.decodeToString(),
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
        dark.svg.decodeToString(),
      )
      assertEquals(
        "svg-long:$previewId:LIGHT:null:null",
        light.svg.decodeToString(),
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
  fun `renderScrollPng returns an override-aware full-page PNG and caches it`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val dark = h.renderScrollPng(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      val light = h.renderScrollPng(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
      val darkAgain = h.renderScrollPng(previewId, PreviewOverrides(uiMode = UiMode.DARK))

      assertEquals(
        "png-long:$previewId:DARK:null:null",
        (dark as RenderOutcome.Ok).png.decodeToString(),
      )
      assertEquals(
        "png-long:$previewId:LIGHT:null:null",
        (light as RenderOutcome.Ok).png.decodeToString(),
      )
      assertContentEquals(dark.png, (darkAgain as RenderOutcome.Ok).png)
      assertEquals(RenderOutcome.Generation.DAEMON_CACHE, darkAgain.generation)
      assertEquals(2, session.scrollPngFetchCount.get())
      val params = session.lastScrollPngFetchParams as JsonObject
      assertEquals(JsonPrimitive(true), params[DataFetchParams.PARAM_FORCE_RERENDER])
      assertNotNull(params[DataFetchParams.PARAM_OVERRIDES])
    }
  }

  @Test
  fun `renderScrollPng 404s an unknown preview`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertEquals(RenderOutcome.NotFound, h.renderScrollPng("no.such.Preview", PreviewOverrides()))
    }
  }

  @Test
  fun `renderSvg serves identical requests from cache`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val first = h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      val second = h.renderSvg(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertEquals(1, session.renderCount.get(), "second identical SVG request must hit the cache")
      // The vector lane reports the same generation ladder as the raster one — it rides the
      // `X-Compose-Preview-Generation` header, so a cache hit must not claim to be a fresh render.
      assertEquals(
        RenderOutcome.Generation.DAEMON,
        (first as SvgOutcome.Ok).generation,
        "the first SVG export really was rendered",
      )
      assertEquals(
        RenderOutcome.Generation.DAEMON_CACHE,
        (second as SvgOutcome.Ok).generation,
        "the second came from the SVG cache",
      )
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
      assertEquals("svg:DARK:null:null", out.svg.decodeToString())
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
          out.json.decodeToString(),
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
  fun `renderAnnotations projects the semantics tree into typography and theme layers`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      val out = h.renderAnnotations(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(out is AnnotationsOutcome.Ok)
      val payload = Json.parseToJsonElement(out.json.decodeToString()).jsonObject
      assertEquals(previewId, payload["previewId"]?.jsonPrimitive?.content)
      val annotations =
        Json.decodeFromJsonElement(
          ListSerializer(DesignAnnotation.serializer()),
          payload.getValue("annotations"),
        )
      val typography = annotations.single { it.kind == AnnotationKind.TYPOGRAPHY }
      // Size over line height, the shortened face, then the weight — the one line a designer reads
      // off a type ramp. The face is shortened from the resolved font FILE the daemon reports.
      assertEquals("14.0sp/20.0sp · Roboto-Medium.ttf · 500", typography.label)
      assertEquals("Supporting text", typography.role)
      assertEquals(AnnotationBounds(x = 48, y = 44, width = 144, height = 20), typography.bounds)
      assertEquals("14.0sp", typography.detail["fontSize"])

      val theme = annotations.single { it.kind == AnnotationKind.THEME }
      assertEquals("fill #FF6750A4 · radius 12.0dp · border 1.0dp #FF79747E", theme.label)
      assertEquals(AnnotationBounds(x = 8, y = 8, width = 32, height = 32), theme.bounds)
      assertEquals("#FF6750A4", theme.detail["background"])

      // The tag index rides the SAME response, off the SAME semantics payload, under the same
      // render lock. That co-location is the contract: a parity element gate compares a recorded
      // tag box against a current one, so an index built by a second render would report movement
      // that never happened.
      val tags =
        Json.decodeFromJsonElement(
          MapSerializer(String.serializer(), ServeSemanticsTags.TagEntry.serializer()),
          payload.getValue("tags"),
        )
      assertEquals(
        setOf(
          "${PreviewSlots.SLOT_TAG_PREFIX}leadingIcon",
          "${PreviewSlots.SLOT_TAG_PREFIX}supporting",
        ),
        tags.keys,
      )
      val icon = tags.getValue("${PreviewSlots.SLOT_TAG_PREFIX}leadingIcon")
      assertEquals(1, icon.count)
      // Same box the theme annotation reports for that node — one coordinate space, not two.
      assertEquals(theme.bounds, icon.bounds)
    }
  }

  @Test
  fun `renderAnnotations joins Material typography consumers to semantics by node id`() {
    val theme =
      Json.parseToJsonElement(
        """
        {
          "resolvedTokens": {
            "colorScheme": {"primary": "#FF000000"},
            "typography": {"labelLarge": {"fontSize": 14.0, "fontSizeUnit": "sp"}},
            "shapes": {}
          },
          "consumers": [{"nodeId": "2", "tokens": ["primary", "labelLarge"]}]
        }
        """
          .trimIndent()
      )
    val session =
      FakeRenderSession(
        newRenderRoot(),
        fetchDataHook = { _, kind ->
          if (kind == Material3ThemeProduct.KIND) {
            DataFetchResult(
              kind = kind,
              schemaVersion = Material3ThemeProduct.SCHEMA_VERSION,
              payload = theme,
            )
          } else {
            null
          }
        },
      )
    host(session).use { h ->
      val out = h.renderAnnotations(previewId, PreviewOverrides())
      assertTrue(out is AnnotationsOutcome.Ok)
      val payload = Json.parseToJsonElement(out.json.decodeToString()).jsonObject
      val annotations =
        Json.decodeFromJsonElement(
          ListSerializer(DesignAnnotation.serializer()),
          payload.getValue("annotations"),
        )
      val typography = annotations.single { it.kind == AnnotationKind.TYPOGRAPHY }

      assertEquals("labelLarge", typography.detail["token"])
      assertEquals(
        "MaterialTheme.typography.labelLarge · 14.0sp/20.0sp · Roboto-Medium.ttf · 500",
        typography.label,
      )
      assertTrue(Material3ThemeProduct.KIND !in session.subscribedDataKinds)
      assertTrue(Material3ThemeProduct.KIND in session.unsubscribedDataKinds)
      val themeOverrides =
        session.lastThemeFetchParams?.jsonObject?.get(DataFetchParams.PARAM_OVERRIDES)?.let {
          Json.decodeFromJsonElement(PreviewOverrides.serializer(), it)
        }
      assertEquals(PreviewOverrides(), themeOverrides)
      assertTrue(ComposeSemanticsProduct.KIND in session.enabledExtensionIds)
      assertTrue(ServeRenderHost.THEME_EXTENSION_ID in session.enabledExtensionIds)
    }
  }

  @Test
  fun `renderAnnotations serves identical requests from cache`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      h.renderAnnotations(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      h.renderAnnotations(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertEquals(
        1,
        session.renderCount.get(),
        "second identical annotations request must hit the cache",
      )
    }
  }

  @Test
  fun `renderA11y merges the hierarchy with ATF findings and touch targets`() {
    val session =
      FakeRenderSession(
        newRenderRoot(),
        fetchDataHook = { _, kind ->
          when (kind) {
            ServeRenderHost.A11Y_HIERARCHY_KIND ->
              a11yFetch(kind, """{"nodes":[{"label":"Follow","boundsInScreen":"0,0,48,24"}]}""")
            ServeRenderHost.A11Y_ATF_KIND ->
              a11yFetch(
                kind,
                """{"findings":[{"level":"ERROR","type":"SpeakableTextPresentCheck","message":"no label"}]}""",
              )
            ServeRenderHost.A11Y_TOUCH_TARGETS_KIND ->
              a11yFetch(
                kind,
                """{"targets":[{"nodeId":"7","boundsInScreen":"0,0,48,24","widthDp":24.0,"heightDp":12.0,"findings":["TouchTargetTooSmall"]}]}""",
              )
            else -> null
          }
        },
      )
    host(session).use { h ->
      val out = h.renderA11y(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(out is A11yOutcome.Ok)
      val payload = Json.parseToJsonElement(out.json.decodeToString()).jsonObject
      assertEquals(previewId, payload["previewId"]?.jsonPrimitive?.content)
      assertEquals(1, payload.getValue("nodes").jsonArray.size)
      assertEquals(1, payload.getValue("findings").jsonArray.size)
      assertEquals(1, payload.getValue("touchTargets").jsonArray.size)
    }
  }

  @Test
  fun `renderA11y still draws the focus map when the backend has no ATF products`() {
    // The desktop backend advertises no `a11y/touchTargets` at all and answers `a11y/atf` with
    // empty findings — the overlay must still be worth drawing from the hierarchy alone rather
    // than failing the whole request because an optional product wasn't there.
    val session =
      FakeRenderSession(
        newRenderRoot(),
        fetchDataHook = { _, kind ->
          when (kind) {
            ServeRenderHost.A11Y_HIERARCHY_KIND ->
              a11yFetch(kind, """{"nodes":[{"label":"Follow","boundsInScreen":"0,0,48,24"}]}""")
            ServeRenderHost.A11Y_ATF_KIND,
            ServeRenderHost.A11Y_TOUCH_TARGETS_KIND -> error("kind not advertised")
            else -> null
          }
        },
      )
    host(session).use { h ->
      val out = h.renderA11y(previewId, PreviewOverrides())
      assertTrue(out is A11yOutcome.Ok)
      val payload = Json.parseToJsonElement(out.json.decodeToString()).jsonObject
      assertEquals(1, payload.getValue("nodes").jsonArray.size)
      assertTrue(payload.getValue("findings").jsonArray.isEmpty())
      assertTrue(payload.getValue("touchTargets").jsonArray.isEmpty())
    }
  }

  @Test
  fun `renderA11y enables the daemon's inactive a11y extension before fetching`() {
    // The bug behind "the a11y overlay doesn't work" on a served catalog. The daemon registers its
    // inspection products INACTIVE, so a `data/fetch` on a session nobody enabled fails `-32020
    // kind not advertised`. `renderA11y` read no capability of its own, so nothing ran the enable:
    // on a host whose flags were never asked for — the per-preview daemons `ServeCatalogLiveHost`
    // routes this lane to, while answering `hasA11yOverlayFor` from the SHARED one — every
    // accessibility fetch 500'd, until an unrelated SVG or scroll request happened to enable that
    // daemon and the same URL silently started working.
    lateinit var session: FakeRenderSession
    session =
      FakeRenderSession(
        newRenderRoot(),
        fetchDataHook = { _, kind ->
          when (kind) {
            ServeRenderHost.A11Y_HIERARCHY_KIND,
            ServeRenderHost.A11Y_ATF_KIND,
            ServeRenderHost.A11Y_TOUCH_TARGETS_KIND -> {
              // Model the daemon: an un-enabled extension's kinds are simply not advertised.
              check(ServeRenderHost.A11Y_EXTENSION_ID in session.enabledExtensionIds) {
                "data/fetch: kind not advertised: $kind"
              }
              if (kind == ServeRenderHost.A11Y_HIERARCHY_KIND)
                a11yFetch(kind, """{"nodes":[{"label":"Follow","boundsInScreen":"0,0,48,24"}]}""")
              else a11yFetch(kind, "{}")
            }
            else -> null
          }
        },
      )
    host(session).use { h ->
      // Nothing has read a capability flag — exactly the state a freshly-leased per-preview daemon
      // is in when the viewer asks it for the overlay.
      val out = h.renderA11y(previewId, PreviewOverrides())
      assertTrue(out is A11yOutcome.Ok, "expected Ok, got $out")
      assertTrue(
        ServeRenderHost.A11Y_EXTENSION_ID in session.enabledExtensionIds,
        "the a11y lane must enable the extension it fetches from",
      )
    }
  }

  @Test
  fun `renderA11y is NotFound rather than a 500 when the backend has no a11y extension`() {
    // Mirrors `renderSvg` on a backend without figma-svg: a host that reports the extension unknown
    // cannot produce the hierarchy at all, so the lane 404s cleanly instead of fetching into a
    // `-32020` the viewer surfaces as a 500.
    val session =
      FakeRenderSession(
        newRenderRoot(),
        unknownExtensionIds = setOf(ServeRenderHost.A11Y_EXTENSION_ID),
        fetchDataHook = { _, kind ->
          if (kind.startsWith("a11y/")) error("kind not advertised: $kind") else null
        },
      )
    host(session).use { h ->
      assertFalse(h.hasA11yOverlay)
      assertTrue(h.renderA11y(previewId, PreviewOverrides()) is A11yOutcome.NotFound)
    }
  }

  @Test
  fun `renderA11y reports a daemon that cannot be opened instead of throwing`() {
    // Forcing the enable moved the daemon OPEN into this lane, and that open is deliberately
    // outside the enable's own `runCatching` so a transient failure leaves the lazy uninitialized
    // and the next caller retries. It must not escape as an exception: the route only translates
    // outcome values, so a throw would skip its 500-with-reason and its log line entirely.
    val logged = CopyOnWriteArrayList<String>()
    val opens = AtomicInteger(0)
    // Not `use {}`: a host whose open failed has no subprocess to reap, and `close()` reaches for
    // the session again to find that out — which would re-throw the stubbed failure from the
    // cleanup and mask the outcome this test is about.
    val h =
      ServeRenderHost(
        openSession = {
          opens.incrementAndGet()
          throw IllegalStateException("daemon handshake timed out")
        },
        previews = listOf(ServePreview(previewId, "Red")),
        onLog = { logged += it },
      )
    val out = h.renderA11y(previewId, PreviewOverrides())
    assertTrue(out is A11yOutcome.Failed, "expected Failed, got $out")
    assertTrue(
      out.reason.contains("daemon handshake timed out"),
      "the failure must carry the open's reason, got '${out.reason}'",
    )
    assertTrue(logged.any { it.contains("daemon handshake timed out") }, "logged: $logged")

    // Nothing was cached, so a later request still tries — the whole point of leaving the lazy
    // uninitialized.
    h.renderA11y(previewId, PreviewOverrides())
    assertEquals(2, opens.get(), "a failed open must not be remembered as the answer")
  }

  @Test
  fun `renderA11y is NotFound for an unknown preview`() {
    val session = FakeRenderSession(newRenderRoot())
    host(session).use { h ->
      assertTrue(h.renderA11y("nope", PreviewOverrides()) is A11yOutcome.NotFound)
    }
  }

  private fun a11yFetch(kind: String, json: String) =
    ee.schimke.composeai.daemon.protocol.DataFetchResult(
      kind = kind,
      schemaVersion = 1,
      payload = Json.parseToJsonElement(json),
    )

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
      val svg = out.svg.decodeToString()
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
  fun `interrupting a bounded render quarantines its late daemon event`() {
    val firstSubmitted = CountDownLatch(1)
    val session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { call, emit ->
          if (call == 1) firstSubmitted.countDown()
          if (call == 2) {
            emit("STALE".toByteArray())
            emit("FRESH".toByteArray())
          }
        },
      )
    ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(previewId, "Red")),
        renderTimeoutSeconds = 60,
      )
      .use { h ->
        var first: RenderOutcome? = null
        val thread = Thread { first = h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT)) }
        thread.start()
        assertTrue(firstSubmitted.await(5, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(5_000)
        assertEquals(RenderOutcome.Busy, first)

        val second = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
        assertTrue(second is RenderOutcome.Ok, "second render should succeed, got $second")
        assertEquals("FRESH", second.png.decodeToString())
      }
  }

  @Test
  fun `interrupt after terminal event does not quarantine the next render`() {
    val session =
      FakeRenderSession(
        newRenderRoot(),
        renderHook = { call, emit ->
          emit(if (call == 1) "FIRST".toByteArray() else "SECOND".toByteArray())
          if (call == 1) Thread.currentThread().interrupt()
        },
      )
    ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(previewId, "Red")),
        renderTimeoutSeconds = 1,
      )
      .use { h ->
        var first: RenderOutcome? = null
        val thread = Thread { first = h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT)) }
        thread.start()
        thread.join(5_000)
        assertEquals(RenderOutcome.Busy, first)

        val second = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
        assertTrue(second is RenderOutcome.Ok, "the next terminal event must not be discarded")
        assertEquals("SECOND", second.png.decodeToString())
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
