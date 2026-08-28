package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.OverrideVariantInteraction
import ee.schimke.composeai.data.overrides.OverrideVariantSpec

/**
 * The **whole** of what a synthetic `_VARIANT_` preview's `@OverrideVariant` asks a daemon render
 * to do, as one [PreviewOverrides] bag: the knob seeds *and* the harness interaction.
 *
 * One function, called from four places — `renderSpecFromInfo` and `PreviewManifestEntry.resolved`
 * on each of the two backends. That is not tidiness. Each of those four used to compute
 * `overrides?.toNamedOverrides()` inline, and the seed half reached them one backend at a
 * time: #3652 wired the two android sites and left the desktop router forwarding nothing, so every
 * desktop-rendered catalog exported base-state vectors for two more weeks (#4638, and
 * yschimke/m3-catalog#201 that reported it). A translation with one home cannot land on half the
 * backends.
 *
 * ### The two halves are carried differently, and that is not an accident
 *
 * A **seed** is data: `state=disabled` is a value the composable reads out of
 * `PreviewOverrideController` on its first pass, so it rides `namedOverrides` and needs nothing
 * from the render host.
 *
 * An **interaction** is a state something else has to put the composition into. Two of the three
 * are expressible as a `FocusOverride`, because `FocusOverrideExtension` (both backends) already
 * owns the in-composition half of that: it flips `LocalInputModeManager` to keyboard mode — which
 * nothing outside composition can do, and without which the focus indication does not draw at all —
 * and walks `FocusManager.moveFocus(...)` to the requested index.
 *
 * * [OverrideVariantInteraction.Focused] → the flip, plus the target index.
 * * [OverrideVariantInteraction.Pressed] → the same, plus [FocusOverride.pressed], matching what
 *   discovery hands the standalone renderers (`FocusCapture(tabIndex, pressed = true)`): a pressed
 *   sticker is focused *and* pressed, so a press with no focus indication under it would disagree
 *   with the baked PNG beside it.
 * * [OverrideVariantInteraction.Hovered] is **not** here. A hover has no in-composition half at all
 *   — no input-mode flip, no manager to walk — so there is nothing for this bag to carry and each
 *   engine reads the intent off the preview index instead ([PreviewIndex.staticHoverFor]), exactly
 *   as the `@ScrollingPreview(END)` drive already does. Returning a `FocusOverride` for it would
 *   draw a focus indication and call it hover: a plausible picture of the wrong state, which is
 *   worse than the honest duplicate this whole change is about.
 *
 * What this bag does **not** carry for focus and press is the *targeting*. Both engines address the
 * node themselves, because `FocusOverrideExtension`'s `moveFocus` walk does not land inside a
 * daemon render on either backend — see `driveFocusPreview` (android) and
 * `RenderEngine.driveStaticInteraction` (desktop) for the evidence and the mechanism. The bag is
 * still what turns the extension on, and the extension is still what makes focus possible at all.
 *
 * Returns null when the variant asks for nothing this bag can carry — an all-unparseable seed set
 * with no interaction — so a caller can leave a live per-call token untouched rather than layering
 * an empty bag under it.
 */
public fun OverrideVariantSpec.toPreviewOverrides(): PreviewOverrides? {
  val named = toNamedOverrides().takeIf { it.isNotEmpty() }
  val focus =
    when (interaction) {
      OverrideVariantInteraction.Focused -> FocusOverride(tabIndex = interactionIndex)
      OverrideVariantInteraction.Pressed ->
        FocusOverride(tabIndex = interactionIndex, pressed = true)
      // Host-driven; see the KDoc.
      OverrideVariantInteraction.Hovered -> null
      null -> null
    }
  if (named == null && focus == null) return null
  return PreviewOverrides(namedOverrides = named, focus = focus)
}
