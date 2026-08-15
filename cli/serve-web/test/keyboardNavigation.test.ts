import "./setup.js";
import assert from "node:assert/strict";
import { flush, stubStorage } from "./setup.js";

describe("power-user keyboard navigation", () => {
    it("onboards from Settings and exposes contextual command palettes", async () => {
        const storage = stubStorage();
        document.body.innerHTML = `
          <details class="cp-settings" open><input type="checkbox" data-cp-keyboard-navigation>
            <button data-cp-keyboard-tour>View keyboard tour</button></details>
          <nav class="cp-catalog-menu">
            <a class="cp-tree-component" href="#cp-card-button">Button</a>
            <a class="cp-tree-component" href="#cp-card-switch">Switch</a>
          </nav>
          <a id="cp-card-button" class="cp-card" href="/catalog/p/button"><span class="cp-label">Button</span></a>
          <a id="cp-card-switch" class="cp-card" href="/catalog/p/switch"><span class="cp-label">Switch</span></a>
          <div id="cp-axes" class="cp-axes-tree"><a class="cp-tree-component" href="/catalog/p/button">Button default</a><a class="cp-tree-variant" href="/catalog/p/button-dark">Dark</a></div>
          <div class="cp-preview-primary">
            <button id="cp-live-toggle" type="button" aria-pressed="false">Snapshot</button>
            <button class="cp-theme-btn" type="button" aria-pressed="false">Day</button>
          </div>
          <div class="cp-viewer"><div id="cp-controls"><details><label>Locale
            <input id="cp-localeTag"></label><label>Size mode
            <select id="cp-sizeMode"><option value="">Default</option><option value="fixed">Fixed size</option></select>
          </label><span aria-hidden="true"><select id="cp-theme"><option>Hidden theme state</option></select></span></details></div></div>
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

        const traversed: string[] = [];
        document
            .querySelectorAll<HTMLAnchorElement>(
                ".cp-catalog-menu .cp-tree-component",
            )
            .forEach((link) =>
                link.addEventListener("click", (event) => {
                    event.preventDefault();
                    traversed.push(link.textContent!.trim());
                    history.replaceState(null, "", link.href);
                }),
            );
        history.replaceState(null, "", location.href.split("#")[0]);
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "j", bubbles: true }),
        );
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "j", bubbles: true }),
        );
        assert.deepEqual(traversed, ["Button", "Switch"]);

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "o", bubbles: true }),
        );
        assert.match(
            document.querySelector("[role='dialog']")!.textContent!,
            /Locale/,
        );
        assert.doesNotMatch(
            document.querySelector("[role='dialog']")!.textContent!,
            /Hidden theme state/,
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

        let daySelections = 0;
        document
            .querySelector<HTMLButtonElement>(".cp-theme-btn")!
            .addEventListener("click", () => daySelections++);
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "m", bubbles: true }),
        );
        const modeItems = Array.from(
            document.querySelectorAll<HTMLButtonElement>(".cp-command-item"),
        );
        assert.equal(
            modeItems[0].textContent,
            "Live previewSwitch to interactive mode",
        );
        const dayCommand = modeItems.find((item) =>
            item.textContent?.startsWith("Day"),
        )!;
        assert.equal(dayCommand.tabIndex, -1);
        dayCommand.dispatchEvent(new MouseEvent("mouseenter"));
        dayCommand.focus();
        dayCommand.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Enter", bubbles: true }),
        );
        dayCommand.click();
        assert.equal(daySelections, 1, "a focused command executes only once");
        assert.equal(
            document.querySelector("[role='dialog']"),
            null,
            "mode selection closes the palette",
        );

        setting.click();
        document
            .querySelector<HTMLButtonElement>("[data-cp-keyboard-tour]")!
            .click();
        assert.match(
            document.querySelector("[role='dialog']")!.textContent!,
            /Next \/ previous component/,
        );
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        assert.equal(
            document.querySelector("[role='dialog']"),
            null,
            "Escape closes the tour even while navigation is disabled",
        );
    });
});
