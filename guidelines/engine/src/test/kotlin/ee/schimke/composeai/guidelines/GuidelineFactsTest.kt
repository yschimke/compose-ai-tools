package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Test

/** The facts computed before any model is asked, on the planted defects they exist to catch. */
class GuidelineFactsTest {
  private fun png(width: Int, height: Int): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  /** A 192dp round Wear screen rendered at 2px a dp. */
  private fun screen(
    nodes: List<PreviewNode>,
    checks: List<PreviewCheck> = emptyList(),
    source: String? = null,
  ) =
    PreviewSubject(
      previewId = "s",
      surface = GuidelineSurfaces.SCREEN,
      pictures = listOf(SubjectPicture("device", png(384, 384), 192, 192)),
      nodes = nodes,
      checks = checks,
      source = source,
    )

  private fun kinds(facts: List<GuidelineFact>, kind: String) = facts.filter { it.kind == kind }

  @Test
  fun `a 24dp button is a touch-target shortfall, in dp`() {
    val facts =
      GuidelineFacts.of(
        screen(
          listOf(PreviewNode("stop", "Button", "Stop", 100, 100, 148, 148, listOf("clickable")))
        ),
        "wear",
      )
    val shortfall = kinds(facts, GuidelineFacts.TOUCH_TARGET).single()
    assertThat(shortfall.text)
      .isEqualTo("stop (Button 'Stop') is 24×24dp: 24dp short of the 48dp minimum touch target.")
    assertThat(shortfall.decisive).isTrue()
    assertThat(shortfall.nodeId).isEqualTo("stop")
    assertThat(kinds(facts, GuidelineFacts.NODE_SIZE).single().text).contains("24dp×24dp")
  }

  @Test
  fun `content past the screen is clipped unless a scrolling container holds it`() {
    val list = PreviewNode("list", null, "", 0, 0, 384, 384, listOf("scrollable"))
    val inList = PreviewNode("last", "Text", "Delete everything", 40, 360, 340, 420)
    val facts = GuidelineFacts.of(screen(listOf(list, inList)), "wear")
    val clip = kinds(facts, GuidelineFacts.VIEWPORT_CLIP).single()
    assertThat(clip.decisive).isFalse()
    assertThat(clip.text).contains("scrolls into view")
    assertThat(kinds(facts, GuidelineFacts.SCROLL).single().text)
      .contains("1 node(s) continue below the screen, so it can scroll further down")

    val cut = GuidelineFacts.of(screen(listOf(inList)), "wear")
    assertThat(kinds(cut, GuidelineFacts.VIEWPORT_CLIP).single().text).contains("it is cut off")
    assertThat(kinds(cut, GuidelineFacts.VIEWPORT_CLIP).single().decisive).isTrue()
  }

  @Test
  fun `text reaching past the round screen's edge is flagged, a centred row is not`() {
    // A title at the very top, as wide as the screen: its top edge's middle is on the circle,
    // its left and right midpoints are off it.
    val wide = PreviewNode("title", "Text", "A long title", 0, 20, 384, 60)
    val centred = PreviewNode("ok", "Text", "OK", 150, 170, 234, 214)
    val facts = GuidelineFacts.of(screen(listOf(wide, centred)), "wear")
    val mask = kinds(facts, GuidelineFacts.ROUND_MASK).single()
    assertThat(mask.nodeId).isEqualTo("title")
    assertThat(mask.text).contains("left and right edge")
    // Not round: a phone screen has no mask.
    assertThat(kinds(GuidelineFacts.of(screen(listOf(wide)), "mobile"), GuidelineFacts.ROUND_MASK))
      .isEmpty()
  }

  @Test
  fun `overlapping controls and text running out of its container are flagged`() {
    val a = PreviewNode("a", "Button", "A", 100, 100, 200, 200, listOf("clickable"))
    val b = PreviewNode("b", "Button", "B", 150, 150, 250, 250, listOf("clickable"))
    val card = PreviewNode("card", null, "", 40, 220, 340, 300)
    val text = PreviewNode("t", "Text", "Eight lines of text", 60, 240, 320, 320)
    val facts = GuidelineFacts.of(screen(listOf(a, b, card, text)), "wear")
    assertThat(kinds(facts, GuidelineFacts.OVERLAP).single().text).contains("overlap")
    val cut = kinds(facts, GuidelineFacts.TEXT_CUT).single()
    assertThat(cut.text).contains("runs past its container card")
    assertThat(cut.text).contains("bottom")
  }

  @Test
  fun `ATF results become numbers, and a clean run says so`() {
    val facts =
      GuidelineFacts.fromChecks(
        listOf(
          PreviewCheck(
            "TextContrastCheck",
            "ERROR",
            "The item's text contrast ratio is 2.33. This ratio is based on an estimated " +
              "foreground color of #FF444444. Consider increasing this item's text contrast " +
              "ratio to 4.50 or greater.",
            "Stop",
          ),
          PreviewCheck(
            "TouchTargetSizeCheck",
            "ERROR",
            "This item's height is 18dp. Consider making the height of this touch target 48dp " +
              "or larger.",
            "Play",
          ),
        )
      )
    assertThat(facts.map { it.text })
      .containsExactly(
        "ATF TextContrastCheck ERROR on 'Stop': contrast 2.33:1, needs 4.50:1.",
        "ATF TouchTargetSizeCheck ERROR on 'Play': measured 18dp, needs 48dp.",
      )
    assertThat(facts.all { it.decisive }).isTrue()
    assertThat(GuidelineFacts.fromChecks(emptyList()).single().text).contains("no errors")
  }

  @Test
  fun `the source scan finds literal sizes, colours, fixed sizes, filled buttons and strings`() {
    val source =
      """
      @Preview @Composable fun Bad() = Column {
        // Text("commented out", fontSize = 99.sp)
        Text("Pause all", fontSize = 7.sp, color = Color(0xFF222222))
        Button(onClick = {}, modifier = Modifier.size(24.dp)) { Text("Start") }
        Button(onClick = {}) { Text("Stop") }
        OutlinedButton(onClick = {}) { Text(stringResource(R.string.later)) }
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Red)
        Text(text = "Eight lines", maxLines = 8, overflow = TextOverflow.Clip)
      }
      """
        .trimIndent()
    val facts = GuidelineFacts.fromSource(source)
    fun text(kind: String) = kinds(facts, kind).joinToString("\n") { it.text }
    assertThat(text(GuidelineFacts.FONT_SIZE)).contains("literal text size of 7.sp")
    assertThat(text(GuidelineFacts.FONT_SIZE)).doesNotContain("99")
    assertThat(text(GuidelineFacts.COLOUR)).contains("Color(0xFF222222), very dark grey")
    assertThat(text(GuidelineFacts.COLOUR)).contains("Color.Red")
    assertThat(text(GuidelineFacts.FIXED_SIZE)).contains("Modifier.size(24.dp)")
    val buttons = kinds(facts, GuidelineFacts.BUTTONS).single()
    assertThat(buttons.text).contains("2 Button (filled)")
    assertThat(buttons.text).contains("1 OutlinedButton")
    assertThat(buttons.decisive).isTrue()
    assertThat(text(GuidelineFacts.STRINGS)).contains("'Pause all'")
    assertThat(text(GuidelineFacts.STRINGS)).contains("1 stringResource call(s)")
    assertThat(text(GuidelineFacts.THEME)).contains("MaterialTheme.typography 0 time(s)")
    assertThat(text(GuidelineFacts.DESCRIPTIONS)).contains("1 contentDescription = null")
    assertThat(text(GuidelineFacts.TEXT_OVERFLOW)).contains("maxLines 8")
  }

  @Test
  fun `colours are named, since a decision model reads words better than hex`() {
    assertThat(GuidelineFacts.describeColour("FF222222")).isEqualTo("very dark grey")
    assertThat(GuidelineFacts.describeColour("FFFFFFFF")).isEqualTo("very light grey")
    assertThat(GuidelineFacts.describeColour("FFD32F2F")).endsWith("red")
    assertThat(GuidelineFacts.describeColour("1565C0")).endsWith("blue")
  }

  @Test
  fun `the summary names each kind of fact and how many are flagged`() {
    val facts =
      GuidelineFacts.of(
        screen(
          listOf(PreviewNode("stop", "Button", "Stop", 100, 100, 148, 148, listOf("clickable"))),
          source = "Text(\"Hi\", fontSize = 7.sp)",
        ),
        "wear",
      )
    val summary = GuidelineFacts.summary(facts)
    assertThat(summary).startsWith("computed facts: ")
    assertThat(summary).contains("touch-target 1 (1 flagged)")
    assertThat(summary).contains("font-size 1 (1 flagged)")
    assertThat(summary.length / 4).isLessThan(60)
  }
}
