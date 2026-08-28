package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.CropOffset
import ee.schimke.composeai.imagecrop.CropSize
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The front door's hero thumbnails are **prebaked**: cropped, downscaled and content-hashed once,
 * then served from memory off an immutable URL. This pins the properties the landing's speed rests
 * on — the bytes actually shrink, the crop lands on the component, the hash is stable and content-
 * addressed, and the bake happens once per catalog rather than once per visitor.
 */
class ServeHeroImagesTest {

  private fun png(width: Int, height: Int, paint: (BufferedImage) -> Unit = {}): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    // A gradient, not a flat fill: a flat image compresses to nothing, which would make the
    // "smaller than the source" assertion vacuous.
    for (y in 0 until height) {
      for (x in 0 until width) {
        img.setRGB(x, y, Color(x * 7 % 256, y * 11 % 256, (x + y) % 256).rgb)
      }
    }
    g.dispose()
    paint(img)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))!!

  /**
   * A render-shaped image: flat panels on a plain surface, which is how a real preview compresses.
   * [png]'s per-pixel gradient is deliberately the opposite — incompressible noise — which makes it
   * the right source for "does the bake shrink it" but the wrong one anywhere the *chosen* encoding
   * matters: a modest downscale of pure noise can legitimately encode larger than the original, and
   * the bake then keeps the original (see `a render already small enough…`).
   */
  private fun uiPng(width: Int, height: Int): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(250, 250, 250)
    g.fillRect(0, 0, width, height)
    var row = 0
    while (row * height / 10 < height) {
      g.color = Color(30 + row * 37 % 200, 60 + row * 53 % 180, 90 + row * 29 % 160)
      g.fillRect(width / 10, row * height / 10 + height / 40, width * 8 / 10, height / 16)
      row++
    }
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  @Test
  fun `a full-resolution render bakes down to a card-sized thumbnail`() {
    val source = png(1000, 2000)
    val hero = ServeHeroImages().bake(source, crop = null)!!
    // The card lays it out at the display cap on the long edge…
    assertEquals(240, hero.cssHeight, "laid out at the card's height cap")
    assertEquals(120, hero.cssWidth, "aspect ratio preserved")
    // …and the raster is 2x that, so a retina display gets real pixels.
    val baked = decode(hero.bytes)
    assertEquals(480, baked.height, "rasterised at 2x the layout size")
    assertEquals(240, baked.width)
    assertTrue(
      hero.bytes.size < source.size / 4,
      "the baked hero is a fraction of the full render (${hero.bytes.size} vs ${source.size} bytes)",
    )
  }

  @Test
  fun `a small render is never upscaled past its own pixels`() {
    val hero = ServeHeroImages().bake(png(40, 20), crop = null)!!
    val baked = decode(hero.bytes)
    assertEquals(40, baked.width, "no upscaling — a tiny component keeps its native pixels")
    assertEquals(20, baked.height)
  }

  @Test
  fun `the content crop is baked into the pixels, not left to the page`() {
    // A 400x400 canvas (a Wear-sticker-shaped render) with the component — a solid red box — at
    // (100, 150), 80x40. The crop frames exactly that, the way `computeThumbCrop` would: unscaled
    // (the box is under the 240 cap), so the render is offset by the negated box origin.
    val source =
      png(400, 400) { img ->
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(100, 150, 80, 40)
        g.dispose()
      }
    val crop =
      ContentCrop(
        window = CropSize(80, 40),
        render = CropSize(400, 400),
        offset = CropOffset(-100, -150),
      )
    val hero = ServeHeroImages().bake(source, crop)!!
    assertEquals(80, hero.cssWidth, "laid out at the component box, not the canvas")
    assertEquals(40, hero.cssHeight)
    val baked = decode(hero.bytes)
    assertEquals(80, baked.width)
    assertEquals(40, baked.height)
    // Every corner is the component's own red: the empty canvas around it is gone from the bytes.
    for ((x, y) in listOf(0 to 0, 79 to 0, 0 to 39, 79 to 39, 40 to 20)) {
      assertEquals(
        Color.RED.rgb,
        baked.getRGB(x, y),
        "($x,$y) shows the component, not the canvas around it",
      )
    }
  }

  @Test
  fun `heroes are content-addressed so their URLs can be cached forever`() {
    val heroes = ServeHeroImages()
    val a = heroes.bake(png(300, 300), crop = null)!!
    val again = heroes.bake(png(300, 300), crop = null)!!
    val different = heroes.bake(png(300, 301), crop = null)!!
    assertEquals(a.fileName, again.fileName, "identical pixels hash to the same immutable URL")
    assertNotEquals(a.fileName, different.fileName, "different pixels get a different URL")
    assertEquals("\"${a.fileName.removeSuffix(".png")}\"", a.etag, "the ETag is the content hash")
    assertTrue(a.fileName.endsWith(".png"))
  }

  @Test
  fun `a baked hero is resolvable by file name, and an unknown name is not`() {
    val heroes = ServeHeroImages()
    val hero = heroes.bake(png(120, 120), crop = null)!!
    assertSame(hero, heroes.byFileName(hero.fileName), "the /hero route resolves it by name")
    assertNull(heroes.byFileName("deadbeef.png"), "an unknown name has nothing to serve")
  }

  @Test
  fun `undecodable bytes bake to nothing so the card can fall back`() {
    assertNull(ServeHeroImages().bake("not a png".encodeToByteArray(), crop = null))
  }

  /** A host that counts how many times its render lane was asked for the hero's bytes. */
  private class CountingHost(private val bytes: ByteArray) : ServeHost {
    override val previews = listOf(ServePreview(id = "hero", label = "Hero"))
    override val label = "counting"
    var renders = 0

    /**
     * Pixels already on disk, as [bakedRender] answers them — null models a card whose image the
     * catalog hasn't filled in yet. Deliberately separate from [bytes]: the grid lane must be shown
     * to read *this*, never the render lane, which would fetch.
     */
    var baked: ByteArray? = null

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      renders++
      return if (previewId == "hero") RenderOutcome.Ok(bytes) else RenderOutcome.NotFound
    }

    override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
      baked?.takeIf { previewId == "hero" }?.let { RenderOutcome.Ok(it) }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {}
  }

  @Test
  fun `the bake runs once per catalog, not once per visitor`() {
    val heroes = ServeHeroImages()
    val host = CountingHost(png(500, 500))
    val first = heroes.heroFor(host, "hero", crop = null)!!
    repeat(20) { assertSame(first, heroes.heroFor(host, "hero", crop = null)) }
    assertEquals(1, host.renders, "20 front-door hits cost the catalog a single read")
  }

  @Test
  fun `a republished catalog re-bakes under a fresh host`() {
    val heroes = ServeHeroImages()
    val before = heroes.heroFor(CountingHost(png(500, 500)), "hero", crop = null)!!
    val after = heroes.heroFor(CountingHost(png(500, 480)), "hero", crop = null)!!
    assertNotEquals(before.fileName, after.fileName, "new pixels, new immutable URL")
    // The old URL keeps resolving, so a page already open in a browser doesn't break mid-refresh.
    assertSame(before, heroes.byFileName(before.fileName))
    assertSame(after, heroes.byFileName(after.fileName))
  }

  @Test
  fun `the memo is per host object, so two catalogs never share a bake`() {
    // Same pixels, same preview id, two distinct hosts: each must be asked for its own bytes. The
    // memo is keyed on the host OBJECT for exactly this reason — anything derived from it (an
    // identity hash, say) can repeat across instances and would silently serve one catalog's hero
    // for another's.
    val heroes = ServeHeroImages()
    val png = png(200, 200)
    val a = CountingHost(png)
    val b = CountingHost(png)
    assertEquals(heroes.heroFor(a, "hero", null), heroes.heroFor(a, "hero", null))
    heroes.heroFor(b, "hero", crop = null)
    assertEquals(1, a.renders, "the first host is read once")
    assertEquals(1, b.renders, "the second host is read on its own account, not off the first")
  }

  @Test
  fun `a preview the host cannot render bakes to nothing`() {
    val heroes = ServeHeroImages()
    assertNull(heroes.heroFor(CountingHost(png(100, 100)), "missing", crop = null))
  }

  @Test
  fun `a grid card ships a downscaled copy of its render, not the full one`() {
    val source = png(1000, 2000)
    val thumb = ServeHeroImages().bakeGridThumb(source, crop = null)!!
    val baked = decode(thumb.bytes)
    // Scaled so the long edge lands at the card cap × the retina factor — and no further.
    assertEquals(480, baked.height, "rasterised at 2x the card's 240px cap")
    assertEquals(240, baked.width, "aspect ratio preserved")
    assertTrue(
      thumb.bytes.size < source.size / 4,
      "the card ships a fraction of the render (${thumb.bytes.size} vs ${source.size} bytes)",
    )
  }

  @Test
  fun `a framed card keeps its whole canvas, leaving the crop to the page`() {
    // The crop window shows a 1000x1000 component on a 2000x2000 canvas. A HERO would bake those
    // pixels down to the component alone; a grid card must not, because the same <img> is
    // re-pointed
    // at a full, uncropped render the moment the visitor picks a declared theme — the page's clip
    // window has to keep framing both identically.
    val crop =
      ContentCrop(
        window = CropSize(1000, 1000),
        render = CropSize(2000, 2000),
        offset = CropOffset(-500, -500),
      )
    val baked = decode(ServeHeroImages().bakeGridThumb(uiPng(2000, 2000), crop)!!.bytes)
    // The visible region — not the canvas — is what lands at the cap, so the cropped component is
    // as crisp as an uncropped one; the canvas around it rides along at the same scale.
    assertEquals(960, baked.width, "the whole canvas is still there, at the region's scale")
    assertEquals(960, baked.height)
  }

  @Test
  fun `a render already small enough is served as-is rather than re-encoded larger`() {
    // Re-encoding costs CPU and can *grow* a tightly-packed PNG. This lane exists to cut bytes, so
    // it must never ship more of them than the render it replaces.
    val source = png(120, 120)
    val thumb = ServeHeroImages().bakeGridThumb(source, crop = null)!!
    assertTrue(thumb.bytes.size <= source.size, "never bigger than the render it stands in for")
    assertEquals(120, decode(thumb.bytes).width, "and never upscaled")
  }

  @Test
  fun `grid thumbnails are content-addressed, so their URLs can be cached forever`() {
    val thumbs = ServeHeroImages()
    val a = thumbs.bakeGridThumb(png(600, 600), crop = null)!!
    val again = thumbs.bakeGridThumb(png(600, 600), crop = null)!!
    val different = thumbs.bakeGridThumb(png(600, 601), crop = null)!!
    assertEquals(a.hash, again.hash, "identical pixels hash to the same immutable URL")
    assertNotEquals(a.hash, different.hash, "different pixels get a different URL")
    assertEquals("\"${a.hash}\"", a.etag, "the ETag is the content hash")
  }

  @Test
  fun `baking a grid thumbnail reads local pixels and never the render lane`() {
    // The catalog page bakes one of these per card while building its HTML, on the request thread.
    // `render` fetches a cold preview over the network, so a page build that reached it would turn
    // into dozens of serial round-trips — the whole reason this lane is on `bakedRender`.
    val thumbs = ServeHeroImages()
    val host = CountingHost(png(500, 500)).apply { baked = png(500, 500) }
    val first = thumbs.gridThumbFor(host, "hero", crop = null)!!
    repeat(20) { assertSame(first, thumbs.gridThumbFor(host, "hero", crop = null)) }
    assertEquals(0, host.renders, "building a page never reaches the fetching lane")
  }

  @Test
  fun `a card with no pixels yet gains a thumbnail once they land`() {
    // Images are filled in after a catalog loads. A miss must not be memoised, or a card that was
    // cold on the first page build would stay full-resolution until the catalog is republished.
    val thumbs = ServeHeroImages()
    val host = CountingHost(png(500, 500))
    assertNull(thumbs.gridThumbFor(host, "hero", crop = null), "nothing baked locally yet")
    host.baked = png(500, 500)
    assertNotNull(thumbs.gridThumbFor(host, "hero", crop = null), "picked up on the next build")
  }

  @Test
  fun `undecodable pixels bake to no thumbnail so the card keeps the render lane`() {
    assertNull(ServeHeroImages().bakeGridThumb("not a png".encodeToByteArray(), crop = null))
  }
}
