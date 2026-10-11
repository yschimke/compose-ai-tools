package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import ee.schimke.composeai.plugin.daemon.DaemonExtension
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

abstract class PreviewExtension @Inject constructor(private val objects: ObjectFactory) {
  val variant: Property<String> = objects.property(String::class.java).convention("debug")

  /**
   * Override for the Robolectric SDK level in the generated `robolectric.properties`. Unset
   * (default) uses the consumer's `android.compileSdk`, which keeps the resource APK parseable by
   * Robolectric's framework. Must be within
   * [GenerateRobolectricPropertiesTask.MIN_SUPPORTED_SDK]..[GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK].
   */
  val sdkVersion: Property<Int> = objects.property(Int::class.java)

  /**
   * The Android theme the preview host activity runs under, e.g. `"@style/Theme.Foo"` (also
   * `"com.example:style/Theme.Foo"` or bare `"Theme.Foo"`).
   *
   * Leave unset in an application module, which inherits `<application android:theme>`. Set it in a
   * library module whose previews host platform views: without an application theme, `?attr/…`
   * lookups fail at inflation and the preview gets no PNG.
   *
   * Per-run override: `-PcomposePreview.hostTheme=…` (or `-Dcomposeai.render.hostTheme=…`).
   */
  val hostTheme: Property<String> = objects.property(String::class.java)

  /**
   * The wall-clock instant renders are pinned to, so screens that paint the time are stable across
   * runs. Matters most for `kind=ACTIVITY` heroes and app tours, which have no `@Preview` argument
   * to inject a clock through.
   *
   * Unset pins `10:10`, matching the design catalogs' fixed time source. Accepts `"HH:mm"`,
   * `"HH:mm:ss"`, an ISO-8601 local date-time, epoch millis, or `"off"` for the host clock.
   * Interpreted in the render JVM's default zone, so the rendered string is what stays stable.
   *
   * **Android only:** implemented by shadowing Wear's `ResourcesKt.currentTimeMillis` under
   * Robolectric, so it doesn't reach `java.time.*.now()` / `Calendar` callers and isn't forwarded
   * to Desktop.
   *
   * Per-run override: `-PcomposePreview.fixedTime=09:41` (or `-Dcomposeai.render.fixedTime=…`).
   */
  val fixedTime: Property<String> = objects.property(String::class.java)

  /**
   * Renders with the Compose runtime's rewritten "link buffer" `SlotTable` composer
   * (`ComposeRuntimeFlags.isLinkBufferComposerEnabled`). `false` (default) leaves the runtime's own
   * default. Rendering a catalog with and without it makes the PNG corpus a regression suite for
   * the rewrite. Honoured by both Android and Desktop lanes.
   *
   * **Whole-run, not per-preview:** the runtime latches the flag at the first composition in a JVM.
   *
   * `true` means **required**: a runtime without the flag fails the render rather than silently
   * testing nothing. For a build spanning several Compose versions use
   * `-PcomposePreview.linkBufferComposer=auto`, which enables it only where available; there is
   * deliberately no `auto` in the DSL.
   *
   * Per-run override: `-PcomposePreview.linkBufferComposer=true|auto|false` (or
   * `-Dcomposeai.render.linkBufferComposer=…`).
   */
  val linkBufferComposer: Property<Boolean> = objects.property(Boolean::class.java)

  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * JDK major version the render subprocess forks into (e.g. `21`). Unset (default) picks the max
   * of the consumer toolchain, Gradle's JVM and the detected `jvmTarget` / `targetCompatibility`,
   * provisioning a JDK through Gradle toolchains when needed.
   *
   * Set it to pin a JDK or when the bytecode target can't be detected. Honoured verbatim, even if
   * lower than the module's target (classes may then fail with `UnsupportedClassVersionError`). The
   * JDK must be resolvable by Gradle toolchains; `composePreviewDoctor` explains mismatches.
   *
   * Per-run override: `-PcomposePreview.renderJavaVersion=21`.
   */
  val renderJavaVersion: Property<Int> = objects.property(Int::class.java)

  /**
   * Number of parallel JVM forks used to render previews.
   * - `0` (default): auto — [ShardTuning] picks a count from discovered preview cost and the
   *   runner's CPU/memory, sharding only when the predicted saving clears absolute and relative
   *   thresholds. Falls back to 1 if `previews.json` doesn't exist yet.
   * - `1`: no sharding.
   * - `≥2`: explicit shard count.
   *
   * Each shard is a generated `RobolectricRenderTest_ShardN` with a round-robin slice of the
   * manifest and pays its own ~3–4s sandbox cold start.
   */
  val shards: Property<Int> = objects.property(Int::class.java).convention(0)

  /**
   * When `true`, Robolectric instantiates the consumer's manifest `Application` before rendering.
   * Default `false`: a plain `android.app.Application` is used, so app init (DI, Firebase,
   * WorkManager, …) doesn't run — such init routinely fails in the sandbox and previews shouldn't
   * depend on it.
   */
  val useConsumerApplication: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * The same choice for `kind=ACTIVITY` / `kind=APP_TOUR` previews only. Default `true`, the
   * opposite of [useConsumerApplication]: an Activity often requires its real Application (e.g.
   * Hilt's `@HiltAndroidApp`, Koin, DI-backed `AppComponentFactory`).
   *
   * The two lanes are separate test classes with their own `robolectric.properties` because
   * Robolectric settles the Application per test class, so this costs composable previews nothing.
   * Set `false` when `Application.onCreate()` can't survive the sandbox. [useConsumerApplication]
   * `= true` forces both lanes.
   */
  val appTourUseConsumerApplication: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)

  /**
   * When `true`, `composePreviewDiscover` fails the build on zero `@Preview` functions and logs
   * diagnostics (class dirs, dependency jars, scan summary, observed annotation FQNs). Default
   * `false`. Intended for CI and for triaging "0 previews discovered".
   *
   * Per-run override: `-PcomposePreview.failOnEmpty=true`.
   */
  val failOnEmpty: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * When `true` (default), a Wear module's device-less, wrap-content previews are retargeted onto
   * the Wear canvas (227dp @ 2.0x) instead of Studio's phone default — right for fill-width catalog
   * components, wrong for widget/tile previews exported as fixed-size assets.
   *
   * Glance-wear widget previews (parameter providers from `androidx.glance.wear.*`) are
   * auto-detected and always cropped to intrinsic bounds. Set `false` to crop every device-less
   * preview in the module instead. Previews that pin `device` / `widthDp` / `heightDp` are
   * unaffected.
   *
   * Per-run override: `-PcomposePreview.retargetWearPreviews=false`.
   */
  val retargetWearPreviews: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)

  /**
   * Extra owners whose composables count as **library components** when a preview calls them,
   * beyond the built-in Material 3, Material, Wear Material, Remote Material 3 and Glimmer
   * packages. A library call gets a `components.json` record (and so can be joined by
   * `ui-builder.policy.json`) only if its owner is listed.
   *
   * Each entry is a package ending in `.` or one exact JVM owner class. Prefer the owner class: a
   * whole layout package also admits `Box`/`Row`-like scaffolding that then competes with the real
   * subject.
   *
   * ```kotlin
   * composePreview {
   *   componentLibraryPrefixes.add("androidx.compose.remote.creation.compose.layout.RemoteTextKt")
   * }
   * ```
   */
  val componentLibraryPrefixes: ListProperty<String> =
    objects.listProperty(String::class.java).convention(emptyList())

  /**
   * When `true` (default), the plugin auto-adds the dependencies it needs (`ui-test-manifest`,
   * `ui-test-junit4`, and conditionally `tiles-renderer`). When `false`, nothing is injected and
   * the consumer must declare them; `composePreviewDoctor` lists what's missing and
   * discovery/render fail fast with the coordinates. For builds with strict, explicit dependency
   * management.
   */
  val manageDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * When `true` (default), task registration is skipped on modules that don't declare a known
   * `@Preview`-tooling dependency, keeping convention-plugin-everywhere setups quiet.
   *
   * Set `false` for a module that gets preview tooling only through a `project(":shared")`
   * dependency (e.g. CMP's `:composeApp`): following project deps is banned under Isolated Projects
   * (see #1549).
   *
   * Per-run override: `-PcomposePreview.enforcePreviewToolingDependency=false`.
   */
  val enforcePreviewToolingDependency: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)

  /**
   * When `true`, `composePreviewRender` depends on [ValidatePreviewToolingPresentTask], which fails
   * fast if no known `@Preview` tooling is on the resolved runtime classpath. Only relevant to
   * modules that passed the config-time gate via `project(...)` deps rather than a direct tooling
   * dependency. Default `false`.
   *
   * Per-run override: `-PcomposePreview.failOnMissingPreviewTooling=true`.
   */
  val failOnMissingPreviewTooling: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * When `true`, AGP's `testDebugUnitTest` / `testReleaseUnitTest` depend on
   * `composePreviewRenderAll`, so pixel tests reading `build/compose-previews/renders/` see
   * complete output. Default `false`. Targets those tasks by name because `composePreviewRender` is
   * itself a `Test` task and matching it would create a cycle. No-op on CMP / Desktop modules.
   */
  val renderBeforeUnitTests: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * Forces the XR subspace render path on (`composePreviewRenderXr`, rendering `@XrSubspacePreview`
   * to `scene.json`).
   *
   * Usually unnecessary: the path auto-enables for modules that declare an `androidx.xr.compose`
   * dependency. Set it only when XR Compose arrives transitively. Not always-on because
   * `androidx.xr.compose` requires compileSdk 36 and the XR fakes are heavy.
   */
  val enableXrPreviews: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * Renders a `com.android.kotlin.multiplatform.library` module through **Robolectric** instead of
   * Compose Desktop.
   *
   * Off by default: Desktop is right for multiplatform `commonMain` UI. Turn it on for Android-only
   * UI (e.g. Wear Compose), which needs `android.jar`, a merged manifest and AAR resources.
   * Requires the consumer to opt into AGP's host tests, since `withHostTest { }` can only be called
   * once:
   * ```
   * kotlin {
   *   android {
   *     withHostTest { isIncludeAndroidResources = true }
   *   }
   * }
   * ```
   *
   * Without `withHostTest` the plugin falls back to Desktop with a warning; without
   * `isIncludeAndroidResources` library resources resolve to 0.
   *
   * Per-run override: `-PcomposePreview.kmpAndroidRobolectric=true` (an explicit DSL value wins).
   * No effect on other module types.
   */
  val kmpAndroidRobolectric: Property<Boolean> =
    objects.property(Boolean::class.java).convention(false)

  /**
   * Source roots of the modules named by `composePreviewSource`, so their previews are attributed
   * to their declaring file.
   *
   * `composePreviewSource` only gets the classes scanned. Mapping a `…Kt` facade back to its source
   * (and so applying its `@file:` annotations such as `@file:CatalogGroup`) needs the sources;
   * without them those defaults silently stop applying.
   * ```
   * dependencies { composePreviewSource(project(":catalog-shared")) }
   * composePreview { previewSourceRoots.from(file("../catalog-shared/src")) }
   * ```
   *
   * Paths are reported relative to the consuming module (e.g. `../catalog-shared/…`). A separate
   * declaration because reading another project's sources through the dependency graph is forbidden
   * under isolated projects.
   */
  val previewSourceRoots: ConfigurableFileCollection = objects.fileCollection()

  /** Generic selector for preview extensions that produce data alongside preview PNGs. */
  val previewExtensions: PreviewExtensionsExtension =
    objects.newInstance(PreviewExtensionsExtension::class.java)

  fun previewExtensions(action: Action<PreviewExtensionsExtension>) {
    action.execute(previewExtensions)
  }

  /**
   * Android XML resource previews (vector, animated-vector, adaptive-icon drawables, mipmaps,
   * manifest icon references). On by default; tasks no-op when `res/` has no matching XML. See
   * [ResourcePreviewsExtension].
   */
  val resourcePreviews: ResourcePreviewsExtension =
    objects.newInstance(ResourcePreviewsExtension::class.java)

  fun resourcePreviews(action: Action<ResourcePreviewsExtension>) {
    action.execute(resourcePreviews)
  }

  /** Control over the dependency graph the renderer resolves in; see [RenderGraphExtension]. */
  val renderGraph: RenderGraphExtension = objects.newInstance(RenderGraphExtension::class.java)

  fun renderGraph(action: Action<RenderGraphExtension>) {
    action.execute(renderGraph)
  }

  /** Persistent preview daemon configuration. Enabled by default for the VS Code extension. */
  val daemon: DaemonExtension = objects.newInstance(DaemonExtension::class.java)

  fun daemon(action: Action<DaemonExtension>) {
    action.execute(daemon)
  }
}

/**
 * `composePreview { renderGraph { … } }` — shapes the configurations the plugin resolves the
 * renderer in.
 *
 * The render configurations deliberately `extendsFrom` the consumer's test/runtime classpath so
 * renderer and consumer resolve as one graph; this block doesn't undo that. It excludes individual
 * modules that can't survive the merge, e.g. a `java-platform` of `strictly`/`reject` constraints
 * that would make every newer renderer dependency a conflict:
 * ```kotlin
 * composePreview {
 *   renderGraph { exclude(group = "com.example", module = "version-constraints") }
 * }
 * ```
 *
 * For builds the consumer can't edit (e.g. CLI-injected), the additive Gradle property equivalent
 * is:
 * ```
 * -PcomposePreview.renderGraphExcludes=com.example:version-constraints
 * ```
 *
 * Exclusions apply only to plugin-owned configurations, never the consumer's own. The Kotlin
 * build-tools configurations aren't covered since they never inherit the consumer graph.
 */
abstract class RenderGraphExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Modules to keep off the render graph. Prefer the [exclude] helpers; this property is the lazy
   * plumbing they and the `composePreview.renderGraphExcludes` Gradle property feed.
   */
  val excludes: ListProperty<RenderGraphExclusion> =
    objects.listProperty(RenderGraphExclusion::class.java)

  /**
   * Excludes a module from the render graph, in the shape of Gradle's own
   * `Configuration.exclude(group:, module:)`: pass either or both. `group` alone drops every module
   * in that group; `module` alone drops that module whatever its group.
   */
  @JvmOverloads
  fun exclude(group: String? = null, module: String? = null) {
    excludes.add(RenderGraphExclusion.of(group, module))
  }

  /**
   * Groovy-friendly overload so `renderGraph { exclude group: 'com.example', module: 'x' }` reads
   * the same in a `build.gradle` as the Kotlin named-argument form does in a `build.gradle.kts`.
   */
  fun exclude(notation: Map<String, String>) {
    val unknown = notation.keys - setOf("group", "module")
    require(unknown.isEmpty()) {
      "composePreview.renderGraph.exclude: unknown key(s) ${unknown.sorted()}; " +
        "supported keys are 'group' and 'module'."
    }
    exclude(notation["group"], notation["module"])
  }
}

/**
 * One `group`/`module` exclusion. At least one is non-null — [of] rejects the empty exclusion,
 * which Gradle would treat as "drop everything". `Serializable` for the configuration cache.
 */
data class RenderGraphExclusion(val group: String?, val module: String?) : java.io.Serializable {
  /** The `group:module` spelling used by the Gradle property and in error messages. */
  override fun toString(): String = "${group.orEmpty()}:${module.orEmpty()}"

  companion object {
    private const val serialVersionUID: Long = 1L

    fun of(group: String?, module: String?): RenderGraphExclusion {
      val g = group?.trim()?.takeIf { it.isNotEmpty() }
      val m = module?.trim()?.takeIf { it.isNotEmpty() }
      require(g != null || m != null) {
        "composePreview.renderGraph.exclude requires a group, a module, or both — " +
          "an exclusion with neither would drop every dependency from the render graph."
      }
      return RenderGraphExclusion(g, m)
    }

    /**
     * Parses `composePreview.renderGraphExcludes`: comma-separated `group:module`, either half may
     * be empty. Blank entries are ignored; a malformed entry fails loudly, since a missed exclusion
     * surfaces as an unresolvable configuration that never names this property.
     */
    fun parse(spec: String): List<RenderGraphExclusion> =
      spec
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { entry ->
          val parts = entry.split(':')
          require(parts.size == 2) {
            "composePreview.renderGraphExcludes: '$entry' is not a 'group:module' coordinate. " +
              "Use 'group:module', 'group:' for a whole group, or ':module' for a module in any " +
              "group, separating entries with commas."
          }
          of(parts[0], parts[1])
        }
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

  // The a11y DSL is gone: a11y data is produced only by the daemon, opted into per invocation (VS
  // Code chip toggle or `compose-preview a11y`).

  init {
    // Pre-register `composeAiTrace` so plugin task wiring, which runs before the build script's
    // `previewExtensions { }` block, never snapshots `null`; `extension()` then `maybeCreate`s this
    // same instance.
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
   * Read-only view of [enableAllChecks] for the runtime plugin, without exposing a settable
   * `Property` in the DSL.
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

  // No-ops kept for binary stability (docs/CONFIG_ONLY_PLUGIN.md): this artifact can be on the
  // buildscript classpath at two versions, so deleting a DSL property breaks the other version's
  // build script. a11y is daemon-only and nothing reads these; deprecation makes the no-op visible.
  // Remove on the next deliberate binary break of `compose-preview-config`.

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
   * Default `true`; tasks no-op on modules without matching resources. Set `false` to skip
   * registration outright.
   */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Density buckets for implicit captures of resources without a density qualifier (explicitly
   * qualified variants use their own file). Default `["xhdpi"]`.
   */
  val densities: ListProperty<String> =
    objects.listProperty(String::class.java).convention(listOf("xhdpi"))

  /**
   * Adaptive-icon masks to render, applied as a canvas clip. Default: all. One capture per `(shape
   * × style)`, plus one `LEGACY` capture per qualifier when [styles] contains
   * [AdaptiveStyle.LEGACY].
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
   * Adaptive-icon styles: [AdaptiveStyle.FULL_COLOR] (App Search), [AdaptiveStyle.THEMED_LIGHT] /
   * [AdaptiveStyle.THEMED_DARK] (themed monochrome icons), [AdaptiveStyle.LEGACY] (pre-O). Default:
   * all. Drop the themed styles when icons have no `<monochrome>` layer.
   */
  val styles: ListProperty<AdaptiveStyle> =
    objects.listProperty(AdaptiveStyle::class.java).convention(AdaptiveStyle.entries.toList())

  /**
   * 9-patch stretch variants: [NinePatchStretch.INTRINSIC] at natural size,
   * [NinePatchStretch.HORIZONTAL] / [NinePatchStretch.VERTICAL] at 2× one axis,
   * [NinePatchStretch.BOTH] at 2× both. Default: all.
   */
  val stretches: ListProperty<NinePatchStretch> =
    objects.listProperty(NinePatchStretch::class.java).convention(NinePatchStretch.entries.toList())

  /**
   * When `true` (default), each [ResourceType.ANIMATED_VECTOR] also gets a `_filmstrip.png` of
   * keyframes sampled at [filmstripFractions], so stills can be diffed in review.
   */
  val filmstrip: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Keyframe fractions (in `[0, 1]` of the animation duration) for the filmstrip, one cell each.
   * Default `[0.0, 0.25, 0.5, 0.75, 1.0]`.
   */
  val filmstripFractions: ListProperty<Float> =
    objects.listProperty(Float::class.java).convention(DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS)
}
