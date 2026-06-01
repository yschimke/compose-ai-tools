package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

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
    val (bundleJsonBytes, previewsJsonBytes) = expandAppJarAndReadManifests(zipBytes, classesDir)
    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir)

    val manifest = BUNDLE_JSON.decodeFromString(BundleReader.Manifest.serializer(), bundleJsonBytes)
    val previews = MANIFEST_JSON.decodeFromString(PreviewManifest.serializer(), previewsJsonBytes)

    if (manifest.backend != "desktop") {
      throw UnsupportedOperationException(
        "bundle render: backend '${manifest.backend}' not supported yet (v1 = desktop only)"
      )
    }

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
    // those Android-only libraries. Skip them here rather than spawn `DesktopRendererMain` against a
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

  private fun expandAppJarAndReadManifests(
    zipBytes: ByteArray,
    classesDir: File,
  ): Pair<String, String> {
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
    val appJarBytesNonNull =
      requireNotNull(appJarBytes) { "bundle render: classes/app.jar missing in ${bundleFile.path}" }
    expandJarBytes(appJarBytesNonNull, classesDir)
    return bundleJsonNonNull to previewsJsonNonNull
  }

  /**
   * Unpack [appJarBytes] (a JAR-format zip) into [targetDir]. Each entry's resolved path is
   * verified to live inside [targetDir] — defeats Zip Slip on a malformed/hostile bundle, same
   * defense as `safeExtractZip` in BundleCommand.
   */
  private fun expandJarBytes(appJarBytes: ByteArray, targetDir: File) {
    val canonicalTarget = targetDir.canonicalFile
    ZipInputStream(ByteArrayInputStream(appJarBytes)).use { zin ->
      while (true) {
        val entry: ZipEntry = zin.nextEntry ?: break
        val candidate = File(targetDir, entry.name).canonicalFile
        if (
          candidate != canonicalTarget &&
            !candidate.path.startsWith(canonicalTarget.path + File.separator)
        ) {
          throw SecurityException(
            "bundle render: app jar entry escapes target dir: ${entry.name} → ${candidate.path}"
          )
        }
        if (entry.isDirectory) {
          candidate.mkdirs()
        } else {
          candidate.parentFile?.mkdirs()
          candidate.outputStream().use { sink -> zin.copyTo(sink) }
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
          "-cp",
          classpath,
          "ee.schimke.composeai.renderer.DesktopRendererMainKt",
        )
        .command()
        .toMutableList()
        .apply { addAll(args) }
        .let { ProcessBuilder(it) }
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText()
    val exitCode = proc.waitFor()
    val tail = output.lines().takeLast(20).joinToString("\n")
    return exitCode to tail
  }

  private fun buildRendererArgs(preview: PreviewInfo, outFile: File): List<String> {
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
    return listOf(
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
    val dir = java.nio.file.Files.createTempDirectory("compose-preview-bundle-render-").toFile()
    Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    return dir
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
    /** Compose Desktop's default density = 2.625× (~xxhdpi). Same constant as the renderer. */
    private const val DEFAULT_DENSITY: Float = 2.625f

    private val BUNDLE_JSON = Json {
      ignoreUnknownKeys = true
      classDiscriminator = "kind"
    }
    private val MANIFEST_JSON = Json { ignoreUnknownKeys = true }
  }
}
