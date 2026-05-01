import { spawn } from 'child_process';

/**
 * Read PNG bytes for a preview's `main` baseline directly from the
 * `preview_main` branch via git plumbing. Used as a fallback when the
 * local `.compose-preview-history/` has no entry for `git.branch === 'main'`
 * — repos that follow the CI baseline workflow (see
 * [.github/actions/preview-baselines/action.yml]) push every `main` build's
 * rendered PNGs to a `preview_main` branch alongside a `baselines.json`
 * manifest. Reading from the ref means "vs main" works without local
 * archived history and without a daemon.
 *
 * Layout on the baseline branch (matches `compare-previews.py`'s writer):
 *
 *   ├── baselines.json            // { "<module>/<previewId>": { sha256, renderBasename, ... } }
 *   └── renders/
 *       └── <module>/
 *           └── <renderBasename>  // typically "<previewId>.png"
 *
 * The reader prefers `origin/preview_main` (post-fetch view of the
 * remote) and falls back to a local `preview_main` branch if no remote
 * tracking ref exists.
 */
export async function readPreviewMainPng(
    workspaceRoot: string,
    moduleId: string,
    previewId: string,
): Promise<PreviewMainResult | null> {
    for (const ref of ['origin/preview_main', 'preview_main']) {
        const baselineText = await gitShowText(workspaceRoot, ref, 'baselines.json');
        if (!baselineText) { continue; }
        const baselines = parseBaselines(baselineText);
        if (!baselines) { continue; }
        const entry = lookupBaselineEntry(baselines, moduleId, previewId);
        if (!entry) { continue; }
        const basename = entry.renderBasename || `${previewId}.png`;
        const pngPath = `renders/${entry.module ?? moduleId}/${basename}`;
        const png = await gitShowBytes(workspaceRoot, ref, pngPath);
        if (!png) { continue; }
        return { ref, baselineKey: entry.key, sha256: entry.sha256, png };
    }
    return null;
}

export interface PreviewMainResult {
    /** e.g. `'origin/preview_main'` — the ref that resolved. */
    ref: string;
    /** e.g. `':samples:android/com.example.RedSquare'`. */
    baselineKey: string;
    /** PNG SHA-256 the manifest claims. Diff stats can verify against
     *  the actual fetched bytes if needed. */
    sha256?: string;
    png: Buffer;
}

interface BaselineEntry {
    key: string;
    module?: string;
    sha256?: string;
    renderBasename?: string;
}

function lookupBaselineEntry(
    baselines: Record<string, unknown>,
    moduleId: string,
    previewId: string,
): BaselineEntry | null {
    const direct = baselines[`${moduleId}/${previewId}`];
    if (direct && typeof direct === 'object') {
        return makeEntry(`${moduleId}/${previewId}`, direct as Record<string, unknown>);
    }
    // Fall back to endswith match. Different module-key encodings (with /
    // without leading colon) shouldn't drop a hit for an unambiguous
    // previewId. If two modules share a previewId, pick whichever the
    // manifest enumerates first — the caller can't tell us which they
    // meant from previewId alone.
    const suffix = `/${previewId}`;
    for (const k of Object.keys(baselines)) {
        if (!k.endsWith(suffix)) { continue; }
        const v = baselines[k];
        if (v && typeof v === 'object') {
            return makeEntry(k, v as Record<string, unknown>);
        }
    }
    return null;
}

function makeEntry(key: string, raw: Record<string, unknown>): BaselineEntry {
    return {
        key,
        module: typeof raw.module === 'string' ? raw.module : undefined,
        sha256: typeof raw.sha256 === 'string' ? raw.sha256 : undefined,
        renderBasename: typeof raw.renderBasename === 'string' ? raw.renderBasename : undefined,
    };
}

function parseBaselines(text: string): Record<string, unknown> | null {
    try {
        const parsed = JSON.parse(text);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { return null; }
        return parsed as Record<string, unknown>;
    } catch {
        return null;
    }
}

function gitShow(
    workspaceRoot: string,
    ref: string,
    path: string,
): Promise<Buffer | null> {
    return new Promise((resolve) => {
        const child = spawn('git', ['show', `${ref}:${path}`], {
            cwd: workspaceRoot,
            stdio: ['ignore', 'pipe', 'ignore'],
        });
        const chunks: Buffer[] = [];
        child.stdout.on('data', (c: Buffer) => chunks.push(c));
        child.on('close', (code) => resolve(code === 0 ? Buffer.concat(chunks) : null));
        child.on('error', () => resolve(null));
    });
}

async function gitShowText(workspaceRoot: string, ref: string, path: string): Promise<string | null> {
    const buf = await gitShow(workspaceRoot, ref, path);
    return buf ? buf.toString('utf8') : null;
}

async function gitShowBytes(workspaceRoot: string, ref: string, path: string): Promise<Buffer | null> {
    return gitShow(workspaceRoot, ref, path);
}
