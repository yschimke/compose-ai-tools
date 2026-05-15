// Tests for `buildInspectionRowDetail`. Three kinds of records
// (compose/semantics, layout/inspector, uia/hierarchy) each have
// their own per-kind builder; the dispatcher just switches on
// `record.kind`.

import * as assert from "assert";
import {
    buildInspectionRowDetail,
    inspectionRowTitle,
} from "../webview/preview/inspectionRowDetail";
import type {
    ComposeSemanticsNode,
    LayoutInspectorNode,
    UiaHierarchyNode,
} from "../webview/preview/inspectionPresenters";

function semanticsNode(
    over: Partial<ComposeSemanticsNode>,
): ComposeSemanticsNode {
    return {
        nodeId: over.nodeId ?? "sn-1",
        boundsInRoot: over.boundsInRoot ?? "0,0,10,10",
        label: over.label ?? null,
        text: over.text ?? null,
        role: over.role ?? null,
        testTag: over.testTag ?? null,
        mergeMode: over.mergeMode ?? null,
        clickable: over.clickable,
        children: over.children,
    };
}

function layoutNode(over: Partial<LayoutInspectorNode>): LayoutInspectorNode {
    return {
        nodeId: over.nodeId ?? "ln-1",
        component: over.component ?? "Box",
        source: over.source ?? null,
        sourceInfo: over.sourceInfo ?? null,
        bounds: over.bounds ?? { left: 0, top: 0, right: 100, bottom: 40 },
        size: over.size,
        constraints: over.constraints,
        modifiers: over.modifiers,
        children: over.children,
    };
}

function uiaNode(over: Partial<UiaHierarchyNode>): UiaHierarchyNode {
    return {
        text: over.text ?? null,
        contentDescription: over.contentDescription ?? null,
        testTag: over.testTag ?? null,
        testTagAncestors: over.testTagAncestors,
        role: over.role ?? null,
        actions: over.actions,
        boundsInScreen: over.boundsInScreen ?? "0,0,10,10",
        merged: over.merged,
    };
}

describe("buildInspectionRowDetail — compose/semantics", () => {
    it("emits an Identity section with node id + bounds", () => {
        const sections = buildInspectionRowDetail({
            kind: "compose/semantics",
            node: semanticsNode({}),
        });
        const id = sections.find((s) => s.heading === "Identity");
        assert.ok(id);
        const labels = id!.entries.map((e) => e.label);
        assert.ok(labels.includes("Node id"));
        assert.ok(labels.includes("Bounds"));
    });

    it("includes role / test tag / merge mode when present", () => {
        const sections = buildInspectionRowDetail({
            kind: "compose/semantics",
            node: semanticsNode({
                role: "Button",
                testTag: "primary_cta",
                mergeMode: "Merged",
            }),
        });
        const labels = sections
            .find((s) => s.heading === "Identity")!
            .entries.map((e) => e.label);
        assert.ok(labels.includes("Role"));
        assert.ok(labels.includes("Test tag"));
        assert.ok(labels.includes("Merge mode"));
    });

    it("skips Semantics text and Flags sections when nothing applies", () => {
        const sections = buildInspectionRowDetail({
            kind: "compose/semantics",
            node: semanticsNode({}),
        });
        assert.strictEqual(
            sections.find((s) => s.heading === "Semantics text"),
            undefined,
        );
        assert.strictEqual(
            sections.find((s) => s.heading === "Flags"),
            undefined,
        );
    });

    it("emits Flags when clickable is true", () => {
        const sections = buildInspectionRowDetail({
            kind: "compose/semantics",
            node: semanticsNode({ clickable: true }),
        });
        const flags = sections.find((s) => s.heading === "Flags");
        assert.ok(flags);
        assert.strictEqual(flags!.entries[0].label, "Clickable");
    });
});

describe("buildInspectionRowDetail — layout/inspector", () => {
    it("always emits an Identity section with component + node id", () => {
        const sections = buildInspectionRowDetail({
            kind: "layout/inspector",
            node: layoutNode({ component: "Column" }),
        });
        const id = sections.find((s) => s.heading === "Identity")!;
        const comp = id.entries.find((e) => e.label === "Component");
        assert.strictEqual(comp?.value, "Column");
    });

    it("emits Bounds + Size + Constraints together in Layout", () => {
        const sections = buildInspectionRowDetail({
            kind: "layout/inspector",
            node: layoutNode({
                size: { width: 100, height: 40 },
                constraints: {
                    minWidth: 0,
                    maxWidth: 200,
                    minHeight: 0,
                    maxHeight: null,
                },
            }),
        });
        const layout = sections.find((s) => s.heading === "Layout")!;
        const labels = layout.entries.map((e) => e.label);
        assert.ok(labels.includes("Bounds"));
        assert.ok(labels.includes("Size"));
        assert.ok(labels.includes("Constraints"));
        const constraints = layout.entries.find(
            (e) => e.label === "Constraints",
        );
        assert.ok(String(constraints!.value).includes("∞"));
    });

    it("emits one Modifiers entry per modifier when present", () => {
        const sections = buildInspectionRowDetail({
            kind: "layout/inspector",
            node: layoutNode({
                modifiers: [
                    { name: "padding", value: "16dp" },
                    { name: "fillMaxWidth" },
                ],
            }),
        });
        const mods = sections.find((s) => s.heading === "Modifiers")!;
        assert.strictEqual(mods.entries.length, 2);
        assert.strictEqual(mods.entries[1].value, "—");
    });
});

describe("buildInspectionRowDetail — uia/hierarchy", () => {
    it("emits Identity with bounds + role + test-tag ancestors when present", () => {
        const sections = buildInspectionRowDetail({
            kind: "uia/hierarchy",
            node: uiaNode({
                role: "Button",
                testTag: "btn",
                testTagAncestors: ["root", "screen"],
            }),
        });
        const id = sections.find((s) => s.heading === "Identity")!;
        const labels = id.entries.map((e) => e.label);
        assert.ok(labels.includes("Role"));
        assert.ok(labels.includes("Test tag"));
        assert.ok(labels.includes("Tag ancestors"));
    });

    it("emits Content only when text or description is non-null", () => {
        const empty = buildInspectionRowDetail({
            kind: "uia/hierarchy",
            node: uiaNode({}),
        });
        assert.strictEqual(
            empty.find((s) => s.heading === "Content"),
            undefined,
        );
        const filled = buildInspectionRowDetail({
            kind: "uia/hierarchy",
            node: uiaNode({ text: "Hello" }),
        });
        assert.ok(filled.find((s) => s.heading === "Content"));
    });

    it("emits Actions section when the node carries an actions list", () => {
        const sections = buildInspectionRowDetail({
            kind: "uia/hierarchy",
            node: uiaNode({ actions: ["click", "long-click"] }),
        });
        const actions = sections.find((s) => s.heading === "Actions");
        assert.ok(actions);
        assert.ok(String(actions!.entries[0].value).includes("click"));
    });
});

describe("inspectionRowTitle", () => {
    it("falls back through label / text / testTag / nodeId for semantics", () => {
        assert.strictEqual(
            inspectionRowTitle({
                kind: "compose/semantics",
                node: semanticsNode({ label: "Done" }),
            }),
            "Done",
        );
        assert.strictEqual(
            inspectionRowTitle({
                kind: "compose/semantics",
                node: semanticsNode({ text: "World" }),
            }),
            "World",
        );
        assert.strictEqual(
            inspectionRowTitle({
                kind: "compose/semantics",
                node: semanticsNode({ nodeId: "only-this" }),
            }),
            "only-this",
        );
    });

    it("uses component for layout rows and falls through to nodeId", () => {
        assert.strictEqual(
            inspectionRowTitle({
                kind: "layout/inspector",
                node: layoutNode({ component: "Surface" }),
            }),
            "Surface",
        );
    });

    it("falls through text / description / testTag for uia rows", () => {
        assert.strictEqual(
            inspectionRowTitle({
                kind: "uia/hierarchy",
                node: uiaNode({ text: "Click me" }),
            }),
            "Click me",
        );
        assert.strictEqual(
            inspectionRowTitle({
                kind: "uia/hierarchy",
                node: uiaNode({ contentDescription: "Add to cart" }),
            }),
            "Add to cart",
        );
        assert.strictEqual(
            inspectionRowTitle({
                kind: "uia/hierarchy",
                node: uiaNode({}),
            }),
            "(uia node)",
        );
    });
});
