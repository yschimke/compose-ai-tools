import * as assert from "assert";
import {
    buildFrameControls,
    updateFrameIndicator,
} from "../webview/preview/frameCarouselDom";

/** Build a bare `<div class="preview-card">` and append to body. The
 *  carousel doesn't care about the rest of the card chrome — just that
 *  the element it's handed accepts `dataset.currentIndex`. */
function buildCard(): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    document.body.appendChild(card);
    return card;
}

/** Build the controls and append them to the card so query selectors
 *  rooted on the card resolve them — mirrors how the controller wires
 *  this up at create-card time. */
function buildAndAttach(
    card: HTMLElement,
    onStep: (card: HTMLElement, delta: number) => void = () => {},
    onSeed: (card: HTMLElement) => void = () => {},
): HTMLElement {
    const bar = buildFrameControls(card, onStep, onSeed);
    card.appendChild(bar);
    return bar;
}

describe("buildFrameControls", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("produces a .frame-controls bar with prev / indicator / next children in order", () => {
        const card = buildCard();
        const bar = buildAndAttach(card);

        assert.strictEqual(bar.className, "frame-controls");
        assert.strictEqual(bar.children.length, 3);

        const prev = bar.children[0] as HTMLButtonElement;
        const indicator = bar.children[1] as HTMLElement;
        const next = bar.children[2] as HTMLButtonElement;

        assert.strictEqual(prev.tagName, "BUTTON");
        assert.strictEqual(prev.className, "icon-button frame-prev");
        assert.strictEqual(prev.getAttribute("aria-label"), "Previous capture");
        assert.strictEqual(prev.title, "Previous capture");
        assert.ok(
            prev.querySelector("i.codicon.codicon-chevron-left"),
            "prev should contain the chevron-left codicon",
        );

        assert.strictEqual(indicator.tagName, "SPAN");
        assert.strictEqual(indicator.className, "frame-indicator");
        assert.strictEqual(indicator.getAttribute("aria-live"), "polite");

        assert.strictEqual(next.tagName, "BUTTON");
        assert.strictEqual(next.className, "icon-button frame-next");
        assert.strictEqual(next.getAttribute("aria-label"), "Next capture");
        assert.strictEqual(next.title, "Next capture");
        assert.ok(
            next.querySelector("i.codicon.codicon-chevron-right"),
            "next should contain the chevron-right codicon",
        );
    });

    it("makes the bar focusable so arrow keys can fire on it", () => {
        const card = buildCard();
        const bar = buildAndAttach(card);
        assert.strictEqual(bar.tabIndex, 0);
    });

    it("calls onIndicatorSeed exactly once after build, with the card", () => {
        const card = buildCard();
        const seeded: HTMLElement[] = [];
        buildFrameControls(
            card,
            () => {},
            (c) => seeded.push(c),
        );
        assert.strictEqual(seeded.length, 1);
        assert.strictEqual(seeded[0], card);
    });

    it("prev click invokes onStep(card, -1) exactly once", () => {
        const card = buildCard();
        const calls: Array<[HTMLElement, number]> = [];
        const bar = buildAndAttach(card, (c, d) => calls.push([c, d]));
        const prev = bar.querySelector<HTMLButtonElement>(".frame-prev")!;
        prev.click();
        assert.deepStrictEqual(calls, [[card, -1]]);
    });

    it("next click invokes onStep(card, +1) exactly once", () => {
        const card = buildCard();
        const calls: Array<[HTMLElement, number]> = [];
        const bar = buildAndAttach(card, (c, d) => calls.push([c, d]));
        const next = bar.querySelector<HTMLButtonElement>(".frame-next")!;
        next.click();
        assert.deepStrictEqual(calls, [[card, 1]]);
    });

    it("ArrowLeft on the bar fires onStep(card, -1), preventDefault, and stops propagation", () => {
        const card = buildCard();
        const calls: Array<[HTMLElement, number]> = [];
        const bar = buildAndAttach(card, (c, d) => calls.push([c, d]));

        let parentSawIt = false;
        card.addEventListener("keydown", () => {
            parentSawIt = true;
        });

        const ev = new KeyboardEvent("keydown", {
            key: "ArrowLeft",
            bubbles: true,
            cancelable: true,
        });
        const dispatched = bar.dispatchEvent(ev);

        assert.deepStrictEqual(calls, [[card, -1]]);
        assert.strictEqual(ev.defaultPrevented, true);
        assert.strictEqual(
            dispatched,
            false,
            "preventDefault should make dispatchEvent return false",
        );
        assert.strictEqual(
            parentSawIt,
            false,
            "stopPropagation should keep the parent listener from seeing it",
        );
    });

    it("ArrowRight on the bar fires onStep(card, +1), preventDefault, and stops propagation", () => {
        const card = buildCard();
        const calls: Array<[HTMLElement, number]> = [];
        const bar = buildAndAttach(card, (c, d) => calls.push([c, d]));

        let parentSawIt = false;
        card.addEventListener("keydown", () => {
            parentSawIt = true;
        });

        const ev = new KeyboardEvent("keydown", {
            key: "ArrowRight",
            bubbles: true,
            cancelable: true,
        });
        const dispatched = bar.dispatchEvent(ev);

        assert.deepStrictEqual(calls, [[card, 1]]);
        assert.strictEqual(ev.defaultPrevented, true);
        assert.strictEqual(dispatched, false);
        assert.strictEqual(parentSawIt, false);
    });

    it("ignores non-arrow keys — no onStep, no preventDefault", () => {
        const card = buildCard();
        const calls: Array<[HTMLElement, number]> = [];
        const bar = buildAndAttach(card, (c, d) => calls.push([c, d]));

        for (const key of ["Enter", " ", "a", "Tab", "ArrowUp", "ArrowDown"]) {
            const ev = new KeyboardEvent("keydown", {
                key,
                bubbles: true,
                cancelable: true,
            });
            bar.dispatchEvent(ev);
            assert.strictEqual(
                ev.defaultPrevented,
                false,
                `key ${key} should not be preventDefaulted`,
            );
        }
        assert.deepStrictEqual(calls, []);
    });
});

describe("updateFrameIndicator", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("writes 'idx / total · label' for currentIndex=0, captures of length 3", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "0";
        updateFrameIndicator(card, [
            { label: "500ms" },
            { label: "1000ms" },
            { label: "1500ms" },
        ]);
        const indicator = card.querySelector<HTMLElement>(".frame-indicator")!;
        assert.strictEqual(indicator.textContent, "1 / 3 · 500ms");
    });

    it("disables prev at idx=0 and leaves next enabled", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "0";
        updateFrameIndicator(card, [
            { label: "a" },
            { label: "b" },
            { label: "c" },
        ]);
        const prev = card.querySelector<HTMLButtonElement>(".frame-prev")!;
        const next = card.querySelector<HTMLButtonElement>(".frame-next")!;
        assert.strictEqual(prev.disabled, true);
        assert.strictEqual(next.disabled, false);
    });

    it("disables next at the last index and leaves prev enabled", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "2";
        updateFrameIndicator(card, [
            { label: "a" },
            { label: "b" },
            { label: "c" },
        ]);
        const prev = card.querySelector<HTMLButtonElement>(".frame-prev")!;
        const next = card.querySelector<HTMLButtonElement>(".frame-next")!;
        assert.strictEqual(prev.disabled, false);
        assert.strictEqual(next.disabled, true);
    });

    it("leaves both buttons enabled when idx is in the middle", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "1";
        updateFrameIndicator(card, [
            { label: "a" },
            { label: "b" },
            { label: "c" },
        ]);
        const prev = card.querySelector<HTMLButtonElement>(".frame-prev")!;
        const next = card.querySelector<HTMLButtonElement>(".frame-next")!;
        assert.strictEqual(prev.disabled, false);
        assert.strictEqual(next.disabled, false);
    });

    it("renders '… · —' when the capture at idx has an empty label", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "0";
        updateFrameIndicator(card, [{ label: "" }, { label: "b" }]);
        const indicator = card.querySelector<HTMLElement>(".frame-indicator")!;
        assert.strictEqual(indicator.textContent, "1 / 2 · —");
    });

    it("is a silent no-op when there is no .frame-indicator on the card", () => {
        const card = buildCard();
        // No controls attached at all.
        card.dataset.currentIndex = "0";
        assert.doesNotThrow(() =>
            updateFrameIndicator(card, [{ label: "500ms" }]),
        );
        assert.strictEqual(
            card.querySelector(".frame-indicator"),
            null,
            "no indicator should appear",
        );
    });

    it("is a silent no-op for undefined captures", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "0";
        const indicator = card.querySelector<HTMLElement>(".frame-indicator")!;
        const before = indicator.textContent;
        assert.doesNotThrow(() => updateFrameIndicator(card, undefined));
        assert.strictEqual(indicator.textContent, before);
    });

    it("is a silent no-op for an empty captures array", () => {
        const card = buildCard();
        buildAndAttach(card);
        card.dataset.currentIndex = "0";
        const indicator = card.querySelector<HTMLElement>(".frame-indicator")!;
        const before = indicator.textContent;
        assert.doesNotThrow(() => updateFrameIndicator(card, []));
        assert.strictEqual(indicator.textContent, before);
    });
});
