// Byte-for-byte pin of what `<cp-inspect-layers>` draws on the VIEWER.
//
// Its siblings pin behaviour — which endpoint is fetched, what invalidates a fetch, what lights up
// what. This one pins the *markup*, and it exists for one reason: the element's DOM wiring was
// generalised to accept a host other than the viewer (the focused comparison's Actual panel mounts
// it too), and a generalisation that quietly reshapes the surface it was generalised FROM is a
// regression on a shipped page that no behavioural assertion would catch. Every class name, every
// attribute and their order, every badge and its text are compared as one string.
//
// So: if this fails, the viewer's overlay changed. That is either the point of your commit — in
// which case update the strings and say so — or it is the bug this test was written to find.

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

const LAYER =
    '<div class="cp-inspect-box" data-cp-kind="a11y" data-level="info" title="Save · Button" style="--cp-inspect-color: #f28b82;"><span class="cp-inspect-badge">1</span></div>' +
    '<div class="cp-inspect-box" data-cp-kind="a11y" data-level="info" title="Cancel · Button" style="--cp-inspect-color: #aecbfa;"><span class="cp-inspect-badge">2</span></div>' +
    '<div class="cp-inspect-box" data-cp-kind="typography" data-level="info" title="20sp"><span class="cp-inspect-badge">1</span></div>' +
    '<div class="cp-inspect-box" data-cp-kind="theme" data-level="info" title="surface"><span class="cp-inspect-badge">1</span></div>';

const LEGEND =
    '<div class="cp-inspect-legend-head">Inspect<span class="cp-inspect-legend-count">4</span></div>' +
    '<div class="cp-inspect-section"><div class="cp-inspect-section-head">Accessibility (2)</div><ol class="cp-inspect-list">' +
    '<li class="cp-inspect-entry" data-cp-entry="a11y-0" data-cp-kind="a11y" data-level="info" tabindex="0" style="--cp-inspect-color: #f28b82;"><span class="cp-inspect-badge">1</span><span class="cp-inspect-text"><strong>Save</strong><span class="cp-inspect-detail">Button</span></span></li>' +
    '<li class="cp-inspect-entry" data-cp-entry="a11y-1" data-cp-kind="a11y" data-level="info" tabindex="0" style="--cp-inspect-color: #aecbfa;"><span class="cp-inspect-badge">2</span><span class="cp-inspect-text"><strong>Cancel</strong><span class="cp-inspect-detail">Button</span></span></li>' +
    "</ol></div>" +
    '<div class="cp-inspect-section"><div class="cp-inspect-section-head">Typography (1)</div><ol class="cp-inspect-list">' +
    '<li class="cp-inspect-entry" data-cp-entry="typography-0" data-cp-kind="typography" data-level="info" tabindex="0" title="20sp"><span class="cp-inspect-badge">1</span><span class="cp-inspect-text"><strong>Title</strong><span class="cp-inspect-detail">20sp</span></span></li>' +
    "</ol></div>" +
    '<div class="cp-inspect-section"><div class="cp-inspect-section-head">Theme (1)</div><ol class="cp-inspect-list">' +
    '<li class="cp-inspect-entry" data-cp-entry="theme-0" data-cp-kind="theme" data-level="info" tabindex="0"><span class="cp-inspect-badge">1</span><span class="cp-inspect-text"><strong>surface</strong></span></li>' +
    "</ol></div>";

async function mountViewer(): Promise<void> {
    window.history.replaceState(null, "", "/m3/p/plain.Button");
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

async function tick(kind: string): Promise<void> {
    const el = document.querySelector<HTMLInputElement>(
        `[data-cp-inspect="${kind}"]`,
    )!;
    el.checked = true;
    el.dispatchEvent(new Event("change"));
    for (let i = 0; i < 5; i++) await flush();
}

describe("<cp-inspect-layers> viewer markup", () => {
    beforeEach(() => {
        globalThis.fetch = (async (url: string) => ({
            ok: true,
            json: async () =>
                String(url).includes(".a11y") ? A11Y : ANNOTATIONS,
        })) as unknown as typeof fetch;
    });

    afterEach(() => {
        resetDom();
        window.history.replaceState(null, "", "/");
    });

    it("draws the boxes and the legend exactly as it always has", async () => {
        await mountViewer();
        for (const kind of ["a11y", "typography", "theme"]) await tick(kind);
        assert.equal(
            document.getElementById("cp-inspect-layer")!.innerHTML,
            LAYER,
        );
        assert.equal(
            document.getElementById("cp-inspect-legend")!.innerHTML,
            LEGEND,
        );
        assert.equal(
            document.querySelector(".cp-viewer")!.getAttribute("data-inspect"),
            "on",
        );
    });
});
