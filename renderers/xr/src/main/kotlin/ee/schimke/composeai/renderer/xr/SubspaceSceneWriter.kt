package ee.schimke.composeai.renderer.xr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.xr.SpatialPanel
import ee.schimke.composeai.xr.SpatialScene
import ee.schimke.composeai.xr.SpatialSemanticsTree
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

/**
 * Writes the producer side of the SpatialScene render output: the per-panel texture PNGs and the
 * `scene.json` the VS Code 3D viewer consumes (see docs/design/SPATIAL_SCENE_CONTRACT.md and the
 * consumer in PR #1704). Both land in one directory; the scene's `<id>.png` texture references are
 * relative to it, which is the `textureBaseUri` the viewer resolves against.
 *
 * Must run under Robolectric with the capture properties the gradle plugin sets
 * (`robolectric.graphicsMode=NATIVE`, `pixelCopyRenderMode=hardware`, `roborazzi.test.record=true`)
 * — `captureRoboImage` rasterises the panel content there exactly as the Compose `@Preview` path
 * does. `SubspaceSceneRecorder` recovers the poses + content views; this writes the textures +
 * scene that match.
 */
public object SubspaceSceneWriter {

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Rasterises each panel's live content [View] (recovered by
   * [SubspaceSceneRecorder.recordAllWithViews]) to `<id>.png` under [outDir] — the same `<id>.png`
   * convention the recorder stamps into each panel's `texture`, so the scene and its textures line
   * up. This is the production texture path: it captures the panel content exactly as it composed
   * in the subspace, so the viewer shows real panels rather than placeholders.
   *
   * Each view is captured at its panel's true size ([SpatialPanel.sizeDp] × display density), so
   * content that `fillMaxSize()`s fills the panel the same way the scene geometry frames it — a
   * panel whose view couldn't be recovered (or has no host activity) is simply skipped (its
   * `<id>.png` stays absent and the viewer falls back to a placeholder).
   */
  public fun captureViewTextures(
    outDir: File,
    panels: List<SpatialPanel>,
    panelViews: Map<String, View>,
  ) {
    outDir.mkdirs()
    // Detach every panel view from its fake-panel window up front: each is hosted in its own
    // APPLICATION_PANEL window, and roborazzi's capture resolves the view through Espresso, whose
    // root selection prefers those panel windows over the activity content we re-parent into. With
    // the panel windows gone, Espresso resolves against the activity and the capture matches.
    val views = panels.mapNotNull { panel -> panelViews[panel.id]?.let { panel to it } }
    views.forEach { (_, view) -> detachFromWindow(view) }
    for ((panel, view) in views) {
      captureViewAtPanelSize(view, panel, File(outDir, "${panel.id}.png"))
    }
  }

  /**
   * Captures [view] to [file] at the panel's true pixel size ([SpatialPanel.sizeDp] × display
   * density).
   *
   * The panel content only draws once its `AndroidComposeView` is attached to a window and laid
   * out, so we re-parent the (detached) view into the activity content frame with *fixed*
   * panel-sized layout params — the content frame then measures the child at exactly that size
   * regardless of screen size, where roborazzi's own `View.captureRoboImage` would force
   * `WRAP_CONTENT` and clamp a panel larger than the screen. After an idle so Compose lays out and
   * draws, we draw the view into a panel-sized bitmap (real Compose pixels under Robolectric NATIVE
   * graphics, the same way roborazzi's standalone-view capture does) and hand that to roborazzi to
   * write — sidestepping the Espresso root resolution its view capture uses, which the fake
   * panel/orbiter windows defeat. The view must already be detached from its fake-panel window (see
   * [captureViewTextures]).
   */
  private fun captureViewAtPanelSize(view: View, panel: SpatialPanel, file: File) {
    val activity = activityOf(view) ?: return
    val density = view.resources.displayMetrics.density
    val wPx = (panel.sizeDp.width * density).roundToInt().coerceAtLeast(1)
    val hPx = (panel.sizeDp.height * density).roundToInt().coerceAtLeast(1)

    val content = activity.findViewById<ViewGroup>(android.R.id.content)
    content.addView(view, ViewGroup.LayoutParams(wPx, hPx))
    try {
      view.measure(
        MeasureSpec.makeMeasureSpec(wPx, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(hPx, MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, wPx, hPx)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      val bitmap = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
      view.draw(Canvas(bitmap))
      bitmap.captureRoboImage(file)
    } finally {
      content.removeView(view)
    }
  }

  /**
   * The fake panel entity hosts each panel's content view in its own `WindowManager` window. Detach
   * it so it can be re-parented into the activity for capture. Rendering is already complete by
   * this point, so removing the panel's window is harmless.
   */
  private fun detachFromWindow(view: View) {
    if (!view.isAttachedToWindow) return
    val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
    runCatching { wm.removeViewImmediate(view) }
  }

  /** The [Activity] hosting [view], unwrapping any [ContextWrapper] chain, or null if none. */
  private fun activityOf(view: View): Activity? {
    var context: Context? = view.context
    while (context is ContextWrapper) {
      if (context is Activity) return context
      context = context.baseContext
    }
    return null
  }

  /** Serialises [scene] to `scene.json` under [outDir] in the wire-contract shape. */
  public fun writeScene(outDir: File, scene: SpatialScene): File {
    outDir.mkdirs()
    val file = File(outDir, "scene.json")
    file.writeText(json.encodeToString(SpatialScene.serializer(), scene))
    return file
  }

  // `encodeDefaults = true` so `version` + `units` ride the wire (the consumer's
  // `isSpatialSemanticsTree` guard requires both); `explicitNulls = false` keeps the optional
  // 2D-node fields off the wire — matching the daemon-side `SpatialSemanticsDataProducer`, which
  // writes the same `compose-spatial-semantics.json` for ordinary previews.
  private val treeJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
  }

  /**
   * Serialises the unified 3D-over-2D [SpatialSemanticsTree] to `compose-spatial-semantics.json`
   * under [outDir] — the `compose/spatial-semantics` data product for an XR preview, carrying the
   * real multi-panel layout with each panel's 2D semantics. Filename kept in sync with
   * `SpatialSemanticsDataProducer.FILE`.
   */
  public fun writeSemanticsTree(outDir: File, tree: SpatialSemanticsTree): File {
    outDir.mkdirs()
    val file = File(outDir, "compose-spatial-semantics.json")
    file.writeText(treeJson.encodeToString(SpatialSemanticsTree.serializer(), tree))
    return file
  }
}
