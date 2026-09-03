package ee.schimke.composeai.cli

/**
 * Recognises the one Gradle failure that is never the consumer's fault — **the compose-preview
 * plugin marker cannot be resolved at the version this run injected** — and says so, instead of
 * letting it arrive as a configuration failure in the user's own project (issue #5034).
 *
 * # The failure
 *
 * ```
 * :: ProjectConfigurationException: A problem occurred configuring root project '…'.
 *    -> Could not resolve all dependencies for configuration 'classpath'.
 *    -> Could not find ee.schimke.composeai.preview:
 *       ee.schimke.composeai.preview.gradle.plugin:1.66.1
 * ```
 *
 * # Why it is worth a message of its own
 * A release makes the CLI available before the plugin: the GitHub release the `install` action
 * resolves `latest` from goes public minutes — sometimes tens of minutes — before
 * `ee.schimke.composeai.preview` is resolvable from plugins.gradle.org and Maven Central. The
 * release workflow's `maven-readiness` gate exists to close that window, and issue #5029 widened
 * its budget; this message covers what any gate leaves over, and the equivalent case with no gate
 * at all — a pin or `--plugin-version` naming a version that was never published.
 *
 * The cost of *not* saying it is measured: three release rounds' worth of imports failed this way,
 * and each was diagnosed as a problem with the imported project, because that is what the text
 * says. Nothing in it points at the CLI, and nothing suggests "come back in five minutes".
 *
 * Matching is deliberately narrow — the plugin's own coordinate, a *missing-artifact* verb, no
 * transport or repository-configuration cause, and (when the caller knows what it injected) that
 * exact version — so an unrelated dependency failure in the user's build never draws this
 * explanation. See [isUnresolvedPluginMarkerFailure].
 */
internal const val COMPOSE_PREVIEW_PLUGIN_ID = "ee.schimke.composeai.preview"

/**
 * The Maven coordinate Gradle resolves a `plugins { id(…) }` / buildscript classpath entry from.
 */
internal const val COMPOSE_PREVIEW_PLUGIN_MARKER =
  "$COMPOSE_PREVIEW_PLUGIN_ID:$COMPOSE_PREVIEW_PLUGIN_ID.gradle.plugin"

/**
 * True when [message] is Gradle reporting our plugin marker **missing from every repository it
 * searched** — the shape a not-yet-published version takes.
 *
 * Deliberately narrow, in three ways:
 * - the marker module must appear, so a failure that merely mentions the plugin id doesn't match;
 * - the verb must be a *missing-artifact* one. Gradle says `Could not find <coordinate>` for
 *   dependency resolution and `could not resolve plugin artifact '<coordinate>'` for the plugins
 *   DSL. The generic `Could not resolve` / `Cannot resolve external dependency` forms are **not**
 *   accepted: they are also what an unreachable proxy, a TLS failure or a build with no
 *   repositories declared produces, and telling someone in that state to wait for a publication
 *   sends them the wrong way (and, in the discovery path, hides the other projects' failures);
 * - and when [version] is given, that version must be the one in the coordinate — which is what
 *   makes this "the release you just resolved isn't published yet" rather than "some
 *   compose-preview version, somewhere, didn't resolve".
 *
 * [TRANSPORT_CAUSES] backs the second point up: a message carrying the marker *and* a
 * missing-artifact verb but also naming a repository-configuration or transport cause is left
 * alone, because that cause is the real one.
 */
internal fun isUnresolvedPluginMarkerFailure(message: String, version: String? = null): Boolean {
  if (!message.contains(COMPOSE_PREVIEW_PLUGIN_MARKER)) return false
  val missingArtifact =
    message.contains("Could not find") ||
      message.contains("could not resolve plugin artifact", ignoreCase = true)
  if (!missingArtifact) return false
  if (TRANSPORT_CAUSES.any { message.contains(it, ignoreCase = true) }) return false
  return version == null || message.contains("$COMPOSE_PREVIEW_PLUGIN_MARKER:$version")
}

/**
 * Phrases that identify a resolution failure as a repository-configuration or transport problem
 * rather than an absent artifact. Gradle can name the marker in any of these, and none of them is
 * fixed by waiting for a publication.
 */
private val TRANSPORT_CAUSES =
  listOf(
    "no repositories are defined",
    "Connection refused",
    "Connection reset",
    "UnknownHostException",
    "SSLHandshakeException",
    "certificate",
    "Read timed out",
    "connect timed out",
    "407 Proxy",
    "401 Unauthorized",
    "403 Forbidden",
  )

/**
 * The message to print for [isUnresolvedPluginMarkerFailure], naming the version, where it came
 * from, and the three things that actually resolve it. [origin] is a short phrase for why *this*
 * version is in play ("pinned by gradle.properties (…)"), omitted when the caller can't say.
 */
internal fun unresolvedPluginMarkerGuidance(version: String, origin: String? = null): String {
  val attribution = origin?.let { " ($it)" }.orEmpty()
  return buildString {
    appendLine(
      "compose-preview: Gradle could not resolve the compose-preview Gradle plugin " +
        "$COMPOSE_PREVIEW_PLUGIN_MARKER:$version$attribution. This is a problem with that " +
        "version's availability, not with your project — the configuration failure above is the " +
        "symptom."
    )
    appendLine()
    appendLine(
      "The usual cause is the publication window: a compose-preview release is downloadable as a " +
        "CLI (and resolvable as `latest`) before the Gradle plugin of the same version is " +
        "resolvable from plugins.gradle.org and Maven Central. Builds dispatched inside that " +
        "window fail exactly like this, and it clears on its own."
    )
    appendLine()
    appendLine("Options:")
    appendLine("  * wait for the publication to land and re-run — nothing to change;")
    appendLine(
      "  * pin the previous release for now: `compose-preview pin <version>` (or " +
        "`--plugin-version <version>` for a single run);"
    )
    append("  * check availability: ${pluginPortalMarkerUrl(version)}")
  }
}

/**
 * The version in an unresolvable marker coordinate, e.g. `1.66.1` out of `…gradle.plugin:1.66.1`.
 * Null when [message] names the marker without a version.
 *
 * Takes the **whole** coordinate version, not a numeric-looking prefix of it: Gradle versions are
 * not required to start with a digit or to avoid `+` (`dev-SNAPSHOT`, `1.2.3+build` are both legal,
 * and the CLI's own pin normalisation accepts any non-blank string), and truncating one would name
 * a different artifact in the message and in the availability URL. So it runs to the first
 * character that cannot be part of a coordinate.
 */
internal fun unresolvedPluginMarkerVersion(message: String): String? =
  Regex("""${Regex.escape(COMPOSE_PREVIEW_PLUGIN_MARKER)}:([^\s,;)'"\]]+)""")
    .find(message)
    ?.groupValues
    ?.get(1)
    // Gradle ends the sentence right after the coordinate ("…gradle.plugin:1.66.1."), and a
    // version never ends in a dot, so a trailing one is punctuation rather than part of it.
    ?.trimEnd('.')
    ?.takeIf { it.isNotEmpty() }

/**
 * The whole diagnosis in one call: null when [message] is not our marker failing to resolve, else
 * the guidance, attributed to [injectedVersion]'s [pinSource] when the version that failed is the
 * one this run asked for.
 *
 * The version comes from the message when it is readable, so a build that resolves the plugin at a
 * version the CLI did not choose (a module declaring the plugin itself) is still explained.
 */
internal fun pluginResolutionGuidance(
  message: String,
  injectedVersion: String?,
  pinSource: String? = null,
): String? {
  if (!isUnresolvedPluginMarkerFailure(message)) return null
  val version = unresolvedPluginMarkerVersion(message) ?: injectedVersion ?: return null
  val origin =
    when {
      version != injectedVersion -> null
      pinSource != null -> "pinned by $pinSource"
      else -> "the version this CLI bundles"
    }
  return unresolvedPluginMarkerGuidance(version, origin)
}

/**
 * Where the plugin marker for [version] lives on the Gradle Plugin Portal's Maven view — the URL
 * that answers "is it published yet?" with a 200 or a 404, which is the only question worth asking
 * when [unresolvedPluginMarkerGuidance] fires.
 */
internal fun pluginPortalMarkerUrl(version: String): String =
  "https://plugins.gradle.org/m2/${COMPOSE_PREVIEW_PLUGIN_ID.replace('.', '/')}/" +
    "$COMPOSE_PREVIEW_PLUGIN_ID.gradle.plugin/$version/"
