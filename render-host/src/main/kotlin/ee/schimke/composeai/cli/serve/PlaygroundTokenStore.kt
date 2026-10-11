package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import okio.FileSystem
import okio.Path

/**
 * The expiring preview-token capability behind `/pg/<token>` — the Stage-1 → Stage-2 handoff in
 * [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md). Holds a cleanly
 * compiled snippet ([PlaygroundSnippet]) and redeems into a live daemon session or a Remote Compose
 * document permalink.
 *
 * Safety model as in [ServeDocStore]:
 * - The id is the capability: 128 bits of [SecureRandom], base64url, `pg_`-prefixed.
 * - Expiring: after [ttlSeconds] `/pg/<id>` 404s without revealing whether it existed.
 * - Bounded: [maxTokens] evicts nearest-expiry first, and dropping a token deletes its work dir, so
 *   disk is bounded too.
 *
 * Every removal path goes through [disposeSnippet]; deletion is best-effort so one stuck directory
 * can't wedge purging.
 */
public class PlaygroundTokenStore(
  /** How long a preview token stays redeemable. Short by design — minutes, not hours. */
  public val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val maxTokens: Int = DEFAULT_MAX_TOKENS,
  /** Injected per the repo's Okio-everywhere rule; tests pass a `FakeFileSystem`. */
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  /** Injected so tests can drive expiry without sleeping. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ::randomId,
  /**
   * Called after a dropped token's work dir is deleted; Stage 2 wires it to
   * [PlaygroundRedeemService.release] to close any live session. Runs under `runCatching`. Defaults
   * to a no-op.
   */
  private val onRemove: (Token) -> Unit = {},
) {

  /**
   * A compiled snippet: everything Stage 2 needs to stand up (or replay) the preview without
   * recompiling.
   */
  public data class PlaygroundSnippet(
    val mode: PlaygroundMode,
    /** Temp root for this snippet; **deleted** when its token is dropped. */
    val workDir: Path,
    /** Compiled `.class` output, on [classpath] for the render. */
    val classesDir: Path,
    /** Full render classpath (catalog live-bundle jars + [classesDir]). */
    val classpath: List<Path>,
    /** Kotlin `MODULE_NAME` the snippet compiled under. */
    val moduleName: String,
    /** The `@Preview` id Stage 2 opens on, and the one the Stage-1 still frame draws. */
    val previewId: String,
    /**
     * Every `@Preview` the snippet declared, [previewId] first, so the redeemed session can
     * navigate between them. Defaults to just [previewId].
     */
    val previewIds: List<String> = listOf(previewId),
  )

  /** One minted token and its lifetime. */
  public data class Token(
    val id: String,
    val snippet: PlaygroundSnippet,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    /** The redeem path — the id is the capability, so this is the whole share. */
    val path: String
      get() = "/pg/$id"

    public fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    // Keyed by id, like ServeDocStore.Doc — array/path-reference equality would be surprising.
    override fun equals(other: Any?): Boolean = other is Token && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  private val tokens = ConcurrentHashMap<String, Token>()

  /**
   * Mint a token for [snippet]; the store now owns its [workDir]. [isSecurityChecked] is the
   * greppable audit marker [ServeDocStore.add] uses: pass `true` only after the route's gate.
   */
  public fun add(snippet: PlaygroundSnippet, isSecurityChecked: Boolean): Token {
    val now = clock()
    purgeExpired(now)
    val token =
      Token(
        id = mintId(),
        snippet = snippet,
        createdAtMillis = now,
        expiresAtMillis = now + ttlSeconds * 1000,
      )
    tokens[token.id] = token
    evictOverflow()
    return token
  }

  /** The live token for [id], or null when it's unknown **or expired** (expired ⇒ dropped). */
  public fun get(id: String): Token? {
    val now = clock()
    purgeExpired(now)
    return tokens[id]?.takeIf { it.expiresAtMillis > now }
  }

  /** Seconds left on [token] by the store's clock (the one that decides expiry). */
  public fun remainingSeconds(token: Token): Long = token.secondsUntilExpiry(clock())

  /** Explicitly drop [id] (and delete its work dir); returns true if it was present. */
  public fun remove(id: String): Boolean {
    val removed = tokens.remove(id) ?: return false
    drop(removed)
    return true
  }

  /** Drop every token whose TTL has run out (deleting each work dir); returns how many went. */
  public fun purgeExpired(nowMillis: Long = clock()): Int {
    var dropped = 0
    val it = tokens.entries.iterator()
    while (it.hasNext()) {
      val entry = it.next()
      if (entry.value.expiresAtMillis <= nowMillis) {
        it.remove()
        drop(entry.value)
        dropped++
      }
    }
    return dropped
  }

  /** Live tokens, soonest expiry first — for the status page. */
  public fun snapshot(): List<Token> {
    val now = clock()
    purgeExpired(now)
    return tokens.values.sortedBy { it.expiresAtMillis }
  }

  /** Drop everything (deleting every work dir) — for host shutdown. */
  public fun clear() {
    val all = tokens.values.toList()
    tokens.clear()
    all.forEach { drop(it) }
  }

  /**
   * Enforce the count cap by dropping the tokens nearest expiry (and their work dirs) rather than
   * refusing new ones.
   */
  private fun evictOverflow() {
    while (tokens.size > maxTokens) {
      val oldest = tokens.values.minByOrNull { it.expiresAtMillis } ?: return
      tokens.remove(oldest.id)?.let { drop(it) }
    }
  }

  /** The single drop path for every removal: delete the work dir, then fire [onRemove]. */
  private fun drop(token: Token) {
    disposeSnippet(token.snippet)
    runCatching { onRemove(token) }
  }

  /** Best-effort delete of a dropped snippet's work dir; a failure must not wedge purging. */
  private fun disposeSnippet(snippet: PlaygroundSnippet) {
    try {
      fileSystem.deleteRecursively(snippet.workDir, mustExist = false)
    } catch (_: Exception) {
      // Leaking one temp dir beats throwing out of a purge.
    }
  }

  public companion object {
    /** Ten minutes: long enough to click through and refresh a tab, short enough to not linger. */
    public const val DEFAULT_TTL_SECONDS: Long = 600L

    public const val DEFAULT_MAX_TOKENS: Int = 64

    /** 128 bits of [SecureRandom], base64url, `pg_`-prefixed — the id IS the capability. */
    private val random = SecureRandom()

    public fun randomId(): String {
      val bytes = ByteArray(16)
      random.nextBytes(bytes)
      return "pg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** True when [id] could be one of ours — cheap shape check before a map lookup. */
    public fun isWellFormedId(id: String): Boolean = id.matches(Regex("pg_[A-Za-z0-9_-]{16,64}"))
  }
}
