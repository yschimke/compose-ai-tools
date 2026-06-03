package ee.schimke.composeai.viewer

import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import java.io.ByteArrayInputStream
import java.net.URLClassLoader
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.source

/**
 * Opens a `compose-preview` bundle (PNG+ZIP polyglot) and exposes its `@Preview` composables ready
 * to invoke inside an active Compose composition.
 *
 * # Classloading
 *
 * The bundle's inlined `classes/app.jar` is written to a temp file (URLClassLoader needs a URL, and
 * a `file:` URL is simpler than a `jar:` polyglot URL). Any jars the bundle carries under `libs/` —
 * the schema-v3 `resolution = "embedded"` / `"mixed"` mode, where reachable third-party (and
 * project-local) deps are packed inside the bundle rather than referenced by Maven coordinate — are
 * extracted alongside it and added to the same child [URLClassLoader]. That loader uses the
 * viewer's own classloader as parent, so every `androidx.compose.*` symbol still resolves against
 * the viewer's bundled Compose runtime (shared composer state, cross-loader `@Composable`
 * invocation), while a preview's *own* third-party dependencies (an icon pack, Coil, a
 * `:design-system` jar, …) resolve from the embedded `libs/` jars instead of blowing up with
 * `NoClassDefFoundError`.
 *
 * A `coordinates`-mode bundle carries no `libs/`; the loader simply finds none and behaves exactly
 * as before (Compose-only previews work; previews needing un-bundled third-party deps still won't,
 * which is what `--embed-deps` is for).
 *
 * # Lifecycle
 *
 * Call [close] to release the URLClassLoader (mandatory on Windows before the temp app.jar can be
 * deleted) and remove the extraction dir. Designed for swap-on-drop: closing a previous
 * [LoadedBundle] before constructing the next one avoids accumulating loaders / temp files in
 * long-running sessions.
 */
data class LoadedBundle(
  val sourceFile: Path,
  val bundleManifest: BundleManifest,
  val previewManifest: PreviewManifest,
  val previews: List<LoadedPreview>,
  val coverPreview: LoadedPreview,
  private val classLoader: URLClassLoader,
  private val workDir: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) : AutoCloseable {
  override fun close() {
    runCatching { classLoader.close() }
    runCatching { fileSystem.deleteRecursively(workDir) }
  }
}

/** One preview ready to invoke via [ComposableMethod.invoke] inside an active composition. */
data class LoadedPreview(
  val info: PreviewInfo,
  /** Resolved enclosing class loaded via the bundle's child classloader. */
  val ownerClass: Class<*>,
  /**
   * Result of [getDeclaredComposableMethod]. Nullable when resolution fails (e.g. the preview uses
   * `@PreviewParameter` or non-default arguments — the viewer's v1 ignores those and surfaces a
   * clear error instead of crashing the window).
   */
  val composableMethod: ComposableMethod?,
  /** Reason resolution failed, when [composableMethod] is null. Human-readable, English. */
  val errorMessage: String?,
)

/**
 * Parses [bundleFile] and returns a [LoadedBundle]. Throws [IllegalArgumentException] if the file
 * isn't a recognised polyglot or zip, [IllegalStateException] when required entries are absent.
 * Per-preview resolution failures are recorded inside [LoadedPreview.errorMessage] rather than
 * aborting the whole load.
 */
fun loadBundle(bundleFile: Path, fileSystem: FileSystem = SystemFileSystem): LoadedBundle {
  require(fileSystem.metadataOrNull(bundleFile)?.isRegularFile == true) {
    "not a file: $bundleFile"
  }

  val zipBytes = extractZipBytes(bundleFile, fileSystem)
  val workDir =
    (TemporaryDirectory / "compose-preview-viewer-${UUID.randomUUID()}").also {
      fileSystem.createDirectories(it)
    }
  val appJarPath = workDir / "app.jar"
  // Embedded dep jars, keyed by their posix `libs/<name>.jar` path so order is deterministic and
  // dedupe-safe even if the zip lists them oddly. Extracted under workDir/libs/.
  val libJarFiles = sortedMapOf<String, Path>()
  var bundleJson: String? = null
  var previewsJson: String? = null
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      val name = entry.name
      when {
        name == "bundle.json" -> bundleJson = zin.readBytes().toString(Charsets.UTF_8)
        name == "previews.json" -> previewsJson = zin.readBytes().toString(Charsets.UTF_8)
        name == "classes/app.jar" ->
          fileSystem.sink(appJarPath).buffer().use { it.writeAll(zin.source()) }
        !entry.isDirectory && name.startsWith("libs/") && name.endsWith(".jar") -> {
          // Flatten to a safe basename under workDir/libs/; `libs/` paths in our own bundles
          // never contain `..` or nested dirs, but guard against a hostile bundle escaping
          // workDir.
          val safe = workDir / "libs" / name.substringAfterLast('/')
          safe.parent?.let { fileSystem.createDirectories(it) }
          fileSystem.sink(safe).buffer().use { it.writeAll(zin.source()) }
          libJarFiles[name] = safe
        }
      }
      zin.closeEntry()
    }
  }
  val bundleJsonNonNull = requireNotNull(bundleJson) { "bundle.json missing in $bundleFile" }
  val previewsJsonNonNull = requireNotNull(previewsJson) { "previews.json missing in $bundleFile" }
  check(fileSystem.exists(appJarPath)) { "classes/app.jar missing in $bundleFile" }

  val bundleManifest = JSON.decodeFromString(BundleManifest.serializer(), bundleJsonNonNull)
  val previewManifest = JSON.decodeFromString(PreviewManifest.serializer(), previewsJsonNonNull)
  if (previewManifest.previews.isEmpty()) {
    fileSystem.deleteRecursively(workDir)
    throw IllegalStateException("bundle has no previews: $bundleFile")
  }

  // Default (coordinate-mode) bundles reference their deps by `maven` coordinate rather than
  // carrying them; resolve those from the machine's local Maven / Gradle caches (warn-not-fail) so
  // the preview's own third-party deps are available the same way embedded `libs/` jars are.
  val resolvedCoordJars =
    CoordinateResolver.resolve(
      bundleManifest.classpath.filterIsInstance<ClasspathEntry.Maven>(),
      fileSystem = fileSystem,
    )

  val parentLoader = LoadedBundle::class.java.classLoader
  // app.jar first, then embedded lib jars (path-sorted), then resolved coordinate jars. The
  // viewer's
  // bundled Compose still wins on shared symbols because it sits on the parent loader; these jars
  // only supply classes the parent doesn't have (the preview's own third-party deps).
  // URLClassLoader is a hard `java.io.File` boundary (it wants `file:` URLs) — bridge each Okio
  // path to a File here.
  val classpathUrls =
    (listOf(appJarPath) + libJarFiles.values + resolvedCoordJars).map {
      it.toFile().toURI().toURL()
    }
  val classLoader = URLClassLoader(classpathUrls.toTypedArray(), parentLoader)

  val loadedPreviews = previewManifest.previews.map { info -> resolvePreview(info, classLoader) }
  val cover =
    loadedPreviews.firstOrNull { it.info.id == bundleManifest.coverPreviewId }
      ?: loadedPreviews.first()

  return LoadedBundle(
    sourceFile = bundleFile,
    bundleManifest = bundleManifest,
    previewManifest = previewManifest,
    previews = loadedPreviews,
    coverPreview = cover,
    classLoader = classLoader,
    workDir = workDir,
    fileSystem = fileSystem,
  )
}

private fun resolvePreview(info: PreviewInfo, classLoader: ClassLoader): LoadedPreview {
  val ownerClass =
    try {
      Class.forName(info.className, true, classLoader)
    } catch (e: Throwable) {
      return LoadedPreview(
        info = info,
        ownerClass = Any::class.java, // placeholder — never read when method is null
        composableMethod = null,
        errorMessage =
          "Could not load class ${info.className}: ${e.javaClass.simpleName}: ${e.message}",
      )
    }

  // Resolution mirrors the renderer's reflective lookup. Composables with `@PreviewParameter` or
  // wrapper providers are out of scope for v1 — fall through to a friendly error so the window
  // shows what went wrong instead of crashing on `IllegalArgumentException`.
  if (info.params.previewParameterProviderClassName != null) {
    return LoadedPreview(
      info = info,
      ownerClass = ownerClass,
      composableMethod = null,
      errorMessage =
        "@PreviewParameter previews are not supported in the viewer v1 — render them via the " +
          "CLI (`compose-preview bundle render ${info.id}`) instead.",
    )
  }

  val method =
    try {
      ownerClass.getDeclaredComposableMethod(info.functionName)
    } catch (e: NoSuchMethodException) {
      return LoadedPreview(
        info = info,
        ownerClass = ownerClass,
        composableMethod = null,
        errorMessage =
          "No composable method '${info.functionName}' on ${info.className} — was the preview " +
            "compiled with non-default parameters?",
      )
    } catch (e: Throwable) {
      return LoadedPreview(
        info = info,
        ownerClass = ownerClass,
        composableMethod = null,
        errorMessage =
          "${e.javaClass.simpleName} resolving ${info.className}.${info.functionName}: ${e.message}",
      )
    }
  return LoadedPreview(
    info = info,
    ownerClass = ownerClass,
    composableMethod = method,
    errorMessage = null,
  )
}

/**
 * Reads [bundleFile] and returns the trailing zip bytes. Detects the PNG signature and walks chunks
 * to the IEND marker; plain zips (signature `PK\x03\x04`) are returned as-is.
 *
 * Mirrors the same routine in `:cli/BundleCommand.kt` — duplicated rather than depended on to keep
 * the viewer's module graph clean.
 */
private fun extractZipBytes(file: Path, fileSystem: FileSystem): ByteArray {
  val bytes = fileSystem.read(file) { readByteArray() }
  require(bytes.size >= 8) { "not a bundle: $file is too small (${bytes.size} bytes)" }
  if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
  if (!isPngSignature(bytes)) {
    throw IllegalArgumentException(
      "not a bundle: $file — leading bytes match neither PNG (\\x89PNG…) nor ZIP (PK\\x03\\x04)"
    )
  }
  val zipStart = pngLength(bytes)
  return bytes.copyOfRange(zipStart, bytes.size)
}

private val PNG_SIG: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

private fun isPngSignature(bytes: ByteArray): Boolean {
  if (bytes.size < PNG_SIG.size) return false
  for (i in PNG_SIG.indices) if (bytes[i] != PNG_SIG[i]) return false
  return true
}

private fun pngLength(bytes: ByteArray): Int {
  var offset = PNG_SIG.size
  while (offset < bytes.size) {
    val length =
      ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
    val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
    offset += 4 + 4 + length + 4
    if (type == "IEND") return offset
  }
  throw IllegalArgumentException("truncated PNG: IEND not found before EOF")
}

internal val JSON = Json {
  ignoreUnknownKeys = true
  classDiscriminator = "kind"
}
