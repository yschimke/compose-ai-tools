// Bundle controller — chip ↔ tab ↔ overlay state machine that the
// new panel shell drives (#1054). The screenshot complaint that
// triggered this work was "there is no way to get back" once a
// hierarchy view paints; these tests pin the dismiss paths so the
// state machine can't regress that gap silently.

import * as assert from "assert";
import {
    BundleController,
    type BundleSnapshot,
} from "../webview/preview/bundleController";
import { defaultOnKindsFor } from "../webview/preview/bundleRegistry";

interface CapturedToggle {
    kind: string;
    enabled: boolean;
}

interface CapturedBatch {
    kinds: readonly string[];
    enabled: boolean;
}

function build(initial?: BundleSnapshot): {
    controller: BundleController;
    toggles: CapturedToggle[];
    batches: CapturedBatch[];
    persisted: BundleSnapshot[];
} {
    const toggles: CapturedToggle[] = [];
    const batches: CapturedBatch[] = [];
    const persisted: BundleSnapshot[] = [];
    const controller = new BundleController(
        {
            setKindsEnabled: (kinds, enabled) => {
                // Capture both the per-kind unrolled view (the original
                // test surface — most assertions look at individual
                // kinds without caring how they were batched) and the
                // batched view used by the "single call per activate"
                // regression test below.
                batches.push({ kinds: [...kinds], enabled });
                for (const kind of kinds) toggles.push({ kind, enabled });
            },
            persist: (snap) => persisted.push(snap),
        },
        initial,
    );
    return { controller, toggles, batches, persisted };
}

describe("BundleController", () => {
    it("activates a bundle and subscribes its default-ON kinds", () => {
        const { controller, toggles } = build();
        controller.toggleBundle("a11y");
        const expected = defaultOnKindsFor("a11y");
        assert.ok(expected.length > 0, "registry should ship default-ON kinds");
        assert.deepStrictEqual(
            toggles.map((t) => ({ kind: t.kind, enabled: t.enabled })),
            expected.map((kind) => ({ kind, enabled: true })),
        );
        assert.deepStrictEqual(controller.state().activeBundles, ["a11y"]);
        assert.strictEqual(controller.state().activeTab, "a11y");
    });

    it("activate posts a single batched host call carrying every default-ON kind", () => {
        // Regression: one wire message per kind raced the daemon's
        // subscription-driven render mode — the first subscribe locked
        // the in-flight render's data-product set, the second arrived
        // too late, and bundles with multiple default-ON kinds (a11y,
        // text/i18n) silently delivered partial data products. The
        // controller must batch into one host call so the extension
        // host issues a single `data/subscribe` sequence + one
        // `renderNow` against the daemon.
        const { controller, batches } = build();
        controller.toggleBundle("a11y");
        const activates = batches.filter((b) => b.enabled);
        assert.strictEqual(
            activates.length,
            1,
            `activate must post exactly one batched host call, got ${activates.length}`,
        );
        assert.deepStrictEqual(
            [...activates[0].kinds].sort(),
            [...defaultOnKindsFor("a11y")].sort(),
        );
    });

    it("deactivate posts a single batched host call carrying every active kind", () => {
        const { controller, batches } = build();
        controller.toggleBundle("a11y");
        batches.length = 0;
        controller.toggleBundle("a11y");
        const deactivates = batches.filter((b) => !b.enabled);
        assert.strictEqual(
            deactivates.length,
            1,
            `deactivate must post exactly one batched host call, got ${deactivates.length}`,
        );
        assert.deepStrictEqual(
            [...deactivates[0].kinds].sort(),
            [...defaultOnKindsFor("a11y")].sort(),
        );
    });

    it("chip re-press dismisses the bundle and unsubscribes", () => {
        const { controller, toggles } = build();
        controller.toggleBundle("a11y");
        toggles.length = 0;
        controller.toggleBundle("a11y");
        assert.ok(
            toggles.every((t) => t.enabled === false),
            "deactivation must unsubscribe every active kind",
        );
        assert.deepStrictEqual(controller.state().activeBundles, []);
        assert.strictEqual(
            controller.state().activeTab,
            null,
            "no inspector is the resting state once all bundles close",
        );
    });

    it("deactivateAll drops every active bundle and unsubscribes their kinds", () => {
        const { controller, toggles } = build();
        controller.toggleBundle("a11y");
        controller.toggleBundle("theming");
        toggles.length = 0;
        controller.deactivateAll();
        assert.deepStrictEqual(controller.state().activeBundles, []);
        assert.strictEqual(controller.state().activeTab, null);
        assert.ok(
            toggles.length > 0,
            "deactivateAll must unsubscribe each previously active kind",
        );
        assert.ok(
            toggles.every((t) => t.enabled === false),
            "every host call from deactivateAll must be an unsubscribe",
        );
    });

    it("deactivateAll on an empty controller is a no-op", () => {
        const { controller, toggles } = build();
        controller.deactivateAll();
        assert.deepStrictEqual(controller.state().activeBundles, []);
        assert.strictEqual(toggles.length, 0);
    });

    it("tab × is identical to chip re-press (dismiss path redundancy)", () => {
        const { controller, toggles } = build();
        controller.toggleBundle("a11y");
        const afterActivate = toggles.length;
        controller.closeTab("a11y");
        const closeToggles = toggles.slice(afterActivate);
        assert.ok(closeToggles.length > 0);
        assert.ok(closeToggles.every((t) => t.enabled === false));
        assert.strictEqual(controller.state().activeTab, null);
    });

    it("re-activates with the prior per-kind set, not the bundle defaults", () => {
        const { controller, toggles } = build();
        controller.toggleBundle("a11y");
        controller.setKindEnabled("a11y", "a11y/atf", false);
        controller.closeTab("a11y");
        toggles.length = 0;
        controller.toggleBundle("a11y");
        const reactivated = toggles.filter((t) => t.enabled).map((t) => t.kind);
        assert.ok(
            !reactivated.includes("a11y/atf"),
            "user-disabled kind should stay off when the bundle re-opens",
        );
    });

    it("re-activating a bundle whose stored kinds drained to empty falls back to defaults", () => {
        // Configure-expander all-off used to persist `{a11y: []}`; the
        // next chip activation read that empty array, the
        // `kinds.length > 0` guard skipped `setKindsEnabled`, and the
        // chip lit up against a bundle that never subscribed to
        // anything — no `setDataExtensionEnabled` ever reached the
        // extension, no `[daemon] onDataProductsAttached` log. Pin
        // the contract that pressing the chip always produces a wire
        // post: empty-stored snaps back to defaults rather than
        // honouring a zero-kind preference the chip UI can't surface.
        const { controller, toggles } = build();
        controller.toggleBundle("a11y");
        for (const k of ["a11y/hierarchy", "a11y/atf"]) {
            controller.setKindEnabled("a11y", k, false);
        }
        // Sanity: stored kinds list is now empty.
        assert.deepStrictEqual(controller.state().enabledKinds("a11y"), []);
        controller.closeTab("a11y");
        toggles.length = 0;
        controller.toggleBundle("a11y");
        const reactivated = toggles
            .filter((t) => t.enabled)
            .map((t) => t.kind)
            .sort();
        assert.deepStrictEqual(
            reactivated,
            ["a11y/atf", "a11y/hierarchy"],
            "empty-stored kinds must snap back to defaults on re-activate",
        );
    });

    it("activating a snapshot whose kinds drained out (registry drift) falls back to defaults", () => {
        // Same guard as above, exercised via the snapshot path: a
        // bundle whose persisted kinds all disappeared from the
        // registry filters down to `[]` in the constructor, and the
        // first activation must still subscribe defaults rather than
        // silently no-op.
        const { controller, toggles } = build({
            activeBundles: [],
            enabledKindsByBundle: { a11y: [] },
            activeTab: null,
        });
        assert.deepStrictEqual(controller.state().enabledKinds("a11y"), []);
        controller.toggleBundle("a11y");
        const subscribed = toggles
            .filter((t) => t.enabled)
            .map((t) => t.kind)
            .sort();
        assert.deepStrictEqual(subscribed, ["a11y/atf", "a11y/hierarchy"]);
    });

    it("MRU promotes the just-pressed bundle to the front of activeBundles", () => {
        const { controller } = build();
        controller.toggleBundle("a11y");
        controller.toggleBundle("theming");
        assert.deepStrictEqual(controller.state().activeBundles, [
            "theming",
            "a11y",
        ]);
    });

    it("selectTab is rejected for inactive bundles", () => {
        const { controller } = build();
        controller.toggleBundle("a11y");
        controller.selectTab("theming");
        assert.strictEqual(controller.state().activeTab, "a11y");
    });

    it("activates the bundle when an external kind toggle subscribes", () => {
        const { controller } = build();
        controller.handleExternalKindToggle("a11y/hierarchy", true);
        assert.ok(controller.state().activeBundles.includes("a11y"));
        assert.strictEqual(controller.state().activeTab, "a11y");
    });

    it("persists a snapshot on every state change", () => {
        const { controller, persisted } = build();
        const before = persisted.length;
        controller.toggleBundle("a11y");
        assert.ok(persisted.length > before);
        const snap = persisted[persisted.length - 1];
        assert.deepStrictEqual(snap.activeBundles, ["a11y"]);
        assert.strictEqual(snap.activeTab, "a11y");
    });

    it("restores from a snapshot, filtering stale kinds", () => {
        const restored = build({
            activeBundles: ["a11y"],
            enabledKindsByBundle: {
                a11y: ["a11y/atf", "fonts/used"], // fonts/used isn't in a11y
            },
            activeTab: "a11y",
        });
        const kinds = restored.controller.state().enabledKinds("a11y");
        assert.ok(kinds.includes("a11y/atf"));
        assert.ok(
            !kinds.includes("fonts/used"),
            "kinds outside the bundle must be filtered on restore",
        );
        // No subscriptions are replayed on restore — the bundle
        // assumes the caller re-subscribes per its own readiness rules.
        assert.deepStrictEqual(restored.toggles, []);
    });
});
