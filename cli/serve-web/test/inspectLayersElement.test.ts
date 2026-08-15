// Behavioural contract for `<cp-inspect-layers>`.
//
// The rules are pinned next door — `inspectEntries.test.ts`, `inspectLayers.test.ts`. What only the
// element can answer is the wiring: that ticking a layer fetches the right endpoint ONCE, that a
// new frame invalidates what was fetched for the old one, that box and legend row light each other
// up, and that a slow fetch cannot draw over a newer one.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/InspectLayers.js";

const A11Y = {
    nodes: [
        { boundsInScreen: "0,0,100,50", label: "Save", role: "Button" },
        { boundsInScreen: "0,60,100,110", label: "Cancel", role: "Button" },
    ],
    findings: [],
    touchTargets: [],
};

const ANNOTATIONS = {
    annotations: [
        {
            kind: "typography",
            bounds: { x: 0, y: 0, width: 100, height: 50 },
            role: "Title",
            label: "20sp",
        },
        {
            kind: "theme",
            bounds: { x: 0, y: 60, width: 100, height: 50 },
            label: "surface",
        },
    ],
};

interface Fetches {
    urls: string[];
    settle(): void;
}

function stubFetch(options: { hold?: boolean; fail?: boolean } = {}): Fetches {
    const held: Array<() => void> = [];
    const state: Fetches = {
        urls: [],
        settle: () => {
            for (const release of held.splice(0)) release();
        },
    };
    globalThis.fetch = (async (url: string) => {
        state.urls.push(String(url));
        if (options.hold) await new Promise<void>((r) => held.push(r));
        if (options.fail) return { ok: false, status: 404 };
        return {
            ok: true,
            json: async () =>
                String(url).includes(".a11y") ? A11Y : ANNOTATIONS,
        };
    }) as unknown as typeof fetch;
    return state;
}

async function mount(search = ""): Promise<void> {
    window.history.replaceState(null, "", `/m3/p/plain.Button${search}`);
    document.body.innerHTML = `
      <cp-inspect-layers></cp-inspect-layers>
      <div class="cp-viewer" data-preview-id="plain.Button">
        <img id="cp-img" data-cp-src="/m3/render/plain.Button.png?at=abc">
        <div class="cp-inspect-layer" id="cp-inspect-layer"></div>
        <div class="cp-inspect-legend" id="cp-inspect-legend" hidden></div>
        <label><input class="cp-inspect" data-cp-inspect="a11y" type="checkbox"> A11y</label>
        <label><input class="cp-inspect" data-cp-inspect="typography" type="checkbox"> Type</label>
        <label><input class="cp-inspect" data-cp-inspect="theme" type="checkbox"> Theme</label>
      </div>`;
    await flush();
}

const toggle = (kind: string) =>
    document.querySelector<HTMLInputElement>(`[data-cp-inspect="${kind}"]`)!;
const tick = async (kind: string) => {
    const el = toggle(kind);
    el.checked = true;
    el.dispatchEvent(new Event("change"));
    for (let i = 0; i < 5; i++) await flush();
};
const boxes = () =>
    Array.from(
        document.querySelectorAll<HTMLElement>(
            "#cp-inspect-layer .cp-inspect-box",
        ),
    );
const rows = () =>
    Array.from(
        document.querySelectorAll<HTMLElement>(
            "#cp-inspect-legend [data-cp-entry]",
        ),
    );
const legend = () =>
    document.getElementById("cp-inspect-legend") as HTMLElement;
const viewer = () => document.querySelector(".cp-viewer") as HTMLElement;
const sections = () =>
    Array.from(document.querySelectorAll(".cp-inspect-section-head")).map(
        (el) => el.textContent,
    );

describe("<cp-inspect-layers>", () => {
    afterEach(() => {
        resetDom();
        window.history.replaceState(null, "", "/");
    });

    it("draws nothing until a layer is ticked", async () => {
        stubFetch();
        await mount();
        assert.equal(legend().hidden, true);
        assert.equal(boxes().length, 0);
        assert.equal(viewer().hasAttribute("data-inspect"), false);
    });

    it("fetches the layer's own endpoint, derived from the frame on screen", async () => {
        // Derived rather than rebuilt, so the overlay describes the pixels actually displayed —
        // including the `at=abc` pin.
        const stub = stubFetch();
        await mount();
        await tick("a11y");
        assert.deepEqual(stub.urls, ["/m3/render/plain.Button.a11y?at=abc"]);
        assert.equal(boxes().length, 2);
        assert.equal(rows().length, 2);
        assert.equal(viewer().getAttribute("data-inspect"), "on");
    });

    it("fetches one payload for the two layers that share it", async () => {
        const stub = stubFetch();
        await mount();
        await tick("typography");
        await tick("theme");
        assert.deepEqual(stub.urls, [
            "/m3/render/plain.Button.annotations?at=abc",
        ]);
        assert.deepEqual(sections(), ["Typography (1)", "Theme (1)"]);
    });

    it("orders the legend by the declared layers, not by what was ticked first", async () => {
        stubFetch();
        await mount();
        await tick("theme");
        await tick("a11y");
        assert.deepEqual(sections(), ["Accessibility (2)", "Theme (1)"]);
    });

    it("re-fetches when the frame underneath changes", async () => {
        // New pixels ⇒ new geometry and new facts. Serving the old payload would describe a frame
        // that is no longer on the stage.
        const stub = stubFetch();
        await mount();
        await tick("a11y");
        assert.equal(stub.urls.length, 1);
        document
            .getElementById("cp-img")!
            .setAttribute("data-cp-src", "/m3/render/plain.Button.png?at=def");
        for (let i = 0; i < 6; i++) await flush();
        assert.deepEqual(stub.urls[1], "/m3/render/plain.Button.a11y?at=def");
    });

    it("lights up the legend row for the box under the pointer, and back", async () => {
        stubFetch();
        await mount();
        await tick("a11y");
        boxes()[1].dispatchEvent(new Event("mouseenter"));
        assert.equal(
            rows()[1].classList.contains("cp-inspect-entry-active"),
            true,
        );
        assert.equal(
            boxes()[1].classList.contains("cp-inspect-box-active"),
            true,
        );
        assert.equal(
            rows()[0].classList.contains("cp-inspect-entry-active"),
            false,
        );
        boxes()[1].dispatchEvent(new Event("mouseleave"));
        assert.equal(
            rows()[1].classList.contains("cp-inspect-entry-active"),
            false,
        );
    });

    it("lights up the box for a legend row reached by keyboard", async () => {
        // The legend is a keyboard path into the same highlight; hover alone would leave it
        // unreachable without a pointer.
        stubFetch();
        await mount();
        await tick("a11y");
        rows()[0].dispatchEvent(new Event("focus"));
        assert.equal(
            boxes()[0].classList.contains("cp-inspect-box-active"),
            true,
        );
    });

    it("puts the layers in the address bar without stacking history", async () => {
        // A reading aid over the same frame, not a different render — so `replaceState`.
        stubFetch();
        await mount();
        await tick("a11y");
        assert.equal(
            new URLSearchParams(location.search).get("inspect"),
            "a11y",
        );
        await tick("theme");
        assert.equal(
            new URLSearchParams(location.search).get("inspect"),
            "a11y,theme",
        );
    });

    it("restores the layers a deep link names", async () => {
        const stub = stubFetch();
        await mount("?inspect=theme");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(toggle("theme").checked, true);
        assert.equal(toggle("a11y").checked, false);
        assert.deepEqual(stub.urls, [
            "/m3/render/plain.Button.annotations?at=abc",
        ]);
    });

    it("clears the overlay when the last layer is un-ticked", async () => {
        stubFetch();
        await mount();
        await tick("a11y");
        const el = toggle("a11y");
        el.checked = false;
        el.dispatchEvent(new Event("change"));
        for (let i = 0; i < 3; i++) await flush();
        assert.equal(boxes().length, 0);
        assert.equal(legend().hidden, true);
        assert.equal(viewer().hasAttribute("data-inspect"), false);
        assert.equal(new URLSearchParams(location.search).get("inspect"), null);
    });

    it("draws nothing rather than erroring when the host has no such product", async () => {
        stubFetch({ fail: true });
        await mount();
        await tick("a11y");
        assert.equal(boxes().length, 0);
        assert.equal(legend().hidden, true);
    });

    it("stays silent on a viewer with no inspect group", async () => {
        stubFetch();
        document.body.innerHTML = `
          <cp-inspect-layers></cp-inspect-layers>
          <div class="cp-viewer"><img id="cp-img"></div>`;
        await flush();
        assert.equal(document.querySelectorAll(".cp-inspect-box").length, 0);
    });
});
