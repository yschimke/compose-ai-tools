import * as assert from 'assert';
import { CatFileBatchParser } from '../previewMainSource';

// Wire format: `<sha> <type> <size>\n<bytes>\n` for hits, `<rev> missing\n`
// for misses. Tests cover the common shapes plus a few hostile inputs
// that the diff feature might reasonably hit (binary payload with
// embedded newlines, chunked delivery, missing-then-hit interleaving).

function header(sha: string, type: string, size: number): Buffer {
    return Buffer.from(`${sha} ${type} ${size}\n`);
}

function missing(rev: string): Buffer {
    return Buffer.from(`${rev} missing\n`);
}

function hit(sha: string, type: string, body: Buffer): Buffer {
    return Buffer.concat([header(sha, type, body.length), body, Buffer.from('\n')]);
}

const SHA = 'a1b2c3d4e5f6789012345678901234567890abcd';

describe('CatFileBatchParser', () => {
    it('parses a single hit delivered as one chunk', () => {
        const parser = new CatFileBatchParser();
        const body = Buffer.from('hello world');
        const out = parser.feed(hit(SHA, 'blob', body));
        assert.strictEqual(out.length, 1);
        assert.deepStrictEqual(out[0], body);
    });

    it('parses a missing response', () => {
        const parser = new CatFileBatchParser();
        const out = parser.feed(missing('origin/compose-preview/main:no/such/path'));
        assert.deepStrictEqual(out, [null]);
    });

    it('preserves bodies that contain newlines', () => {
        // PNG / JSON with embedded newlines exercises the byte-counted
        // body parser. A naive line-based reader would split this.
        const parser = new CatFileBatchParser();
        const body = Buffer.from('line one\nline two\n\nline four');
        const out = parser.feed(hit(SHA, 'blob', body));
        assert.strictEqual(out.length, 1);
        assert.deepStrictEqual(out[0], body);
    });

    it('reassembles a response split across multiple chunks', () => {
        const parser = new CatFileBatchParser();
        const body = Buffer.from('xy'.repeat(50));
        const full = hit(SHA, 'blob', body);
        // Feed one byte at a time — the parser must not emit until the
        // whole response (header + body + trailing newline) is in.
        let collected: Array<Buffer | null> = [];
        for (let i = 0; i < full.length; i++) {
            collected = collected.concat(parser.feed(full.slice(i, i + 1)));
        }
        assert.strictEqual(collected.length, 1);
        assert.deepStrictEqual(collected[0], body);
    });

    it('emits multiple responses from one chunk', () => {
        const parser = new CatFileBatchParser();
        const a = Buffer.from('alpha');
        const b = Buffer.from('beta');
        const combined = Buffer.concat([
            hit(SHA, 'blob', a),
            missing('foo'),
            hit(SHA, 'blob', b),
        ]);
        const out = parser.feed(combined);
        assert.strictEqual(out.length, 3);
        assert.deepStrictEqual(out[0], a);
        assert.strictEqual(out[1], null);
        assert.deepStrictEqual(out[2], b);
    });

    it('handles a zero-byte blob (empty file checked into git)', () => {
        const parser = new CatFileBatchParser();
        const body = Buffer.alloc(0);
        const out = parser.feed(hit(SHA, 'blob', body));
        assert.strictEqual(out.length, 1);
        assert.deepStrictEqual(out[0], body);
    });

    it('recovers when feed contains a malformed header', () => {
        const parser = new CatFileBatchParser();
        const malformed = Buffer.from('garbage that pretends to be a header\n');
        const good = hit(SHA, 'blob', Buffer.from('ok'));
        const out = parser.feed(Buffer.concat([malformed, good]));
        assert.strictEqual(out.length, 2);
        assert.strictEqual(out[0], null);
        assert.deepStrictEqual(out[1], Buffer.from('ok'));
    });

    it('reset() drops mid-message state', () => {
        const parser = new CatFileBatchParser();
        // Feed only the header — body is incomplete.
        parser.feed(header(SHA, 'blob', 10));
        parser.reset();
        // Subsequent feed of a fresh, well-formed message should parse.
        const body = Buffer.from('reset-ok');
        const out = parser.feed(hit(SHA, 'blob', body));
        assert.strictEqual(out.length, 1);
        assert.deepStrictEqual(out[0], body);
    });
});
