package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Test fixtures for [RenderEngineTest] / [JsonRpcDesktopIntegrationTest]. Lives in the test source
 * set so we don't pollute production code with `@Composable` previews used only for verification.
 *
 * Each preview is a single solid-colour fill at the test sandbox size — the test asserts the PNG's
 * dominant colour matches, mirroring the "is this mostly red?" assertion pattern from
 * `samples/android/.../ScrollPreviewPixelTest.kt`.
 */
@Composable
fun RedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

@Composable
fun BlueSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF42A5F5)))
}

@Composable
fun ThemedPrimarySquare() {
  MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF123456))) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
  }
}

/**
 * Fixture for the B-desktop.1.6 cancellation-invariant regression test. Sleeps for ~500ms inside
 * the composition body so the test can race a `host.shutdown(...)` call against an in-flight render
 * and assert the render still completes (per
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)).
 *
 * The sleep is deliberately *inside* the composition rather than around `scene.render()` because we
 * want to exercise the very window that's most dangerous to cancel — a partly-built Compose graph
 * is the worst leak shape per
 * [PREDICTIVE.md § 9](../../../../../../docs/daemon/PREDICTIVE.md#9-decisions-made).
 */
@Composable
fun SlowSquare() {
  Thread.sleep(500)
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF80FFAA.toInt())))
}

/**
 * Third solid-colour fixture used by D-harness.v1.5b's S4 real-mode test (visibility filter). Same
 * shape as [RedSquare] / [BlueSquare]; distinct hue so a wire-level mix-up between the three
 * preview ids would surface as a pixel-diff failure against the per-id baseline PNG.
 */
@Composable
fun GreenSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A)))
}

/**
 * Fixture for D-harness.v1.5b's S5 real-mode test (renderFailed surfacing). Throws unconditionally
 * inside the composition body so [RenderEngine] propagates the exception out of `scene.render()`
 * and `JsonRpcServer.emitRenderFailed` emits a `renderFailed` notification.
 *
 * The thrown message is matched literally by the test's assertion on
 * `renderFailed.params.error.message`. Kept short and obviously-test-only ("boom") to avoid being
 * mistaken for a real render error in stderr scrollback.
 */
@Composable
fun BoomComposable() {
  error("boom")
}

/**
 * v2 interactive-mode fixture — fills red on first composition, flips to green when clicked. Used
 * by `DesktopInteractiveSessionTest` to assert end-to-end that `interactive/input →
 * DesktopInteractiveSession.dispatch → ImageComposeScene.sendPointerEvent → Modifier.clickable {}`
 * actually mutates composition state. Without v2 (one-shot RenderEngine path), `remember` resets
 * between renders and this preview always paints red — which is the negative-control assertion the
 * v2 work needs to flip.
 *
 * The whole-card `Modifier.clickable {}` covers every click coord we'd plausibly send from the
 * test, so the dispatch math doesn't have to be pixel-perfect; v2's wire shape carries
 * image-natural pixels, and the click region is the entire scene.
 */
@Composable
fun ClickToggleSquare() {
  var clicked by remember { mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  // `awaitEachGesture { awaitFirstDown() }` is the simplest pointer-input shape that fires on a
  // bare Press event — no tap-gesture timing, no slop check, no need for a matching Release.
  // We deliberately avoid `Modifier.clickable {}` here because it sits on top of
  // `detectTapGestures` whose coroutine timing is non-trivial under [ImageComposeScene]'s manual
  // clock. The v2 wire-shape work just needs to prove "the dispatched pointer event reaches the
  // composition"; that's what this fixture asserts.
  Box(
    modifier =
      Modifier.fillMaxSize().background(color).pointerInput(Unit) {
        awaitPointerEventScope {
          awaitFirstDown()
          clicked = true
        }
      }
  )
}

/**
 * Live interactive fixture that exposes Compose's frame clock as pixels. It starts red and turns
 * green after at least 250 ms of frame-clock time has elapsed. If [ImageComposeScene.render] is
 * called without an explicit timestamp, every frame is rendered at `nanoTime = 0` and this preview
 * never advances.
 */
@Composable
fun FrameClockSquare() {
  var firstFrameNs by remember { mutableStateOf<Long?>(null) }
  var elapsedNs by remember { mutableStateOf(0L) }
  LaunchedEffect(Unit) {
    while (true) {
      withFrameNanos { frameNs ->
        val first = firstFrameNs ?: frameNs.also { firstFrameNs = it }
        elapsedNs = frameNs - first
      }
    }
  }
  val color = if (elapsedNs >= 250_000_000L) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color))
}

/**
 * D5 fixture for `RecompositionDataProductRegistryTest`. Exposes a `clicks` mutableStateOf that
 * increments on every press; the inner [androidx.compose.runtime.key]-keyed scope reads `clicks` so
 * it recomposes once per click. The Compose runtime's
 * [androidx.compose.runtime.tooling.CompositionObserver] sees that recomposition as a onScopeExit
 * on the inner block, which the producer counts.
 *
 * Whole-card pointerInput (matches `ClickToggleSquare`'s pattern) so click coords don't matter —
 * the test cares about "did exactly one click cause exactly one recomposition of a recognisable
 * scope?", not pixel routing. Background colour shifts subtly per click so a future pixel-diff
 * regression would flag if the click stopped reaching the composition.
 */
@Composable
fun ClickRecomposingSquare() {
  var clicks by remember { mutableStateOf(0) }
  Box(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFF42A5F5)).pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            awaitFirstDown()
            clicks += 1
          }
        }
      }
  ) {
    // Read `clicks` inside an inner scope so this scope (not the outer Box's) recomposes on
    // every click. Intentionally trivial body — what we care about is that the read of
    // `clicks` invalidates *this* recompose scope when the state mutates.
    androidx.compose.runtime.key(clicks) {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A.toInt() + clicks)))
    }
  }
}

/**
 * D5 audit fixture — the canonical "bad recomposition" shape from yschimke/skills
 * `compose-preview-review/references/agent-audits.md` § "Runtime and recomposition audit": a parent
 * owns a counter, reads it in its own body in order to pass it as a parameter to three children,
 * and only one of those children actually depends on the value. When the parent reads `clicks` to
 * forward as an argument, the parent's own [androidx.compose.runtime.RecomposeScope] subscribes to
 * the snapshot state — so on every click the parent invalidates, the children's `Int` parameters
 * change with it (parameter changes defeat skipping even for stable params), and Compose recomposes
 * all four scopes. That's the bug the audit test catches: clicking once should recompose one thing,
 * not the whole subtree.
 *
 * Whole-card `pointerInput` + `awaitFirstDown` so the click coords don't matter (same pattern as
 * [ClickRecomposingSquare]). The two header / footer children touch `clicks` only to absorb the
 * parameter — they don't read it — so a future "why did this scope recompose?" diagnostic that
 * groups by "parameter changed but never read" would flag them as the surprising entries.
 */
@Composable
fun BadCounterRecompositionFixture() {
  var clicks by remember { mutableStateOf(0) }
  Box(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFFEF5350)).pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            awaitFirstDown()
            clicks += 1
          }
        }
      }
  ) {
    // Reading `clicks` here subscribes the *parent* scope. The argument-passing reads (`clicks`
    // inside each child call expression below) happen during the parent's composition, so the
    // parent invalidates on every click — that's the first surprising scope. The three children
    // each take `clicks` as a parameter; parameter changes invalidate each child scope in turn,
    // so the runtime recomposes all four.
    BadCounterHeader(clicks)
    BadCounterValue(clicks)
    BadCounterFooter(clicks)
  }
}

@Composable
private fun BadCounterHeader(@Suppress("UNUSED_PARAMETER") clicks: Int) {
  // Static copy — does not actually depend on `clicks`. The parameter exists only to be
  // surprising in the audit output. A "fix" would change the signature so this scope is
  // not invalidated when the count changes.
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111111)))
}

@Composable
private fun BadCounterValue(clicks: Int) {
  // The one child that legitimately reads `clicks`. After the fix, this is the only scope
  // expected to appear in a post-click delta.
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A.toInt() + clicks)))
}

@Composable
private fun BadCounterFooter(@Suppress("UNUSED_PARAMETER") clicks: Int) {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF222222)))
}

/**
 * D5 audit fixture — the "fixed" counterpart of [BadCounterRecompositionFixture]. State is hoisted
 * into a [androidx.compose.runtime.MutableState] holder whose *reference* is passed down; the
 * parent never reads `.value` during composition, so its own recompose scope does not subscribe to
 * the snapshot. Only [BetterCounterValue] reads through the holder, so only that one scope
 * invalidates per click. The header and footer take no parameter and are never re-invoked with new
 * arguments after first composition. Expected post-click delta: exactly one scope.
 */
@Composable
fun BetterCounterRecompositionFixture() {
  val counter = remember { mutableStateOf(0) }
  Box(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFFEF5350)).pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            awaitFirstDown()
            // Read+write inside an event-time lambda — not a snapshot read in any composition
            // scope, so neither this Box nor the parent fixture's scope subscribes.
            counter.value += 1
          }
        }
      }
  ) {
    BetterCounterHeader()
    BetterCounterValue(counter)
    BetterCounterFooter()
  }
}

@Composable
private fun BetterCounterHeader() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111111)))
}

@Composable
private fun BetterCounterValue(counter: androidx.compose.runtime.State<Int>) {
  // The snapshot read happens inside *this* scope's body, so only this scope invalidates when
  // the counter changes. The parent forwards the holder reference, not the value, so the
  // forwarding read at the call site is just an object reference — not a snapshot subscription.
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A.toInt() + counter.value)))
}

@Composable
private fun BetterCounterFooter() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF222222)))
}

/**
 * v1 recording-mode component-preview fixture — a small (120×60-typical) tri-state square that
 * cycles red → green → blue on successive clicks. Used by `DesktopRecordingSessionTest` to assert
 * that a scripted timeline of `(tMs=0, click) + (tMs=500, click)` plays back as expected:
 *
 * - Frame 0 (after click@0 drains) paints green.
 * - Frames 1..14 hold green — proving `remember`'d state survives between scripted events.
 * - Frame 15 (at tMs=500, after click@500 drains) paints blue.
 *
 * Same `awaitFirstDown` loop pattern as [ClickRecomposingSquare] so the dispatch path doesn't
 * depend on `Modifier.clickable`'s tap-gesture timing (which is awkward under
 * [androidx.compose.ui.ImageComposeScene]'s manual clock).
 */
@Composable
fun TristateClickSquare() {
  var state by remember { mutableStateOf(0) }
  val color =
    when (state) {
      0 -> Color(0xFFEF5350) // red
      1 -> Color(0xFF66BB6A) // green
      else -> Color(0xFF42A5F5) // blue
    }
  Box(
    modifier =
      Modifier.fillMaxSize().background(color).pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            awaitFirstDown()
            state += 1
          }
        }
      }
  )
}

/**
 * Live-mode failure-propagation fixture for `DesktopRecordingSessionTest`. First composition paints
 * cyan and arms a click watcher; the click flips `boom = true`, the recomposition reads `boom` and
 * `error("…")`s. The thrown exception propagates out of `scene.render()` on the live tick thread —
 * exactly the failure mode Codex flagged: without per-tick try/catch + propagation to `stopLive()`,
 * that throwable would silently terminate the tick thread and `stop()` would lie about success.
 *
 * The pattern matches existing [BoomComposable] (which throws at first composition) but defers the
 * throw so the held scene's `setUp` succeeds and the tick loop has a chance to render at least one
 * healthy frame before failing.
 */
@Composable
fun ClickToBoomSquare() {
  var boom by remember { mutableStateOf(false) }
  if (boom) error("boom-after-click")
  Box(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFF00BCD4)).pointerInput(Unit) {
        awaitPointerEventScope {
          awaitFirstDown()
          boom = true
        }
      }
  )
}

/**
 * Reads `isSystemInDarkTheme()` and fills the box with white in light mode, black in dark mode.
 * Used by `OverrideIntegrationTest` (desktop) to prove `renderNow.overrides.uiMode` reaches
 * `LocalSystemTheme` — Compose Desktop's `isSystemInDarkTheme()` reads that local rather than the
 * OS-level Skiko theme probe.
 */
@Composable
fun DarkAwareSquare() {
  val bg = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White
  Box(modifier = Modifier.fillMaxSize().background(bg))
}

/**
 * Reads `LocalDensity.current.fontScale` and renders a square whose colour encodes the scale —
 * black at fontScale=1.0 (background), white at fontScale=2.0. A pure-pixel signal for proving the
 * override reaches `LocalDensity` without needing `Text` rendering (which would entangle
 * font-metrics across platforms).
 */
@Composable
fun FontScaleAwareSquare() {
  val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
  val bg = if (fontScale >= 1.5f) Color.White else Color.Black
  Box(modifier = Modifier.fillMaxSize().background(bg))
}

/**
 * Reads the *ambient* `MaterialTheme.colorScheme.primary` (no inner [MaterialTheme] wrap) so a
 * `WallpaperOverrideExtension` applied at the outer `AroundComposable` phase visibly drives the
 * background colour. Used by the wallpaper override integration tests.
 */
@Composable
fun WallpaperAwareSquare() {
  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
}
