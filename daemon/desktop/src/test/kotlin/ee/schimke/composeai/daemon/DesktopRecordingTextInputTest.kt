package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #3545 — typing and mouse-drag selection over the **recording** lane, on desktop.
 *
 * [DesktopTextInputSessionTest] pins the same two capabilities for the live *interactive* lane
 * (issues #3491 / #3504). Recording is a second dispatch implementation, and it got none of that:
 * `RecordingInputParams` stopped at `keyCode`, `toScriptEvent` mapped neither `text` nor
 * `pointerType`, and `DesktopRecordingSession` had its own `sendKeyEvent(KeyEvent(key, type))` plus
 * a multi-pointer dispatch pinned to `PointerType.Touch`. So while a recording was active, a
 * viewer's keystrokes and its mouse drags were silently downgraded — a recorded `TextField` could
 * not receive a character and a recorded drag could not select.
 *
 * Both lanes now call one implementation (`DesktopSceneInput.kt`), so these assertions and the
 * interactive ones fail together if either regresses.
 *
 * Covered here:
 * - **scripted** — typing (mapped key, unmapped-key character, astral character), the no-op cases,
 *   and `unsupported` evidence when an event carries neither half;
 * - **scripted** — a mouse press-drag selects while a touch press-drag does not;
 * - **live** — the same over `recording/input`, plus the capture → replay round-trip: the captured
 *   script carries `text` / `pointerType`, and replaying it types the same characters again.
 *
 * Assertions read [TextFieldProbe] rather than pixels: "the value gained an `a`" and "three
 * characters are selected" are not distinctions a colour match can make. Selection is asserted from
 * [TextFieldProbe.widestSelection] because releasing a drag collapses the selection to a caret —
 * real behaviour on both lanes, so the resting state after a completed drag says nothing about
 * whether the drag selected.
 */
class DesktopRecordingTextInputTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun scripted_keyDown_carrying_text_types_into_the_field() {
    withScriptedRecording("typed") { session ->
      session.postScript(listOf(keyDown(tMs = FIRST_EVENT_MS, keyCode = KEYCODE_A, text = "a")))
      val result = session.stop()

      assertEquals(
        "a scripted keyDown carrying text must insert it — this is the load-bearing #3545 " +
          "assertion (the recording lane used to dispatch a code-point-less KeyEvent)",
        TEXT_FIELD_SEED + "a",
        TextFieldProbe.text,
      )
      assertEquals(
        RecordingScriptEventStatus.APPLIED,
        result.scriptEvents.single { it.kind == "input.keyDown" }.status,
      )
    }
  }

  @Test
  fun scripted_keyDown_without_text_still_types_nothing() {
    withScriptedRecording("keycode-only") { session ->
      // The pre-#3545 wire shape: the physical key, no typed character. Compose has nothing to
      // insert, so the value must not move. Pinned so a future default can't silently re-break the
      // fix by inventing a character from the keycode.
      session.postScript(listOf(keyDown(tMs = FIRST_EVENT_MS, keyCode = KEYCODE_A, text = null)))
      val result = session.stop()

      assertEquals("", TextFieldProbe.text)
      assertEquals(
        "a mapped keycode still dispatches the physical key, so the event is APPLIED — it just " +
          "has nothing to type",
        RecordingScriptEventStatus.APPLIED,
        result.scriptEvents.single { it.kind == "input.keyDown" }.status,
      )
    }
  }

  @Test
  fun scripted_keyDown_with_text_but_no_mapped_keycode_still_types() {
    withScriptedRecording("no-keycode") { session ->
      // `€` has no Android keycode on the wire's key list at all. The typed half has to stand on
      // its
      // own or every non-US-layout key silently drops — and, pre-fix, the recording lane reported
      // exactly this event as UNSUPPORTED.
      session.postScript(listOf(keyDown(tMs = FIRST_EVENT_MS, keyCode = null, text = "€")))
      val result = session.stop()

      assertEquals(TEXT_FIELD_SEED + "€", TextFieldProbe.text)
      assertEquals(
        RecordingScriptEventStatus.APPLIED,
        result.scriptEvents.single { it.kind == "input.keyDown" }.status,
      )
    }
  }

  @Test
  fun scripted_keyDown_with_an_astral_character_types_it_whole() {
    withScriptedRecording("astral") { session ->
      // An emoji is one character to the browser (`Array.from(key).length == 1`, so the viewer
      // sends
      // it) and two UTF-16 units to Kotlin. Measuring in `Char`s would drop it here while the
      // Android lane inserted it — the two backends have to agree, in recordings as in live input.
      session.postScript(listOf(keyDown(tMs = FIRST_EVENT_MS, keyCode = null, text = ASTRAL)))
      session.stop()

      assertEquals(TEXT_FIELD_SEED + ASTRAL, TextFieldProbe.text)
    }
  }

  @Test
  fun scripted_keyDown_with_neither_half_emits_unsupported_evidence() {
    withScriptedRecording("nothing-to-dispatch") { session ->
      // Neither a keycode the table knows nor a printable character. Interactive input drops this
      // silently (fire-and-forget); recording owes the agent an explanation.
      session.postScript(
        listOf(keyDown(tMs = FIRST_EVENT_MS, keyCode = UNMAPPED_KEYCODE, text = "Shift"))
      )
      val result = session.stop()

      val evidence = result.scriptEvents.single { it.kind == "input.keyDown" }
      assertEquals(RecordingScriptEventStatus.UNSUPPORTED, evidence.status)
      assertNotNull("unsupported evidence must say why", evidence.message)
      assertEquals("", TextFieldProbe.text)
    }
  }

  @Test
  fun scripted_mouse_drag_selects_text() {
    withScriptedRecording("mouse-drag") { session ->
      session.postScript(dragScript(pointerType = "mouse"))
      session.stop()

      assertTrue(
        "a scripted mouse press-drag across the field must select as it drags; widest selection " +
          "was \"${TextFieldProbe.widestSelection}\" — this is the load-bearing #3545 pointer " +
          "assertion (recorded pointers used to be pinned to PointerType.Touch, which never selects)",
        TextFieldProbe.widestSelection.isNotEmpty(),
      )
      assertTrue(
        "the selection must come from the seeded content, not from somewhere else",
        TEXT_FIELD_SEED.contains(TextFieldProbe.widestSelection),
      )
    }
  }

  @Test
  fun scripted_touch_drag_does_not_select_text() {
    withScriptedRecording("touch-drag") { session ->
      // Absent `pointerType` is the pre-#3545 wire shape *and* what a genuine finger drag should
      // still do: a touch drag is a gesture, not a selection. Every script written before the field
      // existed has to keep replaying exactly like this.
      session.postScript(dragScript(pointerType = null))
      session.stop()

      assertEquals(
        "a touch drag must not select — touch selection is driven by long-press + handles",
        "",
        TextFieldProbe.widestSelection,
      )
    }
  }

  @Test
  fun live_input_types_and_the_captured_script_replays_the_same_text() {
    // Live half: drive `recording/input` the way the viewer does and watch the field fill up.
    val captured =
      withLiveRecording("live-typing") { session ->
        session.postInput(liveKeyDown(keyCode = KEYCODE_A, text = "a"))
        session.postInput(liveKeyDown(keyCode = null, text = "€"))
        Thread.sleep(LIVE_SETTLE_MS)
        val result = session.stop()

        assertEquals(
          "a live recording must type exactly like an ordinary interactive session does",
          TEXT_FIELD_SEED + "a€",
          TextFieldProbe.text,
        )
        result.capturedScript
      }

    // The captured timeline is a persisted artefact — it is written out, handed to
    // `recording/generateTest`, and replayed. If `text` didn't survive into it, a recorded typing
    // session would replay as caret movement.
    val keyEvents = captured.filter { it.kind == "input.keyDown" }
    assertEquals(2, keyEvents.size)
    assertEquals(listOf("a", "€"), keyEvents.map { it.text })

    // Replay half: feed the captured script back through the scripted lane and get the same text.
    withScriptedRecording("live-replay") { session ->
      // Re-stamp onto the scripted timeline's virtual clock: the live capture's `tMs` are
      // wall-clock
      // offsets from `recording/start`, and the field needs a render tick to take focus first.
      session.postScript(
        keyEvents.mapIndexed { i, e -> e.copy(tMs = FIRST_EVENT_MS + i * EVENT_GAP_MS) }
      )
      session.stop()

      assertEquals(
        "replaying a captured live recording must reproduce the characters it typed",
        TEXT_FIELD_SEED + "a€",
        TextFieldProbe.text,
      )
    }
  }

  @Test
  fun live_mouse_drag_selects_and_the_captured_script_records_the_device() {
    val captured =
      withLiveRecording("live-drag") { session ->
        for (input in liveDragInputs(pointerType = "mouse")) {
          session.postInput(input)
          // One input per tick: the tick loop drains the whole queue into a single virtual instant,
          // and a press+move+release collapsed into one instant is not a drag.
          Thread.sleep(LIVE_TICK_MS)
        }
        Thread.sleep(LIVE_SETTLE_MS)
        val result = session.stop()

        assertTrue(
          "a live mouse press-drag must select as it drags; widest selection was " +
            "\"${TextFieldProbe.widestSelection}\"",
          TextFieldProbe.widestSelection.isNotEmpty(),
        )
        result.capturedScript
      }

    val pointerEvents = captured.filter { it.kind.startsWith("input.pointer") }
    assertEquals(3, pointerEvents.size)
    assertTrue(
      "every captured pointer event must record the device it came from, or a replay of the " +
        "capture silently becomes a touch drag: ${pointerEvents.map { it.kind to it.pointerType }}",
      pointerEvents.all { it.pointerType == "mouse" },
    )
  }

  // --- fixtures -------------------------------------------------------------------------------

  private fun keyDown(tMs: Long, keyCode: String?, text: String?) =
    RecordingScriptEvent(tMs = tMs, kind = "input.keyDown", keyCode = keyCode, text = text)

  /** Press inside the first glyph, move across the text, release. One pointer, one device class. */
  private fun dragScript(pointerType: String?): List<RecordingScriptEvent> =
    listOf(
      pointerEvent("input.pointerDown", FIRST_EVENT_MS, DRAG_START_X, pointerType),
      pointerEvent("input.pointerMove", FIRST_EVENT_MS + EVENT_GAP_MS, DRAG_END_X, pointerType),
      pointerEvent("input.pointerUp", FIRST_EVENT_MS + 2 * EVENT_GAP_MS, DRAG_END_X, pointerType),
    )

  private fun pointerEvent(kind: String, tMs: Long, x: Int, pointerType: String?) =
    RecordingScriptEvent(
      tMs = tMs,
      kind = kind,
      pixelX = x,
      pixelY = TEXT_BASELINE_Y,
      pointerId = 0,
      pointerType = pointerType,
    )

  private fun liveKeyDown(keyCode: String?, text: String?) =
    RecordingInputParams(
      recordingId = LIVE_RECORDING_ID,
      kind = InteractiveInputKind.KEY_DOWN,
      keyCode = keyCode,
      text = text,
    )

  private fun liveDragInputs(pointerType: String?): List<RecordingInputParams> =
    listOf(
      livePointer(InteractiveInputKind.POINTER_DOWN, DRAG_START_X, pointerType),
      livePointer(InteractiveInputKind.POINTER_MOVE, DRAG_END_X, pointerType),
      livePointer(InteractiveInputKind.POINTER_UP, DRAG_END_X, pointerType),
    )

  private fun livePointer(kind: InteractiveInputKind, x: Int, pointerType: String?) =
    RecordingInputParams(
      recordingId = LIVE_RECORDING_ID,
      kind = kind,
      pixelX = x,
      pixelY = TEXT_BASELINE_Y,
      pointerId = 0,
      pointerType = pointerType,
    )

  private fun <T> withScriptedRecording(slug: String, body: (RecordingSession) -> T): T =
    withRecording(slug, live = false, recordingId = "rec-$slug", body = body)

  private fun <T> withLiveRecording(slug: String, body: (RecordingSession) -> T): T =
    withRecording(slug, live = true, recordingId = LIVE_RECORDING_ID, body = body)

  /**
   * Stand up a host over [EditableTextFieldPreview], hand [body] a recording session, and tear both
   * down. The probe is reset per test since the fixture's statics outlive the scene.
   */
  private fun <T> withRecording(
    slug: String,
    live: Boolean,
    recordingId: String,
    body: (RecordingSession) -> T,
  ): T {
    TextFieldProbe.reset()
    val outputDir = tempFolder.newFolder("recording-text-renders-$slug")
    val recordingsRoot = tempFolder.newFolder("recording-text-root-$slug")
    savedRecordingsDir = savedRecordingsDir ?: System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == TEXT_FIELD_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "EditableTextFieldPreview",
              widthPx = FIELD_WIDTH_PX,
              heightPx = FIELD_HEIGHT_PX,
              density = 1.0f,
              outputBaseName = "editable-text-field-recording",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = TEXT_FIELD_PREVIEW_ID,
          recordingId = recordingId,
          classLoader =
            DesktopRecordingTextInputTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = live,
        )
      try {
        return body(session)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private companion object {
    const val TEXT_FIELD_PREVIEW_ID = "editable-text-field"
    const val LIVE_RECORDING_ID = "rec-live-text"
    const val FIELD_WIDTH_PX = 240
    const val FIELD_HEIGHT_PX = 48
    const val FPS = 30

    /** `KEYCODE_A`, the wire's decimal-string spelling. */
    const val KEYCODE_A = "29"

    /** Not in `InteractiveKeyCodes`, so the desktop translation table can't map it. */
    const val UNMAPPED_KEYCODE = "9999"

    const val ASTRAL = "😀"

    /**
     * First scripted event's virtual time. Deliberately past frame 0: the fixture takes focus from
     * a `LaunchedEffect`, and events at `tMs = 0` dispatch before the first render tick runs it.
     */
    const val FIRST_EVENT_MS = 100L

    /** Comfortably more than one 30 fps frame, so consecutive events land in different buckets. */
    const val EVENT_GAP_MS = 100L

    /** Inside the first glyph, and inside the field's single line of text at density 1. */
    const val DRAG_START_X = 2
    const val DRAG_END_X = 40
    const val TEXT_BASELINE_Y = 12

    /** Live mode runs on wall-clock: one tick between inputs, and a settle before `stop()`. */
    const val LIVE_TICK_MS = 80L
    const val LIVE_SETTLE_MS = 150L
  }
}
