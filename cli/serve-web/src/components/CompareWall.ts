// `<cp-compare-wall>` — the `/compare` wall: every component's baked PNG beside the same render in
// another format, scored. Replaces the `#cp-compare` half of `assets/format-compare.js`.
//
// Three lanes over one table. SVG and Remote Compose compare a render against an export of THAT
// render, so they share geometry by construction and report a bare percentage. The reference lane
// compares independently-authored artwork — the design's own drawing — and is the only one that
// carries a proportion figure, and the only one with a middle column: the delta map between the two
// panels beside it, the same triptych the detail page opens onto, painted from the same normalised
// frames the row's percentage is measured over.
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
import { compareImageUrls } from "../compare/detail.js";
import { grade } from "../compare/grade.js";
import {
    rowTheme,
    variantFor,
    type Available,
    type Format,
} from "../compare/pairing.js";
import { specLeadsColumns, targetHeadLabel } from "../compare/columns.js";
import { initialState, poppedState, type WallState } from "../compare/state.js";
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";
import {
    bakedScoreOf,
    byWorstFirst,
    byWorstKnownFirst,
    countLabel,
    keepRow,
    scoreOf,
} from "../compare/wallRows.js";
import "../chrome/pageTheme.js";
import { whenParsed } from "../dom/whenParsed.js";
// Types only: the player bundle is script-injected at runtime, never imported.
import type { RcPlayer } from "../rc/player.js";

/**
 * Longest side the wall keeps a delta map at.
 *
 * The detail page holds ONE map and shows it as large as the window allows, so it keeps the
 * normalised frame's own dimensions. A wall holds one per row — every row of a catalog with design
 * references, which for the published Wear catalog is 233 — and shows each in a 200px column at most
 * 220px tall. Retaining the full normalised size there is backing store nobody can see: a wall of
 * phone-sized captures would hold hundreds of megabytes of canvas, and a capture past the browser's
 * canvas limit would turn a row that used to score into "unavailable". 440 is twice the tallest the
 * column ever draws, so the map is still crisp at 2× device pixel ratio.
 */
const MAP_MAX_SIDE = 440;

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
    /** The two picture columns' headers, so the pair can swap sides and stay named. */
    private renderHead: HTMLElement | null = null;
    private targetHead: HTMLElement | null = null;
    /** The middle column's header, which rides between the pair wherever the pair goes. */
    private diffHead: HTMLElement | null = null;

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
        this.renderHead = root.querySelector(".cp-compare-render-head");
        this.targetHead = root.querySelector(".cp-compare-target-head");
        this.diffHead = root.querySelector(".cp-compare-diff-head");
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
        this.orderColumns();

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

        // Everything that does not need the scorer, done before the scorer is asked for anything:
        // the pictures, the grounds, the links and the published scores. See {@link dressRow}.
        //
        // Dressing is driven from {@link applySearch} rather than run over every row here, because
        // it assigns both image `src` values — so dressing first and filtering after had the
        // browser fetch and decode the whole wall for a `?preview=` or `?q=` link showing one row.
        // On the large catalogs this page exists for that is hundreds of full-resolution pairs for
        // a single visible comparison. The dressed set is reset per run: a format or theme switch
        // changes which pair each row shows, so a row already dressed for the previous lane has to
        // be dressed again for this one.
        this.dressedRows = new Set();
        this.applySearch();
        const visible = this.rows.filter((row) => !row.hidden);
        // Ordered on the published numbers BEFORE anything is measured. The server already served
        // the reference lane in this order, so on first load this is a no-op; it earns its keep on
        // every lane and theme switch after that, where the served order is about another pairing
        // entirely and the rows would otherwise sit in it for the length of the whole chain below.
        visible.sort((a, b) =>
            byWorstKnownFirst(this.bakedScore(a), this.bakedScore(b)),
        );
        for (const row of visible) this.body?.appendChild(row);

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

    /**
     * Put the design spec on the left of the render, on the lane where there is one.
     *
     * The server renders the table in the order its OWN default format wants; a visitor who arrives
     * on `?format=reference`, or presses the Figma button, changes the question the two columns are
     * answering, so the columns move to match. Moving the cells (rather than reordering with CSS)
     * is what keeps the header, the picture and the copied-out DOM agreeing — and a table cell has
     * no `order` to give anyway.
     *
     * Idempotent: every run re-asserts the order, and a pair already in it is left untouched.
     */
    private orderColumns(): void {
        const specFirst = specLeadsColumns(this.state.format);
        if (this.targetHead) {
            this.targetHead.textContent = targetHeadLabel(
                this.state.format,
                this.root.getAttribute("data-reference-label") ?? "",
            );
            lead(this.targetHead, this.renderHead, specFirst, this.diffHead);
        }
        for (const row of this.rows) {
            lead(
                cellOf(row, ".cp-compare-target-cell"),
                cellOf(row, ".cp-compare-render-cell"),
                specFirst,
                cellOf(row, ".cp-compare-diff-cell"),
            );
        }
    }

    /**
     * The score the delivery branch published for the pair this row is CURRENTLY showing, if any.
     *
     * Reference lane only, and that gate is load-bearing rather than an optimisation: the published
     * number describes a render against an independently-drawn design, and `data-match-light` sits
     * on the same row the SVG lane is scoring. Ungated, switching to SVG would seed and order that
     * lane by numbers about a comparison it is not making.
     */
    private bakedScore(row: HTMLElement): number | null {
        if (this.state.format !== "reference") return null;
        const variant = variantFor(
            this.sourcesOf(row),
            "reference",
            this.state.theme,
        );
        if (!variant) return null;
        return bakedScoreOf(row.getAttribute(`data-match-${variant}`));
    }

    /**
     * Put the published score on a row before anything is measured.
     *
     * The wall used to open on a column of "waiting…" and stay there for as long as it took to
     * decode and score two rasters per row — tens of seconds on a real catalog — with the rows in
     * catalog order the whole time, which is the one order that says nothing about which of them is
     * wrong. The delivery branch already measured every one of these pairs with this same scorer
     * (`design-reference-score.mjs`), so the number exists; carrying it here makes the wall
     * readable and correctly ordered at first paint, and turns the in-browser pass into the
     * refinement it always was (issue #4624).
     *
     * The band is the live one's band, because it is the live one's number. What differs is
     * `data-score-source`, which says where it came from — for the dotted rule in `serve.css`, for
     * the failure path in {@link scoreRow}, and so a test can tell a seeded row from a measured one.
     */
    private seedScore(row: HTMLElement, score: HTMLElement): void {
        const baked = this.bakedScore(row);
        if (baked === null) {
            row.removeAttribute("data-score");
            score.removeAttribute("data-score-source");
            return;
        }
        row.setAttribute("data-score", String(baked));
        score.textContent = `${baked.toFixed(1)}%`;
        score.className = `cp-compare-score cp-compare-score--${grade(baked)}`;
        score.setAttribute("data-score-source", "published");
        score.title =
            "Measured when this catalog was published — re-measured here as the row loads";
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
            // Dressed HERE, and only when it is going to be seen — see {@link run}. `ensureDressed`
            // is also what keeps a row revealed by a later filter change (the search input clearing,
            // a Back to a wider query) from appearing with no pictures in it.
            const show = keep && this.ensureDressed(row);
            row.hidden = !show;
            if (show) visible++;
        }
        if (this.count) this.count.textContent = countLabel(visible);
        if (this.empty) this.empty.hidden = visible !== 0;
    }

    // ---- one row -------------------------------------------------------------

    /**
     * Rows already dressed for the current format and theme, so {@link applySearch} can dress a
     * newly revealed one without redressing the wall. Reset by {@link run}, which is what a format
     * or theme switch goes through.
     */
    private dressedRows = new Set<HTMLElement>();

    /**
     * {@link dressRow} once per row per run, remembering the outcome.
     *
     * False when the row cannot be paired in this format — the caller hides it, exactly as the
     * unconditional pass used to. A failure is deliberately not remembered: it costs one repeated
     * `querySelector` sweep on a row that will not be shown either way, and remembering it would
     * mean carrying a second set whose only purpose is to skip work nobody waits on.
     */
    private ensureDressed(row: HTMLElement): boolean {
        if (this.dressedRows.has(row)) return true;
        if (!this.dressRow(row)) return false;
        this.dressedRows.add(row);
        return true;
    }

    /**
     * Everything a row shows that is known WITHOUT measuring anything: the pair it is pointing at,
     * the ground it sits on, the published score, and where its links go.
     *
     * Split out of {@link scoreRow} and run synchronously over every row before the measuring chain
     * starts, because it used to ride INSIDE that chain — which walks the rows one at a time, each
     * one decoding and scoring two full frames. So a wall of thirty rows did not merely take tens of
     * seconds to finish scoring; it took tens of seconds to finish drawing, painting one row's two
     * pictures per completed comparison and showing nothing but labels until then. None of this
     * needs the scorer, and none of it needed to wait for it (issue #4624).
     *
     * False for a row this format cannot pair, or one whose markup is missing a part — the caller
     * drops it rather than scoring the wrong two pictures.
     */
    private dressRow(row: HTMLElement): boolean {
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
        // Two canvases per row now, so both are named: the delta map in the middle column and the
        // one the Remote Compose lane plays into. A bare `querySelector("canvas")` would have
        // started returning the diff, and the rc lane would have scored an empty frame.
        const canvas = row.querySelector<HTMLCanvasElement>(".cp-compare-rc");
        const diff = row.querySelector<HTMLCanvasElement>(".cp-compare-diff");
        if (!pngUrl || !candidateUrl || !score || !png || !vector || !canvas)
            return false;
        row.setAttribute(
            "data-bg-theme",
            rowTheme(
                variant,
                this.root.getAttribute("data-default-theme") ?? "",
                row.getAttribute(`data-declared-bg-${variant}`),
            ),
        );
        png.src = pngUrl;
        png.alt = `${row.getAttribute("data-label")} rendered PNG`;
        this.seedScore(row, score);

        const format = this.state.format;
        const detail = () => {
            location.href = sources("reference-detail", variant);
        };
        // The Bugs column's "+ file" follows the pair the row is showing, exactly as the pictures
        // do: the focused Reference / Diff / Actual page files a report naming that preview AND
        // that reference, so a link left pointing at the light comparison would file the wrong one
        // from the dark lane. Off the reference lane there is no focused pair to name, and it falls
        // back to the viewer's own report.
        const report = row.querySelector<HTMLAnchorElement>(
            ".cp-compare-bug-new",
        );
        if (report) {
            const focused =
                format === "reference"
                    ? sources("reference-detail", variant)
                    : "";
            report.href = focused || report.dataset.bugFallback || report.href;
        }
        if (format === "svg" || format === "reference") {
            vector.hidden = false;
            canvas.hidden = true;
            vector.src = candidateUrl;
            vector.alt = `${row.getAttribute("data-label")}${format === "svg" ? " SVG" : " design reference"}`;
            vector.title =
                format === "reference" ? "Open Reference / Diff / Actual" : "";
            vector.onclick = format === "reference" ? detail : null;
        } else {
            vector.hidden = true;
            canvas.hidden = false;
        }
        if (diff) {
            // Blanked before the run, not just repainted after it: the map is only redrawn when the
            // measurement succeeds, so a row that goes unmeasurable — or a lane switch away from the
            // reference — would otherwise leave the PREVIOUS pair's magenta standing beside the new
            // render, which reads as a finding rather than as stale paint.
            diff.width = 0;
            diff.height = 0;
            diff.setAttribute(
                "aria-label",
                `${row.getAttribute("data-label")} difference from the design reference`,
            );
            diff.title =
                format === "reference" ? "Open Reference / Diff / Actual" : "";
            diff.onclick = format === "reference" ? detail : null;
        }
        return true;
    }

    private async scoreRow(row: HTMLElement, runId: number): Promise<void> {
        // Nothing below may touch the row unless this chain is still the current one. Bumping
        // `sequence` on a lane switch stops an abandoned run's RESULTS from landing, but the chain
        // itself keeps walking its remaining rows — and it writes to the row on the way past: the
        // score cell's "comparing…", and the result. A stale chain arriving behind a finished one
        // therefore wiped rows the visitor was already reading and left them that way, because the
        // guard further down then discarded the very measurement that would have filled them back
        // in. Checked here, an abandoned chain costs one comparison per remaining row and no paint.
        if (runId !== this.sequence) return;
        const sources = this.sourcesOf(row);
        const variant = variantFor(
            sources,
            this.state.format,
            this.state.theme,
        );
        const pngUrl = sources("png", variant);
        const candidateUrl = sources(this.state.format, variant);
        const score = row.querySelector<HTMLElement>(".cp-compare-score");
        const canvas = row.querySelector<HTMLCanvasElement>(".cp-compare-rc");
        const diff = row.querySelector<HTMLCanvasElement>(".cp-compare-diff");
        if (!pngUrl || !candidateUrl || !score || !canvas) return;
        // A row that arrived with a published score keeps showing it while this one is taken.
        // Blanking it to "comparing…" would spend the very thing carrying it is for: the wall is
        // legible and ordered before this chain — which walks the rows one at a time — reaches it.
        if (!row.hasAttribute("data-score")) {
            score.textContent = "comparing…";
            score.className = "cp-compare-score";
        }
        const format = this.state.format;

        try {
            const measured = await this.measure(
                format,
                pngUrl,
                candidateUrl,
                canvas,
                Boolean(diff),
            );
            if (runId !== this.sequence) return;
            // Only NOW does the map reach the row. `measure` drew it into a canvas of its own, and
            // that ordering is the whole point: the abandoned lane's rows are still in flight when a
            // switch starts a second chain over the same elements, so a comparison that painted the
            // shared canvas before this check could land after the new one and leave the old theme's
            // magenta beside the new render and the new percentage — the stale-paint-reads-as-a-
            // finding failure, arriving by the one route blanking the canvas up front cannot close.
            if (diff && measured.map) this.paintMap(measured.map, diff);
            row.setAttribute("data-score", String(measured.percent));
            score.textContent = `${measured.percent.toFixed(1)}%`;
            score.className = `cp-compare-score cp-compare-score--${grade(measured.percent)}`;
            // Measured here now, so the published marking and the note that went with it go — the
            // geometry tooltip below is written over a cleared title rather than over that note.
            score.removeAttribute("data-score-source");
            score.title = "";
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
            // A PUBLISHED score outlives a failed measurement. The delivery branch scored this
            // exact pair with this exact scorer, so a throw here is a fact about this browser — a
            // fetch that failed, a canvas it would not give us — and not about the pair. Replacing
            // a real number with "unavailable" would lose it and sort the row to the top as though
            // nobody had ever measured it.
            if (score.getAttribute("data-score-source") === "published") return;
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
        pngUrl: string,
        candidateUrl: string,
        canvas: HTMLCanvasElement,
        withMap: boolean,
    ): Promise<{
        percent: number;
        geometry?: number;
        map?: HTMLCanvasElement;
    }> {
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
            if (!withMap) return compare.scoreImageUrls(candidateUrl, pngUrl);
            // The same composition the detail page measures with, for the same reason: normalise
            // the pair ONCE, then diff and score those frames, so the map in the middle column and
            // the percentage at the end of the row are describing the same pixels. The number is
            // unchanged — `compareImageUrls` scores the decoded originals, which is what
            // `scoreImageUrls` did with its own two fetches.
            //
            // Painted into a canvas belonging to THIS call rather than to the row: it is handed back
            // for the caller to copy in once the run has been validated, and it is thrown away
            // afterwards instead of being retained per row at the normalised frame's full size.
            const map = document.createElement("canvas");
            const result = await compareImageUrls(
                compare,
                candidateUrl,
                pngUrl,
                map,
                MAP_MAX_SIDE,
            );
            return { percent: result.score, geometry: result.geometry, map };
        }
        return {
            percent: await this.renderRc(pngUrl, candidateUrl, canvas, compare),
        };
    }

    /**
     * Copy a freshly-measured delta map into the row's canvas, bounded to {@link MAP_MAX_SIDE}.
     *
     * The dimensions are set before the context is asked for, so a row still reports a painted map
     * even where a 2D context is unavailable — that is the state a caller reads to tell a measured
     * row from a blanked one, and it must not depend on the drawing itself succeeding.
     */
    private paintMap(
        source: HTMLCanvasElement,
        target: HTMLCanvasElement,
    ): void {
        const scale = Math.min(
            1,
            MAP_MAX_SIDE / Math.max(source.width, source.height, 1),
        );
        target.width = Math.max(1, Math.round(source.width * scale));
        target.height = Math.max(1, Math.round(source.height * scale));
        const context = target.getContext("2d");
        if (!context) return;
        context.clearRect(0, 0, target.width, target.height);
        context.drawImage(source, 0, 0, target.width, target.height);
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

/** A row's picture cell, by its own class — position is what we are about to change. */
function cellOf(row: HTMLElement, selector: string): HTMLElement | null {
    return row.querySelector<HTMLElement>(selector);
}

/**
 * Ensure [spec] sits before [render] when [specFirst], else after it, with [middle] between them.
 *
 * Both have to be present and siblings for there to be an order at all — a table rendered with the
 * pair in one cell (or with one of them absent) is left exactly as it is rather than half-moved.
 *
 * [middle] is the delta map, and it has to be part of THIS decision rather than left where the
 * server put it: it is only a diff of the two pictures if it sits between them, and swapping a pair
 * that had something parked in the middle would otherwise shunt that something to the end. Absent
 * or in another row, it is ignored and the pair is ordered on its own.
 */
function lead(
    spec: HTMLElement | null,
    render: HTMLElement | null,
    specFirst: boolean,
    middle: HTMLElement | null = null,
): void {
    if (!spec || !render || spec === render) return;
    const parent = spec.parentElement;
    if (!parent || render.parentElement !== parent) return;
    const [first, second] = specFirst ? [spec, render] : [render, spec];
    const seat = middle?.parentElement === parent ? middle : null;
    if (!seat) {
        if (first.nextElementSibling === second) return;
        parent.insertBefore(first, second);
        return;
    }
    if (first.nextElementSibling === seat && seat.nextElementSibling === second)
        return;
    parent.insertBefore(first, second);
    parent.insertBefore(seat, second);
}
