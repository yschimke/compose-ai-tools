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
    /** Latest daemon data-product payload for this kind, if any. */
    data?(kind: string): unknown;
    /** Post a `WebviewToExtension` message back to the host. Optional so
     *  presenter unit tests don't need to thread a stub through every
     *  case — presenters that want deep-link behaviour should null-check
     *  before calling. */
    postMessage?(msg: unknown): void;
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

/**
 * Visible for tests only — re-register the built-in presenters after a
 * call to {@link _resetPresentersForTest}. Side-effect-only `require`
 * of this module won't re-run the bottom-of-file `registerPresenter`
 * calls (Node caches the module), and even busting the require cache
 * creates a *second* copy of the registry — so the only reliable way
 * to put the built-ins back is to call this helper directly.
 */
export function _seedBuiltInPresentersForTest(): void {
    seedBuiltInPresenters();
}

// ---- Built-in presenters --------------------------------------------------
//
// Concrete presenters live alongside the framework so the registry is
// populated by the side-effect of importing this module. The a11y
// hierarchy presenter is the worked example: it contributes to all
// four surfaces. Other built-ins are deliberately narrow — the goal
// here is to prove each surface's mount works end-to-end, not to fake
// data the daemon doesn't actually produce.

function seedBuiltInPresenters(): void {
    registerPresenter("a11y/hierarchy", a11yHierarchyPresenter);
    registerPresenter("a11y/atf", a11yFindingsPresenter);
    registerPresenter("a11y/overlay", a11yOverlayPresenter);
    registerPresenter("compose/theme", composeThemePresenter);
    registerPresenter("resources/used", resourcesUsedPresenter);
    registerPresenter("compose/recomposition", composeRecompositionPresenter);
    registerPresenter("render/trace", renderTracePresenter);
    registerPresenter("local/render/error", renderErrorPresenter);
}

seedBuiltInPresenters();

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

/**
 * `a11y/overlay` — the daemon's annotated PNG. Rendered as a Report
 * (collapsed `<details>`) rather than swapped onto the card so the
 * structured a11y kinds (`a11y/atf` + `a11y/hierarchy`) remain the
 * primary review surface. The PNG is here for investigations where a
 * structured-only view isn't enough — e.g. reproducing what a CI PR
 * preview reported.
 *
 * Wire payload is `{ imageBase64, mediaType, sizeBytes }`, posted from
 * `extension.ts` after reading the path-transport PNG off disk.
 */
function a11yOverlayPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const raw = ctx.data?.("a11y/overlay");
    if (!raw || typeof raw !== "object") return null;
    const data = raw as {
        imageBase64?: unknown;
        mediaType?: unknown;
        sizeBytes?: unknown;
    };
    if (typeof data.imageBase64 !== "string" || data.imageBase64.length === 0) {
        return null;
    }
    const mediaType =
        typeof data.mediaType === "string" ? data.mediaType : "image/png";
    const body = document.createElement("div");
    body.className = "focus-report-overlay-png";
    const note = document.createElement("p");
    note.className = "focus-report-overlay-note";
    note.textContent =
        "Daemon-rendered annotated PNG. Useful for investigating what a CI " +
        "preview reported; for routine review prefer the structured a11y " +
        "kinds (findings + hierarchy) which drive the locally-painted overlay.";
    const img = document.createElement("img");
    img.className = "focus-report-overlay-img";
    img.alt = "Accessibility overlay (annotated)";
    img.src = `data:${mediaType};base64,${data.imageBase64}`;
    body.appendChild(note);
    body.appendChild(img);
    const summary =
        typeof data.sizeBytes === "number" && data.sizeBytes > 0
            ? `${Math.max(1, Math.round(data.sizeBytes / 1024))} kB`
            : undefined;
    return {
        report: {
            title: "Accessibility overlay (PNG)",
            summary,
            body,
        },
    };
}

function composeThemePresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const payload = ctx.data?.("compose/theme") as
        | {
              colors?: Record<string, string>;
              typography?: Record<string, string>;
              shapes?: Record<string, string>;
          }
        | undefined;
    if (!payload) {
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
    const body = document.createElement("div");
    const rows = document.createElement("ul");
    rows.className = "focus-report-list";
    const addSection = (title: string, values: Record<string, string> = {}) => {
        const keys = Object.keys(values);
        if (keys.length === 0) return;
        const header = document.createElement("li");
        header.textContent = title;
        rows.appendChild(header);
        for (const key of keys.sort()) {
            const li = document.createElement("li");
            li.textContent = `${key}: ${values[key]}`;
            rows.appendChild(li);
        }
    };
    addSection("Colors", payload.colors);
    addSection("Typography", payload.typography);
    addSection("Shapes", payload.shapes);
    if (!rows.hasChildNodes()) {
        body.className = "focus-report-empty";
        body.textContent = "compose/theme payload was empty.";
    } else {
        body.appendChild(rows);
    }
    return {
        report: {
            title: "Theme tokens",
            summary: "Daemon data",
            body,
        },
    };
}

/**
 * `resources/used` — Android resources resolved while rendering. Surfaces
 * a single `<table>` Report keyed by `(resourceType, resourceName,
 * packageName)`, with deep-link cells that post `openResourceFile` so the
 * extension host can jump to the values XML entry (string/color/dimen/…)
 * or open the binary asset (drawable/mipmap/raw).
 *
 * Hover-overlay box correlation is gated on `resources/consumers` joins
 * with `compose/semantics` bounds — out of scope for this presenter
 * today, so the Legend / overlay surfaces stay empty. The table is the
 * useful artefact: it's the surface that turns "what did this preview
 * pull from `res/`?" from a build-output spelunk into one click.
 */
function resourcesUsedPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const raw = ctx.data?.("resources/used");
    if (!raw || typeof raw !== "object") return null;
    const references = (raw as { references?: unknown }).references;
    if (!Array.isArray(references) || references.length === 0) return null;

    const rows: ResourceReferenceRow[] = [];
    for (const item of references) {
        if (!item || typeof item !== "object") continue;
        const ref = item as Record<string, unknown>;
        const resourceType =
            typeof ref.resourceType === "string" ? ref.resourceType : "";
        const resourceName =
            typeof ref.resourceName === "string" ? ref.resourceName : "";
        if (!resourceType || !resourceName) continue;
        rows.push({
            resourceType,
            resourceName,
            packageName:
                typeof ref.packageName === "string" ? ref.packageName : "",
            resolvedValue:
                typeof ref.resolvedValue === "string"
                    ? ref.resolvedValue
                    : null,
            resolvedFile:
                typeof ref.resolvedFile === "string" ? ref.resolvedFile : null,
            consumerCount: countConsumers(ref.consumers),
        });
    }
    if (rows.length === 0) return null;

    const body = renderResourcesUsedTable(rows, ctx);
    return {
        report: {
            title: "Resources used",
            summary:
                rows.length + " reference" + (rows.length === 1 ? "" : "s"),
            body,
        },
    };
}

/**
 * `compose/recomposition` — top-N hottest scopes by recomposition count.
 *
 * Wire payload (see `RecompositionModels.kt`):
 *   { mode: "delta" | "snapshot",
 *     sinceFrameStreamId?: string,
 *     inputSeq?: number,
 *     nodes: [{ nodeId: string, count: number }, ...] }
 *
 * Empty `nodes` returns `null` so the inspector skips the Report row
 * when the daemon answered "nothing recomposed." Until source-locations
 * land (slot-table-derived file:line:column keys, tracked alongside
 * `RecompositionDataProductRegistry`), there's no overlay — the legend
 * is the primary surface and entries are kept copy-pasteable so users
 * can paste `nodeId` into a renderer stack-trace search.
 */
function composeRecompositionPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const raw = ctx.data?.("compose/recomposition");
    if (!raw || typeof raw !== "object") return null;
    const payload = raw as {
        mode?: unknown;
        sinceFrameStreamId?: unknown;
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
    const TOP_N = 10;
    const top = parsed.slice(0, TOP_N);
    const mode = typeof payload.mode === "string" ? payload.mode : "";
    const inputSeq =
        typeof payload.inputSeq === "number" &&
        Number.isFinite(payload.inputSeq)
            ? payload.inputSeq
            : null;

    const summaryParts: string[] = [];
    if (mode) summaryParts.push(mode);
    if (mode === "delta" && inputSeq !== null) {
        summaryParts.push(`input ${inputSeq}`);
    }
    summaryParts.push(
        parsed.length === 1 ? "1 node" : `${parsed.length} nodes`,
    );
    const summary = summaryParts.join(" · ");

    const entries: LegendEntry[] = top.map((node, idx) => ({
        id: "recomp-" + idx,
        label: node.nodeId,
        detail:
            node.count === 1
                ? "1 recomposition"
                : `${node.count} recompositions`,
        level: "info",
    }));

    const body = document.createElement("div");
    body.className = "focus-report-recomposition";

    const header = document.createElement("div");
    header.className = "focus-report-recomposition-header";
    if (mode) {
        const badge = document.createElement("span");
        badge.className = "focus-report-recomposition-mode";
        badge.dataset.mode = mode;
        badge.textContent = mode;
        header.appendChild(badge);
    }
    if (mode === "delta" && inputSeq !== null) {
        const seq = document.createElement("span");
        seq.className = "focus-report-recomposition-inputseq";
        seq.textContent = `inputSeq ${inputSeq}`;
        header.appendChild(seq);
    }
    if (header.hasChildNodes()) body.appendChild(header);

    const list = document.createElement("ol");
    list.className = "focus-report-list focus-report-recomposition-list";
    top.forEach((node, idx) => {
        const li = document.createElement("li");
        li.dataset.legendId = "recomp-" + idx;
        const code = document.createElement("code");
        code.className = "focus-report-recomposition-nodeid";
        code.textContent = node.nodeId;
        const count = document.createElement("span");
        count.className = "focus-report-recomposition-count";
        count.textContent = ` · ${node.count}`;
        li.appendChild(code);
        li.appendChild(count);
        list.appendChild(li);
    });
    body.appendChild(list);

    if (parsed.length > TOP_N) {
        const more = document.createElement("div");
        more.className = "focus-report-recomposition-more";
        more.textContent = `+${parsed.length - TOP_N} more`;
        body.appendChild(more);
    }

    return {
        legend: {
            title: "Recomposition",
            summary,
            entries,
        },
        report: {
            title: "Recomposition",
            summary,
            body,
        },
    };
}

interface ResourceReferenceRow {
    resourceType: string;
    resourceName: string;
    packageName: string;
    resolvedValue: string | null;
    resolvedFile: string | null;
    consumerCount: number;
}

function countConsumers(value: unknown): number {
    if (!Array.isArray(value)) return 0;
    return value.length;
}

/** Hex shapes the swatch cell recognises — `#RGB`, `#RGBA`, `#RRGGBB`,
 *  `#AARRGGBB`. The Kotlin recorder normalises colours to `#AARRGGBB`
 *  via `"#%08X".format(value)`, but be lenient about other shapes the
 *  daemon (or a future producer) might emit. */
const RESOURCE_HEX_COLOUR_RE =
    /^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

function renderResourcesUsedTable(
    rows: readonly ResourceReferenceRow[],
    ctx: PresenterContext,
): HTMLElement {
    const wrap = document.createElement("div");
    wrap.className =
        "focus-report-generic-wrap focus-report-resources-used-wrap";
    const table = document.createElement("table");
    table.className = "focus-report-generic focus-report-table";
    table.dataset.kind = "resources/used";

    const thead = document.createElement("thead");
    const headRow = document.createElement("tr");
    for (const col of [
        "resourceType",
        "resourceName",
        "packageName",
        "resolvedValue",
        "resolvedFile",
        "consumers",
    ]) {
        const th = document.createElement("th");
        th.textContent = col;
        headRow.appendChild(th);
    }
    thead.appendChild(headRow);
    table.appendChild(thead);

    const tbody = document.createElement("tbody");
    for (const row of rows) {
        tbody.appendChild(renderResourcesRow(row, ctx));
    }
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
}

function renderResourcesRow(
    row: ResourceReferenceRow,
    ctx: PresenterContext,
): HTMLElement {
    const tr = document.createElement("tr");
    tr.dataset.resourceType = row.resourceType;
    tr.dataset.resourceName = row.resourceName;

    appendCell(tr, row.resourceType);

    const nameCell = document.createElement("td");
    // When no resolvedFile, fall back to making the name clickable —
    // the host's resolver will glob the workspace for a match.
    if (row.resolvedFile) {
        nameCell.textContent = row.resourceName;
    } else {
        nameCell.appendChild(buildResourceLink(row.resourceName, row, ctx));
    }
    tr.appendChild(nameCell);

    appendCell(tr, row.packageName);

    tr.appendChild(buildResolvedValueCell(row));

    const fileCell = document.createElement("td");
    if (row.resolvedFile) {
        fileCell.appendChild(buildResourceLink(row.resolvedFile, row, ctx));
    } else {
        fileCell.textContent = "";
    }
    tr.appendChild(fileCell);

    appendCell(tr, row.consumerCount > 0 ? String(row.consumerCount) : "");
    return tr;
}

function appendCell(tr: HTMLElement, text: string): void {
    const td = document.createElement("td");
    td.textContent = text;
    tr.appendChild(td);
}

function buildResolvedValueCell(row: ResourceReferenceRow): HTMLElement {
    const td = document.createElement("td");
    const value = row.resolvedValue;
    if (!value) {
        return td;
    }
    if (row.resourceType === "color" && RESOURCE_HEX_COLOUR_RE.test(value)) {
        const swatch = document.createElement("span");
        swatch.className = "focus-report-swatch-sample";
        swatch.style.backgroundColor = value;
        swatch.title = value;
        td.appendChild(swatch);
        const text = document.createElement("span");
        text.className = "focus-report-swatch-value";
        text.textContent = value;
        td.appendChild(text);
        return td;
    }
    if (
        (row.resourceType === "drawable" || row.resourceType === "mipmap") &&
        row.resolvedFile &&
        isRasterAsset(row.resolvedFile)
    ) {
        // Browsers in the webview only load images from approved URI
        // schemes (the host's CSP). For now we stick with the path
        // text; a future change can pre-bake a data: URI via the
        // extension when the daemon attaches the asset bytes.
        td.textContent = row.resolvedFile;
        return td;
    }
    td.textContent = value;
    return td;
}

function isRasterAsset(path: string): boolean {
    const lower = path.toLowerCase();
    return (
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".gif")
    );
}

function buildResourceLink(
    text: string,
    row: ResourceReferenceRow,
    ctx: PresenterContext,
): HTMLElement {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "focus-report-resource-link";
    button.textContent = text;
    button.title = "Open " + (row.resolvedFile || row.resourceName);
    button.addEventListener("click", (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        ctx.postMessage?.({
            command: "openResourceFile",
            resourceType: row.resourceType,
            resourceName: row.resourceName,
            resolvedFile: row.resolvedFile,
            packageName: row.packageName,
        });
    });
    return button;
}

/**
 * `render/trace` — phase timeline + total + metrics fallthrough.
 *
 * Wire payload (see `RenderTraceDataProduct.kt`):
 *   { totalMs: number,
 *     phases: [{ name: string, startMs: number, durationMs: number }, ...],
 *     metrics: Record<string, number | string> }
 *
 * Today the daemon emits a single `render` phase covering the whole
 * capture; multi-phase (compose / measure / draw / capture) is planned
 * and the same code paints the breakdown when it lands. Empty `phases`
 * AND empty `metrics` returns `null`. `metrics.tookMs` is suppressed
 * because it's redundant with `totalMs`.
 */
function renderTracePresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const raw = ctx.data?.("render/trace");
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
    const phases: Array<{ name: string; startMs: number; durationMs: number }> =
        [];
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
        phases.push({ name: ph.name, startMs, durationMs: ph.durationMs });
    }
    const rawMetrics =
        payload.metrics && typeof payload.metrics === "object"
            ? (payload.metrics as Record<string, unknown>)
            : {};
    const metricsEntries: Array<[string, string | number]> = [];
    for (const key of Object.keys(rawMetrics).sort()) {
        if (key === "tookMs") continue; // redundant with totalMs
        const value = rawMetrics[key];
        if (typeof value === "string" || typeof value === "number") {
            metricsEntries.push([key, value]);
        }
    }

    if (phases.length === 0 && metricsEntries.length === 0) return null;

    const body = document.createElement("div");
    body.className = "focus-report-render-trace";

    if (phases.length > 0) {
        const maxDuration = phases.reduce(
            (acc, p) => (p.durationMs > acc ? p.durationMs : acc),
            0,
        );
        const chartScale = totalMs && totalMs > 0 ? totalMs : maxDuration;
        const bar = document.createElement("div");
        bar.className = "focus-report-render-trace-bar";
        for (const phase of phases) {
            const seg = document.createElement("div");
            seg.className = "focus-report-render-trace-phase";
            const pct =
                chartScale > 0
                    ? Math.max(
                          0,
                          Math.min(100, (phase.durationMs / chartScale) * 100),
                      )
                    : 0;
            seg.style.width = pct + "%";
            seg.style.background = "var(--vscode-progressBar-background)";
            seg.title = `${phase.name}: ${phase.durationMs} ms`;
            const label = document.createElement("span");
            label.className = "focus-report-render-trace-phase-label";
            label.textContent = `${phase.name} · ${phase.durationMs} ms`;
            seg.appendChild(label);
            bar.appendChild(seg);
        }
        body.appendChild(bar);
    }

    if (metricsEntries.length > 0) {
        const dl = document.createElement("dl");
        dl.className = "focus-report-render-trace-metrics";
        for (const [key, value] of metricsEntries) {
            const dt = document.createElement("dt");
            dt.textContent = key;
            const dd = document.createElement("dd");
            dd.textContent = String(value);
            dl.appendChild(dt);
            dl.appendChild(dd);
        }
        body.appendChild(dl);
    }

    const summary = totalMs !== null ? `${totalMs} ms` : undefined;
    return {
        report: {
            title: "Render trace",
            summary,
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
