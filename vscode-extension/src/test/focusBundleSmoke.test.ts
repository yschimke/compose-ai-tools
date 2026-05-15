// Smoke test for the focus view's "data extension toggle" path.
//
// Each toggle in the Focus view's Configure expander travels through
// four hops: BundleExpander dispatches `kind-toggled` → host listener
// installed by `wireExpanderToController` → `BundleController.set
// KindEnabled` → host stub posts `setDataExtensionEnabled` to the
// extension. Per-module unit tests cover each hop in isolation; this
// test exercises the chain end-to-end so a regression in any single
// link surfaces here.
//
// Crucially, the test calls the *production* `wireExpanderToController`
// helper — the same one `main.ts` calls at each per-bundle body
// builder. If anyone changes the listener shape (event name, detail
// fields, controller method, etc.) the production wiring and this
// smoke test break together. The previous version of this file
// hand-rolled a mirror `addEventListener("kind-toggled", …)` block,
// which meant deleting the real listener from `main.ts` left the
// test passing — that gap is what this rewrite closes.
//
// Residual gap: the test does not catch a future bundle id added to
// the registry without a `wireExpanderToController` call in the new
// body builder. Avoiding that needs either a single factory that
// owns the expander construction (out of scope for this PR; the four
// body builders have different shapes) or a static check that every
// `createElement("bundle-expander")` in `main.ts` has a nearby
// `wireExpanderToController` call. Filed for the Playwright follow-
// up because end-to-end is the cleanest way to catch it anyway.
//
// Known coverage gap retained from the first revision: today's
// specific bug is in `main.ts`'s `currentBundleTarget()` resolver —
// returns `null` when no focused card has `dataset.previewId`,
// silently swallowing the post. That resolver lives as a closure
// inside `firstUpdated` and is not testable without a refactor;
// tracked separately in the Playwright follow-up issue.

import * as assert from "assert";
import {
    BUNDLES,
    defaultOnKindsFor,
    getBundle,
    type BundleId,
} from "../webview/preview/bundleRegistry";
import { BundleController } from "../webview/preview/bundleController";
import { wireExpanderToController } from "../webview/preview/bundleExpanderWiring";
import "../webview/preview/components/BundleExpander";
import type { BundleExpander } from "../webview/preview/components/BundleExpander";

interface CapturedPost {
    previewId: string;
    kind: string;
    enabled: boolean;
}

interface Scenario {
    controller: BundleController;
    posts: CapturedPost[];
    previewId: string;
}

/**
 * Mirror of the host plumbing in `main.ts`. The real
 * implementation posts `setDataExtensionEnabled` to the extension via
 * `vscode.postMessage`; here we capture the payload so the assertion
 * runs against the same shape that crosses the wire.
 */
function buildScenario(): Scenario {
    const posts: CapturedPost[] = [];
    const previewId = "preview-smoke-0";
    const controller = new BundleController({
        setKindEnabled: (kind, enabled) => {
            posts.push({ previewId, kind, enabled });
        },
        persist: () => {},
    });
    return { controller, posts, previewId };
}

/**
 * Mount `<bundle-expander>` and wire it to the controller using the
 * production helper `wireExpanderToController` — the same call
 * `main.ts` makes at every per-bundle body builder. Returns the
 * mounted element after its first render.
 */
async function mountWiredExpander(
    bundleId: BundleId,
    controller: BundleController,
): Promise<BundleExpander> {
    const expander = document.createElement(
        "bundle-expander",
    ) as BundleExpander;
    document.body.appendChild(expander);
    wireExpanderToController(expander, controller);
    const bundle = getBundle(bundleId);
    assert.ok(bundle, `bundle ${bundleId} missing from registry`);
    expander.setOpened(true);
    expander.setState({
        bundleId,
        kinds: bundle.kinds,
        enabledKinds: defaultOnKindsFor(bundleId),
    });
    await expander.updateComplete;
    return expander;
}

function findCheckbox(
    expander: BundleExpander,
    kind: string,
): HTMLInputElement {
    const box = expander.querySelector<HTMLInputElement>(
        `input[data-kind="${kind}"]`,
    );
    assert.ok(box, `expected checkbox for kind ${kind}`);
    return box;
}

function clickCheckbox(box: HTMLInputElement): void {
    box.checked = !box.checked;
    box.dispatchEvent(new Event("change", { bubbles: true }));
}

describe("Focus view data-extension toggle smoke", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    // One spec per bundle. Iterating BUNDLES means a new bundle id
    // added to the registry is automatically covered for the
    // wiring-shape check (event name, detail fields, controller
    // method). It does NOT catch a new bundle whose body builder
    // forgets to call `wireExpanderToController` — see the residual-
    // gap note at the top of the file.
    for (const bundle of BUNDLES) {
        it(`forwards every ${bundle.id} kind to setDataExtensionEnabled`, async () => {
            const { controller, posts, previewId } = buildScenario();
            // The bundle has to be active for the controller to
            // know which preview the per-kind toggle belongs to;
            // `toggleBundle` is what the chip-bar does in
            // production.
            controller.toggleBundle(bundle.id);
            const expander = await mountWiredExpander(bundle.id, controller);
            // Activation posts the default-ON kinds; drop those
            // from the capture so the per-checkbox asserts below
            // see only the user clicks.
            posts.length = 0;

            for (const k of bundle.kinds) {
                const box = findCheckbox(expander, k.kind);
                const wasEnabled = box.checked;
                clickCheckbox(box);
                // Each click is one observable post — drops here
                // mean the listener didn't fire, the controller
                // short-circuited, or the host wasn't called.
                assert.strictEqual(
                    posts.length,
                    1,
                    `clicking ${bundle.id}/${k.kind} produced ` +
                        `${posts.length} posts; expected exactly 1`,
                );
                assert.deepStrictEqual(
                    posts[0],
                    {
                        previewId,
                        kind: k.kind,
                        enabled: !wasEnabled,
                    },
                    `${bundle.id}/${k.kind} post payload mismatch`,
                );
                posts.length = 0;
            }
        });
    }

    it("a no-op host (controller stub posting nothing) still ticks the box", async () => {
        // Guards against a future refactor that conflates "host
        // accepted the toggle" with "checkbox should be checked".
        // The checkbox is owned by the user gesture; reflection
        // arrives later. Whether the host posts is unrelated to
        // the UI state of the input element.
        const controller = new BundleController({
            setKindEnabled: () => {
                /* host black-hole */
            },
            persist: () => {},
        });
        controller.toggleBundle("a11y");
        const expander = await mountWiredExpander("a11y", controller);
        const box = findCheckbox(expander, "a11y/touchTargets");
        assert.strictEqual(box.checked, false, "touchTargets is default-OFF");
        clickCheckbox(box);
        assert.strictEqual(
            box.checked,
            true,
            "user gesture must flip the box regardless of host outcome",
        );
    });
});
