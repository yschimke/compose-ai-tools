package ee.schimke.composeai.renderer

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import java.io.File

/**
 * Framing support for previews that compose into their own window — `Dialog`, `AlertDialog`,
 * `ModalBottomSheet` (issue #3048).
 *
 * The daemon's `RenderEngine` carries the twin of this logic. It is duplicated rather than shared
 * for the same reason `RenderEngine` itself is duplicated (see its file kdoc): the daemon takes no
 * compile-time dependency on this module's render internals. Reconcile when the v2 shared
 * render-body extraction lands — until then, a change here needs the same change there.
 *
 * Two things go wrong for these previews here, and both need fixing:
 *
 * 1. **The root is ambiguous.** The activity's root is still present — zero-sized and empty, but
 *    matched by `isRoot()` all the same — so `onRoot()` resolves to *two* nodes and
 *    `fetchSemanticsNode()` throws `Expected exactly '1' node but found '2'`. Roborazzi papers over
 *    that at the capture site, which is why these previews produced a PNG at all rather than
 *    failing loudly. [selectCaptureRoot] picks the subject deliberately instead.
 * 2. **The frame is the screen, not the component.** The capture spans the whole screen with the
 *    dialog's window composited into it wherever its gravity puts it, so the sticker is the activity
 *    frame with the component floating inside. [cropPngToDialogWindow] crops to the window.
 */
internal object DialogWindowCapture {

  /**
   * The semantics root representing the surface being captured, given every `isRoot()` node.
   *
   * Prefer the activity's own root — the normal single-root case, and a `Popup` over a real surface,
   * where the popup is decoration and the activity is the subject. The preference yields only when
   * the activity root has no content of its own *and* another root lives in a shown dialog window,
   * which is exactly the `Dialog` / `ModalBottomSheet` preview whose whole content composes
   * elsewhere.
   *
   * Keyed on the dialog window rather than on the activity root merely looking empty: a `Box`
   * carrying only a `background` modifier contributes no semantics node, so "no semantic
   * descendants" is not the same as "nothing rendered", and a popup must never win this.
   */
  fun selectCaptureRoot(
    roots: List<SemanticsNode>,
    activityDecorView: android.view.View,
  ): SemanticsNode? {
    if (roots.size <= 1) return roots.firstOrNull()
    val activityRootHasSemantics =
      roots.any { it.belongsToWindow(activityDecorView) && it.descendantCount() > 1 }
    val dialogOwnsThePreview =
      !activityRootHasSemantics && roots.any { shownDialogWindow(it) != null }
    return roots.maxWithOrNull(
      compareBy<SemanticsNode>(
        {
          val preferred =
            if (dialogOwnsThePreview) shownDialogWindow(it) != null
            else it.belongsToWindow(activityDecorView)
          if (preferred) 1 else 0
        },
        { it.descendantCount() },
        { it.size.width.toLong() * it.size.height.toLong() },
      )
    )
  }

  private fun SemanticsNode.belongsToWindow(decorView: android.view.View): Boolean =
    runCatching { (root as? ViewRootForTest)?.view?.rootView === decorView.rootView }
      .getOrDefault(false)

  private fun SemanticsNode.descendantCount(): Int =
    runCatching { 1 + children.sumOf { it.descendantCount() } }.getOrDefault(1)

  /**
   * The window of the currently-shown dialog [root] composes into, or `null` when it is not inside
   * one — an ordinary activity-hosted preview, or a `Popup`, which installs its own owner through
   * the window manager but never a `Dialog`.
   *
   * `getShownDialogs` keeps dismissed dialogs in the list, hence the `isShowing` filter.
   */
  fun shownDialogWindow(root: SemanticsNode): android.view.Window? =
    runCatching {
        val rootView = (root.root as? ViewRootForTest)?.view ?: return null
        org.robolectric.shadows.ShadowDialog.getShownDialogs()
          .lastOrNull { dialog ->
            val decor = dialog.window?.decorView
            dialog.isShowing &&
              decor != null &&
              generateSequence(rootView as android.view.View) { it.parent as? android.view.View }
                .any { it === decor }
          }
          ?.window
      }
      .getOrNull()

  /**
   * Crops [file] — a capture spanning the whole screen — down to [window]'s own rectangle.
   *
   * The rectangle cannot come from the node or its view: under Robolectric a dialog window is never
   * positioned, so `positionOnScreen`, `getLocationOnScreen` and the window's `attributes.x/y` all
   * report `0`, even though the capture composites the window at its gravity. So place the window
   * the way the framework does — [android.view.Gravity.apply] against the captured frame, using the
   * window's own gravity — rather than trusting coordinates Robolectric leaves at the origin.
   *
   * A `ModalBottomSheet`'s window fills the screen, so its rect covers the whole frame and this is a
   * no-op; a centred `Dialog` / `AlertDialog` crops to the dialog itself.
   */
  fun cropPngToDialogWindow(file: File, root: SemanticsNode, window: android.view.Window) {
    if (!file.exists()) return
    val original = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return
    val width = root.size.width.coerceIn(1, original.width)
    val height = root.size.height.coerceIn(1, original.height)
    val placed = android.graphics.Rect()
    android.view.Gravity.apply(
      window.attributes.gravity,
      width,
      height,
      android.graphics.Rect(0, 0, original.width, original.height),
      placed,
    )
    val left = placed.left.coerceIn(0, original.width - width)
    val top = placed.top.coerceIn(0, original.height - height)
    if (left == 0 && top == 0 && width == original.width && height == original.height) return
    val cropped = original.getSubimage(left, top, width, height)
    runCatching { javax.imageio.ImageIO.write(cropped, "PNG", file) }
  }
}
