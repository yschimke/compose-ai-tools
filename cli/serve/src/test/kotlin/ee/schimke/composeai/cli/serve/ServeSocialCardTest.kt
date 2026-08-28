package ee.schimke.composeai.cli.serve

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
 * The drawn link-unfurl card ([ServeSocialCard]).
 *
 * This pins the properties the fix rests on — the card is the shape a large-image unfurl actually
 * gets laid out at, it really does composite the catalogs' own renders rather than just setting
 * type, it never blows a small thumbnail up to fill the frame, and it is content-addressed and
 * drawn once per distinct input rather than per request.
 */
class ServeSocialCardTest {

  /** A solid [color] PNG. Solid so a later pixel count over the card is exact, not approximate. */
  private fun png(width: Int, height: Int, color: Color): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  private fun hero(
    width: Int,
    height: Int,
    color: Color = MARKER,
    name: String = "$width-$height-${color.rgb}",
  ) = ServeHeroImages.Hero(png(width, height, color), "$name.png", "\"$name\"", width, height)

  private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))!!

  /** How many pixels of the card are exactly [MARKER] — i.e. came from a hero's interior. */
  private fun markerPixels(card: ServeSocialCard.Card): Int {
    val img = decode(card.bytes)
    var count = 0
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        if (img.getRGB(x, y) and 0xffffff == MARKER.rgb and 0xffffff) count++
      }
    }
    return count
  }

  private val spec =
    ServeSocialCard.Spec(title = "Design systems", subtitle = "21 catalogs · 1391 previews")

  /**
   * The whole point of the card: it is 1200×630, the size every consumer lays a large-image unfurl
   * out at. The featured render it replaced was 1078×2399, and was cropped to a band because of it.
   */
  @Test
  fun `a card is exactly the shape a large-image unfurl is laid out at`() {
    val card = assertNotNull(ServeSocialCard().cardFor(spec))

    assertEquals(1200, card.width)
    assertEquals(630, card.height)
    val image = decode(card.bytes)
    assertEquals(1200, image.width, "declared width matches the pixels")
    assertEquals(630, image.height, "declared height matches the pixels")
  }

  /** A card is a picture of the catalogs, not just type: their own renders are composited in. */
  @Test
  fun `the catalogs' renders are drawn onto the card`() {
    val cards = ServeSocialCard()
    val withArt = assertNotNull(cards.cardFor(spec.copy(heroes = listOf(hero(216, 480)))))
    val textOnly = assertNotNull(cards.cardFor(spec))

    assertTrue(markerPixels(withArt) > 10_000, "the hero's pixels are on the card")
    assertEquals(0, markerPixels(textOnly), "…and a card with no heroes has none of them")
    assertNotEquals(withArt.fileName, textOnly.fileName)
  }

  /**
   * A hero is baked at up to 480px on its longest edge, and the art column is taller than that, so
   * a naive fit would *upscale* — most visibly turning a 480² watch face into a soft half-card
   * slab. The drawn area must stay the source's own size.
   */
  @Test
  fun `a thumbnail is never upscaled past its own pixels`() {
    val card = assertNotNull(ServeSocialCard().cardFor(spec.copy(heroes = listOf(hero(100, 100)))))

    // 100×100 minus the four rounded corners the card clips it with (radius 10 ⇒ ~86px).
    val drawn = markerPixels(card)
    assertTrue(
      drawn in 9_500..10_000,
      "a 100×100 hero draws at ~100×100, not stretched to the column: was $drawn",
    )
  }

  /** Wide heroes are capped too, so one square catalog can't take the composition over. */
  @Test
  fun `a square thumbnail is capped at the width the front door lays it out at`() {
    val card = assertNotNull(ServeSocialCard().cardFor(spec.copy(heroes = listOf(hero(480, 480)))))

    val drawn = markerPixels(card)
    val cap = ServeHeroImages.DISPLAY_CAP
    assertTrue(drawn < cap * cap + 1, "a 480² hero is drawn no larger than ${cap}²; was $drawn")
    assertTrue(drawn > (cap - 4) * (cap - 4), "…and it is drawn at that cap, not smaller: $drawn")
  }

  /**
   * Beyond [ServeSocialCard.MAX_HEROES] nothing is drawn — the extras are dropped, not squeezed.
   */
  @Test
  fun `only the first few thumbnails are drawn`() {
    val cards = ServeSocialCard()
    val heroes = List(5) { hero(216, 480, name = "hero-$it") }

    val all = assertNotNull(cards.cardFor(spec.copy(heroes = heroes)))
    val capped =
      assertNotNull(cards.cardFor(spec.copy(heroes = heroes.take(ServeSocialCard.MAX_HEROES))))

    assertEquals(capped.fileName, all.fileName, "the extra heroes change nothing about the card")
  }

  /**
   * Drawn once per distinct input. A card per request would rasterize 1200×630 on every unfurl and,
   * because nothing is evicted, grow the registry without bound.
   */
  @Test
  fun `a card is drawn once per distinct spec`() {
    val cards = ServeSocialCard()
    val first = assertNotNull(cards.cardFor(spec))

    assertSame(first, cards.cardFor(spec), "the same spec is answered from the memo")
    assertNotEquals(
      first.fileName,
      assertNotNull(cards.cardFor(spec.copy(subtitle = "22 catalogs · 1400 previews"))).fileName,
      "a changed count is a changed card",
    )
    assertNotEquals(
      first.fileName,
      assertNotNull(cards.cardFor(spec.copy(title = "Wear Compose Material 3"))).fileName,
    )
  }

  /**
   * Two specs that happen to draw the same pixels share one URL. Beyond saving a few kB this is
   * what keeps a catalog *refresh* — which mints fresh [ServeHeroImages.Hero] objects for unchanged
   * bytes — from minting a new card URL each time.
   */
  @Test
  fun `specs that draw identically share one card`() {
    val cards = ServeSocialCard()
    val bytes = png(216, 480, MARKER)
    val first = ServeHeroImages.Hero(bytes, "before.png", "\"before\"", 216, 480)
    val second = ServeHeroImages.Hero(bytes, "after.png", "\"after\"", 216, 480)

    assertSame(
      cards.cardFor(spec.copy(heroes = listOf(first))),
      cards.cardFor(spec.copy(heroes = listOf(second))),
    )
  }

  /** The `/social/` route resolves a card purely by name, and refuses one it never drew. */
  @Test
  fun `a drawn card is resolvable by name and nothing else is`() {
    val cards = ServeSocialCard()
    val card = assertNotNull(cards.cardFor(spec))

    assertSame(card, cards.byFileName(card.fileName))
    assertEquals("\"${card.fileName.removeSuffix(".png")}\"", card.etag, "the ETag is the hash")
    assertNull(cards.byFileName("0000000000000000.png"))
  }

  /**
   * Operator-supplied text of any length still produces a card. A catalog title is data, so the
   * headline has to survive one long enough that no font size wraps it — the layout ellipsizes
   * rather than overflowing the frame or failing the bake.
   */
  @Test
  fun `text that cannot be wrapped still draws a card`() {
    val cards = ServeSocialCard()

    val long =
      assertNotNull(
        cards.cardFor(
          ServeSocialCard.Spec(
            title = "Supercalifragilisticexpialidociousdesignsystemcatalogue".repeat(3),
            subtitle = "an unreasonably long supporting line ".repeat(10),
            heroes = listOf(hero(216, 480)),
          )
        )
      )

    assertEquals(1200, decode(long.bytes).width)
    assertEquals(630, decode(long.bytes).height)
  }

  private companion object {
    /** A colour nothing in the card's own palette uses, so a pixel count can attribute it. */
    val MARKER: Color = Color(255, 0, 255)
  }
}
