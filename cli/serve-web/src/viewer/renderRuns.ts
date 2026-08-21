// The model behind the revision menu's render-run markers: which published revisions of a preview
// actually differ, and what each marker says.
//
// Split from the element for the same reason `historyUrls.ts` was split from `<cp-history-menu>`:
// every thumbnail URL is built from DOM text (`data-render-url`) and lands in an `img.src`, which
// is the flow CodeQL reports as `js/xss-through-dom`. The rule that makes it safe is the same one —
// match, then REBUILD from the captured segments, never pass the DOM string through — and it is
// only checkable if it lives somewhere a test can call it.
//
// The *grouping* deliberately does not live here. The server already collapsed the revisions into
// runs (`ServeCatalogRevision.renderRuns`), computed over the very list the menu rendered, so
// re-deriving it from the DOM would be a second implementation of an off-by-one that is easy to get
// backwards. This file only decides how to draw what the server sent.

import { reencode } from "./historyUrls.js";

/** One run of consecutive publishes sharing their pixels, as `/api/render-runs` states it. */
export interface RenderRun {
    head?: string;
    sourceSha?: string;
    commits?: number;
    open?: boolean;
}

export interface RenderRunsPayload {
    runs?: RenderRun[];
    revisions?: number;
}

/** A site-relative render URL, split so a pin can be appended without touching the DOM string. */
export interface RenderTemplate {
    base: string;
    query: string;
}

/** What the element draws on one revision row. */
export interface RunMarker {
    /** Delivery sha of the row this marker belongs to. */
    head: string;
    /** The render as published at [head] — the mini icon's source. */
    thumb: string;
    /** `×10`, or null for a run of one, where a count says nothing a reader can't see. */
    span: string | null;
    /** Hover text spelling the run out in full. */
    title: string;
}

/** Markers keyed by the delivery sha of the row they mark, plus the one-line summary above them. */
export interface RunsView {
    markers: Map<string, RunMarker>;
    summary: string;
}

/**
 * Validate and rebuild the render URL the server handed over.
 *
 * Same shape rule as `blobTemplateOf`: site-relative (the leading `\/(?!\/)` rejects a
 * protocol-relative `//host/…`), no `:` admitted anywhere so no `javascript:` URL can match, and
 * every segment re-encoded individually so the `/`, `?`, `&` and `=` structure survives while the
 * content cannot smuggle any.
 */
export function renderTemplateOf(url: string | null): RenderTemplate | null {
    if (!url) return null;
    const parts =
        /^(\/(?!\/)[A-Za-z0-9._~%/-]*\.png)(\?[A-Za-z0-9._~%&=-]*)?$/.exec(url);
    if (!parts) return null;
    return {
        base: parts[1].split("/").map(reencode).join("/"),
        query: (parts[2] || "").replace(/[^?&=]+/g, reencode),
    };
}

/**
 * That template pinned to one commit, or null when the sha isn't one.
 *
 * The sha is never interpolated from the DOM — it comes from the JSON the server sent — but it is
 * checked anyway, because "the server said so" is exactly the assumption that stops being true the
 * day something else answers this route.
 */
export function thumbUrlAt(
    template: RenderTemplate,
    sha: string | undefined,
): string | null {
    if (!/^[0-9a-f]{7,40}$/.test(sha || "")) return null;
    const separator = template.query ? "&" : "?";
    return `${template.base}${template.query}${separator}at=${sha}`;
}

/**
 * Turn the payload into markers, or null when there is nothing worth drawing.
 *
 * Null for a single run — every publish in the window renders identically, so there is no
 * *difference* to point at and a thumbnail on the one row would only add furniture. The summary
 * still says so, which is the honest answer to "do they all differ?", so the caller can show that
 * line alone.
 */
export function runsViewOf(
    payload: RenderRunsPayload | null | undefined,
    template: RenderTemplate | null,
): RunsView | null {
    const runs = payload?.runs ?? [];
    // One run means every publish in the window renders identically: there is no *difference* to
    // point at, and a thumbnail on the single row would be furniture. The caller still has the
    // count, and says so in the summary — which is the honest answer to "do they all differ?".
    if (runs.length < 2 || !template) return null;
    const revisions = payload?.revisions ?? 0;

    const markers = new Map<string, RunMarker>();
    for (const run of runs) {
        const thumb = thumbUrlAt(template, run.head);
        // A run whose head cannot be addressed is skipped rather than drawn as a broken image.
        if (!thumb || !run.head) continue;
        const commits = run.commits ?? 1;
        markers.set(run.head, {
            head: run.head,
            thumb,
            span: commits > 1 ? `×${commits}` : null,
            title: runTitle(commits, !!run.open),
        });
    }
    if (!markers.size) return null;
    // Counted from the runs the server found, not the markers we could draw: a run whose head is
    // unaddressable is still a distinct render, and under-reporting the count to match what is on
    // screen would turn a drawing limitation into a claim about the catalog.
    return { markers, summary: summaryOf(runs.length, revisions) };
}

/** "2 distinct renders across these 12 publishes", or the singular case said plainly. */
export function summaryOf(distinct: number, revisions: number): string {
    if (revisions <= 0) return "";
    // One publish is not a comparison, so it gets its own sentence rather than a plural claim
    // ("All 1 publish render identically") wearing a singular count.
    if (revisions === 1) return "Only one publish of this preview so far";
    if (distinct <= 1) return `All ${revisions} publishes render identically`;
    return `${distinct} distinct renders across these ${revisions} publishes`;
}

function runTitle(commits: number, open: boolean): string {
    if (commits === 1) return "Only this publish carried these pixels";
    // An open run ran off the end of the window, so its count is a floor and must read as one.
    const count = open ? `At least ${commits}` : `${commits}`;
    const verb = open ? "publishes carry" : "consecutive publishes carry";
    return `${count} ${verb} these pixels — identical from here down`;
}
