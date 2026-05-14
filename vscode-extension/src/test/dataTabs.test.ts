// `<data-tabs>` — verifies the stable trailing `…More` tab that lists
// every currently-disabled bundle. See
// `docs/design/EXTENSION_DATA_EXPOSURE.md` § "UX shell" item 8.

import * as assert from "assert";
import { BUNDLES } from "../webview/preview/bundleRegistry";
import type { BundleId } from "../webview/preview/bundleRegistry";

// Importing the component registers the custom element with
// `customElements.define`. Tests drive it through `setState` + DOM.
import "../webview/preview/components/DataTabs";
import type {
    BundleToggledDetail,
    DataTabs,
    TabSelectDetail,
} from "../webview/preview/components/DataTabs";

function build(): DataTabs {
    const el = document.createElement("data-tabs") as DataTabs;
    document.body.appendChild(el);
    return el;
}

function moreHandle(el: DataTabs): HTMLElement | null {
    return el.querySelector<HTMLElement>('[data-bundle="__more"]');
}

function moreBody(el: DataTabs): HTMLElement | null {
    return el.querySelector<HTMLElement>(
        '.data-tab-body[data-bundle="__more"]',
    );
}

describe("DataTabs `…More` tab", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders the `…More` tab with no active bundles", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            activeTab: null,
        });
        await el.updateComplete;
        const more = moreHandle(el);
        assert.ok(more, "…More handle must render with no active bundles");
        const handles = el.querySelectorAll(".data-tab-handle");
        assert.strictEqual(
            handles.length,
            1,
            "only the …More handle should render when no bundles are active",
        );
    });

    it("places `…More` last when other tabs are active", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y", "theming"],
            activeTab: "a11y",
        });
        await el.updateComplete;
        const handles = Array.from(
            el.querySelectorAll<HTMLElement>(".data-tab-handle"),
        );
        // Two bundle handles + one …More handle.
        assert.strictEqual(handles.length, 3);
        const ids = handles.map((h) => h.getAttribute("data-bundle"));
        assert.deepStrictEqual(ids, ["a11y", "theming", "__more"]);
    });

    it("lists every inactive bundle by registry id in the `…More` body", async () => {
        const el = build();
        const active: BundleId[] = ["a11y", "errors"];
        el.setState({
            bundles: BUNDLES,
            activeBundles: active,
            activeTab: "a11y",
        });
        await el.updateComplete;
        const body = moreBody(el);
        assert.ok(body, "…More body must render");
        const rows = Array.from(
            body!.querySelectorAll<HTMLElement>(".data-tab-more-row"),
        );
        const rowIds = rows.map((r) => r.getAttribute("data-bundle"));
        const expected = BUNDLES.map((b) => b.id).filter(
            (id) => !active.includes(id),
        );
        assert.deepStrictEqual(rowIds, expected);
    });

    it("dispatches `bundle-toggled` with the row's id when clicked", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            activeTab: null,
        });
        await el.updateComplete;
        const events: BundleToggledDetail[] = [];
        el.addEventListener("bundle-toggled", (e) =>
            events.push((e as CustomEvent<BundleToggledDetail>).detail),
        );
        const row = el.querySelector<HTMLButtonElement>(
            '.data-tab-more-row[data-bundle="theming"]',
        );
        assert.ok(row, "theming row must render");
        row!.click();
        assert.deepStrictEqual(events, [{ id: "theming" }]);
    });

    it("has no close `×` button on the `…More` handle", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y"],
            activeTab: "a11y",
        });
        await el.updateComplete;
        const more = moreHandle(el);
        assert.ok(more);
        const close = more!.querySelector(".data-tab-close");
        assert.strictEqual(
            close,
            null,
            "…More tab must be the only non-closable tab",
        );
    });

    it("shows the 'Everything's on' placeholder when no bundle is disabled", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: BUNDLES.map((b) => b.id),
            activeTab: "a11y",
        });
        await el.updateComplete;
        const body = moreBody(el);
        assert.ok(body);
        const rows = body!.querySelectorAll(".data-tab-more-row");
        assert.strictEqual(
            rows.length,
            0,
            "no disabled-bundle rows when every bundle is active",
        );
        const empty = body!.querySelector(".data-table-empty");
        assert.ok(
            empty && /everything/i.test(empty.textContent ?? ""),
            "placeholder copy must mention everything being on",
        );
    });

    it("dispatches `tab-selected` with id=null when the `…More` handle is clicked", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y"],
            activeTab: "a11y",
        });
        await el.updateComplete;
        const events: TabSelectDetail[] = [];
        el.addEventListener("tab-selected", (e) =>
            events.push((e as CustomEvent<TabSelectDetail>).detail),
        );
        const label =
            moreHandle(el)!.querySelector<HTMLButtonElement>(".data-tab-label");
        assert.ok(label);
        label!.click();
        assert.deepStrictEqual(events, [{ id: null }]);
    });

    it("marks the `…More` handle aria-selected when no bundle tab is active", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            activeTab: null,
        });
        await el.updateComplete;
        const more = moreHandle(el);
        assert.strictEqual(more!.getAttribute("aria-selected"), "true");
    });
});
