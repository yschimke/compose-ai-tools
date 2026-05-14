// Performance bundle presenter — fills the "Performance" tab body in
// `<data-tabs>`. The bundle bundles three wire kinds that all answer
// perf questions but with different shapes, so the tab body is a
// stacked set of sub-sections rather than a single table:
//
//   * `compose/recomposition` — top-N hottest scopes by count
//   * `render/trace` — phase bar chart + metrics fallthrough
//   * `render/composeAiTrace` — summary + Perfetto handoff button
//
// Each sub-section renders independently when its payload is present;
// when the chip is on but no kind is enabled the host shell shows a
// hint to use the Configure expander (default-OFF for all three since
// they're medium+ cost).
//
// The kind-specific renderers in `focusPresentation.ts` (recomposition,
// render-trace) are intentionally left in place so the legacy focus
// inspector keeps working through migration. Shape-wise this presenter
// mirrors their parsing — same defensive `typeof` checks, same TOP_N,
// same `metrics.tookMs` suppression.
//
// See `docs/design/EXTENSION_DATA_EXPOSURE.md` § Performance.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

export interface RecompositionRow {
    id: string;
    nodeId: string;
    count: number;
    mode: string;
    inputSeq: number | null;
}

export interface RenderTracePhase {
    name: string;
    startMs: number;
    durationMs: number;
    /** Per-phase % of `chartScale`, pre-computed so the renderer
     *  doesn't redo the divide on each paint. */
    widthPct: number;
}

export interface RenderTraceMetric {
    key: string;
    value: string;
}

export interface RenderTraceSection {
    totalMs: number | null;
    phases: readonly RenderTracePhase[];
    metrics: readonly RenderTraceMetric[];
}

export interface ComposeAiTraceSummary {
    phaseCount: number;
    totalMs: number | null;
    topPhases: readonly string[];
    /** Full payload kept around so the "Open in Perfetto" button can
     *  ship JSON to the clipboard without re-fetching. */
    rawPayload: unknown;
}

export interface PerformanceBundleData {
    recomposition: {
        rows: readonly RecompositionRow[];
        mode: string;
        inputSeq: number | null;
        totalNodes: number;
        truncated: number;
    } | null;
    renderTrace: RenderTraceSection | null;
    composeAiTrace: ComposeAiTraceSummary | null;
}

const TOP_N_RECOMPOSITION = 10;
const TOP_N_PERFETTO_PHASES = 3;

/**
 * Build the data behind the Performance tab body from the raw wire
 * payloads. Each input may be `null`/missing — the corresponding
 * section comes back as `null` when its payload didn't produce anything
 * displayable (kept symmetric with the focus-inspector presenters so
 * test fixtures port over).
 */
export function computePerformanceBundleData(
    recompositionPayload: unknown,
    traceP: unknown,
    perfettoP: unknown,
): PerformanceBundleData {
    return {
        recomposition: parseRecomposition(recompositionPayload),
        renderTrace: parseRenderTrace(traceP),
        composeAiTrace: parseComposeAiTrace(perfettoP),
    };
}

/**
 * Column shape for the recomposition sub-table. Sourced from the
 * focus-inspector `composeRecompositionPresenter` registration in
 * `focusPresentation.ts` — same nodeId / count split, with the mode
 * and inputSeq lifted into the row so the column renderer can chip
 * them for at-a-glance triage.
 */
export function performanceTableColumns(): readonly DataTableColumn<RecompositionRow>[] {
    return [
        {
            header: "#",
            cellClass: "perf-recomp-rank",
            render: (_row, idx) => String(idx + 1),
        },
        {
            header: "Node",
            cellClass: "perf-recomp-node",
            render: (row) =>
                html`<code class="perf-recomp-nodeid">${row.nodeId}</code>`,
        },
        {
            header: "Count",
            cellClass: "perf-recomp-count",
            render: (row) =>
                html`<span class="perf-recomp-count-badge">${row.count}</span>`,
        },
        {
            header: "Mode",
            cellClass: "perf-recomp-mode",
            render: (row) => renderModeCell(row),
        },
    ];
}

function renderModeCell(row: RecompositionRow): TemplateResult | string {
    if (!row.mode && row.inputSeq === null) return "—";
    const chips: TemplateResult[] = [];
    if (row.mode) {
        chips.push(
            html`<span class="perf-chip" data-mode=${row.mode}
                >${row.mode}</span
            >`,
        );
    }
    if (row.mode === "delta" && row.inputSeq !== null) {
        chips.push(
            html`<span class="perf-chip perf-chip-seq"
                >input ${row.inputSeq}</span
            >`,
        );
    }
    return html`<div class="perf-chip-row">${chips}</div>`;
}

function parseRecomposition(
    raw: unknown,
): PerformanceBundleData["recomposition"] {
    if (!raw || typeof raw !== "object") return null;
    const payload = raw as {
        mode?: unknown;
        inputSeq?: unknown;
        nodes?: unknown;
    };
    const nodes = Array.isArray(payload.nodes) ? payload.nodes : [];
    const parsed: Array<{ nodeId: string; count: number }> = [];
    for (const n of nodes) {
        if (!n || typeof n !== "object") continue;
        const node = n as { nodeId?: unknown; count?: unknown };
        if (typeof node.nodeId !== "string" || node.nodeId.length === 0) {
            continue;
        }
        if (typeof node.count !== "number" || !Number.isFinite(node.count)) {
            continue;
        }
        parsed.push({ nodeId: node.nodeId, count: node.count });
    }
    if (parsed.length === 0) return null;
    parsed.sort((a, b) => b.count - a.count);
    const mode = typeof payload.mode === "string" ? payload.mode : "";
    const inputSeq =
        typeof payload.inputSeq === "number" &&
        Number.isFinite(payload.inputSeq)
            ? payload.inputSeq
            : null;
    const top = parsed.slice(0, TOP_N_RECOMPOSITION);
    const rows: RecompositionRow[] = top.map((node, idx) => ({
        id: "perf-recomp-" + idx,
        nodeId: node.nodeId,
        count: node.count,
        mode,
        inputSeq,
    }));
    return {
        rows,
        mode,
        inputSeq,
        totalNodes: parsed.length,
        truncated: Math.max(0, parsed.length - TOP_N_RECOMPOSITION),
    };
}

function parseRenderTrace(raw: unknown): RenderTraceSection | null {
    if (!raw || typeof raw !== "object") return null;
    const payload = raw as {
        totalMs?: unknown;
        phases?: unknown;
        metrics?: unknown;
    };
    const totalMs =
        typeof payload.totalMs === "number" && Number.isFinite(payload.totalMs)
            ? payload.totalMs
            : null;
    const rawPhases = Array.isArray(payload.phases) ? payload.phases : [];
    const phasesNoWidth: Array<{
        name: string;
        startMs: number;
        durationMs: number;
    }> = [];
    for (const p of rawPhases) {
        if (!p || typeof p !== "object") continue;
        const ph = p as {
            name?: unknown;
            startMs?: unknown;
            durationMs?: unknown;
        };
        if (typeof ph.name !== "string" || ph.name.length === 0) continue;
        if (
            typeof ph.durationMs !== "number" ||
            !Number.isFinite(ph.durationMs)
        ) {
            continue;
        }
        const startMs =
            typeof ph.startMs === "number" && Number.isFinite(ph.startMs)
                ? ph.startMs
                : 0;
        phasesNoWidth.push({
            name: ph.name,
            startMs,
            durationMs: ph.durationMs,
        });
    }
    const maxDuration = phasesNoWidth.reduce(
        (acc, p) => (p.durationMs > acc ? p.durationMs : acc),
        0,
    );
    const chartScale = totalMs && totalMs > 0 ? totalMs : maxDuration;
    const phases: RenderTracePhase[] = phasesNoWidth.map((p) => ({
        ...p,
        widthPct:
            chartScale > 0
                ? Math.max(0, Math.min(100, (p.durationMs / chartScale) * 100))
                : 0,
    }));

    const rawMetrics =
        payload.metrics && typeof payload.metrics === "object"
            ? (payload.metrics as Record<string, unknown>)
            : {};
    const metrics: RenderTraceMetric[] = [];
    for (const key of Object.keys(rawMetrics).sort()) {
        if (key === "tookMs") continue; // redundant with totalMs
        const value = rawMetrics[key];
        if (typeof value === "string" || typeof value === "number") {
            metrics.push({ key, value: String(value) });
        }
    }
    if (phases.length === 0 && metrics.length === 0 && totalMs === null) {
        return null;
    }
    return { totalMs, phases, metrics };
}

function parseComposeAiTrace(raw: unknown): ComposeAiTraceSummary | null {
    if (!raw || typeof raw !== "object") return null;
    // composeAiTrace mirrors the Perfetto JSON shape — it has a
    // top-level array of trace events under `traceEvents`. We only
    // need a summary here; the full payload rides along on
    // `rawPayload` so the copy button doesn't have to round-trip
    // through the daemon again.
    const payload = raw as {
        traceEvents?: unknown;
        totalMs?: unknown;
    };
    const events = Array.isArray(payload.traceEvents)
        ? payload.traceEvents
        : [];
    const phaseCounts = new Map<string, number>();
    for (const e of events) {
        if (!e || typeof e !== "object") continue;
        const evt = e as { name?: unknown; ph?: unknown };
        if (typeof evt.name !== "string" || evt.name.length === 0) continue;
        // Count complete ("X") and begin ("B") events as phases — the
        // common Perfetto shape Compose emits is "X" durations.
        const ph = typeof evt.ph === "string" ? evt.ph : "";
        if (ph && ph !== "X" && ph !== "B") continue;
        phaseCounts.set(evt.name, (phaseCounts.get(evt.name) ?? 0) + 1);
    }
    const totalMs =
        typeof payload.totalMs === "number" && Number.isFinite(payload.totalMs)
            ? payload.totalMs
            : null;
    if (phaseCounts.size === 0 && totalMs === null) return null;
    const topPhases = [...phaseCounts.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, TOP_N_PERFETTO_PHASES)
        .map(([name, count]) => `${name} (${count})`);
    return {
        phaseCount: phaseCounts.size,
        totalMs,
        topPhases,
        rawPayload: raw,
    };
}

/**
 * Imperative host renderer that paints the stacked performance
 * sub-sections into [host] given the computed [data]. Sections that
 * have no payload are skipped — when none have any payload the caller
 * (main.ts) shows a placeholder via {@link renderPerfPlaceholder}.
 *
 * The recomposition section reuses the shared `<data-table>` primitive
 * (passed in as [recompTable]) so it gets the same row-hover, copy-JSON,
 * and visual chrome as the other bundles. The render-trace and Perfetto
 * sections paint their own DOM since they're not row-based.
 */
export function renderPerformanceSections(
    host: HTMLElement,
    data: PerformanceBundleData,
    previewId: string,
    // Caller hands us a `DataTable<unknown>` (shared primitive); we
    // narrow internally without re-typing the element. Using an
    // `unknown`-row shape here keeps the cast at the construction
    // site (main.ts) instead of fighting the element's type
    // parameter from the presenter side.
    recompTable: {
        setRows(rows: readonly unknown[]): void;
        summary: string;
        setOverlayId(fn: (row: unknown, idx: number) => string): void;
        setJsonPayload(fn: () => unknown): void;
    },
    copyToClipboard: (text: string) => void,
    rawPayloads: {
        recomposition: unknown;
        renderTrace: unknown;
        composeAiTrace: unknown;
    },
    openExternal?: (url: string) => void,
): void {
    // Tear down prior contents — sections may have toggled off since
    // the last refresh, and we want a deterministic layout.
    host.replaceChildren();

    if (data.recomposition) {
        const section = document.createElement("div");
        section.className = "perf-bundle-section perf-bundle-recomposition";
        const r = data.recomposition;
        recompTable.setRows(r.rows as readonly unknown[]);
        const summaryParts: string[] = [];
        if (r.mode) summaryParts.push(r.mode);
        if (r.mode === "delta" && r.inputSeq !== null) {
            summaryParts.push("input " + r.inputSeq);
        }
        summaryParts.push(
            r.totalNodes === 1 ? "1 node" : r.totalNodes + " nodes",
        );
        recompTable.summary = summaryParts.join(" · ");
        recompTable.setOverlayId((row) => (row as RecompositionRow).id);
        recompTable.setJsonPayload(() => ({
            previewId,
            recomposition: rawPayloads.recomposition,
        }));
        section.appendChild(recompTable as unknown as HTMLElement);
        if (r.truncated > 0) {
            const more = document.createElement("div");
            more.className = "perf-recomp-more";
            more.textContent =
                "+" + r.truncated + " more (showing top " + r.rows.length + ")";
            section.appendChild(more);
        }
        host.appendChild(section);
    }

    if (data.renderTrace) {
        host.appendChild(renderRenderTraceSection(data.renderTrace));
    }

    if (data.composeAiTrace) {
        host.appendChild(
            renderComposeAiTraceSection(
                data.composeAiTrace,
                previewId,
                rawPayloads,
                copyToClipboard,
                openExternal,
            ),
        );
    }
}

/**
 * Placeholder body when the chip is on but every kind is default-OFF
 * and no payload has arrived. Hint the user to open Configure… in
 * the tab header; the design doc specifies this rather than auto-
 * enabling a kind, since every Performance kind has nontrivial cost.
 */
export function renderPerfPlaceholder(host: HTMLElement): void {
    host.replaceChildren();
    const div = document.createElement("div");
    div.className = "perf-bundle-empty";
    const title = document.createElement("p");
    title.className = "perf-bundle-empty-title";
    title.textContent = "Pick a perf kind in Configure…";
    div.appendChild(title);
    const detail = document.createElement("p");
    detail.className = "perf-bundle-empty-detail";
    detail.textContent =
        "Recomposition counts, render trace, and Perfetto export are all " +
        "off by default because they cost the daemon a render. " +
        "Open the gear next to the tab to enable one.";
    div.appendChild(detail);
    host.appendChild(div);
}

function renderRenderTraceSection(trace: RenderTraceSection): HTMLElement {
    const section = document.createElement("div");
    section.className = "perf-bundle-section perf-bundle-render-trace";
    const header = document.createElement("div");
    header.className = "perf-section-header";
    const title = document.createElement("span");
    title.className = "perf-section-title";
    title.textContent = "Render trace";
    header.appendChild(title);
    if (trace.totalMs !== null) {
        const total = document.createElement("span");
        total.className = "perf-section-summary";
        total.textContent = trace.totalMs + " ms total";
        header.appendChild(total);
    }
    section.appendChild(header);

    if (trace.phases.length > 0) {
        const bar = document.createElement("div");
        bar.className = "perf-phase-bar";
        for (const phase of trace.phases) {
            const seg = document.createElement("div");
            seg.className = "perf-phase-bar-segment";
            seg.style.width = phase.widthPct + "%";
            seg.title = phase.name + ": " + phase.durationMs + " ms";
            const label = document.createElement("span");
            label.className = "perf-phase-bar-segment-label";
            label.textContent = phase.name + " · " + phase.durationMs + " ms";
            seg.appendChild(label);
            bar.appendChild(seg);
        }
        section.appendChild(bar);
    }

    if (trace.metrics.length > 0) {
        const dl = document.createElement("dl");
        dl.className = "perf-metrics-list";
        for (const m of trace.metrics) {
            const dt = document.createElement("dt");
            dt.textContent = m.key;
            const dd = document.createElement("dd");
            dd.textContent = m.value;
            dl.appendChild(dt);
            dl.appendChild(dd);
        }
        section.appendChild(dl);
    }
    return section;
}

function renderComposeAiTraceSection(
    summary: ComposeAiTraceSummary,
    previewId: string,
    rawPayloads: {
        recomposition: unknown;
        renderTrace: unknown;
        composeAiTrace: unknown;
    },
    copyToClipboard: (text: string) => void,
    openExternal?: (url: string) => void,
): HTMLElement {
    const section = document.createElement("div");
    section.className = "perf-bundle-section perf-bundle-perfetto";
    const header = document.createElement("div");
    header.className = "perf-section-header";
    const title = document.createElement("span");
    title.className = "perf-section-title";
    title.textContent = "Perfetto trace";
    header.appendChild(title);
    section.appendChild(header);

    const summaryRow = document.createElement("div");
    summaryRow.className = "perf-perfetto-summary";
    const phasesEl = document.createElement("span");
    phasesEl.textContent =
        summary.phaseCount === 1 ? "1 phase" : summary.phaseCount + " phases";
    summaryRow.appendChild(phasesEl);
    if (summary.totalMs !== null) {
        const total = document.createElement("span");
        total.textContent = " · " + summary.totalMs + " ms";
        summaryRow.appendChild(total);
    }
    if (summary.topPhases.length > 0) {
        const top = document.createElement("span");
        top.className = "perf-perfetto-top";
        top.textContent = " · top: " + summary.topPhases.join(", ");
        summaryRow.appendChild(top);
    }
    section.appendChild(summaryRow);

    const button = document.createElement("button");
    button.type = "button";
    button.className = "perf-perfetto-open";
    button.title =
        "Copies the trace to your clipboard and opens ui.perfetto.dev. " +
        "Paste with Cmd/Ctrl-V to load.";
    const icon = document.createElement("i");
    icon.className = "codicon codicon-cloud-upload";
    icon.setAttribute("aria-hidden", "true");
    button.appendChild(icon);
    const label = document.createElement("span");
    label.textContent = "Open in Perfetto";
    button.appendChild(label);

    // Inline status hint below the button — `ui.perfetto.dev` won't
    // fetch a `vscode://` or `file:` URL from disk (CORS), so the
    // documented `?url=…` ingestion path doesn't apply to traces
    // sourced from this extension's storage. We keep the UX
    // one-click by pairing the clipboard write with opening the
    // Perfetto UI in the browser, and the user pastes with
    // Cmd/Ctrl-V on the just-opened tab.
    const status = document.createElement("div");
    status.className = "perf-perfetto-status";
    status.setAttribute("role", "status");
    status.setAttribute("aria-live", "polite");
    section.appendChild(button);
    section.appendChild(status);

    button.addEventListener("click", () => {
        // Ship the raw Perfetto-importable trace JSON only — paste-
        // into-ui.perfetto.dev expects the trace at the document root
        // (it scans for top-level `traceEvents` etc.), so wrapping it
        // under `composeAiTrace` was a regression: the paste loaded
        // as "not a trace." When the daemon hasn't attached a
        // composeAiTrace payload yet, fall through to an empty
        // string so the clipboard write is a clear no-op rather than
        // shipping a misleading wrapper. In the no-payload case we
        // also suppress the `openExternal` — there's nothing to
        // paste, so opening a browser tab would be pointless.
        const trace = rawPayloads.composeAiTrace;
        if (trace) {
            copyToClipboard(JSON.stringify(trace, null, 2));
            if (openExternal) {
                openExternal("https://ui.perfetto.dev");
            }
            status.textContent =
                "Trace copied. Paste with Cmd/Ctrl-V in the page that just opened.";
        } else {
            copyToClipboard("");
            status.textContent = "";
        }
    });
    return section;
}
