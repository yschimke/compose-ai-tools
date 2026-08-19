// Which live frame reaches the canvas, and in what order.

import assert from "node:assert/strict";
import {
    FrameQueue,
    frameBlob,
    shouldPaintDecodedFrame,
    type ServeFrame,
} from "../src/live/framePainter.js";

function frame(seq: number, payload = "AAAA"): ServeFrame {
    return { seq, codec: "png", dataBase64: payload };
}

describe("FrameQueue", () => {
    it("holds the NEWEST frame when several arrive before a paint", () => {
        // The live lane's whole reason for existing: a frame the daemon has already superseded is
        // not worth a decode, and painting it would show the visitor a state that has passed.
        const q = new FrameQueue();
        q.submit(frame(1));
        q.submit(frame(2));
        q.submit(frame(3));
        assert.equal(q.peek()?.seq, 3);
        assert.equal(q.dispatch()?.seq, 3);
        assert.equal(q.dispatch(), null);
    });

    it("drops a frame that arrives out of order behind a newer one", () => {
        const q = new FrameQueue();
        q.submit(frame(5));
        q.submit(frame(4));
        assert.equal(q.peek()?.seq, 5);
    });

    it("never walks backwards after a dispatch", () => {
        // The regression this class exists for (#4285): a late frame N painting over N+1 leaves a
        // stale image on screen until the *next* frame, which on the live lane's tick is 250ms of
        // visible time-travel — and if the stream has settled, forever.
        const q = new FrameQueue();
        q.submit(frame(7));
        assert.equal(q.dispatch()?.seq, 7);
        q.submit(frame(6));
        q.submit(frame(7));
        assert.equal(q.dispatch(), null);
        q.submit(frame(8));
        assert.equal(q.dispatch()?.seq, 8);
    });

    it("ignores a frame with no usable seq rather than poisoning the floor", () => {
        // A malformed message must not be able to wedge the lane by parking a NaN floor that every
        // subsequent comparison fails against.
        const q = new FrameQueue();
        q.submit({ seq: Number.NaN } as ServeFrame);
        q.submit({} as unknown as ServeFrame);
        assert.equal(q.peek(), null);
        q.submit(frame(1));
        assert.equal(q.dispatch()?.seq, 1);
    });

    it("queues heartbeats under the same rule as painted frames", () => {
        // A heartbeat carries no payload — the daemon saying the pixels are unchanged. It still
        // advances `seq`, so it has to take part in the ordering or it would strand the floor.
        const q = new FrameQueue();
        q.submit({ seq: 1 });
        assert.equal(q.dispatch()?.seq, 1);
        q.submit(frame(2));
        assert.equal(q.dispatch()?.seq, 2);
    });
});

describe("shouldPaintDecodedFrame", () => {
    it("paints only strictly newer decodes", () => {
        // The second watermark. The queue orders what goes INTO the decoder; `createImageBitmap`
        // resolves out of order under load, so a heavier frame N can come back after a lighter
        // N+1 and would otherwise paint over it.
        assert.equal(shouldPaintDecodedFrame(-1, 0), true);
        assert.equal(shouldPaintDecodedFrame(4, 5), true);
        assert.equal(shouldPaintDecodedFrame(5, 5), false);
        assert.equal(shouldPaintDecodedFrame(5, 4), false);
    });
});

describe("frameBlob", () => {
    it("types the blob from the frame's own codec, not the request's", () => {
        // The daemon downgrades a WebP request to PNG when it has no encoder and reports what it
        // actually sent; decoding off the request would then hand the browser a mislabelled blob.
        assert.equal(frameBlob(frame(1))?.type, "image/png");
        assert.equal(
            frameBlob({ seq: 1, codec: "webp", dataBase64: "AAAA" })?.type,
            "image/webp",
        );
        assert.equal(
            frameBlob({ seq: 1, dataBase64: "AAAA" })?.type,
            "image/png",
        );
    });

    it("returns null for a heartbeat so the painter skips the decode", () => {
        assert.equal(frameBlob({ seq: 1 }), null);
        assert.equal(frameBlob({ seq: 1, dataBase64: "" }), null);
    });

    it("decodes base64 to the original bytes", async () => {
        const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x00, 0xff]);
        const b64 = Buffer.from(bytes).toString("base64");
        const blob = frameBlob({ seq: 1, dataBase64: b64 });
        assert.ok(blob);
        assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), bytes);
    });
});
