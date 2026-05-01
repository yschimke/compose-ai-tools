package ee.schimke.composeai.plugin

import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
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
   * `discoverPreviews` and `composePreviewCompile`.
   */
  private val DESKTOP_COMPILE_TASK_CANDIDATES: List<String> =
    listOf("compileKotlinJvm", "compileKotlinDesktop", "compileAndroidMain", "compileKotlin")

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

    // Same single-variant story for the runtime classpath: KMP-Android
    // exposes `androidRuntimeClasspath` (no `debug` / `release` prefix —
    // confirmed against AGP 9's KMP migration guide). Listed BEFORE
    // `runtimeClasspath` so a project applying both kotlin("multiplatform")
    // *and* the KMP-Android plugin resolves AAR deps via the Android
    // resolvable, where AGP's artifact transforms hand back the extracted
    // `classes.jar` (dependencyJars's `android-classes` artifact view).
    val dependencyConfigName =
      listOf(
          "jvmRuntimeClasspath",
          "desktopRuntimeClasspath",
          "androidRuntimeClasspath",
          "runtimeClasspath",
        )
        .firstOrNull { project.configurations.findByName(it) != null } ?: "runtimeClasspath"

    val discoverTask =
      registerDiscoverTask(
        project,
        sourceClassDirs,
        dependencyConfigName,
        previewOutputDir,
        extension,
      ) {
        onlyIf { extension.enabled.get() }
        // `compileAndroidMain` is the lifecycle task for the KMP-Android
        // target's `main` compilation (which depends on the underlying
        // `compileAndroidMainKotlin`-shaped Kotlin compile under the hood).
        // Listed alongside the JVM/Desktop siblings so we wire up exactly
        // one — whichever the consumer's applied plugins actually register.
        for (name in DESKTOP_COMPILE_TASK_CANDIDATES) {
          if (project.tasks.findByName(name) != null) {
            dependsOn(name)
            break
          }
        }
      }
    registerCompileOnlyTask(project, extension, DESKTOP_COMPILE_TASK_CANDIDATES)

    val rendererConfigName = "composePreviewRenderer"
    val rendererConfig = project.configurations.maybeCreate(rendererConfigName)
    rendererConfig.isCanBeResolved = true
    rendererConfig.isCanBeConsumed = false

    val hasDesktopRenderer =
      try {
        project.dependencies.add(
          rendererConfigName,
          project.dependencies.project(mapOf("path" to ":renderer-desktop")),
        )
        true
      } catch (e: org.gradle.api.UnknownProjectException) {
        project.logger.debug("compose-ai-tools: :renderer-desktop project not found, skipping", e)
        false
      }

    if (hasDesktopRenderer) {
      val renderTask =
        project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
          onlyIf { extension.enabled.get() }
          previewsJson.set(previewOutputDir.map { it.file("previews.json") })
          outputDir.set(previewOutputDir.map { it.dir("renders") })
          renderBackend.set("desktop")
          useComposeRenderer.set(true)
          tier.set(tierProperty(project))
          renderClasspath.from(sourceClassDirs)
          project.configurations.findByName(dependencyConfigName)?.let { renderClasspath.from(it) }
          renderClasspath.from(rendererConfig)
          group = "compose preview"
          description = "Render all previews to PNG"
          dependsOn(discoverTask)
        }
      registerRenderAllPreviews(
        project,
        extension,
        renderTask,
        previewOutputDir,
        verifyAccessibilityTask = null,
      )
    } else {
      registerStubRenderTask(
        project,
        previewOutputDir,
        sourceClassDirs,
        dependencyConfigName,
        discoverTask,
        extension,
      )
    }

    registerDesktopDaemonStartTask(
      project,
      extension,
      previewOutputDir,
      sourceClassDirs,
      dependencyConfigName,
    )
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
    dependencyConfigName: String,
  ) {
    val daemonProjectDir = project.rootDir.resolve("daemon/desktop")
    val useLocalDaemon =
      daemonProjectDir.resolve("build.gradle.kts").exists() ||
        daemonProjectDir.resolve("build.gradle").exists()
    if (!useLocalDaemon) {
      project.logger.debug(
        "compose-ai-tools: :daemon:desktop is not yet published; experimental.daemon only " +
          "works with the in-repo source layout."
      )
      return
    }

    val daemonRendererConfig =
      project.configurations.maybeCreate("composePreviewDesktopDaemon").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
      }
    try {
      project.dependencies.add(
        daemonRendererConfig.name,
        project.dependencies.project(mapOf("path" to ":daemon:desktop")),
      )
    } catch (e: org.gradle.api.UnknownProjectException) {
      project.logger.debug("compose-ai-tools: :daemon:desktop project not found, skipping", e)
      return
    }

    // Mirror the Android registration's eager-resolved values (see
    // AndroidPreviewSupport.kt) so each MapProperty / ListProperty entry's Provider chain
    // captures only serialisable references — `org.gradle.configuration-cache.problems=fail`
    // refuses anything that captures `project` / `this` task / `extension`.
    val previewsJsonProvider = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
    val outputFileProvider = previewOutputDir.map { it.file("daemon-launch.json") }
    val daemonFontsCacheDir =
      project.layout.projectDirectory
        .dir(".compose-preview-history")
        .dir("fonts")
        .asFile
        .absolutePath
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
      daemonEnabled.set(extension.experimental.daemon.enabled)
      maxHeapMb.set(extension.experimental.daemon.maxHeapMb)
      maxRendersPerSandbox.set(extension.experimental.daemon.maxRendersPerSandbox)
      warmSpare.set(extension.experimental.daemon.warmSpare)
      // `:daemon:desktop`'s `DaemonMain` and `:daemon:android`'s `DaemonMain` share the FQN
      // intentionally (see the kdoc on `daemon/desktop/.../DaemonMain.kt`). The desktop classes
      // jar is FIRST on the classpath below, so this loads the Compose-Multiplatform path.
      mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
      // Daemon module's classes FIRST so [mainClass] resolves before anything in the
      // consumer's transitive graph shadows it. `:daemon:desktop` is a Kotlin-JVM module, so
      // the default `org.gradle.usage=java-runtime` / `artifactType=jar` resolves directly to
      // the produced JAR — no AGP-style attribute filter needed.
      classpath.from(daemonRendererConfig)
      // User's compiled classes — keeps the Kotlin classloader's class-data-sharing intact for
      // the parent classloader before `UserClassLoaderHolder` constructs its child URL loader.
      classpath.from(sourceClassDirs)
      // User's runtime classpath (Compose Multiplatform deps + transitive Kotlin libraries).
      project.configurations.findByName(dependencyConfigName)?.let { classpath.from(it) }

      // Desktop daemons don't run inside Robolectric, so the AGP-side `--add-opens` flags don't
      // apply here. `-Xmx` is the only essential JVM arg; B-desktop follow-ups can add Skia /
      // ImageComposeScene-specific opens if profiling shows a need.
      jvmArgs.add(extension.experimental.daemon.maxHeapMb.map { "-Xmx${it}m" })

      // Desktop sysprops are a strict subset of the Android side — no Robolectric / Roborazzi
      // keys. Per-key `put(...)` so each Provider chain captures only serialisable references
      // (see Bug 1 fix in `AndroidPreviewSupport.kt` for the rationale).
      systemProperties.put("composeai.daemon.protocolVersion", "1")
      systemProperties.put("composeai.daemon.idleTimeoutMs", "5000")
      systemProperties.put(
        "composeai.daemon.maxHeapMb",
        extension.experimental.daemon.maxHeapMb.map { it.toString() },
      )
      systemProperties.put(
        "composeai.daemon.maxRendersPerSandbox",
        extension.experimental.daemon.maxRendersPerSandbox.map { it.toString() },
      )
      systemProperties.put(
        "composeai.daemon.warmSpare",
        extension.experimental.daemon.warmSpare.map { it.toString() },
      )
      systemProperties.put("composeai.daemon.modulePath", project.path)
      systemProperties.put("composeai.fonts.cacheDir", daemonFontsCacheDir)
      systemProperties.put("composeai.fonts.offline", daemonFontsOffline)
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
      outputFile.set(outputFileProvider)
      group = "compose preview"
      description =
        "Emit build/compose-previews/daemon-launch.json so VS Code can spawn the desktop preview daemon JVM"
    }
  }

  /**
   * Tier-1 cheap-signal file set for the desktop daemon's [ClasspathFingerprint][
   * ee.schimke.composeai.daemon.ClasspathFingerprint]. Same set as [AndroidPreviewSupport]'s
   * private `collectCheapSignalFiles` — duplicated rather than extracted into a shared helper to
   * keep this PR scoped to the desktop registration; both call sites can be unified once both
   * branches stabilise.
   */
  private fun collectDesktopCheapSignalFiles(project: Project): List<java.io.File> {
    val out = LinkedHashSet<java.io.File>()
    val rootProject = project.rootProject
    listOf("gradle/libs.versions.toml").forEach { out += rootProject.file(it) }
    listOf("settings.gradle.kts", "settings.gradle", "gradle.properties", "local.properties")
      .forEach { out += rootProject.file(it) }
    rootProject.allprojects.forEach { sub ->
      out += sub.file("build.gradle.kts")
      out += sub.file("build.gradle")
    }
    return out.filter { it.isFile }
  }

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

  fun registerDiscoverTask(
    project: Project,
    sourceClassDirs: FileCollection,
    dependencyConfigName: String,
    previewOutputDir: Provider<Directory>,
    extension: PreviewExtension,
    configureDeps: DiscoverPreviewsTask.() -> Unit,
  ): TaskProvider<DiscoverPreviewsTask> {
    val artifactType = Attribute.of("artifactType", String::class.java)

    return project.tasks.register("discoverPreviews", DiscoverPreviewsTask::class.java) {
      classDirs.from(sourceClassDirs)
      project.configurations.findByName(dependencyConfigName)?.let { config ->
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
      // Gradle property override: `-PcomposePreview.accessibilityChecks.enabled=true`
      // wins over the extension. Lets VSCode / CLI flip the feature on
      // for a run without editing build.gradle.kts. Isolated-Projects-
      // safe because `providers.gradleProperty` is.
      accessibilityChecksEnabled.set(
        project.providers
          .gradleProperty("composePreview.accessibilityChecks.enabled")
          .map { it.toBooleanStrictOrNull() ?: false }
          .orElse(extension.accessibilityChecks.enabled)
      )
      // `-PcomposePreview.failOnEmpty=true` wins over the extension, so
      // CI profiles and one-off triage runs can flip the gate without
      // touching build.gradle(.kts). Same pattern as
      // `accessibilityChecks.enabled` above.
      failOnEmpty.set(
        project.providers
          .gradleProperty("composePreview.failOnEmpty")
          .map { it.toBooleanStrictOrNull() ?: false }
          .orElse(extension.failOnEmpty)
      )
      outputFile.set(previewOutputDir.map { it.file("previews.json") })
      group = "compose preview"
      description = "Discover @Preview annotations in compiled classes"
      configureDeps()
    }
  }

  /**
   * Registers a `composePreviewCompile` lifecycle task whose only job is to run the same Kotlin
   * compile task `discoverPreviews` depends on, without the discovery action itself. Wired so the
   * VS Code extension can keep `.class` files fresh on save without re-walking the dependency-JAR
   * classpath through ClassGraph — the daemon owns the metadata reconcile via its
   * `IncrementalDiscovery` cascade and `discoveryUpdated` notification, so the editor save loop no
   * longer needs `:discoverPreviews` on every keystroke.
   *
   * Caller passes the set of candidate compile task names; we wire the first that exists at
   * configuration time. When none are found (consumer hasn't applied a Kotlin plugin) the task is
   * still registered but is a no-op — same `onlyIf(false)` shape as the disabled-by-extension
   * gating below.
   */
  fun registerCompileOnlyTask(
    project: Project,
    extension: PreviewExtension,
    compileTaskNames: List<String>,
  ): TaskProvider<DefaultTask> {
    val resolvedCompileTask = compileTaskNames.firstOrNull { project.tasks.findByName(it) != null }
    return project.tasks.register("composePreviewCompile", DefaultTask::class.java) {
      group = "compose preview"
      description =
        "Compile sources without running discoverPreviews — used by the VS Code daemon save path."
      onlyIf { extension.enabled.get() }
      if (resolvedCompileTask != null) {
        dependsOn(resolvedCompileTask)
      }
    }
  }

  fun registerStubRenderTask(
    project: Project,
    previewOutputDir: Provider<Directory>,
    sourceClassDirs: FileCollection,
    dependencyConfigName: String,
    discoverTask: TaskProvider<DiscoverPreviewsTask>,
    extension: PreviewExtension,
  ) {
    val renderTask =
      project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
        onlyIf { extension.enabled.get() }
        previewsJson.set(previewOutputDir.map { it.file("previews.json") })
        outputDir.set(previewOutputDir.map { it.dir("renders") })
        renderBackend.set("stub")
        useComposeRenderer.set(false)
        tier.set(tierProperty(project))
        renderClasspath.from(sourceClassDirs)
        project.configurations.findByName(dependencyConfigName)?.let { renderClasspath.from(it) }
        group = "compose preview"
        description = "Render all previews to PNG (stub)"
        dependsOn(discoverTask)
      }
    registerRenderAllPreviews(
      project,
      extension,
      renderTask,
      previewOutputDir,
      verifyAccessibilityTask = null,
    )
  }

  /** Registers `renderAllPreviews` as the user-facing entry point. */
  fun registerRenderAllPreviews(
    project: Project,
    extension: PreviewExtension,
    renderTask: TaskProvider<*>,
    previewOutputDir: Provider<Directory>,
    verifyAccessibilityTask: TaskProvider<*>?,
  ) {
    // Post-condition check: every entry in the manifest must have a PNG
    // on disk after the render dependency ran. We ship the renderer
    // (RobolectricRenderTest on Android, RenderPreviewsTask on desktop)
    // so we KNOW the task should run for a non-empty manifest — a missing
    // PNG is a wiring bug, never expected. The most common offender on
    // Android is `renderPreviews` reporting NO-SOURCE because the AAR's
    // classes.jar wasn't expanded via `zipTree` before being added to
    // `testClassesDirs`, which silently skips rendering; without this
    // check the failure surfaces only in downstream tools (CLI / VSCode).
    val manifestFile = previewOutputDir.map { it.file("previews.json") }
    val rendersDir = previewOutputDir.map { it.dir("renders") }
    // Captured at config time so the `doLast` body doesn't reach for
    // `project` at execution (config-cache safe). Resolves at execution
    // to "fast" or "full"; "fast" tells the post-condition to tolerate
    // heavy captures that legitimately weren't rendered this run.
    val tierProvider =
      project.providers
        .gradleProperty("composePreview.tier")
        .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
        .orElse("full")
    project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
      group = "compose preview"
      dependsOn(renderTask)
      // `verifyAccessibility` runs AFTER rendering so PNGs always exist
      // even when the check fails. `finalizedBy` (instead of `dependsOn`)
      // lets the build still produce artefacts for CLI/VSCode to
      // inspect when the a11y threshold trips the build.
      verifyAccessibilityTask?.let { finalizedBy(it) }
      doLast {
        val isFastTier = tierProvider.get() == "fast"
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
        val siblingNames =
          manifest.previews
            .filter { it.params.previewParameterProviderClassName == null }
            .flatMap { it.captures.map { c -> c.renderOutput } }
            .filter { it.isNotEmpty() }
            .map { java.io.File(outDir, it).name }
            .toSet()
        val missing =
          manifest.previews
            .filter { p ->
              p.captures.any { c ->
                // `tier=fast` legitimately skips heavy captures —
                // their PNG/GIF either still exists on disk from a
                // prior full run (and stays usable as the "stale"
                // image) or hasn't been produced yet. Either way,
                // it isn't a wiring bug worth failing the build
                // over, so exclude them from the must-exist check.
                if (isFastTier && isHeavyCost(c.cost)) return@any false
                val rel = c.renderOutput.ifEmpty { "renders/${p.id}.png" }
                // `@PreviewParameter` previews fan out at render
                // time: manifest carries a `<stem>.png` template,
                // but the actual files live at
                // `<stem>_<label>.png` (one per provider value,
                // or `_PARAM_<idx>` when the label can't be
                // derived). Check that at least ONE matching
                // fan-out file exists instead of demanding the
                // template itself.
                if (p.params.previewParameterProviderClassName != null) {
                  val file = outDir.resolve(rel)
                  val dir = file.parentFile ?: outDir
                  val prefix = file.nameWithoutExtension + "_"
                  val ext = ".${file.extension}"
                  !(dir.listFiles()?.any { f ->
                    f.name.startsWith(prefix) && f.name.endsWith(ext) && f.name !in siblingNames
                  } ?: false)
                } else {
                  !outDir.resolve(rel).exists()
                }
              }
            }
            .map { it.id }
        if (missing.isNotEmpty()) {
          val preview = missing.take(3).joinToString(", ")
          val andMore = if (missing.size > 3) " (+${missing.size - 3} more)" else ""
          throw GradleException(
            "renderAllPreviews: render produced no PNG for ${missing.size} of " +
              "${manifest.previews.size} preview(s): $preview$andMore. This means " +
              "`renderPreviews` was skipped or silently did nothing — on Android " +
              "that usually means it reported NO-SOURCE because " +
              "RobolectricRenderTest.class wasn't discoverable on its " +
              "testClassesDirs. Run with --info to see the task outcome."
          )
        }
      }
    }
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
