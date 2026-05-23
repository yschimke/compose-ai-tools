// Grid-mode overlay paint smoke — exercises the chip ↔ overlay
// round-trip via the production `BundleController`. The existing
// `cardBundleOverlay.test.ts` covers the helper functions
// (`paintBundleBoxesEverywhere`, `clearBundleBoxes`) in isolation;
// this suite wires them through the controller the way `main.ts`
// does so a regression in the `onChange` → paint/clear plumbing
// surfaces here instead of waiting for an electron run.

import * as assert from "assert";
import { BundleController } from "../webview/preview/bundleController";
import {
    clearBundleBoxes,
    paintBundleBoxesEverywhere,
} from "../webview/preview/cardBundleOverlay";
import { sanitizeId } from "../webview/preview/cardData";
import type { BundleId } from "../webview/preview/bundleRegistry";
import "../webview/preview/components/BoxOverlay";
import type { OverlayBox } from "../webview/preview/components/BoxOverlay";

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

function box(id: string): OverlayBox {
    return {
        id,
        bounds: { left: 0, top: 0, right: 50, bottom: 25 },
        level: "info",
    };
}

/**
 * Mirror of the chip → paint plumbing in `main.ts`'s
 * `reflectBundleState`: when [bundleId] is in `activeBundles` we
 * paint every card with its own data; when it leaves, we clear the
 * bundle's `<box-overlay>` layer from every card. The closure is
 * the smallest piece of `reflectBundleState` we can exercise
 * without spinning up the full panel.
 */
function wireBundleToOverlay(
    controller: BundleController,
    bundleId: BundleId,
    computeOverlay: (previewId: string) => readonly OverlayBox[],
): void {
    const apply = (): void => {
        const s = controller.state();
        if (s.activeBundles.includes(bundleId)) {
            const cards = document.querySelectorAll<HTMLElement>(
                ".preview-card[data-preview-id]",
            );
            const perCard = new Map<string, readonly OverlayBox[]>();
            for (const card of Array.from(cards)) {
                const id = card.dataset.previewId;
                if (!id) continue;
                perCard.set(id, computeOverlay(id));
            }
            paintBundleBoxesEverywhere(bundleId, perCard);
        } else {
            clearBundleBoxes(null, bundleId);
        }
    };
    controller.onChange(apply);
}

function bundleOverlayCount(bundleId: string): number {
    return document.querySelectorAll(
        'box-overlay[data-bundle="' + bundleId + '"]',
    ).length;
}

describe("Grid-mode overlay paint smoke", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("chip-on paints every visible card; chip-off clears every layer", () => {
        const ids = ["preview-a", "preview-b", "preview-c"];
        for (const id of ids) buildCard(id);

        const controller = new BundleController({
            setKindsEnabled: () => {},
            persist: () => {},
        });
        wireBundleToOverlay(controller, "history", (previewId) => [
            box("region-" + previewId),
        ]);

        // Chip starts off → no layers painted.
        assert.strictEqual(bundleOverlayCount("history"), 0);

        // Chip on → every visible card gets a `data-bundle="history"`
        // layer.
        controller.toggleBundle("history");
        assert.strictEqual(bundleOverlayCount("history"), ids.length);
        for (const id of ids) {
            const card = document.getElementById("preview-" + sanitizeId(id))!;
            const overlay = card.querySelector(
                'box-overlay[data-bundle="history"]',
            );
            assert.ok(overlay, `card ${id} should host a history overlay`);
        }

        // Chip off → every layer torn down.
        controller.toggleBundle("history");
        assert.strictEqual(bundleOverlayCount("history"), 0);
    });

    it("hidden cards are skipped on chip-on but cleared on chip-off", () => {
        // The grid hides filtered-out cards via inline `display:none`
        // (FilterController) and the [hidden] attribute. Paint must
        // respect that on activation. Teardown is unconditional —
        // chip-off scrubs every layer regardless of visibility so a
        // re-filter that re-shows a card doesn't expose stale boxes.
        buildCard("preview-visible");
        const hidden1 = buildCard("preview-hidden-attr");
        hidden1.hidden = true;
        const hidden2 = buildCard("preview-hidden-css");
        hidden2.style.display = "none";

        const controller = new BundleController({
            setKindsEnabled: () => {},
            persist: () => {},
        });
        wireBundleToOverlay(controller, "history", () => [box("r")]);

        controller.toggleBundle("history");
        assert.strictEqual(
            bundleOverlayCount("history"),
            1,
            "only the visible card should pick up a layer",
        );

        // Manually stamp a layer onto the hidden cards so we can
        // verify the chip-off teardown still wipes them — the
        // production path can reach a hidden card via a prior visible
        // refresh that ran before a filter narrowed.
        const layer = document.createElement("box-overlay");
        layer.dataset.bundle = "history";
        hidden1.querySelector(".image-container")!.appendChild(layer);
        assert.strictEqual(bundleOverlayCount("history"), 2);

        controller.toggleBundle("history");
        assert.strictEqual(
            bundleOverlayCount("history"),
            0,
            "chip-off should clear every layer regardless of visibility",
        );
    });

    it("re-pressing the chip without an intervening clear repaints in place", () => {
        // The controller fires `onChange` for every state mutation,
        // including transitions like activate → enabledKinds change →
        // deactivate. The paint helper must idempotently update the
        // existing layer rather than stacking new mounts.
        buildCard("preview-x");
        const controller = new BundleController({
            setKindsEnabled: () => {},
            persist: () => {},
        });
        wireBundleToOverlay(controller, "history", () => [box("r")]);

        controller.toggleBundle("history");
        assert.strictEqual(bundleOverlayCount("history"), 1);
        // Trigger another active-side onChange via setKindEnabled —
        // mirrors the user clicking a kind checkbox inside the
        // expander while the chip is on.
        controller.setKindEnabled("history", "history/diff/regions", false);
        assert.strictEqual(
            bundleOverlayCount("history"),
            1,
            "second active-side refresh should reuse the existing layer",
        );
    });
});
