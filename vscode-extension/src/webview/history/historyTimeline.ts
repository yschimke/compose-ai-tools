// Timeline + row construction for the History panel.
//
// Lifted verbatim from `behavior.ts`'s `renderTimeline` / `toggleSelected` /
// `expandRow` / `requestRowDiff` / `fillExpansion` / `populateThumb` cluster
// so the imperative DOM operations stop needing closure access to the
// rest of the panel. The diff sub-views (`fillDiff`, `computeDiffStats`,
// `applyDiffStats`, `renderHistoryDiffMode`, `buildHistoryDiffStack`,
// `buildDiffPane`, `showDiff`) are a follow-up — they're a separate
// concern with its own state and call into a different DOM tree.
//
// Each function takes a `HistoryRowConfig` covering the collaborator
// surface — `vscode` (postMessage), the static `timelineEl` /
// `btnDiffEl` DOM handles, the `selectedIds` Set + `expandedId` mutable
// scalar, and the thumb-loading infrastructure (cache + dedup +
// IntersectionObserver). The state stays owned by `behavior.ts` until
// the eventual `<history-row>` Lit component lands and can claim it.

import type { HistoryEntry } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";
import {
    cssEscape,
    escapeHtml,
    findLatestMainHash,
    formatAbsolute,
    formatRelative,
} from "./historyData";

export interface HistoryRowConfig {
    vscode: VsCodeApi<unknown>;
    /** `<div id="timeline">` — the parent container for all rows. */
    timelineEl: HTMLElement;
    /** Toolbar diff button — disabled state tracks `selectedIds.size === 2`. */
    btnDiffEl: HTMLButtonElement;
    /** Per-session selection set (max 2). Direct Set reference; mutated
     *  by `toggleSelected`. */
    selectedIds: Set<string>;
    /** Currently expanded row's id, or `null` when nothing is expanded.
     *  Mutated by `expandRow` (toggle) and `requestRowDiff` (replace). */
    getExpandedId(): string | null;
    setExpandedId(id: string | null): void;
    /** Thumb image cache (id → base64 PNG). Populated by the
     *  `thumbReady` message handler; read here on render to skip a fresh
     *  request for already-loaded thumbs. */
    thumbCache: Map<string, string>;
    /** Dedup set: ids we've already asked the extension to load.
     *  Cleared per `renderTimeline` so a fresh entry set retries. */
    thumbRequested: Set<string>;
    /** Lazy-load observer for the thumb column — `null` on environments
     *  without `IntersectionObserver`. Disconnected per render. */
    thumbObserver: IntersectionObserver | null;
}

export function renderTimeline(
    entries: readonly HistoryEntry[],
    config: HistoryRowConfig,
): void {
    // Thumbnails not yet loaded for the new entry set should retry —
    // disconnect the old observer and rebuild against the new rows.
    if (config.thumbObserver) config.thumbObserver.disconnect();
    config.thumbRequested.clear();
    config.timelineEl.innerHTML = "";
    // Latest archived render on main for the currently scoped preview.
    // O(N) over the visible page; no extra request. Used for the
    // "vs main" indicator dot below.
    const mainHash = findLatestMainHash(entries);
    for (const entry of entries) {
        const entryId = entry.id ?? "";
        const row = document.createElement("div");
        row.className = "row";
        row.setAttribute("role", "listitem");
        row.dataset.id = entryId;
        row.dataset.sourceKind = (entry.source && entry.source.kind) || "";
        row.dataset.branch = (entry.git && entry.git.branch) || "";

        const thumb = document.createElement("div");
        thumb.className = "thumb";
        thumb.dataset.id = entryId;
        const cached = config.thumbCache.get(entryId);
        if (cached !== undefined) {
            populateThumb(thumb, cached);
        } else if (config.thumbObserver) {
            config.thumbObserver.observe(thumb);
        }
        row.appendChild(thumb);

        const meta = document.createElement("div");
        meta.className = "meta";
        const ts = document.createElement("div");
        ts.className = "ts";
        ts.textContent = formatRelative(entry.timestamp);
        ts.title = entry.timestamp || "";
        meta.appendChild(ts);

        const sub = document.createElement("div");
        sub.className = "sub";
        const dot =
            entry.deltaFromPrevious && entry.deltaFromPrevious.pngHashChanged
                ? '<span class="changed-dot" title="bytes changed vs previous"></span>'
                : "";
        const mainDot =
            mainHash && entry.pngHash && entry.pngHash !== mainHash
                ? '<span class="main-dot" title="bytes differ from latest main render"></span>'
                : "";
        const absolute = formatAbsolute(entry.timestamp);
        const trigger = entry.trigger ? entry.trigger : "—";
        const branch = (entry.git && entry.git.branch) || "";
        const subParts: string[] = [];
        if (absolute) subParts.push(escapeHtml(absolute));
        subParts.push(escapeHtml(trigger));
        if (branch) subParts.push(escapeHtml(branch));
        sub.innerHTML = dot + mainDot + subParts.join(" · ");
        meta.appendChild(sub);
        row.appendChild(meta);

        const badge = document.createElement("span");
        badge.className = "badge";
        badge.textContent = (entry.source && entry.source.kind) || "fs";
        row.appendChild(badge);

        const actions = document.createElement("div");
        actions.className = "row-actions";
        const diffPrevBtn = document.createElement("button");
        diffPrevBtn.className = "icon-button row-action";
        diffPrevBtn.title = "Diff against the previous entry for this preview";
        diffPrevBtn.setAttribute("aria-label", "Diff vs previous");
        diffPrevBtn.innerHTML =
            '<i class="codicon codicon-arrow-up" aria-hidden="true"></i>';
        diffPrevBtn.addEventListener("click", (ev) => {
            ev.stopPropagation();
            requestRowDiff(entryId, row, "previous", config);
        });
        actions.appendChild(diffPrevBtn);
        const diffCurrentBtn = document.createElement("button");
        diffCurrentBtn.className = "icon-button row-action";
        diffCurrentBtn.title = "Diff against the live render of this preview";
        diffCurrentBtn.setAttribute("aria-label", "Diff vs current");
        diffCurrentBtn.innerHTML =
            '<i class="codicon codicon-git-compare" aria-hidden="true"></i>';
        diffCurrentBtn.addEventListener("click", (ev) => {
            ev.stopPropagation();
            requestRowDiff(entryId, row, "current", config);
        });
        actions.appendChild(diffCurrentBtn);
        row.appendChild(actions);

        row.addEventListener("click", (ev) => {
            if (ev.shiftKey) toggleSelected(entryId, row, config);
            else expandRow(entryId, row, config);
        });
        config.timelineEl.appendChild(row);
    }
}

/** Toggle [id] in/out of the (max-2) selection set, mirroring the row's
 *  `.selected` class. Drops the oldest selection when adding a third. */
export function toggleSelected(
    id: string,
    row: HTMLElement,
    config: HistoryRowConfig,
): void {
    if (config.selectedIds.has(id)) {
        config.selectedIds.delete(id);
        row.classList.remove("selected");
    } else {
        if (config.selectedIds.size >= 2) {
            // Drop oldest selection so we never have more than 2.
            const drop = [...config.selectedIds][0];
            config.selectedIds.delete(drop);
            const prev = config.timelineEl.querySelector(
                '.row[data-id="' + cssEscape(drop) + '"]',
            );
            if (prev) prev.classList.remove("selected");
        }
        config.selectedIds.add(id);
        row.classList.add("selected");
    }
    config.btnDiffEl.disabled = config.selectedIds.size !== 2;
}

/** Toggle the inline expansion (full-size image + actions) for [id].
 *  Collapses any other open expansion first. Posts `loadImage` so the
 *  extension streams the PNG bytes back asynchronously. */
export function expandRow(
    id: string,
    row: HTMLElement,
    config: HistoryRowConfig,
): void {
    const prev = config.timelineEl.querySelector(".expanded");
    if (prev) prev.remove();
    if (config.getExpandedId() === id) {
        config.setExpandedId(null);
        return;
    }
    config.setExpandedId(id);

    const expansion = document.createElement("div");
    expansion.className = "expanded";
    expansion.dataset.id = id;
    expansion.innerHTML = "<div>Loading…</div>";
    row.parentNode?.insertBefore(expansion, row.nextSibling);
    config.vscode.postMessage({ command: "loadImage", id });
}

/** Open an inline diff expansion against the previous / current entry.
 *  Replaces any open expansion (image OR diff) and asks the extension
 *  to compute the comparison. */
export function requestRowDiff(
    id: string,
    row: HTMLElement,
    against: "previous" | "current",
    config: HistoryRowConfig,
): void {
    const prev = config.timelineEl.querySelector(".expanded");
    if (prev) prev.remove();
    config.setExpandedId(id);
    const expansion = document.createElement("div");
    expansion.className = "expanded diff-expanded";
    expansion.dataset.id = id;
    expansion.dataset.against = against;
    expansion.innerHTML = "<div>Loading diff…</div>";
    row.parentNode?.insertBefore(expansion, row.nextSibling);
    config.vscode.postMessage({ command: "requestDiff", id, against });
}

/** Populate an open expansion with the streamed full-size image plus
 *  an "Open in Editor" action when the entry's previewMetadata
 *  surfaces a sourceFile. No-op when the matching `.expanded` is gone
 *  (user collapsed before the bytes arrived). */
export function fillExpansion(
    id: string,
    imageData: string,
    entry: HistoryEntry | undefined,
    config: HistoryRowConfig,
): void {
    const expansion = config.timelineEl.querySelector(
        '.expanded[data-id="' + cssEscape(id) + '"]',
    );
    if (!expansion) return;
    expansion.innerHTML = "";
    const img = document.createElement("img");
    img.src = "data:image/png;base64," + imageData;
    img.alt = (entry && entry.previewId) || id;
    expansion.appendChild(img);

    const actions = document.createElement("div");
    actions.className = "actions";
    const sourceFile =
        entry && entry.previewMetadata && entry.previewMetadata.sourceFile;
    if (sourceFile) {
        const open = document.createElement("button");
        open.textContent = "Open in Editor";
        open.addEventListener("click", () =>
            config.vscode.postMessage({ command: "openSource", sourceFile }),
        );
        actions.appendChild(open);
    }
    expansion.appendChild(actions);
}

/** Stamp a thumbnail image into a `.thumb` div. Idempotent — drops any
 *  prior content first. */
export function populateThumb(thumbEl: HTMLElement, imageData: string): void {
    thumbEl.innerHTML = "";
    const img = document.createElement("img");
    img.src = "data:image/png;base64," + imageData;
    img.alt = "";
    thumbEl.appendChild(img);
}
