import "./setup.js";
import assert from "node:assert/strict";
import { flush, stubStorage } from "./setup.js";

describe("power-user keyboard navigation", () => {
    it("onboards from Settings and exposes contextual command palettes", async () => {
        const storage = stubStorage();
        document.body.innerHTML = `
          <details class="cp-settings" open><input type="checkbox" data-cp-keyboard-navigation>
            <button data-cp-keyboard-tour>View keyboard tour</button></details>
          <a class="cp-card" href="/catalog/p/button"><span class="cp-label">Button</span></a>
          <a class="cp-card" href="/catalog/p/switch"><span class="cp-label">Switch</span></a>
          <div id="cp-axes" class="cp-axes-tree"><a class="cp-tree-component" href="/catalog/p/button">Button default</a><a class="cp-tree-variant" href="/catalog/p/button-dark">Dark</a></div>
          <div class="cp-preview-primary"><button type="button" aria-pressed="true">Snapshot</button></div>
          <div class="cp-viewer"><div id="cp-controls"><details><label>Locale
            <input id="cp-localeTag"></label><label>Size mode
            <select id="cp-sizeMode"><option value="">Default</option><option value="fixed">Fixed size</option></select>
          </label></details></div></div>
          <button id="cp-controls-toggle" type="button">Overrides</button>`;

        await import("../src/keyboardNavigation.js");
        const setting = document.querySelector<HTMLInputElement>(
            "[data-cp-keyboard-navigation]",
        )!;
        setting.click();

        assert.equal(storage.get("cp-keyboard-navigation"), "1");
        assert.equal(
            document.querySelector<HTMLDetailsElement>(".cp-settings")!.open,
            false,
        );
        assert.match(
            document.querySelector("[role='dialog']")!.textContent!,
            /Move through previews/,
        );
        document
            .querySelector<HTMLButtonElement>(".cp-onboarding-later")!
            .click();
        assert.ok(
            document.getElementById("cp-keyboard-hints"),
            "the on-screen hint rail appears",
        );

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "c", bubbles: true }),
        );
        await flush();
        const componentItems = Array.from(
            document.querySelectorAll(".cp-command-item span"),
        ).map((item) => item.textContent);
        assert.deepEqual(componentItems, ["Button", "Switch"]);

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "o", bubbles: true }),
        );
        assert.match(
            document.querySelector("[role='dialog']")!.textContent!,
            /Locale/,
        );
        const search = document.querySelector<HTMLInputElement>(
            ".cp-command-search input",
        )!;
        search.value = "Fixed size";
        search.dispatchEvent(new Event("input", { bubbles: true }));
        document.querySelector<HTMLButtonElement>(".cp-command-item")!.click();
        assert.equal(
            document.querySelector<HTMLSelectElement>("#cp-sizeMode")!.value,
            "fixed",
        );

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "?", bubbles: true }),
        );
        assert.match(
            document.querySelector("[role='dialog']")!.textContent!,
            /Next \/ previous component/,
        );
    });
});
