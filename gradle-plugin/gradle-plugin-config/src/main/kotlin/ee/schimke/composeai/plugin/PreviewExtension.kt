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

  /**
   * The Android theme the preview host activity runs under, e.g. `"@style/Theme.Foo"` (also accepts
   * `"com.example:style/Theme.Foo"` or a bare `"Theme.Foo"`).
   *
   * Leave it unset in an **application** module: the host activity already inherits `<application
   * android:theme>` from the merged manifest, which is what makes an `AndroidView` preview resolve
   * app-owned `?attr/…` references.
   *
   * Set it in a **library** module whose previews host platform views. A library's merged manifest
   * has no `<application android:theme>` to inherit, so the host activity falls back to the
   * platform default — and a `TextView` styled through, say, `?attr/primaryText` then dies at
   * inflation with `UnsupportedOperationException: Failed to resolve attribute at index N`, which
   * aborts the render and leaves the preview with no PNG at all (issue #2957). The renderer can't
   * guess which of a design system's themes to use, so name one here; Android Studio's preview pane
   * has a theme picker for the same reason.
   *
   * Override for a single run with `-PcomposePreview.hostTheme=@style/Theme.Foo` (or
   * `-Dcomposeai.render.hostTheme=…`), which takes precedence over this value.
   */
  val hostTheme: Property<String> = objects.property(String::class.java)

  /**
   * The wall-clock instant preview renders are pinned to, so a screen that paints the time produces
   * the same PNG on every run instead of diffing every minute (issue #3239). Matters most for
   * `kind=ACTIVITY` heroes and app tours, where the app's own screen is the subject and there is no
   * `@Preview` argument to inject a fixed clock through — on Wear that is close to every activity
   * preview, since `TimeText` is standard furniture inside `AppScaffold`.
   *
   * Unset (default) pins `10:10`, the literal the Wear/Remote design catalogs and `:samples:wear`'s
   * `FixedPreviewTimeSource` already paint, so an activity hero and a hand-authored preview of the
   * same screen agree. Accepts `"HH:mm"` / `"HH:mm:ss"`, an ISO-8601 local date-time
   * (`"2024-01-01T10:10"`) when the date matters too, bare epoch millis, or `"off"` to render
   * against the host's wall clock as before.
   *
   * Times are interpreted in the render JVM's default zone, so the *rendered string* — not the
   * underlying instant — is what stays identical between a laptop and CI.
   *
   * **Android only, and only where the render can intercept the clock.** The guarantee is
   * implemented by shadowing, under Robolectric, the one function Wear's `TimeText` reads
   * (`androidx.wear.compose.materialcore.ResourcesKt.currentTimeMillis`). The Desktop / CMP lane
   * has no Robolectric and so no interception point at all — this value is not forwarded there
   * rather than forwarded and silently ignored. On Android it likewise cannot reach a preview that
   * reads the clock through `java.time.*.now()` or `Calendar.getInstance()`, because `java.` is on
   * Robolectric's do-not-acquire list; hoist the clock out of such a composable instead.
   *
   * Override for a single run with `-PcomposePreview.fixedTime=09:41` (or
   * `-Dcomposeai.render.fixedTime=…`), which takes precedence over this value.
   */
  val fixedTime: Property<String> = objects.property(String::class.java)

  /**
   * Renders this module's previews with the Compose runtime's rewritten `SlotTable` — the "link
   * buffer" composer the Compose Runtime team shipped in 1.12.0 behind
   * `ComposeRuntimeFlags.isLinkBufferComposerEnabled` (the flag exists on the 1.11.x line too). It
   * is a performance rewrite of composition's random-write path, slated to become the default and
   * then to lose its flag, and the team asked for correctness and performance feedback ahead of
   * that.
   *
   * `false` (default) leaves the runtime on whatever it ships as its own default, so nothing
   * changes until a build asks for it.
   *
   * What makes this worth a knob rather than a one-off patch: a module's rendered PNGs are a
   * committed corpus of Compose output, so rendering the same previews twice — once with the flag,
   * once without — turns any catalog here into a pixel-level regression suite for the rewrite. Both
   * lanes honour it (Android/Robolectric and Desktop/CMP), because it is a runtime-level flag with
   * no platform-specific interception, unlike [fixedTime].
   *
   * **Whole-run, not per-preview.** The runtime latches the flag at the first composition in a
   * JVM (on Android, in a Robolectric sandbox), and the lanes here render many previews per JVM —
   * so this selects a composer for the whole render, which is also how the runtime team frames it
   * ("set the flag before you compose any content").
   *
   * `true` here means **required**: against a Compose runtime with no such flag — an older one, or
   * a future one that has completed the migration and removed it — the render fails with a message
   * naming the flag, rather than quietly rendering the old composer and reporting a clean run that
   * tested nothing. That is the right strictness for a per-module value, where the author knows
   * which Compose the module resolves.
   *
   * A *build-wide* setting spanning modules on different Compose versions wants the other
   * strictness, and only the property form can express it:
   * `-PcomposePreview.linkBufferComposer=auto` enables the new composer wherever the runtime has
   * the flag and renders on the old one — saying so in the render log — where it doesn't. There is
   * deliberately no `auto` in this DSL: a module-scoped opt-in has one Compose version to be true
   * of, so it should say which behaviour it wants outright.
   *
   * Override for a single run with `-PcomposePreview.linkBufferComposer=true|auto|false` (or
   * `-Dcomposeai.render.linkBufferComposer=…`), which takes precedence over this value.
   */
  val linkBufferComposer: Property<Boolean> = objects.property(Boolean::class.java)

  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * JDK major version the preview *render subprocess* forks into (e.g. `21`). Escape hatch for the
   * automatic selection: leave it unset (default) and the plugin picks a render JVM new enough to
   * load the module's compiled classes on its own — the maximum of the consumer's toolchain, the
   * JVM Gradle is running on, and the highest detected Kotlin `jvmTarget` / Java
   * `targetCompatibility` — provisioning a matching JDK through Gradle's toolchain service when an
   * upgrade is needed.
   *
   * Set this only to override that decision: pin a specific JDK for reproducibility, or force one
   * when the module's bytecode target can't be auto-detected (e.g. a non-standard Kotlin compile
   * setup). The value is honoured verbatim, including a deliberately *lower* JDK than the module
   * compiles to — in which case classes may fail to load with `UnsupportedClassVersionError`, so
   * lower it only when you know the render classpath stays within that JDK's bytecode level.
   *
   * A matching JDK must be resolvable — installed and discoverable by Gradle toolchain detection,
   * or downloadable. When it isn't, Gradle fails with a toolchain-resolution error rather than
   * silently falling back; `composePreviewDoctor` explains the mismatch and this knob is the fix.
   *
   * Override at the command line with `-PcomposePreview.renderJavaVersion=21` for a single run
   * without editing `build.gradle.kts`.
   */
  val renderJavaVersion: Property<Int> = objects.property(Int::class.java)

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
   * When `true` (default), a Wear OS module's device-less, wrap-content component previews are
   * retargeted from Studio's phone default onto the Wear canvas (227dp @ 2.0x) so a frame-less Wear
   * sticker renders at wear density/width instead of a phone-sized canvas. This is right for
   * design-catalog components (fill-width Cards that should size to the watch screen), but wrong
   * for Wear **widget/tile** previews (e.g. Glance `wear-tooling-preview` widgets) whose PNGs are
   * exported as fixed-size drawable assets: those must crop to their intrinsic layout bounds, not
   * carry the watch-face canvas whitespace.
   *
   * **Most Wear widget projects need no config:** a glance-wear widget preview — one whose
   * `@PreviewParameter` provider comes from `androidx.glance.wear.*` (the
   * `Squircle`/`RectangularAllWidgetPreviewParams` that feed `WearWidgetParams`) — is auto-detected
   * and always cropped to its intrinsic bounds regardless of this flag, since a widget sticker must
   * never occupy the watch-face canvas. This flag is the override for the broader case: set it to
   * `false` to crop **every** device-less preview in the module (e.g. non-glance widget param
   * types, or a module that renders only widgets). Detection is per-preview, so one module can
   * freely mix fill-width catalog components (pinned) with widgets (auto-cropped).
   *
   * Set to `false` on such a module to opt out of the retarget — device-less previews then stay
   * wrap-content and the renderer crops each PNG to the composable's measured bounds (#2670).
   * Previews that already pin their own `device` / `widthDp` / `heightDp` are unaffected either
   * way.
   *
   * Override at the command line with `-PcomposePreview.retargetWearPreviews=false` to flip for a
   * single run without editing `build.gradle.kts`.
   */
  val retargetWearPreviews: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)

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

  // `failOnErrors` / `failOnWarnings` / `annotateScreenshots` below are **no-ops**, kept only to
  // honour the binary-stability contract in docs/CONFIG_ONLY_PLUGIN.md § "The binary-stability
  // contract": this artifact can land on the buildscript classpath at two versions at once (the
  // one the consumer pinned, and the one the CLI-injected runtime plugin drags in), Gradle
  // conflict-resolves to a single copy, so deleting a documented DSL property breaks the *other*
  // version's build script with an unresolved reference — for a consumer who did nothing wrong,
  // and in a way that stops them rendering at all.
  //
  // They existed while a11y ran as a gate inside the render task. That model is gone: a11y is
  // daemon-only, a data producer rather than a build gate (docs/DATA_PRODUCTS.md), and nothing has
  // read these since. Deprecating rather than deleting also fixes the actual harm — the old KDoc
  // promised behaviour that never ran, so `failOnErrors = true` bought a silently green build.
  // A deprecation warning is visible; a silent no-op is not.
  //
  // Remove on the next deliberate binary break of `compose-preview-config`. If build-failing
  // checks return, wire them to the daemon's producer surface and name them for what they gate.

  @Deprecated(
    "No-op since a11y became a daemon-side data product rather than a build gate; it never " +
      "fails the build. Remove it from your composePreview { } block.",
    level = DeprecationLevel.WARNING,
  )
  val failOnErrors: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  @Deprecated(
    "No-op since a11y became a daemon-side data product rather than a build gate; it never " +
      "fails the build. Remove it from your composePreview { } block.",
    level = DeprecationLevel.WARNING,
  )
  val failOnWarnings: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  @Deprecated(
    "No-op. Annotated screenshots are produced by the daemon's a11y data product, not by this " +
      "flag. Remove it from your composePreview { } block.",
    level = DeprecationLevel.WARNING,
  )
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
