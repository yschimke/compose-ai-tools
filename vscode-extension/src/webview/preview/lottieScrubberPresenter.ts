// Lottie scrubber presenter — fills the "Lottie" bundle tab body from the
// `animation/lottie` data product (see LottieTimelineDataProductRegistry):
// `{ totalFrames, frameRate, durationMillis, width, height }`.
//
// Unlike the table-shaped bundles, this body is a single timeline slider: the
// user drags it to scrub the animation, and each `input` bubbles a
// `lottie-progress-changed` CustomEvent that the host (`main.ts`) turns into a
// `setLottieProgress` post-message back to the extension, which re-renders the
// held preview via `renderNow.overrides.lottie.progress`.
//
// The compute half (`computeLottieScrubberData`) is pure and unit-tested; the
// DOM half (`buildLottieScrubberBody`) is exercised by the preview harness.

/** Wire shape of the `animation/lottie` data product (mirror of LottieTimelineMetadata). */
export interface LottieTimelineMetadata {
    totalFrames?: number | null;
    frameRate?: number | null;
    durationMillis?: number | null;
    width?: number | null;
    height?: number | null;
}

export interface LottieScrubberData {
    /** Whether a usable timeline arrived — drives the "no Lottie timeline" empty state. */
    available: boolean;
    /** Whole frame count (≥ 1 when available), used to range/label the slider. */
    totalFrames: number;
    frameRate: number;
    durationMillis: number;
    /** Clamped 0..1 timeline position. */
    progress: number;
    /** Frame the current progress maps to (`round(progress * (totalFrames - 1))`). */
    frameIndex: number;
    /** One-line summary, e.g. `"frame 18 / 60 · 30 fps · 2.0s"`. */
    summary: string;
}

const clamp01 = (n: number): number =>
    Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0;

/**
 * Normalise the raw `animation/lottie` payload + the current slider position into the display data
 * the body renders. Tolerant of a null / partial payload (returns `available: false`) so the bundle
 * can show an empty state before the data product lands or for a non-Lottie preview.
 */
export function computeLottieScrubberData(
    metadata: LottieTimelineMetadata | null | undefined,
    progress: number,
): LottieScrubberData {
    const p = clamp01(progress);
    const rawFrames =
        typeof metadata?.totalFrames === "number" ? metadata.totalFrames : 0;
    const totalFrames = rawFrames > 0 ? Math.round(rawFrames) : 0;
    const frameRate =
        typeof metadata?.frameRate === "number" && metadata.frameRate > 0
            ? metadata.frameRate
            : 0;
    const durationMillis =
        typeof metadata?.durationMillis === "number" &&
        metadata.durationMillis > 0
            ? Math.round(metadata.durationMillis)
            : 0;
    const available = totalFrames > 0;

    // Map progress onto a frame index over [0, totalFrames-1]. A 1-frame clip pins to frame 0.
    const frameIndex = available
        ? Math.round(p * Math.max(0, totalFrames - 1))
        : 0;

    const summary = available
        ? `frame ${frameIndex} / ${totalFrames}` +
          (frameRate > 0 ? ` · ${formatFps(frameRate)} fps` : "") +
          (durationMillis > 0 ? ` · ${formatSeconds(durationMillis)}` : "")
        : "no Lottie timeline";

    return {
        available,
        totalFrames,
        frameRate,
        durationMillis,
        progress: p,
        frameIndex,
        summary,
    };
}

function formatFps(fps: number): string {
    // Drop a trailing `.0` so 30fps shows as "30" but 23.976 stays precise enough.
    return Number.isInteger(fps)
        ? String(fps)
        : fps.toFixed(2).replace(/0+$/, "");
}

function formatSeconds(ms: number): string {
    return (ms / 1000).toFixed(1) + "s";
}

/** Detail payload of the `lottie-progress-changed` CustomEvent. */
export interface LottieScrubberDetail {
    progress: number;
}

/** Slider resolution: integer ticks the range input uses, mapped onto 0..1 progress. */
const SLIDER_TICKS = 1000;

export interface LottieScrubberBody {
    wrapper: HTMLElement;
    /** Re-range + relabel the slider for fresh metadata, preserving the current position. */
    update(data: LottieScrubberData): void;
}

/**
 * Build the scrubber DOM once (the body is cached for the panel lifetime). The slider's `input`
 * bubbles a `lottie-progress-changed` CustomEvent carrying the 0..1 progress; `main.ts` listens on
 * the wrapper and forwards it as a `setLottieProgress` post-message. Repeated [LottieScrubberBody.update]
 * calls only refresh the labels / disabled state, never re-create the node, so dragging stays smooth.
 */
export function buildLottieScrubberBody(): LottieScrubberBody {
    const wrapper = document.createElement("div");
    wrapper.className = "bundle-tab-body lottie-scrubber-body";
    wrapper.dataset.bundle = "lottie";

    const summary = document.createElement("div");
    summary.className = "lottie-scrubber-summary";
    summary.textContent = "no Lottie timeline";

    const slider = document.createElement("input");
    slider.type = "range";
    slider.className = "lottie-scrubber-slider";
    slider.min = "0";
    slider.max = String(SLIDER_TICKS);
    slider.step = "1";
    slider.value = "0";
    slider.disabled = true;
    slider.setAttribute("aria-label", "Lottie timeline position");

    slider.addEventListener("input", () => {
        const progress = clamp01(Number(slider.value) / SLIDER_TICKS);
        summary.textContent = labelFor(progress);
        wrapper.dispatchEvent(
            new CustomEvent<LottieScrubberDetail>("lottie-progress-changed", {
                detail: { progress },
                bubbles: true,
                composed: true,
            }),
        );
    });

    wrapper.appendChild(summary);
    wrapper.appendChild(slider);

    // Last metadata seen, so the live `input` label (above) can recompute frame numbers as the
    // user drags without waiting for a round-trip.
    let lastMetadata: LottieTimelineMetadata | null = null;
    function labelFor(progress: number): string {
        return computeLottieScrubberData(lastMetadata, progress).summary;
    }

    return {
        wrapper,
        update(data: LottieScrubberData): void {
            lastMetadata = data.available
                ? {
                      totalFrames: data.totalFrames,
                      frameRate: data.frameRate,
                      durationMillis: data.durationMillis,
                  }
                : null;
            slider.disabled = !data.available;
            slider.value = String(Math.round(data.progress * SLIDER_TICKS));
            summary.textContent = data.summary;
        },
    };
}
