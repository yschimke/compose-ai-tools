package ee.schimke.composeai.cli

/**
 * Recognises the one Gradle failure that is never the consumer's fault — the compose-preview plugin
 * marker not resolvable at the injected version — and says so instead of letting it read as a
 * configuration problem in the user's project:
 * ```
 * :: ProjectConfigurationException: A problem occurred configuring root project '…'.
 *    -> Could not resolve all dependencies for configuration 'classpath'.
 *    -> Could not find ee.schimke.composeai.preview:
 *       ee.schimke.composeai.preview.gradle.plugin:1.66.1
 * ```
 *
 * A release's GitHub CLI asset can go public before the plugin is resolvable from the Plugin Portal
 * and Maven Central (the `maven-readiness` gate narrows but can't close that window), and a pin may
 * name a version that was never published. Matching is narrow so unrelated dependency failures
 * never get this explanation ([isUnresolvedPluginMarkerFailure]).
 */
internal const val COMPOSE_PREVIEW_PLUGIN_ID = "ee.schimke.composeai.preview"

/** The Maven coordinate Gradle resolves the plugin from. */
internal const val COMPOSE_PREVIEW_PLUGIN_MARKER =
  "$COMPOSE_PREVIEW_PLUGIN_ID:$COMPOSE_PREVIEW_PLUGIN_ID.gradle.plugin"

/**
 * True when [message] reports our plugin marker missing from every repository searched — what an
 * unpublished version looks like. Narrow on purpose:
 * - the marker module must appear;
 * - the verb must mean a missing artifact (`Could not find <coordinate>` or `could not resolve
 *   plugin artifact '<coordinate>'`), not generic `Could not resolve`, which proxies, TLS and
 *   missing repositories also produce;
 * - when [version] is given, it must be the coordinate's version.
 *
 * Messages also naming a [TRANSPORT_CAUSES] phrase are left alone: that cause is the real one.
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
 * Phrases marking a failure as repository configuration or transport rather than a missing
 * artifact; waiting for a publication fixes none of them.
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
 * The message for [isUnresolvedPluginMarkerFailure]: the version, why it is in play ([origin], e.g.
 * "pinned by gradle.properties (…)", when known), and what resolves it.
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
 * The version in an unresolvable marker coordinate (`1.66.1` from `…gradle.plugin:1.66.1`), or
 * null. Takes the whole version (Gradle versions may be `dev-SNAPSHOT` or contain `+`), up to the
 * first character that can't be part of a coordinate.
 */
internal fun unresolvedPluginMarkerVersion(message: String): String? =
  Regex("""${Regex.escape(COMPOSE_PREVIEW_PLUGIN_MARKER)}:([^\s,;)'"\]]+)""")
    .find(message)
    ?.groupValues
    ?.get(1)
    // Gradle ends the sentence with a dot right after the coordinate; versions never end in one.
    ?.trimEnd('.')
    ?.takeIf { it.isNotEmpty() }

/**
 * The whole diagnosis: null unless [message] is our marker failing to resolve, else the guidance,
 * attributed to [pinSource] when the failing version is [injectedVersion]. The version is read from
 * the message when possible, so a module-declared plugin version is explained too.
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
 * The plugin marker's URL on the Plugin Portal's Maven view: 200 or 404 answers "is it published
 * yet?".
 */
internal fun pluginPortalMarkerUrl(version: String): String =
  "https://plugins.gradle.org/m2/${COMPOSE_PREVIEW_PLUGIN_ID.replace('.', '/')}/" +
    "$COMPOSE_PREVIEW_PLUGIN_ID.gradle.plugin/$version/"
