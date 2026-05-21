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
import {
    ensureControlsToggleButton,
    ensureKeyboardBandToggleButton,
    ensureLiveCardControls,
    ensureTouchOverlayToggleButton,
    removeControlsToggleButton,
    removeKeyboardBandToggleButton,
    removeTouchOverlayToggleButton,
} from "../webview/preview/liveCardControls";

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

describe("ensureControlsToggleButton (issue #1203)", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("stamps a .card-controls-toggle-btn with the right shape + pressed state", () => {
        const card = buildCard();
        ensureControlsToggleButton(card, {
            enabled: false,
            onToggle: () => {},
        });
        const btn = card.querySelector(
            ".card-controls-toggle-btn",
        ) as HTMLButtonElement | null;
        assert.ok(btn, "controls toggle should appear in image-container");
        assert.strictEqual(btn!.type, "button");
        assert.strictEqual(btn!.classList.contains("icon-button"), true);
        assert.strictEqual(btn!.getAttribute("aria-pressed"), "false");
        assert.ok(
            /Turn on/.test(btn!.title),
            "off-state title should say 'Turn on'",
        );
        assert.ok(btn!.querySelector("i.codicon.codicon-keyboard"));
    });

    it("reflects the enabled state via aria-pressed + title", () => {
        const card = buildCard();
        ensureControlsToggleButton(card, { enabled: true, onToggle: () => {} });
        const btn = card.querySelector(
            ".card-controls-toggle-btn",
        ) as HTMLButtonElement;
        assert.strictEqual(btn.getAttribute("aria-pressed"), "true");
        assert.ok(
            /Turn off/.test(btn.title),
            "on-state title should say 'Turn off'",
        );
    });

    it("is idempotent — repeat calls don't duplicate the button or restack handlers", () => {
        const card = buildCard();
        let calls: boolean[] = [];
        ensureControlsToggleButton(card, {
            enabled: false,
            onToggle: (_c, next) => calls.push(next),
        });
        // A second call with a different onToggle must not stack a new handler.
        ensureControlsToggleButton(card, {
            enabled: false,
            onToggle: () => calls.push(true),
        });
        assert.strictEqual(
            card.querySelectorAll(".card-controls-toggle-btn").length,
            1,
            "duplicate calls must not stamp a second button",
        );
        const btn = card.querySelector(
            ".card-controls-toggle-btn",
        ) as HTMLButtonElement;
        btn.click();
        assert.deepStrictEqual(
            calls,
            [true],
            "the FIRST call's onToggle is bound; second-call onToggle stays disconnected",
        );
    });

    it("click toggles between aria-pressed = false → true by invoking onToggle with !current", () => {
        const card = buildCard();
        const seen: boolean[] = [];
        ensureControlsToggleButton(card, {
            enabled: false,
            onToggle: (_c, next) => seen.push(next),
        });
        const btn = card.querySelector(
            ".card-controls-toggle-btn",
        ) as HTMLButtonElement;
        btn.click(); // aria-pressed = "false" → onToggle(true)
        // Simulate the controller updating state.
        ensureControlsToggleButton(card, { enabled: true, onToggle: () => {} });
        // Re-bind onToggle to capture the next call (handler stays original — so use
        // the first onToggle by leaving binding alone). Note this verifies that the
        // pressed state reflects the latest enabled flag passed to ensure().
        assert.strictEqual(btn.getAttribute("aria-pressed"), "true");
        assert.deepStrictEqual(seen, [true]);
        // Toggle off — handler was bound on the first call, so seen should grow.
        btn.click(); // aria-pressed = "true" → onToggle(false)
        assert.deepStrictEqual(seen, [true, false]);
    });

    it("click suppresses default and stops propagation", () => {
        const card = buildCard();
        let bubbled = 0;
        card.addEventListener("click", () => bubbled++);
        ensureControlsToggleButton(card, {
            enabled: false,
            onToggle: () => {},
        });
        const btn = card.querySelector(
            ".card-controls-toggle-btn",
        ) as HTMLButtonElement;
        const evt = new Event("click", { bubbles: true, cancelable: true });
        btn.dispatchEvent(evt);
        assert.strictEqual(evt.defaultPrevented, true);
        assert.strictEqual(bubbled, 0);
    });

    it("is a silent no-op when .image-container is missing", () => {
        const card = buildBareCard();
        assert.doesNotThrow(() =>
            ensureControlsToggleButton(card, {
                enabled: false,
                onToggle: () => {},
            }),
        );
        assert.strictEqual(
            card.querySelector(".card-controls-toggle-btn"),
            null,
        );
        assert.strictEqual(card.children.length, 0);
    });

    it("removeControlsToggleButton drops the button and is idempotent on a button-less card", () => {
        const card = buildCard();
        ensureControlsToggleButton(card, {
            enabled: false,
            onToggle: () => {},
        });
        assert.ok(card.querySelector(".card-controls-toggle-btn"));
        removeControlsToggleButton(card);
        assert.strictEqual(
            card.querySelector(".card-controls-toggle-btn"),
            null,
        );
        // Second call is a no-op.
        assert.doesNotThrow(() => removeControlsToggleButton(card));
    });
});

describe("ensureTouchOverlayToggleButton", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("stamps a .card-touch-overlay-toggle-btn with the codicon-target icon", () => {
        const card = buildCard();
        ensureTouchOverlayToggleButton(card, {
            enabled: false,
            onToggle: () => {},
        });
        const btn = card.querySelector(
            ".card-touch-overlay-toggle-btn",
        ) as HTMLButtonElement | null;
        assert.ok(btn, "touch-overlay toggle should appear in image-container");
        assert.strictEqual(btn!.type, "button");
        assert.strictEqual(btn!.classList.contains("icon-button"), true);
        assert.strictEqual(btn!.getAttribute("aria-pressed"), "false");
        assert.ok(
            /Turn on/.test(btn!.title),
            `off-state title should say 'Turn on'; got '${btn!.title}'`,
        );
        assert.ok(btn!.querySelector("i.codicon.codicon-target"));
    });

    it("reflects the enabled state via aria-pressed + title", () => {
        const card = buildCard();
        ensureTouchOverlayToggleButton(card, {
            enabled: true,
            onToggle: () => {},
        });
        const btn = card.querySelector(
            ".card-touch-overlay-toggle-btn",
        ) as HTMLButtonElement;
        assert.strictEqual(btn.getAttribute("aria-pressed"), "true");
        assert.ok(/Turn off/.test(btn.title));
    });

    it("re-stamping doesn't duplicate the button or rebind the click handler", () => {
        const card = buildCard();
        let clicks = 0;
        ensureTouchOverlayToggleButton(card, {
            enabled: false,
            onToggle: () => {
                clicks++;
            },
        });
        // Re-stamp twice. Same DOM element must persist; click handler must
        // still fire exactly once per real click.
        ensureTouchOverlayToggleButton(card, {
            enabled: true,
            onToggle: () => {
                clicks++;
            },
        });
        ensureTouchOverlayToggleButton(card, {
            enabled: false,
            onToggle: () => {
                clicks++;
            },
        });
        assert.strictEqual(
            card.querySelectorAll(".card-touch-overlay-toggle-btn").length,
            1,
        );
        const btn = card.querySelector(
            ".card-touch-overlay-toggle-btn",
        ) as HTMLButtonElement;
        btn.click();
        assert.strictEqual(clicks, 1, "click should fire onToggle once");
    });

    it("click inverts the current pressed state and calls onToggle with the new value", () => {
        const card = buildCard();
        const calls: { id: string; next: boolean }[] = [];
        ensureTouchOverlayToggleButton(card, {
            enabled: false,
            onToggle: (c, next) =>
                calls.push({ id: c.dataset.previewId!, next }),
        });
        const btn = card.querySelector(
            ".card-touch-overlay-toggle-btn",
        ) as HTMLButtonElement;
        btn.click();
        assert.deepStrictEqual(calls, [{ id: "com.example.A", next: true }]);
    });

    it("removeTouchOverlayToggleButton drops the button and is idempotent", () => {
        const card = buildCard();
        ensureTouchOverlayToggleButton(card, {
            enabled: false,
            onToggle: () => {},
        });
        assert.ok(card.querySelector(".card-touch-overlay-toggle-btn"));
        removeTouchOverlayToggleButton(card);
        assert.strictEqual(
            card.querySelector(".card-touch-overlay-toggle-btn"),
            null,
        );
        assert.doesNotThrow(() => removeTouchOverlayToggleButton(card));
    });
});

describe("ensureKeyboardBandToggleButton", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("stamps a .card-keyboard-band-toggle-btn with the codicon-symbol-keyword icon", () => {
        const card = buildCard();
        ensureKeyboardBandToggleButton(card, {
            enabled: false,
            onToggle: () => {},
        });
        const btn = card.querySelector(
            ".card-keyboard-band-toggle-btn",
        ) as HTMLButtonElement | null;
        assert.ok(btn);
        assert.strictEqual(btn!.getAttribute("aria-pressed"), "false");
        assert.ok(/Force/.test(btn!.title));
        assert.ok(btn!.querySelector("i.codicon.codicon-symbol-keyword"));
    });

    it("toggles on click and removes idempotently", () => {
        const card = buildCard();
        const calls: boolean[] = [];
        ensureKeyboardBandToggleButton(card, {
            enabled: false,
            onToggle: (_c, next) => calls.push(next),
        });
        (
            card.querySelector(
                ".card-keyboard-band-toggle-btn",
            ) as HTMLButtonElement
        ).click();
        assert.deepStrictEqual(calls, [true]);
        removeKeyboardBandToggleButton(card);
        assert.strictEqual(
            card.querySelector(".card-keyboard-band-toggle-btn"),
            null,
        );
        assert.doesNotThrow(() => removeKeyboardBandToggleButton(card));
    });
});
