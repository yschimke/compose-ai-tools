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
import {
    matchAnnotationItems,
    type AnnotationItem,
    type Bounds,
} from "../annotate/match.js";
import {
    groupTypography,
    pairTypography,
    typographyComparableValue,
    typographyValue,
    type Field,
    type TypographyPair,
} from "../annotate/typography.js";
import { dataUrlFor } from "../inspect/layers.js";

/** What `viewer.js` calls on the way into and out of the lane. */
interface SpecCompareApi {
    view(): SpecView;
    prefer(next: string): void;
    hydrate(next: string | null): void;
    open(url: string): void;
    close(): void;
    /** Whether the stage is showing the render the published score was measured against. */
    baseline(atBaseline: boolean): void;
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
    /**
     * Whether the stage is showing the render the PUBLISHED score was measured against.
     *
     * The baked verdict describes the catalog's own snapshot — default theme, declared knobs, no
     * detected features. Pick a theme and the render moves; the reference does not, because a
     * design spec is imported once and is not re-exported per theme. So off the baseline the
     * published number is describing a frame nobody is looking at, and it is describing it
     * flatteringly: on `switch-on__ideal__icon-off` the chip reads 99.6% while the lane, scoring
     * what is actually on the stage under Light High Contrast, reads 88.9%. Entering the lane then
     * looks like a regression when all that happened is that the honest number arrived.
     *
     * Off the baseline the chip therefore falls back to the plain provider label — the same thing
     * every catalog without a baked score shows — until the lane produces a live measurement.
     */
    private atBaseline = true;
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
    /** Whether the chip currently shows a live measurement rather than a resting label. */
    private liveOnChip = false;
    /** The readout that goes with the live number, so the chip's tooltip states the same one. */
    private scoreTip: string | null = null;
    /** Bumped to abandon a comparison in flight. */
    private generation = 0;
    private cleanups: Array<() => void> = [];
    private annotationKey = "";
    private annotationPromise: Promise<unknown> | null = null;
    /** Annotation endpoint captured with the exact render normalised into [frames]. */
    private framesAnnotationUrl = "";
    private typographyLegend: HTMLElement | null = null;
    private typographyLayers: HTMLElement[] = [];

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
        this.clearTypography();
        this.typographyLegend?.remove();
        this.typographyLegend = null;
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
        baseline: (atBaseline) => {
            if (atBaseline === this.atBaseline) return;
            this.atBaseline = atBaseline;
            // Only the chip's RESTING state moves. A live measurement outranks this either way —
            // it was taken from the frames on the stage, which is the very thing being asked
            // about — and a knob edit that invalidates it re-enters `compute()` anyway.
            if (!this.liveOnChip) this.setChipVerdict(null);
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

        // ServeWeb's inline theme bootstrap publishes this before the component bundle loads, so
        // the first install does not briefly show the baked verdict for a themed deep link.
        // viewer.js keeps it current after controls change, and reconnects read the latest value.
        this.atBaseline = this.root.getAttribute("data-spec-baseline") !== "0";
        if (!this.atBaseline) this.setChipVerdict(null);

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
        this.on(window, "resize", () => this.placeTypography());
        this.on(
            window,
            "cp-inspect-change",
            () => void this.refreshTypography(),
        );

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
        else this.clearTypography();
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
            this.liveOnChip = false;
            // Off the baseline there is no published number that describes what is on the stage,
            // so the chip says only which tool the spec came from and the tooltip says why. The
            // band goes with it: a colour is a verdict too, and a green chip over a render the
            // verdict was never taken against is the same lie in less text.
            const baked = this.atBaseline;
            chip.textContent = baked
                ? chip.getAttribute("data-spec-chip-label") || name
                : name;
            const tip = baked
                ? chip.getAttribute("data-spec-chip-tip")
                : chip.getAttribute("data-spec-chip-stale-tip") ||
                  chip.getAttribute("data-spec-chip-tip");
            if (tip) chip.title = tip;
            if (baked && this.bakedBand)
                chip.setAttribute("data-spec-match", this.bakedBand);
            else chip.removeAttribute("data-spec-match");
            return;
        }
        this.liveOnChip = true;
        chip.textContent = chipText(name, percent);
        chip.setAttribute("data-spec-match", matchBand(percent));
        // The tooltip moves with the number. Left alone it went on quoting the publish-time
        // verdict — "99.6% match … 23.09% pixels differ" — beside a chip reading 88.9%, which is
        // the same two-numbers-for-one-comparison the live chip exists to prevent.
        chip.title = this.scoreTip ?? chip.title;
    }

    /** Paint every comparison surface from one normalisation of the current pair. */
    private async compute(): Promise<void> {
        const api = compareApi();
        const reference = sameOrigin(this.referenceUrl, location.origin);
        const actual = sameOrigin(this.actualUrl, location.origin);
        const annotationFrameUrl =
            document.getElementById("cp-img")?.getAttribute("data-cp-src") ||
            this.actualUrl;
        const annotationUrl = dataUrlFor(annotationFrameUrl, "annotations");
        const key = `${reference}\n${actual}`;
        if (!reference || !actual || !api) {
            this.frames = null;
            this.framesKey = "";
            this.framesAnnotationUrl = "";
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
            void this.refreshTypography();
            return;
        }
        const generation = ++this.generation;
        this.setScore(COMPARING);
        try {
            const next = await api.normaliseImageUrls(reference, actual);
            if (generation !== this.generation) return;
            this.frames = next;
            this.framesKey = key;
            // Keep inspection facts tied to the same candidate that produced these canvases. The
            // hidden render image can advance while a comparison remains open; reading its URL
            // later would put new bounds and typography over old pixels.
            this.framesAnnotationUrl = annotationUrl ?? "";
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
            const text = readout(
                result.percent,
                changedPercentOf(changed, next.width, next.height),
                result.geometry,
            );
            this.setScore(text);
            this.scoreTip = text;
            this.framesMatch = result.percent;
            this.setChipVerdict(result.percent);
            void this.refreshTypography();
        } catch {
            if (generation !== this.generation) return;
            this.frames = null;
            this.framesKey = "";
            this.framesAnnotationUrl = "";
            this.framesMatch = null;
            this.scoreTip = null;
            this.setScore(UNAVAILABLE);
            this.clearTypography();
        }
    }

    private typographyOn(): boolean {
        return Boolean(
            document.querySelector<HTMLInputElement>(
                '[data-cp-inspect="typography"]',
            )?.checked,
        );
    }

    private referenceAnnotations(): unknown {
        const node = document.getElementById("cp-spec-annotations");
        if (!node) return [];
        try {
            return (
                JSON.parse(node.textContent ?? "") as { reference?: unknown }
            ).reference;
        } catch {
            return [];
        }
    }

    private actualAnnotations(): Promise<unknown> {
        // Pixel comparison may use a snapshot object URL to avoid a second no-store render. The
        // sibling annotations endpoint was captured when that snapshot was normalised; do not
        // consult the live hidden image here because it may already contain a newer override.
        const url = this.framesAnnotationUrl;
        if (!url) return Promise.resolve([]);
        if (this.annotationKey === url && this.annotationPromise)
            return this.annotationPromise;
        this.annotationKey = url;
        this.annotationPromise = fetch(url, { credentials: "same-origin" })
            .then((response) => {
                if (!response.ok)
                    throw new Error(`annotations ${response.status}`);
                return response.json() as Promise<unknown>;
            })
            .then((payload) => {
                if (
                    payload &&
                    typeof payload === "object" &&
                    Array.isArray(
                        (payload as { annotations?: unknown }).annotations,
                    )
                )
                    return (payload as { annotations: unknown[] }).annotations;
                return payload;
            })
            .catch(() => []);
        return this.annotationPromise;
    }

    private changedFields(pair: TypographyPair): Field[] {
        const fields: Field[] = [
            "token",
            "family",
            "weight",
            "size",
            "tracking",
            "style",
            "axes",
        ];
        if (!pair.reference || !pair.actual) return fields;
        return fields.filter((field) => {
            if (
                typographyComparableValue(pair.reference?.spec, field) !==
                typographyComparableValue(pair.actual?.spec, field)
            )
                return true;
            return (
                field === "size" &&
                typographyComparableValue(
                    pair.reference?.spec,
                    "lineHeight",
                ) !== typographyComparableValue(pair.actual?.spec, "lineHeight")
            );
        });
    }

    private fieldLabel(field: Field): string {
        return (
            {
                token: "Token",
                family: "Family",
                weight: "Weight",
                size: "Size",
                lineHeight: "Line height",
                tracking: "Tracking",
                style: "Style",
                axes: "Variations",
            } as Record<Field, string>
        )[field];
    }

    private value(
        pair: TypographyPair,
        side: "reference" | "actual",
        field: Field,
    ): string {
        const spec = pair[side]?.spec;
        let value = typographyValue(spec, field);
        if (field === "size" && spec?.lineHeight !== undefined)
            value += `/${typographyValue(spec, "lineHeight")}`;
        return value;
    }

    private ensureTypographyLegend(): HTMLElement | null {
        if (this.typographyLegend?.isConnected) return this.typographyLegend;
        const controls = document.getElementById("cp-controls");
        const parent = controls?.parentElement;
        if (!parent || !controls) return null;
        const legend = document.createElement("aside");
        legend.id = "cp-spec-typography-legend";
        legend.className = "cp-inspect-legend cp-spec-typography-legend";
        legend.setAttribute("aria-label", "Typography differences");
        legend.hidden = true;
        parent.insertBefore(legend, controls);
        this.typographyLegend = legend;
        return legend;
    }

    private clearTypography(): void {
        for (const layer of this.typographyLayers) layer.remove();
        this.typographyLayers = [];
        if (this.typographyLegend) {
            this.typographyLegend.textContent = "";
            this.typographyLegend.hidden = true;
        }
    }

    private async refreshTypography(): Promise<void> {
        const view = this.choice.view;
        const frames = this.frames;
        if (
            !this.open ||
            !this.frames ||
            !this.typographyOn() ||
            (view !== "diff" && view !== "triptych")
        ) {
            this.clearTypography();
            return;
        }
        const actual = await this.actualAnnotations();
        if (
            !this.open ||
            !this.typographyOn() ||
            this.choice.view !== view ||
            this.frames !== frames
        )
            return;
        const matched = matchAnnotationItems(
            this.referenceAnnotations(),
            actual,
        );
        const pairs = pairTypography(
            groupTypography(matched.reference),
            groupTypography(matched.actual),
        ).filter((pair) => this.changedFields(pair).length > 0);
        this.drawTypographyLegend(pairs);
        this.drawTypographyLayers(pairs);
    }

    private drawTypographyLegend(pairs: TypographyPair[]): void {
        const legend = this.ensureTypographyLegend();
        if (!legend) return;
        legend.textContent = "";
        legend.hidden = false;
        const head = document.createElement("div");
        head.className = "cp-inspect-legend-head";
        head.textContent = pairs.length
            ? "Typography differences"
            : "Typography matches";
        const count = document.createElement("span");
        count.className = "cp-inspect-legend-count";
        count.textContent = String(pairs.length);
        head.appendChild(count);
        legend.appendChild(head);
        if (!pairs.length) return;
        const list = document.createElement("ol");
        list.className = "cp-inspect-list";
        for (const pair of pairs) {
            const row = document.createElement("li");
            row.className = "cp-inspect-entry cp-spec-type-diff";
            row.setAttribute("data-cp-typography-marker", pair.marker);
            const badge = document.createElement("span");
            badge.className = "cp-inspect-badge";
            badge.textContent = pair.marker;
            row.appendChild(badge);
            const text = document.createElement("span");
            text.className = "cp-inspect-text";
            for (const field of this.changedFields(pair)) {
                const line = document.createElement("span");
                line.className = "cp-spec-type-field cp-typography-changed";
                line.textContent = `${this.fieldLabel(field)}: ${this.value(pair, "reference", field)} → ${this.value(pair, "actual", field)}`;
                text.appendChild(line);
            }
            row.appendChild(text);
            list.appendChild(row);
        }
        legend.appendChild(list);
    }

    private drawTypographyLayers(pairs: TypographyPair[]): void {
        for (const layer of this.typographyLayers) layer.remove();
        this.typographyLayers = [];
        const view = this.choice.view;
        const sides: Array<["reference" | "actual", string]> =
            view === "triptych"
                ? [
                      ["reference", "reference"],
                      ["actual", "actual"],
                  ]
                : [["actual", "diff"]];
        for (const [side, panelName] of sides) {
            const panel = this.compare?.querySelector<HTMLElement>(
                `[data-cp-spec-panel="${panelName}"]`,
            );
            const canvas = panel?.querySelector("canvas");
            if (!panel || !canvas) continue;
            const layer = document.createElement("div");
            layer.className = "cp-spec-annotation-layer";
            layer.setAttribute("data-cp-side", side);
            for (const pair of pairs) {
                const group = pair[side];
                for (const item of group?.items ?? []) {
                    if (!item.bounds) continue;
                    layer.appendChild(this.typographyBox(pair.marker, item));
                }
            }
            panel.appendChild(layer);
            this.typographyLayers.push(layer);
        }
        requestAnimationFrame(() => this.placeTypography());
    }

    private typographyBox(marker: string, item: AnnotationItem): HTMLElement {
        const box = document.createElement("div");
        box.className = "cp-inspect-box cp-spec-type-box";
        box.setAttribute("data-cp-kind", "typography");
        box.setAttribute("data-source-x", String(item.bounds?.x ?? 0));
        box.setAttribute("data-source-y", String(item.bounds?.y ?? 0));
        box.setAttribute("data-source-width", String(item.bounds?.width ?? 0));
        box.setAttribute(
            "data-source-height",
            String(item.bounds?.height ?? 0),
        );
        const badge = document.createElement("span");
        badge.className = "cp-inspect-badge";
        badge.textContent = marker;
        box.appendChild(badge);
        return box;
    }

    private placeTypography(): void {
        const frames = this.frames;
        if (!frames) return;
        for (const layer of this.typographyLayers) {
            const panel = layer.parentElement;
            const canvas = panel?.querySelector("canvas");
            if (!canvas || !canvas.clientWidth) continue;
            const side =
                layer.getAttribute("data-cp-side") === "reference"
                    ? "reference"
                    : "candidate";
            const crop = frames.boxes[side];
            const sx = canvas.clientWidth / frames.width;
            const sy = canvas.clientHeight / frames.height;
            layer.style.left = `${canvas.offsetLeft}px`;
            layer.style.top = `${canvas.offsetTop}px`;
            layer.style.width = `${canvas.clientWidth}px`;
            layer.style.height = `${canvas.clientHeight}px`;
            for (const node of layer.querySelectorAll<HTMLElement>(
                ".cp-spec-type-box",
            )) {
                const bounds: Bounds = {
                    x: Number(node.getAttribute("data-source-x")),
                    y: Number(node.getAttribute("data-source-y")),
                    width: Number(node.getAttribute("data-source-width")),
                    height: Number(node.getAttribute("data-source-height")),
                };
                node.style.left = `${((bounds.x - crop.x) * frames.width * sx) / crop.width}px`;
                node.style.top = `${((bounds.y - crop.y) * frames.height * sy) / crop.height}px`;
                node.style.width = `${(bounds.width * frames.width * sx) / crop.width}px`;
                node.style.height = `${(bounds.height * frames.height * sy) / crop.height}px`;
            }
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
