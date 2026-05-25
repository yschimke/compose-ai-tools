// DOM-feature tests for `ensureLiveCardControls` (the per-card
// stop-button injection lifted out of `LiveStateController` into a
// narrow-deps file so happy-dom can exercise it without dragging the
// controller's interactive-input / vscode-api transitive imports
// into the host tsconfig).
//
// Pins the contract:
//
//   - Stamps a `<button class="icon-button card-live-stop-btn">`
//     into the card's `.image-container`, with the `Stop live preview`
//     title / aria-label and an embedded `codicon-debug-stop` glyph.
//   - Idempotent: a second call doesn't duplicate the button and
//     doesn't re-bind the click handler.
//   - Click on the button suppresses default + propagation and invokes
//     the supplied `onStop` exactly once with the card.
//   - Missing `.image-container` is a silent no-op (no throw, no
//     stray button stamped onto the card root).

import * as assert from "assert";
import { ensureLiveCardControls } from "../webview/preview/liveCardControls";

/** Build a `<div class="preview-card">` with an `.image-container`
 *  child (the place the per-card stop button gets appended to) and
 *  append it to `document.body`. Returns the card. */
function buildCard(previewId = "com.example.A"): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

/** Build a `<div class="preview-card">` with NO `.image-container`
 *  child. Exercises the silent no-op guard. */
function buildBareCard(previewId = "com.example.A"): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    document.body.appendChild(card);
    return card;
}

describe("ensureLiveCardControls", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });

    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("stamps a .card-live-stop-btn into .image-container with the right shape", () => {
        const card = buildCard();
        ensureLiveCardControls(card, () => {});

        const container = card.querySelector(".image-container")!;
        const btn = container.querySelector(
            ".card-live-stop-btn",
        ) as HTMLButtonElement | null;
        assert.ok(btn, "stop button should be appended to image container");
        assert.strictEqual(btn!.tagName, "BUTTON");
        assert.strictEqual(btn!.type, "button");
        assert.strictEqual(
            btn!.classList.contains("icon-button"),
            true,
            "should keep the shared .icon-button class",
        );
        assert.strictEqual(btn!.classList.contains("card-live-stop-btn"), true);
        assert.strictEqual(btn!.title, "Stop live preview");
        assert.strictEqual(
            btn!.getAttribute("aria-label"),
            "Stop live preview",
        );
        const icon = btn!.querySelector("i.codicon.codicon-debug-stop");
        assert.ok(icon, "should embed the debug-stop codicon");
        assert.strictEqual(icon!.getAttribute("aria-hidden"), "true");
    });

    it("is idempotent — repeat calls don't duplicate the button", () => {
        const card = buildCard();
        ensureLiveCardControls(card, () => {});
        const firstHtml = card.innerHTML;
        ensureLiveCardControls(card, () => {});
        ensureLiveCardControls(card, () => {});
        assert.strictEqual(
            card.querySelectorAll(".card-live-stop-btn").length,
            1,
            "second/third call must not duplicate the button",
        );
        assert.strictEqual(
            card.innerHTML,
            firstHtml,
            "second call must not mutate the DOM at all",
        );
    });

    it("doesn't re-bind the click handler on a repeat call", () => {
        // The first call's `onStop` is the one wired to the button.
        // A second call with a different `onStop` must not replace
        // (or stack onto) the existing handler — the bound callback
        // stays the original one.
        const card = buildCard();
        let firstCalls = 0;
        let secondCalls = 0;
        ensureLiveCardControls(card, () => {
            firstCalls += 1;
        });
        ensureLiveCardControls(card, () => {
            secondCalls += 1;
        });
        const btn = card.querySelector(
            ".card-live-stop-btn",
        ) as HTMLButtonElement;
        btn.click();
        assert.strictEqual(
            firstCalls,
            1,
            "the original handler should fire once",
        );
        assert.strictEqual(
            secondCalls,
            0,
            "the second-call handler should NOT have been wired",
        );
    });

    it("click invokes onStop exactly once with the card", () => {
        const card = buildCard("com.example.X");
        const visited: HTMLElement[] = [];
        ensureLiveCardControls(card, (c) => {
            visited.push(c);
        });
        const btn = card.querySelector(
            ".card-live-stop-btn",
        ) as HTMLButtonElement;
        btn.click();
        assert.strictEqual(visited.length, 1);
        assert.strictEqual(visited[0], card);
        assert.strictEqual(visited[0].dataset.previewId, "com.example.X");
    });

    it("click suppresses default and stops propagation", () => {
        const card = buildCard();
        let bubbled = 0;
        // Card-level click listener that would normally fire if the
        // button click bubbled out of the image-container.
        card.addEventListener("click", () => {
            bubbled += 1;
        });
        ensureLiveCardControls(card, () => {});
        const btn = card.querySelector(
            ".card-live-stop-btn",
        ) as HTMLButtonElement;
        // Dispatch a cancellable click so we can observe `defaultPrevented`.
        const evt = new Event("click", { bubbles: true, cancelable: true });
        btn.dispatchEvent(evt);
        assert.strictEqual(
            evt.defaultPrevented,
            true,
            "handler must call preventDefault",
        );
        assert.strictEqual(
            bubbled,
            0,
            "handler must call stopPropagation so card-level click never sees it",
        );
    });

    it("is a silent no-op when .image-container is missing", () => {
        const card = buildBareCard();
        let invoked = 0;
        assert.doesNotThrow(() =>
            ensureLiveCardControls(card, () => {
                invoked += 1;
            }),
        );
        // No stray button stamped onto the card root.
        assert.strictEqual(card.querySelector(".card-live-stop-btn"), null);
        // Card body is untouched.
        assert.strictEqual(card.children.length, 0);
        // The callback isn't invoked just by ensureing controls.
        assert.strictEqual(invoked, 0);
    });
});
