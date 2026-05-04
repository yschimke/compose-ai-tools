// @ts-nocheck
//
// Imperative behaviour for the read-only Preview History panel.
//
// Verbatim port of the previously-inline IIFE script in
// `src/historyPanel.ts` (the `<script nonce="...">` block in `getHtml()`).
// Kept untyped for now so the lift-into-bundle change stays mechanical;
// future commits can incrementally type-tighten and split this into
// reactive Lit components.
//
// Runs once per webview load. Assumes `<history-app>` has already
// rendered its skeleton into light DOM, so `document.getElementById(...)`
// queries below resolve.

import { getVsCodeApi } from "../shared/vscode";

export function setupHistoryBehavior(): void {
    const vscode = getVsCodeApi();
    const messageEl = document.getElementById("message");
    const timelineEl = document.getElementById("timeline");
    const filterSourceEl = document.getElementById("filter-source");
    const filterBranchEl = document.getElementById("filter-branch");
    const btnRefreshEl = document.getElementById("btn-refresh");
    const btnDiffEl = document.getElementById("btn-diff");
    // Scope chip is owned by `<scope-chip>` — see
    // `components/ScopeChip.ts`. It listens for `setScopeLabel` directly.

    let entries = [];
    let selectedIds = new Set();
    let expandedId = null;
    // Thumbnail cache + dedup so each entry's PNG is fetched at most once
    // per panel session even if scrolling brings the same row back into
    // view repeatedly.
    const thumbCache = new Map();
    const thumbRequested = new Set();
    const thumbObserver =
        "IntersectionObserver" in window
            ? new IntersectionObserver(
                  (items) => {
                      for (const item of items) {
                          if (!item.isIntersecting) continue;
                          const el = item.target;
                          const id = el.dataset.id;
                          if (!id || thumbRequested.has(id)) continue;
                          thumbRequested.add(id);
                          vscode.postMessage({ command: "loadThumb", id });
                      }
                  },
                  { root: null, rootMargin: "64px", threshold: 0 },
              )
            : null;

    btnRefreshEl.addEventListener("click", () => {
        vscode.postMessage({ command: "refresh" });
    });
    btnDiffEl.addEventListener("click", () => {
        const ids = [...selectedIds];
        if (ids.length === 2) {
            vscode.postMessage({
                command: "diff",
                fromId: ids[0],
                toId: ids[1],
            });
        }
    });
    filterSourceEl.addEventListener("change", applyFilters);
    filterBranchEl.addEventListener("change", applyFilters);

    function setMessage(text) {
        if (text) {
            messageEl.textContent = text;
            messageEl.style.display = "block";
            timelineEl.innerHTML = "";
        } else {
            messageEl.style.display = "none";
        }
    }

    function applyFilters() {
        const sourceVal = filterSourceEl.value;
        const branchVal = filterBranchEl.value;
        timelineEl.querySelectorAll(".row").forEach((row) => {
            const matchSource =
                sourceVal === "all" || row.dataset.sourceKind === sourceVal;
            const matchBranch =
                branchVal === "all" || row.dataset.branch === branchVal;
            row.style.display = matchSource && matchBranch ? "" : "none";
        });
    }

    function populateBranchFilter(es) {
        const branches = new Set();
        for (const e of es) {
            const b = e.git && e.git.branch;
            if (b) branches.add(b);
        }
        const prev = filterBranchEl.value;
        filterBranchEl.innerHTML = "";
        const allOpt = document.createElement("option");
        allOpt.value = "all";
        allOpt.textContent = "All branches";
        filterBranchEl.appendChild(allOpt);
        for (const b of [...branches].sort()) {
            const opt = document.createElement("option");
            opt.value = b;
            opt.textContent = b;
            filterBranchEl.appendChild(opt);
        }
        if ([...filterBranchEl.options].some((o) => o.value === prev)) {
            filterBranchEl.value = prev;
        }
    }

    function renderTimeline() {
        // Thumbnails not yet loaded for the new entry set should retry —
        // disconnect the old observer and rebuild against the new rows.
        if (thumbObserver) thumbObserver.disconnect();
        thumbRequested.clear();
        timelineEl.innerHTML = "";
        // Latest archived render on main for the currently scoped preview.
        // O(N) over the visible page; no extra request. Used for the
        // "vs main" indicator dot below.
        const mainHash = findLatestMainHash(entries);
        for (const entry of entries) {
            const row = document.createElement("div");
            row.className = "row";
            row.setAttribute("role", "listitem");
            row.dataset.id = entry.id || "";
            row.dataset.sourceKind = (entry.source && entry.source.kind) || "";
            row.dataset.branch = (entry.git && entry.git.branch) || "";

            const thumb = document.createElement("div");
            thumb.className = "thumb";
            thumb.dataset.id = entry.id || "";
            if (thumbCache.has(entry.id)) {
                populateThumb(thumb, thumbCache.get(entry.id));
            } else if (thumbObserver) {
                thumbObserver.observe(thumb);
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
                entry.deltaFromPrevious &&
                entry.deltaFromPrevious.pngHashChanged
                    ? '<span class="changed-dot" title="bytes changed vs previous"></span>'
                    : "";
            const mainDot =
                mainHash && entry.pngHash && entry.pngHash !== mainHash
                    ? '<span class="main-dot" title="bytes differ from latest main render"></span>'
                    : "";
            const absolute = formatAbsolute(entry.timestamp);
            const trigger = entry.trigger ? entry.trigger : "—";
            const branch = (entry.git && entry.git.branch) || "";
            const subParts = [];
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
            diffPrevBtn.title =
                "Diff against the previous entry for this preview";
            diffPrevBtn.setAttribute("aria-label", "Diff vs previous");
            diffPrevBtn.innerHTML =
                '<i class="codicon codicon-arrow-up" aria-hidden="true"></i>';
            diffPrevBtn.addEventListener("click", (ev) => {
                ev.stopPropagation();
                requestRowDiff(entry.id, row, "previous");
            });
            actions.appendChild(diffPrevBtn);
            const diffCurrentBtn = document.createElement("button");
            diffCurrentBtn.className = "icon-button row-action";
            diffCurrentBtn.title =
                "Diff against the live render of this preview";
            diffCurrentBtn.setAttribute("aria-label", "Diff vs current");
            diffCurrentBtn.innerHTML =
                '<i class="codicon codicon-git-compare" aria-hidden="true"></i>';
            diffCurrentBtn.addEventListener("click", (ev) => {
                ev.stopPropagation();
                requestRowDiff(entry.id, row, "current");
            });
            actions.appendChild(diffCurrentBtn);
            row.appendChild(actions);

            row.addEventListener("click", (ev) => {
                if (ev.shiftKey) toggleSelected(entry.id, row);
                else expandRow(entry.id, row);
            });
            timelineEl.appendChild(row);
        }
    }

    function toggleSelected(id, row) {
        if (selectedIds.has(id)) {
            selectedIds.delete(id);
            row.classList.remove("selected");
        } else {
            if (selectedIds.size >= 2) {
                // Drop oldest selection so we never have more than 2.
                const drop = [...selectedIds][0];
                selectedIds.delete(drop);
                const prev = timelineEl.querySelector(
                    '.row[data-id="' + cssEscape(drop) + '"]',
                );
                if (prev) prev.classList.remove("selected");
            }
            selectedIds.add(id);
            row.classList.add("selected");
        }
        btnDiffEl.disabled = selectedIds.size !== 2;
    }

    function expandRow(id, row) {
        // Collapse any previous expansion.
        const prev = timelineEl.querySelector(".expanded");
        if (prev) prev.remove();
        if (expandedId === id) {
            expandedId = null;
            return;
        }
        expandedId = id;

        const expansion = document.createElement("div");
        expansion.className = "expanded";
        expansion.dataset.id = id;
        expansion.innerHTML = "<div>Loading…</div>";
        row.parentNode.insertBefore(expansion, row.nextSibling);
        vscode.postMessage({ command: "loadImage", id });
    }

    function requestRowDiff(id, row, against) {
        const prev = timelineEl.querySelector(".expanded");
        if (prev) prev.remove();
        expandedId = id;
        const expansion = document.createElement("div");
        expansion.className = "expanded diff-expanded";
        expansion.dataset.id = id;
        expansion.dataset.against = against;
        expansion.innerHTML = "<div>Loading diff…</div>";
        row.parentNode.insertBefore(expansion, row.nextSibling);
        vscode.postMessage({ command: "requestDiff", id, against });
    }

    function fillExpansion(id, imageData, entry) {
        const expansion = timelineEl.querySelector(
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
                vscode.postMessage({ command: "openSource", sourceFile }),
            );
            actions.appendChild(open);
        }
        expansion.appendChild(actions);
    }

    function populateThumb(thumbEl, imageData) {
        thumbEl.innerHTML = "";
        const img = document.createElement("img");
        img.src = "data:image/png;base64," + imageData;
        img.alt = "";
        thumbEl.appendChild(img);
    }

    function fillDiff(
        id,
        against,
        leftLabel,
        leftImage,
        rightLabel,
        rightImage,
    ) {
        const expansion = timelineEl.querySelector(
            '.expanded[data-id="' +
                cssEscape(id) +
                '"][data-against="' +
                cssEscape(against) +
                '"]',
        );
        if (!expansion) return;
        expansion.innerHTML = "";
        const payload = { leftLabel, leftImage, rightLabel, rightImage };
        const stored = vscode.getState() || {};
        const initialMode =
            stored.diffMode === "overlay" || stored.diffMode === "onion"
                ? stored.diffMode
                : "side";
        const header = document.createElement("div");
        header.className = "diff-header";
        const body = document.createElement("div");
        body.className = "diff-body";
        const modeBar = buildHistoryDiffModeBar(initialMode, (mode) => {
            const cur = vscode.getState() || {};
            cur.diffMode = mode;
            vscode.setState(cur);
            renderHistoryDiffMode(body, mode, payload);
        });
        const stats = document.createElement("div");
        stats.className = "diff-stats";
        stats.textContent = "computing…";
        header.appendChild(modeBar);
        header.appendChild(stats);
        expansion.appendChild(header);
        expansion.appendChild(body);
        renderHistoryDiffMode(body, initialMode, payload);
        computeDiffStats(payload.leftImage, payload.rightImage).then((s) => {
            applyDiffStats(stats, s);
        });
    }

    // Client-side pixel diff helpers — duplicated from the live panel
    // so the History panel webview is self-contained. Same algorithm.
    function computeDiffStats(leftBase64, rightBase64) {
        return new Promise((resolve) => {
            const left = new Image();
            const right = new Image();
            let loaded = 0;
            const onErr = () => resolve({ error: "image failed to load" });
            const onOk = () => {
                if (++loaded < 2) return;
                try {
                    if (
                        left.naturalWidth !== right.naturalWidth ||
                        left.naturalHeight !== right.naturalHeight
                    ) {
                        resolve({
                            sameSize: false,
                            leftW: left.naturalWidth,
                            leftH: left.naturalHeight,
                            rightW: right.naturalWidth,
                            rightH: right.naturalHeight,
                        });
                        return;
                    }
                    const w = left.naturalWidth,
                        h = left.naturalHeight;
                    const c1 = document.createElement("canvas");
                    c1.width = w;
                    c1.height = h;
                    c1.getContext("2d").drawImage(left, 0, 0);
                    const d1 = c1
                        .getContext("2d")
                        .getImageData(0, 0, w, h).data;
                    const c2 = document.createElement("canvas");
                    c2.width = w;
                    c2.height = h;
                    c2.getContext("2d").drawImage(right, 0, 0);
                    const d2 = c2
                        .getContext("2d")
                        .getImageData(0, 0, w, h).data;
                    let diff = 0;
                    const len = d1.length;
                    for (let i = 0; i < len; i += 4) {
                        if (
                            d1[i] !== d2[i] ||
                            d1[i + 1] !== d2[i + 1] ||
                            d1[i + 2] !== d2[i + 2] ||
                            d1[i + 3] !== d2[i + 3]
                        )
                            diff++;
                    }
                    const total = w * h;
                    resolve({
                        sameSize: true,
                        w,
                        h,
                        diffPx: diff,
                        total,
                        percent: total > 0 ? diff / total : 0,
                    });
                } catch (err) {
                    resolve({
                        error: (err && err.message) || "stats unavailable",
                    });
                }
            };
            left.onload = onOk;
            left.onerror = onErr;
            right.onload = onOk;
            right.onerror = onErr;
            left.src = "data:image/png;base64," + leftBase64;
            right.src = "data:image/png;base64," + rightBase64;
        });
    }

    function findLatestMainHash(es) {
        let bestTs = "";
        let bestHash = null;
        for (const e of es) {
            if (!e || (e.git && e.git.branch) !== "main") continue;
            if (!e.pngHash) continue;
            const ts = e.timestamp || "";
            if (ts > bestTs) {
                bestTs = ts;
                bestHash = e.pngHash;
            }
        }
        return bestHash;
    }

    function applyDiffStats(el, s) {
        if (!s) {
            el.textContent = "";
            el.removeAttribute("data-state");
            return;
        }
        if (s.error) {
            el.textContent = s.error;
            el.removeAttribute("data-state");
            return;
        }
        if (!s.sameSize) {
            el.textContent =
                "sizes differ — " +
                s.leftW +
                "×" +
                s.leftH +
                " vs " +
                s.rightW +
                "×" +
                s.rightH;
            el.dataset.state = "size-mismatch";
            return;
        }
        if (s.diffPx === 0) {
            el.textContent = "identical · " + s.w + "×" + s.h;
            el.dataset.state = "identical";
            return;
        }
        const p = s.percent * 100;
        const pct = p < 0.01 ? p.toFixed(3) : p.toFixed(2);
        el.textContent =
            s.diffPx.toLocaleString() +
            " px (" +
            pct +
            "%) · " +
            s.w +
            "×" +
            s.h;
        el.dataset.state = "changed";
    }

    function buildHistoryDiffModeBar(initialMode, onChange) {
        const bar = document.createElement("div");
        bar.className = "diff-mode-bar";
        bar.setAttribute("role", "tablist");
        const modes = [
            { id: "side", label: "Side" },
            { id: "overlay", label: "Overlay" },
            { id: "onion", label: "Onion" },
        ];
        for (const m of modes) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.textContent = m.label;
            btn.dataset.mode = m.id;
            btn.setAttribute("role", "tab");
            btn.setAttribute(
                "aria-selected",
                m.id === initialMode ? "true" : "false",
            );
            if (m.id === initialMode) btn.classList.add("active");
            btn.addEventListener("click", () => {
                bar.querySelectorAll("button").forEach((b) => {
                    b.classList.toggle("active", b.dataset.mode === m.id);
                    b.setAttribute(
                        "aria-selected",
                        b.dataset.mode === m.id ? "true" : "false",
                    );
                });
                onChange(m.id);
            });
            bar.appendChild(btn);
        }
        return bar;
    }

    function renderHistoryDiffMode(body, mode, payload) {
        body.innerHTML = "";
        if (mode === "side") {
            const grid = document.createElement("div");
            grid.className = "diff-grid";
            grid.appendChild(
                buildDiffPane(payload.leftLabel, payload.leftImage),
            );
            grid.appendChild(
                buildDiffPane(payload.rightLabel, payload.rightImage),
            );
            body.appendChild(grid);
            return;
        }
        body.appendChild(buildHistoryDiffStack(mode, payload));
    }

    function buildHistoryDiffStack(mode, payload) {
        const wrapper = document.createElement("div");
        wrapper.className = "diff-stack-wrapper";
        const stack = document.createElement("div");
        stack.className = "diff-stack";
        stack.dataset.mode = mode;
        const base = document.createElement("img");
        base.className = "diff-stack-base";
        base.alt = payload.leftLabel;
        base.src = "data:image/png;base64," + payload.leftImage;
        const top = document.createElement("img");
        top.className = "diff-stack-top";
        top.alt = payload.rightLabel;
        top.src = "data:image/png;base64," + payload.rightImage;
        stack.appendChild(base);
        stack.appendChild(top);
        wrapper.appendChild(stack);
        if (mode === "onion") {
            const slider = document.createElement("input");
            slider.type = "range";
            slider.min = "0";
            slider.max = "100";
            slider.value = "50";
            slider.className = "diff-stack-onion-slider";
            slider.setAttribute(
                "aria-label",
                "Onion-skin mix between " +
                    payload.leftLabel +
                    " and " +
                    payload.rightLabel,
            );
            stack.style.setProperty("--diff-onion-mix", "0.5");
            slider.addEventListener("input", () => {
                stack.style.setProperty(
                    "--diff-onion-mix",
                    (slider.value / 100).toString(),
                );
            });
            wrapper.appendChild(slider);
        }
        const cap = document.createElement("div");
        cap.className = "diff-stack-caption";
        cap.textContent = payload.leftLabel + "  ◄  " + payload.rightLabel;
        wrapper.appendChild(cap);
        return wrapper;
    }

    function buildDiffPane(label, imageData) {
        const pane = document.createElement("div");
        pane.className = "diff-pane";
        const cap = document.createElement("div");
        cap.className = "diff-pane-label";
        cap.textContent = label;
        pane.appendChild(cap);
        if (imageData) {
            const img = document.createElement("img");
            img.src = "data:image/png;base64," + imageData;
            img.alt = label;
            pane.appendChild(img);
        } else {
            const empty = document.createElement("div");
            empty.className = "diff-pane-empty";
            empty.textContent = "(no image)";
            pane.appendChild(empty);
        }
        return pane;
    }

    function showDiff(fromId, toId, result) {
        const block = document.createElement("div");
        block.className = "diff-inline";
        if (!result) {
            block.textContent = "Diff unavailable.";
        } else {
            const changed = result.pngHashChanged
                ? "pixels differ"
                : "bytes identical";
            block.textContent =
                "Diff (metadata): " +
                changed +
                (result.diffPx != null ? " · diffPx=" + result.diffPx : "") +
                (result.ssim != null
                    ? " · ssim=" + result.ssim.toFixed(3)
                    : "");
        }
        timelineEl.insertBefore(block, timelineEl.firstChild);
        // Auto-clear after 12s so the panel doesn't accumulate stale diffs.
        setTimeout(() => block.remove(), 12_000);
    }

    function escapeHtml(text) {
        const div = document.createElement("div");
        div.textContent = String(text ?? "");
        return div.innerHTML;
    }
    function cssEscape(s) {
        return String(s).replace(/[\\"']/g, "\\$&");
    }

    function formatRelative(iso) {
        if (!iso) return "(no timestamp)";
        const t = Date.parse(iso);
        if (isNaN(t)) return iso;
        const s = Math.round((Date.now() - t) / 1000);
        if (s < 5) return "just now";
        if (s < 60) return s + "s ago";
        const m = Math.round(s / 60);
        if (m < 60) return m + "m ago";
        const h = Math.round(m / 60);
        if (h < 24) return h + "h ago";
        const d = Math.round(h / 24);
        if (d < 30) return d + "d ago";
        const mo = Math.round(d / 30);
        if (mo < 12) return mo + "mo ago";
        return Math.round(mo / 12) + "y ago";
    }

    function formatAbsolute(iso) {
        if (!iso) return "";
        const t = Date.parse(iso);
        if (isNaN(t)) return "";
        try {
            return new Date(t).toLocaleString(undefined, {
                month: "short",
                day: "numeric",
                hour: "2-digit",
                minute: "2-digit",
            });
        } catch (_) {
            return "";
        }
    }

    window.addEventListener("message", (event) => {
        const msg = event.data;
        switch (msg.command) {
            case "setEntries":
                entries = msg.result.entries || [];
                if (entries.length === 0) {
                    setMessage("No history yet for this preview / module.");
                } else {
                    setMessage("");
                    populateBranchFilter(entries);
                    renderTimeline();
                    applyFilters();
                }
                break;
            case "entryAdded":
                if (msg.entry) {
                    entries.unshift(msg.entry);
                    populateBranchFilter(entries);
                    renderTimeline();
                    applyFilters();
                }
                break;
            case "showMessage":
                setMessage(msg.text || "");
                break;
            // setScopeLabel is handled by <scope-chip>.
            case "imageReady":
                fillExpansion(msg.id, msg.imageData, msg.entry);
                break;
            case "imageError": {
                const expansion = timelineEl.querySelector(
                    '.expanded[data-id="' + cssEscape(msg.id) + '"]',
                );
                if (expansion)
                    expansion.textContent =
                        "Failed to load image: " +
                        (msg.message || "(no detail)");
                break;
            }
            case "thumbReady": {
                thumbCache.set(msg.id, msg.imageData);
                const thumbEl = timelineEl.querySelector(
                    '.thumb[data-id="' + cssEscape(msg.id) + '"]',
                );
                if (thumbEl) populateThumb(thumbEl, msg.imageData);
                break;
            }
            case "thumbError":
                // Drop the dedup so a future re-render can retry. Leave
                // the gray box in place; surfacing per-entry errors at
                // the thumb scale would just be noisy.
                thumbRequested.delete(msg.id);
                break;
            case "diffResult":
                showDiff(msg.fromId, msg.toId, msg.result);
                break;
            case "diffError":
                showDiff(msg.fromId, msg.toId, null);
                break;
            case "diffReady":
                fillDiff(
                    msg.id,
                    msg.against,
                    msg.leftLabel,
                    msg.leftImage,
                    msg.rightLabel,
                    msg.rightImage,
                );
                break;
            case "diffPairError": {
                const expansion = timelineEl.querySelector(
                    '.expanded[data-id="' +
                        cssEscape(msg.id) +
                        '"][data-against="' +
                        cssEscape(msg.against) +
                        '"]',
                );
                if (expansion)
                    expansion.textContent =
                        "Diff unavailable: " + (msg.message || "(no detail)");
                break;
            }
        }
    });
}
