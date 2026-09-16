package ee.schimke.composeai.buildlogic

/**
 * The coordinate a project publishes as, derived from where it sits in a build.
 *
 * Almost everywhere this is just the project path with the separators flattened — `:render:host`
 * publishes as `render-host` — and `PublishedArtifactIdTest` pins that against the build files so
 * it cannot quietly stop being true.
 *
 * The `gradle-plugin` included build is where it stops being true, in two places, and both of them
 * mattered only once a release started publishing a *subset* of the modules (#5483). Before that
 * every module took the tag's version and nothing ever asked what a project's artifact id was;
 * since then [PublishedVersions.resolve] looks the id up in the publish set, and an id that does
 * not match the one the module actually declares is not in it:
 *
 *  * the included build's **root** project declares `compose-preview-plugin`, and its path is `:`,
 *    which flattens to the empty string — the failure that killed the v2.18.0 release job with
 *    `" is not in the publish set"`, leading space and all; and
 *  * `:gradle-plugin-config` declares `compose-preview-config`.
 *
 * So the exceptions are listed, once, here. They are keyed by root project name as well as path
 * because an included build's paths are relative to its own root: `:` in the main build is a
 * different project from `:` in `gradle-plugin`, and only the latter publishes.
 *
 * A pure function over (root project name, path) rather than a `Project` extension so
 * `PublishedArtifactIdsTest` can exercise every case without standing a build up.
 */
object PublishedArtifactIds {

  /** Paths in the `gradle-plugin` included build whose coordinate is not their flattened path. */
  private val GRADLE_PLUGIN_BUILD =
    mapOf(":" to "compose-preview-plugin", ":gradle-plugin-config" to "compose-preview-config")

  /** `rootProject.name` of the included build the exceptions above belong to. */
  private const val GRADLE_PLUGIN_BUILD_NAME = "gradle-plugin"

  fun forProject(rootProjectName: String, path: String): String =
    if (rootProjectName == GRADLE_PLUGIN_BUILD_NAME) {
      GRADLE_PLUGIN_BUILD[path] ?: flatten(path)
    } else {
      flatten(path)
    }

  private fun flatten(path: String): String = path.removePrefix(":").replace(':', '-')
}
