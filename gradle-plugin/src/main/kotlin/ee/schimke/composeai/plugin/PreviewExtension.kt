package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.daemon.DaemonExtension
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class PreviewExtension @Inject constructor(private val objects: ObjectFactory) {
  val variant: Property<String> = objects.property(String::class.java).convention("debug")
  val sdkVersion: Property<Int> = objects.property(Int::class.java).convention(35)
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

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
   * When `true`, the plugin wires the AGP `testDebugUnitTest` / `testReleaseUnitTest` tasks to
   * depend on `renderAllPreviews`, so a consumer's pixel-test class (e.g. one that reads the PNGs
   * under `build/compose-previews/renders/`) sees a fully-rendered output directory by the time its
   * assertions run. Mirror of the boilerplate `:samples:android` / `:samples:wear` /
   * `:samples:android-alpha` previously each carried in their own `build.gradle.kts`. Default
   * `false` so consumers without pixel tests don't pay the `renderAllPreviews` cost on every
   * `:check`.
   *
   * Targets the AGP unit-test tasks by name rather than `tasks.withType<Test>()` because the
   * plugin's own `renderPreviews` Test task is what `renderAllPreviews` already depends on —
   * matching it here would create a cycle. No-op on Compose Multiplatform / Desktop modules where
   * those task names don't exist.
   */
  val renderBeforeUnitTests: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /** Generic selector for preview extensions that produce data alongside preview PNGs. */
  val previewExtensions: PreviewExtensionsExtension =
    objects.newInstance(PreviewExtensionsExtension::class.java)

  fun previewExtensions(action: Action<PreviewExtensionsExtension>) {
    action.execute(previewExtensions)
  }

  /**
   * Android XML resource previews — `vector`, `animated-vector`, `adaptive-icon` drawables and
   * mipmaps, plus an `AndroidManifest.xml` icon-attribute reference index. On by default; the tasks
   * self-no-op when the consumer's `res/` tree has no matching XML, so the cost of being
   * always-registered is a single empty `resources.json` write. See [ResourcePreviewsExtension] for
   * the per-axis tuning knobs.
   */
  val resourcePreviews: ResourcePreviewsExtension =
    objects.newInstance(ResourcePreviewsExtension::class.java)

  fun resourcePreviews(action: Action<ResourcePreviewsExtension>) {
    action.execute(resourcePreviews)
  }

  /** Persistent preview daemon configuration. Enabled by default for the VS Code extension. */
  val daemon: DaemonExtension = objects.newInstance(DaemonExtension::class.java)

  fun daemon(action: Action<DaemonExtension>) {
    action.execute(daemon)
  }
}

abstract class PreviewExtensionsExtension @Inject constructor(objects: ObjectFactory) {
  val extensions: NamedDomainObjectContainer<PreviewExtensionConfig> =
    objects.domainObjectContainer(PreviewExtensionConfig::class.java) { name ->
      objects.newInstance(PreviewExtensionConfig::class.java, name)
    }

  val a11y: A11yPreviewExtension = objects.newInstance(A11yPreviewExtension::class.java, "a11y")

  val composeAiTrace: ComposeAiTracePreviewExtension =
    objects.newInstance(ComposeAiTracePreviewExtension::class.java, "composeAiTrace")

  /** Configure the built-in accessibility preview extension. */
  fun a11y(action: Action<A11yPreviewExtension>) {
    action.execute(a11y)
  }

  /** Configure the compose-ai-tools render trace preview extension. */
  fun composeAiTrace(action: Action<ComposeAiTracePreviewExtension>) {
    action.execute(composeAiTrace)
  }

  /**
   * Configure one preview extension by id. [PreviewExtensionConfig.enableAllChecks] enables every
   * check that extension provides; [PreviewExtensionConfig.checks] enables only named checks for
   * that extension.
   */
  fun extension(name: String, action: Action<PreviewExtensionConfig>) {
    action.execute(extensions.maybeCreate(name))
  }
}

abstract class PreviewExtensionConfig
@Inject
constructor(private val extensionName: String, objects: ObjectFactory) : Named {
  override fun getName(): String = extensionName

  /** Internal state behind [enableAllChecks]. Default: false. */
  internal val allChecksEnabled: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /** Enable every check/data product this preview extension provides. */
  fun enableAllChecks() {
    allChecksEnabled.set(true)
  }

  /**
   * Specific check ids to enable for this preview extension when [enableAllChecks] has not been
   * called. For the built-in a11y producer, `atf`, `hierarchy`, and `overlay` all turn on the
   * accessibility render pass.
   */
  val checks: ListProperty<String> =
    objects.listProperty(String::class.java).convention(emptyList())

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

abstract class A11yPreviewExtension
@Inject
constructor(extensionName: String, objects: ObjectFactory) :
  PreviewExtensionConfig(extensionName, objects)

abstract class ComposeAiTracePreviewExtension
@Inject
constructor(extensionName: String, objects: ObjectFactory) :
  PreviewExtensionConfig(extensionName, objects)

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
   * style-specific contents (full-colour composite, or tinted monochrome).
   *
   * Default: every mask — `CIRCLE`, `SQUIRCLE`, `ROUNDED_SQUARE`, `SQUARE`. Restrict to trim
   * rendering cost on modules with many adaptive icons. The [styles] axis multiplies onto this
   * list; one capture is emitted per `(shape × style)` combination, plus one bare `LEGACY` capture
   * per qualifier when [styles] contains [AdaptiveStyle.LEGACY].
   */
  val shapes: ListProperty<AdaptiveShape> =
    objects
      .listProperty(AdaptiveShape::class.java)
      .convention(
        listOf(
          AdaptiveShape.CIRCLE,
          AdaptiveShape.SQUIRCLE,
          AdaptiveShape.ROUNDED_SQUARE,
          AdaptiveShape.SQUARE,
        )
      )

  /**
   * Adaptive-icon style variants to render. [AdaptiveStyle.FULL_COLOR] is the App Search appearance
   * (colour composite); [AdaptiveStyle.THEMED_LIGHT] / [AdaptiveStyle.THEMED_DARK] are the
   * home-screen "Themed icons" appearance (monochrome layer tinted with a 2-tone Material 3
   * baseline palette); [AdaptiveStyle.LEGACY] is the pre-O fallback.
   *
   * Default: every style. Drop [AdaptiveStyle.THEMED_LIGHT] / [AdaptiveStyle.THEMED_DARK] from the
   * list when your icons don't ship a `<monochrome>` layer — captures for those styles are skipped
   * at render time with a warning, but listing them still costs a manifest row each.
   */
  val styles: ListProperty<AdaptiveStyle> =
    objects.listProperty(AdaptiveStyle::class.java).convention(AdaptiveStyle.entries.toList())
}
