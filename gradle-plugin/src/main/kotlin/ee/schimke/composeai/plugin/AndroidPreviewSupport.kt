package ee.schimke.composeai.plugin

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
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
        variant.name,
        androidComponents.sdkComponents.bootClasspath,
      )
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
    variantName: String,
    bootClasspath: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>,
  ) {
    val capVariant = variantName.cap()
    val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")
    val artifactType = Attribute.of("artifactType", String::class.java)

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

    val discoverTask =
      ComposePreviewTasks.registerDiscoverTask(
        project,
        sourceClassDirs,
        dependencyConfigName,
        previewOutputDir,
        extension,
      ) {
        dependsOn("compile${capVariant}Kotlin")
        if (screenshotTestEnabled) {
          dependsOn("compile${capVariant}ScreenshotTestKotlin")
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

    // No version: relies on the consumer's Compose BOM (or direct Compose
    // dep) to resolve these artifacts. Projects using
    // `implementation(platform(libs.compose.bom.stable))` pick up the aligned
    // version automatically; projects without a BOM need to add one (a
    // reasonable ask — the plugin is for Compose apps).
    if (manageDependencies) {
      project.dependencies.add("testImplementation", "androidx.compose.ui:ui-test-manifest")
      project.dependencies.add("testImplementation", "androidx.compose.ui:ui-test-junit4")
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-manifest",
        configuration = "testImplementation",
        outcome = "APPLIED",
        reason = "merges ComponentActivity into the unit-test manifest for renderer",
      )
      recordInjectedDependency(
        project,
        injectedDependencies,
        coordinate = "androidx.compose.ui:ui-test-junit4",
        configuration = "testImplementation",
        outcome = "APPLIED",
        reason = "provides createAndroidComposeRule / mainClock used by renderer",
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

        // GoogleFont interceptor cache — defaults to
        // `<project>/.compose-preview-history/fonts/`, same root the
        // history task uses, so committed TTFs sit beside committed PNGs.
        // The renderer class no-ops when this property is absent, so the
        // feature is fully additive for existing consumers.
        val fontsCacheDir =
          extension.historyDir
            .orElse(project.layout.projectDirectory.dir(".compose-preview-history"))
            .map { it.dir("fonts").asFile.absolutePath }
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
            fontsCacheDir = fontsCacheDir.get(),
            fontsOffline = fontsOffline.get(),
          )
          .forEach { (k, v) -> systemProperty(k, v) }

        // ATF flags are routed through a CommandLineArgumentProvider
        // rather than `systemProperty(...)` so toggling the `-P` override
        // doesn't invalidate the Gradle configuration cache. `systemProperty`
        // evaluates its value eagerly at configuration time — the provider
        // we'd read there becomes part of the config-cache key, so flipping
        // `-PcomposePreview.accessibilityChecks.enabled` forces a ~5-10s
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
        // how previous modules silently vanished from `preview_main`.
        outputs.dir(rendersDirectory).withPropertyName("rendersDir")

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
        listOf("process${capVariant}Resources", "generate${capVariant}UnitTestConfig").forEach {
          taskName ->
          if (project.tasks.findByName(taskName) != null) {
            dependsOn(taskName)
          }
        }
        if (compileShardsTask != null) {
          dependsOn(compileShardsTask)
        }
      }

    // `verifyAccessibility` is ALWAYS registered so toggling
    // `-PcomposePreview.accessibilityChecks.enabled` doesn't change the
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
        failOnErrors.set(extension.accessibilityChecks.failOnErrors)
        failOnWarnings.set(extension.accessibilityChecks.failOnWarnings)
        dependsOn(renderTask)
        onlyIf("composePreview.accessibilityChecks.enabled") { a11yEnabledProvider.get() }
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
    // even when `experimental.daemon.enabled = false` (it then refuses to
    // launch — see [DaemonClasspathDescriptor] KDoc). Inputs mirror the
    // renderPreviews task's so the spawned daemon JVM is byte-for-byte
    // equivalent. See `docs/daemon/DESIGN.md` § 4 / § 6.
    //
    // Built lazily via providers so the AGP unit-test task's javaLauncher
    // resolves at execution time (same reason renderPreviews above defers it).
    val daemonAgpTestTask = project.provider {
      project.tasks.findByName("test${capVariant}UnitTest") as? org.gradle.api.tasks.testing.Test
    }
    val daemonFontsCacheDir =
      extension.historyDir
        .orElse(project.layout.projectDirectory.dir(".compose-preview-history"))
        .map { it.dir("fonts").asFile.absolutePath }
    val daemonFontsOffline =
      project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
    project.tasks.register(
      "composePreviewDaemonStart",
      ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask::class.java,
    ) {
      this.modulePath.set(project.path)
      this.variant.set(variantName)
      this.daemonEnabled.set(extension.experimental.daemon.enabled)
      this.maxHeapMb.set(extension.experimental.daemon.maxHeapMb)
      this.maxRendersPerSandbox.set(extension.experimental.daemon.maxRendersPerSandbox)
      this.warmSpare.set(extension.experimental.daemon.warmSpare)
      // Conventional entry-point name — `renderer-android-daemon` / Stream B
      // (task B1.1) will provide the implementation. Surfacing it as a
      // Property leaves room for future variants (foreground / debug) without
      // schema churn. See [DaemonBootstrapTask] / [DaemonClasspathDescriptor].
      this.mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Inherit AGP's unit-test javaLauncher exactly the way renderPreviews
      // does (see line ~802 above) so the daemon runs on the project's
      // configured toolchain rather than the first `java` on PATH.
      this.javaLauncher.set(
        project.provider {
          daemonAgpTestTask.orNull?.javaLauncher?.orNull?.executablePath?.asFile?.absolutePath
        }
      )
      // Same FileCollection the renderPreviews `Test` task assembles, plus
      // the AGP unit-test task's classpath (R.jar etc.) appended at the
      // tail — see line ~764 for the rationale. Composed lazily to defer
      // `findByName("test${capVariant}UnitTest")` resolution.
      //
      // TODO (Stream B / task B1.1): prepend `renderer-android-daemon`'s
      // configuration here once the module exists, so [mainClass] is
      // loadable. Until then, the descriptor's `enabled: false` default
      // gates the VS Code extension from spawning a JVM that would fail
      // with ClassNotFoundException.
      this.classpath.from(resolvedClasspath)
      this.classpath.from(
        project.provider { daemonAgpTestTask.orNull?.testClassesDirs ?: project.files() }
      )
      this.classpath.from(
        project.provider { daemonAgpTestTask.orNull?.classpath ?: project.files() }
      )
      // Static JVM open flags from the shared helper, plus the
      // daemon-specific heap ceiling. AGP test task's own jvmArgs are
      // intentionally NOT inherited here — they're test-runner specific
      // (e.g. `-ea` and JUnit-internal opens) and may collide with the
      // daemon's own runner. Stream B can opt back in if needed.
      this.jvmArgs.set(
        project.provider {
          AndroidPreviewClasspath.buildJvmArgs() +
            "-Xmx${extension.experimental.daemon.maxHeapMb.get()}m"
        }
      )
      // Same path-bearing system properties the renderPreviews Test task
      // uses, plus daemon-specific keys for [DaemonExtension] config the
      // daemon reads at startup. Built eagerly here (no
      // CommandLineArgumentProvider equivalent for descriptor JSON) — VS
      // Code-driven flips of `composePreview.tier` / a11y don't apply to
      // the daemon yet (it runs the focused-render path), so config-cache
      // invalidation isn't a concern in this task the way it is for
      // renderPreviews.
      //
      // TODO (Stream B): wire `composeai.daemon.protocolVersion`,
      // `composeai.daemon.idleTimeoutMs`, and any other daemon-runtime
      // sysprops once the daemon module is on the classpath. Until then
      // the four extension-derived keys below are the contract.
      this.systemProperties.set(
        project.provider {
          val base =
            AndroidPreviewClasspath.buildSystemProperties(
              manifestPath = manifestFile.get(),
              rendersDir = rendersDir.get(),
              fontsCacheDir = daemonFontsCacheDir.get(),
              fontsOffline = daemonFontsOffline.get(),
            )
          val daemonProps =
            linkedMapOf(
              "composeai.daemon.maxHeapMb" to
                extension.experimental.daemon.maxHeapMb.get().toString(),
              "composeai.daemon.maxRendersPerSandbox" to
                extension.experimental.daemon.maxRendersPerSandbox.get().toString(),
              "composeai.daemon.warmSpare" to
                extension.experimental.daemon.warmSpare.get().toString(),
              "composeai.daemon.modulePath" to project.path,
            )
          LinkedHashMap(base).apply { putAll(daemonProps) }
        }
      )
      this.workingDirectory.set(project.projectDir.absolutePath)
      this.manifestPath.set(manifestFile)
      this.outputFile.set(previewOutputDir.map { it.file("daemon-launch.json") })
    }
  }

  /**
   * Lazy holder for ATF-related system properties on the `renderPreviews` `Test` task. Using a
   * CommandLineArgumentProvider — instead of `test.systemProperty(...)` — means the values are
   * resolved at task execution time, so flipping the underlying Gradle property doesn't invalidate
   * the configuration cache. Each `@Input` participates in the task's own up-to-date check, so
   * toggling a11y correctly re-runs rendering.
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
   * Returns a lazy `Provider<Boolean>` for the effective `accessibilityChecks.enabled` value. The
   * `-PcomposePreview.accessibilityChecks.enabled=<true|false>` Gradle property WINS over the
   * extension block — so VSCode (or a one-off CLI invocation) can flip the feature on for a single
   * run without touching `build.gradle.kts`.
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
  ): org.gradle.api.provider.Provider<Boolean> =
    project.providers
      .gradleProperty("composePreview.accessibilityChecks.enabled")
      .map { it.toBooleanStrictOrNull() ?: false }
      .orElse(extension.accessibilityChecks.enabled)

  /** Same config-cache-friendly treatment for `annotateScreenshots`. See [resolveA11yEnabled]. */
  internal fun resolveA11yAnnotate(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): org.gradle.api.provider.Provider<Boolean> =
    project.providers
      .gradleProperty("composePreview.accessibilityChecks.annotateScreenshots")
      .map { it.toBooleanStrictOrNull() ?: true }
      .orElse(extension.accessibilityChecks.annotateScreenshots)

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
    if (
      tilesRendererRequired &&
        !hasCoord("${variantName}Implementation", "androidx.wear.tiles", "tiles-renderer")
    ) {
      missing += "${variantName}Implementation(\"androidx.wear.tiles:tiles-renderer\")"
    }

    if (missing.isNotEmpty()) {
      val suffix =
        if (tilesRendererRequired) {
          "\n  tiles-renderer required: wear.tiles signal was matched on this module."
        } else ""
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
}
