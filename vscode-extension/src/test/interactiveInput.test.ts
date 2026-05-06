// Streaming-mode coverage for the live-preview wheel and pointer
// handlers. The streaming painter (`streamingPainter.ts`) hides the
// legacy `<img>` and paints frames into a `<canvas class="stream-canvas">`.
// Both handler families must follow that swap, otherwise input never
// reaches the daemon (rotary scroll, click, drag) and the user's gesture
// is silently eaten by `evt.preventDefault()`.

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

/** Deterministic stub for `requestAnimationFrame`: queues callbacks until
 *  `flushRaf()` runs them. The pointermove coalescer dispatches via rAF,
 *  so tests need to control when the flush fires to assert the
 *  before/after states (otherwise happy-dom's real rAF would race the
 *  assertions). Restored after each test. */
let pendingRaf: FrameRequestCallback[] = [];
let originalRaf: typeof globalThis.requestAnimationFrame | undefined;
function installRafStub(): void {
    originalRaf = globalThis.requestAnimationFrame;
    pendingRaf = [];
    globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) => {
        pendingRaf.push(cb);
        return pendingRaf.length;
    }) as typeof globalThis.requestAnimationFrame;
}
function flushRaf(): void {
    const cbs = pendingRaf;
    pendingRaf = [];
    for (const cb of cbs) cb(performance.now());
}
function restoreRaf(): void {
    if (originalRaf) globalThis.requestAnimationFrame = originalRaf;
    originalRaf = undefined;
    pendingRaf = [];
}

afterEach(() => {
    restoreRaf();
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
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        // Canvas displayed at 100×75 CSS pixels at the page origin.
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
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
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
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
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        let live = true;
        attachInteractiveInputHandlers(card, {
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

function pointerEvent(
    type: string,
    opts: {
        clientX: number;
        clientY: number;
        pointerId?: number;
        button?: number;
    },
): PointerEvent {
    return new PointerEvent(type, {
        clientX: opts.clientX,
        clientY: opts.clientY,
        pointerId: opts.pointerId ?? 1,
        button: opts.button ?? 0,
        bubbles: true,
        cancelable: true,
    });
}

describe("attachInteractiveInputHandlers pointer — streaming mode", () => {
    it("forwards a click on the streaming canvas to the daemon", () => {
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        // Canvas displayed at 100×75 CSS pixels at the page origin.
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        canvas.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 50, clientY: 25 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointerup", { clientX: 50, clientY: 25 }),
        );

        assert.strictEqual(posted.length, 1);
        assert.deepStrictEqual(posted[0], {
            command: "recordInteractiveInput",
            previewId: "p1",
            kind: "click",
            // 50 CSS px * (400 nat / 100 css) = 200; 25 * (300/75) = 100.
            pixelX: 200,
            pixelY: 100,
            imageWidth: 400,
            imageHeight: 300,
            scrollDeltaY: undefined,
        });
    });

    it("forwards a drag on the streaming canvas as pointerDown + pointerMove + pointerUp", () => {
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        canvas.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 10, clientY: 10 }),
        );
        // Move past the 4-CSS-px drag threshold (Math.hypot >= 4).
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 20, clientY: 20 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointerup", { clientX: 20, clientY: 20 }),
        );

        const kinds = posted.map((m) => m["kind"]);
        assert.deepStrictEqual(kinds, [
            "pointerDown",
            "pointerMove",
            "pointerUp",
        ]);
        // Every event carries the canvas's natural-pixel dims.
        for (const m of posted) {
            assert.strictEqual(m["imageWidth"], 400);
            assert.strictEqual(m["imageHeight"], 300);
            assert.strictEqual(m["previewId"], "p1");
        }
    });

    it("ignores pointerdowns on chrome (target ≠ surface) so the stop button keeps its click", () => {
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const container = card.querySelector(".image-container")!;
        const stopBtn = document.createElement("button");
        stopBtn.className = "card-live-stop-btn";
        container.appendChild(stopBtn);
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        const evt = pointerEvent("pointerdown", { clientX: 50, clientY: 25 });
        stopBtn.dispatchEvent(evt);

        // Chrome event — handler must not consume it (the button's own click
        // handler depends on default behavior reaching it).
        assert.strictEqual(posted.length, 0);
        assert.strictEqual(evt.defaultPrevented, false);
    });

    it("still works against the legacy <img> when the streaming canvas isn't there yet", () => {
        const { card, img } = buildLiveCard("p1");
        Object.defineProperty(img, "naturalWidth", {
            configurable: true,
            value: 400,
        });
        Object.defineProperty(img, "naturalHeight", {
            configurable: true,
            value: 300,
        });
        stubBoundingRect(img, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        img.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 50, clientY: 25 }),
        );
        img.dispatchEvent(
            pointerEvent("pointerup", { clientX: 50, clientY: 25 }),
        );

        assert.strictEqual(posted.length, 1);
        assert.strictEqual(posted[0]["kind"], "click");
        assert.strictEqual(posted[0]["imageWidth"], 400);
    });

    it("holds the pointerdown surface across a streaming swap mid-drag", () => {
        const { card, img } = buildLiveCard("p1");
        // Start with the legacy img surface.
        Object.defineProperty(img, "naturalWidth", {
            configurable: true,
            value: 200,
        });
        Object.defineProperty(img, "naturalHeight", {
            configurable: true,
            value: 100,
        });
        stubBoundingRect(img, { width: 100, height: 50, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        img.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 10, clientY: 10 }),
        );
        // Streaming painter attaches mid-gesture (different natural dims).
        const canvas = attachStreamCanvas(card, 800, 400);
        stubBoundingRect(canvas, { width: 100, height: 50, left: 0, top: 0 });

        img.dispatchEvent(
            pointerEvent("pointermove", { clientX: 30, clientY: 30 }),
        );
        img.dispatchEvent(
            pointerEvent("pointerup", { clientX: 30, clientY: 30 }),
        );

        // All forwarded events must carry the surface dims captured at
        // pointerdown (the img's 200×100), not the canvas's 800×400 — otherwise
        // the daemon would receive coords in two different spaces inside one
        // gesture.
        assert.ok(posted.length >= 2);
        for (const m of posted) {
            assert.strictEqual(m["imageWidth"], 200);
            assert.strictEqual(m["imageHeight"], 100);
        }
        // Touch the canvas variable so the lint check sees the swap happened.
        assert.strictEqual(canvas.width, 800);
    });

    it("does nothing on pointerdown when the predicate flips off", () => {
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        let live = true;
        attachInteractiveInputHandlers(card, {
            isLive: () => live,
            vscode: api,
        });
        live = false;

        const evt = pointerEvent("pointerdown", { clientX: 50, clientY: 25 });
        canvas.dispatchEvent(evt);

        assert.strictEqual(posted.length, 0);
        assert.strictEqual(evt.defaultPrevented, false);
    });
});

describe("attachInteractiveInputHandlers pointermove — rAF coalescing", () => {
    it("collapses a burst of pointermoves into one daemon post per rAF tick", () => {
        installRafStub();
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        canvas.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 10, clientY: 10 }),
        );
        // First crossing the 4-CSS-px threshold flips `dragging` and posts
        // pointerDown synchronously.
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 20, clientY: 10 }),
        );
        // Three more moves arrive before the rAF fires — the painter only
        // consumes one frame per rAF, so one post per rAF is what the
        // daemon should see too.
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 30, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 40, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 50, clientY: 10 }),
        );

        // pointerDown went through immediately, but no pointerMove yet.
        assert.deepStrictEqual(
            posted.map((m) => m["kind"]),
            ["pointerDown"],
        );

        flushRaf();

        // After the rAF flush, exactly one pointerMove is posted carrying
        // the latest position (50,10 in CSS → 200,40 in natural pixels).
        assert.deepStrictEqual(
            posted.map((m) => m["kind"]),
            ["pointerDown", "pointerMove"],
        );
        const move = posted[1];
        assert.strictEqual(move["pixelX"], 200);
        assert.strictEqual(move["pixelY"], 40);
    });

    it("schedules a fresh rAF for moves that arrive after the previous flush", () => {
        installRafStub();
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        canvas.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 10, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 20, clientY: 10 }),
        );
        flushRaf();
        // Second burst — must trigger a new rAF, not piggyback on a stale flag.
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 60, clientY: 10 }),
        );
        flushRaf();

        const moves = posted.filter((m) => m["kind"] === "pointerMove");
        assert.strictEqual(moves.length, 2);
        assert.strictEqual(moves[0]["pixelX"], 80); // 20 css → 80 nat
        assert.strictEqual(moves[1]["pixelX"], 240); // 60 css → 240 nat
    });

    it("flushes any pending coalesced move on pointerup so the gesture's tail isn't dropped", () => {
        installRafStub();
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        canvas.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 10, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 20, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 30, clientY: 10 }),
        );
        // Pointerup before the rAF would have fired — without an explicit
        // flush the daemon would never see the (30,10) position.
        canvas.dispatchEvent(
            pointerEvent("pointerup", { clientX: 35, clientY: 10 }),
        );

        const kinds = posted.map((m) => m["kind"]);
        assert.deepStrictEqual(kinds, [
            "pointerDown",
            "pointerMove",
            "pointerUp",
        ]);
        const move = posted[1];
        assert.strictEqual(move["pixelX"], 120); // 30 css → 120 nat
        const up = posted[2];
        assert.strictEqual(up["pixelX"], 140); // 35 css → 140 nat

        // The deferred rAF, if any, must not double-post once it fires.
        flushRaf();
        const movesAfter = posted.filter((m) => m["kind"] === "pointerMove");
        assert.strictEqual(movesAfter.length, 1);
    });

    it("drops pending moves on pointercancel without posting them", () => {
        installRafStub();
        const { card } = buildLiveCard("p1");
        const canvas = attachStreamCanvas(card, 400, 300);
        stubBoundingRect(canvas, { width: 100, height: 75, left: 0, top: 0 });
        const { posted, api } = createVscode();
        attachInteractiveInputHandlers(card, {
            isLive: () => true,
            vscode: api,
        });

        canvas.dispatchEvent(
            pointerEvent("pointerdown", { clientX: 10, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointermove", { clientX: 20, clientY: 10 }),
        );
        canvas.dispatchEvent(
            pointerEvent("pointercancel", { clientX: 20, clientY: 10 }),
        );
        flushRaf();

        // Only the pointerDown made it; the pending coalesced move was
        // discarded along with the cancelled gesture.
        assert.deepStrictEqual(
            posted.map((m) => m["kind"]),
            ["pointerDown"],
        );
    });
});
