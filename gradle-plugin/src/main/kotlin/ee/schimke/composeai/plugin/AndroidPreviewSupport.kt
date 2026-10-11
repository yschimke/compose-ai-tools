package ee.schimke.composeai.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import ee.schimke.composeai.daemonlaunch.*
import ee.schimke.composeai.discovery.*
import java.util.Collections
import java.util.IdentityHashMap
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * All AGP-touching code, kept out of [ComposePreviewPlugin] so that class stays loadable without
 * AGP: Gradle's decoration resolves referenced classes eagerly, so AGP is only loaded once these
 * methods run inside `pluginManager.withPlugin("com.android.*")`.
 */
internal object AndroidPreviewSupport {
  /**
   * Output subdirectory for Android `kind=LOTTIE` renders (discovery's `lottieRenderSubdir` and the
   * `composePreviewRenderLottie` output), disjoint from Robolectric's `renders/` because
   * overlapping outputs disable the build cache.
   */
  internal const val LOTTIE_RENDER_SUBDIR: String = "lottie-renders"

  /**
   * Output subdirectory for Android `kind=SVG` renders; same rationale as [LOTTIE_RENDER_SUBDIR].
   */
  internal const val SVG_RENDER_SUBDIR: String = "svg-renders"

  /**
   * Module-root directory of committed app-tour scripts; each `*.json` becomes a `kind=APP_TOUR`
   * preview (see `AppTourDiscovery`).
   */
  internal const val TOUR_SPECS_DIR: String = "compose-previews/tours"

  /**
   * Floor version for injected `androidx.compose.*` coordinates with no other version source
   * (`ui-test-manifest`, `ui-test-junit4`), matching the Compose `:renderer-android` compiles
   * against. Higher consumer versions win via conflict resolution. Bump with `compose-bom-compat`
   * in `gradle/libs.versions.toml`.
   */
  internal const val RENDERER_COMPOSE_FLOOR_VERSION: String = "1.9.5"

  /**
   * Path segments identifying `androidx.xr.scenecore`'s on-device spatial backend artifacts
   * (module-cache dir, versioned file, or extracted-AAR dir). Anchored so directories merely
   * sharing the prefix don't match. See the `composePreviewRenderXr` classpath filter.
   */
  internal val SCENECORE_SPATIAL_BACKEND_SEGMENT: Regex =
    Regex("scenecore-spatial-(core|rendering)([-.][0-9].*)?")

  /**
   * Platform token of the auto-provisioned `xr-composite` cache, matching the release asset matrix
   * and the CLI's `XrCompositeProvision.platformToken`. `null` when no asset is published (e.g.
   * linux-arm64). Parameters rather than `System.getProperty` for testability.
   */
  internal fun xrCompositePlatformToken(osName: String, osArch: String): String? {
    val os = osName.lowercase()
    val arch = osArch.lowercase()
    return when {
      os.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-x86_64"
      (os.contains("mac") || os.contains("darwin")) && (arch == "aarch64" || arch == "arm64") ->
        "macos-arm64"
      os.contains("windows") && (arch == "amd64" || arch == "x86_64") -> "windows-x86_64"
      else -> null
    }
  }

  /**
   * Provider for the shared cache binary
   * `${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite/<version>/<platform>/xr-composite[.exe]`.
   * The same path convention as the CLI's `XrCompositeProvision.cacheBinary`, which is how the
   * CLI's download and the plugin's read meet. [version] is the pinned [XrFakeVersions.composite]
   * on both sides. Built from injected providers for configuration-cache safety.
   *
   * Absent when the platform has no asset or no cache root resolves, so the task falls through to
   * its skip.
   */
  internal fun xrCompositeCacheBinaryPath(
    version: String,
    xdgCacheHome: Provider<String>,
    userHome: Provider<String>,
    osName: Provider<String>,
    osArch: Provider<String>,
  ): Provider<String> {
    val platform = osName.zip(osArch) { n, a -> xrCompositePlatformToken(n, a) ?: "" }
    // `orElse("")` keeps the chain resolvable so "neither available" can be detected.
    val cacheRoot =
      xdgCacheHome.orElse("").zip(userHome.orElse("")) { xdg, home ->
        when {
          xdg.isNotBlank() -> xdg
          home.isNotBlank() -> "$home/.cache"
          else -> ""
        }
      }
    return cacheRoot.zip(platform) { root, plat ->
      if (root.isBlank() || plat.isBlank()) {
        null
      } else {
        val binName = if (plat.startsWith("windows")) "xr-composite.exe" else "xr-composite"
        java.io
          .File(root)
          .resolve("composeai/xr-composite")
          .resolve(version)
          .resolve(plat)
          .resolve(binName)
          .path
      }
    }
  }

  /**
   * `androidx.wear.tiles` modules that signal Tile previews. When declared, [configure] injects
   * `tiles-renderer` so AGP generates the protolayout-renderer R classes `TilePreviewRenderer`
   * needs.
   */
  private val tilesSignalNames =
    setOf("tiles", "tiles-renderer", "tiles-tooling-preview", "tiles-tooling")

  /**
   * `(group, name)` of artifacts that mark a "preview module"; tasks are registered only when one
   * is declared, so convention-plugin-everywhere setups stay quiet. Group+name only: no resolution,
   * IP-safe.
   */
  private val previewArtifactSignals =
    setOf(
      "androidx.compose.ui" to "ui-tooling-preview",
      "androidx.compose.ui" to "ui-tooling-preview-android",
      "androidx.wear.tiles" to "tiles-tooling-preview",
      // CMP-only; AGP consumers never declare it but the helper is shared.
      "org.jetbrains.compose.components" to "components-ui-tooling-preview",
      // CMP's relocation of `androidx.compose.ui:ui-tooling-preview` (same `@Preview` FQN).
      "org.jetbrains.compose.ui" to "ui-tooling-preview",
    )

  /**
   * Compose Multiplatform families whose Android variants are pure redirectors (no files, one
   * `androidx.compose.*` dependency). An explicit list, not an `org.jetbrains.compose.` prefix,
   * because `org.jetbrains.compose.components` does ship Android classes.
   */
  private val COMPOSE_MULTIPLATFORM_ANDROID_ALIAS_GROUPS =
    listOf(
      "org.jetbrains.compose.animation",
      "org.jetbrains.compose.foundation",
      "org.jetbrains.compose.material",
      "org.jetbrains.compose.material3",
      "org.jetbrains.compose.runtime",
      "org.jetbrains.compose.ui",
    )

  /**
   * Adds one of the plugin's own dependencies to an Android render configuration with Rule 3's
   * exclusions (see [applyRenderGraphResolutionRules]) on that dependency alone; config-wide
   * excludes would strip a CMP consumer's own redirectors. Only when [consumerBringsOwnCompose],
   * else Rule 3 would remove the only Compose there is (#3484).
   */
  internal fun addRenderGraphDependency(
    project: Project,
    configurationName: String,
    notation: Any,
  ): Dependency {
    val dependency = if (notation is Dependency) notation else project.dependencies.create(notation)
    val configuration = project.configurations.findByName(configurationName)
    if (dependency is ModuleDependency && consumerBringsOwnCompose(project, configuration)) {
      COMPOSE_MULTIPLATFORM_ANDROID_ALIAS_GROUPS.forEach { group ->
        dependency.exclude(mapOf("group" to group))
      }
    }
    project.dependencies.add(configurationName, dependency)
    return dependency
  }

  /**
   * Whether the consumer has its own Compose on [configuration]'s graph (Rule 3's precondition),
   * without resolving: the Compose compiler plugin is applied here, or an `androidx.compose.*` /
   * `org.jetbrains.compose.*` dependency is declared in the hierarchy.
   *
   * Plugin-injected dependencies ([addPluginDependency]) are skipped by identity, otherwise our
   * floor pins made Compose-less consumers look Compose-capable.
   */
  internal fun consumerBringsOwnCompose(project: Project, configuration: Configuration?): Boolean {
    if (COMPOSE_COMPILER_PLUGIN_IDS.any(project.plugins::hasPlugin)) return true
    val ours = pluginInjectedDependencies(project)
    // Walked by hand: `Configuration.getHierarchy()` is slated for removal in Gradle 10.
    val seen = mutableSetOf<Configuration>()
    val pending = ArrayDeque(listOfNotNull(configuration))
    while (pending.isNotEmpty()) {
      val candidate = pending.removeFirst()
      if (!seen.add(candidate)) continue
      val declaresCompose =
        candidate.dependencies.any { dependency ->
          if (dependency in ours) return@any false
          val group = dependency.group.orEmpty()
          // Bare `androidx.compose` too: the BOM's group, often the only one a consumer names.
          COMPOSE_CONSUMER_GROUP_PREFIXES.any { prefix ->
            group == prefix || group.startsWith("$prefix.")
          }
        }
      if (declaresCompose) return true
      pending.addAll(candidate.extendsFrom)
    }
    return false
  }

  /**
   * Adds a plugin-contributed dependency and records it for [consumerBringsOwnCompose]. Required
   * for every injected Compose coordinate.
   */
  internal fun addPluginDependency(
    project: Project,
    configurationName: String,
    notation: Any,
  ): Dependency {
    val dependency = project.dependencies.add(configurationName, notation)
    if (dependency != null) pluginInjectedDependencies(project).add(dependency)
    return dependency ?: project.dependencies.create(notation)
  }

  /**
   * Identity set of [addPluginDependency] contributions, in the project's extra properties so it is
   * project-scoped and never aliases an equal consumer dependency.
   */
  private fun pluginInjectedDependencies(project: Project): MutableSet<Dependency> {
    val extra = project.extensions.extraProperties
    if (extra.has(PLUGIN_INJECTED_DEPENDENCIES_KEY)) {
      @Suppress("UNCHECKED_CAST")
      return extra.get(PLUGIN_INJECTED_DEPENDENCIES_KEY) as MutableSet<Dependency>
    }
    val set = Collections.newSetFromMap(IdentityHashMap<Dependency, Boolean>())
    extra.set(PLUGIN_INJECTED_DEPENDENCIES_KEY, set)
    return set
  }

  private const val PLUGIN_INJECTED_DEPENDENCIES_KEY = "composeai.pluginInjectedDependencies"

  /**
   * The `androidx.compose` version our CMP artifacts resolve to; only relevant to Compose-less
   * consumers. Bump with `compose-multiplatform`, reading the mapping off CMP's `ui` POM.
   */
  internal const val RENDERER_COMPOSE_CMP_RUNTIME_VERSION: String = "1.11.2"

  /**
   * Lowest compose-ui version the renderer links against; [applyRenderGraphResolutionRules] raises
   * consumers below it (else `NoSuchMethodError` on `getApplyOnDeactivatedNodeAssertion`, #3590).
   * Not [RENDERER_COMPOSE_CMP_RUNTIME_VERSION]: raising further drags in transitives with their own
   * constraints (#3603, #3602).
   */
  internal const val RENDERER_COMPOSE_LINK_FLOOR_VERSION: String = "1.10.0"

  /**
   * `androidx.compose.*` groups on the compose-ui version line; not material / material3, which
   * version independently.
   */
  private val COMPOSE_UI_LINE_GROUPS =
    setOf(
      "androidx.compose.animation",
      "androidx.compose.foundation",
      "androidx.compose.runtime",
      "androidx.compose.ui",
    )

  /**
   * [RENDERER_COMPOSE_LINK_FLOOR_VERSION] if [group] is on the compose-ui line and [version] is
   * provably below it, else `null`. Dynamic or unparseable versions are left alone.
   */
  internal fun composeLineFloorUpgrade(group: String, version: String?): String? {
    if (group !in COMPOSE_UI_LINE_GROUPS) return null
    val current = version?.takeIf { it.isNotBlank() } ?: return null
    return if (isBelowVersion(current, RENDERER_COMPOSE_LINK_FLOOR_VERSION)) {
      RENDERER_COMPOSE_LINK_FLOOR_VERSION
    } else {
      null
    }
  }

  /**
   * Dotted numeric comparison, with a pre-release below its stable. False for non-numeric input.
   */
  private fun isBelowVersion(candidate: String, floor: String): Boolean {
    val candidateBase = candidate.substringBefore('-')
    val floorBase = floor.substringBefore('-')
    val candidateParts = candidateBase.split('.').map { it.toIntOrNull() ?: return false }
    val floorParts = floorBase.split('.').map { it.toIntOrNull() ?: return false }
    for (index in 0 until maxOf(candidateParts.size, floorParts.size)) {
      val left = candidateParts.getOrElse(index) { 0 }
      val right = floorParts.getOrElse(index) { 0 }
      if (left != right) return left < right
    }
    // Numerically equal: a pre-release of the floor is still below it.
    return candidate.contains('-') && !floor.contains('-')
  }

  /**
   * Version for the main-variant `ui` / `foundation` pins, which decide the R class in the merged
   * unit-test resource APK and so must match the Compose that runs: the CMP runtime for a
   * Compose-less consumer, else the link floor. Pinning only the render graph would trade #3590 for
   * #3484's `R$id` `NoSuchFieldError`.
   */
  internal fun mainVariantComposeVersion(
    project: Project,
    variantName: String,
    unitTestClasspathName: String? = "${variantName}UnitTestRuntimeClasspath",
  ): String {
    val unitTestClasspath = unitTestClasspathName?.let { project.configurations.findByName(it) }
    return if (consumerBringsOwnCompose(project, unitTestClasspath)) {
      RENDERER_COMPOSE_LINK_FLOOR_VERSION
    } else {
      RENDERER_COMPOSE_CMP_RUNTIME_VERSION
    }
  }

  /**
   * Compose compiler plugin ids — the Kotlin 2.x plugin every consumer with `@Composable` code
   * applies, and the legacy AndroidX id for older consumers. Used by [consumerBringsOwnCompose].
   */
  private val COMPOSE_COMPILER_PLUGIN_IDS =
    listOf("org.jetbrains.kotlin.plugin.compose", "androidx.compose.compiler")

  /**
   * Group roots meaning "the consumer has its own Compose", matched exactly or as a `<prefix>.`
   * parent. Not `androidx.wear.compose`, which is additive on top of `androidx.compose`.
   */
  private val COMPOSE_CONSUMER_GROUP_PREFIXES = listOf("androidx.compose", "org.jetbrains.compose")

  internal fun kmpAndroidSiblingName(group: String, name: String): String? {
    if (!group.startsWith("androidx.") && !group.startsWith("org.jetbrains.compose.")) {
      return null
    }
    val replacementSuffix =
      when {
        name.endsWith("-desktop") -> "-desktop"
        name.endsWith("-jvmstubs") -> "-jvmstubs"
        else -> return null
      }
    return name.removeSuffix(replacementSuffix) + "-android"
  }

  /**
   * Resolution rules shared by `composePreviewAndroidRenderer<Variant>` and
   * `composePreviewAndroidDaemon<Variant>`. `extendsFrom` inherits dependencies but not
   * `resolutionStrategy`, so without this the render and daemon JVMs would load different versions.
   *
   * Rule 1 — KMP-Android sibling substitution. Force `-desktop` / `-jvmstubs` siblings of AndroidX
   * / CMP modules to `-android`. Kotlin's `platform.type` attribute only disambiguates when the
   * Kotlin plugin is applied, so AGP-only consumers otherwise get desktop variants (e.g. the KMP
   * `ViewModelProvider`, which lacks the constructor `lifecycle-viewmodel-savedstate-android` calls
   * → `NoSuchMethodError`). By coordinate rather than attribute, since forcing `androidJvm` breaks
   * pure-JVM artifacts. Reuses `requested.version`, and is scoped to families that publish
   * `-android` siblings.
   *
   * Rule 2 — Hamcrest. Espresso needs Hamcrest 1.3's 2-arg `AllOf.allOf`, removed in 2.x. The
   * merged 2.x jar and the split 1.3 jars are different coordinates, so Gradle can't dedup them;
   * substitute 2.x back to `hamcrest-core:1.3`.
   *
   * Rule 3 — keep our own Compose off the consumer's render graph. `renderer-android` declares
   * Compose `compileOnly` so the consumer's versions win, but our `data-*` modules export
   * `org.jetbrains.compose.ui:ui`, whose Android variant pins a newer `androidx.compose.ui:ui`.
   * That silently upgraded the consumer's Compose against resources built from its own graph:
   * ```
   * NoSuchFieldError: Class androidx.compose.ui.R$id does not have member field
   *   'int androidx_compose_ui_view_compose_view_context'
   * ```
   *
   * Those CMP Android variants carry no files, so excluding them only removes version pressure. The
   * exclusion must be attached to the dependencies we add ([addRenderGraphDependency]), never
   * configuration-wide: the render configuration extends the consumer's classpath, and a CMP
   * consumer reaches `androidx.compose.material3` only through the redirector (#3483). The
   * consumer's Compose then wins whether older or newer. Android render graph only; desktop uses
   * `alignDesktopToolWithConsumerGraph`.
   */
  /**
   * What the render graph resolves a module to: a substitute [name] (the KMP-Android sibling)
   * and/or a [version], plus whether it came from the compose-line floor. `null` from
   * [renderGraphTarget] means leave it alone.
   */
  internal class RenderGraphTarget(
    val name: String?,
    val version: String?,
    val flooredComposeLine: Boolean,
  )

  /**
   * The decision behind Rule 1 and the compose-line floor, pure for testing. Both halves are
   * decided together because every `eachDependency` action sees the original `requested`:
   * substituting with `requested.version` would undo an earlier floor (e.g. `ui-jvmstubs:1.9.5` →
   * `ui-android:1.9.5`). [floorComposeLine] is `false` under `manageDependencies = false`, since
   * the floor is only safe while the main-variant pins move with it.
   */
  /**
   * The error for a consumer below [RENDERER_COMPOSE_LINK_FLOOR_VERSION] on a graph we may not
   * raise (`manageDependencies = false`): flooring alone causes #3484, leaving it causes #3590.
   * [ValidateComposeFloorTask] checks the resolved graph at execution time, after platforms and
   * constraints have applied.
   */
  internal fun composeFloorOptOutMessage(module: String, resolved: String): String =
    """
    compose-ai-tools cannot render with composePreview.manageDependencies = false.

      Renderer requires  androidx.compose.ui >= $RENDERER_COMPOSE_LINK_FLOOR_VERSION
      $module resolves to $resolved

    Fix one of:
      * raise the module's Compose (e.g. a newer androidx.compose:compose-bom) to
        $RENDERER_COMPOSE_LINK_FLOOR_VERSION or above;
      * set composePreview.manageDependencies = true, which lets the plugin raise the render
        classpath and the main-variant pins together.

    Rendering against $resolved fails every preview before user code runs:
      NoSuchMethodError androidx.compose.ui.node.ComposeUiNode${'$'}Companion
        .getApplyOnDeactivatedNodeAssertion()
    """
      .trimIndent()

  internal fun renderGraphTarget(
    group: String,
    name: String,
    version: String?,
    floorComposeLine: Boolean,
  ): RenderGraphTarget? {
    val floored = if (floorComposeLine) composeLineFloorUpgrade(group, version) else null
    val sibling = kmpAndroidSiblingName(group, name)
    return when {
      sibling != null -> RenderGraphTarget(sibling, floored ?: version, floored != null)
      floored != null -> RenderGraphTarget(null, floored, true)
      else -> null
    }
  }

  internal fun applyRenderGraphResolutionRules(
    configuration: Configuration,
    floorComposeLine: Boolean = true,
  ) {
    // One rule for substitution and floor; see [renderGraphTarget].
    configuration.resolutionStrategy.eachDependency {
      val req = requested
      val decision = renderGraphTarget(req.group, req.name, req.version, floorComposeLine)
      when {
        decision == null -> Unit
        decision.name != null -> {
          useTarget(
            mapOf("group" to req.group, "name" to decision.name, "version" to decision.version)
          )
          because(
            "Gradle resolved a desktop/JVM-stub KMP sibling on a config without the " +
              "Kotlin-plugin platform-type compat rule. Force the Android sibling so the " +
              "renderer's bytecode links against the AGP-flavoured class shapes (e.g. the " +
              "legacy `ViewModelProvider(ViewModelStoreOwner, Factory)` constructor that " +
              "lifecycle-viewmodel-savedstate-android still calls but the desktop variant " +
              "removed in the KMP rewrite)." +
              if (decision.flooredComposeLine) {
                " Carries the compose-line floor into the substitution, which would otherwise " +
                  "drop it (issue #3590)."
              } else {
                ""
              }
          )
        }
        decision.version != null -> {
          useVersion(decision.version)
          because(
            "Rule 3 hands the render classpath to the consumer's Compose, but the renderer's own " +
              "bytecode still has to link against it. Below " +
              "$RENDERER_COMPOSE_LINK_FLOOR_VERSION the compose-ui line lacks entry points the " +
              "renderer calls and every preview dies before user code runs: " +
              "NoSuchMethodError androidx.compose.ui.node.ComposeUiNode\$Companion" +
              ".getApplyOnDeactivatedNodeAssertion(). Raise to the floor; consumers already at or " +
              "above it keep their own version (issue #3590)."
          )
        }
      }
    }
    configuration.resolutionStrategy.eachDependency {
      if (requested.group == "org.hamcrest" && requested.name == "hamcrest") {
        useTarget("org.hamcrest:hamcrest-core:1.3")
        because(
          "Espresso bytecode needs Hamcrest 1.3's AllOf.allOf(Matcher,Matcher); 2.x removed it"
        )
      }
    }
  }

  /**
   * Wires [GenerateRobolectricPropertiesTask] inputs. The task resolves `sdk=N` from:
   * 1. `composePreview.sdkVersion` — strict; out-of-range fails.
   * 2. `android.compileSdk` — from `finalizeDsl`, clamped to
   *    [GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK] with a warning.
   * 3. [GenerateRobolectricPropertiesTask.DEFAULT_SDK] — fallback, mostly for unit tests.
   *
   * Decided in the task action ([GenerateRobolectricPropertiesTask.resolveSdk]) so the clamp
   * warning fires at execution time.
   */
  internal fun wireSdkInputs(
    task: GenerateRobolectricPropertiesTask,
    extensionOverride: Property<Int>,
    consumerCompileSdk: Provider<Int>,
  ) {
    task.sdkOverride.set(extensionOverride)
    task.consumerCompileSdk.set(consumerCompileSdk)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    // `buildJavaMajor` is wired separately in registerAndroidTasks.
  }

  /**
   * [kmpAndroidFallback] runs once when a KMP-Android module doesn't take the Robolectric lane (not
   * opted in, or no host-test compilation), re-routing it to Desktop. Null for classic Android
   * modules.
   */
  fun configure(
    project: Project,
    extension: PreviewExtension,
    kmpAndroidFallback: (() -> Unit)? = null,
  ) {
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

    // Robolectric must run at the API level `apk-for-local-test.ap_` was compiled against, or
    // `PackageParser` rejects the manifest (#1248).
    val consumerCompileSdk = project.objects.property(Int::class.java)
    // For the doctor's library-minSdk check (`CompatRules.checkLibraryMinSdk`); unset when omitted.
    val consumerMinSdk = project.objects.property(Int::class.java)

    androidComponents.finalizeDsl { android: Any ->
      // `com.android.application` / `library` give a [CommonExtension]; KMP-Android gives a
      // [KotlinMultiplatformAndroidLibraryExtension], which has `compileSdk` / `minSdk` directly
      // and `withHostTest { }` instead of `testOptions.unitTests`.
      when (android) {
        is CommonExtension -> {
          if (extension.enabled.get()) {
            android.testOptions.unitTests.isIncludeAndroidResources = true
          }
          // Only propagate when set so the task's `.orElse(...)` chain can fall through.
          val resolvedCompileSdk: Int? = android.compileSdk
          if (resolvedCompileSdk != null) {
            consumerCompileSdk.set(resolvedCompileSdk)
          }
          val resolvedMinSdk: Int? = android.defaultConfig.minSdk
          if (resolvedMinSdk != null) {
            consumerMinSdk.set(resolvedMinSdk)
          }
        }
        is KotlinMultiplatformAndroidLibraryExtension -> {
          // KMP-Android leaves android resources off, so no R classes are generated and Robolectric
          // fails on `androidx/lifecycle/runtime/R$id`. Gated on the opt-in so modules keeping the
          // Desktop lane see no behaviour change.
          if (extension.enabled.get() && extension.kmpAndroidRobolectric.get()) {
            android.androidResources.enable = true
          }
          // No `isIncludeAndroidResources` flip here: `withHostTest { }` can only be called once,
          // so the consumer owns it; [routeKmpAndroid] reports when it matters.
          val resolvedCompileSdk: Int? = android.compileSdk
          if (resolvedCompileSdk != null) {
            consumerCompileSdk.set(resolvedCompileSdk)
          }
          val resolvedMinSdk: Int? = android.minSdk
          if (resolvedMinSdk != null) {
            consumerMinSdk.set(resolvedMinSdk)
          }
        }
      }
    }

    // Must run at apply time: by task realization, Gradle's MutationGuard rejects `afterEvaluate`.
    // Idempotent via `maybeCreate`.
    ComposePreviewTasks.setupBtaConfigurationsFor(project, extension)

    // Register render tasks once, for the selected variant, inside onVariants (reading
    // `bootClasspath` earlier crashes AGP). KMP-Android publishes one variant named after its main
    // source set (`androidMain`), so it's taken as-is unless named exactly.
    val kmpAndroid = isKmpAndroidModule(project)
    var registered = false
    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
      if (registered) return@onVariants
      if (!extension.enabled.get()) return@onVariants
      val target = extension.variant.get()
      if (!kmpAndroid && !variantMatchesTarget(variant.name, target)) return@onVariants
      if (kmpAndroid && !kmpAndroidWantsRobolectric(project, extension, variant)) {
        registered = true
        kmpAndroidFallback?.invoke()
        return@onVariants
      }
      val enforceToolingDep = extension.enforcePreviewToolingDependency.get()
      if (enforceToolingDep && !hasPreviewDependency(project, variant.name)) {
        project.logger.info(
          "compose-preview: no known @Preview dependency declared in module " +
            "'${project.path}' and no `project(\":...\")` deps that could carry it transitively; " +
            "skipping task registration. Add one of " +
            "${previewArtifactSignals.joinToString { "${it.first}:${it.second}" }} " +
            "(or remove the plugin from this module) to opt in, or set " +
            "`composePreview { enforcePreviewToolingDependency = false }` to bypass this gate."
        )
        return@onVariants
      }
      registered = true
      // Store the resolved name so downstream readers report the actually-selected variant.
      if (variant.name != target) extension.variant.set(variant.name)
      val naming =
        if (kmpAndroid) {
          kmpAndroidNaming(project, variant)
        } else {
          AndroidVariantNaming.classic(variant.name)
        }
      registerAndroidTasks(
        project,
        extension,
        variant,
        androidComponents.sdkComponents.bootClasspath,
        consumerCompileSdk,
        consumerMinSdk,
        naming,
      )
      registerAndroidResourcePreviewTasks(project, extension, variant)
    }
  }

  /**
   * Matches an AGP variant name against the requested target:
   * 1. **Exact match.** A flavored target (`demoDebug`) matches only itself, so `paidDebug` doesn't
   *    match `minApi23PaidDebug`.
   * 2. **Build-type suffix.** A bare build type (no uppercase, e.g. `debug`) also matches
   *    `demoDebug` etc., so the default works on flavored apps (#1546).
   *
   * One-directional: `demoDebug` never matches a flavorless `debug`.
   */
  /**
   * Whether a KMP-Android module takes the Robolectric lane: the consumer must opt in
   * ([PreviewExtension.kmpAndroidRobolectric]) and AGP must have a host-test compilation. Opting in
   * without one warns and falls back to Desktop.
   */
  private fun kmpAndroidWantsRobolectric(
    project: Project,
    extension: PreviewExtension,
    variant: Variant,
  ): Boolean =
    kmpAndroidLaneDecision(
      optedIn = extension.kmpAndroidRobolectric.get(),
      hasHostTest = (variant as? HasUnitTest)?.unitTest != null,
    ) {
      project.logger.warn(kmpAndroidMissingHostTestMessage(project.path))
    }

  /**
   * The decision, separate from `Variant` so it can be tested — notably that non-opted-in modules
   * keep Desktop. [onMissingHostTest] fires only for the build-script mistake.
   */
  internal fun kmpAndroidLaneDecision(
    optedIn: Boolean,
    hasHostTest: Boolean,
    onMissingHostTest: () -> Unit = {},
  ): Boolean {
    if (!optedIn) return false
    if (hasHostTest) return true
    onMissingHostTest()
    return false
  }

  internal fun kmpAndroidMissingHostTestMessage(projectPath: String): String =
    "compose-preview: `composePreview { kmpAndroidRobolectric = true }` is set on " +
      "'$projectPath', but its `com.android.kotlin.multiplatform.library` module declares no " +
      "host test, so there is no Android test classpath to render on. Add " +
      "`kotlin { android { withHostTest { isIncludeAndroidResources = true } } }` to render " +
      "through Robolectric. Falling back to the Compose Multiplatform Desktop renderer."

  /**
   * True on a `com.android.kotlin.multiplatform.library` module, whose names follow the KMP target
   * and host-test compilation (see [kmpAndroidNaming]).
   */
  internal fun isKmpAndroidModule(project: Project): Boolean =
    project.pluginManager.hasPlugin("com.android.kotlin.multiplatform.library")

  /**
   * The KMP target behind a variant (`androidMain` → `android`), verified against an existing
   * configuration, else the default.
   */
  internal fun kmpAndroidTargetName(project: Project, variantName: String): String {
    val derived = variantName.removeSuffix("Main")
    if (
      derived.isNotEmpty() &&
        project.configurations.findByName("${derived}RuntimeClasspath") != null
    ) {
      return derived
    }
    return "android"
  }

  /**
   * [AndroidVariantNaming] for a KMP-Android variant. `unitTest` is null until `withHostTest { }`,
   * meaning no unit-test classpath or `test_config.properties`; renders still run without merged
   * AAR resources.
   */
  internal fun kmpAndroidNaming(project: Project, variant: Variant): AndroidVariantNaming =
    AndroidVariantNaming.kmpAndroid(
      variantName = variant.name,
      targetName = kmpAndroidTargetName(project, variant.name),
      unitTestName = (variant as? HasUnitTest)?.unitTest?.name,
    )

  internal fun variantMatchesTarget(variantName: String, target: String): Boolean {
    if (variantName == target) return true
    if (target.isEmpty()) return false
    val isBareBuildType = target.none { it.isUpperCase() }
    if (!isBareBuildType) return false
    val capitalized = target.replaceFirstChar { it.uppercase() }
    return variantName.endsWith(capitalized)
  }

  /**
   * Registers `composePreviewDiscoverAndroidResources` for [variant] (when
   * `resourcePreviews.enabled`), wired from the variant's lazy `sources.res.all` and
   * merged-manifest providers.
   */
  private fun registerAndroidResourcePreviewTasks(
    project: Project,
    extension: PreviewExtension,
    variant: Variant,
  ) {
    if (!extension.resourcePreviews.enabled.get()) return
    val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")
    val projectRoot = project.layout.projectDirectory.asFile.absolutePath
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val resSources = variant.sources.res?.all

    project.tasks.register(
      "composePreviewDiscoverAndroidResources",
      DiscoverAndroidResourcesTask::class.java,
    ) {
      group = "compose preview"
      description =
        "Walk res/drawable* and res/mipmap*, parse AndroidManifest.xml, " +
          "write build/compose-previews/resources.json"
      resSources?.let { this.resSourceRoots.from(it) }
      this.mergedManifest.set(mergedManifest)
      moduleName.set(project.name)
      variantName.set(variant.name)
      densities.set(extension.resourcePreviews.densities)
      shapes.set(extension.resourcePreviews.shapes)
      styles.set(extension.resourcePreviews.styles)
      stretches.set(extension.resourcePreviews.stretches)
      filmstrip.set(extension.resourcePreviews.filmstrip)
      filmstripFractions.set(extension.resourcePreviews.filmstripFractions)
      projectDirectory.set(projectRoot)
      outputFile.set(previewOutputDir.map { it.file("resources.json") })
    }
  }

  /**
   * Config-time gate: could this module host previews? Two IP-safe tiers, no eager resolution:
   * 1. [hasDirectPreviewDependency] — a preview-tooling coord is declared directly.
   * 2. [isComposeModule] + [hasAnyProjectDependency] — tooling could arrive transitively
   *    (`:composeApp -> :shared`, #241 / #1549); [ValidatePreviewToolingPresentTask] verifies
   *    against the resolved graph at task time.
   *
   * The Compose-plugin requirement keeps auto-injected non-Compose modules (e.g. `:core:network`)
   * from getting Compose test dependencies. Modules declaring `androidx.xr.compose`
   * ([moduleDeclaresXrCompose]) also pass, so pure-XR modules get zero-config XR rendering.
   *
   * `variantName` is unused, kept for test-fixture compatibility.
   */
  internal fun hasPreviewDependency(
    project: Project,
    @Suppress("UNUSED_PARAMETER") variantName: String,
  ): Boolean =
    hasDirectPreviewDependency(project) ||
      moduleDeclaresXrCompose(project) ||
      (isComposeModule(project) && hasAnyProjectDependency(project))

  /**
   * True when the module applies `org.jetbrains.kotlin.plugin.compose` or `org.jetbrains.compose`;
   * the tier-2 sanity check in [hasPreviewDependency].
   */
  internal fun isComposeModule(project: Project): Boolean =
    project.pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose") ||
      project.pluginManager.hasPlugin("org.jetbrains.compose")

  /**
   * Direct preview-tooling declaration only; the doctor uses it to recommend pinning the dep
   * locally when the gate passed via tier 2.
   */
  internal fun hasDirectPreviewDependency(project: Project): Boolean {
    for (config in declarableBucketsOf(project)) {
      for (dep in config.allDependencies) {
        val g = dep.group
        if (g != null && previewArtifactSignals.any { (sg, sn) -> g == sg && dep.name == sn }) {
          return true
        }
      }
    }
    return false
  }

  /**
   * True when the module declares `androidx.xr.compose:*`. Auto-enables the XR render path;
   * declarative-only, like [hasDirectPreviewDependency]. Gating on it keeps the heavy
   * minCompileSdk-36 XR fakes off non-XR classpaths.
   */
  internal fun moduleDeclaresXrCompose(project: Project): Boolean {
    for (config in declarableBucketsOf(project)) {
      for (dep in config.allDependencies) {
        if (dep.group == "androidx.xr.compose") return true
      }
    }
    return false
  }

  /**
   * True when any `project(":...")` dependency is declared. IP-safe via
   * [ProjectDependency.getPath]. An over-approximation; [ValidatePreviewToolingPresentTask]
   * confirms at task time.
   */
  internal fun hasAnyProjectDependency(project: Project): Boolean {
    for (config in declarableBucketsOf(project)) {
      for (dep in config.allDependencies) {
        if (dep is org.gradle.api.artifacts.ProjectDependency) return true
      }
    }
    return false
  }

  private fun declarableBucketsOf(
    project: Project
  ): Sequence<org.gradle.api.artifacts.Configuration> =
    project.configurations.asSequence().filter { c ->
      val n = c.name
      n == "implementation" ||
        n.endsWith("Implementation") ||
        n == "api" ||
        n.endsWith("Api") ||
        n == "runtimeOnly" ||
        n.endsWith("RuntimeOnly")
    }

  private fun registerAndroidTasks(
    project: Project,
    extension: PreviewExtension,
    variant: Variant,
    bootClasspath: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>,
    consumerCompileSdk: org.gradle.api.provider.Provider<Int>,
    consumerMinSdk: org.gradle.api.provider.Provider<Int>,
    naming: AndroidVariantNaming,
  ) {
    // AGP-derived names go through [naming], since KMP-Android names configurations and tasks after
    // the target and host-test compilation. These locals are for names keyed by the variant in both
    // worlds.
    val variantName = variant.name
    val capVariant = variantName.cap()
    // The host-test bucket; the elvis is unreachable on the Robolectric lane.
    val testImplementationBucket = naming.testImplementation ?: "testImplementation"
    // AGP's host-test `Test` task, looked up by name since it's registered later. `""` when absent:
    // `findByName("")` is null, which call sites already treat as "no AGP test task".
    val unitTestTaskName = naming.unitTestTask ?: ""
    val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")
    val artifactType = Attribute.of("artifactType", String::class.java)
    val daemonResDirs =
      variant.sources.res?.all?.let { resSources ->
        project.files(resSources).elements.map { elements ->
          elements.joinToString(java.io.File.pathSeparator) { it.asFile.absolutePath }
        }
      } ?: project.providers.provider { "" }

    // Google's `com.android.compose.screenshot` plugin adds a `screenshotTest` source set; we don't
    // drive its tasks but do discover and render previews under `src/screenshotTest/`. Detected by
    // plugin id rather than the global `enableScreenshotTest` property, since only the plugin makes
    // AGP register the compile task and runtime classpath we need.
    val screenshotTestEnabled = project.pluginManager.hasPlugin("com.android.compose.screenshot")

    // `kotlin("multiplatform") + com.android.library` (#1492): KGP names compile tasks per target
    // (`compileDebugKotlinAndroid`) and outputs to `build/classes/kotlin/<target>/<variant>/`. Both
    // shapes are added as candidates; missing ones are skipped. A renamed `androidTarget("foo")`
    // still needs the issue's workaround.
    val isKmp = project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")

    val sourceClassDirs =
      project.files(
        project.layout.buildDirectory.dir("tmp/kotlin-classes/$variantName"),
        project.layout.buildDirectory.dir("intermediates/javac/$variantName/classes"),
        project.layout.buildDirectory.dir(
          "intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"
        ),
      )
    if (isKmp) {
      // Named after the target, not hardcoded `android`, so renamed targets reach the render
      // classpath. Non-existent dirs are skipped by [DiscoverPreviewsTask].
      naming.extraClassDirs.forEach { sourceClassDirs.from(project.layout.buildDirectory.dir(it)) }
      // The `androidTarget()` + `com.android.library` shape (#1492), which `classic` naming doesn't
      // carry.
      if (naming.extraClassDirs.isEmpty()) {
        sourceClassDirs.from(
          project.layout.buildDirectory.dir("classes/kotlin/android/$variantName")
        )
      }
    }
    if (screenshotTestEnabled) {
      sourceClassDirs.from(
        project.layout.buildDirectory.dir(
          "intermediates/built_in_kotlinc/${variantName}ScreenshotTest/compile${capVariant}ScreenshotTestKotlin/classes"
        ),
        project.layout.buildDirectory.dir(
          "intermediates/javac/${variantName}ScreenshotTest/classes"
        ),
      )
    }

    val dependencyConfigName = naming.runtimeClasspath
    val screenshotTestRuntimeConfig =
      if (screenshotTestEnabled) {
        project.configurations.findByName("${variantName}ScreenshotTestRuntimeClasspath")
      } else null

    val mainCompileTaskNames =
      if (isKmp)
        // `compile$capVariant` is KMP-Android's single compilation; the other two are the
        // `androidTarget()` shape. Matched by name, so missing ones cost nothing.
        listOf(
          "compile${capVariant}Kotlin",
          "compile${capVariant}KotlinAndroid",
          "compile$capVariant",
        )
      else listOf("compile${capVariant}Kotlin")
    // Include the screenshotTest javac task too: both its Kotlin and javac outputs are on the
    // render classpath, so strict validation needs both dependencies.
    val screenshotCompileTaskNames =
      if (isKmp)
        listOf(
          "compile${capVariant}ScreenshotTestKotlin",
          "compile${capVariant}ScreenshotTestKotlinAndroid",
          "compile${capVariant}ScreenshotTestJavaWithJavac",
        )
      else
        listOf(
          "compile${capVariant}ScreenshotTestKotlin",
          "compile${capVariant}ScreenshotTestJavaWithJavac",
        )
    val discoverTask =
      ComposePreviewTasks.registerDiscoverTask(
        project,
        sourceClassDirs,
        { dependencyConfigName },
        previewOutputDir,
        extension,
        activeCompilationSourceFiles =
          project
            .files(listOfNotNull(variant.sources.java?.all, variant.sources.kotlin?.all))
            .asFileTree
            .matching {
              include("**/*.kt")
              include("**/*.java")
            },
      ) {
        // Lazy `tasks.matching` so modules lacking `compileDebugKotlin` (KMP) don't crash at
        // task-graph time (#1492).
        dependsOn(project.tasks.matching { it.name in mainCompileTaskNames })
        // Lottie assets from Java-resource source dirs (`src/main/resources`), rendered via desktop
        // Compottie. Source dirs rather than AGP's version-specific `java_res` intermediates; the
        // same dirs go on `composePreviewRenderLottie`'s classpath.
        resourceDirs.from(androidLottieResourceDirs(project))
        // Disjoint from `renders/` to keep both render tasks cacheable.
        lottieRenderSubdir.set(LOTTIE_RENDER_SUBDIR)
        svgRenderSubdir.set(SVG_RENDER_SUBDIR)
        // Lets discovery detect Wear modules (#1985) and drives activity discovery.
        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        // Committed tour scripts: each `compose-previews/tours/<name>.json` becomes a synthetic
        // `kind=APP_TOUR` preview whose captures are the tour's steps (launch → click/intent/back).
        tourSpecFiles.from(
          project.layout.projectDirectory.dir(TOUR_SPECS_DIR).asFileTree.matching {
            include("*.json")
          }
        )
        if (screenshotTestEnabled) {
          dependsOn(project.tasks.matching { it.name in screenshotCompileTaskNames })
          screenshotTestRuntimeConfig?.let { stConfig ->
            dependencyJars.from(
              stConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files
            )
            dependencyJars.from(
              stConfig.incoming
                .artifactView { attributes.attribute(artifactType, "android-classes") }
                .files
            )
            // Coordinates too: on Android a jar path names the module but not its group (see
            // [ComposePreviewTasks]).
            for (attributeValue in listOf("jar", "android-classes")) {
              dependencyJarCoordinates.putAll(
                stConfig.incoming
                  .artifactView { attributes.attribute(artifactType, attributeValue) }
                  .artifacts
                  .resolvedArtifacts
                  .map { artifacts ->
                    artifacts.associate {
                      it.file.absolutePath to it.id.componentIdentifier.displayName
                    }
                  }
              )
            }
          }
        }
      }

    // The variant's own classes via AGP's scoped PROJECT CLASSES artifact, which is populated even
    // under AGP 9 built-in Kotlin, whose output never lands in the legacy directory (#1924). Also
    // wires the dependency on whichever task produced them. Additive to `sourceClassDirs`;
    // ClassGraph dedupes.
    variant.artifacts
      .forScope(ScopedArtifacts.Scope.PROJECT)
      .use(discoverTask)
      .toGet(
        ScopedArtifact.CLASSES,
        DiscoverPreviewsTask::projectClassJars,
        DiscoverPreviewsTask::projectClassDirs,
      )

    // `composePreviewCompile`: the daemon save loop recompiles without re-walking dependency JARs.
    // Main compile only.
    ComposePreviewTasks.registerCompileOnlyTask(
      project,
      extension,
      compileTaskNames = mainCompileTaskNames,
    )

    // Only when the screenshot plugin is applied. See [CheckDebugPreviewsTask]: `src/debug/`
    // previews compiled against the `screenshotTest` closure fail confusingly.
    if (screenshotTestEnabled) {
      val debugSrcTree =
        project.fileTree(project.projectDir.resolve("src/debug")) {
          include("**/*.kt", "**/*.java")
        }
      val checkDebugTask =
        project.tasks.register(
          "composePreviewCheckDebugPreviews",
          CheckDebugPreviewsTask::class.java,
        ) {
          group = "compose preview"
          description =
            "Warn when @Preview functions live in src/debug/ on a module with the " +
              "com.android.compose.screenshot plugin (move them to src/screenshotTest/)"
          debugSourceFiles.from(debugSrcTree)
          projectDirectory.set(project.layout.projectDirectory.asFile.absolutePath)
        }
      // `finalizedBy` so discovery never waits on this warn-only check.
      discoverTask.configure { finalizedBy(checkDebugTask) }
    }

    // Plugin-side compat findings (CompatRules) to `build/compose-previews/doctor.json`, for
    // task-driven tools like VS Code; same schema as `compose-preview doctor --json`. Root
    // components are resolved at configuration time for config-cache safety; a missing unit-test
    // classpath is tolerated.
    val mainRuntimeRoot =
      project.configurations
        .findByName(naming.runtimeClasspath)
        ?.incoming
        ?.resolutionResult
        ?.rootComponent
    val testRuntimeRoot =
      naming.unitTestRuntimeClasspath
        ?.let { project.configurations.findByName(it) }
        ?.incoming
        ?.resolutionResult
        ?.rootComponent
    // AAR manifests on the unit-test classpath, resolved lazily for the library-minSdk check.
    val testManifestArtifacts =
      naming.unitTestRuntimeClasspath
        ?.let { project.configurations.findByName(it) }
        ?.incoming
        ?.artifactView {
          lenient(true)
          attributes.attribute(artifactType, "android-manifest")
        }
        ?.artifacts
        ?.resolvedArtifacts

    // Read at configuration time to keep it out of the task action.
    val currentGradleVersion = org.gradle.util.GradleVersion.current().version
    // Inject records; read lazily so the afterEvaluate tiles entry is included.
    val injectedDependencies =
      mutableListOf<ee.schimke.composeai.plugin.tooling.InjectedDependency>()
    val injectedDependencyJson = kotlinx.serialization.json.Json { encodeDefaults = true }
    // A plain serializable `@Input`; the transitive check happens at the doctor's action time via
    // `mainRuntimeRoot` (#1549).
    val previewToolingDeclaredAtRegistration = hasDirectPreviewDependency(project)
    project.tasks.register(
      "composePreviewDoctor",
      ee.schimke.composeai.plugin.tooling.ComposePreviewDoctorTask::class.java,
    ) {
      group = "compose preview"
      description = "Write compose-preview doctor findings to build/compose-previews/doctor.json"
      this.variant.set(variantName)
      this.modulePath.set(project.path)
      this.gradleVersion.set(currentGradleVersion)
      this.outputFile.set(previewOutputDir.map { it.file("doctor.json") })
      mainRuntimeRoot?.let { this.mainRuntimeRoot.set(it) }
      testRuntimeRoot?.let { this.testRuntimeRoot.set(it) }
      this.moduleMinSdk.set(consumerMinSdk)
      testManifestArtifacts?.let { this.testManifestArtifacts.set(it) }
      this.previewToolingDeclared.set(previewToolingDeclaredAtRegistration)
      this.enforcePreviewToolingDependency.set(extension.enforcePreviewToolingDependency)
      this.injectedDependenciesJson.set(
        project.provider {
          injectedDependencyJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
              ee.schimke.composeai.plugin.tooling.InjectedDependency.serializer()
            ),
            injectedDependencies.toList(),
          )
        }
      )
    }

    // Task-time preview-tooling validation (#1549) for modules that passed the gate only via
    // project deps: resolving the graph is only safe at execution time. Skipped when a direct
    // declaration already proves it. Opt-in via `failOnMissingPreviewTooling`, because aggregator
    // modules legitimately pass tier 2 without hosting previews.
    val validatePreviewToolingPresentTask =
      if (
        !previewToolingDeclaredAtRegistration &&
          extension.enforcePreviewToolingDependency.get() &&
          extension.failOnMissingPreviewTooling.get() &&
          mainRuntimeRoot != null
      ) {
        project.tasks.register(
          "composePreviewValidatePreviewToolingPresent",
          ValidatePreviewToolingPresentTask::class.java,
        ) {
          this.modulePath.set(project.path)
          this.runtimeClasspathRoot.set(mainRuntimeRoot)
        }
      } else null

    // Always inject into `testImplementation`:
    // * `ui-test-manifest` — the `ComponentActivity` manifest entry `createAndroidComposeRule`
    //   needs; our renderer config is outside AGP's graph, so the manifest merger wouldn't see it.
    // * `ui-test-junit4` — `createAndroidComposeRule` / `mainClock`, used unconditionally by the
    //   renderer.
    //
    // `manageDependencies = false` skips injection, records SKIPPED_BY_CONFIG in `doctor.json`, and
    // the afterEvaluate block fails configuration with the missing coordinates.
    val manageDependencies = extension.manageDependencies.get()

    // Pinned to [RENDERER_COMPOSE_FLOOR_VERSION] rather than unversioned: tile-only apps have no
    // Compose BOM, so an unversioned coordinate fails resolution (surfacing as an opaque
    // config-cache error), yet tile renders still use `createAndroidComposeRule`. Consumers with a
    // BOM get their higher version via conflict resolution. Keep in sync with the renderer's
    // compile floor.
    if (manageDependencies) {
      addPluginDependency(
        project,
        testImplementationBucket,
        "androidx.compose.ui:ui-test-manifest:$RENDERER_COMPOSE_FLOOR_VERSION",
      )
      addPluginDependency(
        project,
        testImplementationBucket,
        "androidx.compose.ui:ui-test-junit4:$RENDERER_COMPOSE_FLOOR_VERSION",
      )
      // Main-variant floor pins for tile-only / non-Compose-UI consumers. AGP builds the merged
      // unit-test resource APK from the main variant, so a main variant without compose-ui and its
      // resource deps lacks R classes the renderer reads. Floors only; no-ops for consumers with
      // their own BOM. Each note gives the failure the pin prevents; `composePreviewDoctor` shows
      // the user-facing `reason`.

      // compose-ui 1.10+'s `InsetsListener.onViewAttachedToWindow` reads
      // `androidx.core.R.id.tag_compat_insets_dispatch`, added in core 1.16.0:
      //   NoSuchFieldError: androidx.core.R$id … 'int tag_compat_insets_dispatch'
      project.dependencies.add("${variantName}Implementation", "androidx.core:core:1.16.0")

      // compose-ui's `PoolingContainer.<clinit>` reads
      // `androidx.customview.poolingcontainer.R.id.*`:
      //   NoClassDefFoundError: androidx/customview/poolingcontainer/R$id
      // 1.0.0 is the only published version.
      project.dependencies.add(
        "${variantName}Implementation",
        "androidx.customview:customview-poolingcontainer:1.0.0",
      )

      // `ViewTreeOnBackPressedDispatcherOwner.set` reads an `androidx.activity.R.id` added in
      // 1.5.0:
      //   NoSuchFieldError: androidx.activity.R$id … 'view_tree_on_back_pressed_dispatcher_owner'
      project.dependencies.add("${variantName}Implementation", "androidx.activity:activity:1.10.0")

      // compose-ui's accessibility delegate reads `androidx.compose.ui.R.id.*`:
      //   NoClassDefFoundError: androidx/compose/ui/R$id
      //
      // Not the compile floor: the merged R class must match the compose-ui classes that run — the
      // consumer's BOM, or our CMP runtime for a Compose-less consumer (1.9.5's `R.txt` lacks
      // fields the newer classes read). Reused for `foundation` and the doctor records.
      val mainComposeVersion =
        mainVariantComposeVersion(project, variantName, naming.unitTestRuntimeClasspath)
      addPluginDependency(
        project,
        "${variantName}Implementation",
        "androidx.compose.ui:ui:$mainComposeVersion",
      )

      // `TilePreviewComposable` calls `Modifier.fillMaxSize()`:
      //   NoClassDefFoundError: androidx/compose/foundation/layout/SizeKt
      // Versioned with compose-ui, since foundation and ui share a line.
      addPluginDependency(
        project,
        "${variantName}Implementation",
        "androidx.compose.foundation:foundation:$mainComposeVersion",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-manifest:$RENDERER_COMPOSE_FLOOR_VERSION",
        configuration = testImplementationBucket,
        outcome = "APPLIED",
        reason =
          "merges ComponentActivity into the unit-test manifest for renderer; pinned to the renderer's compile floor so tile-only consumers without a Compose BOM still resolve a version (Gradle picks max with consumer-BOM-aligned versions)",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-junit4:$RENDERER_COMPOSE_FLOOR_VERSION",
        configuration = testImplementationBucket,
        outcome = "APPLIED",
        reason =
          "provides createAndroidComposeRule / mainClock used by renderer; pinned to the renderer's compile floor (see ui-test-manifest entry above for the version-pin rationale)",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.core:core:1.16.0",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "compose-ui 1.10+ on the renderer test classpath reads R.id.tag_compat_insets_dispatch (added in core 1.16); merged test APK needs the field",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.customview:customview-poolingcontainer:1.0.0",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "compose-ui's ViewCompositionStrategy reads androidx.customview.poolingcontainer.R.id.* from PoolingContainer.<clinit>; merged test APK needs the R class",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.activity:activity:1.10.0",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "activity-compose 1.5+ on the renderer test classpath reads R.id.view_tree_on_back_pressed_dispatcher_owner via ViewTreeOnBackPressedDispatcherOwner.set (added in androidx.activity:activity:1.5.0); merged test APK needs the field so ComponentActivity.initializeViewTreeOwners doesn't NoSuchFieldError",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui:$mainComposeVersion",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "compose-ui's AndroidComposeViewAccessibilityDelegateCompat.<clinit> reads androidx.compose.ui.R.id.*; merged test APK needs the compose-ui R class on tile-only consumers without compose-ui in main. Versioned by whichever Compose actually runs: the renderer's compile floor when the consumer brings its own Compose, the CMP runtime version when ours is the only Compose on the render classpath",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.foundation:foundation:$mainComposeVersion",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "TilePreviewRenderer.TilePreviewComposable calls Modifier.fillMaxSize() from androidx.compose.foundation.layout.SizeKt; tile-only consumers without compose-foundation in main hit NoClassDefFoundError at render time. Versioned with compose-ui above — foundation and ui have to stay on one line",
      )
    } else {
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-manifest",
        configuration = testImplementationBucket,
        outcome = "SKIPPED_BY_CONFIG",
        reason = "manageDependencies=false; consumer must declare this in testImplementation",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-junit4",
        configuration = testImplementationBucket,
        outcome = "SKIPPED_BY_CONFIG",
        reason = "manageDependencies=false; consumer must declare this in testImplementation",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.core:core:1.16.0",
        configuration = "${variantName}Implementation",
        outcome = "SKIPPED_BY_CONFIG",
        reason =
          "manageDependencies=false; consumer must ensure androidx.core:core >= 1.16.0 on the main variant so the merged test APK includes R.id.tag_compat_insets_dispatch",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.customview:customview-poolingcontainer:1.0.0",
        configuration = "${variantName}Implementation",
        outcome = "SKIPPED_BY_CONFIG",
        reason =
          "manageDependencies=false; consumer must ensure androidx.customview:customview-poolingcontainer is on the main variant so the merged test APK includes its R class (referenced by compose-ui's PoolingContainer)",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.activity:activity:1.10.0",
        configuration = "${variantName}Implementation",
        outcome = "SKIPPED_BY_CONFIG",
        reason =
          "manageDependencies=false; consumer must ensure androidx.activity:activity >= 1.5.0 on the main variant so the merged test APK includes R.id.view_tree_on_back_pressed_dispatcher_owner (referenced by activity-compose's ComponentActivity.initializeViewTreeOwners)",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui",
        configuration = "${variantName}Implementation",
        outcome = "SKIPPED_BY_CONFIG",
        reason =
          "manageDependencies=false; consumer must ensure androidx.compose.ui:ui is on the main variant so the merged test APK includes its R class (referenced by AndroidComposeViewAccessibilityDelegateCompat.<clinit>)",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.foundation:foundation",
        configuration = "${variantName}Implementation",
        outcome = "SKIPPED_BY_CONFIG",
        reason =
          "manageDependencies=false; consumer must ensure androidx.compose.foundation:foundation is on the main variant so SizeKt (Modifier.fillMaxSize) is class-loadable when TilePreviewRenderer runs",
      )
    }

    // Inject `androidx.wear.tiles:tiles-renderer` into the variant's `implementation` when the
    // consumer declares tile dependencies (checked in `afterEvaluate`, once deps are complete).
    // `TileRenderer` references `protolayout.renderer.R$style.ProtoLayoutBaseTheme`, which is only
    // generated when the artifact is on the main compile classpath. Unversioned: the consumer's
    // wear.tiles atomic group constrains it.
    project.afterEvaluate {
      val composeAiTraceEnabled = resolveComposeAiTraceEnabled(project, extension).get()
      if (composeAiTraceEnabled) {
        if (manageDependencies) {
          // Same floor pin as ui-test-manifest; see [RENDERER_COMPOSE_FLOOR_VERSION].
          addPluginDependency(
            project,
            testImplementationBucket,
            "androidx.compose.runtime:runtime-tracing:$RENDERER_COMPOSE_FLOOR_VERSION",
          )
          recordInjectedDependency(
            project,
            injectedDependencies,
            coordinate = "androidx.compose.runtime:runtime-tracing:$RENDERER_COMPOSE_FLOOR_VERSION",
            configuration = testImplementationBucket,
            outcome = "APPLIED",
            reason =
              "required by compose-ai-tools trace data product; pinned to the renderer's compile floor (Gradle picks max with consumer BOM)",
          )
        } else {
          recordInjectedDependency(
            project,
            injectedDependencies,
            coordinate = "androidx.compose.runtime:runtime-tracing",
            configuration = testImplementationBucket,
            outcome = "SKIPPED_BY_CONFIG",
            reason =
              "manageDependencies=false; consumer must declare this when composeAiTrace is enabled",
          )
        }
      }

      // Scan every declarable bucket (`implementation`/`api`/`runtimeOnly`, any source set, build
      // type or flavor), not a hardcoded list. Declarative rather than resolving, for config cache
      // and Isolated Projects; the exact group+name filter keeps the wide scan precise.
      val matchedConfigs = mutableListOf<String>()
      project.configurations
        .asSequence()
        .filter { c ->
          val n = c.name
          n == "implementation" ||
            n.endsWith("Implementation") ||
            n == "api" ||
            n.endsWith("Api") ||
            n == "runtimeOnly" ||
            n.endsWith("RuntimeOnly")
        }
        .forEach { c ->
          val hit =
            c.allDependencies.any { dep ->
              (dep.group == "androidx.wear.tiles" && dep.name in tilesSignalNames) ||
                (dep.group == "com.google.android.horologist" && dep.name == "horologist-tiles")
            }
          if (hit) matchedConfigs += c.name
        }
      if (matchedConfigs.isNotEmpty()) {
        if (manageDependencies) {
          project.dependencies.add(
            "${variantName}Implementation",
            "androidx.wear.tiles:tiles-renderer",
          )
          recordInjectedDependency(
            project,
            injectedDependencies,
            coordinate = "androidx.wear.tiles:tiles-renderer",
            configuration = "${variantName}Implementation",
            outcome = "MATCHED",
            reason = "signal matched on [${matchedConfigs.joinToString(", ")}]",
          )
        } else {
          recordInjectedDependency(
            project,
            injectedDependencies,
            coordinate = "androidx.wear.tiles:tiles-renderer",
            configuration = "${variantName}Implementation",
            outcome = "SKIPPED_BY_CONFIG",
            reason =
              "manageDependencies=false; tiles signal matched on [${matchedConfigs.joinToString(", ")}] but consumer must declare tiles-renderer in ${variantName}Implementation",
          )
        }
      } else {
        recordInjectedDependency(
          project,
          injectedDependencies,
          coordinate = "androidx.wear.tiles:tiles-renderer",
          configuration = "",
          outcome = "SKIPPED",
          reason =
            "no androidx.wear.tiles / horologist-tiles dep on any *Implementation/*Api/*RuntimeOnly configuration",
        )
      }

      // `manageDependencies=false`: verify the consumer declared what we'd have injected and fail
      // configuration with the coordinates, rather than a ClassNotFoundException at render time.
      // Any declarable bucket counts.
      if (!manageDependencies) {
        validateExternallyManagedDependencies(
          project = project,
          variantName = variantName,
          testImplementation = testImplementationBucket,
          tilesRendererRequired = matchedConfigs.isNotEmpty(),
          composeAiTraceRequired = composeAiTraceEnabled,
        )
      }
    }

    val testConfig = naming.unitTestRuntimeClasspath?.let { project.configurations.findByName(it) }

    // Read eagerly: we're in `onVariants`, and Gradle exclude rules are an eager `Set` anyway.
    val renderGraphExclusions = extension.renderGraph.excludes.get()

    // External consumers resolve `renderer-android:<plugin-version>` from Maven (see
    // [PluginVersion]). Inside this repository, depend on the sibling `:renderer-android` project
    // directly so renderer edits apply without publishing; detected via a filesystem check because
    // `findProject` is banned under Isolated Projects.
    val rendererProjectDir = project.rootDir.resolve("renderers/android")
    val useLocalRenderer =
      rendererProjectDir.resolve("build.gradle.kts").exists() ||
        rendererProjectDir.resolve("build.gradle").exists()

    // Renderer runtime deps resolve through a dedicated configuration with the unit-test
    // classpath's attributes.
    //
    // `extendsFrom(testConfig)` is load-bearing: renderer and consumer deps resolve in one graph to
    // a single coherent version each. Separate graphs put e.g. two `androidx.core` versions on the
    // classpath, giving `NoSuchFieldError`s like `ReportFragment.Companion` or
    // `tag_compat_insets_dispatch`.
    val rendererConfig =
      project.configurations.maybeCreate("composePreviewAndroidRenderer$capVariant").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        if (testConfig != null) {
          copyAttributes(attributes, testConfig.attributes)
          extendsFrom(testConfig)
        }
        // Also the screenshotTest runtime classpath when that plugin is applied, folded in so it
        // stays one graph (resolving it separately repeats the version-skew problem).
        screenshotTestRuntimeConfig?.let { extendsFrom(it) }
        // Shared rules (see [applyRenderGraphResolutionRules]); also applied to the daemon config,
        // since `extendsFrom` doesn't inherit `resolutionStrategy`. `floorComposeLine` follows
        // `manageDependencies`: without our main-variant pins, flooring would cause #3484.
        applyRenderGraphResolutionRules(this, floorComposeLine = manageDependencies)
        // Consumer exclusions after our rules, so substitutions can't pull an excluded module back.
        // See [RenderGraphExtension] (#4995).
        RenderGraphExclusions.applyTo(project, this, renderGraphExclusions)
      }

    if (useLocalRenderer) {
      try {
        addRenderGraphDependency(
          project,
          rendererConfig.name,
          project.dependencies.project(mapOf("path" to ":renderer-android")),
        )
      } catch (e: org.gradle.api.UnknownProjectException) {
        project.logger.debug("compose-ai-tools: :renderer-android project not found, skipping", e)
      }
    } else {
      addRenderGraphDependency(
        project,
        rendererConfig.name,
        PreviewDaemonModules.dependency(project, rendererConfig.name, "renderer-android"),
      )
    }

    // XR render backend: `:renderer-xr`'s `XrSubspaceRenderTest` plus the fake XR runtime, for
    // `composePreviewRenderXr`. Auto-enabled by a declared `androidx.xr.compose` dependency (or
    // `enableXrPreviews`); not always-on because XR Compose needs compileSdk 36 and the fakes are
    // heavy. The fakes are inert for non-XR renders.
    val xrPreviewsEnabled = extension.enableXrPreviews.get() || moduleDeclaresXrCompose(project)
    val xrRendererProjectDir = project.rootDir.resolve("renderers/xr")
    val useLocalXrRenderer =
      xrRendererProjectDir.resolve("build.gradle.kts").exists() ||
        xrRendererProjectDir.resolve("build.gradle").exists()
    if (xrPreviewsEnabled) {
      if (useLocalXrRenderer) {
        try {
          addRenderGraphDependency(
            project,
            rendererConfig.name,
            project.dependencies.project(mapOf("path" to ":renderer-xr")),
          )
          // `compileOnly` on `:renderer-xr`, so supply it at runtime for spatial-semantics
          // projection.
          addRenderGraphDependency(
            project,
            rendererConfig.name,
            project.dependencies.project(mapOf("path" to ":data-layoutinspector-connector")),
          )
        } catch (e: org.gradle.api.UnknownProjectException) {
          project.logger.debug("compose-ai-tools: :renderer-xr project not found, skipping", e)
        }
      } else {
        addRenderGraphDependency(
          project,
          rendererConfig.name,
          "ee.schimke.composeai:renderer-xr:${XrFakeVersions.renderer}",
        )
        addRenderGraphDependency(
          project,
          rendererConfig.name,
          PreviewDaemonModules.dependency(
            project,
            rendererConfig.name,
            "data-layoutinspector-connector",
          ),
        )
      }
      addRenderGraphDependency(
        project,
        rendererConfig.name,
        "androidx.xr.runtime:runtime-testing:${XrFakeVersions.runtimeTesting}",
      )
      addRenderGraphDependency(
        project,
        rendererConfig.name,
        "androidx.xr.scenecore:scenecore-testing:${XrFakeVersions.scenecoreTesting}",
      )
      addRenderGraphDependency(
        project,
        rendererConfig.name,
        "androidx.xr.compose:compose-testing:${XrFakeVersions.compose}",
      )
      // Fake ARCore perception runtime so `rotateToLookAtUser` renders offline; `FakeXrHeadPose`
      // seeds the head pose.
      addRenderGraphDependency(
        project,
        rendererConfig.name,
        "androidx.xr.arcore:arcore-testing:${XrFakeVersions.arcoreTesting}",
      )
    }

    // Daemon counterpart of rendererConfig, providing `DaemonMain` for the launch descriptor.
    val daemonRendererConfig =
      project.configurations.maybeCreate("composePreviewAndroidDaemon$capVariant").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        if (testConfig != null) {
          copyAttributes(attributes, testConfig.attributes)
        }
        // `extendsFrom(rendererConfig)` makes this a strict superset of the render graph resolved
        // in one pass; concatenating separate resolutions put modules on the classpath at several
        // versions.
        extendsFrom(rendererConfig)
        // `resolutionStrategy` isn't inherited, so apply the same rules here or the two JVMs render
        // off different graphs.
        applyRenderGraphResolutionRules(this, floorComposeLine = manageDependencies)
        // Exclude rules aren't inherited either.
        RenderGraphExclusions.applyTo(project, this, renderGraphExclusions)
      }

    val daemonRendererProjectDir = project.rootDir.resolve("daemon/android")
    val useLocalDaemonRenderer =
      daemonRendererProjectDir.resolve("build.gradle.kts").exists() ||
        daemonRendererProjectDir.resolve("build.gradle").exists()

    if (useLocalDaemonRenderer) {
      try {
        addRenderGraphDependency(
          project,
          daemonRendererConfig.name,
          project.dependencies.project(mapOf("path" to ":daemon:android")),
        )
      } catch (e: org.gradle.api.UnknownProjectException) {
        project.logger.debug("compose-ai-tools: :daemon:android project not found, skipping", e)
      }
    } else {
      // External mode: `daemon-android` from Maven, versionless through the daemon BOM (see
      // [PreviewDaemonModules]). This config gets its own platform rather than relying on the
      // inherited one.
      addRenderGraphDependency(
        project,
        daemonRendererConfig.name,
        PreviewDaemonModules.dependency(project, daemonRendererConfig.name, "daemon-android"),
      )
    }

    // `eachDependency` only sees requested selectors, not what constraints and platforms select
    // (#4959), so validate the resolved graph at execution time. The daemon's superset graph gets
    // its own validator.
    val validateComposeFloorTask =
      if (!manageDependencies) {
        project.tasks.register(
          "composePreviewValidateComposeFloor",
          ValidateComposeFloorTask::class.java,
        ) {
          runtimeClasspathRoot.set(rendererConfig.incoming.resolutionResult.rootComponent)
        }
      } else null
    val validateDaemonComposeFloorTask =
      if (!manageDependencies) {
        project.tasks.register(
          "composePreviewValidateDaemonComposeFloor",
          ValidateComposeFloorTask::class.java,
        ) {
          runtimeClasspathRoot.set(daemonRendererConfig.incoming.resolutionResult.rootComponent)
        }
      } else null

    // Classes for Gradle's test-class scanning. Local mode: the project's class dirs. External
    // mode: the AAR's `classes.jar` via `zipTree`, because `Test.include` doesn't descend into JARs
    // (a raw JAR yields `NO-SOURCE`).
    //
    // [rendererClasspathEntries] is the runtime counterpart and uses the jar itself: a `zipTree` on
    // a classpath contributes each `.class` as an invalid element, which some vendor JDKs choke on
    // (#5562).
    val rendererClassDirs: FileCollection
    val rendererClasspathEntries: FileCollection
    if (useLocalRenderer) {
      rendererClassDirs =
        project.files(
          rendererProjectDir.resolve(
            "build/intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"
          ),
          rendererProjectDir.resolve("build/tmp/kotlin-classes/$variantName"),
        )
      rendererClasspathEntries = rendererClassDirs
    } else {
      val rendererJars =
        rendererConfig.incoming
          .artifactView {
            attributes.attribute(artifactType, "android-classes")
            componentFilter { id ->
              id is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                id.group == "ee.schimke.composeai" &&
                id.module == "renderer-android"
            }
          }
          .files
      // `elements.map { … }` so task-graph construction sees a Provider; a Callable would resolve
      // `rendererConfig` at configuration time (#1038).
      rendererClassDirs =
        project.files(
          rendererJars.elements.map { elements -> elements.map { project.zipTree(it.asFile) } }
        )
      rendererClasspathEntries = rendererJars
    }

    // Same shape for `:renderer-xr` (only `composePreviewRenderXr` reads it);
    // [xrRendererClasspathEntries] is its runtime counterpart.
    val xrRendererClassDirs: FileCollection
    val xrRendererClasspathEntries: FileCollection
    if (useLocalXrRenderer) {
      xrRendererClassDirs =
        project.files(
          xrRendererProjectDir.resolve(
            "build/intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"
          ),
          xrRendererProjectDir.resolve("build/tmp/kotlin-classes/$variantName"),
        )
      xrRendererClasspathEntries = xrRendererClassDirs
    } else {
      val xrRendererJars =
        rendererConfig.incoming
          .artifactView {
            attributes.attribute(artifactType, "android-classes")
            componentFilter { id ->
              id is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                id.group == "ee.schimke.composeai" &&
                id.module == "renderer-xr"
            }
          }
          .files
      xrRendererClassDirs =
        project.files(
          xrRendererJars.elements.map { elements -> elements.map { project.zipTree(it.asFile) } }
        )
      xrRendererClasspathEntries = xrRendererJars
    }

    // AGP's `generate${Variant}UnitTestConfig` emits `com/android/tools/test_config.properties`,
    // which Robolectric uses to find the merged resource APK with every AAR's resources. Without it
    // library styles resolve to 0 (TileRenderer fails on `Unknown resource value type 0`). Null
    // without a host-test component (KMP-Android's default).
    val unitTestConfigDir = naming.unitTestConfigDir?.let { project.layout.buildDirectory.dir(it) }

    // A bare buildDir path with no producer wired, so every task reading it through the render
    // classpath must depend on the generator or Gradle 9's strict validation fails. Matched by name
    // so it's empty when unit tests are disabled.
    val unitTestConfigProducer = project.tasks.matching { it.name == naming.unitTestConfigTask }

    // Generates the package-level `robolectric.properties` that stubs the consumer's `Application`
    // by default; see [GenerateRobolectricPropertiesTask].
    val robolectricPropertiesDir =
      project.layout.buildDirectory.dir("generated/composeai/robolectric/$variantName")
    val generateRobolectricPropertiesTask =
      project.tasks.register(
        "composePreviewGenerateRobolectricProperties",
        GenerateRobolectricPropertiesTask::class.java,
      ) {
        group = "compose preview"
        description = "Generate package-level robolectric.properties for composePreviewRender"
        useConsumerApplication.set(extension.useConsumerApplication)
        appTourUseConsumerApplication.set(extension.appTourUseConsumerApplication)
        wireSdkInputs(this, extension.sdkVersion, consumerCompileSdk)
        outputDir.set(robolectricPropertiesDir)
      }

    // A stand-in `poolingcontainer.R$id`, appended last so a real merged `R.jar` always wins; the
    // floor for modules where AGP's unit-test R.jar never arrives (#5026). See
    // [GeneratePoolingContainerRTask].
    val poolingContainerRDir =
      project.layout.buildDirectory.dir("generated/composeai/r-shim/$variantName")
    val generatePoolingContainerRTask =
      project.tasks.register(
        "composePreviewGeneratePoolingContainerR",
        GeneratePoolingContainerRTask::class.java,
      ) {
        group = "compose preview"
        description =
          "Generate a fallback androidx.customview.poolingcontainer.R\$id for composePreviewRender"
        ids.set(GeneratePoolingContainerRTask.TAG_IDS)
        outputDir.set(poolingContainerRDir)
      }
    val poolingContainerRFiles = project.files(generatePoolingContainerRTask.map { it.outputDir })

    // Renderer classpath first: `FileCollection.from()` doesn't resolve conflicts, so the first JAR
    // wins and the renderer must see the versions it was compiled against. Built by
    // [AndroidPreviewClasspath.buildTestClasspath], shared with the daemon. AGP-only generated
    // files use a late-bound collection, since an upstream `tasks.withType<Test>` can realize our
    // task before AGP registers its own.
    val bootClasspathFallback = AndroidPreviewClasspath.buildBootClasspathFallback(project)
    // Escape hatch to the pre-#2731 concatenated classpath (which caused duplicate versions, see
    // [RenderClasspathDuplicates]), for consumers depending on a testConfig-only artifact.
    val legacyClasspathUnion =
      project.providers.gradleProperty("composePreview.legacyClasspathUnion").orNull == "true"
    // warn (default) | fail | off; read at configuration time so the action captures a plain
    // String.
    val classpathDuplicatesMode =
      project.providers
        .gradleProperty("composePreview.classpathDuplicates")
        .orNull
        ?.lowercase()
        ?.takeIf {
          it in
            setOf(
              RenderClasspathDuplicates.MODE_WARN,
              RenderClasspathDuplicates.MODE_FAIL,
              RenderClasspathDuplicates.MODE_OFF,
            )
        } ?: RenderClasspathDuplicates.MODE_WARN
    // File → `group:name:version` map for the duplicate guard, lazily built.
    //
    // **Only include configurations the reading task's classpath resolves.** The `doFirst` read
    // resolves every configuration in the map; one not on the task's classpath has no task
    // dependency, so a same-build producer (e.g. `:daemon:android`'s jar) may not have run and
    // Gradle intermittently refuses the read. Hence render tasks read [renderArtifactCoordinates]
    // and the daemon-descriptor task reads [daemonArtifactCoordinates].
    val renderArtifactCoordinates =
      AndroidPreviewClasspath.buildArtifactCoordinates(
        project = project,
        configurations = listOfNotNull(rendererConfig, testConfig, screenshotTestRuntimeConfig),
      )
    // The descriptor task resolves `daemonRendererConfig` (a superset of `rendererConfig`), making
    // `:daemon:android`'s jar its dependency.
    val daemonArtifactCoordinates =
      AndroidPreviewClasspath.buildArtifactCoordinates(
        project = project,
        configurations =
          listOfNotNull(daemonRendererConfig, testConfig, screenshotTestRuntimeConfig),
      )
    // Bound late for the same reason: AGP's test classes, JVM args and launcher must reach our
    // tasks even when configured first.
    val lateAgpTestTask = LateAgpTestTask(project, unitTestTaskName)
    val lateAgpClasspathExtras =
      AndroidPreviewClasspath.lateAgpClasspathExtras(
        project,
        unitTestTaskName,
        testConfig,
        legacyClasspathUnion,
      )
    val resolvedClasspath =
      AndroidPreviewClasspath.buildTestClasspath(
        project = project,
        bootClasspath = bootClasspath,
        bootClasspathFallback = bootClasspathFallback,
        rendererConfig = rendererConfig,
        rendererClasspathEntries = rendererClasspathEntries,
        sourceClassDirs = sourceClassDirs,
        testConfig = testConfig,
        screenshotTestRuntimeConfig = screenshotTestRuntimeConfig,
        unitTestConfigDir = unitTestConfigDir,
        robolectricPropertiesDir = generateRobolectricPropertiesTask.flatMap { it.outputDir },
        legacyClasspathUnion = legacyClasspathUnion,
      ) + lateAgpClasspathExtras

    val manifestFile = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
    val rendersDirectory = previewOutputDir.map { it.dir("renders") }
    val dataProductsDirectory = previewOutputDir.map { it.dir("data") }
    val rendersDir = rendersDirectory.map { it.asFile.absolutePath }

    // Resolve the optional `xr-composite` tool at configuration time (no `project.*` in the
    // action). Binary, in order: `composePreview.xrCompositeBinary`, `XR_COMPOSITE_BIN`, or the
    // shared auto-provision cache the CLI populates ([xrCompositeCacheBinaryPath]; the plugin never
    // downloads). Materials default to `<binaryDir>/materials`. Every tier may be absent; the task
    // then logs and skips.
    val xrCompositeCachePath =
      xrCompositeCacheBinaryPath(
        version = XrFakeVersions.composite,
        xdgCacheHome = project.providers.environmentVariable("XDG_CACHE_HOME"),
        userHome = project.providers.systemProperty("user.home"),
        osName = project.providers.systemProperty("os.name"),
        osArch = project.providers.systemProperty("os.arch"),
      )
    val xrCompositeBinary =
      project.providers
        .gradleProperty("composePreview.xrCompositeBinary")
        .orElse(project.providers.environmentVariable("XR_COMPOSITE_BIN"))
        .orElse(xrCompositeCachePath)
    val xrCompositeMaterials =
      project.providers
        .gradleProperty("composePreview.xrCompositeMaterials")
        .orElse(
          xrCompositeBinary.map {
            java.io.File(it).absoluteFile.parentFile.resolve("materials").path
          }
        )

    // ATF / hierarchy data products come only from the daemon, so no a11y outputs are declared.

    val shardCount =
      resolveShardCount(project, extension, previewOutputDir.get().file("previews.json").asFile)
    val shardsEnabled = shardCount > 1

    // When sharded, generate N subclasses of RobolectricRenderTestBase, each loading one manifest
    // slice: Gradle distributes forks per class. Each resolves config from the package-level
    // `robolectric.properties`, so every fork reuses its cached sandbox.
    val shardSourcesDir =
      project.layout.buildDirectory.dir("generated/composeai/render-shards/java")
    val shardClassesDir =
      project.layout.buildDirectory.dir("generated/composeai/render-shards/classes")

    val generateShardsTask =
      if (shardsEnabled) {
        project.tasks.register(
          "composePreviewGenerateRenderShards",
          GenerateRenderShardsTask::class.java,
        ) {
          group = "compose preview"
          description = "Generate $shardCount RobolectricRenderTest_Shard subclasses"
          shards.set(shardCount)
          outputDir.set(shardSourcesDir)
        }
      } else null

    val compileShardsTask =
      if (generateShardsTask != null) {
        project.tasks.register("composePreviewCompileRenderShards", JavaCompile::class.java) {
          group = "compose preview"
          description = "Compile generated shard test subclasses"
          source(generateShardsTask.map { it.outputDir.asFileTree })
          classpath = resolvedClasspath
          destinationDirectory.set(shardClassesDir)
          // Release 17: javac 17 (AGP's floor) rejects `--release 21`, and the trivial shards need
          // nothing newer.
          options.release.set(17)
          dependsOn(generateShardsTask)
          validateComposeFloorTask?.let { dependsOn(it) }
          // Reads AGP's unit-test-config dir via `resolvedClasspath` (see unitTestConfigProducer).
          dependsOn(unitTestConfigProducer)
          // The screenshotTest classes dir is on the render classpath too; same strict-validation
          // requirement.
          if (screenshotTestEnabled) {
            dependsOn(project.tasks.matching { it.name in screenshotCompileTaskNames })
          }
          if (useLocalRenderer) {
            dependsOn(":renderer-android:compile${capVariant}Kotlin")
          }
        }
      } else null

    // Render JVM selection: the subprocess must load the consumer's classes, but Kotlin can emit
    // bytecode newer than the toolchain JDK AGP's launcher follows, causing
    // `UnsupportedClassVersionError`. Pick the max of {inherited toolchain, Gradle JVM, detected
    // bytecode target}, overridable via `composePreview.renderJavaVersion`. See
    // [RenderJvmSelection]. Reused by the tasks below.
    val javaToolchains = project.extensions.getByType(JavaToolchainService::class.java)
    val gradleDaemonMajor = JavaVersion.current().majorVersion.toInt()
    val renderJavaOverride =
      project.providers.gradleProperty("composePreview.renderJavaVersion").orNull?.toIntOrNull()
        ?: extension.renderJavaVersion.orNull
    val renderBytecodeMajor = detectRenderBytecodeMajor(project, mainCompileTaskNames)
    fun renderJavaLauncher(agpTestTask: Test?): Provider<JavaLauncher>? =
      RenderJvmSelection.launcherFor(
        toolchains = javaToolchains,
        inherited = agpTestTask?.javaLauncher,
        gradleDaemonMajor = gradleDaemonMajor,
        bytecodeMajor = renderBytecodeMajor,
        explicitOverride = renderJavaOverride,
      )

    val renderTask =
      project.tasks.register("composePreviewRender", RobolectricRenderTask::class.java) {
        group = "compose preview"
        description = "Render Android previews via Robolectric"
        // Preview filters (#2066 / #2966 / #2977) from the same properties as desktop; this task's
        // CLI options override them. Forwarded as `composeai.preview.*` system properties for
        // `PreviewFilter`, including the row filter since this backend expands `@PreviewParameter`
        // itself.
        previewFilters.convention(ComposePreviewTasks.previewFilterProperty(project))
        previewIdFilters.convention(ComposePreviewTasks.previewIdFilterProperty(project))
        previewIdExcludes.convention(ComposePreviewTasks.previewIdExcludeProperty(project))
        previewRowExcludes.convention(ComposePreviewTasks.previewRowExcludeProperty(project))
        permutations.convention(ComposePreviewTasks.previewPermutationsProperty(project))
        jvmArgumentProviders.add(
          PreviewFilterSystemPropsProvider(
            nameFilters = previewFilters,
            idFilters = previewIdFilters,
            idFilterFile = ComposePreviewTasks.previewIdFilterFileProperty(project),
            idExcludes = previewIdExcludes,
            idExcludeFile = ComposePreviewTasks.previewIdExcludeFileProperty(project),
            rowExcludes = previewRowExcludes,
            permutations = permutations,
          )
        )
        // Fail fast when tooling isn't actually reachable (#1549); null when direct tooling was
        // found.
        validatePreviewToolingPresentTask?.let { dependsOn(it) }
        validateComposeFloorTask?.let { dependsOn(it) }
        // Reads AGP's unit-test-config dir via `resolvedClasspath` (see unitTestConfigProducer).
        dependsOn(unitTestConfigProducer)
        if (screenshotTestEnabled) {
          dependsOn(project.tasks.matching { it.name in screenshotCompileTaskNames })
        }
        testClassesDirs =
          if (compileShardsTask != null) {
            rendererClassDirs +
              project.files(compileShardsTask.map { it.destinationDirectory }) +
              lateAgpTestTask.testClassesDirs
          } else {
            rendererClassDirs + lateAgpTestTask.testClassesDirs
          }
        // Append AGP's `test${Cap}UnitTest` classpath extras at the end, for files only it has —
        // notably a library module's unit-test merged R.jar, added as a raw file without
        // `artifactType=jar` so our artifact view drops it (#136). `buildAgpClasspathExtras`
        // subtracts module artifacts, which appended raw caused duplicate versions (#2731).
        val agpTestClasspath =
          AndroidPreviewClasspath.buildAgpClasspathExtras(
            project = project,
            agpTestClasspath = lateAgpTestTask.classpath,
            testConfig = testConfig,
            legacyClasspathUnion = legacyClasspathUnion,
          )
        classpath =
          (if (compileShardsTask != null) {
            resolvedClasspath +
              project.files(compileShardsTask.map { it.destinationDirectory }) +
              lateAgpTestTask.testClassesDirs +
              agpTestClasspath
          } else {
            resolvedClasspath + lateAgpTestTask.testClassesDirs + agpTestClasspath
          }) + poolingContainerRFiles
        if (shardsEnabled) {
          include("**/RobolectricRenderTest_Shard*.class")
          maxParallelForks = shardCount
        } else {
          include("**/RobolectricRenderTest.class")
        }
        // The app-tour lane is its own class and package because Robolectric resolves the
        // Application per test class. Never sharded: few previews, and sharding multiplies the
        // Application init. `PreviewManifestLoader.Lane` keeps selections disjoint.
        include("**/AppTourRobolectricRenderTest.class")
        useJUnit()

        // See [configureRenderTaskReporting]: a non-UTF-8 locale otherwise fails renders with
        // em-dashed names.
        configureRenderTaskReporting(this)

        // AGP registers its test task after onVariants, so [LateAgpTestTask] applies its JVM args
        // and launcher once it exists. Static flags are in [AndroidPreviewClasspath.buildJvmArgs],
        // shared with the daemon.
        jvmArgs(AndroidPreviewClasspath.buildJvmArgs())

        // Fork on AGP's unit-test launcher unless the bytecode needs a newer JDK
        // ([renderJavaLauncher]). Inheriting avoids the PATH `java` default
        // (`ClassNotFoundException: android.app.Application`, #142); raising avoids
        // `UnsupportedClassVersionError`.
        lateAgpTestTask.inheritJvmSettings(this, ::renderJavaLauncher)

        // GoogleFont cache in `$XDG_CACHE_HOME/composeai/fonts` (else `~/.cache/composeai/fonts`);
        // the renderer no-ops without it.
        val fontsCacheDir = composeAiFontsCacheDir(project)
        // `-PcomposePreview.fontsOffline=true` skips network on cache miss and renders the fallback
        // font.
        val fontsOffline =
          project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
        val svgEmbedFonts = composeAiSvgEmbedFonts(project)
        // The following are read in the forked render JVM, so they must be forwarded. Figma-svg
        // background injection; off by default.
        val svgBackground = composeAiSvgBackground(project)
        // Whether an unresolved downloadable font fails its preview (default) or only warns.
        val fontsFailOnFallback = composeAiFontsFailOnFallback(project)
        // Host activity theme; only library modules need it.
        val hostTheme = composeAiHostTheme(project, extension)
        // Pinned wall clock (default `10:10`).
        val fixedTime = composeAiFixedTime(project, extension)
        // Link-buffer composer flag.
        val linkBufferComposer = composeAiLinkBufferComposer(project, extension)
        // Remote Compose player: `androidx-embedded` (default) or `androidx-view`.
        val rcPlayer = composeAiRcPlayer(project)
        // Whether Remote Compose captures bake density / font scale (`fixed`) or defer them
        // (`host`).
        val rcDensity = composeAiRcDensity(project)
        // Static system properties live in [AndroidPreviewClasspath.buildSystemProperties], shared
        // with the daemon; dynamic ones use lazy providers below.
        AndroidPreviewClasspath.buildSystemProperties(
            manifestPath = manifestFile.get(),
            rendersDir = rendersDir.get(),
            fontsCacheDir = fontsCacheDir,
            fontsOffline = fontsOffline.get(),
            svgEmbedFonts = svgEmbedFonts.get(),
            svgBackground = svgBackground.get(),
            fontsFailOnFallback = fontsFailOnFallback.get(),
            hostTheme = hostTheme.get(),
            fixedTime = fixedTime.get(),
            linkBufferComposer = linkBufferComposer.get(),
            rcPlayer = rcPlayer.get(),
            rcDensity = rcDensity.get(),
          )
          .forEach { (k, v) -> systemProperty(k, v) }

        // No a11y providers: the daemon is the only a11y producer. Display filters use a lazy input
        // so toggling them doesn't invalidate the configuration cache.
        jvmArgumentProviders.add(
          DisplayFilterSystemPropsProvider(filters = resolveDisplayFilterFilters(project))
        )
        // Device frame: composites each PNG into device-art when set.
        jvmArgumentProviders.add(
          DeviceFrameSystemPropsProvider(device = resolveDeviceFrameDevice(project))
        )
        // Prefetch device art in the Gradle JVM (never on the render classpath). Captures only the
        // provider.
        val deviceFrameForPrefetch = resolveDeviceFrameDevice(project)
        doFirst {
          val selection = deviceFrameForPrefetch.get()
          if (selection.isNotBlank()) {
            DeviceArtPrefetch.prefetchInto(
              cacheDir = DeviceArtPrefetch.defaultCacheDir(),
              artIds = DeviceArtPrefetch.artIdsFor(selection),
              logger = logger,
            )
          }
        }
        // Render tier via a lazy input so VS Code can pass `tier=fast` per save without
        // reconfiguring; read by [PreviewManifestLoader.loadShard] to drop heavy captures.
        val tierProvider = resolveTier(project)
        jvmArgumentProviders.add(TierSystemPropProvider(tier = tierProvider))
        // Don't cache partial runs: a `tier=fast` or filtered (#2977) run leaves other renders
        // stale in place, and caching that directory could wipe heavy outputs or restore stale
        // ones. Up-to-date checks still apply. Mirrors desktop's `RenderPreviewsTask.cacheIf`.
        outputs.cacheIf("composePreviewRender caches full, unfiltered runs only") {
          tierProvider.get().equals("full", ignoreCase = true) &&
            previewFilters.getOrElse(emptyList()).none { it.isNotBlank() } &&
            previewIdFilters.getOrElse(emptyList()).none { it.isNotBlank() } &&
            previewIdExcludes.getOrElse(emptyList()).none { it.isNotBlank() } &&
            previewRowExcludes.getOrElse(emptyList()).none { it.isNotBlank() } &&
            !PreviewPermutations.expandsAccessibility(permutations.getOrElse(emptyList()))
        }
        // PNGs are written via a system property, not a Gradle output, so declare the directory or
        // cache hits restore nothing.
        outputs.dir(rendersDirectory).withPropertyName("rendersDir")
        // Data products (e.g. scrolling LONG/GIF) live under `data/`; declare them for cache
        // restore too.
        outputs.dir(dataProductsDirectory).withPropertyName("dataProductsDir")

        // Fail fast if android.jar isn't on the classpath, instead of Robolectric's opaque
        // `NoClassDefFoundError: android/app/Application` (#1243).
        doFirst {
          AndroidPreviewClasspath.validateApplicationOnClasspath(classpath.files)
          // Duplicate-jar backstop ([RenderClasspathDuplicates]);
          // `-PcomposePreview.classpathDuplicates=fail` makes it fatal.
          RenderClasspathDuplicates.check(
            this,
            classpath.files,
            classpathDuplicatesMode,
            renderArtifactCoordinates.get(),
          )
        }

        dependsOn(discoverTask)
        dependsOn(generateRobolectricPropertiesTask)
        if (useLocalRenderer) {
          dependsOn(":renderer-android:compile${capVariant}Kotlin")
        }
        if (screenshotTestEnabled) {
          dependsOn("compile${capVariant}ScreenshotTestKotlin")
        }
        // `process${Cap}Resources` exists only on application variants; the resource APK comes via
        // `generate${Cap}UnitTestConfig` anyway, so skip it when absent (#136).
        dependsOn(
          project.tasks.matching {
            it.name in
              listOf("process${capVariant}Resources", "generate${capVariant}UnitTestConfig")
          }
        )
        if (compileShardsTask != null) {
          dependsOn(compileShardsTask)
        }
      }

    // The JDK-aware Robolectric SDK ceiling must use the JVM the render actually forks into
    // ([renderJavaLauncher]), not `JavaVersion.current()`. Derived from AGP's launcher / toolchain
    // rather than the render task to avoid a cycle. The SDK matrix overrides it.
    generateRobolectricPropertiesTask.configure {
      fun setFrom(launcher: Provider<JavaLauncher>?) {
        if (launcher != null) {
          buildJavaMajor.set(launcher.map { it.metadata.languageVersion.asInt() })
        } else {
          buildJavaMajor.set(gradleDaemonMajor)
        }
      }
      // Late-bound like the render task's own launcher, so the two cannot disagree.
      if (!lateAgpTestTask.whenAvailable { setFrom(renderJavaLauncher(it)) }) {
        setFrom(renderJavaLauncher(null))
      }
    }

    if (extension.resourcePreviews.enabled.get()) {
      // Resource render: same Robolectric harness, different test class and manifest. A separate
      // task so resource and composable rendering can run independently. The output dir is the
      // shared `renders/` parent because `renderOutput` paths start with `renders/resources/`; the
      // declared output is the narrower subtree.
      val resourcesManifestPath = previewOutputDir.map {
        it.file("resources.json").asFile.absolutePath
      }
      val resourcesRendersOutputDir = rendersDir
      val resourcesRendersSubtree = previewOutputDir.map { it.dir("renders/resources") }

      project.tasks.register("composePreviewRenderAndroidResources", Test::class.java) {
        group = "compose preview"
        description = "Render Android XML resource previews via Robolectric"
        testClassesDirs = rendererClassDirs + lateAgpTestTask.testClassesDirs
        // AGP-only extras, as for composePreviewRender.
        val agpTestClasspath =
          AndroidPreviewClasspath.buildAgpClasspathExtras(
            project = project,
            agpTestClasspath = lateAgpTestTask.classpath,
            testConfig = testConfig,
            legacyClasspathUnion = legacyClasspathUnion,
          )
        classpath =
          resolvedClasspath +
            lateAgpTestTask.testClassesDirs +
            agpTestClasspath +
            poolingContainerRFiles
        include("**/ResourcePreviewRenderTest.class")
        useJUnit()
        validateComposeFloorTask?.let { dependsOn(it) }
        // Reads the unit-test-config dir, so it needs the producer dependency too (see
        // `unitTestConfigProducer`).
        dependsOn(unitTestConfigProducer)
        // Same locale exposure as the main render task — resource names reach the report path too.
        configureRenderTaskReporting(this)

        jvmArgs(AndroidPreviewClasspath.buildJvmArgs())
        lateAgpTestTask.inheritJvmSettings(this, ::renderJavaLauncher)

        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.looperMode", "PAUSED")
        systemProperty("robolectric.conscryptMode", "OFF")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("composeai.resources.manifest", resourcesManifestPath.get())
        systemProperty("composeai.resources.outputDir", resourcesRendersOutputDir.get())

        // No preview filters (#2977): resource previews have no `@Preview`, and the shared
        // `resource-render-errors.json` would lose diagnostics on a partial run.

        outputs.dir(resourcesRendersSubtree).withPropertyName("resourcesRendersDir")

        // Same #1243 guard.
        doFirst {
          AndroidPreviewClasspath.validateApplicationOnClasspath(classpath.files)
          // Duplicate-jar backstop.
          RenderClasspathDuplicates.check(
            this,
            classpath.files,
            classpathDuplicatesMode,
            renderArtifactCoordinates.get(),
          )
        }

        dependsOn("composePreviewDiscoverAndroidResources")
        dependsOn(generateRobolectricPropertiesTask)
        if (useLocalRenderer) {
          dependsOn(":renderer-android:compile${capVariant}Kotlin")
        }
        dependsOn(
          project.tasks.matching {
            it.name in
              listOf("process${capVariant}Resources", "generate${capVariant}UnitTestConfig")
          }
        )
      }
    }

    // XR subspace render: `XrSubspaceRenderTest` on the same classpaths, reading the same
    // `previews.json` (filtered to `XR_SUBSPACE`) and writing `scene.json` per preview. Registered
    // only when XR is enabled.
    //
    // Filter providers are read here so the `cacheIf` spec doesn't capture `project`, which the
    // configuration cache can't serialise — that failed every build, not just XR.
    val xrFilterPatterns = ComposePreviewTasks.previewFilterProperty(project)
    val xrIdFilterPatterns = ComposePreviewTasks.previewIdFilterProperty(project)
    val xrIdExcludePatterns = ComposePreviewTasks.previewIdExcludeProperty(project)
    val xrRowExcludePatterns = ComposePreviewTasks.previewRowExcludeProperty(project)
    if (xrPreviewsEnabled)
      project.tasks.register("composePreviewRenderXr", Test::class.java) {
        group = "compose preview"
        description = "Render XR subspace previews to scene.json via Robolectric"
        validateComposeFloorTask?.let { dependsOn(it) }
        testClassesDirs = xrRendererClassDirs + lateAgpTestTask.testClassesDirs
        // AGP-only extras.
        val agpTestClasspath =
          AndroidPreviewClasspath.buildAgpClasspathExtras(
            project = project,
            agpTestClasspath = lateAgpTestTask.classpath,
            testConfig = testConfig,
            legacyClasspathUnion = legacyClasspathUnion,
          )
        classpath =
          (resolvedClasspath +
              xrRendererClasspathEntries +
              lateAgpTestTask.testClassesDirs +
              agpTestClasspath +
              // Last, and unaffected by the scenecore filter below.
              poolingContainerRFiles)
            // Drop scenecore's on-device spatial backends: their static init references device-only
            // `XrExtensions`, and xr-compose's probe only catches `ClassNotFoundException`, not a
            // failing `<clinit>`. Absent, the probe falls back to `scenecore-testing`'s fake.
            // Matched per path segment so directories merely containing the name are kept.
            .filter { file ->
              file.toPath().none { segment ->
                SCENECORE_SPATIAL_BACKEND_SEGMENT.matches(segment.toString())
              }
            }
        include("**/XrSubspaceRenderTest.class")
        useJUnit()
        // Same locale exposure as the main render task — preview names reach the report path too.
        configureRenderTaskReporting(this)

        jvmArgs(AndroidPreviewClasspath.buildJvmArgs())
        lateAgpTestTask.inheritJvmSettings(this, ::renderJavaLauncher)

        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.looperMode", "PAUSED")
        systemProperty("robolectric.conscryptMode", "OFF")
        // Panel textures are written via roborazzi's `captureRoboImage`, which only writes when
        // enabled.
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("roborazzi.test.record", "true")
        systemProperty("composeai.render.manifest", manifestFile.get())
        systemProperty("composeai.render.outputDir", rendersDir.get())

        // Same name/id filters as the composable render (#2977); property conventions only.
        jvmArgumentProviders.add(
          PreviewFilterSystemPropsProvider(
            nameFilters = ComposePreviewTasks.previewFilterProperty(project),
            idFilters = ComposePreviewTasks.previewIdFilterProperty(project),
            idFilterFile = ComposePreviewTasks.previewIdFilterFileProperty(project),
            idExcludes = ComposePreviewTasks.previewIdExcludeProperty(project),
            idExcludeFile = ComposePreviewTasks.previewIdExcludeFileProperty(project),
            // Inert here: XR has no `@PreviewParameter` fan-out.
            rowExcludes = ComposePreviewTasks.previewRowExcludeProperty(project),
            permutations = project.providers.provider { emptyList() },
          )
        )
        // The row filter still gates caching: this task declares the whole shared
        // `rendersDirectory`, so a filtered run could store and later restore stale PNGs, just as
        // `composePreviewRender` refuses to.
        outputs.cacheIf("composePreviewRenderXr caches unfiltered runs only") {
          xrFilterPatterns.get().none { it.isNotBlank() } &&
            xrIdFilterPatterns.get().none { it.isNotBlank() } &&
            xrIdExcludePatterns.get().none { it.isNotBlank() } &&
            xrRowExcludePatterns.get().none { it.isNotBlank() }
        }

        outputs.dir(rendersDirectory).withPropertyName("xrRendersDir")

        // Same #1243 guard.
        doFirst {
          AndroidPreviewClasspath.validateApplicationOnClasspath(classpath.files)
          // Duplicate-jar backstop.
          RenderClasspathDuplicates.check(
            this,
            classpath.files,
            classpathDuplicatesMode,
            renderArtifactCoordinates.get(),
          )
        }

        dependsOn(discoverTask)
        dependsOn(generateRobolectricPropertiesTask)
        if (useLocalXrRenderer) {
          dependsOn(":renderer-xr:compile${capVariant}Kotlin")
        }
        dependsOn(
          project.tasks.matching {
            it.name in
              listOf("process${capVariant}Resources", "generate${capVariant}UnitTestConfig")
          }
        )
      }

    // Bake XR scenes into composite stills with `xr-composite`, after `composePreviewRenderXr` and
    // before renderAll's validation, so the composites count and survive `cleanStaleRenders`.
    // Degrades gracefully without a binary or display.
    if (xrPreviewsEnabled)
      project.tasks.register("composePreviewCompositeXr", org.gradle.api.DefaultTask::class.java) {
        group = "compose preview"
        description = "Bake XR subspace scene.json files into composite.png stills via xr-composite"
        // Providers only; the action never touches `project.*`.
        val binaryProvider = xrCompositeBinary
        val materialsProvider = xrCompositeMaterials
        val rendersDirProvider = rendersDirectory
        // The render dir is shared with two other writers, so it's untracked; order explicitly
        // instead. Best-effort native output gains nothing from caching.
        dependsOn("composePreviewRenderXr")
        mustRunAfter("composePreviewRender")
        doLast {
          val binaryPath = binaryProvider.orNull
          val rendersRoot = rendersDirProvider.get().asFile
          if (binaryPath.isNullOrBlank()) {
            logger.lifecycle("xr-composite binary not found; skipping XR composite stills")
            return@doLast
          }
          val binary = java.io.File(binaryPath)
          if (!binary.isFile) {
            logger.lifecycle(
              "xr-composite binary not found at $binaryPath; skipping XR composite stills"
            )
            return@doLast
          }
          if (!rendersRoot.isDirectory) {
            logger.lifecycle("no XR renders dir at $rendersRoot; skipping XR composite stills")
            return@doLast
          }
          // `xr-composite` needs a display: wrap in `xvfb-run -a` when `DISPLAY` is unset, else
          // skip.
          val hasDisplay = !System.getenv("DISPLAY").isNullOrBlank()
          val xvfbRun =
            if (hasDisplay) null
            else
              sequenceOf("/usr/bin/xvfb-run", "/usr/local/bin/xvfb-run")
                .map { java.io.File(it) }
                .firstOrNull { it.isFile }
                ?.path
                ?: run {
                  if (
                    ProcessBuilder("which", "xvfb-run")
                      .redirectErrorStream(true)
                      .start()
                      .waitFor() == 0
                  )
                    "xvfb-run"
                  else null
                }
          if (!hasDisplay && xvfbRun == null) {
            logger.lifecycle(
              "DISPLAY unset and xvfb-run not available; skipping XR composite stills"
            )
            return@doLast
          }
          val materialsDir = materialsProvider.orNull
          val scenes =
            rendersRoot
              .listFiles()
              ?.filter { it.isDirectory }
              ?.map { java.io.File(it, "scene.json") }
              ?.filter { it.isFile }
              .orEmpty()
          if (scenes.isEmpty()) {
            logger.lifecycle("no XR scene.json files under $rendersRoot; nothing to composite")
            return@doLast
          }
          var baked = 0
          for (scene in scenes) {
            val outFile = java.io.File(scene.parentFile, "composite.png")
            val cmd = mutableListOf<String>()
            if (xvfbRun != null) {
              cmd += xvfbRun
              cmd += "-a"
            }
            cmd += binary.absolutePath
            cmd += listOf("--scene", scene.absolutePath, "--out", outFile.absolutePath)
            if (!materialsDir.isNullOrBlank()) cmd += listOf("--materials", materialsDir)
            cmd += listOf("--width", "1280", "--height", "800")
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0 || !outFile.isFile) {
              logger.lifecycle(
                "xr-composite failed for ${scene.parentFile.name} (exit $code); skipping. Output:\n$output"
              )
            } else {
              baked++
            }
          }
          logger.lifecycle("xr-composite baked $baked of ${scenes.size} XR composite still(s)")
        }
      }

    // No `aggregateAccessibility` task: a11y is daemon-only, so there are no sidecars to roll up.

    // Lottie previews render through the JVM desktop Compottie path (Robolectric skips
    // `kind=LOTTIE`), via `DesktopRendererMain` on a `:renderer-desktop` classpath plus the
    // module's resource source dirs. Part of `composePreviewRenderAll`.
    val lottieRendererConfig =
      ComposePreviewTasks.ensureRendererDesktopConfig(project, "composePreviewLottieRenderer")
    val lottieRenderTask =
      project.tasks.register("composePreviewRenderLottie", RenderPreviewsTask::class.java) {
        group = "compose preview"
        description = "Render kind=LOTTIE previews via the desktop Compottie renderer"
        onlyIf { extension.enabled.get() }
        projectDirectory.set(project.layout.projectDirectory)
        previewsJson.set(previewOutputDir.map { it.file("previews.json") })
        renderBackend.set("desktop")
        tier.set(resolveTier(project))
        displayFilterFilters.set(resolveDisplayFilterFilters(project))
        linkBufferComposer.set(composeAiLinkBufferComposer(project, extension))
        deviceFrameDevice.set(resolveDeviceFrameDevice(project))
        // Same filters (#2977). The filter applies over the full manifest before `includeKinds`, so
        // a non-Lottie name doesn't fail fast while a typo still does.
        previewFilters.convention(ComposePreviewTasks.previewFilterProperty(project))
        previewIdFilters.convention(ComposePreviewTasks.previewIdFilterProperty(project))
        previewIdExcludes.convention(ComposePreviewTasks.previewIdExcludeProperty(project))
        includeKinds.add(PreviewKind.LOTTIE.name)
        // Artifact view keeps the classpath config-cache serializable (#1796).
        renderClasspath.from(lottieRendererConfig.incoming.artifactView {}.files)
        renderClasspath.from(androidLottieResourceDirs(project))
        // Matches discovery's `lottieRenderSubdir`, so both render tasks stay cacheable; the gate
        // resolves `renderOutput` relative to the compose-previews root.
        outputDir.set(previewOutputDir.map { it.dir(LOTTIE_RENDER_SUBDIR) })
        dataProductsDir.set(dataProductsDirectory)
        dependsOn(discoverTask)
      }

    // SVG assets render through the JVM desktop path too (Robolectric has no SVG decoder and skips
    // `kind=SVG`). Mirrors the Lottie pass: same classpath, disjoint `svg-renders/` output, part of
    // `composePreviewRenderAll`.
    val svgRendererConfig =
      ComposePreviewTasks.ensureRendererDesktopConfig(project, "composePreviewSvgRenderer")
    val svgRenderTask =
      project.tasks.register("composePreviewRenderSvg", RenderPreviewsTask::class.java) {
        group = "compose preview"
        description = "Render kind=SVG previews via the desktop Skia renderer"
        onlyIf { extension.enabled.get() }
        projectDirectory.set(project.layout.projectDirectory)
        previewsJson.set(previewOutputDir.map { it.file("previews.json") })
        renderBackend.set("desktop")
        tier.set(resolveTier(project))
        displayFilterFilters.set(resolveDisplayFilterFilters(project))
        linkBufferComposer.set(composeAiLinkBufferComposer(project, extension))
        deviceFrameDevice.set(resolveDeviceFrameDevice(project))
        // Same filters (#2977), then restricted to `kind=SVG`.
        previewFilters.convention(ComposePreviewTasks.previewFilterProperty(project))
        previewIdFilters.convention(ComposePreviewTasks.previewIdFilterProperty(project))
        previewIdExcludes.convention(ComposePreviewTasks.previewIdExcludeProperty(project))
        includeKinds.add(PreviewKind.SVG.name)
        renderClasspath.from(svgRendererConfig.incoming.artifactView {}.files)
        renderClasspath.from(androidLottieResourceDirs(project))
        outputDir.set(previewOutputDir.map { it.dir(SVG_RENDER_SUBDIR) })
        dataProductsDir.set(dataProductsDirectory)
        dependsOn(discoverTask)
      }

    ComposePreviewTasks.registerRenderAllPreviews(project, extension, renderTask, previewOutputDir)
    // Render Lottie stills before the missing-render gate.
    project.tasks.named("composePreviewRenderAll").configure { dependsOn(lottieRenderTask) }
    // Same for the JVM SVG render — a `kind=SVG` asset's PNG must exist before the gate validates.
    project.tasks.named("composePreviewRenderAll").configure { dependsOn(svgRenderTask) }
    // XR render + composite run before renderAll's validation/clean, so composites count and are
    // kept.
    if (xrPreviewsEnabled) {
      project.tasks.named("composePreviewRenderAll").configure {
        dependsOn("composePreviewRenderXr")
        dependsOn("composePreviewCompositeXr")
      }
    }

    // Portable bundle on the Android path, with the same class dirs and runtime classpath as
    // rendering; the `artifactType=jar` view turns AARs into classes.jar. `backendId = "android"`
    // is recorded in bundle.json.
    //
    // AAR deps are recorded as Maven coordinates, but typed `jar`, and Android-merged resources
    // aren't packed; only relevant to coordinate-mode re-rendering on an Android player.
    // `--embed-deps` sidesteps it.
    val bundleTask =
      ComposePreviewTasks.registerBundleTask(
        project = project,
        extension = extension,
        previewOutputDir = previewOutputDir,
        sourceClassDirs = sourceClassDirs,
        resolveDependencyConfigName = { dependencyConfigName },
        discoverTaskName = "composePreviewDiscover",
        backendId = "android",
        // (v6) Track the files `test_config.properties` points at (resource APK dir, merged
        // manifest), since the pack action reads them by absolute path; this re-packs when content
        // changes.
        androidUnitTestConfigFiles =
          project.files(
            unitTestConfigDir,
            project.layout.buildDirectory.dir(
              naming.apkForLocalTest ?: "intermediates/apk_for_local_test/${variantName}UnitTest"
            ),
            variant.artifacts.get(SingleArtifact.MERGED_MANIFEST),
          ),
        // Library R classes for tile replay exist only in the unit-test merged R.jar, which only
        // AGP's test task classpath carries (raw file dep, dropped by attribute-filtered views).
        // Read from the resolved task classpath like `composePreviewRender`, lazily once the task
        // exists.
        androidUnitTestRuntimeClasspath = { lateAgpTestTask.classpath },
      )

    // Asset IR from the Android resource source roots: AGP doesn't stage them into
    // `build/resources/main`.
    bundleTask.configure { moduleResourceRoots.from(androidLottieResourceDirs(project)) }
    if (xrPreviewsEnabled) {
      // `renderFiles` tracks the shared renders tree including XR scenes; order after both XR
      // writers.
      bundleTask.configure {
        mustRunAfter("composePreviewRenderXr")
        mustRunAfter("composePreviewCompositeXr")
      }
    }

    // The variant's own classes via the scoped artifact, as for `composePreviewDiscover` (#1924),
    // so `classes/app.jar` covers every preview discovery found (#1926). Also wires the producing
    // task.
    variant.artifacts
      .forScope(ScopedArtifacts.Scope.PROJECT)
      .use(bundleTask)
      .toGet(
        ScopedArtifact.CLASSES,
        BundlePreviewTask::projectClassJars,
        BundlePreviewTask::projectClassDirs,
      )

    // Preview daemon bootstrap descriptor. Registered unconditionally so VS Code can read it even
    // when `daemon.enabled = false` (see [DaemonClasspathDescriptor]). Inputs mirror
    // composePreviewRender so the daemon JVM matches. See `docs/daemon/DESIGN.md` § 4 / § 6.
    val daemonFontsCacheDir = composeAiFontsCacheDir(project)
    val daemonHistoryDir = composeAiHistoryDir(project)
    val daemonFontsOffline =
      project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
    val daemonSvgEmbedFonts = composeAiSvgEmbedFonts(project)
    val daemonSvgBackground = composeAiSvgBackground(project)
    val daemonFontsFailOnFallback = composeAiFontsFailOnFallback(project)
    // Daemon JVM settings mirror the one-shot render path, since the daemon reads them itself;
    // otherwise the VS Code / MCP / a11y routes would diverge from batch renders. Host theme:
    val daemonHostTheme = composeAiHostTheme(project, extension)
    // Pinned wall clock.
    val daemonFixedTime = composeAiFixedTime(project, extension)
    val daemonLinkBufferComposer = composeAiLinkBufferComposer(project, extension)
    // Remote Compose player.
    val daemonRcPlayer = composeAiRcPlayer(project)
    // Remote Compose capture density mode.
    val daemonRcDensity = composeAiRcDensity(project)
    // Resolved at configuration time: these feed `@Input` providers that mustn't capture `project`.
    // A new subproject is a settings edit, which is itself a cheap signal.
    val daemonCheapSignalFiles =
      collectCheapSignalFiles(project).joinToString(java.io.File.pathSeparator) { it.absolutePath }
    val consumerBuildDir = project.layout.buildDirectory.asFile.get().absolutePath
    val daemonUserClassMarkers =
      listOf(
        "$consumerBuildDir/intermediates/",
        "$consumerBuildDir/tmp/kotlin-classes/",
        "$consumerBuildDir/classes/",
      )
    project.tasks.register(
      "composePreviewDaemonStart",
      ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask::class.java,
    ) {
      validateDaemonComposeFloorTask?.let { dependsOn(it) }
      // Bound via [lateAgpTestTask], as for the render task.

      this.modulePath.set(project.path)
      this.variant.set(variantName)
      this.daemonEnabled.set(extension.daemon.enabled)
      this.maxHeapMb.set(extension.daemon.maxHeapMb)
      this.maxRendersPerSandbox.set(extension.daemon.maxRendersPerSandbox)
      this.warmSpare.set(extension.daemon.warmSpare)
      this.backgroundSandboxBoot.set(extension.daemon.backgroundSandboxBoot)
      // Stage-2 BTA: AGP's unit-test classpath carries everything `compileDebugKotlin` sees. Output
      // goes to the dir `compile<Variant>Kotlin` uses — the one `composeai.daemon.userClassDirs`
      // names ([AndroidVariantNaming.btaOutputDir]) — so the daemon's child classloader loads it.
      // MODULE_NAME is `project.name`, matching KGP's default for AGP variants.
      val agpTestClasspath = lateAgpTestTask.classpath
      ComposePreviewTasks.wireBtaInputs(
        project = project,
        task = this,
        userCompileClasspath = agpTestClasspath,
        moduleName = project.name,
        outputDirProvider =
          project.layout.buildDirectory
            .dir(
              naming.btaOutputDir(
                kotlinAndroidPluginApplied =
                  project.pluginManager.hasPlugin("org.jetbrains.kotlin.android")
              )
            )
            .map { it.asFile.absolutePath },
        icWorkingDirProvider =
          project.layout.buildDirectory.dir("compose-previews/daemon-state/bta-ic").map {
            it.asFile.absolutePath
          },
        ineligibilityReason = ComposePreviewTasks.detectStageTwoIneligibilityFor(project),
      )
      // See [DaemonBootstrapTask] / [DaemonClasspathDescriptor].
      this.mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Bake the render JVM into `daemon-launch.json` as composePreviewRender forks. Load-bearing:
      // when unset, the VS Code extension uses its own bundled JDK, which may be too old for the
      // classes.
      fun setLauncher(launcher: Provider<JavaLauncher>?) {
        launcher?.let { l ->
          this.javaLauncher.set(l.map { it.executablePath.asFile.absolutePath })
        }
      }
      if (!lateAgpTestTask.whenAvailable { setLauncher(renderJavaLauncher(it)) }) {
        setLauncher(renderJavaLauncher(null))
      }
      // One graph: `daemonRendererConfig` extends `rendererConfig` and `testConfig`, so one
      // resolution covers daemon, renderer and consumer with one version per module; daemon classes
      // come first so [mainClass] resolves. The other entries match the render task's; only
      // AGP-only extras are appended ([AndroidPreviewClasspath.buildAgpClasspathExtras]).
      this.classpath.from(
        AndroidPreviewClasspath.buildTestClasspath(
          project = project,
          bootClasspath = bootClasspath,
          bootClasspathFallback = bootClasspathFallback,
          rendererConfig = daemonRendererConfig,
          rendererClasspathEntries = rendererClasspathEntries,
          sourceClassDirs = sourceClassDirs,
          testConfig = testConfig,
          screenshotTestRuntimeConfig = screenshotTestRuntimeConfig,
          unitTestConfigDir = unitTestConfigDir,
          robolectricPropertiesDir = generateRobolectricPropertiesTask.flatMap { it.outputDir },
          legacyClasspathUnion = legacyClasspathUnion,
        )
      )
      this.classpath.from(lateAgpClasspathExtras)
      this.classpath.from(lateAgpTestTask.testClassesDirs)
      this.classpath.from(
        AndroidPreviewClasspath.buildAgpClasspathExtras(
          project = project,
          agpTestClasspath = lateAgpTestTask.classpath,
          testConfig = testConfig,
          legacyClasspathUnion = legacyClasspathUnion,
        )
      )
      // Duplicate guard on the daemon classpath, which a11y and VS Code actually use. Runs before
      // the descriptor is written so `fail` never emits one. Reads the daemon-scoped coordinate map
      // (see [renderArtifactCoordinates]).
      doFirst {
        RenderClasspathDuplicates.check(
          this,
          classpath.files,
          classpathDuplicatesMode,
          daemonArtifactCoordinates.get(),
        )
      }
      // AGP's test jvmArgs aren't inherited; they're test-runner specific.
      this.jvmArgs.addAll(AndroidPreviewClasspath.buildJvmArgs())
      this.jvmArgs.add(extension.daemon.maxHeapMb.map { "-Xmx${it}m" })
      // Same system properties as the render task, plus daemon keys from [DaemonExtension]. Per-key
      // `put` so each provider captures only serialisable references; one map-building lambda would
      // capture `project` and fail the configuration cache.
      this.systemProperties.put("robolectric.graphicsMode", "NATIVE")
      this.systemProperties.put("robolectric.looperMode", "PAUSED")
      this.systemProperties.put("robolectric.conscryptMode", "OFF")
      this.systemProperties.put("robolectric.pixelCopyRenderMode", "hardware")
      this.systemProperties.put("roborazzi.test.record", "true")
      this.systemProperties.put("composeai.render.manifest", manifestFile)
      this.systemProperties.put("composeai.render.outputDir", rendersDir)
      this.systemProperties.put("composeai.fonts.cacheDir", daemonFontsCacheDir)
      this.systemProperties.put("composeai.fonts.offline", daemonFontsOffline)
      this.systemProperties.put("composeai.fonts.failOnFallback", daemonFontsFailOnFallback)
      this.systemProperties.put("composeai.svg.embedFonts", daemonSvgEmbedFonts)
      this.systemProperties.put("composeai.svg.background", daemonSvgBackground)
      this.systemProperties.put("composeai.render.hostTheme", daemonHostTheme)
      this.systemProperties.put("composeai.render.fixedTime", daemonFixedTime)
      this.systemProperties.put("composeai.render.linkBufferComposer", daemonLinkBufferComposer)
      this.systemProperties.put("composeai.render.rcPlayer", daemonRcPlayer)
      this.systemProperties.put("composeai.render.rcDensity", daemonRcDensity)
      this.systemProperties.put("composeai.daemon.protocolVersion", "1")
      this.systemProperties.put("composeai.daemon.idleTimeoutMs", "5000")
      this.systemProperties.put(
        "composeai.daemon.maxHeapMb",
        extension.daemon.maxHeapMb.map { it.toString() },
      )
      this.systemProperties.put(
        "composeai.daemon.maxRendersPerSandbox",
        extension.daemon.maxRendersPerSandbox.map { it.toString() },
      )
      this.systemProperties.put(
        "composeai.daemon.warmSpare",
        extension.daemon.warmSpare.map { it.toString() },
      )
      this.systemProperties.put(
        "composeai.daemon.backgroundSandboxBoot",
        extension.daemon.backgroundSandboxBoot.map { it.toString() },
      )
      // Mirrors the generated `application=android.app.Application`; otherwise the daemon uses the
      // manifest Application.
      this.systemProperties.put(
        "composeai.daemon.useConsumerApplication",
        extension.useConsumerApplication.map { it.toString() },
      )
      this.systemProperties.put(
        "composeai.daemon.perfettoTrace",
        resolveComposeAiTraceEnabled(project, extension).map { it.toString() },
      )
      this.systemProperties.put("composeai.daemon.modulePath", project.path)
      this.systemProperties.put(
        "composeai.daemon.moduleProjectDir",
        project.layout.projectDirectory.asFile.absolutePath,
      )
      // The closure captures only a configuration-time list; `classpath.elements` is serialised
      // with the task inputs.
      this.systemProperties.put(
        "composeai.daemon.userClassDirs",
        this.classpath.elements.map { elements ->
          elements
            .map { it.asFile.absolutePath }
            .filter { entry -> daemonUserClassMarkers.any { marker -> entry.startsWith(marker) } }
            .joinToString(java.io.File.pathSeparator)
        },
      )
      this.systemProperties.put("composeai.daemon.cheapSignalFiles", daemonCheapSignalFiles)
      // B2.2 phase 1 — `composeai.daemon.previewsJsonPath`. Same path as the composePreviewRender
      // manifest, surfaced via a separate sysprop so the daemon-side loader doesn't have to
      // know about the renderer-shared key.
      this.systemProperties.put("composeai.daemon.previewsJsonPath", manifestFile)
      this.systemProperties.put("composeai.daemon.resDirs", daemonResDirs)
      // Lets the daemon's `PreviewManifestRouter` map a `previewId` to a `RenderSpec`; without it
      // renders fall back to a stub (#314). The "harness" prefix is historical.
      this.systemProperties.put("composeai.harness.previewsManifest", manifestFile)
      // Enables daemon history recording, under the user-level cache root ([composeAiHistoryDir]).
      this.systemProperties.put("composeai.daemon.historyDir", daemonHistoryDir)
      this.systemProperties.put("composeai.daemon.workspaceRoot", project.rootDir.absolutePath)
      this.workingDirectory.set(project.projectDir.absolutePath)
      this.manifestPath.set(manifestFile)
      // Invalidates the descriptor (triggering VS Code's respawn) when the manifest appears or
      // changes. A null-returning provider leaves the property unset when the file is absent, which
      // is what `@Optional` needs.
      this.previewsManifest.fileProvider(
        previewOutputDir.flatMap { dir ->
          project.providers.provider {
            val f = dir.file("previews.json").asFile
            if (f.isFile) f else null
          }
        }
      )
      // The provider carries no build dependency, which strict validation rejects. `mustRunAfter`
      // rather than `dependsOn`: the daemon must be warmable before any discovery.
      mustRunAfter(discoverTask)
      this.outputFile.set(previewOutputDir.map { it.file("daemon-launch.json") })
    }
  }

  /**
   * Forwards `composePreview.displayFilter.filters` as `composeai.displayfilter.filters`. A
   * `CommandLineArgumentProvider` resolves at execution time, so toggling doesn't invalidate the
   * configuration cache. Blank means disabled.
   */
  internal class DisplayFilterSystemPropsProvider(
    @get:org.gradle.api.tasks.Input val filters: org.gradle.api.provider.Provider<String>
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
      listOf("-Dcomposeai.displayfilter.filters=${filters.get()}")
  }

  /**
   * `-PcomposePreview.displayFilter.filters`, default empty (off). A Provider so it can feed lazy
   * inputs.
   */
  internal fun resolveDisplayFilterFilters(
    project: org.gradle.api.Project
  ): org.gradle.api.provider.Provider<String> =
    project.providers.gradleProperty("composePreview.displayFilter.filters").orElse("")

  /**
   * Forwards `composePreview.deviceFrame.device` as `composeai.deviceframe.device`, like
   * [DisplayFilterSystemPropsProvider]. Blank means disabled.
   */
  internal class DeviceFrameSystemPropsProvider(
    @get:org.gradle.api.tasks.Input val device: org.gradle.api.provider.Provider<String>
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
      val d = device.get()
      if (d.isBlank()) return listOf("-Dcomposeai.deviceframe.device=")
      // The cache the doFirst prefetch fills (network clients can't run on the render classpath).
      return listOf(
        "-Dcomposeai.deviceframe.device=$d",
        "-Dcomposeai.deviceframe.cacheDir=${DeviceArtPrefetch.defaultCacheDir().absolutePath}",
      )
    }
  }

  /**
   * `-PcomposePreview.deviceFrame.device`: `auto`, a Device Art Generator id, or empty (default,
   * off).
   */
  internal fun resolveDeviceFrameDevice(
    project: org.gradle.api.Project
  ): org.gradle.api.provider.Provider<String> =
    project.providers.gradleProperty("composePreview.deviceFrame.device").orElse("")

  /**
   * Java-resource source roots for Lottie assets in an Android module (classic `src/main/resources`
   * and KMP layouts), also linked onto the Lottie render classpath. Missing dirs contribute
   * nothing.
   */
  internal fun androidLottieResourceDirs(
    project: org.gradle.api.Project
  ): org.gradle.api.file.FileCollection =
    project.files(
      project.layout.projectDirectory.dir("src/main/resources"),
      project.layout.projectDirectory.dir("src/commonMain/resources"),
      project.layout.projectDirectory.dir("src/androidMain/resources"),
    )

  private fun parseCheckList(raw: String): Set<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

  internal fun resolveComposeAiTraceEnabled(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): org.gradle.api.provider.Provider<Boolean> {
    val typed = extension.previewExtensions.composeAiTrace
    // Safe eagerly: `PreviewExtensionsExtension` pre-registers the built-in ids, and the build
    // script's `extension(...)` reaches the same instance. A lazy wrap captured `project`, which
    // the configuration cache rejects.
    val generic = extension.previewExtensions.extensions.findByName("composeAiTrace")
    val genericAllChecks =
      generic?.allChecksEnabledProvider ?: project.providers.provider<Boolean> { false }
    val configuredAllChecks =
      typed.allChecksEnabledProvider.zip(genericAllChecks) { typedEnabled, genericEnabled ->
        typedEnabled || genericEnabled
      }
    val genericChecks =
      generic?.checks
        ?: project.objects.listProperty(String::class.java).convention(emptyList<String>())
    val wholeExtension =
      project.providers
        .gradleProperty("composePreview.previewExtensions.composeAiTrace.enableAllChecks")
        .map { it.toBooleanStrictOrNull() ?: false }
        .orElse(configuredAllChecks)
    val selectedChecks =
      project.providers
        .gradleProperty("composePreview.previewExtensions.composeAiTrace.checks")
        .map { raw -> parseCheckList(raw).any { it in COMPOSE_AI_TRACE_CHECK_IDS } }
        .orElse(
          typed.checks.zip(genericChecks) { typedChecks, genericChecks ->
            (typedChecks + genericChecks).any { it in COMPOSE_AI_TRACE_CHECK_IDS }
          }
        )
    return wholeExtension.zip(selectedChecks) { whole, selected -> whole || selected }
  }

  private val COMPOSE_AI_TRACE_CHECK_IDS =
    setOf("trace", "perfetto", "perfettoTrace", "composeAiTrace", "render/composeAiTrace")

  /**
   * `-PcomposePreview.tier=<fast|full>`: `fast` skips heavy captures; `full` (default) renders
   * everything. A Provider so VS Code's per-save toggle doesn't invalidate the configuration cache.
   */
  internal fun resolveTier(
    project: org.gradle.api.Project
  ): org.gradle.api.provider.Provider<String> =
    project.providers
      .gradleProperty("composePreview.tier")
      .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
      .orElse("full")

  /** Lazy render-tier system property for `composePreviewRender`. */
  internal class TierSystemPropProvider(
    @get:org.gradle.api.tasks.Input val tier: org.gradle.api.provider.Provider<String>
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dcomposeai.render.tier=${tier.get()}")
  }

  /**
   * Forwards the preview selection as the `composeai.preview.*` properties `PreviewFilter` reads
   * (#2977), via lazy inputs; an empty list emits nothing. The property names duplicate
   * `PreviewFilter`'s constants (no module dependency) and must stay in sync.
   */
  internal class PreviewFilterSystemPropsProvider(
    @get:org.gradle.api.tasks.Input val nameFilters: org.gradle.api.provider.Provider<List<String>>,
    @get:org.gradle.api.tasks.Input val idFilters: org.gradle.api.provider.Provider<List<String>>,
    /**
     * Path of the newline-delimited id-filter file (#5172). `@Internal`: [idFilters] carries its
     * resolved lines, which drive up-to-date checks.
     */
    @get:org.gradle.api.tasks.Internal val idFilterFile: org.gradle.api.provider.Provider<String>,
    @get:org.gradle.api.tasks.Input val idExcludes: org.gradle.api.provider.Provider<List<String>>,
    /**
     * Path of the exclusion file. `@Internal` for the same reason, so a moved workspace doesn't
     * invalidate the render.
     */
    @get:org.gradle.api.tasks.Internal val idExcludeFile: org.gradle.api.provider.Provider<String>,
    @get:org.gradle.api.tasks.Input val rowExcludes: org.gradle.api.provider.Provider<List<String>>,
    @get:org.gradle.api.tasks.Input
    val permutations: org.gradle.api.provider.Provider<List<String>>,
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = buildList {
      arg("composeai.preview.filter", nameFilters.getOrElse(emptyList()))
      // Pass the filter file's path rather than its contents: ids may contain commas, and arguments
      // are encoded with the daemon's `sun.jnu.encoding`, mangling non-ASCII on a POSIX locale
      // (#5172). Only when the list still came from the file, so an explicit `--preview-id` wins
      // over a stale property.
      val filterFile = idFilterFile.orNull?.trim().orEmpty()
      val resolvedFilters =
        idFilters.getOrElse(emptyList()).map(String::trim).filter(String::isNotEmpty)
      val filtersFromFile =
        java.io
          .File(filterFile)
          .takeIf { filterFile.isNotEmpty() && it.isFile }
          ?.readLines(Charsets.UTF_8)
          ?.map(String::trim)
          ?.filter(String::isNotEmpty)
      if (filtersFromFile != null && filtersFromFile == resolvedFilters) {
        add("-Dcomposeai.preview.idFilterFile=${java.io.File(filterFile).absolutePath}")
      } else {
        arg("composeai.preview.idFilter", resolvedFilters)
      }
      // Likewise the exclude file wins when it's the source (re-joining would split ids on commas);
      // comparing resolved lists detects an explicit `--exclude-preview-id` override.
      val excludeFile = idExcludeFile.orNull?.trim().orEmpty()
      val resolved = idExcludes.getOrElse(emptyList()).map(String::trim).filter(String::isNotEmpty)
      val fromFile =
        java.io
          .File(excludeFile)
          .takeIf { excludeFile.isNotEmpty() && it.isFile }
          ?.readLines()
          ?.map(String::trim)
          ?.filter(String::isNotEmpty)
      if (fromFile != null && fromFile == resolved) {
        add("-Dcomposeai.preview.idExcludeFile=${java.io.File(excludeFile).absolutePath}")
      } else {
        arg("composeai.preview.idExclude", resolved)
      }
      // Rows travel separately: id filters apply before `@PreviewParameter` rows get their ids, so
      // they can't name a row.
      arg("composeai.preview.rowExclude", rowExcludes.getOrElse(emptyList()))
      arg(
        PreviewPermutations.SYSTEM_PROPERTY,
        PreviewPermutations.clean(permutations.getOrElse(emptyList())),
      )
    }

    private fun MutableList<String>.arg(property: String, values: List<String>) {
      val cleaned = values.map(String::trim).filter(String::isNotEmpty)
      if (cleaned.isNotEmpty()) add("-D$property=${cleaned.joinToString(",")}")
    }
  }

  /**
   * Highest bytecode target across Java `targetCompatibility` and Kotlin `jvmTarget`, or `null`;
   * feeds [RenderJvmSelection]. Probes are defensive and skipped on failure.
   */
  /**
   * [compileTaskNames] is the render classpath's candidate list. Needed for KMP-Android, where the
   * task is `compileAndroidMain` and there's no [CommonExtension], so both probes would otherwise
   * come back empty.
   */
  private fun detectRenderBytecodeMajor(
    project: Project,
    compileTaskNames: List<String>,
  ): Int? {
    val candidates = mutableListOf<Int>()
    runCatching {
      project.extensions
        .findByType(CommonExtension::class.java)
        ?.compileOptions
        ?.targetCompatibility
        ?.let { BytecodeTargetDetector.parseTargetMajor(it.toString()) }
        ?.let { candidates += it }
    }
    BytecodeTargetDetector.detectKotlinJvmTarget(project, compileTaskNames)?.let {
      candidates += it
    }
    return candidates.filter { it > 0 }.maxOrNull()
  }

  private fun copyAttributes(target: AttributeContainer, source: AttributeContainer) {
    source.keySet().forEach { key ->
      @Suppress("UNCHECKED_CAST") val attr = key as Attribute<Any>
      source.getAttribute(attr)?.let { target.attribute(attr, it) }
    }
  }

  /**
   * Effective shard count: `≥1` as-is; `0` (auto) feeds a previous `previews.json` to
   * [ShardTuning.autoShards], or falls back to 1 when absent.
   */
  private fun resolveShardCount(
    project: Project,
    extension: PreviewExtension,
    previewsJson: java.io.File,
  ): Int {
    val requested = extension.shards.get()
    if (requested > 0) return requested
    if (!previewsJson.exists()) {
      project.logger.info(
        "compose-ai-tools: shards=auto but previews.json missing; defaulting to 1 for this run"
      )
      return 1
    }
    // Size by preview row: the renderer keeps a row's captures on one fork, so counting captures
    // would pick idle extra forks. [ShardTuning.perPreviewRowCosts] mirrors the renderer's per-row
    // cost.
    val text = previewsJson.readText()
    val rowCosts = ShardTuning.perPreviewRowCosts(text)
    val rowCount = rowCosts.size
    val totalCost = rowCosts.sum()
    val maxIndividualCost = rowCosts.maxOrNull() ?: 0.0
    val hostMemoryMb = ShardTuning.hostMemoryMb()
    val resolved =
      ShardTuning.autoShards(
        totalCost = totalCost,
        maxIndividualCost = maxIndividualCost,
        shardableRows = rowCount,
        availableMemoryMb = hostMemoryMb,
      )
    val memTag = if (hostMemoryMb == Long.MAX_VALUE) "unknown" else "${hostMemoryMb}MB"
    project.logger.lifecycle(
      "compose-ai-tools: shards=auto → $resolved " +
        "(rows=$rowCount, totalCost=${"%.1f".format(totalCost)}, " +
        "maxRowCost=${"%.1f".format(maxIndividualCost)}, " +
        "cores=${Runtime.getRuntime().availableProcessors()}, mem=$memTag)"
    )
    return resolved
  }

  private fun String.cap(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
  }

  /**
   * Records an [ee.schimke.composeai.plugin.tooling.InjectedDependency] for doctor.json and logs it
   * uniformly:
   *
   *     compose-ai-tools: inject[<coord>] <OUTCOME> → <config>  (<reason>)
   */
  private fun recordInjectedDependency(
    project: Project,
    sink: MutableList<ee.schimke.composeai.plugin.tooling.InjectedDependency>,
    coordinate: String,
    configuration: String,
    outcome: String,
    reason: String,
  ) {
    sink +=
      ee.schimke.composeai.plugin.tooling.InjectedDependency(
        coordinate = coordinate,
        configuration = configuration,
        outcome = outcome,
        reason = reason,
      )
    val target = configuration.ifEmpty { "—" }
    project.logger.info("compose-ai-tools: inject[$coordinate] $outcome → $target  ($reason)")
  }

  /**
   * For `manageDependencies = false`: fails configuration with the exact coordinates and buckets
   * the plugin would have used.
   */
  private fun validateExternallyManagedDependencies(
    project: Project,
    variantName: String,
    testImplementation: String,
    tilesRendererRequired: Boolean,
    composeAiTraceRequired: Boolean,
  ) {
    // Declared, not resolved, so it fails before resolution and accepts any parent bucket. Group +
    // name only.
    fun declared(configName: String): Sequence<org.gradle.api.artifacts.Dependency> =
      project.configurations.findByName(configName)?.allDependencies?.asSequence()
        ?: emptySequence()

    fun hasCoord(configName: String, group: String, name: String): Boolean =
      declared(configName).any { it.group == group && it.name == name }

    val missing = mutableListOf<String>()
    if (!hasCoord(testImplementation, "androidx.compose.ui", "ui-test-manifest")) {
      missing += "$testImplementation(\"androidx.compose.ui:ui-test-manifest\")"
    }
    if (!hasCoord(testImplementation, "androidx.compose.ui", "ui-test-junit4")) {
      missing += "$testImplementation(\"androidx.compose.ui:ui-test-junit4\")"
    }
    if (!hasCoord("${variantName}Implementation", "androidx.core", "core")) {
      missing += "${variantName}Implementation(\"androidx.core:core:1.16.0\")"
    }
    if (
      !hasCoord(
        "${variantName}Implementation",
        "androidx.customview",
        "customview-poolingcontainer",
      )
    ) {
      missing +=
        "${variantName}Implementation(\"androidx.customview:customview-poolingcontainer:1.0.0\")"
    }
    if (
      tilesRendererRequired &&
        !hasCoord("${variantName}Implementation", "androidx.wear.tiles", "tiles-renderer")
    ) {
      missing += "${variantName}Implementation(\"androidx.wear.tiles:tiles-renderer\")"
    }
    if (
      composeAiTraceRequired &&
        !hasCoord(testImplementation, "androidx.compose.runtime", "runtime-tracing")
    ) {
      missing += "$testImplementation(\"androidx.compose.runtime:runtime-tracing\")"
    }

    if (missing.isNotEmpty()) {
      val suffix = buildString {
        if (tilesRendererRequired) {
          append("\n  tiles-renderer required: wear.tiles signal was matched on this module.")
        }
        if (composeAiTraceRequired) {
          append("\n  runtime-tracing required: composeAiTrace preview extension is enabled.")
        }
      }
      throw org.gradle.api.GradleException(
        "composePreview.manageDependencies = false, but the following required " +
          "dependencies are not declared in module '${project.path}':\n" +
          missing.joinToString(separator = "\n") { "  - $it" } +
          suffix +
          "\n\nAdd them to your build file, or set composePreview.manageDependencies = true " +
          "to let the plugin add them automatically."
      )
    }
  }

  /**
   * Cheap-signal files per [DESIGN § 8 Tier
   * 1](../../../../../docs/daemon/DESIGN.md#tier-1--project-fundamentally-changed): version
   * catalog, this project's build script, settings, `gradle.properties`, `local.properties` —
   * existing files only. Shared with desktop.
   *
   * Sibling build scripts aren't walked: Tier-2 (runtime classpath fingerprint) already catches
   * meaningful sibling changes, and walking siblings under IP would need a settings plugin.
   */
  internal fun collectCheapSignalFiles(project: org.gradle.api.Project): List<java.io.File> =
    CheapSignalFiles.collect(project)
}

/**
 * IP-safe collector: only `project.rootDir` and the project's own dir. See
 * [AndroidPreviewSupport.collectCheapSignalFiles].
 */
internal object CheapSignalFiles {
  internal fun collect(project: org.gradle.api.Project): List<java.io.File> {
    val out = LinkedHashSet<java.io.File>()
    val rootDir = project.rootDir
    listOf(
        "gradle/libs.versions.toml",
        "settings.gradle.kts",
        "settings.gradle",
        "gradle.properties",
        "local.properties",
      )
      .forEach { out += java.io.File(rootDir, it) }
    val moduleDir = project.projectDir
    out += java.io.File(moduleDir, "build.gradle.kts")
    out += java.io.File(moduleDir, "build.gradle")
    // Existing files only, so missing ones don't put ghost paths into the fingerprint.
    return out.filter { it.isFile }
  }
}
