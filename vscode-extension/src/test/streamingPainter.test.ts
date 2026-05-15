// Tests for the streaming-painter console-noise guards added in
// the #1125 fix. Two paths matter:
//
//   1. Zero-byte `payloadBase64` short-circuits before
//      `createImageBitmap` is even called — historically the
//      decoder rejected with `InvalidStateError` and logged at
//      `console.error`, which read like a real failure.
//   2. A `createImageBitmap` rejection that arrives *after* the
//      entry was detached downgrades to `console.debug` — the
//      detach-mid-decode race is expected when the user
//      navigates away while a stream is in flight.
//
// `paint()` is private and the `attach()` path needs a real
// CanvasRenderingContext2D that happy-dom doesn't provide, so the
// tests reach for the painter's `entries` map directly and inject a
// stub entry — same trick we use elsewhere when testing private
// helpers behind class boundaries.

import * as assert from "assert";
import { StreamingPainter } from "../webview/preview/streamingPainter";

interface CapturedConsole {
    errors: unknown[][];
    debugs: unknown[][];
    restore: () => void;
}

function captureConsole(): CapturedConsole {
    const errors: unknown[][] = [];
    const debugs: unknown[][] = [];
    const origError = console.error;
    const origDebug = console.debug;
    console.error = (...args: unknown[]) => errors.push(args);
    console.debug = (...args: unknown[]) => debugs.push(args);
    return {
        errors,
        debugs,
        restore() {
            console.error = origError;
            console.debug = origDebug;
        },
    };
}

interface PainterInternals {
    entries: Map<string, object>;
    paint: (e: object, f: Record<string, unknown>) => void;
}

function asInternals(painter: StreamingPainter): PainterInternals {
    return painter as unknown as PainterInternals;
}

function stubEntry(): object {
    // The painter's `paint()` only touches the entry fields the
    // success path needs (canvas, ctx, anchor, paintedSeq). The
    // tests below never reach the success path — they exercise the
    // zero-byte guard and the rejection handler — so a minimal stub
    // is enough.
    return {
        canvas: { width: 0, height: 0 },
        ctx: {} as unknown,
        anchor: null,
        pendingFinal: false,
        paintedSeq: -1,
    };
}

function stubCreateImageBitmap(
    impl: (...args: unknown[]) => unknown,
): () => void {
    const orig = (globalThis as unknown as { createImageBitmap?: unknown })
        .createImageBitmap;
    (
        globalThis as unknown as { createImageBitmap: unknown }
    ).createImageBitmap = impl;
    return () => {
        if (orig === undefined) {
            delete (globalThis as unknown as { createImageBitmap?: unknown })
                .createImageBitmap;
        } else {
            (
                globalThis as unknown as { createImageBitmap: unknown }
            ).createImageBitmap = orig;
        }
    };
}

describe("StreamingPainter — paint() console-noise guards", () => {
    it("skips zero-byte payloads without calling createImageBitmap", () => {
        const cap = captureConsole();
        let createCalls = 0;
        const restoreCreate = stubCreateImageBitmap(() => {
            createCalls += 1;
            return Promise.reject(new Error("should not have been called"));
        });
        try {
            const painter = new StreamingPainter();
            const frameStreamId = "stream-zero";
            const entry = stubEntry();
            asInternals(painter).entries.set(frameStreamId, entry);
            asInternals(painter).paint(entry, {
                frameStreamId,
                seq: 0,
                ptsMillis: 0,
                widthPx: 100,
                heightPx: 100,
                codec: "png",
                keyframe: true,
                final: false,
                payloadBase64: "",
            });
            assert.strictEqual(
                createCalls,
                0,
                "createImageBitmap must not be called for zero-byte payloads",
            );
            assert.strictEqual(
                cap.errors.length,
                0,
                `expected no console.error, got ${JSON.stringify(cap.errors)}`,
            );
            assert.strictEqual(cap.debugs.length, 1);
            assert.ok(
                String(cap.debugs[0][0]).includes("zero-byte payload"),
                "debug message must name the zero-byte case",
            );
        } finally {
            restoreCreate();
            cap.restore();
        }
    });

    it("downgrades createImageBitmap rejections to debug after the entry is detached", async () => {
        const cap = captureConsole();
        let rejectFn: ((err: Error) => void) | null = null;
        const restoreCreate = stubCreateImageBitmap(
            () =>
                new Promise((_resolve, rej) => {
                    rejectFn = rej as (err: Error) => void;
                }),
        );
        try {
            const painter = new StreamingPainter();
            const frameStreamId = "stream-detach";
            const entry = stubEntry();
            asInternals(painter).entries.set(frameStreamId, entry);
            asInternals(painter).paint(entry, {
                frameStreamId,
                seq: 0,
                ptsMillis: 0,
                widthPx: 100,
                heightPx: 100,
                codec: "png",
                keyframe: true,
                final: false,
                payloadBase64: "abc=",
            });
            // Simulate detach mid-decode — production triggers this
            // when `streamStopped` arrives between dispatching the
            // frame and the decoder resolving.
            asInternals(painter).entries.delete(frameStreamId);
            assert.ok(rejectFn, "createImageBitmap should have been invoked");
            (rejectFn as (err: Error) => void)(
                new Error("The source image could not be decoded."),
            );
            await Promise.resolve();
            await Promise.resolve();
            assert.strictEqual(
                cap.errors.length,
                0,
                `expected no console.error after detach race, got ${JSON.stringify(cap.errors)}`,
            );
            assert.ok(
                cap.debugs.some((args) =>
                    String(args[0]).includes("decode rejected after detach"),
                ),
                `debug log must name the detach race; saw ${JSON.stringify(cap.debugs)}`,
            );
        } finally {
            restoreCreate();
            cap.restore();
        }
    });

    it("still logs error when the rejection arrives while the entry is live", async () => {
        const cap = captureConsole();
        let rejectFn: ((err: Error) => void) | null = null;
        const restoreCreate = stubCreateImageBitmap(
            () =>
                new Promise((_resolve, rej) => {
                    rejectFn = rej as (err: Error) => void;
                }),
        );
        try {
            const painter = new StreamingPainter();
            const frameStreamId = "stream-live";
            const entry = stubEntry();
            asInternals(painter).entries.set(frameStreamId, entry);
            asInternals(painter).paint(entry, {
                frameStreamId,
                seq: 0,
                ptsMillis: 0,
                widthPx: 100,
                heightPx: 100,
                codec: "png",
                keyframe: true,
                final: false,
                payloadBase64: "abc=",
            });
            assert.ok(rejectFn, "createImageBitmap should have been invoked");
            // Entry stays live — this is the genuine "bytes weren't
            // decodable" case the user can act on. Keep the error log.
            (rejectFn as (err: Error) => void)(
                new Error("The source image could not be decoded."),
            );
            await Promise.resolve();
            await Promise.resolve();
            assert.strictEqual(
                cap.errors.length,
                1,
                `expected one console.error, got ${JSON.stringify(cap.errors)}`,
            );
            assert.ok(
                String(cap.errors[0][0]).includes("createImageBitmap failed"),
            );
        } finally {
            restoreCreate();
            cap.restore();
        }
    });
});
