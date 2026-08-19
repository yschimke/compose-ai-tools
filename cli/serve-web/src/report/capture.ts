// Grabbing a picture of what the visitor is actually looking at.
//
// **Why this is a screen capture and not a DOM render.** Issue #4261: a report filed from the spec
// lane's triptych arrived showing an ordinary single render, because `/render/<id>.png` is the only
// picture of a preview the server can produce. The triptych, the wipe, the exploded stack, the
// Remote Compose canvas, an overlay, a lane that failed with an error on the stage — every one of
// those is composed in the BROWSER out of several artefacts, and none of them has a URL to embed.
// Nor can the server re-derive them: it would have to run the page.
//
// Two families of answer exist client-side. Serialising the DOM into an SVG `foreignObject` and
// rasterising that (what the dom-to-image libraries do) reproduces the markup, not the rendering —
// it needs every stylesheet inlined and every image re-fetched as a data URL, it silently drops
// canvas contents, cross-origin fonts and anything drawn by a shader, and what it produces is
// therefore a *reconstruction* that can differ from the screen in exactly the ways a visual bug
// report is about. `getDisplayMedia` asks the browser for the pixels it actually painted. For a
// tool whose entire job is "show me what you saw", the second is the only honest one.
//
// The cost is a permission prompt, and that is the right trade for a deliberate, once-per-report
// gesture. Where the API is missing or refused, the affordance stays hidden and the report page
// keeps asking for an ordinary pasted screenshot, which is what it did before this existed.

import {
    Rect,
    Scale,
    Size,
    fitWithin,
    frameScale,
    mapRect,
} from "./geometry.js";

/** A frame of the visitor's screen, and enough about it to crop safely. */
export interface Frame {
    canvas: HTMLCanvasElement;
    width: number;
    height: number;
    /**
     * What the browser says was shared: `browser` is a tab, `window`/`monitor` are not.
     *
     * The distinction decides whether a crop is even meaningful. Every rectangle this module is
     * handed is in viewport coordinates, and the mapping onto the frame assumes the frame IS the
     * viewport. Share a whole monitor instead — which some browsers offer regardless of what was
     * asked for — and that assumption is false by an unknown offset, so cropping would silently cut
     * out a piece of some other part of the screen. Unknown (`""`) is treated as not-a-tab.
     */
    surface: string;
}

/** Whether this browser can do any of it. */
export function captureSupported(): boolean {
    return (
        typeof navigator !== "undefined" &&
        !!navigator.mediaDevices &&
        typeof navigator.mediaDevices.getDisplayMedia === "function" &&
        typeof HTMLCanvasElement !== "undefined"
    );
}

/** The longest side a stored capture may have — see `fitWithin`. */
const MAX_SIDE = 1600;

/**
 * One frame of the current tab.
 *
 * `preferCurrentTab` is a Chromium hint that reduces the prompt to "share this tab?" instead of a
 * picker; it is ignored elsewhere, where the visitor picks. `displaySurface: "browser"` states the
 * same preference in the standard vocabulary. Neither is a guarantee, which is why [Frame.surface]
 * is read back rather than assumed.
 *
 * The track is stopped before this returns, always. A live capture track leaves the browser's
 * "sharing your screen" indicator up, and a bug-report tool that leaves a screen share running
 * would deserve every bit of the alarm that causes.
 */
export async function grabFrame(): Promise<Frame> {
    const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { displaySurface: "browser" },
        audio: false,
        // Not in lib.dom: a Chromium-specific hint, harmless where unknown.
        preferCurrentTab: true,
    } as DisplayMediaStreamOptions);
    const track = stream.getVideoTracks()[0];
    try {
        const video = document.createElement("video");
        video.srcObject = stream;
        video.muted = true;
        // Kept out of the layout entirely. It must not be `display: none` — a hidden video is
        // allowed to stop decoding, and then the first frame never arrives.
        video.style.cssText =
            "position:fixed;left:-10000px;top:0;width:1px;height:1px;opacity:0;pointer-events:none";
        document.body.appendChild(video);
        try {
            await video.play();
            await firstFrame(video);
            const width = video.videoWidth;
            const height = video.videoHeight;
            const canvas = document.createElement("canvas");
            canvas.width = Math.max(1, width);
            canvas.height = Math.max(1, height);
            canvas.getContext("2d")?.drawImage(video, 0, 0);
            return {
                canvas,
                width: canvas.width,
                height: canvas.height,
                surface: String(
                    (track?.getSettings() as { displaySurface?: string })
                        ?.displaySurface ?? "",
                ),
            };
        } finally {
            video.srcObject = null;
            video.remove();
        }
    } finally {
        stream.getTracks().forEach((t) => t.stop());
    }
}

/**
 * Wait until the element has a decoded frame with real dimensions.
 *
 * `play()` resolving is not enough: on the first frame after a share dialog closes, `videoWidth` is
 * routinely still 0, and drawing then yields a 1×1 transparent capture — which looks like a bug in
 * the cropping rather than a race. Resolves anyway after a bounded wait, so a stream that never
 * produces a frame fails as an empty capture the caller can report rather than as a hang.
 */
function firstFrame(video: HTMLVideoElement): Promise<void> {
    return new Promise((resolve) => {
        const deadline = Date.now() + 2000;
        const tick = () => {
            if (video.videoWidth > 0 && video.readyState >= 2) return resolve();
            if (Date.now() > deadline) return resolve();
            requestAnimationFrame(tick);
        };
        tick();
    });
}

/** The viewport the frame is a picture of, in CSS px. */
export function viewportSize(): Size {
    return {
        width: window.innerWidth || document.documentElement.clientWidth || 0,
        height:
            window.innerHeight || document.documentElement.clientHeight || 0,
    };
}

/** CSS px → frame px for this frame. */
export function scaleOf(frame: Frame): Scale {
    return frameScale(
        { width: frame.width, height: frame.height },
        viewportSize(),
    );
}

/**
 * Cut [rect] (CSS px, viewport-relative) out of [frame] and downscale it to something a
 * `sessionStorage` budget can hold.
 *
 * The two-step — crop at native resolution, then resize — is deliberate: cropping a pre-scaled
 * frame compounds the resampling, and on text-heavy captures (a table, an error) that is the
 * difference between readable and not.
 */
export function crop(frame: Frame, rect: Rect): HTMLCanvasElement {
    const source = mapRect(rect, scaleOf(frame));
    const fitted = fitWithin(
        { width: source.width, height: source.height },
        MAX_SIDE,
    );
    const out = document.createElement("canvas");
    out.width = fitted.width;
    out.height = fitted.height;
    const ctx = out.getContext("2d");
    if (ctx) {
        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(
            frame.canvas,
            source.x,
            source.y,
            source.width,
            source.height,
            0,
            0,
            fitted.width,
            fitted.height,
        );
    }
    return out;
}

/**
 * The whole frame, downscaled the same way — the "whole view" mode's crop.
 *
 * Expressed in FRAME coordinates rather than as a crop of the viewport rectangle, so it is right
 * whichever surface was shared: on a tab the frame already is the viewport, and on a window or a
 * monitor — where the viewport mapping is meaningless and the two selection modes refuse to
 * run — this still yields exactly what the visitor agreed to share.
 */
export function whole(frame: Frame): HTMLCanvasElement {
    const fitted = fitWithin(
        { width: frame.width, height: frame.height },
        MAX_SIDE,
    );
    const out = document.createElement("canvas");
    out.width = fitted.width;
    out.height = fitted.height;
    const ctx = out.getContext("2d");
    if (ctx) {
        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(frame.canvas, 0, 0, fitted.width, fitted.height);
    }
    return out;
}

/**
 * The clipboard hand-off.
 *
 * The blob goes in as a PROMISE, and the `ClipboardItem` is constructed synchronously — the same
 * shape, for the same reason, as the viewer's existing "Copy PNG": Safari requires the item to
 * exist inside the click that authorised it, so awaiting the encode first loses the gesture and the
 * write is refused.
 */
export function copyPng(blob: Promise<Blob>): Promise<void> {
    if (typeof ClipboardItem === "undefined" || !navigator.clipboard?.write) {
        return Promise.reject(new Error("no clipboard"));
    }
    return navigator.clipboard.write([
        new ClipboardItem({ "image/png": blob }),
    ]);
}

/** A canvas as PNG bytes. */
export function toBlob(canvas: HTMLCanvasElement): Promise<Blob> {
    return new Promise((resolve, reject) => {
        canvas.toBlob((blob) => {
            if (blob) resolve(blob);
            else reject(new Error("encode failed"));
        }, "image/png");
    });
}

/** The same bytes as the data URL that survives the navigation to `/report-bug`. */
export function toDataUrl(canvas: HTMLCanvasElement): string {
    return canvas.toDataURL("image/png");
}

/** A stored data URL back to bytes, for a Copy pressed on the report page. */
export function blobFromDataUrl(dataUrl: string): Promise<Blob> {
    return fetch(dataUrl).then((r) => r.blob());
}
