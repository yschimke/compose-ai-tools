# Mosaic text-field support — RFC

Companion to [`MOSAIC-MOUSE-RFC.md`](MOSAIC-MOUSE-RFC.md). Same pattern: most of the
machinery already exists at the terminal layer, but the consumer-facing composable that
ties it together is missing.

## What exists today

`com.jakewharton.mosaic.terminal.BracketedPasteEvent(start: Boolean)` — emitted when the
terminal sends `\e[200~` (paste start) or `\e[201~` (paste end). That's exactly the
boundary marker a text field needs to treat a multi-character paste as one transaction
rather than 200 individual `KeyEvent`s. The decoder is wired up; the events just don't
reach the composition.

`Modifier.onKeyEvent` already delivers printable characters one at a time. A
hand-rolled text input loop is technically possible today and is what `:tui-cli`'s
[`App.kt`](src/main/kotlin/ee/schimke/composeai/tui/ui/App.kt) does for its `/` filter
editor — see `handleFilterEdit`. It works for the trivial case (type → backspace →
enter), but breaks on:

- **Paste of multi-line text** — currently flattens to a stream of individual keypresses
  with no transaction boundary. There's no way to "discard everything between this paste
  start and paste end if it contains a newline" or "treat the whole paste as one
  undo unit."
- **Arrow-key cursor movement inside the field** — caret is always pinned to end-of-string.
- **Word-aware editing** — Ctrl+W (delete previous word), Alt+Backspace (delete word),
  Ctrl+U (delete line), Ctrl+A/Ctrl+E (home/end).
- **Selection** — Shift+Arrow to extend selection, Ctrl+C to copy.

Each of these is a wheel worth not reinventing in every Mosaic consumer.

## Proposed API

### 1. `TextFieldState`

Mirror Jetpack Compose's modern text field state contract:

```kotlin
package com.jakewharton.mosaic.ui

class TextFieldState(initialText: String = "") {
  var text: TextFieldValue by mutableStateOf(TextFieldValue(initialText))
  fun edit(block: TextFieldBuffer.() -> Unit)
  // … standard contract: select(start, end), setText(...), undo/redo stack.
}

data class TextFieldValue(
  val text: String,
  val selection: IntRange = text.length..text.length,
)
```

### 2. `BasicTextField` composable

```kotlin
@Composable
fun BasicTextField(
  state: TextFieldState,
  modifier: Modifier = Modifier,
  textStyle: TextStyle = TextStyle.Unspecified,
  cursorChar: Char = '█',
  onSubmit: ((String) -> Unit)? = null,
  onCancel: (() -> Unit)? = null,
)
```

Renders the current text, paints the cursor at `state.text.selection.last`, intercepts
the standard editing keystrokes (Backspace, Arrow-Left/Right, Home/End, Ctrl+A/E/U/W,
Delete), and folds bracketed paste into a single `edit { … }` transaction. `onSubmit` is
invoked on Enter; `onCancel` on Escape.

`TextStyle.Unspecified` falls through to the surrounding `Text` defaults, same as the
existing `Text` composable.

### 3. Cursor rendering

The cursor is a single highlighted cell at the caret position. Visible cursor styling
(blinking, underline, block) is terminal-specific — Mosaic should pick block as the
default since it works on every terminal, with an optional `cursorStyle: CursorStyle`
parameter (`Block`, `Underline`, `Bar`) once the rest of the API is solid.

### 4. Bracketed-paste hookup

At startup, emit `\e[?2004h` to enable bracketed paste; teardown emits `\e[?2004l`. Same
pattern as the mouse RFC's mode-enable bytes. The `terminal.BracketedPasteEvent` decoder
already exists; the consumer-facing hook is on `BasicTextField` (it just needs to know
"paste in progress, group these characters") rather than a separately-exposed modifier.

## Why surface this as a composable rather than a modifier

Text editing has a lot of state per field — text content, caret position, selection
extent, undo stack — and a lot of behaviour — every editing keystroke. A
`Modifier.editableText(...)` would either expose all of that surface in its parameter
list (ugly) or require the consumer to manage it externally (defeats the point). A
composable owns the state internally and emits a single `onChange` / `onSubmit` callback.

This is the same reason Jetpack Compose exposes `BasicTextField` as a composable rather
than a modifier on `Text`.

## Examples

### `:tui-cli`'s filter editor (today vs after)

Today (from `App.kt`):

```kotlin
Modifier.onKeyEvent { event ->
  if (filterEditing) {
    when (event.key) {
      "Enter" -> { index.setFilter(draft); filterEditing = false }
      "Escape" -> { filterEditing = false; draft = "" }
      "Backspace" -> draft = draft.dropLast(1)
      else -> if (event.key.length == 1) draft += event.key
    }
    return@onKeyEvent true
  }
  // … main keymap …
}
```

After:

```kotlin
val state = remember { TextFieldState() }
if (filterEditing) {
  BasicTextField(
    state = state,
    onSubmit = { index.setFilter(it.ifEmpty { null }); filterEditing = false },
    onCancel = { filterEditing = false; state.edit { setText("") } },
  )
}
```

The hand-rolled version is ~15 lines and misses paste handling, cursor movement, word
deletion. The proposed API is 4 lines and does the right thing for all of them.

## Out of scope (initial PR)

- **Multi-line text fields.** Single-line first; the layout-rules for multi-line cursor
  positioning are a follow-up.
- **IME composition.** Terminals don't really do IME — the OS handles it before the
  bytes reach the application. Out of scope unless someone has a concrete need.
- **Validators / formatters.** Consumers compose those around the state externally.
- **Password mode.** A `cursorChar = '•'` and don't-render-text mode is a 5-line addition
  after the basic field works; out of scope for v1.

## Sketched implementation order

1. `TextFieldState` + `TextFieldValue` data classes. Pure-Kotlin, testable without a
   terminal.
2. `BasicTextField` composable that renders text + a static cursor char at the caret,
   handles the printable-key / Backspace / Arrow-Left/Right / Home / End / Enter / Escape
   keymap. No bracketed paste yet.
3. Bracketed paste — enable at startup, accumulate characters between Start/End events
   into one `edit { }` call instead of N.
4. Word-aware deletion (Ctrl+W, Alt+Backspace, Ctrl+U). Selection (Shift+Arrow). Undo/Redo.

Step 2 alone is enough for `:tui-cli` to delete its hand-rolled filter editor; the rest
is gravy.

## Open questions for upstream

- **Should the composable own its `TextFieldState` or require it externally?** Compose
  on Android exposes both flavours — `BasicTextField(state = …)` for state hoisting and
  `BasicTextField(value, onValueChange)` for the simple case. Same here, presumably; just
  worth being explicit.
- **What's the keyboard binding for "submit"?** Enter is the obvious answer, but some
  text fields want Enter to insert a newline and Ctrl+Enter to submit. Probably exposed
  as a callback that returns whether to consume the event.
- **Does this need its own focus tracking?** Today Mosaic delivers every key event to
  every `onKeyEvent` handler in pre-order; multiple text fields would need focus to
  disambiguate. Might pull in a sibling RFC for explicit focus management — but a single
  text field at a time is enough for v1.
