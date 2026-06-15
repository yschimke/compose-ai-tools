// Coverage for the a11y data-diff presenter (#1872) — pure mapping from an `A11yDelta` to the
// display rows the history panel renders. No DOM.

import * as assert from "assert";

import { computeA11yDiffData } from "../webview/preview/historyDiffA11yPresenter";
import { type A11yDelta } from "../webview/shared/a11yDiff";

describe("computeA11yDiffData", () => {
    it("returns an empty data set for null deltas", () => {
        const empty = computeA11yDiffData(null);
        assert.strictEqual(empty.empty, true);
        assert.deepStrictEqual(empty.rows, []);
    });

    it("orders rows removed → added → changed with field rows on changes", () => {
        const delta: A11yDelta = {
            schema: "a11y-hierarchy-diff/v1",
            added: [{ key: "ref:a4", label: "Hero", role: "Image" }],
            removed: [{ key: "ref:a3", label: "Caption", role: null }],
            changed: [
                {
                    key: "ref:a1",
                    label: "Send",
                    role: "Button",
                    changes: [{ field: "label", from: "Submit", to: "Send" }],
                },
            ],
        };
        const data = computeA11yDiffData(delta);
        assert.strictEqual(data.empty, false);
        assert.strictEqual(data.addedCount, 1);
        assert.strictEqual(data.removedCount, 1);
        assert.strictEqual(data.changedCount, 1);
        assert.deepStrictEqual(
            data.rows.map((r) => r.kind),
            ["removed", "added", "changed"],
        );
        // Added/removed rows carry no field rows; the changed row carries the field delta.
        assert.deepStrictEqual(data.rows[0].fields, []);
        assert.ok(data.rows[1].label.includes("Hero"));
        assert.deepStrictEqual(data.rows[2].fields, [
            { field: "label", from: "Submit", to: "Send" },
        ]);
        assert.ok(data.rows[2].label.includes("role=Button"));
    });
});
