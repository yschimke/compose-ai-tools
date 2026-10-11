package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.expandZipBytesSafely
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem

/**
 * Re-renders a packed `.png` bundle outside any Gradle project: extract the zip, expand
 * `classes/app.jar`, and spawn `DesktopRendererMain` per preview using the renderer jars in
 * `lib-renderer/`.
 *
 * Subprocess per preview because the Compose Desktop + Skiko runtime must stay off the CLI's own
 * classpath (`CheckCliDaemonLibraryBoundary`), matching `RenderPreviewsTask`; the cold start is
 * acceptable for "open and look".
 *
 * Classpath order: expanded classes, embedded `libs/`, Maven coordinates resolved by
 * [CoordinateResolver] (local caches, then download, sha256-checked; misses only warn), then
 * `lib-renderer/`. Consumer deps resolve while the bundled Compose wins on shared symbols.
 *
 * `APP_HOME` (from the launcher script) locates `lib-renderer/`; Java is `java.home/bin/java`.
 * Tests override via `composeai.cli.appHome` / `composeai.cli.javaBinary`.
 */
class BundleRenderer(
  private val bundleFile: File,
  private val outputDir: File,
  private val verbose: Boolean = false,
  private val logSink: (String) -> Unit = { System.err.println(it) },
  private val fileSystem: FileSystem = SystemFileSystem,
  /**
   * A published bundle's externalized resource pool (`--res`), rehydrated into the classes dir so
   * `/fonts/…` resolves. Null for a self-contained bundle.
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

    // Rehydrate externalized fonts into the classes dir (first on the classpath). Fail-closed when
    // resources are needed but the pool is missing or an entry fails verification.
    materializeExternalResources(manifest.externalResources, resPoolDir, classesDir)

    // A fully IR-backed bundle may omit `classes/app.jar`; a class-backed or mixed one must not.
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
    // Fetched from the compose-preview-daemon release on first use;
    // `-Dcomposeai.cli.libRendererDir` or an installed `lib-renderer/` wins.
    DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.DESKTOP, log = logSink)
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (rendererJars.isEmpty()) {
      throw IllegalStateException(
        "bundle render: no renderer jars found. Looked in `${bundleSidecarSearchDescription("lib-renderer")}`; " +
          "it is fetched from the compose-preview-daemon release on first use, or set " +
          "`-Dcomposeai.cli.libRendererDir=<dir>/lib-renderer`."
      )
    }
    val skikoNative = SkikoNativeProvision.prepare(rendererJars)
    // Resolve detached `maven` coordinates (misses and hash mismatches only warn; see
    // CoordinateResolver), layered between consumer classes and the renderer's Compose.
    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolvedJars =
      CoordinateResolver(warn = { logSink("compose-preview: $it") })
        .resolveAll(mavenCoords)
        .mapNotNull { it.file }
    val classpathString =
      (listOf(classesDir, skikoNative) + libJars + resolvedJars + rendererJars).joinToString(
        File.pathSeparator
      ) {
        it.absolutePath
      }

    // IR-replayed previews (schema v5) have no consumer class; the Android daemon replays them, so
    // skip them here rather than fail with ClassNotFoundException.
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
   * Re-render a `backend="android"` bundle with the Robolectric renderer (`AndroidRendererMainKt`),
   * one subprocess for the whole `previews.json`. Launch assembly lives in [AndroidBundleLaunch].
   *
   * The Android renderer sidecar isn't packaged in the CLI yet, so without
   * `-Dcomposeai.cli.libRendererAndroidDir=<dir>` this reports an actionable diagnostic.
   */
  private fun renderAndroid(
    workDir: File,
    classesDir: File,
    libJars: List<File>,
    manifest: BundleReader.Manifest,
    previews: PreviewManifest,
    previewsJsonRaw: String,
  ): Result {
    // IR-backed previews are replayed by the Android daemon, not this renderer; split them out
    // first and report them as skipped. An all-IR bundle returns before requiring the sidecar or
    // SDK.
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

    // Hide IR previews from the batch renderer's manifest.
    val rendererPreviewsJson =
      if (irIds.isEmpty()) previewsJsonRaw else filterPreviewsJson(previewsJsonRaw, irIds)
    val previewsJsonFile =
      workDir.resolve("previews.json").apply { writeText(rendererPreviewsJson) }

    // Synthesized robolectric.properties first, then consumer classes and deps, the renderer
    // runtime, and android.jar last (a discovery stub; Robolectric supplies the real framework).
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
   * Start a render subprocess, drain its merged output on a separate thread, and wait up to
   * [RENDER_PROCESS_TIMEOUT_SECONDS] so a hung child can't block past the timeout. Returns the exit
   * code (124 on timeout) and the last [tailLines] lines.
   */
  private fun runRenderProcess(pb: ProcessBuilder, tailLines: Int): Pair<Int, String> {
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val sb = StringBuilder()
    val drain = Thread {
      proc.inputStream.bufferedReader().forEachLine { sb.appendLine(it) }
    }
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
   * The nearest `local.properties` (with `sdk.dir`) walking up from the working directory, or null;
   * [AndroidBundleLaunch.resolveAndroidJar] still falls back to env vars.
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
    // Absent from fully IR-backed bundles; the caller validates class-backed previews have it.
    appJarBytes?.let {
      expandZipBytesSafely(it, classesDir, fileSystem, "bundle render: app jar entry")
    }
    return Triple(bundleJsonNonNull, previewsJsonNonNull, appJarBytes != null)
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
          // macOS: run as a background agent (no Dock icon or focus steal); must be set before AWT
          // inits.
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
    // Without discovery-resolved dims, render in a wrap-content sandbox (400×800 dp at 2.625×) and
    // crop to intrinsic size. `wrapSandbox*Dp` narrows the sandbox per axis without disabling the
    // crop, which keys off `widthDp`/`heightDp` only.
    val widthDp = preview.params.widthDp ?: preview.params.wrapSandboxWidthDp ?: 400
    val heightDp = preview.params.heightDp ?: preview.params.wrapSandboxHeightDp ?: 800
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
    // Wrapped-axis bounds go at arg indices 28–31; slots 14–27 are padded with empty strings (read
    // as unset). Nothing is emitted when no bound is set.
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
     * [raw] `previews.json` with previews whose `id` is in [drop] removed, other fields preserved
     * verbatim (JSON tree, not the lossy model).
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

    /**
     * Filename the Android renderer writes for a preview's primary capture: its `renderOutput`
     * basename, or `"<id>.png"`. Matching this avoids misreporting successful renders as failures.
     */
    internal fun androidOutputLeaf(preview: PreviewInfo): String {
      val leaf = preview.captures.firstOrNull()?.renderOutput?.substringAfterLast('/')
      return if (leaf.isNullOrEmpty()) "${preview.id}.png" else leaf
    }

    /**
     * Reconcile the Android batch renderer's exit code and output PNGs into per-preview
     * succeeded/failed, matching by each capture's `renderOutput` leaf (the renderer normalizes
     * names, so id-derived names won't match). A timeout ([RENDER_TIMEOUT_EXIT]) fails every
     * preview, since partial or stale PNGs could falsely pass; a normal non-zero exit keeps partial
     * success.
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
     * Upper bound on one render subprocess (cold start + render); a wedged render is force-killed.
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
