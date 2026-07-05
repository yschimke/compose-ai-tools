package ee.schimke.composeai.preview.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The `testTag` prefix a [PreviewSlot] applies; the slot name is the suffix (`dp-slot:<name>`).
 *
 * The **reader** side (`data-layoutinspector-core`'s `PreviewSlots.SLOT_TAG_PREFIX`) is the single
 * source of truth; this module keeps its own copy so its runtime classpath stays free of the
 * serialization dependency, and a test asserts the two agree so they can't drift.
 */
const val SLOT_TAG_PREFIX: String = "dp-slot:"

/** The `testTag` a `PreviewSlot(name)` applies: `dp-slot:<name>`. */
fun slotTag(name: String): String = "$SLOT_TAG_PREFIX$name"

/**
 * Whether the composition is rendering in **slot mode** — the "author a screen" pass. When `true`,
 * a [PreviewSlot] renders a labelled [SlotPlaceholder] in place of its content; when `false` (the
 * default) it renders its content unchanged.
 *
 * The daemon's `slotMode` render override provides this around the rendered preview (follow-up),
 * the same way `LocalLottieProgress` is provided for the Lottie runtime — so `/render/<id>.png`
 * shows the real preview and `/render/<id>.png?slotMode=true` shows the slot map, both from one
 * preview.
 */
val LocalSlotMode: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Marks [content] as a named slot region a structured-screen builder can fill with a child.
 *
 * **A no-op in a normal render**: it draws [content] inside a [Box] carrying `testTag =
 * "[SLOT_TAG_PREFIX]<name>"`. `testTag` is a semantics property (no visual/layout effect of its
 * own), so the region is captured into the `compose/semantics` tree with its `boundsInRoot` — which
 * the `/render/<id>.slots` route distils into `{ name, bounds }`. Under [LocalSlotMode] it renders
 * a translucent [SlotPlaceholder] labelled [name] instead of [content], so a designer sees exactly
 * where the slot is and drops a composable into that precise box.
 *
 * Give the slot a size (via [modifier], or by placing it in a sized parent — a fixed icon box, a
 * `Row` weight): that box is both what the placeholder fills and the constraint a child rendered to
 * fill the slot is given.
 *
 * @param name the slot's author-declared name (the `dp-slot:` suffix); should be non-blank.
 */
@Composable
fun PreviewSlot(name: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(modifier.testTag(slotTag(name))) {
    if (LocalSlotMode.current) SlotPlaceholder(name) else content()
  }
}

/**
 * A translucent, labelled stand-in for an empty slot — a 50%-opacity fill with the slot [name]
 * centred on it, filling its box. This is what a [PreviewSlot] renders under [LocalSlotMode]: the
 * self-evident target a designer selects to place a different composable in precise position.
 *
 * Fills its parent's constraints ([fillMaxSize]), so it takes the size the slot's box gives it — a
 * slot with no size collapses to nothing, which is why a slot should carry one (see [PreviewSlot]).
 */
@Composable
fun SlotPlaceholder(name: String, modifier: Modifier = Modifier) {
  Box(
    modifier.fillMaxSize().background(SLOT_PLACEHOLDER_COLOR),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(
      name,
      style = SLOT_PLACEHOLDER_TEXT_STYLE,
      maxLines = 1,
      // Scale the label down to fit the slot box on one line — a small icon slot's name won't fit
      // at a fixed size (and wrapping mid-word reads worse than a smaller, whole label).
      autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 12.sp),
    )
  }
}

/** The slot placeholder's fill — a distinct accent at 50% opacity (`0x80` alpha). */
private val SLOT_PLACEHOLDER_COLOR: Color = Color(0x804F46E5)

/**
 * The slot label's text style — white + centred so it reads on the translucent fill. The size is
 * driven by the `autoSize` on the [BasicText] (it scales to fit the slot box), not set here.
 */
private val SLOT_PLACEHOLDER_TEXT_STYLE: TextStyle =
  TextStyle(color = Color.White, textAlign = TextAlign.Center)
