import {
    Fragment,
    createTextVNode,
    h,
    render,
    type VNode,
    type VNodeChild,
} from "vue";

/**
 * The one Vue renderer shared by every serve-web surface bundle on a page.
 *
 * Surface bundles are classic scripts rather than modules because several server pages execute
 * inline bootstraps between them. Publishing this deliberately small API keeps that ordering while
 * allowing the browser to download and parse Vue once, independently of the page-specific controls.
 */
window.cpVue = { Fragment, createTextVNode, h, render };

declare global {
    interface Window {
        cpVue: {
            Fragment: typeof Fragment;
            createTextVNode: typeof createTextVNode;
            h: typeof h;
            render: typeof render;
        };
    }
}

// Keep the types referenced so TypeScript checks that the runtime API remains compatible with the
// VNode shapes consumed by the façade. They are erased from the emitted asset.
export type { VNode, VNodeChild };
