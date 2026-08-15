package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import ee.schimke.composeai.daemon.CachedDeviceArtSource
import ee.schimke.composeai.daemon.DeviceFrameConfig
import ee.schimke.composeai.daemon.DeviceFrameDataProducer
import ee.schimke.composeai.daemon.DisplayFilterConfig
import ee.schimke.composeai.daemon.DisplayFilterDataProducer
import ee.schimke.composeai.data.render.LinkBufferComposer
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.preview.lottie.LottiePreview
import ee.schimke.composeai.preview.svg.SvgPreview
import ee.schimke.composeai.preview.svg.loadSvgAsset
import ee.schimke.composeai.scroll.ScrollAxis as ProductScrollAxis
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat

/**
 * Standalone entry point for rendering Compose Desktop previews to PNG.
 *
 * Args: className functionName widthPx heightPx density showBackground backgroundColor outputFile
 * [wrapperClassName] [wrapWidth] [wrapHeight] [previewParameterProviderFqn] [previewParameterLimit]
 * [localeTag] [scrollMode] [scrollAxis] [scrollMaxScrollPx] [scrollFrameIntervalMs] [previewKind]
 * [assetPath] [fontScale]
 *
 * The optional 21st argument (`fontScale`) carries `@Preview(fontScale = ...)`. Compose Desktop has
 * no resource-qualifier system, so it's threaded through `Density(density, fontScale)` (and
 * re-provided as `LocalDensity`); omit or pass `1.0` for the no-op default.
 *
 * Args 22–24 carry `@Preview(showSystemUi = ...)` support (issue #1930). When [showSystemUi] is
 * `true` and the capture is phone-shape (not a round/Wear device, not a tile), the renderer wraps
 * the composition in [SystemBarsFrame] — a synthetic status bar + gesture-nav pill that *simulates*
 * Android phone chrome on this non-Android backend, matching what the Android/Robolectric renderer
 * draws for the same `@Preview`. Arg 23 (`uiMode`) is the `@Preview(uiMode = …)` int (only the
 * `UI_MODE_NIGHT_*` bits matter — dark chrome on a night capture); arg 24 (`device`) is the raw
 * device string used only to skip round/Wear surfaces. All default to "no system UI".
 *
 * The optional 9th argument is the FQN of a `PreviewWrapperProvider` (Compose 1.11+); pass an empty
 * string or omit to skip wrapping.
 *
 * Args 10 and 11 are AS-parity wrap flags. When an axis wraps, widthPx/heightPx are treated as a
 * sandbox dimension — the renderer wraps the composable, measures its intrinsic size, and crops the
 * final PNG to that size on the wrapped axis. Defaults to `false` when omitted so older callers
 * keep the full-frame behaviour.
 *
 * Args 12 and 13 are the `@PreviewParameter` provider FQN + limit. When arg 12 is non-empty the
 * renderer instantiates the provider, iterates its `values.take(limit)`, and writes one file per
 * value using `outputFile` as a template (`_PARAM_<idx>` inserted before the extension). Looping
 * inside a single JVM — instead of spawning one subprocess per value — avoids N× Compose cold-start
 * cost; the same `ImageComposeScene` is reused across values.
 *
 * The optional 28th argument is a `|`-joined list of sibling stems: filenames (without extension)
 * of OTHER manifest outputs in the same directory whose stem extends this preview's (`@Preview(name
 * = "Dark")` on `Foo` yields sibling stem `Foo_Dark`). The `@PreviewParameter` stale fan-out
 * cleanup must leave those siblings' files alone — see [deleteStaleFanoutFiles] (issue #2193). `|`
 * is safe as a separator because discovery's `sanitizeForPath` strips it from every stem.
 *
 * Args 29–32 (indices 28–31) are the wrapped-axis content-size bounds — `minWidthPx`,
 * `minHeightPx`, `maxWidthPx`, `maxHeightPx` (the Max / Min / Within size modes). Positive
 * integers; missing / blank / non-positive means "no bound" (the AS-parity wrap). They only bite on
 * a wrapped axis: a max bound lowers the wrap ceiling so the component can't grow past it, a min
 * bound raises the floor (the scene is enlarged to fit) so it can't collapse below it, then the
 * capture crops to the resulting intrinsic size. Mirror the daemon's
 * `PreviewOverrides.{min,max}{Width,Height}Px` so a `compose-preview bundle render` matches what
 * `compose-preview serve` produces for the same size override — letting a component be captured as
 * it would appear constrained to e.g. a list column.
 *
 * Args 33–38 (indices 32–37) carry `@FocusedPreview` per-capture drive (issue #3672):
 * `focusTabIndex` (indexed mode, `-1`/blank when absent), `focusDirections` (traversal mode: a
 * `|`-joined list of every step up to and including this capture's, replayed in order) +
 * `focusStep`, `focusEnterPlacesFocus`, `focusPressed`, `focusOverlay`. When either mode is present
 * the capture leaves the [ImageComposeScene] path for [renderFocusPreview], which walks focus with
 * a real `FocusManager.moveFocus(...)` traversal under a synthetic keyboard input mode and — for
 * `focusPressed` — dispatches a real pointer down onto the focused element. Omitted by older
 * plugins, which keeps every capture on the undriven path.
 *
 * Args 15–18 carry `@ScrollingPreview` intent. When [scrollMode] is `"LONG"` or `"GIF"` the
 * renderer leaves the [ImageComposeScene] path and dispatches to [renderScrollPreview] (which uses
 * `runComposeUiTest` for paused-clock + semantic scroll). `"TOP"` / `"END"` / empty are handled by
 * the default single-frame path — TOP is the natural unscrolled capture, END is best-effort because
 * the desktop renderer doesn't yet drive scrolls outside the LONG / GIF code path. `scrollAxis` is
 * `VERTICAL` or `HORIZONTAL` (default `VERTICAL`). `scrollMaxScrollPx` caps the total scrolled
 * extent (`0` = unbounded). `scrollFrameIntervalMs` is the per-frame GIF dwell (`0` = encoder
 * default).
 */
fun main(args: Array<String>) {
  // Before anything composes — including before the pooled worker's *first* request draws a frame,
  // since a worker calls this same `main()` per capture and the runtime latches the flag at the
  // first composition. Idempotent, so every subsequent pooled capture re-applies the same value for
  // free. Unset (the default) makes this a single `getProperty` and no notice at all.
  LinkBufferComposer.applyAndDescribe()?.let(System.err::println)

  if (args.size < 8) {
    System.err.println(
      "Usage: DesktopRendererMain <className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor> <outputFile> [wrapperClassName] [wrapWidth] [wrapHeight] [previewParameterProviderFqn] [previewParameterLimit] [localeTag] [scrollMode] [scrollAxis] [scrollMaxScrollPx] [scrollFrameIntervalMs]"
    )
    exitProcess(1)
  }

  val className = args[0]
  val functionName = args[1]
  val widthPx =
    args[2].toIntOrNull()
      ?: run {
        System.err.println("Invalid widthPx: '${args[2]}' (expected integer)")
        exitProcess(1)
      }
  val heightPx =
    args[3].toIntOrNull()
      ?: run {
        System.err.println("Invalid heightPx: '${args[3]}' (expected integer)")
        exitProcess(1)
      }
  val density =
    args[4].toFloatOrNull()
      ?: run {
        System.err.println("Invalid density: '${args[4]}' (expected float)")
        exitProcess(1)
      }
  val showBackground = args[5].toBoolean()
  val backgroundColor =
    args[6].toLongOrNull()
      ?: run {
        System.err.println("Invalid backgroundColor: '${args[6]}' (expected long)")
        exitProcess(1)
      }
  val outputFile = File(args[7])
  val wrapperClassName = args.getOrNull(8)?.takeIf { it.isNotBlank() }
  val wrapWidth = args.getOrNull(9)?.toBoolean() ?: false
  val wrapHeight = args.getOrNull(10)?.toBoolean() ?: false
  val previewParameterProviderFqn = args.getOrNull(11)?.takeIf { it.isNotBlank() }
  val previewParameterLimit = args.getOrNull(12)?.toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
  val localeTag = args.getOrNull(13)?.takeIf { it.isNotBlank() }
  // `@ScrollingPreview` knobs. Empty / missing scrollMode means "no scroll intent — render
  // a single frame via the default ImageComposeScene path". LONG / GIF / END dispatch to the
  // `runComposeUiTest`-driven `renderScrollPreview`, which is the only path here with a paused
  // clock and semantic-node interactions to drive a scrollable with. TOP stays on the default
  // path: it *is* the undriven first viewport, so routing it through the test harness would buy
  // nothing but a second composition.
  val scrollModeArg = args.getOrNull(14)?.takeIf { it.isNotBlank() }
  val scrollAxis =
    when (args.getOrNull(15)?.takeIf { it.isNotBlank() }?.uppercase()) {
      "HORIZONTAL" -> ProductScrollAxis.HORIZONTAL
      else -> ProductScrollAxis.VERTICAL
    }
  val scrollMaxScrollPx = args.getOrNull(16)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
  val scrollFrameIntervalMs = args.getOrNull(17)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
  val scrollDispatchMode: DesktopScrollMode? =
    when (scrollModeArg?.uppercase()) {
      "LONG" -> DesktopScrollMode.LONG
      "GIF" -> DesktopScrollMode.GIF
      "END" -> DesktopScrollMode.END
      else -> null
    }

  // kind=LOTTIE — a directly-discovered Lottie asset, not a `@Composable`. There is no class /
  // function to reflect; inflate the asset (arg 20, a classpath-relative path) via Compottie and
  // capture a single frame. Short-circuits the whole `@PreviewParameter` / scroll machinery below.
  val previewKind = args.getOrNull(18)?.takeIf { it.isNotBlank() }?.uppercase() ?: "COMPOSE"
  // `@Preview(fontScale = ...)`. Compose Desktop has no resource-qualifier system; the scale is
  // carried on `Density.fontScale` (see [renderPreview] / [renderScrollPreview]). Missing / blank /
  // unparseable falls back to 1.0f (no-op) so older callers and the LOTTIE path keep their
  // behaviour.
  val fontScale = args.getOrNull(20)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.0f
  // Args 22–24 — `@Preview(showSystemUi = ...)` (issue #1930). When showSystemUi is set on a
  // phone-shape capture the renderer wraps the composition in [SystemBarsFrame] (synthetic Android
  // chrome). uiMode supplies the night bit for dark chrome; device is consulted only to skip
  // round/Wear surfaces. All optional — missing/blank keeps the chrome-less default.
  val showSystemUi = args.getOrNull(21)?.toBoolean() ?: false
  val uiMode = args.getOrNull(22)?.toIntOrNull() ?: 0
  val device = args.getOrNull(23)?.takeIf { it.isNotBlank() }
  // Args 25–27 — `@AnimatedPreview` window. `-1` (or missing/unparseable, for older callers) means
  // "no animation intent". `>= 0` means the annotation is present and dispatches to
  // [renderAnimatedPreview] (paused-clock GIF) ahead of the scroll / single-frame paths — `0` is
  // the annotation's auto-detect sentinel, NOT "no animation": collapsing it into the single-frame
  // path wrote PNG bytes into the `.gif` renderOutput (issue #2190). Mutually exclusive with
  // `@ScrollingPreview` in discovery, so no precedence ambiguity in practice; the renderer still
  // checks animation first defensively.
  //
  // Version skew: a *pre-fix* plugin (a consumer can pin a newer renderer on the
  // `composePreviewRenderer` configuration while its plugin lags) sent `0` for EVERY preview
  // without an `@AnimatedPreview`, so a bare `0` is ambiguous between "legacy: no animation" and
  // "new: auto-detect". Disambiguate by the capture's other intent signals: an animated capture
  // never carries scroll intent and discovery always points its renderOutput at a `.gif`, while a
  // legacy caller's `0` accompanies a `.png` output (static) or a scroll mode (scroll GIF). A `0`
  // that fails that shape check falls through to the scroll / single-frame paths — exactly the
  // legacy behaviour those callers expect.
  val animDurationArg = args.getOrNull(24)?.toIntOrNull() ?: -1
  val hasAnimation =
    animDurationArg > 0 ||
      (animDurationArg == 0 &&
        scrollDispatchMode == null &&
        outputFile.extension.equals("gif", ignoreCase = true))
  val animDurationMs = animDurationArg.coerceAtLeast(0)
  val animFrameIntervalMs = args.getOrNull(25)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
  val animShowCurves = args.getOrNull(26)?.toBoolean() ?: false
  // 28th — sibling stems for the stale fan-out cleanup (issue #2193). Missing / blank keeps the
  // pre-#2193 behaviour (no sibling protection), so older callers stay arg-compatible.
  val fanoutSiblingStems = args.getOrNull(27)?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
  // Args 29–32 (indices 28–31) — wrapped-axis content-size bounds (the Max / Min / Within size
  // modes). Positive integers; missing / blank / non-positive means "no bound" (the AS-parity
  // wrap).
  // Only consulted on a wrapped axis. Mirror the daemon's
  // `PreviewOverrides.{min,max}{Width,Height}Px`, so a `compose-preview bundle render` matches what
  // `compose-preview serve` produces for the same size-mode override. Older callers omit them and
  // keep the unbounded wrap.
  val minWidthPx = args.getOrNull(28)?.toIntOrNull()?.takeIf { it > 0 }
  val minHeightPx = args.getOrNull(29)?.toIntOrNull()?.takeIf { it > 0 }
  val maxWidthPx = args.getOrNull(30)?.toIntOrNull()?.takeIf { it > 0 }
  val maxHeightPx = args.getOrNull(31)?.toIntOrNull()?.takeIf { it > 0 }
  // Args 33–38 (indices 32–37) — `@FocusedPreview` per-capture drive (issue #3672). All absent /
  // blank on a preview without the annotation, which is what keeps every existing capture on the
  // untouched [renderPreview] path. Indexed mode carries `focusTabIndex >= 0`; traversal mode
  // carries `focusDirection` (+ its 1-based `focusStep` for the overlay label). Both empty means
  // "no focus intent" even if a later flag is set, so a garbled tail degrades to the old
  // behaviour rather than driving a walk nobody asked for.
  val focusTabIndex = args.getOrNull(32)?.toIntOrNull()?.takeIf { it >= 0 }
  // `|`-joined traversal directions — every step from 1 up to and including the one this capture
  // documents, so the walk can be replayed from scratch in this capture's own scene (the desktop
  // renderer composes one scene per capture; see [DesktopFocusIntent.directions]). A single-step
  // traversal is just a one-element list.
  val focusDirections =
    args
      .getOrNull(33)
      ?.split('|')
      .orEmpty()
      .filter { it.isNotBlank() }
      .mapNotNull { name ->
        ee.schimke.composeai.daemon.protocol.FocusDirection.entries.firstOrNull {
          it.name.equals(name, ignoreCase = true)
        }
      }
  val focusStep = args.getOrNull(34)?.toIntOrNull()?.takeIf { it > 0 }
  val focusEnterPlacesFocus = args.getOrNull(35)?.toBoolean() ?: false
  val focusPressed = args.getOrNull(36)?.toBoolean() ?: false
  val focusOverlay = args.getOrNull(37)?.toBoolean() ?: false
  val hoverIndex = args.getOrNull(38)?.toIntOrNull()?.takeIf { it >= 0 }
  val focusIntent: DesktopFocusIntent? =
    when {
      focusDirections.isNotEmpty() ->
        DesktopFocusIntent(
          directions = focusDirections,
          step = focusStep,
          pressed = focusPressed,
          overlay = focusOverlay,
        )
      focusTabIndex != null ->
        DesktopFocusIntent(
          tabIndex = focusTabIndex,
          enterPlacesFocus = focusEnterPlacesFocus,
          pressed = focusPressed,
          overlay = focusOverlay,
        )
      else -> null
    }
  // `@OverrideVariant` seed for a synthetic variant preview, forwarded by RenderPreviewsTask as the
  // `composeai.overrides.seed` per-render system property (the desktop subprocess has no manifest
  // to
  // read `PreviewInfo.overrides` from). Decode the canonical `OverrideVariantSpec` and seed the
  // controller before composing, so this preview's `previewOverride*` reads resolve to the flipped
  // knob(s) — the desktop counterpart of the Android backend's per-preview seed. Absent/blank ⇒ an
  // ordinary preview (controller stays empty → author defaults). Best-effort: a decode failure must
  // not derail the render. Set once here (each subprocess renders one preview);
  // `clearDeclarations()`
  // below leaves seeds intact so the `.overrides.json` declaration drain still works.
  // Drop any seed a PREVIOUS render left behind before applying this one. In the
  // one-process-per-capture world this is a no-op (the JVM is fresh every time), which is why the
  // note above could say "set once here". A pooled renderer worker breaks that assumption: a
  // seeded `@OverrideVariant` followed by an ordinary preview would leave the variant's values in
  // the controller — `clearDeclarations()` deliberately keeps seeds — and the ordinary preview
  // would silently render with someone else's knobs. Resetting per render makes each capture
  // independent of what the process drew before it, which is exactly what lets the caller stop
  // paying for a JVM per capture.
  ee.schimke.composeai.overrides.PreviewOverrideController.resetForNewSession()
  System.getProperty("composeai.overrides.seed")
    ?.takeIf { it.isNotBlank() }
    ?.let { seedJson ->
      runCatching {
          kotlinx.serialization.json
            .Json { ignoreUnknownKeys = true }
            .decodeFromString(
              ee.schimke.composeai.data.overrides.OverrideVariantSpec.serializer(),
              seedJson,
            )
            .toNamedOverrides()
        }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?.let { ee.schimke.composeai.overrides.PreviewOverrideController.set(it) }
    }
  if (previewKind == "LOTTIE") {
    val assetPath = args.getOrNull(19)?.takeIf { it.isNotBlank() }
    val sidecar = errorSidecarFor(outputFile)
    if (sidecar.exists()) sidecar.delete()
    try {
      requireNotNull(assetPath) {
        "kind=LOTTIE preview is missing its asset path (renderer arg 20)"
      }
      // Still vs animated dispatch keys off the discovery-derived stem, not a bare `_animated`
      // suffix (see [isLottieAnimatedOutput]).
      if (isLottieAnimatedOutput(assetPath, outputFile)) {
        renderLottieApng(
          assetPath = assetPath,
          widthPx = widthPx,
          heightPx = heightPx,
          density = density,
          showBackground = showBackground,
          backgroundColor = backgroundColor,
          outputFile = outputFile,
        )
      } else {
        renderLottieAsset(
          assetPath = assetPath,
          widthPx = widthPx,
          heightPx = heightPx,
          density = density,
          showBackground = showBackground,
          backgroundColor = backgroundColor,
          outputFile = outputFile,
        )
      }
    } catch (e: Throwable) {
      writeErrorSidecar(outputFile, className, functionName, e)
    }
    return
  }

  // kind=SVG — a directly-discovered `.svg` asset, not a `@Composable`. Inflate the asset (arg 20,
  // a
  // classpath-relative path) via the Skia-backed `loadSvgPainter` and capture a single still frame.
  // Static — no GIF companion, unlike LOTTIE. Short-circuits the reflection / scroll machinery.
  if (previewKind == "SVG") {
    val assetPath = args.getOrNull(19)?.takeIf { it.isNotBlank() }
    val sidecar = errorSidecarFor(outputFile)
    if (sidecar.exists()) sidecar.delete()
    try {
      requireNotNull(assetPath) { "kind=SVG preview is missing its asset path (renderer arg 20)" }
      renderSvgAsset(
        assetPath = assetPath,
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        showBackground = showBackground,
        backgroundColor = backgroundColor,
        outputFile = outputFile,
      )
    } catch (e: Throwable) {
      writeErrorSidecar(outputFile, className, functionName, e)
    }
    return
  }

  // Provider enumeration is fatal to the whole subprocess — we can't
  // render anything if values can't be loaded. Per-value render failures
  // are caught individually below and surfaced as `.error.json` sidecars
  // so a single broken preview doesn't sink the whole batch.
  val values: List<Any?> =
    try {
      if (previewParameterProviderFqn != null && previewParameterLimit > 0) {
        loadProviderValues(previewParameterProviderFqn, previewParameterLimit).also { vs ->
          if (vs.isEmpty()) {
            System.err.println(
              "@PreviewParameter(provider = $previewParameterProviderFqn) on $functionName produced no values — skipping."
            )
          }
        }
      } else {
        listOf(NO_PARAM)
      }
    } catch (e: Exception) {
      writeErrorSidecar(outputFile, className, functionName, e)
      // Exit 0 so the gradle plugin keeps rendering subsequent previews.
      // The sidecar carries the structured error; the plugin doesn't need
      // to re-discover it from a non-zero exit.
      return
    }

  val allSuffixes: List<String> =
    if (values.size == 1 && values[0] === NO_PARAM) {
      listOf("")
    } else {
      PreviewParameterLabels.suffixesFor(values)
    }
  val allTargetFiles = values.mapIndexed { idx, value ->
    if (value === NO_PARAM) outputFile
    else File(insertBeforeExtension(outputFile.path, allSuffixes[idx]))
  }
  // Row filter (`--exclude-preview-row`, issue #2966 follow-up): drop the fan-out members the
  // caller
  // named by label. Applied AFTER labelling — the labels are the only handle on a row, and they
  // don't
  // exist until the provider has been enumerated, which is why this can't live in the id filters
  // upstream. Kept out of `deleteStaleFanoutFiles` below on purpose: a skipped row's PNG from a
  // prior
  // full run stays on disk, exactly like a preview the id filter skipped, so an interactive panel
  // still shows the (stale-badged) image instead of a hole.
  val keptRows = PreviewRowFilter.keptRows(allSuffixes, PreviewRowFilter.patterns())
  if (keptRows.size < allSuffixes.size) {
    val skipped =
      allSuffixes.filterIndexed { idx, _ -> idx !in keptRows }.map { it.removePrefix("_") }
    System.err.println(
      "@PreviewParameter on $functionName: skipping ${skipped.size} of ${allSuffixes.size} row(s) " +
        "excluded by ${PreviewRowFilter.PROPERTY}: ${skipped.joinToString(", ")}"
    )
  }
  val targetFiles = keptRows.map { allTargetFiles[it] }
  val keptValues = keptRows.map { values[it] }
  // Renderer is authoritative about the fan-out — delete any
  // `<stem>_*<ext>` files from prior runs that aren't in this run's
  // expected output. Guards against provider renames and the
  // `_PARAM_<idx>` → `_<label>` migration leaving stale PNGs behind.
  //
  // Deliberately keyed on ALL of this run's rows, not just the kept ones: a row the filter skipped
  // is
  // still a row the current provider yields, so its previous PNG is stale-but-valid (badged in the
  // panel), not orphaned. Passing the kept subset here would delete exactly the images the filter
  // exists to avoid re-rendering.
  if (values.any { it !== NO_PARAM }) {
    deleteStaleFanoutFiles(outputFile, allTargetFiles.map { it.name }.toSet(), fanoutSiblingStems)
  }
  for ((idx, value) in keptValues.withIndex()) {
    val targetFile = targetFiles[idx]
    val previewArgs = if (value === NO_PARAM) emptyList() else listOf(value)
    // Per-value try/catch — the user-facing pain we're addressing is "one
    // broken preview turns the whole panel into 'Build failed'". Catching
    // here lets sibling previews in the same subprocess (i.e. multiple
    // @PreviewParameter values for the same function) succeed
    // independently. Cross-preview isolation across functions is already
    // provided by the subprocess-per-preview model in the gradle plugin.
    try {
      // Drop any stale sidecar before attempting a fresh render — if the
      // last run failed and this one succeeds, the .error.json from the
      // failed run would otherwise live forever next to the new PNG and
      // VS Code would surface yesterday's exception as if it were current.
      val sidecar = errorSidecarFor(targetFile)
      if (sidecar.exists()) sidecar.delete()
      if (hasAnimation) {
        // `@AnimatedPreview` — advance a paused clock across the window and encode a GIF. Always
        // produces output (an animation has no "no scrollable" decline path), so no fallback to the
        // single-frame render is needed. durationMs == 0 asks the renderer to auto-detect.
        renderAnimatedPreview(
          className = className,
          functionName = functionName,
          widthPx = widthPx,
          heightPx = heightPx,
          density = density,
          showBackground = showBackground,
          backgroundColor = backgroundColor,
          outputFile = targetFile,
          wrapperClassName = wrapperClassName,
          previewArgs = previewArgs,
          localeTag = localeTag,
          durationMs = animDurationMs,
          frameIntervalMs = animFrameIntervalMs,
          showCurves = animShowCurves,
          fontScale = fontScale,
        )
      } else if (
        (focusIntent != null || hoverIndex != null) &&
          scrollDispatchMode != DesktopScrollMode.LONG &&
          scrollDispatchMode != DesktopScrollMode.GIF
      ) {
        // `@FocusedPreview` — walk focus with a real `FocusManager.moveFocus(...)` traversal (and,
        // for `pressed = true`, dispatch a real pointer down onto the focused element) before
        // capturing. Declines only when nothing took focus, in which case the undriven capture
        // below is written so a misuse still produces a file rather than a hole in the sheet — the
        // same fall-through shape a declined END scroll uses. The renderer prints the decline, so
        // it isn't silent.
        //
        // A capture can carry BOTH intents — discovery crosses the scroll and focus fan-outs — so
        // the two drives are composed rather than raced: END scrolls first inside the focus path
        // (see `scrollToEnd`), and LONG / GIF stay with the scroll branch below, because their
        // product is a stitched strip / animation that a single focused frame cannot stand in for.
        // Either way no capture is published under a filename naming an intent that never ran.
        val didCapture =
          renderFocusPreview(
            className = className,
            functionName = functionName,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            showBackground = showBackground,
            backgroundColor = backgroundColor,
            outputFile = targetFile,
            wrapperClassName = wrapperClassName,
            wrapWidth = wrapWidth,
            wrapHeight = wrapHeight,
            previewArgs = previewArgs,
            localeTag = localeTag,
            focus = focusIntent,
            hoverIndex = hoverIndex,
            scrollToEnd = scrollDispatchMode == DesktopScrollMode.END,
            scrollAxis = scrollAxis,
            scrollMaxScrollPx = scrollMaxScrollPx,
            fontScale = fontScale,
            showSystemUi = showSystemUi,
            uiMode = uiMode,
            device = device,
            minWidthPx = minWidthPx,
            minHeightPx = minHeightPx,
            maxWidthPx = maxWidthPx,
            maxHeightPx = maxHeightPx,
          )
        if (!didCapture) {
          renderPreview(
            className,
            functionName,
            widthPx,
            heightPx,
            density,
            showBackground,
            backgroundColor,
            targetFile,
            wrapperClassName,
            wrapWidth,
            wrapHeight,
            previewArgs,
            localeTag,
            fontScale,
            showSystemUi,
            uiMode,
            device,
            minWidthPx = minWidthPx,
            minHeightPx = minHeightPx,
            maxWidthPx = maxWidthPx,
            maxHeightPx = maxHeightPx,
          )
        }
      } else if (scrollDispatchMode != null) {
        // @ScrollingPreview(modes = [LONG, GIF, END]) — drive the dedicated scroll path. For a
        // primary *capture*, falls through to the default single-frame render on "no
        // scrollable found" so a misuse produces SOMETHING on disk rather than a missing
        // file. END is always a primary capture and leans on that fall-through by design: an
        // END capture of a screen that doesn't scroll is exactly its top frame, and the
        // default path is the one that knows about wrap crops and `showSystemUi` chrome.
        // For a *data product* output (under `data/render-scroll-*/`), mirror the
        // Android renderer's rule (see RobolectricRenderTest's `productFellThrough`): a
        // fall-through would write PNG bytes into a `.gif`-named product, or stamp the
        // unscrolled first viewport into the long-scroll path, and the panel would surface
        // a still frame under a "scroll gif" / "scroll long" label as if capture succeeded.
        // Throw instead — the per-value catch below writes the structured `.error.json`
        // sidecar so the panel surfaces the real failure.
        val didCapture =
          renderScrollPreview(
            className = className,
            functionName = functionName,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            showBackground = showBackground,
            backgroundColor = backgroundColor,
            outputFile = targetFile,
            wrapperClassName = wrapperClassName,
            previewArgs = previewArgs,
            localeTag = localeTag,
            scrollMode = scrollDispatchMode,
            axis = scrollAxis,
            maxScrollPx = scrollMaxScrollPx,
            frameIntervalMs = scrollFrameIntervalMs,
            fontScale = fontScale,
            // END writes an ordinary primary PNG, so it has to honour the same `@Preview` framing
            // the fall-through path below applies. It only reaches that path when it DECLINES, so
            // a successful drive previously produced a capture with none of it — a night preview
            // rendered light, a phone without its system bars, a round device unclipped.
            uiMode = uiMode,
            showSystemUi = showSystemUi,
            device = device,
          )
        if (!didCapture) {
          if (isDataProductOutput(targetFile)) {
            throw IllegalStateException(
              "@ScrollingPreview(${scrollDispatchMode.name}) on $className.$functionName: " +
                "no scrollable composable found on axis ${scrollAxis.name} — refusing to " +
                "write a single-frame capture into the data product path."
            )
          }
          renderPreview(
            className,
            functionName,
            widthPx,
            heightPx,
            density,
            showBackground,
            backgroundColor,
            targetFile,
            wrapperClassName,
            wrapWidth,
            wrapHeight,
            previewArgs,
            localeTag,
            fontScale,
            showSystemUi,
            uiMode,
            device,
            minWidthPx = minWidthPx,
            minHeightPx = minHeightPx,
            maxWidthPx = maxWidthPx,
            maxHeightPx = maxHeightPx,
          )
        }
      } else {
        renderPreview(
          className,
          functionName,
          widthPx,
          heightPx,
          density,
          showBackground,
          backgroundColor,
          targetFile,
          wrapperClassName,
          wrapWidth,
          wrapHeight,
          previewArgs,
          localeTag,
          fontScale,
          showSystemUi,
          uiMode,
          device,
          minWidthPx = minWidthPx,
          minHeightPx = minHeightPx,
          maxWidthPx = maxWidthPx,
          maxHeightPx = maxHeightPx,
        )
      }
      // Display filters — post-capture colour-matrix variants (grayscale / invert / daltonizer
      // simulations). Gated on `composeai.displayfilter.filters` being non-empty; the gradle plugin
      // forwards it from the `composePreview.displayFilter.filters` Gradle property. Wrapped in
      // try/catch so a filter failure does not invalidate the just-rendered PNG. Data dir mirrors
      // the daemon's convention (`<renders-dir>/../data/<previewId>/`), resolved via
      // [resolveDataDir] so scroll data products don't nest a second `data/`.
      val displayFilters = DisplayFilterConfig.fromSystemProperties()
      if (displayFilters.isNotEmpty()) {
        try {
          DisplayFilterDataProducer.writeArtifacts(
            rootDir = resolveDataDir(targetFile),
            previewId = targetFile.nameWithoutExtension,
            pngFile = targetFile,
            filters = displayFilters,
          )
        } catch (t: Throwable) {
          System.err.println(
            "DesktopRendererMain: displayfilter write failed for ${targetFile.name}: " +
              "${t.javaClass.simpleName}: ${t.message}"
          )
        }
      }
      // Device frame — composite the render into a real device-art bezel (round Wear watch, phone)
      // with hardware buttons. Gated on `composeai.deviceframe.device`; same data dir + best-effort
      // try/catch discipline as the display-filter block above.
      val deviceFrame = DeviceFrameConfig.fromSystemProperties()
      if (deviceFrame != null) {
        try {
          DeviceFrameDataProducer.writeArtifacts(
            rootDir = resolveDataDir(targetFile),
            previewId = targetFile.nameWithoutExtension,
            pngFile = targetFile,
            device = device,
            settings = deviceFrame,
            source = CachedDeviceArtSource(deviceFrame.cacheDir),
          )
        } catch (t: Throwable) {
          System.err.println(
            "DesktopRendererMain: deviceframe write failed for ${targetFile.name}: " +
              "${t.javaClass.simpleName}: ${t.message}"
          )
        }
      }
    } catch (e: Throwable) {
      // Catch Throwable, not Exception — preview functions can throw
      // Errors (e.g. AssertionError from a misused require) and the user
      // wants those surfaced too. We won't catch JVM-fatal throwables in
      // practice (those already terminated the JVM before we got here).
      System.err.println("Render failed for $className.$functionName: ${e.message}")
      writeErrorSidecar(targetFile, className, functionName, e)
      // Continue with the next value — keep exit code 0 below.
    }
  }
}

/**
 * Resolve the previews-root `data/` dir for a post-capture data producer (display filters, device
 * frames). For a normal `renders/<id>.png` capture that's the sibling `data/`; for a data-product
 * output already under `data/<kind>/<id>.png` (LONG/GIF scroll products) the grandparent IS
 * `data/`, so don't nest a second `data/`. Shared by both producer call sites so the twin blocks
 * can't drift.
 */
internal fun resolveDataDir(targetFile: File): File {
  val captureDir = targetFile.parentFile
  return if (captureDir?.parentFile?.name == "data") captureDir.parentFile!!
  else (captureDir?.parentFile ?: captureDir ?: targetFile).resolve("data")
}

/**
 * Filename convention for the error sidecar: same path as the PNG with `.error.json` appended.
 * Sibling placement means the gradle plugin doesn't need an aggregation step and the extension
 * finds the sidecar by trivial string-concat on the manifest's existing renderOutput path.
 */
private fun errorSidecarFor(pngFile: File): File =
  File(pngFile.parentFile, pngFile.name + ".error.json")

/**
 * True when [outputFile] is a *data product* output — `<previews-root>/data/<kind>/<id>.<ext>`,
 * e.g. the `data/render-scroll-{long,gif}/` paths `RenderPreviewsTask` resolves from
 * `PreviewDataProduct.output` — rather than a primary capture (`renders/<id>.png`). Same
 * grandparent-is-`data` convention the device-frame block in [main] uses to resolve the data dir.
 */
internal fun isDataProductOutput(outputFile: File): Boolean =
  outputFile.parentFile?.parentFile?.name == "data"

/**
 * Whether [outputFile] is the **animated** Lottie companion for [assetPath] (vs the still frame).
 *
 * `PreviewDiscovery` emits two captures per discovered asset: the still `<stem>.png` and the
 * animated companion `<stem>_animated.png`, where `<stem>` is `lottie__` followed by [assetPath]
 * with every `[^A-Za-z0-9._-]` character replaced by `_`. Matching a bare `_animated.png` suffix is
 * wrong: an asset named `*_animated.json` sanitises to a stem that already ends in `_animated`, so
 * its *required still* would be mistaken for the animated companion (and rendered as an APNG). We
 * recompute the exact still stem and treat only `<stem>_animated` as the companion.
 *
 * Keep the `lottie__` prefix + sanitisation rule in sync with
 * `PreviewDiscovery.discoverLottieAssets`.
 */
internal fun isLottieAnimatedOutput(assetPath: String, outputFile: File): Boolean {
  val stillStem =
    "lottie__" + assetPath.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
  return outputFile.nameWithoutExtension == "${stillStem}_animated"
}

private fun writeErrorSidecar(
  pngFile: File,
  className: String,
  functionName: String,
  e: Throwable,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val sidecar = errorSidecarFor(pngFile)
  sidecar.parentFile?.mkdirs()
  // Drop any stale PNG from a previous successful run so the extension
  // doesn't surface yesterday's image alongside today's error message.
  if (pngFile.exists()) pngFile.delete()
  val stack = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
  val top = pickTopAppFrame(e)
  // A native-loading failure kills every preview in the module with the same root cause, so say so
  // on the sidecar rather than leaving 1000 identical `NoClassDefFoundError`s to be reverse
  // engineered (issue #3690). Null for an ordinary preview throw, which is nearly always.
  val diagnosis = NativeLoadDiagnosis.diagnose(e)
  // Hand-rolled JSON to avoid pulling kotlinx-serialization into the
  // renderer-desktop runtime classpath (the plugin owns serialisation
  // and we don't want a second copy here). Schema must mirror
  // gradle-plugin/.../PreviewRenderError.kt verbatim.
  val sb = StringBuilder()
  sb.append('{')
  sb.append("\"schema\":\"compose-preview-error/v1\",")
  sb.append("\"exception\":").append(jsonString(e.javaClass.name)).append(',')
  sb.append("\"message\":").append(jsonString(e.message ?: "")).append(',')
  if (top != null) {
    sb.append("\"topAppFrame\":{")
    sb.append("\"file\":").append(jsonString(top.file)).append(',')
    sb.append("\"line\":").append(top.line).append(',')
    sb.append("\"function\":").append(jsonString(top.function))
    sb.append("},")
  }
  if (diagnosis != null) {
    sb.append("\"diagnosis\":").append(jsonString(diagnosis.text)).append(',')
  }
  // Which JVM drew this, and what it would have searched for native libraries. Always present:
  // "which of the four JDKs on this box did the toolchain actually fork, and did my LD_LIBRARY_PATH
  // reach it?" is unanswerable after the fact, and it is two lines of JSON to answer up front.
  sb.append("\"runtime\":{")
  NativeLoadDiagnosis.runtimeSnapshot().forEachIndexed { index, (key, value) ->
    if (index > 0) sb.append(',')
    sb.append(jsonString(key)).append(':').append(jsonString(value))
  }
  sb.append("},")
  sb.append("\"stackTrace\":").append(jsonString(stack))
  sb.append('}')
  fileSystem.write(sidecar.path.toPath()) { writeUtf8(sb.toString()) }
  // The build log is where a batch render is actually watched, and a per-preview stack trace there
  // would be the cascade all over again — one line, once per JVM, only for the failure class that
  // means "nothing in this module can render".
  if (diagnosis != null && !diagnosis.cascade) {
    System.err.println("Render failed (native): ${diagnosis.text}")
  }
}

/**
 * The first stack frame attributable to user code — skip Compose scaffold, Kotlin stdlib, JDK
 * frames, and the renderer's own glue so the user-facing "at Previews.kt:47" points where the bug
 * actually is. Returns null when no frame survives the filter (deep framework throw).
 */
private fun pickTopAppFrame(e: Throwable): TopFrameJson? {
  val skipPrefixes =
    listOf(
      "androidx.compose.",
      "kotlin.",
      "kotlinx.",
      "java.",
      "javax.",
      "jdk.",
      "sun.",
      "ee.schimke.composeai.renderer.",
      "org.jetbrains.skia.",
      "org.jetbrains.skiko.",
    )
  for (frame in e.stackTrace) {
    val cls = frame.className
    if (skipPrefixes.any { cls.startsWith(it) }) continue
    return TopFrameJson(
      file = frame.fileName ?: "",
      line = frame.lineNumber.coerceAtLeast(0),
      function = frame.methodName ?: "",
    )
  }
  return null
}

private data class TopFrameJson(val file: String, val line: Int, val function: String)

private fun jsonString(s: String): String {
  val sb = StringBuilder(s.length + 2)
  sb.append('"')
  for (c in s) {
    when (c) {
      '"' -> sb.append("\\\"")
      '\\' -> sb.append("\\\\")
      '\b' -> sb.append("\\b")
      '\n' -> sb.append("\\n")
      '\r' -> sb.append("\\r")
      '\t' -> sb.append("\\t")
      else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
  }
  sb.append('"')
  return sb.toString()
}

// Sentinel: distinguishes "no @PreviewParameter fan-out" from "provider yielded null".
// A null value from the provider is a legitimate case we want to render; NO_PARAM
// short-circuits the file-path suffix logic instead.
private val NO_PARAM = Any()

/**
 * Suffixes a renderer appends to a render output's own file name to make a companion that lives
 * beside it — `renders/Foo.png` → `renders/Foo.png.error.json`. The stale-fan-out sweeps must treat
 * a companion as belonging to the output it names, or a removed provider value leaves its sidecar
 * behind forever (see [deleteStaleFanoutFiles]).
 *
 * `.error.json` is written by [writeErrorSidecar] here and by `RenderErrorSidecar` on the Android
 * side; `.warnings.json` (`RenderWarningsSidecar`) only has an Android writer today, but the sweep
 * covers it on both backends so the cleanup contract doesn't depend on which renderer produced the
 * directory. Kept in sync with `PreviewManifestLoader.RENDER_COMPANION_SUFFIXES`.
 */
internal val RENDER_COMPANION_SUFFIXES = listOf(".error.json", ".warnings.json")

/**
 * The render output [name] belongs to: [name] itself when it is an `[ext]` output, the output it
 * names when it is one of that output's [RENDER_COMPANION_SUFFIXES] companions, and `null`
 * otherwise — `null` meaning "not this template's business", which on a delete path is the answer
 * that keeps a file.
 *
 * A companion is recognised by its suffix **before** the plain-extension fallback is reached, and
 * is then held to the same per-extension scoping as any other candidate: it belongs to this sweep
 * only if the output it names carries [ext]. Deciding in the other order breaks down as soon as
 * [ext] is itself `.json` — a data product — because then every `.error.json` in the directory ends
 * with [ext], and the fallback claims image companions like `Foo_Alice.png.error.json` as JSON
 * outputs of its own, deleting another output's *current* diagnostics.
 */
internal fun fanoutOutputNameOf(name: String, ext: String): String? {
  RENDER_COMPANION_SUFFIXES.forEach { companion ->
    if (name.endsWith(companion)) return name.removeSuffix(companion).takeIf { it.endsWith(ext) }
  }
  return name.takeIf { it.endsWith(ext) }
}

/**
 * Deletes `<stem>_*<ext>` files from prior runs that this render won't rewrite — stale fan-out left
 * behind by provider renames ("loading" → "busy") and the `_PARAM_<idx>` → `_<label>` migration.
 *
 * The prefix match alone over-reaches (issue #2193): `@Preview(name = …)` / `@Preview(group = …)`
 * variant suffixes make a sibling preview's stem an underscore-extension of [template]'s (`Foo` vs
 * `Foo_Dark`), so the sibling's base render and its own fan-out (`Foo_Dark_<label>.png`) both match
 * `Foo_*` while belonging to a different subprocess. The plugin — which has the manifest this
 * subprocess doesn't — passes those stems in [protectedSiblingStems]; any file that is
 * `<sibling>.<ext>` or `<sibling>_*` is theirs, not stale.
 *
 * A stale row's companions go with it. A failed row writes `<stem>_<label><ext>.error.json` and no
 * output, so an extension-only filter never matched the one file the row actually left behind, and
 * the orphan outlived every subsequent run — the CLI's missing-render report then rediscovers it by
 * directory glob and can present an obsolete exception as a failure of the current run (PR #3815).
 * The companion is classified by the output it names, so it is kept exactly when that output is
 * expected and swept exactly when that output is stale.
 */
internal fun deleteStaleFanoutFiles(
  template: File,
  expectedNames: Set<String>,
  protectedSiblingStems: List<String> = emptyList(),
) {
  val dir = template.parentFile ?: return
  if (!dir.isDirectory) return
  val stem = template.nameWithoutExtension
  val ext = ".${template.extension}"
  val prefix = stem + "_"
  dir
    .listFiles()
    ?.filter { f ->
      val output = fanoutOutputNameOf(f.name, ext)
      f.name.startsWith(prefix) &&
        output != null &&
        output !in expectedNames &&
        protectedSiblingStems.none { sib ->
          f.name.startsWith("$sib.") || f.name.startsWith("${sib}_")
        }
    }
    ?.forEach { f ->
      if (!f.delete()) {
        System.err.println("Failed to delete stale fan-out file: ${f.absolutePath}")
      }
    }
}

private fun insertBeforeExtension(path: String, suffix: String): String {
  if (path.isEmpty()) return path
  val dot = path.lastIndexOf('.')
  val slash = path.lastIndexOf(File.separatorChar).coerceAtLeast(path.lastIndexOf('/'))
  return if (dot > slash) path.substring(0, dot) + suffix + path.substring(dot) else path + suffix
}

/**
 * Loads and enumerates a `PreviewParameterProvider` reflectively — same strategy the Android
 * renderer uses in `PreviewManifestLoader.loadProviderValues`. Keeping this renderer-local avoids a
 * shared module dependency and the lookup stays limited to the method shapes the interface
 * guarantees (`getValues(): Sequence`).
 */
private fun loadProviderValues(providerFqn: String, limit: Int): List<Any?> {
  val clazz =
    try {
      Class.forName(providerFqn)
    } catch (e: ClassNotFoundException) {
      System.err.println("@PreviewParameter: provider class $providerFqn not found — skipping.")
      return emptyList()
    }
  val instance =
    runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
      }
      .getOrElse { e ->
        System.err.println(
          "@PreviewParameter: couldn't instantiate $providerFqn via nullary ctor: ${e.message}"
        )
        return emptyList()
      }
  val getValues =
    runCatching { clazz.getMethod("getValues") }
      .getOrElse {
        System.err.println(
          "@PreviewParameter: $providerFqn has no getValues() — not a PreviewParameterProvider?"
        )
        return emptyList()
      }
  // A `private` provider (idiomatic Kotlin, renders fine in Android Studio) compiles to a
  // package-private JVM class; `getValues.invoke` from outside the package then throws
  // IllegalAccessException without this. Mirrors the Android renderer fix for issue #2493.
  getValues.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val sequence = getValues.invoke(instance) as? Sequence<Any?> ?: return emptyList()
  // `Sequence.take(Int)` is lazy and `.toList()` drives it — bounds the
  // enumeration for infinite providers without requiring an explicit
  // counter. Calling through the typed Kotlin API avoids reflective
  // access into `kotlin.jvm.internal.ArrayIterator`, whose visibility is
  // package-private and throws `IllegalAccessException` from outside the
  // stdlib's own module.
  return sequence.take(limit).toList()
}

/**
 * Maps a `@Preview(uiMode = …)` int to the [androidx.compose.ui.SystemTheme] to provide as
 * `LocalSystemTheme`, which Compose Desktop's `isSystemInDarkTheme()` reads. Only the
 * `UI_MODE_NIGHT_*` bits (`0x30` mask) matter: `0x20` (`UI_MODE_NIGHT_YES`) → dark, `0x10`
 * (`UI_MODE_NIGHT_NO`) → light, `UI_MODE_NIGHT_UNDEFINED` → `Unknown` (leaves the JVM's own probe).
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
internal fun systemThemeFromUiMode(uiMode: Int): androidx.compose.ui.SystemTheme =
  when (uiMode and 0x30) {
    0x20 -> androidx.compose.ui.SystemTheme.Dark
    0x10 -> androidx.compose.ui.SystemTheme.Light
    else -> androidx.compose.ui.SystemTheme.Unknown
  }

/**
 * The base BCP-47 tag actually applied for a `localeTag` override, or `null` for a blank override.
 * Pseudolocales (`en-XA`, `ar-XB`) fold to their base (both `en`) — they aren't real BCP-47
 * locales, so `Locale.forLanguageTag("en-XA")` would degrade depending on the JVM's ICU build. The
 * visual pseudolocalisation (RTL flip for `ar-XB`) is applied separately by [renderPreview].
 */
internal fun effectiveLocaleTag(localeTag: String?): String? {
  val tag = localeTag?.takeIf { it.isNotBlank() } ?: return null
  return ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(tag)?.baseTag ?: tag
}

/**
 * Point the JVM default [java.util.Locale] at the (base) [localeTag] override for a render and
 * return the previous default so [renderPreview] can restore it — or `null` when there is no
 * override (nothing to restore).
 *
 * CMP string resources (`org.jetbrains.compose.components:components-resources`) resolve their
 * locale via `rememberResourceEnvironment()` → `androidx.compose.ui.text.intl.Locale.current`,
 * which on Skiko/desktop reads the JVM default `Locale` — **not** `LocalLayoutDirection` or any
 * composition local. Without this a `@Preview(locale = "de")` only flipped the layout direction
 * (for RTL) and left `stringResource(...)` rendering the base (English) copy. Mirrors the daemon
 * desktop `RenderEngine`, so a batch bundle re-render localises the same way `compose-preview
 * serve` does.
 */
internal fun overrideJvmDefaultLocale(localeTag: String?): java.util.Locale? {
  val effectiveTag = effectiveLocaleTag(localeTag) ?: return null
  val previous = java.util.Locale.getDefault()
  java.util.Locale.setDefault(java.util.Locale.forLanguageTag(effectiveTag))
  return previous
}

/** Restore a JVM default [java.util.Locale] captured by [overrideJvmDefaultLocale]. */
internal fun restoreJvmDefaultLocale(previous: java.util.Locale?) {
  if (previous != null) java.util.Locale.setDefault(previous)
}

/**
 * Whether a `localeTag` render should compose with `LayoutDirection.Rtl` — true for the `ar-XB`
 * bidi pseudolocale **and** for a real RTL locale (`ar`, `he`, `fa`, `ur`, …).
 *
 * The real-locale half is the part that was missing on this batch path. [overrideJvmDefaultLocale]
 * gives `stringResource(...)` the translated copy, so a `@Preview(locale = "ar")` came back with
 * correctly shaped Arabic — inside a container that was still laid out left-to-right: navigation
 * icon on the left, FAB bottom-right, start/end padding unmirrored. That is not what an Arabic
 * device shows, and it made the capture read as "RTL is fine" when the layout had never been
 * exercised. The daemon desktop `RenderEngine.localeProviders`, the daemon Android `RenderEngine`
 * and `RobolectricRenderTest` all already consult [LocaleDirection]; the three desktop batch
 * renderers keyed off `Pseudolocale.isRtl` alone, so `ar-XB` mirrored and `ar` did not.
 *
 * Takes the raw tag rather than the effective one: [effectiveLocaleTag] folds `ar-XB` to `en`, and
 * asking "is `en` RTL?" would lose the pseudolocale's flip. Pseudolocale first, real locale second,
 * so the two can never double-count.
 */
internal fun rendersRightToLeft(localeTag: String?): Boolean {
  val pseudolocale = ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(localeTag)
  if (pseudolocale != null) return pseudolocale.isRtl
  return ee.schimke.composeai.data.pseudolocale.LocaleDirection.isRtl(effectiveLocaleTag(localeTag))
}

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
internal fun renderPreview(
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
  fontScale: Float = 1.0f,
  showSystemUi: Boolean = false,
  uiMode: Int = 0,
  device: String? = null,
  // Wrapped-axis content-size bounds (the Max / Min / Within size modes). Null keeps the AS-parity
  // wrap (min = 0, max = sandbox); a max bound lowers the wrap ceiling, a min bound raises the
  // floor.
  // Only consulted on a wrapped axis. Mirrors the desktop daemon RenderEngine so a bundle re-render
  // matches what `compose-preview serve` produces for the same size-mode override.
  minWidthPx: Int? = null,
  minHeightPx: Int? = null,
  maxWidthPx: Int? = null,
  maxHeightPx: Int? = null,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val clazz = Class.forName(className)
  val composableMethod =
    if (previewArgs.isEmpty()) {
      clazz.getDeclaredComposableMethod(functionName)
    } else {
      findComposableMethodWithArgs(clazz, functionName, previewArgs)
    }

  // Arm the named-override capture for this render: drop any knobs a prior preview declared so this
  // preview's `previewOverride*` lookups accumulate a clean set (drained into the sidecar below).
  ee.schimke.composeai.overrides.PreviewOverrideController.clearDeclarations()

  // Second half of applying `localeTag` (the first is the RTL flip below): point the JVM default
  // Locale at the override so CMP `stringResource(...)` — which reads
  // `androidx.compose.ui.text.intl.Locale.current`, the JVM default on desktop, not a composition
  // local — resolves translated copy. Restored in the `finally` so a thrown render (or the next
  // in-process render in the functional tests) never inherits the switch. See
  // [overrideJvmDefaultLocale].
  val previousDefaultLocale = overrideJvmDefaultLocale(localeTag)
  // Held outside the try so the `finally` below can always release it. The scene owns a Skia
  // `Surface`; closing it only on the success path leaked one per *handled* render failure — the
  // sidecar path returns normally rather than throwing — which a fresh JVM per capture disposed of
  // for free and a reused renderer worker does not. `RenderEngine` states the same invariant for
  // the daemon's copy of this body; this is the standalone renderer catching up to it.
  var sceneToRelease: ImageComposeScene? = null
  try {

    // `@Preview(fontScale = ...)` rides on `Density.fontScale`. Threading it through the scene's
    // constructor makes the override visible to layout (sp → px) before the first measure pass; we
    // also re-provide the same `Density` as `LocalDensity` below since some ui-text/text-foundation
    // paths read it directly during composition rather than via the scene density. Mirrors the
    // daemon's desktop RenderEngine (issue: @Preview(fontScale) was ignored on the CMP pipeline).
    val sceneDensity = Density(density, fontScale)
    // A min bound larger than the default sandbox needs the scene enlarged to fit, otherwise the
    // composable is clipped to the scene before the intrinsic-size crop runs. Only widen (never
    // shrink) on a wrapped axis; the crop still trims the PNG back to the measured intrinsic size.
    // Shared with the daemon's desktop RenderEngine via [composePreviewSceneSize] so a bundle
    // re-render and a `compose-preview serve` render size the same preview identically.
    val sizeBounds =
      PreviewSizeBounds(
        minWidthPx = minWidthPx,
        minHeightPx = minHeightPx,
        maxWidthPx = maxWidthPx,
        maxHeightPx = maxHeightPx,
      )
    val sceneSize = composePreviewSceneSize(widthPx, heightPx, wrapWidth, wrapHeight, sizeBounds)
    val sceneWidthPx = sceneSize.width
    val sceneHeightPx = sceneSize.height
    val scene =
      ImageComposeScene(width = sceneWidthPx, height = sceneHeightPx, density = sceneDensity).also {
        sceneToRelease = it
      }

    // Measured content size in pixels, captured from the wrapping Box via
    // onGloballyPositioned. Only read when at least one axis wraps.
    var measured: IntSize? = null

    // RTL: `ar-XB` (bidi pseudolocale) and every real RTL locale (`ar`, `he`, `fa`, …) flip
    // `LocalLayoutDirection` so the captured PNG mirrors layout — see [rendersRightToLeft].
    // en-XA is a no-op visually on desktop — CMP's
    // `org.jetbrains.compose.resources.stringResource` doesn't go through `LocalContext.resources`,
    // so the Resources-subclass trick the Android connector uses doesn't apply here. See the
    // platform-support note in `site/reference/pseudolocale.md`.
    val rtl = rendersRightToLeft(localeTag)
    // `@Preview(uiMode = 32)` (`UI_MODE_NIGHT_YES`) must flip the composition to dark, not just
    // tint
    // the system-bar chrome below. Compose Desktop's `isSystemInDarkTheme()` reads
    // `LocalSystemTheme.current` (foundation-desktop's `DarkTheme.skiko.kt`), so provide it from
    // the
    // night bit — otherwise a dark `@Preview` renders its content in light colours (the cover PNG
    // disagreed with the daemon's figma-svg/semantics, which already do this in RenderEngine).
    val systemTheme = systemThemeFromUiMode(uiMode)
    scene.setContent {
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
        // Precedence and the light/dark default live in
        // [ee.schimke.composeai.data.render.PreviewBackground] so both backends and both renderers
        // agree; in particular `showBackground = true` on a night preview is not white.
        val bgColor =
          Color(
            ee.schimke.composeai.data.render.PreviewBackground.resolveArgbForUiMode(
              showBackground = showBackground,
              backgroundColor = backgroundColor,
              uiMode = uiMode,
            )
          )
        val body: @Composable () -> Unit = {
          // The AS-parity wrap-measure box (and its fixed-axis `fillMaxSize` counterpart) is shared
          // with the daemon's desktop RenderEngine via [ComposePreviewContentBox], so both size the
          // preview and capture its intrinsic bounds identically. `measured` is only written on a
          // wrapped axis; it stays null for a fixed frame and the PNG is left uncropped below.
          ComposePreviewContentBox(
            wrapWidth = wrapWidth,
            wrapHeight = wrapHeight,
            backgroundColor = bgColor,
            sizeBounds = sizeBounds,
            onMeasured = { w, h -> measured = IntSize(w, h) },
          ) {
            InvokeComposable(composableMethod, null, previewArgs)
          }
        }
        // `@PreviewWrapper(Provider::class)` — instantiate the provider reflectively
        // so the renderer stays compatible with apps on stable Compose (no
        // `PreviewWrapperProvider` on classpath).
        val wrapped: @Composable () -> Unit = {
          if (wrapperClassName != null) {
            InvokeWrappedComposable(wrapperClassName, body)
          } else {
            body()
          }
        }
        // `@Preview(showSystemUi = true)` on a phone-shape capture wraps the composition in the
        // synthetic [SystemBarsFrame] (issue #1930). Desktop/Skiko has no Android SystemUI process,
        // so without this the canvas comes back at the right device size but chrome-less; the frame
        // simulates the status + gesture-nav bars to match the Android renderer. showSystemUi pins
        // both axes to the device frame (no wrap-content crop), so wrapping outside `body` is safe.
        // kind is always COMPOSE here — LOTTIE short-circuits in main() before reaching
        // renderPreview,
        // and desktop never produces TILE captures — so the gate reduces to showSystemUi +
        // non-round.
        if (shouldApplySystemBars(showSystemUi, device, kind = null)) {
          SystemBarsFrame(uiMode = uiMode) { wrapped() }
        } else {
          wrapped()
        }
      }
    }

    // Render two frames for animations/effects to settle
    scene.render()
    val image = scene.render()

    val pngData =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: throw IllegalStateException("Failed to encode image to PNG")

    outputFile.parentFile?.mkdirs()

    // Crop to the measured content bounds on wrapped axes. `measured` is
    // populated during the Modifier.layout measure pass in the wrap branch
    // above — if it somehow wasn't set (shouldn't happen, but defensive),
    // fall back to the sandbox dimensions and write the uncropped PNG.
    val m = measured
    val roundClip = isRoundPreviewDevice(device)
    if (((wrapWidth || wrapHeight) && m != null) || roundClip) {
      // Ceiling is the (possibly enlarged) scene dimension, not the raw widthPx/heightPx — a min
      // bound larger than the original frame grew the scene, and the crop must keep that extent.
      val cropW = (if (wrapWidth && m != null) m.width else widthPx).coerceIn(1, sceneWidthPx)
      val cropH = (if (wrapHeight && m != null) m.height else heightPx).coerceIn(1, sceneHeightPx)
      val decoded = ByteArrayInputStream(pngData.bytes).use { ImageIO.read(it) }
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
      } else {
        fileSystem.write(outputFile.path.toPath()) { write(pngData.bytes) }
      }
    } else {
      fileSystem.write(outputFile.path.toPath()) { write(pngData.bytes) }
    }

    // Released in the `finally` below rather than here, so a throw between the scene's
    // construction and this point cannot strand its Skia surface.

    // After a successful render, write the editable knobs this preview declared via
    // `previewOverride*`
    // as the `renders/<stem>.overrides.json` sidecar `BundlePreviewTask` packs into the bundle.
    writePreviewOverridesSidecar(outputFile, fileSystem)
  } finally {
    runCatching { sceneToRelease?.close() }
    restoreJvmDefaultLocale(previousDefaultLocale)
  }
}

internal fun applyRoundClip(source: java.awt.image.BufferedImage): java.awt.image.BufferedImage {
  val output =
    java.awt.image.BufferedImage(
      source.width,
      source.height,
      java.awt.image.BufferedImage.TYPE_INT_ARGB,
    )
  val g = output.createGraphics()
  try {
    g.setRenderingHint(
      java.awt.RenderingHints.KEY_ANTIALIASING,
      java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
    )
    g.clip = java.awt.geom.Ellipse2D.Float(0f, 0f, source.width.toFloat(), source.height.toFloat())
    g.drawImage(source, 0, 0, null)
  } finally {
    g.dispose()
  }
  return output
}

internal fun isRoundPreviewDevice(device: String?): Boolean {
  val lower = device?.lowercase() ?: return false
  return lower.contains("_round") || lower.contains("isround=true") || lower.contains("shape=round")
}

private val overridesSidecarJson =
  kotlinx.serialization.json.Json {
    encodeDefaults = true
    prettyPrint = false
  }

/**
 * Drain the named-override declarations captured during the just-finished render (if any) and write
 * them beside [outputFile] as `<stem>.overrides.json`, the serialized `compose/overrides` payload
 * `BundlePreviewTask.resolvePreviewOverrides` reads. Best-effort; an empty set deletes any stale
 * sidecar so a preview that stopped declaring knobs doesn't keep an old one. The `overrides.json`
 * suffix is kept in lockstep with `PreviewBundleFormat.BUNDLE_OVERRIDES_SIDECAR_EXT`.
 */
internal fun writePreviewOverridesSidecar(outputFile: File, fileSystem: FileSystem) {
  val declarations = ee.schimke.composeai.overrides.PreviewOverrideController.declarations()
  val parent = outputFile.parentFile ?: return
  val sidecar = File(parent, "${outputFile.nameWithoutExtension}.overrides.json").path.toPath()
  if (declarations.isEmpty()) {
    if (fileSystem.exists(sidecar)) fileSystem.delete(sidecar)
    return
  }
  val payload =
    ee.schimke.composeai.data.overrides.PreviewOverridesPayload(declarations = declarations)
  val bytes =
    overridesSidecarJson
      .encodeToString(
        ee.schimke.composeai.data.overrides.PreviewOverridesPayload.serializer(),
        payload,
      )
      .toByteArray(Charsets.UTF_8)
  fileSystem.write(sidecar) { write(bytes) }
}

/**
 * Render a directly-discovered Lottie asset to a single PNG frame. No consumer composable is
 * involved — [LottiePreview] loads [assetPath] off the render classpath (the plugin links the
 * processed-resources dir there) and inflates it via Compottie. Same two-`render()` settle + encode
 * path as [renderPreview], minus the wrap/crop logic (the animation fills the fixed sandbox).
 */
private fun renderLottieAsset(
  assetPath: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density))
  try {
    scene.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        val bgColor =
          when {
            backgroundColor != 0L -> Color(backgroundColor.toInt())
            showBackground -> Color.White
            else -> Color.Transparent
          }
        Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
          LottiePreview(asset = assetPath, modifier = Modifier.fillMaxSize())
        }
      }
    }
    scene.render()
    val image = scene.render()
    val pngData =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: throw IllegalStateException("Failed to encode Lottie frame to PNG")
    outputFile.parentFile?.mkdirs()
    fileSystem.write(outputFile.path.toPath()) { write(pngData.bytes) }
  } finally {
    scene.close()
  }
}

/**
 * Render a directly-discovered `.svg` asset to a single still PNG. The consumer-facing [SvgPreview]
 * runtime helper does the drawing (Skia-backed `loadSvgPainter` → `Painter` → `Image` at
 * [ContentScale.Fit]), the same way [renderLottieAsset] delegates to `LottiePreview`. The asset is
 * loaded **eagerly** via [loadSvgAsset] before composition so a missing file surfaces a clear
 * [IllegalArgumentException] the caller can turn into an error sidecar, rather than throwing deep
 * inside `render()`. SVG is static, so there is no animated companion. `internal` (not `private`
 * like [renderLottieAsset]) so the desktop renderer's unit test can drive it directly.
 */
internal fun renderSvgAsset(
  assetPath: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val svgBytes = loadSvgAsset(assetPath)
  val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density))
  try {
    scene.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        val bgColor =
          when {
            backgroundColor != 0L -> Color(backgroundColor.toInt())
            showBackground -> Color.White
            else -> Color.Transparent
          }
        Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
          SvgPreview(
            bytes = svgBytes,
            contentDescription = assetPath,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
          )
        }
      }
    }
    scene.render()
    val image = scene.render()
    val pngData =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: throw IllegalStateException("Failed to encode SVG frame to PNG")
    outputFile.parentFile?.mkdirs()
    fileSystem.write(outputFile.path.toPath()) { write(pngData.bytes) }
  } finally {
    scene.close()
  }
}

@Composable
private fun InvokeComposable(
  composableMethod: ComposableMethod,
  instance: Any?,
  previewArgs: List<Any?>,
) {
  composableMethod.invoke(currentComposer, instance, *previewArgs.toTypedArray())
}

/**
 * Desktop mirror of the Android renderer's lookup for `@PreviewParameter` functions — see
 * [ee.schimke.composeai.renderer.findComposableMethodWithArgs] for the full commentary. Kept local
 * (not shared via a common module) so the two renderer artefacts stay independently buildable.
 */
internal fun findComposableMethodWithArgs(
  clazz: Class<*>,
  name: String,
  previewArgs: List<Any?>,
): ComposableMethod {
  val argCount = previewArgs.size
  val candidate =
    clazz.declaredMethods.firstOrNull { m ->
      m.name == name && m.parameterCount >= argCount + 2 && argsMatch(m, previewArgs)
    }
      ?: throw NoSuchMethodException(
        "Couldn't find composable method $name on ${clazz.name} taking $argCount parameter(s); " +
          "check that the @PreviewParameter provider's value type matches the preview's parameter type."
      )
  val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
  return clazz.getDeclaredComposableMethod(name, *declaredTypes)
}

private fun argsMatch(method: java.lang.reflect.Method, previewArgs: List<Any?>): Boolean {
  for ((i, arg) in previewArgs.withIndex()) {
    val expected = method.parameterTypes[i]
    if (arg == null) {
      if (expected.isPrimitive) return false
      continue
    }
    val actual = arg.javaClass
    if (expected.isAssignableFrom(actual)) continue
    if (expected.kotlin.javaObjectType.isAssignableFrom(actual)) continue
    return false
  }
  return true
}

/**
 * Reflectively instantiates the `PreviewWrapperProvider` identified by [wrapperFqn] and invokes its
 * `Wrap(content)` composable around [body].
 *
 * See [RobolectricRenderTest.resolveWrapper] — same lookup strategy, same caveats.
 */
@Composable
private fun InvokeWrappedComposable(wrapperFqn: String, body: @Composable () -> Unit) {
  val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
  resolved.first.invoke(currentComposer, resolved.second, body)
}

internal fun resolveWrapper(wrapperFqn: String): Pair<ComposableMethod, Any> {
  val cls = ee.schimke.composeai.data.render.extensions.loadPreviewWrapperClass(wrapperFqn)
  val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
  // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
  // Wrap(Function2, Composer, int) at the bytecode level.
  val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
  return method to instance
}
