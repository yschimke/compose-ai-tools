// Behavioural contract for `<cp-spec-compare>`.
//
// The rules are pinned next door — `specViews.test.ts`, `specVerdict.test.ts`,
// `sameOrigin.test.ts`. What only the element can answer is the lane's lifecycle: that `viewer.js`
// finds the global it calls, that entering and leaving the lane hands the chip back exactly as the
// server rendered it, that a view switch inside one visit does not re-run the comparison, and that
// a comparison still in flight when the lane closes cannot paint over the published verdict.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/SpecCompare.js";

/** Frames whose canvases are never actually drawn — happy-dom has no 2d context worth painting. */
const framesFor = (width = 8, height = 8) => ({
    reference: { width, height } as never,
    candidate: { width, height } as never,
    images: [{}, {}] as [unknown, unknown],
    width,
    height,
});

interface Stub {
    normalise: string[];
    scores: number[];
    settle(): void;
}

/** A `ComposePreviewCompare` whose normalisation resolves only when a test says so. */
function stubCompare(
    result: { percent: number; geometry: number } = {
        percent: 98.4,
        geometry: 0,
    },
    options: { hold?: boolean } = {},
): Stub {
    const held: Array<() => void> = [];
    const stub: Stub = {
        normalise: [],
        scores: [],
        settle: () => {
            for (const release of held.splice(0)) release();
        },
    };
    window.ComposePreviewCompare = {
        scoreImageUrls: async () => result,
        normaliseImageUrls: async (reference: string, actual: string) => {
            stub.normalise.push(`${reference}|${actual}`);
            if (options.hold) await new Promise<void>((r) => held.push(r));
            return framesFor();
        },
        diffCanvases: () => 12,
        scoreImages: async () => {
            stub.scores.push(1);
            return result;
        },
    };
    return stub;
}

async function mount(): Promise<void> {
    document.body.innerHTML = `
      <cp-spec-compare></cp-spec-compare>
      <span id="cp-spec-chip" data-spec-match="close"
            data-spec-chip-name="Button" data-spec-chip-label="Button 97.1%">Button 97.1%</span>
      <div class="cp-viewer">
        <span id="cp-spec-views" hidden>
          <button type="button" data-cp-spec-view="spec" aria-pressed="true">Spec</button>
          <button type="button" data-cp-spec-view="diff" aria-pressed="false">Diff</button>
          <button type="button" data-cp-spec-view="triptych" aria-pressed="false">Triptych</button>
          <button type="button" data-cp-spec-view="slider" aria-pressed="false">Slider</button>
        </span>
        <span id="cp-spec-score" hidden></span>
        <div class="cp-spec-compare" id="cp-spec-compare" hidden data-view="spec"
             data-reference="/reference/Button.png">
          <canvas id="cp-spec-reference"></canvas>
          <canvas id="cp-spec-diff"></canvas>
          <canvas id="cp-spec-actual"></canvas>
          <div class="cp-spec-wipe">
            <canvas id="cp-spec-wipe-canvas"></canvas>
            <input id="cp-spec-wipe-range" type="range" min="0" max="100" value="50">
          </div>
        </div>
      </div>`;
    await flush();
}

const lane = () => window.cpSpecCompare!;
const chip = () => document.getElementById("cp-spec-chip") as HTMLElement;
const score = () => document.getElementById("cp-spec-score") as HTMLElement;
const panel = () => document.getElementById("cp-spec-compare") as HTMLElement;
const viewer = () => document.querySelector(".cp-viewer") as HTMLElement;
const press = (view: string) =>
    document
        .querySelector<HTMLElement>(`[data-cp-spec-view="${view}"]`)
        ?.click();

describe("<cp-spec-compare>", () => {
    afterEach(() => {
        delete window.ComposePreviewCompare;
        delete window.cpSpecCompare;
        resetDom();
    });

    it("hands viewer.js the global it calls", async () => {
        await mount();
        assert.equal(typeof lane().open, "function");
        assert.equal(lane().view(), "spec");
    });

    it("leaves the stage alone on the default view", async () => {
        // A session that never picks a comparison view has to behave exactly as it did before this
        // element existed: the raster `<img>` viewer.js put on the stage stays the whole surface.
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        await flush();
        assert.equal(viewer().getAttribute("data-spec-view"), "spec");
        assert.equal(panel().hidden, true, "the comparison panel stays away");
        assert.equal(score().hidden, true);
    });

    it("takes the stage for a comparison view and scores the pair", async () => {
        const stub = stubCompare({ percent: 98.44, geometry: 0 });
        await mount();
        lane().open("/render/Button.png");
        press("triptych");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(viewer().getAttribute("data-spec-view"), "triptych");
        assert.equal(panel().hidden, false);
        assert.equal(score().textContent, "98.4% match · 18.75% pixels differ");
        assert.deepEqual(stub.normalise, [
            "https://preview.example/reference/Button.png|https://preview.example/render/Button.png",
        ]);
    });

    it("puts the live verdict on the chip, and the published one back on the way out", async () => {
        // The chip carries the score baked at PUBLISH. Once an override or a knob has moved the
        // render, that number describes a frame that is no longer on the stage — but off the lane
        // there is nothing live to describe, so leaving a knob-bent number there would misreport
        // every later visit as if it were the publish.
        stubCompare({ percent: 99.9, geometry: 0 });
        await mount();
        lane().open("/render/Button.png?knob=1");
        press("diff");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(chip().textContent, "Button 99.9%");
        assert.equal(chip().getAttribute("data-spec-match"), "match");

        lane().close();
        assert.equal(
            chip().textContent,
            "Button 97.1%",
            "the published label returns",
        );
        assert.equal(
            chip().getAttribute("data-spec-match"),
            "close",
            "and its published band",
        );
    });

    it("does not re-compare when only the view changes", async () => {
        // One normalisation feeds the diff, the three panels and the wipe, so switching between
        // them inside a visit is free — and re-running it would be a second `/render` request on a
        // `no-store` override, which can come back different.
        const stub = stubCompare();
        await mount();
        lane().open("/render/Button.png");
        press("diff");
        for (let i = 0; i < 5; i++) await flush();
        press("triptych");
        press("slider");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(stub.normalise.length, 1);
    });

    it("re-compares when the render changed underneath", async () => {
        const stub = stubCompare();
        await mount();
        lane().open("/render/Button.png");
        press("diff");
        for (let i = 0; i < 5; i++) await flush();
        lane().close();
        lane().open("/render/Button.png?theme=dark");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(
            stub.normalise.length,
            2,
            "a new pair is a new comparison",
        );
    });

    it("abandons a comparison the lane no longer wants", async () => {
        // `close()` restores the published verdict; a score resolving afterwards would otherwise
        // still pass its generation check and paint a live, possibly override-specific number onto
        // the chip while the published render is back on the stage.
        const stub = stubCompare({ percent: 42, geometry: 0 }, { hold: true });
        await mount();
        lane().open("/render/Button.png?knob=1");
        press("diff");
        await flush();
        lane().close();
        stub.settle();
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(
            chip().textContent,
            "Button 97.1%",
            "the published label survives",
        );
        assert.equal(chip().getAttribute("data-spec-match"), "close");
    });

    it("says so rather than showing nothing when the pair cannot be compared", async () => {
        // No `format-compare.js` on this page: the lane has views but no instruments.
        await mount();
        lane().open("/render/Button.png");
        press("diff");
        for (let i = 0; i < 3; i++) await flush();
        assert.equal(score().textContent, "Comparison unavailable");
    });

    it("refuses a reference that is not ours", async () => {
        const stub = stubCompare();
        await mount();
        panel().setAttribute("data-reference", "https://evil.example/x.png");
        // Re-mount so the element re-reads the attribute the way a served page would.
        document.querySelector("cp-spec-compare")!.remove();
        document.body.insertBefore(
            document.createElement("cp-spec-compare"),
            document.body.firstChild,
        );
        await flush();
        lane().open("/render/Button.png");
        press("diff");
        for (let i = 0; i < 3; i++) await flush();
        assert.equal(
            stub.normalise.length,
            0,
            "nothing cross-origin reaches a canvas",
        );
        assert.equal(score().textContent, "Comparison unavailable");
    });

    it("marks the pressed view, and only that one", async () => {
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        press("slider");
        await flush();
        const pressed = Array.from(
            document.querySelectorAll("[data-cp-spec-view]"),
        ).map((b) => b.getAttribute("aria-pressed"));
        assert.deepEqual(pressed, ["false", "false", "false", "true"]);
    });

    it("honours a view the URL arrived with over one the chip asks for", async () => {
        // The bug this prevents: a shared `?specView=triptych` link, or a Back into one, being
        // overwritten by the chip that happens to sit on the same page.
        stubCompare();
        await mount();
        lane().hydrate("triptych");
        lane().prefer("slider");
        lane().open("/render/Button.png");
        await flush();
        assert.equal(lane().view(), "triptych");
    });

    it("opens on the chip's view when nobody else has spoken", async () => {
        stubCompare();
        await mount();
        lane().prefer("slider");
        lane().open("/render/Button.png");
        await flush();
        assert.equal(lane().view(), "slider");
        assert.equal(viewer().getAttribute("data-spec-view"), "slider");
    });

    it("stays silent on a preview with no published reference", async () => {
        document.body.innerHTML = `<cp-spec-compare></cp-spec-compare><div class="cp-viewer"></div>`;
        await flush();
        assert.equal(window.cpSpecCompare, undefined);
    });
});
