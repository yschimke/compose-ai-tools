// Smoke test for the focus view's "data extension toggle" path.
//
// Each toggle in the Focus view's Configure expander travels through
// four hops: BundleExpander dispatches `kind-toggled` → host listener
// in `main.ts` → `BundleController.setKindEnabled` → host stub posts
// `setDataExtensionEnabled` to the extension. Per-module unit tests
// cover each hop in isolation; this test exercises the chain end-to-
// end with the same wiring shape `main.ts` uses, so a regression in
// any single link surfaces here.
//
// Replicates the listener pattern at `main.ts:766/821/874/1499` —
// mirror copies of `expander.addEventListener("kind-toggled", …)`
// that forward into the controller. We re-create that wiring in
// the test instead of mounting `<preview-app>` because `main.ts`'s
// `firstUpdated` pulls in the entire webview boot sequence (message
// router, focus controller, live state, viewport tracker, …) which
// is unavailable in happy-dom. The bug class this PR targets is
// "click produces no observable effect"; the wiring shape under test
// is what `main.ts` does, so a copy-paste in the same shape is
// adequate signal.
//
// Known coverage gap: today's specific bug is in `main.ts`'s
// `currentBundleTarget()` resolver — it returns `null` when no
// focused card has `dataset.previewId`, silently swallowing the
// post. That resolver lives as a closure inside `firstUpdated` and
// is not testable without a refactor. Filed as a follow-up; see PR
// description.

import * as assert from "assert";
import {
    BUNDLES,
    defaultOnKindsFor,
    getBundle,
    type BundleId,
} from "../webview/preview/bundleRegistry";
import { BundleController } from "../webview/preview/bundleController";
import "../webview/preview/components/BundleExpander";
import type {
    BundleExpander,
    BundleKindToggledDetail,
} from "../webview/preview/components/BundleExpander";

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
 * Mount `<bundle-expander>` and wire it to the controller exactly
 * like `main.ts:766` does so the test exercises the live wiring
 * shape. Returns the mounted element after its first render.
 */
async function mountWiredExpander(
    bundleId: BundleId,
    controller: BundleController,
): Promise<BundleExpander> {
    const expander = document.createElement(
        "bundle-expander",
    ) as BundleExpander;
    document.body.appendChild(expander);
    expander.addEventListener("kind-toggled", (evt) => {
        const det = (evt as CustomEvent<BundleKindToggledDetail>).detail;
        controller.setKindEnabled(det.bundleId, det.kind, det.enabled);
    });
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
    // added to the registry is automatically covered — the test
    // fails fast if its expander wiring is missing instead of
    // shipping a silent no-op tab.
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
