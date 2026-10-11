package ee.schimke.composeai.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module from a strictly higher layer reaches this project's runtime classpath
 * (`docs/design/REPOSITORY_LAYERS.md`: dependencies may only point down).
 *
 * A positive allowlist: any `ee.schimke.composeai:compose-preview-*` coordinate not named below
 * fails, including transitive ones, since resolved identity is checked rather than declared
 * dependencies.
 *
 * Covers projects with a `runtimeClasspath` (every JVM module). Android per-variant classpaths are
 * not covered — a known gap, narrow because no Android module consumes a server artifact.
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
     * `compose-preview-*` coordinates this repository publishes itself; same layer, sharing the
     * product prefix.
     */
    val ownPreviewModules: List<String> =
      listOf("$COMPOSE_AI_GROUP:compose-preview-config", "$COMPOSE_AI_GROUP:compose-preview-plugin")

    /**
     * Layer-2 coordinates allowed on a runtime classpath here: none, so any server coordinate
     * reaching a runtime classpath fails.
     *
     * `compose-preview-serve` remains a `testImplementation` of `:cli` (two tests check wire types
     * against a real `ServeHttpServer`); this task reads `runtimeClasspath` and deliberately
     * ignores that, since the claim is about what ships.
     */
    val knownLayerTwoEdges: List<String> = emptyList()
  }
}
