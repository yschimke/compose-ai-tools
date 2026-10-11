package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaToolchainService

private val previewManifestJson = Json { ignoreUnknownKeys = true }

/**
 * AGP-free task wiring shared by the Android and desktop paths, so desktop can use it without
 * loading AGP.
 */
internal object ComposePreviewTasks {
  /**
   * Desktop / KMP Kotlin compile task names, in priority order; wired as upstream of discovery and
   * compile.
   */
  private val DESKTOP_COMPILE_TASK_CANDIDATES: List<String> =
    listOf("compileKotlinJvm", "compileKotlinDesktop", "compileAndroidMain", "compileKotlin")

  /**
   * Desktop / KMP resource-processing task names. Their output is linked onto the render classpath
   * so previews can load classpath resources (Lottie assets, fonts, images). Missing names are
   * ignored.
   */
  private val DESKTOP_RESOURCE_TASK_CANDIDATES: List<String> =
    listOf("jvmProcessResources", "desktopProcessResources", "processResources")

  /**
   * Creates (or reuses) [configName] with the `:renderer-desktop` renderer: the in-tree project
   * when present, else the published JAR via the daemon BOM (see [PreviewDaemonModules]). Used by
   * the desktop render task and Android's `composePreviewRenderLottie`. A consumer-populated
   * [configName] is left as-is.
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
            PreviewDaemonModules.dependency(project, configName, "renderer-desktop"),
          )
        }
      } else {
        project.dependencies.add(
          configName,
          PreviewDaemonModules.dependency(project, configName, "renderer-desktop"),
        )
      }
    }
    return rendererConfig
  }

  private val consumerArtifactTypeAttribute: Attribute<String> =
    Attribute.of("artifactType", String::class.java)

  /**
   * Whether the desktop renderer can drive [configName]: a pure KMP-Android module exposes only
   * `androidRuntimeClasspath` (`*-android` Compose). Only gates tasks the Tooling-API model builder
   * never realizes, so CLI module detection is unaffected (#1855).
   */
  internal fun isDesktopRenderableConfig(configName: String): Boolean =
    configName != "androidRuntimeClasspath"

  /**
   * Whether `composePreviewBundle` has something to pack. Renderability is a desktop question: on
   * the Android registration `androidRuntimeClasspath` is the real runtime config of every
   * KMP-Android module, so applying [isDesktopRenderableConfig] there silently skipped its bundle
   * task.
   */
  internal fun bundleRenderable(backendId: String, configName: String): Boolean =
    backendId == "android" || isDesktopRenderableConfig(configName)

  /**
   * The consumer runtime configuration for the desktop pipeline, in preference order;
   * `androidRuntimeClasspath` is the last-resort KMP-Android fallback.
   *
   * Must be called lazily: on KMP-Android, [registerDesktopTasks] runs before `kotlin {
   * jvm("desktop") }` creates `desktopRuntimeClasspath`. `internal` for tests.
   */
  internal fun desktopDependencyConfigName(project: Project): String =
    listOf(
        "jvmRuntimeClasspath",
        "desktopRuntimeClasspath",
        "androidRuntimeClasspath",
        "runtimeClasspath",
      )
      .firstOrNull { project.configurations.findByName(it) != null } ?: "runtimeClasspath"

  /**
   * Dependency jars and coordinate map for [registerBundleTask], derived from one resolution so
   * they agree.
   */
  internal class DependencyClasspathBinding(
    val configName: String,
    val jarFiles: org.gradle.api.file.FileCollection?,
    val coordinates: Provider<Map<String, String>>,
  )

  /**
   * Lazy, config-cache-safe view of [configName] pinned to `artifactType=jar`. Without the
   * attribute, `androidRuntimeClasspath`'s many variants fail with `AmbiguousArtifactsFailure`
   * (#1852). `lenient(true)` only for that fallback, so JVM classpaths still surface missing deps.
   */
  /**
   * Resolvable view of [PREVIEW_SOURCE_CONFIGURATION] with the attributes of [runtimeConfigName].
   * Without attributes a multi-variant (KMP) source module silently resolves to nothing; copying
   * the lane's attributes selects `androidJvm` or `jvm` as appropriate. Null when nothing is
   * declared or the runtime config isn't resolvable yet.
   */
  private fun previewSourceClasspath(project: Project, runtimeConfigName: String): Configuration? {
    val bucket = project.configurations.findByName(PREVIEW_SOURCE_CONFIGURATION) ?: return null
    if (bucket.dependencies.isEmpty()) return null
    val runtime = project.configurations.findByName(runtimeConfigName) ?: return null
    val name = "composePreviewSourceClasspath"
    project.configurations.findByName(name)?.let {
      return it
    }
    return project.configurations.create(name) {
      isCanBeConsumed = false
      isCanBeResolved = true
      extendsFrom(bucket)
      description =
        "Resolvable view of $PREVIEW_SOURCE_CONFIGURATION, carrying the attributes of " +
          "$runtimeConfigName so a multiplatform preview-source module selects the same variant " +
          "this module renders against."
      runtime.attributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST") val typed = key as org.gradle.api.attributes.Attribute<Any>
        runtime.attributes.getAttribute(typed)?.let { value -> attributes.attribute(typed, value) }
      }
    }
  }

  private fun pinnedConsumerClasspath(
    project: Project,
    configName: String,
  ): org.gradle.api.file.FileCollection? {
    val config = project.configurations.findByName(configName) ?: return null
    return config.incoming
      .artifactView {
        if (configName == "androidRuntimeClasspath") lenient(true)
        attributes.attribute(consumerArtifactTypeAttribute, "jar")
      }
      .files
  }

  /**
   * Extra Maven repository URLs (beyond Central and Google) recorded as
   * `BundleManifest.repositories`, so players can re-resolve coordinates from snapshot builds,
   * forks or mirrors (#4259 / #4265).
   *
   * Reads both `project.repositories` and settings' `dependencyResolutionManagement.repositories`
   * (the project list is empty under `PREFER_SETTINGS` / `FAIL_ON_PROJECT_REPOS`), the latter via a
   * guarded internal accessor. A Provider so it's sampled after both scripts run. Non-HTTP
   * repositories are skipped, since local paths are useless to a player.
   */
  private fun extraMavenRepositoryUrls(project: Project): Provider<List<String>> =
    project.provider {
      (project.repositories + settingsRepositories(project))
        .filterIsInstance<org.gradle.api.artifacts.repositories.MavenArtifactRepository>()
        .map { it.url.toString().trimEnd('/') }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .filterNot { url -> DEFAULT_PLAYER_REPOSITORIES.any { url.startsWith(it) } }
        .distinct()
    }

  /**
   * Settings' `dependencyResolutionManagement` repositories, or empty when unavailable (e.g.
   * `ProjectBuilder`). Reaching `Settings` from a project plugin isn't public API, hence the
   * guarded cast.
   */
  private fun settingsRepositories(project: Project): List<ArtifactRepository> = runCatching {
    (project.gradle as org.gradle.api.internal.GradleInternal)
      .settings
      .dependencyResolutionManagement
      .repositories
      .toList()
  }
    .getOrElse { emptyList() }

  /**
   * Repositories every player tries anyway; kept in lockstep with
   * `CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES`.
   */
  private val DEFAULT_PLAYER_REPOSITORIES =
    listOf(
      "https://repo1.maven.org/maven2",
      "https://repo.maven.apache.org/maven2",
      "https://dl.google.com/dl/android/maven2",
      "https://maven.google.com",
    )

  /** Builds the [DependencyClasspathBinding] for consumer runtime config [configName]. */
  private fun dependencyClasspathBinding(
    project: Project,
    configName: String,
    artifactTypeAttr: Attribute<String>,
  ): DependencyClasspathBinding {
    val depConfig = project.configurations.findByName(configName)
    // Lenient only for the `androidRuntimeClasspath` fallback; strict elsewhere so unresolvable
    // deps fail loudly.
    val depViewLenient = configName == "androidRuntimeClasspath"
    // `artifactType=jar`: AARs become their `classes.jar`. The coordinate map below must come from
    // this same view, so its keys match the closure walk's paths; otherwise AAR deps would be
    // inlined instead of recorded as `ClasspathEntry.Maven`.
    val depJarView =
      depConfig?.incoming?.artifactView {
        if (depViewLenient) lenient(true)
        attributes.attribute(artifactTypeAttr, "jar")
      }
    // The jar view erases aar vs jar, but the player needs the real packaging, so read the
    // untransformed artifacts too and key packaging off the file extension. Lenient, because deps
    // exposing AGP secondary variants without standard attributes would otherwise fail with
    // `AmbiguousArtifactsFailure`; skipped deps default to `"jar"`.
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
    // Dependency jar → coordinate for `bundle.json`:
    // - `maven:<group>:<artifact>:<version>:<aar|jar>` — resolved by the player at open time.
    // - `project:<gradle path>` — inlined, since it can't be re-resolved.
    // Transformed artifacts keep their `componentIdentifier`, so this works on extracted jars.
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
    return DependencyClasspathBinding(configName, depJarView?.files, coordMapProvider)
  }

  fun registerDesktopTasks(project: Project, extension: PreviewExtension) {
    val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

    // `classes/kotlin/android/main` is the fallback for a module whose only compilation is
    // KMP-Android (#248). It must not be packed alongside a real JVM/desktop compilation: both dirs
    // hold commonMain compiled for different targets, and mixing them causes `NoClassDefFoundError`
    // on the module's own `expect`/`actual` classes. Gated lazily on the same resolution as the
    // dependency classpath (see [desktopDependencyConfigName]).
    val sourceClassDirs =
      project.files(
        project.layout.buildDirectory.dir("classes/kotlin/main"),
        project.layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        project.layout.buildDirectory.dir("classes/kotlin/desktop/main"),
        project.provider {
          if (desktopDependencyConfigName(project) == "androidRuntimeClasspath")
            listOf(project.layout.buildDirectory.dir("classes/kotlin/android/main").get())
          else emptyList()
        },
      )

    // The integrity guard checks only the active compilation's output; stale classes in inactive
    // fallbacks could mask an empty cache restore. Lazy so late-registered Kotlin tasks count.
    val activeSourceClassDirs =
      project.files(
        project.provider {
          val activeCompileTask = DESKTOP_COMPILE_TASK_CANDIDATES.firstOrNull {
            it in project.tasks.names
          }
          val relativePath =
            when (activeCompileTask) {
              "compileKotlinJvm" -> "classes/kotlin/jvm/main"
              "compileKotlinDesktop" -> "classes/kotlin/desktop/main"
              "compileAndroidMain" -> "classes/kotlin/android/main"
              "compileKotlin" -> "classes/kotlin/main"
              else -> null
            }
          relativePath?.let { listOf(project.layout.buildDirectory.dir(it).get()) }.orEmpty()
        }
      )

    val activeSourceFiles =
      project
        .files(
          project.provider {
            val activeCompileTask = DESKTOP_COMPILE_TASK_CANDIDATES.firstOrNull {
              it in project.tasks.names
            }
            val sourceSets =
              when (activeCompileTask) {
                "compileKotlinJvm" -> listOf("main", "commonMain", "jvmMain")
                "compileKotlinDesktop" -> listOf("main", "commonMain", "desktopMain")
                "compileAndroidMain" -> listOf("main", "commonMain", "androidMain")
                "compileKotlin" -> listOf("main")
                else -> emptyList()
              }
            sourceSets.map { project.layout.projectDirectory.dir("src/$it").asFile }
          }
        )
        .asFileTree
        .matching {
          include("**/*.kt")
          include("**/*.java")
        }

    // Processed-resource dirs in [sourceClassDirs] order, linked onto the render classpath; missing
    // dirs contribute nothing.
    val sourceResourceDirs =
      project.files(
        project.layout.buildDirectory.dir("resources/main"),
        project.layout.buildDirectory.dir("processedResources/jvm/main"),
        project.layout.buildDirectory.dir("processedResources/desktop/main"),
      )

    // Resolved lazily inside each task's configuration: on KMP-Android `desktopRuntimeClasspath`
    // doesn't exist yet when [registerDesktopTasks] runs, and eager resolution would pin the
    // desktop renderer to `*-android` AARs.
    val resolveDependencyConfigName: () -> String = { desktopDependencyConfigName(project) }

    val discoverTask =
      registerDiscoverTask(
        project,
        sourceClassDirs,
        resolveDependencyConfigName,
        previewOutputDir,
        extension,
        activeSourceClassDirs = activeSourceClassDirs,
        activeCompilationSourceFiles = activeSourceFiles,
        // Lenient only for the `androidRuntimeClasspath` fallback, so `compose-preview list` never
        // aborts there.
        lenientWhenAndroidOnlyFallback = true,
        // Desktop can't render `@ColorCatalog` sheets yet (#2135).
        catalogRenderSupported = false,
      ) {
        onlyIf { extension.enabled.get() }
        // Lazy matching so compile tasks registered later are still wired.
        dependsOn(project.tasks.matching { it.name in DESKTOP_COMPILE_TASK_CANDIDATES })
        // Lottie assets from processed resources (desktop only).
        resourceDirs.from(sourceResourceDirs)
        dependsOn(project.tasks.matching { it.name in DESKTOP_RESOURCE_TASK_CANDIDATES })
      }
    registerCompileOnlyTask(project, extension, DESKTOP_COMPILE_TASK_CANDIDATES)

    val rendererConfig = ensureRendererDesktopConfig(project, "composePreviewRenderer")
    // Resolve the renderer in the consumer's graph so one Skiko / Compose version wins (#1844). See
    // [alignDesktopToolWithConsumerGraph].
    alignDesktopToolWithConsumerGraph(
      project,
      extension,
      rendererConfig,
      resolveDependencyConfigName,
    )

    val renderClasspathGuard =
      registerDesktopClasspathGuard(
        project = project,
        taskName = "validateComposePreviewDesktopRenderClasspath",
        dependencyConfigName = resolveDependencyConfigName,
        toolClasspath = rendererConfig,
      )
    val renderTask =
      project.tasks.register("composePreviewRender", RenderPreviewsTask::class.java) {
        projectDirectory.set(project.layout.projectDirectory)
        onlyIf { extension.enabled.get() }
        previewsJson.set(previewOutputDir.map { it.file("previews.json") })
        outputDir.set(previewOutputDir.map { it.dir("renders") })
        // Data products (`@ScrollingPreview(modes = [LONG, GIF])`), declared as an output for
        // caching.
        dataProductsDir.set(previewOutputDir.map { it.dir("data") })
        renderBackend.set("desktop")
        // Fork on a JDK new enough for the module's bytecode; desktop `javaexec` otherwise uses the
        // Gradle JVM. See [RenderJvmSelection]; null leaves the default.
        desktopRenderJavaExecutable(project, extension)?.let { renderJavaExecutable.set(it) }
        tier.set(tierProperty(project))
        // `composePreview.filter` convention for `--preview` (#2066); comma-separated.
        previewFilters.convention(previewFilterProperty(project))
        // `composePreview.idFilter` convention for `--preview-id` (#2966).
        previewIdFilters.convention(previewIdFilterProperty(project))
        previewIdExcludes.convention(previewIdExcludeProperty(project))
        // `composePreview.rowExclude`: the `@PreviewParameter` row axis, forwarded as a system
        // property since rows only exist after enumeration.
        previewRowExcludes.convention(previewRowExcludeProperty(project))
        permutations.convention(previewPermutationsProperty(project))
        displayFilterFilters.set(AndroidPreviewSupport.resolveDisplayFilterFilters(project))
        linkBufferComposer.set(composeAiLinkBufferComposer(project, extension))
        deviceFrameDevice.set(AndroidPreviewSupport.resolveDeviceFrameDevice(project))
        renderClasspath.from(sourceClassDirs)
        // Processed resources for classpath assets at render time.
        renderClasspath.from(sourceResourceDirs)
        // Add the consumer's runtime classpath separately only when the renderer config wasn't
        // folded into it ([alignDesktopToolWithConsumerGraph]). Otherwise it would prepend a
        // second, lower-versioned copy of shared modules (e.g. coroutines-core 1.9 against
        // coroutines-test 1.11 → `NoSuchMethodError`). The KMP-Android fallback is never aligned.
        // See [pinnedConsumerClasspath].
        val consumerConfigName = resolveDependencyConfigName()
        if (!isAlignedWithConsumerGraph(project, consumerConfigName)) {
          pinnedConsumerClasspath(project, consumerConfigName)?.let { renderClasspath.from(it) }
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

    // Must run at apply time, before task realization, where MutationGuard rejects `afterEvaluate`.
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
   * Registers `composePreviewBundle`: packs the module and selected previews into a PNG+ZIP
   * polyglot, from `previews.json` and the same classes/classpath the render uses. The cover comes
   * from `renders/` when present, else a stub.
   *
   * Selection comes from `-PbundlePreviewIds=…` or the CLI. Doesn't depend on
   * `composePreviewRender`; run both in one invocation for real covers.
   */
  internal fun registerBundleTask(
    project: Project,
    extension: PreviewExtension,
    previewOutputDir: Provider<Directory>,
    sourceClassDirs: FileCollection,
    resolveDependencyConfigName: () -> String,
    discoverTaskName: String,
    // Recorded as `bundle.json`'s `backend` ("desktop" or "android"); packing itself is
    // backend-agnostic.
    backendId: String = "desktop",
    // (v6 Android) Inputs for protolayout resource carriage; null on desktop.
    //
    // `androidUnitTestConfigFiles` is AGP's unit-test config dir plus the resource APK and manifest
    // it points at, tracked as content so caching can't restore a stale bundle.
    // `androidUnitTestRuntimeClasspath` lazily supplies AGP's test task classpath, the only place
    // the merged R.jar with the tile renderer's `R$style` appears.
    androidUnitTestConfigFiles: FileCollection? = null,
    androidUnitTestRuntimeClasspath: (() -> FileCollection?)? = null,
  ): TaskProvider<BundlePreviewTask> {
    val previewIdsProperty: Provider<List<String>> =
      project.providers.gradleProperty("bundlePreviewIds").map { raw ->
        BundlePreviewIds.parse(raw)
      }
    val outputProperty: Provider<String> = project.providers.gradleProperty("bundleOutput")
    // `-PbundleEmbedDeps=true` → v3 `resolution = "embedded"`: reachable jars in `libs/` instead of
    // coordinates.
    val embedDepsProperty: Provider<Boolean> =
      project.providers.gradleProperty("bundleEmbedDeps").map { it.toBoolean() }
    // `-PbundleIncludeDataExtensions=true` → v7: carry per-extension reports under
    // `extensions/<id>.json`.
    val includeDataExtensionsProperty: Provider<Boolean> =
      project.providers.gradleProperty("bundleIncludeDataExtensions").map { it.toBoolean() }
    val pluginVersionProperty = PluginVersion.value

    val artifactTypeAttr = Attribute.of("artifactType", String::class.java)
    // Resolved at task realization: on KMP-Android this runs before `desktopRuntimeClasspath`
    // exists, and eager resolution packed `*-android` AARs into a `backend: desktop` bundle that
    // then failed on the host JVM. One lazy resolution keeps the carried classpath and the
    // renderability gate in agreement.
    val depBinding: () -> DependencyClasspathBinding = {
      dependencyClasspathBinding(project, resolveDependencyConfigName(), artifactTypeAttr)
    }

    val defaultOutput = previewOutputDir.map { it.file("bundle.png").asFile }
    val resolvedOutput = outputProperty.map { java.io.File(it) }.orElse(defaultOutput)

    // Processed resources dir (`build/resources/main` or
    // `build/processedResources/<sourceSet>/main`), so bundles carry classpath resources; missing
    // means none.
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

    return project.tasks.register("composePreviewBundle", BundlePreviewTask::class.java) {
      // Skip non-renderable pure KMP-Android modules: they discover 0 previews and the bundle task
      // fails on an empty manifest, which would break build-wide `render --bundle`. Captured as a
      // Boolean so `onlyIf` doesn't pin `project`.
      val deps = depBinding()
      val bundleRenderable = bundleRenderable(backendId, deps.configName)
      onlyIf { extension.enabled.get() && bundleRenderable }
      previewsJson.set(previewOutputDir.map { it.file("previews.json") })
      moduleClassDirs.from(sourceClassDirs)
      moduleResourcesDir.set(moduleResourcesDirProvider)
      // Also wire resource dirs as an execution-time collection: [moduleResourcesDir] is probed at
      // configuration time and can snapshot as null on a clean config-cached build, shipping
      // classes only and breaking live re-renders that load fonts. Missing candidates are skipped.
      moduleResourceRoots.from(
        project.files(
          project.layout.buildDirectory.dir("resources/main"),
          project.layout.buildDirectory.dir("processedResources/jvm/main"),
          project.layout.buildDirectory.dir("processedResources/desktop/main"),
        )
      )
      deps.jarFiles?.let { dependencyJars.from(it) }
      dependencyCoordinates.set(deps.coordinates)
      // Invoked here so AGP's test task exists by now.
      androidUnitTestConfigFiles?.let { androidUnitTestConfig.from(it) }
      androidUnitTestRuntimeClasspath?.invoke()?.let {
        this.androidUnitTestRuntimeClasspath.from(it)
      }
      // Missing renders dir means a stub cover. `rendersDir` is the `@Internal` root; `renderFiles`
      // tracks contents so the bundle re-packs when renders change.
      rendersDir.set(previewOutputDir.map { it.dir("renders") })
      renderFiles.from(previewOutputDir.map { it.dir("renders") })
      // Android asset renders live in sibling dirs; track them too. Absent dirs (desktop) snapshot
      // as empty.
      renderFiles.from(previewOutputDir.map { it.dir(AndroidPreviewSupport.SVG_RENDER_SUBDIR) })
      renderFiles.from(previewOutputDir.map { it.dir(AndroidPreviewSupport.LOTTIE_RENDER_SUBDIR) })
      // Catalog-token sidecars live outside `renderFiles`' tree; track them separately.
      catalogTokenFiles.from(previewOutputDir.map { it.dir("data/catalog-tokens") })
      // A `compose-preview guidelines` run's results, carried so a hosting server can serve them.
      guidelineResultsFiles.from(previewOutputDir.map { it.file("guidelines.json") })
      // Builder-catalog inputs, resolved like `composePreviewDiscover`: module first, then
      // repository root.
      uiBuilderPolicyCandidates.from(
        project.layout.projectDirectory.file("ui-builder.policy.json"),
        project.rootProject.layout.projectDirectory.file("ui-builder.policy.json"),
      )
      uiBuilderGuidelinesCandidates.from(
        project.layout.projectDirectory.file(UiBuilderGuidelinesFile.FILE_NAME),
        project.rootProject.layout.projectDirectory.file(UiBuilderGuidelinesFile.FILE_NAME),
      )
      catalogSpecCandidates.from(
        project.layout.projectDirectory.file("catalog.spec.json"),
        project.rootProject.layout.projectDirectory.file("catalog.spec.json"),
      )
      // Template designs from the conventional directory that branch-relative `templates` entries
      // resolve in.
      uiBuilderTemplateCandidates.from(
        uiBuilderTemplateTree(project.layout.projectDirectory.dir("ui-builder")),
        uiBuilderTemplateTree(project.rootProject.layout.projectDirectory.dir("ui-builder")),
      )
      uiBuilderTemplateRoots.from(
        project.layout.projectDirectory,
        project.rootProject.layout.projectDirectory,
      )
      previewIds.set(previewIdsProperty.orElse(emptyList()))
      embedDeps.set(embedDepsProperty.orElse(false))
      // (v9) See [extraMavenRepositoryUrls].
      extraMavenRepositories.set(extraMavenRepositoryUrls(project))
      includeDataExtensions.set(includeDataExtensionsProperty.orElse(false))
      // Aggregated report sidecars, as an up-to-date signal; the `.png` output never matches
      // `*.json`.
      dataExtensionFiles.from(previewOutputDir.map { it.asFileTree.matching { include("*.json") } })
      modulePath.set(project.path)
      // (v6 Android) Base dir for test_config.properties' relative paths. See
      // [BundlePreviewTask.moduleProjectDir].
      moduleProjectDir.set(project.layout.projectDirectory)
      // Resolved here because only Gradle knows the physical directory. Empty for the root project.
      moduleDirectory.set(
        project.projectDir.relativeToOrNull(project.rootDir)?.invariantSeparatorsPath.orEmpty()
      )
      // From the layout, so a relocated `buildDirectory` still finds AGP's blame files. See
      // [BundlePreviewTask.moduleBuildDir].
      moduleBuildDir.set(project.layout.buildDirectory)
      backend.set(backendId)
      producedBy.set("compose-preview $pluginVersionProperty")
      output.set(project.layout.file(resolvedOutput))
      group = "compose preview"
      description = "Pack selected previews + minimal classpath into a portable PNG+ZIP polyglot."
      // No `dependsOn` render (a stub cover is valid), but `renderFiles` reads its output, so
      // `mustRunAfter` gives the ordering Gradle's validation requires without pulling render in.
      mustRunAfter("composePreviewRender")
      // Same for the Android asset render tasks, whose outputs are also inputs; otherwise
      // `composePreviewRenderAll composePreviewBundle` in one invocation fails validation. By type,
      // so it stays lazy and empty on desktop.
      mustRunAfter(project.tasks.withType(RenderPreviewsTask::class.java))
      dependsOn(discoverTaskName)
      // Package processed resources into `classes/app.jar`: without this ordering, a clean build
      // can pack before `processResources`, and a live re-render from the bundle fails on missing
      // fonts.
      dependsOn(project.tasks.matching { it.name in DESKTOP_RESOURCE_TASK_CANDIDATES })
    }
  }

  /**
   * Desktop counterpart of Android's `composePreviewDaemonStart`: wires `:daemon:desktop`'s
   * [ee.schimke.composeai.daemon.DaemonMain] onto a [DaemonBootstrapTask][
   * ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask] so VS Code and the MCP server can
   * launch the desktop daemon.
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
    // The daemon puts the consumer's runtime classpath on `-cp`, so it needs the same graph
    // alignment (#1844).
    alignDesktopToolWithConsumerGraph(
      project,
      extension,
      daemonRendererConfig,
      dependencyConfigName,
    )
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
      // External mode: `daemon-desktop` from Maven; without it the descriptor has no `DaemonMain`.
      project.dependencies.add(
        daemonRendererConfig.name,
        PreviewDaemonModules.dependency(project, daemonRendererConfig.name, "daemon-desktop"),
      )
    }

    val daemonClasspathGuard =
      registerDesktopClasspathGuard(
        project = project,
        taskName = "validateComposePreviewDesktopDaemonClasspath",
        dependencyConfigName = dependencyConfigName,
        toolClasspath = daemonRendererConfig,
      )

    // Eagerly resolved values so each provider captures only serialisable references (configuration
    // cache).
    val previewsJsonProvider = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
    val rendersDirProvider = previewOutputDir.map { it.dir("renders").asFile.absolutePath }
    val outputFileProvider = previewOutputDir.map { it.file("daemon-launch.json") }
    val daemonFontsCacheDir = composeAiFontsCacheDir(project)
    val daemonHistoryDir = composeAiHistoryDir(project)
    val daemonFontsOffline =
      project.providers.gradleProperty("composePreview.fontsOffline").orElse("false")
    val daemonSvgEmbedFonts = composeAiSvgEmbedFonts(project)
    val daemonSvgBackground = composeAiSvgBackground(project)
    val daemonFontsFailOnFallback = composeAiFontsFailOnFallback(project)
    // Host activity theme for the daemon; see `PreviewHostTheme`.
    val daemonHostTheme = composeAiHostTheme(project, extension)
    // Forwarded to desktop too (unlike `fixedTime`): it's a runtime flag both backends share.
    val daemonLinkBufferComposer = composeAiLinkBufferComposer(project, extension)
    val daemonCheapSignalFiles =
      collectDesktopCheapSignalFiles(project).joinToString(java.io.File.pathSeparator) {
        it.absolutePath
      }
    val consumerBuildDir = project.layout.buildDirectory.asFile.get().absolutePath
    // Same compile output dirs as [registerDesktopTasks]'s `sourceClassDirs`, so the daemon's child
    // classloader sees user classes.
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
      // Skip the descriptor for pure KMP-Android modules (#1852). Never realized by the model
      // builder, so CLI detection is unaffected (#1855). Captured as a Boolean for the
      // configuration cache.
      val renderable = isDesktopRenderableConfig(dependencyConfigName())
      onlyIf { renderable }
      modulePath.set(project.path)
      // Informational only; desktop has no AGP variant.
      variant.set("desktop")
      daemonEnabled.set(extension.daemon.enabled)
      maxHeapMb.set(extension.daemon.maxHeapMb)
      maxRendersPerSandbox.set(extension.daemon.maxRendersPerSandbox)
      warmSpare.set(extension.daemon.warmSpare)
      backgroundSandboxBoot.set(extension.daemon.backgroundSandboxBoot)
      // Always pin a launcher: VS Code / MCP spawn the daemon on their own JDK, which may be too
      // old. See [RenderJvmSelection.daemonDescriptorExecutable].
      desktopDaemonJavaExecutable(project, extension)?.let { javaLauncher.set(it) }
      // Stage-2 BTA wiring; the daemon only loads BTA when in-process compile is enabled, so this
      // costs just config-time resolution.
      wireDesktopBtaInputs(
        project = project,
        extension = extension,
        task = this,
        userRuntimeConfig = project.configurations.findByName(dependencyConfigName()),
      )
      // Shares its FQN with `:daemon:android`'s; the desktop jar is first on the classpath.
      mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Daemon classes first so [mainClass] resolves. A lazy artifact view keeps the classpath
      // config-cache serializable (#1796).
      classpath.from(daemonRendererConfig.incoming.artifactView {}.files)
      // User classes, on the parent classpath before `UserClassLoaderHolder` builds its child
      // loader.
      classpath.from(sourceClassDirs)
      // Resources stay parent-loaded: they don't match the `userClassDirs` markers.
      classpath.from(sourceResourceDirs)
      // Runtime classpath via the pinned view (#1796, #1852); see [pinnedConsumerClasspath].
      pinnedConsumerClasspath(project, dependencyConfigName())?.let { classpath.from(it) }

      // No Robolectric `--add-opens` needed; just the heap limit.
      jvmArgs.add(extension.daemon.maxHeapMb.map { "-Xmx${it}m" })
      // macOS background agent (LSUIElement), so the resident daemon never takes a Dock icon or
      // focus. Must be a launch `-D`; ignored elsewhere. Same as the one-shot render's flag.
      jvmArgs.add("-Dapple.awt.UIElement=true")

      // A subset of Android's properties; per-key `put` for the configuration cache.
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
      systemProperties.put(
        "composeai.daemon.backgroundSandboxBoot",
        extension.daemon.backgroundSandboxBoot.map { it.toString() },
      )
      systemProperties.put("composeai.daemon.modulePath", project.path)
      systemProperties.put(
        "composeai.daemon.moduleProjectDir",
        project.layout.projectDirectory.asFile.absolutePath,
      )
      systemProperties.put("composeai.render.outputDir", rendersDirProvider)
      systemProperties.put("composeai.fonts.cacheDir", daemonFontsCacheDir)
      systemProperties.put("composeai.fonts.offline", daemonFontsOffline)
      systemProperties.put("composeai.fonts.failOnFallback", daemonFontsFailOnFallback)
      systemProperties.put("composeai.svg.embedFonts", daemonSvgEmbedFonts)
      systemProperties.put("composeai.svg.background", daemonSvgBackground)
      systemProperties.put("composeai.render.hostTheme", daemonHostTheme)
      systemProperties.put("composeai.render.linkBufferComposer", daemonLinkBufferComposer)
      // `composeai.render.fixedTime` isn't forwarded: it relies on Robolectric shadowing, which
      // desktop doesn't have. See `PreviewExtension.fixedTime`.
      systemProperties.put(
        "composeai.daemon.perfettoTrace",
        AndroidPreviewSupport.resolveComposeAiTraceEnabled(project, extension).map {
          it.toString()
        },
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
      // Lets `PreviewManifestRouter` map `previewId` to a `RenderSpec`; without it renders fall
      // back to a stub (#314). The "harness" prefix is historical.
      systemProperties.put("composeai.harness.previewsManifest", previewsJsonProvider)
      // Enables daemon history, under the user-level cache root ([composeAiHistoryDir]).
      systemProperties.put("composeai.daemon.historyDir", daemonHistoryDir)
      systemProperties.put("composeai.daemon.workspaceRoot", project.rootDir.absolutePath)

      workingDirectory.set(project.projectDir.absolutePath)
      manifestPath.set(previewsJsonProvider)
      // See `DaemonBootstrapTask.previewsManifest`; a null provider leaves the `@Optional` input
      // unset.
      previewsManifest.fileProvider(
        previewOutputDir.flatMap { dir ->
          project.providers.provider {
            val f = dir.file("previews.json").asFile
            if (f.isFile) f else null
          }
        }
      )
      // Ordering only, as on Android: the provider carries no dependency, and `dependsOn` would
      // prevent warming before discovery.
      mustRunAfter("composePreviewDiscover")
      outputFile.set(outputFileProvider)
      dependsOn(daemonClasspathGuard)
      // Stage resources before emitting the descriptor.
      dependsOn(project.tasks.matching { it.name in DESKTOP_RESOURCE_TASK_CANDIDATES })
      group = "compose preview"
      description =
        "Emit build/compose-previews/daemon-launch.json so VS Code can spawn the desktop preview daemon JVM"
    }
  }

  /**
   * Folds the desktop renderer / daemon config [toolConfig] into the consumer runtime graph so
   * conflict resolution picks one version of each shared module. Merging raw `FileCollection`s
   * doesn't resolve conflicts: a newer consumer Skiko's Java bindings ended up beside the
   * renderer's older native library → `UnsatisfiedLinkError` (#1844). Mirrors Android's
   * `extendsFrom(testConfig)`; [copyAttributes] supplies the consumer's platform attributes for KMP
   * variant selection.
   *
   * Not applied to the pure-Android KMP fallback (`androidJvm`), which the desktop renderer can't
   * use anyway. In `afterEvaluate` because the KMP `jvm`/`desktop` classpaths may not exist yet.
   */
  private fun alignDesktopToolWithConsumerGraph(
    project: Project,
    extension: PreviewExtension,
    toolConfig: org.gradle.api.artifacts.Configuration,
    dependencyConfigName: () -> String,
  ) {
    project.afterEvaluate {
      // Consumer exclusions apply here too, since `extendsFrom` brings the consumer's constraints
      // onto our config. See [RenderGraphExtension] (#4995).
      RenderGraphExclusions.applyTo(project, toolConfig, extension.renderGraph.excludes.get())
      val depName = dependencyConfigName()
      // androidJvm classpath: the desktop renderer has no matching variant — leave it alone.
      if (!isAlignedWithConsumerGraph(project, depName)) return@afterEvaluate
      val depConfig = project.configurations.getByName(depName)
      copyAttributes(toolConfig.attributes, depConfig.attributes)
      toolConfig.extendsFrom(depConfig)
    }
  }

  /**
   * Whether [alignDesktopToolWithConsumerGraph] folds a tool config into [consumerConfigName]:
   * every desktop classpath except the KMP-Android fallback, if it exists. One answer for both the
   * wiring and the render classpath, so consumer jars are never added twice.
   */
  internal fun isAlignedWithConsumerGraph(project: Project, consumerConfigName: String): Boolean =
    consumerConfigName != "androidRuntimeClasspath" &&
      project.configurations.findByName(consumerConfigName) != null

  /**
   * Copies attributes from [source] onto [target] for variant selection, except
   * `org.gradle.jvm.version`: the tool modules target Java 17, so inheriting a consumer's lower
   * target would reject them. AGP-free mirror of [AndroidPreviewSupport]'s helper.
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
      // Skip the guard for pure KMP-Android modules, which can't be desktop-rendered and would
      // hard-fail it (#1852). Never realized by the model builder (#1855). Captured as a Boolean
      // for the configuration cache.
      val renderable = isDesktopRenderableConfig(dependencyConfigName())
      onlyIf { renderable }
      platform.set("desktop")
      // A lazy artifact view, not the raw `Configuration`, which the configuration cache can't
      // serialize (#1796). Same files.
      val toolFiles = toolClasspath.incoming.artifactView {}.files
      classpath.from(toolFiles)
      pinnedConsumerClasspath(project, dependencyConfigName())?.let { classpath.from(it) }
      // The tool's files on their own, so the Skiko check can tell its pair from the consumer's
      // (#4200). `classpath` stays the union.
      this.toolClasspath.from(toolFiles)
    }

  /**
   * Tier-1 cheap-signal files for [ClasspathFingerprint][
   * ee.schimke.composeai.daemon.ClasspathFingerprint]; shared with Android via
   * [CheapSignalFiles.collect] since the daemon hashes them as one input.
   */
  private fun collectDesktopCheapSignalFiles(project: Project): List<java.io.File> =
    CheapSignalFiles.collect(project)

  /**
   * Creates the two BTA configurations (`maybeCreate`) and populates them in `afterEvaluate` for
   * the consumer's Kotlin version. Always populated; the cost is only paid when the daemon compiles
   * in-process.
   *
   * Must run at configuration time: MutationGuard rejects `afterEvaluate` during task realization.
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
   * Stage-2 BTA wiring for the desktop daemon-start task; [setupBtaConfigurations] must run first.
   *
   * The Kotlin version comes from `libs.versions.toml`; other consumers get a fallback. TODO: use
   * KGP's `KotlinPluginWrapper.kotlinPluginVersion` once we take a compile-time KGP dependency.
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
      // The consumer runtime classpath is the compile classpath, via the pinned lazy view (#1796,
      // #1852). Empty when no runtime config exists.
      userCompileClasspath =
        userRuntimeConfig?.let { pinnedConsumerClasspath(project, it.name) } ?: project.files(),
      // KGP's default module name for JVM modules, as seen in `kotlin.Metadata.d2[]`.
      moduleName = project.name,
      // KGP's plain Kotlin/JVM output dir; the daemon watches other shapes via `userClassDirs`.
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
   * Wires BTA inputs on [DaemonBootstrapTask] (compile classpath, module name, output and IC dirs)
   * and mirrors them into daemon system properties for `DefaultBtaCompileService.fromSysprops()`.
   * [setupBtaConfigurations] must run first.
   *
   * The property names duplicate `:daemon:core`'s `DefaultBtaCompileService.SYSPROP_*` (separate
   * builds); keep in sync.
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
    // Lazy artifact views for config-cache serializability (#1796); `userCompileClasspath` is
    // already clean from both callers.
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

  /** Shared with `AndroidPreviewSupport`; see [detectStageTwoIneligibility]. */
  internal fun detectStageTwoIneligibilityFor(project: Project): String? =
    detectStageTwoIneligibility(project)

  /** Shared with `AndroidPreviewSupport`, which calls it at apply time. Idempotent. */
  internal fun setupBtaConfigurationsFor(project: Project, extension: PreviewExtension) =
    setupBtaConfigurations(project, extension)

  /**
   * Stage-2 (in-process compile) eligibility: a reason string when ineligible, `null` when
   * eligible.
   * - **KSP / KAPT** — generated sources need regenerating on save, which BTA doesn't drive.
   * - **`annotationProcessor` deps** — same problem for javac APs.
   * - **KMP** — the wiring models a single JVM source set only.
   *
   * Plugins are matched by id; AP configurations are read by name without resolving, so it's
   * configuration-cache-safe.
   */
  private fun detectStageTwoIneligibility(project: Project): String? =
    when {
      project.plugins.hasPlugin("com.google.devtools.ksp") ->
        "com.google.devtools.ksp plugin applied (stage 2 doesn't drive KSP yet)"
      project.plugins.hasPlugin("org.jetbrains.kotlin.kapt") ->
        "org.jetbrains.kotlin.kapt plugin applied (stage 2 doesn't drive KAPT yet)"
      project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ->
        "org.jetbrains.kotlin.multiplatform plugin applied (stage 2 covers single-source-set " +
          "JVM/Android modules only; KMP source-set wiring stays on stage 1)"
      hasAnnotationProcessorDependencies(project) ->
        "annotationProcessor dependencies declared (javac annotation processors aren't BTA-driven)"
      else -> null
    }

  /**
   * True when any `annotationProcessor`-shaped configuration declares a dependency. Only matching
   * configurations are realized; nothing is resolved.
   */
  private fun hasAnnotationProcessorDependencies(project: Project): Boolean =
    project.configurations.names
      .filter { it.contains("annotationProcessor", ignoreCase = true) }
      .any { name -> project.configurations.getByName(name).dependencies.isNotEmpty() }

  /**
   * The consumer's Kotlin version from `libs.versions.toml`. The BTA impl must exactly match the
   * compiler version. Falls back to [KOTLIN_VERSION_FALLBACK] (our bundled Kotlin), which is wrong
   * for arbitrary consumers; follow-up: use KGP's `kotlinPluginVersion`.
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
   * `composePreview.tier`: `"fast"` (case-insensitive) skips captures above [HEAVY_COST_THRESHOLD];
   * anything else is `"full"`. Lazy, so flipping it doesn't invalidate the configuration cache.
   */
  internal fun tierProperty(project: Project): Provider<String> =
    project.providers
      .gradleProperty("composePreview.tier")
      .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
      .orElse("full")

  /**
   * `java` for the desktop render subprocess when the bytecode target outruns the Gradle JVM (or
   * `renderJavaVersion` is set), else `null`. See [RenderJvmSelection]. `null` without a toolchain
   * service.
   */
  internal fun desktopRenderJavaExecutable(
    project: Project,
    extension: PreviewExtension,
  ): Provider<String>? {
    val toolchains = project.extensions.findByType(JavaToolchainService::class.java) ?: return null
    val override =
      project.providers.gradleProperty("composePreview.renderJavaVersion").orNull?.toIntOrNull()
        ?: extension.renderJavaVersion.orNull
    return RenderJvmSelection.daemonJvmExecutable(
      toolchains = toolchains,
      gradleDaemonMajor = JavaVersion.current().majorVersion.toInt(),
      bytecodeMajor = detectDesktopBytecodeMajor(project),
      explicitOverride = override,
    )
  }

  /**
   * Like [desktopRenderJavaExecutable] but always pins a launcher, since VS Code / MCP spawn the
   * daemon on their own JDK. See [RenderJvmSelection.daemonDescriptorExecutable].
   */
  internal fun desktopDaemonJavaExecutable(
    project: Project,
    extension: PreviewExtension,
  ): Provider<String>? {
    val toolchains = project.extensions.findByType(JavaToolchainService::class.java) ?: return null
    val override =
      project.providers.gradleProperty("composePreview.renderJavaVersion").orNull?.toIntOrNull()
        ?: extension.renderJavaVersion.orNull
    return RenderJvmSelection.daemonDescriptorExecutable(
      toolchains = toolchains,
      gradleDaemonMajor = JavaVersion.current().majorVersion.toInt(),
      bytecodeMajor = detectDesktopBytecodeMajor(project),
      explicitOverride = override,
    )
  }

  /**
   * Highest bytecode target from Java `targetCompatibility` and Kotlin `jvmTarget`, or `null`.
   * Defensive, like the Android detector.
   */
  private fun detectDesktopBytecodeMajor(project: Project): Int? {
    val candidates = mutableListOf<Int>()
    runCatching {
      project.extensions
        .findByType(JavaPluginExtension::class.java)
        ?.targetCompatibility
        ?.let { BytecodeTargetDetector.parseTargetMajor(it.toString()) }
        ?.let { candidates += it }
    }
    BytecodeTargetDetector.detectKotlinJvmTarget(
        project,
        listOf("compileKotlin", "compileKotlinJvm", "compileKotlinDesktop"),
      )
      ?.let { candidates += it }
    return candidates.filter { it > 0 }.maxOrNull()
  }

  /**
   * `composePreview.filter`: the `--preview` name filter as a property (#2066). Comma-separated,
   * blanks dropped; empty means render everything. The task option overrides it.
   */
  internal fun previewFilterProperty(project: Project): Provider<List<String>> =
    project.providers
      .gradleProperty("composePreview.filter")
      .map { v -> v.split(",").map(String::trim).filter(String::isNotEmpty) }
      .orElse(emptyList())

  /**
   * `composePreview.idFilter`: the `--preview-id` filter (#2966), selecting fan-out members by id
   * (they share `functionName`). Same shape as [previewFilterProperty]; set by the design-artifacts
   * pipeline via `ORG_GRADLE_PROJECT_…`. Applied on desktop by [RenderPreviewsTask] and forwarded
   * to the Android render JVM by [RobolectricRenderTask] (#2977).
   */
  internal fun previewIdFilterProperty(project: Project): Provider<List<String>> =
    previewIdFilterFileProperty(project)
      .map { path ->
        val file = java.io.File(path)
        check(file.isFile) {
          "composePreview.idFilterFile names '$path', which is not a readable file. Refusing to " +
            "fall back to an unfiltered render, which would render every preview and look like " +
            "success."
        }
        file.readLines(Charsets.UTF_8).map(String::trim).filter(String::isNotEmpty)
      }
      .orElse(
        project.providers.gradleProperty("composePreview.idFilter").map { v ->
          v.split(",").map(String::trim).filter(String::isNotEmpty)
        }
      )
      .orElse(emptyList())

  /**
   * `composePreview.idFilterFile`: path to a newline-delimited UTF-8 pattern list; replaces
   * `composePreview.idFilter` when set. Needed because a non-UTF-8 `sun.jnu.encoding` mangles
   * non-ASCII arguments to `?` (#5172), and it can carry ids containing commas. Used by
   * `compose-preview show` when the property can't carry the selection.
   */
  internal fun previewIdFilterFileProperty(project: Project): Provider<String> =
    project.providers.gradleProperty("composePreview.idFilterFile").map(String::trim).filter {
      it.isNotEmpty()
    }

  /**
   * `composePreview.idExclude`: the `--exclude-preview-id` form, used by design catalogs to defer
   * palettes (#2966). Reaches both backends (#2977).
   */
  internal fun previewIdExcludeProperty(project: Project): Provider<List<String>> =
    previewIdExcludeFileProperty(project)
      .map { path ->
        val file = java.io.File(path)
        check(file.isFile) {
          "composePreview.idExcludeFile names '$path', which is not a readable file. Refusing to " +
            "fall back to an empty exclusion list, which would render every preview and look " +
            "like success."
        }
        file.readLines().map(String::trim).filter(String::isNotEmpty)
      }
      .orElse(
        project.providers.gradleProperty("composePreview.idExclude").map { v ->
          v.split(",").map(String::trim).filter(String::isNotEmpty)
        }
      )
      .orElse(emptyList())

  /**
   * `composePreview.idExcludeFile`: path to a newline-delimited exclusion list; replaces
   * `composePreview.idExclude`. Needed because ids can contain commas, and comma-split fragments
   * then exclude by substring (`dpi=320` would exclude the whole module). Set by `bundle pack
   * --exclude-preview-id-file`.
   */
  internal fun previewIdExcludeFileProperty(project: Project): Provider<String> =
    project.providers.gradleProperty("composePreview.idExcludeFile").map(String::trim).filter {
      it.isNotEmpty()
    }

  /**
   * `composePreview.rowExclude`: the `--exclude-preview-row` form, skipping `@PreviewParameter`
   * rows by label (discovery never sees rows).
   */
  internal fun previewRowExcludeProperty(project: Project): Provider<List<String>> =
    project.providers
      .gradleProperty("composePreview.rowExclude")
      .map { v -> v.split(",").map(String::trim).filter(String::isNotEmpty) }
      .orElse(emptyList())

  /**
   * `composePreview.permutations`. The `accessibility` preset synthesizes dark, RTL and large-font
   * siblings at render time.
   */
  internal fun previewPermutationsProperty(project: Project): Provider<List<String>> =
    project.providers
      .gradleProperty(PreviewPermutations.PROPERTY)
      .map { v -> PreviewPermutations.clean(listOf(v)) }
      .orElse(emptyList())

  /**
   * `composePreview.missingRenders`: how `composePreviewRenderAll` handles a manifest preview with
   * no output — `"fail"` (default), `"warn"`, or `"ignore"` (both still write the validation
   * marker). Unknown values mean `"fail"`. Also exposed by the `apply` GitHub action.
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
    activeSourceClassDirs: FileCollection = sourceClassDirs,
    activeCompilationSourceFiles: FileCollection? = null,
    // Desktop passes `true`: discovery's dependency jars resolve leniently only when the config is
    // the `androidRuntimeClasspath` fallback, so `compose-preview list` doesn't abort on
    // `AmbiguousArtifactsFailure`. Everything else stays strict.
    lenientWhenAndroidOnlyFallback: Boolean = false,
    // `false` on desktop, which can't render `@ColorCatalog` sheets yet (#2135).
    catalogRenderSupported: Boolean = true,
    // Whether the renderer encodes `@AnimatedPreview(format = Apng)` as APNG; `true` for both
    // backends the plugin wires (Android since daemon 3.13.0, pinned via the BOM).
    animatedPreviewApngSupported: Boolean = true,
    configureDeps: DiscoverPreviewsTask.() -> Unit,
  ): TaskProvider<DiscoverPreviewsTask> {
    val artifactType = Attribute.of("artifactType", String::class.java)

    return project.tasks.register("composePreviewDiscover", DiscoverPreviewsTask::class.java) {
      this.catalogRenderSupported.set(catalogRenderSupported)
      this.animatedPreviewApngSupported.set(animatedPreviewApngSupported)
      classDirs.from(sourceClassDirs)
      activeClassDirs.from(activeSourceClassDirs)

      // Shared preview-source modules go on `classDirs`, not `dependencyJars`: dependency jars are
      // never method-walked, so their previews would be invisible. Jars are sorted from dirs
      // because discovery drops non-directories from `classDirs`.
      //
      // Not added to `activeClassDirs`, which is about this module's own compilation output.
      previewSourceClasspath(project, dependencyConfigName())?.let { config ->
        previewSourceClasses.from(
          config.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files
        )
        previewSourceClasses.from(
          config.incoming
            .artifactView { attributes.attribute(artifactType, "android-classes") }
            .files
        )
      }

      sourceFiles.from(
        project.fileTree("src") {
          include("**/*.kt")
          include("**/*.java")
        }
      )
      // Their sources too, so previews map to their declaring file and `@file:CatalogGroup`
      // applies. See `composePreview.previewSourceRoots`.
      sourceFiles.from(
        extension.previewSourceRoots.asFileTree.matching {
          include("**/*.kt")
          include("**/*.java")
        }
      )
      activeSourceFiles.from(activeCompilationSourceFiles ?: sourceFiles)
      val configName = dependencyConfigName()
      project.configurations.findByName(configName)?.let { config ->
        // Lenient only for the KMP-Android fallback.
        val useLenient = lenientWhenAndroidOnlyFallback && configName == "androidRuntimeClasspath"
        // Request extracted `classes.jar` for AARs; a no-op for JVM jars.
        dependencyJars.from(
          config.incoming
            .artifactView {
              if (useLenient) lenient(true)
              attributes.attribute(artifactType, "jar")
            }
            .files
        )
        dependencyJars.from(
          config.incoming
            .artifactView {
              if (useLenient) lenient(true)
              attributes.attribute(artifactType, "android-classes")
            }
            .files
        )
        // Coordinates for the jars above, from the same views so transformed paths match (see
        // [dependencyClasspathBinding]).
        for (attributeValue in listOf("jar", "android-classes")) {
          dependencyJarCoordinates.putAll(
            config.incoming
              .artifactView {
                if (useLenient) lenient(true)
                attributes.attribute(artifactType, attributeValue)
              }
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
      moduleName.set(project.name)
      variantName.set(extension.variant)
      projectDirectory.set(project.layout.projectDirectory.asFile.absolutePath)
      // Defaults to `renders/` (desktop's only writer); Android overrides it in [configureDeps].
      lottieRenderSubdir.convention("renders")
      // Same for SVG.
      svgRenderSubdir.convention("renders")
      // The Gradle property wins over the extension.
      failOnEmpty.set(
        project.providers
          .gradleProperty("composePreview.failOnEmpty")
          .map { it.toBooleanStrictOrNull() ?: false }
          .orElse(extension.failOnEmpty)
      )
      // The Gradle property wins over the extension (#2670).
      retargetWearPreviews.set(
        project.providers
          .gradleProperty("composePreview.retargetWearPreviews")
          .map { it.toBooleanStrictOrNull() ?: true }
          .orElse(extension.retargetWearPreviews)
      )
      componentLibraryPrefixes.set(extension.componentLibraryPrefixes)
      // No per-extension opt-in: a11y is daemon-only, so `dataExtensionReports` is empty here.
      outputFile.set(previewOutputDir.map { it.file("previews.json") })
      componentsFile.set(previewOutputDir.map { it.file("components.json") })
      uiBuilderFile.set(previewOutputDir.map { it.file("ui-builder.json") })
      uiBuilderGuidelinesFile.set(
        previewOutputDir.map { it.file(UiBuilderGuidelinesFile.FILE_NAME) }
      )
      // The tree the copied template designs land in, beside the catalog that names them.
      uiBuilderTemplateDir.set(previewOutputDir.map { it.dir("ui-builder") })
      // Module first, then repository root; candidates may be absent.
      uiBuilderPolicyCandidates.from(
        project.layout.projectDirectory.file("ui-builder.policy.json"),
        project.rootProject.layout.projectDirectory.file("ui-builder.policy.json"),
      )
      uiBuilderGuidelinesCandidates.from(
        project.layout.projectDirectory.file(UiBuilderGuidelinesFile.FILE_NAME),
        project.rootProject.layout.projectDirectory.file(UiBuilderGuidelinesFile.FILE_NAME),
      )
      catalogSpecCandidates.from(
        project.layout.projectDirectory.file("catalog.spec.json"),
        project.rootProject.layout.projectDirectory.file("catalog.spec.json"),
      )
      // Template designs from the conventional directory that branch-relative `templates` entries
      // resolve in.
      uiBuilderTemplateCandidates.from(
        uiBuilderTemplateTree(project.layout.projectDirectory.dir("ui-builder")),
        uiBuilderTemplateTree(project.rootProject.layout.projectDirectory.dir("ui-builder")),
      )
      uiBuilderTemplateRoots.from(
        project.layout.projectDirectory,
        project.rootProject.layout.projectDirectory,
      )
      group = "compose preview"
      description = "Discover @Preview annotations in compiled classes"
      configureDeps()
    }
  }

  /**
   * Registers `composePreviewCompile`, which runs the compile tasks `composePreviewDiscover`
   * depends on without discovery, so VS Code can refresh `.class` files on save without a
   * ClassGraph walk (the daemon reconciles metadata itself).
   *
   * Compile tasks are matched lazily by name; with none (no Kotlin plugin) the task is a no-op.
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
    // Post-condition: every manifest entry must have its output after rendering. A missing PNG is a
    // wiring bug, most commonly `composePreviewRender` going NO-SOURCE because the AAR classes.jar
    // wasn't expanded into `testClassesDirs`.
    val manifestFile = previewOutputDir.map { it.file("previews.json") }
    val rendersDir = previewOutputDir.map { it.dir("renders") }
    val validationMarker = previewOutputDir.map { it.file("composePreviewRenderAll.validated") }
    // Captured at configuration time; under "fast" heavy captures may legitimately be missing.
    val tierProvider =
      project.providers
        .gradleProperty("composePreview.tier")
        .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
        .orElse("full")
    val missingRendersProvider = missingRendersProperty(project)
    val permutationsProvider = previewPermutationsProperty(project)
    // The render's selection (#3730), declared as inputs too: otherwise filtered and unfiltered
    // runs share inputs and the second would skip validation as UP-TO-DATE.
    val nameFilterProvider = previewFilterProperty(project)
    val idFilterProvider = previewIdFilterProperty(project)
    val idExcludeProvider = previewIdExcludeProperty(project)
    project.tasks.register("composePreviewRenderAll", DefaultTask::class.java) {
      group = "compose preview"
      dependsOn(renderTask)
      inputs
        .file(manifestFile)
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("manifest")
      inputs.property("tier", tierProvider)
      inputs.property("missingRenders", missingRendersProvider)
      inputs.property("permutations", permutationsProvider)
      inputs.property("previewFilter", nameFilterProvider)
      inputs.property("previewIdFilter", idFilterProvider)
      inputs.property("previewIdExclude", idExcludeProvider)
      outputs.file(validationMarker).withPropertyName("validationMarker")
      doLast {
        val isFastTier = tierProvider.get() == "fast"
        val missingPolicy = missingRendersProvider.get()
        val manifestOnDisk = manifestFile.get().asFile
        if (!manifestOnDisk.exists()) return@doLast
        val manifestRaw =
          previewManifestJson.decodeFromString(
            PreviewManifest.serializer(),
            manifestOnDisk.readText(),
          )
        val manifest =
          manifestRaw.copy(
            previews = PreviewPermutations.expand(manifestRaw.previews, permutationsProvider.get())
          )
        if (manifest.previews.isEmpty()) return@doLast

        // `renders/` is derived, and downstream tools compare the manifest with what's on disk, so
        // delete anything the current manifest doesn't reference. `@PreviewParameter` fan-out files
        // (`<stem>_*`) are kept; the renderer cleans those itself.
        cleanStaleRenders(previewOutputDir.get().asFile.resolve("renders"), manifest, logger)
        // Likewise prune stale `data/catalog-tokens/` sidecars (#2167).
        cleanStaleCatalogTokens(
          previewOutputDir.get().asFile.resolve("data/catalog-tokens"),
          manifest,
          logger,
        )
        // Report one missing entry per preview with any missing capture.
        val outDir = previewOutputDir.get().asFile
        // Gate only what this run rendered: a filtered render leaves other previews alone (#3730).
        // Filters apply to raw previews before expansion, matching `RenderPreviewsTask.render`, so
        // permutation siblings are still checked.
        val validated =
          PreviewPermutations.expand(
            selectFilteredPreviews(
              manifestRaw.previews,
              nameFilters = nameFilterProvider.get(),
              idFilters = idFilterProvider.get(),
              idExcludes = idExcludeProvider.get(),
            ),
            permutationsProvider.get(),
          )
        // Non-parameterized siblings are excluded from the `<stem>_*` glob.
        val missing = missingPreviewOutputIds(manifest, outDir, isFastTier, validate = validated)
        if (missing.isNotEmpty()) {
          val sidecars = readErrorSidecarsFor(manifest, missing, outDir)
          val message =
            formatMissingPreviewsMessage(manifest.copy(previews = validated), missing, sidecars)
          // `composePreview.missingRenders`: `fail` (default) catches classpath misconfiguration;
          // `warn` / `ignore` let multi-module CI continue. The marker is written either way.
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

    // Opt-in (`renderBeforeUnitTests`): AGP unit-test tasks run after `composePreviewRenderAll` so
    // pixel tests see complete renders. Matched by name, not `withType<Test>()`, which would
    // include `composePreviewRender` and create a cycle. `tasks.matching { }.configureEach { }` is
    // the IP-safe lazy form.
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
   * Prefix marking a diagnosis that only points at the first native failure. Kept in sync with
   * `NativeLoadDiagnosis.kt`; drift only degrades which line leads.
   */
  internal const val CASCADE_DIAGNOSIS_PREFIX = "Cascade of the first native-load failure"

  /** The two ways a JVM reports a class that is not on the classpath. */
  private val CLASS_LOADING_EXCEPTIONS = setOf("ClassNotFoundException", "NoClassDefFoundError")

  /** An AGP resource class name (`<package>.R` or `R$id` etc.), dotted or slashed. */
  private val AGP_R_CLASS = Regex("""\b\w+(?:[./]\w+)*[./]R(?:\$\w+)?\b""")

  /**
   * The missing AGP resource table behind a class-loading failure, or null. Singled out because a
   * missing merged unit-test `R.jar` fails every preview at `PoolingContainer.<clinit>`, which
   * otherwise reads as thousands of independently broken previews (#5026).
   */
  internal fun missingAgpRClass(insight: RenderErrorInsight): String? {
    val classLoadingFailure =
      insight.exception.substringAfterLast('.') in CLASS_LOADING_EXCEPTIONS ||
        insight.chain.split(" \u2192 ").any { it in CLASS_LOADING_EXCEPTIONS }
    if (!classLoadingFailure) return null
    return AGP_R_CLASS.find(insight.message)?.value?.replace('/', '.')
  }

  /**
   * What to check when [missingAgpRClass] fired: the R.jar is a raw file dep without
   * `artifactType`, so only AGP's own test task classpath carries it (#136).
   */
  internal fun missingRClassHint(rClass: String): String =
    "`$rClass` is an AGP-generated resource table, not preview code, so this is a classpath " +
      "fault rather than a preview that is individually broken. On an Android library every " +
      "Compose preview fails the same way when the merged unit-test R.jar is missing, because " +
      "compose-ui reads androidx.customview.poolingcontainer.R.id.* from " +
      "PoolingContainer.<clinit> at class-init." +
      "\n  That jar is `build/intermediates/compile_and_runtime_r_class_jar/<variant>UnitTest/" +
      "process<Variant>UnitTestResources/R.jar`. It reaches the render only through AGP's own " +
      "`test<Variant>UnitTest` task classpath — it is a raw file dependency with no " +
      "`artifactType`, so the render's filtered view of `<variant>UnitTestRuntimeClasspath` " +
      "never sees it (issue #136)." +
      "\n  Check, in order: that `test<Variant>UnitTest` exists for the rendered variant (run " +
      "`composePreviewDoctor` to see which variant that is); that " +
      "`process<Variant>UnitTestResources` ran; and that the jar it wrote actually contains " +
      "$rClass."

  /**
   * The error-sidecar fields [formatMissingPreviewsMessage] uses; the schema lives with the
   * renderers. `ignoreUnknownKeys` tolerates new fields.
   */
  @kotlinx.serialization.Serializable
  internal data class ErrorSidecar(
    val exception: String = "",
    val message: String = "",
    val topAppFrame: TopAppFrame? = null,
    /**
     * The renderer's explanation when a native library failed to load; empty otherwise or from
     * older renderers.
     */
    val diagnosis: String = "",
    /**
     * Full stack trace, read for its `Caused by:` chain and a user-package frame, since [exception]
     * is often the reflective wrapper (#3741). Empty from older renderers.
     */
    val stackTrace: String = "",
  ) {
    @kotlinx.serialization.Serializable
    internal data class TopAppFrame(
      val file: String = "",
      val line: Int = 0,
      val function: String = "",
    )
  }

  /**
   * For each missing preview, read the `<png>.error.json` sidecar the renderer writes when a
   * preview throws (it leaves no PNG), so the report shows the real exception rather than "render
   * was skipped". Previews without any sidecar are absent from the map.
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

  /**
   * One sidecar's root cause, best source frame and compact chain. Also the grouping key, so a
   * shared failure reports once with a count.
   */
  internal data class RenderErrorInsight(
    val exception: String,
    val message: String,
    val frame: ErrorSidecar.TopAppFrame?,
    /** `InvocationTargetException → NoClassDefFoundError`, or empty when there is no chain. */
    val chain: String,
  )

  /**
   * Leads with the root cause rather than the reflective wrapper and points at a frame in the
   * preview's own package; falls back to the sidecar's own fields.
   */
  internal fun renderErrorInsight(
    sidecar: ErrorSidecar,
    previewClassName: String,
  ): RenderErrorInsight {
    val chain = RenderErrorTrace.causeChain(sidecar.stackTrace)
    val root = chain.lastOrNull()
    val names =
      (listOf(sidecar.exception) + chain.map { it.exception })
        .filter { it.isNotBlank() }
        .map { it.substringAfterLast('.') }
    return RenderErrorInsight(
      exception = root?.exception?.takeIf { it.isNotBlank() } ?: sidecar.exception,
      message = if (root != null) root.message else sidecar.message,
      frame =
        RenderErrorTrace.preferredAppFrame(sidecar.stackTrace, previewClassName)
          ?: sidecar.topAppFrame,
      chain = if (chain.isEmpty()) "" else names.joinToString(" → "),
    )
  }

  internal fun formatMissingPreviewsMessage(
    manifest: PreviewManifest,
    missingIds: List<String>,
    sidecars: Map<String, ErrorSidecar>,
  ): String {
    val total = manifest.previews.size
    val n = missingIds.size
    return if (sidecars.isEmpty()) {
      // No sidecars: the render really was skipped or NO-SOURCE; keep the original guidance.
      val sample = missingIds.take(3).joinToString(", ")
      val andMore = if (n > 3) " (+${n - 3} more)" else ""
      "composePreviewRenderAll: render produced no output file for $n of " +
        "$total preview(s): $sample$andMore. This means " +
        "`composePreviewRender` was skipped or silently did nothing — on Android " +
        "that usually means it reported NO-SOURCE because " +
        "RobolectricRenderTest.class wasn't discoverable on its " +
        "testClassesDirs. Run with --info to see the task outcome."
    } else {
      // The render ran but previews threw; show the actual exceptions.
      val withSidecar = missingIds.filter { it in sidecars }
      val withoutSidecar = missingIds.filterNot { it in sidecars }
      val sb = StringBuilder()
      sb
        .append("composePreviewRenderAll: render produced no output file for ")
        .append(n)
        .append(" of ")
        .append(total)
        .append(" preview(s).")
      // A native-library failure takes out the whole module, so it leads (#3690); prefer the real
      // first failure over cascade markers.
      val diagnoses = withSidecar.map { sidecars.getValue(it).diagnosis }.filter { it.isNotBlank() }
      val rootCause =
        diagnoses.firstOrNull { !it.startsWith(CASCADE_DIAGNOSIS_PREFIX) }
          ?: diagnoses.firstOrNull()
      rootCause?.let { sb.append("\n\n").append(it) }

      if (withSidecar.isNotEmpty()) {
        sb.append("\n\nPer-preview render errors (from .error.json sidecars):")
        // Grouped by exception + message + source frame, so one root cause reports once with a
        // count while distinct bugs keep their own line. Preview order is preserved.
        val classNames = manifest.previews.associate { it.id to it.className }
        val insights = withSidecar.associateWith { id ->
          renderErrorInsight(sidecars.getValue(id), classNames[id].orEmpty())
        }
        val groups = withSidecar.groupBy { insights.getValue(it) }
        var shown = 0
        for ((insight, ids) in groups.entries.take(5)) {
          shown += ids.size
          sb.append("\n  - ")
          if (ids.size == 1) sb.append(ids.first()).append(": ")
          sb.append(insight.exception.substringAfterLast('.'))
          if (insight.message.isNotBlank()) sb.append(": ").append(insight.message)
          insight.frame?.let { f ->
            if (f.file.isNotBlank()) {
              sb.append(" (at ").append(f.file)
              if (f.line > 0) sb.append(':').append(f.line)
              sb.append(')')
            }
          }
          if (insight.chain.isNotBlank()) sb.append(" — chain: ").append(insight.chain)
          if (ids.size > 1) {
            sb.append(" — ").append(ids.size).append(" previews, e.g. ")
            sb.append(ids.take(3).joinToString(", "))
          }
        }
        if (withSidecar.size > shown) {
          sb.append("\n  (+").append(withSidecar.size - shown).append(" more with sidecars)")
        }
        // Explain the missing-R-class mechanism once, for the first such failure.
        insights.values
          .firstNotNullOfOrNull { missingAgpRClass(it) }
          ?.let { sb.append("\n\n").append(missingRClassHint(it)) }
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

  /**
   * The previews a filtered render renders: name filter, then id filter, then id exclusions, as in
   * `RenderPreviewsTask.render` and `PreviewFilter.select`. Never throws on an empty result: the
   * render task already reported that.
   */
  internal fun selectFilteredPreviews(
    previews: List<PreviewInfo>,
    nameFilters: List<String>,
    idFilters: List<String>,
    idExcludes: List<String>,
  ): List<PreviewInfo> {
    var kept = previews
    if (nameFilters.any { it.isNotBlank() }) {
      kept = kept.filter { PreviewNameFilter.matches(nameFilters, it.functionName, it.className) }
    }
    if (idFilters.any { it.isNotBlank() }) {
      kept = kept.filter { PreviewNameFilter.matchesId(idFilters, it.id) }
    }
    if (idExcludes.any { it.isNotBlank() }) {
      kept = kept.filterNot { PreviewNameFilter.matchesId(idExcludes, it.id) }
    }
    return kept
  }

  /**
   * Ids in [validate] whose render produced no file. [validate] is narrowed only for filtered
   * renders (#3730); [manifest] stays complete because `siblingNames` needs previews outside the
   * filter.
   */
  internal fun missingPreviewOutputIds(
    manifest: PreviewManifest,
    outDir: java.io.File,
    isFastTier: Boolean,
    validate: List<PreviewInfo> = manifest.previews,
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

    return validate
      .filter { p ->
        val captureMissing =
          p.captures.any { c ->
            // Optional captures are best-effort, never required.
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
   * Deletes files in [rendersDir] not referenced by [manifest], keeping:
   * 1. Exact `renderOutput`s.
   * 2. `<stem>_*.<ext>` fan-out files of `@PreviewParameter` previews (the renderer cleans those).
   * 3. `<stem>.a11y.png` siblings of registered renders (not listed in the manifest).
   * 4. Files outside the plugin's output types.
   */
  /**
   * Prunes `data/catalog-tokens/<id>.catalog.json` sidecars whose sheet is gone, mirroring the
   * renderer's `CatalogTokenSidecar` naming.
   */
  internal fun cleanStaleCatalogTokens(
    catalogTokensDir: java.io.File,
    manifest: PreviewManifest,
    logger: org.gradle.api.logging.Logger,
  ) {
    if (!catalogTokensDir.isDirectory) return
    val expected =
      manifest.previews
        .filter { it.params.kind == PreviewKind.CATALOG }
        .map { sanitizeCatalogTokenId(it.id) + ".catalog.json" }
        .toSet()
    catalogTokensDir
      .listFiles { f -> f.isFile && f.name.endsWith(".catalog.json") }
      ?.forEach { f ->
        if (f.name in expected) return@forEach
        if (!f.delete()) {
          logger.warn("compose-preview: couldn't delete stale catalog-token sidecar $f")
        }
      }
  }

  // Mirror of `CatalogTokenSidecar.sanitize` so the expected filenames match the renderer's.
  private fun sanitizeCatalogTokenId(id: String): String =
    id.replace(Regex("""[/\\:*?"<>|\s]"""), "_")

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

    // Template prefixes of `@PreviewParameter` previews; matching files are preserved as fan-out
    // siblings.
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
      // `.apng` too: a stale recording is as misleading as a stale PNG.
      .filter {
        it.isFile && (it.extension == "png" || it.extension == "gif" || it.extension == "apng")
      }
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

  // Matched by suffix-strip rather than reading accessibility.json, so orphaned `.a11y.png`s are
  // still removed.
  internal fun isA11ySiblingOfExpected(rel: String, expectedRelPaths: Set<String>): Boolean {
    if (!rel.endsWith(".a11y.png")) return false
    val cleanSibling = rel.removeSuffix(".a11y.png") + ".png"
    return cleanSibling in expectedRelPaths
  }

  /**
   * `<stem>.raw.<png|gif>` next to a registered output: from `@FocusedPreview(overlay = true)`
   * (PNG) or `@GlimmerEnvironmentPreview` (GIF); combined, `<stem>.glimmer.raw.png` too. Matched by
   * suffix-strip like [isA11ySiblingOfExpected].
   */
  internal fun isRawSiblingOfExpected(rel: String, expectedRelPaths: Set<String>): Boolean {
    val (suffix, extension) =
      when {
        rel.endsWith(".glimmer.raw.png") -> ".glimmer.raw" to ".png"
        rel.endsWith(".raw.png") -> ".raw" to ".png"
        rel.endsWith(".raw.gif") -> ".raw" to ".gif"
        else -> return false
      }
    val cleanSibling = rel.removeSuffix("$suffix$extension") + extension
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

  /**
   * The authored designs under a `ui-builder/` directory, excluding build output. In a repo that
   * also has a `ui-builder` Gradle module, the whole directory would snapshot that module's
   * `build/` as undeclared inputs, which Gradle rejects. Templates are JSON, so restrict to JSON
   * and exclude `build` dirs (the glob is in code: it would end this KDoc). Roots stay the project
   * directories ([DiscoverPreviewsTask.uiBuilderTemplateRoots]).
   */
  private fun uiBuilderTemplateTree(dir: Directory) =
    dir.asFileTree.matching {
      include("**/*.json")
      exclude("**/build/**")
    }
}
