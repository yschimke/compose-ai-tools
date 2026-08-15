// Behavioural contract for `<cp-backend-badge>`.
//
// Replaces the `ServeWebFixtureTest` assertions that matched the *source text*
// of `assets/backend-badge.js` (`contains("\"▶ CMP-WASM\"")`,
// `contains("if (mode === \"svg\") return \"▪ SVG\";")`). Those proved a string
// literal existed somewhere in a file — not that the badge ever showed it, and
// not that the right lane produced it — and they cannot survive minification.
// The Kotlin side keeps what it can genuinely check: that the viewer emits the
// tag with the server-settable lane labels on it.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/BackendBadge.js";

/**
 * Mount a viewer whose stage carries the badge, and return the badge plus the
 * `.cp-viewer` root that drives it.
 */
async function mount(
    rootAttrs = 'data-mode="snapshot" data-snapshot-backend="Snapshot" data-live-backend="Live"',
    extra = "",
): Promise<{ badge: HTMLElement; root: HTMLElement }> {
    document.body.innerHTML = `
      <div class="cp-viewer" ${rootAttrs}>
        <div class="cp-stage">
          <cp-backend-badge class="cp-backend" id="cp-backend" role="status" aria-live="polite"></cp-backend-badge>
        </div>
        ${extra}
      </div>`;
    await flush();
    return {
        badge: document.getElementById("cp-backend") as HTMLElement,
        root: document.querySelector(".cp-viewer") as HTMLElement,
    };
}

/** Flip a lane on the root the way `viewer.js` does, and let the badge settle. */
async function setMode(root: HTMLElement, mode: string): Promise<void> {
    root.setAttribute("data-mode", mode);
    await flush();
}

describe("<cp-backend-badge>", () => {
    afterEach(() => resetDom());

    it("keeps the server's live region rather than rendering one", async () => {
        const { badge } = await mount();
        // The host IS the badge. If this ever becomes a wrapper that renders a
        // <span role="status">, the region gets created by script with its text
        // already in place and screen readers stop announcing lane changes.
        assert.equal(badge.tagName.toLowerCase(), "cp-backend-badge");
        assert.equal(badge.getAttribute("role"), "status");
        assert.equal(badge.getAttribute("aria-live"), "polite");
        assert.equal(badge.querySelector("[role=status]"), null);
    });

    it("names the snapshot tier with the static icon", async () => {
        const { badge } = await mount();
        assert.equal(badge.textContent?.trim(), "▪ Snapshot");
        assert.equal(badge.getAttribute("data-live"), "false");
    });

    it("hard-codes the CMP-WASM tier label with the live icon", async () => {
        const { badge, root } = await mount();
        await setMode(root, "wasm");
        assert.equal(badge.textContent?.trim(), "▶ CMP-WASM");
    });

    it("names the SVG lane as static", async () => {
        const { badge, root } = await mount();
        await setMode(root, "svg");
        assert.equal(badge.textContent?.trim(), "▪ SVG");
        assert.equal(badge.getAttribute("data-live"), "false");
    });

    it("takes the live and snapshot labels from the server, not a constant", async () => {
        const { badge, root } = await mount(
            'data-mode="live" data-snapshot-backend="Android" data-live-backend="JVM"',
        );
        assert.equal(badge.textContent?.trim(), "▶ JVM");
        await setMode(root, "snapshot");
        assert.equal(badge.textContent?.trim(), "▪ Android");
    });

    it("falls back to generic labels when the server set none", async () => {
        document.body.innerHTML = `
          <div class="cp-viewer" data-mode="live">
            <div class="cp-stage">
              <cp-backend-badge id="cp-backend"></cp-backend-badge>
            </div>
          </div>`;
        await flush();
        const badge = document.getElementById("cp-backend") as HTMLElement;
        const root = document.querySelector(".cp-viewer") as HTMLElement;
        assert.equal(badge.textContent?.trim(), "▶ Live");
        await setMode(root, "snapshot");
        assert.equal(badge.textContent?.trim(), "▪ Snapshot");
    });

    it("wears the outline diamond for an imported spec, never a renderer icon", async () => {
        const { badge, root } = await mount(
            'data-mode="spec" data-snapshot-backend="Snapshot" data-live-backend="Live"',
            '<div id="cp-spec-lane" data-spec-label="Figma"></div>',
        );
        assert.equal(badge.textContent?.trim(), "◇ Figma");
        // Not ours to claim: the spec lane must not read as something this server
        // rendered, so it carries neither ▶ nor ▪ and never the live accent.
        assert.equal(badge.getAttribute("data-live"), "false");
        await setMode(root, "snapshot");
        assert.equal(badge.textContent?.trim(), "▪ Snapshot");
    });

    it("labels a spec lane generically when the page named no design source", async () => {
        const { badge } = await mount(
            'data-mode="spec" data-snapshot-backend="Snapshot" data-live-backend="Live"',
        );
        assert.equal(badge.textContent?.trim(), "◇ Spec");
    });

    it("flips the live accent on for the interactive lanes only", async () => {
        const { badge, root } = await mount();
        for (const [mode, live] of [
            ["snapshot", "false"],
            ["live", "true"],
            ["wasm", "true"],
            ["svg", "false"],
            ["spec", "false"],
        ]) {
            await setMode(root, mode);
            assert.equal(
                badge.getAttribute("data-live"),
                live,
                `${mode} should be data-live=${live}`,
            );
        }
    });

    it("shows the pending lane over its label, then clears it", async () => {
        const { badge, root } = await mount(
            'data-mode="live" data-snapshot-backend="Snapshot" data-live-backend="Live"',
        );
        root.setAttribute("data-pending", "connecting…");
        await flush();
        assert.equal(badge.textContent?.trim(), "◌ connecting…");
        // Presence, not "false": the amber CSS rule keys off the attribute existing.
        assert.equal(badge.getAttribute("data-pending"), "true");

        root.removeAttribute("data-pending");
        await flush();
        assert.equal(badge.textContent?.trim(), "▶ Live");
        assert.equal(badge.hasAttribute("data-pending"), false);
    });

    it("stops observing the viewer once removed", async () => {
        const { badge, root } = await mount();
        badge.remove();
        await setMode(root, "wasm");
        assert.equal(badge.textContent?.trim(), "▪ Snapshot");
    });

    it("stays inert on a page with no viewer", async () => {
        document.body.innerHTML = `<cp-backend-badge id="cp-backend"></cp-backend-badge>`;
        await flush();
        const badge = document.getElementById("cp-backend") as HTMLElement;
        assert.equal(badge.textContent?.trim(), "");
    });
});
