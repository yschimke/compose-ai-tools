// Coverage for `createBundleExpander` — the helper that the per-
// bundle body builders in `main.ts` use to construct + wire each
// `<bundle-expander>`. Pins the post-construction state so a future
// refactor (e.g. lifting the expander slot into `<data-tabs>`)
// can't drop the `data-bundle` attribute or the controller wiring
// without a test noticing.

import * as assert from "assert";
import { BundleController } from "../webview/preview/bundleController";
import { createBundleExpander } from "../webview/preview/bundleExpanderWiring";
import {
    defaultOnKindsFor,
    getBundle,
} from "../webview/preview/bundleRegistry";
import "../webview/preview/components/BundleExpander";

interface CapturedPost {
    kinds: readonly string[];
    enabled: boolean;
}

function buildController(): {
    controller: BundleController;
    posts: CapturedPost[];
} {
    const posts: CapturedPost[] = [];
    const controller = new BundleController({
        setKindsEnabled: (kinds, enabled) => {
            posts.push({ kinds: [...kinds], enabled });
        },
        persist: () => {},
    });
    return { controller, posts };
}

describe("createBundleExpander", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("stamps data-bundle and constructs a bundle-expander element", () => {
        const { controller } = buildController();
        const el = createBundleExpander("a11y", controller);
        assert.strictEqual(el.tagName.toLowerCase(), "bundle-expander");
        assert.strictEqual(el.dataset.bundle, "a11y");
    });

    it("forwards user kind toggles into the controller", async () => {
        const { controller, posts } = buildController();
        const expander = createBundleExpander("a11y", controller);
        document.body.appendChild(expander);
        // Activate the bundle so the controller has a target.
        controller.toggleBundle("a11y");
        const bundle = getBundle("a11y")!;
        expander.setOpened(true);
        expander.setState({
            bundleId: "a11y",
            kinds: bundle.kinds,
            enabledKinds: defaultOnKindsFor("a11y"),
        });
        await expander.updateComplete;
        // The default-OFF touchTargets checkbox is the cheapest gesture
        // to fake — flip it on and assert one capture with the matching
        // kind. Drops here mean the helper forgot the wiring call.
        posts.length = 0;
        const box = expander.querySelector<HTMLInputElement>(
            'input[data-kind="a11y/touchTargets"]',
        );
        assert.ok(box, "touchTargets checkbox missing");
        box!.checked = true;
        box!.dispatchEvent(new Event("change", { bubbles: true }));
        assert.strictEqual(posts.length, 1);
        assert.deepStrictEqual(posts[0].kinds, ["a11y/touchTargets"]);
        assert.strictEqual(posts[0].enabled, true);
    });
});
