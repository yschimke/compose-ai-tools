// History-diff bundle presenter — fills the "History" tab body from
// the `history/diff/regions` payload (see `HistoryDiffModels.kt`):
// `{ baselineHistoryId, totalPixelsChanged, changedFraction,
// regions: [{ bounds, pixelCount, avgDelta: { r, g, b, a } }] }`.
// The table shows one row per region with a tinted swatch driven by
// the region's average colour delta. Each region also paints a box
// on the shared `<box-overlay>` primitive — opacity ramps with delta
// magnitude so small drift fades and large drift is opaque.
//
// The presenter is **stateless** — pure function from payload to
// `{ rows, overlay, header }`. The host paints the header (baseline +
// totals) above the table; the table consumes `rows`.

import { html } from "lit";
import type { DataTableColumn } from "./components/DataTable";
import type { OverlayBox } from "./components/BoxOverlay";
import { parseBounds, type ParsedBounds } from "./cardData";

export interface HistoryDiffAverageDelta {
    r?: number;
    g?: number;
    b?: number;
    a?: number;
}

export interface HistoryDiffRegion {
    bounds: string;
    pixelCount: number;
    avgDelta?: HistoryDiffAverageDelta | null;
}

export interface HistoryDiffPayload {
    baselineHistoryId?: string;
    totalPixelsChanged?: number;
    changedFraction?: number;
    regions?: readonly HistoryDiffRegion[];
}

export interface HistoryDiffRow {
    id: string;
    bounds: ParsedBounds | null;
    boundsLabel: string;
    pixelCount: number;
    deltaMagnitude: number;
    /** 0..1 — drives the swatch / overlay opacity. */
    intensity: number;
}

export interface HistoryDiffHeader {
    baselineHistoryId: string;
    totalPixelsChanged: number;
    changedFraction: number;
    regionCount: number;
}

export interface HistoryDiffBundleData {
    header: HistoryDiffHeader;
    rows: readonly HistoryDiffRow[];
    overlay: readonly OverlayBox[];
}

/** Tint applied to all diff overlays / swatches. Picked to read on
 *  both light and dark VS Code themes — magenta sits out of the
 *  default panel palette and the alpha ramp gives the high-delta
 *  regions their punch. */
const DIFF_COLOR = "#d96bff";

/** Per-channel average deltas are bounded by 255, and the Euclidean
 *  norm across RGBA tops out at sqrt(4) · 255 ≈ 510. Use that as the
 *  ramp ceiling so a single high channel still reads as "significant"
 *  without burying smaller multi-channel deltas in noise. */
const MAX_MAGNITUDE = 510;

export function computeHistoryDiffBundleData(
    payload: HistoryDiffPayload | null | undefined,
): HistoryDiffBundleData {
    const regions = payload?.regions ?? [];
    const rows: HistoryDiffRow[] = [];
    const overlay: OverlayBox[] = [];
    regions.forEach((region, idx) => {
        const id = "history-diff-region-" + idx;
        const bounds = parseBounds(region.bounds);
        const magnitude = deltaMagnitude(region.avgDelta);
        const intensity = clamp01(magnitude / MAX_MAGNITUDE);
        const row: HistoryDiffRow = {
            id,
            bounds,
            boundsLabel: region.bounds,
            pixelCount: region.pixelCount,
            deltaMagnitude: magnitude,
            intensity,
        };
        rows.push(row);
        if (bounds) {
            overlay.push({
                id,
                bounds,
                level: "info",
                color: DIFF_COLOR,
                tooltip:
                    "Δ " +
                    magnitude.toFixed(1) +
                    " · " +
                    region.pixelCount +
                    " px",
            });
        }
    });
    const header: HistoryDiffHeader = {
        baselineHistoryId: payload?.baselineHistoryId ?? "",
        totalPixelsChanged: payload?.totalPixelsChanged ?? 0,
        changedFraction: payload?.changedFraction ?? 0,
        regionCount: regions.length,
    };
    return { header, rows, overlay };
}

export function historyDiffTableColumns(): readonly DataTableColumn<HistoryDiffRow>[] {
    return [
        {
            header: "",
            cellClass: "history-diff-swatch-cell",
            render: (row) => html`
                <span
                    class="history-diff-swatch"
                    style=${`--intensity:${row.intensity.toFixed(3)}`}
                    aria-hidden="true"
                ></span>
            `,
        },
        {
            header: "Bounds",
            cellClass: "history-diff-bounds-cell",
            render: (row) => html`<code>${row.boundsLabel}</code>`,
        },
        {
            header: "Pixels",
            render: (row) => row.pixelCount.toLocaleString(),
        },
        {
            header: "Δ avg",
            render: (row) => row.deltaMagnitude.toFixed(1),
        },
    ];
}

/**
 * Render the per-bundle header (baseline id + totals) the host stamps
 * above the data-table. Pure DOM — no external state, safe to call
 * on every refresh.
 */
export function renderHistoryDiffHeader(
    header: HistoryDiffHeader,
): HTMLElement {
    const el = document.createElement("div");
    el.className = "history-diff-header";
    const baseline = document.createElement("div");
    baseline.className = "history-diff-baseline";
    const labelSpan = document.createElement("span");
    labelSpan.className = "history-diff-baseline-label";
    labelSpan.textContent = "Baseline:";
    const code = document.createElement("code");
    code.className = "history-diff-baseline-id";
    code.textContent = header.baselineHistoryId || "(none)";
    baseline.appendChild(labelSpan);
    baseline.appendChild(document.createTextNode(" "));
    baseline.appendChild(code);
    const totals = document.createElement("div");
    totals.className = "history-diff-totals";
    const fraction = (header.changedFraction * 100).toFixed(2);
    totals.textContent =
        header.totalPixelsChanged.toLocaleString() +
        " px changed · " +
        fraction +
        "% · " +
        header.regionCount +
        " region" +
        (header.regionCount === 1 ? "" : "s");
    el.appendChild(baseline);
    el.appendChild(totals);
    return el;
}

function deltaMagnitude(d: HistoryDiffAverageDelta | null | undefined): number {
    if (!d) return 0;
    // Euclidean magnitude across RGBA gives a single number the
    // swatch / overlay opacity can ramp against. We treat each
    // channel independently and ignore signedness — the daemon
    // reports the mean signed delta, but the visual cue is "how
    // much did pixels move" regardless of direction.
    const r = numberOr(d.r);
    const g = numberOr(d.g);
    const b = numberOr(d.b);
    const a = numberOr(d.a);
    return Math.sqrt(r * r + g * g + b * b + a * a);
}

function numberOr(n: number | null | undefined): number {
    return typeof n === "number" && Number.isFinite(n) ? Math.abs(n) : 0;
}

function clamp01(x: number): number {
    if (x < 0) return 0;
    if (x > 1) return 1;
    return x;
}
