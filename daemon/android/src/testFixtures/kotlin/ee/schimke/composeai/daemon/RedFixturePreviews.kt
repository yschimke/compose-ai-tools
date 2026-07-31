package ee.schimke.composeai.daemon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup

/**
 * Test fixtures for [RenderEngineTest] and the D-harness.v2 Android real-mode scenarios. Lives in
 * the `testFixtures` source set so `:daemon:harness`'s test runtime classpath can pull these
 * composables via `testImplementation(testFixtures(project(":daemon:android")))` — same shape
 * `:daemon:desktop`'s testFixtures already use. Promoted here from `src/test/...` in D-harness.v2
 * (was previously test-source-only because B1.4 only needed same-module verification).
 *
 * Each preview is a single solid-colour fill, identical in coordinates and hue to the desktop
 * counterpart in [`daemon/desktop`'s `RedFixturePreviews`][ ee.schimke.composeai.daemon.RedSquare]
 * (see also `daemon/harness/baselines/desktop/`). Class FQN —
 * `ee.schimke.composeai.daemon.RedFixturePreviewsKt` — and function names match across both
 * backends so a single `RealModePreview(className=…, functionName="RedSquare")` row in the
 * harness's `realModeScenario(...)` manifest resolves to the right composable on either target. The
 * PNG bytes will differ per target (Skiko AA vs Robolectric/HardwareRenderer) — that's what the
 * per-target baseline directories under `daemon/harness/baselines/<target>/` absorb.
 */
@Composable
fun RedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

/** Two-owner fixture: the activity surface plus a popup-owned semantics root. */
@Composable
fun MultipleSemanticsRoots() {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(Color(0xFFEF5350))
        .semantics { contentDescription = "activity-surface" }
  )
  Popup {
    Box(
      modifier =
        Modifier.size(24.dp)
          .background(Color(0xFF42A5F5))
          .semantics { contentDescription = "popup-surface" }
    )
  }
}

/**
 * Dialog-window fixture (issue #3048): the preview's *entire* content composes into a `Dialog`, so
 * the activity's Compose root stays empty and the dialog owns its own window, `ViewRootImpl`, and
 * root. Unlike [MultipleSemanticsRoots] — where the extra root is incidental decoration over a real
 * activity surface — here the extra root *is* the subject, so both the capture and every structured
 * export have to read it or the preview renders blank.
 */
@Composable
fun DialogWindowSurface() {
  Dialog(onDismissRequest = {}) {
    Box(
      modifier =
        Modifier.size(64.dp)
          .background(Color(0xFF42A5F5))
          .semantics { contentDescription = "dialog-surface" }
    )
  }
}

/**
 * Wrap-height regression fixture: a `Column` of many text rows whose natural height (~20 rows ×
 * ~40 px ≈ 800 px) far exceeds the historical fixed 320 px daemon frame. Rendered wrap-content, a
 * `Column` hands each child the *remaining* height, so under the old 320 px frame every row past the
 * budget measured to zero lines — the exact mechanism that collapsed `TcpConnectPanel`'s Port field
 * / Connect button in the figma-svg export. With the AS-parity wrap fix the render measures the full
 * ~800 px against the sandbox bound and crops to it, so no row collapses. Declared with `widthDp`
 * only (like the real component previews) so the height wraps.
 */
@Composable
fun TallWrapColumn() {
  Column(modifier = Modifier.fillMaxWidth().background(Color.White)) {
    repeat(20) { i ->
      Text(
        "Row $i — the quick brown fox",
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        color = Color(0xFFB71C1C),
      )
    }
  }
}

/**
 * Wrap-content sticker fixture mirroring the desktop `WrapContentStickerPreview` (and the
 * design-catalog `CatalogSticker`): a 56.dp badge in 16.dp padding with **no `fillMaxSize`**, so its
 * intrinsic size is 88.dp on both axes — 176 px at density 2, far smaller than the wrap sandbox.
 * Used by [RenderEngineSizeBoundsTest] to prove the Android backend honours the wrapped-axis size
 * bounds (`PreviewOverrides.{min,max}{Width,Height}Px` — the Max / Min / Within size modes) the same
 * way the desktop daemon does, so a `compose-preview serve` size override against an Android module
 * reshapes the crop instead of being silently ignored.
 */
@Composable
fun WrapContentStickerPreview() {
  Box(modifier = Modifier.padding(16.dp)) {
    Box(
      modifier = Modifier.size(56.dp).background(Color(0xFFB71C1C), RoundedCornerShape(28.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text(text = "8", color = Color.White)
    }
  }
}

/**
 * Fixture for `PreviewOverridesDataFetchE2ETest`: declares two opt-in `previewOverride*` knobs (a
 * colour `fill` and a string `label`) so a render through the sandbox records them into the
 * sandbox-classloader `PreviewOverrideController`. The test then asserts the host-side
 * `compose/overrides` data product surfaces them via `data/fetch` — i.e. that
 * `SandboxPreviewOverridesBridge` carried the declarations across the classloader boundary. The
 * `fill` knob drives the rendered colour, so a seeded override also visibly changes the pixels.
 */
@Composable
fun OverridableSquare() {
  val fill =
    ee.schimke.composeai.overrides.previewOverrideColor("fill", default = Color(0xFFEF5350))
  ee.schimke.composeai.overrides.previewOverrideString("label", default = "hi")
  Box(modifier = Modifier.fillMaxSize().background(fill))
}

@Composable
fun BlueSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF42A5F5)))
}

/**
 * Identical fill to [RedSquare] but declared `private`, so it compiles to a JVM-private static
 * method on `RedFixturePreviewsKt`. Kotlin `private fun` previews are a real, supported shape
 * (`samples/android/.../Previews.kt`'s `RedBoxPreview` ships one on purpose). The daemon's
 * [RenderEngine] resolves it via `getDeclaredComposableMethod` — which scans `declaredMethods` and
 * finds private members — but the reflective `invoke` throws `IllegalAccessException` unless the
 * method is opened with `setAccessible(true)` first. Used by
 * [RenderEngineTest.privateComposableRendersToValidPng] to lock that in.
 */
@Suppress("unused") // invoked reflectively by the daemon's RenderEngine, not from Kotlin
@Composable
private fun PrivateRedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

/**
 * Hybrid `compose/figma-svg` fixture (matches the desktop counterpart): a red screen with one
 * **opaque** `Image` (a 32×32 green fill) in the top-left. The layout-inspector names the node so
 * the exporter classifies it as opaque, emits it as an `<image>` layer, and crops the green region
 * out of the captured frame into `figma-raster/`. Used by
 * [RenderEngineTest.figmaSvgExportRastersOpaqueImage].
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

/** Brush-background hybrid SVG fixture: gradient pixels plus editable-looking child content. */
@Composable
fun GradientBackgroundCard() {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.horizontalGradient(listOf(Color(0xFFFF0000), Color(0xFF0000FF))),
          RoundedCornerShape(8.dp),
        ),
    contentAlignment = Alignment.Center,
  ) {
    Text("Gradient", color = Color.White)
  }
}

/** Android end-to-end fixture for #2854's emoji and annotated-font export paths. */
@Composable
fun EmojiAndAnnotatedText() {
  Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
    Text("😀", modifier = Modifier.testTag("emoji").clickable {})
    Text(
      buildAnnotatedString {
        append("body ")
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
          append("code")
        }
      },
      modifier = Modifier.testTag("annotated"),
      style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
    )
  }
}

/**
 * Android end-to-end fixture for #2853: a transparent graphics-layer container beside a square
 * vector painter measured into a deliberately non-square slot.
 */
@Composable
fun GraphicsLayerAndWideVector() {
  val diamond =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(fill = SolidColor(Color.White)) {
          moveTo(12f, 0f)
          lineTo(24f, 12f)
          lineTo(12f, 24f)
          lineTo(0f, 12f)
          close()
        }
      }
      .build()

  Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Box(
      Modifier.testTag("hidden-circle")
        .size(40.dp)
        .graphicsLayer { alpha = 0f }
        .background(Color.Blue, RoundedCornerShape(20.dp))
    )
    Icon(
      imageVector = diamond,
      contentDescription = "diamond",
      modifier = Modifier.testTag("wide-vector").size(width = 48.dp, height = 16.dp),
    )
  }
}

/**
 * Android end-to-end fidelity fixture for #2853, the *embedded container* case: Jetchat's
 * `Conversation/Input` row of `InputSelectorButton`s, whose icons the export blew up to their
 * button box. Each button is an M3 `IconButton` holding
 * `Icon(modifier = Modifier.padding(8.dp).size(56.dp))` — the exact chain Jetchat authors — so the
 * painter draws into the *padded, constraint-clamped* box while the node still measures the button.
 * Fitting the vector to the measured box instead of the drawn one is what oversized the five action
 * icons from `scale(0.06)` to `scale(0.1)`.
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
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
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
    }
  }
}

/**
 * Android end-to-end fidelity fixture for #2853, the alpha-zero clipped background: Jetchat's
 * `Composer/Record button` authors its recording circle with
 * `graphicsLayer { alpha = containerAlpha.value; scaleX = scale; scaleY = scale }`, and idle it
 * evaluates to `alpha = 0`, so the PNG shows only the microphone. The circle here is faded exactly
 * that way — through the lambda block, over a visible vector mic — so the export must carry the
 * evaluated layer alpha onto the group and drop the opaque circle, rather than leaking it as an
 * opaque fill. Embedding it in an input-bar `Row` matches the container the regression appeared in.
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
      // renders without a downloadable font under Robolectric).
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
 * Android end-to-end fidelity fixture for #2853, a vector icon inside a custom animated layout:
 * Jetchat's `Profile/Animating FAB content` scales its create icon through a graphics layer as the
 * FAB expands. The captured draw-time scale is already baked into the icon's drawn bounds, so the
 * export must fit the vector to those bounds *once* (keeping the square icon square) rather than
 * multiplying a layout-slot fit by the captured scale again — the double-count that blew an embedded
 * mic group up from `scale(2.62)` to `scale(6.54)`.
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

@Composable
fun ThemedPrimarySquare() {
  MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF123456))) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
  }
}

@Composable
fun SerifTextPreview() {
  BasicText("Serif text", style = TextStyle(fontFamily = FontFamily.Serif))
}

/**
 * Third solid-colour fixture used by D-harness.v2's S4 Android real-mode test (visibility filter).
 * Same shape as [RedSquare] / [BlueSquare]; distinct hue so a wire-level mix-up between the three
 * preview ids surfaces as a pixel-diff failure against the per-id baseline PNG.
 */
@Composable
fun GreenSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A)))
}

/**
 * Fixture for D-harness.v2's S2 Android real-mode test (drain semantics). Sleeps for ~500ms inside
 * the composition body so the harness can race a `shutdown` request against an in-flight render and
 * assert the render still completes (per
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)).
 *
 * The sleep is deliberately *inside* the composition rather than around the capture — we want to
 * exercise the very window that's most dangerous to cancel: a partly-built Compose graph is the
 * worst leak shape per
 * [PREDICTIVE.md § 9](../../../../../../docs/daemon/PREDICTIVE.md#9-decisions-made). Mirrors the
 * desktop counterpart's contract exactly.
 */
@Composable
fun SlowSquare() {
  Thread.sleep(500)
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF80FFAA.toInt())))
}

/**
 * Fixture for D-harness.v2's S5 Android real-mode test (renderFailed surfacing). Throws
 * unconditionally inside the composition body so [RenderEngine] propagates the exception out of
 * `setContent`, the dispatcher catch in [RobolectricHost.SandboxRunner] returns a stub fallback
 * rather than a real `RenderResult`, and `JsonRpcServer.runHostSubmitter` surfaces the failure as a
 * `renderFailed` notification (… or, today, falls through to `renderFinished` with the stub path —
 * see S5 Android test KDoc for the documented gap).
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
 * Reads `isSystemInDarkTheme()` and fills the box with white in light mode, black in dark mode.
 * Used by `OverrideIntegrationTest` to prove `renderNow.overrides.uiMode` actually flips the
 * resource qualifier — `setQualifiers("+night")` toggles `Configuration.UI_MODE_NIGHT_YES`, which
 * is what `isSystemInDarkTheme()` reads.
 */
@Composable
fun DarkAwareSquare() {
  val bg = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White
  Box(modifier = Modifier.fillMaxSize().background(bg))
}

/**
 * Reads `ContextCompat.checkSelfPermission(...)` for `android.permission.CAMERA` — the exact call
 * shape `samples/android`'s `PermissionGatedPreview` uses and the path the panel's permission UI
 * targets. Paints green when granted, red when denied. Used by `PermissionsOverrideIntegrationTest`
 * to prove `renderNow.overrides.permissions` reaches
 * `PermissionsPreviewOverrideExtension.plan(...)`, the around-composable seeds Robolectric's
 * `ShadowApplication.grantPermissions`, and by `PermissionsDataFetchE2ETest` to prove the
 * `ShadowContextWrapperPermissionTracker` shadow intercepts the call and records the query into the
 * cross-classloader bridge the registry reads.
 *
 * **Why `ContextCompat.checkSelfPermission` and not `context.checkSelfPermission`.** They look
 * interchangeable but route differently inside the Android framework: `Context.checkSelfPermission
 * (String)` is implemented in `ContextImpl` as a direct `PermissionManager.checkPermission(...)`
 * call that bypasses `ContextWrapper.checkPermission(String, int, int)`, where the shadow lives.
 * `ContextCompat.checkSelfPermission(context, perm)` instead calls `context.checkPermission(perm,
 * Process.myPid(), Process.myUid())` — which on any `ContextWrapper`-rooted context (every
 * Activity) dispatches into `ContextWrapper.checkPermission`, the path
 * `ShadowContextWrapperPermissionTracker` intercepts. Pixel correctness held either way because
 * `ShadowApplication`'s grant state is consulted by both code paths; the query-tracking surface
 * only sees the `ContextCompat` path.
 */
@Composable
fun PermissionGatedSquare() {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Inlines what `androidx.core.content.ContextCompat.checkSelfPermission(context, perm)` does:
  // a virtual `context.checkPermission(perm, Process.myPid(), Process.myUid())` dispatch. On any
  // `ContextWrapper`-rooted context (every Activity) that lands in `ContextWrapper.checkPermission
  // (String, int, int)` — the method `ShadowContextWrapperPermissionTracker` shadows. Using the
  // raw call avoids adding `androidx.core:core` to the test-fixtures source set just to import
  // the static helper; the production sample (`samples/android/.../PermissionGatedPreview.kt`)
  // imports `ContextCompat` because it's already on the consumer's classpath.
  val granted =
    context.checkPermission(
      android.Manifest.permission.CAMERA,
      android.os.Process.myPid(),
      android.os.Process.myUid(),
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
  val color = if (granted) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color))
}

@Composable
fun ResourceReadingPreview() {
  val label = stringResource(R.string.compose_ai_resource_used_label)
  val color = colorResource(R.color.compose_ai_resource_used_color)
  val size = dimensionResource(R.dimen.compose_ai_resource_used_size)
  Box(
    modifier =
      Modifier.width(size)
        .height(size)
        .background(if (label.isNotBlank()) color else Color.Transparent)
  )
}

/**
 * Reads a **missing** app-package string id (`0x7f0f9999`, the same id
 * [PlaceholderFallbackResourcesTest] probes) so the `stringResource` lookup is absent from the
 * module's resource table — the exact failure shape the `wear-m3` `CheckboxButtonChecked` sticker
 * hits on the live preview server when the packed / child-loader resource table doesn't carry its
 * `label_sync` string.
 *
 * Under the missing-resource placeholder fallback (`RenderEngine.PLACEHOLDER_MISSING_RESOURCES_PROP`)
 * the miss degrades to an obvious placeholder (a non-blank string), so this paints green. Without the
 * fallback the lookup throws `Resources$NotFoundException`; on the interactive held path that throw
 * fails `acquireInteractiveSession` and the panel shows "input requires a live stream — unavailable".
 * Passing a raw int rather than an `R.string.*` constant keeps the miss guaranteed — the id is never
 * added to the resource table.
 */
@Composable
fun MissingStringResourceSquare() {
  val label = stringResource(0x7f0f9999)
  val color = if (label.isNotBlank()) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color))
}

/**
 * Stateful fixture for the v3 Android-interactive test ([AndroidInteractiveSessionTest]). Paints
 * red on first composition; flips to green when any pointer-down event lands. Same shape as the
 * desktop `ClickToggleSquare` fixture in `daemon/desktop`'s testFixtures so the two backends'
 * integration tests assert against an identical state-mutation contract ("first capture red;
 * dispatch click; second capture green — `remember{}` state survived across captures").
 *
 * Uses `awaitFirstDown` rather than `Modifier.clickable` because `clickable` sits on top of
 * `detectTapGestures`, whose coroutine timing under Compose's paused clock is non-trivial. The
 * `RobolectricInteractiveProbeTest` empirical probe verified `awaitFirstDown` fires reliably for a
 * synthesised `MotionEvent` dispatched through `decorView.dispatchTouchEvent` under the held rule —
 * the simplest pointerInput shape gives the cleanest yes/no answer for the wire-level test and
 * matches what the desktop counterpart already asserts on.
 */
@Composable
fun ClickToggleSquare() {
  var clicked by
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
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
 * Issue #1784 fixture — proves an interaction can target a node by `testTag` (resolved sandbox-side
 * to its centre) instead of pixel coordinates. The whole card starts red; only a click inside the
 * small 24×24 `testTag("target-box")` clickable node pinned to the **top-left corner** flips it
 * green. The node is deliberately off-centre so a naive centre click misses it — a green result is
 * only reachable by resolving the testTag to the node's real centroid.
 */
@Composable
fun TaggedClickTargetSquare() {
  var clicked by
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color)) {
    Box(modifier = Modifier.size(24.dp).testTag("target-box").clickable { clicked = true })
  }
}

@Composable
fun ClickableToggleSquare() {
  var clicked by
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().clickable { clicked = true }.background(color))
}

@Composable
fun DragScrollableSquare() {
  val scrollState = rememberScrollState()
  Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
    Box(modifier = Modifier.width(96.dp).height(96.dp).background(Color(0xFFEF5350)))
    Box(modifier = Modifier.width(96.dp).height(96.dp).background(Color(0xFF66BB6A)))
  }
}

@Composable
fun ReleasePositionSquare() {
  var releasedNearTop by
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val color = if (releasedNearTop) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(
    modifier =
      Modifier.fillMaxSize().background(color).pointerInput(Unit) {
        awaitPointerEventScope {
          awaitFirstDown()
          while (true) {
            val change = awaitPointerEvent().changes.first()
            if (change.changedToUp()) {
              releasedNearTop = change.position.y < 24f
              break
            }
          }
        }
      }
  )
}

@Composable
fun RotaryToggleSquare() {
  var scrolled by
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val requester = androidx.compose.runtime.remember { FocusRequester() }
  LaunchedEffect(Unit) { requester.requestFocus() }
  val color = if (scrolled) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(
    modifier =
      Modifier.fillMaxSize()
        .onRotaryScrollEvent {
          scrolled = true
          true
        }
        .focusRequester(requester)
        .focusable()
        .background(color)
  )
}

/**
 * Non-composable `@NotificationPreview`-shaped fixture: a top-level `fun(Context): Notification`
 * exactly like the ones the gradle plugin discovers and tags `kind = "NOTIFICATION"`. Used by
 * [AndroidInteractiveSessionTest.nonComposableNotificationKindRendersInHeldSessionInsteadOfErroring]
 * to lock in the held-interactive-session fix: previously the held-rule loop called
 * `getDeclaredComposableMethod` unconditionally, which throws `NoSuchMethodException` on a function
 * that returns `android.app.Notification` (no synthesised `(Composer, Int)` method) and blanked the
 * preview the moment live mode was enabled. Notification stands in for the whole non-composable
 * family (tiles / notifications / Glance) because it needs no extra build dependencies — wear-tiles
 * fixtures would pull `protolayout` + a merged-resource AAR into this module's test classpath.
 */
fun RedNotification(context: android.content.Context): android.app.Notification {
  val channelId = "red-fixture-channel"
  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    manager?.createNotificationChannel(
      android.app.NotificationChannel(
        channelId,
        "Red fixture",
        android.app.NotificationManager.IMPORTANCE_DEFAULT,
      )
    )
    return android.app.Notification.Builder(context, channelId)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Held notification")
      .setContentText("rendered inside a live session")
      .build()
  }
  @Suppress("DEPRECATION")
  return android.app.Notification.Builder(context)
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle("Held notification")
    .setContentText("rendered inside a live session")
    .build()
}

/**
 * Realistic mobile scrolling-screen fixture for the `figma-svg-long` Android test — a Material 3
 * [Scaffold] with a pinned [TopAppBar] and a hand-rolled bottom navigation bar framing a
 * `LazyColumn` of 30 numbered rows (more than fit a phone-height viewport). Mirrors the desktop
 * `LazyColumnListPreview` so both backends' full-page exports are exercised the same way:
 * [RenderEngineTest] renders it in `figma-svg-long` mode and asserts the exported SVG carries all 30
 * `Row N` layers. Bottom bar hand-built from primitives to stay independent of the M3
 * `NavigationBar` artifact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazyColumnListPreview() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Scaffold(
      topBar = { TopAppBar(title = { Text("Activity") }) },
      bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
          ) {
            listOf("Home", "Search", "Profile").forEach { label ->
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

/**
 * `@PreviewParameter` regression fixture (issue #3027).
 *
 * The daemon used to resolve every preview with the parameterless
 * `getDeclaredComposableMethod(functionName)` lookup, which matches only `foo(Composer, int)`. This
 * preview compiles to `ThemedTintedSquare(long, Composer, int)`, so resolution threw
 * `NoSuchMethodException` before composition started — no PNG, no semantics, just an `.error.json`.
 * That took out 27 previews in one real consumer module, including the two `Summary*` previews the
 * issue was filed against.
 *
 * Two values on purpose: the daemon renders one frame per preview id, so it must invoke the
 * **first** one ([SquareTintProvider] green), never the second. A regression that silently picks the
 * wrong value fails the pixel assertion rather than passing on "something rendered".
 */
class SquareTintProvider : PreviewParameterProvider<Long> {
  override val values = sequenceOf(0xFF43A047L, 0xFF1E88E5L)
}

@Preview
@Composable
fun ThemedTintedSquare(@PreviewParameter(SquareTintProvider::class) tint: Long) {
  Box(modifier = Modifier.fillMaxSize().background(Color(tint)))
}
