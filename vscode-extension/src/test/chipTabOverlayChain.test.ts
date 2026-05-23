// Bundle chip ↔ tab ↔ overlay integration smoke. Wires
// `BundleController` to the three visual surfaces (chip-bar's active
// set, `<data-tabs>` tab strip, per-card `<box-overlay>` layers) the
// same way `main.ts`'s `reflectBundleState` + `setTabBody` plumbing
// does, then drives the chip on / `×`-close round-trip and asserts
// the full chain.
//
// Complementary to the existing leaf-component tests
// (`bundleChipBar.test.ts`, `dataTabs.test.ts`,
// `cardBundleOverlay.test.ts`) and to the chip → overlay-only smoke
// in `gridOverlayPaintSmoke.test.ts`. The novel coverage here is
// that all three surfaces stay in sync through the controller — a
// regression in any one of `onChange` → DataTabs.setState / tab-body
// install / overlay paint surfaces at the unit level instead of
// waiting for the electron e2e harness (#1006 pattern, see #1104).

import * as assert from "assert";
import { BundleController } from "../webview/preview/bundleController";
import {
    clearBundleBoxes,
    paintBundleBoxesEverywhere,
} from "../webview/preview/cardBundleOverlay";
import { sanitizeId } from "../webview/preview/cardData";
import { BUNDLES, type BundleId } from "../webview/preview/bundleRegistry";
import "../webview/preview/components/BoxOverlay";
import "../webview/preview/components/DataTabs";
import type { DataTabs } from "../webview/preview/components/DataTabs";
import type { OverlayBox } from "../webview/preview/components/BoxOverlay";

const HISTORY: BundleId = "history";

function buildCard(previewId: string): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.id = "preview-" + sanitizeId(previewId);
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    const img = document.createElement("img");
    Object.defineProperty(img, "naturalWidth", { value: 200 });
    Object.defineProperty(img, "naturalHeight", { value: 100 });
    Object.defineProperty(img, "complete", { value: true });
    container.appendChild(img);
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

function buildBundleBody(bundleId: BundleId): HTMLElement {
    const body = document.createElement("div");
    body.className = "bundle-tab-body";
    body.dataset.bundle = bundleId;
    return body;
}

function bundleOverlayCount(bundleId: string): number {
    return document.querySelectorAll(
        'box-overlay[data-bundle="' + bundleId + '"]',
    ).length;
}

interface Wired {
    controller: BundleController;
    tabs: DataTabs;
    bodies: Map<BundleId, HTMLElement>;
}

function wireSurfaces(previewIds: readonly string[]): Wired {
    for (const id of previewIds) buildCard(id);

    const tabs = document.createElement("data-tabs") as DataTabs;
    document.body.appendChild(tabs);

    const controller = new BundleController({
        setKindsEnabled: () => {},
        persist: () => {},
    });

    const bodies = new Map<BundleId, HTMLElement>();

    const computeOverlay = (previewId: string): readonly OverlayBox[] => [
        {
            id: "region-" + previewId,
            bounds: { left: 0, top: 0, right: 50, bottom: 25 },
            level: "info",
        },
    ];

    const apply = (): void => {
        const s = controller.state();
        // Mirror DataTabs.setState — the chip→tabs side.
        tabs.setState({
            bundles: s.bundles,
            activeBundles: s.activeBundles,
            activeTab: s.activeTab,
        });
        // Mirror the install / clear branch in reflectBundleState for
        // the History bundle — install body + paint on activate;
        // remove body + clear on deactivate.
        if (s.activeBundles.includes(HISTORY)) {
            let body = bodies.get(HISTORY);
            if (!body) {
                body = buildBundleBody(HISTORY);
                bodies.set(HISTORY, body);
            }
            tabs.setTabBody(HISTORY, body);
            const cards = document.querySelectorAll<HTMLElement>(
                ".preview-card[data-preview-id]",
            );
            const perCard = new Map<string, readonly OverlayBox[]>();
            for (const card of Array.from(cards)) {
                const id = card.dataset.previewId;
                if (!id) continue;
                perCard.set(id, computeOverlay(id));
            }
            paintBundleBoxesEverywhere(HISTORY, perCard);
        } else {
            tabs.setTabBody(HISTORY, null);
            bodies.delete(HISTORY);
            clearBundleBoxes(null, HISTORY);
        }
    };
    controller.onChange(apply);
    apply();

    return { controller, tabs, bodies };
}

describe("Bundle chip ↔ tab ↔ overlay chain", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("activate → tab appears, body installs, overlays paint on every card", async () => {
        const { controller, tabs, bodies } = wireSurfaces([
            "preview-a",
            "preview-b",
        ]);
        // Resting state: no active bundle → no tab, no body, no overlay.
        await tabs.updateComplete;
        assert.strictEqual(tabs.querySelectorAll(".data-tab-handle").length, 0);
        assert.strictEqual(bundleOverlayCount(HISTORY), 0);

        controller.toggleBundle(HISTORY);
        await tabs.updateComplete;

        const handles = tabs.querySelectorAll<HTMLElement>(
            ".data-tab-handle[data-bundle]",
        );
        assert.strictEqual(handles.length, 1);
        assert.strictEqual(handles[0].dataset.bundle, HISTORY);
        assert.ok(
            bodies.get(HISTORY),
            "tab body should be installed on activation",
        );
        assert.strictEqual(bundleOverlayCount(HISTORY), 2);
    });

    it("× close → tab + body removed, overlays cleared on every card", async () => {
        const { controller, tabs, bodies } = wireSurfaces([
            "preview-a",
            "preview-b",
        ]);
        controller.toggleBundle(HISTORY);
        await tabs.updateComplete;
        assert.strictEqual(bundleOverlayCount(HISTORY), 2);

        // Simulate the × click — the production path calls
        // controller.closeTab in the `tab-closed` listener
        // (main.ts:2292). We exercise the controller method
        // directly so the test isn't bound to the DataTabs DOM
        // shape for the close button itself (covered by
        // dataTabs.test.ts).
        controller.closeTab(HISTORY);
        await tabs.updateComplete;

        assert.strictEqual(
            tabs.querySelectorAll(".data-tab-handle").length,
            0,
            "tab handle should be gone after close",
        );
        assert.strictEqual(
            bodies.get(HISTORY),
            undefined,
            "tab body should be uninstalled",
        );
        assert.strictEqual(
            bundleOverlayCount(HISTORY),
            0,
            "every card's overlay layer should be cleared",
        );
    });

    it("the history bundle is part of the panel registry (smoke against a moved id)", () => {
        // The chain above is keyed on the `history` bundle id. If the
        // registry rename-drops it, every assertion in this suite
        // becomes vacuously true — the controller would refuse to
        // activate an unknown id, and the listeners would skip the
        // active branch entirely. Pin the assumption.
        const id: BundleId = HISTORY;
        assert.ok(
            BUNDLES.some((b) => b.id === id),
            `bundle registry is missing the "${HISTORY}" id`,
        );
    });
});
