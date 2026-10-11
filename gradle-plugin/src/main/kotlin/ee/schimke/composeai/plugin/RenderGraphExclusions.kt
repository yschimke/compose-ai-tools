package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Applies `composePreview { renderGraph { exclude(…) } }` (and
 * `-PcomposePreview.renderGraphExcludes=…`) to the plugin's own render configurations; see
 * [RenderGraphExtension] for why. One shared path so the Android render config, its daemon superset
 * and the desktop configs get the SAME graph; otherwise one-shot and daemon render off different
 * classpaths.
 *
 * Exclusions are config-wide on OUR configurations only, never on the consumer's inherited
 * `…UnitTestRuntimeClasspath`. Unlike the dependency-scoped Rule-3 excludes in
 * [AndroidPreviewSupport.addRenderGraphDependency], the module a consumer excludes is usually one
 * they contribute via `extendsFrom`, which only config-wide scope reaches.
 */
internal object RenderGraphExclusions {

  /**
   * Adds [exclusions] to [configuration]; idempotent (Gradle stores excludes in a set). Logged at
   * `info` at configuration time (a resolve hook would capture [project] and break the
   * configuration cache) so `--info` distinguishes an unresolvable render config from a bad
   * exclusion's `ClassNotFoundException`.
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
