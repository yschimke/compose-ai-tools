// DOM-feature tests for `LoadingOverlay` (the two-stage loading
// escalation lifted out of `behavior.ts` into a narrow-deps file).
//
// Pins the contract:
//
//   - `markAll` stamps `.loading-overlay.minimal` onto every
//     `.preview-card .image-container` that has existing pixels.
//   - Cards still showing the bare skeleton (no `<img>`) are skipped
//     so we don't double-cover loading states.
//   - Cards that already have an overlay don't get a duplicate.
//   - 500ms after `markAll` any still-`.minimal` overlay is promoted
//     to `.subtle`; overlays cleared in the meantime stay gone.
//   - `cancel` cuts the pending escalation timer so a subsequent
//     external clear doesn't re-introduce the dim+blur stage.
//
// Timer determinism: we replace `setTimeout` / `clearTimeout` on the
// happy-dom-installed globalThis with a hand-rolled queue. The class
// captures whichever callable lives on `globalThis.setTimeout` at
// `markAll` time, so the swap takes effect without poking module
// internals.

import * as assert from "assert";
import { LoadingOverlay } from "../webview/preview/loadingOverlay";

interface ScheduledTimer {
    id: number;
    delay: number;
    fn: () => void;
}

const realSetTimeout = globalThis.setTimeout;
const realClearTimeout = globalThis.clearTimeout;
let pending: ScheduledTimer[] = [];
let nextTimerId = 1;

function installFakeTimers(): void {
    pending = [];
    nextTimerId = 1;
    (globalThis as unknown as { setTimeout: unknown }).setTimeout = ((
        fn: () => void,
        delay: number,
    ) => {
        const id = nextTimerId++;
        pending.push({ id, delay, fn });
        return id as unknown as ReturnType<typeof setTimeout>;
    }) as typeof setTimeout;
    (globalThis as unknown as { clearTimeout: unknown }).clearTimeout = ((
        id: number,
    ) => {
        pending = pending.filter((t) => t.id !== id);
    }) as typeof clearTimeout;
}

function restoreTimers(): void {
    (globalThis as unknown as { setTimeout: typeof setTimeout }).setTimeout =
        realSetTimeout;
    (
        globalThis as unknown as { clearTimeout: typeof clearTimeout }
    ).clearTimeout = realClearTimeout;
    pending = [];
}

/** Run every queued timer's callback (escalation timers don't re-arm,
 *  so a single drain matches a "clock advanced past 500 ms" jump). */
function flushTimers(): void {
    const drained = pending;
    pending = [];
    for (const t of drained) t.fn();
}

/** Build a `<div class="preview-card">` whose `.image-container`
 *  already has a rendered `<img>` — i.e. the card has pixels worth
 *  covering with a loading overlay. */
function buildCardWithImage(previewId = "com.example.A"): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    const img = document.createElement("img");
    img.src = "data:image/png;base64,AAAA";
    container.appendChild(img);
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

/** Same shape as above but the `.image-container` only holds a
 *  skeleton — `markAll` should skip these. */
function buildCardWithSkeletonOnly(previewId = "com.example.B"): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    const skel = document.createElement("div");
    skel.className = "skeleton";
    container.appendChild(skel);
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

describe("LoadingOverlay", () => {
    beforeEach(() => {
        installFakeTimers();
    });

    afterEach(() => {
        restoreTimers();
        document.body.innerHTML = "";
    });

    describe("markAll", () => {
        it("stamps .loading-overlay.minimal onto every image-container with pixels", () => {
            const a = buildCardWithImage("com.example.A");
            const b = buildCardWithImage("com.example.B");

            new LoadingOverlay().markAll();

            for (const card of [a, b]) {
                const overlay = card.querySelector(".loading-overlay");
                assert.ok(overlay, "expected an overlay to be appended");
                assert.strictEqual(
                    overlay!.classList.contains("minimal"),
                    true,
                );
                assert.strictEqual(
                    overlay!.classList.contains("subtle"),
                    false,
                );
                assert.ok(
                    overlay!.querySelector(".spinner"),
                    "overlay should contain a .spinner",
                );
            }
        });

        it("skips cards that only have a skeleton (no pixels yet)", () => {
            const card = buildCardWithSkeletonOnly();

            new LoadingOverlay().markAll();

            assert.strictEqual(card.querySelector(".loading-overlay"), null);
        });

        it("does not duplicate the overlay when called twice on the same card", () => {
            const card = buildCardWithImage();
            const overlay = new LoadingOverlay();

            overlay.markAll();
            overlay.markAll();

            assert.strictEqual(
                card.querySelectorAll(".loading-overlay").length,
                1,
            );
        });

        it("skips cards without an .image-container", () => {
            const card = document.createElement("div");
            card.className = "preview-card";
            card.dataset.previewId = "com.example.C";
            document.body.appendChild(card);

            assert.doesNotThrow(() => new LoadingOverlay().markAll());
            assert.strictEqual(card.querySelector(".loading-overlay"), null);
        });

        it("escalates remaining minimal overlays to subtle after the timer fires", () => {
            const card = buildCardWithImage();

            new LoadingOverlay().markAll();
            const overlay = card.querySelector(".loading-overlay")!;
            assert.strictEqual(overlay.classList.contains("minimal"), true);

            flushTimers();

            assert.strictEqual(overlay.classList.contains("minimal"), false);
            assert.strictEqual(overlay.classList.contains("subtle"), true);
        });

        it("does not promote overlays that were removed before the timer fires", () => {
            // Mirrors the production flow: `updateImage` lands in the
            // 0–500 ms window and tears down the overlay before the
            // escalation timer runs.
            const card = buildCardWithImage();

            new LoadingOverlay().markAll();
            card.querySelector(".loading-overlay")!.remove();
            flushTimers();

            assert.strictEqual(card.querySelector(".loading-overlay"), null);
            assert.strictEqual(card.querySelector(".subtle"), null);
        });

        it("schedules exactly one escalation timer per markAll invocation", () => {
            buildCardWithImage("com.example.A");
            const overlay = new LoadingOverlay();

            overlay.markAll();
            assert.strictEqual(pending.length, 1);
            // A second markAll re-arms the timer rather than stacking.
            overlay.markAll();
            assert.strictEqual(pending.length, 1);
        });

        it("schedules the escalation at 500 ms", () => {
            buildCardWithImage();
            new LoadingOverlay().markAll();

            assert.strictEqual(pending.length, 1);
            assert.strictEqual(pending[0]!.delay, 500);
        });
    });

    describe("cancel", () => {
        it("cuts the pending escalation timer so subtle is never applied", () => {
            const card = buildCardWithImage();
            const overlay = new LoadingOverlay();

            overlay.markAll();
            assert.strictEqual(pending.length, 1);
            overlay.cancel();
            assert.strictEqual(pending.length, 0);

            flushTimers(); // would have promoted to subtle if still armed
            const dom = card.querySelector(".loading-overlay")!;
            assert.strictEqual(dom.classList.contains("minimal"), true);
            assert.strictEqual(dom.classList.contains("subtle"), false);
        });

        it("is a no-op when nothing is scheduled", () => {
            const overlay = new LoadingOverlay();
            assert.doesNotThrow(() => overlay.cancel());
            assert.doesNotThrow(() => overlay.cancel());
        });

        it("can be called repeatedly without re-introducing the timer", () => {
            buildCardWithImage();
            const overlay = new LoadingOverlay();

            overlay.markAll();
            overlay.cancel();
            overlay.cancel();
            assert.strictEqual(pending.length, 0);
        });
    });
});
