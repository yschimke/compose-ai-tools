// Which preview a card streams, where its socket points, and what a refused lane says.

import assert from "node:assert/strict";
import {
    closeReason,
    previewIdOf,
    socketUrl,
    startsHold,
    themeProviderOf,
    visibilityMessage,
} from "../src/live/session.js";

describe("previewIdOf", () => {
    const both = { l: "plain.Button", d: "plain.ButtonDark" };

    it("follows what a swap card is SHOWING, not the server-side default", () => {
        // The filter script re-points `data-bg-theme` as it swaps. Pinning `l` here opens the light
        // preview from a dark grid — a live session of a component the visitor is not looking at.
        assert.equal(previewIdOf(both, true, "dark"), "plain.ButtonDark");
        assert.equal(previewIdOf(both, true, "light"), "plain.Button");
        assert.equal(previewIdOf(both, true, null), "plain.Button");
    });

    it("takes the one id a non-swap card has, whichever side it is", () => {
        assert.equal(previewIdOf(both, false, "dark"), "plain.Button");
        assert.equal(previewIdOf({ d: "only.Dark" }, false, null), "only.Dark");
    });

    it("falls back to the other side when a swap card is missing one", () => {
        assert.equal(
            previewIdOf({ l: "only.Light" }, true, "dark"),
            "only.Light",
        );
        assert.equal(
            previewIdOf({ d: "only.Dark" }, true, "light"),
            "only.Dark",
        );
    });

    it("has nothing to open for an entry with neither", () => {
        assert.equal(previewIdOf({}, true, "dark"), "");
        assert.equal(previewIdOf({}, false, null), "");
    });
});

describe("themeProviderOf", () => {
    it("takes the provider a declared theme names", () => {
        assert.equal(themeProviderOf("theme:dracula"), "dracula");
    });

    it("is not a background choice", () => {
        // The same chip row carries both; reading a `bg:` choice as a provider would send the
        // daemon a theme that does not exist.
        assert.equal(themeProviderOf("bg:dark"), "");
        assert.equal(themeProviderOf("light"), "");
    });

    it("has no provider when nothing is pressed", () => {
        assert.equal(themeProviderOf(null), "");
        assert.equal(themeProviderOf(""), "");
    });
});

describe("socketUrl", () => {
    const https = { protocol: "https:", host: "preview.example" };
    const http = { protocol: "http:", host: "localhost:8080" };

    it("follows the page's scheme", () => {
        // A `ws:` socket from an https page is blocked as mixed content, which surfaces as a lane
        // that simply never connects — no error anyone can act on.
        assert.ok(
            socketUrl({}, "x", https).startsWith("wss://preview.example/ws/x?"),
        );
        assert.ok(
            socketUrl({}, "x", http).startsWith("ws://localhost:8080/ws/x?"),
        );
    });

    it("asks for webp, and keeps the session keys the server handed it", () => {
        assert.equal(
            socketUrl({ base: "/m3", query: "token=t&session=s" }, "p", https),
            "wss://preview.example/m3/ws/p?token=t&session=s&codec=webp",
        );
    });

    it("carries the declared theme so the session opens under it", () => {
        assert.equal(
            socketUrl({}, "p", https, "dracula"),
            "wss://preview.example/ws/p?codec=webp&themeProvider=dracula",
        );
    });

    it("encodes an id and a provider that need it", () => {
        assert.equal(
            socketUrl({}, "a/b c", https, "a&b"),
            "wss://preview.example/ws/a%2Fb%20c?codec=webp&themeProvider=a%26b",
        );
    });
});

describe("closeReason", () => {
    it("names the two conditions the server reports by code", () => {
        assert.equal(
            closeReason({ code: 1013 }),
            "Live preview is at capacity — try again shortly.",
        );
        assert.equal(closeReason({ code: 1008 }), "Live preview unauthorized.");
    });

    it("passes the server's own words through when it sent some", () => {
        assert.equal(
            closeReason({ code: 1003, reason: "no live lane" }),
            "Live preview unavailable: no live lane",
        );
    });

    it("says the same thing the viewer says for a bare abnormal close", () => {
        // The divergence this module exists to end. `catalog-live.js` claimed parity with
        // `viewer.js` in a comment and stopped its fallback at "couldn't connect", dropping the half
        // that says where to look. This is the branch that fires MOST — 1006, typically a proxy 502
        // on the WS upgrade — so the shorter wording was what most people actually saw.
        const viewer =
            "Live preview couldn't connect — the live stream may be unavailable on this server.";
        assert.equal(closeReason({ code: 1006 }), viewer);
        assert.equal(closeReason({}), viewer);
        assert.equal(closeReason(null), viewer);
    });
});

describe("startsHold", () => {
    it("claims an unmodified primary press", () => {
        assert.equal(startsHold({ button: 0 }), true);
    });

    it("leaves every modified press to the link it is on", () => {
        // ctrl/meta opens a new tab, shift a new window. Claiming those for a gesture breaks the
        // card's ordinary behaviour for exactly the people who navigate that way.
        assert.equal(startsHold({ button: 0, ctrlKey: true }), false);
        assert.equal(startsHold({ button: 0, metaKey: true }), false);
        assert.equal(startsHold({ button: 0, shiftKey: true }), false);
        assert.equal(startsHold({ button: 0, altKey: true }), false);
    });

    it("is not a right-click or a middle-click", () => {
        assert.equal(startsHold({ button: 1 }), false);
        assert.equal(startsHold({ button: 2 }), false);
    });
});

describe("visibilityMessage", () => {
    it("says which way the lane flipped", () => {
        assert.deepEqual(JSON.parse(visibilityMessage(false)), {
            type: "visibility",
            visible: false,
        });
        assert.deepEqual(JSON.parse(visibilityMessage(true)), {
            type: "visibility",
            visible: true,
        });
    });

    it("carries a throttled fps only when there is one to carry", () => {
        // No fps means the server's own default (1 fps); a non-positive one would mean "never".
        assert.deepEqual(JSON.parse(visibilityMessage(false, 2)), {
            type: "visibility",
            visible: false,
            fps: 2,
        });
        assert.equal("fps" in JSON.parse(visibilityMessage(false, 0)), false);
        // An fps alongside `visible: true` is meaningless — the server ignores it either way, so
        // it does not go on the wire.
        assert.equal("fps" in JSON.parse(visibilityMessage(true, 2)), false);
    });
});
