// The guard on every URL the spec lane hands to a canvas, as a table.
//
// The same guard `viewer.js` puts on the spec raster and the Wasm frame. A server-set attribute is
// still DOM text, so these are the inputs that must never reach `drawImage`.

import assert from "node:assert/strict";
import { sameOrigin, sameOriginNavigation } from "../src/dom/sameOrigin.js";

const ORIGIN = "https://preview.example";

describe("sameOrigin", () => {
    it("resolves a relative URL against the page", () => {
        assert.equal(
            sameOrigin("/reference/Button.png", ORIGIN),
            "https://preview.example/reference/Button.png",
        );
    });

    it("keeps an absolute URL that is already ours", () => {
        assert.equal(
            sameOrigin(`${ORIGIN}/render/Button.png?at=abc`, ORIGIN),
            `${ORIGIN}/render/Button.png?at=abc`,
        );
    });

    it("admits a blob URL this page minted", () => {
        // One minted here from our own fetch already is ours, and the override lane draws from it.
        // A real one is a UUID; anything else would only be testing URL normalisation.
        const blob =
            "blob:https://preview.example/6f1a2c3d-4b5e-6789-abcd-ef0123456789";
        assert.equal(sameOrigin(blob, ORIGIN), blob);
    });

    it("refuses a scheme that could execute or smuggle bytes", () => {
        for (const bad of [
            "javascript:alert(1)",
            "data:image/svg+xml,<svg onload='alert(1)'/>",
            "file:///etc/passwd",
        ]) {
            assert.equal(sameOrigin(bad, ORIGIN), "", bad);
        }
    });

    it("refuses another origin, however it is spelled", () => {
        for (const bad of [
            "https://evil.example/x.png",
            "//evil.example/x.png",
            "http://preview.example/x.png",
            "https://preview.example.evil.test/x.png",
        ]) {
            assert.equal(sameOrigin(bad, ORIGIN), "", bad);
        }
    });

    it("answers empty for nothing at all", () => {
        for (const nothing of ["", null, undefined]) {
            assert.equal(sameOrigin(nothing, ORIGIN), "");
        }
    });
});

describe("sameOriginNavigation", () => {
    it("takes the same ours-or-nothing answer for an ordinary URL", () => {
        assert.equal(
            sameOriginNavigation("/auth/github", ORIGIN),
            `${ORIGIN}/auth/github`,
        );
        assert.equal(sameOriginNavigation("https://evil.example/", ORIGIN), "");
        assert.equal(sameOriginNavigation("javascript:alert(1)", ORIGIN), "");
    });

    it("refuses a blob the raster guard admits", () => {
        // The reason this is a second function rather than a shared one. A blob this page minted is
        // safe to DRAW; navigating to one hands the visitor a document assembled client-side at our
        // own origin, which is a different question — and never one a sign-in link needs to ask.
        // Reusing `sameOrigin` outright would have widened a navigation sink to admit it silently,
        // as a side effect of removing a duplicate.
        const blob = `blob:${ORIGIN}/6f3c1a2e-9d7b-4f10-8a55-2b6e0c9d1f34`;
        assert.equal(sameOrigin(blob, ORIGIN), blob);
        assert.equal(sameOriginNavigation(blob, ORIGIN), "");
    });
});
