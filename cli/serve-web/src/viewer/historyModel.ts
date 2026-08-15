// What the render-history menu shows, decided before any DOM exists.
//
// The menu's real content is a set of judgements about a manifest entry — which versions can be
// addressed at all, whether there are enough of them to be a timeline, which one is "current" and
// what each row says — and every one of them is a rule about data, not about markup. Held in the
// element they could only be checked by screenshotting a menu; here they are a table.

import {
    renderUrlAt,
    shortDate,
    type HistorySource,
    type HistoryVersion,
} from "./historyUrls.js";

/** One version as the manifest records it, with the fields the menu reads. */
export interface ManifestVersion extends HistoryVersion {
    date?: string;
    /** The commit the render was PRODUCED from — more use to a human than the publish marker. */
    sourceSha?: string;
    /** How many publishes carried these same bytes. */
    commits?: number;
}

export interface ManifestEntry {
    versions?: ManifestVersion[];
    path?: string;
    observations?: number;
    unstable?: boolean;
    flapCount?: number;
}

export interface HistoryRow {
    href: string;
    /** The newest published render — the one on the stage. Never true in project mode. */
    current: boolean;
    date: string;
    /** The right-hand column: "current", else the source sha, else a short commit. */
    meta: string;
    /** `×N` when one entry covers several publishes, else null. */
    span: string | null;
    spanTitle: string | null;
    title: string;
}

export interface HistoryMenu {
    rows: HistoryRow[];
    /** The closed control's value half — the list's size, said without opening it. */
    label: string;
    unstable: boolean;
    unstableTitle: string;
    /** Where the list came from, when that is not the same as what is on the stage. */
    note: string;
}

/**
 * Build the menu, or null when there is nothing worth drawing.
 *
 * Two versions is the floor, twice over: once on what the manifest claims, and again on what could
 * actually be addressed — an entry naming a sha the rules reject is skipped rather than rendered as
 * a dead control, and skipping enough of them turns a timeline back into a single version.
 */
export function historyMenuOf(
    source: HistorySource,
    entry: ManifestEntry | null | undefined,
): HistoryMenu | null {
    const versions = entry?.versions ?? [];
    if (!entry || versions.length < 2) return null; // A single version is not a timeline.

    const rows: HistoryRow[] = [];
    versions.forEach((v, i) => {
        // Every entry, including the newest, is addressed from the manifest — never from the
        // stage. That is what keeps the menu independent of whether the render has loaded yet.
        const href = renderUrlAt(source, v, entry.path ?? null);
        if (!href) return;
        const current = i === 0 && !source.local;
        const date = shortDate(v.date);
        rows.push({
            href,
            current,
            date,
            meta: current
                ? "current"
                : v.sourceSha || (v.commit || "").slice(0, 8),
            span: (v.commits ?? 0) > 1 ? `×${v.commits}` : null,
            spanTitle:
                (v.commits ?? 0) > 1
                    ? `${v.commits} publishes carried these bytes`
                    : null,
            title:
                (current
                    ? "Open the current render"
                    : `Open the render as of ${date}`) +
                (v.sourceSha ? ` (source ${v.sourceSha})` : ""),
        });
    });
    if (rows.length < 2) return null;

    return {
        rows,
        label: `${rows.length} ${rows.length === 1 ? "version" : "versions"}`,
        unstable: !!entry.unstable,
        unstableTitle:
            "This render keeps reverting to bytes it had already moved away from (" +
            `${entry.flapCount ?? 0} returns). The list is trimmed to the states it flips between.`,
        note: source.local
            ? "Published baselines, read from the delivery branch in your local checkout. The " +
              "preview is rendered from your working tree, so it may differ from the newest entry."
            : "Every publish of this design system that changed these pixels, over " +
              `${entry.observations ?? versions.length} publishes. Opening one shows that render ` +
              "in a new tab.",
    };
}
