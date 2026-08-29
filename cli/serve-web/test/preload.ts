// Node preload for the component suite. Vue's runtime-dom captures `document` when its module
// evaluates, so happy-dom must exist before Mocha links any test or component module.

import { GlobalRegistrator } from "@happy-dom/global-registrator";

if (!globalThis.document) {
    GlobalRegistrator.register({ url: "https://preview.example/catalog/" });
}

const { Fragment, createTextVNode, h, render } = await import("vue");
window.cpVue = { Fragment, createTextVNode, h, render };
