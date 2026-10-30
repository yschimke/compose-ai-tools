package ee.schimke.composeai.simulator;

/**
 * The home screen's grid on one display, and the cell math widgets are sized by. Kept free of
 * Android types, as {@link DisplayGeometry} is, so it is tested on the plain JVM.
 *
 * <p>The cells follow the preview runtime's launcher grid ({@code runtimes/appwidget}): {@value
 * #CELL_DP}dp cells {@value #GAP_DP}dp apart, so a widget spanning {@code n} cells is {@code 72n +
 * 8(n-1)}dp across and a 4×2 widget is 312×152dp in a preview and on the simulator's home screen
 * alike. As many as fit go between the status area and the drawer handle, centred.
 */
final class LauncherGrid {

  static final int CELL_DP = 72;
  static final int GAP_DP = 8;
  static final int SIDE_DP = 8;
  static final int TOP_DP = 32;
  static final int BOTTOM_DP = 72;

  final int widthDp;
  final int heightDp;
  final float density;
  final int columns;
  final int rows;

  LauncherGrid(int widthDp, int heightDp, float density) {
    this.widthDp = widthDp;
    this.heightDp = heightDp;
    this.density = density;
    int step = CELL_DP + GAP_DP;
    columns = Math.max(1, (widthDp - 2 * SIDE_DP + GAP_DP) / step);
    rows = Math.max(1, (heightDp - TOP_DP - BOTTOM_DP + GAP_DP) / step);
  }

  /** Cells for [px] at [density]: {@code round((dp + gap) / (cell + gap))}, at least one. */
  static int cells(int px, float density) {
    if (px <= 0) return 1;
    float dp = px / density;
    return Math.max(1, Math.round((dp + GAP_DP) / (float) (CELL_DP + GAP_DP)));
  }

  /** The dp a span of [cells] covers. */
  static int sizeDp(int cells) {
    return CELL_DP * cells + GAP_DP * (cells - 1);
  }

  int px(float dp) {
    return Math.round(dp * density);
  }

  /** The left edge of the grid, centred on the display. */
  int left() {
    return px((widthDp - sizeDp(columns)) / 2f);
  }

  /** The top edge of the grid, centred between the status area and the drawer handle. */
  int top() {
    int free = heightDp - TOP_DP - BOTTOM_DP - sizeDp(rows);
    return px(TOP_DP + Math.max(0, free) / 2f);
  }

  /**
   * Where a [spanX]×[spanY] item at [cellX],[cellY] sits, in pixels: {left, top, right, bottom}.
   */
  int[] rect(int cellX, int cellY, int spanX, int spanY) {
    int step = CELL_DP + GAP_DP;
    int l = left() + px(cellX * step);
    int t = top() + px(cellY * step);
    return new int[] {l, t, l + px(sizeDp(spanX)), t + px(sizeDp(spanY))};
  }

  boolean same(LauncherGrid other) {
    return other != null
        && other.widthDp == widthDp
        && other.heightDp == heightDp
        && other.density == density;
  }

  @Override
  public String toString() {
    return columns + "×" + rows;
  }
}
