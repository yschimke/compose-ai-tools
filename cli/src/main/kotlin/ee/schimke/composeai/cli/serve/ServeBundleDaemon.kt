package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.AndroidBundleLaunch
import ee.schimke.composeai.cli.AndroidBundleResources
import ee.schimke.composeai.cli.BundleReader
import ee.schimke.composeai.cli.CoordinateResolver
import ee.schimke.composeai.cli.PreviewManifest
import ee.schimke.composeai.cli.bundleSidecarSearchDescription
import ee.schimke.composeai.cli.extractBundleClassesAndManifest
import ee.schimke.composeai.cli.locateBundleSidecarJars
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
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
 * `android.jar` + the JDK-17 `--add-opens` + `robolectric.*` sysprops [AndroidBundleLaunch]
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
internal object ServeBundleDaemon {

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
    // A fully IR-backed bundle (schema v5+) legitimately carries no classes/app.jar — mirrors
    // BundleDaemonCommand's gate. Serve's live path doesn't replay IR (that's the Android/tile
    // story), but a mixed bundle with at least one class-backed preview must still carry its jar.
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

    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir, fileSystem)
    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolvedJars =
      CoordinateResolver(
          warn = { onLog("catalog $system: $it") },
          networkEnabled = if (offline) false else CoordinateResolver.defaultNetworkEnabled(),
          remoteRepositories =
            CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES +
              extraMavenRepos.filter { it.isNotBlank() },
        )
        .resolveAll(mavenCoords)
        .mapNotNull { it.file }
    // The rehydrated external-resource dirs go right after the bundle's own classes so a lifted
    // font resolves at the same `/fonts/…` classpath path it did when carried inline.
    val userClassPath =
      (listOf(classesDir) + extraClasspathDirs.filter { it.isDirectory } + libJars + resolvedJars)
        .joinToString(File.pathSeparator) { it.absolutePath }

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
        classpath = backendLaunch.daemonClasspath + androidResourceClasspath,
        jvmArgs = backendLaunch.jvmArgs,
        systemProperties =
          buildMap {
            put("composeai.daemon.userClassDirs", userClassPath)
            put("composeai.daemon.previewsJsonPath", previewsJson.absolutePath)
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

    // The author-declared knob sidecars (`previews/<id>.overrides.json`) ride alongside the PNGs in
    // the bundle. Extract them so [readPreviews] can advertise each preview's editable knobs — the
    // `compose/overrides` payload the viewer renders as live knob controls (and that
    // ServeCatalogLiveHost grafts onto the baked browse surface). Best-effort: a bundle that
    // carried
    // none simply yields previews with no knobs.
    val previewsDir = File(destDir, "previews").apply { mkdirs() }
    extractOverrideSidecars(zipBytes, previewsDir, fileSystem)

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
  private fun readPreviews(
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
        overrides = readOverrideSidecar(previewsDir, it.id, fileSystem),
        supportsFocus = focus,
        supportsGestures = gestures,
      )
    }
  }

  /**
   * Extract only the `previews/<id>.overrides.json` sidecars from [zipBytes] into [previewsDir]
   * (zip-slip safe). Mirrors the PNG-side extraction in [ServeBundleStore]; other bundle entries
   * are handled elsewhere ([extractBundleClassesAndManifest]).
   */
  private fun extractOverrideSidecars(
    zipBytes: ByteArray,
    previewsDir: File,
    fileSystem: FileSystem,
  ) {
    val root = previewsDir.canonicalFile.toPath()
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name.replace('\\', '/')
        if (
          !entry.isDirectory &&
            name.startsWith("previews/") &&
            name.endsWith(OVERRIDES_SUFFIX) &&
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

  /** Suffix of the per-preview knob sidecar; lockstep with `PreviewBundleFormat`'s. */
  private const val OVERRIDES_SUFFIX = ".overrides.json"

  private val json = Json { encodeDefaults = true }
  private val overridesJson = Json { ignoreUnknownKeys = true }
  private val previewsManifestJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

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
  }

  /**
   * Android (Robolectric) launch: `lib-daemon-android` + `android.jar`, plus the JDK-17
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
