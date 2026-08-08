package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations

@CacheableTask
abstract class RenderPreviewsTask : DefaultTask() {

  /**
   * The task's project directory — the working directory a render runs in.
   *
   * `ExecOperations.javaexec` defaults to it, so a preview reading a relative path has always
   * resolved it against its own subproject. The worker pool starts its processes here for the same
   * reason; `@Internal` because it affects where a render *runs*, not what it produces, and making
   * it an input would key every module's cache on its own absolute path.
   */
  @get:org.gradle.api.tasks.Internal abstract val projectDirectory: DirectoryProperty

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
   * Preview-name filter (issue #2066). When non-empty, only previews whose simple or
   * package-qualified name matches an entry are rendered; everything else — including any unrelated
   * broken preview — is left untouched on disk and never scheduled. Empty (the default) renders
   * every discovered preview, the historical behaviour.
   *
   * Populated from the repeatable `--preview` task option (see [setPreviewFilterOption]) or, as a
   * convention, from the `composePreview.filter` Gradle property wired at registration. Matching
   * (glob `*`/`?` or substring, against simple + FQN) lives in [PreviewNameFilter]. `@Input` so a
   * filter change re-runs the render.
   */
  @get:Input abstract val previewFilters: ListProperty<String>

  /**
   * Backs the repeatable `--preview` CLI option. `List<String>` makes it repeatable (`--preview A
   * --preview B`); each value is a name or glob. Setting the option overrides the
   * `composePreview.filter` convention rather than merging with it, so the command line always
   * wins.
   */
  @Option(
    option = "preview",
    description =
      "Render only previews whose simple or fully-qualified name matches this pattern " +
        "(repeatable; supports '*'/'?' globs or a plain substring). No match fails the task. " +
        "Overrides -PcomposePreview.filter.",
  )
  fun setPreviewFilterOption(values: List<String>) {
    previewFilters.set(values)
  }

  /**
   * Preview **id** filter (issue #2966) — narrows the render to individual members of a `@Preview`
   * function's fan-out, which [previewFilters] cannot: a multipreview member / `@PreviewParameter`
   * row has its own `id` but shares its `functionName`. Applied AFTER the name filter, so the two
   * compose. Empty (the default) renders every preview the name filter kept.
   *
   * Populated from the repeatable `--preview-id` task option (see [setPreviewIdFilterOption]) or,
   * as a convention, from the `composePreview.idFilter` Gradle property wired at registration.
   * Matching (glob `*`/`?` or substring) lives in [PreviewNameFilter.matchesId]. `@Input` so a
   * filter change re-runs the render.
   *
   * **Applies to this desktop/JVM task**; the Android Robolectric render honours the same three
   * filters via its own path (issue #2977). On an Android module `composePreviewRender` is a
   * `RobolectricRenderTask` registered by [AndroidPreviewSupport] that forwards these filters
   * (sourced from the same `composePreview.*` property conventions) to the render JVM as
   * `composeai.preview.*` system properties, where `PreviewFilter` applies the same matching. So a
   * catalog's render-time saving from a filter now lands on both backends.
   */
  @get:Input abstract val previewIdFilters: ListProperty<String>

  /**
   * Backs the repeatable `--preview-id` CLI option. Setting it overrides the
   * `composePreview.idFilter` convention rather than merging with it, matching how `--preview`
   * relates to `composePreview.filter`.
   */
  @Option(
    option = "preview-id",
    description =
      "Render only previews whose discovered id matches this pattern (repeatable; supports " +
        "'*'/'?' globs or a plain substring). Selects individual members of a multipreview / " +
        "@PreviewParameter fan-out, which --preview cannot. Applied after --preview. No match " +
        "fails the task. Overrides -PcomposePreview.idFilter.",
  )
  fun setPreviewIdFilterOption(values: List<String>) {
    previewIdFilters.set(values)
  }

  /**
   * Preview **id** exclusions (issue #2966) — drops individual fan-out members, keeping everything
   * else. The polarity a *deferral* needs, and not interchangeable with [previewIdFilters]:
   *
   * A catalog that bakes one palette per component and defers the rest can't express that as a
   * positive filter, because the ids it wants are not a matchable set. `*_light` would keep the
   * light members but also drop every preview whose id carries no theme suffix at all — the
   * untagged primary stickers, i.e. most of the catalog. `--exclude-preview-id *_dark` says what it
   * means.
   *
   * Exclusion also fails safe under a stale spec: a pattern that matches nothing renders *more*
   * than intended (a wasted render, caught by the publish), where a positive filter that matches
   * nothing renders none. Applied after [previewIdFilters]. Empty (the default) excludes nothing.
   */
  @get:Input abstract val previewIdExcludes: ListProperty<String>

  /** Backs the repeatable `--exclude-preview-id` CLI option; overrides the property convention. */
  @Option(
    option = "exclude-preview-id",
    description =
      "Skip previews whose discovered id matches this pattern (repeatable; '*'/'?' globs or a " +
        "plain substring), rendering everything else. The polarity a deferred catalog palette " +
        "needs. Applied after --preview-id. Excluding every preview fails the task. Overrides " +
        "-PcomposePreview.idExclude.",
  )
  fun setPreviewIdExcludeOption(values: List<String>) {
    previewIdExcludes.set(values)
  }

  /**
   * `@PreviewParameter` **row** exclusions, by label — the one fan-out the id filters above cannot
   * reach.
   *
   * Discovery emits ONE `PreviewInfo` per parameterized function (it reads bytecode, so it can't
   * instantiate a provider to learn the values), and the rows only exist once the renderer has
   * enumerated them and `PreviewParameterLabels` has named each `<stem>_<label>.png`. So no id
   * pattern can name a row — which is why a design system whose theme axis is a `@PreviewParameter`
   * provider (nine palettes on one provider, the shape behind #2966's measurement) still rendered
   * every palette after `--exclude-preview-id` landed.
   *
   * Forwarded to the render subprocess as `composeai.preview.rowExclude` and applied by
   * `PreviewRowFilter`: exclusion polarity like the id filter, matched case-insensitively (a label
   * is user data — `"Dark"` — while the pattern is usually a spec's own spelling, `"dark"`), and
   * never allowed to empty a preview's row set. Empty (the default) renders every row.
   *
   * Desktop-only, like the filters above (see [previewIdFilters]); the Android/Robolectric renderer
   * expands its own rows and reads none of these — issue #2977.
   */
  @get:Input abstract val previewRowExcludes: ListProperty<String>

  /** Backs the repeatable `--exclude-preview-row` CLI option; overrides the property convention. */
  @Option(
    option = "exclude-preview-row",
    description =
      "Skip @PreviewParameter rows whose label matches this pattern (repeatable; '*'/'?' globs or " +
        "an exact label, case-insensitive), rendering the rest. Addresses one row of a " +
        "parameterized preview, which --exclude-preview-id cannot. Never empties a preview's rows. " +
        "Overrides -PcomposePreview.rowExclude.",
  )
  fun setPreviewRowExcludeOption(values: List<String>) {
    previewRowExcludes.set(values)
  }

  /**
   * Extra render fan-outs to synthesize from every discovered preview. `accessibility` adds dark,
   * RTL pseudolocale, and 2x font-scale siblings by rewriting the manifest entries before the
   * existing renderer path sees them; no new render capability is introduced.
   */
  @get:Input abstract val permutations: ListProperty<String>

  @Option(
    option = "permutations",
    description =
      "Render extra preview permutations. Currently supports 'accessibility' (dark, RTL, " +
        "fontscale-2x). Repeatable or comma-separated. Overrides -PcomposePreview.permutations.",
  )
  fun setPermutationsOption(values: List<String>) {
    permutations.set(values)
  }

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

  /**
   * Absolute path to the `java` binary the render subprocess forks into. Unset (default) means the
   * `javaexec` below runs on the Gradle daemon JVM — the historical behaviour. The plugin sets this
   * only when the module's bytecode target outruns that daemon JVM (or `composePreview
   * .renderJavaVersion` is pinned), raising the render fork to a JDK that can load the classes
   * instead of failing with `UnsupportedClassVersionError`. See [RenderJvmSelection]. `@Input` so a
   * JDK change re-renders; `@Optional` so the "no upgrade needed" path leaves it null.
   */
  @get:org.gradle.api.tasks.Optional @get:Input abstract val renderJavaExecutable: Property<String>

  init {
    // Explicit empty default so the desktop `composePreviewRender` registration (which never sets
    // `includeKinds`) has a configured value for this non-optional `@Input` rather than relying on
    // the managed-`SetProperty` implicit empty convention — keeps "render every kind" the default
    // and self-documents it.
    includeKinds.convention(emptySet())
    // Empty default = "render every preview". The plugin registration overrides this convention
    // with the `composePreview.filter` Gradle property; a `--preview` option overrides both.
    previewFilters.convention(emptyList())
    // Same story one axis down: empty = "render every preview the name filter kept". Overridden at
    // registration with the `composePreview.idFilter` property; `--preview-id` overrides both.
    previewIdFilters.convention(emptyList())
    previewIdExcludes.convention(emptyList())
    // And one axis further down again: empty = "render every row of every parameterized preview".
    // Overridden at registration with `composePreview.rowExclude`; `--exclude-preview-row` wins.
    previewRowExcludes.convention(emptyList())
    permutations.convention(emptyList())
    // Caching is intentionally gated on `tier=full` AND an empty `--preview` filter — a run is only
    // cacheable when its `outputDir` is the module's *complete* render set. A `tier=fast` run
    // writes
    // only the fast captures, so a build-cache restore from a fast snapshot would *wipe* the
    // previous full run's heavy outputs — exactly the stale images the interactive UI relies on. A
    // filtered `tier=full` run is likewise partial: it renders only the named previews and
    // deliberately leaves every other (possibly stale) PNG in place, so caching that mixed
    // directory
    // could store an unrelated stale `Bar.png` and later restore it on a clean checkout for the
    // same
    // filtered inputs (issue #2066 review). Up-to-date checks still apply, so a re-run with no
    // input
    // changes is a no-op and the renders directory stays as-is regardless of tier or filter.
    // An id filter is partial for exactly the same reason a name filter is — it renders a subset
    // and
    // leaves every other (possibly stale) PNG in place — so it disqualifies caching too. Missing
    // this
    // would let a one-palette catalog render be stored and later restored as if it were the
    // module's
    // complete set.
    // A row exclusion is the same kind of partial one level finer: the excluded rows' PNGs stay on
    // disk from whatever ran last, so `outputDir` again isn't this module's complete render set.
    outputs.cacheIf("composePreviewRender caches full, unfiltered runs only") {
      tier.get().equals("full", ignoreCase = true) &&
        previewFilters.getOrElse(emptyList()).none { it.isNotBlank() } &&
        previewIdFilters.getOrElse(emptyList()).none { it.isNotBlank() } &&
        previewIdExcludes.getOrElse(emptyList()).none { it.isNotBlank() } &&
        previewRowExcludes.getOrElse(emptyList()).none { it.isNotBlank() } &&
        !PreviewPermutations.expandsAccessibility(permutations.getOrElse(emptyList()))
    }
  }

  @TaskAction
  fun render() {
    val json = Json { ignoreUnknownKeys = true }
    val rawManifest = json.decodeFromString<PreviewManifest>(previewsJson.get().asFile.readText())

    // Name filter (issue #2066) — when `--preview` / `-PcomposePreview.filter` is set, narrow to
    // the
    // named previews FIRST, before tier/kind/catalog filtering. A non-empty filter that matches
    // nothing fails fast (listing available names) rather than silently rendering zero previews.
    // Filtered-out previews keep their PNGs on disk (protected by the raw-manifest fan-out guard
    // below), so an unrelated broken preview is never scheduled and can't poison a filtered run.
    val nameFiltered =
      selectNamedPreviews(rawManifest.previews, previewFilters.getOrElse(emptyList()))

    // Id filter (issue #2966) — narrows to individual fan-out members within the functions the name
    // filter kept, which is the granularity a catalog's per-theme `modePriority` needs to skip the
    // renders it isn't going to publish. Runs immediately after the name filter so both are applied
    // before tier/kind/catalog filtering, and so `--preview Foo --preview-id *_Light` composes.
    val idFiltered =
      excludePreviewIds(
        selectPreviewIds(nameFiltered, previewIdFilters.getOrElse(emptyList())),
        previewIdExcludes.getOrElse(emptyList()),
      )

    val permutationValues = permutations.getOrElse(emptyList())
    val permuted = PreviewPermutations.expand(idFiltered, permutationValues)

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
      if (!isFastTier) permuted
      else
        permuted.filter {
          val firstCost = it.captures.firstOrNull()?.cost ?: STATIC_COST
          !isHeavyCost(firstCost)
        }
    // Kind filter — when set, render only the named kinds (e.g. the Android Lottie-only pass).
    // Empty
    // keeps every kind.
    val kinds = includeKinds.getOrElse(emptySet())
    val kindFiltered =
      if (kinds.isEmpty()) tierFiltered else tierFiltered.filter { it.params.kind.name in kinds }
    // `CATALOG` / `THEME_CATALOG` / `WEAR_THEME_CATALOG` sheets are synthetic (no consumer
    // composable): a CATALOG carries its tokens as structured data (`params.catalogTokens`) and the
    // theme kinds render a canned specimen inside a `@ThemeCatalog` / `@WearThemeCatalog` provider
    // —
    // none is forwarded by this desktop path's flat positional-arg protocol, and their display
    // `functionName` ("Brand colours" / "Meshcore theme") isn't a real composable, so
    // `getDeclaredComposableMethod` would throw and sink `composePreviewRenderAll` on any
    // CMP/desktop module using them. All render on the Android backend today; desktop support is
    // tracked in #2135. Skip them here rather than crash.
    val previews = kindFiltered.filter {
      it.params.kind.name !in SYNTHETIC_CATALOG_KINDS_UNSUPPORTED_ON_DESKTOP
    }
    // Always rebuild from the filtered list now that the CATALOG skip applies unconditionally (the
    // old fast-path reused `rawManifest` verbatim, which would leave catalog entries in).
    val manifest = rawManifest.copy(previews = previews)

    if (manifest.previews.isEmpty()) {
      logger.lifecycle("No previews to render.")
      return
    }

    val outDir = outputDir.get().asFile
    outDir.mkdirs()

    val rawManifestForCleanup =
      rawManifest.copy(
        previews = PreviewPermutations.expand(rawManifest.previews, permutationValues)
      )
    renderWithCompose(manifest, rawManifestForCleanup, outDir)

    val tierTag =
      if (isFastTier) " (fast tier; ${idFiltered.size - manifest.previews.size} heavy skipped)"
      else ""
    logger.lifecycle("Rendered ${manifest.previews.size} preview(s)$tierTag")
  }

  private fun renderWithCompose(
    manifest: PreviewManifest,
    rawManifest: PreviewManifest,
    outDir: java.io.File,
  ) {
    // This path is only used for desktop rendering.
    // Android rendering uses a separate Test-type task (see ComposePreviewPlugin).
    val mainClass = "ee.schimke.composeai.renderer.DesktopRendererMainKt"

    // Data products (e.g. `@ScrollingPreview(modes = [LONG, GIF])` LONG/GIF outputs) land in
    // `<previews-dir>/data/<kind>/<id>.<ext>` instead of `renders/`. Each PreviewDataProduct's
    // `output` is `data/<kind>/<id>.<ext>` — relative to the previews root (sibling of `outDir`).
    // The `dataProductsDir` task output, when wired by the plugin, points at that same `data/`
    // directory so Gradle tracks the written artifacts for caching / up-to-date checks.
    val previewsRoot = outDir.parentFile

    // Every output file the manifest lays claim to, resolved the same way the render loops below
    // resolve theirs. Built from the RAW manifest — a tier/kind-filtered preview's files stay on
    // disk and must still be protected from a sibling's stale fan-out cleanup (issue #2193).
    val manifestOutputFiles =
      rawManifest.previews.flatMap { p ->
        p.captures.map { c ->
          if (c.renderOutput.isNotEmpty()) previewsRoot.resolve(c.renderOutput)
          else outDir.resolve("${p.id}.png")
        } + p.dataProducts.filter { it.output.isNotBlank() }.map { previewsRoot.resolve(it.output) }
      }

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

    // One warm renderer for this whole task execution, closed before the action returns so no
    // process outlives the build. Created here rather than held on the task so nothing
    // process-shaped is on a field the configuration cache would try to store.
    val lane = RenderLane(openWorkerPool())
    try {
      renderCaptures(manifest, manifestOutputFiles, previewsRoot, outDir, mainClass, lane)
    } finally {
      lane.pool?.let { pool ->
        pool.close()
        val warm = pool.servedWarm.get()
        if (warm > 0) {
          logger.lifecycle(
            "composePreviewRender: $warm capture(s) drawn on a warm renderer " +
              "(no per-capture JVM fork)."
          )
        }
      }
    }
  }

  private fun renderCaptures(
    manifest: PreviewManifest,
    manifestOutputFiles: List<java.io.File>,
    previewsRoot: java.io.File,
    outDir: java.io.File,
    mainClass: String,
    lane: RenderLane,
  ) {
    for (preview in manifest.previews) {
      val spec =
        DeviceDimensions.resolveForRender(
          device = preview.params.device,
          widthDp = preview.params.widthDp,
          heightDp = preview.params.heightDp,
          showSystemUi = preview.params.showSystemUi,
          wrapSandboxWidthDp = preview.params.wrapSandboxWidthDp,
          wrapSandboxHeightDp = preview.params.wrapSandboxHeightDp,
        )
      // Per-device density (= densityDpi / 160), so output bitmaps match
      // what Android Studio renders for the same `@Preview`. Source: the
      // same data sergio-sastre/ComposablePreviewScanner /
      // takahirom/roborazzi consume. Discovery pins `params.density` when
      // a device/showSystemUi frame applies; the wrap-content path leaves
      // it null and we fall back to `spec.density` (= DEFAULT_DENSITY).
      val density = preview.params.density ?: spec.density
      val isDeviceFrame = !preview.params.device.isNullOrBlank()
      val explicitWidthDp = preview.params.widthDp?.takeIf { !isDeviceFrame && it > 0 }
      val explicitHeightDp = preview.params.heightDp?.takeIf { !isDeviceFrame && it > 0 }
      val widthPx =
        if (!spec.wrapWidth && explicitWidthDp != null) {
          (spec.widthDp * density).roundHalfUpPx()
        } else {
          (spec.widthDp * density).toInt().coerceAtLeast(1)
        }
      val heightPx =
        if (!spec.wrapHeight && explicitHeightDp != null) {
          (spec.heightDp * density).roundHalfUpPx()
        } else {
          (spec.heightDp * density).toInt().coerceAtLeast(1)
        }

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
          fanoutSiblingStems = fanoutSiblingStems(manifestOutputFiles, outputFile),
          lane = lane,
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
          fanoutSiblingStems = fanoutSiblingStems(manifestOutputFiles, outputFile),
          lane = lane,
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
    fanoutSiblingStems: List<String> = emptyList(),
    lane: RenderLane,
  ) {
    val rendererArgs =
      rendererArgs(
        preview = preview,
        spec = spec,
        density = density,
        widthPx = widthPx,
        heightPx = heightPx,
        outputFile = outputFile,
        scroll = scroll,
        animation = animation,
        fanoutSiblingStems = fanoutSiblingStems,
      )
    val overridesSeed =
      preview.overrides?.let { OVERRIDES_JSON.encodeToString(OverrideVariantSpec.serializer(), it) }

    // Warm path: a pooled worker draws this on an already-booted JVM instead of paying JVM +
    // Compose Desktop + Skiko startup again for one capture. It calls the renderer's own `main()`,
    // so a pooled capture runs identical code to a forked one. Only `Unusable` falls through to the
    // fork below — a `Failed` is the renderer's real answer about this capture, and re-running it
    // cold would double the cost of every capture that cannot be drawn.
    lane.pool?.let { pool ->
      when (val pooled = pool.render(rendererArgs, overridesSeed)) {
        is DesktopRenderWorkerPool.WorkerResult.Ok -> return
        is DesktopRenderWorkerPool.WorkerResult.Failed ->
          throw GradleException(
            "composePreviewRender: ${preview.className}#${preview.functionName} — ${pooled.reason}"
          )
        is DesktopRenderWorkerPool.WorkerResult.Unusable -> {
          if (lane.fallbackNoticePrinted.compareAndSet(false, true)) {
            logger.info(
              "composePreviewRender: render worker unavailable (${pooled.reason}); " +
                "forking per capture as before."
            )
          }
        }
      }
    }

    execOperations.javaexec {
      // Fork on a JDK new enough for the consumer's bytecode when the plugin raised it (see
      // [RenderJvmSelection]); otherwise leave the default (Gradle daemon JVM).
      renderJavaExecutable.orNull?.let { executable = it }
      classpath = renderClasspath
      this.mainClass.set(mainClass)
      // Run the render JVM as a macOS "background agent" (LSUIElement) so it never claims a Dock
      // icon or steals keyboard focus while capturing. DesktopRendererMain draws offscreen via
      // ImageComposeScene and never opens a window, but any non-headless AWT/Skiko init still
      // registers a Dock tile + focus grab on macOS. Setting it here on the JavaExec spec forwards
      // it as a `-D` on the forked JVM command line, i.e. *before* AWT initializes (a
      // `System.setProperty` inside main() is too late once Skiko touches the toolkit). Ignored on
      // Linux/Windows, so it's safe to set unconditionally. Headless=true would be stronger but can
      // break Skiko font/graphics init, so scope this to the focus/Dock symptom only.
      systemProperty("apple.awt.UIElement", "true")
      // Forward the display-filter selection so DesktopRendererMain can call
      // DisplayFilterDataProducer.writeArtifacts after each render. Empty string is fine —
      // DisplayFilterConfig.parseFilters treats blank input as "feature disabled".
      systemProperty("composeai.displayfilter.filters", displayFilterFilters.get())
      // Forward the device-frame selection + the prefetch cache dir so DesktopRendererMain can
      // composite the render into a device-art bezel (reading the cache the task action filled).
      // Empty string disables it (DeviceFrameConfig treats blank as "off").
      systemProperty("composeai.deviceframe.device", deviceFrameDevice.get())
      // Forward the `@PreviewParameter` row exclusions so `PreviewRowFilter` can drop fan-out
      // members
      // by label — the rows don't exist until the subprocess has enumerated the provider, so this
      // is
      // the only place the filter can be applied. Set only when non-empty, keeping the render JVM's
      // command line (and therefore every unfiltered run's inputs) exactly as it was.
      previewRowExcludes
        .getOrElse(emptyList())
        .filter { it.isNotBlank() }
        .let { rows ->
          if (rows.isNotEmpty()) {
            systemProperty("composeai.preview.rowExclude", rows.joinToString(","))
          }
        }
      if (deviceFrameDevice.get().isNotBlank()) {
        systemProperty(
          "composeai.deviceframe.cacheDir",
          DeviceArtPrefetch.defaultCacheDir().absolutePath,
        )
      }
      // Forward this preview's `@OverrideVariant` seeds (a synthetic variant preview carries a
      // non-null `overrides`) as JSON so DesktopRendererMain can seed `PreviewOverrideController`
      // before composing — the desktop counterpart of the Android renderer's per-preview seed. A
      // per-render system property (not a positional arg) keeps it clear of the size-bound arg
      // tail.
      // Absent/blank ⇒ an ordinary preview whose `previewOverride*` reads resolve to their
      // defaults.
      preview.overrides?.let {
        systemProperty(
          "composeai.overrides.seed",
          OVERRIDES_JSON.encodeToString(OverrideVariantSpec.serializer(), it),
        )
      }
      args = rendererArgs
    }
  }

  /**
   * The warm renderer for one task execution, plus the one-shot notice flag so a pool that cannot
   * serve says so once rather than per capture. Passed down the render loop rather than held on the
   * task: a live pool on a task field is exactly the kind of state the configuration cache cannot
   * store.
   */
  private class RenderLane(val pool: DesktopRenderWorkerPool?) {
    val fallbackNoticePrinted = java.util.concurrent.atomic.AtomicBoolean(false)
  }

  /**
   * Spin up the render worker pool for this execution, or null to keep forking per capture.
   *
   * Null whenever the pool cannot be trusted to behave exactly like the fork it replaces: switched
   * off explicitly, or a renderer classpath that could not be resolved. Everything else — a worker
   * that fails to start or speaks the wrong protocol — is handled per capture inside the pool,
   * which reports `Unusable` and lets the caller fork that one.
   */
  private fun openWorkerPool(): DesktopRenderWorkerPool? {
    if (!DesktopRenderWorkerPool.isEnabled()) return null
    val cp = renderClasspath.files.toList()
    if (cp.isEmpty()) return null
    // A registration that never wired the project directory forks rather than guessing one: a
    // worker started in the wrong directory would resolve a preview's relative paths differently
    // from the lane it replaces, which is worse than not pooling.
    val workingDir = projectDirectory.orNull?.asFile ?: return null
    return DesktopRenderWorkerPool(
      classpath = cp,
      javaExecutable = renderJavaExecutable.orNull ?: defaultJavaExecutable(),
      jvmArgs = workerJvmArgs(),
      maxWorkers = DesktopRenderWorkerPool.configuredWorkers(),
      maxRendersPerWorker = DesktopRenderWorkerPool.configuredMaxRenders(),
      renderTimeoutSeconds = DesktopRenderWorkerPool.RENDER_GUARD_SECONDS,
      // `javaexec` defaulted the working directory to the task's project, so a preview reading a
      // relative path resolved it against that subproject. A worker must start there too, or the
      // warm and forked lanes would disagree about what `File("src/main/resources/…")` means.
      workingDir = workingDir,
      // The forked lane let the renderer's diagnostics through to the build log. Most of them ride
      // a *successful* request — a missing `@PreviewParameter` provider, a device-frame failure,
      // the `Render failed …` line beside an error sidecar — so without this they would vanish the
      // moment a capture went warm.
      stderrSink = { line -> logger.lifecycle("composePreviewRender: $line") },
    )
  }

  private fun defaultJavaExecutable(): String {
    val candidate = java.io.File(System.getProperty("java.home"), "bin/java")
    return if (candidate.canExecute()) candidate.absolutePath else "java"
  }

  /**
   * The `-D` flags a worker boots under — the per-execution half of what the per-capture
   * `javaexec` sets inline.
   *
   * Deliberately the same set, and deliberately *only* the constant ones: every property here is
   * fixed for the whole task, so it can live on the worker's command line. The one genuinely
   * per-capture property (`composeai.overrides.seed`) rides the request frame instead, because a
   * worker that inherited one preview's variant seed would draw the next preview with it.
   */
  private fun workerJvmArgs(): List<String> = buildList {
    add("-Dapple.awt.UIElement=true")
    add("-Dcomposeai.displayfilter.filters=${displayFilterFilters.get()}")
    add("-Dcomposeai.deviceframe.device=${deviceFrameDevice.get()}")
    if (deviceFrameDevice.get().isNotBlank()) {
      add("-Dcomposeai.deviceframe.cacheDir=${DeviceArtPrefetch.defaultCacheDir().absolutePath}")
    }
    previewRowExcludes
      .getOrElse(emptyList())
      .filter { it.isNotBlank() }
      .let { rows ->
        if (rows.isNotEmpty()) {
          add("-Dcomposeai.preview.rowExclude=${rows.joinToString(",")}")
        }
      }
  }

  /**
   * The renderer's positional argv for one capture — the single source of truth for both the pooled
   * worker and the per-capture fork, so the two lanes can never drift into passing different
   * arguments for the same preview.
   */
  private fun rendererArgs(
    preview: PreviewInfo,
    spec: DeviceDimensions.SizeSpec,
    density: Float,
    widthPx: Int,
    heightPx: Int,
    outputFile: java.io.File,
    scroll: ScrollCapture?,
    animation: AnimationCapture?,
    fanoutSiblingStems: List<String>,
  ): List<String> =
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
          // 25th–27th — `@AnimatedPreview` window. `-1` durationMs signals "no animation intent"
          // (the renderer falls through to scroll / single-frame). `>= 0` means the annotation is
          // present and dispatches to `renderAnimatedPreview` (a `runSkikoComposeUiTest`
          // paused-clock loop that advances `mainClock` by frameIntervalMs across the window and
          // encodes the frames as a GIF) — the desktop counterpart of the Android renderer's
          // `@AnimatedPreview` path. `0` is the annotation's auto-detect sentinel and must NOT be
          // collapsed into "no animation": a default-args `@AnimatedPreview` still needs the
          // animated path or the `.gif` renderOutput gets a single PNG frame (issue #2190). An
          // older renderer that predates the `-1` protocol parses it via `takeIf { it > 0 } ?: 0`,
          // so the sentinel degrades to the old "no animation" behaviour rather than breaking. The
          // reverse skew (an older plugin driving a newer renderer pinned on the
          // `composePreviewRenderer` configuration) is guarded renderer-side: a bare `0` is only
          // read as auto-detect when the capture is animation-shaped (a `.gif` output with no
          // scroll intent).
          // `showCurves` is forwarded for parity; the desktop path emits a screenshot-only GIF (no
          // curve strip).
          (animation?.durationMs ?: -1).toString(),
          (animation?.frameIntervalMs ?: 0).toString(),
          (animation?.showCurves ?: false).toString(),
          // 28th — sibling stems the renderer's `@PreviewParameter` stale fan-out cleanup must
          // leave alone (issue #2193): manifest outputs in the same directory whose stem extends
          // this capture's (`Foo` vs the `@Preview(name = "Dark")` sibling's `Foo_Dark`). The
          // subprocess has no manifest, so without this it treats every `<stem>_*` file as its
          // own fan-out and deletes the sibling's renders. Empty string signals "no siblings".
          fanoutSiblingStems.joinToString("|"),
        )
}

private fun Float.roundHalfUpPx(): Int = kotlin.math.floor(this + 0.5f).toInt().coerceAtLeast(1)

/** JSON used to (de)serialise `@OverrideVariant` seeds across the desktop renderer boundary. */
private val OVERRIDES_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** How many available preview names to list in a no-match `--preview` error before truncating. */
private const val MAX_SUGGESTED_PREVIEW_NAMES = 20

/**
 * Synthetic catalog kinds the **desktop** render path can't drive: they have no consumer composable
 * to reflect, so the flat positional-arg protocol has nothing to invoke. Compared by name because
 * this task sees the manifest's kind as a string. Android renders all of them; see #2135.
 */
private val SYNTHETIC_CATALOG_KINDS_UNSUPPORTED_ON_DESKTOP =
  setOf("CATALOG", "THEME_CATALOG", "WEAR_THEME_CATALOG")

/**
 * Narrows [previews] to those matching [filters] (issue #2066). An empty/blank filter returns the
 * list unchanged ("render every preview"). A non-empty filter that matches nothing throws a
 * [GradleException] listing the available preview names — a filtered run that would render zero
 * previews is a user error (typo / wrong module), not a silent no-op. Matching semantics live in
 * [PreviewNameFilter]; this function owns only the select-or-fail policy so it's unit-testable
 * without a Gradle task instance.
 */
internal fun selectNamedPreviews(
  previews: List<PreviewInfo>,
  filters: List<String>,
): List<PreviewInfo> {
  val cleaned = filters.map(String::trim).filter(String::isNotEmpty)
  if (cleaned.isEmpty()) return previews

  val matched = previews.filter {
    PreviewNameFilter.matches(cleaned, it.functionName, it.className)
  }
  if (matched.isNotEmpty()) return matched

  val available =
    previews.map { PreviewNameFilter.fqName(it.className, it.functionName) }.distinct().sorted()
  throw GradleException(
    buildString {
      append("composePreviewRender --preview matched no previews for ")
      append(cleaned.joinToString(", ") { "'$it'" })
      append(".")
      if (available.isEmpty()) {
        append(" This module has no discovered previews — run composePreviewDiscover to confirm.")
      } else {
        append(" Available previews:")
        available.take(MAX_SUGGESTED_PREVIEW_NAMES).forEach { append("\n  ").append(it) }
        val more = available.size - MAX_SUGGESTED_PREVIEW_NAMES
        if (more > 0) {
          append("\n  … and ")
          append(more)
          append(" more (run composePreviewDiscover for the full list).")
        }
      }
    }
  )
}

/**
 * Narrows [previews] to those whose **id** matches [filters] (issue #2966) — the per-fan-out-member
 * counterpart of [selectNamedPreviews].
 *
 * Exists because the name filter can't reach inside a `@Preview` function. A multipreview member or
 * a `@PreviewParameter` row is its own [PreviewInfo] with a distinct `id` (`FilledButton_Light` /
 * `FilledButton_Dark`) but the same `functionName`, so a name filter keeps or drops all of them
 * together. A design catalog that bakes one palette per component and leaves the rest to the live
 * preview server (`modePriority` in `catalog.spec.json`) needs exactly this granularity to skip the
 * renders it isn't publishing — without it that deferral shrinks the published bundle but not the
 * build.
 *
 * Same select-or-fail policy as [selectNamedPreviews], and for the same reason: a filter that
 * matches nothing is a typo or a stale spec, and rendering zero previews silently would surface
 * much later as a bundle full of missing stickers. Both filters compose — the name filter runs
 * first, so `--preview Foo --preview-id *_Light` means "Foo's light member".
 */
internal fun selectPreviewIds(
  previews: List<PreviewInfo>,
  filters: List<String>,
): List<PreviewInfo> {
  val cleaned = filters.map(String::trim).filter(String::isNotEmpty)
  if (cleaned.isEmpty()) return previews

  val matched = previews.filter { PreviewNameFilter.matchesId(cleaned, it.id) }
  if (matched.isNotEmpty()) return matched

  val available = previews.map { it.id }.distinct().sorted()
  throw GradleException(
    buildString {
      append("composePreviewRender --preview-id matched no previews for ")
      append(cleaned.joinToString(", ") { "'$it'" })
      append(".")
      if (available.isEmpty()) {
        append(" This module has no discovered previews — run composePreviewDiscover to confirm.")
      } else {
        append(" Available preview ids:")
        available.take(MAX_SUGGESTED_PREVIEW_NAMES).forEach { append("\n  ").append(it) }
        val more = available.size - MAX_SUGGESTED_PREVIEW_NAMES
        if (more > 0) {
          append("\n  … and ")
          append(more)
          append(" more (run composePreviewDiscover for the full list).")
        }
      }
    }
  )
}

/**
 * Drops previews whose **id** matches [excludes], keeping the rest (issue #2966).
 *
 * The deferral polarity — see [RenderPreviewsTask.previewIdExcludes] for why a positive filter
 * can't express "bake one palette per component, defer the others": the ids to keep aren't a
 * matchable set, because the untagged primary stickers carry no theme suffix to match on.
 *
 * A pattern matching nothing is a no-op on purpose (it renders more than intended, which the
 * publish catches, rather than less). Excluding *everything* still throws: the render would write
 * no PNGs at all and the pack that follows would produce a catalog of missing stickers, which is
 * precisely the silent failure the select-or-fail policy exists to prevent.
 */
internal fun excludePreviewIds(
  previews: List<PreviewInfo>,
  excludes: List<String>,
): List<PreviewInfo> {
  val cleaned = excludes.map(String::trim).filter(String::isNotEmpty)
  if (cleaned.isEmpty() || previews.isEmpty()) return previews

  val kept = previews.filterNot { PreviewNameFilter.matchesId(cleaned, it.id) }
  if (kept.isNotEmpty()) return kept

  throw GradleException(
    "composePreviewRender --exclude-preview-id excluded every one of the ${previews.size} " +
      "preview(s) for ${cleaned.joinToString(", ") { "'$it'" }} — nothing would render. Narrow the " +
      "pattern; a render with no outputs packs a bundle of missing stickers."
  )
}

/**
 * Stems (filenames without extension) of manifest outputs in the same directory as [outputFile],
 * with the same extension, whose name extends [outputFile]'s stem with an underscore — exactly the
 * files the desktop renderer's prefix-greedy `deleteStaleFanoutFiles` would otherwise mistake for
 * its own `@PreviewParameter` fan-out (issue #2193). `@Preview(name = "Dark")` on `Foo` yields the
 * sibling stem `Foo_Dark`; both its base PNG and its own fan-out (`Foo_Dark_<label>.png`) match
 * `Foo_*`.
 *
 * The same-extension restriction matters in both directions: the cleanup only scans files with
 * [outputFile]'s extension, so a different-extension sibling (`Foo_Dark.gif`) needs no protection —
 * and shielding its stem anyway would keep a genuinely stale `Foo_Dark.png`, left from before that
 * sibling's capture became a GIF, on disk forever.
 *
 * Joined with `|` on the renderer command line — discovery's `sanitizeForPath` strips `|` from
 * every stem, so the separator can't collide.
 */
internal fun fanoutSiblingStems(
  manifestOutputFiles: List<java.io.File>,
  outputFile: java.io.File,
): List<String> {
  val prefix = outputFile.nameWithoutExtension + "_"
  return manifestOutputFiles
    .filter {
      it.parentFile == outputFile.parentFile &&
        it != outputFile &&
        it.extension == outputFile.extension
    }
    .map { it.nameWithoutExtension }
    .filter { it.startsWith(prefix) }
    .distinct()
    .sorted()
}
