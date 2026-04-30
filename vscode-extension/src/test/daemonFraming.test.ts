import * as assert from 'assert';
import { FrameDecoder, encodeFrame } from '../daemon/daemonFraming';

describe('daemon framing', () => {
    it('round-trips a single message', () => {
        const messages: string[] = [];
        const errors: Error[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: (err) => errors.push(err),
        });
        const payload = { jsonrpc: '2.0', method: 'hello', params: { x: 1 } };
        decoder.push(encodeFrame(payload));
        assert.strictEqual(errors.length, 0);
        assert.deepStrictEqual(JSON.parse(messages[0]), payload);
    });

    it('parses multiple messages in one chunk', () => {
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail('no error expected'),
        });
        const a = encodeFrame({ method: 'a' });
        const b = encodeFrame({ method: 'b' });
        const c = encodeFrame({ method: 'c' });
        decoder.push(Buffer.concat([a, b, c]));
        const methods = messages.map(m => JSON.parse(m).method);
        assert.deepStrictEqual(methods, ['a', 'b', 'c']);
    });

    it('reassembles a message split across chunks', () => {
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail('no error expected'),
        });
        const full = encodeFrame({ method: 'split', params: { value: 'abcdef' } });
        // Split mid-header, mid-body — both transition points.
        for (let i = 0; i < full.length; i += 5) {
            decoder.push(full.subarray(i, Math.min(i + 5, full.length)));
        }
        assert.strictEqual(messages.length, 1);
        assert.strictEqual(JSON.parse(messages[0]).method, 'split');
    });

    it('counts UTF-8 bytes (not characters) in Content-Length', () => {
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail('no error expected'),
        });
        // Multi-byte UTF-8 — emoji = 4 bytes, kanji = 3 bytes per character.
        const payload = { method: 'i18n', text: '日本語🎉' };
        decoder.push(encodeFrame(payload));
        assert.strictEqual(JSON.parse(messages[0]).text, '日本語🎉');
    });

    it('reports a clear error on malformed header', () => {
        const messages: string[] = [];
        const errors: Error[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: (err) => errors.push(err),
        });
        decoder.push(Buffer.from('No-Length: bogus\r\n\r\nbody', 'ascii'));
        assert.strictEqual(messages.length, 0);
        assert.strictEqual(errors.length, 1);
        assert.match(errors[0].message, /Content-Length/);
    });
});
