// Client-side pixel diff for the History panel + the live preview panel's
// per-card diff overlay. Same algorithm in both call sites; lifted out of
// `preview/diffOverlay.ts` and `history/historyDiffView.ts` so the two
// copies stop drifting (the preview copy used `getContext("2d")!`
// non-null assertions; the history copy guarded explicitly — this module
// keeps the explicit guards).
//
// Loads both base64 PNGs into hidden `<img>`s, draws each to a canvas,
// walks the two `ImageData` buffers in parallel comparing RGBA quads. The
// resolved `DiffStats` is a discriminated union so callers (`applyDiffStats`
// label renderer, both panels) narrow correctly via `"error" in s` and
// `s.sameSize`.
//
// Always resolves; never rejects. Every failure mode (image load error,
// canvas-2d-unavailable, runtime exception during the byte walk) lands as
// an `{ error }` variant so callers don't have to wrap the call in a
// try/catch.

/** Outcome of a pixel diff between two base64 PNGs. */
export type DiffStats =
    | { error: string }
    | {
          sameSize: false;
          leftW: number;
          leftH: number;
          rightW: number;
          rightH: number;
      }
    | {
          sameSize: true;
          w: number;
          h: number;
          diffPx: number;
          total: number;
          percent: number;
      };

export function computeDiffStats(
    leftBase64: string,
    rightBase64: string,
): Promise<DiffStats> {
    return new Promise<DiffStats>((resolve) => {
        const left = new Image();
        const right = new Image();
        let loaded = 0;
        const onErr = (): void => resolve({ error: "image failed to load" });
        const onOk = (): void => {
            if (++loaded < 2) return;
            try {
                if (
                    left.naturalWidth !== right.naturalWidth ||
                    left.naturalHeight !== right.naturalHeight
                ) {
                    resolve({
                        sameSize: false,
                        leftW: left.naturalWidth,
                        leftH: left.naturalHeight,
                        rightW: right.naturalWidth,
                        rightH: right.naturalHeight,
                    });
                    return;
                }
                const w = left.naturalWidth;
                const h = left.naturalHeight;
                const c1 = document.createElement("canvas");
                c1.width = w;
                c1.height = h;
                const ctx1 = c1.getContext("2d");
                if (!ctx1) {
                    resolve({ error: "canvas 2d context unavailable" });
                    return;
                }
                ctx1.drawImage(left, 0, 0);
                const d1 = ctx1.getImageData(0, 0, w, h).data;
                const c2 = document.createElement("canvas");
                c2.width = w;
                c2.height = h;
                const ctx2 = c2.getContext("2d");
                if (!ctx2) {
                    resolve({ error: "canvas 2d context unavailable" });
                    return;
                }
                ctx2.drawImage(right, 0, 0);
                const d2 = ctx2.getImageData(0, 0, w, h).data;
                let diff = 0;
                const len = d1.length;
                for (let i = 0; i < len; i += 4) {
                    if (
                        d1[i] !== d2[i] ||
                        d1[i + 1] !== d2[i + 1] ||
                        d1[i + 2] !== d2[i + 2] ||
                        d1[i + 3] !== d2[i + 3]
                    )
                        diff++;
                }
                const total = w * h;
                resolve({
                    sameSize: true,
                    w,
                    h,
                    diffPx: diff,
                    total,
                    percent: total > 0 ? diff / total : 0,
                });
            } catch (err) {
                resolve({
                    error:
                        err instanceof Error
                            ? err.message
                            : "stats unavailable",
                });
            }
        };
        left.onload = onOk;
        left.onerror = onErr;
        right.onload = onOk;
        right.onerror = onErr;
        left.src = "data:image/png;base64," + leftBase64;
        right.src = "data:image/png;base64," + rightBase64;
    });
}
