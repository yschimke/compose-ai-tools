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
 * Materialises a daemon-backed [ServeSessionState] straight from a packed preview bundle — no
 * Gradle build or repo clone. The engine behind a `--catalogs` system's `liveBundle`
 * ([ServeCatalogStore]): extract the bundle, resolve its Maven classpath via [CoordinateResolver],
 * locate the CLI's daemon sidecar jars, and write a `daemon-launch.json` file. Writing a real file
 * lets the session use the normal open/suspend/resume path ([ServeSessionRegistry] re-opens the
 * descriptor).
 *
 * `desktop` bundles spawn the CMP/Skiko daemon; `android` bundles spawn the Robolectric daemon
 * (needs the separately shipped `lib-daemon-android` sidecar via
 * `-Dcomposeai.cli.libDaemonAndroidDir=…` and an SDK `android.jar`). Anything missing or any other
 * backend returns null with a log, so the caller falls back to baked PNGs or a source build.
 */
public object ServeBundleDaemon {

  /**
   * Live-seat cost ([LiveSeatLimiter] permits) of an Android/Robolectric catalog daemon: a sandbox
   * fleet at ~1.5–2 GB RSS versus ~0.5–1 GB for desktop, so two permits instead of one.
   */
  public const val ANDROID_LIVE_SEAT_WEIGHT: Int = 2

  /**
   * Live-seat weight of an already-built daemon [descriptor], for source-built catalogs with no
   * bundle manifest. Android is detected by its `robolectric.*` sysprops; defaults to 1 when the
   * descriptor is missing or unreadable.
   */
  public fun liveSeatWeightForDescriptor(descriptor: File): Int {
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
   * Extract [bundleFile] into [destDir] and synthesise a [ServeSessionState], or null (logged via
   * [onLog]) on any failure. [offline] forces resolution to skip the network; otherwise
   * [CoordinateResolver]'s own offline defaults apply.
   */
  public fun materialize(
    bundleFile: File,
    destDir: File,
    system: String,
    offline: Boolean = false,
    /**
     * Extra Maven repository URLs beyond Central + Google
     * ([CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES]), from `--extra-maven-repos` /
     * `SERVE_EXTRA_MAVEN_REPOS`; without them such deps are skipped and the live daemon can fail at
     * bootstrap.
     */
    extraMavenRepos: List<String> = emptyList(),
    /**
     * Extra classpath dirs after the bundle's `classes/`: the rehydrated
     * [BundleReader.Manifest.externalResources] (e.g. fonts lifted out by `bundle externalize`).
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
    // A fully IR-backed bundle (schema v5+) may carry no `classes/app.jar`; a mixed one must.
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

    // IR replay: the Android daemon reads captured documents from `ir/` via the carried manifest,
    // the same setup as `compose-preview bundle daemon`.
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
    // Bundles record `skiko-awt` but not the host `skiko-awt-runtime-<host>` native it links
    // against; unpaired, renders die with UnsatisfiedLinkError. See [SkikoNativePairing].
    val skikoNativeRepair = SkikoNativePairing.missingHostRuntime(recordedCoords)
    if (skikoNativeRepair != null) {
      onLog("catalog $system: ${SkikoNativePairing.repairLog(skikoNativeRepair)}")
    }
    val mavenCoords = recordedCoords + listOfNotNull(skikoNativeRepair)
    // (v9) Repositories the bundle's own coordinates resolve from, consulted after the operator's
    // `--extra-maven-repos`. Recorded `sha256`s still verify the bytes.
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
    // Unresolved or hash-mismatched coordinates are dropped/used with a warning and the daemon
    // starts anyway; record the aggregate beside the descriptor so a later linkage trip can name
    // the cause ([BundleClasspathGaps]).
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved = resolutions.filter { it.file == null }.map { it.coordinate },
      total = mavenCoords.size,
      system = system,
      onLog = onLog,
      mismatched = resolutions.filter { it.mismatch }.map { it.coordinate },
      fileSystem = fileSystem,
    )
    // The resolver returns null instead of throwing; an unresolvable repair must not go unnoticed.
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
    // Resolved first: which Remote Compose artifacts the sidecar ships decides what may be
    // promoted.
    val backendLaunch =
      when (backend) {
        "android" -> androidBundleDaemonLaunch(system, onLog)
        else -> desktopBundleDaemonLaunch(system, onLog)
      } ?: return null
    // `androidx.compose.remote:*` must load as one coherent family. On an IR bundle carrying only
    // part of a group, the sidecar (whose replay code links against it) stays authoritative and the
    // bundle's partial group is demoted. Without IR the previews are consumer bytecode, so the
    // bundle's versions are kept and the split only reported. Per group, since base and Wear
    // version independently. See [RemoteComposePairing].
    val remoteComposeLine =
      RemoteComposePairing.Line(
        bundle = RemoteComposePairing.bundleMembers(resolvedDependencies.map { it.coordinate }),
        sidecar = RemoteComposePairing.sidecarMembers(backendLaunch.daemonClasspath),
      )
    val demotedRemoteComposeGroups =
      if (hasIr) RemoteComposePairing.skewedGroups(remoteComposeLine) else emptySet()
    val (parentOverlayDependencies, childDependencies) =
      resolvedDependencies.partition {
        overlaysDaemonSidecar(it.coordinate, demotedRemoteComposeGroups)
      }
    // Android app resources: extract the bundle's `android/` payload and synthesize Robolectric's
    // `test_config.properties` so `stringResource(R.string.…)` resolves. Empty for desktop or older
    // bundles.
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
    // Backstop for split-Skiko cases the repair can't close, read off the assembled classpath
    // order.
    SkikoNativePairing.classpathSkew(classpaths.daemonClasspath)?.let {
      onLog("catalog $system: $it")
    }
    // Same coherence check for Remote Compose, read from both sides (resolved `.aar`s carry no
    // version in their path). Recorded beside the descriptor ([RemoteComposePairing]).
    RemoteComposePairing.record(
      destDir = destDir,
      bundle = remoteComposeLine.bundle,
      sidecar = remoteComposeLine.sidecar,
      system = system,
      onLog = onLog,
      demoted = demotedRemoteComposeGroups.isNotEmpty(),
      fileSystem = fileSystem,
    )

    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
          modulePath = ":catalog",
          variant = backendLaunch.variant,
          enabled = true,
          // Both backends use the same `DaemonMain` over stdio; only classpath / JVM args /
          // sysprops differ.
          mainClass = DAEMON_MAIN_CLASS,
          classpath = classpaths.daemonClasspath,
          // Catalog daemons only: the playground keeps bytecode verification for a stranger's
          // snippet.
          jvmArgs =
            backendLaunch.jvmArgs +
              (if (backendLaunch.variant == "android")
                androidDaemonStartupJvmArgs(classpaths.daemonClasspath)
              else emptyList()),
          systemProperties =
            buildMap {
              put("composeai.daemon.userClassDirs", classpaths.userClassPath)
              put("composeai.daemon.previewsJsonPath", previewsJson.absolutePath)
              irDir?.let { put(IR_DIR_PROPERTY, it.absolutePath) }
              bundleManifestFile?.let { put(BUNDLE_MANIFEST_PATH_PROPERTY, it.absolutePath) }
              // Setting the output dir makes `DaemonMain.dataRoot` non-null, which is what
              // registers the file-based data products (incl. `compose/figma-svg`); otherwise
              // `.svg` renders fail `-32020 kind not advertised`. Literal key to avoid a
              // `:daemon:desktop` dependency.
              put("composeai.render.outputDir", File(destDir, "renders").absolutePath)
              // Live viewer only: a missing app resource renders a placeholder instead of failing.
              // The pack-time daemon leaves this off so misses fail loudly. Literal key to avoid a
              // `:daemon:android` dependency.
              put("composeai.render.placeholderMissingResources", "true")
              // Backend extras (Robolectric flags for Android; none for desktop).
              putAll(backendLaunch.extraSystemProperties)
            },
          workingDirectory = destDir.absolutePath,
          manifestPath = previewsJson.absolutePath,
        )
        .build()
    val descriptorFile = File(destDir, "daemon-launch.json")
    try {
      fileSystem.write(descriptorFile.path.toPath()) {
        writeUtf8(json.encodeToString(DaemonLaunchDescriptor.serializer(), descriptor))
      }
    } catch (e: Exception) {
      onLog("catalog $system: could not write daemon-launch.json (${e.message})")
      return null
    }

    // Extract the knob sidecars (`.overrides.json`, `.remotecompose.json`) so [readPreviews] can
    // advertise editable knobs. Best-effort.
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
      // App-declared `@ThemeCatalog` themes for the live theme selector; empty when none.
      declaredThemes = readDeclaredThemes(previewsJson, fileSystem),
      // Android daemons cost more of the live-seat budget ([LiveSeatLimiter]).
      liveSeatWeight = if (backend == "android") ANDROID_LIVE_SEAT_WEIGHT else 1,
    )
  }

  /**
   * Materialize a compiled playground snippet into a resumable live-session state — the
   * [PlaygroundRedeemService] counterpart of [materialize]. Writes `previews.json` and a
   * `daemon-launch.json` for the snippet's mode so the registry treats it like any catalog. Returns
   * null (logged) when the mode's daemon backend is unavailable.
   *
   * [sandbox] is the per-session containment: its jail argv and hard TTL ride the descriptor.
   * [PlaygroundSandbox.NONE] leaves the descriptor unchanged.
   */
  public fun materializePlaygroundSnippet(
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

    // Partition the catalog jars as the bundle path does: namespaces `UserClassLoaderHolder`
    // delegates to the parent (androidx.*, kotlinx-coroutines, kotlinx-io) must precede the sidecar
    // or older sidecar versions win (NoSuchMethodError). Everything else, including the snippet's
    // classes, stays in the child loader. Only paths are available here, so match by cache path
    // segment.
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
      DaemonLaunchDescriptor.Builder(
          schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
          modulePath = ":playground",
          variant = backendLaunch.variant,
          enabled = true,
          mainClass = DAEMON_MAIN_CLASS,
          classpath = classpaths.daemonClasspath,
          // Sandbox JVM caps last so they win over backend defaults.
          jvmArgs = backendLaunch.jvmArgs + sandbox.jvmArgs(workDir),
          systemProperties =
            buildMap {
              put("composeai.daemon.userClassDirs", classpaths.userClassPath)
              put("composeai.daemon.previewsJsonPath", previewsJson.absolutePath)
              put("composeai.render.outputDir", File(workDir, "renders").absolutePath)
              put("composeai.render.placeholderMissingResources", "true")
              putAll(
                if (android)
                  sandbox.robolectricSystemProperties(backendLaunch.extraSystemProperties)
                else backendLaunch.extraSystemProperties
              )
            },
          workingDirectory = workDir.absolutePath,
          manifestPath = previewsJson.absolutePath,
        )
        .also {
          it.jailCommand =
            sandbox.command(
              PlaygroundSandbox.Paths(
                workDir = workDir,
                // Everything the daemon reads, read-only; only workDir is writable.
                readOnly =
                  (classpaths.daemonClasspath.map { File(it) } +
                      snippet.classpath.map { File(it.toString()) } +
                      classesDir)
                    .distinct(),
                javaHome = File(System.getProperty("java.home")),
              )
            )
          it.hardTtlSeconds = sandbox.ttlSeconds.takeIf { sandbox.isActive }
        }
        .build()
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
   * The catalog's declared `@ThemeCatalog` themes from the carried `previews.json`; empty when
   * absent.
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
   * Read the extracted `previews.json` into [ServePreview]s, with each preview's knobs from its
   * sidecars in [previewsDir].
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

  /** Extract the per-preview knob sidecars from [zipBytes] into [previewsDir] (zip-slip safe). */
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
          // Strip `previews/` so files land directly under previewsDir, keyed by id.
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

  /** [id]'s `<id>.remotecompose.json` knobs; absent or unreadable means none. */
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

  /** Per-preview plain-Compose knob sidecar suffix; lockstep with `PreviewBundleFormat`. */
  private const val OVERRIDES_SUFFIX = ".overrides.json"

  /** Per-preview Remote Compose knob sidecar suffix; lockstep with `PreviewBundleFormat`. */
  private const val REMOTECOMPOSE_SUFFIX = ".remotecompose.json"

  /** IR replay properties consumed by BundleIrReplayStore in the daemon. */
  private const val IR_DIR_PROPERTY = "composeai.daemon.irDir"
  private const val BUNDLE_MANIFEST_PATH_PROPERTY = "composeai.daemon.bundleManifestPath"

  private val json = Json { encodeDefaults = true }

  /**
   * Read back a `daemon-launch.json` written by [materialize], so the theme cache fingerprints the
   * actual launch. Null when unreadable, which means "do not persist".
   */
  public fun readLaunchDescriptor(descriptorFile: File): DaemonLaunchDescriptor? = runCatching {
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
   * Split a bundle's runtime into the daemon parent and the disposable user child classpaths.
   *
   * [ee.schimke.composeai.daemon.UserClassLoaderHolder] delegates Compose, AndroidX, Kotlin and
   * kotlinx packages to the parent, so the bundle's versions of those must go first on the parent
   * `-cp`, ahead of the sidecar, or catalogs built against newer APIs hit `NoSuchMethodError`.
   * Ordinary app dependencies stay in the child. Never overlay kotlinx-serialization: the daemon's
   * JSON-RPC serializers must match their runtime.
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
    // The carried r-classes.jar must precede the sidecar's R.jar, or newer Compose UI resolves an
    // older `R$id` (NoSuchFieldError).
    val parentEntries =
      (parentOverlayJars.map { it.absolutePath } +
          androidResourceClasspath +
          daemonSidecarClasspath +
          // Parent-loaded IR replay connectors link the carried libraries directly, so expose them
          // on the parent after the sidecars (as `BundleDaemonCommand.composeDaemonClasspath`
          // does).
          (if (hasIr) (embeddedLibJars + childDependencyJars).map { it.absolutePath }
          else emptyList()))
        .distinct()
    // External-resource dirs right after the bundle's classes, so lifted fonts resolve at
    // `/fonts/…`.
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
   * [shouldPrecedeDaemonSidecar], except a partially carried Remote Compose group on an IR bundle
   * ([demotedRemoteComposeGroups]) is demoted so the sidecar supplies the whole group. Non-IR
   * bundles never demote. See [RemoteComposePairing].
   */
  internal fun overlaysDaemonSidecar(
    coordinate: BundleReader.ClasspathEntry.Maven,
    demotedRemoteComposeGroups: Set<String>,
  ): Boolean =
    shouldPrecedeDaemonSidecar(coordinate) &&
      !RemoteComposePairing.isDemoted(coordinate, demotedRemoteComposeGroups)

  /**
   * Dependencies whose packages [ee.schimke.composeai.daemon.UserClassLoaderHolder] deliberately
   * resolves from the parent and whose consumer ABI must therefore win over the baked sidecar.
   */
  public fun shouldPrecedeDaemonSidecar(coordinate: BundleReader.ClasspathEntry.Maven): Boolean =
    coordinate.group.startsWith("androidx.") ||
      // CMP artifacts are `org.jetbrains.compose.*` by group but ship `androidx.compose.*`
      // packages, which [UserClassLoaderHolder.mustDelegateToParent] force-delegates; left in the
      // child, the sidecar's Compose answers and mismatched versions fail with `NoSuchMethodError`.
      // Skiko moves with Compose because `org.jetbrains.skia.*` is delegated too, and its bindings
      // must match the native.
      coordinate.group.startsWith("org.jetbrains.compose") ||
      coordinate.group.startsWith("org.jetbrains.skiko") ||
      (coordinate.group == "org.jetbrains.kotlinx" &&
        (coordinate.artifact.startsWith("kotlinx-coroutines") ||
          coordinate.artifact.startsWith("kotlinx-io")))

  /**
   * [shouldPrecedeDaemonSidecar] for a resolved jar path, for the playground path which only has
   * files. Matches `/androidx`, `org.jetbrains.compose`/skiko, and `kotlinx-coroutines` /
   * `kotlinx-io` segments in either Maven-local or Gradle-cache layouts.
   */
  public fun jarPrecedesDaemonSidecar(jar: File): Boolean {
    val path = jar.path.replace('\\', '/')
    return path.contains("/androidx") ||
      JETBRAINS_COMPOSE_ARTIFACT_PATH.containsMatchIn(path) ||
      KOTLINX_SHARED_ARTIFACT_PATH.containsMatchIn(path)
  }

  /**
   * `org.jetbrains.compose.*` and `org.jetbrains.skiko` in either cache layout; promoted together
   * so Skia bindings and native stay one version.
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
   * The backend-specific half of a bundle daemon launch: parent classpath, JVM args and extra
   * `-D`s. The bundle's app classes ride `composeai.daemon.userClassDirs`, not [daemonClasspath].
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
      // `apple.awt.UIElement`: no Dock icon or focus steal on macOS; must be set before AWT inits.
      jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true"),
      extraSystemProperties = desktopFontSystemProperties(),
    )
  }

  /**
   * Font props for the desktop daemon: share the Google Fonts cache with the Android path and
   * Gradle plugin, and forward `composeai.svg.embedFonts` / `composeai.fonts.offline` when set.
   */
  private fun desktopFontSystemProperties(): Map<String, String> = buildMap {
    put("composeai.fonts.cacheDir", composeAiCacheDir("fonts").absolutePath)
    System.getProperty("composeai.fonts.offline")?.let { put("composeai.fonts.offline", it) }
    System.getProperty("composeai.svg.embedFonts")?.let { put("composeai.svg.embedFonts", it) }
    // Read by the daemon, so it only applies if forwarded.
    System.getProperty("composeai.svg.background")?.let { put("composeai.svg.background", it) }
  }

  /**
   * Android (Robolectric) launch: `lib-daemon-android` + `android.jar` plus [AndroidBundleLaunch]'s
   * `--add-opens` and `robolectric.*` sysprops. `android.jar` comes from
   * `ANDROID_HOME`/`ANDROID_SDK_ROOT`. Missing either → null with an actionable log.
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
   * Cold-start knobs for a serve-spawned Robolectric daemon. Serve shows baked PNGs while warming,
   * so default to background sandbox boot (`initialize` returns once one sandbox can render). An
   * explicit `-Dcomposeai.daemon.backgroundSandboxBoot` (or `warmRenderOnBoot`) on this JVM is
   * forwarded and wins.
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
   * JVM flags that speed up a serve-spawned Android daemon and the sandbox workers it spawns (which
   * inherit its `-XX` flags). Each has its own opt-out:
   *
   * - Per-classpath CDS archive (`composeai.serve.androidDaemonCds`, default on, JDK 19+):
   *   `-XX:+AutoCreateSharedArchive`, keyed by daemon classpath since overlays make it per catalog.
   *   First boot of a classpath is slower, later ones much faster.
   * - No bytecode verification (`composeai.serve.androidDaemonBytecodeVerification`, default off):
   *   catalog daemons already run their producers' code. The playground never gets this.
   * - Serial GC (`composeai.serve.androidDaemonSerialGc`, default on): saves ~20-25% RSS per JVM.
   *
   * Archive requirements:
   * - JVM logging goes to stderr with `cds` silenced, because stdout is the JSON-RPC channel.
   * - A torn archive (daemon force-killed mid-write) blocks re-creation, so [validateArchive]
   *   deletes
   *   it first.
   * - The directory is bounded by `composeai.serve.androidDaemonCdsMaxBytes` (default 2 GiB) via
   *   [pruneArchives], never evicting this launch's own archives.
   *
   * [javaFeatureVersion] and [cdsDir] are test seams.
   */
  internal fun androidDaemonStartupJvmArgs(
    daemonClasspath: List<String>,
    javaFeatureVersion: Int = Runtime.version().feature(),
    cdsDir: File = composeAiCacheDir("cds"),
    cdsEnabled: Boolean = System.getProperty(ANDROID_DAEMON_CDS_PROP)?.toBoolean() ?: true,
    verifyBytecode: Boolean = System.getProperty(ANDROID_DAEMON_VERIFY_PROP)?.toBoolean() ?: false,
    cdsMaxBytes: Long =
      System.getProperty(ANDROID_DAEMON_CDS_MAX_BYTES_PROP)?.toLongOrNull()
        ?: DEFAULT_ANDROID_DAEMON_CDS_MAX_BYTES,
    serialGc: Boolean = System.getProperty(ANDROID_DAEMON_SERIAL_GC_PROP)?.toBoolean() ?: true,
  ): List<String> = buildList {
    if (serialGc) add("-XX:+UseSerialGC")
    if (cdsEnabled && javaFeatureVersion >= 19) {
      val key = classpathArchiveKey(daemonClasspath)
      val archive = File(cdsDir, "android-daemon-$key.jsa")
      runCatching { cdsDir.mkdirs() }
      validateArchive(archive)
      pruneArchives(cdsDir, keepStem = "android-daemon-$key", maxBytes = cdsMaxBytes)
      add("-XX:+AutoCreateSharedArchive")
      add("-XX:SharedArchiveFile=${archive.absolutePath}")
      add("-Xlog:disable")
      add("-Xlog:all=warning:stderr")
      add("-Xlog:cds*=off:stderr")
      add("-XX:+DisplayVMOutputToStderr")
    }
    if (!verifyBytecode) {
      add("-XX:+UnlockDiagnosticVMOptions")
      add("-XX:-BytecodeVerificationRemote")
    }
  }

  /**
   * Unlink [archive] unless it starts with HotSpot's dynamic-archive magic (host byte order).
   * Missing is fine. Returns whether a file was removed.
   */
  internal fun validateArchive(archive: File): Boolean {
    if (!archive.isFile) return false
    val magic = runCatching {
      archive.inputStream().use { stream ->
        val header = stream.readNBytes(4)
        if (header.size < 4) null
        else java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.nativeOrder()).int
      }
    }
      .getOrNull()
    if (magic == CDS_DYNAMIC_ARCHIVE_MAGIC) return false
    return archive.delete()
  }

  /** HotSpot `CDS_DYNAMIC_ARCHIVE_MAGIC` (src/hotspot/share/cds/filemap.hpp). */
  private const val CDS_DYNAMIC_ARCHIVE_MAGIC: Int = 0xf00baba8.toInt()

  /**
   * Delete the least recently modified `.jsa` files under [cdsDir] until within [maxBytes], never
   * those starting with [keepStem]. Returns the deleted files. Best-effort.
   */
  internal fun pruneArchives(cdsDir: File, keepStem: String, maxBytes: Long): List<File> {
    val archives =
      cdsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsa") } ?: return emptyList()
    var total = archives.sumOf { it.length() }
    if (total <= maxBytes) return emptyList()
    val deleted = mutableListOf<File>()
    for (candidate in
      archives.filterNot { it.name.startsWith(keepStem) }.sortedBy { it.lastModified() }) {
      if (total <= maxBytes) break
      val size = candidate.length()
      if (candidate.delete()) {
        total -= size
        deleted += candidate
      }
    }
    return deleted
  }

  /** `-Dcomposeai.serve.androidDaemonSerialGc=false` keeps the JVM's default collector. */
  internal const val ANDROID_DAEMON_SERIAL_GC_PROP = "composeai.serve.androidDaemonSerialGc"

  /** `-Dcomposeai.serve.androidDaemonCdsMaxBytes=<bytes>` bounds the archive directory. */
  internal const val ANDROID_DAEMON_CDS_MAX_BYTES_PROP = "composeai.serve.androidDaemonCdsMaxBytes"

  private const val DEFAULT_ANDROID_DAEMON_CDS_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

  /**
   * Archive name for a daemon classpath: SHA-256 of its ordered entries (the JVM validates the
   * rest).
   */
  private fun classpathArchiveKey(daemonClasspath: List<String>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for (entry in daemonClasspath) {
      digest.update(entry.toByteArray(Charsets.UTF_8))
      digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
  }

  /** `-Dcomposeai.serve.androidDaemonCds=false` turns the per-classpath CDS archive off. */
  internal const val ANDROID_DAEMON_CDS_PROP = "composeai.serve.androidDaemonCds"

  /**
   * `-Dcomposeai.serve.androidDaemonBytecodeVerification=true` restores verification in catalog
   * daemons.
   */
  internal const val ANDROID_DAEMON_VERIFY_PROP =
    "composeai.serve.androidDaemonBytecodeVerification"

  /** The daemon entrypoint a bundle spawns (both backends). */
  private const val DAEMON_MAIN_CLASS = "ee.schimke.composeai.daemon.DaemonMain"

  /** Descriptor schema version — mirrors `SubprocessRenderSessions.openBundleDaemon`. */
  private const val DAEMON_LAUNCH_SCHEMA_VERSION = 2
}
