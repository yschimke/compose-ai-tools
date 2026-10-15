package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import java.io.File
import java.net.URI

/**
 * The one switch that turns off every outbound network path of the CLI (#5960), for repositories
 * whose compiled code and rendered UI must never leave the machine: `COMPOSE_PREVIEW_NO_NETWORK=1`
 * in the environment, or `-Dcomposeai.network=off` on the JVM.
 *
 * With it on, each outbound path refuses at its entry, before a socket is opened or a process
 * spawned, with a [NetworkDisabledException] naming the switch and how to pre-provision what the
 * path would have fetched. Nothing degrades silently: a fetch that today falls back to "render
 * without it" fails instead, so an incomplete pre-provisioned directory is found on the first run
 * rather than in a review of what was sent where. The offline deployment it supports is
 * `docs/OFFLINE.md`.
 *
 * It implies the older, softer `COMPOSE_PREVIEW_OFFLINE=1` / `-Dcomposeai.bundle.offline=true`
 * ([offline]), which only skips fetches and keeps its own semantics when this switch is off.
 *
 * Loopback destinations (`localhost`, `127.0.0.1`, `::1`) are not outbound; paths that take a
 * caller-named URL let those through ([requireForUrl]).
 */
internal object NetworkPolicy {

  /** The environment switch: `1` or `true` turns the network off. */
  const val ENV: String = "COMPOSE_PREVIEW_NO_NETWORK"

  /** The JVM equivalent of [ENV]: `-Dcomposeai.network=off`. */
  const val PROPERTY: String = "composeai.network"

  /** The older skip-fetches switch, implied by [ENV]. */
  const val OFFLINE_ENV: String = "COMPOSE_PREVIEW_OFFLINE"

  /** The JVM form of [OFFLINE_ENV]. */
  const val OFFLINE_PROPERTY: String = "composeai.bundle.offline"

  /** The daemon/renderer property that stops GoogleFont downloads on a cache miss. */
  const val FONTS_OFFLINE_PROPERTY: String = "composeai.fonts.offline"

  /**
   * Test seam: non-null replaces the environment and system-property lookup, so a test never
   * depends on (or mutates) the real process environment. Reset it to null afterwards.
   */
  @Volatile internal var override: Boolean? = null

  /** Whether [env] / [property] turn the network off; pure for tests. */
  fun isDisabled(
    env: (String) -> String? = System::getenv,
    property: (String) -> String? = System::getProperty,
  ): Boolean {
    val fromEnv = env(ENV)?.trim()?.lowercase()
    if (fromEnv == "1" || fromEnv == "true") return true
    val fromProperty = property(PROPERTY)?.trim()?.lowercase()
    return fromProperty == "off" || fromProperty == "false" || fromProperty == "none"
  }

  /** True when the switch is on. Every outbound path checks this before connecting. */
  val disabled: Boolean
    get() = override ?: isDisabled()

  /**
   * True when fetches should be skipped: the switch, or the older `COMPOSE_PREVIEW_OFFLINE=1` /
   * `-Dcomposeai.bundle.offline=true`. Callers that degrade under plain offline still check
   * [disabled] to fail instead.
   */
  val offline: Boolean
    get() =
      disabled ||
        System.getProperty(OFFLINE_PROPERTY).toBoolean() ||
        System.getenv(OFFLINE_ENV) == "1"

  /** The refusal [require] throws, for callers that must build it themselves. */
  fun refusal(purpose: String, remedy: String): NetworkDisabledException =
    NetworkDisabledException(purpose, remedy)

  /**
   * Throws [NetworkDisabledException] when the switch is on. [purpose] says what needed the network
   * ("Fetching the Android render daemon"), [remedy] how to supply it without one.
   */
  fun require(purpose: String, remedy: String, disabled: Boolean = this.disabled) {
    if (disabled) throw refusal(purpose, remedy)
  }

  /** [require], except that a loopback [url] never leaves the machine and is let through. */
  fun requireForUrl(
    url: String,
    purpose: String,
    remedy: String,
    disabled: Boolean = this.disabled,
  ) {
    if (disabled && !isLoopback(url)) throw refusal(purpose, remedy)
  }

  /**
   * Whether [url]'s host is this machine: `localhost`, or an IP literal in `127.0.0.0/8` or `::1`.
   * Only literals count, never a DNS name that merely looks like one (`127.example.com`), and
   * nothing is resolved. Unparseable input is not loopback.
   */
  fun isLoopback(url: String): Boolean {
    val host =
      runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.removeSurrounding("[", "]")
        ?: return false
    if (host == "localhost") return true
    val ipv4 = host.split('.')
    if (ipv4.size == 4 && ipv4.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) {
      return ipv4[0] == "127" && ipv4.all { it == it.toInt().toString() }
    }
    // An IPv6 literal: InetAddress parses one without a lookup; anything else is a name.
    if (':' !in host) return false
    return runCatching { java.net.InetAddress.getByName(host).isLoopbackAddress }
      .getOrDefault(false)
  }

  /**
   * [client] for a path that let a loopback URL through: under the switch it follows no redirects,
   * since a local server answering `Location: https://elsewhere` would otherwise take the request
   * off the machine after the check.
   */
  fun noRedirects(
    client: okhttp3.OkHttpClient,
    disabled: Boolean = this.disabled,
  ): okhttp3.OkHttpClient =
    if (disabled) client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    else client

  /**
   * System properties every daemon or renderer the CLI launches gets under the switch: font
   * downloads off. Robolectric's `android-all` is [robolectricOfflineProperties]'s.
   */
  fun daemonSystemProperties(disabled: Boolean = this.disabled): Map<String, String> =
    if (disabled) mapOf(FONTS_OFFLINE_PROPERTY to "true") else emptyMap()

  /**
   * Gradle arguments for the CLI's own Gradle invocations under the switch: Gradle's `--offline`
   * (no dependency or plugin resolution against a remote repository) and the plugin's font opt-out.
   */
  fun gradleArguments(disabled: Boolean = this.disabled): List<String> =
    if (disabled) listOf("--offline", "-PcomposePreview.fontsOffline=true") else emptyList()

  /**
   * Called once at startup: under the switch, set the properties the rest of the process (and
   * modules that cannot see this object, such as the coordinate resolver and the serve host's
   * daemon launch) already read, so they skip their fetches too. An explicit value is kept.
   */
  fun applyToProcess(disabled: Boolean = this.disabled) {
    if (!disabled) return
    if (System.getProperty(OFFLINE_PROPERTY) == null) System.setProperty(OFFLINE_PROPERTY, "true")
    if (System.getProperty(FONTS_OFFLINE_PROPERTY) == null) {
      System.setProperty(FONTS_OFFLINE_PROPERTY, "true")
    }
  }

  /** Runs [block] with the switch forced to [disabled]; for tests. */
  internal fun <T> withOverride(disabled: Boolean, block: () -> T): T {
    val previous = override
    override = disabled
    return try {
      block()
    } finally {
      override = previous
    }
  }
}

/**
 * The jars for a bundle's detached Maven [coords], from [resolver]. A miss only warns (as the
 * resolver documents), except under [NetworkPolicy]'s switch: the resolver then searched only local
 * repositories (`composeai.bundle.offline`, set by [NetworkPolicy.applyToProcess]), and a jar it
 * could not find would have been fetched, so the render refuses rather than run on a short
 * classpath.
 */
internal fun resolveBundleCoordinates(
  resolver: CoordinateResolver,
  coords: List<BundleReader.ClasspathEntry.Maven>,
  disabled: Boolean = NetworkPolicy.disabled,
): List<File> {
  val resolutions = resolver.resolveAll(coords)
  val missing = resolutions.filter { it.file == null }.map { it.coordinate }
  if (missing.isNotEmpty()) {
    NetworkPolicy.require(
      "Resolving ${missing.size} bundle dependenc${if (missing.size == 1) "y" else "ies"} " +
        "(${missing.take(3).joinToString { "${it.group}:${it.artifact}:${it.version}" }}" +
        "${if (missing.size > 3) ", …" else ""}) from Maven",
      "Re-pack the bundle with --embed-deps, or pre-populate ~/.m2/repository, the Gradle cache " +
        "or ${CoordinateResolver.defaultDownloadCacheDir().absolutePath} with them.",
      disabled = disabled,
    )
  }
  return resolutions.mapNotNull { it.file }
}

/** The offline deployment guide, named in every refusal. */
internal const val OFFLINE_DOCS: String =
  "https://github.com/yschimke/compose-ai-tools/blob/main/docs/OFFLINE.md"

/**
 * An outbound path refused by [NetworkPolicy]. The message names the switch and the remedy; `main`
 * prints it and exits 1, never a stack trace.
 */
internal class NetworkDisabledException(val purpose: String, val remedy: String) :
  IllegalStateException(
    "$purpose needs the network, which ${NetworkPolicy.ENV} disables. $remedy " +
      "(What to pre-provision: $OFFLINE_DOCS)"
  )
