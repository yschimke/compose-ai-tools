package ee.schimke.composeai.plugin

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class PreviewExtension @Inject constructor(private val objects: ObjectFactory) {
  val variant: Property<String> = objects.property(String::class.java).convention("debug")
  val sdkVersion: Property<Int> = objects.property(Int::class.java).convention(35)
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * When true, each `renderAllPreviews` run archives every rendered PNG whose content differs from
   * the most recent entry into [historyDir]. Default: false.
   */
  val historyEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * Root directory for preview history snapshots. Each preview gets a subfolder named after its id;
   * inside, filenames are timestamps. Lives outside `build/` by default so `./gradlew clean`
   * doesn't wipe it.
   */
  val historyDir: DirectoryProperty = objects.directoryProperty()

  /**
   * Number of parallel JVM forks used to render previews. Default 1 (no sharding).
   *
   * Special values:
   * - `1` (default): no sharding; a single JVM renders every preview.
   * - `0`: auto — the plugin picks a shard count based on the discovered preview count and
   *   available CPU cores, using [ShardTuning]'s cost model. Falls back to 1 if previews.json
   *   hasn't been generated yet.
   * - `≥2`: explicit shard count.
   *
   * Each shard runs a generated `RobolectricRenderTest_ShardN` subclass with its own slice of the
   * manifest (round-robin partition). Within a shard, the Robolectric sandbox is reused across that
   * shard's previews; across shards each JVM pays its own ~3–4s cold-start cost, so sharding is a
   * net win only when the module has enough previews to amortise that overhead.
   */
  val shards: Property<Int> = objects.property(Int::class.java).convention(1)

  /**
   * When `true`, Robolectric instantiates the consumer's manifest-declared `Application` class
   * (e.g. `MyApp : Application()`) before rendering each preview. Default: `false` — the renderer
   * installs a plain `android.app.Application` via a generated package-level
   * `robolectric.properties`, so consumer-side init (DI containers, `BridgingManager.setConfig`,
   * Firebase bootstrap, WorkManager scheduling, …) does NOT run during preview rendering.
   *
   * Stub by default because Application-level init routinely fails in Robolectric — it depends on
   * platform features the sandbox doesn't emulate (Play Services, Firebase, Wear `FEATURE_WATCH`).
   * Previews should be self-contained composables anyway, not coupled to app-lifecycle state.
   *
   * Flip to `true` only if your previews genuinely depend on your custom Application being
   * constructed (rare) — and expect to supply a Robolectric-safe subclass guarded against
   * unsupported APIs.
   */
  val useConsumerApplication: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * When `true`, `discoverPreviews` fails the build if it finds zero `@Preview`-annotated functions
   * and emits a diagnostics block to the lifecycle log (classDirs entries with class-file counts, a
   * sample of post-filter dependency JARs, the ClassGraph scan summary, and — if classes WERE
   * scanned but no previews matched — the annotation FQNs observed so users can see whether a
   * different-FQN `@Preview` is in use). Default: `false`, so existing empty modules stay silent.
   *
   * Intended mainly for CI (catch a silent regression where a wiring change drops every preview)
   * and for triaging "0 previews discovered" reports — hence the double duty: the flag that fails
   * the build also turns on the logging you need to know why it failed.
   *
   * Override at the command line with `-PcomposePreview.failOnEmpty=true` to flip for a single run
   * without editing `build.gradle.kts`.
   */
  val failOnEmpty: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * When `true` (default), the plugin auto-adds the test/runtime dependencies it needs
   * (`androidx.compose.ui:ui-test-manifest`, `:ui-test-junit4`, and conditionally
   * `androidx.wear.tiles:tiles-renderer`) to the consumer's classpath. When `false`, the plugin
   * injects nothing and instead requires the consumer to declare every required coordinate
   * themselves — `composePreviewDoctor` lists anything missing, and `discoverPreviews` / the render
   * task fail fast with the exact coordinates to add.
   *
   * Flip to `false` in projects that enforce strict, explicit dependency management
   * (version-catalog-only, custom BOMs, or consumers that require review before any plugin mutates
   * their graph). Backwards- compatible default keeps existing builds working unchanged.
   */
  val manageDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * ATF (Accessibility Test Framework) checks, run against each rendered preview. Off by default —
   * turning it on surfaces findings in the CLI, VSCode diagnostics, and `accessibility.json`, but
   * does NOT break the build. Set `failOnErrors = true` / `failOnWarnings = true` to gate builds on
   * findings.
   *
   *     composePreview {
   *         accessibilityChecks {
   *             enabled = true
   *             failOnErrors = true  // opt in to build gating
   *         }
   *     }
   */
  val accessibilityChecks: AccessibilityChecksExtension =
    objects.newInstance(AccessibilityChecksExtension::class.java)

  fun accessibilityChecks(action: Action<AccessibilityChecksExtension>) {
    action.execute(accessibilityChecks)
  }

  /**
   * Android XML resource previews — `vector`, `animated-vector`, `adaptive-icon` drawables and
   * mipmaps, plus an `AndroidManifest.xml` icon-attribute reference index. On by default; the tasks
   * self-no-op when the consumer's `res/` tree has no matching XML, so the cost of being
   * always-registered is a single empty `resources.json` write. See
   * `docs/ANDROID_RESOURCE_PREVIEWS.md` for the data model and [ResourcePreviewsExtension] for the
   * per-axis tuning knobs.
   */
  val resourcePreviews: ResourcePreviewsExtension =
    objects.newInstance(ResourcePreviewsExtension::class.java)

  fun resourcePreviews(action: Action<ResourcePreviewsExtension>) {
    action.execute(resourcePreviews)
  }
}

abstract class AccessibilityChecksExtension @Inject constructor(objects: ObjectFactory) {
  /** Default: `false`. Enabling turns on ATF checks and the `verifyAccessibility` task. */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * Fail the build if any ERROR-level ATF finding is reported. Default: `false` — findings are
   * reported (logged, written to the JSON report, surfaced as CLI/VSCode diagnostics) but do not
   * fail the build unless the consumer explicitly opts in. That way turning on `enabled` is a safe,
   * purely additive change.
   */
  val failOnErrors: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * Fail the build if any WARNING-level ATF finding is reported. Default: `false` (same rationale
   * as [failOnErrors]).
   */
  val failOnWarnings: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * Generate an annotated screenshot per preview showing each finding as a numbered badge + legend
   * panel. Costs ~10ms/preview when there are findings, zero when there aren't. Default: `true` —
   * if you asked for checks, you probably want to see what they found. Set to `false` for CI jobs
   * that only care about the JSON / fail-on-errors gate.
   */
  val annotateScreenshots: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)
}

abstract class ResourcePreviewsExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Default: `true`. The discovery + render tasks self-no-op on modules with no `<vector>` /
   * `<animated-vector>` / `<adaptive-icon>` files (a single empty `resources.json` write), so the
   * cost of being always-registered is negligible. Set `false` to skip task registration outright —
   * useful for modules that explicitly don't want `resources.json` produced or
   * `renderAndroidResources` showing up in `gradle tasks` listings.
   */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Density buckets to fan out implicit captures over. Applied to every resource that doesn't
   * already have a density qualifier on its source-file directory; when a consumer has explicit
   * `drawable-xhdpi/foo.xml` etc., that variant uses the consumer's source file directly and isn't
   * multiplied through [densities] again.
   *
   * Default: `["xhdpi"]` — single bucket so the JSON manifest stays small in the common case.
   * Override to `["mdpi", "xhdpi", "xxxhdpi"]` for thorough density sweeps.
   */
  val densities: ListProperty<String> =
    objects.listProperty(String::class.java).convention(listOf("xhdpi"))

  /**
   * Adaptive-icon shape masks to render. Each shape is applied as a canvas clip on top of the
   * composed foreground+background. `LEGACY` falls back to the `<adaptive-icon android:icon=…>`
   * slot or to the foreground rendered against a transparent background.
   *
   * Default: every shape — `CIRCLE`, `ROUNDED_SQUARE`, `SQUARE`, `LEGACY`. Restrict the list to
   * trim down rendering cost on modules with many adaptive icons.
   */
  val shapes: ListProperty<AdaptiveShape> =
    objects.listProperty(AdaptiveShape::class.java).convention(AdaptiveShape.entries.toList())
}
