// Coverage for the client-side a11y-hierarchy data-diff (#1872) used by the history panel. Pure-data
// tests (no DOM): two `a11y/hierarchy` payloads in, an `A11yDelta` out.

import * as assert from "assert";

import {
    a11yDeltaIsEmpty,
    diffA11y,
    type A11yPayload,
} from "../webview/shared/a11yDiff";

describe("diffA11y", () => {
    it("matches by stable ref and reports changed / added / removed", () => {
        const base: A11yPayload = {
            nodes: [
                {
                    ref: "a1",
                    role: "Button",
                    label: "Submit",
                    states: ["Enabled"],
                    merged: true,
                },
                { ref: "a3", label: "Caption", states: [], merged: true },
            ],
        };
        const head: A11yPayload = {
            nodes: [
                {
                    ref: "a1",
                    role: "Button",
                    label: "Send",
                    states: ["Disabled"],
                    merged: true,
                },
                { ref: "a4", role: "Image", label: "Hero", merged: true },
            ],
        };

        const delta = diffA11y(base, head);

        assert.deepStrictEqual(
            delta.added.map((n) => n.label),
            ["Hero"],
        );
        assert.deepStrictEqual(
            delta.removed.map((n) => n.label),
            ["Caption"],
        );
        assert.strictEqual(delta.changed.length, 1);
        const change = delta.changed[0];
        assert.strictEqual(change.key, "ref:a1");
        const fields = change.changes.map((c) => c.field);
        assert.deepStrictEqual(fields, ["label", "states"]);
        assert.deepStrictEqual(change.changes[0], {
            field: "label",
            from: "Submit",
            to: "Send",
        });
        assert.deepStrictEqual(change.changes[1], {
            field: "states",
            from: "Enabled",
            to: "Disabled",
        });
    });

    it("ignores boundsInScreen churn (pixel diff's job)", () => {
        const base: A11yPayload = {
            nodes: [{ ref: "a1", label: "X", boundsInScreen: "0,0,1,1" }],
        };
        const head: A11yPayload = {
            nodes: [{ ref: "a1", label: "X", boundsInScreen: "9,9,9,9" }],
        };
        assert.ok(a11yDeltaIsEmpty(diffA11y(base, head)));
    });

    it("falls back to a role+label anchor when refs are absent", () => {
        const base: A11yPayload = {
            nodes: [{ role: "Button", label: "Submit", states: [] }],
        };
        const head: A11yPayload = {
            nodes: [{ role: "Button", label: "Submit", states: ["Disabled"] }],
        };
        const delta = diffA11y(base, head);
        // Same role+label anchor → matched → a states change, not add+remove.
        assert.strictEqual(delta.added.length, 0);
        assert.strictEqual(delta.removed.length, 0);
        assert.strictEqual(delta.changed.length, 1);
        assert.deepStrictEqual(delta.changed[0].changes[0], {
            field: "states",
            from: null,
            to: "Disabled",
        });
    });

    it("treats a missing merged as true (no spurious change vs a newer capture)", () => {
        // Legacy sidecar omits `merged`; newer capture has the wire default `true`.
        const base: A11yPayload = { nodes: [{ ref: "a1", label: "X" }] };
        const head: A11yPayload = {
            nodes: [{ ref: "a1", label: "X", merged: true }],
        };
        assert.ok(a11yDeltaIsEmpty(diffA11y(base, head)));
        // A real merged:false still shows as a change.
        const unmerged: A11yPayload = {
            nodes: [{ ref: "a1", label: "X", merged: false }],
        };
        const delta = diffA11y(base, unmerged);
        assert.deepStrictEqual(delta.changed[0].changes, [
            { field: "merged", from: "true", to: "false" },
        ]);
    });

    it("returns an empty delta for identical hierarchies", () => {
        const nodes = [{ ref: "a1", role: "Button", label: "Go", states: [] }];
        assert.ok(a11yDeltaIsEmpty(diffA11y({ nodes }, { nodes: [...nodes] })));
    });
});
