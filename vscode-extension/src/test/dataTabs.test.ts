// `<data-tabs>` — verifies the active-bundle tab row. Enabling /
// disabling bundles is owned by `<bundle-chip-bar>`; this component
// only mirrors the active set, so the suite focuses on render
// presence and the close + select events.

import * as assert from "assert";
import { BUNDLES } from "../webview/preview/bundleRegistry";

// Importing the component registers the custom element with
// `customElements.define`. Tests drive it through `setState` + DOM.
import "../webview/preview/components/DataTabs";
import type {
    DataTabs,
    TabCloseDetail,
    TabSelectDetail,
} from "../webview/preview/components/DataTabs";

function build(): DataTabs {
    const el = document.createElement("data-tabs") as DataTabs;
    document.body.appendChild(el);
    return el;
}

describe("DataTabs", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders nothing when no bundles are active", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            activeTab: null,
        });
        await el.updateComplete;
        assert.strictEqual(
            el.querySelector(".data-tabs"),
            null,
            "no tab strip when no bundles are active",
        );
        assert.strictEqual(el.querySelectorAll(".data-tab-handle").length, 0);
    });

    it("renders one handle per active bundle, in registry order", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["theming", "a11y"],
            activeTab: "a11y",
        });
        await el.updateComplete;
        const handles = Array.from(
            el.querySelectorAll<HTMLElement>(".data-tab-handle"),
        );
        const ids = handles.map((h) => h.getAttribute("data-bundle"));
        // BUNDLES order, filtered to active.
        const expected = BUNDLES.map((b) => b.id).filter((id) =>
            ["theming", "a11y"].includes(id),
        );
        assert.deepStrictEqual(ids, expected);
    });

    it("marks the active tab aria-selected", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y", "theming"],
            activeTab: "theming",
        });
        await el.updateComplete;
        const a11y = el.querySelector<HTMLElement>(
            '.data-tab-handle[data-bundle="a11y"]',
        );
        const theming = el.querySelector<HTMLElement>(
            '.data-tab-handle[data-bundle="theming"]',
        );
        assert.strictEqual(a11y!.getAttribute("aria-selected"), "false");
        assert.strictEqual(theming!.getAttribute("aria-selected"), "true");
    });

    it("dispatches `tab-selected` when a handle's label is clicked", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y", "theming"],
            activeTab: "a11y",
        });
        await el.updateComplete;
        const events: TabSelectDetail[] = [];
        el.addEventListener("tab-selected", (e) =>
            events.push((e as CustomEvent<TabSelectDetail>).detail),
        );
        const label = el.querySelector<HTMLButtonElement>(
            '.data-tab-handle[data-bundle="theming"] .data-tab-label',
        );
        assert.ok(label);
        label!.click();
        assert.deepStrictEqual(events, [{ id: "theming" }]);
    });

    it("dispatches `tab-closed` when the × button is clicked", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y", "theming"],
            activeTab: "a11y",
        });
        await el.updateComplete;
        const events: TabCloseDetail[] = [];
        el.addEventListener("tab-closed", (e) =>
            events.push((e as CustomEvent<TabCloseDetail>).detail),
        );
        const close = el.querySelector<HTMLButtonElement>(
            '.data-tab-handle[data-bundle="a11y"] .data-tab-close',
        );
        assert.ok(close);
        close!.click();
        assert.deepStrictEqual(events, [{ id: "a11y" }]);
    });
});
