import * as fs from 'fs';
import * as path from 'path';
import {
    HistoryDiffMode,
    HistoryDiffResult,
    HistoryListParams,
    HistoryListResult,
    HistoryReadResult,
    HistorySourceKind,
} from './daemonProtocol';

/**
 * Filesystem-backed history reader. Used by the Preview History panel when:
 *
 *   - The daemon is disabled or unhealthy, OR
 *   - The user wants to browse history for a module that doesn't have a
 *     daemon up (no Gradle bootstrap descriptor; cross-worktree browsing).
 *
 * Layout per HISTORY.md § "Sidecar metadata schema":
 *
 *   <historyDir>/
 *   ├── index.jsonl                            # one entry per line, append-only
 *   └── <sanitisedPreviewId>/
 *       ├── 20260430-101234-a1b2c3d4.png
 *       └── 20260430-101234-a1b2c3d4.json      # sidecar
 *
 * The reader prefers `index.jsonl` for listing (cheap aggregate scan), then
 * falls back to walking per-preview folders if the index is missing or has
 * fewer entries than the on-disk truth (torn-line tolerant per § "Concurrency").
 *
 * **Greenfield assumption** per HISTORY.md § "Greenfield — no legacy data":
 * the legacy `HistorizePreviewsTask` was removed in #311. This reader does
 * NOT tolerate PNG-only entries; every entry on disk has a sibling sidecar
 * because the daemon writes both atomically.
 */
export class HistoryReader {
    constructor(private readonly historyDir: string) {}

    /** True iff `<historyDir>/index.jsonl` exists. The panel uses this to
     *  skip rendering when the consumer hasn't enabled history yet. */
    exists(): boolean {
        return fs.existsSync(path.join(this.historyDir, 'index.jsonl'));
    }

    /**
     * Mirrors the daemon's `history/list` semantics on the filesystem.
     * Filter precedence is documented in HISTORY.md § "Worktree-aware
     * listing"; we apply them all in one pass over the index. The
     * `sourceKind` / `sourceId` filters reach into entries written by
     * `LocalFsHistorySource` only — the FS reader doesn't know about
     * git-ref entries, so a `sourceKind: 'git'` filter from the panel
     * returns an empty list against the FS reader.
     */
    list(params: HistoryListParams = {}): HistoryListResult {
        const indexPath = path.join(this.historyDir, 'index.jsonl');
        const entries: HistoryEntryShape[] = [];
        if (fs.existsSync(indexPath)) {
            try {
                const raw = fs.readFileSync(indexPath, 'utf-8');
                for (const line of raw.split('\n')) {
                    if (line.length === 0) { continue; }
                    try {
                        entries.push(JSON.parse(line) as HistoryEntryShape);
                    } catch {
                        // Torn-line tolerance per HISTORY.md § "Concurrency"
                        // — readers seeing a partial line skip it. The
                        // per-preview sidecars stay the source of truth.
                    }
                }
            } catch {
                // Unreadable index → fall through to empty list. Caller
                // re-attempts via the daemon or surfaces the empty state.
            }
        }

        // Newest first per PROTOCOL.md § "history/list".
        entries.sort((a, b) => (b.timestamp ?? '').localeCompare(a.timestamp ?? ''));

        const filtered = entries.filter(e => matchesFilter(e, params));
        const start = parseCursor(params.cursor);
        const limit = clampLimit(params.limit);
        const page = filtered.slice(start, start + limit);
        const nextCursor = (start + limit < filtered.length)
            ? encodeCursor(start + limit)
            : undefined;
        return {
            entries: page,
            nextCursor,
            totalCount: filtered.length,
        };
    }

    /**
     * Mirrors `history/read`. Loads the sidecar from
     * `<historyDir>/<sanitisedPreviewId>/<id>.json`, returns the absolute
     * `pngPath` (sibling on disk) and optionally inlines the bytes when
     * `inline=true` — same contract as the daemon path.
     */
    read(id: string, inline = false): HistoryReadResult | null {
        const sidecar = this.findSidecar(id);
        if (!sidecar) { return null; }
        let entry: HistoryEntryShape;
        try {
            entry = JSON.parse(fs.readFileSync(sidecar.path, 'utf-8')) as HistoryEntryShape;
        } catch {
            return null;
        }
        const pngPath = path.join(path.dirname(sidecar.path), entry.pngPath ?? `${id}.png`);
        if (!fs.existsSync(pngPath)) { return null; }
        const result: HistoryReadResult = {
            entry,
            previewMetadata: entry.previewMetadata,
            pngPath,
        };
        if (inline) {
            try {
                result.pngBytes = fs.readFileSync(pngPath).toString('base64');
            } catch {
                /* leave pngBytes undefined — caller falls back to pngPath */
            }
        }
        return result;
    }

    /**
     * Metadata-mode `history/diff`. `pngHash` lives on the sidecar so we
     * can compare cheaply without reading any bytes. Pixel mode is daemon-
     * only — the FS reader returns the metadata fields and leaves the
     * pixel slots undefined.
     */
    diff(from: string, to: string, mode: HistoryDiffMode = 'metadata'): HistoryDiffResult | null {
        if (mode === 'pixel') { return null; }
        const a = this.read(from);
        const b = this.read(to);
        if (!a || !b) { return null; }
        const aEntry = a.entry as HistoryEntryShape;
        const bEntry = b.entry as HistoryEntryShape;
        if (aEntry.previewId !== bEntry.previewId) {
            // The daemon would surface this as HistoryDiffMismatch (-32011).
            // FS reader returns null and lets the panel show the same
            // "different previews" message; the wire-level error is for
            // RPC callers, not local-FS code paths.
            return null;
        }
        return {
            pngHashChanged: aEntry.pngHash !== bEntry.pngHash,
            fromMetadata: a.entry,
            toMetadata: b.entry,
        };
    }

    /**
     * Walks `<historyDir>/<*>/` looking for the sidecar whose stem matches
     * `id`. The sanitised previewId folder name isn't directly recoverable
     * from `id` — sanitisation is one-way (`/` → `_`, etc.) — so we scan
     * top-level folders. For 17 previews × 50 entries each = 850 files,
     * that's a millisecond-class operation; if it grows we add an in-memory
     * id→folder map keyed off list().
     */
    private findSidecar(id: string): { path: string } | null {
        if (!fs.existsSync(this.historyDir)) { return null; }
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(this.historyDir, { withFileTypes: true });
        } catch {
            return null;
        }
        for (const entry of entries) {
            if (!entry.isDirectory()) { continue; }
            const candidate = path.join(this.historyDir, entry.name, `${id}.json`);
            if (fs.existsSync(candidate)) { return { path: candidate }; }
        }
        return null;
    }
}

/**
 * Subset of the sidecar JSON we actually consume — every other field is
 * passed through verbatim to the panel (which may render an arbitrary
 * subset). Keeping this narrow keeps the reader resilient to additive
 * schema changes per HISTORY.md § "File-format versioning".
 */
interface HistoryEntryShape {
    id?: string;
    previewId?: string;
    timestamp?: string;
    pngHash?: string;
    pngPath?: string;
    producer?: string;
    trigger?: string;
    source?: { kind?: HistorySourceKind; id?: string };
    worktree?: { path?: string; agentId?: string | null };
    git?: { branch?: string | null; commit?: string };
    previewMetadata?: unknown;
    [k: string]: unknown;
}

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 500;

function clampLimit(limit: number | undefined): number {
    if (limit == null) { return DEFAULT_LIMIT; }
    if (limit < 1) { return 1; }
    if (limit > MAX_LIMIT) { return MAX_LIMIT; }
    return limit;
}

/**
 * Cursor format: a base-10 integer offset into the filtered list. The
 * daemon's protocol leaves the cursor opaque, but consumers (the panel)
 * just feed the value back in — no requirement for cross-source stability.
 */
function parseCursor(cursor: string | undefined): number {
    if (!cursor) { return 0; }
    const n = parseInt(cursor, 10);
    return Number.isFinite(n) && n >= 0 ? n : 0;
}

function encodeCursor(offset: number): string { return String(offset); }

function matchesFilter(entry: HistoryEntryShape, params: HistoryListParams): boolean {
    if (params.previewId && entry.previewId !== params.previewId) { return false; }
    if (params.since && (entry.timestamp ?? '') < params.since) { return false; }
    if (params.until && (entry.timestamp ?? '') > params.until) { return false; }
    if (params.commit) {
        const c = entry.git?.commit ?? '';
        if (!c.startsWith(params.commit)) { return false; }
    }
    if (params.branch && entry.git?.branch !== params.branch) { return false; }
    if (params.branchPattern) {
        try {
            const re = new RegExp(params.branchPattern);
            if (!re.test(entry.git?.branch ?? '')) { return false; }
        } catch {
            // Bad regex from caller — treat as no-match rather than throwing
            // out of the list call. The panel can show "filter invalid".
            return false;
        }
    }
    if (params.worktreePath && entry.worktree?.path !== params.worktreePath) { return false; }
    if (params.agentId && entry.worktree?.agentId !== params.agentId) { return false; }
    if (params.sourceKind && entry.source?.kind !== params.sourceKind) { return false; }
    if (params.sourceId && entry.source?.id !== params.sourceId) { return false; }
    return true;
}
