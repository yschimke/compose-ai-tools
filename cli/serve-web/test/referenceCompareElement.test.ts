// Behavioural contract for `<cp-reference-compare>`.
//
// The rules are pinned next door — `annotateMatch`, `annotateTypography`, `annotateClusters`,
// `annotateReport`. What only the element can answer is the wiring: that the scorer handle is read
// LATE rather than at upgrade, that a panel's boxes are placed against ITS OWN image rather than a
// shared coordinate space, that the typography table lights the boxes on both panels, and that a
// pointer leaving a focused row does not clear the highlight the keyboard reader is relying on.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/ReferenceCompare.js";

/**
 * A second usage of the SAME token at a heavier weight, twice on the majority style and once on the
 * override. `typographyDefaults` calls the most-used group the token's default, so the single
 * heavier usage reads as a local override rather than a fidelity finding.
 */
const bold = (side: string) =>
    (side === "reference" ? [700, 400, 400] : [700, 400, 400, 400]).map(
        (weight, i) => ({
            kind: "typography",
            role: `Heading ${side} ${i}`,
            label: "Title",
            bounds: { x: 10, y: 60 + i * 30, width: 60, height: 20 },
            detail: {
                token: "m3/title-medium",
                fontSize: "20sp",
                lineHeight: "24sp",
                fontWeight: String(weight),
            },
        }),
    );

const ANNOTATIONS = {
    reference: [
        {
            kind: "layout",
            role: "Button",
            label: "16dp",
            bounds: { x: 0, y: 0, width: 100, height: 40 },
        },
        {
            kind: "typography",
            role: "Label",
            label: "Save",
            bounds: { x: 10, y: 10, width: 40, height: 14 },
            detail: {
                token: "m3/label-large",
                fontSize: "14sp",
                lineHeight: "20sp",
            },
        },
        ...bold("reference"),
    ],
    actual: [
        {
            kind: "layout",
            role: "Button",
            label: "16dp",
            bounds: { x: 0, y: 0, width: 200, height: 80 },
        },
        {
            kind: "typography",
            role: "Label",
            label: "Save",
            bounds: { x: 20, y: 20, width: 80, height: 28 },
            detail: {
                token: "m3/label-large",
                fontSize: "16sp",
                lineHeight: "20sp",
            },
        },
        ...bold("actual"),
    ],
};

interface Scorer {
    calls: number;
}

let priorCompare: unknown;
let priorResizeObserver: unknown;
const reflows: Array<() => void> = [];

/** `window.ComposePreviewCompare`, published the way `format-compare.js` publishes it. */
function stubCompare(options: { fail?: boolean } = {}): Scorer {
    const state: Scorer = { calls: 0 };
    priorCompare = window.ComposePreviewCompare;
    window.ComposePreviewCompare = {
        async normaliseImageUrls() {
            state.calls += 1;
            if (options.fail) throw new Error("reference unavailable");
            return {
                reference: { width: 200, height: 100 },
                candidate: { width: 200, height: 100 },
                images: [{}, {}],
                width: 200,
                height: 100,
            };
        },
        diffCanvases: () => 600,
        async scoreImages() {
            return { percent: 98.4, geometry: 3.25 };
        },
    } as unknown as typeof window.ComposePreviewCompare;
    return state;
}

/** happy-dom reports every layout box as zero, so each panel's geometry has to be declared. */
function sizePanel(image: HTMLImageElement, natural: number, client: number) {
    for (const [key, value] of [
        ["naturalWidth", natural],
        ["clientWidth", client],
        ["clientHeight", client * 2],
    ] as const) {
        Object.defineProperty(image, key, {
            configurable: true,
            get: () => value,
        });
    }
}

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

function markup(options: { annotations?: unknown } = {}): string {
    const payload =
        options.annotations === undefined ? ANNOTATIONS : options.annotations;
    return `
      <div id="cp-reference-compare" data-reference="/ref.png" data-actual="/act.png?token=secret&theme=dark">
        <div class="cp-reference-grid">
          <section><div class="cp-compare-shot" data-cp-annotated="reference"><img id="ref-img" src="/ref.png"></div></section>
          <section><div class="cp-compare-shot"><canvas class="cp-reference-diff"></canvas></div></section>
          <section><div class="cp-compare-shot" data-cp-annotated="actual"><img id="act-img" src="/act.png"></div></section>
        </div>
        <label><input data-cp-annotation-kind="layout" type="checkbox" checked> Layout</label>
        <label><input data-cp-annotation-kind="typography" type="checkbox" checked> Type</label>
        <p class="cp-reference-result">comparing…</p>
        <input id="cp-report-body" data-report-template="Render {{render}} — {{rawScores}}">
        <label class="cp-overlay-control">Overlay <input class="cp-overlay-range" type="range" min="0" max="100" value="50"><span>50%</span></label>
        <div class="cp-reference-overlay"><img src="/ref.png"><img src="/act.png"></div>
      </div>
      <script id="cp-annotations" type="application/json">${JSON.stringify(payload)}</script>
      <cp-reference-compare></cp-reference-compare>
    `;
}

/** Fire every observed reflow — happy-dom has no layout engine to fire them itself. */
function reflow(): void {
    for (const callback of reflows) callback();
}

async function mount(options: { annotations?: unknown } = {}): Promise<void> {
    document.body.innerHTML = markup(options);
    // Sized AFTER the write, because the element installs during it. The first placement therefore
    // runs against a zero-width image and does nothing, exactly as it does in a browser before the
    // panels decode; the reflow is what a real `ResizeObserver` would deliver next.
    sizePanel(document.getElementById("ref-img") as HTMLImageElement, 100, 50);
    sizePanel(document.getElementById("act-img") as HTMLImageElement, 200, 200);
    reflow();
    await flush();
}

/** 20000 normalised pixels, 600 of them changed, and 3.25 rounded to one place. */
const SCORED =
    "98.4% structural match · 3.00% pixels changed · 3.3% proportion difference";

const result = () =>
    document.querySelector<HTMLElement>(".cp-reference-result")!.textContent;
const boxes = (side: string) =>
    Array.from(
        document.querySelectorAll<HTMLElement>(
            `[data-cp-annotated="${side}"] .cp-annotation`,
        ),
    );

describe("<cp-reference-compare>", () => {
    afterEach(() => {
        reflows.length = 0;
        // Restored rather than deleted: mocha shares one process, and a `delete` here leaves every
        // later file without the global it expected.
        window.ComposePreviewCompare =
            priorCompare as typeof window.ComposePreviewCompare;
        (globalThis as Record<string, unknown>).ResizeObserver =
            priorResizeObserver;
        resetDom();
    });

    it("scores the pair and states both numbers", async () => {
        stubResizeObserver();
        const scorer = stubCompare();
        await mount();
        await flush();
        assert.equal(scorer.calls, 1);
        assert.equal(result(), SCORED);
    });

    it("waits for the rest of the page before deciding the scorer is missing", async () => {
        // `format-compare.js` publishes the global from its own script tag, and a light-DOM element
        // upgrades the moment the parser reaches ITS tag. An element that gave up on the first look
        // would report "unavailable" on a page whose scorer was one tag away.
        stubResizeObserver();
        document.body.innerHTML = markup();
        sizePanel(
            document.getElementById("ref-img") as HTMLImageElement,
            100,
            50,
        );
        sizePanel(
            document.getElementById("act-img") as HTMLImageElement,
            200,
            200,
        );
        const scorer = stubCompare();
        await flush();
        await flush();
        assert.equal(scorer.calls, 1);
        assert.equal(result(), SCORED);
    });

    it("says so plainly on a page with no scorer at all", async () => {
        stubResizeObserver();
        priorCompare = window.ComposePreviewCompare;
        window.ComposePreviewCompare = undefined;
        await mount();
        await flush();
        assert.equal(result(), "Comparison unavailable");
        assert.ok(boxes("reference").length, "the redline is still drawn");
    });

    it("leaves the page usable when the host cannot produce a reference", async () => {
        stubResizeObserver();
        stubCompare({ fail: true });
        await mount();
        await flush();
        assert.equal(result(), "Comparison unavailable");
        assert.ok(boxes("reference").length, "the redline is still drawn");
    });

    it("fills the report form's hidden input, without the session token", async () => {
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const body = document.getElementById(
            "cp-report-body",
        ) as HTMLInputElement;
        assert.ok(!body.value.includes("secret"), body.value);
        assert.ok(body.value.includes("theme=dark"), body.value);
        assert.ok(body.value.includes("98.4% structural match"), body.value);
    });

    it("places each panel's boxes against ITS OWN image", async () => {
        // The two frames are routinely different sizes, so a shared coordinate space would put one
        // panel's redline at the wrong scale. Reference is 100 natural at 50 rendered (0.5×), actual
        // is 200 at 200 (1×), and the same 100-wide element is 100 wide on both captures' terms.
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const layout = (side: string) =>
            boxes(side).find((node) =>
                node.classList.contains("cp-annotation--layout"),
            )!;
        assert.equal(layout("reference").style.width, "50px");
        assert.equal(layout("actual").style.width, "200px");
    });

    it("replaces every box when a panel reflows", async () => {
        // Observed rather than only listening for `load`: the panels sit in a responsive grid that
        // can reflow without the window changing size at all, and a one-shot placement leaves the
        // redline behind wherever it happened to land first.
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const box = boxes("reference")[0];
        assert.equal(box.style.width, "50px");
        sizePanel(
            document.getElementById("ref-img") as HTMLImageElement,
            100,
            300,
        );
        reflow();
        assert.equal(box.style.width, "300px");
    });

    it("groups typography into a table and lights both panels from one row", async () => {
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const row = document.querySelector<HTMLElement>(".cp-typography-group");
        assert.ok(row, "the summary table was appended");
        const marker = row.getAttribute("data-cp-typography-marker");
        const lit = () =>
            document.querySelectorAll(
                `.cp-annotation-active[data-cp-typography-marker="${marker}"]`,
            ).length;
        assert.equal(lit(), 0);
        row.dispatchEvent(new Event("mouseenter"));
        assert.ok(lit() >= 2, "boxes on both panels light up");
        // Focus and hover are OR'd: a pointer leaving a focused row must not clear the highlight
        // the keyboard reader is relying on.
        row.dispatchEvent(new Event("focus"));
        row.dispatchEvent(new Event("mouseleave"));
        assert.ok(lit() >= 2, "still lit for the keyboard");
        row.dispatchEvent(new Event("blur"));
        assert.equal(lit(), 0);
    });

    it("renders one row per style, with each side's usage count", async () => {
        // The capture state `serve-reference-compare/annotated` held these three checks and nothing
        // else did, so the harness doc's claim that they were covered here was wrong until now.
        // The fixture carries three styles: a label pair, and a title token used twice at weight
        // 400 with a single heavier usage that reads as an override off it.
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        assert.equal(
            document.querySelectorAll(".cp-typography-group").length,
            3,
            "one row per paired style, not one per usage",
        );
        const countsByRow = Array.from(
            document.querySelectorAll(".cp-typography-group"),
        ).map((row) =>
            Array.from(row.querySelectorAll(".cp-typography-count")).map(
                (count) => count.textContent,
            ),
        );
        // Preserve row and side association: the title default is used twice in the reference but
        // three times in the actual, while both the label and heavier override are singular per side.
        assert.deepEqual(countsByRow, [
            ["1 usage", "1 usage"],
            ["1 usage", "1 usage"],
            ["2 usages", "3 usages"],
        ]);
    });

    it("marks a local override, and says which default it departed from", async () => {
        // The distinction the whole table exists for: a fidelity finding is the render disagreeing
        // with the design, an OVERRIDE is this usage departing from its own token's most-used
        // group — on both sides equally, so it is not a defect at all. Marking one as the other
        // would report a deliberate choice as a bug.
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const overrides = Array.from(
            document.querySelectorAll(".cp-typography-override"),
        ).map((n) => `${n.textContent}|${n.getAttribute("title")}`);
        assert.deepEqual(overrides, [
            "wght 700|Changed from titleMedium default",
            "wght 700|Changed from titleMedium default",
        ]);
    });

    it("marks the fields that differ between the two sides", async () => {
        // Same token, same family, different size — the finding the page exists to show.
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const changed = Array.from(
            document.querySelectorAll(".cp-typography-changed"),
        ).map((node) => node.textContent);
        assert.ok(
            changed.some((text) => text?.includes("14sp")),
            changed.join(" | "),
        );
        assert.ok(
            changed.some((text) => text?.includes("16sp")),
            changed.join(" | "),
        );
    });

    it("hides a kind when its toggle is cleared", async () => {
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const root = document.getElementById("cp-reference-compare")!;
        assert.equal(root.getAttribute("data-annotate-typography"), "on");
        const toggle = document.querySelector<HTMLInputElement>(
            '[data-cp-annotation-kind="typography"]',
        )!;
        toggle.checked = false;
        toggle.dispatchEvent(new Event("change"));
        assert.equal(root.getAttribute("data-annotate-typography"), "off");
        assert.equal(root.getAttribute("data-annotate-layout"), "on");
    });

    it("drives the overlay slider from its own value", async () => {
        stubResizeObserver();
        stubCompare();
        await mount();
        await flush();
        const range =
            document.querySelector<HTMLInputElement>(".cp-overlay-range")!;
        const overlaid = document.querySelector<HTMLElement>(
            ".cp-reference-overlay img:last-child",
        )!;
        assert.equal(overlaid.style.opacity, "0.5");
        range.value = "80";
        range.dispatchEvent(new Event("input"));
        assert.equal(overlaid.style.opacity, "0.8");
        assert.equal(
            document.querySelector(".cp-overlay-control span")!.textContent,
            "80%",
        );
    });

    it("survives an annotation payload that is not JSON", async () => {
        stubResizeObserver();
        stubCompare();
        document.body.innerHTML = markup().replace(
            /(<script id="cp-annotations"[^>]*>)[^<]*/,
            "$1not json",
        );
        await flush();
        assert.equal(boxes("reference").length, 0);
        assert.equal(result(), SCORED);
    });
});
