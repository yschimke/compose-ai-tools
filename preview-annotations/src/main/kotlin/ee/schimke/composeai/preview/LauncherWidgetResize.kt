package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into a multi-capture launcher-widget resize walk.
 *
 * Pairs with `@LauncherWidgetPreview` — same family, different shape: where
 * `@LauncherWidgetPreview` snaps to a single whole-cell footprint, `@LauncherWidgetResize` fans the
 * function out into one capture per cell stop on the path between [fromWidth] × [fromHeight] and
 * [toWidth] × [toHeight] under [resizeOrder].
 *
 * The Gradle plugin's discovery task picks this up by FQN, computes the stops via the same
 * algorithm `:data-launcher-widget-connector`'s `launcherWidgetStops(...)` uses, and emits one
 * `Capture` per stop. PNGs land at `renders/<id>_RESIZE_<w>x<h>.png` and can be flipped through
 * like a flipbook in any image viewer. [frameDelayMs] is carried through the manifest for a future
 * GIF-stitch pass; today only the per-stop PNGs are produced.
 *
 * Example — the original spec's `1×1 → 4×2` example under the default `WidthFirst` order produces
 * five captures (`1×1, 2×1, 3×1, 4×1, 4×2`):
 * ```
 * @Preview(showBackground = true, widthDp = 312, heightDp = 152)
 * @LauncherWidgetResize(fromWidth = 1, fromHeight = 1, toWidth = 4, toHeight = 2)
 * @Composable
 * fun MyWidgetResizePreview() {
 *   MyWidget()
 * }
 * ```
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class LauncherWidgetResize(
  /** Source whole-cell width. */
  val fromWidth: Int,
  /** Source whole-cell height. */
  val fromHeight: Int,
  /** Target whole-cell width. */
  val toWidth: Int,
  /** Target whole-cell height. */
  val toHeight: Int,
  /**
   * Cell edge length in dp applied to every stop on the walk. `-1` (the default sentinel — see
   * [LauncherWidgetPreview]) falls back to the connector's default (`72`).
   */
  val cellSizeDp: Int = LAUNCHER_WIDGET_CELL_SIZE_DEFAULT,
  /** Gap between adjacent cells in dp. `-1` falls back to the connector default (`8`). */
  val cellSpacingDp: Int = LAUNCHER_WIDGET_CELL_SPACING_DEFAULT,
  /** How the walk visits intermediate stops between source and target. */
  val resizeOrder: LauncherWidgetResizeOrder = LauncherWidgetResizeOrder.WidthFirst,
  /**
   * Per-frame delay in milliseconds — carried through the manifest for the future GIF-stitch pass
   * to pick up. Defaults to [DEFAULT_LAUNCHER_WIDGET_RESIZE_FRAME_DELAY_MS] ≈ 600ms.
   */
  val frameDelayMs: Int = DEFAULT_LAUNCHER_WIDGET_RESIZE_FRAME_DELAY_MS,
)

/**
 * Default per-frame delay for `@LauncherWidgetResize` — ~600ms gives a reader enough dwell at each
 * cell stop to register the snap before the next one. Mirrors the rationale behind
 * `DEFAULT_FOCUS_GIF_FRAME_DELAY_MS` (focus uses ~800ms for ripple-fade dwell; resize stops have no
 * fade so 600ms is enough).
 */
const val DEFAULT_LAUNCHER_WIDGET_RESIZE_FRAME_DELAY_MS: Int = 600
