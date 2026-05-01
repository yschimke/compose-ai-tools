import { ChildProcessByStdio, spawn } from 'child_process';
import type { Readable, Writable } from 'stream';

/**
 * Read PNG bytes for a preview's `main` baseline directly from the
 * `compose-preview/main` branch via git plumbing. Used as a fallback when
 * the local `.compose-preview-history/` has no entry for
 * `git.branch === 'main'` — repos that follow the CI baseline workflow
 * (see [.github/actions/preview-baselines/action.yml]) push every `main`
 * build's rendered PNGs to a `compose-preview/main` branch alongside a
 * `baselines.json` manifest. Reading from the ref means "vs main" works
 * without local archived history and without a daemon.
 *
 * Layout on the baseline branch (matches `compare-previews.py`'s writer):
 *
 *   ├── baselines.json            // { "<module>/<previewId>": { sha256, renderBasename, ... } }
 *   └── renders/
 *       └── <module>/
 *           └── <renderBasename>  // typically "<previewId>.png"
 *
 * The reader prefers `origin/compose-preview/main` (post-fetch view of the
 * remote) and falls back to the local branch and then the legacy
 * `preview_main` flat ref so repos that haven't migrated keep working.
 */
export async function readPreviewMainPng(
    workspaceRoot: string,
    moduleId: string,
    previewId: string,
): Promise<PreviewMainResult | null> {
    const batch = getBatch(workspaceRoot);
    const candidateRefs = [
        'origin/compose-preview/main',
        'compose-preview/main',
        'origin/preview_main',
        'preview_main',
    ];
    for (const ref of candidateRefs) {
        const baselineBuf = await batch.read(`${ref}:baselines.json`);
        if (!baselineBuf) { continue; }
        const baselines = parseBaselines(baselineBuf.toString('utf8'));
        if (!baselines) { continue; }
        const entry = lookupBaselineEntry(baselines, moduleId, previewId);
        if (!entry) { continue; }
        const basename = entry.renderBasename || `${previewId}.png`;
        const png = await batch.read(`${ref}:renders/${entry.module ?? moduleId}/${basename}`);
        if (!png) { continue; }
        return { ref, baselineKey: entry.key, sha256: entry.sha256, png };
    }
    return null;
}

export interface PreviewMainResult {
    /** e.g. `'origin/compose-preview/main'` — the ref that resolved. */
    ref: string;
    /** e.g. `':samples:android/com.example.RedSquare'`. */
    baselineKey: string;
    /** PNG SHA-256 the manifest claims. Diff stats can verify against
     *  the actual fetched bytes if needed. */
    sha256?: string;
    png: Buffer;
}

/** Tear down any long-lived `git cat-file --batch` processes. Wired
 *  into the extension's `context.subscriptions` so an extension reload
 *  doesn't leak children. */
export function disposePreviewMainBatches(): void {
    for (const b of batches.values()) { b.dispose(); }
    batches.clear();
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

const batches = new Map<string, GitCatFileBatch>();

function getBatch(cwd: string): GitCatFileBatch {
    let b = batches.get(cwd);
    if (!b) {
        b = new GitCatFileBatch(cwd);
        batches.set(cwd, b);
    }
    return b;
}

/**
 * Stateful parser for `git cat-file --batch` stdout.
 *
 * Wire format (stdin: `<rev>\n`, stdout: `<sha> <type> <size>\n<bytes>\n`
 * for hits, `<rev> missing\n` for misses). Responses come back in the
 * same order requests were sent. Body parsing tracks `bodyRemaining`
 * byte-exactly because PNG payloads can (and routinely do) contain
 * newlines and the trailing `\n` is also fixed-position. Exported for
 * unit testing — the `GitCatFileBatch` wrapper composes this with the
 * spawn lifecycle.
 */
export class CatFileBatchParser {
    private buffer: Buffer = Buffer.alloc(0);
    private state: 'header' | 'body' = 'header';
    private bodyRemaining = 0;
    private bodyChunks: Buffer[] = [];

    /** Append a stdout chunk and return any responses it completes. Each
     *  array element is either the blob body or `null` for `missing`. */
    feed(chunk: Buffer): Array<Buffer | null> {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        const out: Array<Buffer | null> = [];
        while (true) {
            if (this.state === 'header') {
                const nl = this.buffer.indexOf(0x0a);
                if (nl === -1) { return out; }
                const line = this.buffer.slice(0, nl).toString('utf8');
                this.buffer = this.buffer.slice(nl + 1);
                if (line.endsWith(' missing')) {
                    out.push(null);
                    continue;
                }
                const parts = line.split(' ');
                const size = parts.length === 3 ? parseInt(parts[2], 10) : NaN;
                if (isNaN(size) || size < 0) {
                    // Unparseable header — fail the request and try to
                    // recover on the next line. Realistically only the
                    // first read after a process restart can land here.
                    out.push(null);
                    continue;
                }
                this.state = 'body';
                this.bodyRemaining = size;
                this.bodyChunks = [];
            }
            if (this.state === 'body') {
                // Body is exactly `bodyRemaining` bytes followed by '\n'.
                if (this.buffer.length < this.bodyRemaining + 1) { return out; }
                this.bodyChunks.push(this.buffer.slice(0, this.bodyRemaining));
                this.buffer = this.buffer.slice(this.bodyRemaining + 1);
                this.bodyRemaining = 0;
                this.state = 'header';
                out.push(Buffer.concat(this.bodyChunks));
                this.bodyChunks = [];
            }
        }
    }

    reset(): void {
        this.buffer = Buffer.alloc(0);
        this.state = 'header';
        this.bodyRemaining = 0;
        this.bodyChunks = [];
    }
}

/**
 * Long-running `git cat-file --batch` per workspace. Compared with
 * fork-per-call `git show`, this collapses the per-lookup cost from
 * ~30–80ms (process spawn) to a single stdin write. The diff-all-vs-main
 * walk over N previews drops from O(N) forks (4N including the
 * baselines.json reads) to a single batch process for the session.
 *
 * Resolves a FIFO of pending callbacks against the parser's emitted
 * responses (1:1 with stdin requests).
 */
class GitCatFileBatch {
    private child: ChildProcessByStdio<Writable, Readable, null> | null = null;
    private parser = new CatFileBatchParser();
    private queue: Array<(b: Buffer | null) => void> = [];
    private disposed = false;

    constructor(private readonly cwd: string) {}

    read(rev: string): Promise<Buffer | null> {
        if (this.disposed) { return Promise.resolve(null); }
        if (!this.ensure()) { return Promise.resolve(null); }
        return new Promise((resolve) => {
            this.queue.push(resolve);
            try {
                this.child!.stdin.write(rev + '\n');
            } catch {
                this.handleExit();
                resolve(null);
            }
        });
    }

    private ensure(): boolean {
        if (this.child) { return true; }
        try {
            this.child = spawn(
                'git', ['cat-file', '--batch'],
                { cwd: this.cwd, stdio: ['pipe', 'pipe', 'ignore'] },
            ) as ChildProcessByStdio<Writable, Readable, null>;
        } catch {
            this.child = null;
            return false;
        }
        this.child.stdout.on('data', (c: Buffer) => {
            for (const result of this.parser.feed(c)) {
                const cb = this.queue.shift();
                if (cb) { cb(result); }
            }
        });
        this.child.on('exit', () => this.handleExit());
        this.child.on('error', () => this.handleExit());
        return true;
    }

    private handleExit(): void {
        this.child = null;
        this.parser.reset();
        const drained = this.queue;
        this.queue = [];
        for (const cb of drained) { cb(null); }
    }

    dispose(): void {
        this.disposed = true;
        const child = this.child;
        this.handleExit();
        if (child) {
            try { child.stdin.end(); } catch { /* already closed */ }
            try { child.kill(); } catch { /* already gone */ }
        }
    }
}
