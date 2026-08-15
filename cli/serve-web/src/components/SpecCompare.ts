// `<cp-spec-compare>` — the viewer's design-spec diff options. Replaces `assets/spec-compare.js`.
//
// The spec lane already put the imported design reference on the same stage as the render, so the
// two could be flipped between. Flipping is a weak instrument: it answers "are these different?" by
// asking a visitor to hold one frame in their head while looking at the other, which finds a
// wholesale colour change and misses the 4dp of padding that is the actual bug. The focused
// `/compare/<id>` page has the real instruments, but reaching it means leaving the viewer — and
// with it every override, knob and theme that produced the render worth comparing. So the
// instruments come to the lane: Spec, Diff, Triptych, Slider, one click apart.
//
// Every surface is drawn from ONE normalisation pass, so the diff, the three panels and the wipe
// are all in the same pixel space — a reference exported at a different scale than the render lines
// up here instead of reading as a total mismatch.
//
// Renders nothing of its own: the panels, canvases and buttons are all server-rendered, because the
// lane has to exist before any of this runs. `serve.css` hides the tag.
//
// The decisions live next door: `spec/views.ts` (who gets to pick the view — three sources compete
// and they are not equal), `spec/verdict.ts` (what the chip and the readout say), `spec/wipe.ts`
// (where the seam sits), `dom/sameOrigin.ts` (what may reach a canvas at all).

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import { whenParsed } from "../dom/whenParsed.js";
import { compareApi, type NormalisedPair } from "../compare/api.js";
import { urlState } from "../urlState.js";
import { sameOrigin } from "../dom/sameOrigin.js";
import {
    DEFAULT_VIEW,
    INITIAL,
    choose,
    hydrate,
    onOpen,
    prefer,
    viewParam,
    type SpecView,
    type ViewChoice,
} from "../spec/views.js";
import {
    COMPARING,
    UNAVAILABLE,
    changedPercentOf,
    chipText,
    matchBand,
    readout,
} from "../spec/verdict.js";
import { rangeValueAt, seamX, splitAt, splitFraction } from "../spec/wipe.js";

/** What `viewer.js` calls on the way into and out of the lane. */
interface SpecCompareApi {
    view(): SpecView;
    prefer(next: string): void;
    hydrate(next: string | null): void;
    open(url: string): void;
    close(): void;
}

declare global {
    interface Window {
        cpSpecCompare?: SpecCompareApi;
    }
}

@customElement("cp-spec-compare")
export class SpecCompare extends LitElement {
    private installed = false;
    private root: HTMLElement | null = null;
    private compare: HTMLElement | null = null;
    private views: HTMLElement | null = null;
    private chip: HTMLElement | null = null;
    /** The published verdict's band, so leaving the lane restores the chip exactly as served. */
    private bakedBand = "";
    private referenceUrl = "";
    private actualUrl = "";

    private choice: ViewChoice = INITIAL;
    private open = false;

    /**
     * The normalised pair currently painted, and the `(reference, actual)` it came from. A view
     * switch inside one lane visit must not re-run the comparison; a re-entry after the render
     * changed underneath (a new theme, a new knob) must.
     */
    private frames: NormalisedPair | null = null;
    private framesKey = "";
    /**
     * The live match those frames scored. Re-entering against an unchanged pair restores this
     * rather than leaving the published number on the chip beside a readout showing the live one —
     * two numbers for one comparison.
     */
    private framesMatch: number | null = null;
    /** Bumped to abandon a comparison in flight. */
    private generation = 0;
    private cleanups: Array<() => void> = [];

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // `viewer.js` calls `window.cpSpecCompare` as it enters the lane, so the global has to be up as
    // soon as the markup exists rather than a parse later. Same shape as `<cp-rc-lanes>`.
    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        if (window.cpSpecCompare === this.api) delete window.cpSpecCompare;
        this.installed = false;
        // Abandon anything in flight rather than letting it paint into a lane nobody is watching.
        this.generation++;
        super.disconnectedCallback();
    }

    private api: SpecCompareApi = {
        view: () => this.choice.view,
        prefer: (next) => {
            this.choice = prefer(this.choice, next);
        },
        hydrate: (next) => {
            this.choice = hydrate(this.choice, next);
            this.apply();
        },
        open: (url) => {
            this.open = true;
            this.actualUrl = url || "";
            // Deliberately NOT written to the address bar here. `viewer.js` calls this from inside
            // `enterMode`'s spec branch — before its closing `syncUrl()`, and therefore while the
            // current history entry is still the lane being LEFT. Writing now would stamp a
            // lane-scoped `specView` onto the outgoing render entry, so Back would land on a PNG
            // URL carrying it. `syncUrl` re-emits it from `view()` in the same push that records
            // `mode=spec`, which is where it belongs.
            this.choice = onOpen(this.choice);
            this.apply();
        },
        close: () => {
            this.open = false;
            // `close()` puts the published verdict back, and a normalisation or score resolving
            // afterwards would otherwise still pass its generation check and paint a live —
            // possibly override-specific — number onto the chip while the published render is back.
            this.generation++;
            this.setChipVerdict(null);
            this.apply();
        },
    };

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        this.root = document.querySelector<HTMLElement>(".cp-viewer");
        this.compare = document.getElementById("cp-spec-compare");
        this.views = document.getElementById("cp-spec-views");
        // Inert unless the served catalog published a design reference for this preview.
        if (!this.root || !this.compare || !this.views) return false;
        this.installed = true;

        this.chip = document.getElementById("cp-spec-chip");
        this.bakedBand = this.chip?.getAttribute("data-spec-match") ?? "";
        this.referenceUrl = this.compare.getAttribute("data-reference") ?? "";

        this.on(this.views, "click", (event) => {
            const button = (event.target as Element | null)?.closest?.(
                "[data-cp-spec-view]",
            );
            if (!button || !this.views?.contains(button)) return;
            this.setView(button.getAttribute("data-cp-spec-view") ?? "");
        });
        const range = this.range();
        // A drag is continuous input: redraw every frame, but leave the URL alone. The chosen VIEW
        // is the shareable state; where the seam happened to stop is not.
        if (range) this.on(range, "input", () => this.drawWipe());
        this.bindDrag();

        window.cpSpecCompare = this.api;
        this.apply();
        return true;
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }

    private canvas(id: string): HTMLCanvasElement | null {
        return document.getElementById(id) as HTMLCanvasElement | null;
    }

    private range(): HTMLInputElement | null {
        return document.getElementById(
            "cp-spec-wipe-range",
        ) as HTMLInputElement | null;
    }

    private setView(next: string): void {
        const before = this.choice.view;
        this.choice = choose(this.choice, next);
        if (this.choice.view === before) return;
        this.apply();
        // A discrete choice, so it PUSHES: Back returns to the view you were looking at, the same
        // way it returns to the previous lane or theme.
        urlState()?.push({ specView: viewParam(this.choice.view) });
    }

    /**
     * Reconcile the stage with the chosen view.
     *
     * `spec` deliberately touches nothing but its own container: the raster `<img>` viewer.js put
     * on the stage stays the whole surface, so a session that never picks a comparison view behaves
     * exactly as it did before this existed. The other three hide that `<img>` from CSS (see
     * `.cp-viewer[data-spec-view]` in `serve.css`) and take the stage themselves.
     */
    private apply(): void {
        const view = this.choice.view;
        this.root?.setAttribute("data-spec-view", view);
        this.compare?.setAttribute("data-view", view);
        if (this.views) this.views.hidden = !this.open;
        const score = document.getElementById("cp-spec-score");
        if (score) score.hidden = !this.open || view === DEFAULT_VIEW;
        if (this.compare)
            this.compare.hidden = !this.open || view === DEFAULT_VIEW;
        for (const button of this.views?.querySelectorAll(
            "[data-cp-spec-view]",
        ) ?? []) {
            button.setAttribute(
                "aria-pressed",
                String(button.getAttribute("data-cp-spec-view") === view),
            );
        }
        if (this.open && view !== DEFAULT_VIEW) void this.compute();
    }

    private setScore(text: string): void {
        const score = document.getElementById("cp-spec-score");
        if (score) score.textContent = text;
    }

    /**
     * Put a freshly-computed match on the chip, replacing the published one — or `null` to restore.
     *
     * The chip carries the score baked at publish, which describes the PUBLISHED pixels. The moment
     * an override, a knob or a theme moves the render, that number describes a frame that is no
     * longer on the stage, so once this lane has scored what is actually in front of the visitor,
     * that is what the chip must show. Chip and readout are then one instrument reporting one
     * comparison, which is the whole reason the verdict moved onto the chip.
     */
    private setChipVerdict(percent: number | null): void {
        const chip = this.chip;
        if (!chip) return;
        const name =
            chip.getAttribute("data-spec-chip-name") || chip.textContent || "";
        if (percent === null) {
            chip.textContent =
                chip.getAttribute("data-spec-chip-label") || name;
            if (this.bakedBand)
                chip.setAttribute("data-spec-match", this.bakedBand);
            else chip.removeAttribute("data-spec-match");
            return;
        }
        chip.textContent = chipText(name, percent);
        chip.setAttribute("data-spec-match", matchBand(percent));
    }

    /** Paint every comparison surface from one normalisation of the current pair. */
    private async compute(): Promise<void> {
        const api = compareApi();
        const reference = sameOrigin(this.referenceUrl, location.origin);
        const actual = sameOrigin(this.actualUrl, location.origin);
        const key = `${reference}\n${actual}`;
        if (!reference || !actual || !api) {
            this.frames = null;
            this.framesKey = "";
            this.setScore(UNAVAILABLE);
            return;
        }
        if (key === this.framesKey && this.frames) {
            this.drawWipe();
            // The readout still holds this pair's live numbers, so the chip has to come back to the
            // same ones. Without this an override-bearing page re-entering the lane showed the
            // PUBLISHED score beside the live readout — two numbers for one comparison.
            if (this.framesMatch !== null)
                this.setChipVerdict(this.framesMatch);
            return;
        }
        const generation = ++this.generation;
        this.setScore(COMPARING);
        try {
            const next = await api.normaliseImageUrls(reference, actual);
            if (generation !== this.generation) return;
            this.frames = next;
            this.framesKey = key;
            this.copyInto(next.reference, this.canvas("cp-spec-reference"));
            this.copyInto(next.candidate, this.canvas("cp-spec-actual"));
            const diff = this.canvas("cp-spec-diff");
            const changed = diff
                ? api.diffCanvases(next.reference, next.candidate, diff)
                : 0;
            this.drawWipe();
            // Scored from the frames just decoded, NOT by re-requesting the two URLs. An
            // override-bearing `/render` is `no-store`, so asking again would be a second render —
            // and a second render can come back different, leaving the percentage describing a
            // frame other than the diff beside it.
            const result = await api.scoreImages(
                next.images[0],
                next.images[1],
            );
            if (generation !== this.generation) return;
            this.setScore(
                readout(
                    result.percent,
                    changedPercentOf(changed, next.width, next.height),
                    result.geometry,
                ),
            );
            this.framesMatch = result.percent;
            this.setChipVerdict(result.percent);
        } catch {
            if (generation !== this.generation) return;
            this.frames = null;
            this.framesKey = "";
            this.framesMatch = null;
            this.setScore(UNAVAILABLE);
        }
    }

    private copyInto(
        source: CanvasImageSource & { width: number; height: number },
        target: HTMLCanvasElement | null,
    ): void {
        if (!target) return;
        target.width = source.width;
        target.height = source.height;
        target.getContext("2d")?.drawImage(source, 0, 0);
    }

    /**
     * The wipe: spec on the left of the split, render on the right, in one frame at one size.
     *
     * Drawn rather than clip-path'd over two stacked elements because the two sources are already
     * canvases in a shared pixel space — compositing here keeps the split exact at every fraction
     * and costs two `drawImage` calls per drag frame.
     */
    private drawWipe(): void {
        const wipe = this.canvas("cp-spec-wipe-canvas");
        const frames = this.frames;
        if (!wipe || !frames) return;
        const { width, height } = frames;
        wipe.width = width;
        wipe.height = height;
        const context = wipe.getContext("2d");
        if (!context) return;
        const split = splitAt(width, splitFraction(this.range()?.value));
        context.clearRect(0, 0, width, height);
        context.drawImage(frames.reference, 0, 0);
        if (split < width) {
            context.save();
            context.beginPath();
            context.rect(split, 0, width - split, height);
            context.clip();
            context.drawImage(frames.candidate, 0, 0);
            context.restore();
        }
        // The seam, in the diff map's magenta so the two comparison surfaces read as one instrument.
        context.fillStyle = "#e52e73";
        context.fillRect(seamX(width, split), 0, 2, height);
    }

    /**
     * Dragging on the frame itself is what "slider" means to anyone who has used one; the range
     * input underneath remains the keyboard/assistive path and stays the single source of the split.
     */
    private bindDrag(): void {
        const wipe = this.canvas("cp-spec-wipe-canvas");
        const range = this.range();
        if (!wipe || !range || !this.compare?.querySelector(".cp-spec-wipe"))
            return;
        let dragging = false;
        const seekTo = (event: PointerEvent) => {
            const value = rangeValueAt(
                event.clientX,
                wipe.getBoundingClientRect(),
            );
            if (value === null) return;
            range.value = value;
            this.drawWipe();
        };
        this.on(wipe, "pointerdown", (event) => {
            dragging = true;
            try {
                wipe.setPointerCapture((event as PointerEvent).pointerId);
            } catch {
                // Capture is a convenience — the drag still tracks without it.
            }
            seekTo(event as PointerEvent);
            event.preventDefault();
        });
        this.on(wipe, "pointermove", (event) => {
            if (dragging) seekTo(event as PointerEvent);
        });
        const end = () => {
            dragging = false;
        };
        this.on(wipe, "pointerup", end);
        this.on(wipe, "pointercancel", end);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-spec-compare": SpecCompare;
    }
}
