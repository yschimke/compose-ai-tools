// `<progress-bar>` — replaces the imperative progress bar that used to
// live in `behavior.ts` (`setProgress` / `clearProgress` and friends).
//
// Subscribes to `setProgress` and `clearProgress` messages on the
// window directly so the bar is independent of the rest of the
// preview-app behaviour. Preserves the existing two-stage timing
// exactly — see comments inline.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { classMap } from "lit/directives/class-map.js";
import { styleMap } from "lit/directives/style-map.js";

interface SetProgressMessage {
    command: "setProgress";
    phase?: string;
    label?: string;
    percent?: number;
    slow?: boolean;
}

interface ClearProgressMessage {
    command: "clearProgress";
}

type IncomingMessage =
    | SetProgressMessage
    | ClearProgressMessage
    | { command: string };

interface ProgressVisual {
    pct: number;
    label: string;
    slow: boolean;
    finishing: boolean;
}

const IDLE: ProgressVisual = {
    pct: 0,
    label: "",
    slow: false,
    finishing: false,
};

// Don't paint a fill until ~200ms of in-flight work has accumulated, so
// warm-cache refreshes never pulse the bar. The strip itself is ALWAYS
// mounted (no display:none toggle), so deferring the paint keeps the
// layout identical between idle and pending — only the fill width and
// label text change once the deferral elapses.
const PROGRESS_PAINT_DELAY_MS = 200;

// After landing at 100% the bar holds for a beat so the user sees the
// completed state, then resets the fill + label to their idle look.
// The strip itself stays mounted.
const PROGRESS_FINISH_HOLD_MS = 600;

@customElement("progress-bar")
export class ProgressBar extends LitElement {
    @state() private visual: ProgressVisual = IDLE;

    private active = false;
    private hideTimer: ReturnType<typeof setTimeout> | null = null;
    private paintTimer: ReturnType<typeof setTimeout> | null = null;
    private pending: ProgressVisual | null = null;

    private readonly onMessage = (
        event: MessageEvent<IncomingMessage>,
    ): void => {
        const msg = event.data;
        if (!msg) return;
        if (msg.command === "setProgress") {
            const m = msg as SetProgressMessage;
            this.setProgress(m.label ?? "", m.percent ?? 0, !!m.slow);
        } else if (msg.command === "clearProgress") {
            this.clearProgress();
        }
    };

    // Light DOM so existing `media/preview.css` rules apply unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        window.addEventListener("message", this.onMessage);
    }

    disconnectedCallback(): void {
        window.removeEventListener("message", this.onMessage);
        this.cancelTimers();
        super.disconnectedCallback();
    }

    private setProgress(label: string, percent: number, slow: boolean): void {
        if (this.hideTimer) {
            clearTimeout(this.hideTimer);
            this.hideTimer = null;
        }
        const next: ProgressVisual = {
            pct: clamp01(percent),
            label,
            slow,
            finishing: false,
        };
        // Already painting — apply directly so the bar stays in
        // lockstep with the tracker rather than re-entering the
        // deferral window.
        if (this.active) {
            this.apply(next);
            return;
        }
        // Latched state for when the deferral timer fires.
        this.pending = next;
        // Terminal state: paint immediately so the user gets a visible
        // completion even on instant refreshes (rare path — tracker
        // emits an immediate done=100% on an up-to-date discover).
        if (next.pct >= 1) {
            this.apply(next);
            return;
        }
        if (this.paintTimer === null) {
            this.paintTimer = setTimeout(() => {
                this.paintTimer = null;
                if (this.pending) {
                    this.apply(this.pending);
                }
            }, PROGRESS_PAINT_DELAY_MS);
        }
    }

    private clearProgress(): void {
        this.cancelTimers();
        this.pending = null;
        this.active = false;
        this.visual = IDLE;
    }

    private apply(state: ProgressVisual): void {
        this.active = true;
        this.visual = { ...state, finishing: false };
        if (state.pct >= 1) {
            this.visual = { ...this.visual, finishing: true };
            this.hideTimer = setTimeout(() => {
                this.active = false;
                this.visual = IDLE;
                this.hideTimer = null;
            }, PROGRESS_FINISH_HOLD_MS);
        }
    }

    private cancelTimers(): void {
        if (this.hideTimer) {
            clearTimeout(this.hideTimer);
            this.hideTimer = null;
        }
        if (this.paintTimer) {
            clearTimeout(this.paintTimer);
            this.paintTimer = null;
        }
    }

    protected render(): TemplateResult {
        const { pct, label, slow, finishing } = this.visual;
        const pctRounded = Math.round(pct * 100);
        const slowSuffix = slow ? " (slow)" : "";
        const labelText = label ? `${label}${slowSuffix} · ${pctRounded}%` : "";
        return html`
            <div
                class=${classMap({
                    "progress-bar": true,
                    "progress-slow": slow,
                    "progress-finishing": finishing,
                })}
                role="progressbar"
                aria-label="Refresh progress"
                aria-valuemin="0"
                aria-valuemax="100"
                aria-valuenow=${pctRounded}
            >
                <div class="progress-label">${labelText}</div>
                <div class="progress-track">
                    <div
                        class="progress-fill"
                        style=${styleMap({
                            width: `${(pct * 100).toFixed(1)}%`,
                        })}
                    ></div>
                </div>
            </div>
        `;
    }
}

function clamp01(n: number): number {
    return Math.max(0, Math.min(1, n));
}
