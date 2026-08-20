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
internal data class ResolvedServeUrl(val url: String, val source: ServeUrlSource)

/**
 * Resolves the project's preview server, or null when nothing names one.
 *
 * [projectRoot] is the Gradle root (the directory holding `gradlew`); pass null when the caller
 * isn't in a project — the flag and environment sources still apply, which is what lets a bare
 * directory of PNGs be uploaded from anywhere. Every disk read is failure-tolerant for the same
 * reason the pin's is: an unreadable `gradle.properties` must be no worse than an absent one.
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
