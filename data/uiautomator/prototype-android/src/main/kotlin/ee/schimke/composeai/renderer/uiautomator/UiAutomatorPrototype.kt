package ee.schimke.composeai.renderer.uiautomator

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
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
   * **Compose caveat (not covered in this prototype).** Compose hosts everything inside one
   * `androidx.compose.ui.platform.AndroidComposeView`, which exposes child semantics through an
   * `AccessibilityNodeProvider` rather than as actual `View` children. Reaching Compose
   * widgets requires walking the `SemanticsOwner` tree (or going through the compose-test
   * `SemanticsNodeInteraction` machinery the host already uses). A follow-on `ViewBacking` /
   * `SemanticsBacking` adapter can keep the [Selector] surface identical for both — the
   * predicate set is the same, only the traversal differs.
   */
  public fun findObject(root: View, selector: Selector): UiObject? {
    walk(root) { v ->
      if (selector.matches(v)) {
        val info = v.createAccessibilityNodeInfo() ?: return@walk
        return UiObject(v, info)
      }
    }
    return null
  }

  /** Pre-order walk; all matches in document order. */
  public fun findObjects(root: View, selector: Selector): List<UiObject> {
    val out = mutableListOf<UiObject>()
    walk(root) { v ->
      if (selector.matches(v)) {
        val info = v.createAccessibilityNodeInfo() ?: return@walk
        out += UiObject(v, info)
      }
    }
    return out
  }

  internal inline fun walk(view: View, visit: (View) -> Unit) {
    val stack = ArrayDeque<View>()
    stack.addLast(view)
    while (stack.isNotEmpty()) {
      val v = stack.removeLast()
      visit(v)
      if (v is android.view.ViewGroup) {
        // Iterate in reverse so pop order matches document order.
        for (i in v.childCount - 1 downTo 0) {
          stack.addLast(v.getChildAt(i))
        }
      }
    }
  }
}

/**
 * One matched node. Loosely shaped after `androidx.test.uiautomator.UiObject2`, but actions
 * dispatch through `View.performAccessibilityAction` rather than `UiAutomation` (see the file
 * KDoc § "Actions").
 *
 * `info` is a snapshot at find time — read-only properties (`text`, `contentDescription`,
 * `isClickable`, etc.) are stable for the life of the object. Actions go through `view`
 * directly, so a click on a [UiObject] takes effect immediately on the host view.
 */
public class UiObject internal constructor(public val view: View, public val info: AccessibilityNodeInfo) {

  public val text: CharSequence?
    get() = info.text

  public val contentDescription: CharSequence?
    get() = info.contentDescription

  public val className: CharSequence?
    get() = info.className

  public val resourceName: String?
    get() = info.viewIdResourceName

  public fun click(): Boolean = view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)

  public fun longClick(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null)

  public fun scrollForward(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)

  public fun scrollBackward(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)

  public fun requestFocus(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS, null)

  public fun clearFocus(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, null)

  public fun expand(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null)

  public fun collapse(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null)

  public fun dismiss(): Boolean =
    view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)

  /**
   * Routes through `ACTION_SET_TEXT`. Compose's accessibility delegate maps this to
   * `SemanticsActions.SetText`; on plain `EditText`, the platform's `View` impl rewrites the
   * `Editable` directly.
   */
  public fun inputText(value: CharSequence): Boolean {
    val args =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
      }
    return view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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

  public fun text(pattern: Pattern): Selector = copy(text = TextMatch.Regex(pattern))

  public fun textMatches(regex: String): Selector =
    copy(text = TextMatch.Regex(Pattern.compile(regex)))

  public fun desc(value: String): Selector = copy(desc = TextMatch.Exact(value))

  public fun desc(pattern: Pattern): Selector = copy(desc = TextMatch.Regex(pattern))

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

  internal fun matches(view: View): Boolean {
    val ani = view.createAccessibilityNodeInfo() ?: return false
    if (text != null && !text.matches(ani.text)) return false
    if (desc != null && !desc.matches(ani.contentDescription)) return false
    if (clazz != null && !clazz.matches(ani.className)) return false
    if (res != null && !res.matches(ani.viewIdResourceName)) return false
    if (enabled != null && enabled != ani.isEnabled) return false
    if (clickable != null && clickable != ani.isClickable) return false
    if (longClickable != null && longClickable != ani.isLongClickable) return false
    if (checkable != null && checkable != ani.isCheckable) return false
    if (checked != null && checked != ani.isChecked) return false
    if (selected != null && selected != ani.isSelected) return false
    if (focused != null && focused != ani.isFocused) return false
    if (scrollable != null && scrollable != ani.isScrollable) return false
    if (children.isNotEmpty() && view is android.view.ViewGroup) {
      for (childSel in children) {
        var matched = false
        for (i in 0 until view.childCount) {
          if (childSel.matches(view.getChildAt(i))) {
            matched = true
            break
          }
        }
        if (!matched) return false
      }
    } else if (children.isNotEmpty()) {
      return false
    }
    if (descendants.isNotEmpty()) {
      for (descSel in descendants) {
        if (!hasDescendantMatching(view, descSel)) return false
      }
    }
    return true
  }

  private fun hasDescendantMatching(view: View, selector: Selector): Boolean {
    if (view !is android.view.ViewGroup) return false
    for (i in 0 until view.childCount) {
      val child = view.getChildAt(i)
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

  data class Regex(val pattern: Pattern) : TextMatch() {
    override fun matches(value: CharSequence?): Boolean =
      value != null && pattern.matcher(value).matches()
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

  public fun clickable(): Selector = Selector().clickable()

  public fun checkable(): Selector = Selector().checkable()

  public fun scrollable(): Selector = Selector().scrollable()
}

