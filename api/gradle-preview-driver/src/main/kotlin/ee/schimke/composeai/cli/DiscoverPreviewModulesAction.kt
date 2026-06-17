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
 * that did resolve. The skipped projects are *not* discarded silently: each failure's path and
 * message are collected into [PreviewModuleDiscoveryResult.failures] so the CLI can tell the user
 * *which* modules failed to configure and *why* when discovery comes back empty (issue #3 —
 * "discovery finds 0 modules" with no diagnostic was undebuggable because every per-project
 * exception was swallowed here).
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
          // A configuration failure in an unrelated module (or a Gradle version whose model
          // proxy can't be built) shouldn't sink the whole discovery — drop this project and
          // keep going. See issue #1620's "isolate failures per module" point. We record the
          // failure rather than discarding it so the CLI can surface it (issue #3).
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
   * Flattens a throwable (and its cause chain) into a one-line, daemon-safe message. We can't ship
   * the [Throwable] itself across the Tooling-API boundary (arbitrary exception types from the
   * configured build may not be on the CLI's classpath), so collapse it to text here. The cause
   * chain matters: Gradle wraps the actionable failure ("Plugin ... already on the classpath with
   * an unknown version") several layers deep behind generic configuration exceptions.
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

/**
 * Result of [DiscoverPreviewModulesAction]: the modules that resolved plus the per-project failures
 * encountered while walking the build. Serialized across the Tooling-API daemon boundary, so both
 * fields are plain serializable values.
 */
data class PreviewModuleDiscoveryResult(
  val modules: ArrayList<PreviewModule>,
  val failures: ArrayList<ProjectDiscoveryFailure>,
) : Serializable

/** A project that threw while its [ComposePreviewModel] was being built during discovery. */
data class ProjectDiscoveryFailure(val path: String, val message: String) : Serializable
