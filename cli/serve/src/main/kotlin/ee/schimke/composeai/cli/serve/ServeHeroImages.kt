package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.computeThumbCrop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Prebaked thumbnails: the public front door's hero cards ([heroFor]) and the catalog grid's
 * component cards ([gridThumbFor]).
 *
 * The home page used to point each system card's `<img>` straight at `/<system>/render/<id>.png` —
 * the same lane the catalogs use. That made the landing the most expensive page on the server: a
 * dozen cards meant a dozen requests that each leased a session, took a render-slot permit, and
 * read a **full-resolution** render off disk (a phone screenshot hero is ~260 kB for a card that
 * displays it at ~256 CSS px), with no cache headers at all — so every visit and every reload paid
 * the whole cost again.
 *
 * A hero is a fixed picture of a published catalog: it only changes when the catalog is
 * republished. So bake it once and serve it like the static asset it is:
 * - **Cropped and downscaled at bake time.** The card's content-crop ([ContentCrop], which the page
 *   used to emulate with a CSS clip window around the full render) is baked into the pixels, and
 *   the result is scaled to [DISPLAY_CAP] × [PIXEL_SCALE] — enough for a 2× display, a fraction of
 *   the bytes. Never upscaled past the source region's own pixels.
 * - **Content-addressed.** The file name is a hash of the baked bytes, so a republished catalog
 *   gets a new URL and the old one can be cached forever ([ServeHttpServer] serves the `/hero/`
 *   lane `immutable`). No revalidation, no request on a repeat visit.
 * - **Held in memory.** Serving is a map lookup and a byte-array write: no session lease, no render
 *   permit, no disk read, nothing that can queue behind a catalog render.
 *
 * Baking happens off the home-page path, when a catalog host is first seen (see
 * [ServeHttpServer.rememberCatalogMeta]), and is memoised per host instance — a catalog refresh
 * installs a fresh host, which re-bakes under a new hash.
 *
 * The **catalog grid** ([gridThumbFor]) has the same problem an order of magnitude larger — a
 * catalog page is ~42 cards, not a dozen, and its full-resolution renders add up to a couple of MB
 * — so it gets the same treatment with two deliberate differences:
 * - **The crop is NOT baked in.** A grid card's content-crop stays the CSS clip window
 *   ([ServeWeb.thumbImg]), because that card is re-pointed at a *full* render when the visitor
 *   picks a declared theme; baking the crop into the thumbnail would leave the themed render
 *   uncropped and the card would change shape under the visitor. The crop still sets the *scale* —
 *   the render is downscaled so the visible region, not the whole canvas, lands at the card's cap —
 *   so a sticker's cropped component is as crisp as an uncropped screenshot. This works because
 *   [ServeWeb.thumbImg]'s clip geometry is expressed in percentages, so it is resolution
 *   independent: the same window frames the thumbnail and the full render identically.
 * - **Served through the render lane**, as `/render/<id>.png?thumb=<hash>` rather than a route of
 *   its own ([ServeHttpServer.handleRender] answers it before admission). A catalog grid is served
 *   under both the public `/<system>/` prefix and a plain single-module session, and only the
 *   render lane already exists in both; the hash rides as a query param so the URL still changes
 *   when the pixels do, which is what makes the response `immutable`.
 */
class ServeHeroImages {

  /** One baked hero: the bytes to serve, how to name/validate them, and the size to lay out. */
  data class Hero(
    /** Baked PNG bytes — cropped, downscaled, ready to write to the socket. */
    val bytes: ByteArray,
    /** Content hash of [bytes]; the `/hero/<system>/<fileName>` segment and the ETag. */
    val fileName: String,
    /** Strong ETag (the quoted hash) for conditional requests. */
    val etag: String,
    /** CSS-pixel width the card lays the hero out at (the baked width ÷ [PIXEL_SCALE]). */
    val cssWidth: Int,
    /** CSS-pixel height the card lays the hero out at. */
    val cssHeight: Int,
  ) {
    // Data class over a ByteArray: identity equality is what callers want (heroes are compared by
    // hash, never by content), and the generated array-identity equals/hashCode would be a trap.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * One baked catalog-grid thumbnail. Unlike a [Hero] it carries no layout size — the card's CSS
   * (and, for a framed card, the clip window) sizes it exactly as it sized the full render — and no
   * file name, because it is served from the render lane under its own preview id with [hash] as
   * the cache-busting query param.
   */
  data class Thumb(val bytes: ByteArray, val hash: String, val etag: String) {
    // See [Hero]: identity equality, for the same reason.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * Every hero baked in this process, keyed by [Hero.fileName]. The `/hero/` route resolves purely
   * through this map, so a URL minted before a catalog refresh keeps serving the exact bytes it was
   * hashed from instead of 404ing under an already-open tab. Heroes are a few kB each and bounded
   * by the catalog count × refreshes, so nothing is evicted.
   */
  private val byFileName = ConcurrentHashMap<String, Hero>()

  /**
   * Memo of the bake, per host **object** then per preview id — so the decode + scale runs once per
   * catalog, and a refreshed catalog (which installs a fresh host) re-bakes. A bake that fails
   * caches its failure too, so a corrupt PNG isn't retried on every hit.
   *
   * The outer map is a [java.util.WeakHashMap] keyed by the host itself, deliberately, rather than
   * by something derived from it. A host has no stable id of its own, and the obvious stand-in —
   * `System.identityHashCode` — is *not* unique: it can collide between live objects, and the JVM
   * may hand a fresh object the value a collected one used to have. Either would let a republished
   * catalog inherit the previous host's hero and serve stale front-door imagery indefinitely.
   * Keying on the object gives true identity (these hosts don't override `equals`), and the weak
   * key ties each entry's lifetime to its host, so a retired catalog's memo is collected with it
   * instead of accumulating across refreshes. `WeakHashMap` isn't thread-safe, hence the lock —
   * uncontended in practice, since it only guards resolving a host to its (concurrent) per-preview
   * map, not the bake.
   */
  private val baked = WeakHashMap<ServeHost, ConcurrentHashMap<String, Optional<Hero>>>()

  private val bakedLock = Any()

  /**
   * The grid-thumbnail counterpart of [baked] — same weak-per-host, concurrent-per-preview shape
   * and the same reasoning, kept separate because the two lanes bake the same preview differently
   * (a hero has its crop in the pixels, a grid thumbnail does not).
   *
   * These are held only here, never in [byFileName]: a grid thumbnail's URL names its preview, so
   * the render lane re-resolves it through this memo against the *current* host, and a retired
   * catalog's few hundred thumbnails are collected with it. A hero, whose URL is the hash alone,
   * has to stay resolvable after a refresh and so is retained; at ~40× the count per catalog,
   * retaining grid thumbnails the same way would be a slow leak rather than a rounding error.
   */
  private val gridBaked = WeakHashMap<ServeHost, ConcurrentHashMap<String, Optional<Thumb>>>()

  private val gridLock = Any()

  /**
   * The hero for [previewId] on [host], baking it on first sight. [crop] is the card's content-crop
   * (baked into the pixels here, so the page needs no CSS clip window). Returns null when the host
   * has no such render or the PNG can't be decoded — the caller then falls back to the plain
   * `/render/` lane.
   */
  fun heroFor(host: ServeHost, previewId: String, crop: ContentCrop?): Hero? {
    val perHost = synchronized(bakedLock) { baked.getOrPut(host) { ConcurrentHashMap() } }
    // The bake itself runs outside the lock (it decodes and rescales a full render); worst case
    // two callers racing the same cold catalog bake it twice and agree on the result, which is
    // content-hashed and therefore identical.
    perHost[previewId]?.let {
      return it.orElse(null)
    }
    val png = (host.render(previewId, EMPTY_OVERRIDES) as? RenderOutcome.Ok)?.png
    val hero = png?.let { bake(it, crop) }
    perHost[previewId] = Optional.ofNullable(hero)
    return hero
  }

  /** The baked hero a `/hero/<system>/<fileName>` request names, or null when unknown. */
  fun byFileName(fileName: String): Hero? = byFileName[fileName]

  /**
   * The prebaked **catalog-grid** thumbnail for [previewId] on [host] — downscaled so that the
   * region [crop] frames (the whole render when there's no crop) lands at the card's cap. Null when
   * the host has no locally-baked pixels for [previewId] yet, or they can't be decoded; the card
   * then points at the plain `/render/` lane and picks the thumbnail up on a later page build.
   *
   * Sourced from [ServeHost.bakedRender] — **never** [ServeHost.render]. This is called once per
   * card while the catalog page's HTML is being built, on the request thread: `render` fetches a
   * cold preview over the network (images are fetched lazily), so routing it through here would
   * turn one page build into dozens of serial round-trips. `bakedRender` answers from local pixels
   * or not at all, which is exactly the contract this needs.
   *
   * A *missing* PNG is deliberately not memoised (only a decode failure is): a catalog fills its
   * images in after it loads, so a card that had no pixels on the first page build must be able to
   * gain a thumbnail on the next one rather than staying full-resolution until the catalog is
   * republished.
   */
  fun gridThumbFor(host: ServeHost, previewId: String, crop: ContentCrop?): Thumb? {
    val perHost = synchronized(gridLock) { gridBaked.getOrPut(host) { ConcurrentHashMap() } }
    perHost[previewId]?.let {
      return it.orElse(null)
    }
    val png = host.bakedRender(previewId, EMPTY_OVERRIDES)?.png ?: return null
    val thumb = bakeGridThumb(png, crop)
    perHost[previewId] = Optional.ofNullable(thumb)
    return thumb
  }

  /**
   * Bake [png] into a grid thumbnail: scale the **whole** render (the [crop] window stays in CSS,
   * see the class doc) down by the factor that puts the cropped region at [DISPLAY_CAP] ×
   * [PIXEL_SCALE], and content-hash the result. Null when the bytes aren't a decodable image.
   *
   * Keeps whichever of the two encodings is smaller. A render that's already small enough needs no
   * scaling, and even one that is scaled can re-encode larger than the tightly-packed PNG the
   * renderer wrote — this lane exists to cut bytes, so it never ships more of them than it started
   * with. The hash covers the bytes actually served either way.
   *
   * Visible only for tests.
   */
  internal fun bakeGridThumb(png: ByteArray, crop: ContentCrop?): Thumb? {
    val src = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
    if (src.width <= 0 || src.height <= 0) return null
    val region = sourceRegion(src.width, src.height, crop)
    val fit = min(1.0, DISPLAY_CAP * PIXEL_SCALE / max(region.w, region.h).toDouble())
    val scaled =
      if (fit >= 1.0) null
      else
        encodePng(
          drawRegion(
            src,
            Region(0, 0, src.width, src.height),
            max(1, (src.width * fit).roundToInt()),
            max(1, (src.height * fit).roundToInt()),
          )
        )
    val bytes = if (scaled != null && scaled.size < png.size) scaled else png
    val hash = sha256Hex(bytes).take(HASH_CHARS)
    return Thumb(bytes = bytes, hash = hash, etag = "\"$hash\"")
  }

  /**
   * Bake [png] into a hero: apply [crop] (if any) to the source pixels, scale the result down to at
   * most [DISPLAY_CAP] × [PIXEL_SCALE] on its largest edge, re-encode as PNG, and register it under
   * its content hash. Null when the bytes aren't a decodable image.
   *
   * Visible only for tests.
   */
  internal fun bake(png: ByteArray, crop: ContentCrop?): Hero? {
    val src = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
    if (src.width <= 0 || src.height <= 0) return null
    // A hero bakes its crop into the pixels, and a capture-gutter window is the one crop that must
    // not be: the pixels it leaves outside the box are the component's shadow (see
    // [ContentCrop.clip]). A hero is one image rather than a row to line up, so it keeps its whole
    // canvas — the size comparison this window exists for does not arise there.
    val region = sourceRegion(src.width, src.height, crop?.takeIf { it.clip })
    // The CSS size is the region fitted into the card's cap (never upscaled) — the same size the
    // browser used to compute for itself. The baked raster is PIXEL_SCALE times that, so a 2×
    // display still gets crisp pixels, but never more than the region actually has.
    val fit = min(1.0, DISPLAY_CAP / max(region.w, region.h).toDouble())
    val cssW = max(1, (region.w * fit).roundToInt())
    val cssH = max(1, (region.h * fit).roundToInt())
    val bakedW = max(1, min(region.w, cssW * PIXEL_SCALE))
    val bakedH = max(1, min(region.h, cssH * PIXEL_SCALE))
    val bytes = encodePng(drawRegion(src, region, bakedW, bakedH)) ?: return null
    val hash = sha256Hex(bytes).take(HASH_CHARS)
    val hero =
      Hero(
        bytes = bytes,
        fileName = "$hash.png",
        etag = "\"$hash\"",
        cssWidth = cssW,
        cssHeight = cssH,
      )
    // putIfAbsent, not put: two catalogs whose heroes bake to identical bytes share the URL, and
    // the first registration is already the right answer.
    return byFileName.putIfAbsent(hero.fileName, hero) ?: hero
  }

  /**
   * The rectangle of the source render a hero shows. Without a [crop] that's the whole image; with
   * one it's the component box the card's CSS clip window used to frame.
   *
   * [ContentCrop] is expressed in *display* pixels: the render is drawn at `imgW` wide and shifted
   * by `(left, top)` under a `boxW`×`boxH` window. Dividing back through by the display scale
   * (`imgW / renderW`) recovers the region in the render's own pixels. Clamped to the image so a
   * rounded or stale crop can't ask for pixels that aren't there.
   */
  private fun sourceRegion(renderW: Int, renderH: Int, crop: ContentCrop?): Region {
    if (crop == null || crop.imgW <= 0) return Region(0, 0, renderW, renderH)
    val scale = crop.imgW.toDouble() / renderW
    if (scale <= 0.0) return Region(0, 0, renderW, renderH)
    val x = (-crop.left / scale).roundToInt().coerceIn(0, renderW - 1)
    val y = (-crop.top / scale).roundToInt().coerceIn(0, renderH - 1)
    val w = (crop.boxW / scale).roundToInt().coerceIn(1, renderW - x)
    val h = (crop.boxH / scale).roundToInt().coerceIn(1, renderH - y)
    return Region(x, y, w, h)
  }

  /** Draw [region] of [src] into a fresh [w]×[h] image, smoothly (this is always a downscale). */
  private fun drawRegion(src: BufferedImage, region: Region, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.drawImage(
        src,
        0,
        0,
        w,
        h,
        region.x,
        region.y,
        region.x + region.w,
        region.y + region.h,
        null,
      )
    } finally {
      g.dispose()
    }
    return out
  }

  /** [image] as PNG bytes, or null if the encoder refuses it. */
  private fun encodePng(image: BufferedImage): ByteArray? {
    val buffer = ByteArrayOutputStream()
    if (!runCatching { ImageIO.write(image, "png", buffer) }.getOrDefault(false)) return null
    return buffer.toByteArray()
  }

  private data class Region(val x: Int, val y: Int, val w: Int, val h: Int)

  companion object {
    /**
     * Largest CSS edge a hero is laid out at — the card's own ceiling (`.cp-imgwrap img {
     * max-height: 240px }`, and the [computeThumbCrop] cap a cropped thumbnail was already scaled
     * to).
     */
    const val DISPLAY_CAP = 240

    /** Raster oversampling, so a 2× (retina) display gets real pixels rather than a blur. */
    const val PIXEL_SCALE = 2

    /** Hex characters of the content hash kept in the file name — collision-proof at this scale. */
    private const val HASH_CHARS = 16

    /** The URL prefix the baked heroes are served under. See [ServeHttpServer]'s `/hero/` route. */
    const val PATH_PREFIX = "/hero"

    /**
     * Query parameter carrying a grid thumbnail's [Thumb.hash] on the render lane. Present ⇒ the
     * card wants the prebaked thumbnail; the hash makes the URL change when the pixels do, so the
     * response can be `immutable`.
     */
    const val THUMB_PARAM = "thumb"

    private val EMPTY_OVERRIDES = PreviewOverrides()

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
