/**
 * Phase parser + progress estimator for Gradle-driven preview refreshes.
 *
 * Watches `:<module>:<task>` lines in Gradle stdout and maps them onto a
 * coarse phase machine: configuring → compiling → resolving → discovering →
 * rendering → loading. Each phase has a default weight (fraction of the
 * progress bar it occupies); per-module historical durations override the
 * default once we've seen at least one full refresh, so the bar's animation
 * speed matches what that module actually takes.
 *
 * The estimator keeps the bar moving even when Gradle is silent for tens of
 * seconds (Robolectric sandbox bring-up, kotlinc on a cold cache) by
 * interpolating elapsed-time-in-phase against the phase's expected duration
 * and asymptotically approaching the phase's upper boundary. It will never
 * exceed that boundary until the next phase signal lands, so the bar can't
 * lap itself or finish early.
 */
export type PhaseId =
    | 'starting'
    | 'configuring'
    | 'compiling'
    | 'resolving'
    | 'discovering'
    | 'rendering'
    | 'loading'
    | 'done';

export interface PhaseDescriptor {
    id: PhaseId;
    /** User-facing label shown next to the bar. */
    label: string;
    /** Default fraction of the bar this phase occupies, in [0, 1]. */
    weight: number;
    /** Default expected duration in ms when no calibration data exists. */
    defaultMs: number;
}

/**
 * Order matters — the bar advances monotonically through this list. Phase
 * weights are normalised at use-time so callers can tweak any single weight
 * without re-balancing the rest. `starting` sits before configuring as a
 * zero-weight slot so the bar can show "Starting…" before the first task
 * line arrives without committing to a percent.
 */
export const PHASES: readonly PhaseDescriptor[] = [
    { id: 'starting',    label: 'Starting',          weight: 0,    defaultMs: 200 },
    { id: 'configuring', label: 'Configuring Gradle', weight: 0.08, defaultMs: 1500 },
    { id: 'compiling',   label: 'Compiling Kotlin',  weight: 0.30, defaultMs: 4000 },
    { id: 'resolving',   label: 'Resolving classpath', weight: 0.07, defaultMs: 1000 },
    { id: 'discovering', label: 'Discovering @Preview', weight: 0.10, defaultMs: 1500 },
    { id: 'rendering',   label: 'Rendering previews', weight: 0.35, defaultMs: 8000 },
    { id: 'loading',     label: 'Loading images',    weight: 0.10, defaultMs: 600 },
    { id: 'done',        label: 'Done',              weight: 0,    defaultMs: 0 },
];

const PHASE_INDEX: Record<PhaseId, number> = (() => {
    const out: Partial<Record<PhaseId, number>> = {};
    PHASES.forEach((p, i) => { out[p.id] = i; });
    return out as Record<PhaseId, number>;
})();

export interface ProgressState {
    phase: PhaseId;
    label: string;
    /** [0, 1]. Monotonic within a single tracker. 1 only when phase === 'done'. */
    percent: number;
    /**
     * True when this phase has been running for more than [SLOW_RATIO]× its
     * expected duration. Surfaces as a warning tint on the bar plus a
     * "(slow)" label suffix — cheap signal that a cold cache or version
     * bump is at play, without popping a notification.
     */
    slow: boolean;
}

/**
 * How far past the expected phase duration counts as "slow". 2× chosen as a
 * soft threshold — the asymptotic curve already absorbs short overruns
 * gracefully, so we only flag when the estimate is meaningfully wrong.
 */
const SLOW_RATIO = 2;

/** Recorded duration per phase, used to calibrate future estimates. */
export type PhaseDurations = Partial<Record<PhaseId, number>>;

/**
 * Maps a parsed task name to the phase it belongs to. Returns null when the
 * task is uninteresting (e.g. `composePreviewApplied` bootstrap, doctor,
 * internal aggregators that fire near-instantly). Order in this dispatch
 * matters: more-specific patterns first.
 */
export function classifyTask(taskName: string): PhaseId | null {
    // Strip the leading ':' and any project path, focus on the task segment.
    const segments = taskName.split(':').filter(s => s.length > 0);
    const tail = segments[segments.length - 1] ?? taskName;
    const lower = tail.toLowerCase();

    if (lower.includes('renderpreviews') || lower.includes('renderallpreviews')
        || lower.includes('renderandroidresources')) {
        return 'rendering';
    }
    if (lower.includes('discoverpreviews') || lower.includes('discoverandroidresources')
        || lower.includes('collectpreviewinfo')) {
        return 'discovering';
    }
    if (lower.startsWith('compile')
        && (lower.endsWith('kotlin') || lower.endsWith('java') || lower.endsWith('javac')
            || lower.includes('javawith') || lower.includes('rendershards'))) {
        return 'compiling';
    }
    // AGP task names embed the variant in the middle: `processDebugResources`,
    // `mergeReleaseResources`. Match the verb prefix + the trailing
    // `resources` suffix rather than a literal substring.
    if (lower.includes('extractpreviewclasses') || lower.includes('generaterendershards')
        || /^(merge|process)\w*resources$/.test(lower)) {
        return 'resolving';
    }
    return null;
}

const TASK_LINE_RE = /^>\s*Task\s+(:[^\s]+)/;
const CONFIGURE_LINE_RE = /^>\s*Configure project\b/;
const BUILD_DONE_RE = /^BUILD (SUCCESSFUL|FAILED)\b/;

export interface ProgressTrackerOptions {
    /** Called every time the visible state changes (phase shift or percent tick). */
    onProgress: (state: ProgressState) => void;
    /** Per-phase calibration from prior runs. Falls back to phase defaults. */
    calibration?: PhaseDurations;
    /** Wall-clock source — injected for tests. Defaults to Date.now. */
    now?: () => number;
    /** Tick scheduler — injected for tests. Defaults to setInterval. */
    setInterval?: (cb: () => void, ms: number) => unknown;
    /** Tick canceller — injected for tests. Defaults to clearInterval. */
    clearInterval?: (handle: unknown) => void;
    /** Tick interval; the bar smooths between phase boundaries. */
    tickMs?: number;
}

/**
 * Streaming progress tracker. Single instance per refresh — call [start],
 * feed Gradle stdout chunks via [consume], and [finish] when the build
 * completes. Emits monotonic progress updates through the supplied callback
 * and records per-phase durations into [phaseDurations] for the caller to
 * persist as calibration.
 */
export class BuildProgressTracker {
    private buffer = '';
    private currentPhase: PhaseId = 'starting';
    private phaseEnteredAt = 0;
    private startedAt = 0;
    private lastEmittedPercent = 0;
    private finished = false;
    private tickHandle: unknown = null;
    private readonly nowFn: () => number;
    private readonly setIntervalFn: (cb: () => void, ms: number) => unknown;
    private readonly clearIntervalFn: (handle: unknown) => void;
    private readonly tickMs: number;
    private readonly calibration: PhaseDurations;
    private readonly onProgress: (state: ProgressState) => void;
    /**
     * Wall-clock duration each phase actually took on this run. Populated as
     * phases transition; the caller persists this map after a successful
     * build so the next refresh of the same module can interpolate at the
     * right rate instead of using global defaults.
     */
    public readonly phaseDurations: PhaseDurations = {};

    constructor(opts: ProgressTrackerOptions) {
        this.onProgress = opts.onProgress;
        this.calibration = opts.calibration ?? {};
        this.nowFn = opts.now ?? Date.now;
        this.setIntervalFn = opts.setInterval ?? ((cb, ms) => setInterval(cb, ms));
        this.clearIntervalFn = opts.clearInterval ?? ((h) => clearInterval(h as ReturnType<typeof setInterval>));
        this.tickMs = opts.tickMs ?? 200;
    }

    start(): void {
        const now = this.nowFn();
        this.startedAt = now;
        this.phaseEnteredAt = now;
        this.currentPhase = 'starting';
        this.lastEmittedPercent = 0;
        this.finished = false;
        this.emit();
        this.tickHandle = this.setIntervalFn(() => this.tick(), this.tickMs);
    }

    /**
     * Forces a phase transition independent of Gradle output. Used by the
     * extension to announce the post-Gradle "loading images" phase, which
     * Gradle itself doesn't surface (the work happens in the extension after
     * the task returns).
     */
    enterPhase(phase: PhaseId): void {
        if (this.finished) { return; }
        this.transitionTo(phase);
    }

    /**
     * Feed a chunk of Gradle stdout. Buffers partial lines; safe to call
     * repeatedly with arbitrary chunk boundaries.
     */
    consume(chunk: string): void {
        if (this.finished) { return; }
        this.buffer += chunk;
        let nl = this.buffer.indexOf('\n');
        while (nl !== -1) {
            const line = this.buffer.slice(0, nl);
            this.buffer = this.buffer.slice(nl + 1);
            this.scanLine(line);
            nl = this.buffer.indexOf('\n');
        }
        // Bound runaway buffering when output arrives without newlines.
        if (this.buffer.length > 16 * 1024) {
            this.scanLine(this.buffer);
            this.buffer = '';
        }
    }

    /** Marks the build finished (success or failure). Drives the bar to 100%. */
    finish(): void {
        if (this.finished) { return; }
        // Close out the running phase so its duration lands in phaseDurations.
        this.recordPhaseDuration();
        this.finished = true;
        this.currentPhase = 'done';
        this.lastEmittedPercent = 1;
        this.emit();
        this.stopTicking();
    }

    /** Cancel without driving to 100% — used when a refresh is superseded. */
    abort(): void {
        if (this.finished) { return; }
        this.finished = true;
        this.stopTicking();
    }

    private scanLine(line: string): void {
        if (CONFIGURE_LINE_RE.test(line) && this.currentPhase === 'starting') {
            this.transitionTo('configuring');
            return;
        }
        const taskMatch = TASK_LINE_RE.exec(line);
        if (taskMatch) {
            const phase = classifyTask(taskMatch[1]);
            if (phase) { this.transitionTo(phase); }
            return;
        }
        if (BUILD_DONE_RE.test(line)) {
            // Gradle reports BUILD SUCCESSFUL after every task completes; the
            // extension still has post-task work to do (image loading), so we
            // jump to `loading` rather than `done` here. The caller calls
            // finish() once that work is complete.
            this.transitionTo('loading');
        }
    }

    private transitionTo(phase: PhaseId): void {
        const targetIdx = PHASE_INDEX[phase];
        const currentIdx = PHASE_INDEX[this.currentPhase];
        // Phases are monotonic — a stray `discoverPreviews` line landing in the
        // middle of a render doesn't drag the bar backward. Same-phase
        // signals are no-ops.
        if (targetIdx <= currentIdx) { return; }
        this.recordPhaseDuration();
        this.currentPhase = phase;
        this.phaseEnteredAt = this.nowFn();
        this.emit();
    }

    private recordPhaseDuration(): void {
        const now = this.nowFn();
        const elapsed = now - this.phaseEnteredAt;
        if (elapsed > 0 && this.currentPhase !== 'starting' && this.currentPhase !== 'done') {
            this.phaseDurations[this.currentPhase] = elapsed;
        }
    }

    private tick(): void {
        if (this.finished) { return; }
        this.emit();
    }

    private emit(): void {
        const state = this.computeState();
        // Don't emit a percent that's lower than what we just sent — the bar
        // is monotonic and going backward looks like a bug.
        if (state.percent < this.lastEmittedPercent && state.phase !== 'done') {
            state.percent = this.lastEmittedPercent;
        }
        this.lastEmittedPercent = state.percent;
        this.onProgress(state);
    }

    private computeState(): ProgressState {
        const phase = PHASES[PHASE_INDEX[this.currentPhase]];
        if (this.currentPhase === 'done') {
            return { phase: 'done', label: 'Done', percent: 1, slow: false };
        }
        if (this.currentPhase === 'starting') {
            return { phase: 'starting', label: phase.label, percent: 0, slow: false };
        }
        const { phaseStart, phaseEnd } = this.phaseBoundaries(this.currentPhase);
        const expectedMs = this.expectedPhaseMs(this.currentPhase);
        const elapsed = this.nowFn() - this.phaseEnteredAt;
        // Asymptotic curve: hits ~63% of the phase span at expectedMs, ~95%
        // at 3*expectedMs. Never crosses the next phase boundary unless a
        // task signal explicitly transitions us there. Keeps the bar honest
        // when a phase outruns its estimate (cold Robolectric sandbox).
        const ratio = expectedMs > 0
            ? 1 - Math.exp(-elapsed / expectedMs)
            : 0;
        // Soft cap at 95% of the phase span — leaves visible headroom for the
        // transition into the next phase, so the bar always has somewhere to
        // jump forward to.
        const fraction = Math.min(0.95, ratio);
        const percent = phaseStart + (phaseEnd - phaseStart) * fraction;
        const slow = expectedMs > 0 && elapsed > SLOW_RATIO * expectedMs;
        return { phase: this.currentPhase, label: phase.label, percent, slow };
    }

    /**
     * Expected duration of a phase: calibrated from prior runs when available,
     * otherwise the phase's default. Floors at 250ms to stop very-fast hot
     * builds from snapping the bar instantaneously (which reads as no
     * animation at all).
     */
    private expectedPhaseMs(phase: PhaseId): number {
        const calibrated = this.calibration[phase];
        const desc = PHASES[PHASE_INDEX[phase]];
        const ms = calibrated ?? desc.defaultMs;
        return Math.max(250, ms);
    }

    /**
     * Returns the [start, end] percent-positions of `phase` on the bar. Built
     * from this run's calibration so a slow `compiling` phase visibly takes
     * up more of the bar than a fast one. Falls back to the static default
     * weights when no calibration is supplied.
     */
    private phaseBoundaries(phase: PhaseId): { phaseStart: number; phaseEnd: number } {
        const weights = this.normalisedWeights();
        let acc = 0;
        for (const p of PHASES) {
            if (p.id === phase) {
                return { phaseStart: acc, phaseEnd: acc + weights[p.id] };
            }
            acc += weights[p.id];
        }
        return { phaseStart: 0, phaseEnd: 1 };
    }

    private normalisedWeights(): Record<PhaseId, number> {
        // Convert calibrated durations into weights when we have them. Falls
        // back to the descriptor weights when a phase hasn't been observed
        // yet (e.g. first run on a module). The mix is deliberate: we don't
        // want a single missing observation to collapse a phase to 0.
        const raw: Record<PhaseId, number> = {} as Record<PhaseId, number>;
        let total = 0;
        for (const p of PHASES) {
            const fromCalib = this.calibration[p.id];
            const w = fromCalib != null
                ? Math.max(50, fromCalib) // ms — clamp so a 0ms phase doesn't vanish
                : Math.max(50, p.defaultMs * (p.weight > 0 ? 1 : 0));
            raw[p.id] = w;
            total += w;
        }
        if (total <= 0) {
            // No data anywhere — fall back to descriptor weights as-is.
            const out: Record<PhaseId, number> = {} as Record<PhaseId, number>;
            for (const p of PHASES) { out[p.id] = p.weight; }
            return out;
        }
        const out: Record<PhaseId, number> = {} as Record<PhaseId, number>;
        for (const p of PHASES) { out[p.id] = raw[p.id] / total; }
        return out;
    }

    private stopTicking(): void {
        if (this.tickHandle !== null) {
            this.clearIntervalFn(this.tickHandle);
            this.tickHandle = null;
        }
    }
}

/**
 * Exponential moving average used by the extension to merge the durations
 * recorded on this run into the persistent calibration store. Heavy weight
 * on the latest sample (alpha=0.5) so a single fast or slow run is visible
 * on the next refresh, without making estimates pure noise.
 */
export function mergeCalibration(prior: PhaseDurations, latest: PhaseDurations): PhaseDurations {
    const out: PhaseDurations = { ...prior };
    for (const key of Object.keys(latest) as PhaseId[]) {
        const lv = latest[key];
        if (lv == null) { continue; }
        const pv = out[key];
        out[key] = pv == null ? lv : Math.round(pv * 0.5 + lv * 0.5);
    }
    return out;
}
