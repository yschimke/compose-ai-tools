package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import okio.FileSystem
import okio.Path

/**
 * The expiring **preview-token** capability behind `/pg/<token>` — the Stage-1 → Stage-2 handoff in
 * [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md).
 *
 * Sibling of [ServeDocStore]: where that holds a client-uploaded *document* and hands back a
 * `/d/<id>` playback link, this holds a **just-compiled snippet** ([PlaygroundSnippet]) and hands
 * back a `/pg/<id>` link that redeems into a live daemon session (CMP/Android) or a document
 * permalink (Remote Compose). A token is minted only after a *clean* compile, so possessing one
 * means "there are real classes on disk ready to render".
 *
 * Safety model mirrors [ServeDocStore]:
 * - **Id is the capability.** 128 bits of [SecureRandom], base64url, `pg_`-prefixed. Unguessable,
 *   so a link is safe to hand to one person without listing it.
 * - **Expiring.** After [ttlSeconds] the token is dropped and `/pg/<id>` 404s without disclosing
 *   whether the id ever existed.
 * - **Bounded.** A [maxTokens] cap evicts nearest-expiry first on overflow. Each token owns a temp
 *   work directory ([PlaygroundSnippet.workDir]); dropping the token **deletes that directory**, so
 *   the cap bounds disk, not just the map.
 *
 * Unlike [ServeDocStore] this store owns on-disk state, so every removal path (expiry, overflow,
 * explicit [remove], [clear]) routes through [disposeSnippet] to delete the work dir. The delete is
 * best-effort — a failure is swallowed so one undeletable directory can't wedge purging.
 */
class PlaygroundTokenStore(
  /** How long a preview token stays redeemable. Short by design — minutes, not hours. */
  val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val maxTokens: Int = DEFAULT_MAX_TOKENS,
  /** Injected per the repo's Okio-everywhere rule; tests pass a `FakeFileSystem`. */
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  /** Injected so tests can drive expiry without sleeping. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ::randomId,
  /**
   * Invoked as a token is dropped (expiry, overflow eviction, [remove], [clear]), after its work
   * dir is deleted. Stage 2 wires this to [PlaygroundRedeemService.release] so a dropped token also
   * unregisters + closes any live session it stood up. Best-effort — a throw here must not wedge
   * purging — so it runs under `runCatching`. Defaults to a no-op (mint-only hosts).
   */
  private val onRemove: (Token) -> Unit = {},
) {

  /**
   * A compiled snippet a token points at — everything Stage 2 needs to stand up (or, for Remote
   * Compose, replay) the preview without recompiling.
   */
  data class PlaygroundSnippet(
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
     * **Every** `@Preview` the snippet declared, [previewId] first.
     *
     * A snippet routinely declares more than one — a multi-file snippet almost always does — and
     * for a long time the live session was told about exactly one of them, so the others could be
     * compiled and then never looked at. The redeemed session's `previews.json` lists all of these,
     * which is what makes the viewer's ordinary preview navigation work on a snippet the same way
     * it works on a catalog.
     *
     * Defaults to just [previewId] so a caller that doesn't care (and every existing test) is
     * unchanged.
     */
    val previewIds: List<String> = listOf(previewId),
  )

  /** One minted token and its lifetime. */
  data class Token(
    val id: String,
    val snippet: PlaygroundSnippet,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    /** The redeem path — the id is the capability, so this is the whole share. */
    val path: String
      get() = "/pg/$id"

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    // Keyed by id, like ServeDocStore.Doc — array/path-reference equality would be surprising.
    override fun equals(other: Any?): Boolean = other is Token && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  private val tokens = ConcurrentHashMap<String, Token>()

  /**
   * Mint a token for [snippet] and return it. The store now owns [snippet]'s [workDir] and will
   * delete it on expiry/eviction/[remove]. [isSecurityChecked] is the greppable audit marker
   * [ServeDocStore.add] uses (no runtime enforcement): the caller passes `true` only once the
   * request has cleared the playground route's gate.
   */
  fun add(snippet: PlaygroundSnippet, isSecurityChecked: Boolean): Token {
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
  fun get(id: String): Token? {
    val now = clock()
    purgeExpired(now)
    return tokens[id]?.takeIf { it.expiresAtMillis > now }
  }

  /**
   * Seconds left on [token], measured on the **store's** clock — the one that decides expiry.
   * Callers must not read the wall clock themselves.
   */
  fun remainingSeconds(token: Token): Long = token.secondsUntilExpiry(clock())

  /** Explicitly drop [id] (and delete its work dir); returns true if it was present. */
  fun remove(id: String): Boolean {
    val removed = tokens.remove(id) ?: return false
    drop(removed)
    return true
  }

  /** Drop every token whose TTL has run out (deleting each work dir); returns how many went. */
  fun purgeExpired(nowMillis: Long = clock()): Int {
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
  fun snapshot(): List<Token> {
    val now = clock()
    purgeExpired(now)
    return tokens.values.sortedBy { it.expiresAtMillis }
  }

  /** Drop everything (deleting every work dir) — for host shutdown. */
  fun clear() {
    val all = tokens.values.toList()
    tokens.clear()
    all.forEach { drop(it) }
  }

  /**
   * Enforce the count cap by dropping the tokens closest to expiry first — a burst evicts the
   * oldest shares (deleting their work dirs) rather than being refused, so disk + heap stay
   * bounded.
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
      // The directory may already be gone, or held open on Windows; leaking one temp dir is
      // strictly better than throwing out of a purge and stranding the rest.
    }
  }

  companion object {
    /** Ten minutes: long enough to click through and refresh a tab, short enough to not linger. */
    const val DEFAULT_TTL_SECONDS = 600L

    const val DEFAULT_MAX_TOKENS = 64

    /** 128 bits of [SecureRandom], base64url, `pg_`-prefixed — the id IS the capability. */
    private val random = SecureRandom()

    fun randomId(): String {
      val bytes = ByteArray(16)
      random.nextBytes(bytes)
      return "pg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** True when [id] could be one of ours — cheap shape check before a map lookup. */
    fun isWellFormedId(id: String): Boolean = id.matches(Regex("pg_[A-Za-z0-9_-]{16,64}"))
  }
}
