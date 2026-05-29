package ee.schimke.composeai.cli

import ee.schimke.composeai.plugin.tooling.ComposePreviewModel
import java.io.Serializable
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.GradleBuild

/**
 * Discovers every subproject that applies `ee.schimke.composeai.preview`, *without* realizing the
 * build's task graph.
 *
 * Why this exists (issue #1620): the previous discovery path fetched the Tooling-API
 * `GradleProject` model and read `project.tasks` to spot the `composePreviewDiscover` task.
 * Building `GradleProject` eagerly realizes **every task provider in every project** — so any
 * configuration-time side effect in an unrelated module (a `nativeCompile` task pinning a Java
 * toolchain, say) fired during preview discovery, forcing an expensive/failing toolchain provision
 * just to *list* previews.
 *
 * This action instead walks the lightweight [GradleBuild] model — whose `BasicGradleProject`s carry
 * only `path` + `projectDirectory`, no tasks — and asks each project for the [ComposePreviewModel].
 * That model's builder detects the plugin with a single targeted
 * `tasks.findByName("composePreviewDiscover")`, which realizes only that one named task (returning
 * `null` for projects that don't have it) rather than the whole graph. Unrelated modules' eager
 * tasks are never realized.
 *
 * Runs inside the Gradle daemon (serialised by the Tooling API). Mirrors the per-project
 * `findModel` walk in [GatherComposePreviewModelAction]; aggregating here is cross-project-safe
 * under Isolated Projects, where a single root-scoped builder couldn't poke at subprojects.
 *
 * Per-project failure isolation: each `findModel` is wrapped so a configuration failure in a module
 * that contributes no previews is skipped, not propagated — discovery still returns the modules
 * that did resolve.
 */
class DiscoverPreviewModulesAction : BuildAction<ArrayList<PreviewModule>>, Serializable {
  override fun execute(controller: BuildController): ArrayList<PreviewModule> {
    val build = controller.getModel(GradleBuild::class.java)
    val modules = ArrayList<PreviewModule>()
    for (project in build.projects) {
      val model =
        try {
          controller.findModel(project, ComposePreviewModel::class.java)
        } catch (_: Throwable) {
          // A configuration failure in an unrelated module (or a Gradle version whose model
          // proxy can't be built) shouldn't sink the whole discovery — drop this project and
          // keep going. See issue #1620's "isolate failures per module" point.
          null
        } ?: continue
      // The model builder keys `modules` by the project path only when the plugin is applied to
      // *that* project, so presence of this project's path is the plugin-applied signal.
      if (model.modules.containsKey(project.path)) {
        val path = project.path.removePrefix(":")
        if (path.isNotEmpty()) {
          modules.add(PreviewModule(gradlePath = path, projectDir = project.projectDirectory))
        }
      }
    }
    return modules
  }
}
