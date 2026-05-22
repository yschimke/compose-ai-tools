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
 * Stage-2 spike harness: drives the Kotlin Build Tools API (BTA) to compile a single `.kt` file
 * in-process, loading the Compose compiler plugin into the same isolated classloader BTA uses for
 * its implementation JAR.
 *
 * The goal is **decisive**: can we get a usable `.class` for an `@Composable` source file without
 * going through Gradle, and does that `.class` show evidence of the Compose plugin's transformation
 * (Composer parameter injection)? If yes, the stage-2 path
 * (`composePreview.daemon.compileInProcess`) becomes plausible and we design the JSON-RPC surface +
 * IC caching layer on top. If no, the spike fails forward with a concrete diagnostic we can attach
 * to a KEEP-421 / JetBrains issue.
 *
 * **Not production code.** No incremental compilation, no source-set wiring, no KSP, no Android
 * variants. Single-translation-unit compile against a caller-supplied classpath, output to a
 * caller-supplied directory.
 */
class BtaCompiler(
  /**
   * JARs that form the BTA implementation classloader. Must contain `kotlin-build-tools-impl`.
   * (Compose compiler plugin lives on the per-compile [CompilerPlugin] classpath instead — see
   * [compile].)
   */
  private val implClasspath: List<Path>
) {

  private val toolchains: KotlinToolchains by lazy {
    // BTA's prescribed parent: an API-only classloader that exposes the
    // `org.jetbrains.kotlin.buildtools.api.*` types and delegates to the host's
    // ClassLoader for them, while shielding the impl from every other JAR on
    // our process classpath. `SharedApiClassesClassLoader()` is a top-level
    // function declared in the API jar (`@JvmName("newInstance")` is what
    // makes it visible to Java callers as a static method) — call it like a
    // constructor from Kotlin.
    val loader =
      URLClassLoader(
        implClasspath.map { it.toUri().toURL() }.toTypedArray(),
        SharedApiClassesClassLoader(),
      )
    KotlinToolchains.loadImplementation(loader)
  }

  /**
   * Compile [sources] against [compileClasspath], emit `.class` files into [outputDir]. Returns the
   * list of files written. Throws on compile failure.
   *
   * [compilerPlugins] feeds BTA's `CommonCompilerArguments.COMPILER_PLUGINS` slot — each entry
   * combines a plugin id (e.g. `androidx.compose.compiler.plugins.kotlin`) with the JAR(s) that
   * supply its `CompilerPluginRegistrar` service file. BTA loads those JARs into the same isolated
   * classloader as the impl, so the plugin's registrar resolves alongside the compiler frontend.
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
   * Incremental variant of [compile]. Reuses an on-disk cache under [workingDir] across calls so
   * downstream sessions skip re-analysing classpath entries that haven't moved.
   *
   * The mechanics, drawn from KGP's own `BuildToolsApiCompilationWork`:
   * 1. Snapshot each compile-classpath entry via [JvmClasspathSnapshottingOperation] and persist it
   *    under `workingDir/cp-snapshots/<sha1(jarPath)>.bin`. We cache by absolute path; this is a
   *    coarse signal, but for the spike's purposes (a single fixture compile classpath that never
   *    changes between calls) it's enough — production stage-2 needs a content-hash fallback when a
   *    JAR is rebuilt in place.
   * 2. Build a [JvmSnapshotBasedIncrementalCompilationConfiguration] pointing at:
   *     - `workingDir/ic` — BTA's own IC working dir (it'll create whatever it needs inside)
   *     - the persisted snapshot files
   *     - `workingDir/shrunk-classpath-snapshot.bin` — output target where BTA writes the shrunk
   *       snapshot after the compile
   * 3. Attach the config via `JvmCompilationOperation.INCREMENTAL_COMPILATION`.
   * 4. Execute.
   *
   * [sourcesChanges] defaults to [SourcesChanges.ToBeCalculated] — BTA inspects file timestamps vs.
   * its cache. Callers that already know the dirty set (e.g. a daemon-side file watcher) can pass
   * [SourcesChanges.Known] for tighter incrementality.
   *
   * NOT production-ready. Open follow-ups: content-hash classpath cache keys, source-set wiring,
   * KSP/KAPT, Android variants. See `docs/daemon/BTA-SPIKE.md`.
   */
  fun compileIncremental(
    sources: List<Path>,
    compileClasspath: List<Path>,
    outputDir: Path,
    workingDir: Path,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
    sourcesChanges: SourcesChanges = SourcesChanges.ToBeCalculated,
  ): List<Path> {
    outputDir.toFile().mkdirs()
    workingDir.toFile().mkdirs()
    val cpSnapshotsDir = workingDir.resolve("cp-snapshots").also { it.toFile().mkdirs() }
    val icWorkingDir = workingDir.resolve("ic").also { it.toFile().mkdirs() }
    val shrunkClasspathSnapshot = workingDir.resolve("shrunk-classpath-snapshot.bin")

    val jvm = toolchains.getToolchain<JvmPlatformToolchain>()
    toolchains.createBuildSession().use { session ->
      // 1. Classpath snapshots. Cheap when cached, sub-second per entry cold.
      val snapshotFiles = compileClasspath.map { jar ->
        val cached = cpSnapshotsDir.resolve("${sha1(jar.toString())}.bin")
        if (!cached.exists()) {
          val snapshottingOp = jvm.createClasspathSnapshottingOperation(jar)
          val snapshot = session.executeOperation(snapshottingOp)
          snapshot.saveSnapshot(cached)
        }
        cached
      }

      // 2 + 3. Build the IC config and attach it to the compile op.
      val op = jvm.createJvmCompilationOperation(sources, outputDir)
      val icOptions = op.createSnapshotBasedIcOptions()
      val icConfig =
        JvmSnapshotBasedIncrementalCompilationConfiguration(
          icWorkingDir,
          sourcesChanges,
          snapshotFiles,
          shrunkClasspathSnapshot,
          icOptions,
        )
      op.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icConfig)
      configureCompilerArgs(op, compileClasspath, compilerPlugins)

      // 4. Execute.
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
    args.set(JvmCompilerArguments.MODULE_NAME, "bta-spike")
    if (compilerPlugins.isNotEmpty()) {
      args.set(CommonCompilerArguments.COMPILER_PLUGINS, compilerPlugins)
    }
  }

  private fun executeOrThrow(session: KotlinToolchains.BuildSession, op: JvmCompilationOperation) {
    val result: CompilationResult =
      session.executeOperation(op, toolchains.createInProcessExecutionPolicy(), StderrLogger)
    check(result == CompilationResult.COMPILATION_SUCCESS) { "BTA compile failed: result=$result" }
  }

  private fun collectClassFiles(outputDir: Path): List<Path> =
    outputDir
      .toFile()
      .walkTopDown()
      .filter { it.isFile && it.extension == "class" }
      .map { it.toPath() }
      .toList()

  private fun sha1(s: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}

/**
 * Pipes BTA's diagnostic stream to System.err so a `COMPILER_INTERNAL_ERROR` / warnings / lifecycle
 * messages are visible in the test report.
 */
private object StderrLogger : KotlinLogger {
  override val isDebugEnabled: Boolean = true

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

  override fun debug(msg: String) = System.err.println("[bta] DEBUG: $msg")

  override fun lifecycle(msg: String) = System.err.println("[bta] LIFE: $msg")
}
