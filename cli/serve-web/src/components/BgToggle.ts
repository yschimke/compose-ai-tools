// `<cp-bg-toggle>` — the Transparent toggle, shared by the catalog grid and the
// single-preview viewer. Replaces `assets/bg-toggle.js`.
//
// The preview server shows components on a SOLID surface by default, so a
// transparent sticker reads like a real component instead of washing out against
// the page. This button flips the whole page to a checkerboard to inspect the raw
// alpha, and persists the choice per-visitor.
//
// ONE button, not a Background / Transparent pair. The axis has two states and a
// default, which is exactly what `aria-pressed` on a single toggle expresses: the
// label names the non-default state and pressed-ness says whether it is on. The
// pair spent twice the toolbar width to say the same thing, and half of it was
// always a button that did nothing when clicked.
//
// Light DOM (provided by `VueElement`), so the existing `serve.css`
// `.cp-bg-btn` rules apply unchanged and the button keeps sitting in the toolbar's
// flex flow. Shadow DOM would need every one of those rules restated or piped
// through custom properties, for a control that has no encapsulation problem.
//
// The server emits `<cp-bg-toggle label="…"></cp-bg-toggle>` and this renders the
// button, rather than the server emitting the button and this adopting it. One
// source of truth for the markup beats two, and the control is inert without JS
// anyway — there is no no-JS rendering worth preserving. `serve.css` gives the
// element `display: contents`, so the button — not the wrapper — stays the
// toolbar's flex item and the upgraded control lays out exactly as the bare
// button did.

import { h, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import {
    isTransparent,
    subscribe,
    toggle,
    wirePopstate,
} from "../backgroundChoice.js";

@customElement("cp-bg-toggle")
export class BgToggle extends VueElement {
    /** Tooltip text; the server varies it per surface. */
    private get label(): string {
        return this.getAttribute("label") ?? "";
    }

    private pressed = isTransparent();

    private unsubscribe?: () => void;

    connectedCallback(): void {
        super.connectedCallback();
        wirePopstate();
        this.pressed = isTransparent();
        this.unsubscribe = subscribe(() => {
            this.pressed = isTransparent();
            this.requestUpdate();
        });
        this.requestUpdate();
    }

    disconnectedCallback(): void {
        this.unsubscribe?.();
        this.unsubscribe = undefined;
        super.disconnectedCallback();
    }

    protected renderVue(): VNode {
        return h(
            "button",
            {
                type: "button",
                class: "cp-bg-btn",
                "aria-pressed": this.pressed ? "true" : "false",
                title: this.label,
                onClick: () => toggle(),
            },
            "Transparent",
        );
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-bg-toggle": BgToggle;
    }
}
