// `<bundle-row-detail>` — passive renderer for the detail panel
// below a bundle tab's data-table. These tests pin the empty-sections
// hide path, the section / entry rendering shape, and the clear()
// teardown.

import * as assert from "assert";
import "../webview/preview/components/BundleRowDetail";
import type {
    BundleRowDetail,
    BundleRowDetailSection,
} from "../webview/preview/components/BundleRowDetail";

function build(): BundleRowDetail {
    const el = document.createElement("bundle-row-detail") as BundleRowDetail;
    document.body.appendChild(el);
    return el;
}

describe("BundleRowDetail", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders nothing until setDetail provides sections", async () => {
        const el = build();
        await el.updateComplete;
        assert.strictEqual(
            el.querySelector(".bundle-row-detail-panel"),
            null,
            "panel must stay hidden until a section is supplied",
        );
    });

    it("renders one <section> per supplied section, with header + section heading", async () => {
        const el = build();
        const sections: BundleRowDetailSection[] = [
            {
                heading: "Element",
                entries: [
                    { label: "Role", value: "Button" },
                    { label: "Bounds", value: "0,0,10,10" },
                ],
            },
            {
                heading: "Findings",
                entries: [{ label: "ERROR", value: "Low contrast" }],
            },
        ];
        el.setDetail("Tap me", sections);
        await el.updateComplete;
        const panel = el.querySelector(".bundle-row-detail-panel");
        assert.ok(panel, "panel must render once setDetail seeds sections");
        const headings = Array.from(
            el.querySelectorAll(".bundle-row-detail-section-heading"),
        ).map((n) => n.textContent);
        assert.deepStrictEqual(headings, ["Element", "Findings"]);
        const headerText = el.querySelector(
            ".bundle-row-detail-header span",
        )?.textContent;
        assert.strictEqual(headerText, "Tap me");
    });

    it("skips empty sections so a partial detail doesn't render a blank heading", async () => {
        const el = build();
        el.setDetail("Whatever", [
            { heading: "Element", entries: [{ label: "Role", value: "x" }] },
            { heading: "Findings", entries: [] },
        ]);
        await el.updateComplete;
        const headings = Array.from(
            el.querySelectorAll(".bundle-row-detail-section-heading"),
        ).map((n) => n.textContent);
        assert.deepStrictEqual(headings, ["Element"]);
    });

    it("clear() drops the panel and re-hides the host element", async () => {
        const el = build();
        el.setDetail("Tap me", [
            { heading: "Element", entries: [{ label: "Role", value: "x" }] },
        ]);
        await el.updateComplete;
        assert.ok(el.querySelector(".bundle-row-detail-panel"));
        el.clear();
        await el.updateComplete;
        assert.strictEqual(
            el.querySelector(".bundle-row-detail-panel"),
            null,
            "clear() must remove the panel so the host's layout reclaims the space",
        );
    });

    it("renders key/value pairs from each entry inside a dl.bundle-row-detail-list", async () => {
        const el = build();
        el.setDetail("Tap me", [
            {
                heading: "Element",
                entries: [
                    { label: "Role", value: "Button" },
                    { label: "States", value: "clickable, focusable" },
                ],
            },
        ]);
        await el.updateComplete;
        const dts = Array.from(
            el.querySelectorAll(".bundle-row-detail-key"),
        ).map((n) => n.textContent?.trim());
        const dds = Array.from(
            el.querySelectorAll(".bundle-row-detail-value"),
        ).map((n) => n.textContent?.trim());
        assert.deepStrictEqual(dts, ["Role", "States"]);
        assert.deepStrictEqual(dds, ["Button", "clickable, focusable"]);
    });
});
