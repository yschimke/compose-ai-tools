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
 * Fails the render path when a module's runtime classpath doesn't transitively reach any of the
 * supported `@Preview` tooling coords. Registered as a `dependsOn` of `composePreviewRender` only
 * when the consumer opts in via `composePreview.failOnMissingPreviewTooling = true` AND the
 * **direct** declared-deps scan didn't find preview tooling on this module (typically because a
 * sibling `project(":...")` dep is expected to contribute it — the CMP-Android `:composeApp ->
 * :shared` shape from issue #241 / #1549).
 *
 * **Opt-in by default.** Aggregator modules that pass the tier-2 over-approximation (Compose
 * plugin + project deps) without actually hosting `@Preview` functions don't need the hard fail;
 * `composePreviewDiscover` finding zero previews on them is the correct outcome. CI pipelines that
 * want fast-fail when a missing-tooling regression slips in on a multi-module app turn this on
 * explicitly via the DSL or `-PcomposePreview.failOnMissingPreviewTooling=true`.
 *
 * **Why a task-time check.** Resolving `${variant}RuntimeClasspath` at *configuration time* trips
 * the "Configuration was resolved during configuration time" CC warning (and the older
 * `findProject` / `evaluationDependsOn` cross-project walk was rejected outright under Isolated
 * Projects). Deferring the resolution to task execution via a wired
 * [Provider<ResolvedComponentResult>][org.gradle.api.provider.Provider] keeps both CC and IP happy
 * — the resolution result is captured as an `@Internal` property whose value materialises when the
 * task runs, and the configuration-cache-stored task graph references the Provider, not the
 * resolved snapshot itself.
 *
 * On no-tooling, throws with a remediation message pointing at the ways out:
 * 1. declare a preview-tooling coord directly on this module's `*Implementation` / `*Api` /
 *    `*RuntimeOnly` buckets,
 * 2. drop `composePreview.failOnMissingPreviewTooling` (or set it back to `false`) to let the
 *    render task proceed and surface whatever the actual failure mode is, or
 * 3. set `composePreview.enforcePreviewToolingDependency = false` to bypass the per-module tooling
 *    gate entirely (the issue #241 / #1549 escape hatch).
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
      // Variant doesn't expose a runtime classpath (rare — happens on
      // configuration-less utility variants). Silently no-op; the render task downstream will
      // surface a more specific error if there's actually nothing to render.
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
     * Maven coords whose presence in the resolved runtime graph counts as "this module can host
     * `@Preview` rendering." Mirrors `previewArtifactSignals` in [AndroidPreviewSupport]; the two
     * lists must stay in lockstep.
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
     * Walks [root]'s transitive `ResolvedComponentResult` graph (BFS with cycle detection) and
     * returns `true` if any reachable Maven-coord component matches [PREVIEW_TOOLING_COORDS].
     *
     * Visible for unit tests.
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
