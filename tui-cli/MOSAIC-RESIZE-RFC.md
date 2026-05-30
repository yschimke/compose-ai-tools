# Mosaic terminal-resize support — RFC

Status: **likely already works, needs verification + documentation.** Filing this as a
short RFC because we can't conclude from `:tui-cli`'s current e2e harness whether
`LocalTerminalState.current.size` recomposes on SIGWINCH or is fixed at composition start,
and that's load-bearing for our wide↔narrow layout flip.

## What we know from inspecting Mosaic 0.18

Decompiled from `mosaic-runtime-jvm-0.18.0.jar`:

```kotlin
// com/jakewharton/mosaic/TerminalState
data class TerminalState(
  val focused: Boolean,
  val theme: Terminal.Theme,
  val size: Terminal.Size,
)

// com/jakewharton/mosaic/TerminalStateKt
val LocalTerminalState: ProvidableCompositionLocal<TerminalState>
```

And `MosaicComposition.class` has nested classes named
`MosaicComposition$terminalState$1$1`, `…$2`, `…$3`, which strongly suggest a Flow / state
collector that emits new `TerminalState` values from one of three sources — most likely:

1. Focus events (`FocusEvent` exists in `terminal/`)
2. Theme events (`TerminalColorEvent`, `Capabilities.themeEvents`)
3. Resize events (no dedicated `ResizeEvent` class in the JAR, but
   `Capabilities.inBandResizeEvents` exists, and SIGWINCH is the JVM-default fallback for
   terminals that don't support in-band resize)

So **the plumbing is probably there**, and `LocalTerminalState.current.size.columns`
*probably* reflects the current cell-grid size after a SIGWINCH. We just don't have an
empirical test that confirms it.

## What the consumer needs

A user dragging their terminal window from 200×50 → 80×30 should see `:tui-cli` flip
from wide to narrow layout without restart. The implementation already reads
`LocalTerminalState.current.size.columns` on every recomposition; whether that triggers
recompose on resize is the only open question.

## Asks for upstream

1. **Document the contract.** Add a KDoc paragraph on `TerminalState.size` explicitly
   confirming whether it's reactive to SIGWINCH / in-band resize, and on which platforms.
   The CHANGELOG entry for 0.14.0 mentions "Synchronized terminal rendering, keyboard
   events, frame times" — none of those name resize directly, so consumers can't tell.

2. **Verify SIGWINCH wiring on JVM.** On Linux/macOS the canonical signal is `SIGWINCH`
   delivered to the controlling process; standard JVM tools (`sun.misc.Signal` /
   `sun.misc.SignalHandler` — yes, the awkward internal API) can listen for it. Confirm
   that whatever Mosaic uses for the third terminal-state source is reading
   `Terminal.Size.fromCurrentTty()` (or equivalent) on signal delivery. Worth a one-line
   integration test that:

   ```kotlin
   @Test fun terminalStateUpdatesOnResize() {
     val sizes = mutableListOf<Terminal.Size>()
     runMosaic { val s = LocalTerminalState.current.size; LaunchedEffect(s) { sizes += s } }
     // Send SIGWINCH to self with a new pty size via PTY mock.
     // Assert: sizes contains both the initial and post-resize sizes.
   }
   ```

3. **Surface `inBandResizeEvents` capability.** The `Capabilities` interface already has
   `getInBandResizeEvents()`. Document what consumers should do with this — is it
   informational only (Mosaic uses in-band reports when available, falls back to SIGWINCH),
   or does the consumer have to opt in?

## Out of scope

- **Synchronous resize callbacks.** Some consumers might want a `Modifier.onResize { … }`
  to react to size changes for non-Compose state (e.g. resizing a non-Mosaic side window).
  Not needed for `:tui-cli` — observing `LocalTerminalState.current.size` from inside the
  composition is enough — but a sibling RFC could propose it if other consumers need it.

## Sketched verification

The cheapest end-to-end test from this consumer's side is a Mosaic-side functional test
that mocks the resize signal source and asserts `TerminalState.size` recomposes. If that
test passes today, this RFC reduces to a documentation-only patch.

If it doesn't, the fix is wiring the resize source through the existing flow that
`TerminalState` collects from — same pattern as focus / theme — and `:tui-cli` gets
responsive layout switching automatically.

---

Marked as "verification" rather than "implementation" because the worst case is "Mosaic
already does this and we just need to say so in KDoc." Worth filing upstream regardless,
so the contract is explicit.
