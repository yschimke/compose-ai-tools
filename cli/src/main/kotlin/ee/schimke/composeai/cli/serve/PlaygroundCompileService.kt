package ee.schimke.composeai.cli.serve

import java.util.Base64
import okio.FileSystem
import okio.Path

/**
 * Stage-1 orchestrator for the playground (`docs/design/PLAYGROUND.md` §2). Turns a
 * [PlaygroundRunRequest] into a [PlaygroundRunResponse]: stage the snippet to a temp dir, compile
 * it against the mode's catalog classpath, and — on a clean compile — mint an expiring preview
 * token that Stage 2 redeems into a live session. Compile errors return diagnostics and **no**
 * token.
 *
 * Every collaborator that touches the daemon, the compiler, or the catalog is an **injected seam**,
 * so the orchestration (staging, gating, cleanup, token minting, response shaping) is unit-testable
 * without a real BTA classloader or a running daemon — the same split
 * `DefaultBtaCompileService.forSession` uses. The route handler (a follow-up) constructs this with
 * the real backends.
 *
 * The single knob with real design weight is [catalogClasspath]. Its production implementation is a
 * thin adapter over the **liveBundle** resolution the serve host already runs
 * ([ServeBundleDaemon.materialize]): the catalog's packed `.png`/zip bundle is extracted to its
 * `classes/app.jar` and its `manifest.classpath` Maven coordinates resolved to jars, yielding the
 * `userClassPath` the live daemon runs on. A snippet compiled against that classpath can `import`
 * both the resolved library (e.g. `androidx.compose.material3.*`, complete because it comes from
 * the unminimized library jar) and whatever of the catalog's own composables survived bundle
 * minimization. `compose-m3` is the clean first catalog precisely because its component surface
 * *is* the resolved library jar. See `docs/design/PLAYGROUND.md` §8.
 */
class PlaygroundCompileService(
  /** Resolves the compile+render classpath for a mode; null ⇒ that mode isn't available here. */
  private val catalogClasspath: (PlaygroundMode) -> Classpath?,
  private val compiler: Compiler,
  private val discoverer: PreviewDiscoverer,
  private val tokenStore: PlaygroundTokenStore,
  /**
   * Mints a fresh, empty temp work dir per run — the token store deletes it when the token drops.
   */
  private val newWorkDir: () -> Path,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  /** Optional first-frame render; returns PNG bytes or null. Defaults to no image (wired later). */
  private val renderFirstFrame: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = { null },
) {

  /**
   * The resolved classpath a snippet compiles and renders against. Backed in production by a
   * catalog's liveBundle `userClassPath` (see the class KDoc); [moduleName] matches the catalog's
   * Kotlin module so the snippet's classes carry a consistent `kotlin.Metadata`.
   */
  data class Classpath(val moduleName: String, val entries: List<Path>)

  /** Compiles [sources] against [classpath], emitting `.class` files into [outputDir]. */
  fun interface Compiler {
    fun compile(
      sources: List<Path>,
      classpath: List<Path>,
      outputDir: Path,
    ): List<PlaygroundDiagnostic>
  }

  /**
   * Finds the `@Preview` id(s) in freshly compiled [classesDir]; empty ⇒ the snippet declared none.
   */
  fun interface PreviewDiscoverer {
    fun discover(classesDir: Path, classpath: List<Path>): List<String>
  }

  /**
   * Compile [request] and, on success, mint a preview token. [isSecurityChecked] is the greppable
   * audit marker forwarded to [PlaygroundTokenStore.add]: the route passes `true` only once the
   * request has cleared the playground gate.
   */
  fun run(request: PlaygroundRunRequest, isSecurityChecked: Boolean): PlaygroundRunResponse {
    val files = request.files.filter { it.text.isNotBlank() }
    if (files.isEmpty()) return failure("no source files supplied")

    val mode = PlaygroundMode.fromConfType(request.confType)
    val classpath =
      catalogClasspath(mode) ?: return failure("mode ${mode.name} is not available on this host")

    val workDir = newWorkDir()
    return try {
      compileAndMint(request, files, mode, classpath, workDir, isSecurityChecked)
    } catch (t: Throwable) {
      // Any unexpected failure past workDir creation must not strand the temp dir — the token store
      // only owns dirs it accepted, so an aborted run cleans up its own.
      cleanup(workDir)
      failure("playground compile failed: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  private fun compileAndMint(
    request: PlaygroundRunRequest,
    files: List<PlaygroundFile>,
    mode: PlaygroundMode,
    classpath: Classpath,
    workDir: Path,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val srcDir = workDir / "src"
    val classesDir = workDir / "classes"
    fileSystem.createDirectories(srcDir)
    fileSystem.createDirectories(classesDir)
    val sources = stageSources(files, srcDir)

    val diagnostics = compiler.compile(sources, classpath.entries, classesDir)
    if (diagnostics.any { it.severity == PlaygroundSeverity.ERROR }) {
      cleanup(workDir)
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = PlaygroundErrorsWire.project(diagnostics),
      )
    }

    val renderClasspath = classpath.entries + classesDir
    val previews = discoverer.discover(classesDir, renderClasspath)
    if (previews.isEmpty()) {
      cleanup(workDir)
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = PlaygroundErrorsWire.project(diagnostics),
        exception = "no @Preview found — a playground snippet must declare one @Preview composable",
      )
    }

    val snippet =
      PlaygroundTokenStore.PlaygroundSnippet(
        mode = mode,
        workDir = workDir,
        classesDir = classesDir,
        classpath = renderClasspath,
        moduleName = classpath.moduleName,
        previewId = previews.first(),
      )
    val image = renderFirstFrame(snippet)?.let(::toDataUri)
    // From here the token owns workDir; do NOT cleanup on this path.
    val token = tokenStore.add(snippet, isSecurityChecked = isSecurityChecked)
    return PlaygroundRunResponse(
      diagnostics = diagnostics,
      errors = PlaygroundErrorsWire.project(diagnostics),
      image = image,
      previewToken = token.id,
      previewUrl = token.path,
    )
  }

  /**
   * Write each file to [srcDir] under a safe, unique `.kt` name; return the staged source paths.
   */
  private fun stageSources(files: List<PlaygroundFile>, srcDir: Path): List<Path> {
    val used = mutableSetOf<String>()
    return files.map { file ->
      val name = uniqueName(safeKtName(file.name), used)
      val path = srcDir / name
      fileSystem.write(path) { writeUtf8(file.text) }
      path
    }
  }

  private fun cleanup(workDir: Path) {
    try {
      fileSystem.deleteRecursively(workDir, mustExist = false)
    } catch (_: Exception) {
      // Best-effort — leaking one temp dir beats throwing out of an error path.
    }
  }

  private fun failure(message: String) = PlaygroundRunResponse(exception = message)

  companion object {
    /**
     * Reduce a client-supplied name to a safe `.kt` filename (last segment, printable, non-empty).
     */
    internal fun safeKtName(raw: String): String {
      val base =
        raw
          .substringAfterLast('/')
          .substringAfterLast('\\')
          .filter { it.isLetterOrDigit() || it in "._-" }
          .removeSuffix(".kt")
          .takeIf { it.isNotBlank() } ?: "Snippet"
      return "$base.kt"
    }

    /** Ensure the name is unique within a run, appending `_<n>` before `.kt` on collision. */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
      if (used.add(name)) return name
      val stem = name.removeSuffix(".kt")
      var i = 1
      while (true) {
        val candidate = "${stem}_$i.kt"
        if (used.add(candidate)) return candidate
        i++
      }
    }

    internal fun toDataUri(png: ByteArray): String =
      "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
  }
}
