package ee.schimke.composeai.preview.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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

/**
 * The layout container a slot sits in, recorded onto the tag so a builder knows how a filled child
 * is arranged. The scope-receiver [PreviewSlot] overloads set this automatically from the Compose
 * scope at the call site (`RowScope` → [Row], …); the receiver-less overload takes it explicitly.
 *
 * [wire] is the on-tag token, mirrored by the reader's `SlotScope.fromWire`; a drift test asserts
 * the two agree. Kept as a runtime-local enum (not the reader's `SlotScope`) so this module's
 * classpath stays free of the serialization dependency.
 */
enum class PreviewSlotScope(internal val wire: String?) {
  Unknown(null),
  Row("row"),
  Column("column"),
  Box("box"),
  Lazy("lazy"),
}

/** How a builder should size one axis when replacing a slot's authored content. */
enum class PreviewSlotSizing {
  Unspecified,
  Fixed,
  Fill,
  Hug,
}

/** Content padding declared by a slot host, independent on each edge. */
data class PreviewSlotPadding(
  val startDp: Float = 0f,
  val topDp: Float = 0f,
  val endDp: Float = 0f,
  val bottomDp: Float = 0f,
)

/** The authored sizing contract for a slot; measured bounds remain only its current instance. */
data class PreviewSlotConstraints(
  val horizontal: PreviewSlotSizing = PreviewSlotSizing.Unspecified,
  val vertical: PreviewSlotSizing = PreviewSlotSizing.Unspecified,
  val padding: PreviewSlotPadding = PreviewSlotPadding(),
)

data class PreviewSlotInfo(
  val name: String,
  val scope: PreviewSlotScope,
  val scrolling: Boolean,
  val constraints: PreviewSlotConstraints,
)

/**
 * Optional native builder bridge. A host compiled into the same Compose runtime can observe slot
 * geometry and replace its content without a server render or a component-specific callback.
 */
interface PreviewSlotHost {
  fun onPositioned(slot: PreviewSlotInfo, bounds: Rect) = Unit

  @Composable fun Content(slot: PreviewSlotInfo, defaultContent: @Composable () -> Unit)
}

val LocalPreviewSlotHost: ProvidableCompositionLocal<PreviewSlotHost?> = compositionLocalOf { null }

/** The `testTag` a bare `PreviewSlot(name)` applies: `dp-slot:<name>`. */
fun slotTag(name: String): String = slotTag(name, PreviewSlotScope.Unknown, scrolling = false)

/**
 * The `testTag` a slot applies: `dp-slot:<name>` plus optional `;scope=<wire>` / `;scroll=1`
 * attributes for a non-[PreviewSlotScope.Unknown] scope or a scrolling container. The reader
 * (`PreviewSlots.extractSlots`) parses this exact shape.
 */
fun slotTag(name: String, scope: PreviewSlotScope, scrolling: Boolean): String = buildString {
  append(SLOT_TAG_PREFIX)
  append(name)
  scope.wire?.let {
    append(";scope=")
    append(it)
  }
  if (scrolling) append(";scroll=1")
}

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
 * the `/render/<id>.slots` route distils into `{ name, bounds, scope, scrolling }`. Under
 * [LocalSlotMode] it renders a translucent [SlotPlaceholder] labelled [name] instead of [content],
 * so a designer sees exactly where the slot is and drops a composable into that precise box.
 *
 * A slot placed directly inside a `Row` / `Column` / `Box` / lazy-item body resolves to the
 * matching scope-receiver overload, which records its [PreviewSlotScope] automatically — prefer
 * that. This bare overload records [PreviewSlotScope.Unknown]; use the [scope]-taking overload for
 * a slot in a lambda with **no layout scope** (a `Scaffold` `topBar` / `floatingActionButton`).
 *
 * Give the slot a size (via [modifier], or by placing it in a sized parent — a fixed icon box, a
 * `Row` weight): that box is both what the placeholder fills and the constraint a child rendered to
 * fill the slot is given.
 *
 * @param name the slot's author-declared name (the `dp-slot:` suffix); should be non-blank.
 */
@Composable
fun PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Unknown,
    scrolling = false,
    PreviewSlotConstraints(),
    modifier,
    content,
  )

@Composable
fun PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  constraints: PreviewSlotConstraints,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Unknown,
    scrolling = false,
    constraints,
    modifier,
    content,
  )

/**
 * [PreviewSlot] for a slot in a lambda with **no layout scope** — a `Scaffold` `topBar` /
 * `floatingActionButton` — where the container can't be inferred from a scope receiver. Declares
 * the [scope] (and [scrolling]) explicitly. A slot inside a `Row` / `Column` / `Box` / lazy body
 * should use the scope-receiver overload instead, which infers [scope].
 *
 * @param scope the container the slot sits in.
 * @param scrolling whether that container scrolls; a [PreviewSlotScope.Lazy] slot is always
 *   scrolling.
 */
@Composable
fun PreviewSlot(
  name: String,
  scope: PreviewSlotScope,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  content: @Composable () -> Unit,
) = SlotBox(name, scope, scrolling, PreviewSlotConstraints(), modifier, content)

@Composable
fun PreviewSlot(
  name: String,
  scope: PreviewSlotScope,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  constraints: PreviewSlotConstraints,
  content: @Composable () -> Unit,
) = SlotBox(name, scope, scrolling, constraints, modifier, content)

/** `RowScope` slot — records [PreviewSlotScope.Row]; children are placed horizontally. */
@Composable
fun RowScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Row,
    scrolling,
    PreviewSlotConstraints(),
    modifier,
    content,
  )

@Composable
fun RowScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  constraints: PreviewSlotConstraints,
  content: @Composable () -> Unit,
) = SlotBox(name, PreviewSlotScope.Row, scrolling, constraints, modifier, content)

/** `ColumnScope` slot — records [PreviewSlotScope.Column]; children stack vertically. */
@Composable
fun ColumnScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Column,
    scrolling,
    PreviewSlotConstraints(),
    modifier,
    content,
  )

@Composable
fun ColumnScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  constraints: PreviewSlotConstraints,
  content: @Composable () -> Unit,
) = SlotBox(name, PreviewSlotScope.Column, scrolling, constraints, modifier, content)

/** `BoxScope` slot — records [PreviewSlotScope.Box]; a single child fills / aligns in the box. */
@Composable
fun BoxScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Box,
    scrolling,
    PreviewSlotConstraints(),
    modifier,
    content,
  )

@Composable
fun BoxScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  scrolling: Boolean = false,
  constraints: PreviewSlotConstraints,
  content: @Composable () -> Unit,
) = SlotBox(name, PreviewSlotScope.Box, scrolling, constraints, modifier, content)

/**
 * `LazyItemScope` slot — records [PreviewSlotScope.Lazy], always scrolling: the slot is one item of
 * a lazy list/grid, so a filled child sits in a scrolling container.
 */
@Composable
fun LazyItemScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Lazy,
    scrolling = true,
    PreviewSlotConstraints(),
    modifier,
    content,
  )

@Composable
fun LazyItemScope.PreviewSlot(
  name: String,
  modifier: Modifier = Modifier,
  constraints: PreviewSlotConstraints,
  content: @Composable () -> Unit,
) =
  SlotBox(
    name,
    PreviewSlotScope.Lazy,
    scrolling = true,
    constraints,
    modifier,
    content,
  )

/** Shared body of every [PreviewSlot] overload: the tagged [Box] + slot-mode placeholder swap. */
@Composable
private fun SlotBox(
  name: String,
  scope: PreviewSlotScope,
  scrolling: Boolean,
  constraints: PreviewSlotConstraints,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val slot = PreviewSlotInfo(name, scope, scrolling || scope == PreviewSlotScope.Lazy, constraints)
  val host = LocalPreviewSlotHost.current
  val observedModifier =
    if (host == null) modifier
    else modifier.onGloballyPositioned { host.onPositioned(slot, it.boundsInRoot()) }
  Box(observedModifier.testTag(slotTag(name, scope, slot.scrolling))) {
    when {
      host != null -> host.Content(slot, content)
      LocalSlotMode.current -> SlotPlaceholder(name)
      else -> content()
    }
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
