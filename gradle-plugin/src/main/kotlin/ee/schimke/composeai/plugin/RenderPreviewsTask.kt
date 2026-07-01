package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class RenderPreviewsTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val previewsJson: RegularFileProperty

  @get:Input abstract val renderBackend: Property<String>

  /**
   * Restricts rendering to previews whose `params.kind.name` is in this set. Empty (the default)
   * renders every kind — the historical behaviour. The Android path uses this to run a Lottie-only
   * desktop-renderer pass (`composePreviewRenderLottie`) for `kind=LOTTIE` assets, which the
   * Robolectric `composePreviewRender` deliberately skips (it can't inflate Compottie). Kept
   * generic (a set of kind names) rather than a Lottie-specific flag so other JVM-renderable kinds
   * can reuse it.
   */
  @get:Input abstract val includeKinds: org.gradle.api.provider.SetProperty<String>

  /**
   * Render-tier filter. When `"fast"` the desktop path skips any preview whose representative
   * capture is heavier than [HEAVY_COST_THRESHOLD] (TOP / static stay in; LONG / GIF / animated
   * fall out). Default `"full"` keeps the historical behaviour (every preview rendered).
   */
  @get:Input abstract val tier: Property<String>

  /**
   * Comma-separated `composeai.displayfilter.filters` value forwarded as a system property to the
   * desktop renderer subprocess. Empty / unset disables display filters. See
   * [AndroidPreviewSupport.resolveDisplayFilterFilters] for the canonical resolver shared with the
   * Android Test task. Marked `@Input` so a filter-list change drives re-render.
   */
  @get:Input abstract val displayFilterFilters: Property<String>

  /**
   * Device-frame selection (`auto`, a Device Art Generator id, or empty to disable) forwarded as
   * `composeai.deviceframe.device` to the desktop renderer subprocess. See
   * [AndroidPreviewSupport.resolveDeviceFrameDevice]. Marked `@Input` so a selection change drives
   * re-render.
   */
  @get:Input abstract val deviceFrameDevice: Property<String>

  @get:Classpath abstract val renderClasspath: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  /**
   * Data-products output. Sibling of [outputDir] in the standard layout
   * (`build/compose-previews/data/...`). `@ScrollingPreview(modes = [LONG, GIF])` and other
   * heavyweight annotations route their per-capture artifacts here rather than `renders/` so the
   * primary preview carousel stays small. Optional so older test scaffolds keep working without
   * changes; when absent the task falls back to a sibling-of-[outputDir] directory at execution.
   */
  @get:org.gradle.api.tasks.Optional
  @get:OutputDirectory
  abstract val dataProductsDir: DirectoryProperty

  @get:Inject abstract val execOperations: ExecOperations

  init {
    // Explicit empty default so the desktop `composePreviewRender` registration (which never sets
    // `includeKinds`) has a configured value for this non-optional `@Input` rather than relying on
    // the managed-`SetProperty` implicit empty convention — keeps "render every kind" the default
    // and self-documents it.
    includeKinds.convention(emptySet())
    // Caching is intentionally gated on `tier=full`: a `tier=fast` run
    // only writes a subset of captures (fast ones), so a build-cache
    // restore from a fast snapshot would *wipe* the previous full run's
    // heavy outputs from `outputDir` — exactly the stale images the
    // interactive UI relies on. Up-to-date checks still apply, so a
    // re-run with no input changes is a no-op and the renders directory
    // stays as-is regardless of tier.
    outputs.cacheIf("composePreviewRender caches tier=full runs only") {
      tier.get().equals("full", ignoreCase = true)
    }
  }

  @TaskAction
  fun render() {
    val json = Json { ignoreUnknownKeys = true }
    val rawManifest = json.decodeFromString<PreviewManifest>(previewsJson.get().asFile.readText())

    // Tier filter — drop previews whose representative capture is heavy
    // when running in `fast` mode. The desktop path renders just the
    // first capture per preview, so the decision is per-preview rather
    // than per-capture (unlike the Robolectric path which can pick and
    // choose among an entry's captures). Skipped previews keep their
    // previous PNG on disk (referenced by the manifest, untouched by
    // `cleanStaleRenders`) so VS Code can still display the stale image
    // with its badge.
    val isFastTier = tier.get().equals("fast", ignoreCase = true)
    val tierFiltered =
      if (!isFastTier) rawManifest.previews
      else
        rawManifest.previews.filter {
          val firstCost = it.captures.firstOrNull()?.cost ?: STATIC_COST
          !isHeavyCost(firstCost)
        }
    // Kind filter — when set, render only the named kinds (e.g. the Android Lottie-only pass).
    // Empty
    // keeps every kind.
    val kinds = includeKinds.getOrElse(emptySet())
    val kindFiltered =
      if (kinds.isEmpty()) tierFiltered else tierFiltered.filter { it.params.kind.name in kinds }
    // `CATALOG` sheets carry their tokens as structured data (`params.catalogTokens`), which this
    // desktop path's flat positional-arg protocol doesn't forward — and their display
    // `functionName`
    // ("Brand colours") isn't a real composable, so `getDeclaredComposableMethod` would throw and
    // sink `composePreviewRenderAll` on any CMP/desktop module using `@ColorCatalog`. Catalog
    // sheets
    // render on the Android backend today; desktop catalog support is tracked in #2135. Skip them
    // here rather than crash.
    val previews = kindFiltered.filter { it.params.kind.name != "CATALOG" }
    // Always rebuild from the filtered list now that the CATALOG skip applies unconditionally (the
    // old fast-path reused `rawManifest` verbatim, which would leave catalog entries in).
    val manifest = rawManifest.copy(previews = previews)

    if (manifest.previews.isEmpty()) {
      logger.lifecycle("No previews to render.")
      return
    }

    val outDir = outputDir.get().asFile
    outDir.mkdirs()

    renderWithCompose(manifest, outDir)

    val tierTag =
      if (isFastTier)
        " (fast tier; ${rawManifest.previews.size - manifest.previews.size} heavy skipped)"
      else ""
    logger.lifecycle("Rendered ${manifest.previews.size} preview(s)$tierTag")
  }

  private fun renderWithCompose(manifest: PreviewManifest, outDir: java.io.File) {
    // This path is only used for desktop rendering.
    // Android rendering uses a separate Test-type task (see ComposePreviewPlugin).
    val mainClass = "ee.schimke.composeai.renderer.DesktopRendererMainKt"

    // Data products (e.g. `@ScrollingPreview(modes = [LONG, GIF])` LONG/GIF outputs) land in
    // `<previews-dir>/data/<kind>/<id>.<ext>` instead of `renders/`. Each PreviewDataProduct's
    // `output` is `data/<kind>/<id>.<ext>` — relative to the previews root (sibling of `outDir`).
    // The `dataProductsDir` task output, when wired by the plugin, points at that same `data/`
    // directory so Gradle tracks the written artifacts for caching / up-to-date checks.
    val previewsRoot = outDir.parentFile

    // Device frame — prefetch the needed bezels (Ktor/OkHttp, here in the Gradle JVM) into the
    // shared cache before launching renderer subprocesses, which only read that cache. See
    // DeviceArtPrefetch for why fetching can't live on the render classpath.
    val frameDevice = deviceFrameDevice.get()
    if (frameDevice.isNotBlank()) {
      DeviceArtPrefetch.prefetchInto(
        cacheDir = DeviceArtPrefetch.defaultCacheDir(),
        artIds = DeviceArtPrefetch.artIdsFor(frameDevice),
        logger = logger,
      )
    }

    for (preview in manifest.previews) {
      val spec =
        DeviceDimensions.resolveForRender(
          device = preview.params.device,
          widthDp = preview.params.widthDp,
          heightDp = preview.params.heightDp,
          showSystemUi = preview.params.showSystemUi,
        )
      // Per-device density (= densityDpi / 160), so output bitmaps match
      // what Android Studio renders for the same `@Preview`. Source: the
      // same data sergio-sastre/ComposablePreviewScanner /
      // takahirom/roborazzi consume. Discovery pins `params.density` when
      // a device/showSystemUi frame applies; the wrap-content path leaves
      // it null and we fall back to `spec.density` (= DEFAULT_DENSITY).
      val density = preview.params.density ?: spec.density
      val widthPx = (spec.widthDp * density).toInt().coerceAtLeast(1)
      val heightPx = (spec.heightDp * density).toInt().coerceAtLeast(1)

      // (1) Iterate primary captures. Most previews have exactly one; multi-mode @ScrollingPreview
      // (TOP/END), @RoboComposePreviewOptions time fan-out, and @FocusedPreview indexed mode all
      // produce N captures with different `renderOutput` paths. Skipping all but the first (the
      // old behaviour) silently dropped those extra files.
      for (capture in preview.captures) {
        // Resolve `renderOutput` (e.g. `renders/<id>.png`, or `lottie-renders/<id>.png` for the
        // Android Lottie pass) relative to the compose-previews root — same convention the
        // data-product outputs and the missing-render gate use. For the normal `renders/<id>.png`
        // this is identical to the old `outDir.resolve(<id>.png)` (outDir == previewsRoot/renders),
        // but it also lets a task whose `outputDir` is a disjoint sibling (lottie-renders/) write
        // there without an output-dir overlap.
        val outputFile =
          if (capture.renderOutput.isNotEmpty()) previewsRoot.resolve(capture.renderOutput)
          else outDir.resolve("${preview.id}.png")
        invokeRenderer(
          mainClass = mainClass,
          preview = preview,
          spec = spec,
          density = density,
          widthPx = widthPx,
          heightPx = heightPx,
          outputFile = outputFile,
          scroll = capture.scroll,
          animation = capture.animation,
        )
      }

      // (2) Iterate data products. `@ScrollingPreview(modes = [LONG, GIF])` emits these instead
      // of placing LONG/GIF in `captures` — discovery splits scroll modes so the heavyweight
      // outputs don't crowd the primary carousel (see `PreviewDiscovery.kt:844`). Render each
      // here with the same arg shape; renderer dispatches on `scrollMode` to the dedicated
      // `runComposeUiTest`-driven path.
      for (product in preview.dataProducts) {
        if (product.scroll == null) continue
        if (product.output.isBlank()) continue
        val outputFile = previewsRoot.resolve(product.output)
        invokeRenderer(
          mainClass = mainClass,
          preview = preview,
          spec = spec,
          density = density,
          widthPx = widthPx,
          heightPx = heightPx,
          outputFile = outputFile,
          scroll = product.scroll,
        )
      }
    }
  }

  private fun invokeRenderer(
    mainClass: String,
    preview: PreviewInfo,
    spec: DeviceDimensions.SizeSpec,
    density: Float,
    widthPx: Int,
    heightPx: Int,
    outputFile: java.io.File,
    scroll: ScrollCapture?,
    animation: AnimationCapture? = null,
  ) {
    execOperations.javaexec {
      classpath = renderClasspath
      this.mainClass.set(mainClass)
      // Forward the display-filter selection so DesktopRendererMain can call
      // DisplayFilterDataProducer.writeArtifacts after each render. Empty string is fine —
      // DisplayFilterConfig.parseFilters treats blank input as "feature disabled".
      systemProperty("composeai.displayfilter.filters", displayFilterFilters.get())
      // Forward the device-frame selection + the prefetch cache dir so DesktopRendererMain can
      // composite the render into a device-art bezel (reading the cache the task action filled).
      // Empty string disables it (DeviceFrameConfig treats blank as "off").
      systemProperty("composeai.deviceframe.device", deviceFrameDevice.get())
      if (deviceFrameDevice.get().isNotBlank()) {
        systemProperty(
          "composeai.deviceframe.cacheDir",
          DeviceArtPrefetch.defaultCacheDir().absolutePath,
        )
      }
      args =
        listOf(
          preview.className,
          preview.functionName,
          widthPx.toString(),
          heightPx.toString(),
          density.toString(),
          preview.params.showBackground.toString(),
          preview.params.backgroundColor.toString(),
          outputFile.absolutePath,
          // 9th arg — empty string signals "no wrapper" (keeps arg positions stable).
          preview.params.wrapperClassName.orEmpty(),
          // 10th/11th — AS-parity wrap flags. When set, the renderer
          // wraps the composable, measures it, and crops the PNG to
          // the intrinsic bounds on that axis.
          spec.wrapWidth.toString(),
          spec.wrapHeight.toString(),
          // 12th/13th — @PreviewParameter spec. Empty string signals
          // "no provider"; otherwise the renderer enumerates the
          // provider's values.take(limit) in-process and writes one
          // `<id>_PARAM_<idx>.png` per value. Plugin-side can't know
          // the count (consumer's classpath isn't loaded here), so
          // fan-out is delegated to the renderer process that already
          // has everything on its classpath.
          preview.params.previewParameterProviderClassName.orEmpty(),
          preview.params.previewParameterLimit.toString(),
          // 14th — `@Preview(locale = ...)`. Empty string signals "no override". The renderer
          // detects `en-XA` / `ar-XB` and applies the runtime pseudolocale wrap (currently
          // LayoutDirection.Rtl for ar-XB on desktop; Android additionally pseudolocalises
          // string resources via the `:data-pseudolocale-connector` Resources subclass).
          preview.params.locale.orEmpty(),
          // 15th–18th — @ScrollingPreview intent forwarded per capture / data product. Empty
          // 15th signals "no scroll intent". Renderer dispatches LONG / GIF to
          // `renderScrollPreview` (`runComposeUiTest`-driven scroll + slice or frame encode);
          // TOP / END fall through to the default single-frame path.
          scroll?.mode?.name.orEmpty(),
          scroll?.axis?.name.orEmpty(),
          (scroll?.maxScrollPx ?: 0).toString(),
          (scroll?.frameIntervalMs ?: 0).toString(),
          // 19th/20th — preview kind + (for kind=LOTTIE) the resource-relative asset path. Empty
          // 19th defaults to COMPOSE on the renderer side. A LOTTIE entry has no class/function to
          // reflect; the renderer inflates the asset at arg 20 via Compottie instead.
          preview.params.kind.name,
          preview.params.assetPath.orEmpty(),
          // 21st — `@Preview(fontScale = ...)`. Compose Desktop has no resource-qualifier system,
          // so the renderer threads this through `Density(density, fontScale)` (and re-provides it
          // as `LocalDensity`) the same way the daemon's desktop RenderEngine does. `1.0` is the
          // annotation default / no-op; omitting it keeps older callers at 1.0 on the renderer
          // side.
          preview.params.fontScale.toString(),
          // 22nd–24th — `@Preview(showSystemUi = ...)` (issue #1930). When set on a phone-shape
          // capture, DesktopRendererMain wraps the composition in the synthetic `SystemBarsFrame`
          // (status bar + gesture-nav pill) so the desktop capture matches the Android renderer
          // instead of coming back chrome-less. uiMode carries the night bit for dark chrome;
          // device is forwarded only so the renderer can skip round/Wear surfaces.
          preview.params.showSystemUi.toString(),
          preview.params.uiMode.toString(),
          preview.params.device.orEmpty(),
          // 25th–27th — `@AnimatedPreview` window. `0` durationMs signals "no animation intent"
          // (the renderer falls through to scroll / single-frame). A positive durationMs dispatches
          // to `renderAnimatedPreview` (a `runSkikoComposeUiTest` paused-clock loop that advances
          // `mainClock` by frameIntervalMs across the window and encodes the frames as a GIF) —
          // the desktop counterpart of the Android renderer's `@AnimatedPreview` path. `showCurves`
          // is forwarded for parity; the desktop path emits a screenshot-only GIF (no curve strip).
          (animation?.durationMs ?: 0).toString(),
          (animation?.frameIntervalMs ?: 0).toString(),
          (animation?.showCurves ?: false).toString(),
        )
    }
  }
}
