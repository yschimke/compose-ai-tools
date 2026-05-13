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

function build(initial?: BundleSnapshot): {
    controller: BundleController;
    toggles: CapturedToggle[];
    persisted: BundleSnapshot[];
} {
    const toggles: CapturedToggle[] = [];
    const persisted: BundleSnapshot[] = [];
    const controller = new BundleController(
        {
            setKindEnabled: (kind, enabled) => toggles.push({ kind, enabled }),
            persist: (snap) => persisted.push(snap),
        },
        initial,
    );
    return { controller, toggles, persisted };
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
