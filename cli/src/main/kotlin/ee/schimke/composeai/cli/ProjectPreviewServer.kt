package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem

/**
 * The project's preview server: where a repository names the `compose-preview serve` host its
 * tooling uses. Precedence mirrors the version pin ([resolveVersionPin]): `--serve-url`, then
 * `COMPOSE_PREVIEW_SERVE_URL`, then `gradle.properties` `composePreview.serveUrl`. Nothing found
 * leaves `share-preview` on gist / capture-branch upload.
 *
 * A checkout supplies the value, never the trust: any pull request can edit `gradle.properties`, so
 * a project-sourced host is not used until [confirmProjectServeHost] confirms it from outside the
 * tree. No credential is ever read from here; the URL is the only part safe to commit.
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
  /** Whether this URL was named from outside the checkout and may be sent a credential as is. */
  val isOutsideCheckout: Boolean
    get() = source != ServeUrlSource.GRADLE_PROPERTIES
}

/** What a caller may do with a resolved URL. */
internal sealed interface ServeUrlTrust {
  /** Confirmed: send to it. */
  data class Trusted(val resolved: ResolvedServeUrl) : ServeUrlTrust

  /** Only the checkout names this host; [how] is a printable explanation of how to confirm it. */
  data class NeedsConfirmation(val resolved: ResolvedServeUrl, val how: String) : ServeUrlTrust
}

/** Environment allowlist of hosts a checkout-named preview server may use. */
internal const val SERVE_HOSTS_ENV = "COMPOSE_PREVIEW_SERVE_HOSTS"

/**
 * Decide whether [resolved] may be sent a GitHub credential.
 *
 * Otherwise reviewing someone's branch and running `share-preview` would hand their host a token.
 * Flag and environment sources pass straight through. A `gradle.properties` value is confirmed by
 * `$COMPOSE_PREVIEW_SERVE_HOSTS` (`host` or `owner/repo=host`, see [Confirmation]),
 * `$COMPOSE_PREVIEW_SERVE_URL` naming the same host, or the user-level `gradle.properties` naming
 * it (ignored when that Gradle home is inside the checkout, e.g. `GRADLE_USER_HOME=$PWD/.gradle`).
 *
 * Hosts are compared exactly; port and path are not compared.
 */
internal fun confirmProjectServeHost(
  resolved: ResolvedServeUrl,
  projectRoot: File? = null,
  /** `owner/repo` from the `origin` remote (which no PR can edit), or null. */
  originRepo: String? = null,
  /**
   * The checkout boundary. Not [projectRoot]: a nested build (issue #5031) would otherwise put the
   * repo's own committed `.gradle/gradle.properties` "outside" and let a branch confirm itself.
   */
  checkoutRoot: File? = projectRoot?.let(::findVcsCheckoutRoot),
  env: (String) -> String? = System::getenv,
  userHome: String? = System.getProperty("user.home"),
  fileSystem: FileSystem = SystemFileSystem,
): ServeUrlTrust {
  if (resolved.isOutsideCheckout) return ServeUrlTrust.Trusted(resolved)
  val host = hostOf(resolved.url) ?: return needsConfirmation(resolved, originRepo)
  val confirmed = buildList {
    env(SERVE_HOSTS_ENV)?.split(',')?.forEach { entry -> parseConfirmation(entry)?.let(::add) }
    // A URL confirms only its own host, for any repo.
    env(SERVE_URL_ENV)?.let { hostOf(it)?.let { host -> add(Confirmation(host, null)) } }
    userGradlePropertiesServeUrl(env, userHome, checkoutRoot ?: projectRoot, fileSystem)?.let {
      hostOf(it)?.let { host -> add(Confirmation(host, null)) }
    }
  }
    .any { it.confirms(host, originRepo) }
  return if (confirmed) ServeUrlTrust.Trusted(resolved) else needsConfirmation(resolved, originRepo)
}

/**
 * One entry from [SERVE_HOSTS_ENV]. A bare host is a machine-wide grant; the `owner/repo=host` form
 * limits it to one repository, for machines that clone untrusted code.
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
  // Suggest the narrower, repo-scoped grant when the repo is known.
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
 * `owner/repo` for [projectRoot]'s `origin` remote, or null on any failure; that only costs a
 * repo-scoped confirmation its match, never grants anything.
 */
internal fun gitOriginRepo(projectRoot: File?): String? {
  val root = projectRoot ?: return null
  val url =
    runCatching {
      val process =
        ProcessBuilder("git", "-C", root.path, "remote", "get-url", "origin")
          .redirectError(ProcessBuilder.Redirect.DISCARD)
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
 * `composePreview.serveUrl` from the user-level `gradle.properties` (`$GRADLE_USER_HOME` or
 * `~/.gradle`).
 */
private fun userGradlePropertiesServeUrl(
  env: (String) -> String?,
  userHome: String?,
  /** The checkout boundary — see `checkoutRoot` on [confirmProjectServeHost]. */
  checkoutRoot: File?,
  fileSystem: FileSystem,
): String? {
  val gradleHome =
    env("GRADLE_USER_HOME")?.let(::File) ?: userHome?.let { File(it, ".gradle") } ?: return null
  // A Gradle home inside the checkout (a real CI cache layout) is checkout-writable. Compared
  // canonically so a symlink can't launder it.
  if (checkoutRoot != null && gradleHome.isInside(checkoutRoot)) return null
  return readGradleProperty(gradleHome, SERVE_URL_PROPERTY, fileSystem)?.normalizedUrl()
}

/** Whether this path is [parent] or sits beneath it, with symlinks resolved on both sides. */
private fun File.isInside(parent: File): Boolean {
  val here = canonicalOrAbsolute().path
  val root = parent.canonicalOrAbsolute().path
  return here == root || here.startsWith(root + File.separator)
}

/** The canonical path, falling back to the absolute one, which still catches `$PWD/.gradle`. */
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
 * Resolves the project's preview server, or null when nothing names one. Says what was named, never
 * whether it may be used: that is [confirmProjectServeHost]. [projectRoot] may be null outside a
 * project.
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
 * Trims and drops a trailing slash; blank is absent. Safety is judged at the point of use by
 * [ServeImageUploader.rejectUnsafeUrl].
 */
private fun String.normalizedUrl(): String? = trim().trimEnd('/').takeIf { it.isNotEmpty() }
