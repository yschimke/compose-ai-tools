// Coverage for the pure helpers in `bundleDaemonHost` that don't need
// VS Code or a live JVM. The `spawnBundleDaemon` entry point is exercised
// end-to-end via the electron integration suite (it shells out to the
// real CLI subcommand); here we pin the small wire-mapping helpers that
// the panel relies on.

import * as assert from "assert";
import { Readable, Writable } from "stream";

import {
    A11Y_DATA_KINDS,
    renderAll,
    setKindsSubscribed,
} from "../bundleDaemonHost";
import { DaemonClient } from "../daemon/daemonClient";

/** Build a `DaemonClient` whose request channel is captured to a list,
 *  with canned per-method responses. Avoids reaching for jest-style
 *  spies — we only need to assert call order + reject-recovery. */
function buildClient(responses: Record<string, unknown[]>): {
    client: DaemonClient;
    requests: Array<{ method: string; params: unknown }>;
} {
    const requests: Array<{ method: string; params: unknown }> = [];
    const pendingByMethod = new Map<string, unknown[]>();
    for (const [method, queue] of Object.entries(responses)) {
        pendingByMethod.set(method, [...queue]);
    }
    let nextId = 0;
    const stdout = new Readable({ read() {} });
    const stdin = new Writable({
        write(chunk: Buffer, _enc, cb) {
            // Strip LSP framing: split on `\r\n\r\n` once and decode the body
            // as JSON.
            const text = chunk.toString("utf-8");
            const sep = text.indexOf("\r\n\r\n");
            const body = sep >= 0 ? text.slice(sep + 4) : text;
            let parsed: { id?: number; method?: string; params?: unknown };
            try {
                parsed = JSON.parse(body);
            } catch {
                cb();
                return;
            }
            if (parsed.method) {
                requests.push({
                    method: parsed.method,
                    params: parsed.params,
                });
                if (parsed.id !== undefined) {
                    nextId = parsed.id;
                    const queue = pendingByMethod.get(parsed.method) ?? [];
                    const result = queue.shift();
                    const payload =
                        result instanceof Error
                            ? {
                                  jsonrpc: "2.0",
                                  id: nextId,
                                  error: {
                                      code: -32000,
                                      message: result.message,
                                  },
                              }
                            : { jsonrpc: "2.0", id: nextId, result };
                    const json = JSON.stringify(payload);
                    const frame =
                        `Content-Length: ${Buffer.byteLength(json)}\r\n\r\n` +
                        json;
                    queueMicrotask(() => stdout.push(frame));
                }
            }
            cb();
        },
    });
    const client = new DaemonClient(stdin, stdout, {});
    return { client, requests };
}

describe("renderAll", () => {
    it("issues a single renderNow with tier=full for every preview id", async () => {
        const { client, requests } = buildClient({
            renderNow: [{ queued: ["a", "b"], rejected: [] }],
        });
        await renderAll(client, ["a", "b"], "test");
        assert.strictEqual(requests.length, 1);
        assert.strictEqual(requests[0].method, "renderNow");
        assert.deepStrictEqual(requests[0].params, {
            previews: ["a", "b"],
            tier: "full",
            reason: "test",
        });
    });

    it("no-ops on an empty preview list (no RPC traffic)", async () => {
        const { client, requests } = buildClient({});
        await renderAll(client, [], "test");
        assert.strictEqual(requests.length, 0);
    });
});

describe("setKindsSubscribed", () => {
    it("subscribes each kind via data/subscribe in parallel", async () => {
        const { client, requests } = buildClient({
            "data/subscribe": [{ ok: true }, { ok: true }],
        });
        await setKindsSubscribed(
            client,
            "pv",
            ["a11y/atf", "a11y/hierarchy"],
            true,
        );
        assert.strictEqual(requests.length, 2);
        for (const r of requests) {
            assert.strictEqual(r.method, "data/subscribe");
            assert.strictEqual(
                (r.params as { previewId: string }).previewId,
                "pv",
            );
        }
        assert.deepStrictEqual(
            requests.map((r) => (r.params as { kind: string }).kind).sort(),
            ["a11y/atf", "a11y/hierarchy"],
        );
    });

    it("unsubscribes via data/unsubscribe when enabled=false", async () => {
        const { client, requests } = buildClient({
            "data/unsubscribe": [{ ok: true }],
        });
        await setKindsSubscribed(client, "pv", ["theming/main"], false);
        assert.strictEqual(requests[0].method, "data/unsubscribe");
    });

    it("surfaces per-kind rejections via the error callback without rejecting the whole call", async () => {
        const errors: Array<[string, string]> = [];
        const { client } = buildClient({
            "data/subscribe": [new Error("DataProductUnknown"), { ok: true }],
        });
        await setKindsSubscribed(
            client,
            "pv",
            ["unknown/kind", "a11y/atf"],
            true,
            (kind, err) => errors.push([kind, err.message]),
        );
        // Order of resolution races since we await Promise.all, but at
        // least one error must surface and the call itself completes.
        assert.strictEqual(errors.length, 1);
        assert.match(errors[0][1], /DataProductUnknown/);
    });
});

describe("A11Y_DATA_KINDS", () => {
    it("covers the a11y/atf + a11y/hierarchy pair the focus overlay needs", () => {
        // The sidebar's `setA11yOverlay` handler subscribes the same two
        // kinds; this assertion pins the bundle viewer to the same set
        // so a future split (e.g. introducing `a11y/atf/v2`) is
        // explicit.
        assert.deepStrictEqual(A11Y_DATA_KINDS, ["a11y/atf", "a11y/hierarchy"]);
    });
});
