// Presenters for the Inspection-bundle data kinds — issue #1059.
//
//   - `compose/semantics`   — cheap SemanticsNode projection (testTag,
//                             role, mergeMode, bounds, clickable).
//   - `layout/inspector`    — full layout hierarchy with bounds,
//                             constraints, modifiers, source refs.
//   - `uia/hierarchy`       — UI Automator-shaped semantics nodes with
//                             supported-action chips and a row-action
//                             "Copy as selector".
//
// All three share the `buildInspectionTreeTable` primitive: a tree view
// with collapsible rows, hover → overlay correlation via
// `data-legend-id` / `data-overlay-id`, and a header "Copy JSON"
// button. Each presenter narrows the column shape to what its payload
// carries and paints its own overlay layer of `data-overlay-id` boxes
// for the focus inspector to slot into the card's overlay stack.
//
// Side-effect import: the presenter functions register themselves via
// `registerPresenter` at module load. `focusPresentation.ts` imports
// this module from `seedBuiltInPresenters` so the registrations land
// alongside the existing built-ins.

import {
    buildInspectionTreeTable,
    stringify,
    type TreeColumn,
    type TreeTableNode,
} from "./inspectionTreeTable";
import {
    registerPresenter,
    type PresenterContext,
    type ProductPresentation,
} from "./focusPresentation";
import { getVsCodeApi } from "../shared/vscode";
import { buildSelectorSnippet } from "./uiaSelector";

// ---- compose/semantics --------------------------------------------------

interface ComposeSemanticsNode {
    nodeId: string;
    boundsInRoot: string;
    label?: string | null;
    text?: string | null;
    role?: string | null;
    testTag?: string | null;
    mergeMode?: string | null;
    clickable?: boolean;
    children?: ComposeSemanticsNode[];
}

interface ComposeSemanticsPayload {
    root: ComposeSemanticsNode;
}

function composeSemanticsPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const payload = ctx.data?.("compose/semantics") as
        | ComposeSemanticsPayload
        | undefined;
    if (!payload || !payload.root) return null;

    const flat = flattenSemantics(payload.root);
    const overlay = buildBoundsOverlay(
        flat.map((entry) => ({
            id: entry.id,
            bounds: parseBounds(entry.node.boundsInRoot),
            level: entry.node.mergeMode === "Merged" ? "warning" : "info",
        })),
    );

    const columns: TreeColumn<ComposeSemanticsNode>[] = [
        {
            id: "label",
            label: "Label",
            render: (n) => n.label ?? n.text ?? "—",
        },
        { id: "role", label: "Role", render: (n) => n.role ?? "—" },
        { id: "testTag", label: "Tag", render: (n) => n.testTag ?? "—" },
        { id: "mergeMode", label: "Merge", render: (n) => n.mergeMode ?? "—" },
        {
            id: "clickable",
            label: "Clickable",
            render: (n) => (n.clickable ? "✓" : ""),
        },
    ];

    const rows = [toTreeNode(payload.root, (n) => n.nodeId)];

    const body = buildInspectionTreeTable<ComposeSemanticsNode>({
        title: "Compose semantics",
        summary: flat.length + " node" + (flat.length === 1 ? "" : "s"),
        columns,
        rows,
        hasOverlayFor: (n) => parseBounds(n.boundsInRoot) !== null,
        jsonForCopy: () => payload,
    });

    return {
        overlay: overlay.boxCount > 0 ? overlay.el : null,
        report: {
            title: "Compose semantics",
            summary: flat.length + " node" + (flat.length === 1 ? "" : "s"),
            body,
        },
    };
}

// ---- layout/inspector ---------------------------------------------------

interface LayoutInspectorBounds {
    left: number;
    top: number;
    right: number;
    bottom: number;
}

interface LayoutInspectorModifier {
    name: string;
    value?: string | null;
    properties?: Record<string, string>;
}

interface LayoutInspectorNode {
    nodeId: string;
    component: string;
    source?: string | null;
    sourceInfo?: string | null;
    bounds: LayoutInspectorBounds;
    size?: { width: number; height: number };
    constraints?: {
        minWidth: number;
        maxWidth?: number | null;
        minHeight: number;
        maxHeight?: number | null;
    } | null;
    modifiers?: LayoutInspectorModifier[];
    children?: LayoutInspectorNode[];
}

interface LayoutInspectorPayload {
    root: LayoutInspectorNode;
}

function layoutInspectorPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const payload = ctx.data?.("layout/inspector") as
        | LayoutInspectorPayload
        | undefined;
    if (!payload || !payload.root) return null;

    const flat = flattenLayout(payload.root);
    const overlay = buildBoundsOverlay(
        flat.map((entry) => ({
            id: entry.id,
            bounds: entry.node.bounds,
            level: "info",
        })),
    );

    const className = ctx.preview.className;

    const columns: TreeColumn<LayoutInspectorNode>[] = [
        { id: "component", label: "Component", render: (n) => n.component },
        {
            id: "size",
            label: "Size",
            render: (n) => (n.size ? `${n.size.width}×${n.size.height}` : "—"),
        },
        {
            id: "modifiers",
            label: "Modifiers",
            render: (n) => renderModifiers(n.modifiers ?? []),
        },
        {
            id: "source",
            label: "Source",
            render: (n) => renderSourceLink(n.source, className),
        },
    ];

    const rows = [toTreeNode(payload.root, (n) => n.nodeId)];

    const body = buildInspectionTreeTable<LayoutInspectorNode>({
        title: "Layout inspector",
        summary: flat.length + " node" + (flat.length === 1 ? "" : "s"),
        columns,
        rows,
        hasOverlayFor: () => true,
        jsonForCopy: () => payload,
    });

    return {
        overlay: overlay.boxCount > 0 ? overlay.el : null,
        report: {
            title: "Layout inspector",
            summary: flat.length + " node" + (flat.length === 1 ? "" : "s"),
            body,
        },
    };
}

// ---- uia/hierarchy ------------------------------------------------------

interface UiaHierarchyNode {
    text?: string | null;
    contentDescription?: string | null;
    testTag?: string | null;
    testTagAncestors?: string[];
    role?: string | null;
    actions?: string[];
    boundsInScreen: string;
    merged?: boolean;
}

interface UiaHierarchyPayload {
    nodes: UiaHierarchyNode[];
}

function uiaHierarchyPresenter(
    ctx: PresenterContext,
): ProductPresentation | null {
    const payload = ctx.data?.("uia/hierarchy") as
        | UiaHierarchyPayload
        | undefined;
    if (!payload || !Array.isArray(payload.nodes)) return null;

    const nodes = payload.nodes;
    if (nodes.length === 0) {
        const empty = document.createElement("div");
        empty.className = "focus-report-empty";
        empty.textContent =
            "No actionable semantics nodes in this preview. The UI Automator " +
            "hierarchy filters out pure-layout / non-interactive nodes by default.";
        return {
            report: {
                title: "UI Automator hierarchy",
                summary: "No actionable nodes",
                body: empty,
            },
        };
    }

    // The wire payload is flat — no children — so each node lands as a
    // top-level row. Build an id from the array index so legend ↔
    // overlay correlation has something stable; the payload itself
    // doesn't carry one.
    const rows: TreeTableNode<UiaHierarchyNode>[] = nodes.map((n, idx) => ({
        id: "uia-" + idx,
        data: n,
    }));

    const overlay = buildBoundsOverlay(
        rows.map((r) => ({
            id: r.id,
            bounds: parseBounds(r.data.boundsInScreen),
            level: "info",
        })),
    );

    const columns: TreeColumn<UiaHierarchyNode>[] = [
        { id: "text", label: "Text", render: (n) => n.text ?? "—" },
        {
            id: "contentDescription",
            label: "Description",
            render: (n) => n.contentDescription ?? "—",
        },
        { id: "testTag", label: "Tag", render: (n) => n.testTag ?? "—" },
        { id: "role", label: "Role", render: (n) => n.role ?? "—" },
        {
            id: "actions",
            label: "Actions",
            render: (n) => renderActionChips(n.actions ?? []),
        },
    ];

    const body = buildInspectionTreeTable<UiaHierarchyNode>({
        title: "UI Automator hierarchy",
        summary: nodes.length + " node" + (nodes.length === 1 ? "" : "s"),
        columns,
        rows,
        hasOverlayFor: (n) => parseBounds(n.boundsInScreen) !== null,
        jsonForCopy: () => payload,
        rowAction: {
            icon: "copy",
            label: "Selector",
            title: "Copy as By.testTag(...) selector",
            onClick: (n) => {
                const snippet = buildSelectorSnippet(n, nodes);
                if (snippet) {
                    void writeClipboard(snippet);
                }
            },
        },
    });

    return {
        overlay: overlay.boxCount > 0 ? overlay.el : null,
        report: {
            title: "UI Automator hierarchy",
            summary: nodes.length + " node" + (nodes.length === 1 ? "" : "s"),
            body,
        },
    };
}

// ---- registration -------------------------------------------------------

/**
 * Register the three Inspection-bundle presenters. Called by
 * `focusPresentation.seedBuiltInPresenters` once the registry is ready
 * (and again by `_seedBuiltInPresentersForTest`), so we don't fire on
 * module load — that path would hit the circular import in TDZ before
 * the registry `const` is initialised.
 */
export function registerInspectionPresenters(): void {
    registerPresenter("compose/semantics", composeSemanticsPresenter);
    registerPresenter("layout/inspector", layoutInspectorPresenter);
    registerPresenter("uia/hierarchy", uiaHierarchyPresenter);
}

// ---- helpers ------------------------------------------------------------

function toTreeNode<T extends { children?: T[] }>(
    node: T,
    idOf: (n: T) => string,
): TreeTableNode<T> {
    return {
        id: idOf(node),
        data: node,
        children: (node.children ?? []).map((c) => toTreeNode(c, idOf)),
    };
}

interface FlatEntry<T> {
    id: string;
    node: T;
}

function flattenSemantics(
    root: ComposeSemanticsNode,
    out: FlatEntry<ComposeSemanticsNode>[] = [],
): FlatEntry<ComposeSemanticsNode>[] {
    out.push({ id: root.nodeId, node: root });
    for (const child of root.children ?? []) {
        flattenSemantics(child, out);
    }
    return out;
}

function flattenLayout(
    root: LayoutInspectorNode,
    out: FlatEntry<LayoutInspectorNode>[] = [],
): FlatEntry<LayoutInspectorNode>[] {
    out.push({ id: root.nodeId, node: root });
    for (const child of root.children ?? []) {
        flattenLayout(child, out);
    }
    return out;
}

interface OverlayEntry {
    id: string;
    bounds:
        | {
              left: number;
              top: number;
              right: number;
              bottom: number;
          }
        | null
        | undefined;
    level: "info" | "warning";
}

function buildBoundsOverlay(entries: readonly OverlayEntry[]): {
    el: HTMLElement;
    boxCount: number;
} {
    const el = document.createElement("div");
    el.className = "focus-presentation-overlay focus-overlay-inspection";
    let boxCount = 0;
    for (const entry of entries) {
        if (!entry.bounds) continue;
        const box = document.createElement("div");
        box.className = "focus-overlay-box";
        box.dataset.overlayId = entry.id;
        box.dataset.level = entry.level;
        box.style.left = entry.bounds.left + "px";
        box.style.top = entry.bounds.top + "px";
        box.style.width = entry.bounds.right - entry.bounds.left + "px";
        box.style.height = entry.bounds.bottom - entry.bounds.top + "px";
        el.appendChild(box);
        boxCount += 1;
    }
    return { el, boxCount };
}

function parseBounds(
    s: string | null | undefined,
): { left: number; top: number; right: number; bottom: number } | null {
    if (!s) return null;
    const parts = s.split(",").map((x) => parseInt(x.trim(), 10));
    if (parts.length !== 4 || parts.some(Number.isNaN)) return null;
    return {
        left: parts[0],
        top: parts[1],
        right: parts[2],
        bottom: parts[3],
    };
}

function renderModifiers(
    mods: readonly LayoutInspectorModifier[],
): HTMLElement {
    const span = document.createElement("span");
    span.className = "inspection-modifiers";
    if (mods.length === 0) {
        span.textContent = "—";
        return span;
    }
    // Show the first three names inline; if there are more, append a
    // `+N` chip whose `title` is the full list. Truncation here is the
    // norm — the daemon's layout/inspector typically emits 5–20
    // modifiers per node and the row would otherwise blow out the
    // column width.
    const head = mods
        .slice(0, 3)
        .map((m) => m.name)
        .join(" · ");
    span.textContent = head;
    if (mods.length > 3) {
        const more = document.createElement("span");
        more.className = "inspection-modifiers-more";
        more.textContent = " +" + (mods.length - 3);
        more.title = mods.map((m) => formatModifier(m)).join("\n");
        span.appendChild(more);
    }
    return span;
}

function formatModifier(m: LayoutInspectorModifier): string {
    if (m.value) return `${m.name}(${m.value})`;
    const props = m.properties ?? {};
    const keys = Object.keys(props);
    if (keys.length === 0) return m.name;
    return `${m.name}(${keys.map((k) => `${k}=${props[k]}`).join(", ")})`;
}

function renderSourceLink(
    source: string | null | undefined,
    className: string,
): HTMLElement {
    const span = document.createElement("span");
    if (!source) {
        span.textContent = "—";
        return span;
    }
    const { file, line } = splitSource(source);
    if (!file) {
        span.textContent = source;
        return span;
    }
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "inspection-source-link";
    btn.title = `Open ${source}`;
    btn.textContent = source;
    btn.addEventListener("click", (ev) => {
        ev.stopPropagation();
        try {
            const api = getVsCodeApi();
            api.postMessage({
                command: "openSourceFile",
                fileName: file,
                line,
                className,
            });
        } catch {
            // No vscode handle (test env) — silently do nothing. The
            // button still focuses, which is enough for keyboard
            // testing without DOM-mocking the postMessage path.
        }
    });
    span.appendChild(btn);
    return span;
}

function splitSource(source: string): { file: string | null; line: number } {
    // Layout-inspector source is "Foo.kt:42". `file` is the basename
    // (matches the stack-trace fileName the existing `openSourceFile`
    // handler expects); line is 1-based.
    const idx = source.lastIndexOf(":");
    if (idx < 0) return { file: source, line: 0 };
    const file = source.slice(0, idx);
    const line = parseInt(source.slice(idx + 1), 10);
    if (Number.isNaN(line)) return { file: source, line: 0 };
    return { file, line };
}

function renderActionChips(actions: readonly string[]): HTMLElement {
    const span = document.createElement("span");
    span.className = "inspection-action-chips";
    if (actions.length === 0) {
        span.textContent = "—";
        return span;
    }
    for (const a of actions) {
        const chip = document.createElement("span");
        chip.className = "inspection-action-chip";
        chip.dataset.action = a;
        chip.textContent = a;
        span.appendChild(chip);
    }
    return span;
}

async function writeClipboard(text: string): Promise<void> {
    try {
        const nav = (globalThis as { navigator?: Navigator }).navigator;
        if (nav?.clipboard?.writeText) {
            await nav.clipboard.writeText(text);
            return;
        }
    } catch {
        // fall through
    }
    try {
        const ta = document.createElement("textarea");
        ta.value = text;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
    } catch {
        // give up
    }
}

// `stringify` is re-exported so external callers (e.g. integration
// tests) can format the same way the Copy JSON button does without
// pulling in `JSON.stringify` defaults.
export { stringify };
