@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.getToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation

/**
 * Stage-2 in-process compiler session — see
 * [docs/daemon/COMPILE-IN-PROCESS.md](../../../../../../docs/daemon/COMPILE-IN-PROCESS.md).
 *
 * One session per daemon JVM. Holds the lazy [KotlinToolchains] (impl loaded into an isolated
 * classloader on first use), the per-module IC working directory, and the persistent classpath
 * snapshot cache. Wraps the BTA `JvmCompilationOperation` machinery so the `JsonRpcServer.
 * compileSources` handler is a thin call site.
 *
 * Construction is cheap; nothing happens until the first [compile] / [compileIncremental] call.
 * That first call pays the BTA impl bootstrap (~5 s in the spike); subsequent calls reuse the
 * loaded toolchain and warm compiler frontend. The session lives until daemon shutdown — if the
 * consumer's classpath changes (Tier-1 dirty signal from DESIGN.md § 8) the whole daemon recycles,
 * including this session.
 *
 * Construction parameters mirror the spike's `BtaCompiler` but add per-session policy:
 *
 * - [implClasspath]: the BTA-impl JARs + kotlin-compiler-embeddable + transitive kotlin-/jna-
 *   runtime JARs that the impl classloader needs at its URLs. Supplied by the daemon launch
 *   descriptor's `btaCompilerClasspath` (the gradle plugin populates this when the build opts in
 *   via `composePreview { daemon { compileInProcess = true } }`).
 * - [icWorkingDir]: per-module persistent IC cache directory. Survives across daemon spawns so a
 *   daemon restart doesn't lose the cumulative IC state — but the daemon is recycled on
 *   classpath-dirty (Tier 1) which invalidates the IC inputs anyway, so survival is bounded.
 * - [moduleName]: the Kotlin `MODULE_NAME` arg. Matches the consumer's Gradle module name so output
 *   classes carry the same `kotlin.Metadata.d2[]` entry; this is what makes BTA-emitted classes a
 *   drop-in replacement for Gradle-emitted classes in the daemon's child classloader.
 *
 * All compile calls are dispatched on the caller's thread. The JSON-RPC handler should call from a
 * worker, not the read loop. The `BtaCompileSession` itself is thread-safe; concurrent compile
 * calls serialize on the underlying `KotlinToolchains.BuildSession` lifecycle.
 */
class BtaCompileSession(
  private val implClasspath: List<Path>,
  private val icWorkingDir: Path,
  private val moduleName: String,
  private val logger: KotlinLogger = StderrLogger,
) {

  private val toolchains: KotlinToolchains by lazy {
    val loader =
      URLClassLoader(
        implClasspath.map { it.toUri().toURL() }.toTypedArray(),
        SharedApiClassesClassLoader(),
      )
    KotlinToolchains.loadImplementation(loader)
  }

  /**
   * Non-incremental compile. Equivalent to the spike's `BtaCompiler.compile` — exposed for paths
   * where IC isn't useful (cold-bootstrap warm-up, parity tests). Production save loops should call
   * [compileIncremental] instead.
   */
  fun compile(
    sources: List<Path>,
    compileClasspath: List<Path>,
    outputDir: Path,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
  ): List<Path> {
    outputDir.toFile().mkdirs()
    val jvm = toolchains.getToolchain<JvmPlatformToolchain>()
    toolchains.createBuildSession().use { session ->
      val op = jvm.createJvmCompilationOperation(sources, outputDir)
      configureCompilerArgs(op, compileClasspath, compilerPlugins)
      executeOrThrow(session, op)
    }
    return collectClassFiles(outputDir)
  }

  /**
   * Incremental compile. Same shape as the spike's `BtaCompiler.compileIncremental`:
   *
   * - Each compile-classpath JAR is snapshotted (cheap; cached on disk in
   *   [icWorkingDir]/`cp-snapshots/`) and persisted with a content-hash filename so an in-place JAR
   *   rebuild invalidates the cache automatically.
   * - [sourcesChanges] defaults to [SourcesChanges.ToBeCalculated]; callers with a file watcher
   *   pass [SourcesChanges.Known] for tighter incrementality.
   * - The BTA IC working directory is [icWorkingDir]/`ic/`; the shrunk classpath snapshot is
   *   written to [icWorkingDir]/`shrunk-classpath-snapshot.bin`.
   */
  fun compileIncremental(
    sources: List<Path>,
    compileClasspath: List<Path>,
    outputDir: Path,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
    sourcesChanges: SourcesChanges = SourcesChanges.ToBeCalculated,
  ): List<Path> {
    outputDir.toFile().mkdirs()
    icWorkingDir.toFile().mkdirs()
    val cpSnapshotsDir = icWorkingDir.resolve("cp-snapshots").also { it.toFile().mkdirs() }
    val icDir = icWorkingDir.resolve("ic").also { it.toFile().mkdirs() }
    val shrunkClasspathSnapshot = icWorkingDir.resolve("shrunk-classpath-snapshot.bin")

    val jvm = toolchains.getToolchain<JvmPlatformToolchain>()
    toolchains.createBuildSession().use { session ->
      val snapshotFiles = compileClasspath.map { jar ->
        // Content-hash the JAR rather than path-hashing — production needs to survive in-place
        // AAR rebuilds where the JAR's path stays stable but its contents move. Reuses the
        // existing snapshot when the SHA-256 matches.
        val sha = sha256OfFile(jar)
        val cached = cpSnapshotsDir.resolve("$sha.bin")
        if (!cached.exists()) {
          val snapshotOp = jvm.createClasspathSnapshottingOperation(jar)
          val snapshot = session.executeOperation(snapshotOp)
          snapshot.saveSnapshot(cached)
        }
        cached
      }

      val op = jvm.createJvmCompilationOperation(sources, outputDir)
      val icOptions = op.createSnapshotBasedIcOptions()
      val icConfig =
        JvmSnapshotBasedIncrementalCompilationConfiguration(
          icDir,
          sourcesChanges,
          snapshotFiles,
          shrunkClasspathSnapshot,
          icOptions,
        )
      op.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icConfig)
      configureCompilerArgs(op, compileClasspath, compilerPlugins)
      executeOrThrow(session, op)
    }
    return collectClassFiles(outputDir)
  }

  private fun configureCompilerArgs(
    op: JvmCompilationOperation,
    compileClasspath: List<Path>,
    compilerPlugins: List<CompilerPlugin>,
  ) {
    val args = op.compilerArguments
    args.set(
      JvmCompilerArguments.CLASSPATH,
      compileClasspath.joinToString(separator = java.io.File.pathSeparator) { it.toString() },
    )
    args.set(JvmCompilerArguments.JVM_TARGET, JvmTarget.JVM_17)
    args.set(JvmCompilerArguments.MODULE_NAME, moduleName)
    if (compilerPlugins.isNotEmpty()) {
      args.set(CommonCompilerArguments.COMPILER_PLUGINS, compilerPlugins)
    }
  }

  private fun executeOrThrow(session: KotlinToolchains.BuildSession, op: JvmCompilationOperation) {
    val result: CompilationResult =
      session.executeOperation(op, toolchains.createInProcessExecutionPolicy(), logger)
    check(result == CompilationResult.COMPILATION_SUCCESS) { "BTA compile failed: result=$result" }
  }

  private fun collectClassFiles(outputDir: Path): List<Path> =
    outputDir
      .toFile()
      .walkTopDown()
      .filter { it.isFile && it.extension == "class" }
      .map { it.toPath() }
      .toList()

  private fun sha256OfFile(path: Path): String {
    // Streamed SHA — works on the gradle-cache page-cache hot path without buffering the
    // whole JAR. The block size is small on purpose: AAR JARs can be 10s of MB and we don't
    // want a 64 KB allocation per call.
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8 * 1024)
    path.toFile().inputStream().use { stream ->
      while (true) {
        val n = stream.read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}

/**
 * Default logger that pipes BTA diagnostics to stderr. Production should replace with the daemon's
 * structured logging surface so compile errors flow back through the same channel as the rest of
 * the daemon's output.
 */
private object StderrLogger : KotlinLogger {
  override val isDebugEnabled: Boolean = false

  override fun error(msg: String, throwable: Throwable?) {
    System.err.println("[bta] ERROR: $msg")
    throwable?.printStackTrace(System.err)
  }

  override fun warn(msg: String) = System.err.println("[bta] WARN: $msg")

  override fun warn(msg: String, throwable: Throwable?) {
    System.err.println("[bta] WARN: $msg")
    throwable?.printStackTrace(System.err)
  }

  override fun info(msg: String) = System.err.println("[bta] INFO: $msg")

  override fun debug(msg: String) {
    /* default-quiet */
  }

  override fun lifecycle(msg: String) = System.err.println("[bta] LIFE: $msg")
}
