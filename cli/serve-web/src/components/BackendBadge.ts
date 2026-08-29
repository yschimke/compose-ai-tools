// `<cp-backend-badge>` — the provenance badge on the viewer stage. Replaces
// `assets/backend-badge.js`.
//
// It names the tier that actually painted what you are looking at, because the
// viewer's lanes are not interchangeable: a published snapshot, a daemon stream,
// an in-browser Wasm app, and an imported design spec can all occupy the same
// stage, and "is this pixel ours or theirs" is the first question anyone asks of
// a preview server.
//
// The icon carries the state change. ▶ for an interactive live lane, ▪ for a
// static one, ◇ for the design spec (what is on the stage is the imported
// reference, not something this server rendered, so it must not wear a
// renderer's icon), and ◌ while a lane is still activating. Those are the
// visible signal that the Static⇄Live toggle did something — the accent colours
// in `serve.css` say the same thing, but only to people who can see them.
//
// The badge does not own its state. `.cp-viewer`'s `data-mode` / `data-pending`
// attributes are the single source of truth — `viewer.js` drives the lanes and
// writes them — so this observes those attributes rather than being called. One
// writer, many readers; a lane that forgets to notify the badge cannot exist.
//
// The HOST is the badge, not a wrapper around one: the server emits
// `<cp-backend-badge class="cp-backend" id="cp-backend" role="status"
// aria-live="polite">`, and this renders only the text inside it. That keeps the
// live region in the server's HTML — a `role="status"` element created by script
// with its text already in place is not announced by screen readers, so
// rendering the span from here would have quietly cost the announcement that is
// the whole point of the attribute. It also means `serve.css`'s `.cp-backend`
// rules (absolute in `.cp-stage`, the `[data-live]` / `[data-pending]` accents)
// apply to the host unchanged, with no `display: contents` wrapper to reason
// about.

import { createTextVNode, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";

/** Lanes that are interactive rather than a still image. */
function isLive(mode: string | null): boolean {
    return mode === "wasm" || mode === "live";
}

@customElement("cp-backend-badge")
export class BackendBadge extends VueElement {
    /** `.cp-viewer`'s `data-mode` — which lane owns the stage. */
    private mode: string | null = null;

    /** `.cp-viewer`'s `data-pending` — a lane activating, carrying its own copy. */
    private pending: string | null = null;

    private root: HTMLElement | null = null;
    private observer?: MutationObserver;

    connectedCallback(): void {
        super.connectedCallback();
        // `closest`, not a document query: the badge lives inside the stage of the
        // viewer it reports on, so the relationship is structural and stays correct
        // if a page ever carries more than one viewer.
        this.root = this.closest<HTMLElement>(".cp-viewer");
        if (!this.root) return;
        this.observer = new MutationObserver(() => this.readRoot());
        this.observer.observe(this.root, {
            attributes: true,
            attributeFilter: ["data-mode", "data-pending"],
        });
        this.readRoot();
        // The server can place lane metadata after the stage. Read it once more
        // after parsing so source order cannot leave the initial badge stale.
        queueMicrotask(() => {
            if (this.isConnected) this.requestUpdate();
        });
    }

    disconnectedCallback(): void {
        this.observer?.disconnect();
        this.observer = undefined;
        this.root = null;
        super.disconnectedCallback();
    }

    private readRoot(): void {
        this.mode = this.root?.getAttribute("data-mode") ?? null;
        this.pending = this.root?.getAttribute("data-pending") ?? null;
        this.requestUpdate();
    }

    private label(): string {
        const mode = this.mode;
        if (mode === "wasm") return "▶ CMP-WASM";
        if (mode === "live") {
            return `▶ ${this.root?.getAttribute("data-live-backend") || "Live"}`;
        }
        if (mode === "svg") return "▪ SVG";
        // A recording is not a render, and the badge is a provenance claim — so falling through
        // to the snapshot label would attribute animated pixels to the still renderer. Named
        // generically rather than from the picked capture: the badge re-renders on a MODE change,
        // and switching capture inside the lane is not one, so a per-capture name would go stale
        // the moment the picker was used.
        if (mode === "motion") return "\u25B6 Recording";
        if (mode === "spec") {
            const lane = document.getElementById("cp-spec-lane");
            return `◇ ${lane?.getAttribute("data-spec-label") || "Spec"}`;
        }
        return `▪ ${this.root?.getAttribute("data-snapshot-backend") || "Snapshot"}`;
    }

    protected renderVue(): VNode {
        // Nothing to report without a viewer to report on. The badge would
        // otherwise claim "▪ Snapshot" from its own fallback on any page that
        // happened to carry the tag outside a stage.
        if (!this.root) return createTextVNode("");
        // ◌ (an open circle) reads as "not yet painting", distinct from the ▶/▪
        // lane icons — the wait belongs on the preview, not in a footer.
        const text = this.pending ? `◌ ${this.pending}` : this.label();
        // Written to the host rather than reflected off a property: `data-live` is
        // derived from the mode, and `data-pending` has to be ABSENT (not "false")
        // when idle because `serve.css` keys the amber accent off the attribute's
        // presence.
        this.setAttribute("data-live", isLive(this.mode) ? "true" : "false");
        if (this.pending) this.setAttribute("data-pending", "true");
        else this.removeAttribute("data-pending");
        return createTextVNode(text);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-backend-badge": BackendBadge;
    }
}
