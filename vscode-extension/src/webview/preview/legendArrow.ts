// Hover-arrow correlator linking legend entries to overlay regions.
//
// Each legend row carries `data-legend-id="<id>"`; each overlay
// element carries `data-overlay-id="<id>"`. On `mouseenter` of a
// legend row the controller:
//
//   1. Adds a `legend-active` class to the matching overlay element
//      (so the box / shape can highlight with the regular CSS palette).
//   2. Draws an SVG arrow from the legend row to the overlay element.
//      The SVG is fixed-position on `document.body` and sized to the
//      viewport so the arrow can cross from the inspector (right /
//      bottom of layout) to the preview image (top / centre).
//
// `mouseleave` reverses both steps. A single SVG is re-used across
// hovers so we don't churn DOM during quick legend traversals.
//
// Coordinates use `getBoundingClientRect` and the rects are recomputed
// on every hover — cheap, and avoids stale geometry after layout
// shifts (focus toggle, viewport resize, picker accordions opening).

const ARROW_SVG_ID = "focus-legend-arrow";
const ARROW_HEAD_ID = "focus-legend-arrow-head";

export class LegendArrowController {
    private readonly listeners = new Map<
        HTMLElement,
        { enter: (e: Event) => void; leave: (e: Event) => void }
    >();
    private readonly arrowEl: SVGSVGElement;
    private readonly lineEl: SVGLineElement;

    constructor() {
        this.arrowEl = ensureArrowSvg();
        this.lineEl = this.arrowEl.querySelector("line") as SVGLineElement;
    }

    /**
     * Hook every `[data-legend-id]` row inside [legendsRoot] to its
     * matching `[data-overlay-id]` element inside [overlayRoot]. Call
     * once per inspector render — the controller forgets previous
     * hooks first so re-rendering doesn't double-fire.
     */
    attach(legendsRoot: HTMLElement, overlayRoot: HTMLElement | null): void {
        this.detach();
        const rows =
            legendsRoot.querySelectorAll<HTMLElement>("[data-legend-id]");
        rows.forEach((row) => {
            const id = row.dataset.legendId;
            if (!id) return;
            const enter = (): void => this.show(row, id, overlayRoot);
            const leave = (): void => this.hide(id, overlayRoot);
            row.addEventListener("mouseenter", enter);
            row.addEventListener("mouseleave", leave);
            // Keyboard parity: focusing a legend row also surfaces the
            // arrow. Lets keyboard-only users see the correlation
            // without needing a hover device.
            row.addEventListener("focus", enter);
            row.addEventListener("blur", leave);
            this.listeners.set(row, { enter, leave });
        });
    }

    /** Drop every hover handler the controller installed. Idempotent. */
    detach(): void {
        for (const [row, { enter, leave }] of this.listeners) {
            row.removeEventListener("mouseenter", enter);
            row.removeEventListener("mouseleave", leave);
            row.removeEventListener("focus", enter);
            row.removeEventListener("blur", leave);
        }
        this.listeners.clear();
        this.arrowEl.style.opacity = "0";
    }

    private show(
        row: HTMLElement,
        id: string,
        overlayRoot: HTMLElement | null,
    ): void {
        const target = overlayRoot?.querySelector<HTMLElement>(
            '[data-overlay-id="' + cssEscape(id) + '"]',
        );
        if (target) {
            target.classList.add("legend-active");
        }
        const fromRect = row.getBoundingClientRect();
        const toRect = target?.getBoundingClientRect();
        if (!toRect) {
            // No overlay match — still allow the row to highlight,
            // just skip the arrow.
            this.arrowEl.style.opacity = "0";
            return;
        }
        const fromX = fromRect.left;
        const fromY = fromRect.top + fromRect.height / 2;
        const toX = toRect.left + toRect.width / 2;
        const toY = toRect.top + toRect.height / 2;
        this.lineEl.setAttribute("x1", String(fromX));
        this.lineEl.setAttribute("y1", String(fromY));
        this.lineEl.setAttribute("x2", String(toX));
        this.lineEl.setAttribute("y2", String(toY));
        this.arrowEl.style.opacity = "1";
    }

    private hide(id: string, overlayRoot: HTMLElement | null): void {
        const target = overlayRoot?.querySelector<HTMLElement>(
            '[data-overlay-id="' + cssEscape(id) + '"]',
        );
        if (target) {
            target.classList.remove("legend-active");
        }
        this.arrowEl.style.opacity = "0";
    }
}

function ensureArrowSvg(): SVGSVGElement {
    const existing = document.getElementById(ARROW_SVG_ID);
    if (existing instanceof SVGSVGElement) return existing;
    const svg = document.createElementNS(
        "http://www.w3.org/2000/svg",
        "svg",
    ) as SVGSVGElement;
    svg.id = ARROW_SVG_ID;
    svg.setAttribute("aria-hidden", "true");
    svg.style.position = "fixed";
    svg.style.inset = "0";
    svg.style.width = "100vw";
    svg.style.height = "100vh";
    svg.style.pointerEvents = "none";
    svg.style.zIndex = "9999";
    svg.style.opacity = "0";
    svg.style.transition = "opacity 80ms ease-out";

    const defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
    const marker = document.createElementNS(
        "http://www.w3.org/2000/svg",
        "marker",
    );
    marker.id = ARROW_HEAD_ID;
    marker.setAttribute("viewBox", "0 0 10 10");
    marker.setAttribute("refX", "8");
    marker.setAttribute("refY", "5");
    marker.setAttribute("markerWidth", "6");
    marker.setAttribute("markerHeight", "6");
    marker.setAttribute("orient", "auto-start-reverse");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", "M 0 0 L 10 5 L 0 10 z");
    path.setAttribute("fill", "currentColor");
    marker.appendChild(path);
    defs.appendChild(marker);
    svg.appendChild(defs);

    const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
    line.setAttribute("stroke", "currentColor");
    line.setAttribute("stroke-width", "1.5");
    line.setAttribute("marker-end", "url(#" + ARROW_HEAD_ID + ")");
    svg.appendChild(line);

    document.body.appendChild(svg);
    return svg;
}

function cssEscape(s: string): string {
    // Minimal escaping for `[attr="value"]` selectors — the only
    // characters that break the predicate are `"` and `\`. CSS.escape
    // is overkill and not in every webview environment.
    return s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}
