package ee.schimke.composeai.plugin

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.problems.Problems
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Validates the Compose line selected for an externally managed Android render graph.
 *
 * The check deliberately consumes a [ResolvedComponentResult], rather than inspecting
 * `ResolutionStrategy.eachDependency`'s `requested` selector. The latter is one original edge in
 * the graph; platforms, constraints, and conflict resolution can select a different version for the
 * module. Resolving lazily from a task also avoids resolving the render configuration during
 * project configuration, which would break configuration-cache and Isolated Projects consumers.
 */
@DisableCachingByDefault(
  because =
    "Check depends on live render-classpath resolution; caching could stale-pass a dependency version change.",
)
abstract class ValidateComposeFloorTask : DefaultTask() {

  @get:Inject abstract val problems: Problems

  @get:Internal abstract val runtimeClasspathRoot: Property<ResolvedComponentResult>

  init {
    group = "compose preview"
    description = "Validate the resolved Compose version used by an externally managed render"
  }

  @TaskAction
  fun validate() {
    val belowFloor = findBelowFloor(runtimeClasspathRoot.get()) ?: return
    val message =
      AndroidPreviewSupport.composeFloorOptOutMessage(belowFloor.module, belowFloor.version)
    val failure = GradleException(message)
    ComposePreviewProblems.reportValidationFailure(
      problems = problems,
      id = "compose-below-floor",
      displayName = "Compose version below renderer floor",
      contextualLabel =
        "${belowFloor.module}:${belowFloor.version} is below the preview renderer's Compose floor",
      details = message,
      solutions =
        listOf(
          "Upgrade the Compose BOM / ${belowFloor.module} to meet the renderer floor.",
          "Or let compose-ai-tools manage the render-classpath Compose floor automatically.",
        ),
      exception = failure,
    )
    throw failure
  }

  internal data class ResolvedComposeModule(val module: String, val version: String)

  internal companion object {
    /** Returns the first selected Compose module below the renderer's link floor, if any. */
    internal fun findBelowFloor(root: ResolvedComponentResult): ResolvedComposeModule? {
      val belowFloor = mutableListOf<ResolvedComposeModule>()
      val visited = HashSet<ResolvedComponentResult>()
      val queue = ArrayDeque<ResolvedComponentResult>()
      queue.add(root)
      while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!visited.add(component)) continue
        val id = component.id
        if (
          id is ModuleComponentIdentifier &&
            AndroidPreviewSupport.composeLineFloorUpgrade(id.group, id.version) != null
        ) {
          belowFloor += ResolvedComposeModule("${id.group}:${id.module}", id.version)
        }
        for (dependency in component.dependencies) {
          if (dependency is ResolvedDependencyResult) queue.add(dependency.selected)
        }
      }
      return belowFloor.minWithOrNull(compareBy({ it.module }, { it.version }))
    }
  }
}
