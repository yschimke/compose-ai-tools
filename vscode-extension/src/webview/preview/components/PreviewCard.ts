// `<preview-card>` — Lit shell that owns the preview-card DOM the
// imperative `buildPreviewCard` previously built directly on a
// `<div class="preview-card">`.
//
// Step 2 of #857. The shell is intentionally minimal: it carries the
// `preview` (PreviewInfo) and `config` (CardBuilderConfig) the host
// hands in, and on `firstUpdated()` runs the imperative population
// logic against `this`. Step 3 (#857) lifts the metadata refresh into
// reactive `@state`; step 4 lifts the image / a11y overlay paths.
//
// We pass `preview` as a `@property` rather than just a `previewId`
// because `populatePreviewCard` needs the full `PreviewInfo` (function
// name, params, captures, a11yFindings, …). Resolving from
// `previewStore.allPreviews` would work but adds an indirection that
// step 3 will need to undo when it switches to a reactive selection
// against the store.
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

    // Light DOM keeps `media/preview.css` rules applying unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // Lit calls `render()` even though we populate imperatively; return
    // an empty template so it doesn't wipe children we add in
    // `firstUpdated`. Step 3 / step 4 will start producing real
    // template content here as the metadata / image paths go reactive.
    protected render(): TemplateResult {
        return html``;
    }

    protected firstUpdated(): void {
        const preview = this.preview;
        const config = this.config;
        if (!preview || !config) return;
        populatePreviewCard(this, preview, config);
    }
}
