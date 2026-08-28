// The parity verdict's geometry, and the wiring that puts it on the panels.
//
// Two halves, deliberately checked in one file because the contract spans them: the payload reader
// decides what counts as a drawable region, and the element decides when a region is lit and what
// happens to a row that turns out to have nowhere to point.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import { parseParityAnchors, severityOf } from "../src/annotate/verdict.js";
import "../src/components/ReferenceCompare.js";

const box = (x = 4, y = 6, width = 30, height = 12) => ({
    x,
    y,
    width,
    height,
});

describe("parseParityAnchors", () => {
    it("keeps a drawable region and names its side", () => {
        const anchors = parseParityAnchors({
            findings: {
                "tokens-0": [
                    { side: "actual", bounds: box(), label: " Label " },
                ],
            },
        });
        assert.equal(anchors.size, 1);
        const [anchor] = anchors.get("tokens-0")!;
        assert.equal(anchor.side, "actual");
        assert.equal(anchor.label, "Label");
    });

    it("drops a region that cannot be drawn without taking its finding with it", () => {
        const anchors = parseParityAnchors({
            findings: {
                "layout-0": [
                    { side: "actual", bounds: box(0, 0, 0, 12) },
                    { side: "sideways", bounds: box() },
                    { side: "reference", bounds: box() },
                ],
            },
        });
        assert.equal(anchors.get("layout-0")!.length, 1);
        assert.equal(anchors.get("layout-0")![0].side, "reference");
    });

    it("drops a finding left with nothing drawable, so a lookup is the whole test", () => {
        const anchors = parseParityAnchors({
            findings: {
                "a11y-0": [{ side: "actual", bounds: box(0, 0, 4, 0) }],
            },
        });
        assert.equal(anchors.has("a11y-0"), false);
    });

    for (const [name, raw] of [
        ["a payload that is not an object", 7],
        ["a payload with no findings key", { other: 1 }],
        ["a finding whose value is not a list", { findings: { a: 1 } }],
    ] as const) {
        it(`reads ${name} as no anchors rather than throwing`, () => {
            assert.equal(parseParityAnchors(raw).size, 0);
        });
    }
});

describe("severityOf", () => {
    it("reads the severity the server-rendered row declares", () => {
        const row = document.createElement("li");
        row.className = "cp-parity-finding cp-parity-finding--error";
        assert.equal(severityOf(row), "error");
        row.className = "cp-parity-finding cp-parity-finding--info";
        assert.equal(severityOf(row), "info");
        // Neither class — a row from a producer this build does not fully understand still reads.
        row.className = "cp-parity-finding";
        assert.equal(severityOf(row), "warn");
    });
});

const ANCHORS = {
    findings: {
        "tokens-0": [
            { side: "actual", bounds: box(), label: "Button" },
            { side: "reference", bounds: box(1, 2, 20, 8) },
        ],
        // Keyed to a row the page does not render — the payload and the markup can only be built
        // together by the server, but a stale one must not throw.
        "layout-9": [{ side: "actual", bounds: box() }],
    },
};

let priorResizeObserver: unknown;
const reflows: Array<() => void> = [];

/** happy-dom has no layout engine, so the observer that repositions the boxes is stood in for. */
function stubResizeObserver(): void {
    priorResizeObserver = (globalThis as Record<string, unknown>)
        .ResizeObserver;
    (globalThis as Record<string, unknown>).ResizeObserver = class {
        constructor(callback: () => void) {
            reflows.push(callback);
        }
        observe(): void {}
        disconnect(): void {}
    };
}

/** happy-dom reports every layout box as zero, so each panel's geometry has to be declared. */
function sizePanel(image: HTMLImageElement, natural: number, client: number) {
    for (const [key, value] of [
        ["naturalWidth", natural],
        ["clientWidth", client],
        ["clientHeight", client],
    ] as const) {
        Object.defineProperty(image, key, {
            configurable: true,
            get: () => value,
        });
    }
}

function markup(anchors: unknown): string {
    return `
      <div id="cp-reference-compare" data-reference="/ref.png" data-actual="/act.png">
        <div class="cp-reference-grid">
          <section><div class="cp-compare-shot" data-cp-annotated="reference"><img id="ref-img" src="/ref.png"></div></section>
          <section><div class="cp-compare-shot" data-cp-annotated="actual"><img id="act-img" src="/act.png"></div></section>
        </div>
        <p class="cp-reference-result">comparing…</p>
        <section class="cp-parity-verdict">
          <ul class="cp-parity-list">
            <li class="cp-parity-finding cp-parity-finding--error"
                data-cp-parity-finding="tokens-0" id="row-token">t</li>
            <li class="cp-parity-finding cp-parity-finding--warn"
                data-cp-parity-finding="a11y-3" id="row-orphan">o</li>
            <li class="cp-parity-finding cp-parity-finding--info" id="row-prose">p</li>
          </ul>
        </section>
      </div>
      <script id="cp-parity-anchors" type="application/json">${JSON.stringify(anchors)}</script>
      <cp-reference-compare></cp-reference-compare>
    `;
}

async function mount(anchors: unknown = ANCHORS): Promise<void> {
    stubResizeObserver();
    document.body.innerHTML = markup(anchors);
    // Sized AFTER the write, because the element installs during it: the first placement runs
    // against a zero-width image and does nothing, exactly as it does in a browser before the
    // panels decode. The reflow is what a real `ResizeObserver` delivers next.
    sizePanel(document.getElementById("ref-img") as HTMLImageElement, 100, 200);
    sizePanel(document.getElementById("act-img") as HTMLImageElement, 100, 200);
    for (const callback of reflows) callback();
    await flush();
}

const highlights = (side: string) =>
    Array.from(
        document.querySelectorAll<HTMLElement>(
            `[data-cp-annotated="${side}"] .cp-parity-anchor`,
        ),
    );
const lit = () =>
    document.querySelectorAll(".cp-parity-anchor.cp-parity-anchor-active")
        .length;
const row = (id: string) => document.getElementById(id)!;

describe("<cp-reference-compare> parity highlights", () => {
    afterEach(() => {
        reflows.length = 0;
        // Restored rather than deleted: mocha shares one process, and a `delete` here would leave
        // every later file without the global it expected.
        (globalThis as Record<string, unknown>).ResizeObserver =
            priorResizeObserver;
        resetDom();
    });

    it("draws a region per panel and lights none of them at rest", async () => {
        await mount();
        assert.equal(highlights("actual").length, 1);
        assert.equal(highlights("reference").length, 1);
        assert.equal(lit(), 0);
        // The severity the row declares reaches the box, so an error and a note do not read alike.
        assert.ok(
            highlights("actual")[0].classList.contains(
                "cp-parity-anchor--error",
            ),
        );
        assert.equal(
            highlights("actual")[0].querySelector(".cp-parity-anchor-label")
                ?.textContent,
            "Button",
        );
    });

    it("places each region against its own panel's scale", async () => {
        await mount();
        // 4 → 8 at 200/100. Both panels are the same scale here; what is pinned is that the box is
        // placed at all, in the layer the redline shares.
        assert.equal(highlights("actual")[0].style.left, "8px");
        assert.equal(highlights("actual")[0].style.width, "60px");
    });

    it("lights every region of a hovered finding, on both panels", async () => {
        await mount();
        row("row-token").dispatchEvent(new Event("mouseenter"));
        assert.equal(lit(), 2);
        row("row-token").dispatchEvent(new Event("mouseleave"));
        assert.equal(lit(), 0);
    });

    it("keeps a pinned finding lit after the pointer leaves", async () => {
        await mount();
        row("row-token").dispatchEvent(new Event("click"));
        assert.equal(lit(), 2);
        assert.equal(row("row-token").getAttribute("aria-pressed"), "true");
        row("row-token").dispatchEvent(new Event("mouseenter"));
        row("row-token").dispatchEvent(new Event("mouseleave"));
        assert.equal(lit(), 2, "the pin outlives the hover");
        row("row-token").dispatchEvent(new Event("click"));
        assert.equal(lit(), 0);
        assert.equal(row("row-token").getAttribute("aria-pressed"), "false");
    });

    it("does not clear a focused row's highlight when the pointer leaves it", async () => {
        await mount();
        row("row-token").dispatchEvent(new Event("focus"));
        row("row-token").dispatchEvent(new Event("mouseenter"));
        row("row-token").dispatchEvent(new Event("mouseleave"));
        assert.equal(lit(), 2, "focus still holds it");
        row("row-token").dispatchEvent(new Event("blur"));
        assert.equal(lit(), 0);
    });

    it("pins from the keyboard, because a li is not a button", async () => {
        await mount();
        row("row-token").dispatchEvent(
            new KeyboardEvent("keydown", { key: "Enter" }),
        );
        assert.equal(lit(), 2);
    });

    it("promotes a row to a control only once its boxes exist", async () => {
        // The server ships every row as an ordinary list item, because with script off, blocked or
        // failed there is no highlight to give and a tab stop that does nothing is worse than
        // prose. Only the row that got boxes becomes a button.
        await mount();
        const wired = row("row-token");
        assert.equal(wired.getAttribute("tabindex"), "0");
        assert.equal(wired.getAttribute("role"), "button");
        assert.equal(wired.getAttribute("aria-pressed"), "false");
    });

    it("leaves a row with nowhere to point as prose, and drops its id", async () => {
        await mount();
        const orphan = row("row-orphan");
        assert.equal(orphan.getAttribute("tabindex"), null);
        assert.equal(orphan.getAttribute("role"), null);
        assert.equal(orphan.hasAttribute("data-cp-parity-finding"), false);
        // …and the prose-only row is untouched: the server never gave it an id to wire.
        assert.equal(row("row-prose").getAttribute("tabindex"), null);
        assert.equal(row("row-prose").textContent, "p");
    });

    it("leaves every row plain when the payload cannot be read", async () => {
        stubResizeObserver();
        document.body.innerHTML = markup(ANCHORS).replace(
            /(<script id="cp-parity-anchors"[^>]*>)[^<]*/,
            "$1{ not json",
        );
        await flush();
        // The no-script path, reached with script running: nothing was promoted, so nothing
        // announces itself as a control it cannot be.
        assert.equal(row("row-token").getAttribute("role"), null);
        assert.equal(row("row-token").getAttribute("tabindex"), null);
    });

    it("renders the page unchanged when the payload is unreadable", async () => {
        stubResizeObserver();
        document.body.innerHTML = markup(ANCHORS).replace(
            /(<script id="cp-parity-anchors"[^>]*>)[^<]*/,
            "$1{ not json",
        );
        await flush();
        assert.equal(document.querySelectorAll(".cp-parity-anchor").length, 0);
        // The verdict's prose is the server's, so it survives a payload this build cannot read.
        assert.equal(row("row-token").textContent, "t");
    });
});
