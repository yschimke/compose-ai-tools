package ee.schimke.composeai.viewer

import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

/**
 * Opens a `compose-preview` bundle (PNG+ZIP polyglot) and exposes its `@Preview` composables ready
 * to invoke inside an active Compose composition.
 *
 * # Classloading
 *
 * The bundle's inlined `classes/app.jar` is written to a temp file (URLClassLoader needs a URL, and
 * a `file:` URL is simpler than a `jar:` polyglot URL). A child [URLClassLoader] loads it with the
 * viewer's own classloader as parent — every `androidx.compose.*` symbol the bundle's code
 * references resolves against the viewer's bundled Compose runtime, so the composer state is shared
 * and `@Composable` invocation works across the loader boundary.
 *
 * # Lifecycle
 *
 * Call [close] to release the URLClassLoader (mandatory on Windows before the temp app.jar can be
 * deleted) and remove the extraction dir. Designed for swap-on-drop: closing a previous
 * [LoadedBundle] before constructing the next one avoids accumulating loaders / temp files in
 * long-running sessions.
 */
data class LoadedBundle(
  val sourceFile: File,
  val bundleManifest: BundleManifest,
  val previewManifest: PreviewManifest,
  val previews: List<LoadedPreview>,
  val coverPreview: LoadedPreview,
  private val classLoader: URLClassLoader,
  private val workDir: File,
) : AutoCloseable {
  override fun close() {
    runCatching { classLoader.close() }
    runCatching { workDir.deleteRecursively() }
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
fun loadBundle(bundleFile: File): LoadedBundle {
  require(bundleFile.isFile) { "not a file: ${bundleFile.path}" }

  val zipBytes = extractZipBytes(bundleFile)
  val workDir = Files.createTempDirectory("compose-preview-viewer-").toFile()
  val appJarFile = File(workDir, "app.jar")
  var bundleJson: String? = null
  var previewsJson: String? = null
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      when (entry.name) {
        "bundle.json" -> bundleJson = zin.readBytes().toString(Charsets.UTF_8)
        "previews.json" -> previewsJson = zin.readBytes().toString(Charsets.UTF_8)
        "classes/app.jar" -> appJarFile.outputStream().use { sink -> zin.copyTo(sink) }
      }
      zin.closeEntry()
    }
  }
  val bundleJsonNonNull = requireNotNull(bundleJson) { "bundle.json missing in ${bundleFile.path}" }
  val previewsJsonNonNull =
    requireNotNull(previewsJson) { "previews.json missing in ${bundleFile.path}" }
  check(appJarFile.isFile) { "classes/app.jar missing in ${bundleFile.path}" }

  val bundleManifest = JSON.decodeFromString(BundleManifest.serializer(), bundleJsonNonNull)
  val previewManifest = JSON.decodeFromString(PreviewManifest.serializer(), previewsJsonNonNull)
  if (previewManifest.previews.isEmpty()) {
    workDir.deleteRecursively()
    throw IllegalStateException("bundle has no previews: ${bundleFile.path}")
  }

  val parentLoader = LoadedBundle::class.java.classLoader
  val classLoader = URLClassLoader(arrayOf(appJarFile.toURI().toURL()), parentLoader)

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
private fun extractZipBytes(file: File): ByteArray {
  val bytes = file.readBytes()
  require(bytes.size >= 8) { "not a bundle: ${file.path} is too small (${bytes.size} bytes)" }
  if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
  if (!isPngSignature(bytes)) {
    throw IllegalArgumentException(
      "not a bundle: ${file.path} — leading bytes match neither PNG (\\x89PNG…) nor ZIP " +
        "(PK\\x03\\x04)"
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
