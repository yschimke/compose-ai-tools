// Build `<bundle-row-detail>` sections for the History diff bundle.
// Pure helper — host wires the data-table's `row-clicked` listener
// to call this with the row + the source payload so the detail
// panel can show per-channel deltas (not on the bundle row) and the
// baseline-history context shared by every region.

import { html, type TemplateResult } from "lit";
import type {
    HistoryDiffPayload,
    HistoryDiffRow,
} from "./historyDiffBundlePresenter";
import type { BundleRowDetailSection } from "./components/BundleRowDetail";

export function buildHistoryRowDetail(
    row: HistoryDiffRow,
    payload: HistoryDiffPayload | null | undefined,
    rowIndex: number,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    // ---- Region ------------------------------------------------------
    const regionEntries: {
        label: string;
        value: string | TemplateResult;
    }[] = [
        {
            label: "Bounds",
            value: row.boundsLabel
                ? html`<code>${row.boundsLabel}</code>`
                : "—",
        },
        {
            label: "Pixels changed",
            value: row.pixelCount.toLocaleString(),
        },
        {
            label: "Δ magnitude",
            value: row.deltaMagnitude.toFixed(1),
        },
        {
            label: "Intensity",
            value: (row.intensity * 100).toFixed(0) + "%",
        },
    ];
    sections.push({ heading: "Region", entries: regionEntries });

    // ---- Channel deltas ---------------------------------------------
    // The bundle row carries the Euclidean magnitude but not the
    // per-channel breakdown; re-join against the source payload to
    // surface r/g/b/a. Order matches the wire payload's index — the
    // bundle row's `id` is `history-diff-region-${idx}`, so the
    // caller passes that index directly.
    const region = payload?.regions?.[rowIndex];
    if (region?.avgDelta) {
        const d = region.avgDelta;
        const channelEntries: { label: string; value: string }[] = [];
        if (typeof d.r === "number") {
            channelEntries.push({ label: "Δ red", value: d.r.toFixed(1) });
        }
        if (typeof d.g === "number") {
            channelEntries.push({ label: "Δ green", value: d.g.toFixed(1) });
        }
        if (typeof d.b === "number") {
            channelEntries.push({ label: "Δ blue", value: d.b.toFixed(1) });
        }
        if (typeof d.a === "number") {
            channelEntries.push({ label: "Δ alpha", value: d.a.toFixed(1) });
        }
        if (channelEntries.length > 0) {
            sections.push({
                heading: "Channel deltas",
                entries: channelEntries,
            });
        }
    }

    // ---- Baseline context -------------------------------------------
    if (payload?.baselineHistoryId) {
        const baselineEntries: {
            label: string;
            value: string | TemplateResult;
        }[] = [
            {
                label: "History id",
                value: html`<code>${payload.baselineHistoryId}</code>`,
            },
        ];
        if (typeof payload.totalPixelsChanged === "number") {
            baselineEntries.push({
                label: "Total pixels",
                value: payload.totalPixelsChanged.toLocaleString(),
            });
        }
        if (typeof payload.changedFraction === "number") {
            baselineEntries.push({
                label: "Changed fraction",
                value: (payload.changedFraction * 100).toFixed(2) + "%",
            });
        }
        sections.push({ heading: "Baseline", entries: baselineEntries });
    }

    return sections;
}
