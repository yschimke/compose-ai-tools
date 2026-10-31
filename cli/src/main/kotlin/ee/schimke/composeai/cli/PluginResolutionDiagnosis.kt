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
 *
 * That window is hours, not weeks, so the publication explanation is given only when the version is
 * known to be that recent ([ReleaseTiming]). A version known to be older failed for another reason
 * — most often the network: a repository edge refusing the machine (Maven Central answers 404 with
 * `retry-after`), or repositories that bypass a configured mirror (issue #6360). Then the guidance
 * names the repositories Gradle searched and points at the network instead.
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
    "429 Too Many Requests",
    "Retry-After",
  )

/** What the CLI knows about how long ago a plugin version was published. */
internal enum class VersionAge {
  /** Inside the publication window: published recently enough to still be propagating. */
  RECENT,
  /** Out long enough that propagation cannot be the cause. */
  ESTABLISHED,
  /** Nothing at hand dates it; neither cause can be ruled out. */
  UNKNOWN,
}

/**
 * The dating evidence the CLI carries, with no network lookup: the Maven line it was built against
 * and when its own release commit was made ([CLI_RELEASE_EPOCH_SECONDS], baked at build time).
 *
 * The CLI's release commit predates its Maven line's publication by minutes at most, and every
 * version at or below that line was published no later — so the commit's age is a lower bound on
 * theirs. A version above the line (a pin newer than this CLI) or a CLI with no baked timestamp is
 * [VersionAge.UNKNOWN]. Only the line this release itself published ([cliVersion] equal to it) can
 * be [VersionAge.RECENT]: a CLI-only release keeps an older line, published by an earlier release.
 */
internal data class ReleaseTiming(
  val mavenLineVersion: String,
  val releasedAtEpochSeconds: Long?,
  val cliVersion: String = mavenLineVersion,
  val nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
) {
  fun ageOf(version: String): VersionAge {
    val releasedAt = releasedAtEpochSeconds ?: return VersionAge.UNKNOWN
    if (!isPlainRelease(version) || !isPlainRelease(mavenLineVersion)) return VersionAge.UNKNOWN
    val cmp = compareSemver(version, mavenLineVersion)
    if (cmp > 0) return VersionAge.UNKNOWN
    val cliAge = nowEpochSeconds - releasedAt
    return when {
      cliAge >= PUBLICATION_WINDOW_SECONDS -> VersionAge.ESTABLISHED
      // Within a day of this release, an older version (or an older line kept by a CLI-only
      // release) may be just as recent or weeks old: unknown.
      cmp == 0 && cliVersion == mavenLineVersion -> VersionAge.RECENT
      else -> VersionAge.UNKNOWN
    }
  }

  companion object {
    /** About a day: comfortably longer than the slowest Plugin Portal / Central propagation. */
    const val PUBLICATION_WINDOW_SECONDS: Long = 24 * 60 * 60

    fun current(): ReleaseTiming =
      ReleaseTiming(
        mavenLineVersion = MAVEN_LINE_VERSION,
        releasedAtEpochSeconds = CLI_RELEASE_EPOCH_SECONDS,
        cliVersion = BUNDLE_VERSION,
      )

    private fun isPlainRelease(version: String) = Regex("""\d+(\.\d+)*""").matches(version)
  }
}

/**
 * The message for [isUnresolvedPluginMarkerFailure]: the version, why it is in play ([origin], e.g.
 * "pinned by gradle.properties (…)", when known), and what resolves it. [age] decides whether the
 * publication window is offered as the cause; [message], the Gradle failure, supplies the artifact
 * and the repositories searched.
 */
internal fun unresolvedPluginMarkerGuidance(
  version: String,
  origin: String? = null,
  age: VersionAge = VersionAge.RECENT,
  message: String = "",
): String {
  val attribution = origin?.let { " ($it)" }.orEmpty()
  val missing = missingCoordinate(message)?.takeIf { !it.startsWith(COMPOSE_PREVIEW_PLUGIN_MARKER) }
  val searched = searchedLocations(message)
  return buildString {
    appendLine(
      "compose-preview: Gradle could not resolve the compose-preview Gradle plugin " +
        "$COMPOSE_PREVIEW_PLUGIN_MARKER:$version$attribution" +
        (missing?.let { ", whose dependency $it was not found" } ?: "") +
        ". This is a problem with reaching that version, not with your project — the " +
        "configuration failure above is the symptom."
    )
    appendLine()
    when (age) {
      VersionAge.RECENT -> appendPublicationWindow()
      VersionAge.ESTABLISHED ->
        appendLine(
          "$version is not a new release — it has been published for more than a day — so this " +
            "is not the publication window. The repositories Gradle searched did not serve it, " +
            "which usually means a network, mirror or proxy problem between this machine and " +
            "those repositories: a repository edge refusing this machine (Maven Central answers " +
            "404 with a `retry-after` header when it does), a proxy, or repositories that bypass " +
            "the mirror your build or ~/.gradle/init.d configures."
        )
      VersionAge.UNKNOWN ->
        appendLine(
          "Either $version is not resolvable yet (a release is downloadable as a CLI shortly " +
            "before its Gradle plugin reaches plugins.gradle.org and Maven Central, and that " +
            "clears on its own within hours), or the repositories Gradle searched could not be " +
            "reached properly from this machine — a network, mirror or proxy problem."
        )
    }
    if (searched.isNotEmpty()) {
      appendLine()
      appendLine("Gradle searched:")
      searched.take(MAX_LISTED_LOCATIONS).forEach { appendLine("  - $it") }
      if (searched.size > MAX_LISTED_LOCATIONS) {
        appendLine("  … and ${searched.size - MAX_LISTED_LOCATIONS} more")
      }
    }
    appendLine()
    appendLine("Options:")
    if (age != VersionAge.RECENT) {
      val probe = searched.firstOrNull { it.startsWith("http") }
      if (probe != null) {
        appendLine(
          "  * see what the repository answers: `curl -sSI '$probe'` — a 403, 429, " +
            "`retry-after` or proxy page is a network problem, not a missing release;"
        )
      }
      if (searched.any { MAVEN_CENTRAL_HOSTS.any { host -> it.contains("://$host/") } }) {
        appendLine(
          "  * check a well-known artifact on the same host: `curl -sSI '$MAVEN_CENTRAL_PROBE'` " +
            "— if that fails too, Maven Central is not reachable from here;"
        )
      }
      appendLine(
        "  * if you use a mirror or proxy, check that it covers these repositories (and that " +
          "plain `./gradlew` resolves through it);"
      )
    } else {
      appendLine("  * wait for the publication to land and re-run — nothing to change;")
    }
    appendLine(
      "  * pin a different release: `compose-preview pin <version>` (or " +
        "`--plugin-version <version>` for a single run);"
    )
    append("  * check availability: ${pluginPortalMarkerUrl(version)}")
  }
}

private fun StringBuilder.appendPublicationWindow() {
  appendLine(
    "The usual cause is the publication window: a compose-preview release is downloadable as a " +
      "CLI (and resolvable as `latest`) before the Gradle plugin of the same version is " +
      "resolvable from plugins.gradle.org and Maven Central. Builds dispatched inside that " +
      "window fail exactly like this, and it clears on its own."
  )
}

private const val MAX_LISTED_LOCATIONS = 8

private val MAVEN_CENTRAL_HOSTS = listOf("repo.maven.apache.org", "repo1.maven.org")

/**
 * An artifact every Maven Central mirror serves; resolving it separates "missing" from "blocked".
 */
internal const val MAVEN_CENTRAL_PROBE =
  "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml"

/**
 * The URLs (or repository descriptions) Gradle lists under `Searched in the following locations:`,
 * plus any `Could not GET/HEAD '<url>'`, in order and without duplicates.
 */
internal fun searchedLocations(message: String): List<String> {
  val found = LinkedHashSet<String>()
  var inSearched = false
  for (raw in message.lineSequence()) {
    val line = raw.trim()
    if (line.startsWith("Searched in the following locations", ignoreCase = true)) {
      inSearched = true
      continue
    }
    if (inSearched) {
      if (line.startsWith("- ")) {
        found += line.removePrefix("- ").trim()
        continue
      }
      inSearched = false
    }
    Regex("""Could not (?:GET|HEAD) '([^']+)'""").findAll(line).forEach {
      found += it.groupValues[1]
    }
  }
  return found.toList()
}

/** The coordinate of the first `Could not find <coordinate>.` in [message], or null. */
internal fun missingCoordinate(message: String): String? =
  Regex("""Could not find ([\w.\-]+:[\w.\-]+:[^\s,;)'"\]]+)""")
    .find(message)
    ?.groupValues
    ?.get(1)
    ?.trimEnd('.')

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
 * the message when possible, so a module-declared plugin version is explained too. [timing] dates
 * the version, deciding whether the publication window is offered as the cause.
 */
internal fun pluginResolutionGuidance(
  message: String,
  injectedVersion: String?,
  pinSource: String? = null,
  timing: ReleaseTiming = ReleaseTiming.current(),
): String? {
  if (!isUnresolvedPluginMarkerFailure(message)) return null
  val version = unresolvedPluginMarkerVersion(message) ?: injectedVersion ?: return null
  val origin =
    when {
      version != injectedVersion -> null
      pinSource != null -> "pinned by $pinSource"
      else -> "the version this CLI bundles"
    }
  return unresolvedPluginMarkerGuidance(version, origin, timing.ageOf(version), message)
}

/**
 * The plugin marker's URL on the Plugin Portal's Maven view: 200 or 404 answers "is it published
 * yet?".
 */
internal fun pluginPortalMarkerUrl(version: String): String =
  "https://plugins.gradle.org/m2/${COMPOSE_PREVIEW_PLUGIN_ID.replace('.', '/')}/" +
    "$COMPOSE_PREVIEW_PLUGIN_ID.gradle.plugin/$version/"
