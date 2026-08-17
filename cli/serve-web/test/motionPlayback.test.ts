// The recorded interaction's playhead: play once, stop on the last frame, and be scrubbable.

import assert from "node:assert/strict";
import {
    DEFAULT_RATE,
    PLAYBACK_RATES,
    atEnd,
    frameAt,
    normaliseRate,
    positionOfFrame,
    progress,
    readout,
    replay,
    seek,
    step,
    tick,
    toggle,
    spanMs,
} from "../src/viewer/motionPlayback.js";

// 10 frames at 100ms — a whole second, and every boundary lands on a round number.
const TIMELINE = { frameCount: 10, frameDurationMs: 100 };
const running = (positionMs = 0, rate = 1) => ({
    positionMs,
    playing: true,
    rate,
});

describe("frameAt", () => {
    it("holds a frame for its whole duration", () => {
        assert.equal(frameAt(TIMELINE, 0), 0);
        assert.equal(frameAt(TIMELINE, 99), 0);
        assert.equal(frameAt(TIMELINE, 100), 1);
    });

    it("clamps rather than wrapping at the end", () => {
        // Wrapping would show frame 0 for the final instant of a capture that had just played to
        // its end — the one frame a reader is most likely to be looking at.
        assert.equal(frameAt(TIMELINE, 900), 9);
        assert.equal(frameAt(TIMELINE, 1000), 9);
        assert.equal(frameAt(TIMELINE, 99999), 9);
        assert.equal(frameAt(TIMELINE, -50), 0);
    });
});

describe("tick", () => {
    it("advances by wall time at 1×", () => {
        assert.deepEqual(tick(running(0), TIMELINE, 250), running(250));
    });

    it("stretches the capture at a slower rate instead of dropping frames", () => {
        // 0.25× holds every frame four times as long; nothing is skipped, which is the whole
        // reason a reader slows a spring down.
        const state = tick(running(0, 0.25), TIMELINE, 200);
        assert.equal(state.positionMs, 50);
        assert.equal(frameAt(TIMELINE, state.positionMs), 0);
    });

    it("lands ON the last frame and stops — it does not loop", () => {
        // The bug this whole transport exists for: a looping capture that toggles on and then off
        // gives the reader no seam, so they cannot tell a transition from its own reverse.
        const state = tick(running(880), TIMELINE, 500);
        assert.equal(state.playing, false);
        assert.equal(
            state.positionMs,
            900,
            "the last frame, not one past it or back at 0",
        );
        assert.equal(frameAt(TIMELINE, state.positionMs), 9);
    });

    it("leaves a paused playhead alone", () => {
        const paused = { positionMs: 300, playing: false, rate: 1 };
        assert.deepEqual(tick(paused, TIMELINE, 500), paused);
    });
});

describe("toggle", () => {
    it("pauses a running capture and resumes a paused one in place", () => {
        assert.deepEqual(toggle(running(300), TIMELINE), {
            positionMs: 300,
            playing: false,
            rate: 1,
        });
        assert.deepEqual(
            toggle({ positionMs: 300, playing: false, rate: 1 }, TIMELINE),
            running(300),
        );
    });

    it("plays AGAIN from the top once the pass has finished", () => {
        // Otherwise the reader is stranded on the last frame with a play button that does nothing.
        const ended = { positionMs: 900, playing: false, rate: 1 };
        assert.deepEqual(toggle(ended, TIMELINE), running(0));
    });
});

describe("seek and step", () => {
    it("pauses, because scrubbing is an act of inspection", () => {
        const state = seek(running(0), TIMELINE, 4);
        assert.equal(state.playing, false);
        assert.equal(state.positionMs, 400);
    });

    it("steps a frame either way and stops at the ends", () => {
        assert.equal(step(running(400), TIMELINE, 1).positionMs, 500);
        assert.equal(step(running(400), TIMELINE, -1).positionMs, 300);
        assert.equal(step(running(0), TIMELINE, -1).positionMs, 0);
        assert.equal(step(running(900), TIMELINE, 1).positionMs, 900);
    });

    it("keeps the rate the reader chose", () => {
        assert.equal(seek(running(0, 0.25), TIMELINE, 3).rate, 0.25);
        assert.equal(replay(running(500, 2)).rate, 2);
    });
});

describe("replay", () => {
    it("returns to the first frame and runs", () => {
        assert.deepEqual(
            replay({ positionMs: 900, playing: false, rate: 1 }),
            running(0),
        );
    });
});

describe("progress and atEnd", () => {
    it("fills the timeline bar from 0 to 1 over the playhead's OWN range", () => {
        // Measured against the last frame's timestamp, not the capture's run time. Against the
        // latter the bar would stop at 9/10 while the thumb sat hard right and the counter read
        // "frame 10/10" — three controls, two answers.
        assert.equal(spanMs(TIMELINE), 900);
        assert.equal(progress(TIMELINE, 0), 0);
        assert.equal(progress(TIMELINE, 450), 0.5);
        assert.equal(progress(TIMELINE, 900), 1);
        assert.equal(progress(TIMELINE, 99999), 1, "clamped, never past full");
    });

    it("calls a one-frame capture finished rather than empty", () => {
        const still = { frameCount: 1, frameDurationMs: 40 };
        assert.equal(spanMs(still), 0);
        assert.equal(progress(still, 0), 1);
        assert.equal(atEnd(still, 0), true);
    });

    it("calls the last frame the end", () => {
        assert.equal(atEnd(TIMELINE, 800), false);
        assert.equal(atEnd(TIMELINE, 900), true);
    });
});

describe("normaliseRate", () => {
    it("keeps the offered rates", () => {
        for (const rate of PLAYBACK_RATES)
            assert.equal(normaliseRate(String(rate)), rate);
    });

    it("snaps anything else to the nearest offered one", () => {
        assert.equal(normaliseRate(0.3), 0.25);
        assert.equal(normaliseRate(37), 2);
    });

    it("falls back to 1× on nonsense rather than freezing the capture", () => {
        assert.equal(normaliseRate(null), DEFAULT_RATE);
        assert.equal(normaliseRate("fast"), DEFAULT_RATE);
        assert.equal(normaliseRate(0), DEFAULT_RATE);
        assert.equal(normaliseRate(-1), DEFAULT_RATE);
    });
});

describe("readout", () => {
    it("says elapsed of total, and which frame that is", () => {
        assert.equal(readout(TIMELINE, 0), "0.0s / 0.9s · frame 1/10");
        assert.equal(readout(TIMELINE, 450), "0.5s / 0.9s · frame 5/10");
        // The end agrees with itself: elapsed equals total exactly when the counter says the
        // last frame is up.
        assert.equal(readout(TIMELINE, 900), "0.9s / 0.9s · frame 10/10");
    });
});

describe("positionOfFrame", () => {
    it("lands on a frame's first instant, clamped to the capture", () => {
        assert.equal(positionOfFrame(TIMELINE, 0), 0);
        assert.equal(positionOfFrame(TIMELINE, 9), 900);
        assert.equal(positionOfFrame(TIMELINE, 99), 900);
        assert.equal(positionOfFrame(TIMELINE, -5), 0);
    });
});
