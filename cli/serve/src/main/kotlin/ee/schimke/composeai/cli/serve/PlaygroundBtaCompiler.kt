@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.bta.BtaCompileSession
import ee.schimke.composeai.daemon.bta.DiagnosticCollector
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import java.io.File
import java.nio.file.Path as NioPath
import okio.Path
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption

/**
 * In-process [PlaygroundCompileService.Compiler] backed by the Kotlin Build Tools API — compiles a
 * playground snippet against a catalog classpath without a daemon or a Gradle build
 * (`docs/design/PLAYGROUND.md` §2.1). Wraps the shipped [BtaCompileSession] (from `:daemon:core`,
 * which `:cli` already depends on) and maps BTA's diagnostics to [PlaygroundDiagnostic].
 *
 * The BTA *implementation* jars (`kotlin-build-tools-impl` + `kotlin-compiler-embeddable`) and the
 * Compose compiler plugin are staged into the CLI install's `lib-bta/` dir (see
 * `cli/build.gradle.kts`) and located at runtime by [fromInstall] — the serve host has no Gradle
 * plugin to supply them the way the editor daemon gets them. The impl jars load into BTA's isolated
 * classloader; the Compose plugin rides the per-compile [CompilerPlugin] classpath, exactly as the
 * daemon's `DefaultBtaCompileService` wires it.
 */
class PlaygroundBtaCompiler(
  /** `kotlin-build-tools-impl` + its transitive frontend — BTA's isolated-classloader classpath. */
  private val btaImplJars: List<NioPath>,
  /** `kotlin-compose-compiler-plugin-embeddable` — the Compose plugin's per-compile classpath. */
  private val compilerPluginJars: List<NioPath>,
  /** Per-process IC cache dir; reused across compiles in this host. */
  private val icWorkingDir: NioPath,
  private val moduleName: String = "playground",
) : PlaygroundCompileService.Compiler {

  // One session per compiler instance, retained across requests: its lazily-loaded toolchain pays
  // the BTA-impl bootstrap once (not per request), and BtaCompileSession serializes concurrent
  // compiles internally, so there's no per-request lock/cache race. Built lazily so constructing
  // the
  // compiler (e.g. probing availability) doesn't force the ~5 s toolchain load.
  private val session by lazy {
    BtaCompileSession(
      implClasspath = btaImplJars,
      icWorkingDir = icWorkingDir,
      moduleName = moduleName,
    )
  }
  private val compilerPlugins by lazy { composeCompilerPlugins(compilerPluginJars) }

  override fun compile(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
  ): List<PlaygroundDiagnostic> {
    return compileWithCollector { collector ->
      session.compile(
        sources = sources.map { it.toNioPath() },
        compileClasspath = classpath.map { it.toNioPath() },
        outputDir = outputDir.toNioPath(),
        compilerPlugins = compilerPlugins,
        diagnosticListener = collector,
      )
    }
  }

  override fun compileIncremental(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
    workingDir: Path,
    modified: List<Path>,
    removed: List<Path>,
    firstBuild: Boolean,
  ): PlaygroundCompileService.IncrementalCompileResult {
    val diagnostics = compileWithCollector { collector ->
      session.compileIncremental(
        sources = sources.map { it.toNioPath() },
        compileClasspath = classpath.map { it.toNioPath() },
        outputDir = outputDir.toNioPath(),
        compilerPlugins = compilerPlugins,
        sourcesChanges =
          if (firstBuild) SourcesChanges.Unknown
          else
            SourcesChanges.Known(
              modified.map { java.io.File(it.toString()) },
              removed.map { java.io.File(it.toString()) },
            ),
        diagnosticListener = collector,
        workingDir = workingDir.toNioPath(),
      )
    }
    // The first call seeds BTA's IC state but cannot reuse any previous compilation. Report only
    // subsequent calls as incremental so the UI and soak counters measure an actual warm edit.
    return PlaygroundCompileService.IncrementalCompileResult(
      diagnostics,
      incremental = !firstBuild,
    )
  }

  private inline fun compileWithCollector(
    block: (DiagnosticCollector) -> Unit
  ): List<PlaygroundDiagnostic> {
    val collector = DiagnosticCollector()
    return try {
      // The ordinary path stays non-incremental: a playground compiles a *fresh* snippet against a
      // stable catalog classpath, so retaining per-run IC state would be pure overhead. Leased
      // editing calls the incremental entry point above, including for a catalog's `classes/`
      // directory.
      block(collector)
      // A clean compile: DiagnosticCollector only captures errors, so success yields no
      // diagnostics.
      emptyList()
    } catch (t: Throwable) {
      // BtaCompileSession throws on COMPILATION_ERROR. If the collector caught structured
      // diagnostics, surface them; otherwise it's an internal fault (missing jar, BTA bootstrap) —
      // report it as a single file-level error so the caller still returns the JSON contract.
      if (collector.errors.isNotEmpty()) {
        mapDiagnostics(collector.errors)
      } else {
        listOf(
          PlaygroundDiagnostic(
            severity = PlaygroundSeverity.ERROR,
            message = "compilation failed: ${t.message ?: t.javaClass.simpleName}",
          )
        )
      }
    }
  }

  companion object {
    /** The Compose plugin jar's stable coordinate prefix, used to split it out of `lib-bta/`. */
    private const val COMPOSE_PLUGIN_PREFIX = "kotlin-compose-compiler-plugin-embeddable"

    /**
     * Build a compiler from the CLI install's staged `lib-bta/` dir, or null when it isn't present
     * (a non-installed run, or a build that didn't stage it) — the route then reports the mode
     * unavailable. Splits the Compose plugin jar(s) out of the impl classpath.
     */
    fun fromInstall(icWorkingDir: NioPath): PlaygroundBtaCompiler? {
      val (implJars, pluginJars) = installJars() ?: return null
      return PlaygroundBtaCompiler(
        btaImplJars = implJars.map(File::toPath),
        compilerPluginJars = pluginJars.map(File::toPath),
        icWorkingDir = icWorkingDir,
      )
    }

    /**
     * The staged `lib-bta/` split into (impl, Compose-plugin) jars, or null when the dir is absent
     * or carries no impl jar. Shared with [PlaygroundJailedCompiler], which binds the same jars
     * read-only into the compile sandbox and passes them to the child — so the jailed and
     * in-process compilers can never be looking at different toolchains.
     */
    fun installJars(): Pair<List<File>, List<File>>? {
      val jars = locateBundleSidecarJars("lib-bta")
      if (jars.isEmpty()) return null
      val (pluginJars, implJars) = jars.partition { it.name.startsWith(COMPOSE_PLUGIN_PREFIX) }
      if (implJars.isEmpty()) return null
      return implJars to pluginJars
    }

    /**
     * Compose compiler plugin config, mirroring `DefaultBtaCompileService.composeCompilerPlugins`
     * (which is `internal` to `:daemon:core`): the plugin id, its embeddable jars, and the
     * load-bearing `sourceInformation=true` option KGP enables by default. Empty when no plugin jar
     * was staged (a plain Kotlin/JVM catalog with no Compose).
     */
    internal fun composeCompilerPlugins(pluginJars: List<NioPath>): List<CompilerPlugin> =
      if (pluginJars.isEmpty()) {
        emptyList()
      } else {
        listOf(
          CompilerPlugin(
            "androidx.compose.compiler.plugins.kotlin",
            pluginJars,
            listOf(CompilerPluginOption("sourceInformation", "true")),
            emptySet(),
          )
        )
      }

    /**
     * Map BTA's [CompileErrorDetail]s (kotlinc-style **1-based** line/column) to
     * [PlaygroundDiagnostic] (CodeMirror **0-based**). The file is reduced to its basename — the
     * staged snippet filename the editor knows — not the temp path it compiled from. BTA's
     * [DiagnosticCollector] only captures errors, so every mapped diagnostic is
     * [PlaygroundSeverity.ERROR].
     */
    internal fun mapDiagnostics(details: List<CompileErrorDetail>): List<PlaygroundDiagnostic> =
      details.map { detail ->
        PlaygroundDiagnostic(
          severity = PlaygroundSeverity.ERROR,
          message = detail.message,
          file = File(detail.file).name,
          line = (detail.line - 1).coerceAtLeast(0),
          ch = (detail.column - 1).coerceAtLeast(0),
        )
      }
  }
}
