package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into launcher-widget container sizing.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN, mirroring
 * [AmbientPreview] / [FocusedPreview]: consumers that want to use the annotation in their own code
 * depend on `ee.schimke.composeai:preview-annotations`. The renderer translates the captured cell
 * count into a `LauncherWidgetExtension` (see `:data-launcher-widget-connector`) wrapping the
 * preview's composition, so the rendered PNG sizes to the resolved dp footprint a real Android
 * launcher would assign — `cells = (4, 2)` at the default `72dp` cell size resolves to a `4*72 +
 * 3*8 = 312.dp` wide by `2*72 + 1*8 = 152.dp` tall container.
 *
 * Static `@Preview` rendering through the plugin's per-capture loop applies the wrap before each
 * capture; daemon-driven `renderNow.overrides.launcherWidget` already does the same through the
 * connector's planner. Both paths end up at the same `AroundComposable` extension.
 *
 * Example:
 * ```
 * @Preview(showBackground = true)
 * @LauncherWidgetPreview(width = 4, height = 2)
 * @Composable
 * fun MyWidgetPreview() {
 *   MyWidget()
 * }
 * ```
 *
 * The annotation pairs naturally with the `GlanceAppWidgetContent` helper in
 * `:glance-preview-runtime` or a hand-built `RemoteViews` body — the launcher-cell wrap is
 * orthogonal to what's inside the cell.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class LauncherWidgetPreview(
  /** Target whole-cell width on the launcher grid. Clamped into [minWidth]..[maxWidth]. */
  val width: Int,
  /** Target whole-cell height on the launcher grid. Clamped into [minHeight]..[maxHeight]. */
  val height: Int,
  /**
   * Cell edge length in dp. `-1` (the default sentinel — annotation parameters can't be nullable)
   * falls back to the connector's default (`72`), matching a Pixel launcher's `5×5` grid on a 411dp
   * screen.
   */
  val cellSizeDp: Int = LAUNCHER_WIDGET_CELL_SIZE_DEFAULT,
  /** Gap between adjacent cells in dp. `-1` falls back to the connector default (`8`). */
  val cellSpacingDp: Int = LAUNCHER_WIDGET_CELL_SPACING_DEFAULT,
  /** Inclusive lower bound on the cell width. `-1` falls back to `1`. */
  val minWidth: Int = LAUNCHER_WIDGET_CELL_BOUND_DEFAULT,
  /** Inclusive lower bound on the cell height. `-1` falls back to `1`. */
  val minHeight: Int = LAUNCHER_WIDGET_CELL_BOUND_DEFAULT,
  /** Inclusive upper bound on the cell width. `-1` falls back to `5`. */
  val maxWidth: Int = LAUNCHER_WIDGET_CELL_BOUND_DEFAULT,
  /** Inclusive upper bound on the cell height. `-1` falls back to `5`. */
  val maxHeight: Int = LAUNCHER_WIDGET_CELL_BOUND_DEFAULT,
  /**
   * Hint for a future daemon-side resize-loop orchestrator on how to walk intermediate stops
   * between two sizes. The single-shot static-preview path always snaps to ([width], [height]);
   * this field is plumbed through the manifest so a loop driver can read it later.
   */
  val resizeOrder: LauncherWidgetResizeOrder = LauncherWidgetResizeOrder.WidthFirst,
  /**
   * When `true`, render the widget *inside a simulated launcher home screen* — wallpaper, status
   * bar, weather header, app-icon grid and dock — with the widget placed on the home screen at the
   * [width] × [height] cell footprint, instead of as a bare cell-sized box. Pair it with a
   * phone-shaped `@Preview(widthDp = …, heightDp = …)` (or `device = …`) so the chrome fills a
   * full-device canvas; the cell footprint then sizes the widget *on* that home screen. Defaults to
   * `false` (the original bare cell-sized behaviour).
   */
  val launcherMode: Boolean = false,
)

/**
 * Sentinel used by [LauncherWidgetPreview]'s optional `Int` parameters because annotation
 * parameters cannot be nullable. Discovery treats the value as "fall back to the connector default"
 * rather than as a literal `-1`.
 */
const val LAUNCHER_WIDGET_CELL_SIZE_DEFAULT: Int = -1

/** Sentinel for [LauncherWidgetPreview.cellSpacingDp]. */
const val LAUNCHER_WIDGET_CELL_SPACING_DEFAULT: Int = -1

/** Sentinel for the [LauncherWidgetPreview] min / max cell bound parameters. */
const val LAUNCHER_WIDGET_CELL_BOUND_DEFAULT: Int = -1

/**
 * Discoverable mirror of `LauncherResizeOrder` in `:daemon:core`. The Gradle plugin can't load the
 * daemon-protocol module at discovery time, so we duplicate the relevant enum here and the renderer
 * translates at render time.
 */
enum class LauncherWidgetResizeOrder {
  Diagonal,
  WidthFirst,
  HeightFirst,
}
