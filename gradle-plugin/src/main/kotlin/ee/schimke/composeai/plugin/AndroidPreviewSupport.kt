package ee.schimke.composeai.plugin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import ee.schimke.composeai.daemonlaunch.*
import ee.schimke.composeai.discovery.*
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

/**
 * All AGP-touching code lives here, segregated from [ComposePreviewPlugin] so the plugin class
 * stays loadable on classpaths without AGP (functional tests, Compose-Multiplatform-only
 * consumers). Gradle decorates the plugin class at apply time — and decoration resolves referenced
 * classes eagerly. Keeping every `com.android.build.api.*` reference out of
 * [ComposePreviewPlugin]'s bytecode means AGP only gets loaded when this helper's static methods
 * are actually invoked, which happens inside `pluginManager.withPlugin("com.android.application" /
 * "com.android.library")`.
 */
internal object AndroidPreviewSupport {
  /**
   * Floor version pinned on every plugin-injected `androidx.compose.*` coordinate that doesn't have
   * its own version source (`ui-test-manifest`, `ui-test-junit4`). Matches the Compose line that
   * `:renderer-android` compiles against (`compose-bom-compat` 2025.11.01 → Compose 1.9.5); the
   * renderer's bytecode references `ui-test` entry points at this surface, so injecting the
   * matching version guarantees the test classpath has methods the renderer calls. Consumers with a
   * higher Compose BOM in their `implementation` still get their aligned version through Gradle's
   * max-version conflict resolution. Bump in lockstep with `compose-bom-compat` in
   * `gradle/libs.versions.toml`.
   */
  internal const val RENDERER_COMPOSE_FLOOR_VERSION: String = "1.9.5"

  /**
   * Platform token for the auto-provisioned `xr-composite` cache, matching the Release asset matrix
   * in `.github/workflows/release.yml` and the CLI writer's `XrCompositeProvision.platformToken`.
   * `null` for any OS/arch combination that has no published asset (e.g. linux-arm64) — the cache
   * tier then contributes nothing and the task falls through to its graceful skip. Kept pure
   * (params rather than `System.getProperty`) so it's unit-testable.
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
   * Config-time provider for the shared auto-provision cache binary path:
   * `${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite/<version>/<platform>/xr-composite`
   * (`xr-composite.exe` on Windows). This is the WELL-KNOWN PATH CONVENTION shared with the CLI
   * writer ([XrCompositeProvision.cacheBinary] in `:cli`) — the two derive the identical path from
   * the same release [version] + host platform, which is how the CLI's fetch and the plugin's read
   * meet without a runtime handshake. Built entirely from injected providers so it stays IP- and
   * configuration-cache-safe (no `project.*` / `System.getProperty` at task-action time).
   *
   * Returns an absent provider when the host platform has no published asset, when `user.home`
   * resolves empty and no `XDG_CACHE_HOME` is set — anything that would make the path meaningless —
   * so the binary chain falls through to the graceful skip.
   */
  internal fun xrCompositeCacheBinaryPath(
    version: String,
    xdgCacheHome: Provider<String>,
    userHome: Provider<String>,
    osName: Provider<String>,
    osArch: Provider<String>,
  ): Provider<String> {
    val platform = osName.zip(osArch) { n, a -> xrCompositePlatformToken(n, a) ?: "" }
    // Cache root: XDG_CACHE_HOME if set, else <user.home>/.cache. `orElse("")` keeps the chain
    // resolvable so we can detect "neither available" and drop out.
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
   * Modules within `androidx.wear.tiles` whose presence in a consumer's declared deps signals "this
   * project writes Tile previews." When any match, [configure] injects `wear.tiles:tiles-renderer`
   * into the consumer's variant `implementation` so AGP generates R classes for
   * protolayout-renderer — the class TilePreviewRenderer reflectively needs at render time. See the
   * `afterEvaluate` block in [registerAndroidTasks] for the full rationale.
   */
  private val tilesSignalNames =
    setOf("tiles", "tiles-renderer", "tiles-tooling-preview", "tiles-tooling")

  /**
   * `(group, name)` of every artifact whose presence in a module's declared deps marks it as a
   * "valid preview module" — the plugin registers its tasks and runs discovery only when at least
   * one matches. Convention-plugin-everywhere setups (e.g. applying `composePreview` to every
   * Android module) stay silent no-ops on utility modules without any preview surface.
   *
   * Group+name match only (no version): cheap, IP-safe, doesn't trigger dependency resolution.
   */
  private val previewArtifactSignals =
    setOf(
      "androidx.compose.ui" to "ui-tooling-preview",
      "androidx.compose.ui" to "ui-tooling-preview-android",
      "androidx.wear.tiles" to "tiles-tooling-preview",
      // CMP-only; AGP consumers never declare it but the helper is shared.
      "org.jetbrains.compose.components" to "components-ui-tooling-preview",
      // CMP relocates `androidx.compose.ui:ui-tooling-preview` under its own
      // group when `compose.ui` is consumed via the JetBrains BOM. Same FQN
      // for `@Preview` at runtime — see DiscoverPreviewsTask comments — so
      // accept it as a valid signal too. Without this, CMP-on-Android
      // consumers hit the "no known @Preview dependency" gate and the
      // plugin silently skips task registration.
      "org.jetbrains.compose.ui" to "ui-tooling-preview",
    )

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
   * Wires [GenerateRobolectricPropertiesTask] inputs from the consumer's configuration. The task
   * resolves the final `sdk=N` from these via:
   * 1. `composePreview.sdkVersion = N` — explicit consumer override (validated strictly against
   *    Robolectric's supported range; out-of-range values fail the task).
   * 2. `android.compileSdk` — auto-detected from AGP's `finalizeDsl`; clamped to
   *    [GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK] with a build warning when the consumer
   *    is on a newer compileSdk than Robolectric ships (e.g. `compileSdk = 37` against Robolectric
   *    4.16.1's API 36 ceiling — Tiles consumers often hit this via transitive minCompileSdk
   *    requirements).
   * 3. [GenerateRobolectricPropertiesTask.DEFAULT_SDK] — fallback when neither is set; AGP normally
   *    fails the build before reaching this branch, so it's mostly a unit-test guard.
   *
   * The decision lives inside the task action (see [GenerateRobolectricPropertiesTask.resolveSdk])
   * so the clamp warning fires at execution time alongside the file write.
   */
  internal fun wireSdkInputs(
    task: GenerateRobolectricPropertiesTask,
    extensionOverride: Property<Int>,
    consumerCompileSdk: Provider<Int>,
  ) {
    task.sdkOverride.set(extensionOverride)
    task.consumerCompileSdk.set(consumerCompileSdk)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    // `buildJavaMajor` is wired separately (below, in registerAndroidTasks) to the Gradle build
    // JVM, with the SDK matrix overriding it to its forked test toolchain.
  }

  fun configure(project: Project, extension: PreviewExtension) {
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

    // Captures the consumer's `android.compileSdk` from `finalizeDsl` so the
    // `composePreviewGenerateRobolectricProperties` task can stamp the matching `sdk=N` into
    // the generated `robolectric.properties`. Robolectric must run at the same
    // API level the consumer's `apk-for-local-test.ap_` was compiled against;
    // otherwise `PackageParser` rejects the manifest with "Requires newer sdk
    // version" at sandbox bootstrap (issue #1248).
    val consumerCompileSdk = project.objects.property(Int::class.java)

    androidComponents.finalizeDsl { android: Any ->
      val common = android as CommonExtension
      if (extension.enabled.get()) {
        common.testOptions.unitTests.isIncludeAndroidResources = true
      }
      // `compileSdk` is nullable on `CommonExtension` (consumers can omit it,
      // though AGP usually fails the build later). Only propagate when set so
      // [GenerateRobolectricPropertiesTask.sdk]'s `.orElse(...)` chain falls
      // through to the user override / floor when AGP couldn't supply one.
      val resolvedCompileSdk: Int? = common.compileSdk
      if (resolvedCompileSdk != null) {
        consumerCompileSdk.set(resolvedCompileSdk)
      }
    }

    // Stage-2 BTA configurations + afterEvaluate dep wiring must happen at apply / config
    // time, BEFORE the `tasks.register {…}` block in `registerAndroidTasks` runs (which
    // executes at task-realization time, by which point Gradle's MutationGuard rejects
    // `Project.afterEvaluate(...)` calls — same constraint the desktop path hit).
    // Idempotent: `maybeCreate` no-ops if the configurations already exist from a
    // sibling CMP applyToDesktop call.
    ComposePreviewTasks.setupBtaConfigurationsFor(project, extension)

    // Register render tasks once, for the variant the user picked. onVariants
    // fires after AGP has created variant-specific configurations like
    // `${variant}UnitTestRuntimeClasspath`, so everything we need is there.
    // Fetching `sdkComponents.bootClasspath` eagerly (at apply time) forces
    // AGP to read `compileOptions.targetCompatibility` before it's finalized
    // and crashes — grab it inside onVariants instead.
    var registered = false
    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
      if (registered) return@onVariants
      if (!extension.enabled.get()) return@onVariants
      val target = extension.variant.get()
      if (!variantMatchesTarget(variant.name, target)) return@onVariants
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
      // Snap the extension's variant Property to the resolved variant name so
      // downstream readers (DiscoverPreviewsTask.variantName,
      // ComposePreviewModelBuilder.resolveVariant, doctor task) report the
      // actually-selected name instead of the unresolved target. No-op when
      // the match was already exact.
      if (variant.name != target) extension.variant.set(variant.name)
      registerAndroidTasks(
        project,
        extension,
        variant,
        androidComponents.sdkComponents.bootClasspath,
        consumerCompileSdk,
      )
      registerAndroidResourcePreviewTasks(project, extension, variant)
    }
  }

  /**
   * Matches an AGP variant name against the target the consumer asked for. Used to gate task
   * registration in [configure] and to resolve the right `${variant}RuntimeClasspath` in
   * [ComposePreviewModelBuilder] when a flavored module has no exact match.
   *
   * Two rules, in order:
   * 1. **Exact match.** `target=demoDebug` matches `demoDebug` only — explicit `--variant
   *    demoDebug` pins a specific flavor and any other variant is ignored. Suffix matching is
   *    skipped for flavored targets so `--variant paidDebug` does NOT silently match
   *    `minApi23PaidDebug` on a multi-dimension flavored module.
   * 2. **Build-type suffix match.** A bare build-type target (`debug`, `release`) also matches
   *    `demoDebug`, `prodDebug`, `uatDebug` — anything whose name ends with the capitalized target.
   *    Keeps the default `--variant=debug` working on flavored apps (issue #1546) without making
   *    the consumer add `--variant demoDebug` every run.
   *
   * "Bare build-type" is detected as a target containing no uppercase characters — matches AGP's
   * convention that build types are lowercase identifiers while combined variant names carry an
   * uppercased segment (`paidDebug`, `minApi23PaidDebug`). If the target itself has an internal
   * uppercase, it's a flavored variant name and rule 2 is bypassed.
   *
   * Rule 2 is intentionally one-directional: a target like `demoDebug` does NOT match a flavorless
   * `debug` variant. The user picked a flavor and the module doesn't have it, so the module is
   * silently skipped — same outcome as today.
   */
  internal fun variantMatchesTarget(variantName: String, target: String): Boolean {
    if (variantName == target) return true
    if (target.isEmpty()) return false
    val isBareBuildType = target.none { it.isUpperCase() }
    if (!isBareBuildType) return false
    val capitalized = target.replaceFirstChar { it.uppercase() }
    return variantName.endsWith(capitalized)
  }

  /**
   * Registers `composePreviewDiscoverAndroidResources` for the targeted [variant], gated on
   * `composePreview.resourcePreviews.enabled`. Wires the task's inputs from the variant's lazy
   * `sources.res.all` and `artifacts.get(MERGED_MANIFEST)` providers so the task picks up flavour
   * overrides + manifest-merger output without duplicating AGP's resolution logic. Renderer wiring
   * lands in a follow-up commit; until then the task writes `resources.json` only.
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
   * Config-time decision: does this module *potentially* host previewable composables, such that we
   * should register the renderer tasks?
   *
   * Two-tier check, both IP-safe and CC-friendly (no eager resolution at config time):
   * 1. **Direct declared deps** ([hasDirectPreviewDependency]) — a preview-tooling coord is in this
   *    module's `*Implementation` / `*Api` / `*RuntimeOnly` buckets. Authoritative win.
   * 2. **Compose plugin applied AND has project deps**
   *    ([isComposeModule] + [hasAnyProjectDependency]) — the module compiles Compose code and
   *    declares at least one `project(":...")` dep, so the renderer-required preview-tooling coord
   *    could arrive transitively (the CMP-Android `:composeApp -> :shared` shape from issues #241
   *    / #1549). Tier 1 of the gate stays cheap; the actual transitive verification happens at task
   *    time via [ValidatePreviewToolingPresentTask], which walks `${variant}RuntimeClasspath`'s
   *    resolved graph through a wired `Provider<ResolvedComponentResult>` (the documented IP-safe,
   *    CC-safe pattern for "I want the authoritative answer at task time").
   *
   * **Why the Compose-plugin gate.** Auto-inject applies this plugin to *every* AGP module in a
   * multi-module build (the init script's `withPlugin("com.android.application" /
   * "com.android.library")` hooks don't filter), including pure utility / network modules that
   * don't compile Compose at all (e.g. nowinandroid's `:core:network`). Without the Compose-plugin
   * gate, the project-deps tier would register tasks on those modules — and
   * [registerAndroidTasks]'s `testImplementation(ui-test-manifest)` / `(ui-test-junit4)` injections
   * then leak Compose into builds that didn't want it. Requiring the Compose compiler plugin keeps
   * the tier-2 gate scoped to modules that already compile Compose code and could plausibly host
   * `@Preview` annotations.
   *
   * The previous IP-safe implementation walked sibling project `build.gradle[.kts]` text via a
   * BuildService — fast, but a heuristic that missed coords contributed by convention plugins.
   * Switching tier-2 verification to the resolved graph closes that gap: the resolved classpath is
   * exactly what AGP gives the test JVM, so any coord that would actually be on the renderer's
   * classpath gets seen.
   *
   * The `variantName` argument is unused but kept on the public signature for test-fixture
   * compatibility.
   */
  internal fun hasPreviewDependency(
    project: Project,
    @Suppress("UNUSED_PARAMETER") variantName: String,
  ): Boolean =
    hasDirectPreviewDependency(project) ||
      (isComposeModule(project) && hasAnyProjectDependency(project))

  /**
   * True when this module applies a Kotlin-Compose-compiler-bearing plugin. Used as the tier-2
   * sanity check in [hasPreviewDependency] so utility / network modules that auto-inject the plugin
   * but don't actually compile Compose stay silent (no preview-related dep injection, no preview
   * tasks registered). The two plugins covered:
   * - **`org.jetbrains.kotlin.plugin.compose`** — the modern Kotlin 2.x Compose Compiler plugin
   *   used by AGP modules with Compose UI (and also by Compose Multiplatform modules in
   *   `kotlin("multiplatform")`-only mode through the same id).
   * - **`org.jetbrains.compose`** — Compose Multiplatform's umbrella plugin.
   *
   * Plugin lookup is via [PluginManager.hasPlugin], which is IP-safe (scoped to the current
   * project's plugin state).
   */
  internal fun isComposeModule(project: Project): Boolean =
    project.pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose") ||
      project.pluginManager.hasPlugin("org.jetbrains.compose")

  /**
   * Direct-only variant — declared preview-tooling coord in this module's `*Implementation` /
   * `*Api` / `*RuntimeOnly` buckets. Used by the doctor task to surface the "pin the dep locally"
   * soft recommendation even when the gate passed via the project-dep tier of
   * [hasPreviewDependency].
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
   * True when this module declares at least one `project(":...")` dep in any declarable bucket.
   * IP-safe via [ProjectDependency.getPath] (Gradle 8.11+) — returns the target project's path as a
   * string without touching the other `Project` object the way the legacy `getDependencyProject()`
   * does.
   *
   * Used as a config-time over-approximation of "could preview tooling reach this module
   * transitively"; the resolved-graph walk in [ValidatePreviewToolingPresentTask] is what confirms
   * or rejects at task time.
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
  ) {
    val variantName = variant.name
    val capVariant = variantName.cap()
    val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")
    val artifactType = Attribute.of("artifactType", String::class.java)
    val daemonResDirs =
      variant.sources.res?.all?.let { resSources ->
        project.files(resSources).elements.map { elements ->
          elements.joinToString(java.io.File.pathSeparator) { it.asFile.absolutePath }
        }
      } ?: project.providers.provider { "" }

    // `com.android.compose.screenshot` (Google's alpha Layoutlib-based
    // screenshot testing plugin) adds its own `screenshotTest` source set
    // alongside `main` / `test` / `androidTest`. We don't drive its
    // validate/update tasks — we keep using our Robolectric renderer — but
    // we DO want to discover and render any `@Preview` functions consumers
    // put under `src/screenshotTest/`, so modules that already adopted the
    // Google plugin (e.g. Confetti's `:androidApp`) surface those previews
    // in the CLI / VS Code grid without duplicating them in `main`.
    //
    // Detection is by plugin id rather than the
    // `android.experimental.enableScreenshotTest` gradle property, because
    // the property is a global flag while the plugin is applied per-module
    // — and only the latter actually causes AGP to register
    // `compile${Cap}ScreenshotTestKotlin` and the
    // `${variant}ScreenshotTestRuntimeClasspath` configuration we need.
    val screenshotTestEnabled = project.pluginManager.hasPlugin("com.android.compose.screenshot")

    // `kotlin("multiplatform") + com.android.library` (issue #1492 / Confetti `:shared` shape):
    // KGP creates per-target compile tasks like `compileDebugKotlinAndroid` instead of the plain
    // `compileDebugKotlin` an android-library-only module exposes, and routes class output to
    // `build/classes/kotlin/<targetName>/<variantName>/` rather than `build/tmp/kotlin-classes/…`.
    // Both names / paths are added as candidates — whichever exists in the consumer's actual
    // shape is picked up by `tasks.matching` / DiscoverPreviewsTask's silent skip-missing-dirs
    // behaviour. The default `androidTarget()` name is "android"; consumers who renamed it
    // (`androidTarget("foo")`) still need the workaround flagged in the issue.
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
      sourceClassDirs.from(project.layout.buildDirectory.dir("classes/kotlin/android/$variantName"))
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

    val dependencyConfigName = "${variantName}RuntimeClasspath"
    val screenshotTestRuntimeConfig =
      if (screenshotTestEnabled) {
        project.configurations.findByName("${variantName}ScreenshotTestRuntimeClasspath")
      } else null

    val mainCompileTaskNames =
      if (isKmp) listOf("compile${capVariant}Kotlin", "compile${capVariant}KotlinAndroid")
      else listOf("compile${capVariant}Kotlin")
    val screenshotCompileTaskNames =
      if (isKmp)
        listOf(
          "compile${capVariant}ScreenshotTestKotlin",
          "compile${capVariant}ScreenshotTestKotlinAndroid",
        )
      else listOf("compile${capVariant}ScreenshotTestKotlin")
    val discoverTask =
      ComposePreviewTasks.registerDiscoverTask(
        project,
        sourceClassDirs,
        { dependencyConfigName },
        previewOutputDir,
        extension,
      ) {
        // Lazy `tasks.matching` rather than strict `dependsOn(taskName)` so KMP-Android modules
        // (where `compileDebugKotlin` doesn't exist — only `compileDebugKotlinAndroid` does)
        // don't crash whole-project listings at task-graph-build time. See issue #1492.
        dependsOn(project.tasks.matching { it.name in mainCompileTaskNames })
        // No opt-in-extension wiring on `composePreviewDiscover` — a11y is daemon-only.
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
          }
        }
      }
    // `composePreviewCompile` — the daemon-mode save loop calls this instead of
    // `composePreviewDiscover`
    // so the recompile (and on-disk `.class` refresh) runs without re-walking the dependency-JAR
    // classpath through ClassGraph on every keystroke. We deliberately stop at the main compile —
    // ScreenshotTest sources matter only for `composePreviewDiscover`'s dependency-JAR scan, not
    // for the
    // user's edited preview-bearing file.
    ComposePreviewTasks.registerCompileOnlyTask(
      project,
      extension,
      compileTaskNames = mainCompileTaskNames,
    )

    // `composePreviewCheckDebugPreviews` — only meaningful when the Google
    // screenshot plugin is on the project, so we register conditionally
    // rather than as a permanent no-op task. See [CheckDebugPreviewsTask]
    // for the motivation; tl;dr `src/debug/` previews compiled against the
    // `screenshotTest` dependency closure routinely fail in confusing ways
    // (compile-time NoSuchSymbol or render-time `.error.json` with no PNG),
    // and the fix is to move them to `src/screenshotTest/{java,kotlin}/`.
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
      // `finalizedBy` rather than `dependsOn` so discovery itself never
      // waits on this check, and a check failure (shouldn't happen — the
      // task only warns) doesn't block downstream render tasks. Runs once
      // per discover invocation, skipped when no debug sources exist
      // (`@SkipWhenEmpty` on `debugSourceFiles`).
      discoverTask.configure { finalizedBy(checkDebugTask) }
    }

    // Writes the plugin-side compat findings (CompatRules) to
    // `build/compose-previews/doctor.json`. The CLI doesn't need this
    // file (it reads the same data via the ComposePreviewModel Tooling
    // API), but tools that invoke Gradle tasks rather than BuildActions
    // — specifically the VS Code extension — do. Same JSON schema as
    // `compose-preview doctor --json`'s per-module shape, so both
    // surfaces converge on one contract.
    // Resolve the runtime classpaths' root components at configuration
    // time so the task action stays config-cache safe (no `task.project`
    // access at execution). `findByName` may return null on variants that
    // don't have a paired unit-test classpath; the task tolerates an
    // unset Property as "no deps to inspect".
    val mainRuntimeRoot =
      project.configurations
        .findByName("${variantName}RuntimeClasspath")
        ?.incoming
        ?.resolutionResult
        ?.rootComponent
    val testRuntimeRoot =
      project.configurations
        .findByName("${variantName}UnitTestRuntimeClasspath")
        ?.incoming
        ?.resolutionResult
        ?.rootComponent

    // Capture the running Gradle version at configuration time so the
    // task action stays config-cache safe (GradleVersion.current() is a
    // static call but keeping the read out of `@TaskAction` avoids
    // surprises if Gradle ever namespaces it differently).
    val currentGradleVersion = org.gradle.util.GradleVersion.current().version
    // Accumulator for inject records. The unconditional and
    // conditional blocks below each append; the doctor task reads the
    // list lazily via `project.provider { ... }` so it's evaluated
    // AFTER the `afterEvaluate` block populates the tiles entry.
    val injectedDependencies =
      mutableListOf<ee.schimke.composeai.plugin.tooling.InjectedDependency>()
    val injectedDependencyJson = kotlinx.serialization.json.Json { encodeDefaults = true }
    // Captured at registration time so the doctor task's `@Input Boolean` is a plain serializable
    // value (no `Project` capture in the Provider chain). We're already inside `onVariants` here,
    // so the consumer's `dependencies { }` block has finished evaluating. The "transitive
    // detection" signal that used to ride alongside this is computed at the doctor's action time
    // from `mainRuntimeRoot` — that's the IP-safe, CC-safe way to ask "is preview tooling
    // reachable through the resolved graph?" without forcing config-time resolution (issue #1549).
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

    // Task-time preview-tooling validation (issue #1549). When the config-time gate passed via
    // the project-deps tier rather than a direct declared coord, we don't yet know whether the
    // resolved runtime classpath actually contains preview tooling — only Gradle's resolution
    // engine can answer that authoritatively, and it can only be invoked at task-action time
    // without tripping the "Configuration was resolved during configuration time" CC warning.
    // Wiring `ValidatePreviewToolingPresentTask` as a `dependsOn` of `composePreviewRender` runs
    // the resolved-graph walk first; if no preview-tooling coord is reachable, render bails fast
    // with a remediation-oriented error instead of hitting Robolectric with a missing-class
    // explosion. When the direct check already passed we know the coord is on the classpath and
    // skip registering the validator entirely — saves a resolution at execution time.
    //
    // Opt-in by default: gated on `composePreview.failOnMissingPreviewTooling`. The hard fail is
    // useful for CI fast-fail but actively hurts multi-module apps that have an aggregator module
    // (e.g. a `:demo-app` that pulls together cards from sibling modules without hosting any
    // `@Preview` itself). Those modules pass the tier-2 over-approximation but legitimately don't
    // host previews — letting `composePreviewDiscover` find zero and silently no-op is the right
    // outcome for them.
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

    // Always inject `ui-test-manifest` + `ui-test-junit4` into the consumer's
    // `testImplementation`:
    //
    //  * `ui-test-manifest` contributes the `<activity android:name=
    //    "androidx.activity.ComponentActivity">` entry that has to land in
    //    the consumer's merged unit-test AndroidManifest before
    //    `createAndroidComposeRule<ComponentActivity>()` can launch its
    //    ActivityScenario. Our plugin bypasses the normal AGP dep graph
    //    (renderer classpath lives in our own resolvable config, not
    //    `testImplementation`), so the manifest merger never sees it
    //    otherwise.
    //  * `ui-test-junit4` is where `createAndroidComposeRule` /
    //    `ComposeTestRule` / `mainClock` live. The renderer test references
    //    these unconditionally from its default `renderDefault` path (we
    //    use `mainClock.autoAdvance = false` + explicit frame pumping to
    //    make infinite animations terminate deterministically — see
    //    RobolectricRenderTest.renderDefault), so the consumer's test
    //    classpath needs these classes too, not just the resource/manifest
    //    half of the story.
    //
    // `composePreview.manageDependencies = false` opts out of all
    // plugin-side injection. Deps are recorded as SKIPPED_BY_CONFIG
    // in `doctor.json` so consumers can see what they need to add,
    // and the afterEvaluate block below validates the consumer did
    // add them — the build fails during configuration with an
    // explicit coordinate list instead of surfacing a
    // ClassNotFoundException from Robolectric at render time.
    val manageDependencies = extension.manageDependencies.get()

    // Pin to the renderer-android compile floor (`compose-bom-compat` 2025.11.01 → Compose
    // [RENDERER_COMPOSE_FLOOR_VERSION]) rather than emitting an unversioned coordinate. Two
    // consumer shapes need the explicit version:
    //
    //  * Tile-only / non-Compose-UI Android apps that still go through `composePreviewRender` (e.g.
    //    wear-os-samples WearTilesKotlin, where the only `androidx.compose.ui:*` artifact in main
    //    is `ui-tooling`). Those projects ship no Compose BOM and no `ui-test-*` artifact ever
    //    appears on the dependency graph, so an unversioned `androidx.compose.ui:ui-test-manifest`
    //    fails resolution with `Could not find androidx.compose.ui:ui-test-manifest:.` — and
    //    config-cache serialization then surfaces it as a wrapping `ConfigurationCacheError` on
    //    `:app:compileDebugUnitTestKotlin` instead of the underlying coordinate. Renderer rendering
    //    even for `kind=TILE` previews still wraps the tile composable in
    //    `createAndroidComposeRule<ComponentActivity>()`, so these artifacts ARE reached at test
    //    time — skipping the injection isn't an option.
    //
    //  * Compose-app consumers with a BOM declared in `implementation(platform(...))` rely on the
    //    BOM to align ui-test-manifest / ui-test-junit4 to their Compose line. Gradle's default
    //    conflict resolution picks the maximum among declared sources, so our floor pin is
    //    overridden by any consumer-BOM-aligned higher version automatically — we don't need a
    //    separate BOM-detection branch.
    //
    // Picking 1.9.x specifically: it's the version surface renderer-android compiles against
    // (`compose-bom-compat` in libs.versions.toml), so the bytecode references in the renderer's
    // ui-test entry points are guaranteed to exist. Bumping it later means bumping the renderer's
    // compile floor in lockstep — keep the two in sync.
    if (manageDependencies) {
      project.dependencies.add(
        "testImplementation",
        "androidx.compose.ui:ui-test-manifest:$RENDERER_COMPOSE_FLOOR_VERSION",
      )
      project.dependencies.add(
        "testImplementation",
        "androidx.compose.ui:ui-test-junit4:$RENDERER_COMPOSE_FLOOR_VERSION",
      )
      // Pin `androidx.core:core` to the floor that compose-ui 1.10+ requires.
      // The renderer's test classpath gets compose-ui via roborazzi-compose's
      // transitive deps regardless of what the consumer declares, and
      // compose-ui 1.10+'s `InsetsListener.onViewAttachedToWindow` reads
      // `androidx.core.R.id.tag_compat_insets_dispatch` (added in core
      // 1.16.0). The merged unit-test resource APK is built from the
      // consumer's MAIN variant, so on tile-only / older-Compose consumers
      // (e.g. WearTilesKotlin: no compose-ui in main, transitive core is
      // pre-1.16) the field is missing and Robolectric crashes the moment
      // `AndroidComposeView.onAttachedToWindow` runs:
      //
      //   `NoSuchFieldError: Class androidx.core.R$id does not have member
      //   field 'int tag_compat_insets_dispatch'`
      //
      // Adding the floor to `${variantName}Implementation` is the same
      // pattern used for `tiles-renderer` below — a main-variant dep so AGP
      // includes the R class in the merged test APK. Acts as a floor only:
      // Gradle picks the higher version when consumers already pin core
      // >= 1.16 via their own deps (compose-bom 2026.x, etc.), so it's
      // non-destructive for the common case.
      project.dependencies.add("${variantName}Implementation", "androidx.core:core:1.16.0")
      // Pin `androidx.customview:customview-poolingcontainer` for the same
      // reason as `androidx.core:core` above. compose-ui's
      // `ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool`
      // reads `androidx.customview.poolingcontainer.R.id.*` from
      // `PoolingContainer.<clinit>`, so the merged unit-test resource APK
      // needs that R class. Tile-only consumers without compose-ui in main
      // (e.g. WearTilesKotlin) carry no transitive customview-poolingcontainer
      // on the main variant, so the field lookup crashes Robolectric the
      // moment `AbstractComposeView.<init>` installs the strategy:
      //
      //   `NoClassDefFoundError: Could not initialize class
      //   androidx.customview.poolingcontainer.PoolingContainer`
      //   caused by `NoClassDefFoundError:
      //   androidx/customview/poolingcontainer/R$id`
      //
      // 1.0.0 is the only published version (compose-ui 1.9.x → 1.11.x all
      // depend on it unchanged); the floor here is a no-op for Compose-app
      // consumers that already get it transitively, and a fix for tile-only
      // consumers that don't.
      project.dependencies.add(
        "${variantName}Implementation",
        "androidx.customview:customview-poolingcontainer:1.0.0",
      )
      // Pin `androidx.activity:activity` to a floor that exposes the
      // `view_tree_on_back_pressed_dispatcher_owner` resource id. The
      // renderer's test classpath pulls activity-compose ≥ 1.13 transitively
      // (via roborazzi-compose), whose `ComponentActivity.initializeViewTreeOwners`
      // → `ViewTreeOnBackPressedDispatcherOwner.set` reads
      // `androidx.activity.R.id.view_tree_on_back_pressed_dispatcher_owner`
      // (added in `androidx.activity:activity:1.5.0`). The merged unit-test
      // resource APK is built from the consumer's MAIN variant, so on
      // tile-only consumers whose main pulls only a legacy activity (e.g.
      // wear-os-samples' WearTilesKotlin resolves `activity:1.1.0` via old
      // transitives) the field is missing and Robolectric crashes the
      // moment `createAndroidComposeRule<ComponentActivity>().setContent {}`
      // runs:
      //
      //   `NoSuchFieldError: Class androidx.activity.R$id does not have
      //   member field 'int view_tree_on_back_pressed_dispatcher_owner'`
      //
      // Same `${variantName}Implementation` floor pattern as the
      // `androidx.core:core` and `customview-poolingcontainer` entries
      // above — Gradle picks the max with consumer-aligned versions, so
      // this is a no-op for Compose-app consumers that already get a
      // newer activity transitively, and a fix for tile-only consumers
      // that don't.
      project.dependencies.add("${variantName}Implementation", "androidx.activity:activity:1.10.0")
      // Pin `androidx.compose.ui:ui` on the main variant for tile-only /
      // non-Compose-UI consumers. compose-ui's
      // `AndroidComposeViewAccessibilityDelegateCompat.<clinit>` reads
      // `androidx.compose.ui.R.id.*` (via `accessibility_custom_action_*`
      // / `compose_view_root_id` lookups), so the merged unit-test resource
      // APK needs the compose-ui R class. The renderer's test classpath
      // brings compose-ui transitively (via roborazzi-compose / ui-test-*)
      // — but that's the JVM test classpath only; AGP builds the merged
      // `apk-for-local-test.ap_` from the consumer's MAIN variant, so on
      // tile-only consumers without compose-ui in main (e.g. WearTilesKotlin)
      // the R class is missing and Robolectric crashes the moment
      // `AndroidComposeView.<init>` triggers the accessibility delegate's
      // class init:
      //
      //   `NoClassDefFoundError: Could not initialize class
      //   androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat`
      //   caused by `NoClassDefFoundError: androidx/compose/ui/R$id`
      //
      // Same `${variantName}Implementation` floor pattern as the
      // `androidx.core:core`, `customview-poolingcontainer`, and
      // `androidx.activity:activity` entries above — Gradle picks the max
      // with consumer-aligned versions, so this is a no-op for Compose-app
      // consumers that already get a newer ui via their BOM, and a fix for
      // tile-only consumers that don't.
      project.dependencies.add(
        "${variantName}Implementation",
        "androidx.compose.ui:ui:$RENDERER_COMPOSE_FLOOR_VERSION",
      )
      // Pin `androidx.compose.foundation:foundation` on the main variant for
      // the same reason as compose-ui above — but for class-loading rather
      // than R.id lookup. `TilePreviewRenderer.TilePreviewComposable` calls
      // `Modifier.fillMaxSize()` (from `androidx.compose.foundation.layout.SizeKt`)
      // to fill the renderer's host AndroidView. On tile-only consumers
      // without compose-foundation in main (e.g. WearTilesKotlin), the class
      // isn't on the user-classpath component of the merged test APK and
      // Robolectric crashes the first time the tile compose-tree runs:
      //
      //   `NoClassDefFoundError: androidx/compose/foundation/layout/SizeKt`
      //   at `TilePreviewRendererKt.TilePreviewComposable`
      //
      // Same `${variantName}Implementation` floor pattern as compose-ui —
      // Gradle picks the max with consumer-aligned versions, so this is a
      // no-op for Compose-app consumers that already get foundation via
      // their BOM, and a fix for tile-only consumers that don't.
      project.dependencies.add(
        "${variantName}Implementation",
        "androidx.compose.foundation:foundation:$RENDERER_COMPOSE_FLOOR_VERSION",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-manifest:$RENDERER_COMPOSE_FLOOR_VERSION",
        configuration = "testImplementation",
        outcome = "APPLIED",
        reason =
          "merges ComponentActivity into the unit-test manifest for renderer; pinned to the renderer's compile floor so tile-only consumers without a Compose BOM still resolve a version (Gradle picks max with consumer-BOM-aligned versions)",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-junit4:$RENDERER_COMPOSE_FLOOR_VERSION",
        configuration = "testImplementation",
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
        coordinate = "androidx.compose.ui:ui:$RENDERER_COMPOSE_FLOOR_VERSION",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "compose-ui's AndroidComposeViewAccessibilityDelegateCompat.<clinit> reads androidx.compose.ui.R.id.*; merged test APK needs the compose-ui R class on tile-only consumers without compose-ui in main",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.foundation:foundation:$RENDERER_COMPOSE_FLOOR_VERSION",
        configuration = "${variantName}Implementation",
        outcome = "APPLIED",
        reason =
          "TilePreviewRenderer.TilePreviewComposable calls Modifier.fillMaxSize() from androidx.compose.foundation.layout.SizeKt; tile-only consumers without compose-foundation in main hit NoClassDefFoundError at render time",
      )
    } else {
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-manifest",
        configuration = "testImplementation",
        outcome = "SKIPPED_BY_CONFIG",
        reason = "manageDependencies=false; consumer must declare this in testImplementation",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-junit4",
        configuration = "testImplementation",
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

    // Conditionally inject `androidx.wear.tiles:tiles-renderer` into the
    // consumer's variant `implementation` when the consumer signals they
    // want Tile previews. Detection is deferred to `afterEvaluate` so the
    // consumer's declared deps are complete.
    //
    // Why we inject at all: TilePreviewRenderer.renderTileInto calls
    // `TileRenderer(...)`, whose constructor builds `ProtoLayoutThemeImpl`
    // which holds a Java reference to
    // `androidx.wear.protolayout.renderer.R$style.ProtoLayoutBaseTheme`.
    // That R class is only compiled into the consumer's merged R.jar when
    // `wear.tiles:tiles-renderer` is on the MAIN compile classpath —
    // `testImplementation` and `compileOnly` don't participate in AGP's R
    // class generation. Consumer apps shouldn't have to restate a purely
    // preview-rendering dep in their main `implementation`.
    //
    // Why the signal is "tiles-tooling-preview / tiles-renderer / tiles":
    // these are the modules a consumer actually declares when they write
    // `@Preview`-annotated tile functions. Horologist projects go through
    // `horologist-tiles` so we include that too.
    //
    // No version — the consumer's wear.tiles atomic group constrains
    // `tiles-renderer` to their wear.tiles version. When the detection
    // misfires in a non-tiles project (shouldn't happen under the
    // heuristic above), Gradle fails with a clear "no version for
    // tiles-renderer" error.
    project.afterEvaluate {
      val composeAiTraceEnabled = resolveComposeAiTraceEnabled(project, extension).get()
      if (composeAiTraceEnabled) {
        if (manageDependencies) {
          // Pinned to the same renderer-compile-floor as ui-test-manifest above so consumers
          // without a Compose BOM (tile-only / older-Compose) still resolve a version. See the
          // [RENDERER_COMPOSE_FLOOR_VERSION] KDoc for the resolution model.
          project.dependencies.add(
            "testImplementation",
            "androidx.compose.runtime:runtime-tracing:$RENDERER_COMPOSE_FLOOR_VERSION",
          )
          recordInjectedDependency(
            project,
            injectedDependencies,
            coordinate = "androidx.compose.runtime:runtime-tracing:$RENDERER_COMPOSE_FLOOR_VERSION",
            configuration = "testImplementation",
            outcome = "APPLIED",
            reason =
              "required by compose-ai-tools trace data product; pinned to the renderer's compile floor (Gradle picks max with consumer BOM)",
          )
        } else {
          recordInjectedDependency(
            project,
            injectedDependencies,
            coordinate = "androidx.compose.runtime:runtime-tracing",
            configuration = "testImplementation",
            outcome = "SKIPPED_BY_CONFIG",
            reason =
              "manageDependencies=false; consumer must declare this when composeAiTrace is enabled",
          )
        }
      }

      // Scan every configuration whose name ends in `Implementation` so
      // the detection works for ANY buildType / flavor / variant combo
      // (e.g. `uatImplementation`, `stagingImplementation`,
      // `uatStagingImplementation`). The earlier hardcoded list of
      // `debugImplementation` / `releaseImplementation` only fired on
      // the default AGP buildTypes, missing custom flavored layouts
      // like `uatDebug`. The group+name filter below is precise enough
      // that casting a wider net is safe — false positives require a
      // dep literally in the `androidx.wear.tiles` / horologist-tiles
      // groups, which is the signal we're looking for.
      // Scan every declarative dep-bucket name so the detection works
      // regardless of which bucket (and which sourceSet / buildType /
      // flavor / variant) the consumer used to declare their tile deps:
      //   - `implementation` / `<sourceSet>Implementation` — the common case.
      //   - `api` / `<sourceSet>Api` — Android library modules that
      //     re-export tile APIs to their consumers.
      //   - `runtimeOnly` / `<sourceSet>RuntimeOnly` — rare, but tile
      //     deps declared runtime-only still need the R-class injection.
      // Resolving the actual runtime classpath would be authoritative
      // but triggers config-cache invalidation and is awkward under
      // Isolated Projects, so we stay declarative. The group+name
      // filter inside is precise enough (exact match on `androidx.wear.tiles` /
      // horologist-tiles coords) that widening the config scan can't
      // introduce false positives.
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

      // `manageDependencies=false`: verify the consumer actually
      // declared the coords we would otherwise have injected. Fail
      // during configuration (in afterEvaluate) with an explicit
      // coordinate list rather than letting the render task die
      // later with a ClassNotFoundException. Check by group/name
      // across the relevant declarative buckets so the consumer
      // can place them wherever their project conventions prefer.
      if (!manageDependencies) {
        validateExternallyManagedDependencies(
          project = project,
          variantName = variantName,
          tilesRendererRequired = matchedConfigs.isNotEmpty(),
          composeAiTraceRequired = composeAiTraceEnabled,
        )
      }
    }

    val testConfig = project.configurations.findByName("${variantName}UnitTestRuntimeClasspath")

    // The default path for external consumers: resolve
    // `ee.schimke.composeai:renderer-android:<plugin-version>` from Maven.
    // The plugin's own version is baked into the jar at build time so the
    // matching renderer AAR is chosen automatically — see [PluginVersion].
    //
    // Dev-mode shortcut: when the plugin runs *inside* the compose-ai-tools
    // build itself (in-repo samples), bypass Maven and depend on the sibling
    // `:renderer-android` Gradle project directly. That way live renderer
    // edits show up without a publish step. The signal is the presence of
    // the sibling build script on disk; we deliberately avoid calling
    // `rootProject.findProject(...)` here because reading the sibling's
    // model under Isolated Projects is disallowed — a filesystem check is
    // IP-safe, and only the in-repo layout matches it.
    val rendererProjectDir = project.rootDir.resolve("renderers/android")
    val useLocalRenderer =
      rendererProjectDir.resolve("build.gradle.kts").exists() ||
        rendererProjectDir.resolve("build.gradle").exists()

    // Renderer's transitive runtime dependencies come through a dedicated
    // resolvable configuration in *this* project. Attributes are copied
    // from the sample's unit-test runtime classpath so Gradle picks the
    // right Android variant without us declaring them by hand.
    //
    // `extendsFrom(testConfig)` is load-bearing: it tells Gradle to resolve
    // renderer deps in the SAME graph as the consumer's test-runtime deps,
    // so version conflicts pick a single coherent max version instead of
    // two separate graphs that clash at class-load time. Without it, the
    // renderer's transitive `androidx.core:1.8.0` and consumer's
    // `androidx.core:1.16.0` both end up on the test classpath in different
    // JARs — whichever is listed first wins for each class, and the loaded
    // activity/lifecycle/compose-ui versions don't all agree. Symptoms:
    //   - `NoSuchFieldError: androidx.lifecycle.ReportFragment.Companion`
    //   - `NoSuchFieldError: … tag_compat_insets_dispatch`
    val rendererConfig =
      project.configurations.maybeCreate("composePreviewAndroidRenderer$capVariant").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        if (testConfig != null) {
          copyAttributes(attributes, testConfig.attributes)
          extendsFrom(testConfig)
        }
        // Force AndroidX / Compose Multiplatform KMP-published modules
        // (`-android` / `-desktop` / `-jvmstubs` siblings of the same
        // coordinate) to their Android sibling on the
        // renderer test JVM. Kotlin's `org.jetbrains.kotlin.platform.type`
        // attribute (`androidJvm` vs `jvm`) is the official disambiguator,
        // but its compatibility/disambiguation rules are only registered when
        // the Kotlin plugin is applied — consumers that build Kotlin via AGP
        // alone (e.g. WearTilesKotlin: tile-only app, only `kotlin.compose`
        // applied through `compose-compiler`, no `kotlin.android` anywhere)
        // never pick `androidJvm` and Gradle then selects the desktop variant.
        // The desktop `ViewModelProvider` is the KMP rewrite (only
        // `<init>(ViewModelProviderImpl)` survives — the legacy
        // `(ViewModelStoreOwner, Factory)` constructor is gone), while
        // `lifecycle-viewmodel-savedstate-android:2.8.7`'s
        // `getSavedStateHandlesVM` bytecode at line 107 still calls that
        // legacy constructor. Result: `NoSuchMethodError: 'void
        // ViewModelProvider.<init>(ViewModelStoreOwner, ViewModelProvider$Factory)'`
        // the first time `createAndroidComposeRule<ComponentActivity>()`
        // launches the host activity, in the
        // `ReportFragment` → `LifecycleRegistry` →
        // `SavedStateHandleAttacher.onStateChanged` chain.
        //
        // Substitute by coordinate rather than attribute: setting
        // `platform.type=androidJvm` on the config breaks resolution of
        // pure-jvm artifacts like `kotlin-stdlib` (no `androidJvm` variant)
        // because the compat rule isn't installed. Substitution is scoped to
        // the rendererConfig only — consumer test runs are untouched. Re-using
        // `requested.version` keeps us future-proof: any version Gradle picks
        // for the `-desktop`/`-jvmstubs` sibling is what we re-route to the
        // `-android` sibling, so this rule doesn't pin AndroidX to a stale
        // floor.
        //
        // Scoped to `androidx.*` and `org.jetbrains.compose.*` because those
        // KMP module families publish matching `-android` siblings for their
        // `-desktop` / `-jvmstubs` artifacts. Other JVM artifacts
        // (kotlinx-coroutines, okio, kotlin-stdlib) are genuinely JVM-only at
        // the published name and must not be rewritten.
        resolutionStrategy.eachDependency {
          val req = requested
          val targetName = kmpAndroidSiblingName(req.group, req.name)
          if (targetName != null) {
            useTarget(mapOf("group" to req.group, "name" to targetName, "version" to req.version))
            because(
              "Gradle resolved a desktop/JVM-stub KMP sibling on a config without the " +
                "Kotlin-plugin platform-type compat rule. Force the Android sibling so the " +
                "renderer's bytecode links against the AGP-flavoured class shapes (e.g. the " +
                "legacy `ViewModelProvider(ViewModelStoreOwner, Factory)` constructor that " +
                "lifecycle-viewmodel-savedstate-android still calls but the desktop variant " +
                "removed in the KMP rewrite)."
            )
          }
        }
        // Espresso (transitively pulled by `androidx.compose.ui:ui-test-junit4`) was
        // compiled against Hamcrest 1.3, whose `Matchers.java:33` invokes
        // `org.hamcrest.core.AllOf.allOf(Matcher, Matcher)` — an explicit 2-arg
        // overload that 2.x removed in favour of varargs. When a consumer adds
        // `org.hamcrest:hamcrest:2.x` (e.g. via `junit-jupiter:5.x`), the merged
        // 2.x jar coexists with the legacy split `hamcrest-core` / `hamcrest-library`
        // 1.3 jars (different module coordinates → no Gradle dedup). Whichever
        // class wins for `AllOf` vs `Matchers` is classpath-order-dependent; in
        // the failing case `Matchers` comes from 1.3 and calls into 2.x's
        // `AllOf` — `NoSuchMethodError` at `Espresso.<clinit>` triggered the
        // first time `runUntilIdle` walks through `EspressoLink`.
        //
        // Substituting the merged artifact back to `hamcrest-core:1.3` on
        // *this* configuration is enough: `resolvedClasspath` puts rendererConfig's
        // files ahead of the AGP test classpath in the composePreviewRender JVM
        // classpath (see comment above `resolvedClasspath` below), so Hamcrest
        // 1.3 wins class lookup even if the consumer's `${variant}UnitTestRuntimeClasspath`
        // still resolves 2.x for their own tests.
        resolutionStrategy.eachDependency {
          if (requested.group == "org.hamcrest" && requested.name == "hamcrest") {
            useTarget("org.hamcrest:hamcrest-core:1.3")
            because(
              "Espresso bytecode needs Hamcrest 1.3's AllOf.allOf(Matcher,Matcher); 2.x removed it"
            )
          }
        }
      }

    if (useLocalRenderer) {
      try {
        project.dependencies.add(
          rendererConfig.name,
          project.dependencies.project(mapOf("path" to ":renderer-android")),
        )
      } catch (e: org.gradle.api.UnknownProjectException) {
        project.logger.debug("compose-ai-tools: :renderer-android project not found, skipping", e)
      }
    } else {
      project.dependencies.add(
        rendererConfig.name,
        "ee.schimke.composeai:renderer-android:${PluginVersion.value}",
      )
    }

    // XR render backend (opt-in via `composePreview.enableXrPreviews`). Adds `:renderer-xr`'s
    // `XrSubspaceRenderTest` + the fake XR runtime to the render config so `composePreviewRenderXr`
    // (below) can render `@XrSubspacePreview` to `scene.json`. Gated because `androidx.xr.compose`
    // declares `minCompileSdk = 36` and the `*-testing` fakes are heavyweight — a non-XR consumer
    // (especially below compileSdk 36) must never get them on its render classpath. The fakes are
    // inert for compose/tile/notification/glance renders anyway (they only engage when a `Subspace`
    // reaches `Session.create`); the fake `SceneRuntimeFactory` / `RenderingRuntimeFactory`
    // ServiceLoader registration ships in `:renderer-xr`'s main resources.
    val xrPreviewsEnabled = extension.enableXrPreviews.get()
    if (xrPreviewsEnabled) {
      if (useLocalRenderer) {
        try {
          project.dependencies.add(
            rendererConfig.name,
            project.dependencies.project(mapOf("path" to ":renderer-xr")),
          )
        } catch (e: org.gradle.api.UnknownProjectException) {
          project.logger.debug("compose-ai-tools: :renderer-xr project not found, skipping", e)
        }
      } else {
        project.dependencies.add(
          rendererConfig.name,
          "ee.schimke.composeai:renderer-xr:${PluginVersion.value}",
        )
      }
      project.dependencies.add(
        rendererConfig.name,
        "androidx.xr.runtime:runtime-testing:1.0.0-alpha14",
      )
      project.dependencies.add(
        rendererConfig.name,
        "androidx.xr.scenecore:scenecore-testing:1.0.0-alpha15",
      )
      project.dependencies.add(
        rendererConfig.name,
        "androidx.xr.compose:compose-testing:1.0.0-alpha14",
      )
    }

    // Mirror of rendererConfig for `:daemon:android`. The daemon
    // module depends on :renderer-android, so transitive deps flow through
    // the same `extendsFrom(testConfig)` graph and stay version-coherent
    // with the consumer's classpath. Used by composePreviewDaemonStart to
    // place the daemon's main class on the launch descriptor's classpath.
    val daemonRendererConfig =
      project.configurations.maybeCreate("composePreviewAndroidDaemon$capVariant").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        if (testConfig != null) {
          copyAttributes(attributes, testConfig.attributes)
          extendsFrom(testConfig)
        }
      }

    val daemonRendererProjectDir = project.rootDir.resolve("daemon/android")
    val useLocalDaemonRenderer =
      daemonRendererProjectDir.resolve("build.gradle.kts").exists() ||
        daemonRendererProjectDir.resolve("build.gradle").exists()

    if (useLocalDaemonRenderer) {
      try {
        project.dependencies.add(
          daemonRendererConfig.name,
          project.dependencies.project(mapOf("path" to ":daemon:android")),
        )
      } catch (e: org.gradle.api.UnknownProjectException) {
        project.logger.debug("compose-ai-tools: :daemon:android project not found, skipping", e)
      }
    } else {
      // External-consumer mode: pull `daemon-android` from Maven Central — published as part of
      // PR #373's daemon-* publishing roll-out. Without this dependency the launch descriptor
      // would have no `DaemonMain` class on its classpath and the spawned JVM would die with
      // `ClassNotFoundException: ee.schimke.composeai.daemon.DaemonMain`.
      project.dependencies.add(
        daemonRendererConfig.name,
        "ee.schimke.composeai:daemon-android:${PluginVersion.value}",
      )
    }

    // Classes used for Gradle's test-class scanning. Local mode: the
    // renderer-android project's compiled output directories. External
    // mode: the AAR's `classes.jar`, expanded via `zipTree` so Gradle's
    // `Test.include("**/…Test.class")` filter can walk it — the include
    // filter traverses file trees but does NOT descend into JAR entries,
    // so feeding a raw JAR here silently produces `composePreviewRender NO-SOURCE`
    // and every preview ends up with no PNG. `android-classes` is AGP's
    // `ArtifactType.CLASSES_JAR` (a JAR), not the extracted directory
    // (that would be `android-classes-directory`).
    val rendererClassDirs =
      if (useLocalRenderer) {
        project.files(
          rendererProjectDir.resolve(
            "build/intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"
          ),
          rendererProjectDir.resolve("build/tmp/kotlin-classes/$variantName"),
        )
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
        // Wire the zipTree expansion through `elements.map { ... }` so Gradle's
        // task-graph walk sees a Provider (build-dependency-aware, value
        // resolved lazily) instead of a Callable. A Callable here forces
        // `rendererConfig` to resolve during task-graph construction —
        // `DefaultConfigurableFileCollection.visitDependencies` unwraps Callables
        // eagerly via `DeferredUtil.unpackNestableDeferred`, which calls into
        // `rendererJars.getFiles()` and trips AGP's
        // `DependencyResolutionChecks` "resolved during configuration time"
        // warning (issue #1038). The Provider chain below participates in the
        // build-dependency graph through `rendererJars.elements` without
        // realising the configuration until task execution.
        project.files(
          rendererJars.elements.map { elements -> elements.map { project.zipTree(it.asFile) } }
        )
      }

    // Class dirs for `:renderer-xr`'s `XrSubspaceRenderTest` entry — same local-vs-published shape
    // as
    // [rendererClassDirs] above (the lazy `elements.map { zipTree }` keeps the config off the
    // configuration-time resolution path). Only `composePreviewRenderXr` reads this.
    val xrRendererProjectDir = project.rootDir.resolve("renderers/xr")
    val xrRendererClassDirs =
      if (useLocalRenderer) {
        project.files(
          xrRendererProjectDir.resolve(
            "build/intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"
          ),
          xrRendererProjectDir.resolve("build/tmp/kotlin-classes/$variantName"),
        )
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
        project.files(
          xrRendererJars.elements.map { elements -> elements.map { project.zipTree(it.asFile) } }
        )
      }

    // AGP's `generate${Variant}UnitTestConfig` task emits
    // `com/android/tools/test_config.properties` under
    // `intermediates/unit_test_config_directory/<variant>UnitTest/.../out/`.
    // Robolectric loads it from the classpath and uses it to find the merged
    // resource APK (`apk-for-local-test.ap_`) — the one that contains every
    // AAR's merged resources (protolayout-renderer's `ProtoLayoutBaseTheme`
    // etc.). Without this directory on the classpath, `getIdentifier` returns
    // 0 for any library-provided style and TileRenderer's theme construction
    // explodes on `Unknown resource value type 0`. Compose-only previews
    // don't read AAR resources, which is why this only surfaced with tiles.
    val unitTestConfigDir =
      project.layout.buildDirectory.dir(
        "intermediates/unit_test_config_directory/${variantName}UnitTest/generate${capVariant}UnitTestConfig/out"
      )

    // Generates `ee/schimke/composeai/renderer/robolectric.properties`
    // onto the render classpath so Robolectric overrides the consumer's
    // `Application` with a stub by default — see
    // [GenerateRobolectricPropertiesTask] for rationale and the opt-out.
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
        wireSdkInputs(this, extension.sdkVersion, consumerCompileSdk)
        outputDir.set(robolectricPropertiesDir)
      }

    // Renderer classpath FIRST — renderer depends on kotlinx-serialization
    // 1.11.x and Roborazzi 1.59+ while consumer apps may transitively drag
    // in older versions (Compose BOM, etc). Gradle's FileCollection.from()
    // doesn't do conflict resolution, so whichever JAR comes first wins at
    // classload time. Putting the renderer's dependencies first ensures the
    // test code gets the versions it was compiled against.
    //
    // Construction is delegated to [AndroidPreviewClasspath.buildTestClasspath] so
    // the upcoming preview daemon (see docs/daemon/DESIGN.md) can build the same
    // classpath without re-implementing the inline DSL. The trailing AGP test
    // classes / classpath additions are still composed in the Test lambda below
    // (they need `findByName("test${capVariant}UnitTest")` which only resolves
    // late).
    val bootClasspathFallback = AndroidPreviewClasspath.buildBootClasspathFallback(project)
    val resolvedClasspath =
      AndroidPreviewClasspath.buildTestClasspath(
        project = project,
        bootClasspath = bootClasspath,
        bootClasspathFallback = bootClasspathFallback,
        rendererConfig = rendererConfig,
        rendererClassDirs = rendererClassDirs,
        sourceClassDirs = sourceClassDirs,
        testConfig = testConfig,
        screenshotTestRuntimeConfig = screenshotTestRuntimeConfig,
        unitTestConfigDir = unitTestConfigDir,
        robolectricPropertiesDir = generateRobolectricPropertiesTask.flatMap { it.outputDir },
      )

    val manifestFile = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
    val rendersDirectory = previewOutputDir.map { it.dir("renders") }
    val dataProductsDirectory = previewOutputDir.map { it.dir("data") }
    val rendersDir = rendersDirectory.map { it.asFile.absolutePath }

    // Resolve the optional `xr-composite` native tool location at CONFIG time (Isolated Projects is
    // on / the configuration cache is strict — the task action must not touch `project.*`). The
    // binary comes, in order, from:
    //   (a) the `composePreview.xrCompositeBinary` Gradle property (explicit override),
    //   (b) the `XR_COMPOSITE_BIN` env var,
    //   (c) the shared auto-provision cache for THIS plugin version + the host platform — the path
    //       the CLI writes when it fetches the per-OS Release tarball (see
    //       [xrCompositeCacheBinaryPath] / `XrCompositeProvision` in `:cli`). The plugin only READS
    //       this cache; it never downloads. Raw `./gradlew` therefore stays
    // override-or-prepopulated
    //       — only the CLI populates the cache.
    // The materials dir comes from `composePreview.xrCompositeMaterials` or, by default,
    // `<binaryDir>/materials` (where `build.sh` and the Release tarball emit it next to the
    // binary).
    // Every tier may be absent — the task degrades gracefully (logs + skips) when the binary isn't
    // configured / found, and the cache-tier path is only USED when the file actually exists (the
    // task action's `isFile` check), so an empty cache falls through to the same skip.
    val xrCompositeCachePath =
      xrCompositeCacheBinaryPath(
        version = PluginVersion.value,
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

    // ATF / hierarchy data products are produced only by the daemon path
    // (`:daemon:android`'s RenderEngine). The standalone Robolectric `composePreviewRender` Test
    // task
    // never writes accessibility artefacts, so no per-preview / aggregate output dirs are
    // declared here.

    val shardCount =
      resolveShardCount(project, extension, previewOutputDir.get().file("previews.json").asFile)
    val shardsEnabled = shardCount > 1

    // When sharded, generate N Java subclasses of RobolectricRenderTestBase, each with
    // its own static @Parameters method that loads only that shard's slice of the manifest.
    // Gradle distributes tests across forks at the class level, so a single parameterized
    // class can't be split — we give it N classes. Each shard subclass resolves its
    // Robolectric config via the generated package-level `robolectric.properties`
    // (sdk/graphicsMode/application/shadows), so every JVM's sandbox key matches and
    // each fork reuses its own cached sandbox across all previews in its slice.
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
          options.release.set(21)
          dependsOn(generateShardsTask)
          if (useLocalRenderer) {
            dependsOn(":renderer-android:compile${capVariant}Kotlin")
          }
        }
      } else null

    val renderTask =
      project.tasks.register("composePreviewRender", Test::class.java) {
        group = "compose preview"
        description = "Render Android previews via Robolectric"
        // Bail fast (with remediation) when the gate passed via project-deps tier but the
        // resolved runtime classpath doesn't actually reach a preview-tooling coord (issue #1549).
        // Null when direct tooling was found (validator wasn't registered — nothing to depend on).
        validatePreviewToolingPresentTask?.let { dependsOn(it) }
        val agpTestTask = project.tasks.findByName("test${capVariant}UnitTest") as? Test
        testClassesDirs =
          if (compileShardsTask != null) {
            rendererClassDirs +
              project.files(compileShardsTask.map { it.destinationDirectory }) +
              (agpTestTask?.testClassesDirs ?: project.files())
          } else {
            rendererClassDirs + (agpTestTask?.testClassesDirs ?: project.files())
          }
        // Append AGP's own `test${Cap}UnitTest` classpath at the END so we
        // pick up files that only exist there: specifically, the unit-test
        // merged R.jar for library modules (`com.android.library` variants
        // publish their AAR-transitive R classes — e.g.
        // `androidx.customview.poolingcontainer.R$id`, pulled in by
        // `ViewCompositionStrategy` — via
        // `compile_and_runtime_r_class_jar/${variant}UnitTest/process${Cap}UnitTestResources/R.jar`,
        // which is added to `debugUnitTestRuntimeClasspath` as a raw file
        // dep without the `artifactType=jar` attribute, so our
        // attribute-filtered `artifactView` above silently drops it).
        // Ordering is load-bearing — putting it last means our renderer's
        // pinned versions still win classload lookups in the earlier
        // classpath entries. No-op on applications, since
        // `process${Cap}Resources` puts the merged R.jar on the main
        // runtime classpath where our existing `artifactView` already
        // picks it up. See issue #136.
        val agpTestClasspath = agpTestTask?.classpath ?: project.files()
        classpath =
          if (compileShardsTask != null) {
            resolvedClasspath +
              project.files(compileShardsTask.map { it.destinationDirectory }) +
              (agpTestTask?.testClassesDirs ?: project.files()) +
              agpTestClasspath
          } else {
            resolvedClasspath + (agpTestTask?.testClassesDirs ?: project.files()) + agpTestClasspath
          }
        if (shardsEnabled) {
          include("**/RobolectricRenderTest_Shard*.class")
          maxParallelForks = shardCount
        } else {
          include("**/RobolectricRenderTest.class")
        }
        useJUnit()

        // Copy JVM args from AGP's test task. Deferred to the configuration
        // lambda (rather than called at registration time) so AGP has had
        // a chance to register `test${capVariant}UnitTest` by the time this
        // runs — onVariants fires before unit-test tasks are wired.
        jvmArgs(agpTestTask?.jvmArgs ?: emptyList<String>())
        // Static JVM open flags live in [AndroidPreviewClasspath.buildJvmArgs] so the
        // preview daemon can reuse the same set when launching its own JVM.
        jvmArgs(AndroidPreviewClasspath.buildJvmArgs())

        // Inherit AGP's unit-test javaLauncher so the forked test worker
        // runs on the same JDK as `test${capVariant}UnitTest` — which
        // AGP has already wired to the project's Java toolchain if the
        // consumer configured one (`java { toolchain { … } }` /
        // `kotlin { jvmToolchain(…) }`), or to the daemon JVM otherwise.
        //
        // Without this, a custom `Test` task's `javaLauncher` property
        // defaults to the first `java` on PATH, which on CI and in local
        // shells with `JAVA_HOME` overrides is NOT necessarily the same
        // JVM the Gradle daemon is running. That mismatch produces
        // `ClassNotFoundException: android.app.Application` during JUnit
        // discovery on some JVM/classloader combinations. See #142.
        agpTestTask?.javaLauncher?.orNull?.let { javaLauncher.set(it) }

        // GoogleFont interceptor cache lives in the shared, machine-local
        // `${'$'}XDG_CACHE_HOME/composeai/fonts` (else `~/.cache/composeai/fonts`).
        // The renderer class no-ops when this property is absent, so the feature
        // is fully additive for existing consumers.
        val fontsCacheDir = composeAiFontsCacheDir(project)
        // `-PcomposePreview.fontsOffline=true` (or the same Gradle property
        // on a CI profile) skips network on cache miss so the render
        // shows the fallback font rather than silently fetching from
        // `fonts.googleapis.com`.
        val fontsOffline =
          project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
        // Static system properties (Robolectric modes + the path-bearing composeai.*
        // values) live in [AndroidPreviewClasspath.buildSystemProperties] so the
        // preview daemon can replay the same set when launching its own JVM. The
        // dynamic per-task ArgumentProviders (a11y, tier) stay below — they need
        // lazy `Provider<>` evaluation at task-execution time.
        AndroidPreviewClasspath.buildSystemProperties(
            manifestPath = manifestFile.get(),
            rendersDir = rendersDir.get(),
            fontsCacheDir = fontsCacheDir,
            fontsOffline = fontsOffline.get(),
          )
          .forEach { (k, v) -> systemProperty(k, v) }

        // No a11y JVM-arg providers — the standalone Robolectric path doesn't run ATF or write
        // accessibility sidecars. The daemon is the single source of truth for a11y data
        // products; consumers route through it (VS Code chip → daemon subscription,
        // `compose-preview a11y` → temporary daemon, MCP → daemon directly).
        // Display filters — lazy-input pattern so `-PcomposePreview.displayFilter
        // .filters=grayscale,invert` toggles don't invalidate the configuration cache.
        // RobolectricRenderTest reads `composeai.displayfilter.filters` after each capture and
        // calls DisplayFilterDataProducer.writeArtifacts when non-empty.
        jvmArgumentProviders.add(
          DisplayFilterSystemPropsProvider(filters = resolveDisplayFilterFilters(project))
        )
        // Render-tier filter — fed via the same lazy `@Input` provider
        // pattern so VS Code can flip `-PcomposePreview.tier=fast` on
        // every save without paying a config-cache reconfigure. Renderer
        // reads `composeai.render.tier` in [PreviewManifestLoader.loadShard]
        // to drop HEAVY captures from each entry before sharding.
        val tierProvider = resolveTier(project)
        jvmArgumentProviders.add(TierSystemPropProvider(tier = tierProvider))
        // Disable build-cache participation for `tier=fast` runs. A cache
        // hit restores the cached `renders/` snapshot, which on a fast
        // run only contains the cheap captures — heavy outputs from a
        // previous full run would get wiped, breaking the "stale image"
        // story VS Code shows on heavy cards. Up-to-date checks still
        // apply, so a `tier=fast` re-run with no input changes is a
        // no-op and the renders dir stays as-is. Full-tier runs cache
        // normally.
        outputs.cacheIf("composePreviewRender caches tier=full runs only") {
          tierProvider.get().equals("full", ignoreCase = true)
        }
        // The PNG files are written to `rendersDirectory` via the
        // `composeai.render.outputDir` system property, not through any
        // Gradle-managed output. Declare the directory as an additional
        // output so the build cache round-trips the PNGs alongside the
        // test reports; without this the task gets a cache hit on a fresh
        // checkout but the renders are never restored, which is exactly
        // how previous modules silently vanished from `compose-preview/main`.
        outputs.dir(rendersDirectory).withPropertyName("rendersDir")
        // Heavy preview extensions such as @ScrollingPreview(LONG/GIF)
        // write their artefacts under build/compose-previews/data rather
        // than renders/. Declare that tree too so remote cache hits restore
        // the files that composePreviewRenderAll validates from manifest
        // dataProducts.
        outputs.dir(dataProductsDirectory).withPropertyName("dataProductsDir")

        // Fail fast with a clear, fixable error if android.jar isn't actually
        // on the resolved classpath — otherwise the user sees Robolectric's
        // own `Config.<clinit>` -> `NoClassDefFoundError: android/app/Application`
        // (issue #1243), which doesn't hint at the root cause (missing
        // compileSdk / unresolved SDK location). doFirst runs in the Gradle
        // process at task-execution time, so `classpath.files` is fully
        // resolved by the time we inspect it.
        doFirst { AndroidPreviewClasspath.validateApplicationOnClasspath(classpath.files) }

        dependsOn(discoverTask)
        dependsOn(generateRobolectricPropertiesTask)
        if (useLocalRenderer) {
          dependsOn(":renderer-android:compile${capVariant}Kotlin")
        }
        if (screenshotTestEnabled) {
          dependsOn("compile${capVariant}ScreenshotTestKotlin")
        }
        // `process${Cap}Resources` only exists on `com.android.application`
        // variants — AGP 9.x libraries expose the resource pipeline through
        // `merge${Cap}Resources` / `generate${Cap}RFile` / the unit-test-
        // specific `process${Cap}UnitTestResources`. The unit-test resource
        // APK we actually consume is already routed via
        // `generate${Cap}UnitTestConfig` below, so the `processResources`
        // dep is just belt-and-suspenders; skip it when absent so library
        // modules configure cleanly. See issue #136.
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

    // Feed the JDK-aware Robolectric SDK ceiling with the JVM the render forks into. Default to the
    // Gradle build JVM: the `composePreviewRender` Test task inherits AGP's unit-test
    // `javaLauncher`, which — absent a consumer toolchain — is the build JVM, so this is the JVM
    // `DefaultSdkProvider.verifySupportedSdk` actually runs under (the Confetti case the fix
    // targets). Deliberately a plain value, NOT a provider derived from `renderTask` — mapping a
    // `TaskProvider` carries that task as a dependency, and `renderTask` already dependsOn this
    // generator (via the generated-resources classpath), so that would be a circular dependency.
    // The SDK matrix forks tests into `composeai.matrix.jvmToolchain` and overrides this input
    // directly (see `samples/sdk-matrix/build.gradle.kts`). See
    // [GenerateRobolectricPropertiesTask.buildJavaMajor].
    generateRobolectricPropertiesTask.configure {
      buildJavaMajor.set(JavaVersion.current().majorVersion.toInt())
    }

    if (extension.resourcePreviews.enabled.get()) {
      // Resource render task — same Robolectric harness as `composePreviewRender`, different test
      // class + manifest sysprops. Reuses the renderer/test/runtime classpaths computed above.
      // Kept as a sibling task (not folded into composePreviewRender) so consumers can run resource
      // discovery + render without paying for composable rendering, and vice versa.
      // Output dir is the shared `renders/` parent (same as `composeai.render.outputDir`),
      // NOT the `renders/resources/` subtree — the manifest's `renderOutput` paths are already
      // module-relative starting `renders/resources/...` and the renderer strips the leading
      // `renders/` segment when resolving. The Gradle `outputs.dir` declaration below scopes
      // the cache key to the narrower `renders/resources/` subtree this task actually writes.
      val resourcesManifestPath = previewOutputDir.map {
        it.file("resources.json").asFile.absolutePath
      }
      val resourcesRendersOutputDir = rendersDir
      val resourcesRendersSubtree = previewOutputDir.map { it.dir("renders/resources") }

      project.tasks.register("composePreviewRenderAndroidResources", Test::class.java) {
        group = "compose preview"
        description = "Render Android XML resource previews via Robolectric"
        val agpTestTask = project.tasks.findByName("test${capVariant}UnitTest") as? Test
        testClassesDirs = rendererClassDirs + (agpTestTask?.testClassesDirs ?: project.files())
        val agpTestClasspath = agpTestTask?.classpath ?: project.files()
        classpath =
          resolvedClasspath + (agpTestTask?.testClassesDirs ?: project.files()) + agpTestClasspath
        include("**/ResourcePreviewRenderTest.class")
        useJUnit()

        jvmArgs(agpTestTask?.jvmArgs ?: emptyList<String>())
        jvmArgs(AndroidPreviewClasspath.buildJvmArgs())
        agpTestTask?.javaLauncher?.orNull?.let { javaLauncher.set(it) }

        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.looperMode", "PAUSED")
        systemProperty("robolectric.conscryptMode", "OFF")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("composeai.resources.manifest", resourcesManifestPath.get())
        systemProperty("composeai.resources.outputDir", resourcesRendersOutputDir.get())

        outputs.dir(resourcesRendersSubtree).withPropertyName("resourcesRendersDir")

        // Same #1243 guard as composePreviewRender above — the resource render task
        // boots Robolectric through the identical classpath and hits the same
        // `Config.<clinit>` -> `Application.class` resolution at runner init.
        doFirst { AndroidPreviewClasspath.validateApplicationOnClasspath(classpath.files) }

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

    // XR subspace render task — same Robolectric harness as `composePreviewRender`, a different
    // test
    // entry (`:renderer-xr`'s `XrSubspaceRenderTest`) and no pixel capture. Reuses the renderer /
    // test / runtime classpaths computed above (the XR backend + fakes were added to
    // `rendererConfig`
    // so they're already in `resolvedClasspath`); `xrRendererClassDirs` puts the entry class on the
    // test class dirs. It reads the SAME `previews.json` the compose render does, filtering to
    // `XR_SUBSPACE` itself, and writes `scene.json` per preview under `renders/`. Opt-in via
    // `composePreview.enableXrPreviews` (see the renderer-config gate above) — registered only when
    // the consumer asked for XR, so non-XR consumers don't get the task (or its deps) at all.
    if (xrPreviewsEnabled)
      project.tasks.register("composePreviewRenderXr", Test::class.java) {
        group = "compose preview"
        description = "Render XR subspace previews to scene.json via Robolectric"
        val agpTestTask = project.tasks.findByName("test${capVariant}UnitTest") as? Test
        testClassesDirs = xrRendererClassDirs + (agpTestTask?.testClassesDirs ?: project.files())
        val agpTestClasspath = agpTestTask?.classpath ?: project.files()
        classpath =
          resolvedClasspath +
            xrRendererClassDirs +
            (agpTestTask?.testClassesDirs ?: project.files()) +
            agpTestClasspath
        include("**/XrSubspaceRenderTest.class")
        useJUnit()

        jvmArgs(agpTestTask?.jvmArgs ?: emptyList<String>())
        jvmArgs(AndroidPreviewClasspath.buildJvmArgs())
        agpTestTask?.javaLauncher?.orNull?.let { javaLauncher.set(it) }

        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.looperMode", "PAUSED")
        systemProperty("robolectric.conscryptMode", "OFF")
        // Each panel's content view is rasterised to its `<id>.png` texture (under NATIVE graphics,
        // above) and written via roborazzi's `captureRoboImage`, which only writes when its task
        // type is enabled — so the render task must opt in, or texture capture silently no-ops.
        // pixelCopyRenderMode mirrors the Compose `@Preview` render task for parity.
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("roborazzi.test.record", "true")
        systemProperty("composeai.render.manifest", manifestFile.get())
        systemProperty("composeai.render.outputDir", rendersDir.get())

        outputs.dir(rendersDirectory).withPropertyName("xrRendersDir")

        // Same #1243 guard as composePreviewRender — boots Robolectric through the same classpath
        // and hits the same `Config.<clinit>` -> `Application.class` resolution at runner init.
        doFirst { AndroidPreviewClasspath.validateApplicationOnClasspath(classpath.files) }

        dependsOn(discoverTask)
        dependsOn(generateRobolectricPropertiesTask)
        if (useLocalRenderer) {
          dependsOn(":renderer-xr:compile${capVariant}Kotlin")
        }
        dependsOn(
          project.tasks.matching {
            it.name in
              listOf("process${capVariant}Resources", "generate${capVariant}UnitTestConfig")
          }
        )
      }

    // Bake the XR subspace scenes into composite stills via the native `xr-composite` tool. Runs
    // after `composePreviewRenderXr` (which writes each `renders/<dir>/scene.json` + panel
    // textures) and BEFORE the renderAll validation/clean step so the gate sees the produced
    // composites and `cleanStaleRenders` keeps them (they're referenced by the manifest's optional
    // capture). Degrades gracefully: a missing / unconfigured binary, or no display + no
    // `xvfb-run`,
    // logs at lifecycle level and returns without failing — the optional capture simply has no
    // file.
    if (xrPreviewsEnabled)
      project.tasks.register("composePreviewCompositeXr", org.gradle.api.DefaultTask::class.java) {
        group = "compose preview"
        description = "Bake XR subspace scene.json files into composite.png stills via xr-composite"
        // Captured as providers at config time — the doLast body never touches `project.*` (IP /
        // strict configuration cache).
        val binaryProvider = xrCompositeBinary
        val materialsProvider = xrCompositeMaterials
        val rendersDirProvider = rendersDirectory
        // The render dir is shared with `composePreviewRender` (PNGs) and `composePreviewRenderXr`
        // (scene.json + panel textures). This task both reads it (scene.json) and writes into it
        // (composite.png), so declaring it as a tracked input/output would clash with those
        // producers' outputs and with this task's own writes. Instead we stay untracked and order
        // explicitly: depend on the XR render (our real producer) and run after the PNG render
        // (the other writer to the shared dir). Best-effort + native-shell-out means there's no
        // useful up-to-date / caching story to gain from tracking anyway.
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
          // `xr-composite` is an OpenGL/Filament tool — it needs a display. When `DISPLAY` is unset
          // we wrap the invocation in `xvfb-run -a` if available; otherwise we skip gracefully
          // rather than failing the build (the composite is best-effort).
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

    // `aggregateAccessibility` was the rollup task that turned per-preview ATF sidecars into a
    // top-level `accessibility.json`. With a11y now produced exclusively by the daemon (which
    // streams findings on demand via `data/fetch`), nothing on the standalone Gradle path
    // writes those sidecars — so there is nothing to roll up and the task is no longer
    // registered.

    ComposePreviewTasks.registerRenderAllPreviews(project, extension, renderTask, previewOutputDir)
    // Fold the XR render + composite into the user-facing aggregate so `composePreviewRenderAll`
    // produces scene.json alongside the PNGs, then bakes the composite stills (only when the XR
    // path is enabled / the tasks exist). `composePreviewCompositeXr` itself `dependsOn`
    // `composePreviewRenderXr`, so both run before the renderAll `doLast` validation/clean — the
    // gate sees the produced composites and `cleanStaleRenders` keeps them.
    if (xrPreviewsEnabled) {
      project.tasks.named("composePreviewRenderAll").configure {
        dependsOn("composePreviewRenderXr")
        dependsOn("composePreviewCompositeXr")
      }
    }

    // Register the portable-bundle task on the Android path too. `composePreviewBundle` was
    // previously desktop/JVM-only, so `compose-preview render --bundle` against a project with
    // Android modules failed task-not-found before rendering anything. Wire it with the same
    // variant class dirs and `${variant}RuntimeClasspath` the render path consumes — the bundle's
    // `artifactType=jar` view already transforms AARs to extracted classes.jar, so the closure walk
    // sees real bytecode. `backendId = "android"` is recorded in bundle.json so players know the
    // bundle was packed for the Robolectric/Android renderer.
    //
    // AAR-backed Maven deps are recorded as real `ClasspathEntry.Maven` coordinates (not inlined):
    // registerBundleTask keys the coordinate map off the same `artifactType=jar` view, so the
    // transformed classes.jar paths match what the closure walk sees. Limitation carried for now
    // (only matters for coordinate-mode re-rendering on an Android player, which isn't built yet):
    // the coordinate type is recorded as `jar` even for AAR-published deps, and Android-merged
    // resources aren't packed. `--embed-deps` sidesteps the type concern by inlining resolved jars.
    ComposePreviewTasks.registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = previewOutputDir,
      sourceClassDirs = sourceClassDirs,
      resolveDependencyConfigName = { dependencyConfigName },
      discoverTaskName = "composePreviewDiscover",
      backendId = "android",
      // (v6) Feed the AGP artefacts a protolayout-IR bundle carries for tile replay on a detached
      // daemon: `unitTestConfigDir`'s `test_config.properties` names the merged resource APK +
      // manifest, and the pack action reads those by absolute path — so union the artefacts they
      // point at (the `apk_for_local_test` output dir + the merged manifest) into the tracked
      // `@InputFiles` so the cacheable task re-packs when their content changes even if the paths
      // don't.
      androidUnitTestConfigFiles =
        project.files(
          unitTestConfigDir,
          project.layout.buildDirectory.dir(
            "intermediates/apk_for_local_test/${variantName}UnitTest"
          ),
          variant.artifacts.get(SingleArtifact.MERGED_MANIFEST),
        ),
      // The generated library R classes the tile renderer links
      // (`androidx.wear.protolayout.renderer.R$style`) are generated only into the unit-test merged
      // R.jar. That jar lives on AGP's `test<Variant>UnitTest` task classpath (a raw file dep added
      // without the `artifactType=jar` attribute, so the bundle's filtered `dependencyJars` view —
      // and a configuration `artifactView` — drop it). Source it from the Test task's *resolved*
      // classpath, the SAME collection `composePreviewRender` links it from (so it resolves cleanly
      // without the `AmbiguousArtifactsFailure` a raw configuration read hits). Supplied lazily and
      // invoked inside the bundle task's config lambda, by which point the unit-test task exists.
      androidUnitTestRuntimeClasspath = {
        (project.tasks.findByName("test${capVariant}UnitTest") as? Test)?.classpath
      },
    )

    // Phase 1, Stream A — preview daemon bootstrap descriptor. Registered
    // unconditionally so the VS Code extension can sniff the output file
    // even when `daemon.enabled = false` (it then refuses to
    // launch — see [DaemonClasspathDescriptor] KDoc). Inputs mirror the
    // composePreviewRender task's so the spawned daemon JVM is byte-for-byte
    // equivalent. See `docs/daemon/DESIGN.md` § 4 / § 6.
    //
    // Built lazily via providers so the AGP unit-test task's javaLauncher
    // resolves at execution time (same reason composePreviewRender above defers it).
    val daemonFontsCacheDir = composeAiFontsCacheDir(project)
    val daemonFontsOffline =
      project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
    // Pre-resolved at configuration time — both feed @Input fields whose Provider chains
    // mustn't capture `project`. The cheap-signal set used to be collected at task-action
    // time so newly-added subproject scripts were seen on the same run, but doing it
    // there forces the systemProperties Provider closure to capture `project`, which the
    // configuration cache (`org.gradle.configuration-cache.problems=fail`) refuses to
    // serialise. A subproject add is itself a `settings.gradle.kts` edit, which IS in the
    // cheap-signal set, so the next run picks it up — net behaviour unchanged after one
    // re-run of `composePreviewDaemonStart` and the config-cache invalidation that the
    // edit triggers.
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
      // Resolved once when the task is realised — the register {…} block runs lazily at
      // task-graph-resolution time, by which point AGP has registered the unit-test task.
      // Pulling the reference here (rather than wrapping `findByName` in a Provider that
      // re-runs at execution time) keeps the @Input Provider chains below from capturing
      // `project`, which is what the configuration cache rejects.
      val agpTestTask =
        project.tasks.findByName("test${capVariant}UnitTest") as? org.gradle.api.tasks.testing.Test

      this.modulePath.set(project.path)
      this.variant.set(variantName)
      this.daemonEnabled.set(extension.daemon.enabled)
      this.maxHeapMb.set(extension.daemon.maxHeapMb)
      this.maxRendersPerSandbox.set(extension.daemon.maxRendersPerSandbox)
      this.warmSpare.set(extension.daemon.warmSpare)
      // Stage-2 BTA wiring. The AGP unit-test task's `classpath` carries every input
      // `compileDebugKotlin` would see — Compose runtime, kotlin-stdlib, AGP-generated
      // R.jar / BuildConfig outputs, the consumer's transitive dependencies. Feed that
      // straight into BTA's compileClasspath; the daemon's child classloader watches the
      // matching output dir under `build/intermediates/built_in_kotlinc/<variant>/classes`
      // (see CLASSLOADER.md), so a successful BTA compile drops .class files in the same
      // place Gradle's `compileVariantKotlin` would have. MODULE_NAME mirrors KGP's
      // default for AGP variants — the variant-specific kotlinc compile uses
      // `project.name` (no variant suffix), confirmed against `samples-android`'s
      // kotlin.Metadata.d2[] entries.
      val agpTestClasspath = agpTestTask?.classpath ?: project.files()
      ComposePreviewTasks.wireBtaInputs(
        project = project,
        task = this,
        userCompileClasspath = agpTestClasspath,
        moduleName = project.name,
        outputDirProvider =
          project.layout.buildDirectory
            .dir("intermediates/built_in_kotlinc/$variantName/classes")
            .map { it.asFile.absolutePath },
        icWorkingDirProvider =
          project.layout.buildDirectory.dir("compose-previews/daemon-state/bta-ic").map {
            it.asFile.absolutePath
          },
        ineligibilityReason = ComposePreviewTasks.detectStageTwoIneligibilityFor(project),
      )
      // Conventional entry-point name — `daemon/android` / Stream B
      // (task B1.1) will provide the implementation. Surfacing it as a
      // Property leaves room for future variants (foreground / debug) without
      // schema churn. See [DaemonBootstrapTask] / [DaemonClasspathDescriptor].
      this.mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Inherit AGP's unit-test javaLauncher exactly the way composePreviewRender
      // does (see line ~802 above) so the daemon runs on the project's
      // configured toolchain rather than the first `java` on PATH. AGP's
      // javaLauncher Property is itself a config-cache-safe Provider produced
      // by the toolchains plugin, so mapping it to an absolute path doesn't
      // introduce any new captures.
      agpTestTask?.javaLauncher?.let { launcher ->
        this.javaLauncher.set(launcher.map { it.executablePath.asFile.absolutePath })
      }
      // Daemon module's classes FIRST so [mainClass] resolves before
      // anything in the consumer's transitive graph shadows it. Both
      // `jar` and `android-classes` artifact views are pulled because
      // the daemon module is an AGP library — `android-classes` is its
      // built classes JAR, `jar` would be a plain Kotlin JAR if Stream
      // B ever splits the module. Same defensive pair as
      // AndroidPreviewClasspath uses for testConfig.
      this.classpath.from(
        daemonRendererConfig.incoming
          .artifactView { attributes.attribute(artifactType, "jar") }
          .files
      )
      this.classpath.from(
        daemonRendererConfig.incoming
          .artifactView { attributes.attribute(artifactType, "android-classes") }
          .files
      )
      // Same FileCollection the composePreviewRender `Test` task assembles, plus
      // the AGP unit-test task's classpath (R.jar etc.) appended at the
      // tail — see line ~764 for the rationale.
      this.classpath.from(resolvedClasspath)
      this.classpath.from(agpTestTask?.testClassesDirs ?: project.files())
      this.classpath.from(agpTestTask?.classpath ?: project.files())
      // Static JVM open flags from the shared helper, plus the
      // daemon-specific heap ceiling. AGP test task's own jvmArgs are
      // intentionally NOT inherited here — they're test-runner specific
      // (e.g. `-ea` and JUnit-internal opens) and may collide with the
      // daemon's own runner. Stream B can opt back in if needed.
      this.jvmArgs.addAll(AndroidPreviewClasspath.buildJvmArgs())
      this.jvmArgs.add(extension.daemon.maxHeapMb.map { "-Xmx${it}m" })
      // Same path-bearing system properties the composePreviewRender Test task uses, plus
      // daemon-specific keys for [DaemonExtension] config the daemon reads at startup.
      //
      // Per-key `put(...)` calls (rather than a single `set(provider { wholeMap })`) so
      // each entry's Provider chain only captures serialisable references — Property
      // values from the extension, layout-derived providers, the static markers list,
      // and the eagerly-resolved cheap-signal string. A single map-building lambda
      // would have to capture `project` and `this` (to call `collectCheapSignalFiles`
      // and read `this.classpath.files`), which the configuration cache rejects with
      // `error writing value of type DefaultMapProperty`.
      this.systemProperties.put("robolectric.graphicsMode", "NATIVE")
      this.systemProperties.put("robolectric.looperMode", "PAUSED")
      this.systemProperties.put("robolectric.conscryptMode", "OFF")
      this.systemProperties.put("robolectric.pixelCopyRenderMode", "hardware")
      this.systemProperties.put("roborazzi.test.record", "true")
      this.systemProperties.put("composeai.render.manifest", manifestFile)
      this.systemProperties.put("composeai.render.outputDir", rendersDir)
      this.systemProperties.put("composeai.fonts.cacheDir", daemonFontsCacheDir)
      this.systemProperties.put("composeai.fonts.offline", daemonFontsOffline)
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
      // Mirrors the `application=android.app.Application` line
      // GenerateRobolectricPropertiesTask writes for the composePreviewRender Test path.
      // SandboxHoldingRunner.buildGlobalConfig reads this and supplies the Application
      // default. Without it, the daemon falls back to the consumer's manifest-declared
      // Application — see RobolectricHost.SandboxRunner KDoc for the URL factory cascade.
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
      // B2.0 — `composeai.daemon.userClassDirs`. The closure captures only the
      // `daemonUserClassMarkers` List<String> (a configuration-time constant);
      // `classpath.elements` is a Provider<Set<FileSystemLocation>> wired via the task's
      // own @Classpath FileCollection, which the configuration cache serialises as part
      // of the task's input snapshot.
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
      // Same path the daemon's `PreviewManifestRouter` reads to map the protocol-level
      // `previewId` payload into the `RenderSpec(className, functionName)` the engine needs.
      // Without it, `JsonRpcServer.handleRenderNow`'s `previewId=<id>` payload bottoms out in
      // the host's `renderStubFallback` and the daemon emits a stub PNG path that doesn't
      // exist on disk — see issue #314. The "harness" prefix is historical (only the harness
      // launchers used to set this); now any production-mode launcher needs it.
      this.systemProperties.put("composeai.harness.previewsManifest", manifestFile)
      // H1+H2 — `composeai.daemon.historyDir` flips daemon-side history recording on. Default
      // location is `<projectDir>/.compose-preview-history` (matches the legacy convention;
      // user-visible `.gitignore` pattern). Without this sysprop the daemon's `HistoryManager`
      // stays null and the VS Code history view shows an empty drawer.
      this.systemProperties.put(
        "composeai.daemon.historyDir",
        project.layout.projectDirectory.dir(".compose-preview-history").asFile.absolutePath,
      )
      this.systemProperties.put("composeai.daemon.workspaceRoot", project.rootDir.absolutePath)
      this.workingDirectory.set(project.projectDir.absolutePath)
      this.manifestPath.set(manifestFile)
      // @Optional @InputFile — present when composePreviewDiscover has populated previews.json,
      // missing on the very first warm. Drives Gradle to invalidate the launch descriptor (and
      // therefore re-trigger VS Code's descriptor-watcher respawn path) when the manifest first
      // appears or its content changes. Use a conditional Provider that returns `null` when the
      // file is absent: `@Optional` on an @InputFile means the *property* may be unset, but if it
      // is set the underlying file must exist (Gradle fails the task otherwise). A null-returning
      // Provider leaves the property unset, which is what `@Optional` actually consumes.
      this.previewsManifest.fileProvider(
        previewOutputDir.flatMap { dir ->
          project.providers.provider {
            val f = dir.file("previews.json").asFile
            if (f.isFile) f else null
          }
        }
      )
      this.outputFile.set(previewOutputDir.map { it.file("daemon-launch.json") })
    }
  }

  /**
   * Forwards the `composePreview.displayFilter.filters` Gradle property as the
   * `composeai.displayfilter.filters` system property on the spawned renderer JVM. The
   * `CommandLineArgumentProvider` shape (vs `test.systemProperty(...)`) means values are resolved
   * at task execution time, so toggling `-PcomposePreview.displayFilter.filters=grayscale,invert`
   * doesn't invalidate the configuration cache. Empty / unset is forwarded as an empty string;
   * `DisplayFilterConfig.fromSystemProperties()` treats blank input as "feature disabled".
   */
  internal class DisplayFilterSystemPropsProvider(
    @get:org.gradle.api.tasks.Input val filters: org.gradle.api.provider.Provider<String>
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
      listOf("-Dcomposeai.displayfilter.filters=${filters.get()}")
  }

  /**
   * Lazy `Provider<String>` for the comma-separated display-filter list. Reads
   * `-PcomposePreview.displayFilter.filters=...`; defaults to empty (feature off) so existing
   * builds don't change behaviour. Returns a Provider — not a String — so consumers can pass it to
   * lazy task inputs without invalidating the configuration cache on toggle.
   */
  internal fun resolveDisplayFilterFilters(
    project: org.gradle.api.Project
  ): org.gradle.api.provider.Provider<String> =
    project.providers.gradleProperty("composePreview.displayFilter.filters").orElse("")

  private fun parseCheckList(raw: String): Set<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

  internal fun resolveComposeAiTraceEnabled(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): org.gradle.api.provider.Provider<Boolean> {
    val typed = extension.previewExtensions.composeAiTrace
    // `findByName(...)` is safe to call eagerly because `PreviewExtensionsExtension` eagerly
    // registers the built-in ids in its generic container — see the `init` block there.
    // A `composePreview { previewExtensions { extension("composeAiTrace") { ... } } }` block in
    // the build script reaches the same instance via `maybeCreate`, so the user's
    // `enableAllChecks()` / `checks` settings flow through this Property chain regardless of
    // whether plugin-apply runs before or after the build-script body. The lazy-`Provider` wrap
    // we tried instead captured `project` into the lambda, which the strict configuration cache
    // rejects.
    val generic = extension.previewExtensions.extensions.findByName("composeAiTrace")
    val genericAllChecks =
      generic?.allChecksEnabled ?: project.providers.provider<Boolean> { false }
    val configuredAllChecks =
      typed.allChecksEnabled.zip(genericAllChecks) { typedEnabled, genericEnabled ->
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
   * Resolve the active render tier from `-PcomposePreview.tier=<fast|full>`. `fast` tells the
   * renderer to skip captures classified as [ee.schimke.composeai.plugin.CaptureCost.HEAVY]
   * (`@AnimatedPreview` and non-TOP `@ScrollingPreview` modes); `full` (the default) renders
   * everything as before.
   *
   * Returned as a `Provider<String>` (not a String) so feeding `.get()` to a
   * `CommandLineArgumentProvider` resolves the tier at task-execution time — VS Code flipping the
   * property between saves doesn't invalidate the configuration cache.
   */
  internal fun resolveTier(
    project: org.gradle.api.Project
  ): org.gradle.api.provider.Provider<String> =
    project.providers
      .gradleProperty("composePreview.tier")
      .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
      .orElse("full")

  /**
   * Lazy holder for the render-tier system property on the `composePreviewRender` `Test` task. The
   * Provider is `@Input`, so flipping `-PcomposePreview.tier` re-runs the task without invalidating
   * the configuration cache.
   */
  internal class TierSystemPropProvider(
    @get:org.gradle.api.tasks.Input val tier: org.gradle.api.provider.Provider<String>
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dcomposeai.render.tier=${tier.get()}")
  }

  private fun copyAttributes(target: AttributeContainer, source: AttributeContainer) {
    source.keySet().forEach { key ->
      @Suppress("UNCHECKED_CAST") val attr = key as Attribute<Any>
      source.getAttribute(attr)?.let { target.attribute(attr, it) }
    }
  }

  /**
   * Resolves the effective shard count from [PreviewExtension.shards]:
   * - `≥1`: use the value as-is.
   * - `0` (auto): read [previewsJson] if it exists from a previous discover run and hand the count
   *   to [ShardTuning.autoShards]. If the file is missing (very first build), fall back to 1 — the
   *   next run will have better data and can pick a higher count then.
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
    // Cheap regex parse — keeps kotlinx.serialization off the plugin
    // classpath. Each Capture entry in `previews.json` carries its own
    // `"renderOutput"` field (so counting those gives the capture
    // count, not the preview count) and an optional `"cost"` (added
    // post-0.8.0; older manifests omit it and the renderer treats
    // missing as 1.0). We feed `(totalCost, maxIndividualCost,
    // captureCount)` into [ShardTuning.autoShards] so a module with
    // three GIF captures (cost = 40 each) gets sharded for the right
    // reason rather than being judged by preview count alone.
    val text = previewsJson.readText()
    val captureCount = Regex("\"renderOutput\"\\s*:").findAll(text).count()
    val costs =
      Regex("\"cost\"\\s*:\\s*([0-9.]+)")
        .findAll(text)
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .toList()
    val explicitCostSum = costs.sum()
    val implicitCostSum = (captureCount - costs.size).coerceAtLeast(0).toDouble()
    val totalCost = explicitCostSum + implicitCostSum
    val maxIndividualCost =
      (costs.maxOrNull() ?: 1.0).coerceAtLeast(if (captureCount > costs.size) 1.0 else 0.0)
    val resolved = ShardTuning.autoShards(totalCost, maxIndividualCost, captureCount)
    project.logger.lifecycle(
      "compose-ai-tools: shards=auto → $resolved " +
        "(captures=$captureCount, totalCost=${"%.1f".format(totalCost)}, " +
        "maxCost=${"%.1f".format(maxIndividualCost)}, " +
        "cores=${Runtime.getRuntime().availableProcessors()})"
    )
    return resolved
  }

  private fun String.cap(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
  }

  /**
   * Append an [ee.schimke.composeai.plugin.tooling.InjectedDependency] record and emit a uniform
   * `info`-level line. Central helper so every injection site — unconditional or conditional —
   * contributes to the doctor.json accumulator and the grep-friendly log format with the same
   * shape:
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
   * Validates that the consumer has declared every coordinate the plugin would otherwise have
   * injected. Called from the `afterEvaluate` block in [registerAndroidTasks] when
   * `composePreview.manageDependencies = false`. Fails during configuration (not at render time) so
   * the error message carries the exact coordinate list to add, in the exact buckets the plugin
   * would have used.
   */
  private fun validateExternallyManagedDependencies(
    project: Project,
    variantName: String,
    tilesRendererRequired: Boolean,
    composeAiTraceRequired: Boolean,
  ) {
    // Declared-dependency scan, not resolved-classpath: we want to
    // fail before Gradle resolves anything, and to accept the coord
    // regardless of whether the consumer placed it in the explicit
    // bucket below or any parent config that extends into it (Android
    // library's `api` into variant `Implementation`, custom buckets,
    // etc.). Group + name match only — versions are out of scope,
    // matching how `manageDependencies=true` also passes no version.
    fun declared(configName: String): Sequence<org.gradle.api.artifacts.Dependency> =
      project.configurations.findByName(configName)?.allDependencies?.asSequence()
        ?: emptySequence()

    fun hasCoord(configName: String, group: String, name: String): Boolean =
      declared(configName).any { it.group == group && it.name == name }

    val missing = mutableListOf<String>()
    if (!hasCoord("testImplementation", "androidx.compose.ui", "ui-test-manifest")) {
      missing += "testImplementation(\"androidx.compose.ui:ui-test-manifest\")"
    }
    if (!hasCoord("testImplementation", "androidx.compose.ui", "ui-test-junit4")) {
      missing += "testImplementation(\"androidx.compose.ui:ui-test-junit4\")"
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
        !hasCoord("testImplementation", "androidx.compose.runtime", "runtime-tracing")
    ) {
      missing += "testImplementation(\"androidx.compose.runtime:runtime-tracing\")"
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
   * B2.1 — collects the cheap-signal file set per
   * [DESIGN § 8 Tier 1](../../../../../docs/daemon/DESIGN.md#tier-1--project-fundamentally-changed):
   * `gradle/libs.versions.toml`, this project's `build.gradle.kts` / `build.gradle`,
   * `settings.gradle.kts` / `settings.gradle`, `gradle.properties`, `local.properties`. Only files
   * that exist on disk are returned (a missing `local.properties` is the common case in CI; we
   * don't want a ghost path in the daemon's hash baseline).
   *
   * Shares implementation with the desktop registration; both call sites consume the same shape.
   *
   * **Sibling subprojects deliberately not walked.** Issue #1549 originally asked for sibling
   * `build.gradle[.kts]` files to flip Tier-1 dirty, restoring the pre-#1546 behaviour. The
   * intermediate fix (settings-parsing ValueSource) was dropped on review: Tier-1 is the
   * "fundamentally changed" signal that forces a full daemon reload, and Tier-2 (variant runtime
   * classpath fingerprint) already invalidates on any sibling build-file edit that changes the
   * resolved graph. The only edits the sibling walk would have caught and Tier-2 wouldn't are
   * pure-formatting / comment-only changes on a sibling — and those shouldn't trigger a full
   * reload. Re-adding the walk under IP needs a settings plugin to expose the project tree
   * authoritatively; until then, the Tier-2 path covers the meaningful cases.
   */
  internal fun collectCheapSignalFiles(project: org.gradle.api.Project): List<java.io.File> =
    CheapSignalFiles.collect(project)
}

/**
 * IP-safe cheap-signal file collector shared by the Android (`AndroidPreviewSupport`) and Desktop
 * (`ComposePreviewTasks`) registrations. Uses `project.rootDir` (a `File` snapshot) and the current
 * project's own `projectDir` only — no `rootProject.file(...)` / `rootProject.allprojects` access.
 * Sibling subprojects are intentionally excluded; see the kdoc on
 * [AndroidPreviewSupport.collectCheapSignalFiles] for the rationale.
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
    // Only emit paths that actually exist — missing files contribute their absolute path string
    // to the daemon's hash, but emitting `gradle/libs.versions.toml` for a project that doesn't
    // use a TOML catalog would brand every daemon classpath fingerprint with a ghost path. The
    // daemon's [ClasspathFingerprint] handles missing files defensively even when they are in
    // its list, but the gradle plugin's role is to feed it real paths only.
    return out.filter { it.isFile }
  }
}
