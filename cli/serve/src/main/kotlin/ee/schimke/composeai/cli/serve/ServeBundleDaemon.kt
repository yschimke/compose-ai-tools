package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.AndroidBundleResources
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.extractBundleClassesAndManifest
import ee.schimke.composeai.bundle.extractBundleIrArtifacts
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.composeai.previewdata.PreviewManifest
import java.io.File
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Materialises a daemon-backed [ServeSessionState] straight from a **packed preview bundle** — no
 * Gradle build, no worktree, no repo clone. This is the engine behind serving a `--catalogs`
 * system's `liveBundle` ([ServeCatalogStore]): extract the bundle's `classes/app.jar` +
 * `previews.json` (+ any embedded `libs/`), resolve its `maven` classpath entries via
 * [CoordinateResolver], locate the CLI install's `lib-daemon-desktop`/`lib-renderer` sidecar jars
 * (same lookup `bundle daemon` uses), and write a `daemon-launch.json` in the exact shape
 * `SubprocessRenderSessions.open` reads. Writing it as a **file** (rather than constructing the
 * descriptor purely in-memory, the way
 * [ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions.openBundleDaemon] does)
 * is what lets this session ride the existing `ServeSessionState → openHost → ServeRenderHost.open
 * → registry.register` path unmodified — [ServeSessionRegistry] resumes a suspended session by
 * re-opening the same descriptor path, so suspend/resume works for free.
 *
 * Backend-aware, mirroring `bundle daemon`'s two launches over the **same** `DaemonMain`
 * entrypoint: a `desktop` bundle spawns the CMP/Skiko daemon (`lib-daemon-desktop` +
 * `lib-renderer`), an `android` bundle spawns the Robolectric daemon (`lib-daemon-android` +
 * `android.jar` + the required `--add-opens` + `robolectric.*` sysprops [AndroidBundleLaunch]
 * supplies). Wiring the Android backend here is what gives an Android/Wear catalog (e.g. `wear-m3`)
 * a live daemon session — and hence per-variant renders + the daemon-produced `compose/figma-svg`
 * lane (`renderSvg` on [ServeCatalogLiveHost]) that a baked, per-slug `figma/<slug>.svg` can't
 * match. Any other backend makes [materialize] return `null` (logging why) so the caller falls back
 * to the catalog's baked PNGs or its Gradle `source` build.
 *
 * The `android` backend needs the ~150-200 MB `lib-daemon-android` sidecar (shipped separately as
 * `compose-preview-android-daemon-<version>.zip`, not in the CLI tarball) unpacked and reachable
 * via `-Dcomposeai.cli.libDaemonAndroidDir=…`, plus `android.jar` from a local SDK
 * (`ANDROID_HOME`/`ANDROID_SDK_ROOT`); on its first render the Robolectric runtime fetches the
 * `android-all-instrumented` jar (network + cold-start latency). Missing either → `null` + a clear
 * log, same fail-soft as a missing desktop sidecar.
 */
public object ServeBundleDaemon {

  /**
   * Live-seat cost ([LiveSeatLimiter] permits) of an **Android/Robolectric** catalog daemon. It
   * boots a sandbox fleet (each `wear-m3` daemon spins ~5 Robolectric sandboxes) and holds ~1.5–2
   * GB RSS, versus ~0.5–1 GB for a desktop CMP daemon — so it consumes two permits where desktop
   * takes one. Tuned for the reference 4 GB box's default budget; a bigger box's budget scales up
   * (see `deploy/image/entrypoint.sh`), letting more of these run at once.
   */
  const val ANDROID_LIVE_SEAT_WEIGHT: Int = 2

  /**
   * Live-seat weight ([LiveSeatLimiter] permits) of an already-built daemon [descriptor] file — for
   * the Gradle **source-build** catalog path ([ServeCommand.buildTrustedCatalogSource]), which has
   * no bundle `manifest.backend` to read. Detects the Android/Robolectric backend by the
   * `robolectric.*` JVM sysprops every Android daemon launch carries (see
   * [AndroidPreviewClasspath]) and a desktop CMP daemon never does, so a source-served Android
   * catalog is charged [ANDROID_LIVE_SEAT_WEIGHT] exactly like the bundle path — keeping the OOM
   * protection intact in from-source deployments. Defaults to `1` (desktop) when the descriptor is
   * missing or unreadable.
   */
  fun liveSeatWeightForDescriptor(descriptor: File): Int {
    val text = descriptor.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    val launch =
      text?.let {
        runCatching { overridesJson.decodeFromString(DaemonLaunchDescriptor.serializer(), it) }
          .getOrNull()
      } ?: return 1
    val android =
      launch.systemProperties.keys.any { it.startsWith("robolectric.") } ||
        launch.jvmArgs.any { it.contains("robolectric.", ignoreCase = true) }
    return if (android) ANDROID_LIVE_SEAT_WEIGHT else 1
  }

  /**
   * Extract [bundleFile] into [destDir] and synthesise a working [ServeSessionState] for it, or
   * `null` (logging a clear reason via [onLog]) on any failure — a bad/foreign bundle, an
   * unsupported backend, missing sidecar jars (desktop or android), or an empty preview manifest.
   * [offline] forces classpath resolution to skip the network (mirrors
   * `-Dcomposeai.bundle.offline`); default `false` still honours that sysprop /
   * `COMPOSE_PREVIEW_OFFLINE` via [CoordinateResolver]'s own default.
   */
  fun materialize(
    bundleFile: File,
    destDir: File,
    system: String,
    offline: Boolean = false,
    /**
     * Extra remote Maven repository base URLs the classpath resolver may fetch from, in addition to
     * Maven Central + Google Maven ([CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES]). A catalog
     * whose module pulls deps from a non-default repo (e.g. `https://jitpack.io`, an
     * Apollo/JetBrains snapshot repo) would otherwise have those coordinates skipped — leaving the
     * live daemon's classpath incomplete, so a class that references them fails at bootstrap and
     * the catalog silently falls back to baked PNGs. Empty by default (Central + Google only); the
     * serve host passes its `--extra-maven-repos` / `SERVE_EXTRA_MAVEN_REPOS` list here.
     */
    extraMavenRepos: List<String> = emptyList(),
    /**
     * Extra classpath directories prepended after the bundle's own `classes/` — the rehydrated
     * [BundleReader.Manifest.externalResources] pool (fonts lifted out of `classes/app.jar` by
     * `bundle externalize`, materialized at their original resource paths so
     * `getResourceAsStream("/fonts/…")` resolves). Empty for a self-contained bundle.
     */
    extraClasspathDirs: List<File> = emptyList(),
    fileSystem: FileSystem = SystemFileSystem,
    onLog: (String) -> Unit = { System.err.println("[serve bundle] $it") },
  ): ServeSessionState? {
    destDir.mkdirs()

    val manifest =
      try {
        BundleReader.readMetadata(bundleFile).manifest
      } catch (e: Exception) {
        onLog("catalog $system: could not read bundle metadata (${e.message})")
        return null
      }
    val backend = manifest.backend
    if (backend != "desktop" && backend != "android") {
      onLog(
        "catalog $system: bundle backend '$backend' is not 'desktop' or 'android' — no live daemon " +
          "for this backend"
      )
      return null
    }

    val zipBytes =
      try {
        BundleReader.extractZipBytes(bundleFile, fileSystem)
      } catch (e: Exception) {
        onLog("catalog $system: could not read bundle zip (${e.message})")
        return null
      }

    val classesDir = File(destDir, "classes").apply { mkdirs() }
    val libsDir = File(destDir, "libs").apply { mkdirs() }
    val previewsJson = File(destDir, "previews.json")
    // A fully IR-backed bundle (schema v5+) legitimately carries no classes/app.jar — its daemon
    // replays the carried documents extracted below. A mixed bundle with at least one class-backed
    // preview must still carry its jar.
    val irPreviewIds = manifest.intermediateRepresentations.mapTo(mutableSetOf()) { it.previewId }
    val requireAppJar = manifest.previewIds.any { it !in irPreviewIds }
    try {
      extractBundleClassesAndManifest(
        zipBytes,
        classesDir,
        previewsJson,
        bundleFile,
        requireAppJar,
        fileSystem,
      )
    } catch (e: Exception) {
      onLog("catalog $system: bundle extraction failed (${e.message})")
      return null
    }

    // v5+ IR replay: a Remote Compose preview has no consumer class to reflect. The Android
    // daemon instead reads the captured document from `ir/` and resolves its descriptor through
    // the carried bundle manifest. This is the same setup `compose-preview bundle daemon` uses;
    // without it the public catalog path filtered IR previews out and falsely reported a daemon
    // startup failure.
    val hasIr = manifest.intermediateRepresentations.isNotEmpty()
    val irDir = if (hasIr) File(destDir, "ir").apply { mkdirs() } else null
    val bundleManifestFile = if (hasIr) File(destDir, "bundle.json") else null
    if (hasIr) {
      try {
        extractBundleIrArtifacts(zipBytes, irDir!!, bundleManifestFile!!, bundleFile, fileSystem)
      } catch (e: Exception) {
        onLog("catalog $system: IR extraction failed (${e.message})")
        return null
      }
    }

    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir, fileSystem)
    val recordedCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    // A bundle records `skiko-awt` but not the `skiko-awt-runtime-<host>` its bindings link
    // against — the platform native reaches a Gradle-resolved classpath as a transitive artifact,
    // not as a coordinate. Promoted unpaired, those bindings link against the SERVER's older
    // libskiko and every render dies with UnsatisfiedLinkError. See [SkikoNativePairing].
    val skikoNativeRepair = SkikoNativePairing.missingHostRuntime(recordedCoords)
    if (skikoNativeRepair != null) {
      onLog("catalog $system: ${SkikoNativePairing.repairLog(skikoNativeRepair)}")
    }
    val mavenCoords = recordedCoords + listOfNotNull(skikoNativeRepair)
    // (v9) The bundle names the repositories its own coordinates resolve from — a JitPack fork, an
    // internal mirror, the androidx.dev snapshot build a Remote Compose catalog is built against.
    // Consulted after the operator's `--extra-maven-repos`, so a box that pins a mirror still wins,
    // and a pre-v9 bundle contributes nothing. The recorded `sha256` still decides whether the
    // bytes that come back are the ones the producer packed.
    val bundleRepositories = manifest.repositories.filter { it.isNotBlank() }
    if (bundleRepositories.isNotEmpty()) {
      onLog(
        "catalog $system: bundle declares ${bundleRepositories.size} extra Maven " +
          "repository(s) — ${bundleRepositories.joinToString()}"
      )
    }
    val resolutions =
      CoordinateResolver(
          warn = { onLog("catalog $system: $it") },
          networkEnabled = if (offline) false else CoordinateResolver.defaultNetworkEnabled(),
          remoteRepositories =
            CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES +
              extraMavenRepos.filter { it.isNotBlank() } +
              bundleRepositories,
        )
        .resolveAll(mavenCoords)
    val resolvedDependencies = resolutions.mapNotNull { resolution ->
      resolution.file?.let { file -> ResolvedBundleDependency(resolution.coordinate, file) }
    }
    // A coordinate the resolver couldn't find is dropped with a per-coordinate warning and the
    // daemon starts anyway — correct, because most misses are harmless (a dep nothing on the render
    // path touches). What was missing is the *aggregate*: 13 unresolved coordinates read as 13
    // unrelated warnings, and the consequence only surfaced later as an unattributable
    // `NoClassDefFoundError` that tripped the breaker terminally (issues #4259 / #4265). Record the
    // gap beside the launch descriptor so a linkage trip can name it — see [BundleClasspathGaps].
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved = resolutions.filter { it.file == null }.map { it.coordinate },
      total = mavenCoords.size,
      system = system,
      onLog = onLog,
      fileSystem = fileSystem,
    )
    // The resolver warns and returns null rather than throwing, so an unresolvable repair would
    // otherwise be indistinguishable from one that was never needed — and the daemon would launch
    // straight back into the split-Skiko classpath this repair exists to close.
    if (
      skikoNativeRepair != null && resolvedDependencies.none { it.coordinate == skikoNativeRepair }
    ) {
      onLog(
        "catalog $system: could not resolve ${skikoNativeRepair.artifact}:" +
          "${skikoNativeRepair.version} — the live lane will link Skiko ${skikoNativeRepair.version} " +
          "bindings against this server's own libskiko and is likely to fail every render with " +
          "UnsatisfiedLinkError. Republish the catalog against a Compose Multiplatform version " +
          "whose Skiko this server ships, or give the server network access to Maven Central."
      )
    }
    val (parentOverlayDependencies, childDependencies) =
      resolvedDependencies.partition { shouldPrecedeDaemonSidecar(it.coordinate) }
    // Android app-resource carriage: a classic `@Preview` that calls `stringResource(R.string.…)`
    // needs the app's own `0x7f` resource table at render time. Extract the bundle's carried
    // `android/` payload and synthesize the Robolectric `test_config.properties` onto the daemon
    // `-cp` — the same wiring `bundle daemon` uses — or Robolectric throws
    // `Resources$NotFoundException`. Empty for a desktop bundle, or an Android bundle packed before
    // this carriage existed (renders framework-resources-only, exactly as before).
    val androidResourceClasspath =
      if (backend == "android")
        AndroidBundleResources.daemonClasspath(
            zipBytes,
            destDir,
            manifest.androidResources?.applicationPackage,
          )
          .map { it.absolutePath }
          .also {
            if (it.isNotEmpty())
              onLog("catalog $system: android resource carriage → ${it.size} classpath entry(s)")
          }
      else emptyList()

    val backendLaunch =
      when (backend) {
        "android" -> androidBundleDaemonLaunch(system, onLog)
        else -> desktopBundleDaemonLaunch(system, onLog)
      } ?: return null
    val classpaths =
      bundleDaemonClasspaths(
        classesDir = classesDir,
        extraClasspathDirs = extraClasspathDirs,
        embeddedLibJars = libJars,
        parentOverlayJars = parentOverlayDependencies.map { it.file },
        childDependencyJars = childDependencies.map { it.file },
        daemonSidecarClasspath = backendLaunch.daemonClasspath,
        androidResourceClasspath = androidResourceClasspath,
        hasIr = hasIr,
      )
    if (parentOverlayDependencies.isNotEmpty()) {
      onLog(
        "catalog $system: ${parentOverlayDependencies.size} shared bundle dependency classpath " +
          "entry(s) precede the daemon sidecar; ${childDependencies.size} app dependency " +
          "entry(s) remain isolated"
      )
    }
    // Backstop for every split-Skiko cause the repair above does not close (an offline box, a host
    // with no published native, a bundle recording another platform's). Read off the assembled
    // classpath rather than the coordinates, because order is what decides which pair loads.
    SkikoNativePairing.classpathSkew(classpaths.daemonClasspath)?.let {
      onLog("catalog $system: $it")
    }

    val descriptor =
      DaemonLaunchDescriptor(
        schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
        modulePath = ":catalog",
        variant = backendLaunch.variant,
        enabled = true,
        // Both backends speak the same JSON-RPC over stdio via the same `DaemonMain`; only the
        // classpath / JVM args / sysprops differ (see [BackendDaemonLaunch]).
        mainClass = DAEMON_MAIN_CLASS,
        javaLauncher = null,
        classpath = classpaths.daemonClasspath,
        jvmArgs = backendLaunch.jvmArgs,
        systemProperties =
          buildMap {
            put("composeai.daemon.userClassDirs", classpaths.userClassPath)
            put("composeai.daemon.previewsJsonPath", previewsJson.absolutePath)
            irDir?.let { put(IR_DIR_PROPERTY, it.absolutePath) }
            bundleManifestFile?.let { put(BUNDLE_MANIFEST_PATH_PROPERTY, it.absolutePath) }
            // Point the daemon's render output at `<destDir>/renders`. This is what makes
            // `DaemonMain.dataRoot` non-null (`<destDir>/data`), which is the gate that *registers*
            // the file-based data products — including `compose/figma-svg` (+ `-long`). Without it
            // `dataRoot` is null, the figma-svg producer still writes its SVG (it has an
            // independent
            // fallback dir) but the product is never advertised, so an override-bearing `.svg`
            // render fails `-32020 kind not advertised` and the SVG lane 404s (ServeRenderHost's
            // `enableExtensions` gets it back in `unknown`). `RenderEngine.dataDir` resolves to the
            // SAME `<destDir>/data` (`outputDir.parent/data`), so the registry reads exactly where
            // the render wrote. Keep the key literal to avoid a `:daemon:desktop` compile dep.
            put("composeai.render.outputDir", File(destDir, "renders").absolutePath)
            // Opt in to the missing-resource placeholder fallback: this is the live/serve viewer,
            // so
            // an app-resource lookup absent from a stale or incompletely-packed bundle degrades to
            // an
            // obvious placeholder rather than throwing and showing a broken image. The pack-time
            // semantics daemon leaves this off so a miss fails loudly instead of baking a
            // placeholder
            // into a published catalog sticker. Key kept literal to avoid a `:daemon:android` dep.
            put("composeai.render.placeholderMissingResources", "true")
            // Backend extras: the Robolectric `robolectric.*` flags for `android`; none for
            // desktop.
            putAll(backendLaunch.extraSystemProperties)
          },
        workingDirectory = destDir.absolutePath,
        manifestPath = previewsJson.absolutePath,
      )
    val descriptorFile = File(destDir, "daemon-launch.json")
    try {
      fileSystem.write(descriptorFile.path.toPath()) {
        writeUtf8(json.encodeToString(DaemonLaunchDescriptor.serializer(), descriptor))
      }
    } catch (e: Exception) {
      onLog("catalog $system: could not write daemon-launch.json (${e.message})")
      return null
    }

    // The author-declared knob sidecars ride alongside the PNGs in the bundle — the plain-Compose
    // `previews/<id>.overrides.json` (`compose/overrides`) and the Remote Compose
    // `previews/<id>.remotecompose.json` (`compose/remotecompose`) channels. Extract both so
    // [readPreviews] can advertise each preview's editable knobs, which the viewer renders as live
    // knob controls (and that ServeCatalogLiveHost grafts onto the baked browse surface).
    // Best-effort: a bundle that carried none simply yields previews with no knobs.
    val previewsDir = File(destDir, "previews").apply { mkdirs() }
    extractKnobSidecars(zipBytes, previewsDir, fileSystem)

    val previews = readPreviews(previewsJson, previewsDir, fileSystem)
    if (previews.isEmpty()) {
      onLog("catalog $system: bundle previews.json carried no previews")
      return null
    }

    return ServeSessionState(
      descriptor = descriptorFile,
      workspaceRoot = destDir,
      workspaceName = destDir.name.ifBlank { system },
      previews = previews,
      label = system,
      // The catalog's app-declared @ThemeCatalog themes, read from the same carried previews.json —
      // so a published catalog's live lane offers the App theme selector (its daemon applies the
      // themeProvider override on demand). Empty when the app declares none.
      declaredThemes = readDeclaredThemes(previewsJson, fileSystem),
      // An Android/Robolectric daemon boots a sandbox fleet and is far heavier than a desktop CMP
      // one, so it costs more of the live-seat budget (see [LiveSeatLimiter]); a desktop bundle
      // keeps the default weight of 1.
      liveSeatWeight = if (backend == "android") ANDROID_LIVE_SEAT_WEIGHT else 1,
    )
  }

  /**
   * Materialize a compiled **playground snippet** into a resumable live-session state — the Stage-2
   * ([PlaygroundRedeemService]) counterpart of [materialize], but over a just-compiled snippet's
   * own classes instead of a fetched bundle. Writes a `previews.json` (every `@Preview` the snippet
   * declared, so the session can navigate between them) and a `daemon-launch.json` for the
   * snippet's mode (desktop CMP / Android Robolectric) into the snippet's work dir, so the registry
   * opens, resumes, seat-counts, and streams it through the exact same path a catalog uses — no new
   * live-session machinery. Returns null (logged) when the mode's daemon backend (sidecar /
   * `android.jar`) is unavailable, so redemption reports "unavailable" rather than standing up a
   * dead session.
   *
   * [sandbox] is the per-session containment (PLAYGROUND.md §6, issue #3016): its jail argv and
   * hard TTL ride the written descriptor, so the registry's ordinary descriptor→spawn path launches
   * the snippet's daemon inside the jail and shoots it at the deadline. [PlaygroundSandbox.NONE]
   * leaves the descriptor identical to the pre-sandbox one.
   */
  fun materializePlaygroundSnippet(
    snippet: PlaygroundTokenStore.PlaygroundSnippet,
    sandbox: PlaygroundSandbox = PlaygroundSandbox.NONE,
    fileSystem: FileSystem = SystemFileSystem,
    onLog: (String) -> Unit = { System.err.println("[playground live] $it") },
  ): ServeSessionState? {
    val label = "playground:${snippet.previewId.substringAfterLast('.').ifBlank { "snippet" }}"
    val android = snippet.mode == PlaygroundMode.ANDROID
    val backendLaunch =
      (if (android) androidBundleDaemonLaunch(label, onLog)
      else desktopBundleDaemonLaunch(label, onLog)) ?: return null

    val workDir = File(snippet.workDir.toString())
    val classesDir = File(snippet.classesDir.toString())
    val previewsJson = File(workDir, "previews.json")
    try {
      fileSystem.write(previewsJson.path.toPath()) {
        writeUtf8(PlaygroundPreviews.previewManifestJson(snippet))
      }
    } catch (e: Exception) {
      onLog("$label: could not write previews.json (${e.message})")
      return null
    }

    // Partition the resolved catalog jars exactly as the bundle path does: the namespaces
    // UserClassLoaderHolder delegates to the daemon parent (androidx.*, kotlinx-coroutines,
    // kotlinx-io) must *precede* the sidecar on the parent -cp, or the daemon loads its own
    // (possibly older) sidecar
    // versions and a snippet/catalog composable built against the catalog's newer AndroidX fails
    // with NoSuchMethodError/NoSuchFieldError. Everything else — including the snippet's own
    // classes
    // (classesDir) — stays isolated on the user (child) classloader. We only have file paths here
    // (not coordinates), so match the same groups by their Maven/Gradle cache path segment.
    // classesDir
    // lands in `child` (its temp path matches neither), and bundleDaemonClasspaths dedupes it
    // against
    // the explicit classesDir arg.
    val (parentOverlayJars, childJars) =
      snippet.classpath.map { File(it.toString()) }.partition { jarPrecedesDaemonSidecar(it) }
    val classpaths =
      bundleDaemonClasspaths(
        classesDir = classesDir,
        extraClasspathDirs = emptyList(),
        embeddedLibJars = emptyList(),
        parentOverlayJars = parentOverlayJars,
        childDependencyJars = childJars,
        daemonSidecarClasspath = backendLaunch.daemonClasspath,
        androidResourceClasspath = emptyList(),
        hasIr = false,
      )
    val descriptor =
      DaemonLaunchDescriptor(
        schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
        modulePath = ":playground",
        variant = backendLaunch.variant,
        enabled = true,
        mainClass = DAEMON_MAIN_CLASS,
        javaLauncher = null,
        classpath = classpaths.daemonClasspath,
        // The sandbox's JVM caps come last so they win over any backend default: a snippet's daemon
        // is bounded in heap and CPU even on a jail with no cgroup behind it.
        jvmArgs = backendLaunch.jvmArgs + sandbox.jvmArgs(workDir),
        systemProperties =
          buildMap {
            put("composeai.daemon.userClassDirs", classpaths.userClassPath)
            put("composeai.daemon.previewsJsonPath", previewsJson.absolutePath)
            put("composeai.render.outputDir", File(workDir, "renders").absolutePath)
            put("composeai.render.placeholderMissingResources", "true")
            putAll(
              if (android) sandbox.robolectricSystemProperties(backendLaunch.extraSystemProperties)
              else backendLaunch.extraSystemProperties
            )
          },
        workingDirectory = workDir.absolutePath,
        manifestPath = previewsJson.absolutePath,
        jailCommand =
          sandbox.command(
            PlaygroundSandbox.Paths(
              workDir = workDir,
              // Everything the daemon reads: its own sidecar jars, the catalog classpath, and the
              // snippet's compiled classes. Bound read-only; only workDir is writable.
              readOnly =
                (classpaths.daemonClasspath.map { File(it) } +
                    snippet.classpath.map { File(it.toString()) } +
                    classesDir)
                  .distinct(),
              javaHome = File(System.getProperty("java.home")),
            )
          ),
        hardTtlSeconds = sandbox.ttlSeconds.takeIf { sandbox.isActive },
      )
    val descriptorFile = File(workDir, "daemon-launch.json")
    try {
      fileSystem.write(descriptorFile.path.toPath()) {
        writeUtf8(json.encodeToString(DaemonLaunchDescriptor.serializer(), descriptor))
      }
    } catch (e: Exception) {
      onLog("$label: could not write daemon-launch.json (${e.message})")
      return null
    }

    val previews =
      readPreviews(previewsJson, File(workDir, "previews").apply { mkdirs() }, fileSystem)
    if (previews.isEmpty()) {
      onLog("$label: synthesized previews.json carried no previews")
      return null
    }
    return ServeSessionState(
      descriptor = descriptorFile,
      workspaceRoot = workDir,
      workspaceName = workDir.name.ifBlank { "playground" },
      previews = previews,
      label = label,
      liveSeatWeight = if (android) ANDROID_LIVE_SEAT_WEIGHT else 1,
    )
  }

  /**
   * Read the catalog's declared `@ThemeCatalog` themes from the carried `previews.json` (the
   * synthetic `THEME_CATALOG` entries discovery emits). Module-global, so the whole catalog shares
   * one theme set. Absent / unreadable previews.json → no themes.
   */
  private fun readDeclaredThemes(previewsJson: File, fileSystem: FileSystem): List<ServeTheme> {
    val text =
      try {
        fileSystem.read(previewsJson.path.toPath()) { readUtf8() }
      } catch (_: Exception) {
        return emptyList()
      }
    val manifest =
      runCatching { previewsManifestJson.decodeFromString(PreviewManifest.serializer(), text) }
        .getOrNull() ?: return emptyList()
    return declaredThemesFromPreviews(manifest.previews)
  }

  /**
   * Read the bundle's extracted `previews.json` into the [ServePreview] shape serve expects,
   * folding in each preview's author-declared knobs from its extracted
   * `previews/<id>.overrides.json` sidecar (in [previewsDir]) so the daemon-backed session
   * advertises what's editable.
   */
  public fun readPreviews(
    previewsJson: File,
    previewsDir: File,
    fileSystem: FileSystem,
  ): List<ServePreview> {
    val text =
      try {
        fileSystem.read(previewsJson.path.toPath()) { readUtf8() }
      } catch (_: Exception) {
        return emptyList()
      }
    val manifest =
      runCatching { previewsManifestJson.decodeFromString(PreviewManifest.serializer(), text) }
        .getOrNull() ?: return emptyList()
    return manifest.previews.map {
      val (focus, gestures) = detectedFeaturesOf(it)
      ServePreview(
        id = it.id,
        label = it.functionName.ifBlank { it.id },
        dataProductKinds = it.dataProducts.mapTo(LinkedHashSet()) { product -> product.kind },
        uiMode = it.params.uiMode,
        showBackground = it.params.showBackground,
        backgroundColor = it.params.backgroundColor,
        deviceFrame =
          ServeDeviceFrame.from(it.params.device, it.params.widthDp, it.params.heightDp),
        overrides = readOverrideSidecar(previewsDir, it.id, fileSystem),
        remoteComposeKnobs = readRemoteComposeSidecar(previewsDir, it.id, fileSystem),
        supportsFocus = focus,
        supportsGestures = gestures,
        fixedTheme = it.fixedTheme,
      )
    }
  }

  /**
   * Extract the per-preview knob sidecars (`previews/<id>.overrides.json` and
   * `previews/<id>.remotecompose.json`) from [zipBytes] into [previewsDir] (zip-slip safe). Mirrors
   * the PNG-side extraction in [ServeBundleStore]; other bundle entries are handled elsewhere
   * ([extractBundleClassesAndManifest]).
   */
  public fun extractKnobSidecars(zipBytes: ByteArray, previewsDir: File, fileSystem: FileSystem) {
    val root = previewsDir.canonicalFile.toPath()
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name.replace('\\', '/')
        if (
          !entry.isDirectory &&
            name.startsWith("previews/") &&
            (name.endsWith(OVERRIDES_SUFFIX) || name.endsWith(REMOTECOMPOSE_SUFFIX)) &&
            ".." !in name.split("/")
        ) {
          // Strip the leading `previews/` so the file lands directly under previewsDir (keyed by
          // id).
          val target = File(previewsDir, name.removePrefix("previews/"))
          if (target.canonicalFile.toPath().startsWith(root)) {
            target.parentFile?.mkdirs()
            val bytes = zin.readBytes()
            fileSystem.write(target.path.toPath()) { write(bytes) }
          }
        }
        zin.closeEntry()
      }
    }
  }

  /** Read [id]'s extracted `<id>.overrides.json` sidecar (the `compose/overrides` payload). */
  private fun readOverrideSidecar(
    previewsDir: File,
    id: String,
    fileSystem: FileSystem,
  ): List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> {
    val sidecar = File(previewsDir, "$id$OVERRIDES_SUFFIX").path.toPath()
    if (!fileSystem.exists(sidecar)) return emptyList()
    return try {
      val text = fileSystem.read(sidecar) { readUtf8() }
      overridesJson
        .decodeFromString(
          ee.schimke.composeai.data.overrides.PreviewOverridesPayload.serializer(),
          text,
        )
        .declarations
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Read [id]'s extracted `<id>.remotecompose.json` sidecar (the `compose/remotecompose`
   * declarations payload) into its declared knobs. Absent / unreadable ⇒ no knobs, so a bundle that
   * carried none (or a non-RC catalog) just advertises an empty list.
   */
  private fun readRemoteComposeSidecar(
    previewsDir: File,
    id: String,
    fileSystem: FileSystem,
  ): List<ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration> {
    val sidecar = File(previewsDir, "$id$REMOTECOMPOSE_SUFFIX").path.toPath()
    if (!fileSystem.exists(sidecar)) return emptyList()
    return try {
      val text = fileSystem.read(sidecar) { readUtf8() }
      overridesJson
        .decodeFromString(
          ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload.serializer(),
          text,
        )
        .declarations
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Suffix of the per-preview plain-Compose knob sidecar; lockstep with `PreviewBundleFormat`'s.
   */
  private const val OVERRIDES_SUFFIX = ".overrides.json"

  /**
   * Suffix of the per-preview Remote Compose knob sidecar; lockstep with `PreviewBundleFormat`'s.
   */
  private const val REMOTECOMPOSE_SUFFIX = ".remotecompose.json"

  /** IR replay properties consumed by BundleIrReplayStore in the daemon. */
  private const val IR_DIR_PROPERTY = "composeai.daemon.irDir"
  private const val BUNDLE_MANIFEST_PATH_PROPERTY = "composeai.daemon.bundleManifestPath"

  private val json = Json { encodeDefaults = true }

  /**
   * Read back a `daemon-launch.json` written by [materialize].
   *
   * Exists so the theme cache can fingerprint a generation from the launch the daemon will actually
   * perform — the classpath it loads and the variant it renders as — rather than from a parallel
   * description of it that someone would have to keep in step by hand.
   *
   * Null on anything unreadable, which the caller treats as "this generation has no durable
   * identity" and therefore as "do not persist".
   */
  fun readLaunchDescriptor(descriptorFile: File): DaemonLaunchDescriptor? = runCatching {
    launchDescriptorJson.decodeFromString(
      DaemonLaunchDescriptor.serializer(),
      descriptorFile.readText(),
    )
  }
    .getOrNull()

  private val launchDescriptorJson = Json { ignoreUnknownKeys = true }
  private val overridesJson = Json { ignoreUnknownKeys = true }
  private val previewsManifestJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Split a packed bundle's runtime into the daemon parent and disposable user child classpaths.
   *
   * Compose, AndroidX, Kotlin, and kotlinx packages deliberately delegate to the daemon parent
   * loader so renderer and preview code share one class identity. Consequently, leaving the
   * bundle's resolved Maven jars in `composeai.daemon.userClassDirs` cannot make those catalog
   * versions win: [ee.schimke.composeai.daemon.UserClassLoaderHolder] delegates them straight to
   * the server sidecar, producing `NoSuchMethodError` when the catalog was compiled against newer
   * Material, Lifecycle, or coroutines APIs.
   *
   * Put the bundle's shared AndroidX/Compose and coroutines dependencies first on the parent `-cp`,
   * ahead of the daemon sidecar. The JVM's left-to-right classpath order then selects the catalog's
   * framework versions while retaining one parent-loaded copy for both renderer and app code. Keep
   * ordinary app dependencies in the child loader. In particular, do not overlay
   * kotlinx-serialization: the daemon's generated JSON-RPC serializers and its runtime must stay
   * version-aligned.
   */
  public fun bundleDaemonClasspaths(
    classesDir: File,
    extraClasspathDirs: List<File>,
    embeddedLibJars: List<File>,
    parentOverlayJars: List<File>,
    childDependencyJars: List<File>,
    daemonSidecarClasspath: List<String>,
    androidResourceClasspath: List<String>,
    hasIr: Boolean,
  ): BundleDaemonClasspaths {
    // The carried r-classes.jar belongs to the catalog's AndroidX graph too. It must precede the
    // sidecar's generated R.jar or newer Compose UI bytecode can resolve an older R$id class and
    // fail with NoSuchFieldError.
    val parentEntries =
      (parentOverlayJars.map { it.absolutePath } +
          androidResourceClasspath +
          daemonSidecarClasspath +
          // Parent-loaded IR replay connectors link the carried player/runtime libraries
          // directly. Mirror BundleDaemonCommand.composeDaemonClasspath by making every carried
          // dependency visible to the parent after the authoritative daemon sidecars. Shared ABI
          // overlays above still precede the sidecar and retain their version priority.
          (if (hasIr) (embeddedLibJars + childDependencyJars).map { it.absolutePath }
          else emptyList()))
        .distinct()
    // The rehydrated external-resource dirs go right after the bundle's own classes so a lifted
    // font resolves at the same `/fonts/…` path it did when carried inline.
    val childEntries =
      (listOf(classesDir) +
          extraClasspathDirs.filter { it.isDirectory } +
          embeddedLibJars +
          childDependencyJars)
        .map { it.absolutePath }
        .distinct()
    return BundleDaemonClasspaths(
      daemonClasspath = parentEntries,
      userClassPath = childEntries.joinToString(File.pathSeparator),
    )
  }

  public data class BundleDaemonClasspaths(
    val daemonClasspath: List<String>,
    val userClassPath: String,
  )

  /**
   * Dependencies whose packages [ee.schimke.composeai.daemon.UserClassLoaderHolder] deliberately
   * resolves from the parent and whose consumer ABI must therefore win over the baked sidecar.
   */
  public fun shouldPrecedeDaemonSidecar(coordinate: BundleReader.ClasspathEntry.Maven): Boolean =
    coordinate.group.startsWith("androidx.") ||
      // Compose Multiplatform artifacts are `org.jetbrains.compose.*` by GROUP but ship
      // `androidx.compose.*` PACKAGES — the same overlap [ValidateComposePreviewClasspathTask]
      // warns about. [UserClassLoaderHolder.mustDelegateToParent] keys on the package, so those
      // classes are force-delegated to the parent; leaving the jars in the isolated child means
      // the child copy is never consulted and the sidecar's own Compose answers instead. A
      // consumer pinning a different version than the renderer ships then gets a hard
      // `NoSuchMethodError` mid-render — meshcore-mobile on material3 1.10.0-alpha05 against a
      // 1.11.x sidecar died on `AppBarKt.TopAppBar-gNPyAyM`. Group and package rule must agree.
      //
      // Skiko travels WITH Compose, and for the same reason: `org.jetbrains.skiko:skiko-awt`
      // carries `org.jetbrains.skia.*` (bindings) as well as `org.jetbrains.skiko.*`, and
      // `mustDelegateToParent` force-delegates `org.jetbrains.skia.`. Promoting Compose without it
      // would pair the consumer's newer bindings with the sidecar's older native library — the
      // `UnsatisfiedLinkError` on `skia.paragraph.TextStyleKt._nSetFontEdging` that
      // [DesktopRendererGraphAlignmentFunctionalTest] documents (issue #1844). The two must move
      // together or the graph is incoherent either way.
      coordinate.group.startsWith("org.jetbrains.compose") ||
      coordinate.group.startsWith("org.jetbrains.skiko") ||
      (coordinate.group == "org.jetbrains.kotlinx" &&
        (coordinate.artifact.startsWith("kotlinx-coroutines") ||
          coordinate.artifact.startsWith("kotlinx-io")))

  /**
   * The [shouldPrecedeDaemonSidecar] rule applied to a resolved **jar path** rather than a
   * coordinate — used by the playground live path ([materializePlaygroundSnippet]), which carries
   * only the resolved files (the coordinates were dropped during catalog resolution). Both the
   * Maven-local (`…/androidx/compose/…`) and Gradle-cache (`…/androidx.compose.material3/…`)
   * layouts put the dotted/slashed group right after a path separator, so a `/androidx` segment
   * identifies the AndroidX graph, `org.jetbrains.compose` the Compose Multiplatform graph (whose
   * artifacts ship `androidx.compose.*` packages — see [shouldPrecedeDaemonSidecar]), and
   * `kotlinx-coroutines` / `kotlinx-io` the kotlinx artifacts whose namespaces
   * `UserClassLoaderHolder` delegates to the daemon parent.
   */
  public fun jarPrecedesDaemonSidecar(jar: File): Boolean {
    val path = jar.path.replace('\\', '/')
    return path.contains("/androidx") ||
      JETBRAINS_COMPOSE_ARTIFACT_PATH.containsMatchIn(path) ||
      KOTLINX_SHARED_ARTIFACT_PATH.containsMatchIn(path)
  }

  /**
   * The `org.jetbrains.compose.*` and `org.jetbrains.skiko` graphs in either cache layout. Broader
   * than the old `components-resources` check it replaces — every Compose Multiplatform artifact
   * ships `androidx.compose.*` packages the child delegates to the parent, not just the resources
   * one, and Skiko ships the `org.jetbrains.skia.*` bindings that are delegated too. Both must be
   * promoted together so the bindings and the native library stay one coherent version.
   */
  private val JETBRAINS_COMPOSE_ARTIFACT_PATH =
    Regex("/(?:org\\.jetbrains\\.(?:compose|skiko)|org/jetbrains/(?:compose|skiko))[./]")

  /** Matches Gradle-cache and Maven-local group layouts without inspecting unrelated path parts. */
  private val KOTLINX_SHARED_ARTIFACT_PATH =
    Regex(
      "/(?:org\\.jetbrains\\.kotlinx|org/jetbrains/kotlinx)/" +
        "kotlinx-(?:coroutines|io)(?:-[^/]+)?(?:/|$)"
    )

  private data class ResolvedBundleDependency(
    val coordinate: BundleReader.ClasspathEntry.Maven,
    val file: File,
  )

  /**
   * The backend-specific half of a bundle daemon launch: the daemon (parent `-cp`) classpath, the
   * JVM args, and any extra `-D` system properties. Mirrors `BundleDaemonCommand.DaemonLaunch` but
   * flattened for the descriptor path (which applies `jvmArgs` + `systemProperties` + `classpath`
   * directly — see `SubprocessDaemonClientFactory.spawn`). The daemon's own classpath carries the
   * renderer; the bundle's app classes ride the `composeai.daemon.userClassDirs` sysprop, so they
   * are NOT in [daemonClasspath].
   */
  private data class BackendDaemonLaunch(
    val variant: String,
    val daemonClasspath: List<String>,
    val jvmArgs: List<String>,
    val extraSystemProperties: Map<String, String>,
  )

  /** Desktop (CMP/Skiko) launch: `lib-daemon-desktop` + `lib-renderer`, native-access opened. */
  private fun desktopBundleDaemonLaunch(
    system: String,
    onLog: (String) -> Unit,
  ): BackendDaemonLaunch? {
    val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
    if (daemonJars.isEmpty()) {
      onLog(
        "catalog $system: no daemon jars found (looked in " +
          "${bundleSidecarSearchDescription("lib-daemon-desktop")}) — is this a " +
          "`:cli:installDist` build?"
      )
      return null
    }
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (rendererJars.isEmpty()) {
      onLog(
        "catalog $system: no renderer jars found (looked in " +
          "${bundleSidecarSearchDescription("lib-renderer")})"
      )
      return null
    }
    return BackendDaemonLaunch(
      variant = "desktop",
      daemonClasspath = (daemonJars + rendererJars).map { it.absolutePath },
      // -Dapple.awt.UIElement=true runs the desktop daemon JVM as a macOS background agent
      // (no Dock icon / focus steal). Launch -D so it lands before AWT inits; macOS-only.
      jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true"),
      extraSystemProperties = desktopFontSystemProperties(),
    )
  }

  /**
   * Font-related props the desktop daemon needs, mirroring the Android launch's
   * [AndroidBundleLaunch.robolectricSystemProperties]. The `compose/figma-svg` export embeds fonts
   * by default, so the daemon fetches generic faces (e.g. Roboto) from Google Fonts; point it at
   * the SAME shared cache the Android path and Gradle plugin use so those downloads are cached, and
   * forward this process's `composeai.svg.embedFonts` / `composeai.fonts.offline` choices when set
   * so a `-Dcomposeai.svg.embedFonts=false` opt-out reaches the child daemon.
   */
  private fun desktopFontSystemProperties(): Map<String, String> = buildMap {
    put("composeai.fonts.cacheDir", composeAiCacheDir("fonts").absolutePath)
    System.getProperty("composeai.fonts.offline")?.let { put("composeai.fonts.offline", it) }
    System.getProperty("composeai.svg.embedFonts")?.let { put("composeai.svg.embedFonts", it) }
    // The figma-svg background opt-in is read in the daemon, not here, so a
    // `-Dcomposeai.svg.background=true` on this process only takes effect if it is forwarded.
    System.getProperty("composeai.svg.background")?.let { put("composeai.svg.background", it) }
  }

  /**
   * Android (Robolectric) launch: `lib-daemon-android` + `android.jar`, plus the required
   * `--add-opens` args and `robolectric.*` mode sysprops [AndroidBundleLaunch] supplies (the same
   * ones `bundle daemon`'s `androidDaemonLaunch` passes). `resolveAndroidJar(null)` falls back to
   * `ANDROID_HOME`/`ANDROID_SDK_ROOT` since a module-less serve has no `local.properties`. Missing
   * the sidecar or android.jar → `null` + an actionable log (caller falls back to baked PNGs).
   */
  private fun androidBundleDaemonLaunch(
    system: String,
    onLog: (String) -> Unit,
  ): BackendDaemonLaunch? {
    val daemonJars = locateBundleSidecarJars("lib-daemon-android")
    if (daemonJars.isEmpty()) {
      onLog(
        "catalog $system: backend=android needs the Android daemon sidecar (`lib-daemon-android/`)," +
          " which ships separately as `compose-preview-android-daemon-<version>.zip` (too large for" +
          " the CLI tarball). Unpack it and set" +
          " `-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android`. Looked in " +
          "${bundleSidecarSearchDescription("lib-daemon-android")}."
      )
      return null
    }
    val androidJar =
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = null)
        ?: run {
          onLog(
            "catalog $system: backend=android needs android.jar — set ANDROID_HOME / " +
              "ANDROID_SDK_ROOT."
          )
          return null
        }
    val launch = AndroidBundleLaunch()
    return BackendDaemonLaunch(
      variant = "android",
      daemonClasspath = (daemonJars + listOf(androidJar)).map { it.absolutePath },
      jvmArgs = launch.jvmArgs(),
      extraSystemProperties =
        launch.robolectricSystemProperties() + androidColdStartSystemProperties(),
    )
  }

  /**
   * Cold-start knobs for a serve-spawned Android/Robolectric daemon. Serve fronts the daemon with
   * baked PNGs while it warms ([ServeCatalogLiveHost]'s warm-in-background lane), so nothing here
   * needs the strict all-sandboxes-ready `initialize` contract the Gradle-plugin/VS Code launch
   * keeps — opt into `RobolectricHost`'s background pool boot by default: `initialize` returns once
   * ONE sandbox can render (~12 s warm-cache instead of N×), the rest of the pool boots off the
   * request path, and each background slot gets a boot-time warm render. An explicit
   * `-Dcomposeai.daemon.backgroundSandboxBoot=…` on the serve JVM (e.g. via `JAVA_TOOL_OPTIONS`)
   * wins, so operators can opt a deployment out; `composeai.daemon.warmRenderOnBoot` is forwarded
   * when set for the same reason. Command-line `-D`s land after `JAVA_TOOL_OPTIONS` on the child
   * JVM, so the value emitted here is authoritative for the daemon.
   */
  private fun androidColdStartSystemProperties(): Map<String, String> = buildMap {
    put(
      "composeai.daemon.backgroundSandboxBoot",
      System.getProperty("composeai.daemon.backgroundSandboxBoot") ?: "true",
    )
    System.getProperty("composeai.daemon.warmRenderOnBoot")?.let {
      put("composeai.daemon.warmRenderOnBoot", it)
    }
  }

  /**
   * `ee.schimke.composeai.daemon.DaemonMain` — the daemon entrypoint a bundle spawns (both
   * backends).
   */
  private const val DAEMON_MAIN_CLASS = "ee.schimke.composeai.daemon.DaemonMain"

  /** Descriptor schema version — mirrors `SubprocessRenderSessions.openBundleDaemon`. */
  private const val DAEMON_LAUNCH_SCHEMA_VERSION = 2
}
