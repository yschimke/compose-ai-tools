package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import ee.schimke.composeai.daemon.FocusController
import ee.schimke.composeai.daemon.FocusOverlayDesktop
import ee.schimke.composeai.daemon.FocusOverrideExtension
import ee.schimke.composeai.daemon.protocol.FocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.scroll.ScrollAxis
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * One `@FocusedPreview` capture's worth of intent, as the desktop renderer receives it over the
 * flat positional-arg protocol. The wire-shape [FocusOverride] the connector's around-composable
 * consumes is derived from it in [toOverride] — this type exists so [DesktopRendererMain]'s arg
 * parsing has something to hold before the connector types are involved.
 */
data class DesktopFocusIntent(
  /** Zero-based tab-order index (indexed mode). `null` in traversal mode. */
  val tabIndex: Int? = null,
  /**
   * Traversal mode: every direction from step 1 up to and including the one this capture documents,
   * in order. Empty in indexed mode.
   *
   * The whole prefix, not just this capture's own direction, because the desktop renderer composes
   * a **fresh scene per capture** — one process, one PNG — while the Android renderer keeps a
   * single composition alive across a preview's captures and flips the controller per step. Step 3
   * of `traverse = [Next, Next, Previous]` therefore has to replay `Next, Next, Previous` from the
   * start here to sit where the Android capture sits; replaying only `Previous` would make every
   * step of a traversal render the same first-focusable frame.
   */
  val directions: List<FocusDirection> = emptyList(),
  /** 1-based step index of the documented (last) direction — used for the overlay label. */
  val step: Int? = null,
  /** Skip the `+1 Next` compensation — see `@FocusedPreview.enterPlacesFocus`. */
  val enterPlacesFocus: Boolean = false,
  /** Dispatch a real pointer press onto the focused element before capturing. */
  val pressed: Boolean = false,
  /** Draw the post-capture stroke + label over the focused element's bounds. */
  val overlay: Boolean = false,
) {
  /**
   * The wire-shape override for one step of the walk. [index] selects which of [directions] to
   * apply — the connector's around-composable consumes exactly one direction per controller flip,
   * so a traversal capture flips it once per replayed step. Indexed mode ignores [index].
   */
  fun toOverride(index: Int = directions.lastIndex): FocusOverride =
    FocusOverride(
      tabIndex = if (directions.isEmpty()) tabIndex else null,
      direction = directions.getOrNull(index),
      step = if (directions.isEmpty()) step else index + 1,
      overlay = overlay,
      enterPlacesFocus = enterPlacesFocus,
      pressed = pressed,
    )

  /** The override describing what this capture documents — the last step of the walk. */
  fun documentedOverride(): FocusOverride =
    if (directions.isEmpty()) toOverride() else toOverride().copy(step = step ?: directions.size)
}

/**
 * Renders one `@FocusedPreview` capture on Compose Multiplatform Desktop: focus is walked with a
 * real `FocusManager.moveFocus(...)` traversal and, for `pressed = true`, a real pointer press is
 * dispatched onto the focused element through the scene's hit-testing input path.
 *
 * ## Why this exists (issue #3672)
 *
 * Until now `@FocusedPreview` was Android-only: discovery emitted the per-capture focus state on
 * every target, but the desktop lane ignored it, so a CMP preview asking for focus rendered its
 * resting frame. Samples that wanted a focused / pressed sticker had no option but to emit
 * `FocusInteraction.Focus` / `PressInteraction.Press` onto a `MutableInteractionSource` from a
 * `LaunchedEffect` — a forged visual: nothing is really focused, nothing is really pressed, the
 * emission is never paired with `Unfocus` / `Release`, and a component whose indication reads the
 * focus system rather than the interaction source captures no differently from an untouched one.
 * This path replaces that with input the component actually receives.
 *
 * ## Why `runSkikoComposeUiTest` rather than `ImageComposeScene`
 *
 * Same reason [renderScrollPreview] uses it: `ImageComposeScene` is a bare rendering surface with
 * no `mainClock` and no way to inject input. The focus walk needs a paused clock (Material's focus
 * / press state layers crossfade over ~150 ms and the capture has to land *after* the fade), and
 * `pressed` needs a pointer-injection host. `runSkikoComposeUiTest` gives the renderer what
 * `AndroidComposeTestRule` gives the Robolectric path.
 *
 * ## How pressing differs from the Android path, on purpose
 *
 * The Android connector dispatches an **indirect** pointer event (`sendIndirectPointerEvent`),
 * because XR Glasses have no touchscreen and route touchpad input to whatever is *focused*. Desktop
 * has no indirect-pointer channel at all, so the press here is an ordinary pointer down aimed at
 * the focused element's bounds — which is strictly more evidence, not less: the event is hit-tested
 * to the component like a real click, so a capture can only show the pressed state if the real
 * component (not merely its state layer) received the press.
 *
 * As on Android the press is deliberately **not** released before the capture: a Press+Release pair
 * is a tap, which would fire `onClick` and leave the button resting again. The held press is the
 * "finger still down" shape the sticker documents. The scene is disposed at the end of the capture,
 * so nothing outlives it.
 *
 * Returns `true` when [outputFile] was written, `false` when the walk found nothing focusable — the
 * caller falls back to the ordinary single-frame render so a misuse still produces a capture.
 */
// `InternalComposeUiApi` for `LocalSystemTheme` — the same opt-in [renderPreview] carries, and how
// Compose Desktop's `isSystemInDarkTheme()` is driven.
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
fun renderFocusPreview(
  className: String,
  functionName: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
  wrapperClassName: String?,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  previewArgs: List<Any?>,
  localeTag: String?,
  focus: DesktopFocusIntent,
  /**
   * `@ScrollingPreview(END)` on the same capture: drive the scrollable to its content end *before*
   * walking focus, so a capture carrying both intents documents both. Without this the focus branch
   * would silently win and publish focus-at-the-top under a `_SCROLL_end` filename.
   */
  scrollToEnd: Boolean = false,
  scrollAxis: ScrollAxis = ScrollAxis.VERTICAL,
  scrollMaxScrollPx: Int = 0,
  fontScale: Float = 1.0f,
  showSystemUi: Boolean = false,
  uiMode: Int = 0,
  device: String? = null,
  minWidthPx: Int? = null,
  minHeightPx: Int? = null,
  maxWidthPx: Int? = null,
  maxHeightPx: Int? = null,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val clazz = Class.forName(className)
  // Reflection + wrapper resolution are [renderPreview]'s, not near-copies: an overload lookup
  // without its `argsMatch` filter can pick a same-arity-but-wrong-types sibling, and a wrapper
  // loaded with a bare `Class.forName` skips the `PreviewWrapperSubstitutionProvider` swap (the
  // Remote Compose wrapper is the live case). A focused capture has to be the ordinary capture
  // plus a state — that has to hold for what gets composed, not just for how it is framed.
  // `openForInvoke` keeps `private fun` previews renderable on the focus path too — issue #3873.
  val composableMethod =
    (if (previewArgs.isEmpty()) clazz.getDeclaredComposableMethod(functionName)
      else findComposableMethodWithArgs(clazz, functionName, previewArgs))
      .openForInvoke()

  // Arm the named-override capture exactly as [renderPreview] does, so a focused capture ships the
  // same `renders/<stem>.overrides.json` sidecar an ordinary one would.
  ee.schimke.composeai.overrides.PreviewOverrideController.clearDeclarations()

  // The controller is process-static and the renderer worker is pooled — a capture must never
  // inherit the focus target of whatever this JVM drew before it.
  FocusController.resetForNewSession()

  val previousDefaultLocale = overrideJvmDefaultLocale(localeTag)
  val rtl = rendersRightToLeft(localeTag)
  val sceneDensity = Density(density, fontScale)
  val sizeBounds =
    PreviewSizeBounds(
      minWidthPx = minWidthPx,
      minHeightPx = minHeightPx,
      maxWidthPx = maxWidthPx,
      maxHeightPx = maxHeightPx,
    )
  val sceneSize = composePreviewSceneSize(widthPx, heightPx, wrapWidth, wrapHeight, sizeBounds)
  var measured: IntSize? = null
  var focusedBounds: Rectangle? = null
  var landed = false

  try {
    runSkikoComposeUiTest(
      size = Size(sceneSize.width.toFloat(), sceneSize.height.toFloat()),
      density = sceneDensity,
    ) {
      // Paused clock, same contract as the scroll path: the settle windows below are the only
      // thing that advances time, so a preview with an infinite animation can't hang the capture
      // and the focus / press crossfades land deterministically.
      mainClock.autoAdvance = false

      val systemTheme = systemThemeFromUiMode(uiMode)
      setContent {
        val baseProviders: @Composable (@Composable () -> Unit) -> Unit = { inner ->
          if (rtl) {
            CompositionLocalProvider(
              LocalInspectionMode provides true,
              LocalDensity provides sceneDensity,
              androidx.compose.ui.LocalSystemTheme provides systemTheme,
              androidx.compose.ui.platform.LocalLayoutDirection provides
                androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) {
              inner()
            }
          } else {
            CompositionLocalProvider(
              LocalInspectionMode provides true,
              LocalDensity provides sceneDensity,
              androidx.compose.ui.LocalSystemTheme provides systemTheme,
            ) {
              inner()
            }
          }
        }
        baseProviders {
          val bgColor =
            Color(
              ee.schimke.composeai.data.render.PreviewBackground.resolveArgbForUiMode(
                showBackground = showBackground,
                backgroundColor = backgroundColor,
                uiMode = uiMode,
              )
            )
          val body: @Composable () -> Unit = {
            // The same AS-parity wrap-measure box [renderPreview] uses, so a focused capture is
            // framed and cropped identically to the resting one — a reviewer diffing the two sees
            // the state change, not a reflow.
            ComposePreviewContentBox(
              wrapWidth = wrapWidth,
              wrapHeight = wrapHeight,
              backgroundColor = bgColor,
              sizeBounds = sizeBounds,
              onMeasured = { w, h -> measured = IntSize(w, h) },
            ) {
              InvokeFocusComposable(composableMethod, null, previewArgs)
            }
          }
          val wrapped: @Composable () -> Unit = {
            if (wrapperClassName != null) {
              InvokeFocusWrappedComposable(wrapperClassName, body)
            } else {
              body()
            }
          }
          // The focus concerns — `LocalInputModeManager provides KeyboardInputModeManager` plus the
          // `LaunchedEffect`-driven `moveFocus` walk — live in `:data-focus-connector-desktop`, the
          // same seam the daemon's `renderNow.overrides.focus` path uses. Nothing about the walk is
          // reimplemented here; the renderer only decides *when* to flip the controller.
          FocusOverrideExtension().AroundComposable {
            if (shouldApplySystemBars(showSystemUi, device, kind = null)) {
              SystemBarsFrame(uiMode = uiMode) { wrapped() }
            } else {
              wrapped()
            }
          }
        }
      }

      // Two frames so first composition + layout settle before the walk starts, mirroring the two
      // `scene.render()` calls on the single-frame path.
      mainClock.advanceTimeByFrame()
      mainClock.advanceTimeByFrame()

      // `@ScrollingPreview(END)` + `@FocusedPreview` on one capture: land the scroll first, then
      // walk focus in the same scene. Order matters — focus first would be undone by the scroll
      // moving the focused element out of the viewport (and, for a lazy container, out of
      // composition entirely). Shares [captureEnd]'s drive verbatim so an END capture is the same
      // end whether or not focus rides along. A declined drive is only worth a line of stderr: the
      // focus capture that follows is still valid, it just has nothing to scroll.
      if (scrollToEnd) {
        val scrolled =
          driveScrollToEnd(
            provider = this,
            mainClock = mainClock,
            axis = scrollAxis,
            viewportPx = if (scrollAxis == ScrollAxis.HORIZONTAL) widthPx else heightPx,
            maxScrollPx = scrollMaxScrollPx,
          )
        if (scrolled == null) {
          System.err.println(
            "@ScrollingPreview(END) + @FocusedPreview on $className.$functionName: no scrollable " +
              "on axis ${scrollAxis.name} — capturing focus at the initial viewport."
          )
        }
      }

      // Flip the controller; the connector's `LaunchedEffect` observes the snapshot state and walks
      // focus from inside composition. Driving it from in here (rather than calling `moveFocus`
      // ourselves) is what makes the FocusableNode's paired Focus / Unfocus emission and the
      // indication redraw land before the capture — see [FocusController.activeFocus].
      //
      // Indexed mode is one flip (the index is absolute). Traversal mode flips once per replayed
      // step, each settling before the next, so the walk accumulates exactly as it does on the
      // Android renderer's long-lived composition — see [DesktopFocusIntent.directions].
      val steps = if (focus.directions.isEmpty()) 1 else focus.directions.size
      repeat(steps) { index ->
        FocusController.set(focus.toOverride(index))
        // `waitForIdle()` before the clock advance, not after: the walk lives in a
        // `LaunchedEffect`, and whether its coroutine has been *dispatched* by the time we advance
        // is not something a fixed window can guarantee. Skipping this made the capture a race the
        // CI render lost while the local one won — the walk landed after the settle, so the node
        // was focused (the `landed` probe below said so) but its indication had never been given a
        // frame to fade in, and the published sticker looked exactly like the untouched button.
        waitForIdle()
        mainClock.advanceTimeBy(FocusController.SETTLE_MS)
      }

      // Focus is on a node before the indication is drawn, so probing for it is a precondition,
      // not the settle. Wait for the walk to land, then give the state layer its own full window —
      // Material's focus indicator crossfades over ~150 ms and a capture taken mid-fade documents
      // a weaker state than the component actually has.
      landed = awaitFocusLanded(this, mainClock)
      if (landed) {
        mainClock.advanceTimeBy(FocusController.SETTLE_MS)
        if (focus.pressed) {
          // A real pointer down on the focused element. `performTouchInput { down(center) }` goes
          // through the scene's ordinary hit-testing dispatch, so `Modifier.clickable` raises
          // `PressInteraction.Press` on its own interaction source — the component's wiring, not
          // ours. Touch rather than mouse deliberately: a mouse press would also raise
          // `HoverInteraction.Enter`, and a sticker labelled "pressed" should not be documenting
          // hover as well.
          onAllNodes(isFocused()).onFirst().performTouchInput { down(center) }
          // Press indication is animated (Material's state layer crossfade plus the ripple's own
          // growth), and `clickable` additionally delays the press emission when the composable
          // sits in a scrollable ancestor. Two settle windows cover both.
          mainClock.advanceTimeBy(FocusController.SETTLE_MS)
          mainClock.advanceTimeBy(FocusController.SETTLE_MS)
        }
        focusedBounds = focusedNodeBounds(this)
        captureFocusFrame(
          provider = this,
          outputFile = outputFile,
          sceneSize = sceneSize,
          measured = measured,
          wrapWidth = wrapWidth,
          wrapHeight = wrapHeight,
          widthPx = widthPx,
          heightPx = heightPx,
          device = device,
          fileSystem = fileSystem,
        )
      }
      // Leave the controller clean for the next capture drawn by this (possibly pooled) JVM.
      FocusController.set(null)
    }
  } finally {
    restoreJvmDefaultLocale(previousDefaultLocale)
  }

  if (!landed) {
    System.err.println(
      "@FocusedPreview on $className.$functionName: nothing took focus " +
        "(${focus.describe()}) — falling back to the undriven capture."
    )
    return false
  }

  // `overlay = true`: stroke + label over the focused element's bounds, with the pre-overlay
  // capture preserved as `<basename>.raw.png`. Same review aid (and same drawing) as the Android
  // path's `FocusOverlay`; the bounds come from the semantics tree here rather than from
  // `AndroidComposeView.focusOwner`, because desktop has no `View` to reflect into.
  if (focus.overlay) {
    focusedBounds?.let {
      FocusOverlayDesktop.apply(it, outputFile, focus.documentedOverride(), fileSystem)
    }
  }

  writePreviewOverridesSidecar(outputFile, fileSystem)
  return true
}

private fun DesktopFocusIntent.describe(): String =
  when {
    directions.isNotEmpty() -> "step ${step ?: directions.size} ${directions.last().name}"
    else -> "index ${tabIndex ?: 0}"
  }

/**
 * Advances virtual time in frame-sized steps until something holds focus, up to
 * [FOCUS_LAND_MAX_FRAMES]. Returns whether focus landed.
 *
 * The bound is a runaway guard, not the expected exit: a preview with a focusable settles in the
 * first frame or two, and one without a focusable never settles at all — which is the case the
 * caller turns into a decline rather than a capture claiming a state nothing could take.
 */
@OptIn(ExperimentalTestApi::class)
private fun awaitFocusLanded(
  provider: SemanticsNodeInteractionsProvider,
  mainClock: androidx.compose.ui.test.MainTestClock,
): Boolean {
  repeat(FOCUS_LAND_MAX_FRAMES) {
    if (focusedNodeBounds(provider) != null) return true
    mainClock.advanceTimeByFrame()
  }
  return focusedNodeBounds(provider) != null
}

/** ~1 s of virtual time at 16 ms/frame — far past any real focus walk. */
private const val FOCUS_LAND_MAX_FRAMES = 60

/**
 * Bounds of the focused element in root coordinates, or `null` when nothing holds focus — which is
 * how the caller distinguishes "the walk landed" from "this preview has no focusable content".
 */
@OptIn(ExperimentalTestApi::class)
private fun focusedNodeBounds(provider: SemanticsNodeInteractionsProvider): Rectangle? {
  val node = provider.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull() ?: return null
  val rect = node.boundsInRoot
  if (!rect.left.isFinite() || !rect.top.isFinite()) return null
  if (rect.width <= 0f || rect.height <= 0f) return null
  return Rectangle(rect.left.toInt(), rect.top.toInt(), rect.width.toInt(), rect.height.toInt())
}

/**
 * Captures the root and writes it with the same wrap crop / round clip [renderPreview] applies, so
 * a focused capture and the resting one differ only in the state they document.
 */
@OptIn(ExperimentalTestApi::class)
private fun captureFocusFrame(
  provider: SemanticsNodeInteractionsProvider,
  outputFile: File,
  sceneSize: IntSize,
  measured: IntSize?,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  widthPx: Int,
  heightPx: Int,
  device: String?,
  fileSystem: FileSystem,
) {
  val bitmap = provider.onRoot().captureToImage()
  val skiaImage = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
  val pngData =
    skiaImage.encodeToData(EncodedImageFormat.PNG)
      ?: error("Failed to encode focused capture to PNG")
  val bytes =
    try {
      pngData.bytes
    } finally {
      pngData.close()
      skiaImage.close()
    }

  outputFile.parentFile?.mkdirs()
  val roundClip = isRoundPreviewDevice(device)
  if (((wrapWidth || wrapHeight) && measured != null) || roundClip) {
    val cropW =
      (if (wrapWidth && measured != null) measured.width else widthPx).coerceIn(1, sceneSize.width)
    val cropH =
      (if (wrapHeight && measured != null) measured.height else heightPx).coerceIn(
        1,
        sceneSize.height,
      )
    val decoded = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    if (decoded != null) {
      val cropped =
        if (cropW < decoded.width || cropH < decoded.height) {
          decoded.getSubimage(
            0,
            0,
            cropW.coerceAtMost(decoded.width),
            cropH.coerceAtMost(decoded.height),
          )
        } else {
          decoded
        }
      val output = if (roundClip) applyRoundClip(cropped) else cropped
      fileSystem.write(outputFile.path.toPath()) { ImageIO.write(output, "PNG", outputStream()) }
      return
    }
  }
  fileSystem.write(outputFile.path.toPath()) { write(bytes) }
}

@Composable
private fun InvokeFocusComposable(
  composableMethod: ComposableMethod,
  instance: Any?,
  previewArgs: List<Any?>,
) {
  composableMethod.invoke(currentComposer, instance, *previewArgs.toTypedArray())
}

/**
 * `@PreviewWrapper(Provider::class)` around the focused content, resolved through [resolveWrapper]
 * — the same substitution-aware lookup [renderPreview] uses, so a wrapper the
 * `PreviewWrapperSubstitutionProvider` replaces (the Remote Compose one) is replaced here too.
 */
@Composable
private fun InvokeFocusWrappedComposable(wrapperFqn: String, body: @Composable () -> Unit) {
  val resolved = androidx.compose.runtime.remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
  resolved.first.invoke(currentComposer, resolved.second, body)
}
