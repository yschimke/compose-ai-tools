@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin

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
 *    stage-2 candidate (KSP/KAPT detected, AGP variant without resource-jar plumbing yet, etc. —
 *    see COMPILE-IN-PROCESS.md § "Eligibility"). Every compile call short-circuits to
 *    [BtaCompileService.Outcome.Fallback] with that reason verbatim. The gradle plugin decides the
 *    predicate at daemon-bootstrap time; the daemon never re-evaluates (Tier-1 dirty recycles the
 *    whole daemon, and with it this service).
 *
 * 2. **`SourceChangeSet` → BTA `SourcesChanges` translation.** Editor-supplied known dirty sets
 *    become `SourcesChanges.Known`; null becomes `SourcesChanges.ToBeCalculated` (BTA inspects file
 *    timestamps against its IC cache). Same shape KGP uses.
 *
 * 3. **Exception → Fallback mapping.** Any throw from [backend] is treated as a transient runtime
 *    failure (typically: BTA bootstrap fault, missing JAR, file system error) and downgraded to
 *    [BtaCompileService.Outcome.Fallback]. The daemon's stage-1 `gradle --continuous` worker picks
 *    up the save instead. Diagnostic-bearing compile failures (Kotlin source errors) are not yet
 *    surfaced as [BtaCompileService.Outcome.CompileError] from here — that requires a
 *    `KotlinLogger`-backed diagnostic collector wired through the session, tracked as a follow-up.
 *    Until then, a compile-error save falls through to stage 1 which still surfaces the diagnostics
 *    via `KotlinCompileErrorDetector`. Lossy but not silent.
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
    } catch (t: Throwable) {
      // TODO: parse diagnostic-bearing compile failures into Outcome.CompileError via a
      // `KotlinLogger`-backed collector on the session. Until then a compile-error save
      // falls through to stage 1, which still surfaces diagnostics through the existing
      // `KotlinCompileErrorDetector` path. Tracked in COMPILE-IN-PROCESS.md follow-ups.
      BtaCompileService.Outcome.Fallback(
        "BTA compile threw: ${t.message ?: t.javaClass.simpleName}"
      )
    }
  }

  companion object {
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
            session.compileIncremental(
              sources = sources,
              compileClasspath = compileClasspath,
              outputDir = outputDir,
              compilerPlugins = compilerPlugins,
              sourcesChanges = sourcesChanges,
            )
          },
        ineligibilityReason = ineligibilityReason,
      )
  }
}
