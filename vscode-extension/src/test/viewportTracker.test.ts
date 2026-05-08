// DOM-feature tests for `ViewportTracker` (the IntersectionObserver +
// scroll-velocity EMA cluster lifted out of `behavior.ts` into a
// narrow-deps file).
//
// happy-dom ships an `IntersectionObserver` constructor but does not
// drive entries from layout, so we stub the global before constructing
// the tracker, capture the callback the tracker hands in, and call it
// manually with synthetic entries. The 120 ms publish debounce uses
// `setTimeout` — same fake-timer trick as `loadingOverlay.test.ts`.
//
// Pinned contract:
//
//   - `observe` forwards each card to the IO; entering the viewport
//     adds the id to the published `visible` array, leaving removes
//     it and fires `onCardLeftViewport(id)` exactly once.
//   - Publishes are coalesced through a 120 ms `setTimeout` so a
//     burst of intersection / scroll events fans out one
//     `viewportUpdated` post, not one per event.
//   - `predictNextIds` returns up to 4 cards ahead of (or behind) the
//     last (first) visible card based on signed scroll velocity, and
//     skips `.filtered-out` cards.
//   - `forget` removes the id from the live set without firing
//     `onCardLeftViewport` again, and unobserves the card.
//   - `unobserveAll` clears the live set and unobserves every
//     `.preview-card`.
//
// IntersectionObserver shape pin (regression guard for happy-dom): the
// constructor is invoked with a `{ root, rootMargin, threshold }` init
// matching the production call.

import * as assert from "assert";
import { ViewportTracker } from "../webview/preview/viewportTracker";
import type { VsCodeApi } from "../webview/shared/vscode";

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

function flushTimers(): void {
    const drained = pending;
    pending = [];
    for (const t of drained) t.fn();
}

interface IOInit {
    root: Element | Document | null;
    rootMargin: string;
    threshold: number | number[];
}

interface FakeObserver {
    init: IOInit;
    observed: Set<Element>;
    callback: IntersectionObserverCallback;
    fire(entries: Partial<IntersectionObserverEntry>[]): void;
}

let lastObserver: FakeObserver | null = null;
let realIntersectionObserver: typeof IntersectionObserver | undefined;

function installFakeIO(): void {
    realIntersectionObserver = (
        globalThis as unknown as {
            IntersectionObserver?: typeof IntersectionObserver;
        }
    ).IntersectionObserver;
    class FakeIO implements Partial<IntersectionObserver> {
        readonly observed = new Set<Element>();
        readonly init: IOInit;
        readonly callback: IntersectionObserverCallback;
        constructor(cb: IntersectionObserverCallback, init: IOInit) {
            this.callback = cb;
            this.init = init;
            lastObserver = {
                init,
                observed: this.observed,
                callback: cb,
                fire: (entries) => {
                    this.callback(
                        entries as IntersectionObserverEntry[],
                        this as unknown as IntersectionObserver,
                    );
                },
            };
        }
        observe(el: Element): void {
            this.observed.add(el);
        }
        unobserve(el: Element): void {
            this.observed.delete(el);
        }
        disconnect(): void {
            this.observed.clear();
        }
        takeRecords(): IntersectionObserverEntry[] {
            return [];
        }
    }
    (
        globalThis as unknown as { IntersectionObserver: unknown }
    ).IntersectionObserver = FakeIO as unknown as typeof IntersectionObserver;
}

function restoreIO(): void {
    (
        globalThis as unknown as { IntersectionObserver: unknown }
    ).IntersectionObserver = realIntersectionObserver;
    lastObserver = null;
}

/** Build a `<div class="preview-card" data-preview-id="…">` and append
 *  it to `document.body`. Cards are added in DOM order so
 *  `predictNextIds` traversal matches their construction order. */
function buildCard(
    previewId: string,
    opts: { filteredOut?: boolean } = {},
): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    if (opts.filteredOut) card.classList.add("filtered-out");
    card.dataset.previewId = previewId;
    document.body.appendChild(card);
    return card;
}

function fakeEntry(
    target: Element,
    isIntersecting: boolean,
): Partial<IntersectionObserverEntry> {
    return { target, isIntersecting };
}

interface RecordedPost {
    command: string;
    visible: string[];
    predicted: string[];
}

function makeVscode(): { api: VsCodeApi<unknown>; posts: RecordedPost[] } {
    const posts: RecordedPost[] = [];
    const api: VsCodeApi<unknown> = {
        postMessage: (m: unknown) => {
            posts.push(m as RecordedPost);
        },
        getState: () => undefined,
        setState: () => {},
    };
    return { api, posts };
}

describe("ViewportTracker", () => {
    beforeEach(() => {
        installFakeTimers();
        installFakeIO();
    });

    afterEach(() => {
        restoreTimers();
        restoreIO();
        document.body.innerHTML = "";
    });

    it("constructs the IntersectionObserver with the production init bag", () => {
        const { api } = makeVscode();
        new ViewportTracker({ vscode: api, onCardLeftViewport: () => {} });

        assert.ok(lastObserver, "IO should have been constructed");
        assert.strictEqual(lastObserver!.init.root, null);
        assert.strictEqual(lastObserver!.init.rootMargin, "0px");
        assert.strictEqual(lastObserver!.init.threshold, 0.1);
    });

    it("forwards observe(card) to the underlying IntersectionObserver", () => {
        const { api } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: () => {},
        });
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");

        tracker.observe(a);
        tracker.observe(b);

        assert.strictEqual(lastObserver!.observed.size, 2);
        assert.ok(lastObserver!.observed.has(a));
        assert.ok(lastObserver!.observed.has(b));
    });

    it("publishes the intersecting set after the 120 ms debounce", () => {
        const { api, posts } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: () => {},
        });
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");
        tracker.observe(a);
        tracker.observe(b);

        lastObserver!.fire([fakeEntry(a, true), fakeEntry(b, true)]);

        // Coalesced — nothing posts until the timer fires.
        assert.strictEqual(posts.length, 0);
        assert.strictEqual(pending.length, 1);
        assert.strictEqual(pending[0]!.delay, 120);

        flushTimers();

        assert.strictEqual(posts.length, 1);
        assert.strictEqual(posts[0]!.command, "viewportUpdated");
        assert.deepStrictEqual(posts[0]!.visible.slice().sort(), [
            "com.example.A",
            "com.example.B",
        ]);
        assert.deepStrictEqual(posts[0]!.predicted, []);
    });

    it("coalesces a burst of intersection events into a single publish", () => {
        const { api, posts } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: () => {},
        });
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");
        tracker.observe(a);
        tracker.observe(b);

        lastObserver!.fire([fakeEntry(a, true)]);
        lastObserver!.fire([fakeEntry(b, true)]);
        lastObserver!.fire([fakeEntry(a, false)]);

        assert.strictEqual(
            pending.length,
            1,
            "all three bursts share one timer",
        );
        flushTimers();

        assert.strictEqual(posts.length, 1);
        assert.deepStrictEqual(posts[0]!.visible, ["com.example.B"]);
    });

    it("fires onCardLeftViewport on every isIntersecting=false entry", () => {
        // Pin current behaviour: the callback fires per `false` entry,
        // not per visible→hidden transition. The host (live-state
        // controller) is what dedups by checking whether the id is in
        // its live set — a redundant call is cheap and idempotent.
        const left: string[] = [];
        const { api } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: (id) => left.push(id),
        });
        const a = buildCard("com.example.A");
        tracker.observe(a);

        lastObserver!.fire([fakeEntry(a, true)]);
        lastObserver!.fire([fakeEntry(a, false)]);
        lastObserver!.fire([fakeEntry(a, false)]);

        assert.deepStrictEqual(left, ["com.example.A", "com.example.A"]);
    });

    it("ignores entries whose target has no data-preview-id", () => {
        const { api, posts } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: () => {},
        });
        const orphan = document.createElement("div");
        orphan.className = "preview-card";
        document.body.appendChild(orphan);
        tracker.observe(orphan);

        lastObserver!.fire([fakeEntry(orphan, true)]);
        flushTimers();

        // A publish still happens (timer was scheduled), but visible is empty.
        assert.strictEqual(posts.length, 1);
        assert.deepStrictEqual(posts[0]!.visible, []);
    });

    it("forget(previewId, card) drops the id from the live set without firing onCardLeftViewport", () => {
        const left: string[] = [];
        const { api, posts } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: (id) => left.push(id),
        });
        const a = buildCard("com.example.A");
        tracker.observe(a);
        lastObserver!.fire([fakeEntry(a, true)]);
        flushTimers();
        assert.deepStrictEqual(posts[posts.length - 1]!.visible, [
            "com.example.A",
        ]);

        tracker.forget("com.example.A", a);
        // forget bypasses the IO callback path, so the leave callback
        // must not fire (the card was deliberately removed from the
        // grid, not scrolled out of view).
        assert.deepStrictEqual(left, []);
        assert.strictEqual(lastObserver!.observed.has(a), false);

        // Subsequent publishes should no longer list the forgotten id.
        lastObserver!.fire([fakeEntry(buildCard("com.example.B"), true)]);
        flushTimers();
        const last = posts[posts.length - 1]!;
        assert.deepStrictEqual(last.visible, ["com.example.B"]);
    });

    it("unobserveAll clears the intersecting set and unhooks every preview card", () => {
        const { api, posts } = makeVscode();
        const tracker = new ViewportTracker({
            vscode: api,
            onCardLeftViewport: () => {},
        });
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");
        tracker.observe(a);
        tracker.observe(b);
        lastObserver!.fire([fakeEntry(a, true), fakeEntry(b, true)]);

        tracker.unobserveAll();
        assert.strictEqual(lastObserver!.observed.size, 0);

        // Drain the publish that was already armed by the pre-clear
        // intersection burst so the next assertion sees a clean slate.
        flushTimers();
        const beforeCount = posts.length;

        // After unobserveAll the live set is empty, so any subsequent
        // publish should carry no `visible` ids.
        lastObserver!.fire([fakeEntry(a, false)]);
        flushTimers();
        assert.strictEqual(posts.length, beforeCount + 1);
        assert.deepStrictEqual(posts[posts.length - 1]!.visible, []);
    });

    describe("predictNextIds", () => {
        function buildGrid(
            ids: string[],
            opts: { filteredIndices?: number[] } = {},
        ): HTMLElement[] {
            const cards: HTMLElement[] = [];
            for (let i = 0; i < ids.length; i++) {
                cards.push(
                    buildCard(ids[i]!, {
                        filteredOut: opts.filteredIndices?.includes(i) ?? false,
                    }),
                );
            }
            return cards;
        }

        it("returns empty when scroll velocity is below the 0.05 px/ms threshold", () => {
            const { api, posts } = makeVscode();
            const tracker = new ViewportTracker({
                vscode: api,
                onCardLeftViewport: () => {},
            });
            const cards = buildGrid(["A", "B", "C", "D"]);
            for (const c of cards) tracker.observe(c);
            lastObserver!.fire([fakeEntry(cards[0]!, true)]);
            // No scroll events → velocity stays at 0 → no prediction.
            flushTimers();

            assert.deepStrictEqual(posts[posts.length - 1]!.predicted, []);
        });

        it("predicts the next four DOM-order cards when scrolling down", () => {
            const { api, posts } = makeVscode();
            const tracker = new ViewportTracker({
                vscode: api,
                onCardLeftViewport: () => {},
            });
            const cards = buildGrid(["A", "B", "C", "D", "E", "F", "G"]);
            for (const c of cards) tracker.observe(c);
            // B and C are in the viewport.
            lastObserver!.fire([
                fakeEntry(cards[1]!, true),
                fakeEntry(cards[2]!, true),
            ]);
            // Force positive velocity by stubbing the EMA sample.
            // Two scroll events: y goes from 0 to 100 over ~10 ms.
            Object.defineProperty(window, "scrollY", {
                configurable: true,
                value: 0,
            });
            document.dispatchEvent(new Event("scroll"));
            Object.defineProperty(window, "scrollY", {
                configurable: true,
                value: 200,
            });
            document.dispatchEvent(new Event("scroll"));

            flushTimers();
            const last = posts[posts.length - 1]!;
            assert.deepStrictEqual(last.visible.slice().sort(), ["B", "C"]);
            // Predicts D, E, F, G (PREDICT_AHEAD = 4) — i.e. the four
            // DOM-order cards immediately after the lowest visible (C).
            assert.deepStrictEqual(last.predicted, ["D", "E", "F", "G"]);
        });

        it("predicts upward when scrolling up", () => {
            const { api, posts } = makeVscode();
            const tracker = new ViewportTracker({
                vscode: api,
                onCardLeftViewport: () => {},
            });
            const cards = buildGrid(["A", "B", "C", "D", "E", "F"]);
            for (const c of cards) tracker.observe(c);
            // E and F are in the viewport.
            lastObserver!.fire([
                fakeEntry(cards[4]!, true),
                fakeEntry(cards[5]!, true),
            ]);
            // Negative velocity: scrollY goes from 500 to 100.
            Object.defineProperty(window, "scrollY", {
                configurable: true,
                value: 500,
            });
            document.dispatchEvent(new Event("scroll"));
            Object.defineProperty(window, "scrollY", {
                configurable: true,
                value: 100,
            });
            document.dispatchEvent(new Event("scroll"));

            flushTimers();
            const last = posts[posts.length - 1]!;
            assert.deepStrictEqual(last.visible.slice().sort(), ["E", "F"]);
            // Predicts D, C, B, A — four cards above the topmost visible (E),
            // in reverse DOM order.
            assert.deepStrictEqual(last.predicted, ["D", "C", "B", "A"]);
        });

        it("skips .filtered-out cards when predicting", () => {
            const { api, posts } = makeVscode();
            const tracker = new ViewportTracker({
                vscode: api,
                onCardLeftViewport: () => {},
            });
            // C is filtered out — it sits between B (visible) and D
            // (predicted). The filter test in `predictNextIds` is on
            // the *whole* card list (the filter strips C from
            // consideration entirely), so D is the next predicted card.
            const cards = buildGrid(["A", "B", "C", "D", "E"], {
                filteredIndices: [2],
            });
            for (const c of cards) tracker.observe(c);
            lastObserver!.fire([fakeEntry(cards[1]!, true)]);
            Object.defineProperty(window, "scrollY", {
                configurable: true,
                value: 0,
            });
            document.dispatchEvent(new Event("scroll"));
            Object.defineProperty(window, "scrollY", {
                configurable: true,
                value: 200,
            });
            document.dispatchEvent(new Event("scroll"));

            flushTimers();
            const last = posts[posts.length - 1]!;
            assert.ok(!last.predicted.includes("C"));
            assert.deepStrictEqual(last.predicted, ["D", "E"]);
        });
    });
});
