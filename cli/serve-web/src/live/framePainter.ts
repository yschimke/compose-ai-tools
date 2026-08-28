// Which live frame reaches the canvas, and in what order.
//
// Both serve lanes — the viewer's stage and the grid's `<cp-catalog-live>` cards — used to paint
// every frame the socket delivered by building `new Image()` from a `data:` URL and drawing it in
// `onload`. That has no ordering guarantee at all. Decode time varies with frame content, so a
// heavier frame N can still be decoding when a lighter N+1 resolves, and the late N then paints
// *over* N+1 and stays there until the next frame arrives — which on the live lane's 250 ms tick
// can be a quarter of a second of visible time-travel. A mid-animation frame stranded that way is
// one of the two mechanisms behind the "ripple artifacts" of issue #4159 (the other is the tick
// itself sampling the animation at most once, issue #4283).
//
// The daemon's own protocol already carries what is needed to stop this: `seq` is monotonic per
// stream. The VS Code client has enforced these rules since `composestream/1` shipped
// (the extension's [`src/daemon/streamClient.ts`](https://github.com/yschimke/compose-preview-vscode/blob/main/src/daemon/streamClient.ts), and `docs/daemon/STREAMING.md` § "Client model");
// this is the same discipline for the serve wire, whose frame envelope is `ServeStreamProtocol`'s
// `{type:"frame", seq, codec, dataBase64}` rather than the daemon's `streamFrame`.
//
// Pure — no DOM, no sockets — so the ordering rules are unit-tested directly and the two callers
// share one implementation instead of drifting apart.

/** One `type: "frame"` message off the serve socket. */
export interface ServeFrame {
    seq: number;
    codec?: string;
    widthPx?: number;
    heightPx?: number;
    dataBase64?: string;
}

/**
 * Newest-wins queue for one socket.
 *
 * Holds at most one frame. A frame arriving while another is queued replaces it: the older one was
 * never painted and never will be, and painting a frame the daemon has already superseded costs a
 * decode to show something stale. Frames at or below the last dispatched `seq` are dropped
 * outright, so a reordered or replayed wire cannot walk the stage backwards.
 */
export class FrameQueue {
    private pending: ServeFrame | null = null;
    private lastDispatchedSeq = -1;

    /** Queue a frame, unless it is stale or an older one is already newer. */
    submit(frame: ServeFrame): void {
        if (!isFiniteSeq(frame.seq)) return;
        if (frame.seq <= this.lastDispatchedSeq) return;
        if (this.pending && this.pending.seq >= frame.seq) return;
        this.pending = frame;
    }

    /** Take the queued frame, if any, and record its `seq` as the new floor. */
    dispatch(): ServeFrame | null {
        const out = this.pending;
        this.pending = null;
        if (out !== null) this.lastDispatchedSeq = out.seq;
        return out;
    }

    /** Test / debug accessor. Painter code should call [dispatch]. */
    peek(): ServeFrame | null {
        return this.pending;
    }
}

/**
 * The second ordering guard, for *after* the decode.
 *
 * [FrameQueue] orders what is handed to the decoder; it cannot order what comes back out.
 * `createImageBitmap` (and `Image.onload` before it) resolves asynchronously and out of order under
 * load, so the painter keeps a watermark of the highest `seq` it has actually drawn and consults
 * this before every paint.
 *
 * Returns true when [decodedSeq] is strictly newer than [paintedSeq]. Callers bump their watermark
 * to [decodedSeq] on a true result.
 */
export function shouldPaintDecodedFrame(
    paintedSeq: number,
    decodedSeq: number,
): boolean {
    return decodedSeq > paintedSeq;
}

/**
 * Drain one frame per animation frame, for as long as [alive] says this stream is the live one.
 *
 * Both lanes ran this loop inline, and both re-armed the animation frame *after* painting with
 * nothing around the paint. That made a single throw terminal: `frameBlob` throws on a payload
 * `atob` will not decode, the tick unwound before its `requestAnimationFrame`, and the chain was
 * simply never scheduled again — while the socket stayed open, frames kept arriving, and the badge
 * kept saying Live. Worse, the "am I already running?" flag both callers guard on was still set, so
 * nothing could restart the loop for the life of the page: the stage sat on the last frame it
 * managed to paint until a reload. That is issue #4313 — a live indeterminate progress indicator
 * frozen mid-sweep about a second into the stream.
 *
 * The `finally` is the whole fix: a tick that throws loses its own frame and nothing else. The pump
 * exits on exactly one condition — [alive] going false — which is what the callers' stop paths
 * already set, so the flag and the chain can no longer disagree about whether painting is running.
 *
 * [schedule] is injectable so the loop is testable without a browser; it defaults to
 * `requestAnimationFrame`. [onError] is for the caller's own diagnostics — the pump itself treats a
 * failed paint as a dropped frame, because the next frame repaints the stage anyway.
 */
export function pumpFrames(opts: {
    alive: () => boolean;
    next: () => ServeFrame | null;
    paint: (frame: ServeFrame) => void;
    schedule?: (cb: () => void) => void;
    onError?: (error: unknown) => void;
}): void {
    const schedule =
        opts.schedule ??
        function (cb: () => void) {
            requestAnimationFrame(cb);
        };
    const tick = function () {
        if (!opts.alive()) return;
        try {
            const frame = opts.next();
            if (frame) opts.paint(frame);
        } catch (error) {
            opts.onError?.(error);
        } finally {
            schedule(tick);
        }
    };
    schedule(tick);
}

/**
 * A frame's bytes as a `Blob`, for `createImageBitmap`.
 *
 * `createImageBitmap` rather than an `Image` + `data:` URL because it decodes off the main thread
 * and hands back a bitmap that can be drawn in one go — the visible canvas never tears down what it
 * is showing while the next frame decodes, which is rule 2 of the streaming client model.
 *
 * A frame with no payload is an `unchanged` heartbeat and has nothing to decode; callers get null
 * and skip the paint entirely rather than decoding an empty buffer.
 *
 * A payload `atob` refuses (truncated or otherwise not base64) is treated the same way — null, one
 * dropped frame — rather than thrown at the caller. [pumpFrames] survives the throw either way; the
 * point of catching it here is that "this frame is unpaintable" is this function's own answer to
 * give, and it is the shape every caller already handles.
 */
export function frameBlob(frame: ServeFrame): Blob | null {
    const b64 = frame.dataBase64;
    if (!b64) return null;
    let binary: string;
    try {
        binary = atob(b64);
    } catch {
        return null;
    }
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Blob([bytes], { type: `image/${frame.codec || "png"}` });
}

function isFiniteSeq(seq: unknown): seq is number {
    return typeof seq === "number" && Number.isFinite(seq);
}
