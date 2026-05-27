// Inspection-bundle data builders — issue #1059.
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
// button. Each kind narrows the column shape to what its payload
// carries and contributes overlay boxes to the merged box-overlay
// painted by `paintBundleBoxes(card, "inspection", …)`.

import {
    buildInspectionTreeTable,
    copyToClipboard,
    type TreeColumn,
    type TreeTableNode,
} from "./inspectionTreeTable";
import type { OverlayBox } from "./components/BoxOverlay";
import { parseBounds as parseCardBounds } from "./cardData";
import { buildSelectorSnippet } from "./uiaSelector";
import {
    computePermissionsBundleData,
    type PermissionsPayload,
    type PermissionRow,
} from "./permissionsBundlePresenter";

// ---- compose/semantics --------------------------------------------------

export interface ComposeSemanticsNode {
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

export interface LayoutInspectorNode {
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

// ---- uia/hierarchy ------------------------------------------------------

export interface UiaHierarchyNode {
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

// ---- Inspection bundle data (overlay + tree-table elements) -------------
//
// Parallel surface to `computeA11yBundleData` / `computeHistoryDiffBundleData`
// — used by `main.ts`'s `refreshInspectionBundle` to paint a per-card
// box-overlay layer via `paintBundleBoxes(card, "inspection", data.overlay)`.
// Node ids are namespaced by kind (`semantics-…`, `layout-…`, `uia-…`)
// so the merge step can dedupe across kinds without colliding ids that
// happen to repeat between e.g. semantics and layout trees.

export interface InspectionKindData {
    /** Per-kind tree-table element, slotted into the bundle tab body. */
    body: HTMLElement;
    /** Per-kind summary line ("3 nodes"). */
    summary: string;
    /** Per-kind overlay boxes (already id-namespaced). */
    overlay: readonly OverlayBox[];
}

export interface InspectionBundleData {
    /** Per-kind sections, in the kind order the bundle registry declares. */
    sections: ReadonlyArray<{
        kind:
            | "compose/semantics"
            | "layout/inspector"
            | "uia/hierarchy"
            | "compose/permissions";
        data: InspectionKindData;
    }>;
    /**
     * Merged + de-duped overlay across the enabled kinds. De-duped by
     * `id` so when two kinds' nodes share a stable id (rare in practice
     * — ids are kind-namespaced by default — but possible if a daemon
     * starts emitting cross-kind correlated ids), only the first box
     * is kept. Empty when no kind is enabled or every kind's payload
     * is missing / malformed.
     */
    overlay: readonly OverlayBox[];
    /**
     * Lookup from the namespaced row id (same string used as
     * `data-legend-id` on the tree-table row and as `data-overlay-id`
     * on the matching overlay box) back to the originating per-kind
     * node record. Used by the host's row-click handler to surface a
     * detail panel via `buildInspectionRowDetail` without having to
     * re-walk the per-kind payload trees.
     */
    nodeById: ReadonlyMap<string, InspectionNodeRecord>;
}

/**
 * Discriminated record produced by each per-kind data builder so the
 * detail panel can dispatch by `kind` without knowing the per-kind
 * node shape.
 */
export type InspectionNodeRecord =
    | { kind: "compose/semantics"; node: ComposeSemanticsNode }
    | { kind: "layout/inspector"; node: LayoutInspectorNode }
    | { kind: "uia/hierarchy"; node: UiaHierarchyNode };

export type InspectionKind =
    | "compose/semantics"
    | "layout/inspector"
    | "uia/hierarchy"
    | "compose/permissions";

export interface InspectionPayloadLookup {
    (kind: InspectionKind): unknown;
}

/**
 * Compute the inspection bundle's tab body sections + merged overlay
 * for the focused card. [getPayload] returns the latest cached payload
 * for each kind (or `undefined` when nothing is cached). [enabledKinds]
 * is the user's current per-bundle enabled-kinds set — disabled kinds
 * contribute nothing to either the body or the overlay.
 *
 * Each section is independent: a malformed payload for one kind doesn't
 * suppress the others. Malformed `boundsInScreen` / `boundsInRoot`
 * inside a kind's payload skips that node from the overlay but still
 * emits the tree-table row (the user can still read it).
 */
export function computeInspectionBundleData(
    getPayload: InspectionPayloadLookup,
    enabledKinds: ReadonlySet<InspectionKind>,
): InspectionBundleData {
    const sections: Array<{
        kind: InspectionKind;
        data: InspectionKindData;
    }> = [];

    const nodeById = new Map<string, InspectionNodeRecord>();
    if (enabledKinds.has("compose/semantics")) {
        const payload = getPayload("compose/semantics") as
            | ComposeSemanticsPayload
            | undefined;
        const data = computeComposeSemanticsBundleData(payload, nodeById);
        if (data) sections.push({ kind: "compose/semantics", data });
    }
    if (enabledKinds.has("layout/inspector")) {
        const payload = getPayload("layout/inspector") as
            | LayoutInspectorPayload
            | undefined;
        const data = computeLayoutInspectorBundleData(payload, nodeById);
        if (data) sections.push({ kind: "layout/inspector", data });
    }
    if (enabledKinds.has("uia/hierarchy")) {
        const payload = getPayload("uia/hierarchy") as
            | UiaHierarchyPayload
            | undefined;
        const data = computeUiaHierarchyBundleData(payload, nodeById);
        if (data) sections.push({ kind: "uia/hierarchy", data });
    }
    if (enabledKinds.has("compose/permissions")) {
        const payload = getPayload("compose/permissions") as
            | PermissionsPayload
            | undefined;
        const data = computePermissionsInspectionData(payload);
        if (data) sections.push({ kind: "compose/permissions", data });
    }

    const overlay = mergeOverlayBoxes(sections.map((s) => s.data.overlay));

    return { sections, overlay, nodeById };
}

/** Dedupe overlay boxes by `id`, preserving first-seen order. */
function mergeOverlayBoxes(
    groups: ReadonlyArray<readonly OverlayBox[]>,
): readonly OverlayBox[] {
    const seen = new Set<string>();
    const out: OverlayBox[] = [];
    for (const group of groups) {
        for (const box of group) {
            if (seen.has(box.id)) continue;
            seen.add(box.id);
            out.push(box);
        }
    }
    return out;
}

function computeComposeSemanticsBundleData(
    payload: ComposeSemanticsPayload | undefined,
    nodeById: Map<string, InspectionNodeRecord>,
): InspectionKindData | null {
    if (!payload || !payload.root) return null;
    const flat = flattenSemantics(payload.root);
    const overlay: OverlayBox[] = [];
    for (const entry of flat) {
        const id = nsId("semantics", entry.id);
        nodeById.set(id, { kind: "compose/semantics", node: entry.node });
        const bounds = parseCardBounds(entry.node.boundsInRoot);
        if (!bounds) continue;
        // `mergeDescendants` nodes absorb their children's semantics into
        // the parent, so the box stands in for multiple nodes' worth of
        // a11y data — worth visually distinguishing on the overlay so a
        // reviewer can spot where the merge boundary sits. (`clearAndSet`
        // stays on `info`: it drops descendants rather than absorbing
        // them, and is rare enough that surfacing it competes with the
        // more useful merge-boundary signal.)
        overlay.push({
            id,
            bounds,
            level:
                entry.node.mergeMode === "mergeDescendants"
                    ? "warning"
                    : "info",
            tooltip: semanticsTooltip(entry.node),
        });
    }
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
    const rows = [toTreeNode(payload.root, (n) => nsId("semantics", n.nodeId))];
    const summary = flat.length + " node" + (flat.length === 1 ? "" : "s");
    const body = buildInspectionTreeTable<ComposeSemanticsNode>({
        title: "Compose semantics",
        summary,
        columns,
        rows,
        hasOverlayFor: (n) => parseCardBounds(n.boundsInRoot) !== null,
        jsonForCopy: () => payload,
    });
    return { body, summary, overlay };
}

function computeLayoutInspectorBundleData(
    payload: LayoutInspectorPayload | undefined,
    nodeById: Map<string, InspectionNodeRecord>,
): InspectionKindData | null {
    if (!payload || !payload.root) return null;
    const flat = flattenLayout(payload.root);
    const overlay: OverlayBox[] = [];
    for (const entry of flat) {
        const id = nsId("layout", entry.id);
        nodeById.set(id, { kind: "layout/inspector", node: entry.node });
        const b = entry.node.bounds;
        if (
            !b ||
            !Number.isFinite(b.left) ||
            !Number.isFinite(b.top) ||
            !Number.isFinite(b.right) ||
            !Number.isFinite(b.bottom)
        ) {
            continue;
        }
        overlay.push({
            id,
            bounds: {
                left: b.left,
                top: b.top,
                right: b.right,
                bottom: b.bottom,
            },
            level: "info",
            tooltip: layoutTooltip(entry.node),
        });
    }
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
    ];
    const rows = [toTreeNode(payload.root, (n) => nsId("layout", n.nodeId))];
    const summary = flat.length + " node" + (flat.length === 1 ? "" : "s");
    const body = buildInspectionTreeTable<LayoutInspectorNode>({
        title: "Layout inspector",
        summary,
        columns,
        rows,
        hasOverlayFor: () => true,
        jsonForCopy: () => payload,
    });
    return { body, summary, overlay };
}

function computeUiaHierarchyBundleData(
    payload: UiaHierarchyPayload | undefined,
    nodeById: Map<string, InspectionNodeRecord>,
): InspectionKindData | null {
    if (!payload || !Array.isArray(payload.nodes)) return null;
    const nodes = payload.nodes;
    const overlay: OverlayBox[] = [];
    const rows: TreeTableNode<UiaHierarchyNode>[] = nodes.map((n, idx) => ({
        id: nsId("uia", idx + ""),
        data: n,
    }));
    for (let i = 0; i < nodes.length; i++) {
        const id = nsId("uia", i + "");
        nodeById.set(id, { kind: "uia/hierarchy", node: nodes[i] });
        const bounds = parseCardBounds(nodes[i].boundsInScreen);
        if (!bounds) continue;
        overlay.push({
            id,
            bounds,
            level: "info",
            tooltip: uiaTooltip(nodes[i]),
        });
    }
    const columns: TreeColumn<UiaHierarchyNode>[] = [
        { id: "text", label: "Text", render: (n) => n.text ?? "—" },
        {
            id: "contentDescription",
            label: "Description",
            render: (n) => n.contentDescription ?? "—",
        },
        { id: "testTag", label: "Tag", render: (n) => n.testTag ?? "—" },
        { id: "role", label: "Role", render: (n) => n.role ?? "—" },
    ];
    const summary =
        nodes.length === 0
            ? "No actionable nodes"
            : nodes.length + " node" + (nodes.length === 1 ? "" : "s");
    const body = buildInspectionTreeTable<UiaHierarchyNode>({
        title: "UI Automator hierarchy",
        summary,
        columns,
        rows,
        hasOverlayFor: (n) => parseCardBounds(n.boundsInScreen) !== null,
        jsonForCopy: () => payload,
        rowAction: {
            icon: "copy",
            label: "Selector",
            title: "Copy as By.testTag(...) selector",
            onClick: (n) => {
                const snippet = buildSelectorSnippet(n, nodes);
                if (snippet) void copyToClipboard(snippet);
            },
        },
    });
    return { body, summary, overlay };
}

/**
 * `compose/permissions` doesn't fit the tree-table abstraction the other three
 * kinds share (no hierarchy, no overlay bounds), so we render two flat rows
 * tables — grants and queried — under one `<section>`. The body sits in the
 * inspection bundle's host alongside the tree-tables; the bundle's merged
 * overlay stays empty for this kind.
 *
 * Each table row carries Grant / Deny / Clear buttons that bubble a
 * `permissions-override-change` CustomEvent ({@link PermissionsChangeDetail})
 * up to the inspection bundle body wrapper; `main.ts` catches it and posts
 * `setPermissionsOverride` so the host can push a fresh
 * `renderNow.overrides.permissions`. The section header also carries an
 * "Add permission" form for pinning arbitrary `Manifest.permission.*` names
 * even when the screen hasn't queried them, plus a "Clear overrides" action.
 */
function computePermissionsInspectionData(
    payload: PermissionsPayload | undefined,
): InspectionKindData | null {
    if (!payload) return null;
    const data = computePermissionsBundleData(payload);
    const summary =
        data.allPermissions.length +
        " permission" +
        (data.allPermissions.length === 1 ? "" : "s");
    const body = document.createElement("section");
    body.className = "inspection-permissions-section";
    const header = document.createElement("header");
    header.className = "inspection-permissions-header";
    const headerTitle = document.createElement("span");
    headerTitle.className = "inspection-permissions-title";
    headerTitle.textContent = "Permissions · " + summary;
    header.appendChild(headerTitle);
    header.appendChild(buildClearOverridesButton());
    body.appendChild(header);
    body.appendChild(buildAddPermissionForm());
    if (data.grantRows.length > 0) {
        body.appendChild(
            buildPermissionsTable("Effective grants", data.grantRows, [
                "Permission",
                "Grant",
                "Queried?",
                "Actions",
            ]),
        );
    }
    if (data.queriedRows.length > 0) {
        body.appendChild(
            buildPermissionsTable("Queried", data.queriedRows, [
                "Permission",
                "Effective grant",
                "Actions",
            ]),
        );
    }
    return { body, summary, overlay: [] };
}

function buildPermissionsTable(
    title: string,
    rows: readonly PermissionRow[],
    headers: readonly string[],
): HTMLElement {
    const wrap = document.createElement("section");
    wrap.className = "inspection-permissions-table";
    wrap.dataset.permissionTable =
        title === "Effective grants" ? "grants" : "queried";
    const heading = document.createElement("h4");
    heading.textContent = title;
    wrap.appendChild(heading);
    const table = document.createElement("table");
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    for (const h of headers) {
        const th = document.createElement("th");
        th.textContent = h;
        headerRow.appendChild(th);
    }
    thead.appendChild(headerRow);
    table.appendChild(thead);
    const tbody = document.createElement("tbody");
    const isGrantsTable = headers.length === 4;
    for (const row of rows) {
        const tr = document.createElement("tr");
        tr.dataset.permissionLevel = row.level;
        tr.dataset.permissionName = row.permission;
        const permCell = document.createElement("td");
        const code = document.createElement("code");
        code.textContent = row.shortLabel;
        permCell.appendChild(code);
        tr.appendChild(permCell);
        const grantCell = document.createElement("td");
        grantCell.textContent = row.grant ?? (isGrantsTable ? "—" : "unknown");
        tr.appendChild(grantCell);
        if (isGrantsTable) {
            const queriedCell = document.createElement("td");
            queriedCell.textContent = row.queried ? "yes" : "no";
            tr.appendChild(queriedCell);
        }
        tr.appendChild(buildRowActionsCell(row, isGrantsTable));
        tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
}

/**
 * Build the per-row override-action cell — Grant / Deny buttons, plus a Clear
 * button on grant-table rows (queried-only rows have nothing to clear). The
 * currently-applied grant's button is `aria-pressed` so the panel reads as a
 * toggle, not a one-shot.
 */
function buildRowActionsCell(
    row: PermissionRow,
    isGrantsTable: boolean,
): HTMLElement {
    const cell = document.createElement("td");
    cell.className = "inspection-permissions-actions-cell";
    cell.appendChild(
        buildGrantButton(row.permission, "granted", row.grant === "granted"),
    );
    cell.appendChild(
        buildGrantButton(row.permission, "denied", row.grant === "denied"),
    );
    if (isGrantsTable) {
        const clearBtn = document.createElement("button");
        clearBtn.type = "button";
        clearBtn.className = "inspection-permissions-action";
        clearBtn.dataset.permissionAction = "clear";
        clearBtn.dataset.permissionName = row.permission;
        clearBtn.textContent = "Clear";
        clearBtn.title = "Drop this permission's panel-pinned override";
        clearBtn.addEventListener("click", () => {
            emitPermissionsChange(clearBtn, {
                field: "clearGrant",
                permission: row.permission,
            });
        });
        cell.appendChild(clearBtn);
    }
    return cell;
}

function buildGrantButton(
    permission: string,
    grant: "granted" | "denied",
    isCurrent: boolean,
): HTMLButtonElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "inspection-permissions-action";
    btn.dataset.permissionAction = grant === "granted" ? "grant" : "deny";
    btn.dataset.permissionName = permission;
    btn.textContent = grant === "granted" ? "Grant" : "Deny";
    btn.title =
        grant === "granted"
            ? "Pin this permission as granted for the next render"
            : "Pin this permission as denied for the next render";
    if (isCurrent) {
        btn.setAttribute("aria-pressed", "true");
        btn.classList.add("inspection-permissions-action--current");
    }
    btn.addEventListener("click", () => {
        emitPermissionsChange(btn, {
            field: "setGrant",
            permission,
            grant,
        });
    });
    return btn;
}

function buildAddPermissionForm(): HTMLElement {
    const form = document.createElement("form");
    form.className = "inspection-permissions-add";
    form.dataset.permissionForm = "add";
    const input = document.createElement("input");
    input.type = "text";
    input.className = "inspection-permissions-add-input";
    input.placeholder = "android.permission.CAMERA";
    input.setAttribute(
        "aria-label",
        "Permission name to pin (e.g. android.permission.CAMERA)",
    );
    form.appendChild(input);
    const grantBtn = document.createElement("button");
    grantBtn.type = "submit";
    grantBtn.className = "inspection-permissions-action";
    grantBtn.dataset.permissionAction = "add-grant";
    grantBtn.textContent = "Grant";
    grantBtn.value = "granted";
    form.appendChild(grantBtn);
    const denyBtn = document.createElement("button");
    denyBtn.type = "submit";
    denyBtn.className = "inspection-permissions-action";
    denyBtn.dataset.permissionAction = "add-deny";
    denyBtn.textContent = "Deny";
    denyBtn.value = "denied";
    form.appendChild(denyBtn);
    let pendingGrant: "granted" | "denied" = "granted";
    grantBtn.addEventListener("click", () => {
        pendingGrant = "granted";
    });
    denyBtn.addEventListener("click", () => {
        pendingGrant = "denied";
    });
    form.addEventListener("submit", (evt) => {
        evt.preventDefault();
        const raw = input.value.trim();
        if (!raw) return;
        const permission = normalisePermissionInput(raw);
        emitPermissionsChange(form, {
            field: "setGrant",
            permission,
            grant: pendingGrant,
        });
        input.value = "";
    });
    return form;
}

function buildClearOverridesButton(): HTMLButtonElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className =
        "inspection-permissions-action inspection-permissions-clear-all";
    btn.dataset.permissionAction = "clear-all";
    btn.textContent = "Clear overrides";
    btn.title =
        "Drop every panel-pinned permission override (the next render falls" +
        " back to the manifest baseline).";
    btn.addEventListener("click", () => {
        emitPermissionsChange(btn, { field: "clearAll" });
    });
    return btn;
}

/**
 * `CAMERA` → `android.permission.CAMERA` so the user can type the short
 * label they see in the table without remembering the full prefix; an already-
 * qualified input passes through unchanged. Whitespace-only input is caught by
 * the form's submit handler before reaching this helper.
 */
function normalisePermissionInput(raw: string): string {
    if (raw.includes(".")) return raw;
    return "android.permission." + raw.toUpperCase();
}

function emitPermissionsChange(
    source: HTMLElement,
    detail: PermissionsChangeDetail,
): void {
    const evt = new CustomEvent<PermissionsChangeDetail>(
        "permissions-override-change",
        { detail, bubbles: true, composed: true },
    );
    source.dispatchEvent(evt);
}

/**
 * Detail payload of the `permissions-override-change` CustomEvent the
 * permissions section dispatches up to the inspection bundle body wrapper.
 * Re-typed here (rather than imported from `../types`) because this module
 * is the only webview-side producer and we don't want to drag the full host
 * `WebviewToExtension` union into the presenter's type graph. `main.ts`
 * forwards it verbatim as the `change` field of `setPermissionsOverride`.
 */
export type PermissionsChangeDetail =
    | { field: "setGrant"; permission: string; grant: "granted" | "denied" }
    | { field: "clearGrant"; permission: string }
    | { field: "clearAll" };

/** Namespace a node id with its kind so cross-kind dedupe stays clean. */
function nsId(prefix: string, id: string): string {
    return prefix + "-" + id;
}

function semanticsTooltip(n: ComposeSemanticsNode): string {
    const parts: string[] = [];
    if (n.label) parts.push(n.label);
    if (n.role) parts.push(n.role);
    if (n.testTag) parts.push(n.testTag);
    return parts.join(" · ");
}

function layoutTooltip(n: LayoutInspectorNode): string {
    const parts: string[] = [n.component];
    if (n.size) parts.push(n.size.width + "×" + n.size.height);
    if (n.source) parts.push(n.source);
    return parts.join(" · ");
}

function uiaTooltip(n: UiaHierarchyNode): string {
    const parts: string[] = [];
    if (n.text) parts.push(n.text);
    if (n.role) parts.push(n.role);
    if (n.testTag) parts.push(n.testTag);
    return parts.join(" · ");
}
