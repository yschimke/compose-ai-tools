package ee.schimke.composeai.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when an HTTP server engine reaches the runtime classpath of a project that is not one of the
 * two named exemptions.
 *
 * This is layer 1's own test, mechanised. `docs/design/REPOSITORY_LAYERS.md` places a module by
 * *does it need an HTTP server, a browser, or the UI builder to do its job?*, and compose-ai-tools
 * had no gate asserting the "no HTTP server" half of it — `checkRenderHostIsServerFree` makes that
 * claim for one module, and nothing made it for the rest.
 *
 * **The allowlist is empty, and that is what finishing #5176 looks like.** It held `:mcp` and the
 * `:cli` that bundled it while the MCP server was still here; both went when that module moved to
 * compose-preview-server and `compose-preview mcp serve` became a launcher over the published
 * binary. An empty positive allowlist means any HTTP server engine reaching any runtime classpath
 * in this build fails, with no exceptions to argue from — the same shape, and the same proof, as
 * [CheckLayerBoundary]'s allowlist emptying when the `serve` edge closed.
 *
 * **Resolved identity, not declared dependencies**, for the reason `:cli` proves: it declares no
 * `ktor-server-*` line at all — the five artifacts arrive through `:mcp` and the MCP Kotlin SDK.
 * Reading `dependencies {}` blocks would have found nothing to check, which is how "the Ktor floor
 * left with `serve`" survived as a claim until someone measured the built distribution.
 *
 * **Prefixes, not coordinates**, following `checkRenderHostIsServerFree`: the invariant is "no web
 * server", and an exact list of today's Ktor artifacts would pass the first time someone swaps CIO
 * for Netty or Jetty.
 *
 * Scope is [CheckLayerBoundary]'s: projects with a `runtimeClasspath`, so Android modules — which
 * resolve per-variant — are not covered. None of them serves anything.
 */
abstract class CheckHttpServerFloor : DefaultTask() {

  /** Every component on the resolved runtime classpath as `<group>:<name>`, transitives included. */
  @get:Input abstract val resolvedModules: SetProperty<String>

  /** Coordinate prefixes that identify an embeddable HTTP server engine. */
  @get:Input abstract val serverPrefixes: ListProperty<String>

  @TaskAction
  fun checkFloor() {
    val prefixes = serverPrefixes.get()
    val offenders =
      resolvedModules.get().filter { module -> prefixes.any { module.startsWith(it) } }.sorted()

    check(offenders.isEmpty()) {
      "An HTTP server engine reached ${path.substringBeforeLast(':')}'s runtime classpath: " +
        offenders.joinToString(", ") +
        ". compose-ai-tools is layer 1: a module that needs an HTTP server to do its job belongs " +
        "in compose-preview-server — see docs/design/REPOSITORY_LAYERS.md. There are no " +
        "exemptions; the last two went when the MCP server moved (#5176). If the new code serves " +
        "previews, a catalog, the UI builder or an agent, it belongs in that repository, and this " +
        "CLI launches it the way `serve` and `mcp serve` do."
    }
  }

  companion object {
    /**
     * The projects allowed a server engine on their runtime classpath: **none**.
     *
     * It held `:mcp` and `:cli` for exactly as long as the move took. `:mcp` is
     * compose-preview-server's now, `compose-preview mcp serve` execs the binary published from
     * there, and nothing in this repository binds a port. A new entry here is a diff someone has
     * to justify against the layer rule rather than a dependency that arrives.
     */
    val httpServerProjects: List<String> = emptyList()

    /**
     * What counts as a server. Ktor is what is here today; Jetty and Undertow are named because the
     * cheapest way for this floor to stop meaning anything is for the next embedded server to be a
     * different one. An HTTP *client* is deliberately absent — `:render-host` resolves the Ktor
     * client through `:bundle-coordinates` and opens no listening socket for it.
     */
    val serverPrefixes: List<String> =
      listOf("io.ktor:ktor-server", "org.eclipse.jetty:", "io.undertow:")
  }
}
