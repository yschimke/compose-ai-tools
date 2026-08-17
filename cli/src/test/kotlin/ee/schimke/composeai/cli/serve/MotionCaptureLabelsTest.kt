package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a caption stops being a name and starts being an explanation.
 *
 * The picker is a menu because catalog captions are prose, and the menu only stays narrow if the
 * cut lands in the right place. Each case below is a caption shape a real catalog writes.
 */
class MotionCaptureLabelsTest {
  private fun capture(caption: String? = null, kind: String? = null) =
    ServeMotion(id = "x", kind = kind, caption = caption)

  private fun titleOf(caption: String) =
    MotionCaptureLabels.of(listOf(capture(caption))).single().title

  @Test
  fun `a caption that is already a name is left alone`() {
    val label = MotionCaptureLabels.of(listOf(capture("Tap the avatar"))).single()
    assertEquals("Tap the avatar", label.title)
    // The detail is still the caption, in full — it is [ServeWeb] that decides not to print words
    // the menu already shows, so the rule that decides *what the words are* stays in one place.
    assertEquals("Tap the avatar", label.detail)
  }

  @Test
  fun `the instruction survives and the explanation behind it moves to the detail`() {
    val caption =
      "Toggle repeatedly. The container morphs between its unchecked and checked shapes " +
        "through the theme's spatial animation — Baseline swaps the shape, Expressive travels " +
        "between them."
    val label = MotionCaptureLabels.of(listOf(capture(caption))).single()
    assertEquals("Toggle repeatedly", label.title)
    assertEquals(caption, label.detail)
  }

  @Test
  fun `a dash or a colon introduces the explanation just as a full stop does`() {
    assertEquals("Press and hold", titleOf("Press and hold — the ripple settles under the finger"))
    assertEquals("Press and hold", titleOf("Press and hold: the ripple settles under the finger"))
    assertEquals("Scroll", titleOf("Scroll; the app bar collapses on the first pixel"))
  }

  @Test
  fun `a terminator inside a word is not a sentence ending`() {
    // "1.5dp" and "e.g." would each cut the title to nothing if a bare `.` ended a sentence, and a
    // hyphenated word would lose everything after its first hyphen.
    assertEquals("Nudge the thumb 1.5dp", titleOf("Nudge the thumb 1.5dp"))
    assertEquals("Press-and-hold the card", titleOf("Press-and-hold the card"))
    assertEquals("Crop to 1:1", titleOf("Crop to 1:1"))
  }

  @Test
  fun `a first clause that is itself long is cut on a word boundary`() {
    val caption =
      "Drag the sheet up past the halfway mark and let go so it completes on its own momentum"
    val title = titleOf(caption)
    assertTrue(title.endsWith("…"), "the cut says the rest is elsewhere: $title")
    assertTrue(title.length <= 44, "the menu stays narrow: $title (${title.length})")
    val kept = title.removeSuffix("…")
    assertTrue(caption.startsWith(kept), "the kept words are the caption's own opening: $title")
    assertTrue(!kept.endsWith(" "), "no space before the ellipsis: $title")
    assertTrue(
      caption[kept.length] == ' ',
      "the cut lands between words rather than mid-word: $title",
    )
  }

  @Test
  fun `a caption-less capture falls back to its kind`() {
    assertEquals(
      "Interaction",
      MotionCaptureLabels.of(listOf(capture(kind = "interaction"))).single().title,
    )
    assertEquals(
      "Animation",
      MotionCaptureLabels.of(listOf(capture(kind = "animation"))).single().title,
    )
    assertEquals("Capture", MotionCaptureLabels.of(listOf(capture())).single().title)
    assertEquals("", MotionCaptureLabels.of(listOf(capture())).single().detail)
  }

  @Test
  fun `titles that collide are numbered and the detail says what differs`() {
    // The case the cut makes MORE likely, not less: two recordings of one component opening with
    // the same instruction and differing only in the paragraph behind it.
    val labels =
      MotionCaptureLabels.of(
        listOf(
          capture("Toggle repeatedly. Baseline swaps the shape."),
          capture("Toggle repeatedly. Expressive travels between them."),
        )
      )
    assertEquals(listOf("Toggle repeatedly 1", "Toggle repeatedly 2"), labels.map { it.title })
    assertEquals(
      listOf(
        "Toggle repeatedly. Baseline swaps the shape.",
        "Toggle repeatedly. Expressive travels between them.",
      ),
      labels.map { it.detail },
    )
  }

  @Test
  fun `a title that stands alone is not numbered`() {
    val labels = MotionCaptureLabels.of(listOf(capture("Toggle on"), capture("Toggle off")))
    assertEquals(listOf("Toggle on", "Toggle off"), labels.map { it.title })
  }

  @Test
  fun `a caption wrapped in source reaches the row as one line`() {
    val label =
      MotionCaptureLabels.of(listOf(capture("Toggle on.\n  The thumb\n  travels."))).single()
    assertEquals("Toggle on", label.title)
    assertEquals("Toggle on. The thumb travels.", label.detail)
  }
}
