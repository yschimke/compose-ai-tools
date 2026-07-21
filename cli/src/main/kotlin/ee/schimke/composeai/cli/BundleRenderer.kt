package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source

/**
 * Re-renders a packed `.png` (PNG+ZIP polyglot bundle) outside of any Gradle project: extract the
 * appended zip, expand `classes/app.jar` into a temp classes dir, spawn `DesktopRendererMain` as a
 * subprocess per preview with the renderer classpath shipped alongside the CLI (`lib-renderer/`).
 *
 * # Why subprocess-per-preview
 *
 * The renderer brings the full Compose Multiplatform Desktop + Skiko runtime — too much to load
 * into the CLI's own classpath (see the `CheckCliDaemonLibraryBoundary` guard in
 * `cli/build.gradle.kts`). Isolating each render in a subprocess JVM also matches what
 * `RenderPreviewsTask` does today, so the call shape (args / JVM flags / output convention) stays
 * familiar. The cost — JVM cold-start per preview — is acceptable for the "open and look" flow; the
 * daemon path is reserved for editor-driven hot loops.
 *
 * # Classpath
 *
 * The subprocess JVM's classpath, in order: extracted `classes/app.jar` directory, any embedded
 * `libs/` jars, any `maven` coordinates resolved from local repositories ([CoordinateResolver]),
 * then every jar in `<APP_HOME>/lib-renderer/`. The consumer's deps sit ahead of the renderer's own
 * Compose Multiplatform graph, so a preview's own deps resolve while the bundled Compose still wins
 * on shared symbols (same layering the desktop viewer's URLClassLoader uses).
 *
 * Embedded-mode bundles (schema-v3 `resolution = "embedded"`) carry their reachable deps in
 * `libs/`. Coordinate-mode bundles (the default) carry only references; [CoordinateResolver]
 * re-attaches them from the machine's local Maven / Gradle caches and, on a miss, downloads from
 * Maven Central / Google Maven (Tier 3), hash-checking against the v4 `sha256` — a miss or mismatch
 * warns but never fails, since the renderer's bundled Compose covers the common surface and an
 * almost-compatible jar still renders.
 *
 * # Renderer / Java location
 *
 * The generated `bin/compose-preview` script exports `APP_HOME` pointing at the install root.
 * `lib-renderer/` lives next to `lib/` (the CLI's own classpath). The Java binary is the same one
 * running the CLI — `java.home/bin/java`. Both are overridable via system properties for tests
 * (`composeai.cli.appHome`, `composeai.cli.javaBinary`).
 */
class BundleRenderer(
  private val bundleFile: File,
  private val outputDir: File,
  private val verbose: Boolean = false,
  private val logSink: (String) -> Unit = { System.err.println(it) },
  private val fileSystem: FileSystem = SystemFileSystem,
  /**
   * A published bundle's externalized resource pool (`bundle/res/<sha>`, from `--res`). When the
   * bundle lifted its fonts out of `classes/app.jar` via `bundle externalize`, they're rehydrated
   * back into the expanded classes dir so the subprocess renderer resolves `/fonts/…`. Null for a
   * self-contained bundle (nothing to rehydrate).
   */
  private val resPoolDir: File? = null,
) {

  /** Outcome of one bundle render — surfaced for the CLI's exit-code logic and test assertions. */
  data class Result(
    val previewCount: Int,
    val succeeded: List<RenderedPreview>,
    val failed: List<FailedPreview>,
  ) {
    val allOk: Boolean
      get() = failed.isEmpty()
  }

  data class RenderedPreview(val id: String, val outputFile: File)

  data class FailedPreview(val id: String, val exitCode: Int, val tail: String)

  fun run(): Result {
    if (!bundleFile.isFile) {
      throw IllegalArgumentException("not a file: ${bundleFile.path}")
    }
    val workDir = createTempWorkDir()
    val classesDir = workDir.resolve("classes").apply { mkdirs() }
    val libsDir = workDir.resolve("libs").apply { mkdirs() }
    val zipBytes = BundleReader.extractZipBytes(bundleFile)
    val (bundleJsonBytes, previewsJsonBytes, hasAppJar) =
      expandAppJarAndReadManifests(zipBytes, classesDir)
    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir)

    val manifest = BUNDLE_JSON.decodeFromString(BundleReader.Manifest.serializer(), bundleJsonBytes)
    val previews = MANIFEST_JSON.decodeFromString(PreviewManifest.serializer(), previewsJsonBytes)

    // A published bundle externalized its fonts out of `classes/app.jar` (via `bundle externalize`)
    // to stay slim; rehydrate the `--res` pool back into the expanded classes dir — already first
    // on
    // the render classpath — so the subprocess renderer resolves `/fonts/…`. No-op for a
    // self-contained bundle; fail-closed when it externalized resources but no pool was supplied,
    // or
    // an entry fails its sha256/size check.
    materializeExternalResources(manifest.externalResources, resPoolDir, classesDir)

    // A preview that isn't replayed from an intermediate representation needs its class from
    // `classes/app.jar`; a fully IR-backed bundle legitimately omits it. Re-impose the fast-fail
    // for a class-backed (or mixed) bundle that's missing the jar, rather than letting the renderer
    // start against an empty classes dir — or, for android, trip its sidecar/SDK checks first.
    if (!hasAppJar) {
      val irIds = manifest.intermediateRepresentations.map { it.previewId }.toSet()
      check(previews.previews.all { it.id in irIds }) {
        "bundle render: classes/app.jar missing in ${bundleFile.path}"
      }
    }

    return when (manifest.backend) {
      "desktop" -> renderDesktop(classesDir, libJars, manifest, previews)
      "android" ->
        renderAndroid(workDir, classesDir, libJars, manifest, previews, previewsJsonBytes)
      else ->
        throw UnsupportedOperationException(
          "bundle render: backend '${manifest.backend}' not supported (expected 'desktop' or 'android')"
        )
    }
  }

  private fun renderDesktop(
    classesDir: File,
    libJars: List<File>,
    manifest: BundleReader.Manifest,
    previews: PreviewManifest,
  ): Result {
    val rendererJars = locateRendererClasspath()
    if (rendererJars.isEmpty()) {
      throw IllegalStateException(
        "bundle render: no renderer jars found. Looked in `${rendererClasspathSearchDescription()}`; " +
          "either build the CLI via `./gradlew :cli:installDist` or set `-Dcomposeai.cli.appHome=<install-root>`."
      )
    }
    // Resolve the bundle's detached `maven` coordinates from local repos, downloading on a miss
    // (Tier 3). Misses and hash mismatches warn but never fail — see CoordinateResolver. Embedded
    // `libs/` jars and
    // resolved coordinate jars both sit between the consumer classes and the renderer's own Compose
    // stack, so a preview's own deps resolve while the renderer's bundled Compose still wins on
    // shared symbols (same layering the desktop viewer's URLClassLoader uses).
    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolvedJars =
      CoordinateResolver(warn = { logSink("compose-preview: $it") })
        .resolveAll(mavenCoords)
        .mapNotNull { it.file }
    val classpathString =
      (listOf(classesDir) + libJars + resolvedJars + rendererJars).joinToString(
        File.pathSeparator
      ) {
        it.absolutePath
      }

    // Previews replayed from a captured intermediate representation (schema v5) have NO consumer
    // class in `classes/app.jar` — their bytecode was intentionally dropped at pack time. They are
    // replayed through the Remote Compose / ProtoLayout runtime by the Android daemon
    // (`compose-preview bundle daemon`), not by the desktop subprocess renderer, which can't drive
    // those Android-only libraries. Skip them here rather than spawn `DesktopRendererMain` against
    // a
    // class that isn't present (which would fail every IR preview with a ClassNotFoundException).
    val irById = manifest.intermediateRepresentations.associateBy { it.previewId }

    outputDir.mkdirs()
    val succeeded = mutableListOf<RenderedPreview>()
    val failed = mutableListOf<FailedPreview>()
    for (preview in previews.previews) {
      val ir = irById[preview.id]
      if (ir != null) {
        logSink(
          "compose-preview: skipping ${preview.id} — IR replay (format=${ir.format}) runs in the " +
            "Android daemon (compose-preview bundle daemon), not the desktop renderer"
        )
        continue
      }
      val outFile = outputDir.resolve(safeFilename(preview.id) + ".png")
      val (exitCode, tail) = spawnRenderer(classpathString, preview, outFile)
      if (exitCode == 0 && outFile.isFile && outFile.length() > 0) {
        succeeded += RenderedPreview(preview.id, outFile)
        if (verbose) logSink("rendered ${preview.id} → ${outFile.path}")
      } else {
        failed += FailedPreview(preview.id, exitCode, tail)
        logSink("FAILED ${preview.id} (exit=$exitCode)")
        if (verbose) logSink(tail)
      }
    }
    return Result(previewCount = previews.previews.size, succeeded = succeeded, failed = failed)
  }

  /**
   * Re-render an `backend="android"` bundle by spawning the Android (Robolectric) renderer
   * ([ee.schimke.composeai.renderer.AndroidRendererMain], which reads `composeai.render.manifest`
   * and renders the whole `previews.json` into `composeai.render.outputDir` — one subprocess for
   * the batch, vs the desktop per-preview spawn). Classpath/JVM-arg/property assembly lives in the
   * unit-tested [AndroidBundleLaunch].
   *
   * Phase 1: the Android renderer sidecar (`lib-renderer-android/`) isn't packaged into the CLI
   * distribution yet, so absent an override this surfaces an actionable diagnostic rather than the
   * old blunt "backend not supported". The assembly + spawn path is real and exercisable today by
   * pointing `-Dcomposeai.cli.libRendererAndroidDir=<dir>` at a built renderer; packaging + the
   * end-to-end Robolectric validation land in Phase 2 (the SDK-gated Android CI chain).
   */
  private fun renderAndroid(
    workDir: File,
    classesDir: File,
    libJars: List<File>,
    manifest: BundleReader.Manifest,
    previews: PreviewManifest,
    previewsJsonRaw: String,
  ): Result {
    // IR-backed previews (schema v5) are replayed by the Android daemon (`compose-preview bundle
    // daemon`), not this one-shot renderer: their consumer class was dropped from the bundle at
    // pack
    // time, so handing them to AndroidRendererMain (which reflects the enclosing class) would fail.
    // Compute the IR/non-IR split FIRST and report IR previews as skipped — parity with
    // renderDesktop's per-preview IR skip. An all-IR bundle has nothing for this renderer to do, so
    // return before requiring the Android sidecar/SDK (which Phase 1 doesn't package), rather than
    // throwing on prerequisites we don't actually need.
    val irIds = manifest.intermediateRepresentations.map { it.previewId }.toSet()
    for (preview in previews.previews.filter { it.id in irIds }) {
      logSink(
        "compose-preview: skipping ${preview.id} — IR replay runs in the Android daemon " +
          "(compose-preview bundle daemon), not the one-shot renderer"
      )
    }
    val renderable = previews.previews.filter { it.id !in irIds }
    if (renderable.isEmpty()) {
      return Result(
        previewCount = previews.previews.size,
        succeeded = emptyList(),
        failed = emptyList(),
      )
    }

    val rendererJars = locateAndroidRendererClasspath()
    if (rendererJars.isEmpty()) {
      throw IllegalStateException(
        "bundle render: backend=android needs the Android renderer sidecar, which is not packaged " +
          "in this CLI build yet (Phase 2). Point at a built one via " +
          "`-Dcomposeai.cli.libRendererAndroidDir=<dir>`, or render a desktop bundle. Looked in " +
          "`${androidRendererSearchDescription()}`."
      )
    }
    val androidJar =
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = findLocalProperties())
        ?: throw IllegalStateException(
          "bundle render: backend=android needs android.jar — set ANDROID_HOME / ANDROID_SDK_ROOT, " +
            "or run from a project whose local.properties has sdk.dir (no platforms/android-*/" +
            "android.jar found)."
        )

    val launch = AndroidBundleLaunch(sdkLevel = AndroidBundleLaunch.sdkLevelFromSystemProperty())
    val configRoot =
      launch.writeRobolectricConfig(workDir.resolve("robolectric-config").apply { mkdirs() })

    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolvedJars =
      CoordinateResolver(warn = { logSink("compose-preview: $it") })
        .resolveAll(mavenCoords)
        .mapNotNull { it.file }

    // Strip the IR previews from the manifest the renderer sees so AndroidRendererMain never
    // attempts the classless preview (it renders the whole previews.json in one batch).
    val rendererPreviewsJson =
      if (irIds.isEmpty()) previewsJsonRaw else filterPreviewsJson(previewsJsonRaw, irIds)
    val previewsJsonFile =
      workDir.resolve("previews.json").apply { writeText(rendererPreviewsJson) }

    // Synthesized robolectric.properties wins first; then consumer classes + deps; then the Android
    // renderer's own runtime; android.jar last (discovery-only stub — Robolectric's android-all
    // sandbox supplies the real framework).
    val classpath =
      (listOf(configRoot, classesDir) + libJars + resolvedJars + rendererJars + listOf(androidJar))
        .joinToString(File.pathSeparator) { it.absolutePath }

    outputDir.mkdirs()
    val (exitCode, tail) = spawnAndroidRenderer(launch, classpath, previewsJsonFile, outputDir)

    val (succeeded, failed) = reconcileAndroidRenders(renderable, outputDir, exitCode, tail)
    if (verbose) succeeded.forEach { logSink("rendered ${it.id} → ${it.outputFile.path}") }
    if (failed.isNotEmpty()) {
      logSink("bundle render (android): ${failed.size} preview(s) produced no PNG (exit=$exitCode)")
      if (verbose) logSink(tail)
    }
    return Result(previewCount = previews.previews.size, succeeded = succeeded, failed = failed)
  }

  private fun spawnAndroidRenderer(
    launch: AndroidBundleLaunch,
    classpath: String,
    previewsJsonFile: File,
    outDir: File,
  ): Pair<Int, String> {
    val javaBin = locateJava()
    val command = buildList {
      add(javaBin)
      addAll(launch.jvmArgs())
      launch.systemProperties(previewsJsonFile.absolutePath, outDir.absolutePath).forEach { (k, v)
        ->
        add("-D$k=$v")
      }
      add("-cp")
      add(classpath)
      add("ee.schimke.composeai.renderer.AndroidRendererMainKt")
    }
    return runRenderProcess(ProcessBuilder(command), tailLines = 40)
  }

  /**
   * Start a render subprocess, drain its merged stdout/stderr on a daemon thread, and wait up to
   * [RENDER_PROCESS_TIMEOUT_SECONDS]. Reading the pipe on a separate thread (rather than
   * `readText()` on the caller) means a subprocess that hangs without closing stdout can't block us
   * past the timeout — `destroyForcibly()` closes the stream and ends the drain thread. Returns
   * exit code (124 on timeout) and the last [tailLines] lines of output.
   */
  private fun runRenderProcess(pb: ProcessBuilder, tailLines: Int): Pair<Int, String> {
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val sb = StringBuilder()
    val drain =
      Thread { proc.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
        .apply {
          isDaemon = true
          start()
        }
    val finished = proc.waitFor(RENDER_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) proc.destroyForcibly()
    drain.join(DRAIN_FLUSH_MILLIS)
    val tail = sb.toString().lines().takeLast(tailLines).joinToString("\n")
    return if (finished) {
      proc.exitValue() to tail
    } else {
      RENDER_TIMEOUT_EXIT to
        (tail + "\n[render subprocess timed out after ${RENDER_PROCESS_TIMEOUT_SECONDS}s]")
    }
  }

  /** Locate the Android renderer sidecar jars — Android twin of [locateRendererClasspath]. */
  private fun locateAndroidRendererClasspath(): List<File> {
    val override = System.getProperty("composeai.cli.libRendererAndroidDir")
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    val candidates =
      listOfNotNull(override?.let { File(it) }, appHome?.let { File(it, "lib-renderer-android") })
        .distinct()
    val firstExistingDir = candidates.firstOrNull { it.isDirectory } ?: return emptyList()
    return firstExistingDir
      .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
      ?.sortedBy { it.name }
      .orEmpty()
  }

  /**
   * Find the nearest `local.properties` (carrying `sdk.dir`) by walking up from the working
   * directory — `bundle render` runs outside Gradle, but it's commonly invoked from inside an
   * Android project whose SDK is configured only via `local.properties` (not `ANDROID_HOME`).
   * Returns null when none is found within a few levels; the env-var fallback in
   * [AndroidBundleLaunch.resolveAndroidJar] still applies.
   */
  private fun findLocalProperties(): File? {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    repeat(8) {
      val d = dir ?: return null
      File(d, "local.properties")
        .takeIf { it.isFile }
        ?.let {
          return it
        }
      dir = d.parentFile
    }
    return null
  }

  private fun androidRendererSearchDescription(): String {
    val override = System.getProperty("composeai.cli.libRendererAndroidDir")
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    return listOfNotNull(
        override?.let { "-Dcomposeai.cli.libRendererAndroidDir=$it" },
        appHome?.let { "$it/lib-renderer-android" },
      )
      .ifEmpty { listOf("<no APP_HOME / override set>") }
      .joinToString(" or ")
  }

  private fun expandAppJarAndReadManifests(
    zipBytes: ByteArray,
    classesDir: File,
  ): Triple<String, String, Boolean> {
    var bundleJson: String? = null
    var previewsJson: String? = null
    var appJarBytes: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        when (entry.name) {
          "bundle.json" -> bundleJson = zin.readBytes().toString(Charsets.UTF_8)
          "previews.json" -> previewsJson = zin.readBytes().toString(Charsets.UTF_8)
          "classes/app.jar" -> appJarBytes = zin.readBytes()
        }
        zin.closeEntry()
      }
    }
    val bundleJsonNonNull =
      requireNotNull(bundleJson) { "bundle render: bundle.json missing in ${bundleFile.path}" }
    val previewsJsonNonNull =
      requireNotNull(previewsJson) { "bundle render: previews.json missing in ${bundleFile.path}" }
    // `classes/app.jar` is absent from a fully IR-backed bundle (schema v5+), whose previews replay
    // from `ir/` rather than from reflected consumer bytecode. Expand the consumer classes only
    // when the bundle carries them; the caller validates that a class-backed preview isn't left
    // without its jar.
    appJarBytes?.let { expandJarBytes(it, classesDir) }
    return Triple(bundleJsonNonNull, previewsJsonNonNull, appJarBytes != null)
  }

  /**
   * Unpack [appJarBytes] (a JAR-format zip) into [targetDir]. Each entry's resolved path is
   * verified to live inside [targetDir] — defeats Zip Slip on a malformed/hostile bundle, same
   * defense as `safeExtractZip` in BundleCommand.
   */
  private fun expandJarBytes(appJarBytes: ByteArray, targetDir: File) {
    val targetPath = targetDir.canonicalFile.toPath()
    ZipInputStream(ByteArrayInputStream(appJarBytes)).use { zin ->
      while (true) {
        val entry: ZipEntry = zin.nextEntry ?: break
        // Resolve + normalize the entry against the target and verify containment via
        // Path.startsWith — the form CodeQL's java/zipslip recognizes as sanitization (the prior
        // canonicalFile + String.startsWith guard was equally safe but flagged as a false
        // positive).
        val resolved = targetPath.resolve(entry.name).normalize()
        if (!resolved.startsWith(targetPath)) {
          throw SecurityException(
            "bundle render: app jar entry escapes target dir: ${entry.name} → $resolved"
          )
        }
        val candidate = resolved.toFile()
        if (entry.isDirectory) {
          candidate.mkdirs()
        } else {
          candidate.parentFile?.mkdirs()
          fileSystem.write(candidate.path.toPath()) { writeAll(zin.source()) }
        }
        zin.closeEntry()
      }
    }
  }

  private fun spawnRenderer(
    classpath: String,
    preview: PreviewInfo,
    outFile: File,
  ): Pair<Int, String> {
    val args = buildRendererArgs(preview, outFile)
    val javaBin = locateJava()
    val pb =
      ProcessBuilder(
          javaBin,
          "--enable-native-access=ALL-UNNAMED",
          // Run the desktop renderer JVM as a macOS background agent (LSUIElement) so it
          // doesn't claim a Dock icon or steal focus. Must be a launch -D (before AWT inits);
          // macOS-only, ignored on Linux/Windows.
          "-Dapple.awt.UIElement=true",
          "-cp",
          classpath,
          "ee.schimke.composeai.renderer.DesktopRendererMainKt",
        )
        .command()
        .toMutableList()
        .apply { addAll(args) }
        .let { ProcessBuilder(it) }
    return runRenderProcess(pb, tailLines = 20)
  }

  internal fun buildRendererArgs(preview: PreviewInfo, outFile: File): List<String> {
    // DesktopRendererMain arg positions (see renderer-desktop/DesktopRendererMain.kt):
    //  0 className  1 functionName  2 widthPx  3 heightPx  4 density  5 showBackground
    //  6 backgroundColor  7 outputFile  8 wrapperClassName  9 wrapWidth  10 wrapHeight
    //  11 previewParameterProviderFqn  12 previewParameterLimit  13 locale
    //
    // Sizing: the bundle's previews.json carries discovery-resolved widthDp/heightDp/density
    // when a `@Preview(device=...)` or explicit dims are present. When unset, fall back to a
    // wrap-content sandbox (400×800 dp @ 2.625× default density = 1050×2100 px) and let the
    // renderer's wrap flags crop to the composable's intrinsic size on both axes.
    val widthDp = preview.params.widthDp ?: 400
    val heightDp = preview.params.heightDp ?: 800
    val density = preview.params.density ?: DEFAULT_DENSITY
    val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
    val heightPx = (heightDp * density).toInt().coerceAtLeast(1)
    val wrapWidth = preview.params.widthDp == null
    val wrapHeight = preview.params.heightDp == null
    val base =
      listOf(
        preview.className,
        preview.functionName,
        widthPx.toString(),
        heightPx.toString(),
        density.toString(),
        preview.params.showBackground.toString(),
        preview.params.backgroundColor.toString(),
        outFile.absolutePath,
        preview.params.wrapperClassName.orEmpty(),
        wrapWidth.toString(),
        wrapHeight.toString(),
        preview.params.previewParameterProviderClassName.orEmpty(),
        preview.params.previewParameterLimit.toString(),
        preview.params.locale.orEmpty(),
      )
    // Wrapped-axis size bounds (Max / Min / Within), when the bundle carries them, land at
    // DesktopRendererMain arg indices 28–31. The intervening optional slots (14–27: scroll / kind /
    // fontScale / systemUi / anim / siblings) aren't driven by the bundle path, so pad them with
    // empty strings that DesktopRendererMain's `getOrNull(...)` reads as "unset". Emit nothing when
    // no bound is set so the common case keeps the short, positional-stable arg list.
    val bounds =
      listOf(
        preview.params.minWidthPx,
        preview.params.minHeightPx,
        preview.params.maxWidthPx,
        preview.params.maxHeightPx,
      )
    if (bounds.all { it == null }) return base
    return base + List(14) { "" } + bounds.map { it?.toString() ?: "" }
  }

  /**
   * Locate the bundled renderer's classpath. In order:
   * 1. `-Dcomposeai.cli.libRendererDir=<dir>` — explicit override for tests / dev runs.
   * 2. `$APP_HOME/lib-renderer/` — the gradle `application` start script exports `APP_HOME`.
   * 3. `<classpath-jar-parent>/../lib-renderer/` — walking from a CLI jar location for IDE runs.
   */
  private fun locateRendererClasspath(): List<File> {
    val override = System.getProperty("composeai.cli.libRendererDir")
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    val candidates =
      listOfNotNull(
          override?.let { File(it) },
          appHome?.let { File(it, "lib-renderer") },
          inferLibRendererFromClasspath(),
        )
        .distinct()
    val firstExistingDir = candidates.firstOrNull { it.isDirectory } ?: return emptyList()
    return firstExistingDir
      .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
      ?.sortedBy { it.name }
      .orEmpty()
  }

  private fun rendererClasspathSearchDescription(): String {
    val override = System.getProperty("composeai.cli.libRendererDir")
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    return listOfNotNull(
        override?.let { "-Dcomposeai.cli.libRendererDir=$it" },
        appHome?.let { "$it/lib-renderer" },
        "<classpath-parent>/../lib-renderer",
      )
      .joinToString(" or ")
  }

  private fun inferLibRendererFromClasspath(): File? {
    val cp = System.getProperty("java.class.path") ?: return null
    val firstEntry = cp.split(File.pathSeparator).firstOrNull { it.endsWith(".jar") } ?: return null
    val libDir = File(firstEntry).parentFile ?: return null
    val installRoot = libDir.parentFile ?: return null
    val candidate = File(installRoot, "lib-renderer")
    return candidate.takeIf { it.isDirectory }
  }

  private fun locateJava(): String {
    System.getProperty("composeai.cli.javaBinary")?.let {
      return it
    }
    val javaHome = System.getProperty("java.home") ?: error("java.home not set")
    val bin = File(javaHome, "bin/java")
    if (bin.isFile) return bin.absolutePath
    val winBin = File(javaHome, "bin/java.exe")
    if (winBin.isFile) return winBin.absolutePath
    return bin.absolutePath // best effort; the spawn will surface the failure
  }

  private fun createTempWorkDir(): File {
    val dirPath = TemporaryDirectory / "compose-preview-bundle-render-${UUID.randomUUID()}"
    fileSystem.createDirectories(dirPath)
    Runtime.getRuntime().addShutdownHook(Thread { fileSystem.deleteRecursively(dirPath) })
    return dirPath.toFile()
  }

  /** Strip filesystem-hostile characters from a preview id. */
  private fun safeFilename(id: String): String =
    id
      .map { c ->
        when {
          c.isLetterOrDigit() || c in "._-" -> c
          else -> '_'
        }
      }
      .joinToString("")

  companion object {
    /**
     * Leaf filename the Android renderer ([ee.schimke.composeai.renderer.RobolectricRenderTest]'s
     * `outputFileFor`) writes a preview's primary capture to: the first capture's `renderOutput`
     * basename, or `"<id>.png"` when unset. Reconciling against this — rather than a name derived
     * from the preview id — is what keeps a successful Android render from being mis-reported as a
     * failure. Fan-out captures write additional files; the primary capture is the render signal.
     */
    /**
     * Return [raw] (a `previews.json` body) with every preview whose `id` is in [drop] removed from
     * the top-level `previews` array, preserving all other fields verbatim (operates on the JSON
     * tree, not the lossy `ignoreUnknownKeys` model). Used to hide IR-backed previews from the
     * batch Android renderer, whose consumer classes were dropped from the bundle at pack time.
     */
    internal fun filterPreviewsJson(raw: String, drop: Set<String>): String {
      val root = Json.parseToJsonElement(raw).jsonObject
      val previews = root["previews"]?.jsonArray ?: return raw
      val kept = previews.filter { el ->
        el.jsonObject["id"]?.jsonPrimitive?.contentOrNull !in drop
      }
      return Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(root + ("previews" to JsonArray(kept))),
      )
    }

    internal fun androidOutputLeaf(preview: PreviewInfo): String {
      val leaf = preview.captures.firstOrNull()?.renderOutput?.substringAfterLast('/')
      return if (leaf.isNullOrEmpty()) "${preview.id}.png" else leaf
    }

    /**
     * Reconcile the Android batch renderer's exit code + produced PNGs into per-preview
     * succeeded/failed lists. [AndroidRendererMain] renders the whole manifest into [outputDir];
     * each preview is matched by its capture's `renderOutput` leaf (the exact name
     * `RobolectricRenderTest.outputFileFor` writes — NOT an id-derived name, which the renderer
     * normalizes, e.g. `com.example.FooKt.CardPreview` → `CardPreview.png`).
     *
     * A force-killed run (timeout → [RENDER_TIMEOUT_EXIT]) fails *every* preview regardless of
     * on-disk PNGs: it may have written some before the kill, and [outputDir] can hold stale PNGs
     * from a prior run — either would falsely pass the file check. A normal non-zero exit keeps
     * partial-success semantics (some previews render, others don't), matching the desktop path.
     */
    internal fun reconcileAndroidRenders(
      renderable: List<PreviewInfo>,
      outputDir: File,
      exitCode: Int,
      tail: String,
    ): Pair<List<RenderedPreview>, List<FailedPreview>> {
      val timedOut = exitCode == RENDER_TIMEOUT_EXIT
      val succeeded = mutableListOf<RenderedPreview>()
      val failed = mutableListOf<FailedPreview>()
      for (preview in renderable) {
        val outFile = outputDir.resolve(androidOutputLeaf(preview))
        if (!timedOut && outFile.isFile && outFile.length() > 0) {
          succeeded += RenderedPreview(preview.id, outFile)
        } else {
          failed += FailedPreview(preview.id, exitCode, tail)
        }
      }
      return succeeded to failed
    }

    /** Compose Desktop's default density = 2.625× (~xxhdpi). Same constant as the renderer. */
    private const val DEFAULT_DENSITY: Float = 2.625f

    /**
     * Upper bound on a single render subprocess (cold JVM start + one preview). Matches the
     * generous Gradle-render ceiling in [serve.GradleRevisionBuilder]; a wedged render (composition
     * that never settles, native Skiko stall) is force-killed rather than hanging `bundle render`.
     */
    private const val RENDER_PROCESS_TIMEOUT_SECONDS = 600L
    private const val DRAIN_FLUSH_MILLIS = 2000L

    /** Exit code [runRenderProcess] returns when it force-kills a subprocess past the timeout. */
    private const val RENDER_TIMEOUT_EXIT = 124

    private val BUNDLE_JSON = Json {
      ignoreUnknownKeys = true
      classDiscriminator = "kind"
    }
    private val MANIFEST_JSON = Json { ignoreUnknownKeys = true }
  }
}
