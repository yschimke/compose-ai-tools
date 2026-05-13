// `<bundle-expander>` — the per-tab "Configure…" block that lets a
// power user toggle individual kinds inside a bundle. Default-OFF
// kinds (`a11y/touchTargets`, `a11y/overlay`, `compose/wallpaper`) only
// reachable through this expander, so the checkbox wiring is load-
// bearing UX.

import * as assert from "assert";
import { BUNDLES, getBundle } from "../webview/preview/bundleRegistry";

// Importing the component for its side-effect registers the custom
// element with `customElements.define`. Tests below stand up the
// element via `document.createElement` and drive it through its public
// setState / event surface.
import "../webview/preview/components/BundleExpander";
import type {
    BundleExpander,
    BundleKindToggledDetail,
} from "../webview/preview/components/BundleExpander";

function build(): BundleExpander {
    const el = document.createElement("bundle-expander") as BundleExpander;
    document.body.appendChild(el);
    return el;
}

describe("BundleExpander", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders nothing until setState seeds a bundle id", async () => {
        const el = build();
        await el.updateComplete;
        assert.strictEqual(
            el.querySelector(".bundle-expander"),
            null,
            "expander must render nothing until bundle id is set",
        );
    });

    it("renders one row per kind with the registry label", async () => {
        const el = build();
        const a11y = getBundle("a11y")!;
        el.setState({
            bundleId: "a11y",
            kinds: a11y.kinds,
            enabledKinds: ["a11y/hierarchy", "a11y/atf"],
        });
        await el.updateComplete;
        const rows = el.querySelectorAll(".bundle-expander-row");
        assert.strictEqual(rows.length, a11y.kinds.length);
        const labels = Array.from(
            el.querySelectorAll(".bundle-expander-label"),
        ).map((n) => n.textContent);
        for (const k of a11y.kinds) assert.ok(labels.includes(k.label));
    });

    it("checks the boxes for kinds in the enabled set", async () => {
        const el = build();
        const a11y = getBundle("a11y")!;
        el.setState({
            bundleId: "a11y",
            kinds: a11y.kinds,
            // Only one kind enabled — touchTargets, which is default-OFF.
            enabledKinds: ["a11y/touchTargets"],
        });
        await el.updateComplete;
        const checked = Array.from(
            el.querySelectorAll<HTMLInputElement>("input[type=checkbox]"),
        )
            .filter((i) => i.checked)
            .map((i) => i.dataset.kind);
        assert.deepStrictEqual(checked, ["a11y/touchTargets"]);
    });

    it("dispatches kind-toggled when the user clicks a checkbox", async () => {
        const el = build();
        const a11y = getBundle("a11y")!;
        el.setState({
            bundleId: "a11y",
            kinds: a11y.kinds,
            enabledKinds: [],
        });
        await el.updateComplete;
        const events: BundleKindToggledDetail[] = [];
        el.addEventListener("kind-toggled", (e) =>
            events.push((e as CustomEvent<BundleKindToggledDetail>).detail),
        );
        const overlayInput = el.querySelector<HTMLInputElement>(
            'input[data-kind="a11y/overlay"]',
        );
        assert.ok(overlayInput);
        overlayInput!.checked = true;
        overlayInput!.dispatchEvent(new Event("change", { bubbles: true }));
        assert.deepStrictEqual(events, [
            { bundleId: "a11y", kind: "a11y/overlay", enabled: true },
        ]);
    });

    it("dispatches kind-toggled with enabled=false on uncheck", async () => {
        const el = build();
        const a11y = getBundle("a11y")!;
        el.setState({
            bundleId: "a11y",
            kinds: a11y.kinds,
            enabledKinds: ["a11y/hierarchy"],
        });
        await el.updateComplete;
        const events: BundleKindToggledDetail[] = [];
        el.addEventListener("kind-toggled", (e) =>
            events.push((e as CustomEvent<BundleKindToggledDetail>).detail),
        );
        const input = el.querySelector<HTMLInputElement>(
            'input[data-kind="a11y/hierarchy"]',
        );
        assert.ok(input);
        input!.checked = false;
        input!.dispatchEvent(new Event("change", { bubbles: true }));
        assert.deepStrictEqual(events, [
            { bundleId: "a11y", kind: "a11y/hierarchy", enabled: false },
        ]);
    });

    it("tags the default-ON kinds with a 'default' badge", async () => {
        const el = build();
        const a11y = getBundle("a11y")!;
        el.setState({
            bundleId: "a11y",
            kinds: a11y.kinds,
            enabledKinds: [],
        });
        await el.updateComplete;
        const defaultOn = a11y.kinds.filter((k) => k.defaultOn);
        assert.ok(
            defaultOn.length > 0,
            "registry should ship default-ON kinds",
        );
        const badges = el.querySelectorAll(".bundle-expander-default");
        assert.strictEqual(badges.length, defaultOn.length);
    });

    it("covers every bundle in the registry (smoke)", async () => {
        for (const b of BUNDLES) {
            const el = build();
            el.setState({
                bundleId: b.id,
                kinds: b.kinds,
                enabledKinds: [],
            });
            await el.updateComplete;
            assert.strictEqual(
                el.querySelectorAll(".bundle-expander-row").length,
                b.kinds.length,
                `bundle ${b.id} should render one row per kind`,
            );
            el.remove();
        }
    });
});
