import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import { FrameDecoder, encodeFrame } from "../daemon/daemonFraming";

describe("daemon framing", () => {
    it("round-trips a single message", () => {
        const messages: string[] = [];
        const errors: Error[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: (err) => errors.push(err),
        });
        const payload = { jsonrpc: "2.0", method: "hello", params: { x: 1 } };
        decoder.push(encodeFrame(payload));
        assert.strictEqual(errors.length, 0);
        assert.deepStrictEqual(JSON.parse(messages[0]), payload);
    });

    it("parses multiple messages in one chunk", () => {
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        const a = encodeFrame({ method: "a" });
        const b = encodeFrame({ method: "b" });
        const c = encodeFrame({ method: "c" });
        decoder.push(Buffer.concat([a, b, c]));
        const methods = messages.map((m) => JSON.parse(m).method);
        assert.deepStrictEqual(methods, ["a", "b", "c"]);
    });

    it("reassembles a message split across chunks", () => {
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        const full = encodeFrame({
            method: "split",
            params: { value: "abcdef" },
        });
        // Split mid-header, mid-body — both transition points.
        for (let i = 0; i < full.length; i += 5) {
            decoder.push(full.subarray(i, Math.min(i + 5, full.length)));
        }
        assert.strictEqual(messages.length, 1);
        assert.strictEqual(JSON.parse(messages[0]).method, "split");
    });

    it("counts UTF-8 bytes (not characters) in Content-Length", () => {
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        // Multi-byte UTF-8 — emoji = 4 bytes, kanji = 3 bytes per character.
        const payload = { method: "i18n", text: "日本語🎉" };
        decoder.push(encodeFrame(payload));
        assert.strictEqual(JSON.parse(messages[0]).text, "日本語🎉");
    });

    it("reports a clear error on malformed header", () => {
        const messages: string[] = [];
        const errors: Error[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: (err) => errors.push(err),
        });
        decoder.push(Buffer.from("No-Length: bogus\r\n\r\nbody", "ascii"));
        assert.strictEqual(messages.length, 0);
        assert.strictEqual(errors.length, 1);
        assert.match(errors[0].message, /Content-Length/);
    });

    it("handles a body that contains the header terminator literally", () => {
        // The header terminator is `\r\n\r\n`. If a JSON body contains the
        // same byte sequence (e.g. inside a string) the decoder MUST NOT
        // treat it as the start of the next frame — Content-Length is the
        // only authority for body boundaries.
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        const payload = { method: "embed", text: "line1\r\n\r\nline2" };
        decoder.push(encodeFrame(payload));
        assert.strictEqual(messages.length, 1);
        assert.deepStrictEqual(JSON.parse(messages[0]), payload);
    });

    it("accepts case-insensitive Content-Length header", () => {
        // LSP framing canonicalises the header name as `Content-Length`, but
        // RFC 7230 § 3.2 says HTTP headers are case-insensitive. Tolerate
        // a daemon that emits it as `content-length:`.
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        const body = Buffer.from('{"method":"x"}', "utf-8");
        const header = Buffer.from(
            `content-length: ${body.length}\r\n\r\n`,
            "ascii",
        );
        decoder.push(Buffer.concat([header, body]));
        assert.strictEqual(JSON.parse(messages[0]).method, "x");
    });

    it("ignores an optional Content-Type header alongside Content-Length", () => {
        // PROTOCOL.md § 1: Content-Type is optional and ignored by the reader.
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        const body = Buffer.from('{"method":"y"}', "utf-8");
        const header = Buffer.from(
            `Content-Length: ${body.length}\r\n` +
                `Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n`,
            "ascii",
        );
        decoder.push(Buffer.concat([header, body]));
        assert.strictEqual(JSON.parse(messages[0]).method, "y");
    });

    it("round-trips every shipped protocol fixture", () => {
        // Encode each fixture, push the encoded bytes through the decoder,
        // assert the decoded JSON matches. Catches regressions in encode
        // (length miscounts) and decode (boundary mishandling) against the
        // exact shapes the daemon uses on the wire.
        const fixturesDir = path.resolve(
            __dirname,
            "..",
            "..",
            "..",
            "docs",
            "daemon",
            "protocol-fixtures",
        );
        const files = fs
            .readdirSync(fixturesDir)
            .filter((f) => f.endsWith(".json") && !f.startsWith("README"));
        assert.ok(files.length > 0, "no fixtures found");

        for (const file of files) {
            const original = JSON.parse(
                fs.readFileSync(path.join(fixturesDir, file), "utf-8"),
            );
            const messages: string[] = [];
            const decoder = new FrameDecoder({
                onMessage: (json) => messages.push(json),
                onError: () => assert.fail(`framing error on fixture ${file}`),
            });
            decoder.push(encodeFrame(original));
            assert.strictEqual(
                messages.length,
                1,
                `fixture ${file} did not round-trip`,
            );
            assert.deepStrictEqual(
                JSON.parse(messages[0]),
                original,
                `fixture ${file} mutated`,
            );
        }
    });

    it("handles an empty-object payload (Content-Length: 2)", () => {
        // The `daemonReady` notification carries `{}` as params — minimum
        // legal body length. Make sure the decoder doesn't get stuck on
        // sub-byte tracking.
        const messages: string[] = [];
        const decoder = new FrameDecoder({
            onMessage: (json) => messages.push(json),
            onError: () => assert.fail("no error expected"),
        });
        decoder.push(encodeFrame({}));
        assert.strictEqual(messages.length, 1);
        assert.deepStrictEqual(JSON.parse(messages[0]), {});
    });
});
