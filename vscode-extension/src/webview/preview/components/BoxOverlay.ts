// `<box-overlay>` — shared overlay primitive that paints percentage-
// positioned tinted rectangles on top of a preview image. Used by
// every per-bundle presenter that has bounds in the source-bitmap
// pixel space (a11y, strings, resources, layout-inspector, uia,
// history-diff). The card's `.image-container` provides the
// reference frame; bounds are translated to percent-of-natural so the
// overlay scales with the image without a resize handler.
//
// The component is data-driven (`setBoxes`) — no DOM building in
// the host. The host updates the natural dimensions whenever the
// underlying `<img>` resolves (`setNaturalSize`). Hover events fire
// via `overlay-hovered` so the matching legend / table row can light
// up via the shared `data-legend-id` ↔ `data-overlay-id` correlation.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";

export type OverlayLevel = "error" | "warning" | "info";

export interface OverlayBox {
    /** Correlates with `data-legend-id` on the matching legend / table row. */
    id: string;
    /** Bounds in source-bitmap pixels. */
    bounds: {
        left: number;
        top: number;
        right: number;
        bottom: number;
    };
    /** Drives the `data-level` accent — error / warning / info palette. */
    level?: OverlayLevel;
    /** Optional override colour; takes precedence over `level`. */
    color?: string;
    /** Short tooltip shown on hover. */
    tooltip?: string;
}

export interface OverlayHoveredDetail {
    overlayId: string | null;
}

@customElement("box-overlay")
export class BoxOverlay extends LitElement {
    @state() private boxes: readonly OverlayBox[] = [];
    @state() private naturalWidth = 0;
    @state() private naturalHeight = 0;
    @state() private activeOverlayId: string | null = null;

    setBoxes(boxes: readonly OverlayBox[]): void {
        this.boxes = boxes;
    }

    setNaturalSize(width: number, height: number): void {
        this.naturalWidth = width;
        this.naturalHeight = height;
    }

    /**
     * Drive the highlight from an external source (e.g. legend
     * hover). The internal hover state is mirrored so the user can
     * still hover the overlay directly.
     */
    setActiveOverlayId(id: string | null): void {
        this.activeOverlayId = id;
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        if (!this.naturalWidth || !this.naturalHeight) {
            return html``;
        }
        return html`
            <div class="box-overlay" aria-hidden="true">
                ${this.boxes.map((b) => this.renderBox(b))}
            </div>
        `;
    }

    private renderBox(b: OverlayBox): TemplateResult {
        const left = (b.bounds.left / this.naturalWidth) * 100;
        const top = (b.bounds.top / this.naturalHeight) * 100;
        const width =
            ((b.bounds.right - b.bounds.left) / this.naturalWidth) * 100;
        const height =
            ((b.bounds.bottom - b.bounds.top) / this.naturalHeight) * 100;
        const active = this.activeOverlayId === b.id;
        const style = [
            `left:${left}%`,
            `top:${top}%`,
            `width:${width}%`,
            `height:${height}%`,
            b.color ? `--overlay-color:${b.color}` : "",
        ]
            .filter(Boolean)
            .join(";");
        return html`
            <div
                class=${active
                    ? "overlay-box overlay-box-active"
                    : "overlay-box"}
                data-overlay-id=${b.id}
                data-level=${b.level ?? "info"}
                style=${style}
                title=${b.tooltip ?? ""}
                @mouseenter=${() => this.onHover(b.id)}
                @mouseleave=${() => this.onHover(null)}
            ></div>
        `;
    }

    private onHover(id: string | null): void {
        this.activeOverlayId = id;
        this.dispatchEvent(
            new CustomEvent<OverlayHoveredDetail>("overlay-hovered", {
                detail: { overlayId: id },
                bubbles: true,
                composed: true,
            }),
        );
    }
}
