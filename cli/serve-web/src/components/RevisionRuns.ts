// `<cp-revision-runs>` — mini render thumbnails in the viewer's Revision menu, one per distinct
// look, so a reader can see at a glance which of a preview's publishes actually differ.
//
// The menu lists every publish of the catalog, which is not the same list as "the versions of THIS
// preview": a delivery branch is regenerated on every catalog change, so a preview can sit through
// ten consecutive publishes without a pixel moving and the menu will still offer ten rows that all
// open the same image. This element marks the top row of each stretch that shares its bytes and
// leaves the rest unmarked, which turns the wall of dates into "these two are different, and here
// is what each one looks like".
//
// It DECORATES server-rendered markup rather than owning it. The rows, their hrefs and their order
// are the revision menu's business and are already correct; adding a second renderer for them would
// mean two places that must agree about which row is pinned. So the server emits the rows and this
// hangs an image on the ones the server also told it are run heads — and when the fetch fails the
// menu is exactly what it was before, which is a working control.
//
// Lazy on purpose: the answer costs a delivery-branch read, and most visits never open this menu.
// Nothing is fetched until the disclosure is opened, and the result is kept for the life of the
// page.

import { h, type VNode } from "vue";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    renderTemplateOf,
    runsViewOf,
    summaryOf,
    type RenderRunsPayload,
    type RunsView,
} from "../viewer/renderRuns.js";

@customElement("cp-revision-runs")
export class RevisionRuns extends VueElement {
    /** The one-line answer above the list; the thumbnails go on the rows themselves. */
    private summary = "";

    private asked = false;
    private details: HTMLDetailsElement | null = null;

    connectedCallback(): void {
        super.connectedCallback();
        void whenParsed().then(() => this.install());
    }

    /**
     * Watch the enclosing disclosure and answer its first opening.
     *
     * `toggle` rather than a click handler on the summary: a `<details>` can also be opened by
     * find-in-page, by a keyboard activation, or by the browser restoring its state, and only the
     * event covers all of them.
     */
    private install(): void {
        if (!this.isConnected) return;
        const details = this.closest("details");
        if (!details || this.details === details) return;
        this.details = details;
        if (details.open) void this.load();
        this.listen(details, "toggle", () => {
            if (details.open) void this.load();
        });
    }

    override disconnectedCallback(): void {
        this.details = null;
        super.disconnectedCallback();
    }

    private async load(): Promise<void> {
        if (this.asked) return;
        this.asked = true;
        const template = renderTemplateOf(this.getAttribute("data-render-url"));
        if (!template) return;
        const payload = this.inline() ?? (await this.fetched());
        if (!payload) return;
        // ONE window check, before anything is claimed, and deliberately ahead of the split below.
        // Putting it inside the marker path would leave the single-run branch unguarded — and that
        // branch makes the boldest claim of the two ("All N publishes render identically"), so a
        // page whose catalog republished under it would state the strongest possible falsehood
        // about a list of rows the answer was never about.
        if (!this.describesThisPage(payload)) return;
        const view = runsViewOf(payload, template);
        if (view) {
            this.decorate(view);
            this.summary = view.summary;
            this.requestUpdate();
            return;
        }
        // No runs worth marking, but the count itself still answers "do they all differ?" — so say
        // that much rather than leaving the reader to open a dozen rows to find out.
        this.summary = summaryOf(
            payload.runs?.length ?? 0,
            payload.revisions ?? 0,
        );
        this.requestUpdate();
    }

    /** The revision rows this menu is drawn over, in the order the server listed them. */
    private rows(): HTMLElement[] {
        const list =
            this.closest("details")?.querySelector<HTMLElement>(
                ".cp-revision-list",
            );
        return [
            ...(list?.querySelectorAll<HTMLElement>("[data-revision]") ?? []),
        ];
    }

    /**
     * Whether [payload] is about the rows on this page.
     *
     * The menu is fetched lazily, so a catalog that republished since the page was rendered answers
     * over a newer window: its newest publish is a row this list does not have. The newest row is a
     * run head by construction, so comparing those two shas is the whole check.
     */
    private describesThisPage(payload: RenderRunsPayload): boolean {
        const newest = this.rows()[0]?.getAttribute("data-revision");
        return !!newest && newest === payload.runs?.[0]?.head;
    }

    /**
     * An INLINE payload, so a fixture (and any offline viewer) draws the markers without reaching
     * the runs lane.
     *
     * Same reasoning as `<cp-history-menu>`'s: without it the preview-harness capture of this menu
     * would look identical whether the markers work or the whole feature is deleted, which is no
     * visual coverage at all.
     */
    private inline(): RenderRunsPayload | null {
        const node = document.getElementById("cp-revision-runs-data");
        if (!node) return null;
        try {
            return JSON.parse(
                node.textContent || "null",
            ) as RenderRunsPayload | null;
        } catch {
            // A malformed payload falls through to the fetch, which is the same answer the page
            // would have given without it.
            return null;
        }
    }

    private async fetched(): Promise<RenderRunsPayload | null> {
        const runsUrl = this.getAttribute("data-runs-url");
        if (!runsUrl) return null;
        try {
            const response = await fetch(runsUrl, {
                credentials: "same-origin",
            });
            // A 404 is the honest answer for a catalog whose branch could not be asked, and for one
            // with no delivery branch at all. Neither is worth saying anything about: the menu is
            // already a working control without this.
            if (!response.ok) return null;
            return (await response.json()) as RenderRunsPayload | null;
        } catch {
            return null;
        }
    }

    /**
     * Hang a thumbnail on each run head.
     *
     * Rows are matched by `data-revision`, the delivery sha the server stamps on every row, rather
     * than by parsing `?at=` out of the href — the *current* row deliberately carries no pin, so
     * href-parsing would silently never mark the one row that is always a run head.
     */
    private decorate(view: RunsView): void {
        const list =
            this.closest("details")?.querySelector<HTMLElement>(
                ".cp-revision-list",
            );
        const rows = this.rows();
        if (!list || !rows.length) return;
        // Marks the list as decorated, which is what lets the stylesheet indent the rows that are
        // NOT run heads. Absence of `data-run-head` cannot carry that on its own: it is equally the
        // state of every row before the fetch lands, and of a menu whose fetch never succeeded.
        list.setAttribute("data-runs", "on");
        let seen = 0;
        for (const row of rows) {
            const marker = view.markers.get(
                row.getAttribute("data-revision") || "",
            );
            if (!marker) continue;
            // Marks every run head; the first one additionally opens the list, so the divider that
            // separates runs can be drawn on the others without a leading rule above the first row.
            row.setAttribute("data-run-head", seen === 0 ? "first" : "1");
            seen += 1;
            if (row.querySelector(".cp-revision-thumb")) continue;
            const img = document.createElement("img");
            img.className = "cp-revision-thumb";
            img.loading = "lazy";
            img.decoding = "async";
            img.alt = "";
            img.title = marker.title;
            img.src = marker.thumb;
            // A publish whose render will not load says nothing useful as a broken-image glyph; the
            // row's date and sha still do.
            img.addEventListener("error", () => img.remove());
            row.prepend(img);
            if (marker.span) {
                const span = document.createElement("span");
                span.className = "cp-revision-span";
                span.title = marker.title;
                span.textContent = marker.span;
                row.appendChild(span);
            }
        }
    }

    protected renderVue(): VNode | null {
        if (!this.summary) return null;
        return h("p", { class: "cp-revision-runs-summary" }, this.summary);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-revision-runs": RevisionRuns;
    }
}
