package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration coverage for the v3 Android-interactive held-rule loop (INTERACTIVE-ANDROID.md § 9).
 * This is the substantive PR B test — it boots two Robolectric sandboxes (`sandboxCount = 2`),
 * pins slot 1 to a held interactive session against the [ClickToggleSquare] testFixture, drives
 * the click into the held composition via [AndroidInteractiveSession.dispatch], and verifies the
 * second [AndroidInteractiveSession.render] reflects the flipped state.
 *
 * The empirical probe at `RobolectricInteractiveProbeTest` already confirmed the underlying
 * recipe (held rule + paused mainClock + dispatchTouchEvent + recapture) works under Robolectric.
 * This test adds the host plumbing on top: cross-classloader command marshalling through the
 * bridge, host-side `acquireInteractiveSession` precondition checks, the slot-pinning policy,
 * and the streamId / requestId correlation paths.
 *
 * **Sandbox cold-boot cost.** With `sandboxCount = 2` the test pays ~2× the cold-boot wall-clock
 * of single-sandbox tests (each sandbox downloads + instruments `android-all` independently on a
 * cold cache). On a warm cache it's ≤ 5 s extra. Acceptable for a single integration test; we
 * don't multiply this across the harness — the broader S1-S8 coverage stays on the v1 stateless
 * path.
 *
 * **No `assumeTrue` gate.** Unlike the standalone probe (`-Dcomposeai.probe.interactive=true`),
 * this test runs on every `:daemon:android:test` invocation. The probe stays gated because it's
 * an empirical experiment about Robolectric internals; this is the wired-up production path and
 * must not regress silently.
 *
 * Pixel-match helper inlined for the same reason `RenderEngineTest` and the probe inline theirs
 * — pulling `:daemon:harness`'s `PixelDiff` would invert the dependency graph.
 */
class AndroidInteractiveSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun heldClickToggleSurvivesAcrossInputs() {
    val outputDir = tempFolder.newFolder("interactive-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val resolver = previewSpecResolver()
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = resolver)
    host.start()
    try {
      assertTrue(
        "supportsInteractive must be true with sandboxCount=2 + previewSpecResolver wired",
        host.supportsInteractive,
      )

      val session =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = AndroidInteractiveSessionTest::class.java.classLoader!!,
        )
      try {
        assertEquals(INTERACTIVE_PREVIEW_ID, session.previewId)

        // First frame: held composition starts on red (initial state of `ClickToggleSquare`).
        val firstResult = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("first render must produce a PNG path", firstResult.pngPath)
        val firstImg = decode(File(firstResult.pngPath!!))
        val redBefore = pixelMatchPct(firstImg, RED_RGB, perChannelTolerance = 8)
        assertTrue(
          "expected initial held capture to be ≥95% red (got ${"%.2f".format(redBefore * 100)}%)",
          redBefore >= 0.95,
        )

        // Dispatch a click in the centre of the box. ClickToggleSquare's pointerInput awaits the
        // first ACTION_DOWN and flips its remember'd state to green; held composition keeps the
        // mutated state across captures.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
          )
        )

        // Second frame: state should now be green.
        val secondResult = session.render(requestId = RenderHost.nextRequestId())
        val secondImg = decode(File(secondResult.pngPath!!))
        val greenAfter = pixelMatchPct(secondImg, GREEN_RGB, perChannelTolerance = 8)
        val redAfter = pixelMatchPct(secondImg, RED_RGB, perChannelTolerance = 8)
        assertTrue(
          "expected post-click held capture to be ≥95% green (got " +
            "${"%.2f".format(greenAfter * 100)}%, redAfter=${"%.2f".format(redAfter * 100)}%) — " +
            "either dispatchTouchEvent didn't reach pointerInput or recomposition didn't land " +
            "before the second capture",
          greenAfter >= 0.95,
        )
      } finally {
        session.close()
      }

      // After close, normal renders must still work — slot 0 should have stayed live throughout
      // the held session, and slot 1 should be back to draining its requests queue. Submit a
      // standard render to confirm.
      val followupId = RenderHost.nextRequestId()
      val followupResult =
        host.submit(
          RenderRequest.Render(
            id = followupId,
            payload =
              "previewId=red-square;" +
                "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=RedSquare;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=post-interactive-red",
          ),
          timeoutMs = 60_000,
        )
      assertEquals(followupId, followupResult.id)
      assertNotNull(
        "post-interactive normal render must still produce a PNG (slot 0 stayed alive)",
        followupResult.pngPath,
      )
      val followupImg = decode(File(followupResult.pngPath!!))
      val followupRed = pixelMatchPct(followupImg, RED_RGB, perChannelTolerance = 8)
      assertTrue(
        "post-interactive RedSquare render should still be ≥95% red (got " +
          "${"%.2f".format(followupRed * 100)}%)",
        followupRed >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun clickableClickMutatesHeldState() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-clickable").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = CLICKABLE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val before = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        assertTrue(
          "clickable fixture should start red",
          pixelMatchPct(before, RED_RGB, perChannelTolerance = 8) >= 0.95,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
          )
        )

        val after = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        val greenAfter = pixelMatchPct(after, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "click should drive Modifier.clickable state to green; got " +
            "${"%.2f".format(greenAfter * 100)}% green",
          greenAfter >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun uiAutomatorClickMutatesHeldStateThroughTheBridge() {
    // End-to-end coverage of the new InteractiveCommand.DispatchUiAutomator path:
    // session.dispatchUiAutomator(...) → bridge envelope → RobolectricHost.performUiAutomatorAction
    // → :data-uiautomator-core's decodeSelectorJson + UiAutomator.findObject
    // → SemanticsActions.OnClick on the matched ClickableToggleSquare node.
    //
    // Mirrors `clickableClickMutatesHeldState` but takes the selector path instead of pixel
    // input. ClickableToggleSquare is the cheapest target — `Modifier.clickable {}` exposes
    // exactly one OnClick lambda, so `{"clickable":true}` matches the single Box and flips
    // its red→green state.
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-uia").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = CLICKABLE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val before = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        assertTrue(
          "ClickableToggleSquare must start red before the uia.click",
          pixelMatchPct(before, RED_RGB, perChannelTolerance = 8) >= 0.95,
        )

        val matched =
          session.dispatchUiAutomator(
            actionKind = "click",
            selectorJson = """{"clickable":true}""",
            useUnmergedTree = false,
            inputText = null,
          )
        assertTrue(
          "dispatchUiAutomator must report a match against the clickable node — selector " +
            "{clickable:true} should hit Modifier.clickable {}'s OnClick",
          matched,
        )

        val after = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        val greenAfter = pixelMatchPct(after, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "expected post-uia.click held capture to be ≥95% green (got " +
            "${"%.2f".format(greenAfter * 100)}%) — bridge → RobolectricHost → SemanticsActions " +
            "didn't flip the held composition's state",
          greenAfter >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun uiAutomatorClickReportsUnmatchedWhenNoNodeSatisfiesTheSelector() {
    // Negative-path coverage — the bridge must report `matched = false` (and *not* throw)
    // when the selector finds no nodes. The recording-session handler relies on this to
    // emit a precise unsupported evidence string.
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-uia-miss").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = CLICKABLE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val matched =
          session.dispatchUiAutomator(
            actionKind = "click",
            selectorJson = """{"text":"NoSuchLabel"}""",
            useUnmergedTree = false,
            inputText = null,
          )
        assertEquals(
          "selector with no matching node must report unmatched (the recording session turns " +
            "this into unsupported evidence)",
          false,
          matched,
        )

        // #874 item #2 — after a miss, the recording-session handler asks the bridge to
        // compute structured evidence so the response carries the matched-count + closest
        // near-match node. Verify the round-trip end-to-end through the same held-rule loop
        // the dispatch ran against.
        val reason =
          session.findUiAutomatorEvidence(
            actionKind = "click",
            selectorJson = """{"text":"NoSuchLabel"}""",
            useUnmergedTree = false,
            inputText = null,
          )
        assertNotNull(
          "findUiAutomatorEvidence must return a structured reason after a miss",
          reason,
        )
        assertEquals(
          ee.schimke.composeai.daemon.protocol.UiAutomatorUnsupportedReasonCode.NO_MATCH,
          reason!!.code,
        )
        assertEquals(0, reason.matchCount)
        assertEquals("click", reason.actionKind)
        // ClickableToggleSquare exposes Modifier.clickable, so the heuristic must surface it
        // as the near-match — agents see "you matched 0 nodes; here's the actionable node
        // you might have meant" without re-rendering.
        assertNotNull(
          "the held composition has an actionable node; the heuristic must surface it",
          reason.nearMatch,
        )
        assertTrue(
          "near-match must expose the click action so the agent sees that fixing the selector " +
            "will let the dispatch fire — got actions=${reason.nearMatch!!.actions}",
          "click" in reason.nearMatch!!.actions,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun fingerDragScrollsVerticalScrollable() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-scroll").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = SCROLL_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val before = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        assertTrue(
          "scroll fixture should initially show the red top item",
          pixelMatchPct(before, RED_RGB, perChannelTolerance = 8) >= 0.95,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_DOWN,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = 82,
          )
        )
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_MOVE,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = 8,
          )
        )
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_UP,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = 8,
          )
        )

        val after = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        val greenAfter = pixelMatchPct(after, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "finger drag should scroll the verticalScroll content enough to reveal green; got " +
            "${"%.2f".format(greenAfter * 100)}% green",
          greenAfter >= 0.35,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun pointerUpUsesReleaseCoordinates() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-release-position").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = RELEASE_POSITION_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val before = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        assertTrue(
          "release-position fixture should start red",
          pixelMatchPct(before, RED_RGB, perChannelTolerance = 8) >= 0.95,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_DOWN,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = 82,
          )
        )
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_MOVE,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = 70,
          )
        )
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_UP,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = 8,
          )
        )

        val after = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        val greenAfter = pixelMatchPct(after, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "pointerUp should release at its command coordinates, not the last move; got " +
            "${"%.2f".format(greenAfter * 100)}% green",
          greenAfter >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun rotaryScrollDispatchesAsRsbInput() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-rsb").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = ROTARY_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val before = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        assertTrue(
          "rotary fixture should start red",
          pixelMatchPct(before, RED_RGB, perChannelTolerance = 8) >= 0.95,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.ROTARY_SCROLL,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
            scrollDeltaY = 120f,
          )
        )

        val after = decode(File(session.render(RenderHost.nextRequestId()).pngPath!!))
        val greenAfter = pixelMatchPct(after, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "rotary scroll should reach the focused onRotaryScrollEvent handler; got " +
            "${"%.2f".format(greenAfter * 100)}% green",
          greenAfter >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithoutResolverThrowsUnsupported() {
    val host = RobolectricHost(sandboxCount = 2)
    assertFalse(
      "supportsInteractive must be false without a previewSpecResolver, even with sandboxCount=2",
      host.supportsInteractive,
    )
    host.start()
    try {
      try {
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
        fail("expected UnsupportedOperationException for missing previewSpecResolver")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun previewManifestRouterPreservesInteractiveCapabilityWhenPooled() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = INTERACTIVE_PREVIEW_ID,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ClickToggleSquare",
              widthPx = INTERACTIVE_WIDTH_PX,
              heightPx = INTERACTIVE_HEIGHT_PX,
              density = 1.0f,
            )
          )
      )

    assertFalse(
      "single-sandbox router must keep the v1 fallback capability",
      PreviewManifestRouter(manifest = manifest, sandboxCount = 1).supportsInteractive,
    )
    assertTrue(
      "pooled production router must advertise held interactive sessions",
      PreviewManifestRouter(manifest = manifest, sandboxCount = 2).supportsInteractive,
    )
  }

  @Test
  fun previewManifestRouterKeepsPreviewIdInRoutedPayloadForSlotAffinity() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = SCROLL_PREVIEW_ID,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "DragScrollableSquare",
              widthPx = INTERACTIVE_WIDTH_PX,
              heightPx = INTERACTIVE_HEIGHT_PX,
              density = 1.0f,
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest, sandboxCount = 2)

    val routed = router.routePayload("previewId=$SCROLL_PREVIEW_ID;widthPx=128")

    assertTrue(
      "routed payload must retain previewId so RobolectricHost can keep renderNow off the " +
        "held interactive slot by preview affinity",
      routed.split(';').contains("previewId=$SCROLL_PREVIEW_ID"),
    )
    assertTrue("routed payload should still carry resolved class", routed.contains("className="))
    assertTrue("inbound overrides must survive routing", routed.contains("widthPx=128"))
  }

  @Test
  fun acquireWithSandboxCountOneThrowsUnsupported() {
    // sandboxCount = 1 should refuse interactive even when a resolver is wired — the single slot
    // can't be sacrificed without taking normal renders down.
    val host = RobolectricHost(sandboxCount = 1, previewSpecResolver = previewSpecResolver())
    assertFalse(
      "supportsInteractive must be false with sandboxCount=1 even when resolver is wired",
      host.supportsInteractive,
    )
    host.start()
    try {
      try {
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
        fail("expected UnsupportedOperationException for sandboxCount=1")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
        assertTrue(
          "error message should mention the sandboxCount constraint",
          expected.message!!.contains("sandboxCount"),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun idleLeaseAutoClosesAbandonedSession() {
    // Drive the watchdog with a 500ms lease so we don't burn a minute of CI per run. The host's
    // interactiveIdleLeaseMs forwards to every session it acquires.
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("zombie").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host =
      RobolectricHost(
        sandboxCount = 2,
        previewSpecResolver = previewSpecResolver(),
        interactiveIdleLeaseMs = 500L,
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      val typed = session as AndroidInteractiveSession
      assertFalse("session must be open immediately after acquire", typed.isClosed)
      assertNull("autoClosedReason should be null on a fresh session", typed.autoClosedReason())

      // Wait long enough for the watchdog to fire and complete its close round-trip. Lease is
      // 500ms; watchdog ticks at 500ms/4 = 125ms; close round-trip is < 1s. Polling once a tick
      // for up to 10s is generous against a slow CI but fast in normal cases.
      val deadline = System.currentTimeMillis() + 10_000L
      while (!typed.isClosed && System.currentTimeMillis() < deadline) {
        Thread.sleep(100L)
      }
      assertTrue(
        "watchdog should have auto-closed the idle session within 10s of acquire " +
          "(idleLeaseMs=500)",
        typed.isClosed,
      )
      val reason = typed.autoClosedReason()
      assertNotNull("autoClosedReason should be populated by the watchdog path", reason)
      assertTrue(
        "autoClosedReason should mention the idle gap and the lease for diagnostics: '$reason'",
        reason!!.contains("idle for") && reason.contains("lease"),
      )

      // After auto-close, a fresh acquire must succeed — the host's activeInteractiveStreamId
      // should have been cleared by the watchdog's close() call.
      val followup =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      followup.close()
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun nestedAcquireRefusesUntilFirstSessionCloses() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("nested").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val first =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        try {
          host.acquireInteractiveSession(
            previewId = INTERACTIVE_PREVIEW_ID,
            classLoader = javaClass.classLoader!!,
          )
          fail("nested acquire should have thrown")
        } catch (expected: UnsupportedOperationException) {
          assertTrue(
            "nested-acquire error should mention 'already held'",
            expected.message!!.contains("already held"),
          )
        }
      } finally {
        first.close()
      }
      // After close, a fresh acquire must succeed — the host should have cleared its
      // activeInteractiveStreamId ref.
      val second =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      second.close()
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun uiModeOverrideReachesHeldSession() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("dark-overrides").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host =
      RobolectricHost(
        sandboxCount = 2,
        previewSpecResolver = previewSpecResolver(),
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = DARK_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val result = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("dark-mode held render must produce a PNG", result.pngPath)
        val img = decode(File(result.pngPath!!))
        // DarkAwareSquare paints white in light, black in dark. The DARK uiMode override should
        // route through `night` qualifier → `Configuration.UI_MODE_NIGHT_YES` → the composable's
        // `isSystemInDarkTheme()` returns true → black.
        val blackPct = pixelMatchPct(img, BLACK_RGB, perChannelTolerance = 8)
        val whitePct = pixelMatchPct(img, WHITE_RGB, perChannelTolerance = 8)
        assertTrue(
          "uiMode=DARK should drive DarkAwareSquare to ≥95% black (got " +
            "${"%.2f".format(blackPct * 100)}% black, ${"%.2f".format(whitePct * 100)}% white) " +
            "— qualifier didn't reach the held composition's Configuration",
          blackPct >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun swapUserClassLoadersForceClosesHeldSession() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("classpath-dirty").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host =
      RobolectricHost(
        sandboxCount = 2,
        previewSpecResolver = previewSpecResolver(),
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        ) as AndroidInteractiveSession
      assertFalse("session must be open after acquire", session.isClosed)

      // Simulate a `fileChanged{kind: "source"}` save while a session is held. The host's
      // `swapUserClassLoaders` should tear the held composition down BEFORE swapping the
      // per-slot child loaders so the next acquire sees fresh bytecode.
      host.swapUserClassLoaders()

      assertTrue(
        "session must be closed after swapUserClassLoaders — held composition references stale " +
          "bytecode that swap is about to invalidate",
        session.isClosed,
      )

      // After the force-close, a fresh acquire must succeed — the host should have cleared its
      // active-session state.
      val followup =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      followup.close()
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun shutdownClosesHeldSessionCleanly() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("shutdown-drain").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host =
      RobolectricHost(
        sandboxCount = 2,
        previewSpecResolver = previewSpecResolver(),
      )
    host.start()
    val session =
      host.acquireInteractiveSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        classLoader = javaClass.classLoader!!,
      ) as AndroidInteractiveSession

    // host.shutdown() should drain the held session before posting slot poison pills. Without
    // PR C's wiring, slot 1's worker would be stuck in `interactiveCommands.take()` and
    // shutdown would time out joining the thread (see the IllegalStateException path in
    // shutdown). We assert it returns within a generous budget AND the session is closed.
    val startMs = System.currentTimeMillis()
    host.shutdown(timeoutMs = 30_000L)
    val elapsedMs = System.currentTimeMillis() - startMs

    assertTrue(
      "shutdown() must close the held session — without that drain, slot 1's worker sits in " +
        "interactiveCommands.take() and shutdown times out",
      session.isClosed,
    )
    assertTrue(
      "shutdown() should complete well under the timeout (got ${elapsedMs}ms / 30000ms) when " +
        "the held session is drained correctly",
      elapsedMs < 25_000L,
    )
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    when (previewId) {
      INTERACTIVE_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "ClickToggleSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-clicktoggle",
        )
      DARK_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "DarkAwareSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-darkaware",
          uiMode = RenderSpec.SpecUiMode.DARK,
        )
      CLICKABLE_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "ClickableToggleSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-clickable",
        )
      SCROLL_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "DragScrollableSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-scroll",
        )
      RELEASE_POSITION_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "ReleasePositionSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-release-position",
        )
      ROTARY_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "RotaryToggleSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-rsb",
        )
      else -> null
    }
  }

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    val bytes = file.readBytes()
    require(bytes.isNotEmpty()) { "capture is empty: ${file.absolutePath}" }
    return ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }

  companion object {
    private const val INTERACTIVE_PREVIEW_ID = "interactive-clicktoggle"
    private const val DARK_PREVIEW_ID = "interactive-darkaware"
    private const val CLICKABLE_PREVIEW_ID = "interactive-clickable"
    private const val SCROLL_PREVIEW_ID = "interactive-scroll"
    private const val RELEASE_POSITION_PREVIEW_ID = "interactive-release-position"
    private const val ROTARY_PREVIEW_ID = "interactive-rsb"
    private const val INTERACTIVE_WIDTH_PX = 96
    private const val INTERACTIVE_HEIGHT_PX = 96
    private const val RED_RGB = 0xEF5350
    private const val GREEN_RGB = 0x66BB6A
    private const val BLACK_RGB = 0x000000
    private const val WHITE_RGB = 0xFFFFFF
  }
}
