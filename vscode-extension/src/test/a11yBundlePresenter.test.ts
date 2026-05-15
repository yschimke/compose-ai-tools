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
});
