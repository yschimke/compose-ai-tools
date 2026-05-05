// Pins the newest-wins / heartbeat / final-frame contract on the client side
// of the `composestream/1` protocol.
//
// The daemon-side equivalent is `daemon/core` `StreamRpcIntegrationTest` and
// `FrameStreamRegistryTest`; this file covers the *client* glitches that
// motivate the protocol — the queue behaviour the webview painter relies on
// to deliver buttery scroll/repaint.

import * as assert from "assert";
import {
    StreamClient,
    StreamFrameQueue,
    type PaintableFrame,
} from "../daemon/streamClient";
import type { StreamFrameParams } from "../daemon/daemonProtocol";

function frame(
    overrides: Partial<StreamFrameParams> & Pick<StreamFrameParams, "seq">,
): StreamFrameParams {
    return {
        frameStreamId: "fstream-1",
        ptsMillis: 0,
        widthPx: 1,
        heightPx: 1,
        codec: "png",
        keyframe: false,
        final: false,
        payloadBase64: "AAAA",
        ...overrides,
    };
}

describe("StreamFrameQueue", () => {
    it("submits and dispatches a single frame", () => {
        const q = new StreamFrameQueue();
        q.submit(frame({ seq: 1 }));
        const out = q.dispatch();
        assert.ok(out, "dispatch should yield the queued frame");
        assert.strictEqual(out!.seq, 1);
        assert.strictEqual(
            q.dispatch(),
            null,
            "queue must be empty after dispatch",
        );
    });

    it("drops the older frame when a newer one arrives before dispatch (newest-wins)", () => {
        const q = new StreamFrameQueue();
        q.submit(frame({ seq: 5, payloadBase64: "OLD" }));
        q.submit(frame({ seq: 6, payloadBase64: "NEW" }));
        q.submit(frame({ seq: 7, payloadBase64: "NEWEST" }));
        const out = q.dispatch()!;
        assert.strictEqual(
            out.payloadBase64,
            "NEWEST",
            "queue must hold only the newest pending frame",
        );
        assert.strictEqual(q.dispatch(), null);
    });

    it("drops a stale frame (seq <= last dispatched) on submit", () => {
        const q = new StreamFrameQueue();
        q.submit(frame({ seq: 5 }));
        q.dispatch(); // lastDispatchedSeq = 5
        q.submit(frame({ seq: 4 })); // stale — must be dropped
        q.submit(frame({ seq: 5 })); // duplicate of the dispatched one — drop too
        assert.strictEqual(q.dispatch(), null);
        q.submit(frame({ seq: 6 }));
        assert.strictEqual(q.dispatch()!.seq, 6);
    });

    it("ignores submits after a final frame is dispatched", () => {
        const q = new StreamFrameQueue();
        q.submit(
            frame({
                seq: 1,
                final: true,
                codec: undefined,
                payloadBase64: undefined,
            }),
        );
        const finalFrame = q.dispatch()!;
        assert.strictEqual(finalFrame.final, true);
        assert.strictEqual(q.isClosed(), true);
        q.submit(frame({ seq: 2 }));
        assert.strictEqual(
            q.dispatch(),
            null,
            "submit after final must be a no-op",
        );
    });

    it("queues a heartbeat with the same newest-wins semantics", () => {
        const q = new StreamFrameQueue();
        q.submit(
            frame({
                seq: 1,
                codec: undefined,
                payloadBase64: undefined,
            }),
        );
        const out = q.dispatch()!;
        assert.strictEqual(out.codec, undefined);
        assert.strictEqual(out.payloadBase64, undefined);
    });
});

describe("StreamClient — multi-stream demux", () => {
    it("routes frames to the right sink and tracks dispatch counts", () => {
        const client = new StreamClient();
        const a: PaintableFrame[] = [];
        const b: PaintableFrame[] = [];
        client.bind("stream-a", (f) => a.push(f));
        client.bind("stream-b", (f) => b.push(f));
        client.onFrame(
            frame({ frameStreamId: "stream-a", seq: 1, payloadBase64: "A1" }),
        );
        client.onFrame(
            frame({ frameStreamId: "stream-b", seq: 1, payloadBase64: "B1" }),
        );
        const dispatched = client.tick();
        assert.strictEqual(dispatched, 2);
        assert.strictEqual(a.length, 1);
        assert.strictEqual(b.length, 1);
        assert.strictEqual(a[0].payloadBase64, "A1");
        assert.strictEqual(b[0].payloadBase64, "B1");
    });

    it("collapses a burst into a single tick (newest-wins under load)", () => {
        const client = new StreamClient();
        const painted: PaintableFrame[] = [];
        client.bind("stream-a", (f) => painted.push(f));

        // Simulate a slider drag — five frames between paint ticks.
        for (let seq = 1; seq <= 5; seq++) {
            client.onFrame(
                frame({
                    frameStreamId: "stream-a",
                    seq,
                    payloadBase64: `frame-${seq}`,
                }),
            );
        }
        client.tick();

        assert.strictEqual(
            painted.length,
            1,
            "burst must collapse to one paint",
        );
        assert.strictEqual(painted[0].seq, 5);
        assert.strictEqual(painted[0].payloadBase64, "frame-5");
    });

    it("auto-unbinds the stream after a final frame is painted", () => {
        const client = new StreamClient();
        const painted: PaintableFrame[] = [];
        client.bind("stream-a", (f) => painted.push(f));
        client.onFrame(
            frame({
                frameStreamId: "stream-a",
                seq: 1,
                final: true,
                codec: undefined,
                payloadBase64: undefined,
            }),
        );
        client.tick();
        assert.strictEqual(painted.length, 1);
        assert.strictEqual(painted[0].final, true);

        // Subsequent frames on the same id must not reach the (now-unbound) sink.
        client.onFrame(frame({ frameStreamId: "stream-a", seq: 2 }));
        client.tick();
        assert.strictEqual(painted.length, 1);
    });

    it("queues frames that arrive before bind so the first paint isn't lost", () => {
        const client = new StreamClient();
        client.onFrame(frame({ frameStreamId: "stream-a", seq: 1 }));
        // Simulate the small race window between `stream/start` ack and `bind()`.
        const painted: PaintableFrame[] = [];
        client.bind("stream-a", (f) => painted.push(f));
        client.tick();
        assert.strictEqual(painted.length, 1);
    });

    it("isolates a throwing sink so other streams keep painting", () => {
        const client = new StreamClient();
        const painted: PaintableFrame[] = [];
        client.bind("stream-a", () => {
            throw new Error("intentional sink failure");
        });
        client.bind("stream-b", (f) => painted.push(f));
        // Suppress console.error noise from the isolated throw.
        const origError = console.error;
        console.error = () => {};
        try {
            client.onFrame(frame({ frameStreamId: "stream-a", seq: 1 }));
            client.onFrame(frame({ frameStreamId: "stream-b", seq: 1 }));
            client.tick();
        } finally {
            console.error = origError;
        }
        assert.strictEqual(painted.length, 1, "stream-b must still paint");
    });

    it("drops out-of-order frames once a higher seq is dispatched", () => {
        const client = new StreamClient();
        const painted: PaintableFrame[] = [];
        client.bind("stream-a", (f) => painted.push(f));
        client.onFrame(frame({ frameStreamId: "stream-a", seq: 5 }));
        client.tick();
        // A late frame from before — must be ignored.
        client.onFrame(frame({ frameStreamId: "stream-a", seq: 3 }));
        client.tick();
        assert.strictEqual(painted.length, 1);
        assert.strictEqual(painted[0].seq, 5);
    });
});
