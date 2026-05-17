// A11y bundle presenter (#1054 Cluster B). Pins the row + overlay
// derivation, including the orphan-finding path that surfaces ATF
// findings without a matching hierarchy node — regression coverage
// for the codex review comment that blank-bounds findings were being
// silently dropped when a hierarchy node also happened to have blank
// bounds.

import * as assert from "assert";
import {
    computeA11yBundleData,
    type AccessibilityTouchTarget,
} from "../webview/preview/a11yBundlePresenter";
import type {
    AccessibilityFinding,
    AccessibilityNode,
} from "../webview/shared/types";

function touchTarget(
    overrides: Partial<AccessibilityTouchTarget>,
): AccessibilityTouchTarget {
    return {
        nodeId: overrides.nodeId ?? "node-1",
        boundsInScreen: overrides.boundsInScreen ?? "0,0,10,10",
        widthDp: overrides.widthDp ?? 40,
        heightDp: overrides.heightDp ?? 40,
        findings: overrides.findings ?? [],
        overlappingNodeIds: overrides.overlappingNodeIds,
    };
}

function node(
    overrides: Partial<AccessibilityNode> & { boundsInScreen?: string },
): AccessibilityNode {
    return {
        label: overrides.label ?? "node",
        role: overrides.role ?? "Button",
        states: overrides.states ?? [],
        merged: overrides.merged ?? true,
        boundsInScreen: overrides.boundsInScreen ?? "0,0,10,10",
    };
}

function finding(
    overrides: Partial<AccessibilityFinding>,
): AccessibilityFinding {
    return {
        level: overrides.level ?? "ERROR",
        type: overrides.type ?? "ContrastCheck",
        message: overrides.message ?? "low contrast",
        viewDescription: overrides.viewDescription ?? null,
        boundsInScreen: overrides.boundsInScreen ?? "0,0,10,10",
    };
}

describe("computeA11yBundleData", () => {
    it("emits one row per hierarchy node with palette colour and no level", () => {
        const data = computeA11yBundleData(
            [
                node({ label: "Title", boundsInScreen: "0,0,10,10" }),
                node({ label: "Body", boundsInScreen: "10,10,20,20" }),
            ],
            [],
        );
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.overlay.length, 2);
        // Palette colour applied when no finding pins the level.
        assert.ok(data.overlay[0].color);
        assert.strictEqual(data.overlay[0].level, "info");
    });

    it("merges a matching finding's level onto its node row", () => {
        const data = computeA11yBundleData(
            [node({ boundsInScreen: "0,0,10,10" })],
            [
                finding({
                    level: "ERROR",
                    boundsInScreen: "0,0,10,10",
                    message: "x",
                }),
            ],
        );
        assert.strictEqual(data.rows.length, 1);
        assert.strictEqual(data.rows[0].findingCount, 1);
        assert.strictEqual(data.rows[0].topFindingLevel, "error");
        assert.strictEqual(data.overlay[0].level, "error");
        // Findings pin the level so the palette colour is *not* set.
        assert.strictEqual(data.overlay[0].color, undefined);
    });

    it("surfaces unmatched findings as orphan rows", () => {
        const data = computeA11yBundleData(
            [node({ boundsInScreen: "0,0,10,10" })],
            [
                finding({
                    boundsInScreen: "100,100,110,110",
                    level: "WARNING",
                    type: "Touch",
                }),
            ],
        );
        // 1 hierarchy row + 1 orphan finding row.
        assert.strictEqual(data.rows.length, 2);
        const orphan = data.rows[1];
        assert.ok(orphan.id.startsWith("a11y-finding-orphan-"));
        assert.strictEqual(orphan.topFindingLevel, "warning");
        // Hierarchy node has 0 findings (the orphan matched neither).
        assert.strictEqual(data.rows[0].findingCount, 0);
    });

    it("does not drop a blank-bounds finding when a node also has blank bounds", () => {
        // Codex review regression: matchedKeys used to include "" from
        // the malformed node, masking the finding as 'already
        // represented' even though it has nothing in common with it.
        const data = computeA11yBundleData(
            [node({ label: "broken", boundsInScreen: "" })],
            [
                finding({
                    boundsInScreen: null,
                    level: "ERROR",
                    message: "no bounds",
                }),
            ],
        );
        // 1 hierarchy row + 1 orphan finding row — the finding is NOT
        // matched against the empty-bounds node.
        assert.strictEqual(data.rows.length, 2);
        const orphan = data.rows.find((r) =>
            r.id.startsWith("a11y-finding-orphan-"),
        );
        assert.ok(orphan, "blank-bounds finding must surface as an orphan row");
        assert.strictEqual(orphan!.topFindingLevel, "error");
    });

    it("emits no overlay box for rows whose bounds did not parse", () => {
        const data = computeA11yBundleData(
            [node({ boundsInScreen: "not-a-rect" })],
            [],
        );
        assert.strictEqual(data.rows.length, 1);
        assert.strictEqual(data.overlay.length, 0);
    });

    it("merges a touch target's size and findings onto the matching hierarchy row", () => {
        const data = computeA11yBundleData(
            [node({ label: "Tap me", boundsInScreen: "0,0,10,10" })],
            [],
            [
                touchTarget({
                    boundsInScreen: "0,0,10,10",
                    widthDp: 32,
                    heightDp: 32,
                    findings: ["TouchTargetTooSmall"],
                }),
            ],
        );
        assert.strictEqual(data.rows.length, 1);
        assert.strictEqual(data.rows[0].touchTargetSizeDp, "32×32 dp");
        // Touch-target findings bump level to "warning" (not "error" —
        // ATF errors still dominate, but a missing-ATF target should
        // not silently look "info").
        assert.strictEqual(data.rows[0].topFindingLevel, "warning");
        assert.strictEqual(data.rows[0].findingCount, 1);
    });

    it("does not downgrade an ATF error when a touch target also applies", () => {
        const data = computeA11yBundleData(
            [node({ boundsInScreen: "0,0,10,10" })],
            [
                finding({
                    boundsInScreen: "0,0,10,10",
                    level: "ERROR",
                    message: "low contrast",
                }),
            ],
            [
                touchTarget({
                    boundsInScreen: "0,0,10,10",
                    findings: ["TouchTargetOverlap"],
                }),
            ],
        );
        // Error from ATF wins; touch-target finding adds to the count.
        assert.strictEqual(data.rows[0].topFindingLevel, "error");
        assert.strictEqual(data.rows[0].findingCount, 2);
    });

    it("emits an orphan row for a touch target with findings and no matching node", () => {
        const data = computeA11yBundleData(
            [node({ boundsInScreen: "0,0,10,10" })],
            [],
            [
                touchTarget({
                    boundsInScreen: "100,100,140,140",
                    findings: ["TouchTargetTooSmall"],
                }),
            ],
        );
        // 1 hierarchy row + 1 touch-target orphan.
        assert.strictEqual(data.rows.length, 2);
        const orphan = data.rows[1];
        assert.ok(orphan.id.startsWith("a11y-touchtarget-orphan-"));
        assert.strictEqual(orphan.topFindingLevel, "warning");
    });

    it("ignores touch targets that have no findings (still useful in the JSON payload, not as a row)", () => {
        // Pure-shape targets (size info, no rule trip) don't deserve
        // their own orphan row — they'd just be noise. They DO merge
        // onto matching hierarchy rows for the size column.
        const data = computeA11yBundleData(
            [],
            [],
            [
                touchTarget({
                    boundsInScreen: "0,0,10,10",
                    findings: [],
                }),
            ],
        );
        assert.strictEqual(data.rows.length, 0);
    });

    it("includes the touch-target size in the overlay tooltip when one matches", () => {
        const data = computeA11yBundleData(
            [node({ label: "Btn", boundsInScreen: "0,0,10,10" })],
            [],
            [
                touchTarget({
                    boundsInScreen: "0,0,10,10",
                    widthDp: 48,
                    heightDp: 48,
                    findings: [],
                }),
            ],
        );
        assert.ok(data.overlay[0].tooltip?.includes("48×48 dp"));
    });

    it("annotates merged nodes with depth 0 and unmerged with depth 1", () => {
        const data = computeA11yBundleData(
            [
                node({
                    label: "Button",
                    merged: true,
                    boundsInScreen: "0,0,100,40",
                }),
                node({
                    label: "Text inside",
                    merged: false,
                    boundsInScreen: "5,5,95,35",
                }),
            ],
            [],
        );
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.rows[0].depth, 0, "merged ⇒ top-level");
        assert.strictEqual(data.rows[1].depth, 1, "unmerged ⇒ child of merged");
    });

    it("keeps unmerged children in the table but skips their overlay box (parent covers them)", () => {
        const data = computeA11yBundleData(
            [
                node({
                    label: "Button",
                    merged: true,
                    boundsInScreen: "0,0,100,40",
                }),
                node({
                    label: "Text inside",
                    merged: false,
                    boundsInScreen: "5,5,95,35",
                }),
            ],
            [],
        );
        // Both rows appear in the table — the tree view needs the
        // child to be visible to show the merge structure.
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.rows[0].merged, true);
        assert.strictEqual(data.rows[1].merged, false);
        // But the overlay only paints the merged parent — the child
        // sits inside the parent's bounds and would just stack a
        // duplicate box on the preview.
        assert.strictEqual(data.overlay.length, 1);
        assert.strictEqual(data.overlay[0].id, data.rows[0].id);
    });

    it("derives a merged node's displayLabel from its unmerged children when the daemon emitted no label", () => {
        // Wear `ActivityListPreview` case: the `clickable` row has no
        // label on the wire, only its inner Text children. The
        // legend would show "(unlabelled)" if we used `label`
        // verbatim — `displayLabel` joins the child texts so users
        // see what TalkBack would announce.
        const data = computeA11yBundleData(
            [
                node({
                    label: "",
                    role: "clickable",
                    merged: true,
                    boundsInScreen: "0,0,100,40",
                }),
                node({
                    label: "Morning run",
                    merged: false,
                    boundsInScreen: "5,5,95,20",
                }),
                node({
                    label: "5.2 km · 28 min",
                    merged: false,
                    boundsInScreen: "5,20,95,35",
                }),
                node({
                    label: "Heart rate row",
                    role: "clickable",
                    merged: true,
                    boundsInScreen: "0,40,100,80",
                }),
            ],
            [],
        );
        const parent = data.rows.find(
            (r) => r.role === "clickable" && r.merged,
        )!;
        assert.strictEqual(parent.label, "(unlabelled)");
        assert.strictEqual(
            parent.displayLabel,
            "Morning run · 5.2 km · 28 min",
        );
        // A merged node that DOES have its own label keeps it — the
        // child walk only kicks in when the daemon emitted an empty
        // string, and it stops at the next merged node.
        const sibling = data.rows.find((r) => r.label === "Heart rate row")!;
        assert.strictEqual(sibling.displayLabel, "Heart rate row");
    });

    it("treats a missing `merged` field as merged=true (daemon JSON omits the default)", () => {
        // `AccessibilityDataProducer` writes `a11y-hierarchy.json` with
        // `encodeDefaults = false`, so `merged: true` (the Kotlin
        // default) is omitted from the wire JSON. The presenter must
        // accept the missing field as the default-true case, otherwise
        // every TalkBack stop on the wire gets dropped by `mergedOnly`
        // and the bundle renders "0 elements / No rows" even when the
        // daemon delivered 12 merged nodes.
        const wireNodes: readonly AccessibilityNode[] = [
            {
                label: "Today",
                role: "header",
                states: ["focusable"],
                boundsInScreen: "0,0,100,40",
            } as unknown as AccessibilityNode,
            {
                label: "Morning run",
                role: "text",
                states: [],
                boundsInScreen: "0,40,100,80",
            } as unknown as AccessibilityNode,
        ];
        const data = computeA11yBundleData(wireNodes, []);
        assert.strictEqual(data.rows.length, 2);
        assert.ok(data.rows.every((r) => r.merged));
        assert.ok(data.rows.every((r) => r.depth === 0));
    });

    it("does not surface a finding on an unmerged child as an orphan when the bounds match", () => {
        // The finding lives on the inner Text node's bounds, which
        // also matches the merged Button (same bounds). The finding
        // must merge onto the Button row instead of looking like an
        // orphan, regardless of whether the unmerged child is also
        // present in the table.
        const data = computeA11yBundleData(
            [
                node({
                    label: "Button",
                    merged: true,
                    boundsInScreen: "0,0,100,40",
                }),
                node({
                    label: "Text inside",
                    merged: false,
                    boundsInScreen: "0,0,100,40",
                }),
            ],
            [
                finding({
                    boundsInScreen: "0,0,100,40",
                    level: "WARNING",
                    type: "TextContrast",
                }),
            ],
        );
        const parent = data.rows.find((r) => r.merged && r.label === "Button");
        assert.ok(parent);
        assert.strictEqual(parent!.findingCount, 1);
        assert.strictEqual(parent!.topFindingLevel, "warning");
        // No orphan row should have been synthesised — the finding
        // attached to the matching merged ancestor.
        assert.ok(
            !data.rows.some((r) => r.id.startsWith("a11y-finding-orphan-")),
        );
    });

    it("orphan finding rows render at depth 0 regardless of hierarchy", () => {
        // Orphan findings (no matching node) aren't bound to a
        // merged ancestor — they should sit at the top level so
        // they don't pretend to belong under whoever ran last.
        const data = computeA11yBundleData(
            [node({ boundsInScreen: "0,0,10,10", merged: true })],
            [
                finding({
                    boundsInScreen: "100,100,200,200",
                    level: "ERROR",
                }),
            ],
        );
        const orphan = data.rows.find((r) =>
            r.id.startsWith("a11y-finding-orphan-"),
        );
        assert.ok(orphan);
        assert.strictEqual(orphan!.depth, 0);
    });
});
