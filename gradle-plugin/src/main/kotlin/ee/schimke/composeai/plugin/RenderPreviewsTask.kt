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
    val previews =
      if (!isFastTier) rawManifest.previews
      else
        rawManifest.previews.filter {
          val firstCost = it.captures.firstOrNull()?.cost ?: STATIC_COST
          !isHeavyCost(firstCost)
        }
    val manifest = if (isFastTier) rawManifest.copy(previews = previews) else rawManifest

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
        val relRender =
          capture.renderOutput.substringAfter("renders/", missingDelimiterValue = "").ifEmpty {
            "${preview.id}.png"
          }
        val outputFile = outDir.resolve(relRender)
        invokeRenderer(
          mainClass = mainClass,
          preview = preview,
          spec = spec,
          density = density,
          widthPx = widthPx,
          heightPx = heightPx,
          outputFile = outputFile,
          scroll = capture.scroll,
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
  ) {
    execOperations.javaexec {
      classpath = renderClasspath
      this.mainClass.set(mainClass)
      // Forward the display-filter selection so DesktopRendererMain can call
      // DisplayFilterDataProducer.writeArtifacts after each render. Empty string is fine —
      // DisplayFilterConfig.parseFilters treats blank input as "feature disabled".
      systemProperty("composeai.displayfilter.filters", displayFilterFilters.get())
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
        )
    }
  }
}
