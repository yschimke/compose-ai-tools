import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/vue-spike/elements.js";

describe("Vue migration feasibility", () => {
    afterEach(() => resetDom());

    it("renders reactive controls into light DOM", async () => {
        document.body.innerHTML =
            '<cp-vue-render-spike label="Add one"></cp-vue-render-spike>';
        await flush();
        const element = document.querySelector("cp-vue-render-spike")!;
        const button = element.querySelector("button")!;
        assert.equal(element.shadowRoot, null);
        assert.equal(button.getAttribute("aria-label"), "Add one");
        assert.equal(button.textContent, "0");
        button.click();
        await flush();
        assert.equal(button.textContent, "1");
    });

    it("wires server-rendered markup and removes the listener on disconnect", async () => {
        document.body.innerHTML = `
          <button data-vue-spike-target>Outside Vue</button>
          <cp-vue-controller-spike></cp-vue-controller-spike>`;
        await flush();
        const target = document.querySelector<HTMLButtonElement>(
            "[data-vue-spike-target]",
        )!;
        const element = document.querySelector("cp-vue-controller-spike")!;
        target.click();
        assert.equal(element.getAttribute("data-count"), "1");
        element.remove();
        await flush();
        target.click();
        assert.equal(element.getAttribute("data-count"), "1");
    });

    it("renders async data and ignores non-string wire values", async () => {
        globalThis.fetch = (async () => ({
            json: async () => ["Published", 42, "Live"],
        })) as unknown as typeof fetch;
        document.body.innerHTML =
            '<cp-vue-async-spike src="/api/lanes"></cp-vue-async-spike>';
        await flush();
        await flush();
        const rows = [
            ...document.querySelectorAll("cp-vue-async-spike li"),
        ].map((row) => row.textContent);
        assert.deepEqual(rows, ["Published", "Live"]);
    });
});
