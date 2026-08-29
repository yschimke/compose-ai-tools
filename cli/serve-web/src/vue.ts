import type { VNode, VNodeChild } from "vue";

/** Vue's page-global renderer, installed synchronously by `vue-runtime.js`. */
const runtime = window.cpVue;

export const Fragment = runtime.Fragment;
export const createTextVNode = runtime.createTextVNode;
export const h = runtime.h;
export const render = runtime.render;
export type { VNode, VNodeChild };
