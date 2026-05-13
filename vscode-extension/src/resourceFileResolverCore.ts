// Pure host-side resolution logic for the `resources/used` deep-link.
// Lives separately from `resourceFileResolver.ts` so unit tests can
// drive it through stubs without `import * as vscode from "vscode"`,
// which Mocha can't load outside the extension host. The thin
// vscode wrapper (`openResourceFile`) closes over this module and
// hands off to `vscode.workspace.openTextDocument` / editor APIs.
//
// Strategy mirrors `findManifestIconReferences`: regex against the
// document text is enough for the AGP-emitted shapes, and skipping a
// full XML parser keeps the resolver portable to a future CLI / MCP
// surface where carrying a vscode runtime would be awkward.

/**
 * `(resourceType, resourceName, resolvedFile, packageName)` payload of
 * the `openResourceFile` webview message — quoted here so the core
 * module doesn't have to depend on the wider `WebviewToExtension`
 * union just to type its inputs.
 */
export interface OpenResourceRequest {
    resourceType: string;
    resourceName: string;
    resolvedFile: string | null | undefined;
    packageName: string | null | undefined;
}

/**
 * Filesystem / workspace operations the resolver needs. Implementors:
 * production code wires VS Code's `workspace.findFiles` / `fs.existsSync`
 * / `path.isAbsolute` (see `resourceFileResolver.ts`); tests hand in
 * fully-deterministic stubs.
 *
 * `findFiles` returns absolute paths (the wire shape VS Code's
 * `findFiles` exposes via `Uri.fsPath`) so the core module can keep
 * working in string-land.
 */
export interface ResolverFs {
    findFiles(
        include: string,
        exclude: string | null,
        maxResults: number,
    ): Promise<string[]>;
    readTextFile(absolutePath: string): Promise<string>;
    fileExists(absolutePath: string): boolean;
    isAbsolutePath(filePath: string): boolean;
}

/**
 * Values-style resource types whose entries live in a values XML file
 * (`values/`, `values-night/`, `values-w600dp/`, ...) under a
 * `<{type} name="…">` element. `array` is split into `<array>`,
 * `<string-array>` and `<integer-array>` in the platform — the
 * recorder collapses all three into the `array` type when recording,
 * so we accept any of the three when searching back.
 */
const VALUES_TYPES = new Set([
    "string",
    "color",
    "dimen",
    "bool",
    "integer",
    "array",
    "plurals",
]);

/**
 * File-style resource types — entries live in their own files under
 * `res/<type>...` named `<name>.<ext>`. The recorder emits absolute
 * paths for these via `Resources.getValue` so the resolver almost
 * always lands on path 1; the name-glob is a fallback for daemons
 * that emit only the type+name pair.
 */
const FILE_TYPES = new Set([
    "drawable",
    "mipmap",
    "raw",
    "layout",
    "menu",
    "xml",
    "font",
    "anim",
    "animator",
    "interpolator",
    "transition",
]);

/**
 * Resolve [request] to an absolute filesystem path or `null` when no
 * workspace file fits. Resolution order:
 *
 *   1. Absolute `resolvedFile` that exists on disk.
 *   2. Project-relative `res/<rel>` path — glob `**\/<rel>` so
 *      multi-module projects pick the right module's copy.
 *   3. Type-aware fallback by name: values resources scan every
 *      `values*` XML; file resources glob for `res/<type>*\/<name>.*`.
 */
export async function resolveResourceFilePath(
    request: OpenResourceRequest,
    fs: ResolverFs,
): Promise<string | null> {
    const resolvedFile = request.resolvedFile;
    if (resolvedFile) {
        if (fs.isAbsolutePath(resolvedFile) && fs.fileExists(resolvedFile)) {
            return resolvedFile;
        }
        if (resolvedFile.startsWith("res/")) {
            const matches = await fs.findFiles(
                "**/" + resolvedFile,
                "**/build/**",
                1,
            );
            if (matches.length > 0) {
                return matches[0];
            }
        }
        if (fs.fileExists(resolvedFile)) {
            return resolvedFile;
        }
    }
    return resolveByName(request, fs);
}

async function resolveByName(
    request: OpenResourceRequest,
    fs: ResolverFs,
): Promise<string | null> {
    const type = request.resourceType;
    if (isValuesType(type)) {
        const candidates = await fs.findFiles(
            "**/res/values*/**/*.xml",
            "**/build/**",
            64,
        );
        for (const candidate of candidates) {
            const text = await fs.readTextFile(candidate);
            if (findValuesEntry(text, type, request.resourceName)) {
                return candidate;
            }
        }
        return null;
    }
    if (FILE_TYPES.has(type)) {
        const matches = await fs.findFiles(
            `**/res/${type}*/${request.resourceName}.*`,
            "**/build/**",
            1,
        );
        if (matches.length > 0) {
            return matches[0];
        }
    }
    return null;
}

/** True iff [type] is one whose entries live in a values XML file. */
export function isValuesType(type: string): boolean {
    return VALUES_TYPES.has(type);
}

/**
 * Find the byte range of the `name="…"` attribute that belongs to a
 * resource of [type] [name] within [text]. Returns null when no match
 * exists. Range covers just the attribute value (between the quotes)
 * so the editor selection highlights the name itself.
 *
 * Exported for unit tests. The same regex strategy
 * `findManifestIconReferences` uses — values XML files are simple
 * enough that full parsing isn't worth the cost.
 */
export function findValuesEntry(
    text: string,
    type: string,
    name: string,
): { start: number; end: number } | null {
    const elementPattern = elementPatternFor(type);
    const escapedName = escapeRegExp(name);
    const typed = new RegExp(
        `<\\s*(?:${elementPattern})\\b[^>]*?\\bname\\s*=\\s*(["'])(${escapedName})\\1`,
        "g",
    );
    const match = typed.exec(text);
    if (match) {
        const nameOffset = match[0].lastIndexOf(match[2]);
        const start = match.index + nameOffset;
        return { start, end: start + match[2].length };
    }
    return null;
}

function elementPatternFor(type: string): string {
    if (type === "array") {
        return "string-array|integer-array|array";
    }
    return escapeRegExp(type);
}

function escapeRegExp(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
