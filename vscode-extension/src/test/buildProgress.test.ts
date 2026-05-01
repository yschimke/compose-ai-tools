import * as assert from 'assert';
import {
    BuildProgressTracker,
    PHASES,
    PhaseDurations,
    PhaseId,
    classifyTask,
    mergeCalibration,
} from '../buildProgress';

/**
 * Deterministic clock + tick scheduler so the tracker's behaviour is fully
 * inspectable without timers. `advance(ms)` moves wall-clock forward and
 * fires every scheduled tick whose interval elapses.
 */
class FakeClock {
    private t = 0;
    private timers: { interval: number; nextAt: number; cb: () => void }[] = [];
    now = (): number => this.t;
    setInterval = (cb: () => void, ms: number): unknown => {
        const handle = { interval: ms, nextAt: this.t + ms, cb };
        this.timers.push(handle);
        return handle;
    };
    clearInterval = (handle: unknown): void => {
        this.timers = this.timers.filter(h => h !== handle);
    };
    advance(ms: number): void {
        const target = this.t + ms;
        // Fire ticks deterministically in order.
        // eslint-disable-next-line no-constant-condition
        while (true) {
            const next = this.timers
                .filter(h => h.nextAt <= target)
                .sort((a, b) => a.nextAt - b.nextAt)[0];
            if (!next) { break; }
            this.t = next.nextAt;
            next.nextAt += next.interval;
            next.cb();
        }
        this.t = target;
    }
}

interface Snapshot {
    phase: PhaseId;
    label: string;
    percent: number;
    slow: boolean;
}

function makeTracker(opts: {
    calibration?: PhaseDurations;
    clock: FakeClock;
}): { tracker: BuildProgressTracker; states: Snapshot[] } {
    const states: Snapshot[] = [];
    const tracker = new BuildProgressTracker({
        onProgress: (s) => states.push({ ...s }),
        calibration: opts.calibration,
        now: opts.clock.now,
        setInterval: opts.clock.setInterval,
        clearInterval: opts.clock.clearInterval,
        tickMs: 100,
    });
    return { tracker, states };
}

describe('classifyTask', () => {
    it('routes render tasks to the rendering phase', () => {
        assert.strictEqual(classifyTask(':samples:android:renderPreviews'), 'rendering');
        assert.strictEqual(classifyTask(':app:renderAllPreviews'), 'rendering');
        assert.strictEqual(classifyTask(':app:renderAndroidResources'), 'rendering');
    });

    it('routes discover tasks to the discovering phase', () => {
        assert.strictEqual(classifyTask(':samples:cmp:discoverPreviews'), 'discovering');
        assert.strictEqual(classifyTask(':app:discoverAndroidResources'), 'discovering');
    });

    it('routes Kotlin/Java compile tasks to the compiling phase', () => {
        assert.strictEqual(classifyTask(':app:compileDebugKotlin'), 'compiling');
        assert.strictEqual(classifyTask(':app:compileDebugUnitTestKotlin'), 'compiling');
        assert.strictEqual(classifyTask(':app:compileDebugJavaWithJavac'), 'compiling');
        assert.strictEqual(classifyTask(':app:compileRenderShards'), 'compiling');
    });

    it('routes resource / classpath staging to resolving', () => {
        assert.strictEqual(classifyTask(':app:extractPreviewClasses'), 'resolving');
        assert.strictEqual(classifyTask(':app:generateRenderShards'), 'resolving');
        assert.strictEqual(classifyTask(':app:processDebugResources'), 'resolving');
    });

    it('returns null for uninteresting tasks', () => {
        assert.strictEqual(classifyTask(':composePreviewApplied'), null);
        assert.strictEqual(classifyTask(':app:composePreviewDoctor'), null);
    });
});

describe('BuildProgressTracker', () => {
    it('starts at zero and advances through phases on task signals', () => {
        const clock = new FakeClock();
        const { tracker, states } = makeTracker({ clock });
        tracker.start();
        const initial = states[states.length - 1];
        assert.strictEqual(initial.phase, 'starting');
        assert.strictEqual(initial.percent, 0);

        tracker.consume('> Configure project :samples:android\n');
        assert.strictEqual(states[states.length - 1].phase, 'configuring');

        tracker.consume('> Task :samples:android:compileDebugKotlin\n');
        assert.strictEqual(states[states.length - 1].phase, 'compiling');

        tracker.consume('> Task :samples:android:discoverPreviews\n');
        assert.strictEqual(states[states.length - 1].phase, 'discovering');

        tracker.consume('> Task :samples:android:renderPreviews\n');
        assert.strictEqual(states[states.length - 1].phase, 'rendering');

        tracker.finish();
        const last = states[states.length - 1];
        assert.strictEqual(last.phase, 'done');
        assert.strictEqual(last.percent, 1);
    });

    it('is monotonic — late stray signals do not move the bar backward', () => {
        const clock = new FakeClock();
        const { tracker, states } = makeTracker({ clock });
        tracker.start();
        tracker.consume('> Task :a:renderPreviews\n');
        const renderingPct = states[states.length - 1].percent;
        // A stray discoverPreviews line after rendering started must not
        // drag the bar backward — phase index is lower but tracker ignores
        // it.
        tracker.consume('> Task :a:discoverPreviews\n');
        const afterStray = states[states.length - 1].percent;
        assert.ok(afterStray >= renderingPct,
            `expected ${afterStray} >= ${renderingPct} (no rewind on out-of-order signal)`);
        assert.strictEqual(states[states.length - 1].phase, 'rendering');
    });

    it('animates within a phase via the tick timer without crossing phase boundary', () => {
        const clock = new FakeClock();
        const { tracker, states } = makeTracker({ clock });
        tracker.start();
        tracker.consume('> Task :a:compileDebugKotlin\n');
        const compileStart = states[states.length - 1].percent;

        // Advance well past the default compile duration. The bar should
        // approach but not exceed the compile phase's upper boundary.
        clock.advance(60_000);
        const long = states[states.length - 1].percent;
        assert.ok(long > compileStart, `expected progress to advance during ticks: ${long} > ${compileStart}`);

        tracker.consume('> Task :a:renderPreviews\n');
        const renderStart = states[states.length - 1].percent;
        assert.ok(renderStart >= long,
            `entering render must not rewind: ${renderStart} >= ${long}`);
        tracker.finish();
    });

    it('records phase durations for caller-side calibration', () => {
        const clock = new FakeClock();
        const { tracker } = makeTracker({ clock });
        tracker.start();
        clock.advance(200);
        tracker.consume('> Configure project :a\n');
        clock.advance(800);
        tracker.consume('> Task :a:compileDebugKotlin\n');
        clock.advance(3500);
        tracker.consume('> Task :a:discoverPreviews\n');
        clock.advance(400);
        tracker.consume('> Task :a:renderPreviews\n');
        clock.advance(7000);
        tracker.finish();
        const d = tracker.phaseDurations;
        assert.strictEqual(d.configuring, 800);
        assert.strictEqual(d.compiling, 3500);
        assert.strictEqual(d.discovering, 400);
        assert.strictEqual(d.rendering, 7000);
    });

    it('jumps to 100% on finish() and stays there', () => {
        const clock = new FakeClock();
        const { tracker, states } = makeTracker({ clock });
        tracker.start();
        tracker.finish();
        const last = states[states.length - 1];
        assert.strictEqual(last.percent, 1);
        // No ticks should fire after finish — the timer is cleared.
        const stateCount = states.length;
        clock.advance(5_000);
        assert.strictEqual(states.length, stateCount,
            'no progress events should fire after finish()');
    });

    it('abort() stops emitting without driving to 100%', () => {
        const clock = new FakeClock();
        const { tracker, states } = makeTracker({ clock });
        tracker.start();
        tracker.consume('> Task :a:renderPreviews\n');
        const beforeAbort = states[states.length - 1].percent;
        tracker.abort();
        clock.advance(5_000);
        const last = states[states.length - 1];
        // We never emitted a synthetic 100% — the last percent we sent is
        // whatever the renderer phase had reached.
        assert.ok(last.percent < 1, `aborted progress should not reach 1: ${last.percent}`);
        assert.ok(last.percent >= beforeAbort);
    });

    it('handles split chunks across newline boundaries', () => {
        const clock = new FakeClock();
        const { tracker, states } = makeTracker({ clock });
        tracker.start();
        // Split the same task line over four chunks — must still classify.
        tracker.consume('> Task ');
        tracker.consume(':app:co');
        tracker.consume('mpileDebugKotlin');
        tracker.consume('\n');
        assert.strictEqual(states[states.length - 1].phase, 'compiling');
    });

    it('uses calibration data to expand slow phases on the bar', () => {
        const clock = new FakeClock();
        const calibration: PhaseDurations = {
            configuring: 500,
            compiling: 50_000, // pretend this module is very slow at compile
            discovering: 200,
            rendering: 1_000,
            loading: 100,
        };
        const { tracker, states } = makeTracker({ clock, calibration });
        tracker.start();
        tracker.consume('> Task :a:compileDebugKotlin\n');
        const compileStart = states[states.length - 1].percent;
        tracker.consume('> Task :a:renderPreviews\n');
        const renderStart = states[states.length - 1].percent;
        const compileSpan = renderStart - compileStart;
        // Compile owns ~50/51.8 of the bar after configure, so its span
        // should dominate (>50% of the [compileStart, 1] tail).
        assert.ok(compileSpan > 0.5,
            `compile span should be large with this calibration, got ${compileSpan}`);
        tracker.finish();
    });

    it('every phase descriptor has a non-empty label', () => {
        for (const p of PHASES) {
            assert.ok(p.label.length > 0, `phase ${p.id} missing label`);
        }
    });

    it('flags slow=true once elapsed exceeds 2x the phase estimate', () => {
        const clock = new FakeClock();
        // Calibrate compile to 1000ms — so any tick after 2000ms in compile
        // counts as slow.
        const calibration: PhaseDurations = { compiling: 1000 };
        const { tracker, states } = makeTracker({ clock, calibration });
        tracker.start();
        tracker.consume('> Task :a:compileDebugKotlin\n');
        const enteredCompile = states[states.length - 1];
        assert.strictEqual(enteredCompile.slow, false,
            'just-entered phase should not be slow');

        clock.advance(1500);
        const halfwayOver = states[states.length - 1];
        assert.strictEqual(halfwayOver.slow, false,
            '1.5x expected is not yet slow');

        clock.advance(1000); // total 2500ms — past the 2x threshold
        const slow = states[states.length - 1];
        assert.strictEqual(slow.slow, true, '>2x expected should flag slow');

        // Transition out of the slow phase — slow flag resets for the next.
        tracker.consume('> Task :a:renderPreviews\n');
        const renderJustStarted = states[states.length - 1];
        assert.strictEqual(renderJustStarted.slow, false,
            'fresh phase should reset the slow flag');
        tracker.finish();
    });
});

describe('mergeCalibration', () => {
    it('returns the latest sample when no prior exists', () => {
        const merged = mergeCalibration({}, { compiling: 4000 });
        assert.strictEqual(merged.compiling, 4000);
    });

    it('blends prior and latest with EMA', () => {
        const merged = mergeCalibration({ compiling: 1000 }, { compiling: 3000 });
        // 0.5 * 1000 + 0.5 * 3000 = 2000
        assert.strictEqual(merged.compiling, 2000);
    });

    it('preserves prior samples for phases not seen this run', () => {
        const merged = mergeCalibration(
            { compiling: 1000, rendering: 5000 },
            { compiling: 2000 },
        );
        assert.strictEqual(merged.rendering, 5000);
        assert.strictEqual(merged.compiling, 1500);
    });
});
