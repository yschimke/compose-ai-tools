// Pure model + parsing for the Google Fonts catalog.
//
// The catalog is sourced from `https://fonts.google.com/metadata/fonts`
// (the same keyless JSON the fonts.google.com site itself consumes — no
// Developer API key required). This module is deliberately free of any
// `node`/`vscode`/`fs` imports so it can be shared verbatim between the
// host (`googleFontsClient`, `fontBrowserPanel`) and the webview bundle
// (`webview/fonts/*`), and unit-tested without a DOM.
//
// Nothing here touches the network — `googleFontsClient` owns the fetch
// and hands the raw JSON text in. We only parse, normalise, search, and
// build the `fonts.googleapis.com/css2` request URLs.

/** A single registered variation axis on a variable font. */
export interface FontAxis {
    /** OpenType axis tag, e.g. `wght`, `opsz`, `slnt`, `GRAD`. */
    tag: string;
    /** Human label from the axis registry (falls back to the tag). */
    displayName: string;
    min: number;
    max: number;
    defaultValue: number;
}

/** A static named instance the family ships (from the `fonts` map). */
export interface FontInstance {
    weight: number;
    italic: boolean;
}

export interface FontFamilyMeta {
    family: string;
    category: string;
    subsets: readonly string[];
    /** Lower is more popular (Google's ranking is 1-based). */
    popularity: number;
    /** Variable axes; empty for a non-variable family. */
    axes: readonly FontAxis[];
    /** Static instances parsed from the `fonts` map. */
    instances: readonly FontInstance[];
    /** Sorted, de-duplicated weights from `instances`. */
    weights: readonly number[];
    hasItalic: boolean;
    isVariable: boolean;
}

export interface FontCatalog {
    families: readonly FontFamilyMeta[];
    categories: readonly string[];
    fetchedAt: string;
}

const CSS2_BASE = "https://fonts.googleapis.com/css2";

function num(value: unknown, fallback: number): number {
    return typeof value === "number" && Number.isFinite(value)
        ? value
        : fallback;
}

/**
 * Google prefixes some JSON endpoints with an XSSI guard (`)]}'`).
 * Strip everything before the first `{` so `JSON.parse` succeeds. The
 * guard contains no braces, so the first `{` is always the real start
 * of the object payload.
 */
export function stripXssiPrefix(text: string): string {
    const brace = text.indexOf("{");
    return brace > 0 ? text.slice(brace) : text;
}

/** Parse a `fonts` map key (`"400"`, `"400italic"`, `"regular"`, …). */
function parseInstanceKey(key: string): FontInstance | null {
    if (key === "regular") return { weight: 400, italic: false };
    if (key === "italic") return { weight: 400, italic: true };
    const m = /^(\d{1,4})(italic)?$/.exec(key);
    if (!m) return null;
    return { weight: parseInt(m[1], 10), italic: m[2] != null };
}

/**
 * Parse the raw `metadata/fonts` JSON object into a normalised catalog.
 * Defensive against schema drift — unknown / malformed families are
 * dropped rather than throwing, so one bad row can't blank the browser.
 */
export function parseFontsMetadata(raw: unknown): FontCatalog {
    const root =
        raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};

    // Axis registry → displayName / default lookups for enrichment.
    const registry = new Map<
        string,
        { displayName: string; defaultValue: number }
    >();
    const rawRegistry = root.axisRegistry;
    if (Array.isArray(rawRegistry)) {
        for (const entry of rawRegistry) {
            if (!entry || typeof entry !== "object") continue;
            const a = entry as Record<string, unknown>;
            if (typeof a.tag !== "string") continue;
            registry.set(a.tag, {
                displayName:
                    typeof a.displayName === "string" ? a.displayName : a.tag,
                defaultValue: num(a.defaultValue, 0),
            });
        }
    }

    const families: FontFamilyMeta[] = [];
    const list = Array.isArray(root.familyMetadataList)
        ? root.familyMetadataList
        : [];
    for (const item of list) {
        if (!item || typeof item !== "object") continue;
        const f = item as Record<string, unknown>;
        const family = typeof f.family === "string" ? f.family : "";
        if (!family) continue;
        const category =
            typeof f.category === "string" && f.category ? f.category : "Other";
        const subsets = Array.isArray(f.subsets)
            ? (f.subsets.filter((s) => typeof s === "string") as string[])
            : [];
        const popularity = num(f.popularity, Number.MAX_SAFE_INTEGER);

        const instances: FontInstance[] = [];
        if (f.fonts && typeof f.fonts === "object") {
            for (const key of Object.keys(f.fonts as object)) {
                const parsed = parseInstanceKey(key);
                if (parsed) instances.push(parsed);
            }
        }

        const axes: FontAxis[] = [];
        if (Array.isArray(f.axes)) {
            for (const entry of f.axes) {
                if (!entry || typeof entry !== "object") continue;
                const a = entry as Record<string, unknown>;
                if (typeof a.tag !== "string") continue;
                const reg = registry.get(a.tag);
                axes.push({
                    tag: a.tag,
                    displayName: reg?.displayName ?? a.tag,
                    min: num(a.min, 0),
                    max: num(a.max, 0),
                    defaultValue: num(a.defaultValue, reg?.defaultValue ?? 0),
                });
            }
        }

        const weights = [...new Set(instances.map((i) => i.weight))].sort(
            (a, b) => a - b,
        );
        const hasItalic = instances.some((i) => i.italic);
        families.push({
            family,
            category,
            subsets,
            popularity,
            axes,
            instances,
            weights: weights.length > 0 ? weights : [400],
            hasItalic,
            isVariable: axes.length > 0,
        });
    }

    families.sort(
        (a, b) =>
            a.popularity - b.popularity || a.family.localeCompare(b.family),
    );
    const categories = [...new Set(families.map((f) => f.category))].sort();
    return { families, categories, fetchedAt: new Date().toISOString() };
}

export interface SearchOptions {
    query?: string;
    /** Category filter; `"all"` (or empty) matches everything. */
    category?: string;
    /** Cap the result count (default 60 to keep browse previews cheap). */
    limit?: number;
}

/**
 * Filter + cap a catalog. Families arrive already sorted by popularity,
 * so callers get the most popular matches first without re-sorting.
 */
export function searchCatalog(
    catalog: FontCatalog,
    options: SearchOptions = {},
): FontFamilyMeta[] {
    const query = (options.query ?? "").trim().toLowerCase();
    const category = options.category ?? "all";
    const limit = options.limit ?? 60;
    const out: FontFamilyMeta[] = [];
    for (const family of catalog.families) {
        if (category && category !== "all" && family.category !== category) {
            continue;
        }
        if (query && !family.family.toLowerCase().includes(query)) {
            continue;
        }
        out.push(family);
        if (out.length >= limit) break;
    }
    return out;
}

/** Encode a family name for a css2 `family=` segment (spaces → `+`). */
export function css2FamilyToken(family: string): string {
    return family.replace(/ /g, "+");
}

/**
 * css2 requires axis tags sorted: lowercase tags alphabetically, then
 * the rest (uppercase / custom registered axes like `GRAD`).
 */
export function sortAxisTags(tags: readonly string[]): string[] {
    const lower = tags.filter((t) => t === t.toLowerCase()).sort();
    const upper = tags.filter((t) => t !== t.toLowerCase()).sort();
    return [...lower, ...upper];
}

function fmtAxisValue(value: number): string {
    return Number.isInteger(value) ? String(value) : String(value);
}

/**
 * A lightweight css2 URL for *browsing* — just the default upright face
 * so each result row can paint in its real typeface without dragging
 * every weight/axis down the wire.
 */
export function buildCss2BrowseUrl(family: string): string {
    return `${CSS2_BASE}?family=${css2FamilyToken(family)}&display=swap`;
}

/**
 * The css2 URL used when *downloading* a family. For a variable font we
 * request the full range of every declared axis (plus both italic
 * tuples when the family ships italics) so the cached file retains all
 * its variation axes. For a static family we enumerate every shipped
 * (italic, weight) instance so each face is downloaded individually.
 */
export function buildCss2DownloadUrl(meta: FontFamilyMeta): string {
    const token = css2FamilyToken(meta.family);
    let spec: string;
    if (meta.isVariable) {
        const tags: string[] = [];
        if (meta.hasItalic) tags.push("ital");
        for (const a of meta.axes) if (a.tag !== "ital") tags.push(a.tag);
        const sorted = sortAxisTags(tags);
        const rangeFor = (tag: string): string => {
            const axis = meta.axes.find((a) => a.tag === tag);
            return axis
                ? `${fmtAxisValue(axis.min)}..${fmtAxisValue(axis.max)}`
                : "0";
        };
        const tuple = (italValue: number | null): string =>
            sorted
                .map((t) =>
                    t === "ital" ? String(italValue ?? 0) : rangeFor(t),
                )
                .join(",");
        const tuples = meta.hasItalic ? [tuple(0), tuple(1)] : [tuple(null)];
        spec = `${sorted.join(",")}@${tuples.join(";")}`;
    } else {
        const hasItalic = meta.hasItalic;
        const tags = hasItalic ? ["ital", "wght"] : ["wght"];
        const instances = [...meta.instances].sort(
            (a, b) =>
                (a.italic ? 1 : 0) - (b.italic ? 1 : 0) || a.weight - b.weight,
        );
        const tuples = instances.map((i) =>
            hasItalic ? `${i.italic ? 1 : 0},${i.weight}` : `${i.weight}`,
        );
        spec = `${tags.join(",")}@${tuples.join(";")}`;
    }
    return `${CSS2_BASE}?family=${token}:${spec}&display=swap`;
}
