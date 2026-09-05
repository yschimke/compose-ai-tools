package ee.schimke.composeai.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module from a strictly higher layer reaches this project's runtime classpath.
 *
 * The layer rule lives in `docs/design/REPOSITORY_LAYERS.md`: contracts is shape, this repository
 * is offline behaviour, compose-preview-server is HTTP and the surfaces over it, and a dependency
 * may only point down. compose-preview-server has enforced its own floor since the split
 * (`checkServeModuleBoundary`); this repository enforced nothing at all, so the edge that created
 * yschimke/compose-preview-server#180 could be re-added by one `api(...)` line with CI green.
 *
 * **A positive allowlist, not a list of banned coordinates**, for the reason the server's task
 * learned the hard way: a denylist names the artifacts that exist today and says nothing about the
 * next one. Any `ee.schimke.composeai:compose-preview-*` coordinate that is not named below is a
 * failure, including one that arrives transitively.
 *
 * **Resolved identity, not declared dependencies.** A layer-2 artifact pulled in through another
 * dependency's POM is exactly the case a scan of build files misses, and exactly the case that
 * matters — `:cli` resolves `compose-preview-render-host` transitively through
 * `compose-preview-serve` as well as directly.
 *
 * Scope, stated because it is not total: this runs on projects with a `runtimeClasspath`, which is
 * every JVM module. Android modules resolve per-variant classpaths (`debugRuntimeClasspath` and
 * friends) and are not covered. That is a real gap rather than a claim that they are safe — it is
 * narrow because no Android module here consumes a server artifact, and widening it means paying
 * to resolve every variant on `check`.
 */
abstract class CheckLayerBoundary : DefaultTask() {

  /**
   * Every component on the resolved runtime classpath as a `<group>:<name>` string, transitives
   * included.
   */
  @get:Input abstract val resolvedModules: SetProperty<String>

  /** The `compose-preview-*` coordinates permitted on any classpath in this build. */
  @get:Input abstract val allowedPreviewModules: SetProperty<String>

  @TaskAction
  fun checkBoundary() {
    val hits =
      resolvedModules
        .get()
        .filter { it.startsWith("$COMPOSE_AI_GROUP:$PREVIEW_PREFIX") }
        .filterNot { it in allowedPreviewModules.get() }
        .sorted()

    check(hits.isEmpty()) {
      "A compose-preview-server artifact reached ${path.substringBeforeLast(':')}'s runtime " +
        "classpath. compose-ai-tools is layer 1 and the server is layer 2, so this dependency " +
        "points the wrong way — see docs/design/REPOSITORY_LAYERS.md. Found: " +
        hits.joinToString(", ") +
        ". If the coordinate is published by this repository, add it to `ownPreviewModules`; if it " +
        "is a new edge into the server, it needs the layer rule changed first."
    }
  }

  companion object {
    const val COMPOSE_AI_GROUP: String = "ee.schimke.composeai"

    private const val PREVIEW_PREFIX = "compose-preview-"

    /**
     * `compose-preview-*` coordinates this repository publishes itself. Same-layer, so they are not
     * the rule's target; they share the prefix only because the prefix names the product, not the
     * repository.
     */
    val ownPreviewModules: List<String> =
      listOf("$COMPOSE_AI_GROUP:compose-preview-config", "$COMPOSE_AI_GROUP:compose-preview-plugin")

    /**
     * The layer-2 coordinates `:cli` resolves today, allowed under protest.
     *
     * These **are** the cycle. `compose-preview-render-host` is offline behaviour filed in the
     * wrong repository (step 2 of #180's order moves it here); `compose-preview-serve` is reached
     * from `ServeCommand.kt` alone and goes when `serve` becomes a launcher over the published
     * binary (steps 3-4). This list shrinks to empty as those land, and the gate's value in the
     * meantime is that it is exactly three entries long: a fourth cannot be added by accident.
     *
     * `compose-preview-ui-builder-runtime` is the one nobody had counted, and it is why this task
     * reads resolved identity rather than build files. It is declared **nowhere** in this
     * repository — no `cli/build.gradle.kts` line, no version-catalog alias. It arrives because
     * the server publishes its three modules in lockstep and `compose-preview-serve`'s POM names
     * all of them, so depending on the server drags the UI-builder runtime onto the CLI's runtime
     * classpath. The published analysis on #180 counts two coordinates crossing this edge; there
     * are three, and it goes when `compose-preview-serve` does.
     */
    val knownLayerTwoEdges: List<String> =
      listOf(
        "$COMPOSE_AI_GROUP:compose-preview-render-host",
        "$COMPOSE_AI_GROUP:compose-preview-serve",
        "$COMPOSE_AI_GROUP:compose-preview-ui-builder-runtime",
      )
  }
}
