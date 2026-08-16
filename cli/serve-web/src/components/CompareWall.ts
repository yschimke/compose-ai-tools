// `<cp-compare-wall>` — the `/compare` wall: every component's baked PNG beside the same render in
// another format, scored. Replaces the `#cp-compare` half of `assets/format-compare.js`.
//
// Three lanes over one table. SVG and Remote Compose compare a render against an export of THAT
// render, so they share geometry by construction and report a bare percentage. The reference lane
// compares independently-authored artwork — the design's own drawing — and is the only one that
// carries a proportion figure.
//
// The scoring itself still belongs to `format-compare.js`, reached through the typed handle in
// `compare/api.ts`. This element owns the wall: which two artifacts a row pairs, what the page is
// showing and why, which rows survive the filter, and the order they end up in.
//
// Renders nothing of its own; `serve.css` hides the tag. The decisions live next door:
// `compare/pairing.ts`, `compare/state.ts`, `compare/wallRows.ts` and `compare/grade.ts`.

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import { compareApi } from "../compare/api.js";
import { grade } from "../compare/grade.js";
import {
    rowTheme,
    variantFor,
    type Available,
    type Format,
} from "../compare/pairing.js";
import { initialState, poppedState, type WallState } from "../compare/state.js";
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";
import {
    byWorstFirst,
    countLabel,
    keepRow,
    scoreOf,
} from "../compare/wallRows.js";
import "../chrome/pageTheme.js";
import { whenParsed } from "../dom/whenParsed.js";
// Types only: the player bundle is script-injected at runtime, never imported.
import type { RcPlayer } from "../rc/player.js";

@customElement("cp-compare-wall")
export class CompareWall extends LitElement {
    private installed = false;
    private root!: HTMLElement;
    private rows: HTMLElement[] = [];
    private body: HTMLElement | null = null;
    private formatButtons: HTMLElement[] = [];
    private themeButtons: HTMLElement[] = [];
    private search: HTMLInputElement | null = null;
    private count: HTMLElement | null = null;
    private empty: HTMLElement | null = null;
    /** The published Remote Compose player wall, when this catalog has one. */
    private lanesPane: HTMLElement | null = null;
    private formatsPane: HTMLElement | null = null;

    private available: Available = { svg: false, rc: false, reference: false };
    private state!: WallState;
    /** What Back falls back to on an entry naming no format or theme: what THIS load resolved to. */
    private initial!: WallState;
    /** Bumped per run, so a slow lane cannot write its scores over a newer one's. */
    private sequence = 0;
    private cleanups: Array<() => void> = [];

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.installed = false;
        this.sequence++;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const root = document.getElementById("cp-compare");
        if (!root) return false;
        this.installed = true;
        this.root = root;

        this.formatButtons = Array.from(
            root.querySelectorAll<HTMLElement>("[data-compare-format]"),
        );
        this.themeButtons = Array.from(
            root.querySelectorAll<HTMLElement>("[data-compare-theme]"),
        );
        this.rows = Array.from(
            root.querySelectorAll<HTMLElement>(".cp-compare-row"),
        );
        this.body = root.querySelector("#cp-compare-formats tbody");
        this.lanesPane = document.getElementById("cp-rc-lanes");
        this.formatsPane = document.getElementById("cp-compare-formats");
        this.count = document.getElementById("cp-compare-count");
        this.search =
            document.querySelector<HTMLInputElement>("#cp-compare-search");
        this.empty = document.getElementById("cp-compare-empty");

        this.available = {
            svg: root.getAttribute("data-has-svg") === "1",
            rc: root.getAttribute("data-has-rc") === "1",
            reference: root.getAttribute("data-has-reference") === "1",
        };

        this.state = initialState({
            defaults: {
                format: root.getAttribute("data-default-format") ?? "svg",
                theme: root.getAttribute("data-default-theme") ?? "light",
            },
            remembered: this.remembered(),
            params: new URLSearchParams(location.search),
            available: this.available,
        });
        this.initial = { ...this.state };
        if (this.search) this.search.value = this.state.query;

        this.wire();
        this.run();
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

    private themeKey(): string {
        return this.root.getAttribute("data-theme-key") ?? "";
    }

    private remembered(): string | null {
        try {
            return localStorage.getItem(this.themeKey());
        } catch {
            // A browser with storage blocked still gets the wall, on the page's default.
            return null;
        }
    }

    private wire(): void {
        for (const button of this.formatButtons) {
            this.on(button, "click", () => {
                const picked = button.getAttribute("data-compare-format");
                if (
                    picked !== "svg" &&
                    picked !== "rc" &&
                    picked !== "reference"
                )
                    return;
                this.state.format = picked;
                // A discrete pick gets its own history entry, so Back returns to the format the
                // visitor was comparing before rather than out of the page entirely.
                window.cpUrlState?.push({ format: picked });
                this.run();
            });
        }
        for (const button of this.themeButtons) {
            this.on(button, "click", () => {
                const picked = button.getAttribute("data-compare-theme");
                if (picked !== "light" && picked !== "dark") return;
                this.state.theme = picked;
                try {
                    localStorage.setItem(this.themeKey(), picked);
                } catch {
                    // Not remembering is survivable; not comparing is not.
                }
                window.cpUrlState?.push({ theme: picked });
                // Paint the page to match the theme being compared, when the visitor's Page theme
                // setting asks for that.
                window.cpPageTheme?.follow(picked);
                this.run();
            });
        }
        if (this.search) {
            // Typing REPLACES rather than pushes: the filter stays bookmarkable without one history
            // entry per keystroke.
            this.on(this.search, "input", () => {
                this.state.query = this.search?.value ?? "";
                window.cpUrlState?.replace({ q: this.state.query.trim() });
                this.applySearch();
            });
        }
        const off = window.cpUrlState?.onPop(() => {
            this.state = poppedState({
                initial: this.initial,
                params: new URLSearchParams(location.search),
                available: this.available,
            });
            if (this.search) this.search.value = this.state.query;
            window.cpPageTheme?.follow(this.state.theme);
            this.run();
        });
        if (off) this.cleanups.push(off);
    }

    /** Whether the published player wall is standing in for the client-rendered `rc` lane. */
    private lanesActive(): boolean {
        return Boolean(this.lanesPane) && this.state.format === "rc";
    }

    private sourcesOf(row: HTMLElement) {
        return (kind: string, variant: string) =>
            row.getAttribute(`data-${kind}-${variant}`) ?? "";
    }

    // ---- the run -------------------------------------------------------------

    private run(): void {
        const runId = ++this.sequence;
        for (const button of this.formatButtons) {
            button.setAttribute(
                "aria-pressed",
                String(
                    button.getAttribute("data-compare-format") ===
                        this.state.format,
                ),
            );
        }
        for (const button of this.themeButtons) {
            button.setAttribute(
                "aria-pressed",
                String(
                    button.getAttribute("data-compare-theme") ===
                        this.state.theme,
                ),
            );
        }
        this.root.setAttribute("data-format", this.state.format);
        this.root.setAttribute("data-theme", this.state.theme);

        // The lane wall is its own view: it owns its rows, its reference picker and its diffs, and
        // needs none of the per-row scoring below — every number it shows was computed offline. Hand
        // it the filter and stop, or the client-rendered table keeps decoding a document per preview
        // for a table nobody can see.
        if (this.lanesPane) this.lanesPane.hidden = !this.lanesActive();
        if (this.formatsPane) this.formatsPane.hidden = this.lanesActive();
        if (this.lanesActive()) {
            this.applySearch();
            return;
        }

        for (const row of this.rows) row.removeAttribute("data-score");
        this.applySearch();
        const visible = this.rows.filter((row) => !row.hidden);

        // Serial, not parallel: each row decodes two full frames and scores them, and a wall of
        // thirty racing each other is what made this page unusable on a laptop.
        let chain: Promise<unknown> = Promise.resolve();
        for (const row of visible) {
            chain = chain.then(() => this.scoreRow(row, runId));
        }
        void chain.then(() => {
            if (runId !== this.sequence) return;
            visible.sort((a, b) =>
                byWorstFirst(
                    scoreOf(a.getAttribute("data-score")),
                    scoreOf(b.getAttribute("data-score")),
                ),
            );
            for (const row of visible) this.body?.appendChild(row);
            this.applySearch();
        });
    }

    private applySearch(): void {
        const query = this.search?.value ?? "";
        if (this.lanesActive()) {
            window.cpRcLanes?.filter(query);
            return;
        }
        // Re-read per pass rather than resolved once at load: the viewer links in with `?preview=`,
        // and a Back to such an entry has to re-narrow.
        const preview =
            new URLSearchParams(location.search).get("preview") ?? "";
        let visible = 0;
        for (const row of this.rows) {
            const keep = keepRow(
                {
                    hay: row.getAttribute("data-hay") ?? "",
                    previewIds: row.getAttribute("data-preview-ids") ?? "",
                    hasFormat: Boolean(
                        variantFor(
                            this.sourcesOf(row),
                            this.state.format,
                            this.state.theme,
                        ),
                    ),
                },
                query,
                preview,
            );
            row.hidden = !keep;
            if (keep) visible++;
        }
        if (this.count) this.count.textContent = countLabel(visible);
        if (this.empty) this.empty.hidden = visible !== 0;
    }

    // ---- one row -------------------------------------------------------------

    private async scoreRow(row: HTMLElement, runId: number): Promise<void> {
        const sources = this.sourcesOf(row);
        const variant = variantFor(
            sources,
            this.state.format,
            this.state.theme,
        );
        const pngUrl = sources("png", variant);
        const candidateUrl = sources(this.state.format, variant);
        const score = row.querySelector<HTMLElement>(".cp-compare-score");
        const png = row.querySelector<HTMLImageElement>(".cp-compare-png");
        const vector =
            row.querySelector<HTMLImageElement>(".cp-compare-vector");
        const canvas = row.querySelector("canvas");
        if (!pngUrl || !candidateUrl || !score || !png || !vector || !canvas) {
            row.hidden = true;
            return;
        }
        row.hidden = false;
        row.setAttribute("data-bg-theme", rowTheme(variant));
        png.src = pngUrl;
        png.alt = `${row.getAttribute("data-label")} rendered PNG`;
        score.textContent = "comparing…";
        score.className = "cp-compare-score";

        const format = this.state.format;
        if (format === "svg" || format === "reference") {
            vector.hidden = false;
            canvas.hidden = true;
            vector.src = candidateUrl;
            vector.alt = `${row.getAttribute("data-label")}${format === "svg" ? " SVG" : " design reference"}`;
            vector.title =
                format === "reference" ? "Open Reference / Diff / Actual" : "";
            vector.onclick =
                format === "reference"
                    ? () => {
                          location.href = sources("reference-detail", variant);
                      }
                    : null;
        } else {
            vector.hidden = true;
            canvas.hidden = false;
        }

        try {
            const measured = await this.measure(
                format,
                row,
                pngUrl,
                candidateUrl,
                canvas,
            );
            if (runId !== this.sequence) return;
            row.setAttribute("data-score", String(measured.percent));
            score.textContent = `${measured.percent.toFixed(1)}%`;
            score.className = `cp-compare-score cp-compare-score--${grade(measured.percent)}`;
            if (typeof measured.geometry === "number") {
                row.setAttribute(
                    "data-geometry-delta",
                    measured.geometry.toFixed(2),
                );
                score.title =
                    measured.geometry >= GEOMETRY_REPORT_THRESHOLD
                        ? `${measured.geometry.toFixed(1)}% proportion difference between the two content boxes`
                        : "";
            } else {
                row.removeAttribute("data-geometry-delta");
            }
        } catch {
            if (runId !== this.sequence) return;
            // `-1` rather than dropping the row: an unmeasurable pair sorts to the top, because it
            // is the one nobody is looking at.
            row.setAttribute("data-score", "-1");
            row.removeAttribute("data-geometry-delta");
            score.textContent = "unavailable";
            score.className = "cp-compare-score cp-compare-score--na";
        }
    }

    /**
     * The comparison handle, read HERE rather than cached at install.
     *
     * `format-compare.js` publishes the global from its own script tag, and this page emits the
     * components bundle first — so an element that cached the handle when it upgraded would cache
     * `null` and every row would read "unavailable", silently, on a page that otherwise looks fine.
     */
    private async measure(
        format: Format,
        row: HTMLElement,
        pngUrl: string,
        candidateUrl: string,
        canvas: HTMLCanvasElement,
    ): Promise<{ percent: number; geometry?: number }> {
        const compare = compareApi();
        if (!compare) throw new Error("no scorer");
        // The vector lanes score a render against an export of that same render, so they share its
        // geometry by construction and report a bare percentage. Only the reference lane compares
        // independently-authored artwork, and only it carries a geometry figure.
        if (format === "svg") {
            return {
                percent: await compare.scoreSvgUrls(pngUrl, candidateUrl),
            };
        }
        if (format === "reference") {
            return compare.scoreImageUrls(candidateUrl, pngUrl);
        }
        return {
            percent: await this.renderRc(pngUrl, candidateUrl, canvas, compare),
        };
    }

    // ---- the client-rendered Remote Compose lane -----------------------------

    private ensureRcPlayer(): Promise<void> {
        if (window.RC) return Promise.resolve();
        return new Promise((resolve, reject) => {
            const existing = document.querySelector(
                "script[data-cp-rc-compare]",
            );
            if (existing) {
                existing.addEventListener("load", () => resolve(), {
                    once: true,
                });
                existing.addEventListener(
                    "error",
                    () => reject(new Error("rc")),
                    {
                        once: true,
                    },
                );
                return;
            }
            const script = document.createElement("script");
            script.src = "/rc-player/bundle.js";
            script.setAttribute("data-cp-rc-compare", "1");
            script.onload = () => resolve();
            script.onerror = () => reject(new Error("rc"));
            document.head.appendChild(script);
        });
    }

    private nextFrame(): Promise<void> {
        return new Promise((resolve) => requestAnimationFrame(() => resolve()));
    }

    private async renderRc(
        pngUrl: string,
        documentUrl: string,
        canvas: HTMLCanvasElement,
        compare: NonNullable<ReturnType<typeof compareApi>>,
    ): Promise<number> {
        // The page registers the vendored faces the player's generic-family stacks name;
        // `cpRcFonts.ready()` is what actually LOADS them, since a canvas neither drives a lazy
        // `@font-face` nor repaints when one arrives. Unawaited, this lane would score the document
        // drawn in the visitor's own `sans-serif` against a PNG baked with Roboto — a permanent
        // residual that reads as a layout defect.
        const [, png, response] = await Promise.all([
            this.ensureRcPlayer(),
            compare.loadImage(pngUrl),
            fetch(documentUrl),
            window.cpRcFonts?.ready() ?? Promise.resolve(),
        ]);
        if (!response.ok) throw new Error(`RC ${response.status}`);
        canvas.width = png.naturalWidth || png.width;
        canvas.height = png.naturalHeight || png.height;
        const buffer = await response.arrayBuffer();

        const player = new window.RC!.RcdPlayer(canvas);
        // Artifact theme is an explicit comparison input; it must not inherit the site's or the OS's
        // `prefers-color-scheme`, or a light PNG gets scored against a dark RC canvas.
        player.setTheme(this.state.theme);
        await player.loadFromArrayBuffer(buffer);
        player.repaint?.();
        // The first paint is what DISCOVERS the named font families. Wait for those faces, repaint
        // with the resolved glyphs, and only then take the single-shot measurement.
        await player.fontsReady();
        player.repaint?.();
        await this.nextFrame();
        await this.nextFrame();
        return compare.scoreCanvas(pngUrl, canvas);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-compare-wall": CompareWall;
    }
}
