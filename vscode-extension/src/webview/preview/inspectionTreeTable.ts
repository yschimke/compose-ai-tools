// Tree-table primitive used by the Inspection-bundle presenters
// (`compose/semantics`, `layout/inspector`, `uia/hierarchy`).
//
// Each kind in the bundle is hierarchical (a semantics tree, a layout
// tree, a filtered semantics-actionable subtree), and the differences
// across kinds are only in the column shape: presenter declares the
// columns, the primitive owns the rest — row expand/collapse, header
// "Copy JSON" button, hover → overlay correlation via `data-legend-id`
// (matched against `data-overlay-id` boxes by `LegendArrowController`,
// the same wiring the a11y-hierarchy presenter uses), and the optional
// per-row action chip (the `uia/hierarchy` presenter wires
// "Copy selector" through it).
//
// Light DOM only — the inspector's CSS targets descendants by class.

export interface TreeColumn<T> {
    /** Stable id; lands on the column header / cell as `data-col`. */
    id: string;
    /** Header text. */
    label: string;
    /**
     * Render the cell body. Returning a string emits a plain text node;
     * returning an element gives the presenter full control (icons,
     * truncation tooltips, the layout-inspector source-link button).
     */
    render(node: T): HTMLElement | string;
    /** Optional extra class on the cell `<td>`. */
    className?: string;
}

export interface TreeTableNode<T> {
    /**
     * Stable id used as `data-legend-id` on the row and as the
     * matching `data-overlay-id` on overlay boxes the presenter
     * paints separately. Must be unique within a tree.
     */
    id: string;
    data: T;
    children?: readonly TreeTableNode<T>[];
}

export interface TreeRowAction<T> {
    icon: string;
    label: string;
    title?: string;
    onClick(node: T, row: HTMLElement): void;
}

export interface TreeTableOptions<T> {
    /** Block header, shown in the Report `<details>` summary by the inspector. */
    title: string;
    /** Optional one-liner shown next to the title. */
    summary?: string;
    columns: readonly TreeColumn<T>[];
    /** Top-level rows. Each row recurses through `children`. */
    rows: readonly TreeTableNode<T>[];
    /**
     * Payload to copy when the user clicks the header "Copy JSON" button.
     * Returns the value to stringify; called lazily on click so the
     * presenter can avoid eager serialisation on every render.
     */
    jsonForCopy?: () => unknown;
    /**
     * When defined, rows whose data has bounds shown as
     * `data-has-overlay="true"` so CSS can paint an overlay handle. The
     * actual overlay boxes are produced by the presenter (`overlay`
     * surface in `ProductPresentation`) — this is just the visual cue.
     */
    hasOverlayFor?(node: T): boolean;
    /**
     * Optional per-row action — emitted as a button at the end of the
     * row. Used by `uia/hierarchy` for "Copy as selector".
     */
    rowAction?: TreeRowAction<T>;
    /** Default-expand to this depth. `-1` (default) means all open. */
    defaultExpandDepth?: number;
}

/**
 * Build the tree-table DOM. The returned element is meant to be slotted
 * into a `PresentationReport.body`; presenters wrap it in their own
 * `<details>` via the inspector's report surface.
 */
export function buildInspectionTreeTable<T>(
    opts: TreeTableOptions<T>,
): HTMLElement {
    const container = document.createElement("div");
    container.className = "inspection-tree";

    const head = document.createElement("div");
    head.className = "inspection-tree-head";
    if (opts.summary) {
        const sum = document.createElement("span");
        sum.className = "inspection-tree-summary";
        sum.textContent = opts.summary;
        head.appendChild(sum);
    }
    if (opts.jsonForCopy) {
        const copy = document.createElement("button");
        copy.type = "button";
        copy.className = "inspection-tree-copy-json";
        copy.innerHTML =
            '<i class="codicon codicon-json" aria-hidden="true"></i>';
        const lab = document.createElement("span");
        lab.textContent = "Copy JSON";
        copy.appendChild(lab);
        copy.title = "Copy the union of enabled kinds as JSON";
        copy.addEventListener("click", () => {
            const payload = opts.jsonForCopy!();
            void copyToClipboard(stringify(payload));
        });
        head.appendChild(copy);
    }
    container.appendChild(head);

    const table = document.createElement("table");
    table.className = "inspection-tree-table";
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    const expanderHead = document.createElement("th");
    expanderHead.className = "inspection-tree-expander-col";
    expanderHead.setAttribute("aria-hidden", "true");
    headerRow.appendChild(expanderHead);
    for (const col of opts.columns) {
        const th = document.createElement("th");
        th.dataset.col = col.id;
        if (col.className) th.classList.add(col.className);
        th.textContent = col.label;
        headerRow.appendChild(th);
    }
    if (opts.rowAction) {
        const actionTh = document.createElement("th");
        actionTh.className = "inspection-tree-action-col";
        actionTh.setAttribute("aria-hidden", "true");
        headerRow.appendChild(actionTh);
    }
    thead.appendChild(headerRow);
    table.appendChild(thead);

    const tbody = document.createElement("tbody");
    const expandDepth = opts.defaultExpandDepth ?? -1;
    for (const node of opts.rows) {
        appendRow(tbody, node, 0, expandDepth, opts);
    }
    table.appendChild(tbody);
    container.appendChild(table);
    return container;
}

function appendRow<T>(
    tbody: HTMLElement,
    node: TreeTableNode<T>,
    depth: number,
    expandDepth: number,
    opts: TreeTableOptions<T>,
): void {
    const tr = document.createElement("tr");
    tr.dataset.legendId = node.id;
    tr.dataset.depth = String(depth);
    if (opts.hasOverlayFor?.(node.data)) {
        tr.dataset.hasOverlay = "true";
    }
    tr.tabIndex = 0;

    const expanderTd = document.createElement("td");
    expanderTd.className = "inspection-tree-expander";
    const hasChildren = (node.children?.length ?? 0) > 0;
    // Indent based on depth so nested rows visibly nest even though
    // they live as siblings in the flat tbody — same trick the VS Code
    // tree-view uses.
    expanderTd.style.paddingLeft = depth * 16 + "px";
    if (hasChildren) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "inspection-tree-expand";
        btn.setAttribute("aria-expanded", "false");
        const icon = document.createElement("i");
        icon.className = "codicon codicon-chevron-right";
        icon.setAttribute("aria-hidden", "true");
        btn.appendChild(icon);
        expanderTd.appendChild(btn);
        // Click is wired below once we've appended the child rows so
        // we know what to toggle.
    }
    tr.appendChild(expanderTd);

    for (const col of opts.columns) {
        const td = document.createElement("td");
        td.dataset.col = col.id;
        if (col.className) td.classList.add(col.className);
        const content = col.render(node.data);
        if (typeof content === "string") {
            td.textContent = content;
        } else {
            td.appendChild(content);
        }
        tr.appendChild(td);
    }

    if (opts.rowAction) {
        const actTd = document.createElement("td");
        actTd.className = "inspection-tree-action";
        const action = opts.rowAction;
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "inspection-tree-action-btn";
        btn.title = action.title ?? action.label;
        btn.innerHTML =
            '<i class="codicon codicon-' +
            action.icon +
            '" aria-hidden="true"></i>';
        const lab = document.createElement("span");
        lab.textContent = action.label;
        btn.appendChild(lab);
        btn.addEventListener("click", (ev) => {
            ev.stopPropagation();
            action.onClick(node.data, tr);
        });
        actTd.appendChild(btn);
        tr.appendChild(actTd);
    }

    tbody.appendChild(tr);

    if (!hasChildren) {
        return;
    }

    const childRowStart = tbody.children.length;
    for (const child of node.children!) {
        appendRow(tbody, child, depth + 1, expandDepth, opts);
    }
    const childRowEnd = tbody.children.length;
    const childRows: HTMLElement[] = [];
    for (let i = childRowStart; i < childRowEnd; i++) {
        childRows.push(tbody.children[i] as HTMLElement);
    }
    const initiallyOpen = expandDepth < 0 || depth < expandDepth;
    setExpanded(tr, childRows, initiallyOpen);

    const btn = tr.querySelector<HTMLElement>(".inspection-tree-expand")!;
    btn.addEventListener("click", (ev) => {
        ev.stopPropagation();
        const open = btn.getAttribute("aria-expanded") === "true";
        setExpanded(tr, childRows, !open);
    });
}

function setExpanded(
    parent: HTMLElement,
    children: readonly HTMLElement[],
    open: boolean,
): void {
    const btn = parent.querySelector<HTMLElement>(".inspection-tree-expand");
    if (btn) {
        btn.setAttribute("aria-expanded", open ? "true" : "false");
        const icon = btn.querySelector<HTMLElement>(".codicon");
        if (icon) {
            icon.classList.toggle("codicon-chevron-right", !open);
            icon.classList.toggle("codicon-chevron-down", open);
        }
    }
    for (const row of children) {
        // Hide the whole subtree, not just direct children — we own
        // every descendant we appended after `parent`.
        row.hidden = !open;
        if (!open) {
            // When collapsing, also fold any nested expanders so the
            // re-open shows the depth-1 view rather than re-expanding
            // everything the user had previously fanned out.
            const nestedBtn = row.querySelector<HTMLElement>(
                ".inspection-tree-expand[aria-expanded='true']",
            );
            if (nestedBtn) {
                nestedBtn.setAttribute("aria-expanded", "false");
                const icon = nestedBtn.querySelector<HTMLElement>(".codicon");
                if (icon) {
                    icon.classList.add("codicon-chevron-right");
                    icon.classList.remove("codicon-chevron-down");
                }
            }
        }
    }
}

/**
 * Best-effort clipboard write. Falls back to a hidden `<textarea>` +
 * `document.execCommand("copy")` when the async clipboard API is
 * unavailable (older webviews / non-secure contexts). Failures swallow
 * silently — the button has no visible result anyway.
 */
async function copyToClipboard(text: string): Promise<void> {
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

/** Stable JSON.stringify with a 2-space indent for human pasting. */
export function stringify(value: unknown): string {
    return JSON.stringify(value, null, 2);
}
