package ee.schimke.composeai.daemon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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

/**
 * Declares an opt-in `previewOverride*` colour knob (`fill`) driving a solid fill, plus a string
 * `label` knob. [OverrideIntegrationTest.namedOverrideChangesRenderedFill] renders this with no
 * seed (fill = default red) and with a `namedOverrides` seed (fill = blue) to prove a named
 * override reaches the composition and changes the rendered pixels — the render side of what the
 * `/render?knob.<key>=…` serve URL feeds. Mirrors `:daemon:android`'s fixture of the same name.
 */
@Composable
fun OverridableSquare() {
  val fill =
    ee.schimke.composeai.overrides.previewOverrideColor("fill", default = Color(0xFFEF5350))
  ee.schimke.composeai.overrides.previewOverrideString("label", default = "hi")
  Box(modifier = Modifier.fillMaxSize().background(fill))
}

/**
 * Identical fill to [RedSquare] but declared `private`, so it compiles to a JVM-private static
 * method on `RedFixturePreviewsKt`. Kotlin `private fun` previews are a real, supported shape
 * (`samples/android/.../Previews.kt`'s `RedBoxPreview` ships one on purpose). [RenderEngine]
 * resolves it via `getDeclaredComposableMethod` — which scans `declaredMethods` and finds private
 * members — but the reflective `invoke` throws `IllegalAccessException` unless the method is opened
 * with `setAccessible(true)` first. Used by [RenderEngineTest.privateComposableRendersToValidPng].
 */
@Suppress("unused") // invoked reflectively by the daemon's RenderEngine, not from Kotlin
@Composable
private fun PrivateRedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

/**
 * Hybrid `compose/figma-svg` fixture: a red screen with one **opaque** `Image` (a 32×32 green fill)
 * in the top-left. The layout-inspector names the node `Image`, which the exporter classifies as
 * opaque — so the export must emit it as an `<image>` layer and crop the green region out of the
 * captured frame into `figma-raster/`. Used by [RenderEngineTest.figmaSvgExportRastersOpaqueImage].
 */
@Composable
fun OpaqueImageSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350))) {
    Image(
      painter = ColorPainter(Color(0xFF2E7D32)),
      contentDescription = null,
      modifier = Modifier.size(32.dp),
    )
  }
}

/**
 * A composite, non-base fixture for the figma-svg **fidelity harness**: a themed `Surface` holding
 * a title + body `Text` and a coloured `Box`. Unlike the single-fill squares this exercises several
 * of the export's structural concerns at once — a resolved surface fill, text baselines/typography,
 * and a nested container — so the fidelity score is meaningful rather than trivially 100%. Used by
 * [RenderEngineTest.figmaSvgFidelityScoresARender].
 */
@Composable
fun FidelityCardPreview() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Surface(color = MaterialTheme.colorScheme.surface) {
      Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(text = "Fidelity", style = MaterialTheme.typography.titleMedium)
        Text(text = "harness card", style = MaterialTheme.typography.bodyMedium)
        Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary))
      }
    }
  }
}

/**
 * Raw-pixel corner fixture for the figma-svg export: a filled box clipped with
 * `RoundedCornerShape(20f)` — a **pixel** corner (`PxCornerSize`), not dp. The dp `cornerRadius`
 * token can't express it, so before the raw-px capture the export dropped it to a sharp rect; now
 * `ModifierTokenResolver.cornerRadiusPxWire` reads the `PxCornerSize` and the export rounds it.
 * Used by [RenderEngineTest.figmaSvgExportRoundsRawPixelCorner] — the padding keeps the rounded
 * edge off the canvas edge so it's unmistakable in the SVG.
 */
@Composable
fun PxCornerSquare() {
  Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF6750A4), RoundedCornerShape(20f)))
  }
}

/**
 * Cut-corner fixture for the figma-svg export: a filled box clipped with `CutCornerShape(20.dp)`.
 * The capture reports the corner size on `cornerRadius` plus a `shape="cut"` descriptor; before it
 * was consumed the export ignored the descriptor and *rounded* the corner, so a bevelled shape
 * rendered wrong. Used by [RenderEngineTest.figmaSvgExportChamfersCutCorner].
 */
@Composable
fun CutCornerSquare() {
  Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF6750A4), CutCornerShape(20.dp)))
  }
}

@Composable
fun ThemedPrimarySquare() {
  MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF123456))) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
  }
}

/**
 * Themed-text fixture for `compose/theme` consumer attribution (#1847). The `Text` reads two tokens
 * off the active Material theme — the `error` colour role and the `titleMedium` typography style —
 * so the theme producer should attribute this node to {`error`, `titleMedium`}. Both are chosen
 * because each resolves to a value no other M3 role/style shares, keeping the assertion exact.
 */
@Composable
fun ThemedAttributionText() {
  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    Text(
      text = "Hi",
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

/**
 * Fixture for `assert.textEquals` (issue #1965). The `Text` carries both a `testTag` and its text
 * on the same semantics node, so a script can resolve it by `testTag("greeting")` and assert the
 * resolved node's text equals `"Hello"`.
 */
@Composable
fun TaggedTextSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350))) {
    Text(text = "Hello", modifier = Modifier.testTag("greeting"))
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
 * Issue #1784 fixture — proves an interaction can target a node by `testTag` (resolved to its
 * centre point server-side) instead of pixel coordinates. The whole 64×64 card starts red; only a
 * click inside the small 20×20 `testTag("target-box")` node pinned to the **top-left corner** flips
 * it green.
 *
 * The target node is deliberately off-centre: a naive centre click (`32,32`) misses it, so a green
 * result is only reachable by resolving the testTag to its real centroid (~`10,10`). That makes the
 * green assertion load-bearing for `DesktopInteractiveSession`'s `target` → centroid resolution
 * rather than something a whole-card click would also satisfy.
 */
@Composable
fun TaggedClickTargetSquare() {
  var clicked by remember { mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color)) {
    Box(
      modifier =
        Modifier.size(20.dp).testTag("target-box").pointerInput(Unit) {
          awaitPointerEventScope {
            awaitFirstDown()
            clicked = true
          }
        }
    )
  }
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
 * Reads `androidx.compose.ui.text.intl.Locale.current` — the exact source CMP string resources
 * resolve their locale from (`rememberResourceEnvironment()` → `Locale.current`, which on
 * Skiko/desktop is the JVM default `Locale`) — and encodes its language subtag as a solid fill:
 * green for German (`de`), blue for Arabic (`ar`), red for anything else (the base / English case).
 * A pure-pixel signal proving a `localeTag` override reaches the locale CMP `stringResource(...)`
 * reads, without needing a generated `Res` / `composeResources` set on the test classpath. Used by
 * [OverrideIntegrationTest.localeTagOverrideReachesComposeResourceLocale].
 */
@Composable
fun LocaleAwareSquare() {
  val bg =
    when (androidx.compose.ui.text.intl.Locale.current.language) {
      "de" -> Color(0xFF66BB6A) // green
      "ar" -> Color(0xFF42A5F5) // blue
      else -> Color(0xFFEF5350) // red (base / English)
    }
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

/**
 * A `PreviewWrapperProvider`-shaped stand-in for an app's `@ThemeCatalog` theme — its `Wrap`
 * installs a `MaterialTheme` with a distinctive blue `primary`. Used by
 * [OverrideIntegrationTest.themeProviderOverrideWrapsPreviewInDeclaredTheme] to prove that a
 * `renderNow.overrides.themeProvider = <this FQN>` renders an arbitrary preview (e.g.
 * [WallpaperAwareSquare], which paints the ambient `colorScheme.primary`) under this theme instead
 * of the M3 default. The renderer resolves it by FQN and invokes `Wrap` reflectively
 * (`getDeclaredComposableMethod("Wrap", …)`, the same path `@PreviewWrapper` uses), so a no-arg
 * class exposing a `@Composable Wrap(content)` is all it needs — it does not have to implement the
 * `PreviewWrapperProvider` interface (which is Compose 1.11+ and absent from this CMP classpath),
 * and `@ThemeCatalog` only adds *discovery*, not the apply path exercised here.
 */
@Suppress("unused") // instantiated reflectively by the daemon's InvokeWithOptionalWrapper
class BluePrimaryThemeProvider {
  @Composable
  fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1565C0))) { content() }
}

/**
 * Issue #1203 — fixture for the desktop keyboard-dispatch integration tests.
 *
 * Paints red on first composition; flips green when the `Key.A` key down event reaches the
 * composition's focused node. The fixture installs a [FocusRequester] and requests focus inside a
 * [LaunchedEffect] so the held scene's key-event pipeline has a target by the time the test
 * dispatches its first `KEY_DOWN(KEYCODE_A)`. Without focus, `BaseComposeScene. sendKeyEvent` has
 * nowhere to deliver the event and the assertion would fail spuriously.
 *
 * Used by `DesktopInteractiveSessionTest.key_down_input_flips_state_and_repaints` and
 * `DesktopRecordingSessionTest.scripted_keyDown_flips_state_and_emits_applied_evidence`. Both
 * backends share the fixture so a wire-level mix-up between interactive and recording paths would
 * surface as the same colour transition test.
 */
/**
 * Mirrors the design-catalog `CatalogSticker`: a Material 3 [Surface] that paints an opaque
 * `colorScheme.surface` fill by default, but drops it to `Color.Transparent` when the render sets
 * [ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared] (the `clearBackground` "crisp
 * outline" override). The content is a real M3 [OutlinedButton] — a component that is *itself*
 * mostly transparent (a bordered outline with a text label), so clearing the surface leaves a crisp
 * floating outline on transparency rather than a button embedded in a solid card. The corner pixels
 * carry the *background* signal — opaque light surface when not cleared, fully transparent when
 * cleared. Used by [RenderEngineClearBackgroundTest] to prove the override reaches both the harness
 * background (Layer 1) and a composable's own fill (Layer 2), and to emit before/after visual
 * evidence.
 */
@Composable
fun SurfaceCardSquare() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    val cleared = ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared.current
    Surface(
      color = if (cleared) Color.Transparent else MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
      ) {
        androidx.compose.material3.OutlinedButton(onClick = {}) { Text("Outlined") }
      }
    }
  }
}

/**
 * A wrap-content sticker fixture mirroring the design-catalog `CatalogSticker` — a small component
 * in 16.dp padding with **no `fillMaxSize`**, so its intrinsic size is far smaller than the render
 * sandbox. Used by [RenderEngineWrapContentTest] to prove the AS-parity wrap crop the interactive /
 * stream lane was missing: rendered wrap-OFF the component sits small in the **top-left** of the
 * fixed frame (the old live-stream framing — content in the corner), while rendered wrap-ON the
 * frame crops to the component's intrinsic size (matching the wrap-cropped baked snapshot). That
 * crop is exactly what stops a catalog sticker from shifting size + position when the viewer
 * toggles PNG ↔ Live Compose.
 */
@Composable
fun WrapContentStickerPreview() {
  Box(modifier = Modifier.padding(16.dp)) {
    Box(
      modifier = Modifier.size(56.dp).background(Color(0xFFB71C1C), RoundedCornerShape(28.dp)),
      contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
      Text(text = "8", color = Color.White)
    }
  }
}

/**
 * Realistic mobile scrolling-screen fixture for the figma-svg scroll experiment: a Material 3
 * [Scaffold] with a pinned [TopAppBar] ("status bar") and a bottom [NavigationBar] ("bottom
 * buttons") framing a `LazyColumn` of 30 numbered rows — far more than fit in a phone-height
 * viewport.
 *
 * `LazyColumn` is virtualised, so at a normal viewport height only the on-screen rows (plus
 * LazyList's small prefetch) are composed and land in the captured layout/semantics tree; the rest
 * have no `LayoutNode` and are absent from the `compose/figma-svg` export. Rendered at an
 * *expanded* (tall) viewport every row lays out, so the export carries all 30 — bookended by the
 * pinned top app bar and the bottom navigation bar, which is exactly the "full page" representation
 * the mobile SVG scroll mode produces. [RenderEngineFigmaSvgScrollTest] renders it both ways and
 * counts the `Row N` text layers to prove the "expand the device vertically" approach.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LazyColumnListPreview() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    androidx.compose.material3.Scaffold(
      topBar = { androidx.compose.material3.TopAppBar(title = { Text("Activity") }) },
      bottomBar = {
        // Hand-rolled bottom navigation bar ("bottom buttons") — three labelled icon slots. Built
        // from primitives so the fixture doesn't depend on an M3 `NavigationBar` artifact that may
        // be absent from this module's classpath.
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
          androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
          ) {
            listOf("Home", "Search", "Profile").forEach { label ->
              Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Box(
                  modifier =
                    Modifier.size(24.dp)
                      .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                )
                Text(text = label, style = MaterialTheme.typography.labelSmall)
              }
            }
          }
        }
      },
    ) { contentPadding ->
      LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items((1..30).toList()) { index ->
          Column(
            modifier =
              Modifier.fillMaxWidth()
                .background(if (index % 2 == 0) Color(0xFFEEEEEE) else Color.White)
                .padding(12.dp)
          ) {
            Text(text = "Row $index", style = MaterialTheme.typography.titleMedium)
          }
        }
      }
    }
  }
}

@Composable
fun KeyPressColorSquare() {
  var pressed by remember { mutableStateOf(false) }
  val color = if (pressed) Color(0xFF66BB6A) else Color(0xFFEF5350)
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(color)
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.A) {
            pressed = true
            true
          } else false
        }
  )
}
