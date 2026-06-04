// Host-side network access for the Google Fonts browser.
//
// Three jobs, all driven through an injected `FetchLike` so the unit
// tests can pin responses without touching the network:
//
//   * `fetchFontCatalog`  — GET the keyless `metadata/fonts` JSON.
//   * `fetchCss2Faces`    — GET a `css2` stylesheet and parse the
//                           `@font-face` blocks (one per face/subset)
//                           down to the gstatic file URLs.
//   * `downloadFontBytes` — GET a single gstatic font file.
//
// Google's css2 endpoint returns *modern* formats (woff2) only when the
// request carries a browser-ish User-Agent; from Node we set one
// explicitly so we don't get served legacy TTF.

import {
    FontCatalog,
    parseFontsMetadata,
    stripXssiPrefix,
} from "./googleFontsCatalog";

export interface FetchResponse {
    ok: boolean;
    status: number;
    text(): Promise<string>;
    arrayBuffer(): Promise<ArrayBuffer>;
}

export type FetchLike = (
    url: string,
    init?: { headers?: Record<string, string> },
) => Promise<FetchResponse>;

export const FONTS_METADATA_URL = "https://fonts.google.com/metadata/fonts";

// A current-ish Chrome UA so css2 serves woff2 rather than legacy ttf.
const BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

function defaultFetch(): FetchLike {
    const g = globalThis as { fetch?: FetchLike };
    if (!g.fetch) {
        throw new Error(
            "global fetch is unavailable in this runtime; pass a FetchLike explicitly",
        );
    }
    return g.fetch.bind(globalThis) as FetchLike;
}

/** GET + parse the keyless Google Fonts catalog. */
export async function fetchFontCatalog(
    fetchImpl: FetchLike = defaultFetch(),
): Promise<FontCatalog> {
    const res = await fetchImpl(FONTS_METADATA_URL, {
        headers: { "User-Agent": BROWSER_UA, Accept: "application/json" },
    });
    if (!res.ok) {
        throw new Error(
            `Google Fonts metadata request failed (HTTP ${res.status})`,
        );
    }
    const text = await res.text();
    let parsed: unknown;
    try {
        parsed = JSON.parse(stripXssiPrefix(text));
    } catch (err) {
        throw new Error(
            `Google Fonts metadata was not valid JSON: ${(err as Error).message}`,
        );
    }
    return parseFontsMetadata(parsed);
}

export interface Css2Face {
    style: "normal" | "italic";
    /** Static weight, or the low end of a variable `font-weight` range. */
    weightMin: number;
    /** Equal to `weightMin` for a static face. */
    weightMax: number;
    /** gstatic file URL. */
    url: string;
    /** `woff2` / `woff` / `truetype` etc. (defaults to `woff2`). */
    format: string;
    /** Subset comment preceding the block (`latin`, `cyrillic`, …). */
    subset: string | null;
}

const FONT_FACE_RE = /(?:\/\*\s*([\w-]+)\s*\*\/\s*)?@font-face\s*\{([^}]*)\}/g;

/**
 * Parse every `@font-face` block out of a css2 stylesheet. css2 emits
 * one block per (style, weight) × subset, so the same face appears for
 * `latin`, `latin-ext`, `cyrillic`, … each with its own file URL. We
 * de-duplicate by (style, weightMin, weightMax) preferring the `latin`
 * subset's file — that's the slice that covers the specimen text we
 * render, and downloading one file per face keeps the cache small.
 */
export function parseCss2Faces(css: string): Css2Face[] {
    const byKey = new Map<string, Css2Face>();
    let m: RegExpExecArray | null;
    FONT_FACE_RE.lastIndex = 0;
    while ((m = FONT_FACE_RE.exec(css)) !== null) {
        const subset = m[1] ?? null;
        const body = m[2] ?? "";
        const styleMatch = /font-style:\s*([a-z]+)/i.exec(body);
        const style: "normal" | "italic" =
            styleMatch && styleMatch[1].toLowerCase() === "italic"
                ? "italic"
                : "normal";
        const weightMatch = /font-weight:\s*([\d]+)(?:\s+([\d]+))?/.exec(body);
        const weightMin = weightMatch ? parseInt(weightMatch[1], 10) : 400;
        const weightMax =
            weightMatch && weightMatch[2]
                ? parseInt(weightMatch[2], 10)
                : weightMin;
        const urlMatch = /src:\s*url\(([^)]+)\)/i.exec(body);
        if (!urlMatch) continue;
        const url = urlMatch[1].trim().replace(/^['"]|['"]$/g, "");
        const formatMatch = /format\(\s*['"]?([\w-]+)/i.exec(body);
        const format = formatMatch ? formatMatch[1] : "woff2";

        const key = `${style}:${weightMin}:${weightMax}`;
        const existing = byKey.get(key);
        if (!existing || (subset === "latin" && existing.subset !== "latin")) {
            byKey.set(key, {
                style,
                weightMin,
                weightMax,
                url,
                format,
                subset,
            });
        }
    }
    return [...byKey.values()];
}

/** GET a css2 stylesheet and return its parsed faces. */
export async function fetchCss2Faces(
    css2Url: string,
    fetchImpl: FetchLike = defaultFetch(),
): Promise<Css2Face[]> {
    const res = await fetchImpl(css2Url, {
        headers: { "User-Agent": BROWSER_UA },
    });
    if (!res.ok) {
        throw new Error(
            `Google Fonts css2 request failed (HTTP ${res.status})`,
        );
    }
    return parseCss2Faces(await res.text());
}

/** GET a single font file as bytes. */
export async function downloadFontBytes(
    url: string,
    fetchImpl: FetchLike = defaultFetch(),
): Promise<Uint8Array> {
    const res = await fetchImpl(url);
    if (!res.ok) {
        throw new Error(`Font file download failed (HTTP ${res.status})`);
    }
    return new Uint8Array(await res.arrayBuffer());
}
