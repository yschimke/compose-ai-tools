package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Applies `composePreview { renderGraph { exclude(…) } }` (and its
 * `-PcomposePreview.renderGraphExcludes=…` twin) to the configurations the plugin creates for
 * resolving the renderer.
 *
 * Why the DSL exists at all, and why the render configurations inherit the consumer's graph in the
 * first place, is on [RenderGraphExtension]. This object is only the wiring: one place that every
 * render configuration goes through, so the Android render config, its daemon superset and the two
 * desktop configurations cannot drift apart the way they would if each call site spelled the
 * exclusions out. A consumer excluding a module must get the SAME graph in the one-shot render task
 * and in the daemon, or the two JVMs render the same previews off different classpaths.
 *
 * Exclusions land on OUR configurations only — never on the consumer's `…UnitTestRuntimeClasspath`
 * that we `extendsFrom`, which stays exactly as their own build resolves it.
 *
 * Note the deliberate contrast with [AndroidPreviewSupport.addRenderGraphDependency], which carries
 * the plugin's OWN Rule-3 exclusions on individual dependencies precisely so they cannot reach the
 * inherited consumer subtree. That restraint is right for a rule the plugin invents; it would be
 * wrong here. A consumer writing `renderGraph { exclude(…) }` is asking for exactly the config-wide
 * scope — the module they need gone is one THEY contribute, inherited through `extendsFrom`, and a
 * dependency-scoped exclude could never reach it.
 */
internal object RenderGraphExclusions {

  /**
   * Adds [exclusions] to [configuration]. Safe to call repeatedly: Gradle stores exclude rules in a
   * set, and `maybeCreate` plus a second registration pass can reach the same configuration twice.
   *
   * Logged at `info` at configuration time (not from a resolve hook, which would capture [project]
   * into an execution-time callback and break the configuration cache), because the failure this
   * feature exists to fix — an unresolvable render configuration — and the failure a wrong
   * exclusion causes — a `ClassNotFoundException` on the render JVM — look nothing alike, and
   * `--info` is where a consumer checks which of the two they are looking at.
   */
  fun applyTo(
    project: Project,
    configuration: Configuration,
    exclusions: List<RenderGraphExclusion>,
  ) {
    if (exclusions.isEmpty()) return
    exclusions.forEach { exclusion ->
      configuration.exclude(
        buildMap {
          exclusion.group?.let { put("group", it) }
          exclusion.module?.let { put("module", it) }
        }
      )
    }
    project.logger.info(
      "compose-preview: excluding ${exclusions.joinToString()} from the render graph " +
        "(configuration '${configuration.name}', via composePreview.renderGraph)"
    )
  }
}
