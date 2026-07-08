@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption

/**
 * Production [BtaCompileService] adapter. Constructed once per daemon JVM at startup if (and only
 * if) the launch descriptor opted into in-process compile by carrying a non-null
 * `btaCompilerClasspath`. The renderer-specific module (`:daemon:desktop`, `:daemon:android`)
 * instantiates this in its `DaemonMain` (via [forSession]) and hands it to [JsonRpcServer] via the
 * `btaCompileService` constructor slot — the JSON-RPC handler stays renderer-agnostic.
 *
 * Three things this adapter owns that the underlying [BtaCompileSession] doesn't:
 *
 * 1. **Eligibility gate.** A non-null [ineligibilityReason] means the consumer's module isn't a
 *    stage-2 candidate (KSP/KAPT detected, AGP variant without resource-jar plumbing yet, etc.; the
 *    gradle plugin's `detectStageTwoIneligibility` is the source of truth for the predicate). Every
 *    compile call short-circuits to [BtaCompileService.Outcome.Fallback] with that reason verbatim.
 *    The gradle plugin decides the predicate at daemon-bootstrap time; the daemon never
 *    re-evaluates (Tier-1 dirty recycles the whole daemon, and with it this service).
 *
 * 2. **`SourceChangeSet` → BTA `SourcesChanges` translation.** Editor-supplied known dirty sets
 *    become `SourcesChanges.Known`; null becomes `SourcesChanges.ToBeCalculated` (BTA inspects file
 *    timestamps against its IC cache). Same shape KGP uses.
 *
 * 3. **Exception → outcome mapping.** [forSession]'s backend routes BTA's diagnostic stream through
 *    a [DiagnosticCollector]; on a `COMPILATION_ERROR` it re-throws the collected diagnostics as a
 *    typed [BtaCompileDiagnosticException], which this adapter maps to
 *    [BtaCompileService.Outcome.CompileError] so the editor's existing compile-error banner renders
 *    the Kotlin source errors directly from stage 2. Any *other* throw (BTA bootstrap fault,
 *    missing JAR, file system error, or a `COMPILATION_ERROR` with no diagnostics captured) is
 *    treated as a transient runtime failure and downgraded to [BtaCompileService.Outcome.Fallback]
 *    so the daemon's stage-1 `gradle --continuous` worker picks up the save instead.
 *
 * The split between [backend] (a function reference) and [forSession] (the production factory
 * wrapping a [BtaCompileSession]) lets unit tests stub the compile behaviour without dragging in a
 * real BTA classloader — same idea KGP's tests use for their compilation work.
 */
class DefaultBtaCompileService(
  /**
   * The actual compile call. Production wiring captures a [BtaCompileSession] + the module's
   * resolved compile classpath + output dir + plugins; tests inject lambdas that model success /
   * diagnostic / throwing behaviour.
   */
  private val backend: CompileBackend,
  /** See class docs § "Eligibility gate". Null = eligible; non-null = always Fallback. */
  private val ineligibilityReason: String? = null,
) : BtaCompileService {

  /**
   * Bound compile call — what [DefaultBtaCompileService] actually invokes on each save. Throws on
   * any unrecoverable error; the service maps the throw to [BtaCompileService.Outcome.Fallback].
   */
  fun interface CompileBackend {
    fun compile(sources: List<Path>, sourcesChanges: SourcesChanges)
  }

  override fun compile(sources: List<Path>, changes: SourceChangeSet?): BtaCompileService.Outcome {
    ineligibilityReason?.let {
      return BtaCompileService.Outcome.Fallback(it)
    }
    val sourcesChanges =
      changes?.let { c ->
        SourcesChanges.Known(
          c.modified.map { java.io.File(it) },
          c.removed.map { java.io.File(it) },
        )
      } ?: SourcesChanges.ToBeCalculated
    return try {
      backend.compile(sources, sourcesChanges)
      BtaCompileService.Outcome.Ok
    } catch (diag: BtaCompileDiagnosticException) {
      // Production backend collected diagnostics via `DiagnosticCollector` and re-threw them
      // as a typed exception — surface them through the editor's existing compile-error
      // banner shape. Classloader is NOT swapped (the backend never wrote new .class files).
      BtaCompileService.Outcome.CompileError(diag.errors)
    } catch (t: Throwable) {
      // Unrecoverable runtime error (BTA bootstrap fault, missing JAR, file system error).
      // Fall back to stage 1 / 0; the editor retries through `gradleService.compileOnly`.
      BtaCompileService.Outcome.Fallback(
        "BTA compile threw: ${t.message ?: t.javaClass.simpleName}"
      )
    }
  }

  companion object {
    /**
     * System-property keys the gradle plugin's daemon-bootstrap task populates unconditionally
     * whenever the variant wiring resolved the BTA classpath. Mirror of `BtaCompileConfig` in the
     * launch descriptor; the sysprop names below are the wire format. All path-list sysprops are
     * `File.pathSeparator`-joined; an unset / empty value means "this part of the config is
     * missing" and [fromSysprops] returns null.
     */
    const val SYSPROP_IMPL_CLASSPATH: String = "composeai.daemon.bta.implClasspath"
    const val SYSPROP_COMPILE_CLASSPATH: String = "composeai.daemon.bta.compileClasspath"
    const val SYSPROP_COMPILER_PLUGINS: String = "composeai.daemon.bta.compilerPlugins"
    const val SYSPROP_MODULE_NAME: String = "composeai.daemon.bta.moduleName"
    const val SYSPROP_OUTPUT_DIR: String = "composeai.daemon.bta.outputDir"
    const val SYSPROP_IC_WORKING_DIR: String = "composeai.daemon.bta.icWorkingDir"
    /**
     * Optional. Non-empty value disables stage 2 for this module with the given reason surfaced
     * verbatim in every `compileSources` result.
     */
    const val SYSPROP_INELIGIBILITY_REASON: String = "composeai.daemon.bta.ineligibilityReason"

    /**
     * Production factory — captures the [session] + its per-module compile config in a
     * [CompileBackend] lambda and constructs the service.
     */
    fun forSession(
      session: BtaCompileSession,
      compileClasspath: List<Path>,
      outputDir: Path,
      compilerPlugins: List<CompilerPlugin>,
      ineligibilityReason: String? = null,
    ): DefaultBtaCompileService =
      DefaultBtaCompileService(
        backend =
          CompileBackend { sources, sourcesChanges ->
            // Per-call collector: BTA's `error(...)` callbacks land here and parse into
            // structured `CompileErrorDetail` entries. On `COMPILATION_ERROR` the session
            // throws an IllegalStateException; if the collector caught any diagnostics, we
            // re-throw as the typed [BtaCompileDiagnosticException] so the outer
            // `compile()` maps to `Outcome.CompileError`. Empty collector → original throw
            // propagates → outer maps to `Outcome.Fallback` (unrecoverable internal error).
            val collector = DiagnosticCollector()
            try {
              session.compileIncremental(
                sources = sources,
                compileClasspath = compileClasspath,
                outputDir = outputDir,
                compilerPlugins = compilerPlugins,
                sourcesChanges = sourcesChanges,
                diagnosticListener = collector,
              )
            } catch (t: Throwable) {
              if (collector.errors.isNotEmpty()) {
                throw BtaCompileDiagnosticException(collector.errors)
              }
              throw t
            }
          },
        ineligibilityReason = ineligibilityReason,
      )

    /**
     * Reads the BTA launch configuration from system properties and returns a wired-up service.
     * Returns `null` when stage 2 wasn't opted in (any of [SYSPROP_IMPL_CLASSPATH],
     * [SYSPROP_MODULE_NAME], [SYSPROP_OUTPUT_DIR], [SYSPROP_IC_WORKING_DIR] is missing or empty);
     * in that case [JsonRpcServer]'s `compileSources` handler returns `result=fallback` for every
     * call and the editor falls back to stage 1 / 0.
     *
     * Called once from each renderer module's `DaemonMain` at startup; the result is handed to
     * [JsonRpcServer]'s `btaCompileService` constructor slot. The factory does NOT eagerly
     * instantiate the [BtaCompileSession]'s `KotlinToolchains` — that happens lazily on first
     * compile, paying the ~5 s BTA-impl bootstrap once per daemon JVM.
     *
     * [sysprops] is the lookup function — defaults to [System.getProperty] for production; tests
     * pass a [Map.get]-shaped lambda so the factory can be exercised without polluting JVM-wide
     * state.
     */
    fun fromSysprops(
      sysprops: (String) -> String? = System::getProperty
    ): DefaultBtaCompileService? {
      val implClasspath = sysprops(SYSPROP_IMPL_CLASSPATH).toPathList()
      val moduleName = sysprops(SYSPROP_MODULE_NAME)?.takeIf { it.isNotEmpty() }
      val outputDirStr = sysprops(SYSPROP_OUTPUT_DIR)?.takeIf { it.isNotEmpty() }
      val icWorkingDirStr = sysprops(SYSPROP_IC_WORKING_DIR)?.takeIf { it.isNotEmpty() }
      if (
        implClasspath.isEmpty() ||
          moduleName == null ||
          outputDirStr == null ||
          icWorkingDirStr == null
      ) {
        return null
      }
      val compileClasspath = sysprops(SYSPROP_COMPILE_CLASSPATH).toPathList()
      val pluginJars = sysprops(SYSPROP_COMPILER_PLUGINS).toPathList()
      val ineligibilityReason = sysprops(SYSPROP_INELIGIBILITY_REASON)?.takeIf { it.isNotEmpty() }
      val compilerPlugins = composeCompilerPlugins(pluginJars)
      val session =
        BtaCompileSession(
          implClasspath = implClasspath,
          icWorkingDir = java.nio.file.Path.of(icWorkingDirStr),
          moduleName = moduleName,
        )
      return forSession(
        session = session,
        compileClasspath = compileClasspath,
        outputDir = java.nio.file.Path.of(outputDirStr),
        compilerPlugins = compilerPlugins,
        ineligibilityReason = ineligibilityReason,
      )
    }

    /**
     * Compose compiler plugin config for the in-process BTA compile. Mirrors what KGP's Compose
     * plugin hands to `compileKotlin`: the plugin id, the embeddable plugin JARs, and the
     * `sourceInformation=true` option.
     *
     * The option is load-bearing, not cosmetic. KGP enables `sourceInformation` by default and the
     * markers it emits (a ~236-byte delta per compiled file) are read by Compose Inspector / Live
     * Literals / recomposition tooling. Without it, stage-2-emitted classes drift from the
     * Gradle-emitted classes the daemon's hot-swap diffs against. Returns an empty list when no
     * plugin JARs were resolved (plain Kotlin/JVM module with no Compose plugin on the classpath).
     */
    internal fun composeCompilerPlugins(pluginJars: List<Path>): List<CompilerPlugin> =
      if (pluginJars.isEmpty()) emptyList()
      else
        listOf(
          CompilerPlugin(
            "androidx.compose.compiler.plugins.kotlin",
            pluginJars,
            listOf(CompilerPluginOption("sourceInformation", "true")),
            emptySet(),
          )
        )

    private fun String?.toPathList(): List<Path> =
      if (this.isNullOrEmpty()) emptyList()
      else
        this.split(java.io.File.pathSeparator)
          .filter { it.isNotEmpty() }
          .map { java.nio.file.Path.of(it) }
  }
}
