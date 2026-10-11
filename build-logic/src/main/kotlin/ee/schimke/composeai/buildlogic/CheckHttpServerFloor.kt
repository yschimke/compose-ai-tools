package ee.schimke.composeai.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when an HTTP server engine reaches the runtime classpath of a project not in
 * [httpServerProjects] — layer 1's "no HTTP server" rule from `docs/design/REPOSITORY_LAYERS.md`,
 * mechanised.
 *
 * Checks resolved identity, not declared dependencies (server engines arrive transitively), and
 * matches prefixes rather than exact coordinates, so swapping CIO for Netty or Jetty still fails.
 * Scope matches [CheckLayerBoundary]: Android per-variant classpaths aren't covered.
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
     * The projects allowed a server engine on their runtime classpath: none. A new entry must be
     * justified against the layer rule.
     */
    val httpServerProjects: List<String> = emptyList()

    /**
     * What counts as a server: Ktor today, plus Jetty and Undertow so a different embedded server
     * can't slip past. HTTP clients are deliberately excluded.
     */
    val serverPrefixes: List<String> =
      listOf("io.ktor:ktor-server", "org.eclipse.jetty:", "io.undertow:")
  }
}
