package ee.schimke.composeai.plugin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
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

  fun configure(project: Project, extension: PreviewExtension) {
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

    androidComponents.finalizeDsl { android: Any ->
      if (extension.enabled.get()) {
        (android as CommonExtension).testOptions.unitTests.isIncludeAndroidResources = true
      }
    }

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
      if (variant.name != extension.variant.get()) return@onVariants
      if (!hasPreviewDependency(project, variant.name)) {
        project.logger.info(
          "compose-preview: no known @Preview dependency declared in module " +
            "'${project.path}'; skipping task registration. " +
            "Add one of ${previewArtifactSignals.joinToString { "${it.first}:${it.second}" }} " +
            "(or remove the plugin from this module) to opt in."
        )
        return@onVariants
      }
      registered = true
      registerAndroidTasks(
        project,
        extension,
        variant,
        androidComponents.sdkComponents.bootClasspath,
      )
      registerAndroidResourcePreviewTasks(project, extension, variant)
    }
  }

  /**
   * Registers `discoverAndroidResources` for the targeted [variant], gated on
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

    project.tasks.register("discoverAndroidResources", DiscoverAndroidResourcesTask::class.java) {
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
      projectDirectory.set(projectRoot)
      outputFile.set(previewOutputDir.map { it.file("resources.json") })
    }
  }

  /**
   * True when any declarative dep bucket (`implementation` / `api` / `runtimeOnly`, including their
   * `<name>Implementation` variants) declares a coord in [previewArtifactSignals], OR when the
   * resolved runtime classpath transitively includes one. The CMP-Android canonical layout has
   * Compose UI behind a `project(":shared")` reference and only surfaces preview tooling
   * transitively — see issue #241 — so direct-only inspection wrongly rejects `:composeApp`-style
   * shells. Direct check first (cheap, no resolution); the transitive walk only fires when the
   * cheap check fails.
   */
  internal fun hasPreviewDependency(project: Project, variantName: String): Boolean =
    hasDirectPreviewDependency(project) || hasTransitivePreviewDependency(project, variantName)

  private fun hasDirectPreviewDependency(project: Project): Boolean =
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
      .any { c ->
        c.allDependencies.any { dep ->
          val g = dep.group ?: return@any false
          previewArtifactSignals.any { (sg, sn) -> g == sg && dep.name == sn }
        }
      }

  /**
   * Walks the resolved `${variantName}RuntimeClasspath` dep graph looking for any
   * [previewArtifactSignals] match. Resolves the dependency *graph* (no artifact downloads), and is
   * Isolated-Projects safe — the resolution result is the consumer module's own view of its
   * classpath, not a reach into another project's model.
   *
   * Walks both selected components ([ResolvedDependencyResult]) and unresolved-but-requested
   * coords. Treating a requested-but-unresolved signal as "yes, this module wants the preview
   * tooling" matches the doctor task's "declared intent" semantics — an offline cache miss or a
   * one-time metadata 503 shouldn't push the user back into the "no @Preview dependency declared"
   * skip-and-confuse path.
   *
   * Resolves a `copyRecursive()` rather than the original configuration. Resolving the original
   * `${variantName}RuntimeClasspath` marks its `extendsFrom` parents —
   * `${variantName}Implementation`, `implementation`, `runtimeOnly`, etc. — as observed, which then
   * forbids the `dependencies.add( "testImplementation", …)` / `${variantName}Implementation` calls
   * below in [registerAndroidTasks] (and its `afterEvaluate` block) when another plugin like
   * tapmoc's `checkDependencies` has already pulled the test runtime classpath into resolution
   * earlier in the lifecycle. The recursive copy flattens the parent chain into a detached
   * configuration, so resolving it exercises the same dep graph without observing any of the
   * consumer's declarable buckets — see issue #244 (cadence) for the original repro.
   *
   * Returns false when the variant runtime classpath isn't present (non-Android modules / variants
   * that don't synthesise one) or when traversing the resolution result throws (corrupt graph
   * during early configuration). The caller treats both as "no signal found" and logs the standard
   * "no known @Preview dependency declared" message.
   */
  private fun hasTransitivePreviewDependency(project: Project, variantName: String): Boolean {
    val runtime =
      project.configurations.findByName("${variantName}RuntimeClasspath") ?: return false
    val probe = runCatching { runtime.copyRecursive() }.getOrNull() ?: return false
    val root = runCatching { probe.incoming.resolutionResult.root }.getOrNull() ?: return false
    val seen = HashSet<org.gradle.api.artifacts.result.ResolvedComponentResult>()
    val stack = ArrayDeque<org.gradle.api.artifacts.result.ResolvedComponentResult>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      if (!seen.add(node)) continue
      val id = node.id
      if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
        if (previewArtifactSignals.any { (g, n) -> id.group == g && id.module == n }) return true
      }
      for (dep in node.dependencies) {
        // ResolvedDependencyResult — the happy path. Walk the selected component.
        // UnresolvedDependencyResult — resolution failed (offline, missing
        // artifact, etc.) but the consumer DID request a coord; check that
        // `requested` selector against the signal list so a missing transitive
        // doesn't mask the intent. Same reasoning the doctor task uses for
        // its dep audit: declared intent counts even when resolution slipped.
        when (dep) {
          is org.gradle.api.artifacts.result.ResolvedDependencyResult -> stack.addLast(dep.selected)
          else -> {
            val requested = dep.requested
            if (requested is org.gradle.api.artifacts.component.ModuleComponentSelector) {
              if (
                previewArtifactSignals.any { (g, n) ->
                  requested.group == g && requested.module == n
                }
              ) {
                return true
              }
            }
          }
        }
      }
    }
    return false
  }

  private fun registerAndroidTasks(
    project: Project,
    extension: PreviewExtension,
    variant: Variant,
    bootClasspath: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>,
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

    val sourceClassDirs =
      project.files(
        project.layout.buildDirectory.dir("tmp/kotlin-classes/$variantName"),
        project.layout.buildDirectory.dir("intermediates/javac/$variantName/classes"),
        project.layout.buildDirectory.dir(
          "intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"
        ),
      )
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

    val mainCompileTaskName = "compile${capVariant}Kotlin"
    val screenshotCompileTaskName = "compile${capVariant}ScreenshotTestKotlin"
    val discoverTask =
      ComposePreviewTasks.registerDiscoverTask(
        project,
        sourceClassDirs,
        { dependencyConfigName },
        previewOutputDir,
        extension,
      ) {
        dependsOn(mainCompileTaskName)
        if (screenshotTestEnabled) {
          dependsOn(screenshotCompileTaskName)
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
    // `composePreviewCompile` — the daemon-mode save loop calls this instead of `discoverPreviews`
    // so the recompile (and on-disk `.class` refresh) runs without re-walking the dependency-JAR
    // classpath through ClassGraph on every keystroke. We deliberately stop at the main compile —
    // ScreenshotTest sources matter only for `discoverPreviews`'s dependency-JAR scan, not for the
    // user's edited preview-bearing file.
    ComposePreviewTasks.registerCompileOnlyTask(
      project,
      extension,
      compileTaskNames = listOf(mainCompileTaskName),
    )

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
    //  * Tile-only / non-Compose-UI Android apps that still go through `renderPreviews` (e.g.
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
    val rendererProjectDir = project.rootDir.resolve("renderer-android")
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
        // files ahead of the AGP test classpath in the renderPreviews JVM
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
    // so feeding a raw JAR here silently produces `renderPreviews NO-SOURCE`
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
        // Callable defers `.files` resolution until the Test task queries
        // this FileCollection, keeping the configuration lazy.
        project.files(
          java.util.concurrent.Callable { rendererJars.files.map { project.zipTree(it) } }
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
        "generateRobolectricProperties",
        GenerateRobolectricPropertiesTask::class.java,
      ) {
        group = "compose preview"
        description = "Generate package-level robolectric.properties for renderPreviews"
        useConsumerApplication.set(extension.useConsumerApplication)
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
    val resolvedClasspath =
      AndroidPreviewClasspath.buildTestClasspath(
        project = project,
        bootClasspath = bootClasspath,
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

    // Per-preview ATF findings land here. `verifyAccessibility` rolls them
    // up into a single `accessibility.json` next to `previews.json`. Kept
    // separate from renders/ so caching treats the two output trees
    // independently.
    val accessibilityPerPreviewDir = previewOutputDir.map { it.dir("accessibility-per-preview") }
    val accessibilityReportFile = previewOutputDir.map { it.file("accessibility.json") }

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
        project.tasks.register("generateRenderShards", GenerateRenderShardsTask::class.java) {
          group = "compose preview"
          description = "Generate $shardCount RobolectricRenderTest_Shard subclasses"
          shards.set(shardCount)
          outputDir.set(shardSourcesDir)
        }
      } else null

    val compileShardsTask =
      if (generateShardsTask != null) {
        project.tasks.register("compileRenderShards", JavaCompile::class.java) {
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
      project.tasks.register("renderPreviews", Test::class.java) {
        group = "compose preview"
        description = "Render Android previews via Robolectric"
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

        // GoogleFont interceptor cache lives under
        // `<project>/.compose-preview-history/fonts/`. The dirname is
        // historical; nothing else writes there now. The renderer class
        // no-ops when this property is absent, so the feature is fully
        // additive for existing consumers.
        val fontsCacheDir =
          project.layout.projectDirectory
            .dir(".compose-preview-history")
            .dir("fonts")
            .asFile
            .absolutePath
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

        // Data-product flags are routed through a CommandLineArgumentProvider
        // rather than `systemProperty(...)` so toggling the `-P` override
        // doesn't invalidate the Gradle configuration cache. `systemProperty`
        // evaluates its value eagerly at configuration time — the provider
        // we'd read there becomes part of the config-cache key, so flipping
        // `-PcomposePreview.previewExtensions.a11y.enableAllChecks` forces a ~5-10s
        // reconfigure. CommandLineArgumentProvider's `@Input` providers
        // are only evaluated at task execution, which is exactly the
        // lazy-input semantics we want for VSCode toggles.
        //
        // Renderer always inspects these sysprops at runtime; when
        // enabled=false, the a11y code paths are no-ops (see
        // [RobolectricRenderTest.renderWithA11y] / `renderDefault`).
        jvmArgumentProviders.add(
          AccessibilitySystemPropsProvider(
            enabled = resolveA11yEnabled(project, extension),
            annotate = resolveA11yAnnotate(project, extension),
            outputDir = accessibilityPerPreviewDir.map { it.asFile.absolutePath },
            debug = project.providers.gradleProperty("composeai.a11y.debug").orElse("false"),
          )
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
        outputs.cacheIf("renderPreviews caches tier=full runs only") {
          tierProvider.get().equals("full", ignoreCase = true)
        }
        // Per-preview dir is always an output — the feature being off just
        // means no files get written there. Declaring it unconditionally
        // lets the config cache key stay stable across toggles.
        outputs.dir(accessibilityPerPreviewDir).withPropertyName("a11yPerPreviewDir")

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
        // the files that renderAllPreviews validates from manifest
        // dataProducts.
        outputs.dir(dataProductsDirectory).withPropertyName("dataProductsDir")

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

    if (extension.resourcePreviews.enabled.get()) {
      // Resource render task — same Robolectric harness as `renderPreviews`, different test
      // class + manifest sysprops. Reuses the renderer/test/runtime classpaths computed above.
      // Kept as a sibling task (not folded into renderPreviews) so consumers can run resource
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

      project.tasks.register("renderAndroidResources", Test::class.java) {
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
        jvmArgs(
          "--add-opens=java.base/java.io=ALL-UNNAMED",
          "--add-opens=java.base/java.lang=ALL-UNNAMED",
          "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
          "--add-opens=java.base/java.nio=ALL-UNNAMED",
        )
        agpTestTask?.javaLauncher?.orNull?.let { javaLauncher.set(it) }

        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.looperMode", "PAUSED")
        systemProperty("robolectric.conscryptMode", "OFF")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("composeai.resources.manifest", resourcesManifestPath.get())
        systemProperty("composeai.resources.outputDir", resourcesRendersOutputDir.get())

        outputs.dir(resourcesRendersSubtree).withPropertyName("resourcesRendersDir")

        dependsOn("discoverAndroidResources")
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

    // `verifyAccessibility` is ALWAYS registered so toggling
    // `-PcomposePreview.previewExtensions.a11y.enableAllChecks` doesn't change the
    // task graph — config cache stays valid across VSCode / CLI toggles.
    // An `onlyIf` gate backed by the lazy provider makes it a no-op when
    // the feature is off: the task configures but never executes, so the
    // JSON aggregation and failure thresholds only kick in when the user
    // actually opted in.
    val a11yEnabledProvider = resolveA11yEnabled(project, extension)
    val verifyA11yTask =
      project.tasks.register("verifyAccessibility", VerifyAccessibilityTask::class.java) {
        group = "compose preview"
        description =
          "Aggregate ATF findings from renderPreviews and fail per configured thresholds"
        perPreviewDir.set(accessibilityPerPreviewDir)
        reportFile.set(accessibilityReportFile)
        moduleName.set(project.name)
        failOnErrors.set(a11yExtension(extension).failOnErrors)
        failOnWarnings.set(a11yExtension(extension).failOnWarnings)
        dependsOn(renderTask)
        onlyIf("composePreview.previewExtensions.a11y.enableAllChecks") {
          a11yEnabledProvider.get()
        }
      }

    ComposePreviewTasks.registerRenderAllPreviews(
      project,
      extension,
      renderTask,
      previewOutputDir,
      verifyA11yTask,
    )

    // Phase 1, Stream A — preview daemon bootstrap descriptor. Registered
    // unconditionally so the VS Code extension can sniff the output file
    // even when `daemon.enabled = false` (it then refuses to
    // launch — see [DaemonClasspathDescriptor] KDoc). Inputs mirror the
    // renderPreviews task's so the spawned daemon JVM is byte-for-byte
    // equivalent. See `docs/daemon/DESIGN.md` § 4 / § 6.
    //
    // Built lazily via providers so the AGP unit-test task's javaLauncher
    // resolves at execution time (same reason renderPreviews above defers it).
    val daemonFontsCacheDir =
      project.layout.projectDirectory
        .dir(".compose-preview-history")
        .dir("fonts")
        .asFile
        .absolutePath
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
      // Conventional entry-point name — `daemon/android` / Stream B
      // (task B1.1) will provide the implementation. Surfacing it as a
      // Property leaves room for future variants (foreground / debug) without
      // schema churn. See [DaemonBootstrapTask] / [DaemonClasspathDescriptor].
      this.mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Inherit AGP's unit-test javaLauncher exactly the way renderPreviews
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
      // Same FileCollection the renderPreviews `Test` task assembles, plus
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
      // Same path-bearing system properties the renderPreviews Test task uses, plus
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
      // Preview extension selection. The daemon consumes the same a11y selector as
      // `renderPreviews`; there is no daemon-specific a11y feature flag.
      this.systemProperties.put(
        "composeai.previewExtensions.a11y.enabled",
        resolveA11yEnabled(project, extension).map { it.toString() },
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
      // B2.2 phase 1 — `composeai.daemon.previewsJsonPath`. Same path as the renderPreviews
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
      this.outputFile.set(previewOutputDir.map { it.file("daemon-launch.json") })
    }
  }

  /**
   * Lazy holder for data-product-related system properties on the `renderPreviews` `Test` task.
   * Using a CommandLineArgumentProvider — instead of `test.systemProperty(...)` — means the values
   * are resolved at task execution time, so flipping the underlying Gradle property doesn't
   * invalidate the configuration cache. Each `@Input` participates in the task's own up-to-date
   * check, so toggling a11y correctly re-runs rendering.
   */
  internal class AccessibilitySystemPropsProvider(
    @get:org.gradle.api.tasks.Input val enabled: org.gradle.api.provider.Provider<Boolean>,
    @get:org.gradle.api.tasks.Input val annotate: org.gradle.api.provider.Provider<Boolean>,
    @get:org.gradle.api.tasks.Input val outputDir: org.gradle.api.provider.Provider<String>,
    @get:org.gradle.api.tasks.Input val debug: org.gradle.api.provider.Provider<String>,
  ) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
      listOf(
        "-Dcomposeai.a11y.enabled=${enabled.get()}",
        "-Dcomposeai.a11y.annotate=${annotate.get()}",
        "-Dcomposeai.a11y.outputDir=${outputDir.get()}",
        "-Dcomposeai.a11y.debug=${debug.get()}",
      )
  }

  /**
   * Returns a lazy `Provider<Boolean>` for the effective a11y data-product selection. The
   * `-PcomposePreview.previewExtensions.a11y.enableAllChecks=<true|false>` Gradle property enables
   * every a11y check; `-PcomposePreview.previewExtensions.a11y.checks=atf,hierarchy` enables only
   * named a11y checks.
   *
   * **Deliberately returns a Provider, not a Boolean.** Reading `.get()` at configuration time keys
   * the configuration cache on the current property value, which means every VSCode toggle would
   * invalidate the cache and pay a ~5-10s reconfigure cost. Consumers should pass this provider to
   * task `onlyIf`, `CommandLineArgumentProvider` inputs, etc. — those evaluate it at
   * task-graph-resolution time without cache invalidation.
   */
  internal fun resolveA11yEnabled(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): org.gradle.api.provider.Provider<Boolean> {
    val a11y = a11yExtension(extension)
    val genericA11y = extension.previewExtensions.extensions.findByName("a11y")
    val genericAllChecks =
      genericA11y?.allChecksEnabled ?: project.providers.provider<Boolean> { false }
    val configuredAllChecks =
      a11y.allChecksEnabled.zip(genericAllChecks) { typed, generic -> typed || generic }
    val genericChecks =
      genericA11y?.checks
        ?: project.objects.listProperty(String::class.java).convention(emptyList())
    val wholeExtension =
      project.providers
        .gradleProperty("composePreview.previewExtensions.a11y.enableAllChecks")
        .map { it.toBooleanStrictOrNull() ?: false }
        .orElse(configuredAllChecks)
    val selectedChecks =
      project.providers
        .gradleProperty("composePreview.previewExtensions.a11y.checks")
        .map { raw -> parseCheckList(raw).any { it in A11Y_CHECK_IDS } }
        .orElse(
          a11y.checks.zip(genericChecks) { typedChecks, genericChecks ->
            (typedChecks + genericChecks).any { it in A11Y_CHECK_IDS }
          }
        )
    return wholeExtension.zip(selectedChecks) { whole, selected -> whole || selected }
  }

  /** Same config-cache-friendly treatment for `annotateScreenshots`. See [resolveA11yEnabled]. */
  internal fun resolveA11yAnnotate(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): org.gradle.api.provider.Provider<Boolean> =
    project.providers
      .gradleProperty("composePreview.previewExtensions.a11y.annotateScreenshots")
      .map { it.toBooleanStrictOrNull() ?: true }
      .orElse(a11yExtension(extension).annotateScreenshots)

  private fun a11yExtension(extension: PreviewExtension): A11yPreviewExtension =
    extension.previewExtensions.a11y

  private fun parseCheckList(raw: String): Set<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

  private val A11Y_CHECK_IDS =
    setOf("atf", "hierarchy", "overlay", "a11y/atf", "a11y/hierarchy", "a11y/overlay")

  internal fun resolveComposeAiTraceEnabled(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): org.gradle.api.provider.Provider<Boolean> {
    val typed = extension.previewExtensions.composeAiTrace
    val genericTrace = extension.previewExtensions.extensions.findByName("composeAiTrace")
    val genericAllChecks =
      genericTrace?.allChecksEnabled ?: project.providers.provider<Boolean> { false }
    val configuredAllChecks =
      typed.allChecksEnabled.zip(genericAllChecks) { typedEnabled, genericEnabled ->
        typedEnabled || genericEnabled
      }
    val genericChecks =
      genericTrace?.checks
        ?: project.objects.listProperty(String::class.java).convention(emptyList())
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
   * Returned as a `Provider<String>` for the same reason as [resolveA11yEnabled]: feeding `.get()`
   * to a `CommandLineArgumentProvider` means the tier is resolved at task-execution time, so VS
   * Code flipping the property between saves doesn't invalidate the configuration cache.
   */
  internal fun resolveTier(
    project: org.gradle.api.Project
  ): org.gradle.api.provider.Provider<String> =
    project.providers
      .gradleProperty("composePreview.tier")
      .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
      .orElse("full")

  /**
   * Lazy holder for the render-tier system property on the `renderPreviews` `Test` task. Same
   * pattern as [AccessibilitySystemPropsProvider] — the Provider is `@Input`, so flipping
   * `-PcomposePreview.tier` re-runs the task without invalidating the configuration cache.
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
   *
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
   * `gradle/libs.versions.toml`, every `build.gradle.kts` / `build.gradle` reachable from the root
   * project's allprojects, `settings.gradle.kts` / `settings.gradle`, `gradle.properties`,
   * `local.properties`. Only files that exist on disk are returned (a missing `local.properties` is
   * the common case in CI; we don't want a ghost path in the daemon's hash baseline).
   *
   * **Cheap-signal evolution.** The set is computed at task-action time, not at configuration time,
   * so a `build.gradle.kts` added under a freshly-included subproject *before* the next
   * `composePreviewDaemonStart` re-run is picked up. Subprojects added *after* the daemon already
   * spawned with the previous list won't be in the daemon's baseline — but adding a subproject is
   * itself a `settings.gradle.kts` edit, which IS in the cheap-signal set, so the very edit that
   * adds it triggers the Tier-1 dirty path. Net: no missed-dirty case under realistic editor
   * workflows; the only edge case is hand-creating a `subproject/build.gradle.kts` without touching
   * `settings.gradle.kts`, which would require manual Gradle re-run anyway.
   */
  private fun collectCheapSignalFiles(project: org.gradle.api.Project): List<java.io.File> {
    val out = LinkedHashSet<java.io.File>()
    val rootProject = project.rootProject
    listOf("gradle/libs.versions.toml").forEach { out += rootProject.file(it) }
    listOf("settings.gradle.kts", "settings.gradle", "gradle.properties", "local.properties")
      .forEach { out += rootProject.file(it) }
    rootProject.allprojects.forEach { sub ->
      out += sub.file("build.gradle.kts")
      out += sub.file("build.gradle")
    }
    // Only emit paths that actually exist — missing files contribute their absolute path string
    // to the daemon's hash, but emitting `gradle/libs.versions.toml` for a project that doesn't
    // use a TOML catalog would brand every daemon classpath fingerprint with a ghost path. The
    // daemon's [ClasspathFingerprint] handles missing files defensively even when they are in
    // its list, but the gradle plugin's role is to feed it real paths only.
    return out.filter { it.isFile }
  }
}
