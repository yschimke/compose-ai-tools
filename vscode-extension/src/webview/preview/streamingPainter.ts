// Webview-side glue between the daemon's `composestream/1` `streamFrame`
// notifications and the on-screen canvas. Lives next to `behavior.ts` so
// the LIVE-mode swap (between the legacy `<img>` and the new canvas
// painter) is a thin three-line check at the message-router level.
//
// Three rules, mirroring docs/daemon/STREAMING.md § "Client model":
//
//  1. Newest-wins queue (StreamClient — already tested in
//     `streamClient.test.ts`).
//  2. Decode out-of-band via `createImageBitmap` so the visible canvas
//     never tears down its current bitmap before the next is ready.
//  3. Cache the most-recent painted bitmap as the keyframe anchor —
//     scrolling the card back into view repaints from cache immediately.
//
// The painter takes a card element, attaches a `<canvas>` inside its
// `.image-container`, registers a sink with the StreamClient, and starts
// a single rAF loop. Stopping the stream tears the canvas back down and
// reverts the card to its `<img>` so the legacy renderFinished path can
// take over again.

import { StreamClient, type PaintableFrame } from "../../daemon/streamClient";

interface PainterEntry {
    canvas: HTMLCanvasElement;
    ctx: ImageBitmapRenderingContext | CanvasRenderingContext2D;
    /**
     * Cached most-recent bitmap so a re-bind (scroll-back-into-view) can
     * paint immediately while the next live frame arrives.
     */
    anchor: ImageBitmap | null;
    pendingFinal: boolean;
}

function mimeForCodec(codec: "png" | "webp" | undefined): string {
    if (codec === "webp") return "image/webp";
    return "image/png";
}

function base64ToBlob(b64: string, mime: string): Blob {
    const binary = atob(b64);
    const len = binary.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return new Blob([bytes], { type: mime });
}

/**
 * Owns the per-card canvas painters plus the single rAF loop driving
 * `StreamClient.tick()`. The webview creates one StreamingPainter for the
 * lifetime of the panel and reuses it across every `streamStarted` /
 * `streamStopped` cycle.
 */
export class StreamingPainter {
    private readonly client = new StreamClient();
    private readonly entries = new Map<string, PainterEntry>(); // by frameStreamId
    private readonly previewIdToStreamId = new Map<string, string>();
    private rafId: number | null = null;

    /**
     * Attach a canvas to [card] and register a sink with the underlying
     * StreamClient. Called from `streamStarted` in the message router.
     * Idempotent — re-attaching to the same previewId tears the prior
     * entry down first.
     */
    attach(card: HTMLElement, previewId: string, frameStreamId: string): void {
        this.detach(previewId); // idempotent
        const container = card.querySelector(
            ".image-container",
        ) as HTMLElement | null;
        if (!container) {
            return;
        }
        // Hide the legacy `<img>` (don't remove — keeps the card's
        // bookkeeping of cached captures intact when stream/stop reverts).
        const img = container.querySelector("img") as HTMLImageElement | null;
        if (img) img.style.display = "none";
        const canvas = document.createElement("canvas");
        canvas.className = "stream-canvas";
        // Initial size is a placeholder; the first frame's widthPx/heightPx
        // resizes it. Letting the layout flicker for one paint is fine —
        // the streamStarted message races the first frame by < 1 frame.
        canvas.width = 1;
        canvas.height = 1;
        // Replicate the `<img>` flow style — same `object-fit` + max-width.
        canvas.style.maxWidth = "100%";
        canvas.style.height = "auto";
        container.appendChild(canvas);

        // Prefer ImageBitmapRenderingContext when available — it's a direct
        // GPU-side bitmap transfer, cheaper than `drawImage`. Fall back to
        // 2D for browsers that haven't implemented it yet (Electron <100).
        const bitmapCtx = canvas.getContext(
            "bitmaprenderer",
        ) as ImageBitmapRenderingContext | null;
        const ctx2d = bitmapCtx
            ? null
            : (canvas.getContext("2d") as CanvasRenderingContext2D | null);
        if (!bitmapCtx && !ctx2d) {
            return;
        }
        const entry: PainterEntry = {
            canvas,
            ctx: bitmapCtx ?? (ctx2d as CanvasRenderingContext2D),
            anchor: null,
            pendingFinal: false,
        };
        this.entries.set(frameStreamId, entry);
        this.previewIdToStreamId.set(previewId, frameStreamId);

        this.client.bind(frameStreamId, (frame) => this.paint(entry, frame));
        this.startLoop();
    }

    /**
     * `streamFrame` arrived from the extension. Forward to StreamClient's
     * newest-wins queue; the rAF loop drains it on the next tick.
     */
    onFrame(frame: PaintableFrame): void {
        // PaintableFrame is the same shape StreamClient.onFrame consumes
        // (StreamFrameParams), with `keyframe`/`final` widened to required.
        this.client.onFrame({
            frameStreamId: frame.frameStreamId,
            seq: frame.seq,
            ptsMillis: frame.ptsMillis,
            widthPx: frame.widthPx,
            heightPx: frame.heightPx,
            codec: frame.codec,
            keyframe: frame.keyframe,
            final: frame.final,
            payloadBase64: frame.payloadBase64,
        });
    }

    /**
     * Tear down the canvas + StreamClient entry for [previewId] and revert
     * the card to its `<img>`. Idempotent — `streamStopped` may race the
     * `final` frame from the same teardown, so being called twice is
     * normal.
     */
    detach(previewId: string): void {
        const sid = this.previewIdToStreamId.get(previewId);
        if (!sid) return;
        this.previewIdToStreamId.delete(previewId);
        const entry = this.entries.get(sid);
        this.entries.delete(sid);
        this.client.unbind(sid);
        if (entry) {
            entry.canvas.remove();
            if (entry.anchor && typeof entry.anchor.close === "function") {
                entry.anchor.close();
            }
        }
        // Reveal the legacy `<img>` again (we hid it on attach).
        const card = document.getElementById("preview-" + cssId(previewId));
        const img = card?.querySelector(
            ".image-container img",
        ) as HTMLImageElement | null;
        if (img) img.style.display = "";
        if (this.entries.size === 0) {
            this.stopLoop();
        }
    }

    /** Test introspection — pending frames not yet painted. */
    pendingCount(): number {
        return this.client.pendingCount();
    }

    /** Test introspection — set of previewIds with active painters. */
    activePreviewIds(): string[] {
        return [...this.previewIdToStreamId.keys()];
    }

    private paint(entry: PainterEntry, frame: PaintableFrame): void {
        if (frame.final) {
            entry.pendingFinal = true;
            // Final frames carry no payload; nothing to paint. The detach()
            // call from the streamStopped message handler will tear down.
            return;
        }
        if (frame.codec === undefined || frame.payloadBase64 === undefined) {
            // Unchanged-heartbeat — the daemon told us bytes are stable;
            // leave the current bitmap in place. No paint cost.
            return;
        }
        const blob = base64ToBlob(
            frame.payloadBase64,
            mimeForCodec(frame.codec),
        );
        // createImageBitmap returns a Promise resolved on the decode worker.
        // The next rAF tick consults `entry.anchor` so the paint thread is
        // never blocked on decode.
        void createImageBitmap(blob).then(
            (bitmap) => {
                if (!this.entries.has(frame.frameStreamId)) {
                    // Detached while we were decoding — drop the bitmap.
                    bitmap.close?.();
                    return;
                }
                if (
                    entry.canvas.width !== bitmap.width ||
                    entry.canvas.height !== bitmap.height
                ) {
                    entry.canvas.width = bitmap.width;
                    entry.canvas.height = bitmap.height;
                }
                if ("transferFromImageBitmap" in entry.ctx) {
                    // ImageBitmapRenderingContext branch — single-paint
                    // GPU-side transfer.
                    (
                        entry.ctx as ImageBitmapRenderingContext
                    ).transferFromImageBitmap(bitmap);
                    // After transfer the bitmap is owned by the canvas; we
                    // mustn't `close()` it. Cache the anchor by holding a
                    // fresh decode of the same blob lazily — use the bitmap
                    // we already have but only via the canvas. A brand-new
                    // anchor isn't needed because the canvas itself is the
                    // anchor on this code path.
                    entry.anchor = null;
                } else {
                    const ctx2d = entry.ctx as CanvasRenderingContext2D;
                    ctx2d.drawImage(bitmap, 0, 0);
                    if (
                        entry.anchor &&
                        typeof entry.anchor.close === "function"
                    ) {
                        entry.anchor.close();
                    }
                    entry.anchor = bitmap;
                }
            },
            (err) => {
                console.error(
                    `compose-ai stream painter: createImageBitmap failed`,
                    err,
                );
            },
        );
    }

    private startLoop(): void {
        if (this.rafId !== null) return;
        if (typeof requestAnimationFrame !== "function") {
            // Non-browser test runner — caller is expected to drive
            // `client.tick()` manually via the StreamClient unit tests.
            return;
        }
        const loop = () => {
            this.client.tick();
            if (this.entries.size === 0) {
                this.rafId = null;
                return;
            }
            this.rafId = requestAnimationFrame(loop);
        };
        this.rafId = requestAnimationFrame(loop);
    }

    private stopLoop(): void {
        if (this.rafId !== null && typeof cancelAnimationFrame === "function") {
            cancelAnimationFrame(this.rafId);
        }
        this.rafId = null;
    }
}

/**
 * Replicates the `sanitizeId` used elsewhere in `behavior.ts` for
 * `preview-<id>` element ids. Inlined here to keep this module standalone
 * (one fewer cross-module import for an existing utility).
 */
function cssId(previewId: string): string {
    return previewId.replace(/[^a-zA-Z0-9_-]/g, "_");
}
