package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSetupOption
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.background
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.locale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import ee.schimke.composeai.daemon.AmbientOverrideExtension
import ee.schimke.composeai.daemon.CachedDeviceArtSource
import ee.schimke.composeai.daemon.DeviceFrameConfig
import ee.schimke.composeai.daemon.DeviceFrameDataProducer
import ee.schimke.composeai.daemon.DisplayFilterConfig
import ee.schimke.composeai.daemon.DisplayFilterDataProducer
import ee.schimke.composeai.daemon.FocusController
import ee.schimke.composeai.daemon.FocusOverlay
import ee.schimke.composeai.daemon.FocusOverrideExtension
import ee.schimke.composeai.daemon.GestureOverrideExtension
import ee.schimke.composeai.daemon.KeyboardController
import ee.schimke.composeai.daemon.KeyboardOverrideExtension
import ee.schimke.composeai.daemon.LauncherWidgetExtension
import ee.schimke.composeai.daemon.PermissionsOverrideExtension
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.FocusDirection as ProtocolFocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.LauncherResizeOrder
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.data.render.LinkBufferComposer
import ee.schimke.composeai.data.render.PreviewAnimationContext
import ee.schimke.composeai.data.render.PreviewFilter
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
import ee.schimke.composeai.data.render.extensions.compose.ExtensionFrameContext
import ee.schimke.composeai.data.render.extensions.loadPreviewWrapperClass
import ee.schimke.composeai.data.render.extensions.provides
import ee.schimke.composeai.scroll.FLING_DECAY
import ee.schimke.composeai.scroll.FLING_MAX_DISTANCE_VIEWPORTS
import ee.schimke.composeai.scroll.FLING_MIN_STEP_DP
import ee.schimke.composeai.scroll.FLING_PEAK_DP_PER_FRAME
import ee.schimke.composeai.scroll.HOLD_END_MS
import ee.schimke.composeai.scroll.HOLD_START_MS
import ee.schimke.composeai.scroll.INTER_FLING_HOLD_MS
import ee.schimke.composeai.scroll.ScrollAxis as ProductScrollAxis
import ee.schimke.composeai.scroll.ScrollDriveResult
import ee.schimke.composeai.scroll.ScrollGifEncoder
import ee.schimke.composeai.scroll.ScrollGifFrameDriverExtension
import ee.schimke.composeai.scroll.ScrollGifFramePlan
import ee.schimke.composeai.scroll.ScrollLongFrameDriverExtension
import ee.schimke.composeai.scroll.ScrollLongFramePlan
import ee.schimke.composeai.scroll.ScrollPreviewExtension
import ee.schimke.composeai.scroll.SliceCapture
import ee.schimke.composeai.scroll.applyWearPillClip
import ee.schimke.composeai.scroll.driveScrollBy
import ee.schimke.composeai.scroll.driveScrollByViewport
import ee.schimke.composeai.scroll.driveScrollToEnd
import ee.schimke.composeai.scroll.driveScrollToStart
import ee.schimke.composeai.scroll.remainingScrollPx
import ee.schimke.composeai.scroll.stitchSlicesWithFinalFrame
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private fun ScrollAxis.toProductAxis(): ProductScrollAxis =
  when (this) {
    ScrollAxis.VERTICAL -> ProductScrollAxis.VERTICAL
    ScrollAxis.HORIZONTAL -> ProductScrollAxis.HORIZONTAL
  }

/**
 * Encoder for the `renders/<stem>.overrides.json` sidecar (the `compose/overrides` payload).
 * File-level so the render class's `writeOverridesSidecar` can reach it. `encodeDefaults = true` to
 * match the desktop renderer's sidecar writer so the format is identical across backends.
 */
private val overridesSidecarJson = Json { encodeDefaults = true }

/**
 * Loads the previews manifest and returns the subset assigned to `shardIndex` out of `shardCount`
 * shards. Generated shard subclasses delegate their `@Parameters` method here (see the plugin's
 * `generateShardTests` task).
 *
 * With `shardCount = 1`, returns every preview — that's the default single-class path.
 *
 * System properties: composeai.render.manifest — path to previews.json composeai.render.outputDir —
 * directory for rendered PNGs
 */
object PreviewManifestLoader {
  private val json = Json { ignoreUnknownKeys = true }

  @JvmStatic
  fun loadShard(shardIndex: Int, shardCount: Int): List<Array<Any>> {
    require(shardCount >= 1) { "shardCount must be >= 1" }
    require(shardIndex in 0 until shardCount) { "shardIndex must be in [0, $shardCount)" }
    val manifestPath = System.getProperty("composeai.render.manifest") ?: return emptyList()
    val file = File(manifestPath)
    if (!file.exists()) return emptyList()

    val manifest = json.decodeFromString<RenderManifest>(file.readText())
    // Name / id / id-exclude filters (issues #2066 / #2966 / #2977) — the same `--preview` /
    // `--preview-id` / `--exclude-preview-id` selection the desktop `RenderPreviewsTask` applies,
    // forwarded here as system properties by the Android `composePreviewRender` task so a filter
    // actually narrows a Robolectric render (before #2977 it was inert on this backend). Applied to
    // the discovered entries FIRST, before the XR/Lottie/SVG drop and any `@PreviewParameter`
    // expansion, so the fail-fast "available previews" list spans everything discovered and the
    // id filter selects the same multipreview-member ids the desktop path sees. The FULL
    // `manifest.previews` is still what `deleteStaleFanoutFiles` protects below, so a filtered-out
    // preview keeps its existing PNG on disk exactly as it does on desktop.
    val selectedBase =
      PreviewFilter.select(
        items = manifest.previews,
        nameFilters = PreviewFilter.patternsFrom(PreviewFilter.NAME_FILTER_PROPERTY),
        idFilters = PreviewFilter.patternsFrom(PreviewFilter.ID_FILTER_PROPERTY),
        idExcludes = PreviewFilter.patternsFrom(PreviewFilter.ID_EXCLUDE_PROPERTY),
        functionName = { it.functionName },
        className = { it.className },
        id = { it.id },
      )
    val permutationPatterns = RenderPreviewPermutations.patternsFrom()
    val selected = RenderPreviewPermutations.expand(selectedBase, permutationPatterns)
    val allEntries = RenderPreviewPermutations.expand(manifest.previews, permutationPatterns)
    // Expand `@PreviewParameter` providers into one row per value BEFORE
    // sharding, so one preview's values never span multiple shards — each
    // (preview, value) row carries an already-suffixed id / renderOutput
    // ready for the test runner. Values are kept alongside the entry
    // (Array<Any>[entry, args]) instead of being serialised back into the
    // manifest: provider values can be arbitrary runtime objects, often
    // not JSON-representable.
    // XR_SUBSPACE previews are rendered by the separate `:renderer-xr` Robolectric task, not by
    // this Android image renderer (there's no `PreviewRenderStrategy` for them, by design).
    // LOTTIE assets discovered in an Android module are rendered by the JVM desktop Compottie
    // path (`composePreviewRenderLottie`), not Robolectric — there's no Android Lottie player
    // and the asset is portable IR. SVG assets likewise render on the JVM desktop path
    // (`composePreviewRenderSvg`) via Skia's `loadSvgPainter` — Robolectric has no SVG decoder.
    // Drop all three before any expansion so they never reach `strategyFor`.
    val expandedByEntry =
      selected
        .filter {
          it.params.kind != PreviewKind.XR_SUBSPACE &&
            it.params.kind != PreviewKind.LOTTIE &&
            it.params.kind != PreviewKind.SVG
        }
        .map { entry ->
          // `expandParameterProvider` already isolates provider-load failures; this outer guard is
          // belt-and-suspenders so any *other* unexpected throw while expanding one entry drops
          // just
          // that entry rather than failing the whole shard's `@Parameters` load (issue #2493).
          entry to
            runCatching { expandParameterProvider(entry) }
              .getOrElse { e ->
                System.err.println("Failed to expand preview '${entry.id}': ${e.message}")
                emptyList()
              }
        }
    // Excluded rows stay in `expandedByEntry` (the stale-fan-out sweep needs them in its expected
    // set, see PreviewRow.excludedRow) but never reach sharding or the renderer.
    val expanded = expandedByEntry.flatMap { it.second }.filterNot { it.excludedRow }
    // Tier filter (set by the plugin via TierSystemPropProvider). When
    // `fast`, drop heavyweight captures and annotation-sourced data
    // products. Heavy outputs keep their previous files on disk and stay
    // reachable via explicit product fetch / refresh paths.
    // Entries left with no outputs are dropped from sharding entirely so
    // shards don't allocate a row for a no-op render.
    val isFast =
      System.getProperty("composeai.render.tier", "full").equals("fast", ignoreCase = true)
    val filtered =
      if (!isFast) expanded
      else
        expanded.mapNotNull { row ->
          val keep = row.entry.captures.filter { it.cost <= HEAVY_COST_THRESHOLD }
          val keepProducts = row.entry.dataProducts.filter { it.cost <= HEAVY_COST_THRESHOLD }
          if (keep.isEmpty() && keepProducts.isEmpty()) null
          else {
            PreviewRow(
              row.entry.copy(captures = keep, dataProducts = keepProducts),
              row.previewArgs,
            )
          }
        }
    val ours = assignToShard(filtered, shardCount, shardIndex)
    // The renderer is authoritative about which fan-out files will exist
    // for its parameterized previews — delete any `<stem>_*<ext>` files
    // from prior runs that today's manifest doesn't account for. Guards
    // against provider renames ("loading" → "busy") and the
    // `_PARAM_<idx>` → `_<label>` migration leaving a mix of old-shape
    // and new-shape captures or rendered data products on disk. Runs at shard-load time, before
    // any test body writes to the directory.
    deleteStaleFanoutFiles(
      outDir = System.getProperty("composeai.render.outputDir")?.let(::File),
      allEntries = allEntries,
      expandedByEntry = expandedByEntry,
      ownedIds = ours.map { it.entry.id }.toSet(),
    )
    return ours.map { arrayOf<Any>(it.entry, it.previewArgs) }
  }

  /**
   * One (preview, `@PreviewParameter` value) row. [excludedRow] marks a row the row filter dropped
   * (`--exclude-preview-row`): it is NOT rendered, but it stays in the expanded list so
   * [deleteStaleFanoutFiles]' expected-name set still covers its output — otherwise the sweep would
   * delete the very PNG the filter exists to avoid re-rendering, which is the opposite of how a
   * filtered-out preview behaves.
   */
  internal data class PreviewRow(
    val entry: RenderPreviewEntry,
    val previewArgs: List<Any?>,
    val excludedRow: Boolean = false,
  )

  /**
   * LPT (Longest-Processing-Time-first) bin-packing of preview rows onto `shardCount` shards by
   * per-row cost (= sum of capture costs). Returns the rows assigned to `shardIndex`.
   *
   * Why LPT over the previous `i % shardCount` round-robin: round-robin was correct under the
   * uniform-cost world (every preview ≈ 0.15s) but with the capture-cost catalogue (static = 1, GIF
   * = 40, animated = 50) it routinely produced make-spans 3-5× worse than the optimum on
   * heterogeneous modules — one shard would end up with every GIF while the others rendered cheap
   * statics in seconds and then sat idle. LPT's 4/3-of-optimal worst-case bound is good enough that
   * we don't bother with a true ILP solver.
   *
   * Tie-breaking on the `id` field (after the descending cost sort) keeps the assignment
   * deterministic across runs even when several rows weigh the same — the build cache and snapshot
   * tests don't have to round-trip a shuffle.
   *
   * `shardCount == 1` short-circuits the partitioning entirely; the shardIndex must be in `[0,
   * shardCount)`.
   */
  internal fun assignToShard(
    rows: List<PreviewRow>,
    shardCount: Int,
    shardIndex: Int,
  ): List<PreviewRow> {
    require(shardCount >= 1) { "shardCount must be >= 1" }
    require(shardIndex in 0 until shardCount) {
      "shardIndex must be in [0, $shardCount), was $shardIndex"
    }
    if (shardCount == 1) return rows

    val rowsWithCost =
      rows
        .map { row ->
          row to
            row.entry.captures.sumOf { it.cost.toDouble() } +
              row.entry.dataProducts.sumOf { it.cost.toDouble() }
        }
        .sortedWith(
          compareByDescending<Pair<PreviewRow, Double>> { it.second }.thenBy { it.first.entry.id }
        )
    val shardLoads = DoubleArray(shardCount)
    val ours = mutableListOf<PreviewRow>()
    for ((row, cost) in rowsWithCost) {
      // ArgMin scan is O(K) per row — K is bounded at
      // [ShardTuning.MAX_SHARDS] = 8 in the plugin, so this stays
      // cheap even on big modules.
      var pick = 0
      for (k in 1 until shardCount) {
        if (shardLoads[k] < shardLoads[pick]) pick = k
      }
      shardLoads[pick] += cost
      if (pick == shardIndex) ours += row
    }
    return ours
  }

  internal fun expandParameterProvider(entry: RenderPreviewEntry): List<PreviewRow> {
    val providerFqn =
      entry.params.previewParameterProviderClassName
        ?: return listOf(PreviewRow(entry, emptyList()))
    val limit = entry.params.previewParameterLimit.coerceAtLeast(0)
    if (limit == 0) return emptyList()
    val values =
      try {
        loadProviderValues(providerFqn, limit)
      } catch (e: Throwable) {
        // Isolate the failure: one unloadable provider must not throw out of the JUnit
        // `@Parameters` method and sink the whole shard (issue #2493). Emit a per-preview
        // `.error.json` so the failing preview surfaces its cause on the panel instead of silently
        // vanishing, then drop only this preview from the shard.
        System.err.println(
          "@PreviewParameter(provider = $providerFqn) on '${entry.id}' failed to load: ${e.message}"
        )
        providerErrorOutputFile(entry)?.let { RenderErrorSidecar.write(it, e) }
        return emptyList()
      }
    if (values.isEmpty()) {
      System.err.println(
        "@PreviewParameter(provider = $providerFqn) on '${entry.id}' produced no values — skipping."
      )
      return emptyList()
    }
    // Provider loaded cleanly this run — drop any stale base error card a prior failed run left at
    // the unsuffixed output path, so a now-fixed provider doesn't keep haunting the panel.
    providerErrorOutputFile(entry)?.let { RenderErrorSidecar.deleteStale(it) }
    val allSuffixes = PreviewParameterLabels.suffixesFor(values)
    // Row filter (`--exclude-preview-row` → `composeai.preview.rowExclude`). Applied here, after
    // labelling: the id filters above ran over the DISCOVERED entries, where this parameterized
    // preview is a single row with no per-value id, so a label is the only handle on one value. The
    // stale fan-out cleanup below still keys on the FULL row set, so a skipped row keeps its
    // previous
    // PNG on disk exactly as a filtered-out preview does.
    val keptRows =
      PreviewFilter.keptRowIndices(
        allSuffixes,
        PreviewFilter.patternsFrom(PreviewFilter.ROW_EXCLUDE_PROPERTY),
      )
    if (keptRows.size < allSuffixes.size) {
      System.err.println(
        "@PreviewParameter on '${entry.id}': skipping ${allSuffixes.size - keptRows.size} of " +
          "${allSuffixes.size} row(s) excluded by ${PreviewFilter.ROW_EXCLUDE_PROPERTY}"
      )
    }
    val rows = values.mapIndexed { idx, value ->
      val paramSuffix = allSuffixes[idx]
      val newCaptures =
        entry.captures.map { c ->
          c.copy(renderOutput = insertBeforeExtension(c.renderOutput, paramSuffix))
        }
      val newProducts =
        entry.dataProducts.map { p ->
          p.copy(output = insertBeforeExtension(p.output, paramSuffix))
        }
      val newId = entry.id + paramSuffix
      PreviewRow(
        entry.copy(id = newId, captures = newCaptures, dataProducts = newProducts),
        listOf(value),
        excludedRow = idx !in keptRows,
      )
    }
    return rows
  }

  /**
   * Deletes stale `<stem>_*<ext>` capture and rendered-data-product fan-out files for the
   * parameterized previews in [expandedByEntry]. Both output classes use the same path-aware sweep
   * rule so their cleanup cannot drift independently.
   *
   * Three guards keep the prefix match from destroying correct sibling output (issue #2193):
   * - **Manifest-wide expected set.** `@Preview(name = …)` / `@Preview(group = …)` variant suffixes
   *   make a sibling preview's stem an underscore-extension of the base stem (`Foo` vs `Foo_Dark`),
   *   so the sibling's base render and its own fan-out (`Foo_Dark_<label>.png`) match `Foo_*`. The
   *   exclusion set therefore spans every manifest entry's outputs — declared ([allEntries]) and
   *   expanded ([expandedByEntry]) — not just the preview whose prefix is being swept.
   * - **Declared sibling stems.** The expected set only covers a sibling's *expanded* rows when the
   *   sibling was itself expanded, which a `--preview` / `--preview-id` filter prevents: the filter
   *   applies to the selection [expandedByEntry] is built from, while [allEntries] stays the full
   *   manifest. A filtered-out parameterized sibling therefore contributes its declared
   *   `Foo_Dark.png` and nothing else, leaving `Foo_Dark_<label>.png` and its companions
   *   unaccounted for under `Foo`'s prefix. [fanoutSiblingStems] shields the whole stem, so the
   *   loader's promise that a filtered-out preview keeps its existing artifacts holds for a
   *   parameterized one too — matching what the plugin already computes for the desktop renderer.
   * - **Shard-owned pass.** Every parallel fork (`maxParallelForks = shardCount`) expands the whole
   *   manifest at shard load, at a time when sibling forks may already be rendering — under the old
   *   per-entry expected set a late-loading fork would delete fan-out PNGs another fork had just
   *   written. The manifest-wide set makes that impossible (every fork's outputs are expected), and
   *   gating the sweep on [ownedIds] additionally keeps forks whose shard was assigned none of a
   *   preview's fan-out from redundantly re-sweeping its prefix.
   *
   * A stale row's companions go with it. A failed row writes `<stem>_<label><ext>.error.json`
   * ([RenderErrorSidecar]) and no PNG, so an extension-only filter never matched the one file the
   * row actually left behind, and the orphan outlived every subsequent run — the CLI's
   * missing-render report then rediscovers it by directory glob and can present an obsolete
   * exception as a failure of the current run (PR #3815). A companion is classified by the output
   * it names ([fanoutOutputNameOf]), so it is kept exactly when that output is expected and swept
   * exactly when that output is stale.
   */
  internal fun deleteStaleFanoutFiles(
    outDir: File?,
    allEntries: List<RenderPreviewEntry>,
    expandedByEntry: List<Pair<RenderPreviewEntry, List<PreviewRow>>>,
    ownedIds: Set<String>,
  ) {
    if (outDir == null || !outDir.isDirectory) return
    val productRoot = outDir.parentFile ?: outDir
    val expectedFiles = buildSet {
      allEntries.forEach { entry ->
        entry.captures.mapNotNullTo(this) { captureOutputFile(outDir, it.renderOutput) }
        entry.dataProducts.mapNotNullTo(this) { productOutputFile(productRoot, it.output) }
      }
      expandedByEntry
        .flatMap { it.second }
        .forEach { row ->
          row.entry.captures.mapNotNullTo(this) { captureOutputFile(outDir, it.renderOutput) }
          row.entry.dataProducts.mapNotNullTo(this) { productOutputFile(productRoot, it.output) }
        }
    }
    val declaredOutputFiles = buildList {
      allEntries.forEach { entry ->
        entry.captures.mapNotNullTo(this) { captureOutputFile(outDir, it.renderOutput) }
        entry.dataProducts.mapNotNullTo(this) { productOutputFile(productRoot, it.output) }
      }
    }
    for ((entry, rows) in expandedByEntry) {
      if (entry.params.previewParameterProviderClassName == null) continue
      if (rows.none { it.entry.id in ownedIds }) continue
      for (template in entry.captures) {
        captureOutputFile(outDir, template.renderOutput)?.let { templateFile ->
          deleteStaleFanoutFiles(templateFile, expectedFiles, declaredOutputFiles)
        }
      }
      for (template in entry.dataProducts) {
        productOutputFile(productRoot, template.output)?.let { templateFile ->
          deleteStaleFanoutFiles(templateFile, expectedFiles, declaredOutputFiles)
        }
      }
    }
  }

  /**
   * Suffixes a renderer appends to a render output's own file name to make a companion that lives
   * beside it — `renders/Foo.png` → `renders/Foo.png.error.json` ([RenderErrorSidecar.pathFor]) and
   * `renders/Foo.png.warnings.json` ([RenderWarningsSidecar.pathFor]).
   *
   * Kept in sync with the desktop renderer's `RENDER_COMPANION_SUFFIXES`: the two sweeps answer the
   * same question about the same directory layout, and a suffix known to one but not the other
   * would leave an orphan on whichever backend rendered the module.
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
   * [ext] is itself `.json`, because then every `.error.json` in the directory ends with [ext] and
   * the fallback claims image companions like `Foo_Alice.png.error.json` as JSON outputs of its
   * own, deleting another output's *current* diagnostics.
   */
  internal fun fanoutOutputNameOf(name: String, ext: String): String? {
    RENDER_COMPANION_SUFFIXES.forEach { companion ->
      if (name.endsWith(companion)) return name.removeSuffix(companion).takeIf { it.endsWith(ext) }
    }
    return name.takeIf { it.endsWith(ext) }
  }

  /**
   * Stems of declared manifest outputs in the same directory as [templateFile], with the same
   * extension, whose name extends [templateFile]'s stem with an underscore — the issue #2193
   * siblings whose files a prefix-greedy sweep would otherwise mistake for its own fan-out.
   *
   * Deliberately built from the **declared** entries rather than the expanded rows, and so from the
   * FULL manifest rather than the filtered selection: a `@Preview(name = "Dark")` sibling that a
   * `--preview-id` filter dropped is never expanded, so its fan-out rows (`Foo_Dark_<label>.png`
   * and their companions) appear in no expected-name set, yet they all match the swept preview's
   * `Foo_*` prefix. Protecting the declared stem covers the sibling's whole fan-out without
   * requiring it to have been expanded, which is what makes good on the loader's promise that a
   * filtered-out preview keeps its existing artifacts.
   *
   * Mirrors the plugin's `fanoutSiblingStems`, which computes the same list for the desktop
   * renderer (that subprocess has no manifest, so the plugin passes the stems on the command line).
   * The same-extension restriction is load-bearing in both: the sweep only ever deletes files
   * belonging to an [templateFile]-extension output, so shielding a different-extension sibling's
   * stem would strand a genuinely stale `Foo_Dark.png` from before that sibling's capture became a
   * GIF.
   */
  private fun fanoutSiblingStems(
    declaredOutputFiles: List<File>,
    templateFile: File,
  ): List<String> {
    val prefix = templateFile.nameWithoutExtension + "_"
    return declaredOutputFiles
      .filter {
        it.parentFile == templateFile.parentFile &&
          it != templateFile &&
          it.extension == templateFile.extension
      }
      .map { it.nameWithoutExtension }
      .filter { it.startsWith(prefix) }
      .distinct()
  }

  private fun captureOutputFile(outDir: File, renderOutput: String): File? {
    if (renderOutput.isEmpty()) return null
    return File(outDir, renderOutput.substringAfterLast('/'))
  }

  private fun productOutputFile(productRoot: File, output: String): File? {
    if (output.isEmpty()) return null
    return File(productRoot, output)
  }

  private fun deleteStaleFanoutFiles(
    templateFile: File,
    expectedFiles: Set<File>,
    declaredOutputFiles: List<File>,
  ) {
    val dir = templateFile.parentFile ?: return
    val stem = templateFile.nameWithoutExtension
    val ext = ".${templateFile.extension}"
    val prefix = stem + "_"
    val siblingStems = fanoutSiblingStems(declaredOutputFiles, templateFile)
    dir
      .listFiles()
      ?.filter { file ->
        val output = fanoutOutputNameOf(file.name, ext)
        file.name.startsWith(prefix) &&
          output != null &&
          File(dir, output) !in expectedFiles &&
          siblingStems.none { sibling ->
            file.name.startsWith("$sibling.") || file.name.startsWith("${sibling}_")
          }
      }
      ?.forEach { file ->
        if (!file.delete()) {
          System.err.println("Failed to delete stale fan-out file: ${file.absolutePath}")
        }
      }
  }

  /**
   * Inserts [suffix] before the extension of a `renders/<id>.<ext>` path. `renders/foo.png` +
   * `_PARAM_0` → `renders/foo_PARAM_0.png`. Leaves paths without an extension untouched (appended
   * at the end) so the mapping stays a pure string transformation.
   */
  internal fun insertBeforeExtension(path: String, suffix: String): String {
    if (path.isEmpty()) return path
    val dot = path.lastIndexOf('.')
    val slash = path.lastIndexOf('/')
    return if (dot > slash) path.substring(0, dot) + suffix + path.substring(dot) else path + suffix
  }

  /**
   * Enumerate a `PreviewParameterProvider`'s values reflectively.
   *
   * Throws [PreviewParameterLoadException] on any hard failure (class missing, no no-arg
   * constructor, missing/throwing `getValues()`) so the caller can isolate the failing preview and
   * surface it as a per-preview error card rather than letting the throw sink the whole shard.
   * Returns an empty list only when the provider legitimately yields no values.
   *
   * The reflection itself lives in [PreviewParameterSupport] so the daemon's render body resolves
   * providers exactly the same way (issue #3027) rather than growing a second copy that drifts.
   */
  private fun loadProviderValues(providerFqn: String, limit: Int): List<Any?> =
    PreviewParameterSupport.loadValues(providerFqn, limit)

  /**
   * Resolve the base output PNG for [entry] the same way [RobolectricRenderTestBase.outputFileFor]
   * does, so a provider-load error card lands where the panel already looks for this preview's
   * render. `null` when no output dir is configured or the entry declares no output at all.
   */
  private fun providerErrorOutputFile(entry: RenderPreviewEntry): File? {
    val outputDir = System.getProperty("composeai.render.outputDir")?.let(::File) ?: return null
    entry.captures.firstOrNull()?.let { capture ->
      val leaf = capture.renderOutput.substringAfterLast('/').ifEmpty { "${entry.id}.png" }
      return File(outputDir, leaf)
    }
    entry.dataProducts.firstOrNull()?.let { product ->
      val rootDir = outputDir.parentFile ?: outputDir
      return File(rootDir, product.output)
    }
    return null
  }
}

/**
 * Rendering logic — driven by a single [RenderPreviewEntry]. Subclasses supply the `@RunWith` +
 * `@Parameters` wiring. [RobolectricRenderTest] is the default single-class entry; the plugin
 * generates `RobolectricRenderTest_ShardN` subclasses when `composeAiPreview.shards > 1`.
 *
 * Uses `roborazzi-compose`'s `captureRoboImage { @Composable }` overload, which registers
 * `RoborazziActivity` with Robolectric's ShadowPackageManager and drives the composition without
 * requiring `createComposeRule()` or a consumer-side ui-test-manifest. Per-preview
 * width/height/fontScale/locale/uiMode/background are applied through [RoborazziComposeOptions]; it
 * re-applies the Robolectric qualifiers around each capture so different previews render at
 * different sizes.
 *
 * The content itself is produced by a [PreviewRenderStrategy] keyed off [RenderPreviewParams.kind]
 * — @Composable previews use the reflective Compose strategy, tile previews route through
 * [TilePreviewComposable].
 *
 * Annotations that would normally live on this class (`@Config`, `@GraphicsMode`) are DELIBERATELY
 * absent. They've moved to the generated `ee/schimke/composeai/renderer/robolectric.properties`
 * file on the test classpath, which Robolectric merges into the effective config for every test in
 * this package. The motivation is issue #142: JUnit's `AnnotationParser.parseClassValue` eagerly
 * resolves `@Config.application()` default (`android.app.Application`) during test-class discovery,
 * and on some JVMs (JDK 25 on certain Linux distros) that resolution fails with
 * `ClassNotFoundException` because the test worker forks on a JVM where `android.jar` isn't on the
 * bootstrap classpath. Removing `@Config` from the bytecode removes that parse path entirely.
 *
 * The properties file pins:
 * - `sdk=N` — auto-detected from the consumer's `android.compileSdk` (see
 *   [GenerateRobolectricPropertiesTask] and issue #1248). Override with `composePreview.sdkVersion
 *   = N`.
 * - `graphicsMode=NATIVE` — HardwareRenderer path for Compose capture
 * - `application=android.app.Application` (default) — skips the consumer's custom
 *   `Application.onCreate()` so preview rendering sidesteps platform-specific init (BridgingManager
 *   on non-Wear sandboxes, Firebase, Play Services, WorkManager) that routinely fails inside
 *   Robolectric. Consumers can set `composePreview.useConsumerApplication = true` to restore the
 *   manifest-declared Application.
 * - `shadows=…ShadowFontsContractCompat` — GoogleFont shadow is always on.
 */
abstract class RobolectricRenderTestBase(
  private val preview: RenderPreviewEntry,
  /**
   * Values supplied to the preview composable — non-empty only when the preview's
   * `@PreviewParameter` fan-out produced a row. The test-runner row carries `(entry, args)` so
   * values never round-trip through JSON; the loader enumerates the provider on the test JVM and
   * passes the raw objects straight through.
   */
  private val previewArgs: List<Any?>,
) {

  @OptIn(ExperimentalRoborazziApi::class)
  @Test
  fun renderPreview() {
    // Before this preview's first composition, and inside the Robolectric sandbox — each sandbox
    // classloader holds its own copy of `ComposeRuntimeFlags`, so the opt-in has to be applied from
    // in here rather than once per JVM. Idempotent, so a shard that reuses its sandbox across
    // previews pays a `getProperty` per capture and nothing more. Unset (the default) is silent.
    LinkBufferComposer.applyAndDescribe(javaClass.classLoader)?.let(System.err::println)

    val outputDir =
      File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
    outputDir.mkdirs()

    val params = preview.params
    // AS-parity sizing: an axis wraps to intrinsic content when the user
    // didn't specify it (and didn't pick a device/showSystemUi frame —
    // discovery has already pre-resolved those cases). We use a generous
    // sandbox dp for wrapped axes so the Robolectric window / Configuration
    // has a finite, coherent size; the captured PNG is cropped back down
    // to the measured content bounds after capture. `wrapSandboxWidthDp`/`wrapSandboxHeightDp`
    // narrow that sandbox per preview WITHOUT fixing the axis (a Wear module's device-less previews
    // measure against the 227dp watch screen, then still crop) — see
    // `discovery.PreviewParams.wrapSandboxWidthDp`.
    val wrapWidth = params.widthDp == null || params.widthDp <= 0
    val wrapHeight = params.heightDp == null || params.heightDp <= 0
    val widthDp =
      params.widthDp?.takeIf { it > 0 }
        ?: params.wrapSandboxWidthDp?.takeIf { it > 0 }
        ?: SANDBOX_WIDTH_DP
    val heightDp =
      params.heightDp?.takeIf { it > 0 }
        ?: params.wrapSandboxHeightDp?.takeIf { it > 0 }
        ?: SANDBOX_HEIGHT_DP
    // Round crop fires for ordinary Compose @Preview captures with a round device, matching
    // Layoutlib / Android Studio. Tile and notification captures have their own surface renderers
    // and are intentionally left out of this Studio @Preview parity mask.
    val isRound = isRoundDevice(params.device) && params.kind == PreviewKind.COMPOSE

    // AS-parity default: previews render with `LocalInspectionMode = true`,
    // matching Android Studio's `@Preview` behaviour, so a preview that
    // branches on `LocalInspectionMode.current` (e.g. stub data instead of a
    // network call) hits the same branch it does in the IDE. Override
    // globally with `-Dcomposeai.render.inspectionMode=false` — the same
    // system-property channel the plugin already uses for `tier` /
    // `outputDir`. Features that strictly need it off (a11y semantics dump,
    // live interaction) force `false` from the inside on the daemon path;
    // this static path writes no a11y sidecars, so the property governs
    // plain previews only. See issue #1584.
    val inspectionMode =
      System.getProperty("composeai.render.inspectionMode")?.toBooleanStrictOrNull() ?: true

    val composeOptions =
      RoborazziComposeOptions.Builder()
        .apply {
          size(widthDp, heightDp)
          if (isRound) addOption(RoundScreenOption)
          if (params.fontScale != 1.0f) fontScale(params.fontScale)
          if (params.uiMode != 0) uiMode(params.uiMode)
          params.locale?.let { locale(it) }
          // Resolve to a concrete ARGB rather than handing Roborazzi the raw
          // (showBackground, backgroundColor) pair: its own default for
          // `showBackground = true` with no colour is white, which is wrong for a
          // night-mode preview. See [ee.schimke.composeai.data.render.PreviewBackground].
          val backingArgb = resolveBackgroundColor(params).toArgb()
          if (backingArgb == ee.schimke.composeai.data.render.PreviewBackground.TRANSPARENT_ARGB) {
            background(false, 0L)
          } else {
            background(true, backingArgb.toLong() and 0xFFFFFFFFL)
          }
          inspectionMode(inspectionMode)
        }
        .build()

    val roborazziOptions =
      RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))

    // Drop any stale .error.json from a prior run before attempting a
    // fresh render. If today's render succeeds, the panel doesn't want
    // to surface yesterday's exception alongside the new PNG.
    // `@ScrollingPreview(modes = [LONG/GIF])` with no other captures lands
    // here with an empty captures list (issue #1524) — use the first data
    // product as the sidecar anchor instead so the same per-output error
    // surface keeps working.
    val pngFile =
      preview.captures.firstOrNull()?.let { outputFileFor(it, outputDir) }
        ?: outputFileFor(preview.dataProducts.first(), outputDir)
    RenderErrorSidecar.deleteStale(pngFile)
    // Drop any font-fallback warnings sidecar from a prior run too, so a now-clean (or now-fatal)
    // render doesn't leave yesterday's warning card beside the fresh PNG.
    RenderWarningsSidecar.deleteStale(pngFile)
    // Drop any IR sidecar from a prior run before rendering. The renders dir is reused, and
    // `BundlePreviewTask.resolvePreviewIr` treats any non-empty sidecar as authoritative — so a
    // preview that stops producing IR (RC wrapper removed, tile serialization fails, kind
    // changed) would otherwise leave stale `<stem>.{rc,tilelayout,tileresources}` that a later
    // bundle embeds while dropping the now-classpath-backed preview's classes, breaking replay.
    deleteStaleIrSidecars(pngFile)

    // Catch Throwable per preview. Today, a throw inside the preview
    // function fails the JUnit test, fails the Test task, and the
    // panel shows a generic "Build failed" message — ONE broken
    // preview hides every sibling preview's render. By catching here
    // and writing a structured `.error.json` sidecar, the failing
    // card surfaces the actual exception while every other preview
    // still produces its PNG.
    //
    // Catching Throwable (not Exception) so AssertionErrors from
    // `require`/`check` calls in user code surface the same way as
    // RuntimeExceptions. JVM-fatal throwables already terminated the
    // JVM before we got here.
    // Stamp the current preview id onto the launcher-widget metadata channel so render-side
    // helpers (`:glance-preview-runtime`'s `GlanceAppWidgetContent`) can `offer(...)` widget
    // metadata without an explicit `previewId` parameter. The connector's registry consumes
    // the offered entry post-render via the same id. Cleared in `finally` so the next
    // preview's render doesn't accidentally carry this one's id.
    ee.schimke.composeai.daemon.LauncherWidgetMetadataChannel.setCurrentPreviewId(preview.id)
    // Arm the IR channel for this preview so a Remote Compose / protolayout producer can offer
    // its captured intermediate representation during composition; drained post-render below.
    ee.schimke.composeai.data.render.IrSidecarChannel.setCurrentPreviewId(preview.id)
    // Drop any named-override knobs a prior preview declared so this preview's `previewOverride*`
    // lookups accumulate a clean set (drained into the overrides sidecar below).
    ee.schimke.composeai.overrides.PreviewOverrideController.clearDeclarations()
    // Same for the Remote Compose knob declarations (reflectively — the alpha-gated connector may
    // be
    // absent), so preview B doesn't serialize the Remote Compose knobs preview A declared in the
    // same
    // JVM into its `renders/<stem>.remotecompose.json` sidecar.
    clearRemoteComposeDeclarations()
    // Seed any `@OverrideVariant` values onto the controller so this synthetic variant preview's
    // `previewOverride*` reads resolve to the flipped knob(s). Replaces the whole seed map, so an
    // ordinary preview (null overrides → empty map) clears the prior variant's seeds — no leakage
    // between previews. Same `ControllerPreviewOverrideHost` seam the daemon's
    // `renderNow.overrides`
    // lane feeds; here the batch render owns the controller lifecycle directly.
    ee.schimke.composeai.overrides.PreviewOverrideController.set(overrideSeedMap(preview))
    // Arm the per-preview downloadable-font tracker so a face that falls back to Roboto during THIS
    // render is attributed to this preview (drained right after the render below).
    FontResolutionDiagnostics.beginPreview()
    // Same for coil: arm the per-preview image-load tracker so a request that fails or is still in
    // flight at capture time is attributed to THIS preview (drained right after the render below).
    CoilLoadDiagnostics.beginPreview()
    try {
      if (params.kind == PreviewKind.ACTIVITY || params.kind == PreviewKind.APP_TOUR) {
        // App-level previews: no composition to host — the real activity owns its content.
        // Apply the same qualifiers/fontScale the composable path would, then hand the whole
        // launch → navigate → capture loop to [AppTourRenderer].
        applyPreviewQualifiers(
          widthDp = widthDp,
          heightDp = heightDp,
          isRound = isRoundDevice(params.device),
          locale = params.locale,
          uiMode = params.uiMode,
          density = params.density,
        )
        org.robolectric.RuntimeEnvironment.setFontScale(params.fontScale)
        AppTourRenderer.render(preview, outputDir, roborazziOptions)
      } else {
        renderDefault(
          params = params,
          widthDp = widthDp,
          heightDp = heightDp,
          wrapWidth = wrapWidth,
          wrapHeight = wrapHeight,
          outputDir = outputDir,
          roborazziOptions = roborazziOptions,
          composeOptions = composeOptions,
          inspectionMode = inspectionMode,
        )
      }
      // A downloadable font that couldn't be resolved rendered in the platform fallback (Roboto) —
      // wrong typeface for a branded preview. By default that's fatal (throw → the catch below
      // drops
      // the PNG and writes `.error.json`, same as any render failure). With
      // `-Dcomposeai.fonts.failOnFallback=false` keep the PNG and record the fell-back faces in a
      // non-fatal `<png>.warnings.json` instead.
      val fontFallbacks = FontResolutionDiagnostics.drainPreview()
      if (fontFallbacks.isNotEmpty() && FontResolutionDiagnostics.failOnFallback) {
        throw FontFallbackException(fontFallbacks)
      }
      // Coil requests that didn't resolve are never fatal — a blank image is a legitimate thing to
      // capture (an offline/empty state), and the renderer can't conjure bytes the sandbox can't
      // reach. They ride in the same warnings sidecar so the blank is diagnosable.
      RenderWarningsSidecar.writeOrDelete(
        pngFile,
        fontFallbacks,
        CoilLoadDiagnostics.drainPreview(),
      )
      // Render succeeded: if the preview's flavour captured an IR, write it beside the PNG as
      // the `renders/<stem>.<ext>` sidecar `BundlePreviewTask.resolvePreviewIr` packs.
      writeIrSidecar(pngFile, preview.id)
      // Write the editable knobs the preview declared via `previewOverride*` as the
      // `renders/<stem>.overrides.json` sidecar `BundlePreviewTask.resolvePreviewOverrides` packs.
      writeOverridesSidecar(pngFile)
      // Same, for Remote Compose named-value knobs declared through `LocalRemoteComposeHost`:
      // `renders/<stem>.remotecompose.json`, packed by
      // `BundlePreviewTask.resolvePreviewRemoteCompose`.
      writeRemoteComposeSidecar(pngFile)
    } catch (e: Throwable) {
      System.err.println(
        "Render failed for ${preview.className}.${preview.functionName}: ${e.message}"
      )
      RenderErrorSidecar.write(pngFile, e)
      // Don't rethrow — keep the JUnit test green so a single
      // broken preview doesn't fail the Test task and prevent
      // sibling previews from rendering. The structured error
      // travels through the sidecar; VS Code reads it via
      // `gradleService.readPreviewRenderError` and shows the
      // exception detail on the failing card.
    } finally {
      ee.schimke.composeai.daemon.LauncherWidgetMetadataChannel.setCurrentPreviewId(null)
      // Clear the IR channel id and drop any capture this preview left behind (e.g. on a
      // render that threw after offering) so it can't leak onto the next preview.
      ee.schimke.composeai.data.render.IrSidecarChannel.consume(preview.id)
      ee.schimke.composeai.data.render.IrSidecarChannel.setCurrentPreviewId(null)
    }
  }

  /**
   * Write the IR captured during the just-finished render (if any) as a sidecar next to [pngFile],
   * keyed by the PNG stem so it matches the `renders/<stem>.<ext>` contract
   * `BundlePreviewTask.resolvePreviewIr` reads: `.rc` for a Remote Compose document, or
   * `.tilelayout` (+ `.tileresources`) for a Wear protolayout proto. Best-effort — a write failure
   * must not derail the PNG render path.
   */
  private fun deleteStaleIrSidecars(pngFile: File) {
    val dir = pngFile.parentFile ?: return
    val stem = pngFile.nameWithoutExtension
    for (ext in listOf("rc", "tilelayout", "tileresources")) {
      val f = File(dir, "$stem.$ext")
      if (f.isFile && !f.delete()) {
        System.err.println("Failed to delete stale IR sidecar: ${f.absolutePath}")
      }
    }
  }

  private fun writeIrSidecar(pngFile: File, previewId: String) {
    val capture = ee.schimke.composeai.data.render.IrSidecarChannel.consume(previewId) ?: return
    try {
      val dir = pngFile.parentFile ?: return
      val stem = pngFile.nameWithoutExtension
      when (capture.format) {
        ee.schimke.composeai.data.render.IrSidecarChannel.FORMAT_REMOTECOMPOSE ->
          File(dir, "$stem.rc").writeBytes(capture.bytes)
        ee.schimke.composeai.data.render.IrSidecarChannel.FORMAT_PROTOLAYOUT -> {
          File(dir, "$stem.tilelayout").writeBytes(capture.bytes)
          capture.resourcesBytes?.let { File(dir, "$stem.tileresources").writeBytes(it) }
        }
      }
    } catch (e: Throwable) {
      System.err.println("Failed to write IR sidecar for $previewId: ${e.message}")
    }
  }

  /**
   * Write the editable knobs the preview declared via `previewOverride*` during the just-finished
   * render as the `renders/<stem>.overrides.json` sidecar
   * `BundlePreviewTask.resolvePreviewOverrides` packs. An empty set deletes any stale sidecar so a
   * preview that stopped declaring knobs doesn't keep an old one. Best-effort — a write failure
   * must not derail the PNG render path. The `overrides.json` suffix is kept in lockstep with
   * `PreviewBundleFormat.BUNDLE_OVERRIDES_SIDECAR_EXT`.
   */
  private fun writeOverridesSidecar(pngFile: File) {
    val declarations = ee.schimke.composeai.overrides.PreviewOverrideController.declarations()
    val dir = pngFile.parentFile ?: return
    val sidecar = File(dir, "${pngFile.nameWithoutExtension}.overrides.json")
    try {
      if (declarations.isEmpty()) {
        if (sidecar.isFile && !sidecar.delete()) {
          System.err.println("Failed to delete stale overrides sidecar: ${sidecar.absolutePath}")
        }
        return
      }
      val payload =
        ee.schimke.composeai.data.overrides.PreviewOverridesPayload(declarations = declarations)
      sidecar.writeText(
        overridesSidecarJson.encodeToString(
          ee.schimke.composeai.data.overrides.PreviewOverridesPayload.serializer(),
          payload,
        )
      )
    } catch (e: Throwable) {
      System.err.println("Failed to write overrides sidecar: ${e.message}")
    }
  }

  /**
   * Write the Remote Compose named-value knobs the preview declared through
   * `LocalRemoteComposeHost` as the `renders/<stem>.remotecompose.json` sidecar
   * `BundlePreviewTask.resolvePreviewRemoteCompose` packs. An empty / absent set deletes any stale
   * sidecar. Read **reflectively** so this renderer never hard-depends on the alpha-gated
   * `:data-remotecompose-connector` (`RemoteComposeController` is only on the classpath when the
   * consumer ships the `compose-remote` runtime — a non-Remote-Compose module renders exactly as
   * before). Best-effort — a write failure must not derail the PNG render path. The
   * `remotecompose.json` suffix is kept in lockstep with
   * `PreviewBundleFormat.BUNDLE_REMOTECOMPOSE_SIDECAR_EXT`.
   */
  /**
   * Reflectively drop the process-static Remote Compose declarations before a render, mirroring the
   * `PreviewOverrideController.clearDeclarations()` call in the render loop. Reflective so this
   * renderer keeps no hard dependency on the alpha-gated `:data-remotecompose-connector` — a no-op
   * when the connector isn't on the classpath.
   */
  private fun clearRemoteComposeDeclarations() {
    try {
      val cls = Class.forName("ee.schimke.composeai.daemon.RemoteComposeController")
      val instance = cls.getField("INSTANCE").get(null)
      cls.getMethod("clearDeclarations").invoke(instance)
    } catch (_: ClassNotFoundException) {
      // No Remote Compose runtime on the classpath — nothing to clear.
    } catch (_: ReflectiveOperationException) {
      // Best-effort: a reflective miss must not derail the render path.
    }
  }

  private fun writeRemoteComposeSidecar(pngFile: File) {
    val dir = pngFile.parentFile ?: return
    val sidecar = File(dir, "${pngFile.nameWithoutExtension}.remotecompose.json")
    val json =
      try {
        val cls = Class.forName("ee.schimke.composeai.daemon.RemoteComposeController")
        val instance = cls.getField("INSTANCE").get(null)
        cls.getMethod("declarationsJson").invoke(instance) as String?
      } catch (_: ClassNotFoundException) {
        // The consumer doesn't ship the Remote Compose runtime — nothing to write.
        null
      } catch (_: ReflectiveOperationException) {
        null
      }
    try {
      if (json == null) {
        if (sidecar.isFile && !sidecar.delete()) {
          System.err.println(
            "Failed to delete stale remotecompose sidecar: ${sidecar.absolutePath}"
          )
        }
        return
      }
      sidecar.writeText(json)
    } catch (e: Throwable) {
      System.err.println("Failed to write remotecompose sidecar: ${e.message}")
    }
  }

  /**
   * Resolve one capture's output file by stripping the module-relative `renders/` prefix the
   * manifest carries and re-rooting under the configured output dir.
   */
  private fun outputFileFor(capture: RenderPreviewCapture, outputDir: File): File {
    val leafName = capture.renderOutput.substringAfterLast('/').ifEmpty { "${preview.id}.png" }
    return File(outputDir, leafName)
  }

  private fun outputFileFor(product: RenderPreviewArtifact, outputDir: File): File {
    val rootDir = outputDir.parentFile ?: outputDir
    return File(rootDir, product.output)
  }

  private sealed interface RenderJob {
    val advanceTimeMillis: Long?
    val scroll: ScrollCapture?
    val outputFile: File
  }

  private data class CaptureRenderJob(
    val capture: RenderPreviewCapture,
    override val outputFile: File,
  ) : RenderJob {
    override val advanceTimeMillis: Long? = capture.advanceTimeMillis
    override val scroll: ScrollCapture? = capture.scroll
  }

  private data class ProductRenderJob(
    val product: RenderPreviewArtifact,
    override val outputFile: File,
  ) : RenderJob {
    override val advanceTimeMillis: Long? = product.advanceTimeMillis
    override val scroll: ScrollCapture? = product.scroll
  }

  /**
   * Default render path — paused `mainClock`, pump by [CAPTURE_ADVANCE_MS], capture.
   *
   * Replaces the earlier `captureRoboImage { @Composable }` flow. The composable overload drives
   * the composition to idle before capturing, which hangs on infinite animations
   * (`CircularProgressIndicator()`, `rememberInfiniteTransition`, hand-rolled `withFrameNanos`
   * loops) and was the root cause of the 12-minute / OOM runs that PR #14 papered over. With
   * `mainClock.autoAdvance = false` we never wait for idle — each `advanceTimeByFrame()`
   * deterministically dispatches one frame cycle, and after by [CAPTURE_ADVANCE_MS] we just capture
   * whatever the composition has drawn.
   *
   * `ui-test-manifest` (injected into the consumer's `testImplementation` by the plugin) supplies
   * the `ComponentActivity` entry `createAndroidComposeRule` needs; we still register the component
   * explicitly with `ShadowPackageManager` to satisfy Robolectric 4.13+'s intent-resolution check
   * (robolectric/robolectric#4736).
   *
   * Options (size, locale, uiMode, round, fontScale, background, inspectionMode) are applied by
   * hand here rather than through [RoborazziComposeOptions], because the option chain wants an
   * [ActivityScenario] it owns and that's awkward to share with a [ComposeTestRule].
   * size/locale/uiMode/round go through Robolectric resource qualifiers; fontScale goes through
   * `RuntimeEnvironment.setFontScale` (matching Roborazzi's own `RoborazziComposeFontScaleOption`)
   * since fontScale is a Configuration field, not a qualifier; background/inspection go through
   * composition locals.
   */
  @OptIn(ExperimentalRoborazziApi::class)
  private fun renderDefault(
    params: RenderPreviewParams,
    widthDp: Int,
    heightDp: Int,
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    outputDir: File,
    roborazziOptions: RoborazziOptions,
    composeOptions: RoborazziComposeOptions,
    inspectionMode: Boolean,
  ) {
    val appContext: android.app.Application =
      androidx.test.core.app.ApplicationProvider.getApplicationContext()
    org.robolectric.Shadows.shadowOf(appContext.packageManager)
      .addActivityIfNotPresent(
        android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name)
      )

    // Seed `Typeface.sSystemFontMap` with the Pixel-system-family aliases
    // that map onto public Google Fonts. Makes
    // `Font(DeviceFontFamilyName("roboto-flex"), weight = …)` — the
    // production shape consumers use when targeting Pixel's bundled
    // variable fonts — resolve to a cached downloadable TTF instead of
    // silently falling back to Roboto. Idempotent + process-level cached,
    // so the first preview pays the download cost once per session and
    // every subsequent preview hits the warm map. See
    // [PixelSystemFontAliases].
    PixelSystemFontAliases.seedSystemFonts()

    // When the consumer ships `emoji2-bundled`, initialise EmojiCompat with its bundled config so
    // emoji route through the app's own version-pinned NotoColorEmoji rather than the platform
    // fallback — closing the preview↔device emoji fidelity gap. No-op otherwise. See
    // [EmojiCompatRenderSupport].
    EmojiCompatRenderSupport.ensureInitialized(appContext)

    applyPreviewQualifiers(
      widthDp = widthDp,
      heightDp = heightDp,
      isRound = isRoundDevice(params.device) && params.kind == PreviewKind.COMPOSE,
      locale = params.locale,
      uiMode = params.uiMode,
      density = params.density,
    )
    // fontScale isn't a Robolectric resource qualifier — it's a
    // Configuration field. `RuntimeEnvironment.setFontScale(Float)` is the
    // Robolectric API that updates Configuration before the activity
    // launches; Roborazzi's own `RoborazziComposeFontScaleOption` uses the
    // same entrypoint. Applied unconditionally (default 1f) so it resets
    // between previews sharing the same Robolectric sandbox.
    org.robolectric.RuntimeEnvironment.setFontScale(params.fontScale)

    // a11y data products (ATF + hierarchy) are produced exclusively by the daemon path
    // (`daemon/android`'s `RenderEngine` drives the post-capture walk via
    // `AccessibilityHierarchyExtension` against the live SemanticsNode root). This
    // standalone Robolectric `composePreviewRender` task is the "normal render only" path — no
    // accessibility sidecars are written here. `LocalInspectionMode` defaults to `true`
    // (Android-Studio `@Preview` parity, issue #1584) and is overridable per render run via
    // `-Dcomposeai.render.inspectionMode`; the a11y subject is unaffected because no ATF /
    // hierarchy walk runs on this path.

    // The v2 replacement (`androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`)
    // that the deprecation warning suggests was added in compose-ui-test
    // 1.11.0-alpha03; the renderer compiles against `compose-bom-compat`
    // (1.9.5) so the v2 entry point isn't on our compile classpath. Once
    // the compat floor moves up, drop the suppression and switch.
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    val description =
      org.junit.runner.Description.createTestDescription(
        this::class.java,
        "renderDefault_${preview.id}",
      )
    // Captures the content's intrinsic pixel size when either axis wraps.
    // Read after `captureRoboImage` to crop the PNG down to the composable's
    // actual bounds.
    var measured: IntSize? = null
    // [android.view.View] read from inside composition so the
    // post-capture overlay can reflect into AndroidComposeView's
    // `focusOwner.getFocusRect()` to find the focused element's
    // bounds. `null` until the first composition.
    var capturedView: android.view.View? = null
    // The focus-driver state lives on the connector's [FocusController]; the around-composable
    // installed by [FocusOverrideExtension] reads from it via snapshot state. Reset before
    // each render so the previous preview's tab walk doesn't leak into this one.
    FocusController.resetForNewSession()
    val statement =
      object : org.junit.runners.model.Statement() {
        override fun evaluate() {
          rule.mainClock.autoAdvance = false
          // Paint the preview background on the host activity's window
          // rather than wrapping our setContent body in
          // `Box(Modifier.fillMaxSize().background(bg)) { … }`. The
          // compose-compiler emits `ComposeUiNode.setCompositeKeyHash`
          // calls for every layout node in the renderer bytecode; those
          // methods only exist on compose-ui 1.8+. Consumers pinned to
          // older Compose BOMs (e.g. WearTilesKotlin's 1.6.x) hit
          // `NoSuchMethodError` at render time. Painting the background
          // natively sidesteps the wrapper composable entirely — the
          // preview's own @Composable body still runs through its own
          // compose-compiler (the consumer's), so the consumer's
          // emitted bytecode naturally targets their runtime.
          val bg = resolveBackgroundColor(params).toArgb()
          rule.runOnUiThread {
            // Give the host activity the consumer's `<application android:theme>` before anything
            // inflates a platform View. Without it an `AndroidView` whose content resolves an
            // app-owned `?attr/…` dies with `UnsupportedOperationException: Failed to resolve
            // attribute …` and takes the whole render (and therefore the PNG) with it. See
            // [PreviewHostTheme]. Applied *before* the background paint below so a theme's
            // `android:windowBackground` can't survive into the capture.
            PreviewHostTheme.applyTo(rule.activity)
            rule.activity.window.decorView.setBackgroundColor(bg)
          }
          // Swap coil's singleton `ImageLoader` for one whose dispatchers are the immediate main
          // dispatcher, so `AsyncImage` resolves INLINE during the composition below instead of
          // on a `Dispatchers.IO` pool nothing here drives — otherwise the painter never gets a
          // result, reports no intrinsic size, and a `ContentScale.FillWidth` image eats the whole
          // frame (issue #2952). Idempotent and a no-op when the consumer has no coil; must run
          // before `setContent` because `AsyncImagePainter` starts its load from `onRemembered`.
          CoilPreviewSupport.installIfPresent(rule.activity)
          // Mirror Compose's system long-screenshot signal so composables
          // can suppress transient UI (e.g. Wear's `ScreenScaffold` scroll
          // indicator) by reading `LocalScrollCaptureInProgress.current`.
          //
          // Only set for `@ScrollingPreview(modes = [LONG])`: stitched
          // captures composite many frames into one tall PNG, and a
          // fading indicator at arbitrary opacity per slice dominates
          // the diff. END mode is a single frame at the natural
          // scroll-to-end position — the indicator there is what a real
          // app would show, so we leave it visible.
          //
          // `LocalScrollCaptureInProgress` shipped in compose-ui 1.7.
          // Looked up reflectively so the renderer compiles against an
          // older Compose floor and consumers on pre-1.7 Compose get a
          // null lookup (scroll-capture becomes a no-op — the natural
          // transient UI stays visible at stitched seams). See
          // [ScrollCaptureInProgressLocal].
          val scrollCaptureInProgress =
            preview.captures.any { it.scroll?.mode == ScrollMode.LONG } ||
              preview.dataProducts.any { it.scroll?.mode == ScrollMode.LONG }
          val scrollCaptureProvidable =
            if (scrollCaptureInProgress) ScrollCaptureInProgressLocal.get() else null
          // Wear-only: flatten `TransformingLazyColumn` item scaling for
          // `@ScrollingPreview(..., reduceMotion = true)` captures.
          // Without this, items mid-transform at a viewport edge get
          // captured at non-1.0 scale, then the stitcher paints that
          // same item again (at its next-slice scale) one viewport
          // down — producing the ghost/duplicate rows at slice seams
          // the user sees on long Wear previews. `LocalReduceMotion`
          // is looked up reflectively so this file stays free of a
          // Wear Compose compile dep; on non-Wear modules the lookup
          // returns null and the flag is a no-op.
          //
          // The annotation flag only governs TOP / END stills — LONG and
          // GIF each have exactly one sensible setting and ignore it:
          //
          // * LONG is a stitched STILL — baked-in mid-scale items are
          //   exactly what the content-aware stitcher cannot collapse
          //   (Confetti's `HomeListViewLongPreview`, the one
          //   reduceMotion = false consumer, shipped ghost/duplicate card
          //   bands for months) — so the LONG capture ALWAYS flattens.
          //   Mirrors the grown scroll-SVG path (`flattenWearScroll` in
          //   `:daemon:android`'s `RenderEngine`).
          // * GIF frames genuinely animate, so the GIF capture ALWAYS
          //   keeps motion — flattening there would only hide what the
          //   watch shows.
          //
          // Both are implemented by flipping [reduceMotionState] around
          // the respective capture and restoring it after, so one
          // multi-mode composition yields a clean LONG still and a lively
          // GIF with no per-mode configuration.
          val annotationReduceMotion =
            preview.captures.any { it.scroll?.reduceMotion == true } ||
              preview.dataProducts.any { it.scroll?.reduceMotion == true }
          val reduceMotionLocal =
            if (annotationReduceMotion || scrollCaptureInProgress) WearReduceMotionLocal.get()
            else null
          val reduceMotionState = androidx.compose.runtime.mutableStateOf(annotationReduceMotion)
          // @AnimatedPreview(showCurves = true): capture the slot table
          // by wrapping the composition in `InspectablePreviewContent`,
          // which seeds parameter information collection and snapshots
          // `currentComposer.compositionData` into the holder for
          // `AnimationInspector.attach(...)` to read post-settle. Held
          // nullable so non-curve previews don't pay the
          // collectParameterInformation cost.
          val animationCurveCapture: SlotTreeCapture? =
            preview.captures
              .firstNotNullOfOrNull { it.animation?.takeIf { a -> a.showCurves } }
              ?.let { SlotTreeCapture() }
          // `@FocusedPreview` requests focus drive on at least one capture: the
          // connector-side `FocusOverrideExtension.AroundComposable` installs
          // `LocalInputModeManager provides KeyboardInputModeManager` and the
          // `LaunchedEffect`-driven walk; the renderer just decides whether to wrap.
          //
          // **No more hardcoded focus / keyboard logic should live in this file.** Add new
          // override-driven features as data extensions in `:data-focus-connector` (or peer
          // connector modules) and either register a `DataExtension<PreviewOverrides>`
          // planner here or wrap content with the extension's `AroundComposable` directly,
          // matching the existing ambient / wallpaper / theme pattern. Per-feature renderer
          // branches are a smell — see `docs/AGENTS.md` § "Architecture rules".
          val anyFocusCapture = preview.captures.any { it.focus != null || it.focusGif != null }
          val focusExtension = if (anyFocusCapture) FocusOverrideExtension() else null
          // Soft-keyboard (IME) overlay: always-on. The around-composable shadows
          // `LocalSoftwareKeyboardController` so a preview that focuses a `BasicTextField`
          // or calls `keyboardController.show()` naturally raises the band — no per-capture
          // opt-in needed. The state holder `KeyboardController` is reset before each render
          // so previews don't leak visibility / pressed-key state into one another.
          KeyboardController.resetForNewSession()
          // `device` (or `showSystemUi`, which implies a whole screen) is the preview's own
          // statement that it is a screen rather than a component, and decides whether the band
          // draws at all on a surface too short for the size rule to recognise — a landscape
          // phone, a wearable (issue #3491).
          val keyboardExtension =
            KeyboardOverrideExtension(
              deviceScoped = !params.device.isNullOrBlank() || params.showSystemUi
            )
          // `@AmbientPreview` discovery stamps the same `AmbientCapture` onto every capture
          // of an annotated function (single-shot per function — one preview produces one
          // ambient state). Wrap the composition with `AmbientOverrideExtension` from
          // `:data-ambient-connector` when present so consumer code reading
          // `LocalAmbientModeManager.current?.currentAmbientMode` observes the override.
          // Daemon-driven `renderNow.overrides.ambient` lands at the same extension via the
          // `AmbientPreviewOverrideExtension` planner registered in `RobolectricHost`.
          val ambientExtension =
            preview.captures
              .firstNotNullOfOrNull { it.ambient }
              ?.let { AmbientOverrideExtension(it.toAmbientOverride()) }
          // `@GestureHintPreview` discovery stamps the same `GestureHintCapture` onto every
          // capture of an annotated function. Wrap the composition with `GestureOverrideExtension`
          // from `:data-gestures-connector` so `GestureHint` force-shows the one-handed-gesture
          // indicator. Daemon-driven `renderNow.overrides.gestures.showHints` lands at the same
          // extension via the `GesturePreviewOverrideExtension` planner registered in
          // `RobolectricHost`.
          val gestureHintExtension =
            preview.captures
              .firstNotNullOfOrNull { it.gestureHint }
              ?.let { GestureOverrideExtension(it.toGestureOverride()) }
          // `@PermissionPreview` discovery stamps the same `PermissionsCapture` onto every capture
          // of an annotated function. Constructing `PermissionsOverrideExtension` is what applies
          // the grants: its `init` calls `PermissionsController.set(...)`, which reflectively
          // seeds Robolectric's `ShadowApplication.grantPermissions/denyPermissions`. That has to
          // happen HERE — before `rule.setContent` below — and not from inside the composition,
          // because a permission-gated screen reads
          // `ContextCompat.checkSelfPermission(...)` on its FIRST composition; a seed applied from
          // an effect would leave the screen on the pre-seed branch for the whole capture, which is
          // exactly the regression `PermissionsOverrideIntegrationTest` in `:daemon:android` pins
          // for the daemon lane. Daemon-driven `renderNow.overrides.permissions` reaches the same
          // controller via the `PermissionsPreviewOverrideExtension` planner registered in
          // `RobolectricHost`, so both lanes agree by construction.
          //
          // Built only when the preview actually carries the annotation: the extension's contract
          // is "the grant map is exhaustive", so constructing it unconditionally would deny every
          // permission on every render and change captures that never asked for an override.
          val permissionsExtension =
            preview.captures
              .firstNotNullOfOrNull { it.permissions }
              ?.let { PermissionsOverrideExtension(seed = it.toPermissionsOverride()) }
          // `@LauncherWidgetPreview` discovery stamps the same `LauncherWidgetCapture` onto
          // every capture of an annotated function. Wrap the composition with
          // `LauncherWidgetExtension` from `:data-launcher-widget-connector` so the rendered
          // PNG sizes to the resolved dp footprint of a launcher cell. Daemon-driven
          // `renderNow.overrides.launcherWidget` lands at the same extension via the
          // `LauncherWidgetPreviewOverrideExtension` planner registered in `RobolectricHost`.
          val launcherWidgetExtension =
            preview.captures
              .firstNotNullOfOrNull { it.launcherWidget }
              ?.let { LauncherWidgetExtension(it.toLauncherWidgetOverride()) }
          // Pseudolocale: when `@Preview(locale = "en-XA" | "ar-XB")`, wrap composition with
          // `PseudolocaleOverrideExtension` so `LocalContext.current.resources.getString(...)`
          // — the path `androidx.compose.ui.res.stringResource` walks — returns the
          // pseudolocalised template. The base-locale qualifier rewrite happens in
          // `applyPreviewQualifiers` above. Mirrors the daemon path's
          // `PseudolocalePreviewOverrideExtension` planner registered in `RobolectricHost`.
          val pseudolocaleExtension =
            ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(params.locale)?.let {
              ee.schimke.composeai.daemon.PseudolocaleOverrideExtension(it)
            }
          val providedValues = buildList {
            add(LocalInspectionMode provides inspectionMode)
            LocaleCompositionLocals.providedValue(
                RuntimeEnvironment.getApplication().resources.configuration
              )
              ?.let(::add)
            if (scrollCaptureProvidable != null) {
              add(scrollCaptureProvidable provides scrollCaptureInProgress)
            }
            // XR Compose composables (`Orbiter`, `SpatialElevation`, …) are written once and
            // take a 2D fallback off-device — which is what an ordinary @Preview captures.
            // Since `androidx.xr.compose` 1.0.0-alpha16 that fallback consumes `LocalSession`
            // and null-checks it before branching, so hand it an offline session. Null (and
            // therefore absent) whenever XR isn't on the preview's classpath, which is the
            // overwhelmingly common case. See [OfflineXrSession].
            OfflineXrSession.providedValue(rule.activity)?.let(::add)
            // coil 3 short-circuits into a placeholder-only branch while
            // `LocalInspectionMode` is true (the AS-parity default), which is what leaves an
            // `AsyncImage` blank AND intrinsic-size-less. It exposes
            // `LocalAsyncImagePreviewHandler` to override that branch, so hand it one that
            // runs the real request. Null for coil 2 (no such hook — see
            // [ShadowAsyncImagePainter]) and for the common no-coil consumer. Issue #2952.
            CoilPreviewSupport.previewHandlerProvidedValue()?.let(::add)
          }
            .toTypedArray()
          // `showSystemUi = true` on a phone-shape preview wraps the
          // composition in [SystemBarsFrame] so the captured PNG carries
          // synthetic status + gesture-pill nav bars. Robolectric has no
          // SystemUI process to draw real bars; without the wrapper the
          // canvas comes back at the right device size but with no chrome
          // (issue #256). Conceptually the same as `@PreviewWrapper`,
          // applied automatically here for the system-UI case. Skipped
          // for round Wear devices (circular clip already brands the
          // capture) and for tile previews (tiles fill the whole watch
          // face — bars don't apply).
          val applySystemBars =
            params.showSystemUi && params.kind != PreviewKind.TILE && !isRoundDevice(params.device)
          rule.setContent {
            // Reduce-motion is provided separately from [providedValues]
            // because its value is state-backed: the LONG dispatch below
            // flips [reduceMotionState] on around the stitched capture
            // (and back off for a following GIF), and the recomposition
            // must observe the change.
            val withReduceMotion: @Composable (@Composable () -> Unit) -> Unit = { inner ->
              if (reduceMotionLocal != null) {
                CompositionLocalProvider(reduceMotionLocal provides reduceMotionState.value) {
                  inner()
                }
              } else {
                inner()
              }
            }
            withReduceMotion {
              CompositionLocalProvider(values = providedValues) {
                if (focusExtension != null) {
                  capturedView = androidx.compose.ui.platform.LocalView.current
                }
                val previewBody: @Composable () -> Unit = {
                  val core: @Composable () -> Unit = {
                    MeasuredWrapBox(
                      wrapWidth = wrapWidth,
                      wrapHeight = wrapHeight,
                      onMeasured = { measured = it },
                    ) {
                      strategyFor(params.kind).Render(preview, widthDp, heightDp, previewArgs)
                    }
                  }
                  if (applySystemBars) {
                    SystemBarsFrame(uiMode = params.uiMode) { core() }
                  } else {
                    core()
                  }
                }
                val curveOrPlain: @Composable () -> Unit = {
                  if (animationCurveCapture != null) {
                    InspectablePreviewContent(animationCurveCapture, previewBody)
                  } else {
                    previewBody()
                  }
                }
                val focusOrPlain: @Composable () -> Unit = {
                  if (focusExtension != null) {
                    focusExtension.AroundComposable { curveOrPlain() }
                  } else {
                    curveOrPlain()
                  }
                }
                // `@GestureHintPreview` — installs `LocalGestureRegistry` /
                // `LocalOneHandedGestureEnabled` and force-shows the hint, same
                // `DataExtensionPhase.OuterEnvironment` seam as ambient.
                val gestureHintOrPlain: @Composable () -> Unit = {
                  if (gestureHintExtension != null) {
                    gestureHintExtension.AroundComposable { focusOrPlain() }
                  } else {
                    focusOrPlain()
                  }
                }
                val ambientOrPlain: @Composable () -> Unit = {
                  if (ambientExtension != null) {
                    ambientExtension.AroundComposable { gestureHintOrPlain() }
                  } else {
                    gestureHintOrPlain()
                  }
                }
                // Launcher-widget sizing wraps OUTSIDE ambient/focus/curve so the
                // `Box.size(...)` constrains the visible viewport before any inner
                // override applies — the cell footprint is the launcher chrome, not
                // the preview's own surface chemistry. Matches the connector's
                // `DataExtensionPhase.OuterEnvironment` ordering.
                val launcherWidgetOrPlain: @Composable () -> Unit = {
                  if (launcherWidgetExtension != null) {
                    launcherWidgetExtension.AroundComposable { ambientOrPlain() }
                  } else {
                    ambientOrPlain()
                  }
                }
                // `@PermissionPreview` — the grants were already seeded into Robolectric when the
                // extension was constructed above; this wrap is what scopes the connector's
                // query tracking to this preview (`PermissionsController.beginRender`) and clears
                // the override on dispose so the next preview in the sandbox starts clean. Same
                // `DataExtensionPhase.OuterEnvironment` seam as ambient / gestures.
                val permissionsOrPlain: @Composable () -> Unit = {
                  if (permissionsExtension != null) {
                    // `Around` (not `AroundComposable`): the permissions extension implements the
                    // raw `AroundComposableHook`, because it needs the context's `previewId` to
                    // scope query tracking — the convenience `AroundComposableExtension` base the
                    // gesture / launcher-widget extensions use drops the context.
                    permissionsExtension.Around(
                      ExtensionComposeContext(
                        extensionId = PermissionsOverrideExtension.ID,
                        previewId = preview.id,
                        renderMode = null,
                      )
                    ) {
                      launcherWidgetOrPlain()
                    }
                  } else {
                    launcherWidgetOrPlain()
                  }
                }
                val pseudoOrPlain: @Composable () -> Unit = {
                  if (pseudolocaleExtension != null) {
                    pseudolocaleExtension.AroundComposable { permissionsOrPlain() }
                  } else {
                    permissionsOrPlain()
                  }
                }
                keyboardExtension.AroundComposable { pseudoOrPlain() }
              }
            }
          }
          // With `mainClock.autoAdvance = false` the clock stays at 0
          // until we step it, so capturing each frame at its intended
          // virtual time is a matter of advancing the delta.
          // `DiscoverPreviewsTask` guarantees `captures` is ordered by
          // ascending `advanceTimeMillis`, so we accumulate forward-only.
          var currentTime = 0L
          // A preview whose content is a `Dialog` / `ModalBottomSheet` installs a second Compose
          // owner, and the activity's own root stays present but empty — so `isRoot()` matches two
          // nodes and `onRoot()` is ambiguous (issue #3048). Resolve the subject deliberately in
          // that case. Single-root previews — the overwhelming majority — keep using `onRoot()`
          // verbatim so their captures stay byte-identical.
          //
          // Resolved per capture rather than once up front: a dialog can open from a
          // `LaunchedEffect`, so at virtual time 0 there is only the activity root, and the window
          // appears after the job advances the clock below. A cached selection would also go stale
          // across several timed still captures if a dialog opens or closes between them.
          fun resolveCaptureRoot() = DialogWindowCapture.resolveCaptureRoot(rule)
          val jobs =
            (preview.captures.map { CaptureRenderJob(it, outputFileFor(it, outputDir)) } +
                preview.dataProducts.map { ProductRenderJob(it, outputFileFor(it, outputDir)) })
              .sortedWith(
                compareBy<RenderJob> { it.advanceTimeMillis ?: CAPTURE_ADVANCE_MS }
                  // Animated captures consume a time window after their target.
                  // Keep same-target still/product jobs before that window opens.
                  .thenBy { if (it is CaptureRenderJob && it.capture.animation != null) 1 else 0 }
              )
          var captureIndex = 0
          jobs.forEach { job ->
            // When the job has no explicit `advanceTimeMillis`, default
            // to `CAPTURE_ADVANCE_MS` *or* the current virtual time —
            // whichever is later. Internal advances (e.g. the
            // `@FocusedPreview` settle window) bump `currentTime`
            // past `CAPTURE_ADVANCE_MS` after the first such capture;
            // taking the max keeps the require's monotonicity check
            // satisfied without forcing every consumer to thread an
            // explicit `advanceTimeMillis` through.
            val target = job.advanceTimeMillis ?: maxOf(CAPTURE_ADVANCE_MS, currentTime)
            require(target >= currentTime) {
              "Preview ${preview.id}: output advanceTimeMillis must be ascending " +
                "(got $target after clock was at $currentTime)"
            }
            val delta = target - currentTime
            if (delta > 0) {
              rule.mainClock.advanceTimeBy(delta)
              currentTime = target
            }

            // `@FocusedPreview` per-capture focus walk. Discovery sorts focus indices
            // ascending and emits one capture per index; the around-composable installed
            // by [FocusOverrideExtension] consumes the controller state from inside
            // composition and walks `FocusManager.moveFocus(...)` in a `LaunchedEffect`
            // (driving focus from outside the composition leaves a one-frame
            // off-by-one). After the controller flips, [FocusController.SETTLE_MS]
            // advances enough virtual time for the focus event to reach the interaction
            // stream and the ripple's `IndicationNode` to schedule an invalidation
            // before `captureRoboImage` reads back pixels.
            val capture = (job as? CaptureRenderJob)?.capture
            val focus = capture?.focus
            if (focus != null) {
              rule.runOnUiThread {
                FocusController.set(focus.toFocusOverride())
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
              }
              rule.mainClock.advanceTimeBy(FocusController.SETTLE_MS)
              currentTime += FocusController.SETTLE_MS

              // Material's ripple / pressed state layer is a platform `RippleDrawable`
              // animated on the Android looper / `Choreographer`, not Compose's
              // `mainClock` — so the advance above never progresses it. Idle the main
              // looper by the same window so the platform animation settles (the pressed
              // ripple reaches full expansion) before `captureRoboImage`. Gated to
              // pressed focus captures so ordinary renders keep their existing
              // deterministic timing; the Android clock and Compose's `mainClock` are
              // independent, so this advances only platform animations.
              if (focus.pressed) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                  .idleFor(java.time.Duration.ofMillis(FocusController.SETTLE_MS))
              }
            }

            // @ScrollingPreview(END): drive the first scrollable on the
            // requested axis to the end of its content before a single
            // capture.
            // @ScrollingPreview(LONG): stitched capture — one slice per
            // viewport-height of scroll, then Java2D-stitched into one
            // tall PNG with an optional Wear pill clip. See
            // [handleLongCapture].
            val outputFile = job.outputFile
            outputFile.parentFile?.mkdirs()
            // Clean any stale .error.json from this slot before
            // attempting a fresh render. Today's success must not
            // leave yesterday's failure haunting the panel; today's
            // failure must overwrite yesterday's anyway. The
            // capture-zero sidecar is already cleaned earlier in
            // renderDefault — this covers every other capture and
            // every data product.
            RenderErrorSidecar.deleteStale(outputFile)

            val scroll = job.scroll
            // LONG always flattens Wear motion, even for
            // `reduceMotion = false` annotations (see the
            // [reduceMotionState] doc above). Flip the state on for the
            // stitched capture only and restore it after, so a GIF later
            // in the same composition keeps its morph animation. Each
            // flip settles a short recomposition window on the paused
            // clock so the flattened (or restored) item transforms are
            // what the next capture actually reads back.
            val forceLongFlatten =
              scroll != null &&
                scroll.mode == ScrollMode.LONG &&
                scroll.axis == ScrollAxis.VERTICAL &&
                reduceMotionLocal != null &&
                !annotationReduceMotion
            if (forceLongFlatten) {
              rule.runOnUiThread {
                reduceMotionState.value = true
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
              }
              rule.mainClock.advanceTimeBy(REDUCE_MOTION_FLIP_SETTLE_MS)
              currentTime += REDUCE_MOTION_FLIP_SETTLE_MS
            }
            val longHandled =
              scroll != null &&
                scroll.mode == ScrollMode.LONG &&
                scroll.axis == ScrollAxis.VERTICAL &&
                handleLongCapture(
                  rule = rule,
                  scroll = scroll,
                  previewId = preview.id,
                  heightDp = heightDp,
                  isRound = isRoundDevice(params.device) && params.kind == PreviewKind.COMPOSE,
                  outputFile = outputFile,
                )
            if (forceLongFlatten) {
              rule.runOnUiThread {
                reduceMotionState.value = false
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
              }
              rule.mainClock.advanceTimeBy(REDUCE_MOTION_FLIP_SETTLE_MS)
              currentTime += REDUCE_MOTION_FLIP_SETTLE_MS
            }
            // @ScrollingPreview(GIF): drive the scroller by small
            // steps and encode the sequence as an animated GIF.
            // Same multi-frame shape as LONG, but encodes into a
            // GIF container instead of stitching one tall PNG.
            // GIF always keeps motion (see the [reduceMotionState]
            // doc above): when the annotation flattened the
            // composition for its stills, un-flatten around the GIF
            // frames and restore after.
            val forceGifMotion =
              scroll != null &&
                scroll.mode == ScrollMode.GIF &&
                scroll.axis == ScrollAxis.VERTICAL &&
                reduceMotionLocal != null &&
                annotationReduceMotion &&
                !longHandled
            if (forceGifMotion) {
              rule.runOnUiThread {
                reduceMotionState.value = false
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
              }
              rule.mainClock.advanceTimeBy(REDUCE_MOTION_FLIP_SETTLE_MS)
              currentTime += REDUCE_MOTION_FLIP_SETTLE_MS
            }
            val gifHandled =
              !longHandled &&
                scroll != null &&
                scroll.mode == ScrollMode.GIF &&
                scroll.axis == ScrollAxis.VERTICAL &&
                handleGifCapture(
                  rule = rule,
                  scroll = scroll,
                  previewId = preview.id,
                  heightDp = heightDp,
                  isRound = isRoundDevice(params.device) && params.kind == PreviewKind.COMPOSE,
                  outputFile = outputFile,
                )
            if (forceGifMotion) {
              rule.runOnUiThread {
                reduceMotionState.value = true
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
              }
              rule.mainClock.advanceTimeBy(REDUCE_MOTION_FLIP_SETTLE_MS)
              currentTime += REDUCE_MOTION_FLIP_SETTLE_MS
            }

            // @AnimatedPreview: paused mainClock, advance per frame
            // across the annotation's window, capture each frame,
            // encode as GIF. When `showCurves = true`, the outer
            // setContent has already wrapped the composition in
            // Inspectable(animationCurveRecord, …) so we can attach
            // `AnimationInspector` to sample property values across
            // the same time window.
            val animationHandled =
              !longHandled &&
                !gifHandled &&
                job is CaptureRenderJob &&
                job.capture.animation != null &&
                handleAnimatedCapture(
                    rule = rule,
                    animation = job.capture.animation,
                    previewId = preview.id,
                    isRound = isRoundDevice(params.device) && params.kind == PreviewKind.COMPOSE,
                    outputFile = outputFile,
                    curveCapture = animationCurveCapture,
                  )
                  .also { handled ->
                    // The clock has been driven well past `currentTime`
                    // by the animation pass — keep our local marker in
                    // sync so any subsequent capture in the same
                    // composition asserts ascending time correctly.
                    if (handled) currentTime += job.capture.animation.durationMs.toLong()
                  }

            // @FocusedPreview(gif = true): drive `FocusController` through each step
            // and stitch the captured frames into a single GIF. Inside-composition
            // walk is what makes focus-gained AND focus-lost both fire (see the
            // ripple's `IndicationNode` for the pairing contract); driving from
            // outside via direct `MutableInteractionSource.emit` loses the pairing
            // and leaves stale indications on every visited focusable (#1020).
            val focusGifHandled =
              !longHandled &&
                !gifHandled &&
                !animationHandled &&
                job is CaptureRenderJob &&
                job.capture.focusGif != null &&
                handleFocusGifCapture(
                    rule = rule,
                    focusGif = job.capture.focusGif,
                    previewId = preview.id,
                    isRound = isRoundDevice(params.device) && params.kind == PreviewKind.COMPOSE,
                    outputFile = outputFile,
                  )
                  .also { handled ->
                    if (handled) {
                      val perStep =
                        (job.capture.focusGif.frameDelayMs.toLong() + FocusController.SETTLE_MS)
                      currentTime += perStep * job.capture.focusGif.steps.size
                    }
                  }

            // `handleLongCapture` / `handleGifCapture` returned false
            // (e.g. `NoScrollable` — no scrollable on the requested
            // axis): for a LONG/GIF *data product* job we must NOT
            // fall through to a single `captureRoboImage`. That
            // would write PNG bytes into a `.gif`-named file (no
            // animation in the panel) or stamp the unscrolled first
            // viewport into the long-scroll PNG path (the panel
            // would show what looks like the static base capture
            // under a "scroll long" / "scroll gif" label).
            // Write a structured error sidecar instead so the panel
            // surfaces the real failure.
            val productFellThrough = job is ProductRenderJob && !longHandled && !gifHandled
            // The same reasoning applies to a CAPTURE job that targets a GIF —
            // `@AnimatedPreview`, or `@FocusedPreview(gif = true)`. Those are
            // CaptureRenderJobs, so the guard above never covered them: when the
            // animated pass bailed (no frames, encoder refused, …) they fell
            // through to the single `captureRoboImage` below and wrote PNG bytes
            // to a `.gif` path. Nothing failed, the file looked plausible, and
            // the extension lied to every consumer that trusts it — browser,
            // Figma import, the preview server. Decide on the output extension,
            // not the job type, so a still can never land under a `.gif` name.
            val animatedCaptureFellThrough =
              job is CaptureRenderJob &&
                !animationHandled &&
                !focusGifHandled &&
                outputFile.extension.equals("gif", ignoreCase = true)
            if (animatedCaptureFellThrough) {
              RenderErrorSidecar.write(
                outputFile,
                IllegalStateException(
                  "Animated capture on '${preview.id}' produced no GIF — refusing to " +
                    "write a single-frame PNG into the .gif output path. Most often the " +
                    "preview has an unbounded axis: pin widthDp AND heightDp on an " +
                    "@AnimatedPreview so the frames share one fixed size."
                ),
              )
            } else if (productFellThrough) {
              val modeName = scroll?.mode?.name ?: "?"
              val axisName = scroll?.axis?.name ?: "VERTICAL"
              RenderErrorSidecar.write(
                outputFile,
                IllegalStateException(
                  "@ScrollingPreview($modeName) on '${preview.id}': no scrollable " +
                    "composable found on axis $axisName — refusing to write a " +
                    "single-frame capture into the data product path."
                ),
              )
            } else if (!longHandled && !gifHandled && !animationHandled && !focusGifHandled) {
              // TOP mode is the unscrolled initial frame — no
              // drive, just a capture. END mode drives the first
              // scrollable on the requested axis to its content
              // end before capturing.
              if (scroll != null && scroll.mode == ScrollMode.END) {
                if (!driveScrollingPreviewToEnd(rule, scroll)) {
                  System.err.println(
                    "@ScrollingPreview on '${preview.id}' but no scrollable " +
                      "composable found on axis ${scroll.axis} — capturing initial frame."
                  )
                }
              }
              resolveCaptureRoot()
                .interaction
                .captureRoboImage(file = outputFile, roborazziOptions = roborazziOptions)
            }

            // A `Dialog` / `ModalBottomSheet` preview composes into a window of its own, and the
            // capture spans the whole screen with that window composited into it — so the sticker
            // is the activity frame with the component floating somewhere inside (issue #3048).
            // Crop to the dialog's window instead. The AS-parity wrap crop below cannot do this
            // job: it crops from the origin, and a centred dialog is not at (0,0).
            val capturedDialogWindow =
              if (
                !productFellThrough &&
                  !animatedCaptureFellThrough &&
                  !longHandled &&
                  !gifHandled &&
                  !animationHandled &&
                  !focusGifHandled
              ) {
                // Re-resolved rather than reusing the capture's value: the clock has not moved
                // since, so this sees the same roots, and it keeps the selection out of a mutable
                // var shared across jobs.
                resolveCaptureRoot().semanticsRoot?.let { root ->
                  DialogWindowCapture.shownDialogWindow(root)?.also { window ->
                    DialogWindowCapture.cropPngToDialogWindow(outputFile, root, window)
                  }
                }
              } else {
                null
              }

            // AS-parity: crop the PNG down to the composable's
            // intrinsic size on wrapped axes. Skipped for stitched
            // LONG output, scroll GIF output, @AnimatedPreview GIF
            // output, and @FocusedPreview(gif=true) GIF output —
            // those files' dimensions are the full scrollable
            // extent / frame size, not the composable's intrinsic box.
            // Also skipped when the frame was already cropped to a dialog window above, which has
            // framed it to the component already.
            if (
              capturedDialogWindow == null &&
                !productFellThrough &&
                !animatedCaptureFellThrough &&
                !longHandled &&
                !gifHandled &&
                !animationHandled &&
                !focusGifHandled &&
                (wrapWidth || wrapHeight) &&
                measured != null
            ) {
              cropPngTopLeft(
                file = outputFile,
                wrapWidth = wrapWidth,
                wrapHeight = wrapHeight,
                measured = measured!!,
              )
            }
            if (
              capturedDialogWindow == null &&
                !productFellThrough &&
                !animatedCaptureFellThrough &&
                !longHandled &&
                !gifHandled &&
                !animationHandled &&
                !focusGifHandled
            ) {
              val resizeDensity = params.density ?: 2.0f
              resizeFixedAxesPng(
                file = outputFile,
                targetWidth =
                  if (!wrapWidth && params.device == null && params.widthDp != null)
                    (widthDp * resizeDensity).roundHalfUpPx()
                  else null,
                targetHeight =
                  if (!wrapHeight && params.device == null && params.heightDp != null)
                    (heightDp * resizeDensity).roundHalfUpPx()
                  else null,
              )
            }

            // `@FocusedPreview(overlay = true)`: post-process the captured PNG with a
            // stroke + label drawn over the currently-focused element. Implementation
            // lives in `:data-focus-connector`'s [FocusOverlay]; pre-overlay capture is
            // preserved alongside as `<basename>.raw.png`.
            if (focus?.overlay == true) {
              FocusOverlay.apply(capturedView, outputFile, focus.toFocusOverride())
            }

            // ATF / hierarchy production lives in `daemon/android`'s `RenderEngine` —
            // the standalone Robolectric `composePreviewRender` Task is "normal render only"
            // and never writes accessibility sidecars. Consumers that want a11y data
            // run the daemon (VS Code, `compose-preview a11y`, MCP / agent flows).

            // Display filters — post-capture colour-matrix variants. Gated on
            // `composeai.displayfilter.filters` being non-empty; the gradle plugin
            // forwards it from the `composePreview.displayFilter.filters` Gradle
            // property. Wrapped in try/catch so a filter failure never invalidates the
            // just-captured PNG. Data dir mirrors the daemon convention
            // (`<renders>/../data/<previewId>/`) so consumers see the same on-disk
            // layout whether a render came through the daemon or the gradle plugin.
            if (job is CaptureRenderJob) {
              // Both producers share the previews-root `data/` dir and key on the
              // capture's file stem, not preview.id: a preview can emit several
              // captures (manual-clock fan-out, TOP/END scroll, focus/animated) and
              // preview.id would make later captures overwrite earlier siblings'
              // variants. Matches the desktop path.
              val productDataDir = (outputDir.parentFile ?: outputDir).resolve("data")
              val productPreviewId = outputFile.nameWithoutExtension
              val displayFilters = DisplayFilterConfig.fromSystemProperties()
              if (displayFilters.isNotEmpty()) {
                try {
                  DisplayFilterDataProducer.writeArtifacts(
                    rootDir = productDataDir,
                    previewId = productPreviewId,
                    pngFile = outputFile,
                    filters = displayFilters,
                  )
                } catch (t: Throwable) {
                  System.err.println(
                    "RobolectricRenderTest: displayfilter write failed for " +
                      "${preview.id}: ${t.javaClass.simpleName}: ${t.message}"
                  )
                }
              }

              // Device frame — composite the capture into a real device-art bezel
              // (round Wear watch, phone) with hardware buttons. Gated on
              // `composeai.deviceframe.device`; same data dir + best-effort try/catch
              // discipline as the display-filter block above so a fetch/compose failure
              // never invalidates the just-captured PNG.
              val deviceFrame = DeviceFrameConfig.fromSystemProperties()
              if (deviceFrame != null) {
                try {
                  DeviceFrameDataProducer.writeArtifacts(
                    rootDir = productDataDir,
                    previewId = productPreviewId,
                    pngFile = outputFile,
                    device = preview.params.device,
                    settings = deviceFrame,
                    source = CachedDeviceArtSource(deviceFrame.cacheDir),
                  )
                } catch (t: Throwable) {
                  System.err.println(
                    "RobolectricRenderTest: deviceframe write failed for " +
                      "${preview.id}: ${t.javaClass.simpleName}: ${t.message}"
                  )
                }
              }
            }
            if (job is CaptureRenderJob) captureIndex++
          }
        }
      }
    rule.apply(statement, description).evaluate()
  }

  // a11y is no longer produced from this Robolectric path — the daemon's `RenderEngine`
  // owns the walk; see `:daemon:android` for the post-capture hierarchy / ATF code.

  /**
   * Match how Roborazzi's `RoborazziComposeSizeOption` / `LocaleOption` / `UiModeOption` express
   * themselves as Robolectric qualifiers — applied before the ComposeTestRule's ActivityScenario
   * launches so the Configuration the activity picks up has our intended dimensions / locale /
   * night bits.
   *
   * Order matters: Robolectric's parser (and Android's underlying grammar —
   * https://developer.android.com/guide/topics/resources/providing-resources#QualifierRules) is
   * strict about the sequence. Locale comes before smallest-width/width/height, which come before
   * orientation, which comes before night-mode, which comes before density. Out-of-order qualifiers
   * produce `IllegalArgumentException: failed to parse qualifiers` at runtime.
   */
  private fun applyPreviewQualifiers(
    widthDp: Int,
    heightDp: Int,
    isRound: Boolean,
    locale: String?,
    uiMode: Int,
    density: Float?,
  ) {
    // Pseudolocales (`en-XA`, `ar-XB`) aren't first-class Android locales — they have no
    // `values-en-rXA/` resources to load. Substitute the base locale (`en`) before emitting
    // the qualifier so the framework still finds default-locale strings, and append `ldrtl`
    // for `ar-XB` so the resource framework reports an RTL configuration. The
    // pseudolocalisation of the strings themselves happens in the around-composable
    // `PseudolocaleOverrideExtension` wrapped in `setContent` above.
    val pseudo = ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(locale)
    val effectiveLocale = if (pseudo != null) pseudo.baseTag else locale
    // A real RTL locale (`ar`, `he`, `fa`, …) also needs `ldrtl` so the layout mirrors like a real
    // device — `ar-XB` isn't the only RTL case.
    val rtl =
      pseudo?.isRtl == true ||
        (pseudo == null &&
          ee.schimke.composeai.data.pseudolocale.LocaleDirection.isRtl(effectiveLocale))
    val qualifiers = buildList {
      if (!effectiveLocale.isNullOrBlank()) add(effectiveLocale)
      if (rtl) add("ldrtl")
      // `sw<n>dp-w<n>dp-h<n>dp` — smallest width has to precede available width in the grammar,
      // and it has to be emitted at all, or Robolectric's baseline `sw320dp` survives our
      // incremental `setQualifiers("+…")` and the Configuration contradicts the viewport we just
      // set (issue #3309).
      addAll(ee.schimke.composeai.data.render.previewSizeQualifiers(widthDp, heightDp))
      if (isRound) add("round")
      // The frame decides, not the request — shared with the daemon's two qualifier builders
      // (`RenderEngine.applyPreviewQualifiers`, `RobolectricHost`) so a preview rendered through
      // the
      // plugin and the same preview rendered through the daemon land on the same `Configuration`.
      // There is no separate request on this lane: `device = "spec:…,orientation=portrait"` is
      // already resolved into these dimensions by `DeviceDimensions`.
      ee.schimke.composeai.data.render
        .previewOrientationQualifier(widthDp, heightDp, requested = null)
        ?.let { add(it) }
      if (uiMode != 0) {
        when (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
          android.content.res.Configuration.UI_MODE_NIGHT_YES -> add("night")
          android.content.res.Configuration.UI_MODE_NIGHT_NO -> add("notnight")
        }
      }
      // `<n>dpi` — same shape sergio-sastre/ComposablePreviewScanner's
      // RobolectricDeviceQualifierBuilder emits, so output dimensions
      // match what Studio (and a Roborazzi/scanner-support setup) renders
      // for the same `@Preview`. Without this Robolectric defaults to
      // `mdpi` (1.0x) and bitmaps come out smaller than Studio's preview.
      if (density != null && density > 0f) {
        add("${(density * 160).toInt()}dpi")
      }
    }
    if (qualifiers.isNotEmpty()) {
      org.robolectric.RuntimeEnvironment.setQualifiers("+${qualifiers.joinToString("-")}")
    }
  }

  /**
   * AS-parity wrap-to-content: measure the strategy's composable with unbounded constraints on
   * wrapped axes, capture the child's pixel size via [onMeasured], then size the outer Box to
   * match. Doing this in a single layout pass (rather than via onGloballyPositioned) keeps the
   * measurement deterministic even when Compose's post-layout scheduling is short-circuited by
   * Robolectric. Measures with bounded sandbox constraints (not Infinity): that's what Android
   * Studio's preview pane does, and it's the only shape that `Modifier.fillMax*` / `LazyColumn`
   * accept without throwing from `InlineClassHelper`. Min constraint on wrapped axes is relaxed to
   * 0 so small composables can shrink below the sandbox; `fillMaxWidth` composables still measure
   * at the sandbox width and no width-crop happens on that axis.
   */
  @Composable
  private fun MeasuredWrapBox(
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    onMeasured: (IntSize) -> Unit,
    content: @Composable () -> Unit,
  ) {
    Box(
      modifier =
        Modifier.layout { measurable, constraints ->
          val wrappedConstraints =
            Constraints(
              minWidth = if (wrapWidth) 0 else constraints.maxWidth,
              maxWidth = constraints.maxWidth,
              minHeight = if (wrapHeight) 0 else constraints.maxHeight,
              maxHeight = constraints.maxHeight,
            )
          val placeable = measurable.measure(wrappedConstraints)
          onMeasured(IntSize(placeable.width, placeable.height))
          layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
      propagateMinConstraints = true,
    ) {
      content()
    }
  }

  /**
   * Precedence and the light/dark default live in
   * [ee.schimke.composeai.data.render.PreviewBackground] so both backends and both renderers agree;
   * in particular `showBackground = true` on a `uiMode = UI_MODE_NIGHT_YES` preview is not white.
   */
  private fun resolveBackgroundColor(
    params: RenderPreviewParams
  ): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(
      ee.schimke.composeai.data.render.PreviewBackground.resolveArgbForUiMode(
        showBackground = params.showBackground,
        backgroundColor = params.backgroundColor,
        uiMode = params.uiMode,
      )
    )

  companion object {
    /**
     * Sandbox dp used for wrapped axes. Matches the historical phone-shaped 400×800 dp default that
     * stood in for "no device" before AS-parity sizing — `fillMax*` composables get a reasonable
     * viewport instead of a giant square. Mirrors `DeviceDimensions.SANDBOX_WIDTH/HEIGHT_DP` on the
     * plugin side.
     */
    private const val SANDBOX_WIDTH_DP = 400
    private const val SANDBOX_HEIGHT_DP = 800

    /**
     * Virtual time to advance before capture in the paused-`mainClock` path, in milliseconds. Small
     * on purpose: "settled" is `autoAdvance = false` + however far we step. 32ms (≈ 2 Choreographer
     * frames) is enough for static previews (initial composition + one settle pass for
     * `LaunchedEffect`s); for infinite animations it defines the deterministic snapshot point.
     *
     * Expressed in ms rather than frame count to line up with Roborazzi's
     * `@RoboComposePreviewOptions` / `ManualClockOptions.advanceTimeMillis` convention —
     * per-preview overrides would plug straight into `mainClock.advanceTimeBy(...)` with no
     * translation.
     */
    private const val CAPTURE_ADVANCE_MS = 32L

    /**
     * Virtual time advanced after flipping [reduceMotionState] around a LONG capture — enough
     * paused-clock frames for the recomposition to re-read `LocalReduceMotion` and for
     * `TransformingLazyColumn` to drop (or restore) its item transforms before the next capture
     * reads pixels. Reduce-motion transforms snap rather than animate, so a few frames suffice;
     * 128ms (≈ 8 frames) leaves headroom for the morph spec's own invalidation pass.
     */
    private const val REDUCE_MOTION_FLIP_SETTLE_MS = 128L

    // The per-`@FocusedPreview`-capture settle window used to live here as
    // `FOCUS_SETTLE_MS = 250L`. It moved to `:data-focus-connector`'s
    // `FocusController.SETTLE_MS` so the connector's around-composable + the renderer's
    // per-capture clock advance share a single source of truth. Don't redeclare it here.
  }
}

/**
 * Crops [file] in-place to the top-left region defined by [measured] on the wrapped axes. The
 * non-wrapped axis keeps its original pixel extent — we never expand beyond the captured PNG, so a
 * wrapped-axis crop that somehow exceeds the sandbox is clamped.
 *
 * Uses `javax.imageio` rather than Android's `Bitmap` so the path doesn't need a Robolectric
 * shadow: this runs on the JVM side after `captureRoboImage` has already written a standard PNG.
 */
private fun cropPngTopLeft(file: File, wrapWidth: Boolean, wrapHeight: Boolean, measured: IntSize) {
  if (!file.exists()) return
  val original = javax.imageio.ImageIO.read(file) ?: return
  val cropW = (if (wrapWidth) measured.width else original.width).coerceIn(1, original.width)
  val cropH = (if (wrapHeight) measured.height else original.height).coerceIn(1, original.height)
  if (cropW == original.width && cropH == original.height) return
  val cropped = original.getSubimage(0, 0, cropW, cropH)
  javax.imageio.ImageIO.write(cropped, "PNG", file)
}

private fun resizeFixedAxesPng(file: File, targetWidth: Int?, targetHeight: Int?) {
  if (targetWidth == null && targetHeight == null) return
  if (!file.exists()) return
  val original = javax.imageio.ImageIO.read(file) ?: return
  val newWidth = targetWidth ?: original.width
  val newHeight = targetHeight ?: original.height
  if (newWidth == original.width && newHeight == original.height) return
  val resized =
    java.awt.image.BufferedImage(newWidth, newHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
  val g = resized.createGraphics()
  try {
    val copyWidth = minOf(original.width, newWidth)
    val copyHeight = minOf(original.height, newHeight)
    g.drawImage(original, 0, 0, copyWidth, copyHeight, 0, 0, copyWidth, copyHeight, null)
    if (newWidth > copyWidth && copyWidth > 0 && copyHeight > 0) {
      g.drawImage(
        original,
        copyWidth,
        0,
        newWidth,
        copyHeight,
        copyWidth - 1,
        0,
        copyWidth,
        copyHeight,
        null,
      )
    }
    if (newHeight > copyHeight && copyHeight > 0) {
      g.drawImage(
        resized,
        0,
        copyHeight,
        newWidth,
        newHeight,
        0,
        copyHeight - 1,
        newWidth,
        copyHeight,
        null,
      )
    }
  } finally {
    g.dispose()
  }
  javax.imageio.ImageIO.write(resized, "PNG", file)
}

private fun Float.roundHalfUpPx(): Int = kotlin.math.floor(this + 0.5f).toInt().coerceAtLeast(1)

/**
 * Handles `@ScrollingPreview(modes = [LONG])` captures. Plans the projected slice walk through the
 * typed [ScrollLongFrameDriverExtension] (uniform 80%-of-viewport stride from `0..1`, exported via
 * `ScrollLongExtensionStateKeys.Frames` for downstream consumers), then drives the first scrollable
 * on [ScrollCapture.axis] adaptively via [driveScrollByViewport] using the planner's stride —
 * adaptive driving is required because LazyList's `maxValue` materialises progressively, so the
 * upfront extent hint can underestimate. Captures each slice to a temp PNG with per-slice round
 * crop DISABLED and Java2D-stitches them via [stitchSlices].
 *
 * For round Wear devices ([isRound] = true), the stitched output gets a `capsule` clip —
 * half-circle at the very top, rectangular middle, half-circle at the very bottom
 * ([applyWearPillClip]) — so the captured scroll preserves the round screen edge at the first and
 * last frames.
 *
 * Returns `true` when [outputFile] was written; `false` to let the caller fall through to END-style
 * single capture (e.g. when no scrollable matched).
 */
/**
 * Public so the daemon-android `RenderEngine` can dispatch into the same scroll-scenario logic the
 * in-process Robolectric harness uses for the `:samples` end-to-end runs — see issue #1528 and
 * [ScrollScenarioHandlers] for the wider scope-and-rationale doc. Wraps the original private
 * `handleLongCapture` so the harness code path stays unchanged.
 *
 * `rule` is the held `AndroidComposeTestRule<*, ComponentActivity>` (`createAndroidComposeRule`)
 * whose `setContent` has already painted the preview; `scroll` carries the annotation's intent
 * (axis / maxScrollPx); `previewId` is logged into the structured error messages; `heightDp` is the
 * viewport height in dp matching the device qualifier; `isRound` triggers the Wear pill clip;
 * `outputFile` is the final stitched PNG.
 *
 * Returns `true` when [outputFile] was written. Returns `false` when there was no scrollable on
 * `scroll.axis` (caller can fall through to a single-frame capture / structured failure sidecar —
 * see issue #1528 § "NoScrollable failure shape on the wire").
 */
@OptIn(ExperimentalRoborazziApi::class)
public fun handleLongCapture(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  scroll: ScrollCapture,
  previewId: String,
  heightDp: Int,
  isRound: Boolean,
  outputFile: File,
): Boolean = handleLongCaptureInternal(rule, scroll, previewId, heightDp, isRound, outputFile)

/**
 * Drives `@ScrollingPreview(modes = [END])` — the mode that produces an ordinary single-frame PNG
 * rather than a data product, so unlike [handleLongCapture]/[handleGifCapture] it writes nothing
 * and the caller captures as usual afterwards.
 *
 * The first scrollable on `scroll.axis` is driven to its content end, then post-scroll animations
 * are settled: Wear's `ScreenScaffold` reveals and expands its `EdgeButton` *in response to* the
 * scroll landing, so capturing immediately snapshots the button mid-reveal (overscroll-stretched)
 * or, at the resting top, not at all.
 *
 * Returns `false` when no scrollable matched on the axis — the caller should capture the initial
 * frame and say so. Public so the daemon's duplicated render body drives END identically instead of
 * silently exporting the unscrolled frame; TOP needs no call at all, being the undriven frame.
 */
public fun driveScrollingPreviewToEnd(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  scroll: ScrollCapture,
): Boolean {
  val result =
    driveScrollToEnd(
      rule = rule,
      axis = scroll.axis.toProductAxis(),
      maxScrollPx = scroll.maxScrollPx,
    )
  if (result is ScrollDriveResult.NoScrollable) return false
  settlePostScrollAnimations(rule)
  return true
}

/**
 * Public sibling of [handleLongCapture] for `@ScrollingPreview(modes = [GIF])`. Same arguments —
 * see that doc for semantics. Writes [outputFile] as an animated GIF rather than a stitched still
 * PNG.
 */
@OptIn(ExperimentalRoborazziApi::class)
public fun handleGifCapture(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  scroll: ScrollCapture,
  previewId: String,
  heightDp: Int,
  isRound: Boolean,
  outputFile: File,
): Boolean = handleGifCaptureInternal(rule, scroll, previewId, heightDp, isRound, outputFile)

@OptIn(ExperimentalRoborazziApi::class)
private fun handleLongCaptureInternal(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  scroll: ScrollCapture,
  previewId: String,
  heightDp: Int,
  isRound: Boolean,
  outputFile: File,
): Boolean {
  val density = rule.activity.resources.displayMetrics.density
  val viewportLayoutPx = (heightDp * density).toInt().coerceAtLeast(1)
  val slicesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_slices")
  slicesDir.deleteRecursively()
  slicesDir.mkdirs()

  // For stitched capture on round devices, suppress per-slice round crop —
  // otherwise every slice has a circle cut out of it and the stitched output
  // has scalloped scallops down the middle. We apply a capsule clip after
  // stitching instead.
  val sliceRoborazziOptions =
    RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = false))

  val slices = mutableListOf<SliceCapture>()
  val stableDialogCrop = DialogWindowCapture.StableDialogCrop()
  try {
    // Multi-mode annotations (e.g. END + LONG) run captures in enum
    // ordinal order against the same composition, so an earlier END
    // leaves the scrollable at content end. Reset to the top before
    // slicing — otherwise the first slice is the end state and
    // `driveScrollByViewport`'s first iteration bails with
    // remaining ≈ 0, yielding a single "stitched" slice. See #154.
    driveScrollToStart(rule, scroll.axis.toProductAxis())

    // Plan slice positions through the typed long-scroll driver. The
    // planned frames are exported through ScrollLongExtensionStateKeys.Frames
    // for downstream consumers (the typed-graph contract); the renderer
    // itself drives the scrollable via [driveScrollByViewport] below,
    // which re-reads live remaining each iteration. That matters for
    // LazyList content where `maxValue` grows as more items materialize
    // mid-walk — a planner-only loop sized from one upfront snapshot
    // would truncate the stitched output before the real end of content.
    val liveRemaining = remainingScrollPx(rule, scroll.axis.toProductAxis())
    val cap = if (scroll.maxScrollPx > 0) scroll.maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
    val extentHint = minOf(liveRemaining, cap)
    val plan =
      ScrollLongFramePlan(
        contentExtentPxHint = extentHint,
        viewportPx = viewportLayoutPx.toFloat(),
        density = density,
      )
    ScrollLongFrameDriverExtension(plan)
      .scrollFrames(
        ExtensionFrameContext(
          extensionId = DataExtensionId(ScrollPreviewExtension.KIND_LONG),
          previewId = previewId,
          renderMode = "scroll-long",
        )
      )

    // Drive at the planner's stride (80% of the viewport so each
    // consecutive slice pair has a ~20% physical overlap for the
    // content-aware stitcher to lock onto). The stitcher uses
    // scrolledPx only as a hint — actual vertical placement is decided
    // by pixel matching.
    val result =
      driveScrollByViewport(
        rule = rule,
        axis = scroll.axis.toProductAxis(),
        stepPx = plan.stepPx,
        maxScrollPx = scroll.maxScrollPx,
      ) { scrolledPx ->
        val sliceFile = File(slicesDir, "slice_${slices.size}.png")
        stableDialogCrop.captureFrame(
          rule = rule,
          file = sliceFile,
          roborazziOptions = sliceRoborazziOptions,
        )
        slices += SliceCapture(scrolledPx, sliceFile)
      }
    if (result is ScrollDriveResult.NoScrollable) {
      System.err.println(
        "@ScrollingPreview(LONG) on '$previewId': no scrollable composable — falling through."
      )
      return false
    }
    if (slices.isEmpty()) return false

    // Settle post-scroll animations (Wear `EdgeButton` reveal, spring
    // snaps, AnimatedVisibility fade-ins that only start once the list
    // has landed) before capturing the final frame. The per-step 250ms
    // advance inside `driveScrollByViewport` is tuned for scroll
    // settling, not for animations that START when the scroll reaches
    // its end.
    //
    // Tick one frame at a time so any withFrameNanos-driven animation
    // gets each cycle it's waiting on. Bounded (POST_SCROLL_SETTLE_MS
    // / 16ms frames) so infinite animations can't run away — they keep
    // the paused-clock semantics of the rest of the render path.
    settlePostScrollAnimations(rule)

    // Capture the settled end-state viewport as a stand-alone frame
    // (not overwriting the last in-scroll slice). `stitchSlicesWithFinalFrame`
    // then composes the during-scroll history above this full settled
    // viewport and cuts the seam cleanly using the same weighted-SAD
    // row matcher, so the complete revealed tail (EdgeButton, bottom
    // bar, FAB — whatever animates in at scroll-end) is always
    // present. Generic over layout: not Wear-specific.
    val finalFrameFile = File(slicesDir, "final_frame.png")
    stableDialogCrop.captureFrame(
      rule = rule,
      file = finalFrameFile,
      roborazziOptions = sliceRoborazziOptions,
    )

    stitchSlicesWithFinalFrame(
      slices = slices,
      finalFrameFile = finalFrameFile,
      viewportLayoutPx = viewportLayoutPx,
      outputFile = outputFile,
      isRound = isRound,
    ) ?: return false
    if (isRound) applyWearPillClip(outputFile)
    System.err.println(
      "@ScrollingPreview(LONG) on '$previewId': stitched ${slices.size} slices + settled final frame."
    )
    return true
  } finally {
    slicesDir.deleteRecursively()
  }
}

/**
 * Advance the paused `mainClock` one frame at a time up to [POST_SCROLL_SETTLE_MS], letting
 * animations that begin once the scroll lands (e.g. Wear `ScreenScaffold`'s `EdgeButton` reveal)
 * run to their resting state before the final slice is captured.
 *
 * Bounded so infinite animations (`rememberInfiniteTransition`, etc.) don't turn the settle step
 * into an open-ended render. 1000ms is ~4× Wear Material3's 250ms EdgeButton reveal spec — enough
 * headroom for chained animations or a spring overshoot, still cheap per preview.
 */
private fun settlePostScrollAnimations(rule: AndroidComposeTestRule<*, *>) {
  val frameMs = 16L
  val frames = POST_SCROLL_SETTLE_MS / frameMs
  repeat(frames.toInt()) { rule.mainClock.advanceTimeByFrame() }
}

/**
 * Total virtual-time budget for [settlePostScrollAnimations]. Sized for Wear Material3's
 * `EdgeButton` expand spec (~250ms at the time of writing) with comfortable headroom for overshoot
 * / chained animations.
 */
private const val POST_SCROLL_SETTLE_MS = 1000L

/**
 * Handles `@ScrollingPreview(modes = [GIF])` captures with a "realistic user" scroll shape: 1 s
 * hold at the top, a slow finger-drag ramp, one or more fling bursts sized for the content, then a
 * 1 s hold on the settled final frame. See [buildGifScrollScript] for the plan shape.
 *
 * Every frame is a full viewport-sized image — the GIF shows the scroll as an animation rather than
 * stitching frames into one tall still. Per-frame round crop stays ON (each GIF frame should show a
 * proper watch-shaped viewport), so we reuse the default `RoborazziOptions` the caller wired for
 * single-frame captures.
 *
 * Returns `true` when [outputFile] was written; `false` to fall through to the default
 * single-capture path (e.g. when no scrollable matched, or when the encoder declines).
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun handleGifCaptureInternal(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  scroll: ScrollCapture,
  previewId: String,
  heightDp: Int,
  isRound: Boolean,
  outputFile: File,
): Boolean {
  val density = rule.activity.resources.displayMetrics.density
  val viewportLayoutPx = (heightDp * density).toInt().coerceAtLeast(1)
  val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_gif_frames")
  framesDir.deleteRecursively()
  framesDir.mkdirs()

  // Per-frame crop matches END mode: each GIF frame should look like a
  // normal single capture, circle-clipped on round devices included.
  val frameRoborazziOptions =
    RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))

  val frameIntervalMs =
    if (scroll.frameIntervalMs > 0) scroll.frameIntervalMs
    else ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS
  val frameFiles = mutableListOf<File>()
  val frameDelays = mutableListOf<Int>()
  val stableDialogCrop = DialogWindowCapture.StableDialogCrop()

  fun captureFrame(delayMs: Int) {
    val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
    captureDecodableFrame(frameFile, role = "scroll GIF") { f ->
      stableDialogCrop.captureFrame(
        rule = rule,
        file = f,
        roborazziOptions = frameRoborazziOptions,
      )
    }
    frameFiles += frameFile
    frameDelays += delayMs
  }

  try {
    // Multi-mode annotations (`modes = [..., GIF]`) run captures in
    // enum ordinal order against the same composition, so an earlier
    // END / LONG leaves the scrollable at content end and the GIF
    // would animate "from end to end" — a single frame indistinguishable
    // from the END capture. Reset to the top before the frame walk
    // starts. Fix for #154.
    val resetResult = driveScrollToStart(rule, scroll.axis.toProductAxis())
    if (resetResult is ScrollDriveResult.NoScrollable) {
      System.err.println(
        "@ScrollingPreview(GIF) on '$previewId': no scrollable composable — falling through."
      )
      return false
    }

    // Hold-start: the viewer needs a beat to read the top of the
    // screen before motion begins. 1 s dwell.
    captureFrame(HOLD_START_MS)

    // Upfront extent hint — capped to `maxScrollPx` when the
    // annotation sets one. Runtime clamps each step to live remaining
    // anyway, so LazyList's progressive maxValue doesn't over-scroll.
    val liveRemaining = remainingScrollPx(rule, scroll.axis.toProductAxis())
    val cap = if (scroll.maxScrollPx > 0) scroll.maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
    val extentHint = minOf(liveRemaining, cap)

    val scrollFrames =
      ScrollGifFrameDriverExtension(
          ScrollGifFramePlan(
            contentExtentPxHint = extentHint,
            viewportPx = viewportLayoutPx.toFloat(),
            density = density,
            frameIntervalMs = frameIntervalMs,
          )
        )
        .scrollFrames(
          ExtensionFrameContext(
            extensionId = DataExtensionId("render/scroll/gif"),
            previewId = previewId,
            renderMode = "scroll-gif",
          )
        )

    var scrolledPx = 0f
    var scriptHitEnd = false
    for (scrollFrame in scrollFrames.frames.drop(1)) {
      if (scrollFrame.scrollDeltaPx > 0f) {
        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        val target = minOf(scrollFrame.scrollDeltaPx, headroom)
        if (target <= 0f) {
          scriptHitEnd = true
          break
        }
        val actual = driveScrollBy(rule, scroll.axis.toProductAxis(), target)
        if (actual <= 0f) {
          scriptHitEnd = true
          break
        }
        scrolledPx += actual
      } else {
        // Inter-fling dwell: no scroll, just a pause frame. Advance
        // virtual time a little so animations mid-composition keep
        // ticking honestly across the hold.
        rule.mainClock.advanceTimeBy(frameIntervalMs.toLong())
      }
      captureFrame(scrollFrame.frame.delayMillis)
    }

    // Tail flings: LazyList reports `maxValue` progressively, so the
    // upfront `extentHint` can under-cover — the script finishes with
    // the scroll still mid-content. Keep emitting fling bursts against
    // the *live* remaining until the scrollable is exhausted (or the
    // `cap` / safety cap kicks in). Without this the final frame of
    // the GIF can land in the middle of the gradient on tall lists.
    if (!scriptHitEnd) {
      emitTailFlings(
        rule = rule,
        axis = scroll.axis.toProductAxis(),
        density = density,
        viewportPx = viewportLayoutPx.toFloat(),
        frameIntervalMs = frameIntervalMs,
        cap = cap,
        alreadyScrolledPx = scrolledPx,
        captureFrame = ::captureFrame,
      )
    }

    // Settle + hold-end: let animations that start when the scroll
    // lands (Wear `EdgeButton` reveal, spring overshoot) reach their
    // resting state, then capture one final frame with a long dwell.
    settlePostScrollAnimations(rule)
    captureFrame(HOLD_END_MS)

    if (frameFiles.isEmpty()) return false

    val frames = frameFiles.map { FramePngReader.decode(it, role = "scroll GIF") }
    val written =
      ScrollGifEncoder.encode(
        frames = frames,
        outputFile = outputFile,
        frameDelaysMs = frameDelays.toIntArray(),
      ) ?: return false
    System.err.println(
      "@ScrollingPreview(GIF) on '$previewId': encoded ${frames.size} frames → ${written.name}."
    )
    return true
  } finally {
    framesDir.deleteRecursively()
  }
}

/**
 * Drives the remaining scroll to content end in fling-shaped bursts, capturing one frame per step.
 * Used after the scripted plan runs out of steps on content taller than the initial extent hint —
 * the common LazyList progressive-materialisation case where the first [remainingScrollPx] read
 * under-reports the true scroll extent.
 *
 * Each iteration emits one fling (geometric decay from peak to min step, capped at
 * [FLING_MAX_DISTANCE_VIEWPORTS] of a viewport) preceded by a short inter-fling hold so the
 * continuation reads as "user swiped again" rather than one endless glide. Bounded by
 * [MAX_TAIL_FLINGS] so a runaway `maxValue` (infinite LazyList with a no-op `ScrollBy`) can't spin
 * forever.
 */
@Suppress("LongParameterList")
private fun emitTailFlings(
  rule: AndroidComposeTestRule<*, *>,
  axis: ProductScrollAxis,
  density: Float,
  viewportPx: Float,
  frameIntervalMs: Int,
  cap: Float,
  alreadyScrolledPx: Float,
  captureFrame: (delayMs: Int) -> Unit,
) {
  val flingPeakPx = FLING_PEAK_DP_PER_FRAME * density
  val flingMinPx = FLING_MIN_STEP_DP * density
  val flingCapPx = FLING_MAX_DISTANCE_VIEWPORTS * viewportPx
  var scrolledPx = alreadyScrolledPx

  repeat(MAX_TAIL_FLINGS) {
    val remaining = remainingScrollPx(rule, axis)
    if (remaining <= TAIL_FLING_EPSILON_PX) return
    val headroom = (cap - scrolledPx).coerceAtLeast(0f)
    if (headroom <= TAIL_FLING_EPSILON_PX) return

    // Inter-fling hold frame: short dwell + no scroll. Makes the
    // follow-up read as a distinct swipe.
    rule.mainClock.advanceTimeBy(frameIntervalMs.toLong())
    captureFrame(INTER_FLING_HOLD_MS)

    var step = flingPeakPx
    var distanceInFling = 0f
    while (step >= flingMinPx && distanceInFling < flingCapPx) {
      val live = remainingScrollPx(rule, axis)
      val remainingHeadroom = (cap - scrolledPx).coerceAtLeast(0f)
      val cappedRemaining = minOf(live, remainingHeadroom)
      if (cappedRemaining <= TAIL_FLING_EPSILON_PX) return
      val emit = minOf(step, cappedRemaining, flingCapPx - distanceInFling)
      if (emit <= 0f) return
      val actual = driveScrollBy(rule, axis, emit)
      if (actual <= 0f) return
      distanceInFling += actual
      scrolledPx += actual
      captureFrame(frameIntervalMs)
      step *= FLING_DECAY
    }
  }
}

// Safety cap on continuation flings so an infinite or pathological
// LazyList can't spin. Four flings × 1.5 viewports/fling covers six more
// viewports beyond the initial scripted walk — enough for any reasonable
// real-world preview.
private const val MAX_TAIL_FLINGS = 4

// Slightly looser than SETTLED_EPSILON_PX in ScrollDriver: LazyList's
// maxValue can wobble a pixel or two as items recompose, and we don't
// want a sub-pixel remainder to keep us spinning in the tail loop.
private const val TAIL_FLING_EPSILON_PX = 1f

/**
 * How many times the multi-frame capture paths re-issue a single frame's `captureRoboImage` when
 * the written PNG won't decode — one initial attempt plus retries.
 */
internal const val FRAME_CAPTURE_ATTEMPTS = 3

/**
 * Capture one GIF frame to [file] and re-capture if the written PNG won't decode.
 *
 * Robolectric's NATIVE graphics backend very occasionally flushes a per-frame PNG whose 8-byte
 * signature and IEND trailer are both intact but whose interior IDAT stream `ImageIO` then refuses
 * ("ImageIO could not read it") — a transient encode glitch under the rapid write cadence of the
 * multi-frame paths (`@AnimatedPreview` GIF, scroll GIF, focus GIF). A single such frame turns an
 * otherwise-green multi-frame render red even though re-issuing the capture at the same clock state
 * re-encodes the identical frame cleanly — see the Compose Preview `ShaderRaymarchAnimatedPreview …
 * frame_39 … ImageIO could not read it` failure.
 *
 * [capture] writes [file] (overwriting any prior attempt); [FramePngReader] then validates it. A
 * frame that still won't decode after [FRAME_CAPTURE_ATTEMPTS] re-throws the last decode error, so
 * a genuinely undecodable frame keeps the render red with the same diagnostic as before — the retry
 * only absorbs a glitch that clears on a fresh encode.
 */
internal fun captureDecodableFrame(file: File, role: String, capture: (File) -> Unit) {
  var lastFailure: IllegalStateException? = null
  repeat(FRAME_CAPTURE_ATTEMPTS) { attempt ->
    capture(file)
    try {
      FramePngReader.decode(file, role = role)
      return
    } catch (e: IllegalStateException) {
      lastFailure = e
      System.err.println(
        "$role: captured frame ${file.name} did not decode " +
          "(attempt ${attempt + 1}/$FRAME_CAPTURE_ATTEMPTS); re-capturing. " +
          "Cause: ${e.message}"
      )
    }
  }
  throw lastFailure ?: IllegalStateException("Failed to capture a decodable frame: ${file.path}")
}

/**
 * Handles `@AnimatedPreview` captures.
 *
 * Single-pass with an inline measure step:
 *
 * 1. Tick one frame to settle the composition — any `LaunchedEffect(Unit) { … }` that kicks an
 *    animation off must fire before the inspector attaches, otherwise AnimationSearch sees a target
 *    == initial transition and the curve is flat.
 * 2. When `showCurves = true`, attach an [AnimationInspector] over the slot table captured by
 *    [InspectablePreviewContent]. Read `inspector.maxDurationMs` to determine the actual animation
 *    duration; the user's `durationMs > 0` overrides this.
 * 3. Loop one frame per `frameIntervalMs` of virtual time across the effective duration, capturing
 *    the screenshot to a temp PNG and seeking the inspector to the same time to sample each
 *    animated property's value.
 * 4. Build the output GIF. With `showCurves = true`, every frame is a composite of (screenshot on
 *    top, curve panel below with a moving dot on each curve at the current virtual time). With
 *    `showCurves = false`, the GIF is screenshot-only.
 *
 * Returns `true` when [outputFile] was written.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun handleAnimatedCapture(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  animation: AnimationCapture,
  previewId: String,
  isRound: Boolean,
  outputFile: File,
  curveCapture: SlotTreeCapture?,
): Boolean {
  val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_anim_frames")
  framesDir.deleteRecursively()
  framesDir.mkdirs()

  val frameRoborazziOptions =
    RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))

  val frameIntervalMs = animation.frameIntervalMs.coerceAtLeast(10)

  val frameFiles = mutableListOf<File>()
  val stableDialogCrop = DialogWindowCapture.StableDialogCrop()

  // Settle the composition by ticking one frame so any
  // LaunchedEffect(Unit) { … } has fired before the inspector reads
  // the slot table.
  rule.mainClock.advanceTimeByFrame()
  val inspector =
    if (animation.showCurves && curveCapture != null) {
      val context =
        curveCapture.previewContext(
          previewId = previewId,
          renderMode = "animation",
          outputBaseName = outputFile.nameWithoutExtension,
          animation =
            PreviewAnimationContext(
              showCurves = true,
              requestedDurationMs = animation.durationMs,
              effectiveDurationMs = null,
              frameIntervalMs = frameIntervalMs,
            ),
        )
      runCatching { AnimationInspector.attach(context) }
        .onFailure { e ->
          // Graceful degradation: incompat Compose UI Tooling (e.g. consumers
          // on a Compose runtime older than the AnimationInspector reflective
          // lookups support) → emit the GIF without the curves overlay
          // rather than failing the render outright. The renderer module
          // compiles against `compose-bom-compat` (currently the 1.9.x line)
          // so a missing 1.10+ API surfaces here at runtime, not at compile.
          System.err.println(
            "@AnimatedPreview(showCurves=true): inspector unavailable on " +
              "this Compose UI Tooling version, falling back to GIF " +
              "without curve overlay. Cause: ${e.message}"
          )
        }
        .getOrNull()
    } else null

  // Effective duration: user override (>0) wins; otherwise ask the
  // inspector how long the discovered animations actually run, capped
  // at AUTO_DURATION_MAX_MS so an InfiniteTransition or a measure
  // glitch can't blow up GIF size, and floored at frameIntervalMs so
  // we always emit at least one tick.
  val measuredDurationMs = inspector?.maxDurationMs ?: -1L
  val effectiveDurationMs =
    when {
      animation.durationMs > 0 -> animation.durationMs.coerceAtMost(AUTO_DURATION_MAX_MS)
      measuredDurationMs > 0 -> {
        // Add a small tail beyond the animation's natural end so
        // the viewer sees the settled state for a beat before the
        // GIF loops. Cap at AUTO_DURATION_MAX_MS.
        (measuredDurationMs + AUTO_DURATION_TAIL_MS)
          .coerceAtMost(AUTO_DURATION_MAX_MS.toLong())
          .toInt()
      }
      else -> AUTO_DURATION_FALLBACK_MS
    }.coerceAtLeast(frameIntervalMs)
  val totalFrames = (effectiveDurationMs / frameIntervalMs).coerceAtLeast(1)

  // Curve series keyed by (tracked-animation identity, sample index) rather than
  // by display label. AnimatedContent composes its outgoing and incoming slots at
  // the same time, and the tooling exposes each slot's alpha under an identical
  // label ("Built-in alpha", "enter/exit for … alpha"); keying by label alone
  // collapsed the two mirror-image values (one slot 1→0, the other 0→1) into a
  // single zig-zag series. Identity + sample index is stable across frames, so
  // the slots stay separate, smooth lines. Base labels shared by more than one
  // series are suffixed "(1)", "(2)", … when the panel is built.
  val curveSamplesByKey = linkedMapOf<Pair<Int, Int>, MutableList<Pair<Long, Any?>>>()
  val curveBaseLabelByKey = linkedMapOf<Pair<Int, Int>, String>()
  val frameTimes = mutableListOf<Long>()

  fun captureFrame(virtualTimeMs: Long) {
    val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
    captureDecodableFrame(frameFile, role = "animation") { f ->
      stableDialogCrop.captureFrame(
        rule = rule,
        file = f,
        roborazziOptions = frameRoborazziOptions,
      )
    }
    frameFiles += frameFile
    frameTimes += virtualTimeMs

    if (inspector != null) {
      // Drive PreviewAnimationClock to the same virtual time the
      // outer mainClock is at — `setClockTime` seeks every tracked
      // transition to that point, so `getAnimatedProperties` reads
      // a fresh value rather than the cached value from when the
      // animation was first registered.
      inspector.setClockTime(virtualTimeMs)
      inspector.snapshot().forEach { tracked ->
        tracked.samples.forEachIndexed { sampleIndex, sample ->
          val key = tracked.animationId to sampleIndex
          curveBaseLabelByKey.getOrPut(key) { "${tracked.label} · ${sample.label}" }
          curveSamplesByKey.getOrPut(key) { mutableListOf() }.add(virtualTimeMs to sample.value)
        }
      }
    }
  }

  try {
    captureFrame(virtualTimeMs = 0L)
    var t = 0L
    repeat(totalFrames) {
      t += frameIntervalMs.toLong()
      rule.mainClock.advanceTimeBy(frameIntervalMs.toLong())
      // Compose's test clock does not drive Android Views. Advance Robolectric's paused main
      // looper by the same interval so View/Choreographer animations land on the same virtual
      // timestamp as Compose animations. Remote Compose's preview wrapper also derives its
      // injected player clock from shadowed uptime, avoiding the real System.nanoTime phase drift
      // that made unchanged animated GIFs differ between renders (issue #3156).
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(frameIntervalMs.toLong()))
      captureFrame(virtualTimeMs = t)
    }

    if (frameFiles.isEmpty()) return false

    val rawFrames = frameFiles.map { FramePngReader.decode(it, role = "animation") }
    // Drop tracks that never visibly changed across the captured
    // window. AnimatedVisibility, AnimatedContent, and a few other
    // composables register internal book-keeping animations
    // (`Built-in InterruptionHandlingOffset` — slide-target on
    // mid-flight interruption; `Built-in shrink/expand` — geometry
    // change on a non-resizing reveal) that the inspector exposes
    // alongside the user-meaningful properties (alpha, etc.). For
    // a single state flip those internals stay flat at 0, so they
    // add an empty 80px row each to the curve panel without
    // information. Filtering by "values change" is more principled
    // than maintaining a denylist of internal labels — if a track
    // genuinely doesn't move, it's not interesting to plot.
    // Turn each series into a Track. When two or more series share a base label
    // (AnimatedContent's concurrent slots), suffix them "(1)", "(2)", … in
    // first-seen order so the panel shows separate labelled lines rather than one
    // merged zig-zag.
    val baseLabelCounts = curveBaseLabelByKey.values.groupingBy { it }.eachCount()
    val baseLabelSeen = mutableMapOf<String, Int>()
    val tracks =
      curveSamplesByKey
        .map { (key, samples) ->
          val base = curveBaseLabelByKey.getValue(key)
          val label =
            if ((baseLabelCounts[base] ?: 1) <= 1) {
              base
            } else {
              val n = (baseLabelSeen[base] ?: 0) + 1
              baseLabelSeen[base] = n
              "$base ($n)"
            }
          AnimationCurvePlotter.Track(label = label, samples = samples)
        }
        .filter { it.hasVisibleVariation() }

    // Combined-GIF mode: each frame composes the screenshot above a
    // curve panel that highlights the current frame's position with
    // a moving dot on every track. Falls back to screenshot-only
    // when there are no tracks (e.g. showCurves = false, or the
    // inspector found nothing — which can happen on a static
    // preview accidentally annotated).
    val composedFrames: List<BufferedImage> =
      if (tracks.isNotEmpty()) {
        rawFrames.mapIndexed { i, screenshot ->
          AnimationCurvePlotter.composeFrameWithCurves(
            screenshot = screenshot,
            tracks = tracks,
            durationMs = effectiveDurationMs,
            currentTimeMs = frameTimes[i],
          )
        }
      } else {
        rawFrames
      }

    // Hold the first frame for [HOLD_START_MS] and the last for
    // [HOLD_END_MS] so the GIF reads as "pre-state → animation → settled
    // state" rather than instantly looping back. Single-frame GIFs
    // collapse to one long-hold image.
    val frameDelays =
      IntArray(composedFrames.size) { i ->
        when (i) {
          0 -> HOLD_START_ANIM_MS
          composedFrames.lastIndex -> HOLD_END_ANIM_MS
          else -> frameIntervalMs
        }
      }
    val written =
      ScrollGifEncoder.encode(
        frames = composedFrames,
        outputFile = outputFile,
        frameDelaysMs = frameDelays,
      ) ?: return false

    val durationLabel =
      if (animation.durationMs == 0) {
        "auto-detected ${effectiveDurationMs}ms (measured ${measuredDurationMs}ms)"
      } else {
        "${effectiveDurationMs}ms"
      }
    val curvesLabel = if (tracks.isNotEmpty()) " + ${tracks.size} curve track(s)" else ""
    System.err.println(
      "@AnimatedPreview on '$previewId': encoded ${composedFrames.size} frames " +
        "($durationLabel)$curvesLabel → ${written.name}."
    )
    return true
  } finally {
    framesDir.deleteRecursively()
  }
}

/**
 * Handles `@FocusedPreview(gif = true)` captures: drives [FocusController] through each declared
 * step and stitches the per-step captures into a single GIF. The inside-composition
 * `LaunchedEffect`-driven walk in [FocusOverrideExtension.AroundComposable] is what makes each step
 * emit *both* `FocusInteraction.Focus` on the new target *and* `FocusInteraction.Unfocus` on the
 * previously focused one — the renderer just flips the controller's state and waits for the settle
 * window before capturing.
 *
 * Returns `true` when [outputFile] was written.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun handleFocusGifCapture(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  focusGif: FocusGifCapture,
  previewId: String,
  isRound: Boolean,
  outputFile: File,
): Boolean {
  if (focusGif.steps.isEmpty()) return false
  val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_focus_frames")
  framesDir.deleteRecursively()
  framesDir.mkdirs()

  val frameRoborazziOptions =
    RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))
  val frameFiles = mutableListOf<File>()
  val stableDialogCrop = DialogWindowCapture.StableDialogCrop()

  try {
    focusGif.steps.forEachIndexed { i, step ->
      rule.runOnUiThread {
        FocusController.set(step.toFocusOverride())
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
      }
      // Settle so the focus walk's `LaunchedEffect` runs `moveFocus`, the FocusableNode
      // emits paired Focus/Unfocus interactions, and the ripple's `IndicationNode`
      // crossfades to the new highlight before the frame is captured.
      rule.mainClock.advanceTimeBy(FocusController.SETTLE_MS)
      rule.mainClock.advanceTimeBy(focusGif.frameDelayMs.toLong())
      // Same platform-ripple settling as the static per-PNG focus block: a
      // pressed step's Material state layer is a platform `RippleDrawable`
      // animated on the Android looper, not Compose's `mainClock`, so the
      // advances above never progress it. Idle the looper for pressed frames so
      // the ripple settles before capture; gated per-step so non-pressed frames
      // keep their deterministic timing.
      if (step.pressed) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
          .idleFor(java.time.Duration.ofMillis(FocusController.SETTLE_MS))
      }
      val frameFile = File(framesDir, "frame_$i.png")
      captureDecodableFrame(frameFile, role = "focus GIF") { f ->
        stableDialogCrop.captureFrame(
          rule = rule,
          file = f,
          roborazziOptions = frameRoborazziOptions,
        )
      }
      frameFiles += frameFile
    }

    val frames = frameFiles.map { FramePngReader.decode(it, role = "focus GIF") }
    // Hold the first and last frames a touch longer so the viewer reads the starting
    // and ending focus state before the loop restarts — mirrors @AnimatedPreview.
    val frameDelays =
      IntArray(frames.size) { i ->
        when (i) {
          0 -> HOLD_START_ANIM_MS
          frames.lastIndex -> HOLD_END_ANIM_MS
          else -> focusGif.frameDelayMs
        }
      }
    val written =
      ScrollGifEncoder.encode(frames = frames, outputFile = outputFile, frameDelaysMs = frameDelays)
        ?: return false
    System.err.println(
      "@FocusedPreview(gif=true) on '$previewId': encoded ${frames.size} frames → " +
        written.name +
        "."
    )
    return true
  } finally {
    framesDir.deleteRecursively()
    rule.runOnUiThread { FocusController.set(null) }
  }
}

/**
 * Hard cap on auto-detected animation duration. `InfiniteTransition` and a few hand-rolled
 * `withFrameNanos` loops report enormous `maxDuration` values; we don't want one of them to spawn a
 * 10-MB GIF.
 */
private const val AUTO_DURATION_MAX_MS = 5000

/** Floor used when `durationMs = 0` and no animations were discovered. */
private const val AUTO_DURATION_FALLBACK_MS = 1500

/**
 * Extra tail appended after the auto-detected animation end, so the GIF holds the settled state
 * visibly for a moment before looping.
 */
private const val AUTO_DURATION_TAIL_MS = 200L

/**
 * Per-frame `delayTime` overrides for the first and last frames of an `@AnimatedPreview` GIF.
 * Without them the GIF transitions straight from settled-end back to pre-animation start without
 * giving the viewer time to read either state. Mirrors [HOLD_START_MS] / [HOLD_END_MS] in
 * `@ScrollingPreview(GIF)`'s scripted scroll cadence.
 */
private const val HOLD_START_ANIM_MS = 500
private const val HOLD_END_ANIM_MS = 1000

// `KeyboardInputModeManager`, `FocusDirection.toCompose`, the focus-walk `LaunchedEffect`, and
// the `applyFocusOverlay` / `readFocusRect` reflection helpers used to live here. They moved to
// `:data-focus-connector` so the renderer no longer carries hardcoded focus / keyboard logic —
// see the around-composable installed via [FocusOverrideExtension] and the post-capture
// `FocusOverlay`. Add new focus-related features inside the connector module, never inline.

/**
 * Maps the renderer-side [FocusCapture] (read from `previews.json`) onto the connector's
 * `protocol.FocusOverride` wire shape. Both types describe the same fields — duplicated only
 * because the renderer's `previews.json` schema predates the protocol module's override type.
 */
private fun FocusCapture.toFocusOverride(): FocusOverride =
  FocusOverride(
    tabIndex = tabIndex,
    direction = direction?.toProtocol(),
    step = step,
    overlay = overlay,
    enterPlacesFocus = enterPlacesFocus,
    pressed = pressed,
  )

private fun FocusDirection.toProtocol(): ProtocolFocusDirection =
  when (this) {
    FocusDirection.Next -> ProtocolFocusDirection.Next
    FocusDirection.Previous -> ProtocolFocusDirection.Previous
    FocusDirection.Up -> ProtocolFocusDirection.Up
    FocusDirection.Down -> ProtocolFocusDirection.Down
    FocusDirection.Left -> ProtocolFocusDirection.Left
    FocusDirection.Right -> ProtocolFocusDirection.Right
  }

/**
 * Maps the renderer-side [AmbientCapture] (read from `previews.json`) onto the connector's
 * `protocol.AmbientOverride` wire shape. Discovery emits `Interactive` / `Ambient` only; the
 * daemon's `AmbientStateOverride.INACTIVE` value is reserved for the controller's "no override"
 * state and never round-trips through `@AmbientPreview`.
 */
private fun AmbientCapture.toAmbientOverride(): AmbientOverride =
  AmbientOverride(
    state =
      when (state) {
        AmbientCaptureState.Interactive -> AmbientStateOverride.INTERACTIVE
        AmbientCaptureState.Ambient -> AmbientStateOverride.AMBIENT
      },
    burnInProtectionRequired = burnInProtectionRequired,
    deviceHasLowBitAmbient = deviceHasLowBitAmbient,
  )

/**
 * Builds the seed map for a synthetic `@OverrideVariant` preview from its
 * [RenderPreviewEntry.overrides] via the canonical
 * [ee.schimke.composeai.data.overrides.OverrideVariantSpec.toNamedOverrides] (keyed by `seedKey`,
 * type-strict, dropping unparseable seeds). Returns `null` for an ordinary preview or an empty
 * result — `PreviewOverrideController.set(null)` then clears all seeds, so nothing leaks between
 * previews.
 */
private fun overrideSeedMap(
  preview: RenderPreviewEntry
): Map<String, ee.schimke.composeai.data.overrides.PreviewOverrideValue>? =
  preview.overrides?.toNamedOverrides()?.ifEmpty { null }

/**
 * Maps the renderer-side [GestureHintCapture] (read from `previews.json`) onto the connector's
 * `protocol.GestureOverride` wire shape — only the `showHints` immediate-mode flag;
 * `@GestureHintPreview` carries no invoke / enabled dimensions (those are daemon-interactive
 * concerns).
 */
private fun GestureHintCapture.toGestureOverride(): GestureOverride =
  GestureOverride(showHints = showHints)

/**
 * Maps the renderer-side [PermissionsCapture] (read from `previews.json`) onto the connector's
 * `protocol.PermissionsOverride` wire shape — the exact type daemon-driven
 * `renderNow.overrides.permissions` carries, so `@PermissionPreview` and a live panel chip flip
 * feed one code path instead of two grant-seeding implementations that could drift.
 *
 * The two enums are separate on purpose: discovery cannot depend on `:daemon:core`, so the plugin
 * mirrors the state as [PermissionGrantCaptureState] and this is where the two meet. Both are
 * closed two-value sets, so the `when` is exhaustive and a future third state fails to compile here
 * rather than silently mapping to "denied".
 */
private fun PermissionsCapture.toPermissionsOverride(): PermissionsOverride =
  PermissionsOverride(
    grants =
      grants.mapValues { (_, state) ->
        when (state) {
          PermissionGrantCaptureState.GRANTED -> PermissionGrantStateOverride.GRANTED
          PermissionGrantCaptureState.DENIED -> PermissionGrantStateOverride.DENIED
        }
      }
  )

/**
 * Maps the renderer-side [LauncherWidgetCapture] (read from `previews.json`) onto the connector's
 * `protocol.LauncherWidgetOverride` wire shape. Discovery serialises optional `Int?` fields when
 * the consumer left the annotation at its `-1` sentinel — those round-trip as `null` here and the
 * connector applies its own defaults.
 *
 * Each axis of `minCells` / `maxCells` falls back independently: specifying just `minWidth` (or
 * just `maxHeight`) emits an override that combines the user-supplied bound with the connector's
 * default for the other axis. The annotation contract is per-axis, so dropping the whole
 * `LauncherWidgetSize` when one axis is at the sentinel value would silently swallow a half-set
 * constraint.
 *
 * The other-axis defaults must mirror `LauncherWidgetOverride.resolve()`'s `DEFAULT_MIN_CELLS =
 * 1×1` / `DEFAULT_MAX_CELLS = 5×5`. They're `private` in the connector module, so we inline the
 * literals with this comment as the cross-reference.
 */
private fun LauncherWidgetCapture.toLauncherWidgetOverride(): LauncherWidgetOverride =
  LauncherWidgetOverride(
    cells = LauncherWidgetSize(width, height),
    cellSizeDp = cellSizeDp,
    cellSpacingDp = cellSpacingDp,
    minCells =
      if (minWidth != null || minHeight != null) LauncherWidgetSize(minWidth ?: 1, minHeight ?: 1)
      else null,
    maxCells =
      if (maxWidth != null || maxHeight != null) LauncherWidgetSize(maxWidth ?: 5, maxHeight ?: 5)
      else null,
    resizeOrder =
      when (resizeOrder) {
        LauncherWidgetCaptureResizeOrder.Diagonal -> LauncherResizeOrder.DIAGONAL
        LauncherWidgetCaptureResizeOrder.WidthFirst -> LauncherResizeOrder.WIDTH_FIRST
        LauncherWidgetCaptureResizeOrder.HeightFirst -> LauncherResizeOrder.HEIGHT_FIRST
      },
    launcherMode = launcherMode,
  )

/**
 * Adds Robolectric's `+round` qualifier so `Configuration.isScreenRound` becomes true before
 * capture — that's what Roborazzi's `applyDeviceCrop` keys off to produce a circular crop.
 */
@OptIn(ExperimentalRoborazziApi::class)
private object RoundScreenOption : RoborazziComposeSetupOption {
  override fun configure(configBuilder: RoborazziComposeSetupOption.ConfigBuilder) {
    configBuilder.addRobolectricQualifier("round")
  }
}

internal fun resolveWrapper(wrapperFqn: String): Pair<ComposableMethod, Any> {
  val cls = loadPreviewWrapperClass(wrapperFqn)
  val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
  // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
  // Wrap(Function2, Composer, int). getDeclaredComposableMethod handles the
  // synthetic Composer/changed tail, so we look up by the content param's JVM type.
  val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
  return method to instance
}

/**
 * Default single-shard entry. Runs every preview in the manifest in one JVM, reusing the sandbox
 * across all parameter values. Generated shard subclasses (see the plugin's `generateShardTests`
 * task) replace this class when `composeAiPreview.shards > 1`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RobolectricRenderTest(
  preview: RenderPreviewEntry,
  @Suppress("UNCHECKED_CAST") previewArgs: List<Any?>,
) : RobolectricRenderTestBase(preview, previewArgs) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun previews(): List<Array<Any>> = PreviewManifestLoader.loadShard(0, 1)
  }
}
