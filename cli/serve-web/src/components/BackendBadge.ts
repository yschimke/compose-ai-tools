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

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";

/** Lanes that are interactive rather than a still image. */
function isLive(mode: string | null): boolean {
    return mode === "wasm" || mode === "live";
}

@customElement("cp-backend-badge")
export class BackendBadge extends LitElement {
    /** `.cp-viewer`'s `data-mode` — which lane owns the stage. */
    @state() private mode: string | null = null;

    /** `.cp-viewer`'s `data-pending` — a lane activating, carrying its own copy. */
    @state() private pending: string | null = null;

    private root: HTMLElement | null = null;
    private observer?: MutationObserver;

    protected createRenderRoot(): HTMLElement {
        return this;
    }

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
    }

    private label(): string {
        const mode = this.mode;
        if (mode === "wasm") return "▶ CMP-WASM";
        if (mode === "live") {
            return `▶ ${this.root?.getAttribute("data-live-backend") || "Live"}`;
        }
        if (mode === "svg") return "▪ SVG";
        if (mode === "spec") {
            const lane = document.getElementById("cp-spec-lane");
            return `◇ ${lane?.getAttribute("data-spec-label") || "Spec"}`;
        }
        return `▪ ${this.root?.getAttribute("data-snapshot-backend") || "Snapshot"}`;
    }

    protected render(): TemplateResult {
        // Nothing to report without a viewer to report on. The badge would
        // otherwise claim "▪ Snapshot" from its own fallback on any page that
        // happened to carry the tag outside a stage.
        if (!this.root) return html``;
        // ◌ (an open circle) reads as "not yet painting", distinct from the ▶/▪
        // lane icons — the wait belongs on the preview, not in a footer.
        return html`${this.pending ? `◌ ${this.pending}` : this.label()}`;
    }

    protected updated(): void {
        if (!this.root) return;
        // Written to the host rather than reflected off a property: `data-live` is
        // derived from the mode, and `data-pending` has to be ABSENT (not "false")
        // when idle because `serve.css` keys the amber accent off the attribute's
        // presence.
        this.setAttribute("data-live", isLive(this.mode) ? "true" : "false");
        if (this.pending) this.setAttribute("data-pending", "true");
        else this.removeAttribute("data-pending");
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-backend-badge": BackendBadge;
    }
}
