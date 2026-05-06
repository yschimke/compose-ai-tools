// `<history-row>` — Lit component for a single timeline row.
//
// Step 1 of #858: shell + reactive thumb. The row renders the markup
// the imperative `renderTimeline` used to build with
// `document.createElement` (thumb, meta, badge, action buttons), takes
// its `entry` as a `@property`, and subscribes to `historyStore` for
// the thumbnail bytes via [StoreController]. When the extension's
// `thumbReady` message lands and `behavior.ts` writes it into the
// store, only the rows whose entry id matches re-render.
//
// Selection (`.selected` class) and inline expansion (`.expanded`
// sibling div) STAY OWNED by the host — the row exposes
// `setSelected(bool)` for the host to toggle, and the click handlers
// route back through the existing config callbacks. Step 2 of #858
// migrates that state into `@state` and a reactive `expanded` slot.
//
// Light DOM: `media/history.css` rules target `.row`, `.thumb`,
// `.meta`, etc. — keep them applying without touching CSS.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { unsafeHTML } from "lit/directives/unsafe-html.js";
import { StoreController } from "../../shared/storeController";
import type { HistoryEntry } from "../../shared/types";
import { escapeHtml, formatAbsolute, formatRelative } from "../historyData";
import { historyStore, type HistoryState } from "../historyStore";

/** Per-row callbacks the host wires up so the row stays presentational
 *  while step-1 keeps selection/expansion logic in `behavior.ts`. */
export interface HistoryRowCallbacks {
    /** Shift-click: toggle membership in the (max-2) selection set. */
    onToggleSelected(id: string, row: HistoryRow): void;
    /** Plain click: open / collapse the inline image expansion. */
    onExpand(id: string, row: HistoryRow): void;
    /** Up-arrow icon: diff against the previous entry for this preview. */
    onDiffPrevious(id: string, row: HistoryRow): void;
    /** Compare icon: diff against the live render of this preview. */
    onDiffCurrent(id: string, row: HistoryRow): void;
    /** Lazy-load hook fired once after the row mounts. The host
     *  registers the `.thumb` element with its IntersectionObserver so
     *  the thumb byte fetch is deferred until scroll-into-view. No-op
     *  on environments without IntersectionObserver. */
    onThumbConnected(thumbEl: HTMLElement, id: string): void;
}

@customElement("history-row")
export class HistoryRow extends LitElement {
    /** The history entry this row renders. Required — render() returns
     *  empty until set. */
    @property({ attribute: false }) entry: HistoryEntry | null = null;

    /** When true the row paints with the `.selected` class — used by the
     *  shift-click selection logic. Mirror state, not source of truth
     *  yet (step 2 of #858 moves the source-of-truth into the row). */
    @property({ type: Boolean }) selected = false;

    /** Hash of the latest archived render on `main` for the currently
     *  scoped preview. Drives the "vs main" indicator dot. `null` when
     *  no main-branch entry has a `pngHash`. */
    @property({ attribute: false }) mainHash: string | null = null;

    /** Callbacks supplied by the host's `renderTimeline` so the row's
     *  click / button handlers stay routed through the existing
     *  selection / expansion / diff plumbing. Set imperatively after
     *  construction. */
    callbacks: HistoryRowCallbacks | null = null;

    private readonly thumbBytes = new StoreController<
        HistoryState,
        string | undefined
    >(this, historyStore, (s) =>
        this.entry?.id ? s.thumbCache.get(this.entry.id) : undefined,
    );

    // Light DOM so the panel-specific `.row` / `.thumb` / `.meta`
    // styles in `media/history.css` apply unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    /** Imperative setter so the host doesn't have to re-render the
     *  whole timeline to flip selection state. Mirrors the legacy
     *  `row.classList.add/remove("selected")` calls. */
    setSelected(next: boolean): void {
        if (this.selected === next) return;
        this.selected = next;
    }

    protected updated(): void {
        // Mirror dataset attributes the host's `applyFilters` /
        // expansion-anchor / cssEscape selectors rely on. Lit doesn't
        // bind `data-*` from `@property` automatically, and using
        // `?attribute` on a non-string field is the wrong tool — we
        // just stamp them after each render.
        const entry = this.entry;
        const id = entry?.id ?? "";
        this.classList.add("row");
        this.classList.toggle("selected", this.selected);
        this.setAttribute("role", "listitem");
        this.dataset.id = id;
        this.dataset.sourceKind = (entry?.source && entry.source.kind) || "";
        this.dataset.branch = (entry?.git && entry.git.branch) || "";
    }

    protected firstUpdated(): void {
        // Hook the host's IntersectionObserver up to our `.thumb` once
        // it's in the DOM. The store subscription handles cache hits;
        // this path covers the cold lazy-load.
        const id = this.entry?.id;
        if (!id) return;
        const thumbEl = this.querySelector<HTMLElement>(".thumb");
        if (thumbEl) this.callbacks?.onThumbConnected(thumbEl, id);
    }

    protected render(): TemplateResult {
        const entry = this.entry;
        if (!entry) return html``;
        const entryId = entry.id ?? "";
        const cached = this.thumbBytes.value;
        const dotChanged =
            entry.deltaFromPrevious && entry.deltaFromPrevious.pngHashChanged
                ? '<span class="changed-dot" title="bytes changed vs previous"></span>'
                : "";
        const dotMain =
            this.mainHash && entry.pngHash && entry.pngHash !== this.mainHash
                ? '<span class="main-dot" title="bytes differ from latest main render"></span>'
                : "";
        const absolute = formatAbsolute(entry.timestamp);
        const trigger = entry.trigger ? entry.trigger : "—";
        const branch = (entry.git && entry.git.branch) || "";
        const subParts: string[] = [];
        if (absolute) subParts.push(escapeHtml(absolute));
        subParts.push(escapeHtml(trigger));
        if (branch) subParts.push(escapeHtml(branch));
        const subHtml = dotChanged + dotMain + subParts.join(" · ");
        const sourceKind = (entry.source && entry.source.kind) || "fs";

        return html`
            <div class="thumb" data-id=${entryId}>
                ${cached !== undefined
                    ? html`<img
                          src=${"data:image/png;base64," + cached}
                          alt=""
                      />`
                    : ""}
            </div>
            <div class="meta">
                <div class="ts" title=${entry.timestamp || ""}>
                    ${formatRelative(entry.timestamp)}
                </div>
                <div class="sub">${unsafeHTML(subHtml)}</div>
            </div>
            <span class="badge">${sourceKind}</span>
            <div class="row-actions">
                <button
                    class="icon-button row-action"
                    title="Diff against the previous entry for this preview"
                    aria-label="Diff vs previous"
                    @click=${this.onDiffPrevClick}
                >
                    <i class="codicon codicon-arrow-up" aria-hidden="true"></i>
                </button>
                <button
                    class="icon-button row-action"
                    title="Diff against the live render of this preview"
                    aria-label="Diff vs current"
                    @click=${this.onDiffCurrentClick}
                >
                    <i
                        class="codicon codicon-git-compare"
                        aria-hidden="true"
                    ></i>
                </button>
            </div>
        `;
    }

    connectedCallback(): void {
        super.connectedCallback();
        this.addEventListener("click", this.onRowClick);
    }

    disconnectedCallback(): void {
        this.removeEventListener("click", this.onRowClick);
        super.disconnectedCallback();
    }

    private readonly onRowClick = (ev: MouseEvent): void => {
        // The action buttons stop propagation themselves — anything
        // reaching here is a click on the row body.
        const id = this.entry?.id ?? "";
        if (!id || !this.callbacks) return;
        if (ev.shiftKey) this.callbacks.onToggleSelected(id, this);
        else this.callbacks.onExpand(id, this);
    };

    private readonly onDiffPrevClick = (ev: MouseEvent): void => {
        ev.stopPropagation();
        const id = this.entry?.id ?? "";
        if (!id || !this.callbacks) return;
        this.callbacks.onDiffPrevious(id, this);
    };

    private readonly onDiffCurrentClick = (ev: MouseEvent): void => {
        ev.stopPropagation();
        const id = this.entry?.id ?? "";
        if (!id || !this.callbacks) return;
        this.callbacks.onDiffCurrent(id, this);
    };
}
