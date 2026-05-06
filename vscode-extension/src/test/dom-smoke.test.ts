// Smoke tests proving the happy-dom global registration set up by
// `setup-dom.ts` actually exposes `document` / `window` / standard
// element types to test code, so DOM-touching webview modules can be
// unit-tested without each test having to spin up its own environment.
//
// Real-feature DOM tests live in their own files (e.g. `liveState.test.ts`,
// `cardBuilder.test.ts`) once the corresponding source file is added to
// the host tsconfig's `files` list. This file just smoke-checks the
// infrastructure.

import * as assert from "assert";

describe("DOM smoke (happy-dom)", () => {
    it("exposes document on globalThis after happy-dom registration", () => {
        assert.ok(typeof document !== "undefined", "document should be global");
        assert.ok(typeof window !== "undefined", "window should be global");
    });

    it("createElement returns a real HTMLElement", () => {
        const div = document.createElement("div");
        assert.ok(div instanceof HTMLElement);
        assert.strictEqual(div.tagName, "DIV");
    });

    it("classList toggles work", () => {
        const div = document.createElement("div");
        div.classList.add("foo", "bar");
        assert.strictEqual(div.className, "foo bar");
        div.classList.remove("foo");
        assert.strictEqual(div.className, "bar");
        assert.strictEqual(div.classList.contains("bar"), true);
        assert.strictEqual(div.classList.contains("foo"), false);
    });

    it("dataset round-trips through `data-*` attributes", () => {
        const div = document.createElement("div");
        div.dataset.previewId = "preview:A";
        assert.strictEqual(div.getAttribute("data-preview-id"), "preview:A");
        assert.strictEqual(div.dataset.previewId, "preview:A");
    });

    it("appendChild + querySelector navigate the DOM tree", () => {
        const root = document.createElement("div");
        const child = document.createElement("span");
        child.className = "leaf";
        root.appendChild(child);
        assert.strictEqual(root.querySelector(".leaf"), child);
        assert.strictEqual(root.children.length, 1);
    });

    it("getElementById finds elements attached to document.body", () => {
        const div = document.createElement("div");
        div.id = "test-target";
        document.body.appendChild(div);
        try {
            assert.strictEqual(document.getElementById("test-target"), div);
        } finally {
            document.body.removeChild(div);
        }
    });

    it("CSS custom properties round-trip via element.style", () => {
        const div = document.createElement("div");
        div.style.setProperty("--size-ratio", "0.5");
        assert.strictEqual(div.style.getPropertyValue("--size-ratio"), "0.5");
        div.style.removeProperty("--size-ratio");
        assert.strictEqual(div.style.getPropertyValue("--size-ratio"), "");
    });
});
