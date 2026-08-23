// Which DOM a mounted set of inspection layers works against.
//
// The interesting cases are all refusals. A host resolver that "helpfully" falls back to a viewer id
// the page does not have would mount the layers over the wrong element and draw the render's boxes
// onto whatever happened to answer that selector — a wrong picture that looks deliberate. Every
// missing part therefore makes the mount inert instead.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { panelHost, resolveHost, viewerHost } from "../src/inspect/host.js";
import { baseFrom } from "../src/inspect/layers.js";

function mount(attrs: Record<string, string>): HTMLElement {
    const el = document.createElement("cp-inspect-layers");
    for (const [key, value] of Object.entries(attrs))
        el.setAttribute(key, value);
    document.body.appendChild(el);
    return el;
}

const VIEWER = `
  <div class="cp-viewer" data-preview-id="plain.Button">
    <img id="cp-img" data-cp-src="/m3/render/plain.Button.png">
    <div id="cp-inspect-layer"></div>
    <div id="cp-inspect-legend"></div>
    <label><input class="cp-inspect" data-cp-inspect="a11y" type="checkbox"></label>
  </div>`;

const PANEL = `
  <div class="cp-compare-shot" id="cp-actual-panel" data-preview-id="plain.Button">
    <img src="/m3/render/plain.Button.png?token=t">
    <div id="cp-derived-layer"></div>
  </div>
  <div id="cp-derived-legend"></div>
  <label><input class="cp-derived-inspect" data-cp-inspect="typography" type="checkbox"></label>`;

describe("baseFrom", () => {
    it("strips the viewer's and the comparison's own segment alike", () => {
        // One rule for both surfaces that mount the layers: they are addressed the same way, and a
        // second rule is a second thing to forget when a third surface arrives.
        assert.equal(baseFrom("/compose-m3/p/plain.Button"), "/compose-m3");
        assert.equal(
            baseFrom("/compose-m3/compare/plain.Button"),
            "/compose-m3",
        );
        assert.equal(baseFrom("/p/plain.Button"), "");
    });

    it("leaves a path that names neither alone", () => {
        assert.equal(baseFrom("/compose-m3/parity"), "/compose-m3/parity");
    });
});

describe("viewerHost", () => {
    afterEach(() => resetDom());

    it("reads the viewer's own parts", () => {
        document.body.innerHTML = VIEWER;
        const host = viewerHost()!;
        assert.equal(host.root.className, "cp-viewer");
        assert.equal(host.frame.id, "cp-img");
        assert.equal(host.toggles.length, 1);
        // `data-cp-src` and not `src`: the viewer swaps frames as the knobs change and stamps that
        // attribute once the replacement has DECODED, so it is the honest "these are the pixels on
        // screen" signal. See `InspectHost.frameSource`.
        assert.equal(host.frameSource, "data-cp-src");
        assert.equal(host.anchor, "offset");
        assert.equal(host.hasSpecModes, true);
    });

    it("is inert on a viewer with no inspect group", () => {
        document.body.innerHTML = VIEWER.replace(/<label>.*<\/label>/s, "");
        assert.equal(viewerHost(), null);
    });
});

describe("panelHost", () => {
    afterEach(() => resetDom());

    it("reads the parts the mount names, wherever they sit", () => {
        // Deliberately not nested under the host: on the focused comparison the frame is inside the
        // Actual panel while the legend is a sibling of the whole grid.
        document.body.innerHTML = PANEL;
        const host = panelHost(
            mount({
                "data-cp-host": "#cp-actual-panel",
                "data-cp-layer": "#cp-derived-layer",
                "data-cp-legend": "#cp-derived-legend",
                "data-cp-toggles": ".cp-derived-inspect",
                "data-cp-base": "/m3",
            }),
        )!;
        assert.equal(host.root.id, "cp-actual-panel");
        assert.equal(host.layer.id, "cp-derived-layer");
        assert.equal(host.legend.id, "cp-derived-legend");
        assert.equal(host.toggles.length, 1);
        assert.equal(host.base, "/m3");
        // A server-rendered panel never swaps its frame, so `src` IS the decoded frame.
        assert.equal(host.frameSource, "src");
        // The shot centres the layer in CSS; writing left/top would fight the translate.
        assert.equal(host.anchor, "centred");
        // No spec stage on a comparison panel — and so no `data-mode` reading to go wrong.
        assert.equal(host.hasSpecModes, false);
        assert.equal(host.specFrame, null);
    });

    it("refuses a mount whose named part is not there", () => {
        document.body.innerHTML = PANEL;
        for (const missing of [
            "data-cp-host",
            "data-cp-layer",
            "data-cp-legend",
            "data-cp-toggles",
        ]) {
            const attrs: Record<string, string> = {
                "data-cp-host": "#cp-actual-panel",
                "data-cp-layer": "#cp-derived-layer",
                "data-cp-legend": "#cp-derived-legend",
                "data-cp-toggles": ".cp-derived-inspect",
            };
            attrs[missing] = "#nothing-here";
            assert.equal(
                panelHost(mount(attrs)),
                null,
                `${missing} should make the mount inert`,
            );
        }
    });

    it("refuses a host panel carrying no frame", () => {
        document.body.innerHTML = PANEL.replace(/<img[^>]*>/, "");
        assert.equal(
            panelHost(
                mount({
                    "data-cp-host": "#cp-actual-panel",
                    "data-cp-layer": "#cp-derived-layer",
                    "data-cp-legend": "#cp-derived-legend",
                    "data-cp-toggles": ".cp-derived-inspect",
                }),
            ),
            null,
        );
    });
});

describe("resolveHost", () => {
    afterEach(() => resetDom());

    it("gives an unadorned tag the viewer, exactly as before", () => {
        document.body.innerHTML = VIEWER;
        assert.equal(resolveHost(mount({}))?.frame.id, "cp-img");
    });

    it("never falls back to the viewer for a tag that named a host", () => {
        // The load-bearing half. A panel mount whose page is missing a part must draw NOTHING —
        // falling through to `.cp-viewer` would put this page's boxes on another page's frame.
        document.body.innerHTML = VIEWER + PANEL;
        assert.equal(resolveHost(mount({ "data-cp-host": "#nope" })), null);
    });
});
