// TS port of the daemon's SemanticsDiff (#1785). Pins the same behaviours the Kotlin
// `SemanticsDiffTest` / `HistoryDiffTest` semantics cases assert: ref-matched field changes,
// add/remove, identical → empty, and that a copy edit stays on the same ref (not remove + add).

import * as assert from "assert";
import {
    assignSemanticsRefs,
    diffSemantics,
    semanticsDeltaIsEmpty,
    type SemanticsNode,
} from "../webview/shared/semanticsDiff";

function node(partial: Partial<SemanticsNode>): SemanticsNode {
    return { ...partial };
}

describe("semanticsDiff", () => {
    it("reports a field change on the same ref for a copy edit", () => {
        const base = { root: node({ testTag: "greeting", text: "Hello" }) };
        const head = { root: node({ testTag: "greeting", text: "World" }) };

        const delta = diffSemantics(base, head);

        assert.strictEqual(delta.schema, "compose-semantics-diff/v1");
        assert.strictEqual(delta.added.length, 0, "copy edit must not add");
        assert.strictEqual(
            delta.removed.length,
            0,
            "copy edit must not remove",
        );
        assert.strictEqual(delta.changed.length, 1);
        const change = delta.changed[0].changes;
        assert.strictEqual(change.length, 1);
        assert.deepStrictEqual(change[0], {
            field: "text",
            from: "Hello",
            to: "World",
        });
    });

    it("reports a node leaving the frame, mirroring the Kotlin differ", () => {
        // `placed` is COMPARED rather than pruned: every other consumer of this tree skips an
        // unplaced subtree, but a diff that pruned it would report a node leaving the frame as
        // no change at all.
        const base = { root: node({ testTag: "row", text: "One" }) };
        const head = {
            root: node({ testTag: "row", text: "One", placed: false }),
        };

        const delta = diffSemantics(base, head);

        assert.strictEqual(delta.changed.length, 1);
        assert.deepStrictEqual(delta.changed[0].changes, [
            { field: "placed", from: "true", to: "false" },
        ]);
    });

    it("treats an omitted placed as true, so a pre-v15 snapshot is not a change", () => {
        // The producer omits the field when true. Comparing raw values would report every v14
        // snapshot as having changed against a v15 one.
        const base = { root: node({ testTag: "row" }) };
        const head = { root: node({ testTag: "row", placed: true }) };

        assert.ok(semanticsDeltaIsEmpty(diffSemantics(base, head)));
    });

    it("reports identical trees as an empty delta", () => {
        const tree = () => ({
            root: node({ testTag: "greeting", text: "Hello" }),
        });
        const delta = diffSemantics(tree(), tree());
        assert.ok(semanticsDeltaIsEmpty(delta));
    });

    it("treats a lean-encoded delta with omitted arrays as empty (no throw)", () => {
        // A daemon `history/diff mode=semantics` result with an empty diff omits the arrays.
        const lean = {
            schema: "compose-semantics-diff/v1",
        } as unknown as Parameters<typeof semanticsDeltaIsEmpty>[0];
        assert.strictEqual(semanticsDeltaIsEmpty(lean), true);
    });

    it("detects added and removed nodes (matched by ref, not content)", () => {
        const base = {
            root: node({
                role: "Column",
                children: [node({ testTag: "a", text: "A" })],
            }),
        };
        const head = {
            root: node({
                role: "Column",
                children: [
                    node({ testTag: "a", text: "A" }),
                    node({ testTag: "b", text: "B" }),
                ],
            }),
        };

        const delta = diffSemantics(base, head);
        assert.strictEqual(delta.removed.length, 0);
        assert.strictEqual(delta.added.length, 1);
        assert.strictEqual(delta.added[0].testTag, "b");
        assert.strictEqual(delta.changed.length, 0);

        // Reverse direction: the b node now reads as removed.
        const reverse = diffSemantics(head, base);
        assert.strictEqual(reverse.added.length, 0);
        assert.strictEqual(reverse.removed.length, 1);
        assert.strictEqual(reverse.removed[0].testTag, "b");
    });

    it("keeps refs stable across a content edit (ref ignores text/label)", () => {
        const before = assignSemanticsRefs(
            node({ testTag: "greeting", text: "Hello" }),
        );
        const after = assignSemanticsRefs(
            node({ testTag: "greeting", text: "World" }),
        );
        assert.strictEqual(before.ref, after.ref);
    });

    it("disambiguates same-anchor siblings by occurrence index", () => {
        const root = assignSemanticsRefs(
            node({
                role: "Column",
                children: [node({ role: "Button" }), node({ role: "Button" })],
            }),
        );
        const refs = (root.children ?? []).map((c) => c.ref);
        assert.deepStrictEqual(refs, ["r/role:Button[0]", "r/role:Button[1]"]);
    });

    it("treats a changed testTag as a moved ref (remove + add, not a field change)", () => {
        // testTag is part of the ref, so changing it relocates the node.
        const base = {
            root: node({
                role: "Column",
                children: [node({ testTag: "old" })],
            }),
        };
        const head = {
            root: node({
                role: "Column",
                children: [node({ testTag: "new" })],
            }),
        };
        const delta = diffSemantics(base, head);
        assert.strictEqual(delta.added.length, 1);
        assert.strictEqual(delta.removed.length, 1);
    });
});
