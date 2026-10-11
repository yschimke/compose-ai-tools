package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails the render when the runtime classpath doesn't transitively reach any supported `@Preview`
 * tooling. Registered only with `composePreview.failOnMissingPreviewTooling = true` and when the
 * direct scan found no tooling (e.g. `:composeApp -> :shared`, #241 / #1549). Opt-in because
 * aggregator modules legitimately have no previews.
 *
 * Checked at task time via a wired `Provider<ResolvedComponentResult>`
 * ([org.gradle.api.provider.Provider]): resolving at configuration time trips a configuration-cache
 * warning, and cross-project walks are banned under Isolated Projects.
 *
 * On failure, suggests: declaring a tooling coord directly; dropping `failOnMissingPreviewTooling`;
 * or `enforcePreviewToolingDependency = false`.
 */
@DisableCachingByDefault(
  because =
    "Check depends on live runtime-classpath resolution; caching the verdict across version bumps would silently stale-pass missing tooling."
)
abstract class ValidatePreviewToolingPresentTask : DefaultTask() {

  @get:Input abstract val modulePath: Property<String>

  @get:Internal abstract val runtimeClasspathRoot: Property<ResolvedComponentResult>

  init {
    group = "compose preview"
    description =
      "Validate that the consumer's runtime classpath transitively reaches a known @Preview tooling coordinate"
  }

  @TaskAction
  fun validate() {
    val root = runtimeClasspathRoot.orNull
    if (root == null) {
      // No runtime classpath on this variant (rare): no-op; the render task reports anything more
      // specific.
      return
    }
    if (containsPreviewTooling(root)) return
    throw GradleException(
      buildString {
        appendLine(
          "compose-preview: no @Preview tooling coord reachable in module '${modulePath.get()}'."
        )
        appendLine(
          "  The runtime classpath was resolved and none of the supported tooling artifacts are present:"
        )
        for ((g, n) in PREVIEW_TOOLING_COORDS) appendLine("    - $g:$n")
        appendLine("  Fix this by either:")
        appendLine(
          "  - declaring the tooling coord directly on this module (recommended — pins the"
        )
        appendLine("    version locally and makes it visible to `compose-preview doctor`):")
        appendLine("      implementation(\"androidx.compose.ui:ui-tooling-preview\")")
        appendLine("      // or, for Compose Multiplatform consumers:")
        appendLine(
          "      // implementation(\"org.jetbrains.compose.components:components-ui-tooling-preview\")"
        )
        appendLine(
          "  - dropping `composePreview { failOnMissingPreviewTooling = true }` so render no"
        )
        appendLine(
          "    longer hard-fails when this module's classpath lacks tooling (the default)."
        )
        appendLine(
          "  - or setting `composePreview { enforcePreviewToolingDependency = false }` to bypass"
        )
        appendLine("    the per-module gate entirely (issue #241 / #1549 escape hatch).")
      }
    )
  }

  internal companion object {
    /**
     * Coords that count as preview tooling; keep in lockstep with [AndroidPreviewSupport]'s
     * `previewArtifactSignals`.
     */
    internal val PREVIEW_TOOLING_COORDS: List<Pair<String, String>> =
      listOf(
        "androidx.compose.ui" to "ui-tooling-preview",
        "androidx.compose.ui" to "ui-tooling-preview-android",
        "androidx.wear.tiles" to "tiles-tooling-preview",
        "org.jetbrains.compose.components" to "components-ui-tooling-preview",
        "org.jetbrains.compose.ui" to "ui-tooling-preview",
      )

    /**
     * BFS (with cycle detection) over [root]'s graph for any [PREVIEW_TOOLING_COORDS] component.
     * Visible for tests.
     */
    internal fun containsPreviewTooling(root: ResolvedComponentResult): Boolean {
      val visited = HashSet<ResolvedComponentResult>()
      val queue = ArrayDeque<ResolvedComponentResult>()
      queue.add(root)
      while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (!visited.add(cur)) continue
        val id = cur.id
        if (id is ModuleComponentIdentifier) {
          val g = id.group
          val m = id.module
          if (PREVIEW_TOOLING_COORDS.any { (sg, sm) -> g == sg && m == sm }) return true
        }
        for (dep in cur.dependencies) {
          if (dep is org.gradle.api.artifacts.result.ResolvedDependencyResult) {
            queue.add(dep.selected)
          }
        }
      }
      return false
    }
  }
}
