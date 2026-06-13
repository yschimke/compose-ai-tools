package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

private val previewManifestJson = Json { ignoreUnknownKeys = true }

/**
 * AGP-free task wiring shared between the Android and desktop code paths. Keeping this out of
 * [ComposePreviewPlugin] (or inside [AndroidPreviewSupport], which does transitively reference AGP)
 * means the desktop path can reuse these helpers without dragging AGP onto the classpath.
 */
internal object ComposePreviewTasks {
  /**
   * Candidate Kotlin compile task names for the desktop / KMP-flavoured side, in priority order.
   * The first name that resolves at configuration time is wired as the upstream of both
   * `composePreviewDiscover` and `composePreviewCompile`.
   */
  private val DESKTOP_COMPILE_TASK_CANDIDATES: List<String> =
    listOf("compileKotlinJvm", "compileKotlinDesktop", "compileAndroidMain", "compileKotlin")

  /**
   * Candidate resource-processing task names for the desktop / KMP side. The render path links the
   * consumer's processed resources onto the renderer classpath (so a `@Preview` can load a
   * classpath resource — a Lottie `.json`/`.lottie` asset, a bundled font, an image — via the
   * classloader), and those tasks are what stage `src/main/resources/` into `build/resources/main`
   * (kotlin("jvm")) or `build/processedResources/<target>/main` (KMP). Mirrors
   * [DESKTOP_COMPILE_TASK_CANDIDATES]; missing names are ignored by the lazy `tasks.matching`
   * wiring.
   */
  private val DESKTOP_RESOURCE_TASK_CANDIDATES: List<String> =
    listOf("jvmProcessResources", "desktopProcessResources", "processResources")

  /**
   * Creates (or reuses) a resolvable configuration named [configName] populated with the
   * `:renderer-desktop` JVM renderer — the in-tree project when this build contains it (so live
   * renderer edits land without a publish), else the published
   * `ee.schimke.composeai:renderer-desktop:<plugin-version>` JAR. Used both by the desktop render
   * task and by the Android `composePreviewRenderLottie` task, which renders `kind=LOTTIE` assets
   * through the JVM Compottie path (the asset is portable IR — no Android/Robolectric player). The
   * default add is skipped when the consumer already populated [configName] themselves, so an
   * explicit `dependencies { "<configName>"(files(...)) }` override still wins.
   */
  internal fun ensureRendererDesktopConfig(
    project: Project,
    configName: String,
  ): org.gradle.api.artifacts.Configuration {
    val rendererConfig = project.configurations.maybeCreate(configName)
    rendererConfig.isCanBeResolved = true
    rendererConfig.isCanBeConsumed = false
    val rendererProjectDir = project.rootDir.resolve("renderers/desktop")
    val useLocalRenderer =
      rendererProjectDir.resolve("build.gradle.kts").exists() ||
        rendererProjectDir.resolve("build.gradle").exists()
    project.afterEvaluate {
      if (rendererConfig.dependencies.isNotEmpty()) return@afterEvaluate
      if (useLocalRenderer) {
        try {
          project.dependencies.add(
            configName,
            project.dependencies.project(mapOf("path" to ":renderer-desktop")),
          )
        } catch (e: org.gradle.api.UnknownProjectException) {
          project.logger.debug(
            "compose-ai-tools: :renderer-desktop project not found, falling back to Maven",
            e,
          )
          project.dependencies.add(
            configName,
            "ee.schimke.composeai:renderer-desktop:${PluginVersion.value}",
          )
        }
      } else {
        project.dependencies.add(
          configName,
          "ee.schimke.composeai:renderer-desktop:${PluginVersion.value}",
        )
      }
    }
    return rendererConfig
  }

  fun registerDesktopTasks(project: Project, extension: PreviewExtension) {
    val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

    // `classes/kotlin/android/main` (issue #248): the
    // `com.android.kotlin.multiplatform.library` plugin (AGP 9's replacement
    // for nesting `com.android.library` inside KMP) compiles its single
    // `android` target into the canonical KMP layout
    // `build/classes/kotlin/<targetName>/<compilationName>` — same convention
    // as `kotlin/jvm/main` and `kotlin/desktop/main` for those targets — so
    // adding it to the candidate list lets the desktop renderer pick up
    // `@Preview` functions in `androidMain` without any classic-AGP wiring.
    // [DiscoverPreviewsTask] silently skips dirs that don't exist, so listing
    // it on JVM-only consumers is harmless.
    val sourceClassDirs =
      project.files(
        project.layout.buildDirectory.dir("classes/kotlin/main"),
        project.layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        project.layout.buildDirectory.dir("classes/kotlin/desktop/main"),
        project.layout.buildDirectory.dir("classes/kotlin/android/main"),
      )

    // Processed-resources output dirs, in the same priority order as [sourceClassDirs]. Linked onto
    // the render classpath so a `@Preview` can load a classpath resource (e.g. a Lottie `.json`
    // asset) at render time. Non-existent dirs resolve to nothing — harmless on resource-free
    // modules. The resource-processing tasks are wired as render dependencies below so the dirs are
    // populated before the render subprocess launches.
    val sourceResourceDirs =
      project.files(
        project.layout.buildDirectory.dir("resources/main"),
        project.layout.buildDirectory.dir("processedResources/jvm/main"),
        project.layout.buildDirectory.dir("processedResources/desktop/main"),
      )

    // Same single-variant story for the runtime classpath: KMP-Android
    // exposes `androidRuntimeClasspath` (no `debug` / `release` prefix —
    // confirmed against AGP 9's KMP migration guide). Listed BEFORE
    // `runtimeClasspath` so a project applying both kotlin("multiplatform")
    // *and* the KMP-Android plugin resolves AAR deps via the Android
    // resolvable, where AGP's artifact transforms hand back the extracted
    // `classes.jar` (dependencyJars's `android-classes` artifact view).
    //
    // Resolved lazily (called from inside each task's `tasks.register {}`
    // configuration block, which Gradle invokes only when the task is
    // realised). For KMP-Android-shared modules (`samples:cmp-shared`-shape
    // — issue: validate task hitting `androidRuntimeClasspath`) the
    // `kotlin { jvm("desktop") }` block runs AFTER `pluginManager.withPlugin`
    // fires, so `desktopRuntimeClasspath` doesn't exist yet at the moment
    // [registerDesktopTasks] is invoked. Eager resolution would fall through
    // to `androidRuntimeClasspath` and pin the desktop renderer to
    // `androidx.compose.ui:ui-android` AAR variants, which the new
    // [ValidateComposePreviewClasspathTask] correctly rejects (and
    // `ImageComposeScene` would crash on too).
    val resolveDependencyConfigName: () -> String = {
      listOf(
          "jvmRuntimeClasspath",
          "desktopRuntimeClasspath",
          "androidRuntimeClasspath",
          "runtimeClasspath",
        )
        .firstOrNull { project.configurations.findByName(it) != null } ?: "runtimeClasspath"
    }

    val discoverTask =
      registerDiscoverTask(
        project,
        sourceClassDirs,
        resolveDependencyConfigName,
        previewOutputDir,
        extension,
      ) {
        onlyIf { extension.enabled.get() }
        // `compileAndroidMain` is the lifecycle task for the KMP-Android target's `main`
        // compilation (which depends on the underlying `compileAndroidMainKotlin`-shaped Kotlin
        // compile under the hood). Use lazy matching so compile tasks registered after this plugin
        // block are still wired into discovery.
        dependsOn(project.tasks.matching { it.name in DESKTOP_COMPILE_TASK_CANDIDATES })
        // Lottie asset discovery scans the consumer's processed resources. Desktop-only for now —
        // the Android discover task (AndroidPreviewSupport) doesn't wire this, so `.json`/`.lottie`
        // assets surface as previews on the JVM/Desktop backend where Compottie renders them.
        resourceDirs.from(sourceResourceDirs)
        dependsOn(project.tasks.matching { it.name in DESKTOP_RESOURCE_TASK_CANDIDATES })
      }
    registerCompileOnlyTask(project, extension, DESKTOP_COMPILE_TASK_CANDIDATES)

    val rendererConfig = ensureRendererDesktopConfig(project, "composePreviewRenderer")
    // Resolve the renderer in the consumer's dependency graph so a single coherent Skiko / Compose
    // version wins (issue #1844). See [alignDesktopToolWithConsumerGraph].
    alignDesktopToolWithConsumerGraph(project, rendererConfig, resolveDependencyConfigName)

    val renderClasspathGuard =
      registerDesktopClasspathGuard(
        project = project,
        taskName = "validateComposePreviewDesktopRenderClasspath",
        dependencyConfigName = resolveDependencyConfigName,
        toolClasspath = rendererConfig,
      )
    val renderTask =
      project.tasks.register("composePreviewRender", RenderPreviewsTask::class.java) {
        onlyIf { extension.enabled.get() }
        previewsJson.set(previewOutputDir.map { it.file("previews.json") })
        outputDir.set(previewOutputDir.map { it.dir("renders") })
        // `@ScrollingPreview(modes = [LONG, GIF])` outputs land here (sibling of `renders/`).
        // Declared as a tracked task output so Gradle's caching + up-to-date checks cover the
        // long-PNG and GIF artifacts; older Android Test wiring uses the same `data/` subdir.
        dataProductsDir.set(previewOutputDir.map { it.dir("data") })
        renderBackend.set("desktop")
        tier.set(tierProperty(project))
        displayFilterFilters.set(AndroidPreviewSupport.resolveDisplayFilterFilters(project))
        renderClasspath.from(sourceClassDirs)
        // Consumer's processed resources so previews can load classpath assets (Lottie `.json`,
        // fonts, images) at render time. Depend on the resource-processing task that stages them.
        renderClasspath.from(sourceResourceDirs)
        // Feed configurations through a lazily-resolved `incoming.artifactView {}.files` view
        // rather than the raw `Configuration`, so the @Classpath collection never pins a live
        // `Configuration` into the task's config-cache `__classpath__` field (the serialization
        // failure issue #1796 hit on the desktop validate guards). The empty view yields the same
        // default resolution — module + file dependencies alike — just lazily and serializably.
        project.configurations.findByName(resolveDependencyConfigName())?.let {
          renderClasspath.from(it.incoming.artifactView {}.files)
        }
        renderClasspath.from(rendererConfig.incoming.artifactView {}.files)
        group = "compose preview"
        description = "Render all previews to PNG"
        dependsOn(discoverTask)
        dependsOn(renderClasspathGuard)
        dependsOn(project.tasks.matching { it.name in DESKTOP_RESOURCE_TASK_CANDIDATES })
      }
    registerRenderAllPreviews(project, extension, renderTask, previewOutputDir)

    registerBundleTask(
      project = project,
      extension = extension,
      previewOutputDir = previewOutputDir,
      sourceClassDirs = sourceClassDirs,
      resolveDependencyConfigName = resolveDependencyConfigName,
      discoverTaskName = "composePreviewDiscover",
    )

    // Stage-2 BTA configurations + afterEvaluate dep wiring must happen at
    // plugin-apply / config time, BEFORE `registerDesktopDaemonStartTask`'s
    // `task.register {…}` block (which runs at task-realization time, by which point
    // Gradle's MutationGuard rejects `Project.afterEvaluate(...)` calls). Lifted out
    // here so the registration block is a pure consumer of the resulting configs.
    setupBtaConfigurations(project, extension)

    registerDesktopDaemonStartTask(
      project,
      extension,
      previewOutputDir,
      sourceClassDirs,
      sourceResourceDirs,
      resolveDependencyConfigName,
    )
  }

  /**
   * Register `composePreviewBundle` — packs the consumer module + selected previews into a portable
   * PNG+ZIP polyglot. Inputs reuse the existing `previews.json` from `composePreviewDiscover` and
   * the same module class dirs + runtime classpath that `composePreviewRender` consumes; the cover
   * PNG is read from the `renders/` directory when present (lazy — task still runs in "no-render"
   * mode and writes a stub gray PNG cover).
   *
   * Selection comes from project properties (`-PbundlePreviewIds=…`) or CLI input. The bundle task
   * does NOT depend on `composePreviewRender` — bundling without pre-rendering is the common case
   * for the CLI's "pack and share" flow. Callers who want the cover populated should run
   * `composePreviewRender` first (the CLI shells through `composePreviewRender
   * composePreviewBundle` as a single Gradle invocation).
   */
  internal fun registerBundleTask(
    project: Project,
    extension: PreviewExtension,
    previewOutputDir: Provider<Directory>,
    sourceClassDirs: FileCollection,
    resolveDependencyConfigName: () -> String,
    discoverTaskName: String,
    // Recorded into `bundle.json`'s `backend` field. "desktop" for the CMP/JVM path; the Android
    // path (AndroidPreviewSupport) passes "android" so players know which renderer the bundle was
    // packed for. Packing itself is backend-agnostic — the closure walk only sees JVM bytecode.
    backendId: String = "desktop",
    // (v6 Android) Wired only on the Android path so a protolayout-IR bundle can carry the merged
    // resource APK + manifest + generated R classes the tile renderer needs on a detached daemon.
    // Null on desktop — no Android resource carriage.
    //
    // `androidUnitTestConfigFiles` is AGP's `unit_test_config_directory` (carrying
    // `test_config.properties`) UNIONED with the merged resource APK + merged manifest those
    // properties point at. The pack action reads the APK/manifest by the absolute paths in
    // `test_config.properties`, so they must also be declared here as tracked `@InputFiles`
    // content — otherwise the cacheable task could restore a stale bundle when those generated
    // files change while their paths stay the same. `androidUnitTestRuntimeClasspath` is a lazy
    // supplier of the AGP unit-test `Test` task's classpath (the SAME source the render path links
    // its resources + library R classes from) — invoked inside the task-config lambda so the
    // `test<Variant>UnitTest` task exists by then. With non-transitive R, the tile renderer's
    // `R$style` is generated only into that classpath's merged R.jar — a raw file dep without the
    // `artifactType=jar` attribute, so the bundle's filtered `dependencyJars` view drops it.
    androidUnitTestConfigFiles: FileCollection? = null,
    androidUnitTestRuntimeClasspath: (() -> FileCollection?)? = null,
  ) {
    val previewIdsProperty: Provider<List<String>> =
      project.providers.gradleProperty("bundlePreviewIds").map { raw ->
        BundlePreviewIds.parse(raw)
      }
    val outputProperty: Provider<String> = project.providers.gradleProperty("bundleOutput")
    // `-PbundleEmbedDeps=true` → schema-v3 `resolution = "embedded"`: carry reachable third-party
    // jars inside the bundle's `libs/` instead of referencing Maven coordinates. Larger file, but
    // renders with no network / no consumer build system.
    val embedDepsProperty: Provider<Boolean> =
      project.providers.gradleProperty("bundleEmbedDeps").map { it.toBoolean() }
    // `-PbundleIncludeDataExtensions=true` → schema-v7: carry the aggregated per-extension data
    // reports (a11y findings, theme tokens, …) named by `previews.json`'s `dataExtensionReports`
    // under `extensions/<id>.json` so a detached reader can surface them without re-rendering.
    val includeDataExtensionsProperty: Provider<Boolean> =
      project.providers.gradleProperty("bundleIncludeDataExtensions").map { it.toBoolean() }
    val pluginVersionProperty = PluginVersion.value

    val artifactTypeAttr = Attribute.of("artifactType", String::class.java)
    val depConfig = project.configurations.findByName(resolveDependencyConfigName())
    // `artifactType=jar` view: AAR consumers transform to extracted classes.jar; pure JVM consumers
    // pass through. Either way we get real jars on the closure walk. The coordinate map below MUST
    // be built from THIS SAME view's resolvedArtifacts, not the configuration's untransformed
    // artifacts — otherwise an AAR dep's transformed classes.jar path (what `dependencyJars` and
    // the
    // closure walk see) wouldn't match the coordinate-map key (the untransformed `.aar` path), so
    // the task would treat the Maven dep as an anonymous/project jar and inline it instead of
    // recording a `ClasspathEntry.Maven`. On JVM this view is a passthrough, so desktop is
    // unchanged; on Android it's what makes coordinate-mode bundles stay small + re-resolvable.
    val depJarView =
      depConfig?.incoming?.artifactView { attributes.attribute(artifactTypeAttr, "jar") }
    val depJarFiles = depJarView?.files
    // The `artifactType=jar` view extracts every AAR to its `classes.jar`, erasing the aar/jar
    // distinction — but the player's `CoordinateResolver` needs the real packaging to find the
    // artifact (`<artifact>-<version>.aar`) in a Maven/Gradle cache and unpack its classes. So read
    // the *untransformed* artifacts too and key each component's packaging off its file extension
    // (Android deps resolve to `.aar`, JVM deps to `.jar`), then stamp it into the coordinate
    // below.
    //
    // This read goes through a `lenient(true)` artifactView rather than the raw
    // `incoming.artifacts`. The raw read selects artifacts using the configuration's full runtime
    // attributes, which a dependency exposing AGP secondary variants without the standard
    // `org.gradle.category` / jvm-environment / `kotlin.platform.type` attributes (e.g. an Android
    // library relying on AGP's built-in Kotlin, consumed on an app's `…RuntimeClasspath`) can't
    // satisfy — turning packaging detection into a hard `AmbiguousArtifactsFailure` that fails the
    // whole bundle. `lenient(true)` skips any such unselectable dep here; it simply isn't keyed in
    // the packaging map and falls back to the `"jar"` default at the coordinate below. The
    // `artifactType=jar` view that feeds the actual classpath/closure walk is unaffected — it
    // selects each dep's `jar` secondary variant unambiguously.
    val typeByComponent: Provider<Map<String, String>> =
      depConfig
        ?.incoming
        ?.artifactView { lenient(true) }
        ?.artifacts
        ?.resolvedArtifacts
        ?.map { artifacts ->
          artifacts.associate { artifact ->
            artifact.id.componentIdentifier.displayName to
              if (artifact.file.name.endsWith(".aar", ignoreCase = true)) "aar" else "jar"
          }
        } ?: project.providers.provider { emptyMap<String, String>() }
    // Map each resolved dependency jar to a coordinate string the task action can fold into
    // `bundle.json`'s `classpath`:
    // - `maven:<group>:<artifact>:<version>:<aar|jar>` for Maven-resolved deps (the player resolves
    //   at open time — small bundle, no inlined jars).
    // - `project:<gradle path>` for project-local deps (the task inlines those into the bundle
    //   since they can't be re-resolved from Maven).
    //
    // Resolution happens via `Provider` transformations, so this stays config-cache-safe. The
    // transformed artifact keeps its original `componentIdentifier` (the Maven module / project),
    // so coordinates resolve correctly even though `.file` points at the extracted classes.jar.
    val coordMapProvider: Provider<Map<String, String>> =
      depJarView?.artifacts?.resolvedArtifacts?.zip(typeByComponent) { artifacts, typeByComponentMap
        ->
        artifacts.associate { artifact ->
          val id = artifact.id.componentIdentifier
          val value =
            when (id) {
              is org.gradle.api.artifacts.component.ModuleComponentIdentifier ->
                "maven:${id.group}:${id.module}:${id.version}:${typeByComponentMap[id.displayName] ?: "jar"}"
              is org.gradle.api.artifacts.component.ProjectComponentIdentifier ->
                "project:${id.projectPath}"
              else -> "unknown:${id.displayName}"
            }
          artifact.file.absolutePath to value
        }
      } ?: project.providers.provider { emptyMap<String, String>() }

    val defaultOutput = previewOutputDir.map { it.file("bundle.png").asFile }
    val resolvedOutput = outputProperty.map { java.io.File(it) }.orElse(defaultOutput)

    // Consumer-module resources directory. The Kotlin / Compose plugins write `src/main/resources/`
    // (string ids referenced from bytecode via Compose Resources, fonts, etc.) into the standard
    // `build/resources/main` (kotlin("jvm")) or `build/processedResources/<sourceSet>/main` (KMP).
    // Wire whichever exists so packed bundles still contain classpath resources the closure walk
    // can't introspect. Task action treats a missing dir as "no resources" (the `@Optional` +
    // `orNull?.isDirectory` check inside `buildJar`).
    val moduleResourcesDirProvider: Provider<Directory> =
      project.providers.provider {
        val candidates =
          listOf(
            project.layout.buildDirectory.dir("resources/main").orNull,
            project.layout.buildDirectory.dir("processedResources/jvm/main").orNull,
            project.layout.buildDirectory.dir("processedResources/desktop/main").orNull,
          )
        candidates.firstOrNull { it != null && it.asFile.isDirectory }
      }

    project.tasks.register("composePreviewBundle", BundlePreviewTask::class.java) {
      onlyIf { extension.enabled.get() }
      previewsJson.set(previewOutputDir.map { it.file("previews.json") })
      moduleClassDirs.from(sourceClassDirs)
      moduleResourcesDir.set(moduleResourcesDirProvider)
      depJarFiles?.let { dependencyJars.from(it) }
      dependencyCoordinates.set(coordMapProvider)
      // (v6 Android) Inputs for protolayout resource carriage; null on desktop (no-op). The
      // unit-test classpath supplier is invoked here (inside the task-config lambda) so AGP's
      // `test<Variant>UnitTest` task is registered by the time we query its classpath.
      androidUnitTestConfigFiles?.let { androidUnitTestConfig.from(it) }
      androidUnitTestRuntimeClasspath?.invoke()?.let {
        this.androidUnitTestRuntimeClasspath.from(it)
      }
      // Renders dir is wired conditionally — when composePreviewRender has run, the dir exists and
      // contains PNGs. Use orNull semantics: missing dir = stub cover. `rendersDir` is the
      // @Internal
      // resolution root; `renderFiles` tracks the PNG contents as a real input so the task re-packs
      // (and the cache key changes) when renders appear/change rather than restoring a stale
      // bundle.
      rendersDir.set(previewOutputDir.map { it.dir("renders") })
      renderFiles.from(previewOutputDir.map { it.dir("renders") })
      previewIds.set(previewIdsProperty.orElse(emptyList()))
      embedDeps.set(embedDepsProperty.orElse(false))
      includeDataExtensions.set(includeDataExtensionsProperty.orElse(false))
      // Track the aggregated extension report sidecars (the render task writes them as top-level
      // `*.json` under the preview output dir) so a content change re-packs the bundle. The bundle
      // output is a `.png`, so the `*.json` filter never captures it as a circular input. Paths are
      // resolved from the manifest at pack time; this is just the up-to-date / cache-key signal.
      dataExtensionFiles.from(previewOutputDir.map { it.asFileTree.matching { include("*.json") } })
      modulePath.set(project.path)
      // (v6 Android) base dir for resolving test_config.properties' module-relative apk/manifest
      // paths. Harmless on desktop. See [BundlePreviewTask.moduleProjectDir].
      moduleProjectDir.set(project.layout.projectDirectory)
      backend.set(backendId)
      producedBy.set("compose-preview $pluginVersionProperty")
      output.set(project.layout.file(resolvedOutput))
      group = "compose preview"
      description = "Pack selected previews + minimal classpath into a portable PNG+ZIP polyglot."
      // No dependsOn composePreviewRender — bundle without a render is valid (stub cover). But
      // `renderFiles` declares the renders dir (composePreviewRender's output) as an input, so when
      // BOTH tasks are in the graph (the common `composePreviewRender composePreviewBundle` pack
      // flow, e.g. `compose-preview bundle pack`) Gradle requires a declared ordering to avoid the
      // implicit-dependency validation error. `mustRunAfter` supplies it WITHOUT pulling render in
      // when only bundle is requested — render still doesn't run unless the caller asks for it.
      mustRunAfter("composePreviewRender")
      dependsOn(discoverTaskName)
    }
  }

  /**
   * Phase 1, Stream A — desktop counterpart of [AndroidPreviewSupport.registerAndroidTasks]'s
   * `composePreviewDaemonStart` registration. Wires `:daemon:desktop` (which ships
   * [ee.schimke.composeai.daemon.DaemonMain] for the `ImageComposeScene` render path) onto a
   * [DaemonBootstrapTask][ ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask] so the VS Code
   * extension's `daemonProcess.ts` and the MCP server's `SubprocessDaemonClientFactory` can launch
   * the desktop daemon JVM directly, the same way they do for Android consumers — closing the gap
   * called out in `:daemon:desktop`'s `DaemonMain.kt` kdoc and #314 ("desktop has no
   * `composePreviewDaemonStart`").
   *
   * Gated on the in-repo `:daemon:desktop` source tree; the daemon module is intentionally NOT
   * published to Maven yet (see the kdoc on its `build.gradle.kts`), so out-of-tree consumers don't
   * get a registration. They still get the `DaemonExtension`-default `enabled = false` and the VS
   * Code extension's "no descriptor → don't spawn" behaviour, just like the Android side before
   * `:daemon:android` is published.
   */
  private fun registerDesktopDaemonStartTask(
    project: Project,
    extension: PreviewExtension,
    previewOutputDir: Provider<Directory>,
    sourceClassDirs: FileCollection,
    sourceResourceDirs: FileCollection,
    dependencyConfigName: () -> String,
  ) {
    val daemonProjectDir = project.rootDir.resolve("daemon/desktop")
    val useLocalDaemon =
      daemonProjectDir.resolve("build.gradle.kts").exists() ||
        daemonProjectDir.resolve("build.gradle").exists()

    val daemonRendererConfig =
      project.configurations.maybeCreate("composePreviewDesktopDaemon").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
      }
    // The daemon JVM puts the consumer's full runtime classpath on its parent `-cp` (below), so it
    // hits the same Skiko skew as the one-shot render path — resolve it in the consumer's graph too
    // (issue #1844). See [alignDesktopToolWithConsumerGraph].
    alignDesktopToolWithConsumerGraph(project, daemonRendererConfig, dependencyConfigName)
    if (useLocalDaemon) {
      try {
        project.dependencies.add(
          daemonRendererConfig.name,
          project.dependencies.project(mapOf("path" to ":daemon:desktop")),
        )
      } catch (e: org.gradle.api.UnknownProjectException) {
        project.logger.debug("compose-ai-tools: :daemon:desktop project not found, skipping", e)
        return
      }
    } else {
      // External-consumer mode: pull `daemon-desktop` from Maven Central — published as part of
      // PR #373's daemon-* publishing roll-out. Without this dependency the launch descriptor
      // would have no `DaemonMain` class on its classpath and the spawned JVM would die with
      // `ClassNotFoundException: ee.schimke.composeai.daemon.DaemonMain`.
      project.dependencies.add(
        daemonRendererConfig.name,
        "ee.schimke.composeai:daemon-desktop:${PluginVersion.value}",
      )
    }

    val daemonClasspathGuard =
      registerDesktopClasspathGuard(
        project = project,
        taskName = "validateComposePreviewDesktopDaemonClasspath",
        dependencyConfigName = dependencyConfigName,
        toolClasspath = daemonRendererConfig,
      )

    // Mirror the Android registration's eager-resolved values (see
    // AndroidPreviewSupport.kt) so each MapProperty / ListProperty entry's Provider chain
    // captures only serialisable references — `org.gradle.configuration-cache.problems=fail`
    // refuses anything that captures `project` / `this` task / `extension`.
    val previewsJsonProvider = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
    val rendersDirProvider = previewOutputDir.map { it.dir("renders").asFile.absolutePath }
    val outputFileProvider = previewOutputDir.map { it.file("daemon-launch.json") }
    val daemonFontsCacheDir = composeAiFontsCacheDir(project)
    val daemonFontsOffline =
      project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
    val daemonCheapSignalFiles =
      collectDesktopCheapSignalFiles(project).joinToString(java.io.File.pathSeparator) {
        it.absolutePath
      }
    val consumerBuildDir = project.layout.buildDirectory.asFile.get().absolutePath
    // KMP / JVM / Desktop / KMP-Android compile output dirs — same set
    // [registerDesktopTasks]'s `sourceClassDirs` searches, so the daemon's child
    // URLClassLoader (CLASSLOADER.md) sees the user's compiled classes when a desktop module
    // applies any of those plugins.
    val daemonUserClassMarkers =
      listOf(
        "$consumerBuildDir/classes/kotlin/main",
        "$consumerBuildDir/classes/kotlin/jvm/main",
        "$consumerBuildDir/classes/kotlin/desktop/main",
        "$consumerBuildDir/classes/kotlin/android/main",
      )

    project.tasks.register(
      "composePreviewDaemonStart",
      ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask::class.java,
    ) {
      modulePath.set(project.path)
      // Desktop daemons have no AGP variant. The string is surfaced in `daemon-launch.json`'s
      // `variant` field for debug/log purposes only — VS Code's `daemonProcess.ts` doesn't key
      // off it.
      variant.set("desktop")
      daemonEnabled.set(extension.daemon.enabled)
      maxHeapMb.set(extension.daemon.maxHeapMb)
      maxRendersPerSandbox.set(extension.daemon.maxRendersPerSandbox)
      warmSpare.set(extension.daemon.warmSpare)
      // Stage-2 BTA wiring. The configurations + dep adds are owned by
      // `wireDesktopBtaInputs` so the registration block stays scannable. Daemon JVM
      // lazily loads BTA only when the editor's save loop calls `compileSources` (gated
      // by the VS Code workspace setting `composePreview.daemon.compileInProcess`), so
      // populating the descriptor unconditionally costs only the on-disk classpath
      // resolution at config time.
      wireDesktopBtaInputs(
        project = project,
        extension = extension,
        task = this,
        userRuntimeConfig = project.configurations.findByName(dependencyConfigName()),
      )
      // `:daemon:desktop`'s `DaemonMain` and `:daemon:android`'s `DaemonMain` share the FQN
      // intentionally (see the kdoc on `daemon/desktop/.../DaemonMain.kt`). The desktop classes
      // jar is FIRST on the classpath below, so this loads the Compose-Multiplatform path.
      mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Daemon module's classes FIRST so [mainClass] resolves before anything in the
      // consumer's transitive graph shadows it. `:daemon:desktop` is a Kotlin-JVM module, so
      // the default `org.gradle.usage=java-runtime` / `artifactType=jar` resolves directly to
      // the produced JAR — no AGP-style attribute filter needed. Resolved through a lazy
      // `incoming.artifactView {}.files` view (not the raw `Configuration`) so the classpath stays
      // config-cache serializable — see issue #1796 and the desktop validate guards.
      classpath.from(daemonRendererConfig.incoming.artifactView {}.files)
      // User's compiled classes — keeps the Kotlin classloader's class-data-sharing intact for
      // the parent classloader before `UserClassLoaderHolder` constructs its child URL loader.
      classpath.from(sourceClassDirs)
      // Consumer's processed resources so a daemon/VS Code render can load classpath assets (a
      // Lottie `.json`, a font, an image) — same as the one-shot `composePreviewRender` path. These
      // land on the daemon's parent `-cp`; the `userClassDirs` filter below excludes them (they
      // don't match the `build/classes/...` markers), so they stay parent-loaded, not child-first.
      classpath.from(sourceResourceDirs)
      // User's runtime classpath (Compose Multiplatform deps + transitive Kotlin libraries).
      // Lazy artifact view (not the raw `Configuration`) for config-cache serializability — #1796.
      project.configurations.findByName(dependencyConfigName())?.let {
        classpath.from(it.incoming.artifactView {}.files)
      }

      // Desktop daemons don't run inside Robolectric, so the AGP-side `--add-opens` flags don't
      // apply here. `-Xmx` is the only essential JVM arg; B-desktop follow-ups can add Skia /
      // ImageComposeScene-specific opens if profiling shows a need.
      jvmArgs.add(extension.daemon.maxHeapMb.map { "-Xmx${it}m" })

      // Desktop sysprops are a strict subset of the Android side — no Robolectric / Roborazzi
      // keys. Per-key `put(...)` so each Provider chain captures only serialisable references
      // (see Bug 1 fix in `AndroidPreviewSupport.kt` for the rationale).
      systemProperties.put("composeai.daemon.protocolVersion", "1")
      systemProperties.put("composeai.daemon.idleTimeoutMs", "5000")
      systemProperties.put(
        "composeai.daemon.maxHeapMb",
        extension.daemon.maxHeapMb.map { it.toString() },
      )
      systemProperties.put(
        "composeai.daemon.maxRendersPerSandbox",
        extension.daemon.maxRendersPerSandbox.map { it.toString() },
      )
      systemProperties.put(
        "composeai.daemon.warmSpare",
        extension.daemon.warmSpare.map { it.toString() },
      )
      systemProperties.put("composeai.daemon.modulePath", project.path)
      systemProperties.put(
        "composeai.daemon.moduleProjectDir",
        project.layout.projectDirectory.asFile.absolutePath,
      )
      systemProperties.put("composeai.render.outputDir", rendersDirProvider)
      systemProperties.put("composeai.fonts.cacheDir", daemonFontsCacheDir)
      systemProperties.put("composeai.fonts.offline", daemonFontsOffline)
      systemProperties.put(
        "composeai.daemon.perfettoTrace",
        AndroidPreviewSupport.resolveComposeAiTraceEnabled(project, extension).map { it.toString() },
      )
      systemProperties.put(
        "composeai.daemon.userClassDirs",
        this.classpath.elements.map { elements ->
          elements
            .map { it.asFile.absolutePath }
            .filter { entry -> daemonUserClassMarkers.any { marker -> entry.startsWith(marker) } }
            .joinToString(java.io.File.pathSeparator)
        },
      )
      systemProperties.put("composeai.daemon.cheapSignalFiles", daemonCheapSignalFiles)
      systemProperties.put("composeai.daemon.previewsJsonPath", previewsJsonProvider)
      // Same path the daemon's `PreviewManifestRouter` reads to map the protocol-level
      // `previewId` payload into the `RenderSpec(className, functionName)` the engine needs.
      // Without it, `JsonRpcServer.handleRenderNow`'s `previewId=<id>` payload bottoms out in
      // `DesktopHost.renderStubFallback` and the daemon emits a stub PNG path that doesn't
      // exist on disk — see issue #314. The "harness" prefix is historical (only the harness
      // launchers used to set this); now any production-mode launcher needs it.
      systemProperties.put("composeai.harness.previewsManifest", previewsJsonProvider)
      // H1+H2 — `composeai.daemon.historyDir` is what flips daemon-side history recording from
      // off to on. Default location is `<projectDir>/.compose-preview-history` (matches the
      // legacy convention; user-visible `.gitignore` pattern). Without this sysprop the daemon's
      // `HistoryManager` stays null and the VS Code history view shows an empty drawer.
      systemProperties.put(
        "composeai.daemon.historyDir",
        project.layout.projectDirectory.dir(".compose-preview-history").asFile.absolutePath,
      )
      systemProperties.put("composeai.daemon.workspaceRoot", project.rootDir.absolutePath)

      workingDirectory.set(project.projectDir.absolutePath)
      manifestPath.set(previewsJsonProvider)
      // @Optional @InputFile — see kdoc on `DaemonBootstrapTask.previewsManifest`. Matches the
      // Android registration's wire-up so descriptor invalidation is consistent across backends.
      // Conditional Provider returns `null` when previews.json is absent; @Optional on @InputFile
      // requires *unset*, not "set to a missing file" (Gradle fails the task on the latter).
      previewsManifest.fileProvider(
        previewOutputDir.flatMap { dir ->
          project.providers.provider {
            val f = dir.file("previews.json").asFile
            if (f.isFile) f else null
          }
        }
      )
      outputFile.set(outputFileProvider)
      dependsOn(daemonClasspathGuard)
      // Stage the consumer's resources (so `sourceResourceDirs` on the daemon `-cp` is populated)
      // before the descriptor is emitted — mirrors the one-shot render task.
      dependsOn(project.tasks.matching { it.name in DESKTOP_RESOURCE_TASK_CANDIDATES })
      group = "compose preview"
      description =
        "Emit build/compose-previews/daemon-launch.json so VS Code can spawn the desktop preview daemon JVM"
    }
  }

  /**
   * Folds the desktop renderer / daemon tool configuration [toolConfig] into the SAME dependency
   * graph as the consumer's runtime classpath, so Gradle's conflict resolution picks one coherent
   * max-version of every shared module instead of leaving the renderer's pinned versions and the
   * consumer's newer versions side by side on the classpath as separate JARs.
   *
   * Issue #1844: a consumer on Compose Multiplatform 1.11 resolves a newer Skiko whose Java
   * bindings call `org.jetbrains.skia.paragraph.TextStyleKt._nSetFontEdging`. The renderer bundles
   * an older Skiko (pinned to CMP 1.10.3) whose native library doesn't export that symbol; merging
   * the two configurations as raw `FileCollection`s does no cross-graph conflict resolution, so
   * both Skikos land on the render classpath and the older native library + newer Java bindings
   * collide at runtime with `UnsatisfiedLinkError`. `extendsFrom` makes the tool config resolve in
   * one graph with the consumer's deps, so the higher Skiko (the consumer's) wins for both the Java
   * bindings and the native library — and because both configs then resolve the same artifact file,
   * the `FileCollection` (a `Set<File>`) carries it exactly once. Mirrors the Android renderer's
   * `extendsFrom(testConfig)` (docs/RENDERER_COMPATIBILITY.md mitigation #2); [copyAttributes]
   * hands Gradle the consumer's JVM/Kotlin platform attributes so it selects the matching variant
   * of each KMP-published module (Skiko, the JetBrains Compose runtime) without us declaring them
   * by hand.
   *
   * Scoped to genuinely JVM/desktop consumer classpaths. The pure-Android KMP fallback
   * (`androidRuntimeClasspath`, `platform.type=androidJvm`) is left untouched: the desktop renderer
   * has no `androidJvm` variant, so extending it would fail resolution outright — and that
   * classpath can't feed the JVM renderer anyway ([ValidateComposePreviewClasspathTask] already
   * rejects its AndroidX Compose artifacts). Wired in `afterEvaluate` because the KMP
   * `jvm`/`desktop` runtime classpaths the consumer resolves against may not exist yet when
   * [registerDesktopTasks] runs (the `kotlin { jvm("desktop") }` block can configure after
   * `pluginManager.withPlugin` fires).
   */
  private fun alignDesktopToolWithConsumerGraph(
    project: Project,
    toolConfig: org.gradle.api.artifacts.Configuration,
    dependencyConfigName: () -> String,
  ) {
    project.afterEvaluate {
      val depName = dependencyConfigName()
      // androidJvm classpath: the desktop renderer has no matching variant — leave it alone.
      if (depName == "androidRuntimeClasspath") return@afterEvaluate
      val depConfig = project.configurations.findByName(depName) ?: return@afterEvaluate
      copyAttributes(toolConfig.attributes, depConfig.attributes)
      toolConfig.extendsFrom(depConfig)
    }
  }

  /**
   * Copies attributes from [source] onto [target] for variant selection, EXCEPT the consumer's
   * bytecode-target attribute (`org.gradle.jvm.version`). AGP-free mirror of the identically named
   * helper in [AndroidPreviewSupport] — kept here so the desktop wiring doesn't pull AGP onto its
   * classpath. Used by [alignDesktopToolWithConsumerGraph] to give the renderer tool config the
   * consumer classpath's JVM/Kotlin platform attributes before extending it.
   *
   * `org.gradle.jvm.version` is deliberately skipped: the renderer / daemon tool modules are built
   * at the repo's Java 17 convention, so inheriting a consumer that targets a lower bytecode level
   * (e.g. a project on a JVM 11 toolchain, whose `runtimeClasspath` carries
   * `org.gradle.jvm.version=11`) would make Gradle demand a Java-11-compatible variant of
   * `renderer-desktop` / `daemon-desktop` that doesn't exist and reject the dependency before
   * rendering. The platform-type / usage attributes we DO need for KMP variant selection are
   * copied.
   */
  private fun copyAttributes(
    target: org.gradle.api.attributes.AttributeContainer,
    source: org.gradle.api.attributes.AttributeContainer,
  ) {
    val targetJvmVersion =
      org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE.name
    source.keySet().forEach { key ->
      if (key.name == targetJvmVersion) return@forEach
      @Suppress("UNCHECKED_CAST") val attr = key as Attribute<Any>
      source.getAttribute(attr)?.let { target.attribute(attr, it) }
    }
  }

  private fun registerDesktopClasspathGuard(
    project: Project,
    taskName: String,
    dependencyConfigName: () -> String,
    toolClasspath: org.gradle.api.artifacts.Configuration,
  ): TaskProvider<ValidateComposePreviewClasspathTask> =
    project.tasks.register(taskName, ValidateComposePreviewClasspathTask::class.java) {
      platform.set("desktop")
      // Feed the @Classpath FileCollection a lazily-resolved, content-keyed FileCollection
      // (`incoming.artifactView { }.files`) instead of the raw `Configuration`.
      // `classpath.from(config)`
      // pins the live `Configuration` instance into the task's `__classpath__` backing field, and
      // the
      // configuration cache can't serialize it — the nested TestKit bundle builds (config cache on,
      // `org.gradle.configuration-cache.problems=fail`) fail the store step with
      // "field `__classpath__` … error writing value" and the bundle task exits 1 (issue #1796).
      // The empty artifact view preserves the default resolution (same files as `from(config)`)
      // while
      // resolving lazily through a serializable view — mirrors `composePreviewDiscover` above.
      classpath.from(toolClasspath.incoming.artifactView {}.files)
      project.configurations.findByName(dependencyConfigName())?.let {
        classpath.from(it.incoming.artifactView {}.files)
      }
    }

  /**
   * Tier-1 cheap-signal file set for the desktop daemon's [ClasspathFingerprint][
   * ee.schimke.composeai.daemon.ClasspathFingerprint]. Delegates to the shared
   * [CheapSignalFiles.collect] so the Android and Desktop registrations stay in lockstep — critical
   * because both feed the same daemon `composeai.daemon.cheapSignalFiles` sysprop and the daemon
   * side hashes them as one logical input.
   */
  private fun collectDesktopCheapSignalFiles(project: Project): List<java.io.File> =
    CheapSignalFiles.collect(project)

  /**
   * Plugin-apply-time setup for stage-2 BTA configurations. Creates the two detached configurations
   * idempotently (`maybeCreate`) and schedules `afterEvaluate` to populate them with the BTA impl +
   * Compose-plugin-embeddable coordinates against the consumer's Kotlin version. Always populated:
   * the runtime cost lives on the daemon JVM and is paid only when the editor's save loop actually
   * calls `compileSources` (gated by `composePreview.daemon.compileInProcess` in VS Code) — the
   * classpath itself is small enough at config time that gating doesn't earn its UX complexity.
   *
   * MUST be called from config time, NOT from inside `task.register {…}` — Gradle's MutationGuard
   * refuses `Project.afterEvaluate(...)` once task realization starts.
   */
  private fun setupBtaConfigurations(project: Project, extension: PreviewExtension) {
    val _unused = extension
    val btaImplConfig =
      project.configurations.maybeCreate("composePreviewBtaImpl").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        description = "Stage-2 BTA implementation classpath."
      }
    val btaPluginConfig =
      project.configurations.maybeCreate("composePreviewBtaPlugin").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        description =
          "Stage-2 Compose compiler plugin embeddable JAR (loaded into BTA's isolated classloader)."
      }
    project.afterEvaluate {
      val kotlinVersion = resolveConsumerKotlinVersion(project)
      project.dependencies.add(
        btaImplConfig.name,
        "org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion",
      )
      project.dependencies.add(
        btaPluginConfig.name,
        "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$kotlinVersion",
      )
    }
  }

  /**
   * Stage-2 BTA wiring for the CMP / desktop daemon-start task. Wires every BTA input on
   * [DaemonBootstrapTask] from the configurations + project layout. The configurations themselves
   * are created (and populated) by [setupBtaConfigurations], which must run BEFORE this method —
   * see that method's KDoc for why. See `docs/daemon/COMPILE-IN-PROCESS.md` § "Module layout".
   *
   * Kotlin version sniff currently reads our `libs.versions.toml` Kotlin entry — the
   * version-catalog convention this repo and its samples use. Out-of-repo consumers fall back to a
   * constant matching the plugin's own bundled Kotlin (TODO: replace with KGP's
   * `KotlinPluginWrapper.kotlinPluginVersion` once we're willing to take the compile-time dep on
   * KGP types).
   */
  private fun wireDesktopBtaInputs(
    project: Project,
    extension: PreviewExtension,
    task: ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask,
    userRuntimeConfig: org.gradle.api.artifacts.Configuration?,
  ) {
    val _unused = extension // kept in signature for parity with Android wrapper
    wireBtaInputs(
      project = project,
      task = task,
      // CMP / Kotlin-JVM: the consumer's runtime classpath is the compile classpath. Empty
      // file collection when no runtime config was found (rare; the desktop task wiring
      // would have logged that case). Resolved through a lazy `incoming.artifactView {}.files`
      // view so the BTA compile classpath never pins the raw `Configuration` into the daemon
      // bootstrap task's config-cache state — see issue #1796. (The Android caller already passes
      // AGP's config-cache-safe `Test.classpath`, so only this desktop path needs the wrap.)
      userCompileClasspath = userRuntimeConfig?.incoming?.artifactView {}?.files ?: project.files(),
      // MODULE_NAME mirrors KGP's default `compileKotlin` for non-multiplatform JVM modules
      // — `project.name`, no path-mangling. The bta-host-fixture spike confirmed Gradle
      // uses this exact spelling in `kotlin.Metadata.d2[]`.
      moduleName = project.name,
      // Output dir mirrors KGP's `compileKotlin` for plain Kotlin/JVM. CMP-Desktop uses
      // `classes/kotlin/desktop/main` instead under some plugin combinations; the daemon's
      // child classloader watches both via `userClassDirs`, so this picks the most common
      // shape and the runtime handles the rest.
      outputDirProvider =
        project.layout.buildDirectory.dir("classes/kotlin/main").map { it.asFile.absolutePath },
      icWorkingDirProvider =
        project.layout.buildDirectory.dir("compose-previews/daemon-state/bta-ic").map {
          it.asFile.absolutePath
        },
      ineligibilityReason = detectStageTwoIneligibility(project),
    )
  }

  /**
   * Shared by [wireDesktopBtaInputs] (CMP path) and `AndroidPreviewSupport`'s analogous call site.
   * Wires every BTA input on [DaemonBootstrapTask] from a per-target compile classpath
   * + module-name + output dir + IC dir, then mirrors every value into the daemon JVM's sysprops so
   *   `DefaultBtaCompileService.fromSysprops()` constructs the in-process service at startup. The
   *   configurations themselves are created (and conditionally populated) by
   *   [setupBtaConfigurations], which must run BEFORE this method.
   *
   * The sysprop NAMES are duplicated verbatim from `:daemon:core`'s
   * `DefaultBtaCompileService.Companion.SYSPROP_*` constants — the gradle plugin and the daemon
   * live in separate included builds, so we can't import the constants directly. Keep both halves
   * in sync; the daemon's `CompileSourcesTest` and `DefaultBtaCompileServiceTest.fromSysprops*`
   * exercise the read path against literal strings that match these.
   */
  internal fun wireBtaInputs(
    project: Project,
    task: ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask,
    userCompileClasspath: org.gradle.api.file.FileCollection,
    moduleName: String,
    outputDirProvider: org.gradle.api.provider.Provider<String>,
    icWorkingDirProvider: org.gradle.api.provider.Provider<String>,
    ineligibilityReason: String?,
  ) {
    val btaImplConfig = project.configurations.getByName("composePreviewBtaImpl")
    val btaPluginConfig = project.configurations.getByName("composePreviewBtaPlugin")
    // Lazy artifact views (not the raw `Configuration`s) so these input classpaths stay
    // config-cache serializable — same rationale as the desktop validate guards (issue #1796).
    // `userCompileClasspath` arrives already config-cache-clean from both callers (a lazy view on
    // desktop, AGP's `Test.classpath` on Android), so it's added as-is.
    task.btaImplClasspath.from(btaImplConfig.incoming.artifactView {}.files)
    task.btaCompilerPluginClasspath.from(btaPluginConfig.incoming.artifactView {}.files)
    task.btaCompileClasspath.from(userCompileClasspath)
    task.btaModuleName.set(moduleName)
    task.btaOutputDir.set(outputDirProvider)
    task.btaIcWorkingDir.set(icWorkingDirProvider)
    ineligibilityReason?.let { task.btaIneligibilityReason.set(it) }

    val pathSep = java.io.File.pathSeparator
    task.systemProperties.put(
      "composeai.daemon.bta.implClasspath",
      btaImplConfig.elements.map { elements ->
        elements.joinToString(pathSep) { it.asFile.absolutePath }
      },
    )
    task.systemProperties.put(
      "composeai.daemon.bta.compilerPlugins",
      btaPluginConfig.elements.map { elements ->
        elements.joinToString(pathSep) { it.asFile.absolutePath }
      },
    )
    task.systemProperties.put(
      "composeai.daemon.bta.compileClasspath",
      task.btaCompileClasspath.elements.map { elements ->
        elements.joinToString(pathSep) { it.asFile.absolutePath }
      },
    )
    task.systemProperties.put("composeai.daemon.bta.moduleName", task.btaModuleName)
    task.systemProperties.put("composeai.daemon.bta.outputDir", task.btaOutputDir)
    task.systemProperties.put("composeai.daemon.bta.icWorkingDir", task.btaIcWorkingDir)
    task.systemProperties.put(
      "composeai.daemon.bta.ineligibilityReason",
      task.btaIneligibilityReason.orElse(""),
    )
  }

  /**
   * Public to `AndroidPreviewSupport` — the KSP/KAPT predicate is shared between CMP and Android.
   * See [detectStageTwoIneligibility]'s docstring.
   */
  internal fun detectStageTwoIneligibilityFor(project: Project): String? =
    detectStageTwoIneligibility(project)

  /**
   * Public to `AndroidPreviewSupport` so it can call `setupBtaConfigurations` at its apply-time
   * hook. The Android registration calls this immediately on the apply side (before the `Variant`
   * callback fires) so the configurations exist by the time the per-variant `tasks.register {…}`
   * blocks read them. Idempotent — `maybeCreate`.
   */
  internal fun setupBtaConfigurationsFor(project: Project, extension: PreviewExtension) =
    setupBtaConfigurations(project, extension)

  /**
   * Daemon-warm-time stage-2 eligibility predicate. Returns a human-readable string when the
   * consumer's module is NOT eligible for in-process compile; `null` when eligible. Mirrors
   * `docs/daemon/COMPILE-IN-PROCESS.md` § "Eligibility" — keep the two in sync.
   *
   * - **KSP** modules need their generated sources recompiled on every save. BTA doesn't drive KSP;
   *   stage 1's `gradle --continuous` covers it because Gradle drives KSP for it.
   * - **KAPT** is the legacy variant of the same problem.
   * - **Plain `annotationProcessor` dependencies** (javac APs) similarly aren't BTA-driven — a save
   *   in an AP-processed source would render with stale generated code.
   * - **KMP** modules: the spike covered a single JVM source set only; the stage-2 wiring uses the
   *   plain Kotlin/JVM module-name + `classes/kotlin/main` output dir, which doesn't model KMP's
   *   per-target source-set layout. Re-evaluate when KMP support is explicitly added.
   *
   * Plugins are matched by id (stable across KGP versions, no classpath resolution). The
   * annotationProcessor check reads declared dependencies on the AP configurations by name —
   * `configurations.names` doesn't realize the container, and only the matching configs are
   * realized — so it stays configuration-cache-safe.
   */
  private fun detectStageTwoIneligibility(project: Project): String? =
    when {
      project.plugins.hasPlugin("com.google.devtools.ksp") ->
        "com.google.devtools.ksp plugin applied (stage 2 doesn't drive KSP yet — see " +
          "docs/daemon/COMPILE-IN-PROCESS.md § Eligibility)"
      project.plugins.hasPlugin("org.jetbrains.kotlin.kapt") ->
        "org.jetbrains.kotlin.kapt plugin applied (stage 2 doesn't drive KAPT yet)"
      project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ->
        "org.jetbrains.kotlin.multiplatform plugin applied (stage 2 covers single-source-set " +
          "JVM/Android modules only; KMP source-set wiring stays on stage 1 — see " +
          "docs/daemon/COMPILE-IN-PROCESS.md § Eligibility)"
      hasAnnotationProcessorDependencies(project) ->
        "annotationProcessor dependencies declared (javac annotation processors aren't BTA-driven " +
          "— see docs/daemon/COMPILE-IN-PROCESS.md § Eligibility)"
      else -> null
    }

  /**
   * True when the consumer declares any dependency on an `annotationProcessor`-shaped configuration
   * (`annotationProcessor`, `testAnnotationProcessor`, AGP's per-variant
   * `<variant>AnnotationProcessor`, …). Reads `configurations.names` (which does not force the
   * container to realize) and only realizes the configurations whose name matches, reading their
   * *declared* dependencies — no classpath resolution — so the check is configuration-cache-safe at
   * config time.
   */
  private fun hasAnnotationProcessorDependencies(project: Project): Boolean =
    project.configurations.names
      .filter { it.contains("annotationProcessor", ignoreCase = true) }
      .any { name -> project.configurations.getByName(name).dependencies.isNotEmpty() }

  /**
   * Reads the consumer's Kotlin version from their `libs.versions.toml` `kotlin` entry — the
   * convention this repo and its samples use. The BTA impl JAR version must EXACTLY match the
   * Kotlin compiler version (an impl 2.3.21 + compiler 2.3.20 mismatch fails at
   * `KotlinToolchains.loadImplementation` time, not a runtime surprise) so this sniff has to be
   * right.
   *
   * Falls back to [KOTLIN_VERSION_FALLBACK] when no `libs.versions.toml` exists or no `kotlin`
   * entry is declared. The fallback matches our own plugin's bundled Kotlin — good for in-repo
   * samples, wrong for arbitrary out-of-repo consumers. Tracked as "switch to KGP's
   * KotlinPluginWrapper.kotlinPluginVersion" in `docs/daemon/COMPILE-IN-PROCESS.md` follow-ups.
   */
  private fun resolveConsumerKotlinVersion(project: Project): String {
    val catalogs =
      project.extensions.findByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        ?: return KOTLIN_VERSION_FALLBACK
    val catalog =
      runCatching { catalogs.named("libs") }.getOrNull() ?: return KOTLIN_VERSION_FALLBACK
    return catalog.findVersion("kotlin").orElse(null)?.requiredVersion ?: KOTLIN_VERSION_FALLBACK
  }

  /** See [resolveConsumerKotlinVersion]. Bumped in lockstep with the plugin's own KGP. */
  private const val KOTLIN_VERSION_FALLBACK = "2.3.21"

  /**
   * Shared `Provider<String>` for the `composePreview.tier` Gradle property. `"fast"`
   * (case-insensitive) tells the renderer to skip captures whose `cost` exceeds
   * [HEAVY_COST_THRESHOLD]; anything else maps to `"full"`. Lazy + cacheable through
   * `project.providers`, so reading `.get()` at task-execution time doesn't invalidate the
   * configuration cache when the Gradle property flips between runs.
   */
  internal fun tierProperty(project: Project): Provider<String> =
    project.providers
      .gradleProperty("composePreview.tier")
      .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
      .orElse("full")

  /**
   * `Provider<String>` for the `composePreview.missingRenders` Gradle property. Controls how
   * `composePreviewRenderAll` reacts when a preview is listed in the manifest but produced no
   * on-disk output: `"fail"` (default — throws), `"warn"` (logs a `WARN` line + writes the
   * validation marker), `"ignore"` (silent + writes the marker). Set via
   * `-PcomposePreview.missingRenders=warn` or the corresponding env var
   * (`ORG_GRADLE_PROJECT_composePreview.missingRenders=warn`) — the `apply` GitHub action exposes
   * the same knob as the `missing-renders` input so consumers don't have to wire it into their
   * build files. Unknown values fall through to `"fail"` so a typo can't silently widen the policy.
   */
  internal fun missingRendersProperty(project: Project): Provider<String> =
    project.providers
      .gradleProperty("composePreview.missingRenders")
      .map { raw ->
        when (raw.trim().lowercase()) {
          "warn",
          "ignore",
          "fail" -> raw.trim().lowercase()
          else -> "fail"
        }
      }
      .orElse("fail")

  fun registerDiscoverTask(
    project: Project,
    sourceClassDirs: FileCollection,
    dependencyConfigName: () -> String,
    previewOutputDir: Provider<Directory>,
    extension: PreviewExtension,
    configureDeps: DiscoverPreviewsTask.() -> Unit,
  ): TaskProvider<DiscoverPreviewsTask> {
    val artifactType = Attribute.of("artifactType", String::class.java)

    return project.tasks.register("composePreviewDiscover", DiscoverPreviewsTask::class.java) {
      classDirs.from(sourceClassDirs)
      sourceFiles.from(
        project.fileTree("src") {
          include("**/*.kt")
          include("**/*.java")
        }
      )
      project.configurations.findByName(dependencyConfigName())?.let { config ->
        // For Android projects, dependencies resolve as AARs. Use artifact view
        // filtering to request the extracted classes.jar (AGP registers the
        // transform). Desktop/JVM projects already return JARs so this is a no-op.
        dependencyJars.from(
          config.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files
        )
        dependencyJars.from(
          config.incoming
            .artifactView { attributes.attribute(artifactType, "android-classes") }
            .files
        )
      }
      moduleName.set(project.name)
      variantName.set(extension.variant)
      projectDirectory.set(project.layout.projectDirectory.asFile.absolutePath)
      // Default Lottie captures into the shared `renders/` dir (desktop, where the desktop renderer
      // is the only writer). The Android task overrides this in [configureDeps] to a disjoint dir
      // so
      // its JVM Lottie render doesn't share `renders/` with the Robolectric render.
      lottieRenderSubdir.convention("renders")
      // `-PcomposePreview.failOnEmpty=true` wins over the extension, so
      // CI profiles and one-off triage runs can flip the gate without
      // touching build.gradle(.kts). Same pattern as
      // the preview-extension selector above.
      failOnEmpty.set(
        project.providers
          .gradleProperty("composePreview.failOnEmpty")
          .map { it.toBooleanStrictOrNull() ?: false }
          .orElse(extension.failOnEmpty)
      )
      // No per-extension opt-in plumbed here — a11y data products are produced only by the
      // daemon (see `:daemon:android`'s `RenderEngine`). The standalone `composePreviewDiscover`
      // task writes an empty `dataExtensionReports` map.
      outputFile.set(previewOutputDir.map { it.file("previews.json") })
      group = "compose preview"
      description = "Discover @Preview annotations in compiled classes"
      configureDeps()
    }
  }

  /**
   * Registers a `composePreviewCompile` lifecycle task whose only job is to run the same Kotlin
   * compile task `composePreviewDiscover` depends on, without the discovery action itself. Wired so
   * the VS Code extension can keep `.class` files fresh on save without re-walking the
   * dependency-JAR classpath through ClassGraph — the daemon owns the metadata reconcile via its
   * `IncrementalDiscovery` cascade and `discoveryUpdated` notification, so the editor save loop no
   * longer needs `:composePreviewDiscover` on every keystroke.
   *
   * Caller passes the set of candidate compile task names; we wire every matching task lazily so
   * Android variants registered after this plugin block still participate. When none are found
   * (consumer hasn't applied a Kotlin plugin) the task is still registered but is a no-op — same
   * `onlyIf(false)` shape as the disabled-by-extension gating below.
   */
  fun registerCompileOnlyTask(
    project: Project,
    extension: PreviewExtension,
    compileTaskNames: List<String>,
  ): TaskProvider<DefaultTask> {
    return project.tasks.register("composePreviewCompile", DefaultTask::class.java) {
      group = "compose preview"
      description =
        "Compile sources without running composePreviewDiscover — used by the VS Code daemon save path."
      onlyIf { extension.enabled.get() }
      dependsOn(project.tasks.matching { it.name in compileTaskNames })
    }
  }

  /** Registers `composePreviewRenderAll` as the user-facing entry point. */
  fun registerRenderAllPreviews(
    project: Project,
    extension: PreviewExtension,
    renderTask: TaskProvider<*>,
    previewOutputDir: Provider<Directory>,
  ) {
    // Post-condition check: every entry in the manifest must have a PNG
    // on disk after the render dependency ran. We ship the renderer
    // (RobolectricRenderTest on Android, RenderPreviewsTask on desktop)
    // so we KNOW the task should run for a non-empty manifest — a missing
    // PNG is a wiring bug, never expected. The most common offender on
    // Android is `composePreviewRender` reporting NO-SOURCE because the AAR's
    // classes.jar wasn't expanded via `zipTree` before being added to
    // `testClassesDirs`, which silently skips rendering; without this
    // check the failure surfaces only in downstream tools (CLI / VSCode).
    val manifestFile = previewOutputDir.map { it.file("previews.json") }
    val rendersDir = previewOutputDir.map { it.dir("renders") }
    val validationMarker = previewOutputDir.map { it.file("composePreviewRenderAll.validated") }
    // Captured at config time so the `doLast` body doesn't reach for
    // `project` at execution (config-cache safe). Resolves at execution
    // to "fast" or "full"; "fast" tells the post-condition to tolerate
    // heavy captures that legitimately weren't rendered this run.
    val tierProvider =
      project.providers
        .gradleProperty("composePreview.tier")
        .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
        .orElse("full")
    val missingRendersProvider = missingRendersProperty(project)
    project.tasks.register("composePreviewRenderAll", DefaultTask::class.java) {
      group = "compose preview"
      dependsOn(renderTask)
      inputs
        .file(manifestFile)
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("manifest")
      inputs.property("tier", tierProvider)
      inputs.property("missingRenders", missingRendersProvider)
      outputs.file(validationMarker).withPropertyName("validationMarker")
      doLast {
        val isFastTier = tierProvider.get() == "fast"
        val missingPolicy = missingRendersProvider.get()
        val manifestOnDisk = manifestFile.get().asFile
        if (!manifestOnDisk.exists()) return@doLast
        val manifest =
          previewManifestJson.decodeFromString(
            PreviewManifest.serializer(),
            manifestOnDisk.readText(),
          )
        if (manifest.previews.isEmpty()) return@doLast

        // `build/compose-previews/renders/` is a derived artefact —
        // the renderer rewrites it every run, and downstream tools
        // (VS Code, CLI) compare the CURRENT manifest against on-disk
        // state. Files left over from deleted or
        // renamed previews confuse that comparison, so we delete
        // anything that isn't referenced by a current manifest
        // entry.
        //
        // Parameterized (`@PreviewParameter`) previews are special:
        // the Gradle side only knows the stem (e.g.
        // `Foo_PARAM_template.png`), not which fan-out filenames
        // the provider will produce. The renderer itself cleans up
        // its own stale fan-out before writing (see
        // `deleteStaleFanoutFiles` in the renderer modules), so
        // here we keep every `<stem>_*<ext>` match rather than
        // second-guessing the provider values.
        cleanStaleRenders(previewOutputDir.get().asFile.resolve("renders"), manifest, logger)
        // Each preview can produce multiple captures (`@RoboComposePreviewOptions`
        // time fan-out, future scroll / dimension fan-outs). Verify each
        // capture's renderOutput lands on disk — report back one missing
        // entry per preview with at least one missing capture.
        val outDir = previewOutputDir.get().asFile
        // Files owned by non-parameterized siblings — exclude them
        // from the `<stem>_*` glob so a `Foo_header.png` that
        // belongs to a different preview never gets treated as
        // part of `Foo`'s fan-out.
        val missing = missingPreviewOutputIds(manifest, outDir, isFastTier)
        if (missing.isNotEmpty()) {
          val sidecars = readErrorSidecarsFor(manifest, missing, outDir)
          val message = formatMissingPreviewsMessage(manifest, missing, sidecars)
          // `composePreview.missingRenders` controls escalation: `fail` (default) preserves
          // the historical hard error that catches whole-module classpath misconfig; `warn`
          // and `ignore` let multi-module CI runs ride out a handful of stubborn previews
          // without losing the rest of the report. Marker is still written so downstream
          // tasks that wire off the validation outcome see the run as "validated".
          when (missingPolicy) {
            "warn" -> logger.warn("composePreviewRenderAll: missing-renders policy=warn — $message")
            "ignore" -> Unit
            else -> throw GradleException(message)
          }
        }
        val marker = validationMarker.get().asFile
        marker.parentFile?.mkdirs()
        marker.writeText("validated\n")
      }
    }

    // Pixel-test wiring: chain the AGP unit-test tasks behind
    // `composePreviewRenderAll` so a consumer test class that reads the PNGs under
    // `build/compose-previews/renders/` (e.g. `:samples:android-alpha`'s
    // `FocusedPreviewPixelTest`) sees the rendered output by the time its
    // assertions run. Opt-in via `composePreview { renderBeforeUnitTests =
    // true }` — default off so consumers without pixel tests don't pay the
    // `composePreviewRenderAll` cost on every `:check`.
    //
    // Targets the AGP unit-test tasks by name rather than
    // `tasks.withType<Test>()`: the plugin's own `composePreviewRender` Test task
    // is what `composePreviewRenderAll` already depends on, so matching it here
    // would create a cycle. No-op on Compose Multiplatform / Desktop modules
    // where those task names don't exist.
    //
    // `tasks.matching { ... }.configureEach { ... }` is the
    // Isolated-Projects-safe lazy form: the matching predicate fires as
    // each task is registered, and the configureEach body only fires for
    // matches.
    val renderBeforeUnitTests = extension.renderBeforeUnitTests
    project.tasks
      .matching { it.name in PIXEL_TEST_UNIT_TEST_TASKS }
      .configureEach {
        if (renderBeforeUnitTests.get()) {
          dependsOn("composePreviewRenderAll")
        }
      }
  }

  private val PIXEL_TEST_UNIT_TEST_TASKS = setOf("testDebugUnitTest", "testReleaseUnitTest")

  /**
   * Per-preview error-sidecar payload as the gradle plugin reads it. We mirror only the fields used
   * by [formatMissingPreviewsMessage]; the sidecar schema itself lives next to the renderer that
   * writes it (`renderer-android/.../RenderErrorSidecar.kt` and the desktop equivalent).
   * `ignoreUnknownKeys` keeps us compatible with future sidecar fields without a coordinated bump.
   */
  @kotlinx.serialization.Serializable
  internal data class ErrorSidecar(
    val exception: String = "",
    val message: String = "",
    val topAppFrame: TopAppFrame? = null,
  ) {
    @kotlinx.serialization.Serializable
    internal data class TopAppFrame(
      val file: String = "",
      val line: Int = 0,
      val function: String = "",
    )
  }

  /**
   * For each missing preview, look for the `<png>.error.json` sidecar the renderer writes on a
   * per-preview throw. The renderer catches `Throwable` around each preview's `setContent` so one
   * broken preview doesn't fail the whole `Test` task — it writes a sidecar instead, and leaves no
   * PNG. Without this lookup, the renderAll wrapper sees "missing PNG" and emits a generic "render
   * was skipped" message that masks the actual exception.
   *
   * Returns one entry per preview that had AT LEAST one capture-or-product sidecar; preview ids
   * without any sidecar (true silent skip / NO-SOURCE) are absent from the map.
   */
  internal fun readErrorSidecarsFor(
    manifest: PreviewManifest,
    missingIds: List<String>,
    outDir: java.io.File,
  ): Map<String, ErrorSidecar> {
    val json = Json { ignoreUnknownKeys = true }
    val byId = manifest.previews.associateBy { it.id }
    val result = mutableMapOf<String, ErrorSidecar>()
    for (id in missingIds) {
      val preview = byId[id] ?: continue
      val candidatePaths =
        preview.captures.map { it.renderOutput.ifEmpty { "renders/$id.png" } } +
          preview.dataProducts.map { it.output }
      val sidecar =
        candidatePaths
          .asSequence()
          .mapNotNull { rel ->
            val sidecarFile = java.io.File(outDir, "$rel.error.json")
            if (!sidecarFile.isFile) null
            else
              runCatching {
                  json.decodeFromString(ErrorSidecar.serializer(), sidecarFile.readText())
                }
                .getOrNull()
          }
          .firstOrNull()
      if (sidecar != null) result[id] = sidecar
    }
    return result
  }

  internal fun formatMissingPreviewsMessage(
    manifest: PreviewManifest,
    missingIds: List<String>,
    sidecars: Map<String, ErrorSidecar>,
  ): String {
    val total = manifest.previews.size
    val n = missingIds.size
    return if (sidecars.isEmpty()) {
      // No sidecars anywhere — the render task really was skipped or
      // silently NO-SOURCE'd. Keep the original guidance so the
      // testClassesDirs / RobolectricRenderTest.class diagnosis stays in
      // place for the case it was written for.
      val sample = missingIds.take(3).joinToString(", ")
      val andMore = if (n > 3) " (+${n - 3} more)" else ""
      "composePreviewRenderAll: render produced no output file for $n of " +
        "$total preview(s): $sample$andMore. This means " +
        "`composePreviewRender` was skipped or silently did nothing — on Android " +
        "that usually means it reported NO-SOURCE because " +
        "RobolectricRenderTest.class wasn't discoverable on its " +
        "testClassesDirs. Run with --info to see the task outcome."
    } else {
      // At least one sidecar exists: the render task DID run, but the
      // preview threw. Surface the actual exception(s) so the user sees
      // a `Class.forName` / `NoSuchMethodError` / consumer-code failure
      // instead of a misleading "NO-SOURCE" boilerplate.
      val withSidecar = missingIds.filter { it in sidecars }
      val withoutSidecar = missingIds.filterNot { it in sidecars }
      val sb = StringBuilder()
      sb
        .append("composePreviewRenderAll: render produced no output file for ")
        .append(n)
        .append(" of ")
        .append(total)
        .append(" preview(s).")
      if (withSidecar.isNotEmpty()) {
        sb.append("\n\nPer-preview render errors (from .error.json sidecars):")
        for (id in withSidecar.take(5)) {
          val s = sidecars.getValue(id)
          sb.append("\n  - ").append(id).append(": ").append(s.exception.substringAfterLast('.'))
          if (s.message.isNotBlank()) sb.append(": ").append(s.message)
          s.topAppFrame?.let { f ->
            if (f.file.isNotBlank()) {
              sb.append(" (at ").append(f.file)
              if (f.line > 0) sb.append(':').append(f.line)
              sb.append(')')
            }
          }
        }
        if (withSidecar.size > 5) {
          sb.append("\n  (+").append(withSidecar.size - 5).append(" more with sidecars)")
        }
      }
      if (withoutSidecar.isNotEmpty()) {
        sb
          .append("\n\nNo sidecar (render was skipped or silently produced nothing) for: ")
          .append(withoutSidecar.take(5).joinToString(", "))
        if (withoutSidecar.size > 5) {
          sb.append(" (+").append(withoutSidecar.size - 5).append(" more)")
        }
      }
      sb.toString()
    }
  }

  internal fun missingPreviewOutputIds(
    manifest: PreviewManifest,
    outDir: java.io.File,
    isFastTier: Boolean,
  ): List<String> {
    val siblingNames =
      manifest.previews
        .filter { it.params.previewParameterProviderClassName == null }
        .flatMap { p ->
          p.captures.map { c -> c.renderOutput } + p.dataProducts.map { product -> product.output }
        }
        .filter { it.isNotEmpty() }
        .map { java.io.File(outDir, it).name }
        .toSet()

    return manifest.previews
      .filter { p ->
        val captureMissing =
          p.captures.any { c ->
            // Optional captures are best-effort (e.g. the XR composite still baked out-of-band by
            // the native `xr-composite` tool): shown when present, never required — so a missing
            // one is not flagged.
            if (c.optional) return@any false
            if (isFastTier && isHeavyCost(c.cost)) return@any false
            val rel = c.renderOutput.ifEmpty { "renders/${p.id}.png" }
            outputMissing(
              outDir,
              rel,
              p.params.previewParameterProviderClassName != null,
              siblingNames,
            )
          }
        val productMissing =
          p.dataProducts.any { product ->
            if (isFastTier && isHeavyCost(product.cost)) return@any false
            outputMissing(
              outDir,
              product.output,
              p.params.previewParameterProviderClassName != null,
              siblingNames,
            )
          }
        captureMissing || productMissing
      }
      .map { it.id }
  }

  private fun outputMissing(
    outDir: java.io.File,
    rel: String,
    isPreviewParameter: Boolean,
    siblingNames: Set<String>,
  ): Boolean {
    if (isPreviewParameter) {
      val file = outDir.resolve(rel)
      val dir = file.parentFile ?: outDir
      val prefix = file.nameWithoutExtension + "_"
      val ext = ".${file.extension}"
      return !(dir.listFiles()?.any { f ->
        f.name.startsWith(prefix) && f.name.endsWith(ext) && f.name !in siblingNames
      } ?: false)
    }
    return !outDir.resolve(rel).exists()
  }

  /**
   * Deletes files inside [rendersDir] that aren't referenced by [manifest].
   *
   * Keeps four kinds of files:
   * 1. Exact `renderOutput` matches from non-parameterized previews.
   * 2. `<stem>_*.<ext>` fan-out files where `<stem>` belongs to a `@PreviewParameter` preview — the
   *    renderer itself cleans up its own stale fan-outs (it knows the exact filenames), so the
   *    Gradle side deliberately stays conservative and doesn't delete files it can't be sure are
   *    stale.
   * 3. `<stem>.a11y.png` siblings of registered renders. The renderer's `AccessibilityOverlay`
   *    writes these next to the clean PNG when a preview produces ATF findings; the manifest
   *    doesn't list them (the pointer lives in `accessibility.json` instead), so without this
   *    exemption they'd be deleted between writing and publishing.
   * 4. Non-PNG/GIF files that aren't in the plugin's output domain.
   *
   * Anything else (PNGs or GIFs that were produced for a now-removed preview) gets removed so
   * downstream tools compare the manifest against a clean directory.
   */
  private fun cleanStaleRenders(
    rendersDir: java.io.File,
    manifest: PreviewManifest,
    logger: org.gradle.api.logging.Logger,
  ) {
    if (!rendersDir.isDirectory) return

    val expectedRelPaths =
      manifest.previews
        .filter { it.params.previewParameterProviderClassName == null }
        .flatMap { it.captures.mapNotNull { c -> c.renderOutput.stripRendersPrefix() } }
        .toSet()

    // `<stem>_` / `.<ext>` pairs we MUST leave alone — each one is the
    // template filename of a `@PreviewParameter` preview. Any file in
    // [rendersDir] whose leaf name starts with one of these prefixes
    // and ends with the matching extension is treated as a fan-out
    // sibling and preserved.
    val paramStems =
      manifest.previews
        .filter { it.params.previewParameterProviderClassName != null }
        .flatMap { it.captures }
        .mapNotNull { c ->
          val rel = c.renderOutput.stripRendersPrefix() ?: return@mapNotNull null
          val leaf = rel.substringAfterLast('/')
          val dot = leaf.lastIndexOf('.')
          if (dot <= 0) null
          else
            FanoutKey(
              relDir = rel.substringBeforeLast('/', missingDelimiterValue = ""),
              prefix = leaf.substring(0, dot) + "_",
              ext = leaf.substring(dot),
            )
        }
        .toSet()

    rendersDir
      .walkBottomUp()
      .filter { it.isFile && (it.extension == "png" || it.extension == "gif") }
      .forEach { f ->
        val rel = f.relativeTo(rendersDir).invariantSeparatorsPath
        if (rel in expectedRelPaths) return@forEach
        if (paramStems.any { it.matches(rel, f.name) }) return@forEach
        if (isA11ySiblingOfExpected(rel, expectedRelPaths)) return@forEach
        if (isRawSiblingOfExpected(rel, expectedRelPaths)) return@forEach
        if (!f.delete()) {
          logger.warn("compose-preview: couldn't delete stale render $f")
        }
      }
  }

  // `<stem>.a11y.png` lives next to the clean `<stem>.png` registered in
  // the manifest. Match by mechanical suffix-strip rather than scanning
  // accessibility.json: the cleanup runs whether a11y is enabled or not,
  // and a stale `.a11y.png` whose clean sibling has been removed is still
  // garbage we want gone.
  internal fun isA11ySiblingOfExpected(rel: String, expectedRelPaths: Set<String>): Boolean {
    if (!rel.endsWith(".a11y.png")) return false
    val cleanSibling = rel.removeSuffix(".a11y.png") + ".png"
    return cleanSibling in expectedRelPaths
  }

  /**
   * `<stem>.raw.png` lives next to a clean `<stem>.png` registered in the manifest. Produced by
   * `@FocusedPreview(overlay = true)` — the renderer copies the unmarked capture aside before
   * applying the focus-rect overlay to the main file. Same preservation shape as
   * [isA11ySiblingOfExpected] — match by mechanical suffix-strip, not by re-checking the manifest's
   * overlay flag, so a `.raw.png` whose clean sibling has been removed is still garbage.
   */
  internal fun isRawSiblingOfExpected(rel: String, expectedRelPaths: Set<String>): Boolean {
    if (!rel.endsWith(".raw.png")) return false
    val cleanSibling = rel.removeSuffix(".raw.png") + ".png"
    return cleanSibling in expectedRelPaths
  }

  private fun String.stripRendersPrefix(): String? {
    if (isEmpty()) return null
    return substringAfter("renders/", missingDelimiterValue = this).takeIf { it.isNotEmpty() }
  }

  private data class FanoutKey(val relDir: String, val prefix: String, val ext: String) {
    fun matches(rel: String, leaf: String): Boolean {
      val dir = rel.substringBeforeLast('/', missingDelimiterValue = "")
      return dir == relDir && leaf.startsWith(prefix) && leaf.endsWith(ext)
    }
  }
}
