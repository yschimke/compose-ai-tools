package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.nio.file.Path

/**
 * Thin abstraction the `JsonRpcServer.compileSources` handler dispatches through. Lets the server
 * stay independent of how the BTA host is wired up — `:daemon:desktop` and `:daemon:android` each
 * provide a concrete `DefaultBtaCompileService` that knows its variant's compile classpath, output
 * directory, and Compose plugin coordinates. In-process integration tests pass null and the handler
 * returns [Outcome.Fallback].
 *
 * The interface is intentionally narrow — sources in, outcome out, the daemon swaps classloaders on
 * success via the host's existing `swapUserClassLoaders()` path. Anything richer (file watchers,
 * incremental diff serialization, plugin option negotiation) is left to the implementation and
 * never leaks into the JSON-RPC surface.
 *
 * Lifetime: one instance per daemon JVM. Constructed at startup if the launch descriptor's
 * `btaCompilerClasspath` is non-null; never reconstructed (daemon recycles on classpath-dirty, Tier
 * 1).
 */
interface BtaCompileService {

  /**
   * Compile [sources] in-process. Implementations are responsible for translating BTA's
   * `CompilationResult.COMPILER_INTERNAL_ERROR` / `COMPILATION_ERROR` into [Outcome.CompileError]
   * (with diagnostics) or [Outcome.Fallback] (with a human-readable reason). Implementations should
   * NOT throw — the handler treats any thrown exception as "fallback with the exception message as
   * reason" and logs.
   */
  fun compile(sources: List<Path>, changes: SourceChangeSet?): Outcome

  sealed class Outcome {
    /**
     * Compile succeeded; `.class` files are on disk and the caller should swap the user
     * classloader.
     */
    object Ok : Outcome()

    /**
     * BTA returned diagnostics that the editor can render in its existing compile-error banner. The
     * classloader was NOT swapped.
     */
    data class CompileError(val errors: List<CompileErrorDetail>) : Outcome()

    /**
     * Implementation refused this compile and the caller should retry through stage 1 / 0. Common
     * reasons: KSP/KAPT-tainted classpath, BTA bootstrap failure, transient classpath-dirty between
     * session construction and this call.
     */
    data class Fallback(val reason: String) : Outcome()
  }
}
