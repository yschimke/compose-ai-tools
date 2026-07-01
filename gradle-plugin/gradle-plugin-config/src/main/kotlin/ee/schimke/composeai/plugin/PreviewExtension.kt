package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import ee.schimke.composeai.plugin.daemon.DaemonExtension
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

abstract class PreviewExtension @Inject constructor(private val objects: ObjectFactory) {
  val variant: Property<String> = objects.property(String::class.java).convention("debug")

  /**
   * Override for the Robolectric SDK level baked into the generated `robolectric.properties`. When
   * unset (default), the plugin auto-detects the consumer's `android.compileSdk` and uses that, so
   * `apk-for-local-test.ap_`'s `compileSdkVersion` matches Robolectric's synthesized framework and
   * `PackageParser` can parse the resource APK. Set this only when you deliberately want to render
   * against a different framework level than your `compileSdk` (rare). Must fall within
   * Robolectric's supported range — see [GenerateRobolectricPropertiesTask.MIN_SUPPORTED_SDK] /
   * [GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK].
   */
  val sdkVersion: Property<Int> = objects.property(Int::class.java)

  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Number of parallel JVM forks used to render previews. Default `0` (auto).
   *
   * Special values:
   * - `0` (default): auto — the plugin picks a shard count based on the discovered preview cost
   *   (see [ShardTuning]'s model) and the runner's CPU cores + memory. It only shards when the
   *   predicted saving clears both an absolute and a relative threshold, so light modules stay on a
   *   single fork; heavy ones (many previews, GIF/animated captures) fan out. Falls back to 1 if
   *   `previews.json` hasn't been generated yet — the CLI runs `composePreviewDiscover` as a
   *   separate Gradle invocation before rendering, so on CI the render's configuration already sees
   *   a fresh manifest and auto sizing engages on the first run.
   * - `1`: force no sharding; a single JVM renders every preview.
   * - `≥2`: explicit shard count.
   *
   * Each shard runs a generated `RobolectricRenderTest_ShardN` subclass with its own slice of the
   * manifest (round-robin partition). Within a shard, the Robolectric sandbox is reused across that
   * shard's previews; across shards each JVM pays its own ~3–4s cold-start cost, so sharding is a
   * net win only when the module has enough previews to amortise that overhead — which is exactly
   * what the auto model checks before turning it on.
   */
  val shards: Property<Int> = objects.property(Int::class.java).convention(0)

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
   * When `true`, `composePreviewDiscover` fails the build if it finds zero `@Preview`-annotated
   * functions and emits a diagnostics block to the lifecycle log (classDirs entries with class-file
   * counts, a sample of post-filter dependency JARs, the ClassGraph scan summary, and — if classes
   * WERE scanned but no previews matched — the annotation FQNs observed so users can see whether a
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
   * themselves — `composePreviewDoctor` lists anything missing, and `composePreviewDiscover` / the
   * render task fail fast with the exact coordinates to add.
   *
   * Flip to `false` in projects that enforce strict, explicit dependency management
   * (version-catalog-only, custom BOMs, or consumers that require review before any plugin mutates
   * their graph). Backwards- compatible default keeps existing builds working unchanged.
   */
  val manageDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * When `true` (default), the plugin skips task registration on modules that don't declare a known
   * `@Preview`-tooling dependency (`androidx.compose.ui:ui-tooling-preview`, `compose.components
   * .uiToolingPreview`, `androidx.wear.tiles:tiles-tooling-preview`, …) in any `*Implementation` /
   * `*Api` / `*RuntimeOnly` bucket. The skip keeps convention-plugin-everywhere setups quiet on
   * utility modules without `@Preview` surface.
   *
   * Flip to `false` on the CMP-Android `:composeApp` (issue #241) shape — the consumer applies
   * `com.android.application`, declares no preview-tooling dep itself, but depends on `:shared` via
   * `project(":shared")` where the preview tooling lives. Plugin auto-detection used to follow
   * `project(":foo")` deps across module boundaries to catch this; the walk was dropped under
   * Isolated Projects (`rootProject.findProject(...)` is IP-banned — see issue #1549 for the
   * planned IP-safe redesign). Until that lands this flag is the explicit escape hatch.
   *
   * Override at the command line with `-PcomposePreview.enforcePreviewToolingDependency=false` for
   * a single CLI invocation without editing `build.gradle.kts`.
   */
  val enforcePreviewToolingDependency: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)

  /**
   * When `true`, `composePreviewRender` depends on [ValidatePreviewToolingPresentTask], which walks
   * the resolved `${variant}RuntimeClasspath` and fails the build (with a remediation message) if
   * no known `@Preview` tooling coord is reachable. Only meaningful on modules that passed the
   * config-time gate via the tier-2 over-approximation (Compose plugin + `project(":...")` deps but
   * no directly-declared tooling coord) — when the consumer declared a tooling coord directly, the
   * validator isn't registered regardless.
   *
   * Default: `false` — render proceeds and surfaces whatever the actual failure mode is (often
   * "discovery found zero previews", since the discovery task walks the classpath for
   * annotation-bearing classes). Flip to `true` for fast-fail in CI when a missing-tooling
   * regression on a multi-module app should stop the build at the gate with a coordinate to add,
   * rather than at render time with a less-direct error.
   *
   * Override at the command line with `-PcomposePreview.failOnMissingPreviewTooling=true` for a
   * single CLI invocation without editing `build.gradle.kts`.
   */
  val failOnMissingPreviewTooling: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * When `true`, the plugin wires the AGP `testDebugUnitTest` / `testReleaseUnitTest` tasks to
   * depend on `composePreviewRenderAll`, so a consumer's pixel-test class (e.g. one that reads the
   * PNGs under `build/compose-previews/renders/`) sees a fully-rendered output directory by the
   * time its assertions run. Mirror of the boilerplate `:samples:android` / `:samples:wear` /
   * `:samples:android-alpha` previously each carried in their own `build.gradle.kts`. Default
   * `false` so consumers without pixel tests don't pay the `composePreviewRenderAll` cost on every
   * `:check`.
   *
   * Targets the AGP unit-test tasks by name rather than `tasks.withType<Test>()` because the
   * plugin's own `composePreviewRender` Test task is what `composePreviewRenderAll` already depends
   * on — matching it here would create a cycle. No-op on Compose Multiplatform / Desktop modules
   * where those task names don't exist.
   */
  val renderBeforeUnitTests: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * Forces the XR subspace render path on. When the effective value is true, the plugin registers
   * `composePreviewRenderXr` (and folds it into `composePreviewRenderAll`), pulling
   * `:renderer-xr` + the fake XR runtime onto a dedicated render configuration to render
   * `@XrSubspacePreview` functions to `scene.json`.
   *
   * Usually you don't set this: the plugin **auto-enables** the XR path for any module that
   * declares an `androidx.xr.compose` dependency (see
   * `AndroidPreviewSupport.moduleDeclaresXrCompose`), the same declared-dependency signal it uses
   * to auto-inject the Wear Tiles renderer — so `@XrSubspacePreview`s render with zero
   * `composePreview { }` configuration. Set this to true only to force the path on for a module
   * that pulls `androidx.xr.compose` in transitively rather than declaring it directly.
   *
   * The auto-detect is gated on the declared dependency (not always-on) because
   * `androidx.xr.compose` declares `minCompileSdk = 36` and the XR `*-testing` fakes are
   * heavyweight, so a non-XR consumer (especially below compileSdk 36) must never pay for them on
   * its render classpath.
   */
  val enableXrPreviews: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

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

  val composeAiTrace: ComposeAiTracePreviewExtension =
    objects.newInstance(ComposeAiTracePreviewExtension::class.java, "composeAiTrace")

  /** Configure the compose-ai-tools render trace preview extension. */
  fun composeAiTrace(action: Action<ComposeAiTracePreviewExtension>) {
    action.execute(composeAiTrace)
  }

  // NOTE: the `a11y` typed DSL / `previewExtensions.a11y { enableAllChecks() }` block and the
  // matching `composePreview.previewExtensions.a11y.enableAllChecks` Gradle property are gone.
  // A11y data products (ATF + hierarchy) are produced exclusively by `:daemon:android`'s
  // `RenderEngine` now — the standalone Gradle render task does not participate. Consumers
  // opt into a11y by:
  //   - the chip toggle in VS Code (drives the daemon's per-preview subscription path), or
  //   - `compose-preview a11y` (which spins up a temporary daemon).
  // There is no gradle-plugin or VS Code DSL knob anymore — opting in is a per-invocation
  // decision through one of those daemon entry points.

  init {
    // Eagerly register the built-in `composeAiTrace` extension id in the generic container so
    // `extensions.findByName("composeAiTrace")` is non-null at every phase — plugin task wiring
    // runs during plugin apply, *before* the build script's `composePreview { previewExtensions
    // { … } }` block evaluates, and we don't want to snapshot `null` for a generic entry the
    // user configures later via `extension("composeAiTrace") { … }`.
    //
    // The user's `extension(name, action)` method below routes through `maybeCreate`, which
    // returns this pre-registered instance instead of creating a new one — so user-written
    // generic config flows into the same Property objects the resolvers read from.
    //
    // Configuration-cache safe: `maybeCreate` runs at extension construction time, which
    // happens during plugin apply — pure configuration phase, never serialized. The Property
    // values themselves are evaluated lazily by the resolvers' `zip`/`map` chains.
    extensions.maybeCreate("composeAiTrace")
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

  /**
   * Read-only view of [enableAllChecks] state. Exposed for the runtime plugin (`:gradle-plugin`, a
   * separate module from this shared DSL artifact) to fold into its resolver chain without widening
   * the consumer-facing DSL to a settable `Property`.
   */
  val allChecksEnabledProvider: Provider<Boolean>
    get() = allChecksEnabled

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
   * `composePreviewRenderAndroidResources` showing up in `gradle tasks` listings.
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

  /**
   * 9-patch stretch variants to render. Each value drives a different `(width, height)` target on
   * the same `NinePatchDrawable`, so a reviewer can see how the patches stretch as the container
   * grows — [NinePatchStretch.INTRINSIC] at natural size, [NinePatchStretch.HORIZONTAL] /
   * [NinePatchStretch.VERTICAL] at 2× one axis, [NinePatchStretch.BOTH] at 2× both axes.
   *
   * Default: every variant (4 captures per 9-patch per qualifier). Trim this list on modules with
   * many 9-patches and only one stretch axis of interest.
   */
  val stretches: ListProperty<NinePatchStretch> =
    objects.listProperty(NinePatchStretch::class.java).convention(NinePatchStretch.entries.toList())

  /**
   * When `true` (default), every [ResourceType.ANIMATED_VECTOR] resource gets a second capture per
   * qualifier — a horizontal PNG composited from keyframe Bitmaps sampled at [filmstripFractions] ×
   * the animation's reported `totalDuration`. Lets reviewers diff stills in code review without
   * scrubbing the sibling GIF.
   *
   * Filename ends `_filmstrip.png` (e.g.
   * `renders/resources/drawable/avd_pulse_xhdpi_filmstrip.png`). Cost is ~`RESOURCE_ANIMATED_COST /
   * 5` — fewer frames than the GIF and no encode loop.
   *
   * Set `false` on modules where the GIF is enough and the extra PNG would be noise.
   */
  val filmstrip: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Keyframe fractions for the filmstrip capture. Each value is a fraction of the resolved
   * animation duration in `[0, 1]`; the renderer samples one bitmap per fraction via
   * `AnimatorSet.setCurrentPlayTime` and composites the frames side-by-side. Cell count = list
   * size; the rendered PNG width is `intrinsicWidth × fractionsCount`.
   *
   * Default: `[0.0, 0.25, 0.5, 0.75, 1.0]` (5 cells, equally spaced). Override to e.g. `[0.0, 0.5,
   * 1.0]` for a 3-cell strip on long animations, or `[0.0, 0.2, 0.4, 0.6, 0.8, 1.0]` for a
   * finer-grained 6-cell sweep.
   */
  val filmstripFractions: ListProperty<Float> =
    objects.listProperty(Float::class.java).convention(DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS)
}
