// Presentation framework for focus-mode data products.
//
// The four surfaces a data product can contribute to:
//
//   1. **Overlay** — DOM painted on top of the focused card's preview
//      image. Boxed regions, heatmaps, annotated points. Lives in a
//      stacking context that scales with the image's natural pixel
//      dimensions, so percentage-based positioning works without a
//      resize handler. (Same coordinate model as the existing a11y
//      overlay — see `a11yOverlay.ts`.)
//
//   2. **Legend** — sidecar list inside the inspector. One entry per
//      logical item the overlay highlights (a finding, a node, a
//      tracepoint). Hovering a legend entry lights up the matching
//      overlay region via shared `data-legend-id` attributes plus an
//      SVG arrow drawn from the entry to the region (see
//      `legendArrow.ts`).
//
//   3. **Report** — collapsible block below the buckets list. Use for
//      structured data that doesn't fit on the image: theme tokens,
//      string tables, font metadata, render traces.
//
//   4. **Error** — banner at the top of the inspector. Surfaces when
//      the data the presenter expected is missing, malformed, or the
//      underlying daemon call failed. Distinct from a render error
//      (those are per-capture and live on the card itself).
//
// Each `kind` registers a `ProductPresenter` with the registry. The
// inspector iterates the enabled set, calls each presenter with the
// current card / preview / cached data, and slots the returned DOM
// into the right surface. Presenters are pure factories — no state
// of their own — so re-rendering the inspector simply re-builds the
// DOM. The arrow correlator is wired up after the legends are in the
// DOM (see `attachLegendArrows` below).

import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";

export interface PresenterContext {
    /** The focused card element. Presenters that produce overlays
     *  must paint into the card's `.focus-overlay-stack` layer. */
    card: HTMLElement;
    /** The currently-focused preview manifest entry. */
    preview: PreviewInfo;
    /** Latest cached a11y findings, or empty. */
    findings: readonly AccessibilityFinding[];
    /** Latest cached a11y hierarchy nodes, or empty. */
    nodes: readonly AccessibilityNode[];
}

/** Bag of contributions a presenter can make to one or more surfaces.
 *  Returning `null` skips the surface for this kind on this preview. */
export interface ProductPresentation {
    overlay?: HTMLElement | null;
    legend?: PresentationLegend | null;
    report?: PresentationReport | null;
    error?: PresentationError | null;
}

export interface PresentationLegend {
    /** Title shown in the legend block header. */
    title: string;
    /** Optional rollup like "(3 findings)". */
    summary?: string;
    /** Rows. Each row's `id` becomes `data-legend-id` and is matched
     *  against `data-overlay-id` on overlay descendants for hover
     *  correlation. */
    entries: readonly LegendEntry[];
}

export interface LegendEntry {
    id: string;
    label: string;
    detail?: string;
    /** Influences the row's accent colour. `error`/`warning`/`info`
     *  match the existing a11y level palette. */
    level?: "error" | "warning" | "info";
}

export interface PresentationReport {
    title: string;
    summary?: string;
    /** Pre-built body. The inspector wraps it in a `<details>` so the
     *  presenter doesn't have to. */
    body: HTMLElement;
}

export interface PresentationError {
    title: string;
    message: string;
    detail?: string;
}

export type ProductPresenter = (
    ctx: PresenterContext,
) => ProductPresentation | null;

const registry = new Map<string, ProductPresenter>();

/**
 * Register a presenter for [kind]. Last-write-wins so tests / future
 * extensions can replace built-ins. Built-ins register at module load
 * via the `register*` calls below — keeps the registration colocated
 * with the implementation rather than centralising in `main.ts`.
 */
export function registerPresenter(
    kind: string,
    presenter: ProductPresenter,
): void {
    registry.set(kind, presenter);
}

export function getPresenter(kind: string): ProductPresenter | undefined {
    return registry.get(kind);
}

/**
 * Visible for tests only — clear the registry between cases. Using a
 * registry that persists across tests would otherwise leak state from
 * one test's `registerPresenter` into another's assertion.
 */
export function _resetPresentersForTest(): void {
    registry.clear();
}

// ---- Built-in presenters --------------------------------------------------
//
// Concrete presenters live alongside the framework so the registry is
// populated by the side-effect of importing this module. The a11y
// hierarchy presenter is the worked example: it contributes to all
// four surfaces. Other built-ins are deliberately narrow — the goal
// here is to prove each surface's mount works end-to-end, not to fake
// data the daemon doesn't actually produce.

registerPresenter("a11y/hierarchy", a11yHierarchyPresenter);
registerPresenter("a11y/atf", a11yFindingsPresenter);
registerPresenter("compose/theme", composeThemePresenter);
registerPresenter("local/render/error", renderErrorPresenter);

function a11yHierarchyPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const nodes = ctx.nodes;
    if (nodes.length === 0) {
        return {
            report: {
                title: "Layout hierarchy",
                summary: "No nodes",
                body: emptyBody("Daemon hasn't attached a11y/hierarchy yet."),
            },
        };
    }

    const overlay = document.createElement("div");
    overlay.className = "focus-presentation-overlay focus-overlay-hierarchy";
    overlay.dataset.kind = "a11y/hierarchy";
    let parsed = 0;
    let dropped = 0;
    nodes.forEach((node, idx) => {
        const bounds = parseBoundsString(node.boundsInScreen);
        if (!bounds) {
            dropped += 1;
            return;
        }
        parsed += 1;
        const box = document.createElement("div");
        box.className = "focus-overlay-box";
        box.dataset.overlayId = "node-" + idx;
        box.dataset.level = node.merged ? "warning" : "info";
        // Bounds are in source-bitmap pixels; the overlay layer sizes
        // to the image's intrinsic size so % positioning lines up
        // without a resize handler.
        box.style.left = bounds.left + "px";
        box.style.top = bounds.top + "px";
        box.style.width = bounds.right - bounds.left + "px";
        box.style.height = bounds.bottom - bounds.top + "px";
        overlay.appendChild(box);
    });

    const entries: LegendEntry[] = nodes.map((node, idx) => ({
        id: "node-" + idx,
        label: node.label || "(unlabelled)",
        detail:
            (node.role ? node.role : "") +
            (node.states.length > 0 ? " · " + node.states.join(", ") : ""),
        level: node.merged ? "warning" : "info",
    }));

    const reportBody = document.createElement("ol");
    reportBody.className = "focus-report-list";
    nodes.forEach((node, idx) => {
        const li = document.createElement("li");
        li.dataset.legendId = "node-" + idx;
        li.textContent = (node.label || "(unlabelled)") + " · " + node.role;
        reportBody.appendChild(li);
    });

    return {
        overlay: parsed > 0 ? overlay : null,
        legend: {
            title: "Hierarchy",
            summary: parsed + " node" + (parsed === 1 ? "" : "s"),
            entries,
        },
        report: {
            title: "Layout hierarchy",
            summary: parsed + " parsed · " + dropped + " dropped",
            body: reportBody,
        },
        error:
            dropped > 0 && parsed === 0
                ? {
                      title: "Hierarchy bounds malformed",
                      message:
                          "All " +
                          dropped +
                          " hierarchy node bounds failed to parse.",
                  }
                : null,
    };
}

function a11yFindingsPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const findings = ctx.findings;
    if (findings.length === 0) {
        return null;
    }
    const entries: LegendEntry[] = findings.map((f, idx) => ({
        id: "finding-" + idx,
        label: f.level + " · " + f.type,
        detail: f.message,
        level: levelOf(f.level),
    }));
    return {
        legend: {
            title: "A11y findings",
            summary:
                findings.length +
                " finding" +
                (findings.length === 1 ? "" : "s"),
            entries,
        },
    };
}

function composeThemePresenter(
    _ctx: PresenterContext,
): ProductPresentation | null {
    // Theme tokens are a placeholder until `compose/theme` lands as a
    // real data product; the report mount is what we're proving here.
    const body = document.createElement("div");
    body.className = "focus-report-empty";
    body.textContent =
        "Theme tokens will appear here when the daemon attaches `compose/theme`.";
    return {
        report: {
            title: "Theme tokens",
            summary: "Awaiting data",
            body,
        },
    };
}

function renderErrorPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    // Pulls a render-error sidecar off the card via dataset (set by
    // `messageHandlers.handleErrorMessage` when present). When no
    // error is recorded, the presenter contributes nothing.
    const message = ctx.card.dataset.renderError;
    if (!message) return null;
    return {
        error: {
            title: "Render failed",
            message,
            detail: ctx.card.dataset.renderErrorDetail,
        },
    };
}

// ---- Local helpers --------------------------------------------------------

function emptyBody(message: string): HTMLElement {
    const div = document.createElement("div");
    div.className = "focus-report-empty";
    div.textContent = message;
    return div;
}

function levelOf(s: string): "error" | "warning" | "info" {
    const lower = s.toLowerCase();
    if (lower === "error") return "error";
    if (lower === "warning") return "warning";
    return "info";
}

interface ParsedBounds {
    left: number;
    top: number;
    right: number;
    bottom: number;
}

function parseBoundsString(s: string | null | undefined): ParsedBounds | null {
    if (!s) return null;
    const parts = s.split(",").map((x) => parseInt(x.trim(), 10));
    if (parts.length !== 4 || parts.some(isNaN)) return null;
    return {
        left: parts[0],
        top: parts[1],
        right: parts[2],
        bottom: parts[3],
    };
}
