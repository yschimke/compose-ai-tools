package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P5 — end-to-end test for [AndroidRecordingSession] (the Robolectric-backed scripted screen-
 * recording surface). Boots a 2-sandbox `RobolectricHost`, allocates a recording session against
 * the [ClickToggleSquare] fixture (red → green on first click), posts a click at `tMs = 67`, runs
 * `stop()`, and asserts:
 *
 * 1. Frame count = 4 (ceil(67 ms × 30 fps / 1000) + 1, with frame `tMs`'s of 0, 33, 66, 100).
 * 2. Frame 0 paints red — pre-click state at tMs=0.
 * 3. Frame 3 paints green — click@67 was drained at the first frame at or after 67 ms.
 * 4. APNG encode produces a non-empty file with the standard PNG header bytes.
 *
 * **Test cost.** This test pays the same ~2× cold-boot wall-clock as
 * [AndroidInteractiveSessionTest] (each sandbox download + instrumentation independent on a cold
 * cache) plus 4 paused-clock captures × ~500–1000 ms each. ~30 s on a warm cache is realistic;
 * we deliberately don't multiply this across more recording scenarios — the broader scripted
 * coverage stays on the desktop side where renders are sub-100 ms each.
 *
 * Pixel-match helper inlined for the same reason `AndroidInteractiveSessionTest` and
 * `RenderEngineTest` inline theirs — pulling `:daemon:harness`'s `PixelDiff` would invert the
 * dependency graph.
 */
class AndroidRecordingSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun scriptedPlaybackPassesFrameDeltasToInteractiveRender() {
    val framesDir = tempFolder.newFolder("delta-frames")
    val encodedDir = tempFolder.newFolder("delta-encoded")
    val sourcePng = File(tempFolder.newFolder("delta-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-deltas",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    session.postScript(
      listOf(
        RecordingScriptEvent(
          tMs = 67L,
          kind = "click",
          pixelX = 4,
          pixelY = 4,
        )
      )
    )
    val result = session.stop()

    assertEquals(4, result.frameCount)
    assertEquals(listOf(0L, 33L, 33L, 34L), interactive.renderAdvances)
    assertEquals(1, interactive.dispatchCount)
    assertEquals(true, interactive.closed)
  }

  @Test
  fun liveRecordingRoutesQueuedInputThroughTheSameRegistryAsScripted() {
    // Live mode used to call a separate `dispatchLiveInput` ladder; now both paths funnel through
    // `scriptHandlers.dispatch(...)`. Pin that the click in `liveInputs` reaches the same
    // `interactive.dispatch(InteractiveInputParams)` call the scripted `kind = "click"` handler
    // makes — verifying the wireName translation + registry routing without standing up a real
    // Robolectric sandbox.
    val framesDir = tempFolder.newFolder("live-registry-frames")
    val encodedDir = tempFolder.newFolder("live-registry-encoded")
    val sourcePng = File(tempFolder.newFolder("live-registry-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-live-registry",
        fps = FPS,
        scale = 1.0f,
        live = true,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    session.postInput(
      ee.schimke.composeai.daemon.protocol.RecordingInputParams(
        recordingId = "test-rec-live-registry",
        kind = InteractiveInputKind.CLICK,
        pixelX = 4,
        pixelY = 4,
      )
    )
    Thread.sleep(90)
    val result = session.stop()

    assertTrue("live recording should capture at least one frame", result.frameCount >= 1)
    assertEquals(
      "the queued click must route through the registry's `click` handler",
      1,
      interactive.dispatchCount,
    )
    assertTrue(
      "live mode must not emit script-event evidence — only scripted [stop] populates it",
      result.scriptEvents.isEmpty(),
    )
  }

  @Test
  fun liveRecordingCapturesFramesAndDispatchesQueuedInput() {
    val framesDir = tempFolder.newFolder("live-frames")
    val encodedDir = tempFolder.newFolder("live-encoded")
    val sourcePng = File(tempFolder.newFolder("live-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-live-android",
        fps = FPS,
        scale = 1.0f,
        live = true,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    session.postInput(
      ee.schimke.composeai.daemon.protocol.RecordingInputParams(
        recordingId = "test-rec-live-android",
        kind = InteractiveInputKind.CLICK,
        pixelX = 4,
        pixelY = 4,
      )
    )
    Thread.sleep(90)
    val result = session.stop()

    assertTrue("live recording should capture at least one frame", result.frameCount >= 1)
    assertEquals(1, interactive.dispatchCount)
    assertEquals(true, interactive.closed)
    assertTrue(File(result.framesDir, "frame-00000.png").isFile)
  }

  @Test
  fun liveRecordingRejectsScriptedEvents() {
    val framesDir = tempFolder.newFolder("live-reject-frames")
    val encodedDir = tempFolder.newFolder("live-reject-encoded")
    val sourcePng = File(tempFolder.newFolder("live-reject-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-live-reject",
        fps = FPS,
        scale = 1.0f,
        live = true,
        interactive = RecordingDeltaSession(sourcePng),
        framesDir = framesDir,
        encodedDir = encodedDir,
      )
    try {
      try {
        session.postScript(
          listOf(
            RecordingScriptEvent(
              tMs = 0L,
              kind = "click",
              pixelX = 1,
              pixelY = 1,
            )
          )
        )
        fail("expected live recording to reject postScript")
      } catch (expected: IllegalStateException) {
        assertNotNull(expected.message)
      }
    } finally {
      session.close()
    }
  }

  @Test
  fun scriptedRecordingRejectsLiveInput() {
    val framesDir = tempFolder.newFolder("scripted-reject-frames")
    val encodedDir = tempFolder.newFolder("scripted-reject-encoded")
    val sourcePng = File(tempFolder.newFolder("scripted-reject-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-scripted-reject",
        fps = FPS,
        scale = 1.0f,
        interactive = RecordingDeltaSession(sourcePng),
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postInput(
        ee.schimke.composeai.daemon.protocol.RecordingInputParams(
          recordingId = "test-rec-scripted-reject",
          kind = InteractiveInputKind.CLICK,
          pixelX = 1,
          pixelY = 1,
        )
      )
      fail("expected scripted recording to reject postInput")
    } catch (expected: IllegalStateException) {
      assertNotNull(expected.message)
    } finally {
      session.close()
    }
  }

  @Test
  fun scriptedRecordingReportsUnsupportedStateEventsWithoutDispatchingThem() {
    val framesDir = tempFolder.newFolder("scripted-state-events-frames")
    val encodedDir = tempFolder.newFolder("scripted-state-events-encoded")
    val sourcePng = File(tempFolder.newFolder("scripted-state-events-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-scripted-state-events",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 0L,
            kind = "click",
            pixelX = 1,
            pixelY = 1,
          ),
          RecordingScriptEvent(
            tMs = 0L,
            kind = RecordingScriptDataExtensions.STATE_SAVE_EVENT,
            label = "before",
            checkpointId = "c1",
          ),
          RecordingScriptEvent(
            tMs = 0L,
            kind = RecordingScriptDataExtensions.PROBE_EVENT,
            label = "after-save-marker",
          ),
        )
      )
      val result = session.stop()

      assertEquals("only the click should dispatch through interactive input", 1, interactive.dispatchCount)
      assertEquals(3, result.scriptEvents.size)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
        result.scriptEvents[0].status,
      )
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.UNSUPPORTED,
        result.scriptEvents[1].status,
      )
      assertEquals("c1", result.scriptEvents[1].checkpointId)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
        result.scriptEvents[2].status,
      )
      assertEquals("after-save-marker", result.scriptEvents[2].label)
    } finally {
      session.close()
    }
  }

  @Test
  fun a11ySemanticsActionsRouteThroughDispatch() {
    // Pin every entry in `AndroidRecordingSession.A11Y_SEMANTIC_ACTIONS`: the handler must read
    // `nodeContentDescription`, route through `interactive.dispatchSemanticsAction(actionKind,
    // description)`, and produce `applied` evidence naming the action when the dispatch returned
    // true. Adding a new entry to A11Y_SEMANTIC_ACTIONS without registering an arm in
    // `RobolectricHost.performSemanticsActionByContentDescription` won't fail this test (it tests
    // the host-side routing, not the sandbox dispatch — the `RecordingDeltaSession` fake just
    // returns `semanticsActionResult`), but the descriptor split test pins the supported set
    // alongside it so a missing pair is caught at unit-test time.
    val framesDir = tempFolder.newFolder("a11y-actions-frames")
    val encodedDir = tempFolder.newFolder("a11y-actions-encoded")
    val sourcePng = File(tempFolder.newFolder("a11y-actions-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng).apply { semanticsActionResult = true }
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-a11y-actions",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        AndroidRecordingSession.A11Y_SEMANTIC_ACTIONS.map { actionKind ->
          RecordingScriptEvent(
            tMs = 0L,
            kind = "a11y.action.$actionKind",
            nodeContentDescription = "Target-$actionKind",
          )
        }
      )
      val result = session.stop()

      // Every action routed through dispatchSemanticsAction with its own kind + description.
      assertEquals(
        AndroidRecordingSession.A11Y_SEMANTIC_ACTIONS.map { actionKind ->
          actionKind to "Target-$actionKind"
        },
        interactive.semanticsActionCalls,
      )
      // None should have routed through pointer dispatch.
      assertEquals(
        "no a11y.action.* event must touch the pointer-input dispatch path",
        0,
        interactive.dispatchCount,
      )
      // Every action's evidence is applied with the action name in the message.
      AndroidRecordingSession.A11Y_SEMANTIC_ACTIONS.forEachIndexed { index, actionKind ->
        val evidence = result.scriptEvents[index]
        assertEquals(
          "evidence for a11y.action.$actionKind must be applied",
          ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
          evidence.status,
        )
        val message = evidence.message ?: ""
        assertTrue(
          "evidence message must name the action kind '$actionKind'; got '$message'",
          message.contains("'$actionKind'"),
        )
      }
    } finally {
      session.close()
    }
  }

  @Test
  fun a11yActionClickRoutesThroughDispatchSemanticsAction() {
    // Happy path for the new `a11y.action.click` registry entry: the handler reads
    // `nodeContentDescription`, calls `interactive.dispatchSemanticsAction("click", description)`,
    // and reports `applied` evidence with a message naming the description it matched.
    val framesDir = tempFolder.newFolder("a11y-click-frames")
    val encodedDir = tempFolder.newFolder("a11y-click-encoded")
    val sourcePng = File(tempFolder.newFolder("a11y-click-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng).apply { semanticsActionResult = true }
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-a11y-click",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 0L,
            kind = "a11y.action.click",
            nodeContentDescription = "Save",
          )
        )
      )
      val result = session.stop()

      assertEquals(
        "the registry must route a11y.action.click through dispatchSemanticsAction, not dispatch",
        0,
        interactive.dispatchCount,
      )
      assertEquals(listOf("click" to "Save"), interactive.semanticsActionCalls)
      assertEquals(1, result.scriptEvents.size)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "evidence must name the matched contentDescription; got '$message'",
        message.contains("'Save'"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun a11yActionClickReportsUnsupportedWhenNoNodeMatches() {
    // When `dispatchSemanticsAction` returns false (no matching node, or matched node didn't
    // expose OnClick), the handler must surface a specific unsupported reason — not let the agent
    // think the click landed.
    val framesDir = tempFolder.newFolder("a11y-click-miss-frames")
    val encodedDir = tempFolder.newFolder("a11y-click-miss-encoded")
    val sourcePng = File(tempFolder.newFolder("a11y-click-miss-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng).apply { semanticsActionResult = false }
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-a11y-click-miss",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 0L,
            kind = "a11y.action.click",
            nodeContentDescription = "Nonexistent",
          )
        )
      )
      val result = session.stop()

      assertEquals(1, result.scriptEvents.size)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.UNSUPPORTED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "miss diagnostic must mention the contentDescription so the agent can debug; got '$message'",
        message.contains("'Nonexistent'"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun a11yActionClickRequiresNodeContentDescription() {
    // Bare `kind = "a11y.action.click"` with no contentDescription is meaningless — the registry
    // handler short-circuits to unsupported BEFORE touching `interactive.dispatchSemanticsAction`,
    // so we know at most we waste an empty-string lookup.
    val framesDir = tempFolder.newFolder("a11y-click-blank-frames")
    val encodedDir = tempFolder.newFolder("a11y-click-blank-encoded")
    val sourcePng = File(tempFolder.newFolder("a11y-click-blank-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-a11y-click-blank",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(RecordingScriptEvent(tMs = 0L, kind = "a11y.action.click"))
      )
      val result = session.stop()

      assertTrue(
        "handler must short-circuit before calling interactive.dispatchSemanticsAction",
        interactive.semanticsActionCalls.isEmpty(),
      )
      assertEquals(1, result.scriptEvents.size)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.UNSUPPORTED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "diagnostic must call out the missing nodeContentDescription; got '$message'",
        message.contains("nodeContentDescription"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun lifecycleEventRoutesThroughDispatchLifecycle() {
    // Happy path for the new `lifecycle.event` registry entry: the handler reads
    // `event.lifecycleEvent`, calls `interactive.dispatchLifecycle(...)`, and reports `applied`
    // evidence with a message naming the transition. Pin every supported transition in one go so
    // adding a new entry to `LifecycleRecordingScriptEvents.SUPPORTED_LIFECYCLE_EVENTS` without
    // updating the handler short-circuit is caught here.
    val framesDir = tempFolder.newFolder("lifecycle-frames")
    val encodedDir = tempFolder.newFolder("lifecycle-encoded")
    val sourcePng = File(tempFolder.newFolder("lifecycle-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng).apply { lifecycleResult = true }
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-lifecycle",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        LifecycleRecordingScriptEvents.SUPPORTED_LIFECYCLE_EVENTS.sorted().map { transition ->
          RecordingScriptEvent(
            tMs = 0L,
            kind = LifecycleRecordingScriptEvents.LIFECYCLE_EVENT,
            lifecycleEvent = transition,
          )
        }
      )
      val result = session.stop()

      // Every transition in the supported set routed through dispatchLifecycle in order.
      assertEquals(
        LifecycleRecordingScriptEvents.SUPPORTED_LIFECYCLE_EVENTS.sorted(),
        interactive.lifecycleCalls,
      )
      // None went through pointer or semantics dispatch.
      assertEquals(0, interactive.dispatchCount)
      assertTrue(interactive.semanticsActionCalls.isEmpty())
      // Every event reported applied with the transition named in the message.
      val supported = LifecycleRecordingScriptEvents.SUPPORTED_LIFECYCLE_EVENTS.sorted()
      supported.forEachIndexed { index, transition ->
        val evidence = result.scriptEvents[index]
        assertEquals(
          "evidence for lifecycle.event '$transition' must be applied",
          ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
          evidence.status,
        )
        val message = evidence.message ?: ""
        assertTrue(
          "evidence message must name the lifecycle transition '$transition'; got '$message'",
          message.contains("'$transition'"),
        )
      }
    } finally {
      session.close()
    }
  }

  @Test
  fun lifecycleEventReportsUnsupportedForBlankPayload() {
    // `kind = "lifecycle.event"` without a `lifecycleEvent` payload short-circuits BEFORE
    // touching `interactive.dispatchLifecycle` — we know the agent has nothing meaningful for the
    // host to do, so we surface a precise unsupported reason naming the supported set.
    val framesDir = tempFolder.newFolder("lifecycle-blank-frames")
    val encodedDir = tempFolder.newFolder("lifecycle-blank-encoded")
    val sourcePng = File(tempFolder.newFolder("lifecycle-blank-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-lifecycle-blank",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(tMs = 0L, kind = LifecycleRecordingScriptEvents.LIFECYCLE_EVENT)
        )
      )
      val result = session.stop()

      assertTrue(
        "blank payload must short-circuit before dispatchLifecycle",
        interactive.lifecycleCalls.isEmpty(),
      )
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.UNSUPPORTED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "diagnostic must list the supported transitions; got '$message'",
        message.contains("pause") && message.contains("resume") && message.contains("stop"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun lifecycleEventReportsUnsupportedForUnknownTransition() {
    // `lifecycleEvent: "destroy"` (and any other string outside the supported set) is rejected up
    // front — destroy specifically is documented as v2 because it would tear down the scenario
    // and break subsequent renders.
    val framesDir = tempFolder.newFolder("lifecycle-destroy-frames")
    val encodedDir = tempFolder.newFolder("lifecycle-destroy-encoded")
    val sourcePng = File(tempFolder.newFolder("lifecycle-destroy-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng)
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-lifecycle-destroy",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 0L,
            kind = LifecycleRecordingScriptEvents.LIFECYCLE_EVENT,
            lifecycleEvent = "destroy",
          )
        )
      )
      val result = session.stop()

      assertTrue(
        "unsupported transition must short-circuit before dispatchLifecycle",
        interactive.lifecycleCalls.isEmpty(),
      )
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.UNSUPPORTED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "diagnostic must mention the rejected transition name; got '$message'",
        message.contains("'destroy'"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun previewReloadRoutesThroughDispatchPreviewReload() {
    // Happy path for the new `preview.reload` registry entry: handler calls
    // `interactive.dispatchPreviewReload()` and reports `applied` evidence with a message
    // describing the rebuild. No payload validation needed — the kind alone is sufficient.
    val framesDir = tempFolder.newFolder("preview-reload-frames")
    val encodedDir = tempFolder.newFolder("preview-reload-encoded")
    val sourcePng = File(tempFolder.newFolder("preview-reload-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng).apply { previewReloadResult = true }
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-preview-reload",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 0L,
            kind = PreviewReloadRecordingScriptEvents.PREVIEW_RELOAD_EVENT,
          )
        )
      )
      val result = session.stop()

      assertEquals(
        "the registry must route preview.reload through dispatchPreviewReload, not pointer dispatch",
        0,
        interactive.dispatchCount,
      )
      assertEquals(1, interactive.previewReloadCount)
      assertEquals(1, result.scriptEvents.size)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "evidence must mention the composition rebuild; got '$message'",
        message.contains("composition") && message.contains("rebuilt"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun previewReloadReportsUnsupportedWhenHostCannotReload() {
    // When `dispatchPreviewReload` returns false (no held rule, or reload counter wasn't wired),
    // the handler must surface a specific unsupported reason — not let the agent think the
    // reload landed.
    val framesDir = tempFolder.newFolder("preview-reload-miss-frames")
    val encodedDir = tempFolder.newFolder("preview-reload-miss-encoded")
    val sourcePng = File(tempFolder.newFolder("preview-reload-miss-source"), "source.png")
    ImageIO.write(
      java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
      "png",
      sourcePng,
    )
    val interactive = RecordingDeltaSession(sourcePng).apply { previewReloadResult = false }
    val session =
      AndroidRecordingSession(
        previewId = INTERACTIVE_PREVIEW_ID,
        recordingId = "test-rec-preview-reload-miss",
        fps = FPS,
        scale = 1.0f,
        interactive = interactive,
        framesDir = framesDir,
        encodedDir = encodedDir,
      )

    try {
      session.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 0L,
            kind = PreviewReloadRecordingScriptEvents.PREVIEW_RELOAD_EVENT,
          )
        )
      )
      val result = session.stop()

      assertEquals(1, interactive.previewReloadCount)
      assertEquals(
        ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.UNSUPPORTED,
        result.scriptEvents[0].status,
      )
      val message = result.scriptEvents[0].message ?: ""
      assertTrue(
        "miss diagnostic must mention reload counter wiring; got '$message'",
        message.contains("reload counter"),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun scriptedClickFlipsStateAndProducesFrames() {
    val outputDir = tempFolder.newFolder("recording-renders")
    val recordingsRoot = tempFolder.newFolder("recordings-root")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty(RobolectricHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      assertTrue(
        "supportsRecording must be true with sandboxCount=2 + resolver wired",
        host.supportsRecording,
      )
      assertTrue(
        "supportedRecordingFormats must include apng when recording is supported",
        "apng" in host.supportedRecordingFormats,
      )

      val session =
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-android",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = false,
        )
      try {
        // Click at tMs=67 lands just after the 66 ms frame boundary. The frame-count math must
        // ceil the script duration so frame 3 (100 ms) exists and drains the event.
        session.postScript(
          listOf(
            RecordingScriptEvent(
              tMs = 67L,
              kind = "click",
              pixelX = INTERACTIVE_WIDTH_PX / 2,
              pixelY = INTERACTIVE_HEIGHT_PX / 2,
            )
          )
        )
        val result = session.stop()

        // 1. Frame count: ceil(67ms × 30fps / 1000) + 1 = 4 frames.
        assertEquals(
          "expected 4 frames at 30 fps over 67 ms timeline; got ${result.frameCount}",
          4,
          result.frameCount,
        )
        assertEquals("durationMs", 67L, result.durationMs)
        for (i in 0 until result.frameCount) {
          val f = File(result.framesDir, "frame-${"%05d".format(i)}.png")
          assertTrue("frame $i missing on disk: ${f.absolutePath}", f.isFile)
          assertTrue("frame $i must be non-empty PNG", f.length() > 0)
        }

        // 2. Frame 0 — pre-click; tMs=0 < 66 so no event drained yet → red.
        val frame0 = decode(File(result.framesDir, "frame-00000.png"))
        val redAt0 = pixelMatchPct(frame0, RED_RGB, perChannelTolerance = 8)
        assertTrue(
          "frame 0 expected ≥ 95% red (pre-click); got ${"%.2f".format(redAt0 * 100)}%",
          redAt0 >= 0.95,
        )

        // 3. Frame 3 — click@67 was drained at frame 3; final frame reflects the post-click
        //    composition. This is the load-bearing assertion: it proves the script event reached
        //    the held-rule loop, the state mutated, and the next render captured the new state.
        val frame3 = decode(File(result.framesDir, "frame-00003.png"))
        val greenAt3 = pixelMatchPct(frame3, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "frame 3 expected ≥ 95% green (post-click); got ${"%.2f".format(greenAt3 * 100)}% — " +
            "either dispatchTouchEvent didn't reach pointerInput across the bridge or the held " +
            "composition didn't capture the post-click state",
          greenAt3 >= 0.95,
        )

        // 4. APNG encode round-trip — the agent's eventual `recording/encode` path.
        val encoded = session.encode(RecordingFormat.APNG)
        assertEquals("image/apng", encoded.mimeType)
        val apngFile = File(encoded.videoPath)
        assertTrue("APNG file must exist on disk", apngFile.isFile)
        assertTrue("APNG file must be non-empty", encoded.sizeBytes > 0)
        val firstEight = apngFile.readBytes().copyOf(8)
        val expectedHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertTrue(
          "APNG must carry the PNG header; got ${firstEight.joinToString { "0x%02x".format(it) }}",
          firstEight.contentEquals(expectedHeader),
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithLiveTrueAllocatesLiveRecording() {
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-live-allocated",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
      try {
        assertEquals(true, session.live)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithOverridesAppliesFrameSize() {
    val outputDir = tempFolder.newFolder("recording-override-renders")
    val recordingsRoot = tempFolder.newFolder("recording-override-root")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty(RobolectricHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-android-overrides",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(widthPx = 48, heightPx = 64, density = 1.0f),
          live = false,
        )
      try {
        val result = session.stop()
        assertEquals(1, result.frameCount)
        assertEquals(48, result.frameWidthPx)
        assertEquals(64, result.frameHeightPx)
        val frame0 = decode(File(result.framesDir, "frame-00000.png"))
        assertEquals(48, frame0.width)
        assertEquals(64, frame0.height)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithSandboxCountOneThrowsUnsupported() {
    // Same gating as interactive: sandboxCount=1 refuses recording too because it rides on the
    // held-rule loop's slot pinning.
    val host = RobolectricHost(sandboxCount = 1, previewSpecResolver = previewSpecResolver())
    assertEquals(
      "supportsRecording must be false with sandboxCount=1",
      false,
      host.supportsRecording,
    )
    host.start()
    try {
      try {
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-no-slot",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = false,
        )
        fail("expected UnsupportedOperationException for sandboxCount=1")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
      }
    } finally {
      host.shutdown()
    }
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    if (previewId == INTERACTIVE_PREVIEW_ID) {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "ClickToggleSquare",
        widthPx = INTERACTIVE_WIDTH_PX,
        heightPx = INTERACTIVE_HEIGHT_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "recording-clicktoggle",
      )
    } else null
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

  private class RecordingDeltaSession(private val sourcePng: File) : InteractiveSession {
    override val previewId: String = INTERACTIVE_PREVIEW_ID
    val renderAdvances = mutableListOf<Long?>()
    var dispatchCount = 0
    var closed = false
    val semanticsActionCalls = mutableListOf<Pair<String, String>>()
    val lifecycleCalls = mutableListOf<String>()

    /**
     * When non-null, [dispatchSemanticsAction] returns this value verbatim and records the call
     * into [semanticsActionCalls]. When null (the default), the inherited interface-level default
     * (`return false`) is used so existing tests don't have to opt in.
     */
    var semanticsActionResult: Boolean? = null

    /**
     * When non-null, [dispatchLifecycle] returns this value verbatim and records the call into
     * [lifecycleCalls]. When null (the default), the inherited interface-level default (`return
     * false`) is used so existing tests don't have to opt in.
     */
    var lifecycleResult: Boolean? = null

    override fun dispatch(input: ee.schimke.composeai.daemon.protocol.InteractiveInputParams) {
      dispatchCount++
    }

    override fun dispatchSemanticsAction(
      actionKind: String,
      nodeContentDescription: String,
    ): Boolean {
      semanticsActionCalls += actionKind to nodeContentDescription
      val override = semanticsActionResult
      return override ?: super.dispatchSemanticsAction(actionKind, nodeContentDescription)
    }

    override fun dispatchLifecycle(lifecycleEvent: String): Boolean {
      lifecycleCalls += lifecycleEvent
      val override = lifecycleResult
      return override ?: super.dispatchLifecycle(lifecycleEvent)
    }

    var previewReloadCount: Int = 0

    /**
     * When non-null, [dispatchPreviewReload] returns this value verbatim and increments
     * [previewReloadCount]. When null (the default), the inherited interface-level default
     * (`return false`) is used so existing tests don't have to opt in.
     */
    var previewReloadResult: Boolean? = null

    override fun dispatchPreviewReload(): Boolean {
      previewReloadCount++
      val override = previewReloadResult
      return override ?: super.dispatchPreviewReload()
    }

    override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
      renderAdvances.add(advanceTimeMs)
      return RenderResult(
        id = requestId,
        classLoaderHashCode = 0,
        classLoaderName = "recording-delta-session",
        pngPath = sourcePng.absolutePath,
        metrics = mapOf("tookMs" to 0L),
      )
    }

    override fun close() {
      closed = true
    }
  }

  companion object {
    private const val INTERACTIVE_PREVIEW_ID = "recording-clicktoggle"
    private const val INTERACTIVE_WIDTH_PX = 96
    private const val INTERACTIVE_HEIGHT_PX = 96
    private const val FPS = 30
    private const val RED_RGB = 0xEF5350
    private const val GREEN_RGB = 0x66BB6A
  }
}
