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
// Step 2 of #858: the row owns its `selected` state. Shift-click
// flips `this.selected` and dispatches `history-row-selection-change`
// (bubbling) so the host can aggregate the (max-2) selection queue
// without holding a separate `Set<string>`. The host can still
// imperatively set `.selected = false` to clear the oldest selection
// when a third row gets picked.
//
// Step 3 of #858: inline expansion (image / diff) state lives here
// too. The row tracks `_expandedKind` / `_diffAgainst` plus the
// pending payload via `@state` and renders the `.expanded` block as a
// child of the row's light DOM. Plain-click toggles image expansion;
// the action buttons request a diff. Either way the row dispatches
// `history-row-expand` (bubbling, composed) so the host can post
// `loadImage` / `requestDiff` and collapse any other open row. When
// the extension's `imageReady` / `diffReady` messages land, the host
// pushes the bytes into the row via `setImage` / `setDiff` (or the
// matching error setters); the row re-renders and Lit paints the
// new content. The `data-id` / `data-against` attributes on
// `.expanded` are preserved — `historyDiffView.ts.fillDiff` still
// targets them for the diff-body integration, and the inline CSS in
// `historyPanel.ts` keys off `.expanded` / `.diff-expanded`.
//
// Light DOM: `media/history.css` rules target `.row`, `.thumb`,
// `.meta`, etc. — keep them applying without touching CSS.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { unsafeHTML } from "lit/directives/unsafe-html.js";
import { StoreController } from "../../shared/storeController";
import type { HistoryEntry } from "../../shared/types";
import type { VsCodeApi } from "../../shared/vscode";
import { escapeHtml, formatAbsolute, formatRelative } from "../historyData";
import {
    fillDiff as fillDiffDom,
    type HistoryDiffViewConfig,
} from "../historyDiffView";
import { historyStore, type HistoryState } from "../historyStore";

/** Per-row callbacks the host wires up. After step 3 of #858 the
 *  only remaining concern is hooking the host's IntersectionObserver
 *  up to the row's `.thumb` element for lazy-load — expansion / diff
 *  flow through the `history-row-expand` CustomEvent now. */
export interface HistoryRowCallbacks {
    /** Lazy-load hook fired once after the row mounts. The host
     *  registers the `.thumb` element with its IntersectionObserver so
     *  the thumb byte fetch is deferred until scroll-into-view. No-op
     *  on environments without IntersectionObserver. */
    onThumbConnected(thumbEl: HTMLElement, id: string): void;
}

/** Detail for the `history-row-selection-change` CustomEvent the row
 *  dispatches when its `selected` state flips because of user input
 *  (shift-click). External `.selected = ...` writes do NOT dispatch —
 *  only the row's own click handler does, so the host can imperatively
 *  clear an oldest selection without re-entering its own listener. */
export interface HistoryRowSelectionChangeDetail {
    id: string;
    selected: boolean;
}

export type HistoryRowSelectionChangeEvent =
    CustomEvent<HistoryRowSelectionChangeDetail>;

/** Detail for the `history-row-expand` CustomEvent the row
 *  dispatches when the user opens / re-targets an inline expansion.
 *  The host listens once on `timelineEl` and posts the appropriate
 *  vscode message (`loadImage` for `kind: "image"`, `requestDiff`
 *  for `kind: "diff"`) plus collapses any other currently-open row. */
export interface HistoryRowExpandDetail {
    id: string;
    kind: "image" | "diff";
    /** Only present when `kind === "diff"`. */
    against?: "previous" | "current";
}

export type HistoryRowExpandEvent = CustomEvent<HistoryRowExpandDetail>;

declare global {
    interface HTMLElementEventMap {
        "history-row-selection-change": HistoryRowSelectionChangeEvent;
        "history-row-expand": HistoryRowExpandEvent;
    }
}

@customElement("history-row")
export class HistoryRow extends LitElement {
    /** The history entry this row renders. Required — render() returns
     *  empty until set. */
    @property({ attribute: false }) entry: HistoryEntry | null = null;

    /** Source of truth for whether the row is in the (max-2) selection
     *  set. The row flips this itself on shift-click and dispatches
     *  `history-row-selection-change`; the host's listener may write it
     *  back to `false` to evict the oldest selection when a third row
     *  is picked. External writes do NOT re-dispatch. */
    @property({ type: Boolean }) selected = false;

    /** Hash of the latest archived render on `main` for the currently
     *  scoped preview. Drives the "vs main" indicator dot. `null` when
     *  no main-branch entry has a `pngHash`. */
    @property({ attribute: false }) mainHash: string | null = null;

    /** Callbacks supplied by the host's `renderTimeline`. After step 3
     *  of #858 this only carries the lazy-load thumb hook. Set
     *  imperatively after construction. */
    callbacks: HistoryRowCallbacks | null = null;

    /** Diff-view config used by the row's `updated()` hook to call
     *  `historyDiffView.ts.fillDiff` once a `_diffPayload` lands. The
     *  host wires this up the same way as `callbacks` so the row
     *  doesn't need direct vscode access. */
    diffViewConfig: HistoryDiffViewConfig | null = null;

    /** Which inline expansion block (if any) is currently open below
     *  the row body. `null` means collapsed; the row renders no
     *  `.expanded` child in that case. */
    @state() private _expandedKind: "image" | "diff" | null = null;

    /** Direction for the diff expansion. Only meaningful when
     *  `_expandedKind === "diff"`. Mirrored onto the rendered
     *  `.expanded.diff-expanded` element via `data-against` so
     *  `historyDiffView.ts.fillDiff` can target it. */
    @state() private _diffAgainst: "previous" | "current" | null = null;

    /** Base64 PNG bytes for the open image expansion, populated by the
     *  host when the extension's `imageReady` message lands. `null`
     *  while loading or after collapse. */
    @state() private _imageBytes: string | null = null;

    /** Error text for the open image expansion, populated by the host
     *  when the extension's `imageError` message lands. */
    @state() private _imageError: string | null = null;

    /** Diff payload (left / right labels + base64 images) for the open
     *  diff expansion, populated by the host when `diffReady` lands.
     *  When non-null AND the `.expanded.diff-expanded` shell is in the
     *  DOM, the row's `updated()` hook calls `fillDiff` once to render
     *  the mode bar / panes / async pixel-diff stats — that machinery
     *  stays in `historyDiffView.ts` for now. */
    @state() private _diffPayload: {
        leftLabel: string;
        leftImage: string;
        rightLabel: string;
        rightImage: string;
    } | null = null;

    /** Error text for the open diff expansion, populated by the host
     *  when the extension's `diffPairError` message lands. */
    @state() private _diffError: string | null = null;

    /** Identity of the diff payload the row last imperatively drew via
     *  `fillDiff`. Lit's reactive lifecycle re-invokes `updated()`
     *  every render; we only want to call `fillDiff` again when a new
     *  payload arrives, not on every selection / thumb change. */
    private _lastFilledDiff: object | null = null;

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

        // Diff-body integration is still imperative — `fillDiff` wires
        // up the persisted mode bar, the async pixel-diff stats, and
        // the side / overlay / onion render swap. The row provides the
        // empty `.expanded.diff-expanded` shell with `data-id` /
        // `data-against` so the existing selector keeps resolving;
        // when a fresh payload lands we hand off once. Identity-track
        // via `_lastFilledDiff` so subsequent re-renders for unrelated
        // reactive state (selection toggle, thumb bytes) don't re-call
        // `fillDiff` and reset the persisted mode bar.
        if (
            this._expandedKind === "diff" &&
            this._diffPayload &&
            this._diffAgainst &&
            this._lastFilledDiff !== this._diffPayload &&
            this.diffViewConfig
        ) {
            const shell = this.querySelector<HTMLElement>(
                ".expanded.diff-expanded",
            );
            if (shell) {
                this._lastFilledDiff = this._diffPayload;
                fillDiffDom(
                    id,
                    this._diffAgainst,
                    this._diffPayload.leftLabel,
                    this._diffPayload.leftImage,
                    this._diffPayload.rightLabel,
                    this._diffPayload.rightImage,
                    this.diffViewConfig,
                );
            }
        }
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
            ${this._expandedKind === "image"
                ? html`<div class="expanded" data-id=${entryId}>
                      ${this._renderImageExpansion(entry, entryId)}
                  </div>`
                : ""}
            ${this._expandedKind === "diff" && this._diffAgainst
                ? html`<div
                      class="expanded diff-expanded"
                      data-id=${entryId}
                      data-against=${this._diffAgainst}
                  >
                      ${this._renderDiffExpansion()}
                  </div>`
                : ""}
        `;
    }

    private _renderImageExpansion(
        entry: HistoryEntry,
        entryId: string,
    ): TemplateResult {
        if (this._imageError !== null) {
            return html`<div>
                Failed to load image: ${this._imageError || "(no detail)"}
            </div>`;
        }
        if (this._imageBytes !== null) {
            const sourceFile =
                entry &&
                entry.previewMetadata &&
                entry.previewMetadata.sourceFile;
            const alt = (entry && entry.previewId) || entryId;
            return html`
                <img
                    src=${"data:image/png;base64," + this._imageBytes}
                    alt=${alt}
                />
                <div class="actions">
                    ${sourceFile
                        ? html`<button
                              @click=${() => this._openSource(sourceFile)}
                          >
                              Open in Editor
                          </button>`
                        : ""}
                </div>
            `;
        }
        return html`<div>Loading…</div>`;
    }

    private _renderDiffExpansion(): TemplateResult {
        if (this._diffError !== null) {
            return html`<div>
                Diff unavailable: ${this._diffError || "(no detail)"}
            </div>`;
        }
        // When `_diffPayload` is non-null the imperative `fillDiff`
        // call in `updated()` replaces this placeholder with the real
        // body. Keeping a Lit-rendered loading line here makes the
        // empty-state visible during the request gap and gives Lit a
        // valid template to render before the imperative takeover.
        if (this._diffPayload === null) {
            return html`<div>Loading diff…</div>`;
        }
        return html``;
    }

    private _openSource(sourceFile: string): void {
        const vscode = this.diffViewConfig?.vscode as
            | VsCodeApi<unknown>
            | undefined;
        if (vscode) vscode.postMessage({ command: "openSource", sourceFile });
    }

    /** Host-side hook for the extension's `imageReady` message. The
     *  host finds the matching row by id and forwards the bytes; the
     *  row re-renders and Lit paints the `<img>` plus the actions
     *  block (with the optional "Open in Editor" button). */
    setImage(imageData: string, _entry?: HistoryEntry): void {
        // Drop any stale diff state. `expandWithImage` already
        // guarantees the row is in image mode by the time the host
        // posts `loadImage`, but `setImage` is a public method and a
        // direct caller may race a collapse — pin the kind defensively.
        this._expandedKind = "image";
        this._imageError = null;
        this._imageBytes = imageData;
    }

    /** Host-side hook for `imageError`. Replaces any pending bytes
     *  with the error message; the row re-renders into the failure
     *  state. */
    setImageError(message: string): void {
        this._expandedKind = "image";
        this._imageBytes = null;
        this._imageError = message;
    }

    /** Host-side hook for `diffReady`. Stash the payload; the row's
     *  `updated()` hook hands off to `historyDiffView.ts.fillDiff`
     *  which paints the mode bar / panes into the `.expanded`
     *  shell. */
    setDiff(
        against: "previous" | "current",
        leftLabel: string,
        leftImage: string,
        rightLabel: string,
        rightImage: string,
    ): void {
        this._expandedKind = "diff";
        this._diffAgainst = against;
        this._diffError = null;
        this._diffPayload = { leftLabel, leftImage, rightLabel, rightImage };
    }

    /** Host-side hook for `diffPairError`. Renders the error inside
     *  the diff shell. */
    setDiffError(against: "previous" | "current", message: string): void {
        this._expandedKind = "diff";
        this._diffAgainst = against;
        this._diffPayload = null;
        this._diffError = message;
    }

    /** Collapse any open expansion. Used by the host when a new row
     *  expands so only one expansion is open at a time. */
    collapse(): void {
        this._expandedKind = null;
        this._diffAgainst = null;
        this._imageBytes = null;
        this._imageError = null;
        this._diffPayload = null;
        this._diffError = null;
        this._lastFilledDiff = null;
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
        if (!id) return;
        if (ev.shiftKey) {
            this.selected = !this.selected;
            this.dispatchEvent(
                new CustomEvent<HistoryRowSelectionChangeDetail>(
                    "history-row-selection-change",
                    {
                        detail: { id, selected: this.selected },
                        bubbles: true,
                        composed: true,
                    },
                ),
            );
            return;
        }
        // Plain click → toggle the image expansion. Already-open image
        // collapses; anything else (closed, or open in diff mode)
        // (re)opens as image. Either way we dispatch
        // `history-row-expand` so the host can collapse other rows
        // and post the corresponding `loadImage`. Collapse-only
        // transitions don't post a request — there's nothing to load.
        if (this._expandedKind === "image") {
            this.collapse();
            return;
        }
        this._expandedKind = "image";
        this._diffAgainst = null;
        this._imageBytes = null;
        this._imageError = null;
        this._diffPayload = null;
        this._diffError = null;
        this._lastFilledDiff = null;
        this.dispatchEvent(
            new CustomEvent<HistoryRowExpandDetail>("history-row-expand", {
                detail: { id, kind: "image" },
                bubbles: true,
                composed: true,
            }),
        );
    };

    private readonly onDiffPrevClick = (ev: MouseEvent): void => {
        ev.stopPropagation();
        this._requestDiff("previous");
    };

    private readonly onDiffCurrentClick = (ev: MouseEvent): void => {
        ev.stopPropagation();
        this._requestDiff("current");
    };

    private _requestDiff(against: "previous" | "current"): void {
        const id = this.entry?.id ?? "";
        if (!id) return;
        this._expandedKind = "diff";
        this._diffAgainst = against;
        this._imageBytes = null;
        this._imageError = null;
        this._diffPayload = null;
        this._diffError = null;
        this._lastFilledDiff = null;
        this.dispatchEvent(
            new CustomEvent<HistoryRowExpandDetail>("history-row-expand", {
                detail: { id, kind: "diff", against },
                bubbles: true,
                composed: true,
            }),
        );
    }
}
