import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { HistoryReader } from '../daemon/historyReader';

interface SidecarFields {
    id: string;
    previewId: string;
    timestamp: string;
    pngHash: string;
    pngPath?: string;
    trigger?: string;
    source?: { kind: string; id?: string };
    git?: { branch?: string | null; commit?: string };
    worktree?: { path?: string; agentId?: string | null };
    deltaFromPrevious?: { pngHashChanged?: boolean };
    previewMetadata?: { sourceFile?: string };
}

/** Builds a `<historyDir>` skeleton with one entry per sidecar. */
function withFixture<T>(
    sidecars: SidecarFields[],
    fn: (historyDir: string) => T | Promise<T>,
): () => Promise<T> {
    return async () => {
        const root = fs.mkdtempSync(path.join(os.tmpdir(), 'history-reader-'));
        try {
            const indexLines: string[] = [];
            for (const sidecar of sidecars) {
                const folder = sidecar.previewId.replace(/[^a-zA-Z0-9._-]/g, '_');
                const dir = path.join(root, folder);
                fs.mkdirSync(dir, { recursive: true });
                const pngName = sidecar.pngPath ?? `${sidecar.id}.png`;
                fs.writeFileSync(path.join(dir, pngName), 'png-bytes');
                fs.writeFileSync(
                    path.join(dir, `${sidecar.id}.json`),
                    JSON.stringify({ ...sidecar, pngPath: pngName }),
                );
                indexLines.push(JSON.stringify(sidecar));
            }
            fs.writeFileSync(path.join(root, 'index.jsonl'), indexLines.join('\n') + '\n');
            return await fn(root);
        } finally {
            fs.rmSync(root, { recursive: true });
        }
    };
}

describe('HistoryReader', () => {
    const sidecars: SidecarFields[] = [
        {
            id: '20260430-101234-aaaaaaaa', previewId: 'com.example.A', timestamp: '2026-04-30T10:12:34Z',
            pngHash: 'aaa', trigger: 'fileChanged',
            source: { kind: 'fs', id: 'fs:/h' },
            git: { branch: 'main', commit: '6af6b8c1' },
            worktree: { path: '/work', agentId: null },
        },
        {
            id: '20260430-110000-bbbbbbbb', previewId: 'com.example.A', timestamp: '2026-04-30T11:00:00Z',
            pngHash: 'aaa', trigger: 'fileChanged',
            source: { kind: 'fs', id: 'fs:/h' },
            git: { branch: 'main', commit: '6af6b8c1' },
            worktree: { path: '/work' },
            deltaFromPrevious: { pngHashChanged: false },
        },
        {
            id: '20260430-120000-cccccccc', previewId: 'com.example.B', timestamp: '2026-04-30T12:00:00Z',
            pngHash: 'ccc', trigger: 'renderNow',
            source: { kind: 'git', id: 'git:preview/agent/foo' },
            git: { branch: 'agent/foo', commit: 'deadbeef' },
            worktree: { path: '/work', agentId: 'claude-code' },
        },
    ];

    it('exists() reports false when index.jsonl is missing', () => {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'history-empty-'));
        try {
            const reader = new HistoryReader(dir);
            assert.strictEqual(reader.exists(), false);
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    });

    it('lists entries newest-first', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        assert.strictEqual(reader.exists(), true);
        const result = reader.list();
        assert.strictEqual(result.totalCount, 3);
        const ids = result.entries.map(e => (e as { id: string }).id);
        assert.deepStrictEqual(ids, [
            '20260430-120000-cccccccc',
            '20260430-110000-bbbbbbbb',
            '20260430-101234-aaaaaaaa',
        ]);
    }));

    it('filters by previewId', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ previewId: 'com.example.A' });
        assert.strictEqual(r.totalCount, 2);
        for (const e of r.entries) {
            assert.strictEqual((e as { previewId: string }).previewId, 'com.example.A');
        }
    }));

    it('filters by branch', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ branch: 'agent/foo' });
        assert.strictEqual(r.totalCount, 1);
        assert.strictEqual((r.entries[0] as { id: string }).id, '20260430-120000-cccccccc');
    }));

    it('filters by branchPattern regex', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ branchPattern: '^agent/' });
        assert.strictEqual(r.totalCount, 1);
    }));

    it('treats invalid branchPattern regex as no-match (does not throw)', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ branchPattern: '[invalid' });
        assert.strictEqual(r.totalCount, 0);
    }));

    it('filters by sourceKind = fs', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ sourceKind: 'fs' });
        assert.strictEqual(r.totalCount, 2);
    }));

    it('filters by agentId', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ agentId: 'claude-code' });
        assert.strictEqual(r.totalCount, 1);
    }));

    it('filters by since lower-bound', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ since: '2026-04-30T11:00:00Z' });
        assert.strictEqual(r.totalCount, 2);
    }));

    it('filters by until upper-bound', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ until: '2026-04-30T11:00:00Z' });
        assert.strictEqual(r.totalCount, 2);
    }));

    it('honours commit prefix matching', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ commit: '6af6b8c' });
        assert.strictEqual(r.totalCount, 2);
    }));

    it('paginates via opaque cursor', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        const first = reader.list({ limit: 2 });
        assert.strictEqual(first.entries.length, 2);
        assert.ok(first.nextCursor);
        const second = reader.list({ limit: 2, cursor: first.nextCursor });
        assert.strictEqual(second.entries.length, 1);
        assert.strictEqual(second.nextCursor, undefined);
    }));

    it('clamps limit to MAX', withFixture(sidecars, (dir) => {
        const r = new HistoryReader(dir).list({ limit: 5_000_000 });
        assert.strictEqual(r.entries.length, 3); // bounded by content, not limit
    }));

    it('skips torn lines in index.jsonl per HISTORY.md § Concurrency', () => {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'history-torn-'));
        try {
            // Write a valid preview folder + sidecar so the reader can
            // resolve at least one entry.
            const folder = path.join(dir, 'X');
            fs.mkdirSync(folder);
            fs.writeFileSync(path.join(folder, 'a.json'), JSON.stringify({
                id: 'a', previewId: 'X', timestamp: 't', pngHash: 'h', pngPath: 'a.png',
            }));
            fs.writeFileSync(path.join(folder, 'a.png'), 'p');
            // Index has the good entry plus a half-line.
            fs.writeFileSync(path.join(dir, 'index.jsonl'),
                '{"id":"a","previewId":"X","timestamp":"t","pngHash":"h"}\n' +
                '{"id":"b","previewId":"X","timestamp":"t","pngHash":\n');
            const reader = new HistoryReader(dir);
            const r = reader.list();
            assert.strictEqual(r.totalCount, 1);
            assert.strictEqual((r.entries[0] as { id: string }).id, 'a');
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    });

    it('reads an entry with pngPath resolved against the sidecar dir', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        const result = reader.read('20260430-101234-aaaaaaaa');
        assert.notStrictEqual(result, null);
        assert.ok(fs.existsSync(result!.pngPath));
        assert.strictEqual((result!.entry as { id: string }).id, '20260430-101234-aaaaaaaa');
        assert.strictEqual(result!.pngBytes, undefined,
            'inline=false (default) omits pngBytes');
    }));

    it('inline=true returns base64 PNG bytes', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        const result = reader.read('20260430-101234-aaaaaaaa', true);
        assert.ok(result?.pngBytes);
        assert.strictEqual(Buffer.from(result!.pngBytes!, 'base64').toString('utf-8'), 'png-bytes');
    }));

    it('returns null for missing entry id', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        assert.strictEqual(reader.read('does-not-exist'), null);
    }));

    it('diff metadata-mode reports byte-identical when pngHash matches', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        const r = reader.diff('20260430-101234-aaaaaaaa', '20260430-110000-bbbbbbbb');
        assert.notStrictEqual(r, null);
        assert.strictEqual(r!.pngHashChanged, false);
    }));

    it('diff metadata-mode reports change when pngHash differs', withFixture(sidecars, (dir) => {
        // Same folder, different hashes — skip via cross-preview combo
        // since the fixture's same-preview pair has identical hashes.
        // We extend the fixture inline.
        const folder = path.join(dir, 'com.example.A');
        fs.writeFileSync(path.join(folder, 'changed.json'), JSON.stringify({
            id: 'changed', previewId: 'com.example.A', timestamp: 't', pngHash: 'zzz',
            pngPath: 'changed.png',
        }));
        fs.writeFileSync(path.join(folder, 'changed.png'), 'p');
        const reader = new HistoryReader(dir);
        const r = reader.diff('20260430-101234-aaaaaaaa', 'changed');
        assert.notStrictEqual(r, null);
        assert.strictEqual(r!.pngHashChanged, true);
    }));

    it('diff returns null for cross-preview entries (HistoryDiffMismatch on the wire)', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        // A vs B → different previewId. The daemon would surface -32011;
        // the FS reader returns null per its KDoc.
        assert.strictEqual(
            reader.diff('20260430-101234-aaaaaaaa', '20260430-120000-cccccccc'),
            null,
        );
    }));

    it('diff pixel-mode is daemon-only (FS reader returns null)', withFixture(sidecars, (dir) => {
        const reader = new HistoryReader(dir);
        assert.strictEqual(
            reader.diff('20260430-101234-aaaaaaaa', '20260430-110000-bbbbbbbb', 'pixel'),
            null,
        );
    }));
});
