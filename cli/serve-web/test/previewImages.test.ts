import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installPreviewImageStates } from "../src/chrome/previewImages.js";

describe("preview image states", () => {
    afterEach(() => resetDom());

    it("shows loading, replaces browser errors, and offers retry", () => {
        document.body.innerHTML =
            '<a class="cp-card"><div class="cp-imgwrap"><img src="/render/card.png" alt="Card"></div></a>';
        const host = document.querySelector<HTMLElement>(".cp-imgwrap")!;
        const img = host.querySelector("img")!;
        Object.defineProperty(img, "complete", {
            configurable: true,
            value: false,
        });
        installPreviewImageStates();

        assert.equal(host.dataset.imageState, "loading");
        img.dispatchEvent(new Event("error"));
        assert.equal(host.dataset.imageState, "error");
        assert.equal(
            host.querySelector(".cp-image-error")?.getAttribute("role"),
            "alert",
        );
        assert.equal(host.querySelector("button")?.textContent, "Retry");

        img.dispatchEvent(new Event("load"));
        assert.equal(host.dataset.imageState, "loaded");
        assert.equal(host.querySelector(".cp-image-error"), null);
    });

    it("recognizes an image that failed before the listeners were installed", () => {
        document.body.innerHTML =
            '<div class="cp-imgwrap"><img src="/missing.png" alt="Missing"></div>';
        const img = document.querySelector<HTMLImageElement>("img")!;
        Object.defineProperty(img, "complete", {
            configurable: true,
            value: true,
        });
        Object.defineProperty(img, "naturalWidth", {
            configurable: true,
            value: 0,
        });

        installPreviewImageStates();

        const host = document.querySelector<HTMLElement>(".cp-imgwrap")!;
        assert.equal(host.dataset.imageState, "error");
        assert.equal(host.querySelector("button")?.textContent, "Retry");
    });

    it("keeps an error scoped to its visible viewer lane", async () => {
        document.body.innerHTML = `
          <div class="cp-stage">
            <img id="snapshot" src="/snapshot.png" alt="Snapshot">
            <img id="spec" src="/missing-spec.png" alt="Spec" hidden>
          </div>`;
        installPreviewImageStates();
        const host = document.querySelector<HTMLElement>(".cp-stage")!;
        const snapshot = document.querySelector<HTMLImageElement>("#snapshot")!;
        const spec = document.querySelector<HTMLImageElement>("#spec")!;
        snapshot.dispatchEvent(new Event("load"));

        snapshot.hidden = true;
        spec.hidden = false;
        spec.dispatchEvent(new Event("error"));
        await Promise.resolve();
        assert.equal(host.dataset.imageState, "error");

        spec.hidden = true;
        snapshot.hidden = false;
        await Promise.resolve();
        assert.equal(host.dataset.imageState, "loaded");
        assert.equal(host.querySelector(".cp-image-error"), null);
    });
});
