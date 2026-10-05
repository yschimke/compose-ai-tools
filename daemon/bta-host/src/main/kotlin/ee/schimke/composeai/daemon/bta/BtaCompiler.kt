@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import org.jetbrains.kotlin.buildtools.api.CompilationResult
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
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation

/**
 * Stage-2 spike harness: compiles `.kt` sources in-process through the Kotlin Build Tools API
 * (BTA), with the Compose compiler plugin loaded into BTA's isolated classloader, to test whether
 * the daemon can produce Compose-transformed classes without Gradle.
 *
 * Not production code: no source-set wiring, KSP or Android variants.
 */
class BtaCompiler(
  /** JARs for the BTA implementation classloader; must contain `kotlin-build-tools-impl`. */
  private val implClasspath: List<Path>
) {

  private val toolchains: KotlinToolchains by lazy {
    // BTA's prescribed parent: exposes only the API types, shielding the impl from our classpath.
    val loader =
      URLClassLoader(
        implClasspath.map { it.toUri().toURL() }.toTypedArray(),
        SharedApiClassesClassLoader(),
      )
    KotlinToolchains.loadImplementation(loader)
  }

  /**
   * Compile [sources] against [compileClasspath] into [outputDir], returning the class files there.
   * Throws on failure. [compilerPlugins] are loaded into the impl's isolated classloader.
   */
  fun compile(
    sources: List<Path>,
    compileClasspath: List<Path>,
    outputDir: Path,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
    moduleName: String = DEFAULT_MODULE_NAME,
  ): List<Path> {
    outputDir.toFile().mkdirs()
    val jvm = toolchains.getToolchain<JvmPlatformToolchain>()
    toolchains.createBuildSession().use { session ->
      val builder = jvm.jvmCompilationOperationBuilder(sources, outputDir)
      configureCompilerArgs(
        builder.compilerArguments,
        compileClasspath,
        compilerPlugins,
        moduleName,
      )
      executeOrThrow(session, builder.build())
    }
    return collectClassFiles(outputDir)
  }

  /**
   * Incremental variant of [compile], following KGP's `BuildToolsApiCompilationWork`: classpath
   * snapshots are cached under `workingDir/cp-snapshots/` keyed by jar path (production would need
   * content hashes for jars rebuilt in place), and BTA's IC state lives in `workingDir/ic`.
   * [sourcesChanges] may name the dirty set when the caller already knows it.
   */
  fun compileIncremental(
    sources: List<Path>,
    compileClasspath: List<Path>,
    outputDir: Path,
    workingDir: Path,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
    sourcesChanges: SourcesChanges = SourcesChanges.ToBeCalculated,
    moduleName: String = DEFAULT_MODULE_NAME,
  ): List<Path> {
    outputDir.toFile().mkdirs()
    workingDir.toFile().mkdirs()
    val cpSnapshotsDir = workingDir.resolve("cp-snapshots").also { it.toFile().mkdirs() }
    val icWorkingDir = workingDir.resolve("ic").also { it.toFile().mkdirs() }

    val jvm = toolchains.getToolchain<JvmPlatformToolchain>()
    toolchains.createBuildSession().use { session ->
      val snapshotFiles = compileClasspath.map { jar ->
        val cached = cpSnapshotsDir.resolve("${sha1(jar.toString())}.bin")
        if (!cached.exists()) {
          val snapshottingOp = jvm.classpathSnapshottingOperationBuilder(jar).build()
          val snapshot = session.executeOperation(snapshottingOp)
          snapshot.saveSnapshot(cached)
        }
        cached
      }

      val builder = jvm.jvmCompilationOperationBuilder(sources, outputDir)
      val icConfig =
        builder
          .snapshotBasedIcConfigurationBuilder(
            icWorkingDir,
            sourcesChanges,
            snapshotFiles,
          )
          .build()
      builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icConfig)
      configureCompilerArgs(
        builder.compilerArguments,
        compileClasspath,
        compilerPlugins,
        moduleName,
      )

      executeOrThrow(session, builder.build())
    }
    return collectClassFiles(outputDir)
  }

  private fun configureCompilerArgs(
    args: JvmCompilerArguments.Builder,
    compileClasspath: List<Path>,
    compilerPlugins: List<CompilerPlugin>,
    moduleName: String,
  ) {
    args.set(JvmCompilerArguments.CLASSPATH, compileClasspath)
    args.set(JvmCompilerArguments.JVM_TARGET, JvmTarget.JVM_17)
    args.set(JvmCompilerArguments.MODULE_NAME, moduleName)
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

  private companion object {
    /** Overridden by tests that compare byte-for-byte against a Gradle-produced reference. */
    const val DEFAULT_MODULE_NAME = "bta-spike"
  }
}

/** Pipes BTA's diagnostics to stderr so they show in the test report. */
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
