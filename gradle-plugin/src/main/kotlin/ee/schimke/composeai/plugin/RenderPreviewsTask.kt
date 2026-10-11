package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
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
   * The project directory renders run in, matching `javaexec`'s default so relative paths resolve
   * against the subproject. `@Internal`: it affects where a render runs, not what it produces.
   */
  @get:org.gradle.api.tasks.Internal abstract val projectDirectory: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val previewsJson: RegularFileProperty

  @get:Input abstract val renderBackend: Property<String>

  /**
   * Restricts rendering to these `params.kind` names; empty renders every kind. Used for Android's
   * Lottie/SVG-only desktop passes (Robolectric skips those kinds).
   */
  @get:Input abstract val includeKinds: org.gradle.api.provider.SetProperty<String>

  /**
   * Preview-name filter (#2066): only previews whose simple or qualified name matches are rendered;
   * others are untouched. Empty renders everything. From `--preview` ([setPreviewFilterOption]) or
   * the `composePreview.filter` convention; matching lives in [PreviewNameFilter].
   */
  @get:Input abstract val previewFilters: ListProperty<String>

  /**
   * Repeatable `--preview` option; overrides (not merges with) the `composePreview.filter`
   * convention.
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
   * Preview id filter (#2966): selects individual fan-out members, which share a `functionName`.
   * Applied after the name filter. From `--preview-id` ([setPreviewIdFilterOption]) or
   * `composePreview.idFilter`; matching is [PreviewNameFilter.matchesId]. The Android render
   * applies the same filters via system properties (#2977).
   */
  @get:Input abstract val previewIdFilters: ListProperty<String>

  /** Repeatable `--preview-id` option; overrides the `composePreview.idFilter` convention. */
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
   * Preview id exclusions (#2966). A deferral needs this polarity: untagged primary stickers have
   * no suffix a positive filter could keep. Fails safe — a non-matching pattern renders more, not
   * less. Applied after [previewIdFilters].
   */
  @get:Input abstract val previewIdExcludes: ListProperty<String>

  /** Backs the repeatable `--exclude-preview-id` CLI option; overrides the property convention. */
  @Option(
    option = "exclude-preview-id",
    description =
      "Skip previews whose discovered id matches this pattern (repeatable; '*'/'?' globs, a " +
        "plain substring, or '=<id>' for an exact match), rendering everything else. Use '=' for " +
        "a generated list: ids are hierarchical, so a plain base id also drops its variants. The " +
        "polarity a deferred catalog palette needs. Applied after --preview-id. Excluding every " +
        "preview fails the task. Overrides -PcomposePreview.idExclude.",
  )
  fun setPreviewIdExcludeOption(values: List<String>) {
    previewIdExcludes.set(values)
  }

  /**
   * `@PreviewParameter` row exclusions by label. Discovery emits one entry per parameterized
   * function, and rows only exist after the renderer enumerates the provider, so id patterns can't
   * name them.
   *
   * Forwarded as `composeai.preview.rowExclude` to `PreviewRowFilter`: case-insensitive, and never
   * allowed to empty a preview's rows. Desktop-only; Android expands its own rows (#2977).
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
   * Fan-outs synthesized from every preview: `accessibility` adds dark, RTL pseudolocale and 2x
   * font-scale siblings by rewriting manifest entries.
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
   * `"fast"` skips previews whose representative capture exceeds [HEAVY_COST_THRESHOLD]; `"full"`
   * (default) renders everything.
   */
  @get:Input abstract val tier: Property<String>

  /**
   * `composeai.displayfilter.filters` for the renderer subprocess; empty disables. See
   * [AndroidPreviewSupport.resolveDisplayFilterFilters].
   */
  @get:Input abstract val displayFilterFilters: Property<String>

  /**
   * `composeai.deviceframe.device` for the renderer subprocess; empty disables. See
   * [AndroidPreviewSupport.resolveDeviceFrameDevice].
   */
  @get:Input abstract val deviceFrameDevice: Property<String>

  /**
   * `"true"` renders with the link-buffer `SlotTable` composer (see [composeAiLinkBufferComposer]).
   * An `@Input` so switching composers re-renders.
   */
  @get:Input abstract val linkBufferComposer: Property<String>

  @get:Classpath abstract val renderClasspath: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  /**
   * Data-products output (`build/compose-previews/data/...`) for heavyweight artifacts such as
   * scrolling LONG/GIF. Optional; defaults to a sibling of [outputDir].
   */
  @get:org.gradle.api.tasks.Optional
  @get:OutputDirectory
  abstract val dataProductsDir: DirectoryProperty

  @get:Inject abstract val execOperations: ExecOperations

  /**
   * `java` binary for the render subprocess; unset uses the Gradle JVM. Set only when the bytecode
   * target needs a newer JDK (or `renderJavaVersion` is pinned). See [RenderJvmSelection].
   */
  @get:org.gradle.api.tasks.Optional @get:Input abstract val renderJavaExecutable: Property<String>

  /**
   * The `composeai.render.nativeEnv` mode ([RenderNativeEnv.SYS_PROP_MODE]) as an input, so a
   * failed run under `inherit` (which still writes error sidecars) isn't kept UP-TO-DATE after the
   * override is dropped. `LD_LIBRARY_PATH` itself isn't an input: it differs per machine and would
   * defeat cache sharing.
   */
  @get:org.gradle.api.tasks.Optional @get:Input abstract val nativeEnvMode: Property<String>

  @get:Inject protected abstract val providerFactory: org.gradle.api.provider.ProviderFactory

  init {
    // Conventioned here so no registration site forgets it.
    nativeEnvMode.convention(providerFactory.systemProperty(RenderNativeEnv.SYS_PROP_MODE))
    // Explicit empty default: render every kind.
    includeKinds.convention(emptySet())
    // Empty renders every preview; overridden by `composePreview.filter`, then `--preview`.
    previewFilters.convention(emptyList())
    // Same for the id filter.
    previewIdFilters.convention(emptyList())
    previewIdExcludes.convention(emptyList())
    // Same for row exclusions.
    previewRowExcludes.convention(emptyList())
    permutations.convention(emptyList())
    // Cacheable only when `outputDir` will be the module's complete render set: a `tier=fast`,
    // name/id-filtered, or row-excluded run leaves other PNGs stale in place, and caching it could
    // later restore stale renders or wipe heavy outputs (#2066). Up-to-date checks still apply.
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

    // Name filter first (#2066); a non-empty filter matching nothing fails fast. Filtered-out PNGs
    // are protected below, so unrelated broken previews are never scheduled.
    val nameFiltered =
      selectNamedPreviews(
        rawManifest.previews,
        previewFilters.getOrElse(emptyList()),
        previewsJson.get().asFile.absolutePath,
      )

    // Id filter (#2966), right after the name filter so the two compose.
    val idSelected =
      selectPreviewIds(
        nameFiltered,
        previewIdFilters.getOrElse(emptyList()),
        previewsJson.get().asFile.absolutePath,
        rawManifest.previews,
      )
    // Report what each exclusion pattern matched before rendering (#5064): a zero-match pattern is
    // almost always a mistake, and the counts expose partial exclusions.
    val exclusionMatches =
      previewIdExclusionMatches(idSelected, previewIdExcludes.getOrElse(emptyList()))
    for (match in exclusionMatches) {
      // `warn` for a zero match: it reports a probable mistake.
      if (match.matched == 0) logger.warn("composePreviewRender: ${match.line}")
      else logger.lifecycle("composePreviewRender: ${match.line}")
    }
    val idFiltered = excludePreviewIds(idSelected, previewIdExcludes.getOrElse(emptyList()))

    val permutationValues = permutations.getOrElse(emptyList())
    val permuted = PreviewPermutations.expand(idFiltered, permutationValues)

    // Fast tier: desktop renders one capture per preview, so the decision is per preview. Skipped
    // previews keep their old PNG for VS Code to show as stale.
    val isFastTier = tier.get().equals("fast", ignoreCase = true)
    val tierFiltered =
      if (!isFastTier) permuted
      else
        permuted.filter {
          val firstCost = it.captures.firstOrNull()?.cost ?: STATIC_COST
          !isHeavyCost(firstCost)
        }
    val kinds = includeKinds.getOrElse(emptySet())
    val kindFiltered =
      if (kinds.isEmpty()) tierFiltered else tierFiltered.filter { it.params.kind.name in kinds }
    // Synthetic catalog sheets have no composable to reflect, and the positional-arg protocol can't
    // carry their data; desktop support is #2135. Skip rather than crash.
    val previews = kindFiltered.filter {
      it.params.kind.name !in SYNTHETIC_CATALOG_KINDS_UNSUPPORTED_ON_DESKTOP
    }
    // Always rebuild from the filtered list so catalog entries are dropped.
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
    // Desktop only; Android uses a Test task.
    val mainClass = "ee.schimke.composeai.renderer.DesktopRendererMainKt"

    // Data products land in `<previews-dir>/data/<kind>/<id>.<ext>`, relative to the previews root.
    val previewsRoot = outDir.parentFile

    // Every output the RAW manifest claims, so filtered-out previews' files are protected from a
    // sibling's fan-out cleanup (#2193).
    val manifestOutputFiles =
      rawManifest.previews.flatMap { p ->
        p.captures.map { c ->
          if (c.renderOutput.isNotEmpty()) previewsRoot.resolve(c.renderOutput)
          else outDir.resolve("${p.id}.png")
        } + p.dataProducts.filter { it.output.isNotBlank() }.map { previewsRoot.resolve(it.output) }
      }

    // Prefetch bezels in the Gradle JVM; subprocesses only read the cache. See DeviceArtPrefetch.
    val frameDevice = deviceFrameDevice.get()
    if (frameDevice.isNotBlank()) {
      DeviceArtPrefetch.prefetchInto(
        cacheDir = DeviceArtPrefetch.defaultCacheDir(),
        artIds = DeviceArtPrefetch.artIdsFor(frameDevice),
        logger = logger,
      )
    }

    // Decided once so pooled and forked lanes agree (see [RenderNativeEnv]).
    val nativeEnv = renderNativeEnv()

    // One warm renderer per execution, closed before returning; a local so the configuration cache
    // never sees it.
    val lane = RenderLane(openWorkerPool(nativeEnv), nativeEnv)
    val attempted = mutableListOf<java.io.File>()
    try {
      renderCaptures(
        manifest,
        manifestOutputFiles,
        previewsRoot,
        outDir,
        mainClass,
        lane,
        attempted,
      )
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
    failIfNothingWasWritten(attempted)
  }

  /**
   * Fail when no scheduled capture produced a file. The renderer swallows per-preview failures
   * (reported via `.error.json`), so a total failure otherwise looks like success — e.g. a skiko
   * API change published an empty sticker sheet (#4190). One broken preview is normal; none
   * surviving is an environment or classpath fault.
   */
  private fun failIfNothingWasWritten(attempted: List<java.io.File>) {
    emptyRunFailure(attempted)?.let { throw GradleException(it) }
  }

  internal companion object {
    /** The failure message for an execution that wrote nothing, or null when it wrote something. */
    internal fun emptyRunFailure(attempted: List<java.io.File>): String? {
      if (attempted.isEmpty()) return null
      if (attempted.any { it.isFile && it.length() > 0L }) return null
      // Fan-outs (e.g. per theme) write `<stem>_<suffix>` siblings rather than the scheduled path,
      // so a stem match counts: the question is whether anything was produced.
      if (attempted.any { candidate -> hasSuffixedSibling(candidate) }) return null
      return "composePreviewRender: ${attempted.size} capture(s) were drawn and none produced a " +
        "file. That is a renderer or classpath fault rather than a broken preview — check the " +
        "`Render failed for …` lines above and the `.error.json` sidecars beside the expected " +
        "outputs, and `validateComposePreviewDesktopRenderClasspath` for a version skew. " +
        "Expected e.g. ${attempted.first().absolutePath}"
    }

    /** True when [candidate]'s directory has a non-empty `<stem>_<suffix>` file. */
    private fun hasSuffixedSibling(candidate: java.io.File): Boolean {
      val stem = candidate.nameWithoutExtension
      val extension = candidate.extension
      val siblings = candidate.parentFile?.listFiles() ?: return false
      return siblings.any {
        it.isFile &&
          it.length() > 0L &&
          it.extension == extension &&
          it.nameWithoutExtension.startsWith("${stem}_")
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
    attempted: MutableList<java.io.File>,
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
      // Per-device density (densityDpi / 160) to match Studio; discovery pins it for device frames,
      // else `spec.density`.
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

      // Render every primary capture (scroll TOP/END, time fan-out, focus indices each have their
      // own path).
      for (capture in preview.captures) {
        // Relative to the compose-previews root, so tasks with a sibling `outputDir` (e.g.
        // `lottie-renders/`) can write there.
        val outputFile =
          if (capture.renderOutput.isNotEmpty()) previewsRoot.resolve(capture.renderOutput)
          else outDir.resolve("${preview.id}.png")
        attempted += outputFile
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
          interaction = capture.interaction,
          focus = capture.focus,
          hover = capture.hover,
          drag = capture.drag,
          settle = capture.settle,
          fanoutSiblingStems = fanoutSiblingStems(manifestOutputFiles, outputFile),
          lane = lane,
        )
      }

      // Data products (scrolling LONG/GIF), rendered with the same args; the renderer dispatches on
      // `scrollMode`.
      for (product in preview.dataProducts) {
        if (product.scroll == null) continue
        if (product.output.isBlank()) continue
        val outputFile = previewsRoot.resolve(product.output)
        attempted += outputFile
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
    interaction: ee.schimke.composeai.discovery.InteractionCapture? = null,
    focus: FocusCapture? = null,
    hover: ee.schimke.composeai.discovery.HoverCapture? = null,
    drag: ee.schimke.composeai.discovery.DragCapture? = null,
    settle: ee.schimke.composeai.discovery.SettleCapture? = null,
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
        interaction = interaction,
        focus = focus,
        hover = hover,
        drag = drag,
        settle = settle,
        fanoutSiblingStems = fanoutSiblingStems,
      )
    val overridesSeed =
      preview.overrides?.let { OVERRIDES_JSON.encodeToString(OverrideVariantSpec.serializer(), it) }
    // The preview's knob parameters (`previews.json`'s `knobs`, unchanged), for the knob sidecar
    // and `@OverrideVariant` binding. Desktop has no manifest to read them from, so they ride per
    // capture.
    val knobsPayload =
      preview.knobs
        .takeIf { it.isNotEmpty() }
        ?.let { OVERRIDES_JSON.encodeToString(ListSerializer(PreviewKnob.serializer()), it) }

    // Warm path: a pooled worker runs the renderer's own `main()` on a booted JVM. Only `Unusable`
    // falls back to forking; `Failed` is a real answer and re-running cold would double its cost.
    lane.pool?.let { pool ->
      when (val pooled = pool.render(rendererArgs, overridesSeed, knobsPayload)) {
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
      // Raised JDK when needed ([RenderJvmSelection]), else the Gradle JVM.
      renderJavaExecutable.orNull?.let { executable = it }
      // Same environment as pooled workers (see [RenderNativeEnv]).
      RenderNativeEnv.rewritten(lane.nativeEnv, environment)?.let { environment = it }
      classpath = renderClasspath
      this.mainClass.set(mainClass)
      // macOS background agent (LSUIElement) so renders never take a Dock icon or focus. Must be a
      // launch `-D`, before AWT initializes; ignored elsewhere. Not headless, which can break
      // Skiko.
      systemProperty("apple.awt.UIElement", "true")
      // Display filters; blank disables.
      systemProperty("composeai.displayfilter.filters", displayFilterFilters.get())
      // Device frame and prefetch cache; blank disables.
      systemProperty("composeai.deviceframe.device", deviceFrameDevice.get())
      // Must be a launch property: the runtime latches it at first composition.
      systemProperty("composeai.render.linkBufferComposer", linkBufferComposer.get())
      // Row exclusions, applied in the subprocess after enumeration. Only set when non-empty,
      // keeping unfiltered command lines unchanged.
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
      // `@OverrideVariant` seeds as JSON for `PreviewOverrideController`; a system property keeps
      // it out of the positional args.
      preview.overrides?.let {
        systemProperty(
          "composeai.overrides.seed",
          OVERRIDES_JSON.encodeToString(OverrideVariantSpec.serializer(), it),
        )
      }
      // Set only when the preview declares knobs.
      knobsPayload?.let { systemProperty("composeai.preview.knobs", it) }
      args = rendererArgs
    }
  }

  /**
   * The warm renderer for one execution, plus a once-only notice flag. Passed down rather than
   * stored on the task, which the configuration cache would try to serialize.
   */
  private class RenderLane(
    val pool: DesktopRenderWorkerPool?,
    /** The render JVM's environment decision, applied by whichever lane serves a capture. */
    val nativeEnv: RenderNativeEnv.Decision,
  ) {
    val fallbackNoticePrinted = java.util.concurrent.atomic.AtomicBoolean(false)
  }

  /** Decides the render JVM's `LD_LIBRARY_PATH`, logging when it differs from the daemon's. */
  private fun renderNativeEnv(): RenderNativeEnv.Decision {
    val decision =
      RenderNativeEnv.decide(
        renderJavaExecutable = renderJavaExecutable.orNull,
        daemonJavaHome = System.getProperty("java.home"),
        ldLibraryPath = System.getenv(RenderNativeEnv.VAR),
        mode = nativeEnvMode.orNull,
      )
    if (decision is RenderNativeEnv.Decision.Sanitized) {
      logger.info("composePreviewRender: ${decision.explanation}")
    }
    return decision
  }

  /**
   * The render worker pool, or null to fork per capture (disabled, or no resolvable classpath).
   * Per-worker failures are handled inside the pool as `Unusable`.
   */
  private fun openWorkerPool(nativeEnv: RenderNativeEnv.Decision): DesktopRenderWorkerPool? {
    if (!DesktopRenderWorkerPool.isEnabled()) return null
    val cp = renderClasspath.files.toList()
    if (cp.isEmpty()) return null
    // Without a project directory, fork rather than guess: relative paths must resolve as in the
    // forked lane.
    val workingDir = projectDirectory.orNull?.asFile ?: return null
    return DesktopRenderWorkerPool(
      classpath = cp,
      javaExecutable = renderJavaExecutable.orNull ?: defaultJavaExecutable(),
      jvmArgs = workerJvmArgs(),
      maxWorkers = DesktopRenderWorkerPool.configuredWorkers(),
      maxRendersPerWorker = DesktopRenderWorkerPool.configuredMaxRenders(),
      // `javaexec`'s default working directory, so both lanes agree on relative paths.
      workingDir = workingDir,
      // Forward renderer stderr; many diagnostics accompany successful requests.
      stderrSink = { line -> logger.lifecycle("composePreviewRender: $line") },
      // The forked lane's environment verbatim, so workers can't load a different libskiko.
      nativeEnv = nativeEnv,
    )
  }

  /**
   * The worker launcher when no JDK was raised, taken from the running process: `bin/java` vs
   * `bin\java.exe` differ by platform, and a wrong guess would silently disable the pool on
   * Windows.
   */
  private fun defaultJavaExecutable(): String {
    ProcessHandle.current().info().command().orElse(null)?.let { running ->
      if (java.io.File(running).canExecute()) return running
    }
    // Fallbacks for a JVM that hides its command line, still platform-correct.
    val home = java.io.File(System.getProperty("java.home"), "bin")
    return listOf("java", "java.exe")
      .map { java.io.File(home, it) }
      .firstOrNull { it.canExecute() }
      ?.absolutePath ?: "java"
  }

  /**
   * `-D` flags workers boot with: only the constant per-execution ones. `composeai.overrides.seed`
   * rides each request, or a worker would carry one preview's seed into the next.
   */
  private fun workerJvmArgs(): List<String> = buildList {
    add("-Dapple.awt.UIElement=true")
    add("-Dcomposeai.displayfilter.filters=${displayFilterFilters.get()}")
    add("-Dcomposeai.deviceframe.device=${deviceFrameDevice.get()}")
    // Must be on the worker command line: the runtime may latch its default before any request
    // arrives.
    add("-Dcomposeai.render.linkBufferComposer=${linkBufferComposer.get()}")
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
   * The renderer's positional argv for one capture, shared by pooled and forked lanes so they can't
   * drift.
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
    interaction: ee.schimke.composeai.discovery.InteractionCapture?,
    focus: FocusCapture?,
    hover: ee.schimke.composeai.discovery.HoverCapture?,
    drag: ee.schimke.composeai.discovery.DragCapture?,
    settle: ee.schimke.composeai.discovery.SettleCapture?,
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
      // 10th/11th — wrap flags: measure and crop to intrinsic bounds on that axis.
      spec.wrapWidth.toString(),
      spec.wrapHeight.toString(),
      // 12th/13th — @PreviewParameter provider and limit; empty means none. The renderer enumerates
      // values and writes `<id>_PARAM_<idx>.png` per value, since only it has the consumer
      // classpath.
      preview.params.previewParameterProviderClassName.orEmpty(),
      preview.params.previewParameterLimit.toString(),
      // 14th — `@Preview(locale)`; empty means none. `en-XA` / `ar-XB` get the pseudolocale wrap.
      preview.params.locale.orEmpty(),
      // 15th–18th — @ScrollingPreview intent; empty 15th means none. LONG / GIF go to
      // `renderScrollPreview`.
      scroll?.mode?.name.orEmpty(),
      scroll?.axis?.name.orEmpty(),
      (scroll?.maxScrollPx ?: 0).toString(),
      (scroll?.frameIntervalMs ?: 0).toString(),
      // 19th/20th — kind and, for LOTTIE, the asset path; empty kind means COMPOSE.
      preview.params.kind.name,
      preview.params.assetPath.orEmpty(),
      // 21st — `@Preview(fontScale)`, applied via `Density(density, fontScale)`; `1.0` is the
      // default.
      preview.params.fontScale.toString(),
      // 22nd–24th — `showSystemUi` (#1930): wraps phone captures in `SystemBarsFrame`; uiMode
      // supplies dark chrome, device lets round/Wear skip it.
      preview.params.showSystemUi.toString(),
      preview.params.uiMode.toString(),
      preview.params.device.orEmpty(),
      // 25th–27th — `@AnimatedPreview` window. `-1` means no animation; `0` is auto-detect and must
      // stay distinct (#2190). Older renderers read `-1` as no animation, and newer ones only treat
      // a bare `0` as auto-detect for animation-shaped captures. `showCurves` is forwarded but
      // desktop emits no curve strip.
      (animation?.durationMs ?: -1).toString(),
      (animation?.frameIntervalMs ?: 0).toString(),
      (animation?.showCurves ?: false).toString(),
      // 28th — sibling stems the renderer's `@PreviewParameter` fan-out cleanup must not delete
      // (#2193); `|`-joined, empty for none.
      fanoutSiblingStems.joinToString("|"),
      // 29th–32nd — content-size bound placeholders (only set by the daemon's serve/bundle-render
      // path), so later args keep their positions. `0` means no bound.
      "0",
      "0",
      "0",
      "0",
      // 33rd–38th — `@FocusedPreview` drive (#3672), desktop only (Android reads the manifest).
      // `-1` / empty means no focus intent.
      (focus?.tabIndex ?: -1).toString(),
      focusTraversalPrefix(preview, focus).joinToString("|"),
      (focus?.step ?: 0).toString(),
      (focus?.enterPlacesFocus ?: false).toString(),
      (focus?.pressed ?: false).toString(),
      (focus?.overlay ?: false).toString(),
      // 39th — `@OverrideVariant(interaction = Hovered)` target, separate from focus.
      (hover?.targetIndex ?: -1).toString(),
      // 40th–46th — `@InteractionPreview` script; empty gesture means none. Desktop only. Sent as
      // parts, not a duration, because the renderer derives the window from the script.
      (interaction?.gesture?.name).orEmpty(),
      interaction?.targets?.joinToString("|").orEmpty(),
      (interaction?.holdMs ?: 0).toString(),
      (interaction?.gapMs ?: 0).toString(),
      (interaction?.leadInMs ?: 0).toString(),
      (interaction?.frameIntervalMs ?: 0).toString(),
      (interaction?.format?.name).orEmpty(),
      // 47th — `@AnimatedPreview(format)`; empty keeps GIF.
      (animation?.format?.name).orEmpty(),
      // 48th/49th — `@SettledPreview` (#4202), desktop only; `-1` means none.
      (settle?.afterMs ?: -1).toString(),
      (settle?.maxMs ?: 0).toString(),
      // 50th–53rd — `@CaptureGutter` per edge in dp; `0` (also the missing-arg default) means none.
      (preview.params.captureGutter?.start ?: 0).toString(),
      (preview.params.captureGutter?.top ?: 0).toString(),
      (preview.params.captureGutter?.end ?: 0).toString(),
      (preview.params.captureGutter?.bottom ?: 0).toString(),
      // 54th — `@OverrideVariant(interaction = Dragged)` target; appended so older renderers ignore
      // it.
      (drag?.targetIndex ?: -1).toString(),
    )
}

/**
 * Traversal directions up to [focus]'s step, in order; empty for indexed or absent focus. Desktop
 * only: it renders one process per capture, so each step replays the walk from the start (Android
 * keeps one composition across steps). Internal for [FocusTraversalPrefixTest].
 */
internal fun focusTraversalPrefix(preview: PreviewInfo, focus: FocusCapture?): List<String> {
  val step = focus?.step ?: return emptyList()
  if (focus.direction == null) return emptyList()
  return preview.captures
    .mapNotNull { it.focus }
    .filter { it.direction != null && (it.step ?: 0) <= step }
    .sortedBy { it.step ?: 0 }
    // One entry per step: `captures` is a cross product, so steps can repeat across timings.
    .distinctBy { it.step }
    .mapNotNull { it.direction?.name }
}

private fun Float.roundHalfUpPx(): Int = kotlin.math.floor(this + 0.5f).toInt().coerceAtLeast(1)

/** JSON used to (de)serialise `@OverrideVariant` seeds across the desktop renderer boundary. */
private val OVERRIDES_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** How many available preview names to list in a no-match `--preview` error before truncating. */
private const val MAX_SUGGESTED_PREVIEW_NAMES = 20

/**
 * Synthetic catalog kinds desktop can't render (no composable to reflect); Android renders them
 * (#2135).
 */
private val SYNTHETIC_CATALOG_KINDS_UNSUPPORTED_ON_DESKTOP =
  setOf("CATALOG", "THEME_CATALOG", "WEAR_THEME_CATALOG")

/**
 * Narrows [previews] by name (#2066). Blank filters keep everything; a non-empty filter matching
 * nothing throws, listing available names. Matching is [PreviewNameFilter]; this owns the
 * select-or-fail policy.
 */
internal fun selectNamedPreviews(
  previews: List<PreviewInfo>,
  filters: List<String>,
  manifestPath: String? = null,
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
      appendEncodingHint(cleaned)
      appendManifestContext(previews, manifestPath)
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
 * Narrows [previews] by id (#2966), reaching individual fan-out members a name filter can't (they
 * share `functionName`), e.g. to skip palettes a catalog defers. Same select-or-fail policy as
 * [selectNamedPreviews]; runs after it.
 */
internal fun selectPreviewIds(
  previews: List<PreviewInfo>,
  filters: List<String>,
  manifestPath: String? = null,
  manifestPreviews: List<PreviewInfo> = previews,
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
      appendEncodingHint(cleaned)
      appendManifestContext(manifestPreviews, manifestPath)
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
 * Hints at argument mangling when a filter contains `?` (#5172): on a non-UTF-8 `sun.jnu.encoding`,
 * non-ASCII characters arrive as `?`.
 */
private fun StringBuilder.appendEncodingHint(filters: List<String>) {
  if (filters.none { it.contains('?') }) return
  val encoding = System.getProperty("sun.jnu.encoding") ?: return
  if (encoding.equals("UTF-8", ignoreCase = true)) return
  append(
    " A requested id contains '?' and this JVM encodes process arguments as $encoding, so a" +
      " non-ASCII character in the id (an em dash, say) was replaced on the way in: re-run with a" +
      " UTF-8 locale (LC_ALL=C.UTF-8) or pass the ids as -PcomposePreview.idFilterFile=<path to a" +
      " newline-delimited UTF-8 list>."
  )
}

private fun StringBuilder.appendManifestContext(
  previews: List<PreviewInfo>,
  manifestPath: String?,
) {
  val assetCount = previews.count {
    it.params.kind == PreviewKind.LOTTIE || it.params.kind == PreviewKind.SVG
  }
  val codeCount = previews.size - assetCount
  append(" Manifest contains $codeCount code preview(s) and $assetCount asset preview(s)")
  manifestPath?.let { append(" at $it") }
  append(".")
}

/**
 * What one `--exclude-preview-id` pattern matched, reported before rendering. Counts are per
 * pattern and may overlap; the point is spotting a pattern that did nothing.
 */
internal data class PreviewIdExclusionMatch(
  val pattern: String,
  val matched: Int,
  val total: Int,
) {
  /**
   * The printed line; a zero match hints that ids keep spaces that filenames turn into underscores
   * (#5064).
   */
  val line: String
    get() =
      "--exclude-preview-id '$pattern' matched $matched of $total preview(s)" +
        if (matched == 0)
          " — nothing excluded. Check the pattern: preview ids keep spaces where render filenames " +
            "use underscores, and a plain pattern matches on substring."
        else ""
}

/**
 * Per-pattern match counts for [excludes], in order. Separate from [excludePreviewIds], which only
 * needs the result; blank patterns are dropped the same way.
 */
internal fun previewIdExclusionMatches(
  previews: List<PreviewInfo>,
  excludes: List<String>,
): List<PreviewIdExclusionMatch> =
  excludes.map(String::trim).filter(String::isNotEmpty).map { pattern ->
    PreviewIdExclusionMatch(
      pattern = pattern,
      matched = previews.count { PreviewNameFilter.matchesId(listOf(pattern), it.id) },
      total = previews.size,
    )
  }

/**
 * Drops previews whose id matches [excludes] (#2966); see [RenderPreviewsTask.previewIdExcludes]. A
 * non-matching pattern is a no-op, reported via [previewIdExclusionMatches] (#5064). Excluding
 * everything throws.
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
 * Stems of same-directory, same-extension manifest outputs that extend [outputFile]'s stem with `_`
 * (e.g. `Foo_Dark` for `Foo`), which the renderer's `deleteStaleFanoutFiles` would otherwise delete
 * as its own fan-out (#2193). Other extensions need no protection, and shielding them would keep
 * stale files forever. Joined with `|`, which `sanitizeForPath` strips from stems.
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
