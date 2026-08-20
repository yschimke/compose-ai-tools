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
            <a class="cp-tree-component" href="#cp-card-button">Button<span class="cp-tree-count">34</span></a>
            <a class="cp-tree-component" href="#cp-card-switch">Switch</a>
            <a class="cp-tree-component" href="#cp-card-caf%C3%A9">Café</a>
          </nav>
          <a id="cp-card-button" class="cp-card" href="/catalog/p/button"><span class="cp-label">Button</span></a>
          <a id="cp-card-switch" class="cp-card" href="/catalog/p/switch"><span class="cp-label">Switch</span></a>
          <a id="cp-card-café" class="cp-card" href="/catalog/p/cafe"><span class="cp-label">Café</span></a>
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
        assert.deepEqual(
            componentItems,
            ["Button", "Switch", "Café"],
            "tree counts stay out of labels and encoded fragments dedupe against Unicode card ids",
        );

        const cafeCommand = Array.from(
            document.querySelectorAll<HTMLButtonElement>(".cp-command-item"),
        ).find((item) => item.textContent?.startsWith("Café"))!;
        cafeCommand.click();
        assert.equal(
            document.querySelector("[role='dialog']"),
            null,
            "fragment component commands close the palette before scrolling",
        );

        const traversed: string[] = [];
        document
            .querySelectorAll<HTMLAnchorElement>(
                ".cp-catalog-menu .cp-tree-component",
            )
            .forEach((link) =>
                link.addEventListener("click", (event) => {
                    event.preventDefault();
                    traversed.push(link.firstChild?.textContent?.trim() || "");
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

        const settings = setting.closest("details")!;
        document.body.replaceChildren(settings);
        document.body.insertAdjacentHTML(
            "beforeend",
            `
          <span hidden data-cp-global-components="/api/components"></span>
          <div class="cp-card cp-sys">
            <span class="cp-sys-title"><a class="cp-sys-open" href="/compose-m3/">Compose Material 3</a></span>
            <span class="cp-id">compose-m3</span>
            <p class="cp-sys-actions"><a class="cp-action-chip" href="/compose-m3/compare?format=reference">compare to Figma</a></p>
          </div>
          <div class="cp-card cp-sys">
            <span class="cp-sys-title"><a class="cp-sys-open" href="/wear-m3/">Wear Material 3</a></span>
            <span class="cp-id">wear-m3</span>
          </div>`,
        );
        let componentFetches = 0;
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: async () => {
                componentFetches++;
                if (componentFetches === 1)
                    return new Response("temporarily unavailable", {
                        status: 503,
                    });
                return new Response(
                    JSON.stringify({
                        components: [
                            {
                                label: "Filled button",
                                catalog: "compose-m3",
                                catalogTitle: "Compose Material 3",
                                href: "/compose-m3/p/button-filled",
                                keywords: "button-filled Buttons",
                            },
                        ],
                    }),
                    { status: 200 },
                );
            },
        });
        setting.click();

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "c", bubbles: true }),
        );
        await flush();
        assert.match(
            document.querySelector(".cp-command-empty")!.textContent!,
            /Components are unavailable/,
        );
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "c", bubbles: true }),
        );
        await flush();
        assert.equal(
            document.querySelector(".cp-command-item")!.textContent,
            "Filled buttonCompose Material 3",
            "the home-page C palette searches components across catalogs",
        );
        assert.equal(
            componentFetches,
            2,
            "a failed component fetch retries on reopen",
        );
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );

        document.dispatchEvent(
            new KeyboardEvent("keydown", {
                key: "k",
                metaKey: true,
                bubbles: true,
            }),
        );
        const homeSections = Array.from(
            document.querySelectorAll(".cp-command-section"),
        ).map((section) => section.textContent);
        assert.deepEqual(
            homeSections,
            ["catalogs", "components"],
            "the home command palette searches catalogs and components",
        );
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );

        document.body.replaceChildren(settings);
        setting.click();
        setting.click();
        assert.doesNotMatch(
            document.getElementById("cp-keyboard-hints")!.textContent!,
            /Components/,
            "an empty page never advertises an empty component palette",
        );
    });
});
