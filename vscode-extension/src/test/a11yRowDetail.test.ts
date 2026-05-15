// Tests for `buildA11yRowDetail`. The helper produces the
// `<bundle-row-detail>` sections shown when the user clicks a row
// in the Accessibility tab. The bundle row carries the count of
// matching findings but not their messages, so the detail builder
// re-joins against the source findings list (and the touch-targets
// list) using bounds — same join key the presenter uses.

import * as assert from "assert";
import { buildA11yRowDetail } from "../webview/preview/a11yRowDetail";
import type {
    A11yRow,
    AccessibilityTouchTarget,
} from "../webview/preview/a11yBundlePresenter";
import type { AccessibilityFinding } from "../webview/shared/types";

function row(over: Partial<A11yRow>): A11yRow {
    return {
        id: over.id ?? "a11y-0",
        label: over.label ?? "Title",
        role: over.role ?? "Button",
        states: over.states ?? "",
        merged: over.merged ?? true,
        findingCount: over.findingCount ?? 0,
        topFindingLevel: over.topFindingLevel ?? null,
        boundsInScreen: over.boundsInScreen ?? "0,0,10,10",
        bounds: over.bounds ?? { left: 0, top: 0, right: 10, bottom: 10 },
        touchTargetSizeDp: over.touchTargetSizeDp ?? null,
        depth: over.depth ?? 0,
    };
}

function finding(over: Partial<AccessibilityFinding>): AccessibilityFinding {
    return {
        level: over.level ?? "ERROR",
        type: over.type ?? "ContrastCheck",
        message: over.message ?? "Insufficient contrast ratio",
        viewDescription: over.viewDescription ?? null,
        boundsInScreen: over.boundsInScreen ?? "0,0,10,10",
    };
}

function touchTarget(
    over: Partial<AccessibilityTouchTarget>,
): AccessibilityTouchTarget {
    return {
        nodeId: over.nodeId ?? "node-1",
        boundsInScreen: over.boundsInScreen ?? "0,0,10,10",
        widthDp: over.widthDp ?? 32,
        heightDp: over.heightDp ?? 32,
        findings: over.findings ?? [],
        overlappingNodeIds: over.overlappingNodeIds,
    };
}

describe("buildA11yRowDetail", () => {
    it("always emits an Element section with role + focus target + bounds", () => {
        const sections = buildA11yRowDetail(
            row({ role: "Button", states: "clickable" }),
            [],
            [],
        );
        const element = sections.find((s) => s.heading === "Element");
        assert.ok(element);
        const labels = element!.entries.map((e) => e.label);
        assert.ok(labels.includes("Role"));
        assert.ok(labels.includes("Focus target"));
        assert.ok(labels.includes("States"));
        assert.ok(labels.includes("Bounds"));
    });

    it("labels the focus target as 'no (child of merged)' when row.merged is false", () => {
        const sections = buildA11yRowDetail(row({ merged: false }), [], []);
        const element = sections.find((s) => s.heading === "Element")!;
        const focus = element.entries.find((e) => e.label === "Focus target")!;
        assert.strictEqual(
            String(focus.value).includes("no"),
            true,
            "unmerged rows must read as a child of a merged parent",
        );
    });

    it("adds a Findings (ATF) section with one entry per matching finding", () => {
        const sections = buildA11yRowDetail(
            row({ boundsInScreen: "0,0,10,10" }),
            [
                finding({
                    level: "ERROR",
                    boundsInScreen: "0,0,10,10",
                    message: "contrast",
                }),
                finding({
                    level: "WARNING",
                    boundsInScreen: "0,0,10,10",
                    message: "label",
                }),
                finding({
                    level: "ERROR",
                    boundsInScreen: "99,99,109,109",
                    message: "out of band",
                }),
            ],
            [],
        );
        const f = sections.find((s) => s.heading === "Findings (ATF)");
        assert.ok(f);
        assert.strictEqual(f!.entries.length, 2);
        assert.strictEqual(f!.entries[0].label, "ERROR");
        assert.strictEqual(f!.entries[1].label, "WARNING");
    });

    it("skips the Findings section when no finding matches the row's bounds", () => {
        const sections = buildA11yRowDetail(
            row({ boundsInScreen: "0,0,10,10" }),
            [finding({ boundsInScreen: "100,100,200,200" })],
            [],
        );
        assert.strictEqual(
            sections.find((s) => s.heading === "Findings (ATF)"),
            undefined,
        );
    });

    it("emits a Touch target section when a target matches the row's bounds", () => {
        const sections = buildA11yRowDetail(
            row({ touchTargetSizeDp: "32×32 dp" }),
            [],
            [
                touchTarget({
                    boundsInScreen: "0,0,10,10",
                    widthDp: 32,
                    heightDp: 32,
                    findings: ["TouchTargetTooSmall"],
                    overlappingNodeIds: ["other-1"],
                }),
            ],
        );
        const tt = sections.find((s) => s.heading === "Touch target");
        assert.ok(tt);
        const labels = tt!.entries.map((e) => e.label);
        assert.deepStrictEqual(labels, ["Size", "Issues", "Overlaps"]);
    });

    it("omits Issues and Overlaps when the touch target has no findings or overlap data", () => {
        const sections = buildA11yRowDetail(
            row({ touchTargetSizeDp: "48×48 dp" }),
            [],
            [touchTarget({ findings: [] })],
        );
        const tt = sections.find((s) => s.heading === "Touch target")!;
        assert.strictEqual(tt.entries.length, 1);
        assert.strictEqual(tt.entries[0].label, "Size");
    });

    it("skips the Touch target section entirely when no target matches", () => {
        const sections = buildA11yRowDetail(
            row({ boundsInScreen: "0,0,10,10" }),
            [],
            [touchTarget({ boundsInScreen: "100,100,140,140" })],
        );
        assert.strictEqual(
            sections.find((s) => s.heading === "Touch target"),
            undefined,
        );
    });

    it("surfaces an orphan finding row via the Findings section (its own bounds self-match)", () => {
        // Orphan finding rows have role = f.type; the join finds the
        // finding by its own bounds and shows the full message
        // alongside the level — the table only carried the count.
        const findings = [
            finding({
                level: "WARNING",
                type: "TouchTargetSize",
                boundsInScreen: "50,50,60,60",
                message: "Tap target 24×24dp is below 48dp minimum",
            }),
        ];
        const sections = buildA11yRowDetail(
            row({
                id: "a11y-finding-orphan-0",
                label: "(no element)",
                role: "TouchTargetSize",
                merged: true,
                topFindingLevel: "warning",
                boundsInScreen: "50,50,60,60",
            }),
            findings,
            [],
        );
        const f = sections.find((s) => s.heading === "Findings (ATF)");
        assert.ok(f);
        assert.strictEqual(f!.entries[0].label, "WARNING");
    });
});
