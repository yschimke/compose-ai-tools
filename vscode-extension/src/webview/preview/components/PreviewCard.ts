// `<preview-card>` — Lit shell that owns the preview-card DOM the
// imperative `buildPreviewCard` previously built directly on a
// `<div class="preview-card">`.
//
// Step 3 of #857: the metadata refresh and per-frame image paint now
// live behind narrow-deps helpers (`refreshCardMetadata` in
// `../cardMetadata`, `paintCardCapture` in `../cardImage`) and the
// component owns the call sites. The host's old free-function
// `updateImage` shim is gone — the message dispatcher resolves the
// card by id and calls `card.paintCapture(captureIndex, imageData)`
// here, which delegates to `paintCardCapture` against `this`.
//
// `firstUpdated` still runs the imperative `populatePreviewCard`
// logic to build initial DOM — that one stays in `cardBuilder.ts`
// for now since it touches collaborator hooks (`enterFocus`,
// `observeForViewport`) that aren't on the narrow-deps surface.
// Folding that one in is a follow-up step.
//
// `_mapsRevision` (`StoreController`) drives a11y-overlay repaints
// from the per-preview cache: `paintCardCapture` bumps
// `previewStore.mapsRevision` AFTER assigning the new `<img>.src`, so
// the component re-runs `_repaintA11yOverlaysFromCache` with the new
// image in place. When the `<img>` hasn't yet resolved its natural
// dimensions, the repaint attaches a one-time `load` listener and
// paints when the bytes land.
//
// We pass `preview` as a `@property` rather than just a `previewId`
// because `populatePreviewCard` and `refreshCardMetadata` need the
// full `PreviewInfo` (function name, params, captures, a11yFindings,
// …). Resolving from `previewStore.allPreviews` would work but adds
// an indirection a later step can undo when it switches to a reactive
// selection against the store.
//
// Light DOM only — `media/preview.css` selectors target
// `.preview-card .card-header .card-title-row` etc. and would not
// pierce a shadow root. The id (`preview-${sanitizeId(p.id)}`),
// `preview-card` className, and dataset attributes (`previewId`,
// `className`, `wearPreview`, `currentIndex`, `function`, `group`,
// `referenced`) all land on the host element so the existing
// `document.getElementById` paths in `messageHandlers.ts` /
// `liveState.ts` and the `.preview-card` selector in `renderPreviews`
// keep working unchanged.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import type { CardBuilderConfig } from "../cardBuilder";
import { populatePreviewCard } from "../cardBuilder";
import { paintCardCapture } from "../cardImage";
import { refreshCardMetadata } from "../cardMetadata";
import { previewStore, type PreviewState } from "../previewStore";
import { StoreController } from "../../shared/storeController";
import type { PreviewInfo } from "../../shared/types";

@customElement("preview-card")
export class PreviewCard extends LitElement {
    /** The preview manifest entry this card renders. Required —
     *  `firstUpdated` no-ops until both `preview` and `config` are set. */
    @property({ attribute: false }) preview: PreviewInfo | null = null;

    /** Collaborator surface the host hands in. Carries `vscode`,
     *  `staleBadge`, `frameCarousel`, `liveState`, focus / viewport /
     *  message hooks — everything `populatePreviewCard` reaches for. */
    @property({ attribute: false }) config: CardBuilderConfig | null = null;

    /** Set once `firstUpdated` has populated the card. Guards `updated`
     *  from running the metadata patch on the initial render — that path
     *  is already covered by `populatePreviewCard`. */
    private _built = false;

    /** Subscription to `previewStore.mapsRevision`. Each per-preview
     *  Map mutation (`setCardCaptures` / `setCardA11yFindings` /
     *  `setCardA11yNodes`) bumps the counter, which fires
     *  `requestUpdate()` on this component. The `updated()` hook then
     *  re-paints the a11y overlays for this card from the latest
     *  cache snapshot — `applyA11yUpdate` no longer needs to drive the
     *  overlay repaint itself. */
    private readonly _mapsRevision = new StoreController<PreviewState, number>(
        this,
        previewStore,
        (s) => s.mapsRevision,
    );

    // Light DOM keeps `media/preview.css` rules applying unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // Lit calls `render()` even though we populate imperatively; return
    // an empty template so it doesn't wipe children we add in
    // `firstUpdated`. Step 4 will start producing real template content
    // here as the image / a11y paths go reactive.
    protected render(): TemplateResult {
        return html``;
    }

    protected firstUpdated(): void {
        const preview = this.preview;
        const config = this.config;
        if (!preview || !config) return;
        populatePreviewCard(this, preview, config);
        this._built = true;
    }

    /** React to a `preview` reassignment from the host (`renderPreviews`
     *  reseed) AND to per-preview store map mutations.
     *
     *  - Preview reassignment: patches the card's dataset, title,
     *    capture cache, variant badge, and a11y legend / overlay in
     *    place — same body the imperative call site used to drive
     *    directly. Skipped on the first render: `firstUpdated`
     *    already built the card.
     *  - `mapsRevision` change: re-paint the a11y findings / hierarchy
     *    overlays from the cache snapshot for `this.preview.id`. This
     *    runs on every `updated()` (Lit re-renders fire the hook
     *    regardless of which property changed) so the cache-change
     *    case is covered without an explicit changedProperties guard.
     *    If the `<img>` has not yet resolved natural dimensions,
     *    `_repaintA11yOverlaysFromCache` falls back to a one-time
     *    `load` listener so a fresh `updateImage` swap is followed by
     *    a deferred repaint once the new bytes are decoded. */
    protected updated(changedProperties: Map<string, unknown>): void {
        if (!this._built) return;
        if (changedProperties.has("preview")) {
            const preview = this.preview;
            const config = this.config;
            if (preview && config) {
                refreshCardMetadata(this, preview, config);
            }
        }
        this._repaintA11yOverlaysFromCache();
    }

    /** Per-frame image paint — invoked by the message dispatcher
     *  after resolving the card by id. Delegates to
     *  `paintCardCapture` against `this`; the helper handles cache
     *  mutation, currently-displayed-vs-arriving capture comparison,
     *  `<img>` swap, interactive-input rebind, and the
     *  `mapsRevision` bump that drives the overlay repaint. No-op
     *  when `config` hasn't been set (the card is mid-construction
     *  and `firstUpdated` hasn't run yet — shouldn't happen in
     *  practice since the dispatcher only finds cards already
     *  attached to the grid). */
    paintCapture(captureIndex: number, imageData: string): void {
        const preview = this.preview;
        const config = this.config;
        if (!preview || !config) return;
        paintCardCapture(this, preview.id, captureIndex, imageData, config);
    }

    /**
     * Hook for store-driven repaints. The legacy `applyHierarchyOverlay`
     * / `buildA11yOverlay` calls used to live here and painted boxes
     * onto the card unconditionally whenever the a11y caches bumped.
     * After #1087 the A11y bundle owns that paint via
     * `paintBundleBoxes(card, 'a11y', ...)` driven from
     * `refreshA11yBundle` — the chip is the gate.
     *
     * The method stays in place so the `_mapsRevision` subscription
     * (which fires `requestUpdate()` on every cache bump) has
     * somewhere to land in `updated()`. Future bundles that want
     * cache-driven repaints can hook in here without resurrecting the
     * legacy paint path. Empty by design — non-empty work belongs in
     * a per-bundle refresh function in `main.ts`.
     */
    private _repaintA11yOverlaysFromCache(): void {
        // Intentionally empty — see docstring.
    }
}
