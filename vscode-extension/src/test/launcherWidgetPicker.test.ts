// Launcher-widget picker — pure-logic + DOM-helper coverage.
//
// Asserts the picker's cell-clickability gating tracks the constraint
// payload populated by the three discovery layers (annotation bounds, Glance
// `previewSizeMode`, AppWidget XML auto-discovery) uniformly. The picker is
// payload-shape-driven, so a single test set covers all three sources.

import * as assert from "assert";
import type {
    LauncherWidgetPayload,
    LauncherWidgetSize,
} from "../daemon/daemonProtocol";
import {
    DEFAULT_PICKER_MAX,
    buildLauncherWidgetPickerCells,
    renderLauncherWidgetPicker,
} from "../webview/preview/launcherWidgetPicker";

function payload(
    overrides: Partial<LauncherWidgetPayload> = {},
): LauncherWidgetPayload {
    return {
        cells: { width: 4, height: 2 },
        cellSizeDp: 72,
        cellSpacingDp: 8,
        widthDp: 312,
        heightDp: 152,
        ...overrides,
    };
}

function cells(width: number, height: number): LauncherWidgetSize {
    return { width, height };
}

describe("launcherWidgetPicker — buildLauncherWidgetPickerCells", () => {
    it("falls back to a default rectangle when payload is null", () => {
        const grid = buildLauncherWidgetPickerCells(null, cells(1, 1));
        assert.strictEqual(grid.length, DEFAULT_PICKER_MAX);
        assert.strictEqual(grid[0].length, DEFAULT_PICKER_MAX);
        // Every cell is clickable, no constraint to gate.
        assert.ok(grid.flat().every((c) => c.clickable));
    });

    it("marks cells inside the current rectangle as inCurrent", () => {
        const grid = buildLauncherWidgetPickerCells(null, cells(3, 2));
        // (1..3) × (1..2) — 6 cells flagged.
        const inCurrentCount = grid.flat().filter((c) => c.inCurrent).length;
        assert.strictEqual(inCurrentCount, 6);
        // Edge case: (4, 1) is outside the current rectangle.
        assert.strictEqual(grid[0][3].inCurrent, false);
    });

    it("respects supportedCells as a sparse set (Glance Responsive)", () => {
        // Glance widget declares two responsive sizes.
        const supported = [cells(2, 1), cells(4, 2)];
        const grid = buildLauncherWidgetPickerCells(
            payload({ supportedCells: supported, resizeAxes: "both" }),
            cells(2, 1),
        );
        // Grid extends to the largest supported cell (4×2).
        assert.strictEqual(grid.length, 2);
        assert.strictEqual(grid[0].length, 4);
        // Only the two declared cells are clickable.
        const clickable = grid.flat().filter((c) => c.clickable);
        assert.strictEqual(clickable.length, 2);
        assert.ok(clickable.some((c) => c.width === 2 && c.height === 1));
        assert.ok(clickable.some((c) => c.width === 4 && c.height === 2));
    });

    it("respects supportedCells as a dense rectangle (AppWidget XML)", () => {
        // AppWidget XML declares min 1×1 → max 3×2 — every cell in the
        // rectangle is clickable.
        const supported: LauncherWidgetSize[] = [];
        for (let w = 1; w <= 3; w++)
            for (let h = 1; h <= 2; h++) supported.push(cells(w, h));
        const grid = buildLauncherWidgetPickerCells(
            payload({ supportedCells: supported, resizeAxes: "both" }),
            cells(2, 1),
        );
        assert.strictEqual(grid.length, 2);
        assert.strictEqual(grid[0].length, 3);
        assert.strictEqual(grid.flat().filter((c) => c.clickable).length, 6);
    });

    it("resizeAxes = none locks every cell except the current one", () => {
        // Glance `SizeMode.Single` translates to `supportedCells = [single]`,
        // `resizeAxes = "none"`. The picker is read-only.
        const grid = buildLauncherWidgetPickerCells(
            payload({
                supportedCells: [cells(3, 2)],
                resizeAxes: "none",
            }),
            cells(3, 2),
        );
        const clickable = grid.flat().filter((c) => c.clickable);
        assert.strictEqual(clickable.length, 1);
        assert.strictEqual(clickable[0].width, 3);
        assert.strictEqual(clickable[0].height, 2);
    });

    it("resizeAxes = horizontal locks the height axis at current", () => {
        const grid = buildLauncherWidgetPickerCells(
            payload({ supportedCells: null, resizeAxes: "horizontal" }),
            cells(2, 3),
        );
        // Only cells on row h=3 are clickable.
        const clickable = grid.flat().filter((c) => c.clickable);
        assert.ok(clickable.length > 0);
        assert.ok(clickable.every((c) => c.height === 3));
    });

    it("resizeAxes = vertical locks the width axis at current", () => {
        const grid = buildLauncherWidgetPickerCells(
            payload({ supportedCells: null, resizeAxes: "vertical" }),
            cells(2, 3),
        );
        const clickable = grid.flat().filter((c) => c.clickable);
        assert.ok(clickable.length > 0);
        assert.ok(clickable.every((c) => c.width === 2));
    });

    it("grid extends to cover the current cell even past supported max", () => {
        // Edge case: payload constraint is 1..2, but the override the daemon
        // already applied is (3, 3) — pre-flight clamp may not have run, or
        // the widget's metadata changed since last render. Grid should still
        // include (3, 3) so the inCurrent highlight is whole.
        const grid = buildLauncherWidgetPickerCells(
            payload({
                supportedCells: [cells(1, 1), cells(2, 2)],
                resizeAxes: "both",
            }),
            cells(3, 3),
        );
        assert.strictEqual(grid.length, 3);
        assert.strictEqual(grid[0].length, 3);
    });
});

describe("launcherWidgetPicker — renderLauncherWidgetPicker DOM helper", () => {
    // happy-dom is set up globally via `.mocharc.json`'s `file: setup-dom.ts`,
    // so `document` / `HTMLElement` are already on globalThis. No per-suite
    // fixture needed — same shape `a11yBundlePresenter.test.ts` and friends
    // use.

    it("renders a row per cell-height and a button per cell-width", () => {
        const table = renderLauncherWidgetPicker(
            payload({ supportedCells: null, resizeAxes: "both" }),
            cells(2, 1),
            () => {},
        );
        const rows = table.querySelectorAll("tr");
        assert.strictEqual(rows.length, DEFAULT_PICKER_MAX);
        const cellsInFirstRow = rows[0].querySelectorAll(
            ".launcher-widget-picker-cell",
        );
        assert.strictEqual(cellsInFirstRow.length, DEFAULT_PICKER_MAX);
    });

    it("disables non-clickable cells with .disabled and disabled=true", () => {
        const supported = [cells(2, 1), cells(4, 2)];
        const table = renderLauncherWidgetPicker(
            payload({ supportedCells: supported, resizeAxes: "both" }),
            cells(2, 1),
            () => {},
        );
        const disabled = table.querySelectorAll<HTMLButtonElement>(
            ".launcher-widget-picker-cell.disabled",
        );
        // Grid is 2×4 = 8 cells, supported has 2, so 6 disabled.
        assert.strictEqual(disabled.length, 6);
        for (const btn of Array.from(disabled)) {
            assert.strictEqual(btn.disabled, true);
        }
    });

    it("click on clickable cell fires onSelect with the picked size", () => {
        let picked: LauncherWidgetSize | null = null;
        const table = renderLauncherWidgetPicker(
            payload({ supportedCells: null, resizeAxes: "both" }),
            cells(1, 1),
            (c) => {
                picked = c;
            },
        );
        const btn = table.querySelector<HTMLButtonElement>(
            '.launcher-widget-picker-cell[data-width="3"][data-height="2"]',
        );
        assert.ok(btn);
        btn?.click();
        assert.deepStrictEqual(picked, { width: 3, height: 2 });
    });

    it("click on disabled cell does not fire onSelect", () => {
        let calls = 0;
        // Grid extends to 4×4 (max of supportedCells), but only (1,1) and
        // (4,4) are clickable — (3,3) is disabled.
        const table = renderLauncherWidgetPicker(
            payload({
                supportedCells: [cells(1, 1), cells(4, 4)],
                resizeAxes: "both",
            }),
            cells(1, 1),
            () => {
                calls += 1;
            },
        );
        const btn = table.querySelector<HTMLButtonElement>(
            '.launcher-widget-picker-cell[data-width="3"][data-height="3"]',
        );
        assert.ok(btn);
        // Button is `disabled=true` — click event fires no handler.
        btn?.click();
        assert.strictEqual(calls, 0);
    });

    it("inCurrent cells get the .in-current class", () => {
        const table = renderLauncherWidgetPicker(
            payload({ supportedCells: null, resizeAxes: "both" }),
            cells(2, 2),
            () => {},
        );
        const inCurrent = table.querySelectorAll(
            ".launcher-widget-picker-cell.in-current",
        );
        // (1..2) × (1..2) = 4 cells in the current rectangle.
        assert.strictEqual(inCurrent.length, 4);
    });
});
