package ee.schimke.composeai.previewdriver

import ee.schimke.composeai.plugin.tooling.ComposePreviewModel
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.Serializable
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.GradleBuild

/**
 * Discovers every subproject that applies `ee.schimke.composeai.preview` without realizing the task
 * graph.
 *
 * The Tooling-API `GradleProject` model realizes every task provider in every project, firing
 * unrelated configuration side effects (e.g. toolchain provisioning) just to list previews. This
 * walks the lightweight [GradleBuild] model instead and asks each project for its
 * [ComposePreviewModel], whose builder realizes only `composePreviewDiscover`. Cross-project-safe
 * under Isolated Projects, like [GatherComposePreviewModelAction].
 *
 * Per-project failures are isolated: a module that fails to configure is skipped, and its path and
 * message go into [PreviewModuleDiscoveryResult.failures] so an empty discovery can be explained.
 */
class DiscoverPreviewModulesAction : BuildAction<PreviewModuleDiscoveryResult>, Serializable {
  override fun execute(controller: BuildController): PreviewModuleDiscoveryResult {
    val build = controller.getModel(GradleBuild::class.java)
    val modules = ArrayList<PreviewModule>()
    val failures = ArrayList<ProjectDiscoveryFailure>()
    for (project in build.projects) {
      val model =
        try {
          controller.findModel(project, ComposePreviewModel::class.java)
        } catch (t: Throwable) {
          // An unrelated module's configuration failure shouldn't sink discovery; record it and
          // continue.
          failures.add(ProjectDiscoveryFailure(project.path, describeFailure(t)))
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
    return PreviewModuleDiscoveryResult(modules, failures)
  }

  /**
   * Flattens a throwable and its cause chain into one line: the throwable itself can't cross the
   * Tooling-API boundary, and Gradle buries the actionable cause several layers deep.
   */
  private fun describeFailure(t: Throwable): String {
    val parts = LinkedHashSet<String>()
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 10) {
      val message = current.message?.trim()
      parts.add(
        if (message.isNullOrEmpty()) current.javaClass.name
        else "${current.javaClass.simpleName}: $message"
      )
      current = current.cause
      depth++
    }
    return parts.joinToString(" -> ")
  }
}

/** Lightweight path-to-directory map for every project in the build. */
class DiscoverGradleProjectsAction : BuildAction<ArrayList<PreviewModule>>, Serializable {
  override fun execute(controller: BuildController): ArrayList<PreviewModule> {
    val projects = ArrayList<PreviewModule>()
    for (project in controller.getModel(GradleBuild::class.java).projects) {
      val path = project.path.removePrefix(":")
      if (path.isNotEmpty()) {
        projects.add(PreviewModule(gradlePath = path, projectDir = project.projectDirectory))
      }
    }
    return projects
  }
}

/**
 * Result of [DiscoverPreviewModulesAction]: resolved modules plus per-project failures, as plain
 * serializable values for the Tooling-API boundary.
 */
data class PreviewModuleDiscoveryResult(
  val modules: ArrayList<PreviewModule>,
  val failures: ArrayList<ProjectDiscoveryFailure>,
) : Serializable

/** A project that threw while its [ComposePreviewModel] was being built during discovery. */
data class ProjectDiscoveryFailure(val path: String, val message: String) : Serializable
