// Shape-aware renderers for "no presenter, but the daemon attached
// some data" payloads in the focus inspector's Reports surface. The
// goal is to make every advertised data kind unsurprising without
// needing a hand-rolled presenter per kind:
//
//   - Image payload (`{ imageBase64, mediaType? }`)        → `<img>`
//   - Array of homogeneous objects                          → `<table>`
//   - Array of primitives                                   → `<ul>`
//   - Object whose values are all hex colours               → swatches
//   - Object with primitive scalar values                   → `<dl>`
//   - Anything else (trees, mixed shapes, unserialisable)   → `<pre>` JSON
//
// Every body also gets a "Copy raw" button so the user can paste the
// daemon's bytes into a side-channel for deeper investigation — that's
// the PR-preview use case `a11y/overlay` lit up first.
//
// Detectors are pure (no DOM) so they're easy to unit-test against
// fixture payloads. The body builders take detector output and return
// `HTMLElement`. The top-level `renderGenericBody` is the orchestrator
// the focus inspector reaches for.

/**
 * Max array length we'll table-render before falling back to JSON pre.
 * Above this, tables become slower than they're worth and the user is
 * usually better off scrubbing the raw bytes anyway.
 */
const MAX_TABLE_ROWS = 100;

/**
 * Max distinct keys we'll surface as table columns. Anything beyond
 * this and the table becomes hard to scan; the raw JSON view is the
 * right escape hatch.
 */
const MAX_TABLE_COLUMNS = 10;

/** Hex colour shapes we recognise for the swatches view. */
const HEX_COLOUR_RE = /^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

export interface ImagePayload {
    imageBase64: string;
    mediaType: string;
}

/**
 * Detect the image-payload shape `extension.ts` produces for
 * `.png`-transport data products. Returns `null` for any other shape.
 */
export function detectImagePayload(value: unknown): ImagePayload | null {
    if (typeof value !== "object" || value === null) return null;
    const maybe = value as { imageBase64?: unknown; mediaType?: unknown };
    if (
        typeof maybe.imageBase64 !== "string" ||
        maybe.imageBase64.length === 0
    ) {
        return null;
    }
    const mediaType =
        typeof maybe.mediaType === "string" ? maybe.mediaType : "image/png";
    return { imageBase64: maybe.imageBase64, mediaType };
}

/**
 * Returns `true` iff `value` is a non-empty array of objects whose
 * keys overlap enough to make a table worth rendering. Heuristics
 * tuned for the daemon's typical "list of structured records" shape
 * (recomposition counts, render traces, touch targets, font usage).
 */
export function isArrayOfObjects(value: unknown): value is object[] {
    if (!Array.isArray(value)) return false;
    if (value.length === 0 || value.length > MAX_TABLE_ROWS) return false;
    for (const item of value) {
        if (typeof item !== "object" || item === null) return false;
        if (Array.isArray(item)) return false;
    }
    return true;
}

/**
 * Returns `true` iff `value` is a non-empty array whose elements are
 * all primitives (string / number / boolean). The bulleted-list view
 * is cleaner than JSON for these.
 */
export function isArrayOfPrimitives(
    value: unknown,
): value is (string | number | boolean)[] {
    if (!Array.isArray(value)) return false;
    if (value.length === 0) return false;
    for (const item of value) {
        const t = typeof item;
        if (t !== "string" && t !== "number" && t !== "boolean") return false;
    }
    return true;
}

/**
 * Returns `true` iff `value` is a plain object whose values are all
 * hex-colour strings. Drives the swatch view for theme-token-shaped
 * data.
 */
export function isColourMap(value: unknown): value is Record<string, string> {
    if (typeof value !== "object" || value === null) return false;
    if (Array.isArray(value)) return false;
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return false;
    for (const [, v] of entries) {
        if (typeof v !== "string" || !HEX_COLOUR_RE.test(v)) return false;
    }
    return true;
}

/**
 * Returns `true` iff `value` is a plain object whose values are all
 * scalar primitives (no nested objects/arrays). A definition list is
 * the natural rendering.
 */
export function isPrimitiveKv(
    value: unknown,
): value is Record<string, string | number | boolean | null> {
    if (typeof value !== "object" || value === null) return false;
    if (Array.isArray(value)) return false;
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return false;
    for (const [, v] of entries) {
        if (v === null) continue;
        const t = typeof v;
        if (t !== "string" && t !== "number" && t !== "boolean") return false;
    }
    return true;
}

/** Render an image payload as a sized `<img>`. */
export function renderImageBody(
    payload: ImagePayload,
    alt: string,
): HTMLElement {
    const wrap = document.createElement("div");
    wrap.className = "focus-report-generic focus-report-generic-image";
    const img = document.createElement("img");
    img.className = "focus-report-overlay-img";
    img.alt = alt;
    img.src = `data:${payload.mediaType};base64,${payload.imageBase64}`;
    wrap.appendChild(img);
    return wrap;
}

/**
 * Render an array of homogeneous objects as a table. Column set is
 * the union of keys across rows, capped at [MAX_TABLE_COLUMNS] — extra
 * keys land in a final "…" column so the user knows something is
 * being elided. Cell values are stringified scalars; nested objects
 * show as `{…}` to keep rows scannable.
 */
export function renderArrayOfObjectsTable(rows: object[]): HTMLElement {
    const columns: string[] = [];
    const seen = new Set<string>();
    let elided = false;
    for (const row of rows) {
        for (const key of Object.keys(row)) {
            if (seen.has(key)) continue;
            if (columns.length >= MAX_TABLE_COLUMNS) {
                elided = true;
                continue;
            }
            seen.add(key);
            columns.push(key);
        }
    }

    const table = document.createElement("table");
    table.className = "focus-report-generic focus-report-table";
    const thead = document.createElement("thead");
    const headRow = document.createElement("tr");
    for (const col of columns) {
        const th = document.createElement("th");
        th.textContent = col;
        headRow.appendChild(th);
    }
    if (elided) {
        const th = document.createElement("th");
        th.textContent = "…";
        th.title = `More keys present; capped at ${MAX_TABLE_COLUMNS} columns.`;
        headRow.appendChild(th);
    }
    thead.appendChild(headRow);
    table.appendChild(thead);

    const tbody = document.createElement("tbody");
    for (const row of rows) {
        const tr = document.createElement("tr");
        const record = row as Record<string, unknown>;
        for (const col of columns) {
            const td = document.createElement("td");
            td.textContent = formatCell(record[col]);
            tr.appendChild(td);
        }
        if (elided) {
            tr.appendChild(document.createElement("td"));
        }
        tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    return table;
}

/** Bulleted list for an array of primitives. */
export function renderArrayOfPrimitivesList(
    items: readonly (string | number | boolean)[],
): HTMLElement {
    const ul = document.createElement("ul");
    ul.className = "focus-report-generic focus-report-primitive-list";
    for (const item of items) {
        const li = document.createElement("li");
        li.textContent = String(item);
        ul.appendChild(li);
    }
    return ul;
}

/**
 * Swatch grid for a `Record<string, hex-colour>`. Each entry renders
 * as a small coloured square + the key + the canonical hex string.
 */
export function renderColourSwatches(
    colours: Record<string, string>,
): HTMLElement {
    const wrap = document.createElement("div");
    wrap.className = "focus-report-generic focus-report-swatches";
    for (const [name, hex] of Object.entries(colours)) {
        const row = document.createElement("div");
        row.className = "focus-report-swatch";
        const sample = document.createElement("span");
        sample.className = "focus-report-swatch-sample";
        sample.style.backgroundColor = hex;
        // Inline tooltip carries the value too so it survives screenshot.
        sample.title = hex;
        const label = document.createElement("span");
        label.className = "focus-report-swatch-label";
        label.textContent = name;
        const value = document.createElement("span");
        value.className = "focus-report-swatch-value";
        value.textContent = hex;
        row.appendChild(sample);
        row.appendChild(label);
        row.appendChild(value);
        wrap.appendChild(row);
    }
    return wrap;
}

/** `<dl>` for a primitive-valued object. */
export function renderPrimitiveKvDl(
    obj: Record<string, string | number | boolean | null>,
): HTMLElement {
    const dl = document.createElement("dl");
    dl.className = "focus-report-generic focus-report-kv";
    for (const [key, value] of Object.entries(obj)) {
        const dt = document.createElement("dt");
        dt.textContent = key;
        const dd = document.createElement("dd");
        dd.textContent = value === null ? "null" : String(value);
        dl.appendChild(dt);
        dl.appendChild(dd);
    }
    return dl;
}

/**
 * Raw JSON `<pre>` fallback. Always succeeds — unserialisable values
 * (cycles, BigInt) fall through to `String(data)` so we still surface
 * *something* rather than dropping the report.
 */
export function renderJsonPre(data: unknown): HTMLElement {
    const wrap = document.createElement("div");
    wrap.className = "focus-report-generic focus-report-generic-json";
    const pre = document.createElement("pre");
    pre.className = "focus-report-pre";
    pre.textContent = stringifySafe(data);
    wrap.appendChild(pre);
    return wrap;
}

/** Stringify `data` as pretty JSON, falling back to `String(data)`. */
export function stringifySafe(data: unknown): string {
    try {
        const out = JSON.stringify(data, null, 2);
        if (out === undefined) return String(data);
        return out;
    } catch {
        return String(data);
    }
}

/**
 * Append a "Copy raw" button that writes pretty-printed JSON for
 * [data] to the clipboard. Uses `navigator.clipboard.writeText` —
 * happy-dom and modern VS Code webview hosts both expose it. The
 * button is decorative when the underlying clipboard API is missing
 * (rare in the webview); the failure is silent because copy actions
 * shouldn't toast.
 */
export function appendCopyRawButton(host: HTMLElement, data: unknown): void {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "focus-report-copy-raw";
    btn.title = "Copy raw JSON to clipboard";
    btn.textContent = "Copy raw";
    btn.addEventListener("click", (ev) => {
        // The Report row is a <details>; clicks on the button shouldn't
        // also toggle the expansion.
        ev.preventDefault();
        ev.stopPropagation();
        const text = stringifySafe(data);
        const clipboard = navigator.clipboard;
        if (clipboard && typeof clipboard.writeText === "function") {
            void clipboard.writeText(text);
        }
        // Visual ack — flip the label briefly. Avoids a toast that
        // would steal focus; the button label itself confirms.
        const original = btn.textContent;
        btn.textContent = "Copied";
        btn.disabled = true;
        setTimeout(() => {
            btn.textContent = original;
            btn.disabled = false;
        }, 1200);
    });
    host.appendChild(btn);
}

/**
 * Top-level orchestrator. Detect the most-specific shape we can, build
 * its body, and tack on a copy-raw button. Returns the wrapping div so
 * callers can drop it directly into a Report body.
 */
export function renderGenericBody(data: unknown, title: string): HTMLElement {
    const wrap = document.createElement("div");
    wrap.className = "focus-report-generic-wrap";
    let inner: HTMLElement;
    const image = detectImagePayload(data);
    if (image) {
        inner = renderImageBody(image, title);
    } else if (isArrayOfObjects(data)) {
        inner = renderArrayOfObjectsTable(data);
    } else if (isArrayOfPrimitives(data)) {
        inner = renderArrayOfPrimitivesList(data);
    } else if (isColourMap(data)) {
        inner = renderColourSwatches(data);
    } else if (isPrimitiveKv(data)) {
        inner = renderPrimitiveKvDl(data);
    } else {
        inner = renderJsonPre(data);
    }
    wrap.appendChild(inner);
    appendCopyRawButton(wrap, data);
    return wrap;
}

function formatCell(value: unknown): string {
    if (value === null || value === undefined) return "";
    const t = typeof value;
    if (t === "string" || t === "number" || t === "boolean")
        return String(value);
    if (Array.isArray(value))
        return value.length === 0 ? "[]" : `[…${value.length}]`;
    return "{…}";
}
