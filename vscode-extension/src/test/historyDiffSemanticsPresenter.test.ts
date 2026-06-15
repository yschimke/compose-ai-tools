// History-diff semantics presenter (#1872). Pins delta → display-row derivation: ordering
// (removed, added, changed), counts, the human label, and field rows for changed nodes.

import * as assert from "assert";
import { computeSemanticsDiffData } from "../webview/preview/historyDiffSemanticsPresenter";
import { type SemanticsDelta } from "../webview/shared/semanticsDiff";

describe("computeSemanticsDiffData", () => {
    it("returns an empty data set for a null delta", () => {
        const data = computeSemanticsDiffData(null);
        assert.strictEqual(data.empty, true);
        assert.strictEqual(data.rows.length, 0);
        assert.strictEqual(data.addedCount, 0);
        assert.strictEqual(data.removedCount, 0);
        assert.strictEqual(data.changedCount, 0);
    });

    it("emits rows in removed → added → changed order with counts", () => {
        const delta: SemanticsDelta = {
            schema: "compose-semantics-diff/v1",
            removed: [
                {
                    ref: "r/role:Old",
                    role: "Old",
                    testTag: null,
                    text: null,
                    label: null,
                },
            ],
            added: [
                {
                    ref: "r/tag:new",
                    role: null,
                    testTag: "new",
                    text: "Hi",
                    label: null,
                },
            ],
            changed: [
                {
                    ref: "r/tag:greeting",
                    anchor: "tag:greeting",
                    changes: [{ field: "text", from: "Hello", to: "World" }],
                },
            ],
        };

        const data = computeSemanticsDiffData(delta);

        assert.strictEqual(data.empty, false);
        assert.strictEqual(data.removedCount, 1);
        assert.strictEqual(data.addedCount, 1);
        assert.strictEqual(data.changedCount, 1);
        assert.deepStrictEqual(
            data.rows.map((r) => r.kind),
            ["removed", "added", "changed"],
        );
        // Added row's label folds in testTag + text hints.
        assert.strictEqual(
            data.rows[1].label,
            'r/tag:new (testTag=new text="Hi")',
        );
        // Changed row carries the field-level delta.
        assert.deepStrictEqual(data.rows[2].fields, [
            { field: "text", from: "Hello", to: "World" },
        ]);
        assert.strictEqual(data.rows[2].label, "r/tag:greeting (tag:greeting)");
    });

    it("labels a bare node by its ref alone", () => {
        const data = computeSemanticsDiffData({
            schema: "compose-semantics-diff/v1",
            removed: [
                {
                    ref: "r/node",
                    role: null,
                    testTag: null,
                    text: null,
                    label: null,
                },
            ],
            added: [],
            changed: [],
        });
        assert.strictEqual(data.rows[0].label, "r/node");
    });
});
