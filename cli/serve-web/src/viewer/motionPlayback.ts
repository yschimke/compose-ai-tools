// Where a recorded interaction is up to, and what pressing anything on the transport does to that.
//
// ### Why a playhead exists at all
//
// The lane used to hand the capture to an `<img>` and let the browser play it. That is one line of
// code and it answers none of the questions a reader actually has: an APNG published by this
// project loops forever (`loopCount = 0`), so a capture that toggles a switch on and then off runs
// on → off → on → off with no seam, and the reader cannot tell a transition from its own reverse.
// There is no pausing it, no slowing it down, and no way to sit on the two frames either side of
// the moment being documented — which is the entire reason someone opened a recording rather than
// looking at the still.
//
// So the viewer drives playback itself, frame by frame, and this is the part of that with no DOM
// in it: a position in milliseconds, a rate, and whether it is running. `viewer.ts` owns the
// decoder, the canvas and the clock; every decision about what those should show is here, with
// tests beside it.
//
// ### Play once, then offer it again
//
// [tick] stops dead on the last frame instead of wrapping. A loop is the right default for a
// capture embedded in a README, where nobody is going to press anything; it is the wrong one for a
// reader studying a transition, because the recording never sits still long enough to be read and
// the reader has no idea which pass they are watching. Pressing play from the end restarts from
// the top ([toggle]), which is what every video player does and what "watch that again" means.

/** The frames a capture published, and how long each is held. */
export interface MotionTimeline {
    /** Frames in the capture. Always ≥ 1 — a capture with none is not a capture. */
    frameCount: number;
    /**
     * How long ONE frame is held, in milliseconds.
     *
     * Uniform on purpose, because every capture this project publishes is: the recorder advances
     * its clock by a fixed `frameIntervalMs` and both encoders write that one delay onto every
     * frame. Reading a per-frame duration table off the decoder would model a generality the
     * format allows and this pipeline never produces, and it would make the timeline's scale
     * depend on frames that have not been decoded yet.
     */
    frameDurationMs: number;
}

/** Where the playhead is, and what it is doing. */
export interface PlaybackState {
    /** Milliseconds from the start of the capture. Never past [spanMs]. */
    positionMs: number;
    /** Whether the clock is advancing. False at the end of a pass, and while scrubbing. */
    playing: boolean;
    /** Clock multiplier — 1 is the rate the capture was recorded at. */
    rate: number;
}

/**
 * The rates offered, slowest first.
 *
 * Captures are recorded at 60fps and the motion being documented is often a single spring settling
 * over ~300ms, so the useful end of this range is the slow end: at 0.25× that settle takes over a
 * second and its overshoot is separable by eye. 2× is there for the long scripted interactions
 * (three taps with a 700ms gap between them) where the gaps, not the motion, are most of the run.
 */
export const PLAYBACK_RATES = [0.25, 0.5, 1, 2] as const;

/** The rate a lane opens at. */
export const DEFAULT_RATE = 1;

/**
 * How far the playhead can travel: the last frame's timestamp, at 1×.
 *
 * One frame short of how long the capture *runs* (`frameCount × frameDurationMs`), and that is the
 * right quantity for every consumer here. The playhead addresses frames, so its range is first
 * frame → last frame; measuring the bar against the run time instead would leave the fill at
 * `(N-1)/N` — a timeline that reads 93% full while the thumb sits hard against its right end and
 * the counter says frame 14 of 14. The last frame is still held for its own duration on screen;
 * nothing here is shortened, and only the number attached to the end of the bar changes.
 */
export function spanMs(timeline: MotionTimeline): number {
    return Math.max(0, timeline.frameCount - 1) * timeline.frameDurationMs;
}

/**
 * Which frame is on screen at [positionMs].
 *
 * Clamped at both ends rather than wrapped: the position is already clamped by [tick] and [seek],
 * and a frame index that wrapped would show frame 0 for the final instant of a capture that had
 * just been played to its end.
 */
export function frameAt(timeline: MotionTimeline, positionMs: number): number {
    if (timeline.frameDurationMs <= 0) return 0;
    const raw = Math.floor(positionMs / timeline.frameDurationMs);
    return Math.min(Math.max(raw, 0), Math.max(0, timeline.frameCount - 1));
}

/** The position that puts [frame] on screen — its first instant, not its midpoint. */
export function positionOfFrame(
    timeline: MotionTimeline,
    frame: number,
): number {
    const clamped = Math.min(
        Math.max(frame, 0),
        Math.max(0, timeline.frameCount - 1),
    );
    return clamped * timeline.frameDurationMs;
}

/** How far through the capture the playhead is, 0…1 — what the timeline bar fills to. */
export function progress(timeline: MotionTimeline, positionMs: number): number {
    const span = spanMs(timeline);
    // A single-frame capture has nowhere to travel, and there is nothing left to play: full, not
    // empty, which is also what its thumb (min 0, max 0) shows.
    if (span <= 0) return 1;
    return Math.min(1, Math.max(0, positionMs / span));
}

/** True once the playhead is sitting on the last frame with nothing left to play. */
export function atEnd(timeline: MotionTimeline, positionMs: number): boolean {
    return frameAt(timeline, positionMs) >= timeline.frameCount - 1;
}

/**
 * Advance the clock by [elapsedMs] of wall time.
 *
 * Wall time × rate, so slowing playback down stretches the capture rather than dropping frames out
 * of it — at 0.25× every frame is held four times as long and none is skipped. A paused state is
 * returned untouched, which is what lets the caller drive this from a single animation frame
 * callback without tracking whether the last one was during playback.
 *
 * Lands ON the last frame and stops. Not one frame past it and not back at the start: the final
 * frame is the resting state the interaction ended in, and leaving it on screen is the answer to
 * "what did that do?".
 */
export function tick(
    state: PlaybackState,
    timeline: MotionTimeline,
    elapsedMs: number,
): PlaybackState {
    if (!state.playing) return state;
    const advanced = state.positionMs + Math.max(0, elapsedMs) * state.rate;
    const last = positionOfFrame(timeline, timeline.frameCount - 1);
    if (advanced >= last) return { ...state, positionMs: last, playing: false };
    return { ...state, positionMs: advanced };
}

/**
 * Play / pause — and, from a finished pass, play AGAIN from the top.
 *
 * The restart is the whole reason this is not a one-line boolean flip. A transport whose play
 * button did nothing once the capture had run to its end would strand the reader on the last frame
 * with the control that looks like it should help greyed out in spirit if not in markup.
 */
export function toggle(
    state: PlaybackState,
    timeline: MotionTimeline,
): PlaybackState {
    if (state.playing) return { ...state, playing: false };
    if (atEnd(timeline, state.positionMs))
        return { ...state, positionMs: 0, playing: true };
    return { ...state, playing: true };
}

/** Back to the first frame and running — the ↻ button, and what entering the lane does. */
export function replay(state: PlaybackState): PlaybackState {
    return { ...state, positionMs: 0, playing: true };
}

/**
 * Scrub to a frame.
 *
 * Pauses, always. Dragging the timeline is an act of inspection — the reader is choosing a moment
 * to look at — and a playhead that kept running would carry them off it before they had read it.
 */
export function seek(
    state: PlaybackState,
    timeline: MotionTimeline,
    frame: number,
): PlaybackState {
    return {
        ...state,
        positionMs: positionOfFrame(timeline, frame),
        playing: false,
    };
}

/** Step one frame either way, from wherever the playhead is. Pauses, for the same reason [seek] does. */
export function step(
    state: PlaybackState,
    timeline: MotionTimeline,
    delta: number,
): PlaybackState {
    return seek(state, timeline, frameAt(timeline, state.positionMs) + delta);
}

/** Pick the offered rate nearest a raw value, so a hand-edited URL or a stale control cannot set 37×. */
export function normaliseRate(raw: number | string | null | undefined): number {
    const value = typeof raw === "string" ? parseFloat(raw) : raw;
    if (!value || !isFinite(value) || value <= 0) return DEFAULT_RATE;
    return PLAYBACK_RATES.reduce(
        (best, rate) =>
            Math.abs(rate - value) < Math.abs(best - value) ? rate : best,
        PLAYBACK_RATES[0] as number,
    );
}

/**
 * The transport's readout: elapsed of total, then which frame that is.
 *
 * Seconds to one decimal, because a capture is one to three seconds long and a minutes:seconds
 * clock would spend its first three characters saying "0:0". The frame count rides beside it
 * because it is the unit the ← / → keys move in — a reader stepping through a spring wants to know
 * they are on frame 84 of 147, not at 1.4 seconds of 2.4.
 */
export function readout(timeline: MotionTimeline, positionMs: number): string {
    const seconds = (ms: number) => `${(ms / 1000).toFixed(1)}s`;
    const frame = frameAt(timeline, positionMs) + 1;
    return `${seconds(positionMs)} / ${seconds(spanMs(timeline))} · frame ${frame}/${timeline.frameCount}`;
}
