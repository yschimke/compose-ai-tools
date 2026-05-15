// `<bundle-legend>` — side-panel legend rendered beside the focused
// preview. The host populates per-bundle slices via
// `setBundleEntries`; `showBundle` swaps which slice is visible.
// These tests pin the slice-swap behaviour, the hover/select event
// contract, and the empty-slice / cleared-slice hide paths.

import * as assert from "assert";
import "../webview/preview/components/BundleLegend";
import type {
    BundleLegend,
    BundleLegendEntry,
    BundleLegendHoveredDetail,
    BundleLegendSelectedDetail,
} from "../webview/preview/components/BundleLegend";

function entry(over: Partial<BundleLegendEntry>): BundleLegendEntry {
    return {
        id: over.id ?? "id-1",
        label: over.label ?? "Label",
        detail: over.detail,
        level: over.level ?? "info",
        color: over.color,
    };
}

function build(): BundleLegend {
    const el = document.createElement("bundle-legend") as BundleLegend;
    document.body.appendChild(el);
    return el;
}

describe("BundleLegend", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders nothing until a bundle is shown", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [entry({})]);
        await el.updateComplete;
        assert.strictEqual(
            el.querySelector(".bundle-legend-panel"),
            null,
            "legend must stay empty until showBundle() picks a slice",
        );
    });

    it("showBundle swaps which slice renders and returns the entry count", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [
            entry({ id: "n0", label: "Title" }),
            entry({ id: "n1", label: "Body" }),
        ]);
        el.setBundleEntries("inspection", "Inspection", [
            entry({ id: "s0", label: "Semantics" }),
        ]);
        const a11yCount = el.showBundle("a11y");
        await el.updateComplete;
        assert.strictEqual(a11yCount, 2);
        const labels = Array.from(
            el.querySelectorAll(".bundle-legend-label"),
        ).map((n) => n.textContent);
        assert.deepStrictEqual(labels, ["Title", "Body"]);
        const inspCount = el.showBundle("inspection");
        await el.updateComplete;
        assert.strictEqual(inspCount, 1);
        const after = Array.from(
            el.querySelectorAll(".bundle-legend-label"),
        ).map((n) => n.textContent);
        assert.deepStrictEqual(after, ["Semantics"]);
    });

    it("showBundle(null) hides the legend and returns 0", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [entry({})]);
        el.showBundle("a11y");
        await el.updateComplete;
        assert.ok(el.querySelector(".bundle-legend-panel"));
        const cleared = el.showBundle(null);
        await el.updateComplete;
        assert.strictEqual(cleared, 0);
        assert.strictEqual(el.querySelector(".bundle-legend-panel"), null);
    });

    it("clearBundle drops the slice and hides the panel if it was active", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [entry({})]);
        el.showBundle("a11y");
        await el.updateComplete;
        el.clearBundle("a11y");
        await el.updateComplete;
        assert.strictEqual(
            el.querySelector(".bundle-legend-panel"),
            null,
            "clearing the active slice must hide the panel",
        );
    });

    it("hover fires legend-hovered with the entry id, mouseleave fires with null", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [
            entry({ id: "node-7", label: "Tap me" }),
        ]);
        el.showBundle("a11y");
        await el.updateComplete;
        const captured: (string | null)[] = [];
        el.addEventListener("legend-hovered", (evt) => {
            captured.push(
                (evt as CustomEvent<BundleLegendHoveredDetail>).detail.entryId,
            );
        });
        const row = el.querySelector<HTMLElement>(".bundle-legend-entry")!;
        row.dispatchEvent(new Event("mouseenter", { bubbles: true }));
        row.dispatchEvent(new Event("mouseleave", { bubbles: true }));
        assert.deepStrictEqual(captured, ["node-7", null]);
    });

    it("click fires legend-selected with the entry id", async () => {
        const el = build();
        el.setBundleEntries("inspection", "Inspection", [
            entry({ id: "sem-3" }),
        ]);
        el.showBundle("inspection");
        await el.updateComplete;
        let detail: BundleLegendSelectedDetail | null = null;
        el.addEventListener("legend-selected", (evt) => {
            detail = (evt as CustomEvent<BundleLegendSelectedDetail>).detail;
        });
        const row = el.querySelector<HTMLElement>(".bundle-legend-entry")!;
        row.click();
        assert.deepStrictEqual(detail, { entryId: "sem-3" });
    });

    it("setActiveEntryId mirrors a host-driven highlight onto the matching row", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [
            entry({ id: "a" }),
            entry({ id: "b" }),
        ]);
        el.showBundle("a11y");
        await el.updateComplete;
        el.setActiveEntryId("b");
        await el.updateComplete;
        const active = el.querySelector(".bundle-legend-entry-active");
        assert.ok(active);
        assert.strictEqual(active!.getAttribute("data-legend-id"), "b");
    });

    it("setBundleEntries on the currently-shown bundle re-renders without an extra showBundle call", async () => {
        const el = build();
        el.setBundleEntries("a11y", "Accessibility", [
            entry({ id: "n0", label: "Old" }),
        ]);
        el.showBundle("a11y");
        await el.updateComplete;
        el.setBundleEntries("a11y", "Accessibility", [
            entry({ id: "n0", label: "New" }),
        ]);
        await el.updateComplete;
        const label = el.querySelector(".bundle-legend-label");
        assert.strictEqual(label?.textContent, "New");
    });
});
