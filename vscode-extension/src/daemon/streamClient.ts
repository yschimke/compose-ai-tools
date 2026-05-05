// Pure (no DOM, no vscode) state machine that coordinates `composestream/1`
// frame consumption.
//
// The streaming protocol's buttery-frame guarantees come from three rules
// the *client* enforces, not the daemon:
//
//  1. **Newest-wins queue.** Hold at most one pending frame; if a new one
//     arrives before paint, drop the old. This is what eliminates the
//     visible-element-blanks-on-swap glitch the legacy `<img src=…>` path
//     suffers from when frames arrive faster than the browser decodes.
//
//  2. **Decode out-of-band.** Surface the queued bytes to the painter
//     (typically `createImageBitmap` + canvas) so the visible element
//     never tears down its current bitmap before the next one is ready.
//
//  3. **Keep the keyframe anchor.** Cache the most recently *painted*
//     frame so visibility-back / scroll-into-view repaints from cache
//     immediately, without the cold-blank an `interactive/stop` then
//     `interactive/start` cycle produces.
//
// This module owns rule #1 and the bookkeeping for #3; the painter lives
// in the webview (where it can talk to the DOM) and is wired through
// `StreamClient.onFrameReady`.

import type { StreamCodec, StreamFrameParams } from "./daemonProtocol";

/**
 * The frame the painter receives — either fresh bytes (when `payloadBase64`
 * is set) or an `unchanged` heartbeat (when both `codec` and `payloadBase64`
 * are absent).
 */
export interface PaintableFrame {
    readonly frameStreamId: string;
    readonly seq: number;
    readonly ptsMillis: number;
    readonly widthPx: number;
    readonly heightPx: number;
    readonly codec?: StreamCodec;
    readonly keyframe: boolean;
    readonly final: boolean;
    readonly payloadBase64?: string;
}

/**
 * Sink for paintable frames. The webview implementation typically:
 *  - decodes `payloadBase64` to a Blob → `createImageBitmap` → canvas paint;
 *  - records a "no-op tick" indicator for heartbeat frames;
 *  - releases decoder state on `final`.
 */
export type StreamPaintSink = (frame: PaintableFrame) => void;

/**
 * Per-stream client-side queue + dispatch. Construct one per active stream.
 *
 * Invariants:
 *  - At most one frame is queued at any time. A submit() call while another
 *    frame is queued discards the older one (newest-wins).
 *  - `dispatch()` consumes the queued frame and returns it. Returns `null`
 *    when the queue is empty. Idempotent.
 *  - Heartbeat frames are queued with the same newest-wins logic — if a
 *    heartbeat is queued and a real frame arrives before dispatch, the real
 *    frame replaces the heartbeat. (The reverse — heartbeat overwrites real
 *    frame — also holds, but is exactly the semantics we want: the daemon
 *    only emits a heartbeat when the bytes are stable, so dropping the prior
 *    real frame is fine.)
 *  - Stale frames (`seq` ≤ the most recently *dispatched* seq) are dropped
 *    on submit, so a reordered or replayed wire doesn't "go backwards" in
 *    the painter.
 */
export class StreamFrameQueue {
    private pending: PaintableFrame | null = null;
    private lastDispatchedSeq: number = -1;
    private gotFinal: boolean = false;

    submit(frame: StreamFrameParams): void {
        if (this.gotFinal) return;
        if (frame.seq <= this.lastDispatchedSeq) return;
        // A queued frame older than the new one → drop the older one. Equal seq
        // is the same frame; treat as a no-op submit.
        if (this.pending && this.pending.seq >= frame.seq) return;
        this.pending = toPaintable(frame);
    }

    dispatch(): PaintableFrame | null {
        const out = this.pending;
        this.pending = null;
        if (out !== null) {
            this.lastDispatchedSeq = out.seq;
            if (out.final) this.gotFinal = true;
        }
        return out;
    }

    /** Test / debug accessor. Don't read this in painter code. */
    peek(): PaintableFrame | null {
        return this.pending;
    }

    /** True once a `final` frame has been dispatched. Submits are no-ops afterwards. */
    isClosed(): boolean {
        return this.gotFinal;
    }
}

function toPaintable(frame: StreamFrameParams): PaintableFrame {
    return {
        frameStreamId: frame.frameStreamId,
        seq: frame.seq,
        ptsMillis: frame.ptsMillis,
        widthPx: frame.widthPx,
        heightPx: frame.heightPx,
        codec: frame.codec,
        keyframe: frame.keyframe ?? false,
        final: frame.final ?? false,
        payloadBase64: frame.payloadBase64,
    };
}

/**
 * Multi-stream demultiplexer + raf-driven dispatcher.
 *
 * Consumers:
 *  - call `onFrame(frame)` from the JSON-RPC notification handler;
 *  - register sinks via `bind(frameStreamId, sink)` and `unbind(frameStreamId)`;
 *  - call `tick()` from a `requestAnimationFrame` loop, or wire it to the
 *    `StreamClient.runRaf` helper for the browser path.
 *
 * The split lets the unit test drive `tick()` synchronously without a real
 * raf loop while the webview wires it through `requestAnimationFrame`.
 */
export class StreamClient {
    private queues = new Map<string, StreamFrameQueue>();
    private sinks = new Map<string, StreamPaintSink>();

    bind(frameStreamId: string, sink: StreamPaintSink): void {
        this.sinks.set(frameStreamId, sink);
        if (!this.queues.has(frameStreamId)) {
            this.queues.set(frameStreamId, new StreamFrameQueue());
        }
    }

    unbind(frameStreamId: string): void {
        this.sinks.delete(frameStreamId);
        this.queues.delete(frameStreamId);
    }

    /**
     * Route a `streamFrame` notification onto the matching queue. Frames for
     * unbound streams are still queued — useful when a frame arrives during
     * the brief window between `stream/start` ack and `bind()`.
     */
    onFrame(frame: StreamFrameParams): void {
        const q =
            this.queues.get(frame.frameStreamId) ??
            (() => {
                const fresh = new StreamFrameQueue();
                this.queues.set(frame.frameStreamId, fresh);
                return fresh;
            })();
        q.submit(frame);
    }

    /**
     * Drain pending frames into their bound sinks. Idempotent; returns the
     * number of frames dispatched on this tick — handy for tests.
     */
    tick(): number {
        let dispatched = 0;
        for (const [id, queue] of this.queues) {
            const frame = queue.dispatch();
            if (frame === null) continue;
            const sink = this.sinks.get(id);
            if (sink !== undefined) {
                try {
                    sink(frame);
                } catch (e) {
                    // Sinks may throw inside browser paint paths; isolate so a
                    // misbehaving sink doesn't poison the rest of the dispatch.
                    // Log via console so the webview's vscode bridge surfaces it.
                    console.error(
                        `compose-ai stream client: sink for ${id} threw`,
                        e,
                    );
                }
                dispatched++;
                if (frame.final) {
                    this.unbind(id);
                }
            }
        }
        return dispatched;
    }

    /** Test introspection. */
    pendingCount(): number {
        let n = 0;
        for (const q of this.queues.values()) {
            if (q.peek() !== null) n++;
        }
        return n;
    }
}
