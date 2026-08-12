package ee.schimke.composeai.daemon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * Desktop mirror of the Android `IconButtonRowInputBar` fidelity fixture (issue #2853), the
 * **padded icon** case: Jetchat's `Conversation/Input` row of `InputSelectorButton`s, each an
 * `IconButton` around `Icon(modifier = Modifier.padding(8.dp).size(56.dp))`, plus its
 * `RecordButton` shape — `Icon(modifier = Modifier.sizeIn(minWidth = 56.dp, …).padding(18.dp))`. In
 * both, the padding ahead of the painter insets the box the glyph is drawn into, so a fit taken
 * from the node's own box draws the glyph at its button's size. This is the one that scores the
 * fix: the fidelity harness rasterises the SVG against the render, and an oversized glyph shows up
 * as a direct score drop.
 */
@Composable
fun IconButtonRowInputBar() {
  val glyph =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(fill = SolidColor(Color.White)) {
          moveTo(2f, 2f)
          lineTo(22f, 2f)
          lineTo(22f, 22f)
          lineTo(2f, 22f)
          close()
        }
      }
      .build()

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F))) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      repeat(2) { index ->
        IconButton(onClick = {}) {
          Icon(
            imageVector = glyph,
            contentDescription = "action-$index",
            tint = Color.White,
            modifier = Modifier.testTag("action-$index").padding(8.dp).size(56.dp),
          )
        }
      }
      Icon(
        imageVector = glyph,
        contentDescription = "record",
        tint = Color.White,
        modifier = Modifier.testTag("record-mic").sizeIn(minWidth = 56.dp).padding(18.dp),
      )
    }
  }
}

/**
 * Desktop mirror of the Android `AlphaZeroRecordButton` fidelity fixture (issue #2853): a recording
 * circle faded to `alpha = 0` through a `graphicsLayer` lambda block, over a visible vector mic in
 * an input-bar row. Same FQN + function name as the Android copy so one harness scenario resolves
 * on either backend; the desktop backend is the one that rasterises the SVG and scores fidelity.
 */
@Composable
fun AlphaZeroRecordButton() {
  val mic =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(fill = SolidColor(Color.White)) {
          moveTo(12f, 3f)
          lineTo(15f, 3f)
          lineTo(15f, 13f)
          lineTo(12f, 13f)
          close()
        }
      }
      .build()

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F))) {
    Row(
      modifier = Modifier.fillMaxSize().padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // A stand-in for the input field the record button sits beside (kept text-free so the fixture
      // renders without a downloadable font under Robolectric on the Android backend).
      Box(
        modifier =
          Modifier.testTag("input-field")
            .width(120.dp)
            .height(24.dp)
            .background(Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
      )
      Box(modifier = Modifier.width(8.dp).height(1.dp))
      Box(contentAlignment = Alignment.Center) {
        Box(
          modifier =
            Modifier.testTag("record-circle")
              .size(40.dp)
              .graphicsLayer {
                alpha = 0f
                scaleX = 0.8f
                scaleY = 0.8f
              }
              .background(Color(0xFF2962FF), RoundedCornerShape(20.dp))
        )
        Icon(
          imageVector = mic,
          contentDescription = "record",
          tint = Color.White,
          modifier = Modifier.testTag("record-mic").size(24.dp),
        )
      }
    }
  }
}

/**
 * Desktop mirror of the Android `VectorIconInAnimatedLayout` fidelity fixture (issue #2853): a
 * square create icon scaled through a graphics layer, as in Jetchat's `Profile/Animating FAB
 * content`. The export must fit the vector to its drawn bounds once and keep it square, not
 * double-count the captured scale.
 */
@Composable
fun VectorIconInAnimatedLayout() {
  val create =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(fill = SolidColor(Color.White)) {
          moveTo(11f, 5f)
          lineTo(13f, 5f)
          lineTo(13f, 11f)
          lineTo(19f, 11f)
          lineTo(19f, 13f)
          lineTo(13f, 13f)
          lineTo(13f, 19f)
          lineTo(11f, 19f)
          lineTo(11f, 13f)
          lineTo(5f, 13f)
          lineTo(5f, 11f)
          lineTo(11f, 11f)
          close()
        }
      }
      .build()

  Box(
    modifier = Modifier.fillMaxSize().background(Color(0xFF6200EE)),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier =
        Modifier.testTag("fab-content").graphicsLayer {
          scaleX = 1.5f
          scaleY = 1.5f
        }
    ) {
      Icon(
        imageVector = create,
        contentDescription = "create",
        tint = Color.White,
        modifier = Modifier.testTag("create-icon").size(24.dp),
      )
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
 * High-density coordinate-contract fixture for the hosted live/recording lanes. The only clickable
 * node is pinned to the top-right: at 2.625x its centre is around (142, 26) in a 168px frame.
 * Dividing that natural-pixel x coordinate by density moves the event to ~54px and misses it.
 */
@Composable
fun HighDensityClickTargetSquare() {
  var clicked by remember { mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color)) {
    Box(
      modifier =
        Modifier.align(Alignment.TopEnd).size(20.dp).pointerInput(Unit) {
          awaitPointerEventScope {
            awaitFirstDown()
            clicked = true
          }
        }
    )
  }
}

/**
 * Records the JVM default `Locale` each time [PressLocaleProbeSquare] composes.
 *
 * `localeTag` is applied by pointing the JVM default `Locale` at the override for the duration of a
 * composition / frame — that is what CMP `stringResource(...)` resolves against on desktop (see
 * `RenderEngine.overrideJvmDefaultLocale`). So "which locale was live while this composed" *is* the
 * JVM default at composition time, and reading it is how a test can tell whether a given frame ran
 * inside the override or outside it.
 */
object PressLocaleProbe {
  @Volatile var composedUnder: String? = null

  /**
   * Every locale seen, keyed by the composing thread's name.
   *
   * A held session composes on its own `compose-ai-daemon-interactive-scene-<previewId>` executor,
   * so the thread name is which *session* composed — which is what a concurrency test needs, since
   * two sessions share this one object.
   */
  val observedByThread: java.util.concurrent.ConcurrentHashMap<String, MutableList<String>> =
    java.util.concurrent.ConcurrentHashMap()

  /**
   * Held open, mid-composition, on threads whose name contains [gateThreadMarker].
   *
   * A locale leak is a *window*, not a state: it needs one session to be inside the override while
   * another composes. Parking the localized session inside its composition is how a test opens that
   * window on purpose instead of hoping the scheduler produces it.
   */
  @Volatile var gate: java.util.concurrent.CountDownLatch? = null

  /** Counted down once a gated composition has recorded and is about to park. */
  @Volatile var gateReached: java.util.concurrent.CountDownLatch? = null

  @Volatile var gateThreadMarker: String? = null

  fun record() {
    val thread = Thread.currentThread().name
    val tag = java.util.Locale.getDefault().toLanguageTag()
    composedUnder = tag
    observedByThread
      .computeIfAbsent(thread) { java.util.Collections.synchronizedList(mutableListOf()) }
      .add(tag)
    val marker = gateThreadMarker
    if (marker != null && thread.contains(marker)) {
      gateReached?.countDown()
      gate?.await(30, java.util.concurrent.TimeUnit.SECONDS)
    }
  }

  /** Locales recorded by composing threads whose name contains [threadMarker]. */
  fun observedOn(threadMarker: String): List<String> =
    observedByThread.entries.filter { it.key.contains(threadMarker) }.flatMap { it.value.toList() }

  fun reset() {
    composedUnder = null
    observedByThread.clear()
    gate = null
    gateReached = null
    gateThreadMarker = null
  }
}

/**
 * Recomposes on **every frame** and publishes the locale each one ran under to [PressLocaleProbe].
 *
 * [PressLocaleProbeSquare] only recomposes once, on the press — enough to say what the settling
 * frame ran under, but silent about the frames after it. A recording renders a frame per tick and
 * is the lane where "the composition kept running long after `setUp`" matters, so its probe has to
 * report on every one of them.
 */
@Composable
fun FrameTickLocaleProbeSquare() {
  var tick by remember { mutableStateOf(0L) }
  PressLocaleProbe.record()
  LaunchedEffect(Unit) {
    while (true) {
      withFrameNanos { tick = it }
    }
  }
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(if (tick % 2L == 0L) Color(0xFFEF5350) else Color(0xFF66BB6A))
  )
}

/**
 * Recomposes on a pointer press and publishes the locale it recomposed under to [PressLocaleProbe].
 *
 * The press-settling render is a frame like any other — it composes whatever the press invalidated
 * — so it has to run inside the same `localeTag` scope as the capture frames, or a localized
 * preview caches host-language resources on a press it can never re-resolve.
 */
@Composable
fun PressLocaleProbeSquare() {
  var pressed by remember { mutableStateOf(false) }
  PressLocaleProbe.record()
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(if (pressed) Color(0xFF66BB6A) else Color(0xFFEF5350))
        .pointerInput(Unit) {
          awaitPointerEventScope {
            awaitFirstDown()
            pressed = true
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

/**
 * `@PreviewParameter` regression fixture — the desktop counterpart of `:daemon:android`'s
 * `SquareTintProvider` / `ThemedTintedSquare` (issue #3027 on Android; the desktop follow-up that
 * taught the desktop daemon to resolve `@PreviewParameter` at all).
 *
 * The desktop daemon used to resolve every preview with the parameterless
 * `getDeclaredComposableMethod(functionName)` lookup, which matches only `foo(Composer, int)`. A
 * preview with a `@PreviewParameter` argument compiles to `ThemedTintedSquare(long, Composer,
 * int)`, so resolution threw `NoSuchMethodException` before composition started — no PNG, none of
 * the composition-derived data products, and `bundle pack --with-semantics` dropped the whole
 * fan-out's a11y tree on CMP/desktop modules.
 *
 * The provider is resolved reflectively via `getValues()` — the harness manifest carries its FQN in
 * `previewParameterProviderClassName`, so it needs no `@PreviewParameter` annotation or
 * `PreviewParameterProvider` supertype here, just a `values: Sequence` property and a `Long`
 * parameter, matching the shapes [ee.schimke.composeai.renderer.PreviewParameterSupport] probes.
 *
 * Two values on purpose: the daemon renders one frame per preview id, so it must invoke the FIRST
 * one (green `0xFF43A047`), never the second (blue `0xFF1E88E5`). A regression that silently picks
 * the wrong value fails the pixel assertion instead of passing on "something rendered".
 */
@Suppress("unused")
class SquareTintProvider {
  val values: Sequence<Long> = sequenceOf(0xFF43A047L, 0xFF1E88E5L)
}

@Composable
fun ThemedTintedSquare(tint: Long) {
  Box(modifier = Modifier.fillMaxSize().background(Color(tint)))
}

/**
 * Stands in for Wear M3's `AnimatedMorphShape`, the wrapper every `RoundButton`-family container —
 * including the `Stepper` volume buttons that exposed this — puts between its corners and the
 * modifier chain. Matches the real class field-for-field, because that is exactly what
 * `ModifierTokenResolver` reflects on: a resting [shape] and a [pressedShape], plus a `morphState`
 * whose name ends in `state` and so is picked up (and rejected) by the older `getMorphedShape()`
 * unwrap before the resting-field unwrap gets its turn.
 *
 * Not a `CornerBasedShape`, so no corner getter sees it, and its outline is deliberately an
 * `Outline.Generic` morph path rather than an `Outline.Rounded` — which is what defeated the
 * rounded-outline fallback too. Used by [RenderEngineTest.figmaSvgExportUnwrapsAnimatedMorphShape].
 */
private class FixtureAnimatedMorphShape(
  private val shape: Shape,
  private val pressedShape: Shape,
  @Suppress("unused") private val progress: () -> Float = { 0f },
  @Suppress("unused") private val morphState: MutableMap<Size, Any> = mutableMapOf(),
) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
    Outline.Generic(
      Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
      }
    )
}

/**
 * Wear-M3-shaped corner fixture: a 60×48 box filled through a shape wrapper that hides its corners
 * exactly the way `AnimatedMorphShape` does. The resting shape is a full pill, so a correct export
 * rounds it to `rx = 48`; before the resting-field unwrap the whole node exported as a sharp
 * `<rect>` — a square drawn over the correctly-rounded pixels beneath it (issue #3254).
 */
@Composable
fun AnimatedMorphShapeButton() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(
      modifier =
        Modifier.size(60.dp, 48.dp)
          .background(
            Color(0xFF04409F),
            FixtureAnimatedMorphShape(shape = CircleShape, pressedShape = RoundedCornerShape(8.dp)),
          )
    )
  }
}

/**
 * The general guard behind the unwrap: a bespoke shape that reduces to *no* corners at all, whose
 * outline is only ever an `Outline.Generic` triangle. Nothing can turn this into a `<rect rx>`, so
 * the export must fall back to the shape's sampled outline rather than asserting a sharp rectangle
 * it never established. Used by [RenderEngineTest.figmaSvgExportVectorisesUnreducibleShape].
 */
private object TriangleShape : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
    Outline.Generic(
      Path().apply {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
      }
    )
}

/** A filled box whose shape has no corners to report at all — see [TriangleShape]. */
@Composable
fun GenericOutlineTriangle() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(modifier = Modifier.size(60.dp, 48.dp).background(Color(0xFF04409F), TriangleShape))
  }
}

/**
 * Reuse-pool fixture (issue #3324): a `LazyColumn` whose first row disappears one frame in, so its
 * `LayoutNode` is retired into `SubcomposeLayout`'s slot-reuse pool — **deactivated**, unplaced,
 * still holding the retired row's text — while the surviving rows shift up.
 */
@Composable
fun ReusedSlotGhostPreview() {
  var dropFirst by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    withFrameNanos {}
    dropFirst = true
  }
  MaterialTheme(colorScheme = lightColorScheme()) {
    Surface(color = Color.White) {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(if (dropFirst) listOf("Kept row") else listOf("Ghost row", "Kept row")) { label ->
          Text(text = label, modifier = Modifier.fillMaxWidth().padding(4.dp))
        }
      }
    }
  }
}

/**
 * Scroll-then-capture fixture (issue #3324): a `LazyColumn` that scrolls a few rows in before the
 * frame is captured, so the rows that left the viewport are retired into `SubcomposeLayout`'s
 * slot-reuse pool — deactivated, unplaced, still holding their text.
 */
@Composable
fun ScrolledLazyColumnPreview() {
  val state = androidx.compose.foundation.lazy.rememberLazyListState()
  LaunchedEffect(Unit) {
    withFrameNanos {}
    state.scrollToItem(8)
  }
  MaterialTheme(colorScheme = lightColorScheme()) {
    Surface(color = Color.White) {
      LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items((1..30).toList()) { index ->
          Text(text = "Row $index", modifier = Modifier.fillMaxWidth().padding(4.dp))
        }
      }
    }
  }
}

/**
 * Records what a live text-editing session did to [EditableTextFieldPreview]'s value, so the
 * interactive tests can assert on the field's *content* and *selection* rather than on pixels —
 * "the caret moved" and "three characters are selected" are not things a colour match can tell
 * apart.
 *
 * Written from the composition and read from the test thread, hence `@Volatile`; the fixture and
 * its test share a classloader (the interactive session is handed the test's own), so these are
 * genuinely the same statics.
 */
object TextFieldProbe {
  @Volatile var text: String = ""

  @Volatile var selected: String = ""

  /**
   * The widest selection seen across every value change, rather than whatever is selected right
   * now.
   *
   * Releasing a mouse drag collapses the selection back to a caret — real Compose behaviour, and
   * what a user sees when they let go — so [selected] read after a completed drag is legitimately
   * empty. A test that drives a whole press → drag → release and wants to assert "this drag
   * selected" has to look at what the drag produced, not at the resting state after it.
   */
  @Volatile var widestSelection: String = ""

  fun record(value: TextFieldValue) {
    text = value.text
    selected = value.text.substring(value.selection.min, value.selection.max)
    if (selected.length > widestSelection.length) widestSelection = selected
  }

  fun reset() {
    text = ""
    selected = ""
    widestSelection = ""
  }
}

/**
 * Editable text field for the typing / selection dispatch tests (issue #3491). Self-focusing, so a
 * test can drive the keyboard without first synthesising a click, and it publishes every value
 * change — text *and* selected substring — into [TextFieldProbe].
 *
 * Deliberately a plain `BasicTextField` over a `TextFieldValue`: the selection range is the thing
 * under test, and `TextFieldValue` is where it lives.
 */
@Composable
fun EditableTextFieldPreview() {
  // Caret seeded at the end, where focusing a real field leaves it, so a typed character appends
  // rather than landing in front of the seed.
  var value by remember {
    mutableStateOf(TextFieldValue(TEXT_FIELD_SEED, TextRange(TEXT_FIELD_SEED.length)))
  }
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
    BasicTextField(
      value = value,
      onValueChange = {
        value = it
        TextFieldProbe.record(it)
      },
      textStyle = TextStyle(color = Color.Black, fontSize = 20.sp),
      modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
  }
}

/** Seed content of [EditableTextFieldPreview] — wide enough that a drag can select part of it. */
const val TEXT_FIELD_SEED: String = "Filled"

/**
 * A Material 3 [androidx.compose.material3.DateRangePicker] — a component whose long scroll is
 * **built in**, not something the caller wired up.
 *
 * Every other scrolling fixture here declares its own `LazyColumn`, so a scroll capture over them
 * only proves the driver can move a scrollable the fixture handed it. `DateRangePicker` is the
 * interesting case: it lays its months out as one continuously scrolling list inside the component,
 * and the call site below passes no scroll state, no modifier and no list. (Its sibling
 * `DatePicker` is *not* this — that one shows a single month with paging arrows, and keeps its only
 * lazy list in the year picker behind a toggle.) Driving this exercises what someone screenshotting
 * a stock M3 component actually faces: the only handle on the scroll is the semantics the component
 * publishes for itself.
 *
 * `yearRange` is clamped to two years so the content is comfortably longer than any viewport it is
 * captured in without being absurd, and the displayed month is pinned to 2024-01 (UTC) so the
 * capture doesn't depend on the clock.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerLongScrollPreview() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Surface(color = MaterialTheme.colorScheme.surface) {
      androidx.compose.material3.DateRangePicker(
        state =
          androidx.compose.material3.rememberDateRangePickerState(
            initialSelectedStartDateMillis = 1_704_067_200_000L,
            initialDisplayedMonthMillis = 1_704_067_200_000L,
            yearRange = 2024..2025,
          ),
        title = null,
        headline = null,
        showModeToggle = false,
      )
    }
  }
}

/**
 * [DarkAwareSquare]'s scrollable sibling: rows that read `isSystemInDarkTheme()`, white under light
 * and black under dark, with enough of them to make the daemon's LONG stitcher take several
 * viewports.
 *
 * Exists because the daemon's scroll dispatch is a SECOND call into `renderScrollPreview`, separate
 * from the standalone renderer's. A dark-aware fixture that does not scroll cannot reach it, and
 * every scrolling fixture here pins its own `lightColorScheme()` — so nothing in the suite could
 * observe a scroll data product being rendered in the wrong theme.
 */
@Composable
fun DarkAwareLongScrollPreview() {
  val bg = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White
  androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().background(bg)) {
    items(60) { Box(modifier = Modifier.fillMaxWidth().height(60.dp).background(bg)) }
  }
}
