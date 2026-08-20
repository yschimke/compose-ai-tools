package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime ingestion of **client-provided documents** — a Remote Compose `.rc` or a Lottie animation
 * ([ServeDocFormats]) — held for a bounded time and handed back as an **expiring permalink**.
 *
 * This is the "I generated a document, let someone else look at it" lane, the sibling of
 * [ServeBundleStore]'s bundle ingestion: the bundle store registers a whole *preview session*; this
 * one takes a single document and returns one shareable `/d/<id>` URL that plays it in the browser
 * and then goes away. Nothing here becomes a session — a document has no previews, no daemon, and
 * no server-side render.
 *
 * Safety model:
 * - **Data-only.** Documents are descriptions of what to draw. The server never executes them; the
 *   vendored player runs in the *viewer's* browser. So an anonymous upload is safe to host.
 * - **Sniffed, not trusted.** Bytes must match a known format ([ServeDocFormats.detect]) — an
 *   arbitrary file (a zip, a script, an HTML page) is refused, so the host can't be used as a
 *   general file drop or to serve attacker-chosen HTML from its origin.
 * - **Bounded.** Per-document ([maxBytes]), count ([maxDocs]) and total-memory ([maxTotalBytes])
 *   caps, plus a [ttlSeconds] expiry — documents live in memory and are dropped on expiry, so an
 *   upload burst can't fill a disk or grow the heap without bound.
 * - **Unguessable ids.** The permalink id is 128 bits from [SecureRandom]; the id *is* the
 *   capability, so a public host's link is safe to paste to one person without also listing it.
 * - **SSRF-gated URL fetch.** `?url=` is refused unless the host is on [allowedHosts] (empty ⇒
 *   every URL refused, fail closed) — the same gate [ServeBundleStore] applies.
 */
class ServeDocStore(
  /** How long an uploaded document stays reachable. */
  val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val maxDocs: Int = DEFAULT_MAX_DOCS,
  private val maxBytes: Int = DEFAULT_MAX_DOC_BYTES,
  private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
  /** SSRF allowlist for [addFromUrl]; empty refuses every URL. */
  private val allowedHosts: List<String> = emptyList(),
  /**
   * Transport override for tests. Null ⇒ the real one-request-at-a-time HTTP transport, driven by
   * [ServeUrlFetch.followingRedirects] so every hop is allowlist-checked.
   */
  private val fetch: ((String) -> ByteArray?)? = null,
  /** Injected so tests can drive expiry without sleeping. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ::randomId,
) {

  /** One stored document and its permalink lifetime. */
  data class Doc(
    val id: String,
    /** Display name (the uploaded filename, sanitised), or the format label when none was given. */
    val name: String,
    val format: ServeDocFormat,
    val bytes: ByteArray,
    val uploadedAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    val sizeBytes: Int
      get() = bytes.size

    /** The permalink path — the id is the capability, so this is the whole share. */
    val path: String
      get() = "/d/$id"

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    // Data class over a ByteArray: identity semantics are what callers want (docs are keyed by id),
    // and the generated array-reference equals/hashCode would be surprising, so pin them to the id.
    override fun equals(other: Any?): Boolean = other is Doc && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  sealed interface Result {
    data class Ok(val doc: Doc) : Result

    data class Failed(val reason: String) : Result
  }

  private val docs = ConcurrentHashMap<String, Doc>()

  /** Whether this host will fetch a document for a client at all (a non-empty SSRF allowlist). */
  val urlFetchAllowed: Boolean
    get() = allowedHosts.isNotEmpty()

  /**
   * Store [bytes] as a document and mint its expiring permalink.
   *
   * [isSecurityChecked] is the same greppable audit marker [ServeBundleStore.add] uses (no runtime
   * enforcement): the caller passes `true` only once the request has cleared the route's policy
   * gate. The store still defends in depth — format sniff, size caps, TTL.
   *
   * [name] is the client-supplied filename; it is only ever used as a **label** (sanitised), never
   * as a path or as the format decision.
   */
  fun add(name: String?, bytes: ByteArray, isSecurityChecked: Boolean): Result {
    if (bytes.isEmpty()) return Result.Failed("empty document")
    if (bytes.size > maxBytes) {
      return Result.Failed("document exceeds ${maxBytes / (1024 * 1024)}MB")
    }
    val format =
      ServeDocFormats.detect(bytes)
        ?: return Result.Failed(
          "unrecognised document format — this host accepts ${ServeDocFormats.knownSummary()}"
        )
    val now = clock()
    purgeExpired(now)
    val doc =
      Doc(
        id = mintId(),
        name = displayName(name, format),
        format = format,
        bytes = bytes,
        uploadedAtMillis = now,
        expiresAtMillis = now + ttlSeconds * 1000,
      )
    docs[doc.id] = doc
    evictOverflow()
    return Result.Ok(doc)
  }

  /**
   * Fetch a document from [url] and [add] it — the "paste a link instead of uploading" path. Gated
   * by the [allowedHosts] SSRF allowlist, which is applied to the starting URL **and to every
   * redirect target** ([ServeUrlFetch.followingRedirects]): an allowlisted host that answers `302
   * http://169.254.169.254/…` must not be able to walk the server onto an internal address.
   */
  fun addFromUrl(name: String?, url: String, isSecurityChecked: Boolean): Result {
    if (!isAllowedUrl(url)) {
      return Result.Failed(
        "refusing to fetch $url: host is not on the --accept-docs-from allowlist"
      )
    }
    val bytes =
      try {
        fetchDocument(url)
      } catch (e: Exception) {
        return Result.Failed("could not fetch $url: ${e.message}")
      } ?: return Result.Failed("could not fetch $url")
    val label = name ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
    return add(label, bytes, isSecurityChecked = isSecurityChecked)
  }

  /**
   * The injected [fetch] when a caller supplied one (tests), else the real transport — which never
   * follows a redirect on its own; [ServeUrlFetch.followingRedirects] does that, re-checking the
   * allowlist per hop.
   */
  private fun fetchDocument(url: String): ByteArray? {
    // An injected fetcher OWNS the result, including a null one — `?:` here would treat "the
    // override reported a failure" as "there is no override" and quietly fall through to the real
    // network.
    val override = fetch
    if (override != null) return override(url)
    return ServeUrlFetch.followingRedirects(url, ::isAllowedUrl) {
      ServeUrlFetch.sendOnce(it, DEFAULT_MAX_DOC_BYTES.toLong())
    }
  }

  /** The live document for [id], or null when it's unknown **or expired** (expired ⇒ dropped). */
  fun get(id: String): Doc? {
    val now = clock()
    purgeExpired(now)
    return docs[id]?.takeIf { it.expiresAtMillis > now }
  }

  /**
   * Seconds left on [doc]'s permalink, measured on the **store's** clock — the one that decides
   * expiry. Callers must not read the wall clock themselves, or a test (or a host whose clock is
   * injected) reports a lifetime the store doesn't honour.
   */
  fun remainingSeconds(doc: Doc): Long = doc.secondsUntilExpiry(clock())

  /** Drop every document whose TTL has run out; returns how many went. */
  fun purgeExpired(nowMillis: Long = clock()): Int {
    var dropped = 0
    docs.entries.removeIf { (_, doc) ->
      (doc.expiresAtMillis <= nowMillis).also { if (it) dropped++ }
    }
    return dropped
  }

  /** Live documents, soonest expiry first — for the status page. */
  fun snapshot(): List<Doc> {
    val now = clock()
    purgeExpired(now)
    return docs.values.sortedBy { it.expiresAtMillis }
  }

  /**
   * Enforce the count + total-memory caps by dropping the documents closest to expiry first — an
   * upload burst evicts the oldest shares rather than being refused, and the heap stays bounded.
   */
  private fun evictOverflow() {
    while (docs.size > maxDocs || docs.values.sumOf { it.sizeBytes.toLong() } > maxTotalBytes) {
      val oldest = docs.values.minByOrNull { it.expiresAtMillis } ?: return
      docs.remove(oldest.id)
    }
  }

  private fun isAllowedUrl(url: String): Boolean = ServeUrlFetch.isAllowedUrl(url, allowedHosts)

  /** A safe display label: the filename's own last segment, trimmed to printable characters. */
  private fun displayName(raw: String?, format: ServeDocFormat): String {
    val candidate =
      raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.filter { it.isLetterOrDigit() || it in "._- " }
        ?.take(80)
        ?.trim()
    return candidate?.takeIf { it.isNotEmpty() } ?: "${format.label} document"
  }

  companion object {
    /** One hour: long enough to share a link in a chat, short enough that nothing lingers. */
    const val DEFAULT_TTL_SECONDS = 3600L

    const val DEFAULT_MAX_DOCS = 64
    const val DEFAULT_MAX_DOC_BYTES = 8 * 1024 * 1024
    const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 128 bits of [SecureRandom], base64url — the permalink id IS the capability. */
    fun randomId(): String = ServeCapabilityId.mint()

    /** True when [id] could be one of ours — cheap shape check before a map lookup. */
    fun isWellFormedId(id: String): Boolean = ServeCapabilityId.isWellFormed(id)
  }
}
