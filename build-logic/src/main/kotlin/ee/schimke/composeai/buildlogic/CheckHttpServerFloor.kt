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
 * The exemptions are `:mcp` and the `:cli` that bundles it, and they are temporary: #5176 decided
 * that `:mcp` is layer 2 by the letter of the rule and is moving to compose-preview-server, rather
 * than the rule growing a carve-out for it. Until that move lands the two projects genuinely carry
 * five `ktor-server-*` artifacts, so the honest gate is a two-entry allowlist that goes to empty
 * when `:mcp` leaves — the same shape as [CheckLayerBoundary]'s allowlist, which emptied when the
 * `serve` edge closed. A third entry is a diff someone has to justify.
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
        "in compose-preview-server — see docs/design/REPOSITORY_LAYERS.md. The only exemptions " +
        "are ${httpServerProjects.joinToString(", ")}, and they are the MCP server on its way out " +
        "of this repository (#5176), not a precedent."
    }
  }

  companion object {
    /**
     * The projects allowed a server engine on their runtime classpath, and the measure of #5176's
     * completion: when `:mcp` moves to compose-preview-server this list is empty.
     *
     * `:mcp` runs the Streamable HTTP endpoint the MCP SDK's protocol routes sit on, and `:cli`
     * bundles `:mcp` so `compose-preview mcp serve` starts it in-process. Nothing else depends on
     * `:mcp`; `:render-session-subprocess` used to, which is why `DaemonClient` was lifted into
     * `:daemon-client` (#3824), and `:cli`'s offline `render-matrix` command did, which is why
     * `MatrixAxes` and `ContactSheet` were lifted into `:render-matrix`.
     */
    val httpServerProjects: List<String> = listOf(":mcp", ":cli")

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
