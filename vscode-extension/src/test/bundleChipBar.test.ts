// `<bundle-chip-bar>` — verifies the chip strip's render, daemon-ready
// gate, and toggle event. The chip-driven subscription flow itself is
// covered by `bundleController.test.ts`; this suite focuses on the
// component's DOM contract.

import * as assert from "assert";
import { BUNDLES } from "../webview/preview/bundleRegistry";

// Importing the component registers the custom element. Tests drive it
// through `setState` + DOM, then assert on the rendered light DOM.
import "../webview/preview/components/BundleChipBar";
import type {
    BundleChipBar,
    BundleToggledDetail,
} from "../webview/preview/components/BundleChipBar";

function build(): BundleChipBar {
    const el = document.createElement("bundle-chip-bar") as BundleChipBar;
    document.body.appendChild(el);
    return el;
}

describe("BundleChipBar", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders one chip per available bundle in registry order", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            availableBundles: null,
        });
        await el.updateComplete;
        const chips = Array.from(
            el.querySelectorAll<HTMLButtonElement>(".bundle-chip"),
        );
        assert.strictEqual(chips.length, BUNDLES.length);
        const ids = chips.map((c) => c.getAttribute("data-bundle"));
        assert.deepStrictEqual(
            ids,
            BUNDLES.map((b) => b.id),
        );
    });

    it("filters chips by availableBundles when supplied", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            availableBundles: ["a11y"],
        });
        await el.updateComplete;
        const ids = Array.from(
            el.querySelectorAll<HTMLButtonElement>(".bundle-chip"),
        ).map((c) => c.getAttribute("data-bundle"));
        assert.deepStrictEqual(ids, ["a11y"]);
    });

    it("paints active chips with bundle-chip-on + aria-pressed=true", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: ["a11y"],
            availableBundles: null,
        });
        await el.updateComplete;
        const a11y = el.querySelector<HTMLButtonElement>(
            '.bundle-chip[data-bundle="a11y"]',
        )!;
        assert.ok(a11y.classList.contains("bundle-chip-on"));
        assert.strictEqual(a11y.getAttribute("aria-pressed"), "true");
    });

    it("dispatches bundle-toggled when an enabled chip is clicked", async () => {
        const el = build();
        el.setState({
            bundles: BUNDLES,
            activeBundles: [],
            availableBundles: null,
        });
        await el.updateComplete;
        const toggled: BundleToggledDetail[] = [];
        el.addEventListener("bundle-toggled", (evt) => {
            toggled.push((evt as CustomEvent<BundleToggledDetail>).detail);
        });
        const a11y = el.querySelector<HTMLButtonElement>(
            '.bundle-chip[data-bundle="a11y"]',
        )!;
        a11y.click();
        assert.deepStrictEqual(toggled, [{ id: "a11y" }]);
    });

    describe("daemonReady gate", () => {
        // Regression for the "first preview never gets extensions" UX:
        // until the daemon has spawned, chip clicks would queue
        // subscriptions whose follow-up renderNow races the warm-up
        // render and misses the daemon's subscriptionDrivenRenderMode
        // lock. Greying the chip surfaces the wait instead of leaving
        // the user staring at a clickable control that produces no data.

        it("disables inactive chips when daemonReady is false", async () => {
            const el = build();
            el.setState({
                bundles: BUNDLES,
                activeBundles: [],
                availableBundles: null,
                daemonReady: false,
            });
            await el.updateComplete;
            const chips = Array.from(
                el.querySelectorAll<HTMLButtonElement>(".bundle-chip"),
            );
            for (const chip of chips) {
                assert.ok(
                    chip.disabled,
                    `chip ${chip.getAttribute("data-bundle")} should be disabled while daemon spawns`,
                );
                assert.ok(
                    chip.classList.contains("bundle-chip-disabled"),
                    `chip ${chip.getAttribute("data-bundle")} should carry the disabled class`,
                );
                assert.strictEqual(chip.getAttribute("aria-disabled"), "true");
            }
        });

        it("keeps active chips enabled while daemon is not ready so the user can still turn them off", async () => {
            const el = build();
            el.setState({
                bundles: BUNDLES,
                activeBundles: ["a11y"],
                availableBundles: null,
                daemonReady: false,
            });
            await el.updateComplete;
            const a11y = el.querySelector<HTMLButtonElement>(
                '.bundle-chip[data-bundle="a11y"]',
            )!;
            assert.strictEqual(a11y.disabled, false);
            assert.ok(!a11y.classList.contains("bundle-chip-disabled"));
        });

        it("swallows click events on disabled chips — no bundle-toggled dispatched", async () => {
            const el = build();
            el.setState({
                bundles: BUNDLES,
                activeBundles: [],
                availableBundles: null,
                daemonReady: false,
            });
            await el.updateComplete;
            const toggled: BundleToggledDetail[] = [];
            el.addEventListener("bundle-toggled", (evt) => {
                toggled.push((evt as CustomEvent<BundleToggledDetail>).detail);
            });
            const a11y = el.querySelector<HTMLButtonElement>(
                '.bundle-chip[data-bundle="a11y"]',
            )!;
            // Dispatch a synthetic click — the browser would block this for
            // a disabled button, but the test environment doesn't always
            // enforce that. The component's own handler must also guard.
            a11y.dispatchEvent(
                new MouseEvent("click", { bubbles: true, cancelable: true }),
            );
            assert.deepStrictEqual(toggled, []);
        });

        it("re-enables chips when daemonReady flips to true on a subsequent setState", async () => {
            const el = build();
            el.setState({
                bundles: BUNDLES,
                activeBundles: [],
                availableBundles: null,
                daemonReady: false,
            });
            await el.updateComplete;
            el.setState({
                bundles: BUNDLES,
                activeBundles: [],
                availableBundles: null,
                daemonReady: true,
            });
            await el.updateComplete;
            const chips = Array.from(
                el.querySelectorAll<HTMLButtonElement>(".bundle-chip"),
            );
            for (const chip of chips) {
                assert.strictEqual(chip.disabled, false);
                assert.ok(!chip.classList.contains("bundle-chip-disabled"));
            }
        });

        it("defaults to enabled when daemonReady is omitted (legacy callers / fixtures)", async () => {
            const el = build();
            el.setState({
                bundles: BUNDLES,
                activeBundles: [],
                availableBundles: null,
            });
            await el.updateComplete;
            const chips = Array.from(
                el.querySelectorAll<HTMLButtonElement>(".bundle-chip"),
            );
            for (const chip of chips) {
                assert.strictEqual(chip.disabled, false);
            }
        });
    });
});
