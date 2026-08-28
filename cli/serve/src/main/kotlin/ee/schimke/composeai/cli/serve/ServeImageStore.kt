package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime ingestion of **rendered preview images** — held for a bounded time and handed back as a
 * direct, embeddable image URL.
 *
 * The lane exists for one job: an **agent preparing a pull request** has just rendered a
 * before/after pair and needs each PNG at a URL it can put in the PR body, on a box where it has
 * neither a GitHub CLI nor push rights to a capture branch (the two mechanisms `compose-preview
 * share-preview` already covers). It POSTs the bytes, gets back `https://<host>/i/<id>.png`, and
 * writes `![before](…)`.
 *
 * Sibling of [ServeDocStore], and deliberately **not** a format inside it. Both are TTL-bounded
 * in-memory shares, but the two lanes differ in every way that decides policy:
 * - **Who may write.** The document lane is an anonymous drop-box: a document is data the
 *   *viewer's* browser plays, so an open upload costs the host nothing but memory. An image is
 *   bytes this origin serves back to anyone holding the link, which is a small hosting service — so
 *   this lane is gated on a GitHub account with real access to the operator's repository
 *   ([ServeImageUploadAuth]) and is never open, not even on a `--public` box. Folding images into
 *   `--accept-docs` would have silently converted every existing document host into an open image
 *   host.
 * - **How long.** A document link is shared into a chat and used within the hour. A PR body
 *   outlives the review it was opened for, so this lane's default TTL is measured in days
 *   ([DEFAULT_TTL_SECONDS]) — see the caveat on that constant.
 *
 * What it keeps from [ServeDocStore], because those parts were right: content sniffing (an upload
 * must *be* a known raster image — [ServeImageFormats]), hard per-image / count / total-memory caps
 * with eviction rather than unbounded growth, and an unguessable id that is itself the capability.
 *
 * **No `?url=` leg.** [ServeDocStore] can fetch a document for a client behind an SSRF allowlist;
 * this store deliberately cannot. The caller here is a build agent that already holds the bytes on
 * local disk, so a server-side fetcher would add an SSRF surface to buy nothing.
 */
class ServeImageStore(
  /** How long an uploaded image stays reachable. */
  val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val maxImages: Int = DEFAULT_MAX_IMAGES,
  private val maxBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
  private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
  /** Injected so tests can drive expiry without sleeping. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ServeCapabilityId::mint,
) {

  /** One stored image and its link's lifetime. */
  class Image(
    val id: String,
    /**
     * Display label (the uploaded filename, sanitised), or the format label when none was given.
     */
    val name: String,
    val format: ServeImageFormat,
    val bytes: ByteArray,
    /**
     * The GitHub login that uploaded this, as verified at the gate. Kept so the operator can see
     * who filled the store on `/status.json` — an audit trail is the other half of a lane that
     * hands out hosting.
     */
    val uploadedBy: String,
    val uploadedAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    val sizeBytes: Int
      get() = bytes.size

    /**
     * The permalink path. It ends in the format's real extension so that everything downstream
     * which decides by suffix — a markdown renderer, an image proxy, a reader saving the file —
     * agrees with the content type served alongside it.
     */
    val path: String
      get() = "/i/$id${format.extension}"

    /** Pixel dimensions when the header declared them; null otherwise. */
    val dimensions: ServeDocSize?
      get() = format.size(bytes)

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
  }

  sealed interface Result {
    data class Ok(val image: Image) : Result

    data class Failed(val reason: String) : Result
  }

  private val images = ConcurrentHashMap<String, Image>()

  /**
   * Store [bytes] as an image and mint its expiring link.
   *
   * [isSecurityChecked] is the same greppable audit marker [ServeDocStore.add] uses (no runtime
   * enforcement): the caller passes `true` only once the request has cleared the route's identity
   * gate. The store still defends in depth — format sniff, size caps, TTL.
   *
   * [name] is the client-supplied filename; it is only ever used as a **label** (sanitised), never
   * as a path and never as the format decision.
   */
  fun add(
    name: String?,
    bytes: ByteArray,
    uploadedBy: String,
    isSecurityChecked: Boolean,
  ): Result {
    if (bytes.isEmpty()) return Result.Failed("empty image")
    if (bytes.size > maxBytes) {
      return Result.Failed("image exceeds ${maxBytes / (1024 * 1024)}MB")
    }
    val format =
      ServeImageFormats.detect(bytes)
        ?: return Result.Failed(
          "unrecognised image format — this host accepts ${ServeImageFormats.knownSummary()}"
        )
    val now = clock()
    purgeExpired(now)
    val image =
      Image(
        id = mintId(),
        name = displayName(name, format),
        format = format,
        bytes = bytes,
        uploadedBy = uploadedBy,
        uploadedAtMillis = now,
        expiresAtMillis = now + ttlSeconds * 1000,
      )
    images[image.id] = image
    evictOverflow()
    return Result.Ok(image)
  }

  /**
   * The live image for [id], or null when it's unknown **or expired** (expired ⇒ dropped).
   *
   * [extension], when given, must be the format's own — the permalink's suffix is part of the
   * address, not decoration, so `/i/<id>.jpg` for a stored PNG is a miss rather than a PNG served
   * under a JPEG name. A bare id (no suffix) still resolves, so a client that stripped it isn't
   * stranded.
   */
  fun get(id: String, extension: String? = null): Image? {
    val now = clock()
    purgeExpired(now)
    val image = images[id]?.takeIf { it.expiresAtMillis > now } ?: return null
    if (extension != null && !extension.equals(image.format.extension, ignoreCase = true)) {
      return null
    }
    return image
  }

  /**
   * Seconds left on [image]'s link, measured on the **store's** clock — the one that decides
   * expiry. Callers must not read the wall clock themselves, or a test (or a host whose clock is
   * injected) reports a lifetime the store doesn't honour.
   */
  fun remainingSeconds(image: Image): Long = image.secondsUntilExpiry(clock())

  /** Drop every image whose TTL has run out; returns how many went. */
  fun purgeExpired(nowMillis: Long = clock()): Int {
    var dropped = 0
    images.entries.removeIf { (_, image) ->
      (image.expiresAtMillis <= nowMillis).also { if (it) dropped++ }
    }
    return dropped
  }

  /** Live images, soonest expiry first — for the status page. */
  fun snapshot(): List<Image> {
    val now = clock()
    purgeExpired(now)
    return images.values.sortedBy { it.expiresAtMillis }
  }

  /** What the store currently holds, for `/status.json`. */
  fun occupancy(): Occupancy {
    val live = snapshot()
    return Occupancy(
      count = live.size,
      maxCount = maxImages,
      totalBytes = live.sumOf { it.sizeBytes.toLong() },
      maxTotalBytes = maxTotalBytes,
      uploaders = live.map { it.uploadedBy }.distinct().size,
    )
  }

  data class Occupancy(
    val count: Int,
    val maxCount: Int,
    val totalBytes: Long,
    val maxTotalBytes: Long,
    val uploaders: Int,
  )

  /**
   * Enforce the count + total-memory caps by dropping the images closest to expiry first — an
   * upload burst evicts the oldest links rather than being refused, and the heap stays bounded.
   *
   * With a TTL measured in days, "closest to expiry" is "uploaded longest ago", so a busy host
   * expires old PR evidence to make room for new. That is the intended trade and the reason the
   * total cap is generous relative to a single render.
   */
  private fun evictOverflow() {
    while (
      images.size > maxImages || images.values.sumOf { it.sizeBytes.toLong() } > maxTotalBytes
    ) {
      val oldest = images.values.minByOrNull { it.expiresAtMillis } ?: return
      images.remove(oldest.id)
    }
  }

  /** A safe display label: the filename's own last segment, trimmed to printable characters. */
  private fun displayName(raw: String?, format: ServeImageFormat): String {
    val candidate =
      raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.filter { it.isLetterOrDigit() || it in "._- " }
        ?.take(80)
        ?.trim()
    return candidate?.takeIf { it.isNotEmpty() } ?: "${format.label} image"
  }

  companion object {
    /**
     * Seven days: a PR link has to outlive the review that opened it, which an hour (the document
     * lane's default) does not.
     *
     * **It is still a TTL, and a PR body is forever.** GitHub proxies and caches embedded images
     * through camo, so a body usually keeps painting after the source expires — but that is a
     * cache, not a guarantee. For evidence that must survive indefinitely, commit the PNG to a
     * capture branch (`compose-preview share-preview`, which is SHA-pinned by design); this lane is
     * for the box where that isn't available. `--image-ttl` raises it, bounded by the memory caps
     * below.
     */
    const val DEFAULT_TTL_SECONDS = 7L * 24 * 60 * 60

    /** Roughly a large PR's worth of before/after renders, live at once. */
    const val DEFAULT_MAX_IMAGES = 256

    /** Per image. A render PNG is tens of kB; this is a ceiling on a mistake, not a target. */
    const val DEFAULT_MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** Across the whole lane — these are heap, so this number is a memory decision. */
    const val DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024
  }
}
