package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
 *    dialog's window composited into it wherever its gravity puts it, so the sticker is the
 *    activity frame with the component floating inside. [cropPngToDialogWindow] crops to the
 *    window.
 */
internal object DialogWindowCapture {
  data class CaptureRoot(
    val interaction: SemanticsNodeInteraction,
    val semanticsRoot: SemanticsNode?,
  )

  /**
   * Per-frame dialog crop for a multi-frame capture, with the rect resolved once and reused.
   *
   * [gutter] is the `@CaptureGutter` expansion, and it has to be passed for the same reason the
   * still path passes one: a dialog capture is cropped to the dialog's own window rect, which is
   * inside the gutter the grown window and `MeasuredWrapBox` just made room for. Leaving it at the
   * default would crop those pixels straight back off, so a guttered dialog would publish a still
   * with its shadow and a GIF beside it without — the disagreement `@CaptureGutter`'s motion
   * support exists to prevent (compose-ai-tools#4452). Scroll products deliberately pass nothing: a
   * scrolling capture is documented as carrying no gutter.
   */
  class StableDialogCrop(
    private val gutter: DialogCropGutter = DialogCropGutter(),
    private val fixedAxisTarget: FixedAxisTarget = FixedAxisTarget(),
  ) {
    private var cropRect: android.graphics.Rect? = null

    @OptIn(ExperimentalRoborazziApi::class)
    fun captureFrame(
      rule: AndroidComposeTestRule<*, ComponentActivity>,
      file: File,
      roborazziOptions: RoborazziOptions,
    ): CaptureRoot {
      val root = resolveCaptureRoot(rule)
      root.interaction.captureRoboImage(file = file, roborazziOptions = roborazziOptions)
      val semanticsRoot = root.semanticsRoot
      val window = semanticsRoot?.let { shownDialogWindow(it) }
      if (semanticsRoot == null || window == null) {
        // Not a dialog preview: the frame is the hosting window, which the gutter grew in whole
        // **dp**. Trim it to the pixel target the still uses. Same precedence the still path
        // applies — a dialog crop frames the component itself, so it wins and this is skipped.
        fixedAxisTarget.applyTo(file)
        return root
      }
      val rect =
        cropRect
          ?: dialogWindowCropRect(file, semanticsRoot, window, gutter)?.also { cropRect = it }
      if (rect != null) cropPngToRect(file, rect)
      return root
    }
  }

  /**
   * The exact pixel size a **fixed** axis's capture must come out at, or `null` per axis for an
   * axis that wraps (and is therefore already the measured content plus its gutter).
   *
   * A Robolectric resource qualifier has no unit but dp, so the hosting window grows by
   * `ceil(totalGutterPx / density)` dp — at a fractional density that is more pixels than the
   * gutter actually resolves to. Two 4 dp edges at density 2.625 are 11 + 11 = 22 px, while the
   * qualifier grows 9 dp ≈ 24 px. The still has always corrected for that; motion products encoded
   * the qualifier-sized frame, so the same preview published a PNG at `frame + 22` and a GIF beside
   * it at `frame + 24` (compose-ai-tools#4467). Passing the target through to every per-frame
   * capture is what makes the two agree.
   *
   * All-null is the un-corrected behaviour, which is what a fully wrapped preview and every scroll
   * product want.
   */
  data class FixedAxisTarget(val widthPx: Int? = null, val heightPx: Int? = null) {
    internal fun applyTo(file: File) {
      if (widthPx == null && heightPx == null) return
      // A frame that won't decode is left alone rather than throwing. On the multi-frame paths
      // this runs inside `captureDecodableFrame`'s capture lambda, and that retry only absorbs a
      // transient Robolectric encode glitch when `FramePngReader.decode` is the thing that meets
      // it — an `IIOException` raised *here* escapes the loop and turns a frame that would have
      // re-encoded cleanly into an error sidecar. Skipping leaves the bad bytes on disk for the
      // decode below to catch and re-capture, and the fresh frame gets trimmed on the next
      // attempt. Only the decode failure is swallowed; anything else still propagates.
      runCatching { resizeFixedAxesPng(file, widthPx, heightPx) }
        .onFailure { if (it !is javax.imageio.IIOException) throw it }
    }
  }

  fun resolveCaptureRoot(rule: AndroidComposeTestRule<*, ComponentActivity>): CaptureRoot {
    val interactions = rule.onAllNodes(isRoot(), useUnmergedTree = true)
    val nodes = runCatching {
      interactions.fetchSemanticsNodes(atLeastOneRootRequired = false)
    }
      .getOrDefault(emptyList())
    if (nodes.size <= 1) return CaptureRoot(rule.onRoot(), nodes.firstOrNull())
    val resolved =
      selectCaptureRoot(nodes, rule.activity.window.decorView)
        ?: return CaptureRoot(rule.onRoot(), nodes.firstOrNull())
    return CaptureRoot(interactions[nodes.indexOf(resolved)], resolved)
  }

  /**
   * The semantics root representing the surface being captured, given every `isRoot()` node.
   *
   * Prefer the activity's own root — the normal single-root case, and a `Popup` over a real
   * surface, where the popup is decoration and the activity is the subject. The preference yields
   * only when the activity root has no content of its own *and* another root lives in a shown
   * dialog window, which is exactly the `Dialog` / `ModalBottomSheet` preview whose whole content
   * composes elsewhere.
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
    val activityRootHasSemantics = roots.any {
      it.belongsToWindow(activityDecorView) && it.descendantCount() > 1
    }
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

  private fun SemanticsNode.belongsToWindow(decorView: android.view.View): Boolean = runCatching {
    (root as? ViewRootForTest)?.view?.rootView === decorView.rootView
  }
    .getOrDefault(false)

  private fun SemanticsNode.descendantCount(): Int = runCatching {
    1 + children.sumOf { it.descendantCount() }
  }
    .getOrDefault(1)

  /**
   * The window of the currently-shown dialog [root] composes into, or `null` when it is not inside
   * one — an ordinary activity-hosted preview, or a `Popup`, which installs its own owner through
   * the window manager but never a `Dialog`.
   *
   * `getShownDialogs` keeps dismissed dialogs in the list, hence the `isShowing` filter.
   */
  fun shownDialogWindow(root: SemanticsNode): android.view.Window? = runCatching {
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
   * A `ModalBottomSheet`'s window fills the screen, so its rect covers the whole frame and this is
   * a no-op; a centred `Dialog` / `AlertDialog` crops to the dialog itself.
   */
  /**
   * [gutter] is the resolved `@CaptureGutter` in pixels, expanding the crop past the dialog window
   * so an elevation shadow the dialog draws outside its own bounds survives. The dialog is composed
   * into its own window and cropped to that window's rect here, which is a path the activity-hosted
   * wrap crop never sees — so without this the gutter reaches every capture except the one whose
   * component most reliably casts a shadow. Clamped to the captured frame: the dialog sits inside a
   * full-screen capture, so there is normally room, but a dialog flush against an edge simply gets
   * less gutter on that side rather than a crop that runs off the image.
   */
  fun cropPngToDialogWindow(
    file: File,
    root: SemanticsNode,
    window: android.view.Window,
    gutter: DialogCropGutter = DialogCropGutter(),
  ) {
    dialogWindowCropRect(file, root, window, gutter)?.let { cropPngToRect(file, it) }
  }

  /** Per-edge crop expansion in pixels; all-zero is the pre-`@CaptureGutter` behaviour. */
  data class DialogCropGutter(
    val leftPx: Int = 0,
    val topPx: Int = 0,
    val rightPx: Int = 0,
    val bottomPx: Int = 0,
  )

  fun dialogWindowCropRect(
    file: File,
    root: SemanticsNode,
    window: android.view.Window,
    gutter: DialogCropGutter = DialogCropGutter(),
  ): android.graphics.Rect? {
    if (!file.exists()) return null
    val original = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return null
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
    // Grow outward from the window's own rect, then clamp to the frame. Each edge is clamped
    // independently so a dialog near one edge keeps the gutter it can have on the other three.
    return android.graphics.Rect(
      (left - gutter.leftPx).coerceAtLeast(0),
      (top - gutter.topPx).coerceAtLeast(0),
      (left + width + gutter.rightPx).coerceAtMost(original.width),
      (top + height + gutter.bottomPx).coerceAtMost(original.height),
    )
  }

  private fun cropPngToRect(file: File, rect: android.graphics.Rect) {
    if (!file.exists()) return
    val original = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return
    val left = rect.left.coerceIn(0, original.width - 1)
    val top = rect.top.coerceIn(0, original.height - 1)
    val right = rect.right.coerceIn(left + 1, original.width)
    val bottom = rect.bottom.coerceIn(top + 1, original.height)
    if (left == 0 && top == 0 && right == original.width && bottom == original.height) return
    val cropped = original.getSubimage(left, top, right - left, bottom - top)
    runCatching { javax.imageio.ImageIO.write(cropped, "PNG", file) }
  }
}
