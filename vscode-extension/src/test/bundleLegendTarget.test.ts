// Regression test for the bundle-legend ↔ table-row lookup. The
// previous implementation called `document.querySelector` with a
// pair of comma-separated selectors that matched both overlay boxes
// (inside `<preview-grid>`) and table rows (inside `<data-tabs>`).
// Document-order means the overlay box won every time, so clicking
// a legend entry scrolled the preview image instead of the table
// row — defeating the point of the click. `findLegendTarget` scopes
// the lookup to the `<data-tabs>` subtree so the overlay box is out
// of reach by construction.

import * as assert from "assert";
import { findLegendTarget } from "../webview/preview/bundleLegendTarget";

function buildShell(): HTMLElement {
    // Mirror the production DOM order: preview-grid (with overlay
    // boxes) comes *before* data-tabs (with the table rows). Each
    // surface has the same `data-bundle` and the overlay shares the
    // entry id as `data-overlay-id`; the table row uses
    // `data-legend-id`. The buggy selector picked the overlay.
    document.body.innerHTML = `
        <div id="preview-grid">
            <div class="preview-card">
                <box-overlay data-bundle="a11y">
                    <div class="overlay-box" data-overlay-id="row-3"></div>
                </box-overlay>
            </div>
        </div>
        <data-tabs>
            <div class="data-tab-body" data-bundle="a11y">
                <div class="bundle-tab-body" data-bundle="a11y">
                    <table>
                        <tr id="target-row" data-legend-id="row-3"></tr>
                    </table>
                </div>
            </div>
        </data-tabs>
    `;
    return document.querySelector<HTMLElement>("data-tabs")!;
}

describe("findLegendTarget", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("returns the table row inside the data-tabs subtree, not the overlay box above", () => {
        const dataTabs = buildShell();
        const target = findLegendTarget(dataTabs, "a11y", "row-3");
        assert.ok(target, "expected a target for entry row-3");
        assert.strictEqual(
            target!.id,
            "target-row",
            "must scope to the data-tabs subtree so the overlay box doesn't win",
        );
    });

    it("falls back to data-overlay-id inside the tab body when no data-legend-id matches", () => {
        // Legacy tables that haven't wired the legend-id mirroring
        // still expose `data-overlay-id` on rows for the existing
        // hover correlation. The fallback should match those too —
        // but only inside the tab body, never the preview overlay.
        document.body.innerHTML = `
            <div id="preview-grid">
                <div class="preview-card">
                    <box-overlay data-bundle="text">
                        <div data-overlay-id="row-9"></div>
                    </box-overlay>
                </div>
            </div>
            <data-tabs>
                <div class="data-tab-body" data-bundle="text">
                    <table>
                        <tr id="legacy-row" data-overlay-id="row-9"></tr>
                    </table>
                </div>
            </data-tabs>
        `;
        const dataTabs = document.querySelector<HTMLElement>("data-tabs")!;
        const target = findLegendTarget(dataTabs, "text", "row-9");
        assert.ok(target);
        assert.strictEqual(target!.id, "legacy-row");
    });

    it("returns null when the active tab has no body in the data-tabs subtree", () => {
        document.body.innerHTML = `<data-tabs></data-tabs>`;
        const dataTabs = document.querySelector<HTMLElement>("data-tabs")!;
        const target = findLegendTarget(dataTabs, "a11y", "row-3");
        assert.strictEqual(target, null);
    });

    it("returns null when the tab body has no matching row id", () => {
        document.body.innerHTML = `
            <data-tabs>
                <div data-bundle="a11y"></div>
            </data-tabs>
        `;
        const dataTabs = document.querySelector<HTMLElement>("data-tabs")!;
        const target = findLegendTarget(dataTabs, "a11y", "row-3");
        assert.strictEqual(target, null);
    });
});
