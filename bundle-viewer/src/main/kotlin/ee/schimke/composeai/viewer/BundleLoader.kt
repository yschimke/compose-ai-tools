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
import okio.Path
import okio.buffer
import okio.source

/**
 * Opens a `compose-preview` bundle (PNG+ZIP polyglot) and exposes its `@Preview` composables ready
 * to invoke inside an active composition.
 *
 * The bundle's `classes/app.jar` and any embedded `libs/` jars (or resolved coordinate jars) go on
 * a child [URLClassLoader] whose parent is the viewer's loader, so `androidx.compose.*` resolves to
 * the viewer's Compose runtime while a preview's own third-party deps come from the bundle.
 *
 * Call [close] to release the loader (required on Windows before the temp jar can be deleted) and
 * remove the extraction dir; close the previous bundle before loading the next.
 */
data class LoadedBundle(
  val sourceFile: Path,
  val bundleManifest: BundleManifest,
  val previewManifest: PreviewManifest,
  val previews: List<LoadedPreview>,
  val coverPreview: LoadedPreview,
  private val classLoader: URLClassLoader,
  private val workDir: Path,
) : AutoCloseable {
  override fun close() {
    runCatching { classLoader.close() }
    runCatching { SystemFileSystem.deleteRecursively(workDir) }
  }
}

/** One preview ready to invoke via [ComposableMethod.invoke] inside an active composition. */
data class LoadedPreview(
  val info: PreviewInfo,
  /** Resolved enclosing class loaded via the bundle's child classloader. */
  val ownerClass: Class<*>,
  /**
   * Result of [getDeclaredComposableMethod], null when resolution fails (e.g. `@PreviewParameter`),
   * which surfaces as an error rather than a crash.
   */
  val composableMethod: ComposableMethod?,
  /** Reason resolution failed, when [composableMethod] is null. Human-readable, English. */
  val errorMessage: String?,
)

/**
 * Parses [bundleFile] and returns a [LoadedBundle]. Throws [IllegalArgumentException] for an
 * unrecognised file and [IllegalStateException] for missing entries; per-preview failures go in
 * [LoadedPreview.errorMessage].
 *
 * Uses the real [SystemFileSystem], since extracted jars are handed to a [URLClassLoader] by
 * `file:` URL.
 */
fun loadBundle(bundleFile: Path): LoadedBundle {
  require(SystemFileSystem.metadataOrNull(bundleFile)?.isRegularFile == true) {
    "not a file: $bundleFile"
  }

  val zipBytes = extractZipBytes(bundleFile)
  val workDir =
    (TemporaryDirectory / "compose-preview-viewer-${UUID.randomUUID()}").also {
      SystemFileSystem.createDirectories(it)
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
          SystemFileSystem.sink(appJarPath).buffer().use { it.writeAll(zin.source()) }
        !entry.isDirectory && name.startsWith("libs/") && name.endsWith(".jar") -> {
          // Flatten to a basename under workDir/libs/ so a hostile bundle can't escape workDir.
          val safe = workDir / "libs" / name.substringAfterLast('/')
          safe.parent?.let { SystemFileSystem.createDirectories(it) }
          SystemFileSystem.sink(safe).buffer().use { it.writeAll(zin.source()) }
          libJarFiles[name] = safe
        }
      }
      zin.closeEntry()
    }
  }
  val bundleJsonNonNull = requireNotNull(bundleJson) { "bundle.json missing in $bundleFile" }
  val previewsJsonNonNull = requireNotNull(previewsJson) { "previews.json missing in $bundleFile" }

  val bundleManifest = JSON.decodeFromString(BundleManifest.serializer(), bundleJsonNonNull)
  val previewManifest = JSON.decodeFromString(PreviewManifest.serializer(), previewsJsonNonNull)
  if (previewManifest.previews.isEmpty()) {
    SystemFileSystem.deleteRecursively(workDir)
    throw IllegalStateException("bundle has no previews: $bundleFile")
  }

  // A preview replayed from an intermediate representation (schema v5+, `ir/<id>.<ext>`) drops its
  // class, so a fully IR-backed bundle legitimately has no app.jar; only require it when needed.
  val hasAppJar = SystemFileSystem.exists(appJarPath)
  val irPreviewIds =
    bundleManifest.intermediateRepresentations.mapTo(mutableSetOf()) { it.previewId }
  val needsAppJar = previewManifest.previews.any { it.id !in irPreviewIds }
  check(hasAppJar || !needsAppJar) { "classes/app.jar missing in $bundleFile" }

  // Coordinate-mode bundles reference deps by `maven` coordinate; resolve them from local caches
  // (warn-not-fail), using any extra repositories the bundle names (v9).
  val resolvedCoordJars =
    CoordinateResolver.resolve(
      bundleManifest.classpath.filterIsInstance<ClasspathEntry.Maven>(),
      remoteRepositories =
        CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES +
          bundleManifest.repositories.filter { it.isNotBlank() },
    )

  val parentLoader = LoadedBundle::class.java.classLoader
  // app.jar, then embedded lib jars (sorted), then resolved coordinate jars. The parent loader's
  // Compose still wins on shared symbols.
  val classpathUrls =
    (listOfNotNull(appJarPath.takeIf { hasAppJar }) + libJarFiles.values + resolvedCoordJars).map {
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

  // `@PreviewParameter` and wrapper providers aren't supported; show an error instead of crashing.
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
      // A `private fun` preview resolves but needs its method opened for `ComposableMethod.invoke`.
      // `runCatching` so a SecurityManager refusal still lets public previews try the invoke.
      ownerClass.getDeclaredComposableMethod(info.functionName).also { resolved ->
        runCatching { resolved.asMethod().isAccessible = true }
      }
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
 * Reads [bundleFile] and returns the trailing zip bytes, walking PNG chunks to IEND; plain zips are
 * returned as-is. Duplicated from `:cli` to keep the viewer's module graph clean.
 */
private fun extractZipBytes(file: Path): ByteArray {
  val bytes = SystemFileSystem.read(file) { readByteArray() }
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
