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
import androidx.compose.ui.test.onRoot
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
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.FocusDirection as ProtocolFocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.LauncherResizeOrder
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.data.render.PreviewAnimationContext
import ee.schimke.composeai.data.render.extensions.DataExtensionId
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
      manifest.previews
        .filter {
          it.params.kind != PreviewKind.XR_SUBSPACE &&
            it.params.kind != PreviewKind.LOTTIE &&
            it.params.kind != PreviewKind.SVG
        }
        .map { it to expandParameterProvider(it) }
    val expanded = expandedByEntry.flatMap { it.second }
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
    // and new-shape PNGs on disk. Runs at shard-load time, before any
    // test body writes to the directory.
    deleteStaleFanoutFiles(
      outDir = System.getProperty("composeai.render.outputDir")?.let(::File),
      allEntries = manifest.previews,
      expandedByEntry = expandedByEntry,
      ownedIds = ours.map { it.entry.id }.toSet(),
    )
    return ours.map { arrayOf<Any>(it.entry, it.previewArgs) }
  }

  internal data class PreviewRow(val entry: RenderPreviewEntry, val previewArgs: List<Any?>)

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
    val values = loadProviderValues(providerFqn, limit)
    if (values.isEmpty()) {
      System.err.println(
        "@PreviewParameter(provider = $providerFqn) on '${entry.id}' produced no values — skipping."
      )
      return emptyList()
    }
    val suffixes = PreviewParameterLabels.suffixesFor(values)
    val rows = values.mapIndexed { idx, value ->
      val paramSuffix = suffixes[idx]
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
      )
    }
    return rows
  }

  /**
   * Deletes stale `<stem>_*<ext>` fan-out files for the parameterized previews in
   * [expandedByEntry].
   *
   * Two guards keep the prefix match from destroying correct sibling output (issue #2193):
   * - **Manifest-wide expected set.** `@Preview(name = …)` / `@Preview(group = …)` variant suffixes
   *   make a sibling preview's stem an underscore-extension of the base stem (`Foo` vs `Foo_Dark`),
   *   so the sibling's base render and its own fan-out (`Foo_Dark_<label>.png`) match `Foo_*`. The
   *   exclusion set therefore spans every manifest entry's outputs — declared ([allEntries]) and
   *   expanded ([expandedByEntry]) — not just the preview whose prefix is being swept.
   * - **Shard-owned pass.** Every parallel fork (`maxParallelForks = shardCount`) expands the whole
   *   manifest at shard load, at a time when sibling forks may already be rendering — under the old
   *   per-entry expected set a late-loading fork would delete fan-out PNGs another fork had just
   *   written. The manifest-wide set makes that impossible (every fork's outputs are expected), and
   *   gating the sweep on [ownedIds] additionally keeps forks whose shard was assigned none of a
   *   preview's fan-out from redundantly re-sweeping its prefix.
   */
  internal fun deleteStaleFanoutFiles(
    outDir: File?,
    allEntries: List<RenderPreviewEntry>,
    expandedByEntry: List<Pair<RenderPreviewEntry, List<PreviewRow>>>,
    ownedIds: Set<String>,
  ) {
    if (outDir == null || !outDir.isDirectory) return
    val expectedNames = buildSet {
      allEntries.flatMap { it.captures }.mapNotNullTo(this) { fanoutLeaf(it.renderOutput) }
      expandedByEntry
        .flatMap { it.second }
        .flatMap { it.entry.captures }
        .mapNotNullTo(this) { fanoutLeaf(it.renderOutput) }
    }
    for ((entry, rows) in expandedByEntry) {
      if (entry.params.previewParameterProviderClassName == null) continue
      if (rows.none { it.entry.id in ownedIds }) continue
      for (template in entry.captures) {
        val templateFile = File(outDir, template.renderOutput.substringAfter("renders/"))
        val dir = templateFile.parentFile ?: continue
        val stem = templateFile.nameWithoutExtension
        val ext = ".${templateFile.extension}"
        val prefix = stem + "_"
        dir
          .listFiles()
          ?.filter { f ->
            f.name.startsWith(prefix) && f.name.endsWith(ext) && f.name !in expectedNames
          }
          ?.forEach { f ->
            if (!f.delete()) {
              System.err.println("Failed to delete stale fan-out file: ${f.absolutePath}")
            }
          }
      }
    }
  }

  private fun fanoutLeaf(renderOutput: String): String? {
    if (renderOutput.isEmpty()) return null
    return renderOutput.substringAfterLast('/').ifEmpty { renderOutput }
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

  private fun loadProviderValues(providerFqn: String, limit: Int): List<Any?> {
    val clazz =
      try {
        Class.forName(providerFqn)
      } catch (e: ClassNotFoundException) {
        System.err.println(
          "@PreviewParameter: provider class $providerFqn not found on test classpath — skipping."
        )
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
            "@PreviewParameter: couldn't instantiate $providerFqn via nullary constructor: ${e.message}"
          )
          return emptyList()
        }
    // `PreviewParameterProvider<T>` exposes `values: Sequence<T>` as a Kotlin
    // property — its JVM signature is `getValues(): Sequence`. Look up the
    // method by name to avoid taking a compile-time dependency on the
    // provider interface (which lives in the consumer's Compose artifact).
    val getValues =
      runCatching { clazz.getMethod("getValues") }
        .getOrElse {
          System.err.println(
            "@PreviewParameter: $providerFqn has no getValues() — not a PreviewParameterProvider?"
          )
          return emptyList()
        }
    @Suppress("UNCHECKED_CAST")
    val sequence = getValues.invoke(instance) as? Sequence<Any?> ?: return emptyList()
    // `Sequence.take(Int).toList()` is the Kotlin stdlib contract —
    // drives the sequence lazily up to `limit` without requiring
    // reflective access into package-private iterator implementations
    // (`kotlin.jvm.internal.ArrayIterator`, which `Method.invoke`
    // rejects with IllegalAccessException from outside the stdlib
    // module).
    return sequence.take(limit).toList()
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
    val outputDir =
      File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
    outputDir.mkdirs()

    val params = preview.params
    // AS-parity sizing: an axis wraps to intrinsic content when the user
    // didn't specify it (and didn't pick a device/showSystemUi frame —
    // discovery has already pre-resolved those cases). We use a generous
    // sandbox dp for wrapped axes so the Robolectric window / Configuration
    // has a finite, coherent size; the captured PNG is cropped back down
    // to the measured content bounds after capture.
    val wrapWidth = params.widthDp == null || params.widthDp <= 0
    val wrapHeight = params.heightDp == null || params.heightDp <= 0
    val widthDp = params.widthDp?.takeIf { it > 0 } ?: SANDBOX_WIDTH_DP
    val heightDp = params.heightDp?.takeIf { it > 0 } ?: SANDBOX_HEIGHT_DP
    // Round crop fires when the preview is on a round device AND it's the
    // kind of surface that fills the watch — either a @Composable the user
    // asked for system UI on, or a tile (tiles always fill the watchface,
    // so `showSystemUi` is never set for them). Without the tile branch,
    // tile previews render as rectangles even on wearos_*_round devices.
    val isRound =
      isRoundDevice(params.device) && (params.showSystemUi || params.kind == PreviewKind.TILE)

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
          background(params.showBackground, params.backgroundColor)
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
    // Drop any IR sidecar from a prior run before rendering. The renders dir is reused, and
    // `BundlePreviewTask.resolvePreviewIr` treats any non-empty sidecar as authoritative — so a
    // preview that stops producing IR (RC wrapper removed, tile serialization fails, kind
    // changed) would otherwise leave stale `<stem>.{rcdoc,tilelayout,tileresources}` that a later
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
    try {
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
      // Render succeeded: if the preview's flavour captured an IR, write it beside the PNG as
      // the `renders/<stem>.<ext>` sidecar `BundlePreviewTask.resolvePreviewIr` packs.
      writeIrSidecar(pngFile, preview.id)
      // Write the editable knobs the preview declared via `previewOverride*` as the
      // `renders/<stem>.overrides.json` sidecar `BundlePreviewTask.resolvePreviewOverrides` packs.
      writeOverridesSidecar(pngFile)
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
   * `BundlePreviewTask.resolvePreviewIr` reads: `.rcdoc` for a Remote Compose document, or
   * `.tilelayout` (+ `.tileresources`) for a Wear protolayout proto. Best-effort — a write failure
   * must not derail the PNG render path.
   */
  private fun deleteStaleIrSidecars(pngFile: File) {
    val dir = pngFile.parentFile ?: return
    val stem = pngFile.nameWithoutExtension
    for (ext in listOf("rcdoc", "tilelayout", "tileresources")) {
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
          File(dir, "$stem.rcdoc").writeBytes(capture.bytes)
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
    PixelSystemFontAliases.seedSystemFontMap()

    applyPreviewQualifiers(
      widthDp = widthDp,
      heightDp = heightDp,
      isRound =
        isRoundDevice(params.device) && (params.showSystemUi || params.kind == PreviewKind.TILE),
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
          rule.runOnUiThread { rule.activity.window.decorView.setBackgroundColor(bg) }
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
          val reduceMotion =
            preview.captures.any { it.scroll?.reduceMotion == true } ||
              preview.dataProducts.any { it.scroll?.reduceMotion == true }
          val reduceMotionLocal = if (reduceMotion) WearReduceMotionLocal.get() else null
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
          val keyboardExtension = KeyboardOverrideExtension()
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
          val providedValues =
            buildList {
                add(LocalInspectionMode provides inspectionMode)
                if (scrollCaptureProvidable != null) {
                  add(scrollCaptureProvidable provides scrollCaptureInProgress)
                }
                if (reduceMotionLocal != null) {
                  add(reduceMotionLocal provides true)
                }
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
            CompositionLocalProvider(values = providedValues) {
              if (focusExtension != null) {
                capturedView = androidx.compose.ui.platform.LocalView.current
              }
              val previewBody: @Composable () -> Unit = {
                val core: @Composable () -> Unit = {
                  if (wrapWidth || wrapHeight) {
                    MeasuredWrapBox(
                      wrapWidth = wrapWidth,
                      wrapHeight = wrapHeight,
                      onMeasured = { measured = it },
                    ) {
                      strategyFor(params.kind).Render(preview, widthDp, heightDp, previewArgs)
                    }
                  } else {
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
              val pseudoOrPlain: @Composable () -> Unit = {
                if (pseudolocaleExtension != null) {
                  pseudolocaleExtension.AroundComposable { launcherWidgetOrPlain() }
                } else {
                  launcherWidgetOrPlain()
                }
              }
              keyboardExtension.AroundComposable { pseudoOrPlain() }
            }
          }
          // With `mainClock.autoAdvance = false` the clock stays at 0
          // until we step it, so capturing each frame at its intended
          // virtual time is a matter of advancing the delta.
          // `DiscoverPreviewsTask` guarantees `captures` is ordered by
          // ascending `advanceTimeMillis`, so we accumulate forward-only.
          var currentTime = 0L
          val onRoot = rule.onRoot()
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
            val longHandled =
              scroll != null &&
                scroll.mode == ScrollMode.LONG &&
                scroll.axis == ScrollAxis.VERTICAL &&
                handleLongCapture(
                  rule = rule,
                  scroll = scroll,
                  previewId = preview.id,
                  heightDp = heightDp,
                  isRound =
                    isRoundDevice(params.device) &&
                      (params.showSystemUi || params.kind == PreviewKind.TILE),
                  outputFile = outputFile,
                )
            // @ScrollingPreview(GIF): drive the scroller by small
            // steps and encode the sequence as an animated GIF.
            // Same multi-frame shape as LONG, but encodes into a
            // GIF container instead of stitching one tall PNG.
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
                  isRound =
                    isRoundDevice(params.device) &&
                      (params.showSystemUi || params.kind == PreviewKind.TILE),
                  outputFile = outputFile,
                )

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
                    isRound =
                      isRoundDevice(params.device) &&
                        (params.showSystemUi || params.kind == PreviewKind.TILE),
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
                    isRound =
                      isRoundDevice(params.device) &&
                        (params.showSystemUi || params.kind == PreviewKind.TILE),
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
            if (productFellThrough) {
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
                val result =
                  driveScrollToEnd(
                    rule = rule,
                    axis = scroll.axis.toProductAxis(),
                    maxScrollPx = scroll.maxScrollPx,
                  )
                if (result is ScrollDriveResult.NoScrollable) {
                  System.err.println(
                    "@ScrollingPreview on '${preview.id}' but no scrollable " +
                      "composable found on axis ${scroll.axis} — capturing initial frame."
                  )
                } else {
                  // Let animations that begin once the scroll lands settle to their
                  // resting state before capture — notably Wear `ScreenScaffold`'s
                  // `EdgeButton`, which reveals/expands on reaching the end. Without
                  // this, END snapshots the button mid-reveal (overscroll-stretched).
                  // Mirrors the LONG final-frame path (see [handleLongCapture]).
                  settlePostScrollAnimations(rule)
                }
              }
              onRoot.captureRoboImage(file = outputFile, roborazziOptions = roborazziOptions)
            }

            // AS-parity: crop the PNG down to the composable's
            // intrinsic size on wrapped axes. Skipped for stitched
            // LONG output, scroll GIF output, @AnimatedPreview GIF
            // output, and @FocusedPreview(gif=true) GIF output —
            // those files' dimensions are the full scrollable
            // extent / frame size, not the composable's intrinsic box.
            if (
              !productFellThrough &&
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
   * strict about the sequence. Locale comes before width/height, which come before orientation,
   * which comes before night-mode, which comes before density. Out-of-order qualifiers produce
   * `IllegalArgumentException: failed to parse qualifiers` at runtime.
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
      if (widthDp > 0) add("w${widthDp}dp")
      if (heightDp > 0) add("h${heightDp}dp")
      if (isRound) add("round")
      if (widthDp > 0 && heightDp > 0) {
        add(if (widthDp > heightDp) "land" else "port")
      }
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
              minWidth = if (wrapWidth) 0 else constraints.minWidth,
              maxWidth = constraints.maxWidth,
              minHeight = if (wrapHeight) 0 else constraints.minHeight,
              maxHeight = constraints.maxHeight,
            )
          val placeable = measurable.measure(wrappedConstraints)
          onMeasured(IntSize(placeable.width, placeable.height))
          layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    ) {
      content()
    }
  }

  private fun resolveBackgroundColor(
    params: RenderPreviewParams
  ): androidx.compose.ui.graphics.Color =
    when {
      params.backgroundColor != 0L ->
        androidx.compose.ui.graphics.Color(params.backgroundColor.toInt())
      params.showBackground -> androidx.compose.ui.graphics.Color.White
      else -> androidx.compose.ui.graphics.Color.Transparent
    }

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
        rule.onRoot().captureRoboImage(file = sliceFile, roborazziOptions = sliceRoborazziOptions)
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
    rule.onRoot().captureRoboImage(file = finalFrameFile, roborazziOptions = sliceRoborazziOptions)

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

  fun captureFrame(delayMs: Int) {
    val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
    captureDecodableFrame(frameFile, role = "scroll GIF") { f ->
      rule.onRoot().captureRoboImage(file = f, roborazziOptions = frameRoborazziOptions)
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
      rule.onRoot().captureRoboImage(file = f, roborazziOptions = frameRoborazziOptions)
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
        rule.onRoot().captureRoboImage(file = f, roborazziOptions = frameRoborazziOptions)
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
 * Maps the renderer-side [GestureHintCapture] (read from `previews.json`) onto the connector's
 * `protocol.GestureOverride` wire shape — only the `showHints` immediate-mode flag; `@GestureHintPreview`
 * carries no invoke / enabled dimensions (those are daemon-interactive concerns).
 */
private fun GestureHintCapture.toGestureOverride(): GestureOverride =
  GestureOverride(showHints = showHints)

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
