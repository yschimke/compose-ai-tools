// Coverage for the theme data-diff presenter (#1872) — pure mapping from a `ThemeDelta` to the
// display rows the history panel renders. No DOM.

import * as assert from "assert";

import { computeThemeDiffData } from "../webview/preview/historyDiffThemePresenter";
import { type ThemeDelta } from "../webview/shared/themeDiff";

describe("computeThemeDiffData", () => {
    it("returns an empty data set for null / empty deltas", () => {
        const empty = computeThemeDiffData(null);
        assert.strictEqual(empty.empty, true);
        assert.deepStrictEqual(empty.rows, []);
        assert.strictEqual(empty.addedCount, 0);
    });

    it("orders rows removed → added → changed with counts", () => {
        const delta: ThemeDelta = {
            schema: "compose-theme-diff/v1",
            added: [{ category: "color", key: "tertiary", value: "#FFFF00" }],
            removed: [{ category: "shape", key: "small", value: "Cut(4dp)" }],
            changed: [
                {
                    category: "color",
                    key: "primary",
                    from: "#FF0000",
                    to: "#0000FF",
                },
            ],
        };
        const data = computeThemeDiffData(delta);
        assert.strictEqual(data.empty, false);
        assert.strictEqual(data.addedCount, 1);
        assert.strictEqual(data.removedCount, 1);
        assert.strictEqual(data.changedCount, 1);
        assert.deepStrictEqual(
            data.rows.map((r) => r.kind),
            ["removed", "added", "changed"],
        );

        const [removed, added, changed] = data.rows;
        assert.deepStrictEqual(
            { kind: removed.kind, key: removed.key, value: removed.value },
            { kind: "removed", key: "small", value: "Cut(4dp)" },
        );
        assert.strictEqual(added.value, "#FFFF00");
        assert.strictEqual(changed.value, null);
        assert.strictEqual(changed.from, "#FF0000");
        assert.strictEqual(changed.to, "#0000FF");
    });
});
