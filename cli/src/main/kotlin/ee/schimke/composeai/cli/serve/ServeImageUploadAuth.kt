package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The identity gate on the image lane ([ServeImageStore]): **who is allowed to upload**.
 *
 * The lane hands out hosting on the operator's origin, so unlike the document drop-box it is never
 * anonymous — not even on a `--public` box, where every *browsing* surface is open. A caller must
 * present a GitHub credential that GitHub itself says has real access to the operator's repository.
 *
 * ## What counts as a credential
 *
 * Both kinds a headless caller actually holds:
 * - A **user token** — `gh auth token`, a PAT — verified as that user, against the same repo-access
 *   rule the playground applies.
 * - A **GitHub App installation token**, which is what `${'$'}{{ github.token }}` is inside a
 *   GitHub Actions job. There is no user behind one, so it is verified on the installation's own
 *   write permission on the gating repo and attributed to [GitHubOAuthVerifier.INSTALLATION_LOGIN].
 *
 * ## Why a bearer token and not the OAuth session
 *
 * [ServeGithubAuth] already gates the playground on a signed-in GitHub account, and reusing it here
 * would have been less code — but its credential is a **cookie minted by a browser redirect flow**,
 * and the entire audience for this lane is headless: an agent in a CI job or a cloud coding
 * session, holding a `GITHUB_TOKEN` or a `gh auth token`, running `curl`. There is no browser to
 * round-trip. So the credential is `Authorization: Bearer <github-token>`, verified live against
 * GitHub on the host's own outbound connection.
 *
 * That also means the lane needs **no OAuth app**: it never mints a token, it only checks one the
 * caller already has. `--accept-images` therefore works on a box with no `--github-auth-*` config
 * at all, given a repository to check access against.
 *
 * ## What the token is used for, and what happens to it
 *
 * Exactly two GitHub reads, both as the caller: who they are, and whether they have access to
 * [repository]. The token is never stored, never logged, and never echoed back — the cache below is
 * keyed by its SHA-256, so a heap dump of a running server yields a hash, not a credential. The
 * access rule itself is [GitHubOAuthVerifier]'s, unchanged: write access on a public repository (on
 * which every GitHub user has read), any real grant on a private one.
 */
interface ServeImageUploadAuth {

  /** The repository a caller must have access to. Shown in the refusal, so it can be acted on. */
  val repository: String

  /** Decide who [bearerToken] is, or why it isn't good enough. */
  fun identify(bearerToken: String?): Identity

  sealed interface Identity {
    /**
     * Verified: [login] has access to [repository].
     *
     * [budgetKey] is who to charge for the upload, which is **not** always [login]. Every verified
     * installation token shares one placeholder login (there is no user behind one), so keying a
     * budget on that would put every GitHub App with write access to the gating repo in a single
     * bucket — one app's batch would 429 another's. The key is therefore the credential's own
     * fingerprint for those, and the login for a real user.
     */
    data class Ok(val login: String, val budgetKey: String = "gh:$login") : Identity

    /** No credential presented at all — answered `401`, with how to present one. */
    data object Missing : Identity

    /**
     * A credential was presented and is not good enough: unreadable by GitHub, or a real account
     * without access to the gating repository. [status] is what the route answers with, and
     * [reason] is safe to hand back — it never contains any part of the token.
     */
    data class Refused(val status: Int, val reason: String) : Identity
  }
}

/**
 * The real gate: verifies the presented token against GitHub, with a short-lived positive/negative
 * cache in front.
 *
 * The cache is not an optimisation so much as a rate control. Without it, every uploaded PNG in a
 * batch of twenty costs two GitHub API calls against the *caller's* rate limit, and an attacker
 * spraying random tokens gets the host to spend its outbound connections one-per-guess. With it, a
 * batch verifies once and a repeated bad token is refused locally.
 *
 * Positive entries are short ([POSITIVE_TTL_SECONDS]) because they cache an *authorisation* —
 * access revoked on GitHub must stop working here promptly, and the whole point of checking live is
 * that it does. Negative entries are shorter still, so a caller who fixes their token's scopes
 * isn't locked out of their own fix.
 */
class GithubTokenUploadAuth(
  override val repository: String,
  /** When non-empty, only these logins may upload, whatever GitHub says about repo access. */
  private val allowedUsers: Set<String> = emptySet(),
  /**
   * The GitHub round-trip, as a function so a test can stand in for it: identity + repo access for
   * a presented credential. Defaults to the real [GitHubOAuthVerifier], whose rule the playground
   * already shares.
   */
  private val verifier: (String, String, Set<String>) -> Result<GitHubOAuthUser> =
    GitHubOAuthVerifier()::verifyAccessToken,
  private val clock: () -> Long = System::currentTimeMillis,
) : ServeImageUploadAuth {

  private class Entry(val identity: ServeImageUploadAuth.Identity, val expiresAtMillis: Long)

  private val cache = ConcurrentHashMap<String, Entry>()

  override fun identify(bearerToken: String?): ServeImageUploadAuth.Identity {
    val token =
      bearerToken?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ServeImageUploadAuth.Identity.Missing
    val key = fingerprint(token)
    val now = clock()
    cache[key]
      ?.takeIf { it.expiresAtMillis > now }
      ?.let {
        return it.identity
      }
    // Never cache under an unbounded key space: a token spray would otherwise grow the map by one
    // entry per guess. The ceiling is far above any real caller count on one host.
    if (cache.size >= MAX_CACHED_TOKENS) {
      cache.entries.removeIf { it.value.expiresAtMillis <= now }
      if (cache.size >= MAX_CACHED_TOKENS) cache.clear()
    }
    val identity = verify(token, key)
    val ttl =
      if (identity is ServeImageUploadAuth.Identity.Ok) POSITIVE_TTL_SECONDS
      else NEGATIVE_TTL_SECONDS
    cache[key] = Entry(identity, now + ttl * 1000)
    return identity
  }

  private fun verify(token: String, fingerprint: String): ServeImageUploadAuth.Identity {
    val user =
      verifier(token, repository, allowedUsers).getOrElse { error ->
        // The message is GitHub's or ours about GitHub — a status code, a "not allowed" — and never
        // contains the credential, which is the only thing that must not travel back out.
        return ServeImageUploadAuth.Identity.Refused(
          status = 401,
          reason = "GitHub could not verify that token (${error.message ?: "unknown error"}).",
        )
      }
    if (!user.repositoryAccess) {
      return ServeImageUploadAuth.Identity.Refused(
        status = 403,
        reason =
          "GitHub user ${user.login} does not have access to $repository. " +
            "Uploading preview images is limited to that repository's collaborators.",
      )
    }
    // An installation has no login to charge, so it is charged as the credential it presented.
    // A GitHub Actions token rotates hourly, so its bucket rotates with it — which is the right
    // granularity anyway: one workflow run, one budget.
    val budgetKey =
      if (user.login == GitHubOAuthVerifier.INSTALLATION_LOGIN) "app:${fingerprint.take(16)}"
      else "gh:${user.login}"
    return ServeImageUploadAuth.Identity.Ok(user.login, budgetKey)
  }

  /** SHA-256, hex — a stable cache key that is not the credential it stands for. */
  private fun fingerprint(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
      "%02x".format(it)
    }

  companion object {
    /** How long a verified identity is reused before GitHub is asked again. */
    const val POSITIVE_TTL_SECONDS = 300L

    /** How long a refusal sticks — short, so fixing a token's scopes takes effect quickly. */
    const val NEGATIVE_TTL_SECONDS = 30L

    private const val MAX_CACHED_TOKENS = 4096
  }
}
