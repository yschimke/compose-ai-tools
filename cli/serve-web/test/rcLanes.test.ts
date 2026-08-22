// Behavioural contract for `<cp-rc-lanes>`.
//
// The decisions are pinned next door — `rcRowPlan.test.ts`, `rcRowFilter.test.ts`,
// `pixelDiff.test.ts`. What only the element can answer is the wiring: that it reads the model the
// server inlined, that picking a reference clears and re-observes every row rather than leaving the
// on-screen ones stale, that a row is scored once per pass, and that `format-compare.js` still finds
// the global it calls.
//
// The in-browser measuring path is not exercised here: it needs a real canvas to decode a PNG, and
// happy-dom has none. `diffPixels` is tested directly instead, which is where the arithmetic is.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/RcLanes.js";

/** Rows this observer is told about, so a test can scroll them into view on demand. */
let observed: Set<Element>;
let notify: ((targets: Element[]) => void) | null = null;

class StubObserver {
    constructor(private readonly callback: IntersectionObserverCallback) {
        notify = (targets) =>
            this.callback(
                targets.map((target) => ({
                    target,
                    isIntersecting: true,
                })) as never,
                this as never,
            );
    }
    observe(target: Element): void {
        observed.add(target);
    }
    unobserve(target: Element): void {
        observed.delete(target);
    }
    disconnect(): void {
        observed.clear();
    }
}

const MODEL = {
    threshold: 0.1,
    lanes: [
        { id: "baked", label: "AndroidX Embedded · baked", short: "baked" },
        { id: "android", label: "Android player", short: "android" },
        { id: "cmp", label: "CMP player", short: "cmp" },
    ],
    rows: [
        {
            label: "Button",
            referenceBlank: false,
            lanes: {
                baked: { rendered: true, render: "baked/0.png" },
                android: {
                    rendered: true,
                    render: "android/0.png",
                    diff: "android-diff/0.png",
                    mismatchPct: 1.25,
                    mismatchPx: 4800,
                },
                cmp: { rendered: false, note: "unsupported op: shader" },
            },
        },
        {
            label: "Card",
            referenceBlank: false,
            lanes: {
                baked: { rendered: true, render: "baked/1.png" },
                android: {
                    rendered: true,
                    render: "android/1.png",
                    diff: "android-diff/1.png",
                    mismatchPct: 14,
                    mismatchPx: 90000,
                },
                cmp: { rendered: false, note: "" },
            },
        },
    ],
};

function cells(laneIds: string[]): string {
    return laneIds
        .map(
            (id) =>
                `<td class="cp-rc-cell" data-lane="${id}"><div class="cp-rc-diffslot" hidden></div></td>`,
        )
        .join("");
}

async function mount(search = ""): Promise<void> {
    if (search) window.history.replaceState(null, "", `/compare${search}`);
    else window.history.replaceState(null, "", "/compare");
    document.body.innerHTML = `
      <cp-rc-lanes></cp-rc-lanes>
      <span id="cp-compare-count"></span>
      <section id="cp-rc-lanes">
        <button type="button" data-rc-ref="none" aria-pressed="true">nothing</button>
        <button type="button" data-rc-ref="baked" aria-pressed="false">baked</button>
        <button type="button" data-rc-ref="android" aria-pressed="false">android</button>
        <span id="cp-rc-status"></span>
        <table><tbody>
          <tr class="cp-rc-row" data-row="0" data-hay="button" data-preview-ids="m3.Button">
            <td data-scores></td>${cells(["baked", "android", "cmp"])}
          </tr>
          <tr class="cp-rc-row" data-row="1" data-hay="card" data-preview-ids="m3.Card">
            <td data-scores></td>${cells(["baked", "android", "cmp"])}
          </tr>
        </tbody></table>
        <p id="cp-rc-empty" hidden></p>
        <script type="application/json" id="cp-rc-model">${JSON.stringify(MODEL)}</script>
      </section>`;
    await flush();
}

const rows = () =>
    Array.from(document.querySelectorAll<HTMLElement>(".cp-rc-row"));
const scrollAllIntoView = () =>
    notify?.(rows().filter((row) => observed.has(row)));
const chipsIn = (row: HTMLElement) =>
    Array.from(row.querySelectorAll(".cp-rc-scoreline")).map((line) => [
        line.querySelector(".cp-rc-scorelabel")?.textContent,
        line.querySelector(".cp-rc-score")?.textContent,
        line.querySelector(".cp-rc-score")?.className,
        line.querySelector(".cp-rc-px")?.textContent ?? null,
    ]);
const press = (ref: string) =>
    document.querySelector<HTMLElement>(`[data-rc-ref="${ref}"]`)?.click();

describe("<cp-rc-lanes>", () => {
    let realObserver: typeof IntersectionObserver;

    beforeEach(() => {
        observed = new Set();
        notify = null;
        realObserver = globalThis.IntersectionObserver;
        globalThis.IntersectionObserver = StubObserver as never;
    });

    afterEach(() => {
        globalThis.IntersectionObserver = realObserver;
        delete window.cpRcLanes;
        resetDom();
    });

    it("scores nothing until a reference is picked", async () => {
        await mount();
        assert.equal(observed.size, 0, "no reference, nothing to measure");
        assert.equal(document.getElementById("cp-rc-status")?.textContent, "");
        assert.equal(
            document
                .querySelector('[data-rc-ref="none"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
    });

    it("replays the build's numbers against the baked PNG", async () => {
        await mount();
        press("baked");
        scrollAllIntoView();
        await flush();
        assert.deepEqual(chipsIn(rows()[0]), [
            ["android", "1.25%", "cp-rc-score cp-rc-score--good", "4,800 px"],
            [
                "cmp",
                "unsupported op: shader",
                "cp-rc-score cp-rc-score--na",
                null,
            ],
        ]);
        assert.deepEqual(chipsIn(rows()[1]), [
            ["android", "14.00%", "cp-rc-score cp-rc-score--bad", "90,000 px"],
            ["cmp", "no render", "cp-rc-score cp-rc-score--na", null],
        ]);
    });

    it("shows the build-time diff image beside the lane it belongs to", async () => {
        await mount();
        press("baked");
        scrollAllIntoView();
        await flush();
        const slot = rows()[0].querySelector<HTMLElement>(
            '.cp-rc-cell[data-lane="android"] .cp-rc-diffslot',
        );
        assert.equal(slot?.hidden, false);
        assert.equal(
            slot?.querySelector("img")?.getAttribute("src"),
            "android-diff/0.png",
        );
        assert.equal(
            slot?.querySelector(".cp-rc-difflabel")?.textContent,
            "pixel diff vs baked",
        );
        // The lane that produced nothing gets no slot opened — an empty diff frame would read as
        // "identical" rather than "absent".
        assert.equal(
            rows()[0].querySelector<HTMLElement>(
                '.cp-rc-cell[data-lane="cmp"] .cp-rc-diffslot',
            )?.hidden,
            true,
        );
    });

    it("marks the reference column and says where its numbers came from", async () => {
        await mount();
        press("baked");
        scrollAllIntoView();
        await flush();
        assert.equal(
            rows()[0]
                .querySelector('.cp-rc-cell[data-lane="baked"]')
                ?.classList.contains("is-reference"),
            true,
        );
        assert.equal(
            document.getElementById("cp-rc-status")?.textContent,
            "showing the build-time pixel diffs against the baked render",
        );
        assert.equal(
            document
                .getElementById("cp-rc-lanes")
                ?.getAttribute("data-reference"),
            "baked",
        );
    });

    it("re-observes every row when the reference changes, including on-screen ones", async () => {
        // The bug this is here for: `observe()` on an already-observed target is a no-op, so
        // switching straight from one reference to another left every on-screen row blank until it
        // scrolled out and back. The observer is disconnected first so a fresh callback is queued.
        await mount();
        press("baked");
        scrollAllIntoView();
        await flush();
        assert.equal(chipsIn(rows()[0]).length, 2);

        press("android");
        assert.deepEqual(
            chipsIn(rows()[0]),
            [],
            "the previous pass's chips are cleared",
        );
        assert.equal(
            observed.size,
            2,
            "both rows are observed again, not just off-screen ones",
        );
        assert.equal(
            rows()[0]
                .querySelector('.cp-rc-cell[data-lane="baked"]')
                ?.classList.contains("is-reference"),
            false,
            "the old reference column stops being marked",
        );
    });

    it("scores a row once per pass, however often it crosses the viewport", async () => {
        await mount();
        press("baked");
        scrollAllIntoView();
        await flush();
        scrollAllIntoView();
        scrollAllIntoView();
        await flush();
        assert.equal(
            chipsIn(rows()[0]).length,
            2,
            "not appended three times over",
        );
    });

    it("honours a reference the URL arrives with", async () => {
        await mount("?ref=baked");
        assert.equal(
            document
                .querySelector('[data-rc-ref="baked"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
        assert.equal(observed.size, 2);
    });

    it("ignores a reference the model does not have", async () => {
        await mount("?ref=nonsense");
        assert.equal(
            document
                .querySelector('[data-rc-ref="none"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
        assert.equal(observed.size, 0);
    });

    it("hands format-compare.js the filter it calls", async () => {
        await mount();
        assert.equal(typeof window.cpRcLanes?.filter, "function");
        window.cpRcLanes?.filter("card");
        assert.deepEqual(
            rows().map((row) => row.hidden),
            [true, false],
        );
        assert.equal(
            document.getElementById("cp-compare-count")?.textContent,
            "1 comparison",
        );
        assert.equal(document.getElementById("cp-rc-empty")?.hidden, true);
    });

    it("explains an empty table rather than leaving it blank", async () => {
        await mount();
        window.cpRcLanes?.filter("nothing matches");
        assert.equal(document.getElementById("cp-rc-empty")?.hidden, false);
        assert.equal(
            document.getElementById("cp-compare-count")?.textContent,
            "0 comparisons",
        );
    });

    it("stops observing a row the filter hid, and starts on one it revealed", async () => {
        await mount();
        press("baked");
        assert.equal(observed.size, 2);
        window.cpRcLanes?.filter("card");
        assert.equal(observed.size, 1, "the hidden row stops being measured");
        window.cpRcLanes?.filter("");
        assert.equal(observed.size, 2);
    });

    it("says when a row is mid-measurement and when it has finished", async () => {
        // Everything about the reference picker is asynchronous, so without this there is no way to
        // tell "still working" from "finished, and this is all there is" — which is exactly what
        // the preview-harness has to know before it takes a screenshot.
        await mount();
        press("baked");
        assert.equal(rows()[0].dataset.scored, undefined, "not started");
        scrollAllIntoView();
        await flush();
        assert.equal(rows()[0].dataset.scored, "done");
        assert.equal(rows()[1].dataset.scored, "done");

        press("android");
        assert.equal(
            rows()[0].dataset.scored,
            undefined,
            "cleared for the new pass",
        );
    });

    it("decodes the reference frame once per row, not once per lane", async () => {
        // `load()` caching the <img> is not enough: every `pixels()` call allocates a canvas,
        // redraws and does another full-frame `getImageData`. On the five-player wall that was four
        // redundant readbacks per row, in the lazy scroll path the observer exists to protect.
        await mount();
        const decoded: string[] = [];
        // happy-dom cannot decode a PNG, so the measuring path is intercepted at its one seam.
        const element = document.querySelector("cp-rc-lanes") as unknown as {
            pixels(src: string): Promise<unknown>;
        };
        element.pixels = async (src: string) => {
            decoded.push(src);
            return {
                width: 4,
                height: 4,
                data: new Uint8ClampedArray(4 * 4 * 4),
            };
        };
        press("android");
        scrollAllIntoView();
        await flush();
        await flush();
        // Row 0 has one measurable lane besides the reference (cmp never rendered), row 1 the same,
        // so the reference is fetched once per row and each lane once.
        assert.deepEqual(decoded, [
            "android/0.png",
            "baked/0.png",
            "android/1.png",
            "baked/1.png",
        ]);
        assert.equal(
            decoded.filter((src) => src === "android/0.png").length,
            1,
            "the reference is not re-read per lane",
        );
    });

    it("wires itself up again after being detached and reinserted", async () => {
        // Everything this element owns lives OUTSIDE it — listeners on the picker, an observer on
        // the rows, a global — so tearing that down on disconnect has to be undoable. Without it
        // the reference picker and the shared filter come back inert after any DOM relocation.
        await mount();
        const element = document.querySelector("cp-rc-lanes") as HTMLElement;
        const parent = element.parentNode as HTMLElement;
        element.remove();
        assert.equal(window.cpRcLanes, undefined, "the global goes with it");

        parent.insertBefore(element, parent.firstChild);
        await flush();
        assert.equal(
            typeof window.cpRcLanes?.filter,
            "function",
            "and comes back",
        );
        press("baked");
        scrollAllIntoView();
        await flush();
        assert.equal(chipsIn(rows()[0]).length, 2, "the picker still measures");
    });

    it("subscribes to Back once per connection, not once per lifetime", async () => {
        // `onPop` is a `popstate` listener on `window`, so a reconnect that re-subscribes without
        // unsubscribing stacks one callback per prior life: every Back then clears the rows and
        // restarts the diff work once per connection the element has ever had.
        //
        // Counted at the listener, not at the end state: four stacked callbacks all compute the
        // same reference and leave the same chips behind, so only the subscription itself shows it.
        const live = new Set<unknown>();
        const add = window.addEventListener.bind(window);
        const remove = window.removeEventListener.bind(window);
        window.addEventListener = ((
            type: string,
            fn: unknown,
            ...rest: unknown[]
        ) => {
            if (type === "popstate") live.add(fn);
            return add(type as never, fn as never, ...(rest as []));
        }) as never;
        window.removeEventListener = ((
            type: string,
            fn: unknown,
            ...rest: unknown[]
        ) => {
            if (type === "popstate") live.delete(fn);
            return remove(type as never, fn as never, ...(rest as []));
        }) as never;
        // The real `serve-chrome.js` global, in the one shape this element uses. Without it
        // `urlState()` is null and the whole Back path — including the leak — is unreachable.
        window.cpUrlState = {
            get: () => "",
            push: () => {},
            replace: () => {},
            sync: () => {},
            onPop: (callback: () => void) => {
                window.addEventListener("popstate", callback);
                return () => window.removeEventListener("popstate", callback);
            },
        };
        try {
            await mount();
            assert.equal(live.size, 1, "one subscription on first connect");
            const element = document.querySelector(
                "cp-rc-lanes",
            ) as HTMLElement;
            const parent = element.parentNode as HTMLElement;
            for (let i = 0; i < 3; i++) {
                element.remove();
                parent.insertBefore(element, parent.firstChild);
                await flush();
            }
            assert.equal(live.size, 1, "still one after three reconnects");
            element.remove();
            await flush();
            assert.equal(live.size, 0, "and none once it is gone for good");
        } finally {
            window.addEventListener = add as never;
            window.removeEventListener = remove as never;
            delete window.cpUrlState;
        }
    });

    it("survives a payload it cannot read", async () => {
        // The table is fully server-rendered, so a broken model costs the numbers and nothing else.
        document.body.innerHTML = `
          <cp-rc-lanes></cp-rc-lanes>
          <section id="cp-rc-lanes">
            <script type="application/json" id="cp-rc-model">{not json</script>
          </section>`;
        await flush();
        assert.equal(window.cpRcLanes, undefined);
    });

    it("stays silent on a page with no compare section", async () => {
        document.body.innerHTML = `<cp-rc-lanes></cp-rc-lanes>`;
        await flush();
        assert.equal(window.cpRcLanes, undefined);
    });
});
