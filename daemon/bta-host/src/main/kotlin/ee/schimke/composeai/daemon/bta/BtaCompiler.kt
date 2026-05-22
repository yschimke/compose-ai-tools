@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.net.URLClassLoader
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.getToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain

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
      val result: CompilationResult =
        session.executeOperation(op, toolchains.createInProcessExecutionPolicy(), StderrLogger)
      check(result == CompilationResult.COMPILATION_SUCCESS) {
        "BTA compile failed: result=$result"
      }
    }
    return outputDir
      .toFile()
      .walkTopDown()
      .filter { it.isFile && it.extension == "class" }
      .map { it.toPath() }
      .toList()
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
