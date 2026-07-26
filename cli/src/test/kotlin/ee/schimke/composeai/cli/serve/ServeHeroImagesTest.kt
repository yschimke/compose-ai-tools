package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    val crop = ContentCrop(boxW = 80, boxH = 40, imgW = 400, imgH = 400, left = -100, top = -150)
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

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      renders++
      return if (previewId == "hero") RenderOutcome.Ok(bytes) else RenderOutcome.NotFound
    }

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
}
