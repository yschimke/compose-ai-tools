// Host-side reader for the Text/i18n bundle's data-URI font preview.
//
// The webview's resolved-family preview cell uses CSS
// `font-family: <resolvedFamily>` which only paints in the real font when
// the webview already has it on its system stack. Asset / google-
// downloaded fonts silently fall back to the panel's system default — so
// the webview asks the host to read the bytes via `loadFontPreview` and
// the host responds with a base64 `data:` URI keyed to the row that
// requested it. The webview then injects a `@font-face` rule and swaps
// `style.fontFamily` over to the injected family.
//
// Validation lives in `resolveFontPreviewPath` (pure — testable without a
// running VS Code instance); the actual `fs.readFile` + base64-encode
// step lives in `readFontPreview` and takes an injected reader so the
// test can pin its own bytes without touching disk.

import * as path from "path";

/** Map from `.ttf` / `.otf` / `.woff` / `.woff2` to its IANA media-type.
 *  Anything else resolves the request to `dataUri: null` so a stray
 *  `.xml` font descriptor doesn't end up base64-encoded as
 *  `font/unknown`. */
const FONT_MIME_BY_EXT: Readonly<Record<string, string>> = {
    ".ttf": "font/ttf",
    ".otf": "font/otf",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
};

/** 5 MB cap. Larger files still validate as paths but the read step
 *  resolves to `dataUri: null` so a pathological asset can't pin the
 *  panel by streaming hundreds of MB into a data URI. */
export const MAX_FONT_PREVIEW_BYTES = 5 * 1024 * 1024;

export interface FontPreviewPath {
    /** Absolute, normalised path under the workspace root. */
    absolutePath: string;
    /** IANA media type derived from the extension. */
    mime: string;
}

/**
 * Validates that [sourceFile] is an absolute path under [workspaceRoot]
 * (no path-traversal) AND has a supported font extension. Returns the
 * resolved path + media-type on success, or `null` when the caller
 * should respond with `dataUri: null`.
 *
 * Pure — exposed so a unit test can pin the rejection cases without
 * spinning up a workspace.
 */
export function resolveFontPreviewPath(
    sourceFile: string | null | undefined,
    workspaceRoot: string | null | undefined,
): FontPreviewPath | null {
    if (!sourceFile || typeof sourceFile !== "string") return null;
    if (!workspaceRoot || typeof workspaceRoot !== "string") return null;
    if (!path.isAbsolute(sourceFile)) return null;
    const normalised = path.normalize(sourceFile);
    const root = path.normalize(workspaceRoot);
    // Append a separator so `/foo/bar-evil` doesn't get accepted under
    // root `/foo/bar`. `path.relative` then has to produce a path that
    // doesn't begin with `..` and isn't absolute.
    const rel = path.relative(root, normalised);
    if (rel.startsWith("..") || path.isAbsolute(rel)) return null;
    const ext = path.extname(normalised).toLowerCase();
    const mime = FONT_MIME_BY_EXT[ext];
    if (!mime) return null;
    return { absolutePath: normalised, mime };
}

/** Injected reader so tests can pin bytes without touching disk. */
export type ReadFileBytes = (
    absolutePath: string,
) => Promise<Uint8Array | Buffer>;

/**
 * Reads [resolved.absolutePath] as base64 and returns a `data:` URI of
 * the form `data:<mime>;base64,<encoded>`. Returns `null` when the read
 * fails or the file exceeds [MAX_FONT_PREVIEW_BYTES] — the caller (the
 * `loadFontPreview` message handler) posts `dataUri: null` in either
 * case so the webview leaves the system-font fallback in place.
 */
export async function readFontPreview(
    resolved: FontPreviewPath,
    readFileBytes: ReadFileBytes,
): Promise<string | null> {
    try {
        const buf = await readFileBytes(resolved.absolutePath);
        if (buf.byteLength > MAX_FONT_PREVIEW_BYTES) return null;
        const base64 =
            buf instanceof Buffer
                ? buf.toString("base64")
                : Buffer.from(buf).toString("base64");
        return "data:" + resolved.mime + ";base64," + base64;
    } catch {
        return null;
    }
}
