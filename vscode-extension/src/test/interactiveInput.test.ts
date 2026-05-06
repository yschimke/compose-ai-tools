// Streaming-mode coverage for the live-preview wheel handler. The
// streaming painter (`streamingPainter.ts`) hides the legacy `<img>` and
// paints frames into a `<canvas class="stream-canvas">`. The wheel
// handler must follow that swap, otherwise rotary scroll never reaches
// the daemon and `evt.preventDefault()` silently eats the user's input.

import * as assert from "assert";
import {
    attachInteractiveInputHandlers,
    liveRenderSurface,
} from "../webview/preview/interactiveInput";

interface PostedMessage {
    [key: string]: unknown;
}

interface FakeRect {
    width: number;
    height: number;
    left: number;
    top: number;
}

/** happy-dom's layout engine returns 0×0 rects for everything, so each
 *  test pins a synthetic rect on the surface element it's exercising. */
function stubBoundingRect(el: Element, rect: FakeRect): void {
    Object.defineProperty(el, "getBoundingClientRect", {
        configurable: true,
        value: () => ({
            width: rect.width,
            height: rect.height,
            left: rect.left,
            top: rect.top,
            right: rect.left + rect.width,
            bottom: rect.top + rect.height,
            x: rect.left,
            y: rect.top,
            toJSON: () => ({}),
        }),
    });
}

/** happy-dom's `new WheelEvent("wheel", { clientX, clientY })` drops the
 *  pointer coords from the init dict (only `deltaY` survives). Patch them
 *  on after construction so the handler sees realistic values. */
function makeWheelEvent(opts: {
    clientX: number;
    clientY: number;
    deltaY: number;
}): WheelEvent {
    const evt = new WheelEvent("wheel", {
        deltaY: opts.deltaY,
        bubbles: true,
        cancelable: true,
    });
    Object.defineProperty(evt, "clientX", {
        configurable: true,
        value: opts.clientX,
    });
    Object.defineProperty(evt, "clientY", {
        configurable: true,
        value: opts.clientY,
    });
    return evt;
}

function buildLiveCard(previewId: string): {
    card: HTMLDivElement;
    img: HTMLImageElement;
} {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    const img = document.createElement("img");
    img.className = "preview-image";
    container.appendChild(img);
    card.appendChild(container);
    document.body.appendChild(card);
    return { card, img };
}

/** Match `streamingPainter.attach` — hides the `<img>` and appends a
 *  sized `<canvas class="stream-canvas">` inside `.image-container`. */
function attachStreamCanvas(
    card: HTMLElement,
    width: number,
    height: number,
): HTMLCanvasElement {
    const container = card.querySelector(".image-container")!;
    const img = container.querySelector<HTMLImageElement>("img");
    if (img) img.style.display = "none";
    const canvas = document.createElement("canvas");
    canvas.className = "stream-canvas";
    canvas.width = width;
    canvas.height = height;
    container.appendChild(canvas);
    return canvas;
}

function createVscode(): {
    posted: PostedMessage[];
    api: {
        postMessage(msg: unknown): void;
        getState(): undefined;
        setState(): void;
    };
} {
    const posted: PostedMessage[] = [];
    return {
        posted,
        api: {
            postMessage(msg: unknown) {
                posted.push(msg as PostedMessage);
            },
            getState() {
                return undefined;
            },
            setState() {
                /* noop */
            },
        },
    };
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("liveRenderSurface", () => {
    it("returns the streaming <canvas> when present and sized", () => {
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 480, 320);
        const surface = liveRenderSurface(card);
        assert.ok(surface);
        assert.strictEqual(surface.el, canvas);
        assert.strictEqual(surface.naturalWidth, 480);
        assert.strictEqual(surface.naturalHeight, 320);
    });

    it("falls back to the legacy <img> when no streaming canvas is attached", () => {
        const { card, img } = buildLiveCard("p1");
        Object.defineProperty(img, "naturalWidth", {
            configurable: true,
            value: 200,
        });
        Object.defineProperty(img, "naturalHeight", {
            configurable: true,
            value: 100,
        });
        const surface = liveRenderSurface(card);
        assert.ok(surface);
        assert.strictEqual(surface.el, img);
        assert.strictEqual(surface.naturalWidth, 200);
        assert.strictEqual(surface.naturalHeight, 100);
    });

    it("returns the canvas even at the painter's initial 1×1 placeholder size", () => {
        // streamingPainter sets canvas.width/height = 1 immediately on
        // attach, so the canvas wins from the very first event — even
        // before the first decoded frame swaps the real dimensions in.
        const { card, img } = buildLiveCard("p1");
        Object.defineProperty(img, "naturalWidth", {
            configurable: true,
            value: 200,
        });
        Object.defineProperty(img, "naturalHeight", {
            configurable: true,
            value: 100,
        });
        const canvas = attachStreamCanvas(card, 1, 1);
        const surface = liveRenderSurface(card);
        assert.ok(surface);
        assert.strictEqual(surface.el, canvas);
    });

    it("returns null when neither surface has natural dimensions yet", () => {
        const { card } = buildLiveCard("p1");
        assert.strictEqual(liveRenderSurface(card), null);
    });
});

describe("attachInteractiveInputHandlers wheel — streaming mode", () => {
    it("forwards rotaryScroll using the streaming canvas's natural-pixel dims", () => {
        const { card, img } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        // Canvas displayed at 100×75 CSS pixels at the page origin.
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, img, {
            isLive: () => true,
            vscode: api,
        });
        const evt = makeWheelEvent({ clientX: 50, clientY: 25, deltaY: 120 });
        card.dispatchEvent(evt);
        assert.strictEqual(posted.length, 1);
        assert.deepStrictEqual(posted[0], {
            command: "recordInteractiveInput",
            previewId: "p1",
            kind: "rotaryScroll",
            // 50 CSS px * (400 nat / 100 css) = 200; 25 CSS px * (300 nat / 75 css) = 100.
            pixelX: 200,
            pixelY: 100,
            imageWidth: 400,
            imageHeight: 300,
            scrollDeltaY: 120,
        });
        assert.strictEqual(evt.defaultPrevented, true);
    });

    it("still consumes the wheel when the cursor sits over card chrome (outside the surface)", () => {
        const { card, img } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, img, {
            isLive: () => true,
            vscode: api,
        });
        const evt = makeWheelEvent({ clientX: 500, clientY: 500, deltaY: 120 });
        card.dispatchEvent(evt);
        // Outside the surface — no rotaryScroll posted, but wheel still consumed
        // so enthusiastic scrolling can't push the live preview out of view.
        assert.strictEqual(posted.length, 0);
        assert.strictEqual(evt.defaultPrevented, true);
    });

    it("does nothing when the card is no longer live (predicate flips off)", () => {
        const { card, img } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        let live = true;
        attachInteractiveInputHandlers(card, img, {
            isLive: () => live,
            vscode: api,
        });
        live = false;
        const evt = makeWheelEvent({ clientX: 50, clientY: 25, deltaY: 120 });
        card.dispatchEvent(evt);
        assert.strictEqual(posted.length, 0);
        assert.strictEqual(evt.defaultPrevented, false);
    });
});
