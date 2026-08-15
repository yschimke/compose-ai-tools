package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #3491 — typing and mouse-drag selection over the desktop (CMP JVM) live lane.
 *
 * The reported symptom was oddly specific: on a served `TextField` preview the caret moved and
 * Backspace deleted, but no character ever appeared and no drag ever selected. Both halves have the
 * same root shape — the wire carried only *which key* and *where*, never *what was typed* or *what
 * device pointed* — and Compose needs both to be more than that:
 *
 * 1. **Typing** is gated on `KeyEvent.isTypedEvent`, which on desktop reaches through to the
 *    backing AWT event and demands `KEY_TYPED` with a printable char. Caret/delete commands map off
 *    `Key` alone, so they worked from the same synthesised event that could never type.
 * 2. **Selection** is gated on the pointer's device class: Compose starts a drag-selection for a
 *    *mouse* press-drag; a touch drag is a gesture and leaves the selection alone. Every pointer
 *    was dispatched as `PointerType.Touch`.
 *
 * Each capability is asserted twice — once driven the way the fixed clients drive it, and once
 * driven the way they used to (no `text`, no `pointerType`), which pins the old behaviour as the
 * documented no-op rather than letting a future default silently re-break the fix.
 *
 * Assertions read [TextFieldProbe] rather than pixels: "the value gained an `a`" and "three
 * characters are selected" are not distinctions a colour match can make.
 */
class DesktopTextInputSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun key_down_carrying_text_types_into_the_field() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      session.dispatch(keyDown(keyCode = KEYCODE_A, text = "a"))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals(
        "KEY_DOWN carrying text must insert it — this is the load-bearing #3491 assertion " +
          "(the desktop lane needs a KEY_TYPED-backed event, not just a Key)",
        TEXT_FIELD_SEED + "a",
        TextFieldProbe.text,
      )
    }
  }

  @Test
  fun key_down_without_text_still_types_nothing() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // The pre-#3491 wire shape: the physical key, no typed character. Compose has nothing to
      // insert, so the value must not move — the exact no-op the bug report described.
      session.dispatch(keyDown(keyCode = KEYCODE_A, text = null))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals(
        "a keycode-only KEY_DOWN must not type; it has no character to insert",
        "",
        TextFieldProbe.text,
      )
    }
  }

  @Test
  fun key_down_with_text_but_no_mapped_keycode_still_types() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // A character with no Android keycode in the translation table at all (`€` is not on the
      // wire's key list). The typed half must stand on its own, or every non-US-layout key would
      // silently drop.
      session.dispatch(keyDown(keyCode = null, text = "€"))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals(TEXT_FIELD_SEED + "€", TextFieldProbe.text)
    }
  }

  @Test
  fun key_down_with_an_astral_character_types_it_whole() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // An emoji is one character to the browser (`Array.from(key).length == 1`, so the viewer
      // sends it) and two UTF-16 units to Kotlin. Measuring in `Char`s here would drop it on the
      // floor while the Android lane inserted it — the two backends have to agree.
      session.dispatch(keyDown(keyCode = null, text = "\uD83D\uDE00"))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals(TEXT_FIELD_SEED + "\uD83D\uDE00", TextFieldProbe.text)
    }
  }

  @Test
  fun key_down_with_more_than_one_code_point_types_nothing() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // The wire carries one keystroke, so anything longer than a single code point is a client
      // sending something this path does not model — dropped rather than pasted in.
      session.dispatch(keyDown(keyCode = null, text = "ab"))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals("", TextFieldProbe.text)
    }
  }

  @Test
  fun key_down_with_a_non_printing_key_name_types_nothing() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // Browsers put non-printing key *names* in `KeyboardEvent.key` ("Shift", "ArrowLeft"). A
      // client that forwards them verbatim must not have them land as literal text.
      session.dispatch(keyDown(keyCode = null, text = "Shift"))
      session.dispatch(keyDown(keyCode = null, text = "\u0000"))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals("", TextFieldProbe.text)
    }
  }

  @Test
  fun mouse_drag_selects_text() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      drag(session, pointerType = "mouse")

      assertTrue(
        "a mouse press-drag across the field must leave a non-empty selection; got " +
          "\"${TextFieldProbe.selected}\" — this is the load-bearing #3491 assertion (pointers " +
          "used to be dispatched as touch, which never starts a text selection)",
        TextFieldProbe.selected.isNotEmpty(),
      )
      assertTrue(
        "the selection must come from the seeded content, not from somewhere else",
        TEXT_FIELD_SEED.contains(TextFieldProbe.selected),
      )
    }
  }

  @Test
  fun mouse_drag_selects_from_the_press_even_with_no_render_between_down_and_move() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // Issue #3697 — the shape a *browser* drag actually has. The viewer defers the press until
      // the first pointermove (so a tap stays a click), then sends `pointerDown` and the first
      // `pointerMove` back to back with nothing in between. Every other drag test here renders
      // between the two, which quietly settled the press and hid the race.
      session.dispatch(pointer(InteractiveInputKind.POINTER_DOWN, DRAG_START_X, "mouse"))
      session.dispatch(pointer(InteractiveInputKind.POINTER_MOVE, DRAG_END_X, "mouse"))
      session.render(requestId = RenderHost.nextRequestId())
      // Then on past the end of the text, which is what makes a lost press *visible*: the caret
      // starts at the end of the seed, so a selection anchored on the caret instead of on the
      // press collapses to nothing here rather than merely selecting the wrong span.
      session.dispatch(pointer(InteractiveInputKind.POINTER_MOVE, DRAG_PAST_END_X, "mouse"))
      session.render(requestId = RenderHost.nextRequestId())
      session.dispatch(pointer(InteractiveInputKind.POINTER_UP, DRAG_PAST_END_X, "mouse"))
      session.render(requestId = RenderHost.nextRequestId())

      assertEquals(
        "a drag that presses at the start of the text and ends past its end must select the " +
          "whole seed — anything shorter means the press position was dropped and the selection " +
          "anchored on the pre-existing caret instead",
        TEXT_FIELD_SEED,
        TextFieldProbe.widestSelection,
      )
    }
  }

  @Test
  fun touch_drag_does_not_select_text() {
    withTextField { session ->
      session.render(requestId = RenderHost.nextRequestId())

      // The pre-#3491 dispatch, which is also what a genuine finger drag should still do: a touch
      // drag is a gesture, not a selection.
      drag(session, pointerType = null)

      assertEquals(
        "a touch drag must not select — touch selection is driven by long-press + handles",
        "",
        TextFieldProbe.selected,
      )
    }
  }

  /** Press at [DRAG_START_X], move across the text, release. One pointer, held device class. */
  private fun drag(session: InteractiveSession, pointerType: String?) {
    session.dispatch(pointer(InteractiveInputKind.POINTER_DOWN, DRAG_START_X, pointerType))
    session.render(requestId = RenderHost.nextRequestId())
    session.dispatch(pointer(InteractiveInputKind.POINTER_MOVE, DRAG_END_X, pointerType))
    session.render(requestId = RenderHost.nextRequestId())
    session.dispatch(pointer(InteractiveInputKind.POINTER_UP, DRAG_END_X, pointerType))
    session.render(requestId = RenderHost.nextRequestId())
  }

  private fun keyDown(keyCode: String?, text: String?) =
    InteractiveInputParams(
      frameStreamId = "test-stream-text",
      kind = InteractiveInputKind.KEY_DOWN,
      keyCode = keyCode,
      text = text,
    )

  private fun pointer(kind: InteractiveInputKind, x: Int, pointerType: String?) =
    InteractiveInputParams(
      frameStreamId = "test-stream-text",
      kind = kind,
      pixelX = x,
      pixelY = TEXT_BASELINE_Y,
      pointerId = 0,
      pointerType = pointerType,
    )

  /**
   * Stand up a host over [EditableTextFieldPreview], hand [body] a live interactive session, and
   * tear both down. The probe is reset per test since the fixture's statics outlive the scene.
   */
  private fun withTextField(body: (InteractiveSession) -> Unit) {
    TextFieldProbe.reset()
    val outputDir = tempFolder.newFolder("interactive-text-renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == TEXT_FIELD_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "EditableTextFieldPreview",
              widthPx = 240,
              heightPx = 48,
              density = 1.0f,
              outputBaseName = "editable-text-field",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = TEXT_FIELD_PREVIEW_ID,
          classLoader =
            DesktopTextInputSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        body(session)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private companion object {
    const val TEXT_FIELD_PREVIEW_ID = "editable-text-field"

    /** `KEYCODE_A`, the wire's decimal-string spelling. */
    const val KEYCODE_A = "29"

    /** Inside the first glyph, and inside the field's single line of text at density 1. */
    const val DRAG_START_X = 2
    const val DRAG_END_X = 40

    /**
     * Past the right edge of the seed's glyphs but still inside the field, so a drag that ends here
     * resolves to the last text offset.
     */
    const val DRAG_PAST_END_X = 200
    const val TEXT_BASELINE_Y = 12
  }
}
