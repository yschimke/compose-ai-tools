package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem

/**
 * The **project's preview server** — one place a repository names the `compose-preview serve` host
 * its tooling should use, so nobody has to pass it per invocation.
 *
 * Same shape and the same reasoning as the version pin ([resolveVersionPin]): a fact about the
 * *project* belongs in the project, committed, where every entrypoint and every contributor reads
 * the same value. An agent that clones the repo and runs `share-preview` then reaches the team's
 * host without being told about it — which is the difference between a mechanism that exists and a
 * mechanism that gets used.
 *
 * # Sources, in precedence order
 * 1. `--serve-url <url>` on the invocation — a per-run override, nothing is read from disk.
 * 2. `COMPOSE_PREVIEW_SERVE_URL` in the environment — the CI / container override, and how a
 *    sandbox that provisions its own host announces it.
 * 3. `gradle.properties` → `composePreview.serveUrl` — the canonical project setting, in the same
 *    namespace as `composePreview.version` and the plugin's other knobs.
 *
 * Nothing found → null, and `share-preview` behaves exactly as it did before: gist when `gh` is
 * signed in, else a capture-branch push.
 *
 * # A checkout supplies the value, never the trust
 *
 * Source 3 is a file **any pull request can edit**, so it is resolved here but not acted on until
 * [confirmProjectServeHost] finds the host confirmed from outside the checkout. Without that, a
 * branch could name an attacker's host and a maintainer reviewing that branch — checking it out and
 * running the ordinary `share-preview` — would send their GitHub token to it. That is the entire
 * reason this file has a trust function and not just a getter.
 *
 * # What is deliberately *not* here
 *
 * **No credential of any kind.** Not the GitHub token the upload authenticates with, and not the
 * host's own browse token. `gradle.properties` is a committed file; a secret in it is a secret in
 * the repository's history, and offering the key at all would invite exactly that. The URL is the
 * only part of this configuration that is safe to commit — the credential comes from the
 * environment or a protected file ([AgentGithubToken]), per run, per caller.
 *
 * Committing a URL is still a decision with consequences, which is why it is a deliberate act
 * rather than a default: it makes uploading the *default* path for everyone working in the repo,
 * and an upload publishes an image to anyone holding the link. A project whose renders shouldn't
 * leave the building should not name a public host here.
 */
internal const val SERVE_URL_PROPERTY = "composePreview.serveUrl"

/** Environment override, read after `--serve-url` and before anything on disk. */
internal const val SERVE_URL_ENV = "COMPOSE_PREVIEW_SERVE_URL"

/** Where a resolved preview-server URL came from. Ordered by precedence — first match wins. */
internal enum class ServeUrlSource(val display: String) {
  FLAG("--serve-url"),
  ENV(SERVE_URL_ENV),
  GRADLE_PROPERTIES("gradle.properties ($SERVE_URL_PROPERTY)"),
}

/** A preview-server URL that was actually found, plus which source supplied it. */
internal data class ResolvedServeUrl(val url: String, val source: ServeUrlSource) {
  /**
   * Whether this URL was named by something **outside the checkout**, and may therefore be sent a
   * credential without further confirmation. See [confirmProjectServeHost] for why that distinction
   * is the whole security model here.
   */
  val isOutsideCheckout: Boolean
    get() = source != ServeUrlSource.GRADLE_PROPERTIES
}

/** What a caller may do with a resolved URL. */
internal sealed interface ServeUrlTrust {
  /** Confirmed: send to it. */
  data class Trusted(val resolved: ResolvedServeUrl) : ServeUrlTrust

  /**
   * The **project** names this host and nothing outside the checkout confirms it. [how] is the
   * operator-facing explanation of what would confirm it; it is safe to print.
   */
  data class NeedsConfirmation(val resolved: ResolvedServeUrl, val how: String) : ServeUrlTrust
}

/** Environment allowlist of hosts a checkout-named preview server may use. */
internal const val SERVE_HOSTS_ENV = "COMPOSE_PREVIEW_SERVE_HOSTS"

/**
 * Decide whether [resolved] may be sent a GitHub credential.
 *
 * **A checkout may supply the value; it may never supply the trust.** `gradle.properties` is a file
 * any pull request can edit, and checking a branch out to look at it is the normal way to review
 * one — so a project-sourced host that took effect on its own would mean that opening someone's PR
 * and running the ordinary `share-preview` command hands them a repository-scoped token. TLS proves
 * nothing about that: an attacker's host has a certificate too. This is the same class as a
 * malicious `.vscode/settings.json` or a `Makefile` that runs on open, and it gets the same answer:
 * configuration from inside the tree is data, and the decision to *act* on it comes from outside.
 *
 * Anything outside the checkout is already an act of consent and passes straight through:
 * `--serve-url` (typed for this run) and `$COMPOSE_PREVIEW_SERVE_URL` (set by the environment that
 * built the sandbox). A `gradle.properties` value is confirmed by any of:
 * - `$COMPOSE_PREVIEW_SERVE_HOSTS` — a comma-separated host allowlist, which is how an org's CI
 *   image or agent sandbox says "these hosts are ours" once, for every repo it will ever check out;
 * - `$COMPOSE_PREVIEW_SERVE_URL` naming the same host;
 * - the **user-level** `gradle.properties` (`$GRADLE_USER_HOME` or `~/.gradle`) naming the same
 *   host — a developer's own machine config, which no checkout can write.
 *
 * Comparison is on the **host** alone, exactly, so `preview.coo.ee.evil.example` does not pass as
 * `preview.coo.ee`. Port and path are not compared: whoever operates a host operates its ports.
 */
internal fun confirmProjectServeHost(
  resolved: ResolvedServeUrl,
  env: (String) -> String? = System::getenv,
  userHome: String? = System.getProperty("user.home"),
  fileSystem: FileSystem = SystemFileSystem,
): ServeUrlTrust {
  if (resolved.isOutsideCheckout) return ServeUrlTrust.Trusted(resolved)
  val host = hostOf(resolved.url) ?: return needsConfirmation(resolved)
  val allowed = buildSet {
    env(SERVE_HOSTS_ENV)?.split(',')?.forEach { entry ->
      entry.trim().lowercase().takeIf { it.isNotEmpty() }?.let(::add)
    }
    env(SERVE_URL_ENV)?.let { hostOf(it)?.let(::add) }
    userGradlePropertiesServeUrl(env, userHome, fileSystem)?.let { hostOf(it)?.let(::add) }
  }
  return if (host in allowed) ServeUrlTrust.Trusted(resolved) else needsConfirmation(resolved)
}

private fun needsConfirmation(resolved: ResolvedServeUrl): ServeUrlTrust.NeedsConfirmation {
  val host = hostOf(resolved.url) ?: resolved.url
  return ServeUrlTrust.NeedsConfirmation(
    resolved,
    "This project's $SERVE_URL_PROPERTY names $host, but nothing outside the checkout confirms " +
      "it — and gradle.properties is a file any branch can change, so acting on it unconfirmed " +
      "would let a pull request redirect your GitHub token. Confirm the host once, from outside " +
      "the repo:" +
      "\n  export $SERVE_HOSTS_ENV=$host        (this machine / CI image trusts that host)" +
      "\n  or pass --serve-url ${resolved.url}  (just this run)",
  )
}

/**
 * `composePreview.serveUrl` from the **user-level** `gradle.properties` — `$GRADLE_USER_HOME`, else
 * `~/.gradle`. Outside every checkout by construction, which is what makes it a confirmation.
 */
private fun userGradlePropertiesServeUrl(
  env: (String) -> String?,
  userHome: String?,
  fileSystem: FileSystem,
): String? {
  val gradleHome =
    env("GRADLE_USER_HOME")?.let(::File) ?: userHome?.let { File(it, ".gradle") } ?: return null
  return readGradleProperty(gradleHome, SERVE_URL_PROPERTY, fileSystem)?.normalizedUrl()
}

/** Lowercased host of [url], or null when it isn't a URL with one. */
internal fun hostOf(url: String): String? = runCatching {
  java.net.URI(url.trim()).host?.lowercase()
}
  .getOrNull()
  ?.takeIf { it.isNotEmpty() }

/**
 * Resolves the project's preview server, or null when nothing names one.
 *
 * [projectRoot] is the Gradle root (the directory holding `gradlew`); pass null when the caller
 * isn't in a project — the flag and environment sources still apply, which is what lets a bare
 * directory of PNGs be uploaded from anywhere. Every disk read is failure-tolerant for the same
 * reason the pin's is: an unreadable `gradle.properties` must be no worse than an absent one.
 *
 * Resolution says *what* was named, never whether it may be used — [confirmProjectServeHost] owns
 * that, so no caller can accidentally act on a checkout-supplied host by skipping a boolean.
 */
internal fun resolveProjectServeUrl(
  projectRoot: File?,
  args: List<String> = emptyList(),
  env: (String) -> String? = System::getenv,
  fileSystem: FileSystem = SystemFileSystem,
): ResolvedServeUrl? {
  args.flagValue("--serve-url")?.normalizedUrl()?.let {
    return ResolvedServeUrl(it, ServeUrlSource.FLAG)
  }
  env(SERVE_URL_ENV)?.normalizedUrl()?.let {
    return ResolvedServeUrl(it, ServeUrlSource.ENV)
  }
  if (projectRoot == null) return null
  readGradleProperty(projectRoot, SERVE_URL_PROPERTY, fileSystem)?.normalizedUrl()?.let {
    return ResolvedServeUrl(it, ServeUrlSource.GRADLE_PROPERTIES)
  }
  return null
}

/**
 * Trims and drops a trailing slash, so `https://host/` and `https://host` are one value. Blank is
 * treated as absent — an empty `composePreview.serveUrl=` line must not configure a host of "".
 *
 * Nothing here validates the URL: whether it is somewhere a credential may be sent is
 * [ServeImageUploader.rejectUnsafeUrl]'s decision, made at the point of use so the answer is the
 * same however the URL arrived.
 */
private fun String.normalizedUrl(): String? = trim().trimEnd('/').takeIf { it.isNotEmpty() }
