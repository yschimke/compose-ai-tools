package com.example.designcatalogremotem3

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Guards that the clickable stickers encode a **self-contained** click — one the player can act on
 * by itself — rather than a `hostAction` that leaves the document unchanged.
 *
 * Every button and card on this sheet used to carry a shared `hostAction(...)` as its `onClick`.
 * That posts a payload *out* of the document and mutates nothing inside it, so a tap in the preview
 * player repainted nothing: there was no state change to repaint. The stickers now use
 * `countedRemote(...)`, which pairs a `rememberMutableRemoteInt` with a `valueChange` action and a
 * label expression conditional on that counter — so the player updates itself, no host round-trip.
 *
 * This has to be a **sidecar** assertion, not a pixel one, and that is the whole point: at rest the
 * counter is 0, so the conditional resolves to the bare label and the baked PNG is byte-identical
 * to the one this catalog has always published (that parity is the feature, not a gap). The counter
 * branch is only reachable once a player dispatches a real touch, which a static render never does.
 * The evidence therefore lives in the encoded document — where the branch's own string literals are
 * stored as UTF-8, exactly as [WidgetContainerIrCaptureTest] reads them.
 *
 * A regression that reverted a sticker to `hostAction` would drop those literals and fail here,
 * while the render stayed green.
 */
class InteractiveActionCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")

  /** Sticker stem → the base label its `countedRemote(...)` was given. */
  private val countedStickers =
    mapOf(
      // The kit's label for every single-slot button — see `KitCopy`. Referenced rather than
      // spelled, so this test cannot drift back into asserting copy the catalog no longer draws.
      "FilledRemoteButton" to KitCopy.PRIMARY_LABEL,
      "OutlinedRemoteButton" to KitCopy.PRIMARY_LABEL,
      "CustomShapeRemoteButton" to KitCopy.PRIMARY_LABEL,
      // Its label is an overridable named binding; the counter composes over it rather than
      // replacing it, so it takes the same default tally as everything else.
      "NamedLabelRemoteButton" to KitCopy.PRIMARY_LABEL,
      "TonalRemoteButton" to KitCopy.PRIMARY_LABEL,
      "IconLabelRemoteButton" to KitCopy.PRIMARY_LABEL,
      "IconLabelSecondaryRemoteButton" to KitCopy.PRIMARY_LABEL,
      "CompactRemoteButton" to KitCopy.PRIMARY_LABEL,
      "CompactIconLabelRemoteButton" to KitCopy.PRIMARY_LABEL,
      // The ROUND text buttons take the kit's glyph run instead: a two-word label is drawn through
      // the edge of a circle, which is what `PRIMARY_LABEL` here produced before it was caught.
      "TextRemoteButton" to KitCopy.GLYPHS,
      "SmallRemoteTextButton" to KitCopy.GLYPHS,
      "LargeRemoteTextButton" to KitCopy.GLYPHS,
      "FilledRemoteTextButton" to KitCopy.GLYPHS,
      "OutlinedRemoteTextButton" to KitCopy.GLYPHS,
      // Cards quote the kit's card slots.
      "CardRemote" to KitCopy.CARD_CONTENT,
      "OutlinedCardRemote" to KitCopy.CARD_CONTENT,
      "TitleCardRemote" to KitCopy.CARD_TITLE,
      "AppCardRemote" to KitCopy.CARD_TITLE,
      "TitleOnlyRemoteTitleCard" to KitCopy.CARD_TITLE,
      "TimeContentRemoteTitleCard" to KitCopy.CARD_TITLE,
      "TimeRemoteAppCard" to KitCopy.CARD_TITLE,
    )

  /**
   * The fragments `countedRemote` concatenates around the counter (`"<base> (" + n + ")"`). They
   * are in the document only because the counter branch was encoded — the resting label never draws
   * them.
   */
  private val counterFragments = listOf(" (", ")")

  /**
   * Captures are named `<stem>_width_…_height_…_dpi_….<ext>`, so match on the stem prefix rather
   * than pinning the size suffix — a breakpoint change shouldn't rename this test's inputs.
   */
  private fun capture(stem: String, ext: String): File? =
    rendersDir
      .listFiles { f -> f.name.startsWith("${stem}_") && f.name.endsWith(".$ext") }
      .orEmpty()
      .minByOrNull { it.name }

  private fun documentText(stem: String): String {
    val rc = capture(stem, "rc")
    assertWithMessage("missing encoded document for $stem").that(rc).isNotNull()
    return rc!!.readBytes().toString(Charsets.UTF_8)
  }

  @Test
  fun `every counted sticker still carries its resting label`() {
    for ((stem, label) in countedStickers) {
      assertWithMessage("$stem lost its label").that(documentText(stem)).contains(label)
    }
  }

  @Test
  fun `every counted sticker encodes the click-counter branch of its label`() {
    for (stem in countedStickers.keys) {
      val text = documentText(stem)
      for (fragment in counterFragments) {
        assertWithMessage("$stem does not encode the counter fragment '$fragment'")
          .that(text)
          .contains(fragment)
      }
    }
  }

  @Test
  fun `the sheet still renders every counted sticker`() {
    // Keeps the assertions above from passing vacuously if the render stopped emitting captures.
    for (stem in countedStickers.keys) {
      assertWithMessage("no baked capture for $stem").that(capture(stem, "png")).isNotNull()
    }
  }
}
