package ee.schimke.composeai.renderer.uiautomator

import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.AnnotatedString
import java.util.regex.Pattern

/**
 * Prototype UIAutomator-shaped API that runs against the local View tree inside Robolectric (no
 * `UiAutomation`, no `adb`, no shell). The entry point is a `View` — every Compose preview
 * already has one (the `ViewRootForTest` the renderer captures) — and the rest of the surface
 * walks `AccessibilityNodeInfo` produced by `View.createAccessibilityNodeInfo()`.
 *
 * # Why this might be useful
 *
 * The `:daemon:android` interactive session today drives clicks two ways:
 *  - by **screen pixel** through `performTouchInput { down/move/up }` (see `RobolectricHost`);
 *  - by **`contentDescription` lookup** through `SemanticsActions.OnClick`
 *    (`performSemanticsActionByContentDescription`).
 *
 * Neither generalises well: pixel-targeting needs the agent to read coordinates off the PNG,
 * and the contentDescription path is a single-axis selector (only descriptions; no class, no
 * resource id, no enabled-state filter, no tree predicates). UIAutomator's `BySelector` is the
 * lingua-franca for that kind of multi-axis query, and the Compose accessibility delegate
 * already populates `AccessibilityNodeInfo.text` / `contentDescription` / `viewIdResourceName`
 * / `className` / `isEnabled` / `isClickable` / etc. — so the same selector that targets a
 * widget on-device matches the same widget under Robolectric.
 *
 * # Evaluation
 *
 * **Filters** — clearly useful and cheap. UIAutomator's selector grammar is well-known to test
 * authors and to coding agents; rebuilding the query layer in our own DSL would just produce a
 * worse version of the same thing. The selector → ANI walker (`findObject`) is ~50 lines and
 * works directly on the ANI tree.
 *
 * **Actions** — partially useful, with one hard caveat the test suite surfaced. Real
 * `UiObject2.click()` dispatches `ACTION_CLICK` through `UiAutomation` →
 * `AccessibilityInteractionClient.performAccessibilityAction(...)` → the system's window
 * server → eventually `View.performAccessibilityAction`. Inside Robolectric there's no
 * `UiAutomation` and no `AccessibilityInteractionClient` connection, so calling
 * `AccessibilityNodeInfo.performAction(...)` directly is a no-op (the ANI returned by
 * `View.createAccessibilityNodeInfo()` has no `mConnectionId` set; the runtime path bails out
 * silently and reports `true` regardless of whether anything happened). The fix is to call the
 * **View**-side equivalent — `view.performAccessibilityAction(action, args)` — which is the
 * same method `View.performAccessibilityActionInternal(int, Bundle)` ends up at. That's why
 * [UiObject] wraps both the `View` and its ANI projection, and dispatches actions through the
 * view.
 *
 * The reusable subset (routed through `view.performAccessibilityAction`):
 *  - `ACTION_CLICK`, `ACTION_LONG_CLICK`, `ACTION_FOCUS`, `ACTION_CLEAR_FOCUS`
 *  - `ACTION_SCROLL_FORWARD`, `ACTION_SCROLL_BACKWARD`
 *  - `ACTION_SET_TEXT`, `ACTION_SET_SELECTION`
 *  - `ACTION_EXPAND`, `ACTION_COLLAPSE`, `ACTION_DISMISS`
 *
 * Compose's accessibility delegate routes these into the same `SemanticsActions` lambdas that
 * `:daemon:android`'s `performSemanticsActionByContentDescription` already invokes — so the
 * action half of UIAutomator collapses onto the existing semantics-action plumbing, just with a
 * better selector on the front.
 *
 * **Not reusable**: anything that depends on `UiAutomation.injectInputEvent` —
 * `UiObject2.swipe()`, `pinchOpen()`, `drag()`, hardware-key injection. Robolectric has no
 * `UiAutomation`, and the upstream `Gestures` / `InteractionController` classes can't run
 * without one. These already have a Robolectric-side equivalent on the host
 * (`performTouchInput { … }`), so the gap isn't a regression — it's just a "don't expose the
 * gestural half of the UIAutomator API in this prototype" decision.
 *
 * # Why a local DSL instead of upstream `BySelector`
 *
 * Two reasons:
 *  1. `androidx.test.uiautomator.ByMatcher` (the walker that consumes `BySelector`) is
 *     package-private. Reusing the upstream selector type means reflecting into
 *     `ByMatcher.findMatches(…)` — pinning a uiautomator point-release as a runtime
 *     dependency of the renderer. The local types are 50 lines of regex-vs-equals code; the
 *     cost of writing them is lower than the cost of pinning + reflecting.
 *  2. We can drop fields that don't make sense inside Robolectric (`pkg(String)` — single
 *     application, `inputMethodFocused()` — no IME, `displayId(Int)` — single virtual
 *     display) without shipping unsupported chains.
 *
 * If a future variant needs to share selectors with on-device tests, the selector type below is
 * a 1:1 superset of the upstream chains we keep, so a thin adapter can rebuild a `BySelector`
 * from a [Selector] for that use case.
 *
 * # Non-goals (in this prototype)
 *
 *  - No `UiDevice`-equivalent — there's no concept of "device" with one window stack here.
 *  - No `UiObject` (the older selector type) — `BySelector` is the modern surface.
 *  - No gesture pipeline — see "not reusable" above.
 *  - No data-extension wiring — no `PostCaptureProcessor`, no
 *    `RecordingScriptEventDescriptor`. If we promote this, that wiring lands then.
 */
public object UiAutomator {

  /**
   * Walks the [root] view subtree pre-order; first match wins. Returns the matched node as a
   * [UiObject] (a `View` + its `AccessibilityNodeInfo` projection), or `null` when no view
   * matches.
   *
   * **Why View-tree, not ANI-tree.** `AccessibilityNodeInfo.getChild(int)` requires a connected
   * `AccessibilityInteractionClient` to fetch children — present on-device through
   * `UiAutomation`, absent in Robolectric. ATF (the existing
   * `AccessibilityHierarchyAndroid.newBuilder(root).build()` path used by
   * `AccessibilityChecker`) handles this by walking the View tree directly. The selector
   * machinery does the same: traversal goes through `ViewGroup.getChildAt(i)`, predicates
   * evaluate against each view's lazily-created ANI.
   *
   * **Compose support — same DSL, different traversal.** Compose hosts everything inside one
   * `androidx.compose.ui.platform.AndroidComposeView`, which exposes child semantics through an
   * `AccessibilityNodeProvider` rather than as actual `View` children. Walking the `View` tree
   * therefore stops at the Compose host. The Compose overload below
   * (`findObject(rule, selector)`) walks the `SemanticsOwner` tree instead and dispatches
   * actions through `SemanticsActions` lambdas — the same path `:daemon:android`'s
   * `performSemanticsActionByContentDescription` uses. Both overloads share the same [Selector]
   * predicate set; only traversal and action dispatch differ.
   */
  public fun findObject(root: View, selector: Selector): ViewUiObject? {
    walkBacking(ViewBacking(root)) { backing ->
      if (selector.matches(backing) && backing is ViewBacking) {
        val info = backing.view.createAccessibilityNodeInfo() ?: return@walkBacking
        return ViewUiObject(backing.view, info)
      }
    }
    return null
  }

  /** Pre-order walk; all matches in document order. */
  public fun findObjects(root: View, selector: Selector): List<ViewUiObject> {
    val out = mutableListOf<ViewUiObject>()
    walkBacking(ViewBacking(root)) { backing ->
      if (selector.matches(backing) && backing is ViewBacking) {
        val info = backing.view.createAccessibilityNodeInfo() ?: return@walkBacking
        out += ViewUiObject(backing.view, info)
      }
    }
    return out
  }

  /**
   * Compose-side overload. Walks the [rule]'s `SemanticsOwner` tree pre-order and returns the
   * first node matching [selector] as a [SemanticsUiObject] (which dispatches actions through
   * `SemanticsActions` lambdas the same way `:daemon:android`'s
   * `performSemanticsActionByContentDescription` does).
   *
   * **Tree variant** ([useUnmergedTree], default `false`):
   *  - **Merged** (default) — same view Compose's accessibility delegate exposes to the
   *    platform, and what real on-device UIAutomator selectors match against.
   *    `By.text("Submit")` matches a `Button { Text("Submit") }` as a single node carrying
   *    both the text and the `OnClick`.
   *  - **Unmerged** — every Compose node visible to the test framework. Use when you need to
   *    target a specific inner node (e.g. the inner `Text` of a `Button` for its raw label,
   *    or distinct child rows that the merged Button collapses into one announcement). Note
   *    that selectors composed across "this node has the text AND has OnClick" will fail in
   *    unmerged mode because text and click action live on separate nodes.
   */
  public fun findObject(
    rule: ComposeContentTestRule,
    selector: Selector,
    useUnmergedTree: Boolean = false,
  ): SemanticsUiObject? {
    val root = rule.onRoot(useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    walkBacking(SemanticsBacking(root)) { backing ->
      if (selector.matches(backing) && backing is SemanticsBacking) {
        return SemanticsUiObject(rule, backing.node)
      }
    }
    return null
  }

  /** Pre-order walk; all matches in document order. See [findObject] for the [useUnmergedTree] semantics. */
  public fun findObjects(
    rule: ComposeContentTestRule,
    selector: Selector,
    useUnmergedTree: Boolean = false,
  ): List<SemanticsUiObject> {
    val root = rule.onRoot(useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    val out = mutableListOf<SemanticsUiObject>()
    walkBacking(SemanticsBacking(root)) { backing ->
      if (selector.matches(backing) && backing is SemanticsBacking) {
        out += SemanticsUiObject(rule, backing.node)
      }
    }
    return out
  }

  internal inline fun walkBacking(root: NodeBacking, visit: (NodeBacking) -> Unit) {
    val stack = ArrayDeque<NodeBacking>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
      val n = stack.removeLast()
      visit(n)
      val children = n.children()
      // Iterate in reverse so pop order matches document order.
      for (i in children.indices.reversed()) {
        stack.addLast(children[i])
      }
    }
  }
}

/**
 * Internal tree-shaped projection of "the bits of a node a selector cares about". One impl
 * per backing — [ViewBacking] reads from `View.createAccessibilityNodeInfo()`, [SemanticsBacking]
 * reads from `SemanticsNode.config`. Letting the matcher work against a single interface keeps
 * [Selector.matches] backing-agnostic; `findObject(View)` and `findObject(ComposeContentTestRule)`
 * differ only in which root they wrap before walking.
 *
 * Properties return `null` when the backing genuinely has no equivalent (e.g. SemanticsNode
 * has no notion of `clazz` — the matcher treats `null` as "selector field doesn't apply").
 * Booleans are nullable for the same reason: a Compose node that doesn't expose
 * `SemanticsActions.OnClick` reports `isClickable = null`, and a selector that didn't ask
 * about clickability passes regardless.
 */
internal interface NodeBacking {
  val text: CharSequence?
  val desc: CharSequence?
  val clazz: CharSequence?
  val res: String?
  val isEnabled: Boolean?
  val isClickable: Boolean?
  val isLongClickable: Boolean?
  val isCheckable: Boolean?
  val isChecked: Boolean?
  val isSelected: Boolean?
  val isFocused: Boolean?
  val isScrollable: Boolean?

  fun children(): List<NodeBacking>
}

internal class ViewBacking(val view: View) : NodeBacking {
  private val ani: AccessibilityNodeInfo? by lazy { view.createAccessibilityNodeInfo() }

  override val text: CharSequence?
    get() = ani?.text

  override val desc: CharSequence?
    get() = ani?.contentDescription

  override val clazz: CharSequence?
    get() = ani?.className

  override val res: String?
    get() = ani?.viewIdResourceName

  override val isEnabled: Boolean?
    get() = ani?.isEnabled

  override val isClickable: Boolean?
    get() = ani?.isClickable

  override val isLongClickable: Boolean?
    get() = ani?.isLongClickable

  override val isCheckable: Boolean?
    get() = ani?.isCheckable

  override val isChecked: Boolean?
    get() = ani?.checkedCompat()

  override val isSelected: Boolean?
    get() = ani?.isSelected

  override val isFocused: Boolean?
    get() = ani?.isFocused

  override val isScrollable: Boolean?
    get() = ani?.isScrollable

  override fun children(): List<NodeBacking> {
    val group = view as? android.view.ViewGroup ?: return emptyList()
    return List(group.childCount) { i -> ViewBacking(group.getChildAt(i)) }
  }
}

private fun AccessibilityNodeInfo.checkedCompat(): Boolean =
  if (Build.VERSION.SDK_INT >= 36) {
    getChecked() == AccessibilityNodeInfo.CHECKED_STATE_TRUE
  } else {
    @Suppress("DEPRECATION")
    isChecked
  }

/**
 * Compose-side projection. Maps `BySelector` chains onto the closest `SemanticsProperties` /
 * `SemanticsActions` analogue:
 *
 *  - `text` — `EditableText` (preferred, since selectors are usually targeting input fields)
 *    falls back to joined `Text`. Compose stores text as `AnnotatedString`; we surface it as a
 *    `CharSequence` for the matcher.
 *  - `desc` — `ContentDescription` (joined when a node carries multiple).
 *  - `res` — `TestTag` (the closest stable per-node id; Compose has no resource id).
 *  - `clazz` — no analogue (no JVM class on a SemanticsNode); always `null` — selector field
 *    doesn't filter Compose nodes.
 *  - `clickable` / `longClickable` / `scrollable` — presence of `OnClick` / `OnLongClick` /
 *    `ScrollBy` in the action set.
 *  - `enabled` — `!Disabled` (`Disabled` is a unit property; missing means enabled).
 *  - `checkable` / `checked` — derived from `ToggleableState`.
 *  - `selected` — `Selected`.
 *  - `focused` — `Focused`.
 */
internal class SemanticsBacking(val node: SemanticsNode) : NodeBacking {
  private val config = node.config

  override val text: CharSequence?
    get() {
      config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
      return config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }
    }

  override val desc: CharSequence?
    get() = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

  override val clazz: CharSequence?
    get() = null

  override val res: String?
    get() = config.getOrNull(SemanticsProperties.TestTag)

  override val isEnabled: Boolean?
    get() = config.getOrNull(SemanticsProperties.Disabled)?.let { false } ?: true

  override val isClickable: Boolean?
    get() = config.getOrNull(SemanticsActions.OnClick) != null

  override val isLongClickable: Boolean?
    get() = config.getOrNull(SemanticsActions.OnLongClick) != null

  override val isCheckable: Boolean?
    get() = config.getOrNull(SemanticsProperties.ToggleableState) != null

  override val isChecked: Boolean?
    get() =
      when (config.getOrNull(SemanticsProperties.ToggleableState)) {
        ToggleableState.On -> true
        ToggleableState.Off,
        ToggleableState.Indeterminate -> false
        null -> null
      }

  override val isSelected: Boolean?
    get() = config.getOrNull(SemanticsProperties.Selected)

  override val isFocused: Boolean?
    get() = config.getOrNull(SemanticsProperties.Focused)

  override val isScrollable: Boolean?
    get() = config.getOrNull(SemanticsActions.ScrollBy) != null

  override fun children(): List<NodeBacking> = node.children.map { SemanticsBacking(it) }
}

/**
 * One matched node. Loosely shaped after `androidx.test.uiautomator.UiObject2`. Two impls:
 *
 *  - [ViewUiObject] — actions dispatch through `View.performAccessibilityAction` (since
 *    `AccessibilityNodeInfo.performAction` is a silent no-op in Robolectric — see the file KDoc).
 *  - [SemanticsUiObject] — actions invoke `SemanticsActions` lambdas directly inside
 *    `rule.runOnUiThread { ... }`, the same path `:daemon:android`'s
 *    `performSemanticsActionByContentDescription` uses. The rule is needed both to schedule the
 *    invocation on the UI thread and to drive `waitForIdle()` after the action so subsequent
 *    matches see the post-action state.
 *
 * Action methods return `true` when the action was accepted, `false` when the node didn't
 * expose it. The host can convert `false` to a typed `unsupported(reason="...")` evidence
 * message.
 */
public sealed class UiObject {
  public abstract val text: CharSequence?
  public abstract val contentDescription: CharSequence?
  public abstract val className: CharSequence?
  public abstract val resourceName: String?

  public abstract fun click(): Boolean

  public abstract fun longClick(): Boolean

  public abstract fun scrollForward(): Boolean

  public abstract fun scrollBackward(): Boolean

  public abstract fun requestFocus(): Boolean

  public abstract fun expand(): Boolean

  public abstract fun collapse(): Boolean

  public abstract fun dismiss(): Boolean

  public abstract fun inputText(value: CharSequence): Boolean
}

public class ViewUiObject
internal constructor(public val view: View, public val info: AccessibilityNodeInfo) : UiObject() {

  override val text: CharSequence?
    get() = info.text

  override val contentDescription: CharSequence?
    get() = info.contentDescription

  override val className: CharSequence?
    get() = info.className

  override val resourceName: String?
    get() = info.viewIdResourceName

  override fun click(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)

  override fun longClick(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null)

  override fun scrollForward(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)

  override fun scrollBackward(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)

  override fun requestFocus(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS, null)

  public fun clearFocus(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, null)

  override fun expand(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null)

  override fun collapse(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null)

  override fun dismiss(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)

  /**
   * Routes through `ACTION_SET_TEXT`. Compose's accessibility delegate maps this to
   * `SemanticsActions.SetText`; on plain `EditText`, the platform's `View` impl rewrites the
   * `Editable` directly.
   */
  override fun inputText(value: CharSequence): Boolean {
    val args =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
      }
    return view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }
}

/**
 * Compose-backed [UiObject]. Holds a [SemanticsNode] reference and the test rule used to
 * dispatch actions on the UI thread. After every successful action the rule's `waitForIdle()`
 * is called so the node's post-action state is visible to subsequent finds.
 *
 * Scroll actions need a non-zero step. We use the node's bounds height/width as one "page" —
 * same heuristic `:daemon:android`'s `performSemanticsActionByContentDescription` uses for its
 * `scrollForward`/`scrollBackward` arms.
 */
public class SemanticsUiObject
internal constructor(
  internal val rule: ComposeContentTestRule,
  public val node: SemanticsNode,
) : UiObject() {

  private val config = node.config

  override val text: CharSequence?
    get() {
      config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
      return config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }
    }

  override val contentDescription: CharSequence?
    get() = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

  /** Compose has no JVM-class concept on a node — always `null`. */
  override val className: CharSequence?
    get() = null

  /** Best stable per-node id Compose offers. */
  override val resourceName: String?
    get() = config.getOrNull(SemanticsProperties.TestTag)

  override fun click(): Boolean = invokeLambda(SemanticsActions.OnClick)

  override fun longClick(): Boolean = invokeLambda(SemanticsActions.OnLongClick)

  override fun requestFocus(): Boolean = invokeLambda(SemanticsActions.RequestFocus)

  override fun expand(): Boolean = invokeLambda(SemanticsActions.Expand)

  override fun collapse(): Boolean = invokeLambda(SemanticsActions.Collapse)

  override fun dismiss(): Boolean = invokeLambda(SemanticsActions.Dismiss)

  override fun scrollForward(): Boolean =
    invokeScrollBy(dx = 0f, dy = node.size.height.toFloat())

  override fun scrollBackward(): Boolean =
    invokeScrollBy(dx = 0f, dy = -node.size.height.toFloat())

  /** Routes through `SemanticsActions.SetText` (the same path Compose's a11y delegate uses). */
  override fun inputText(value: CharSequence): Boolean {
    val action = config.getOrNull(SemanticsActions.SetText) ?: return false
    val lambda = action.action ?: return false
    var accepted = false
    rule.runOnUiThread { accepted = lambda.invoke(AnnotatedString(value.toString())) }
    rule.waitForIdle()
    return accepted
  }

  private fun invokeLambda(
    key:
      androidx.compose.ui.semantics.SemanticsPropertyKey<
        androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>
      >
  ): Boolean {
    val action = config.getOrNull(key) ?: return false
    val lambda = action.action ?: return false
    var accepted = false
    rule.runOnUiThread { accepted = lambda.invoke() }
    rule.waitForIdle()
    return accepted
  }

  private fun invokeScrollBy(dx: Float, dy: Float): Boolean {
    val action = config.getOrNull(SemanticsActions.ScrollBy) ?: return false
    val lambda = action.action ?: return false
    var accepted = false
    rule.runOnUiThread { accepted = lambda.invoke(dx, dy) }
    rule.waitForIdle()
    return accepted
  }
}

/**
 * Selector predicate over [AccessibilityNodeInfo]. Mirrors the chains of
 * `androidx.test.uiautomator.BySelector` that don't need an on-device gesture pipeline:
 *
 *   - String fields ([text], [desc], [clazz], [res]) accept either an exact value (`String`) or
 *     a regex ([Pattern]) — same as upstream, where `By.text(String)` is exact and
 *     `By.text(Pattern)` is regex.
 *   - Boolean state predicates ([enabled], [clickable], [longClickable], [checkable], [checked],
 *     [selected], [focused], [scrollable]).
 *   - Tree predicates [hasChild] / [hasDescendant] — recurse the same matching machinery.
 *
 * Selectors are immutable; chained calls return copies. Build them via the [By] factory.
 */
@ConsistentCopyVisibility
public data class Selector
internal constructor(
  internal val text: TextMatch? = null,
  internal val desc: TextMatch? = null,
  internal val clazz: TextMatch? = null,
  internal val res: TextMatch? = null,
  internal val enabled: Boolean? = null,
  internal val clickable: Boolean? = null,
  internal val longClickable: Boolean? = null,
  internal val checkable: Boolean? = null,
  internal val checked: Boolean? = null,
  internal val selected: Boolean? = null,
  internal val focused: Boolean? = null,
  internal val scrollable: Boolean? = null,
  internal val children: List<Selector> = emptyList(),
  internal val descendants: List<Selector> = emptyList(),
) {
  public fun text(value: String): Selector = copy(text = TextMatch.Exact(value))

  public fun text(pattern: Pattern): Selector = copy(text = TextMatch.Regex(pattern.pattern()))

  public fun textMatches(regex: String): Selector = copy(text = TextMatch.Regex(regex))

  public fun desc(value: String): Selector = copy(desc = TextMatch.Exact(value))

  public fun desc(pattern: Pattern): Selector = copy(desc = TextMatch.Regex(pattern.pattern()))

  public fun clazz(value: String): Selector = copy(clazz = TextMatch.Exact(value))

  public fun res(value: String): Selector = copy(res = TextMatch.Exact(value))

  public fun enabled(b: Boolean = true): Selector = copy(enabled = b)

  public fun clickable(b: Boolean = true): Selector = copy(clickable = b)

  public fun longClickable(b: Boolean = true): Selector = copy(longClickable = b)

  public fun checkable(b: Boolean = true): Selector = copy(checkable = b)

  public fun checked(b: Boolean = true): Selector = copy(checked = b)

  public fun selected(b: Boolean = true): Selector = copy(selected = b)

  public fun focused(b: Boolean = true): Selector = copy(focused = b)

  public fun scrollable(b: Boolean = true): Selector = copy(scrollable = b)

  public fun hasChild(selector: Selector): Selector = copy(children = children + selector)

  public fun hasDescendant(selector: Selector): Selector =
    copy(descendants = descendants + selector)

  internal fun matches(backing: NodeBacking): Boolean {
    if (text != null && !text.matches(backing.text)) return false
    if (desc != null && !desc.matches(backing.desc)) return false
    if (clazz != null && !clazz.matches(backing.clazz)) return false
    if (res != null && !res.matches(backing.res)) return false
    if (enabled != null && backing.isEnabled != null && enabled != backing.isEnabled) return false
    if (clickable != null && backing.isClickable != null && clickable != backing.isClickable)
      return false
    if (
      longClickable != null &&
        backing.isLongClickable != null &&
        longClickable != backing.isLongClickable
    )
      return false
    if (checkable != null && backing.isCheckable != null && checkable != backing.isCheckable)
      return false
    if (checked != null && backing.isChecked != null && checked != backing.isChecked) return false
    if (selected != null && backing.isSelected != null && selected != backing.isSelected)
      return false
    if (focused != null && backing.isFocused != null && focused != backing.isFocused) return false
    if (scrollable != null && backing.isScrollable != null && scrollable != backing.isScrollable)
      return false
    if (children.isNotEmpty()) {
      val direct = backing.children()
      for (childSel in children) {
        if (direct.none { childSel.matches(it) }) return false
      }
    }
    if (descendants.isNotEmpty()) {
      for (descSel in descendants) {
        if (!hasDescendantMatching(backing, descSel)) return false
      }
    }
    return true
  }

  private fun hasDescendantMatching(backing: NodeBacking, selector: Selector): Boolean {
    for (child in backing.children()) {
      if (selector.matches(child)) return true
      if (hasDescendantMatching(child, selector)) return true
    }
    return false
  }
}

internal sealed class TextMatch {
  abstract fun matches(value: CharSequence?): Boolean

  data class Exact(val expected: String) : TextMatch() {
    override fun matches(value: CharSequence?): Boolean = value?.toString() == expected
  }

  /**
   * Regex matcher. The regex source is stored as a `String` (rather than a compiled `Pattern`)
   * so that `equals` / `hashCode` round-trip cleanly through the JSON wire format —
   * `Pattern.equals` is reference identity, which would break selector round-trips even when
   * two patterns have identical sources.
   */
  data class Regex(val regex: String) : TextMatch() {
    @Transient private val compiled: Pattern = Pattern.compile(regex)

    override fun matches(value: CharSequence?): Boolean =
      value != null && compiled.matcher(value).matches()
  }
}

/** Selector entry points — mirrors `androidx.test.uiautomator.By`. */
public object By {
  public fun text(value: String): Selector = Selector().text(value)

  public fun text(pattern: Pattern): Selector = Selector().text(pattern)

  public fun textMatches(regex: String): Selector = Selector().textMatches(regex)

  public fun desc(value: String): Selector = Selector().desc(value)

  public fun desc(pattern: Pattern): Selector = Selector().desc(pattern)

  public fun clazz(value: String): Selector = Selector().clazz(value)

  public fun res(value: String): Selector = Selector().res(value)

  /**
   * Convenience alias for [res] — Compose's stable per-node id is the `Modifier.testTag(...)`
   * value, which the renderer surfaces as `viewIdResourceName` (matched by [res]). [HierarchySelectorBuilder]
   * emits `By.testTag("…")` snippets through this method so the copied selector is directly
   * runnable against the prototype.
   */
  public fun testTag(value: String): Selector = res(value)

  public fun clickable(): Selector = Selector().clickable()

  public fun checkable(): Selector = Selector().checkable()

  public fun scrollable(): Selector = Selector().scrollable()
}
