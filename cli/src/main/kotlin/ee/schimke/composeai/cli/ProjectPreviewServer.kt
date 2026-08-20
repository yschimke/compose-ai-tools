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
 * - `$COMPOSE_PREVIEW_SERVE_HOSTS` — a comma-separated allowlist, which is how an org's CI image or
 *   agent sandbox says "these hosts are ours" once. Each entry is a bare `host` (any repository) or
 *   the narrower `owner/repo=host`, which confirms only while the checkout's `origin` is that
 *   repository — see [Confirmation] for when the difference matters;
 * - `$COMPOSE_PREVIEW_SERVE_URL` naming the same host;
 * - the **user-level** `gradle.properties` (`$GRADLE_USER_HOME` or `~/.gradle`) naming the same
 *   host — a developer's own machine config, which no checkout can write. Unless it can:
 *   `GRADLE_USER_HOME=$PWD/.gradle` is a real CI cache layout, so a gradle home resolving inside
 *   the project root is ignored rather than believed.
 *
 * Comparison is on the **host** alone, exactly, so `preview.coo.ee.evil.example` does not pass as
 * `preview.coo.ee`. Port and path are not compared: whoever operates a host operates its ports.
 */
internal fun confirmProjectServeHost(
  resolved: ResolvedServeUrl,
  projectRoot: File? = null,
  /**
   * `owner/repo` this checkout belongs to, from its `origin` remote — `.git/config`, which no pull
   * request can edit. Null when it isn't a recognisable GitHub checkout, which simply means a
   * repo-scoped confirmation cannot match.
   */
  originRepo: String? = null,
  env: (String) -> String? = System::getenv,
  userHome: String? = System.getProperty("user.home"),
  fileSystem: FileSystem = SystemFileSystem,
): ServeUrlTrust {
  if (resolved.isOutsideCheckout) return ServeUrlTrust.Trusted(resolved)
  val host = hostOf(resolved.url) ?: return needsConfirmation(resolved, originRepo)
  val confirmed = buildList {
    env(SERVE_HOSTS_ENV)?.split(',')?.forEach { entry -> parseConfirmation(entry)?.let(::add) }
    // A URL rather than an allowlist entry, so it can only ever confirm its own host, for any
    // repo — it is the host this environment was built to talk to.
    env(SERVE_URL_ENV)?.let { hostOf(it)?.let { host -> add(Confirmation(host, null)) } }
    userGradlePropertiesServeUrl(env, userHome, projectRoot, fileSystem)?.let {
      hostOf(it)?.let { host -> add(Confirmation(host, null)) }
    }
  }
    .any { it.confirms(host, originRepo) }
  return if (confirmed) ServeUrlTrust.Trusted(resolved) else needsConfirmation(resolved, originRepo)
}

/**
 * One entry from [SERVE_HOSTS_ENV]: a host, optionally scoped to the repository it may be used for.
 *
 * The scoped form (`owner/repo=host`) exists because a bare host is a standing grant on the whole
 * machine — every checkout of every repository, forever. That is fine for a CI image that only ever
 * builds its own repos, and too broad for a laptop that clones strangers' code: a branch of any
 * repo could then name a host you confirmed for a different one, and cause an upload you never
 * asked for. It does **not** hand anyone your credential — the host is still one you trust — which
 * is why the bare form remains supported rather than removed.
 */
private data class Confirmation(val host: String, val repo: String?) {
  fun confirms(host: String, originRepo: String?): Boolean {
    if (this.host != host) return false
    val scope = repo ?: return true
    return originRepo != null && originRepo.equals(scope, ignoreCase = true)
  }
}

/** `host` or `owner/repo=host`, trimmed and lowercased. Null for an entry that is neither. */
private fun parseConfirmation(entry: String): Confirmation? {
  val trimmed = entry.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
  if ('=' !in trimmed) return Confirmation(trimmed, null)
  val repo = trimmed.substringBefore('=').trim()
  val host = trimmed.substringAfter('=').trim()
  if (host.isEmpty() || repo.count { it == '/' } != 1) return null
  return Confirmation(host, repo)
}

private fun needsConfirmation(
  resolved: ResolvedServeUrl,
  originRepo: String?,
): ServeUrlTrust.NeedsConfirmation {
  val host = hostOf(resolved.url) ?: resolved.url
  // The scoped form is offered first when we know which repo this is: it is the one a reader will
  // copy, and the narrower grant should be the one that is easy to reach for.
  val scoped = originRepo?.let { "$it=$host" } ?: host
  return ServeUrlTrust.NeedsConfirmation(
    resolved,
    "This project's $SERVE_URL_PROPERTY names $host, but nothing outside the checkout confirms " +
      "it — and gradle.properties is a file any branch can change, so acting on it unconfirmed " +
      "would let a pull request redirect your GitHub token. Confirm it once, from outside the " +
      "repo:" +
      "\n  export $SERVE_HOSTS_ENV=$scoped" +
      (if (originRepo != null) "   (that host, for this repository)" else "   (that host)") +
      "\n  or pass --serve-url ${ServeImageUploader.redactedUrl(resolved.url)}  (just this run)",
  )
}

/**
 * `owner/repo` for [projectRoot]'s `origin` remote, or null when there isn't one this CLI
 * recognises. Failure-tolerant in every direction: no git, no remote, a non-GitHub remote, or a
 * timeout all mean "no repo identity", which costs a repo-scoped confirmation its match and never
 * grants anything.
 */
internal fun gitOriginRepo(projectRoot: File?): String? {
  val root = projectRoot ?: return null
  val url =
    runCatching {
      val process =
        ProcessBuilder("git", "-C", root.path, "remote", "get-url", "origin")
          .redirectErrorStream(false)
          .start()
      val out = process.inputStream.bufferedReader().use { it.readText() }
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) null else out.trim().takeIf { it.isNotEmpty() }
    }
      .getOrNull() ?: return null
  return SharePreviewCommand.githubOwnerRepo(url)
}

/**
 * `composePreview.serveUrl` from the **user-level** `gradle.properties` — `$GRADLE_USER_HOME`, else
 * `~/.gradle`. Outside every checkout by construction, which is what makes it a confirmation.
 */
private fun userGradlePropertiesServeUrl(
  env: (String) -> String?,
  userHome: String?,
  projectRoot: File?,
  fileSystem: FileSystem,
): String? {
  val gradleHome =
    env("GRADLE_USER_HOME")?.let(::File) ?: userHome?.let { File(it, ".gradle") } ?: return null
  // `GRADLE_USER_HOME=$PWD/.gradle` is a real CI cache layout, and under it a branch that commits
  // both `gradle.properties` and `.gradle/gradle.properties` would be confirming itself — the
  // checkout supplying its own trust, which is the one thing this mechanism exists to prevent.
  // Compared canonically, so a symlink out of the tree and back in cannot launder it.
  if (projectRoot != null && gradleHome.isInside(projectRoot)) return null
  return readGradleProperty(gradleHome, SERVE_URL_PROPERTY, fileSystem)?.normalizedUrl()
}

/** Whether this path is [parent] or sits beneath it, with symlinks resolved on both sides. */
private fun File.isInside(parent: File): Boolean {
  val here = canonicalOrAbsolute().path
  val root = parent.canonicalOrAbsolute().path
  return here == root || here.startsWith(root + File.separator)
}

/**
 * The canonical path, falling back to the absolute one. Canonicalisation touches the filesystem and
 * can fail (a missing directory, a permission error); "couldn't canonicalise" must not become
 * "isn't inside the checkout", so the fallback still catches the plain `$PWD/.gradle` case.
 */
private fun File.canonicalOrAbsolute(): File = runCatching {
  canonicalFile
}
  .getOrElse { absoluteFile }

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
