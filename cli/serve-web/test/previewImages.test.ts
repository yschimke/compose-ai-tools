import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installPreviewImageStates } from "../src/chrome/previewImages.js";

describe("preview image states", () => {
    afterEach(() => resetDom());

    it("shows loading, replaces browser errors, and offers retry", () => {
        document.body.innerHTML =
            '<a class="cp-card"><div class="cp-imgwrap"><img src="/render/card.png" alt="Card"></div></a>';
        installPreviewImageStates();
        const host = document.querySelector<HTMLElement>(".cp-imgwrap")!;
        const img = host.querySelector("img")!;

        assert.equal(host.dataset.imageState, "loading");
        img.dispatchEvent(new Event("error"));
        assert.equal(host.dataset.imageState, "error");
        assert.equal(host.querySelector(".cp-image-error")?.getAttribute("role"), "alert");
        assert.equal(host.querySelector("button")?.textContent, "Retry");

        img.dispatchEvent(new Event("load"));
        assert.equal(host.dataset.imageState, "loaded");
        assert.equal(host.querySelector(".cp-image-error"), null);
    });
});
