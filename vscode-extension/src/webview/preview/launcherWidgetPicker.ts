// Launcher-widget cell-grid picker.
//
// Pure logic + DOM helper for a `5×5`-style picker that lets the user choose a
// `LauncherWidgetOverride.cells` value for the focused preview. Reads the
// `LauncherWidgetPayload` (`supportedCells`, `resizeAxes`, current `cells`)
// surfaced by the `compose/launcher-widget` data product to gate which cells
// are clickable and which are read-only.
//
// Source of constraints, in payload-population order (later overrides earlier):
//   1. Annotation bounds via `@LauncherWidgetPreview(minCells, maxCells)`.
//   2. Glance `previewSizeMode = SizeMode.Single | Responsive | Exact`
//      (`:glance-preview-runtime`).
//   3. `<appwidget-provider>` XML auto-discovery from
//      `AppWidgetManager.installedProviders` (`:appwidget-preview-runtime`).
//
// The picker is source-agnostic — it consumes `LauncherWidgetPayload` fields
// uniformly regardless of which layer populated them.
//
// Module split:
//   * `buildLauncherWidgetPickerCells(...)` — pure function returning a
//     `PickerCell[][]` grid the renderer can stamp into the DOM. Testable
//     without a DOM environment.
//   * `renderLauncherWidgetPicker(...)` — DOM helper that wires the cells
//     into a `<table>` with click handlers. The toolbar consumer (a future
//     `focusToolbar.ts` integration) drops this into a popover.

import type {
    LauncherResizeAxes,
    LauncherWidgetPayload,
    LauncherWidgetSize,
} from "../../daemon/daemonProtocol";

/**
 * One cell in the rendered picker grid.
 *
 * `clickable` is the read+act gate: cells that fall outside the widget's
 * declared constraints (`!supportedCells.contains((w, h))`) or on a locked
 * axis (`resizeAxes = "horizontal"` with height differing from current) are
 * `clickable: false` and styled as disabled.
 *
 * `inCurrent` highlights the rectangle from `(1, 1)` to the current `cells`
 * — visually emphasises what the widget is laid out as right now, so a click
 * to expand reads as a drag-to-resize gesture.
 */
export interface PickerCell {
    width: number;
    height: number;
    clickable: boolean;
    inCurrent: boolean;
}

/** Default upper bound when the payload doesn't surface one. */
export const DEFAULT_PICKER_MAX = 5;

/**
 * Build the 2-D picker grid for [payload] with the given [currentCells] as
 * the highlight target. Returns a `rows × cols` array where each cell has
 * `clickable` set per the payload's resize-axis lock + `supportedCells`
 * membership test, and `inCurrent` set for cells inside the current
 * rectangle (top-left to current).
 *
 * Grid bounds: when `payload.supportedCells` is non-null and non-empty, the
 * grid extends to `max(cell.width)` × `max(cell.height)` across the supported
 * set. Otherwise falls back to [DEFAULT_PICKER_MAX]. The grid always extends
 * to at least the current cell so the "inCurrent" highlight is fully drawn.
 */
export function buildLauncherWidgetPickerCells(
    payload: LauncherWidgetPayload | null,
    currentCells: LauncherWidgetSize,
): PickerCell[][] {
    const supported = payload?.supportedCells ?? undefined;
    const resizeAxes: LauncherResizeAxes = payload?.resizeAxes ?? "both";

    // Pick grid dimensions. Prefer the supported-cells extent; fall back to
    // the default; always cover the current cell so the highlight is whole.
    const supportedMaxW =
        supported && supported.length > 0
            ? Math.max(...supported.map((c) => c.width))
            : DEFAULT_PICKER_MAX;
    const supportedMaxH =
        supported && supported.length > 0
            ? Math.max(...supported.map((c) => c.height))
            : DEFAULT_PICKER_MAX;
    const maxW = Math.max(supportedMaxW, currentCells.width, 1);
    const maxH = Math.max(supportedMaxH, currentCells.height, 1);

    // Fast lookup: which (w, h) pairs are in the supported set?
    const supportedKey = (w: number, h: number): string => `${w}x${h}`;
    const supportedSet =
        supported !== undefined
            ? new Set(supported.map((c) => supportedKey(c.width, c.height)))
            : null;

    const rows: PickerCell[][] = [];
    for (let h = 1; h <= maxH; h++) {
        const row: PickerCell[] = [];
        for (let w = 1; w <= maxW; w++) {
            row.push({
                width: w,
                height: h,
                clickable: isClickable(
                    w,
                    h,
                    currentCells,
                    resizeAxes,
                    supportedSet,
                ),
                inCurrent: w <= currentCells.width && h <= currentCells.height,
            });
        }
        rows.push(row);
    }
    return rows;
}

function isClickable(
    w: number,
    h: number,
    current: LauncherWidgetSize,
    resizeAxes: LauncherResizeAxes,
    supportedSet: Set<string> | null,
): boolean {
    // `none` — widget declared "no resize", the picker is read-only and only
    // the current cell is "clickable" in the sense that re-selecting it is
    // a no-op but visually it stays the active one.
    if (resizeAxes === "none") {
        return w === current.width && h === current.height;
    }
    // Axis-locked modes — only the unlocked axis can move.
    if (resizeAxes === "horizontal" && h !== current.height) return false;
    if (resizeAxes === "vertical" && w !== current.width) return false;
    // Supported-set membership: if a set is declared, the cell must be in
    // it. Otherwise (null set) any cell in the grid is clickable.
    if (supportedSet !== null) {
        return supportedSet.has(`${w}x${h}`);
    }
    return true;
}

/**
 * DOM helper that renders the picker grid as a `<table>` and wires click
 * handlers. Returns the root `<table>` element — caller drops it into a
 * popover / dialog / inline container.
 *
 * Click handler fires [onSelect] with the picked `(w, h)` when the user
 * clicks a clickable cell; non-clickable cells receive no event handler so
 * VoiceOver / Talkback skip past them. Each row is a `<tr>`, each cell a
 * `<button>` inside a `<td>` (button so keyboard `Enter` / `Space` works).
 *
 * CSS classes the caller should style:
 *   * `.launcher-widget-picker` — root table
 *   * `.launcher-widget-picker-cell` — every cell button
 *   * `.launcher-widget-picker-cell.in-current` — cells inside the active rectangle
 *   * `.launcher-widget-picker-cell.disabled` — non-clickable cells (axis lock /
 *     unsupported size)
 */
export function renderLauncherWidgetPicker(
    payload: LauncherWidgetPayload | null,
    currentCells: LauncherWidgetSize,
    onSelect: (cells: LauncherWidgetSize) => void,
): HTMLTableElement {
    const grid = buildLauncherWidgetPickerCells(payload, currentCells);
    const table = document.createElement("table");
    table.className = "launcher-widget-picker";
    table.setAttribute("role", "grid");
    table.setAttribute(
        "aria-label",
        `Launcher widget cell picker — current size ${currentCells.width}×${currentCells.height}`,
    );
    for (const row of grid) {
        const tr = document.createElement("tr");
        tr.setAttribute("role", "row");
        for (const cell of row) {
            const td = document.createElement("td");
            td.setAttribute("role", "gridcell");
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "launcher-widget-picker-cell";
            btn.dataset.width = String(cell.width);
            btn.dataset.height = String(cell.height);
            btn.setAttribute(
                "aria-label",
                `${cell.width}×${cell.height} cells`,
            );
            if (cell.inCurrent) {
                btn.classList.add("in-current");
            }
            if (cell.clickable) {
                btn.addEventListener("click", (evt) => {
                    evt.preventDefault();
                    evt.stopPropagation();
                    onSelect({ width: cell.width, height: cell.height });
                });
            } else {
                btn.classList.add("disabled");
                btn.disabled = true;
            }
            td.appendChild(btn);
            tr.appendChild(td);
        }
        table.appendChild(tr);
    }
    return table;
}
