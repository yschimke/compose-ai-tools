// The URL arithmetic behind the viewer's render-history menu, as pure functions over strings.
//
// This is the half of `viewer-history.js` that was worth extracting: every link the strip draws is
// built from DOM text (`data-history-repo`, `data-history-blob-url`) and lands in an `href`, which
// is the flow CodeQL reports as `js/xss-through-dom`. Three earlier attempts got it wrong the same
// way — a guard that inspects a string and hands the *same* string onward leaves the DOM value
// reaching the href verbatim. What is safe is to match, then REBUILD from the captured segments,
// encoding each one. Nothing is passed through.
//
// Held in the element those rules could only be checked by reading them. Here they are a table.

/** One entry in a preview's timeline, as the manifest records it. */
export interface HistoryVersion {
    /** Delivery-branch commit the render was published in (hosted mode). */
    commit?: string;
    /** Content sha addressing the render directly (project mode). */
    blob?: string;
}

/**
 * How this page addresses old renders. Exactly one of the two is ever present: a delivery repo to
 * link into, or — running `serve` against a local checkout — the server's own content-addressed
 * lane, because there is no published repo to point at.
 */
export interface HistorySource {
    /** `owner/name`, already validated and encoded, or null in project mode. */
    repoPath: string | null;
    /** Site-relative prefix up to the `{blob}` placeholder, or null in hosted mode. */
    blobBase: string | null;
    /** The template's query string, re-encoded, or `""`. */
    blobQuery: string;
    /**
     * Whether the strip describes renders this page is NOT showing. In project mode the stage comes
     * from the working tree while the timeline comes from published baselines, so the newest entry
     * is the last publish rather than "what you are looking at".
     */
    local: boolean;
}

/** Percent-encode one URL word, idempotent for one that already is. */
export function reencode(word: string): string {
    try {
        return encodeURIComponent(decodeURIComponent(word));
    } catch {
        // A stray `%` is not a valid escape and `decodeURIComponent` throws on it; encode literally.
        return encodeURIComponent(word);
    }
}

/**
 * Validate and rebuild an `owner/name`.
 *
 * The pattern admits only the shape a GitHub repo can take, and every character it admits is
 * URI-unreserved — so the encoding is a no-op on real values (identical bytes on the wire) and a
 * value that cannot be made safe by escaping simply yields null, and the strip is not drawn.
 */
export function repoPathOf(repo: string | null): string | null {
    if (!repo) return null;
    const parts =
        /^([A-Za-z0-9][A-Za-z0-9._-]*)\/([A-Za-z0-9][A-Za-z0-9._-]*)$/.exec(
            repo,
        );
    if (!parts) return null;
    return `${encodeURIComponent(parts[1])}/${encodeURIComponent(parts[2])}`;
}

/**
 * Validate and rebuild the project-mode blob template.
 *
 * Identical treatment for an identical flow. The `{blob}` placeholder is never substituted into the
 * passed-through string: it is dropped and the URL reassembled around the version's own sha. The
 * leading `\/(?!\/)` keeps the result site-relative — the character class has to admit `/` as a
 * separator, so the lookahead is what rejects a protocol-relative `//host/…` — and no `:` is
 * admitted anywhere, so no `javascript:` URL can match.
 */
export function blobTemplateOf(
    blobUrl: string | null,
): { base: string; query: string } | null {
    if (!blobUrl) return null;
    const parts =
        /^(\/(?!\/)[A-Za-z0-9._~%/-]*)\{blob\}(\.png)(\?[A-Za-z0-9._~%&=-]*)?$/.exec(
            blobUrl,
        );
    if (!parts) return null;
    // Path segments and query words are re-encoded individually, leaving the `/`, `?`, `&` and `=`
    // structure intact. Decoded first so a segment the server already encoded round-trips to the
    // same bytes instead of double-encoding (`%3A` → `:` → `%3A`, not `%253A`).
    return {
        base: parts[1].split("/").map(reencode).join("/"),
        query: (parts[3] || "").replace(/[^?&=]+/g, reencode),
    };
}

/** Resolve the two mutually exclusive addressing modes, or null when neither is usable. */
export function historySourceOf(
    repo: string | null,
    blobUrl: string | null,
): HistorySource | null {
    if (repo) {
        const repoPath = repoPathOf(repo);
        if (!repoPath) return null;
        return { repoPath, blobBase: null, blobQuery: "", local: false };
    }
    const template = blobTemplateOf(blobUrl);
    if (!template) return null;
    return {
        repoPath: null,
        blobBase: template.base,
        blobQuery: template.query,
        local: true,
    };
}

/**
 * The URL of one historical render, or null when the manifest names something that cannot be
 * addressed.
 *
 * Mirrors `ServeUrls.historicalRenderUrl` and rejects the same inputs for the same reason: the
 * manifest records shas, so accepting a ref would let a malformed manifest point the viewer at an
 * arbitrary branch. In project mode the version's content sha addresses it directly instead — the
 * same rule, one identifier shorter, and the server refuses any sha its own timeline does not name.
 *
 * Keyed on `source.local` rather than on which field is set, so a page that somehow carried both
 * stays coherent: one flag decides how an entry is addressed and how it is labelled.
 */
export function renderUrlAt(
    source: HistorySource,
    version: HistoryVersion,
    path: string | null,
): string | null {
    if (source.local) {
        if (!/^[0-9a-f]{40}$/.test(version.blob || "")) return null;
        return `${source.blobBase}${version.blob}.png${source.blobQuery}`;
    }
    const commit = version.commit;
    if (!/^[0-9a-fA-F]{7,40}$/.test(commit || "")) return null;
    if (!path || !path.startsWith("renders/") || path.includes(".."))
        return null;
    return (
        "https://raw.githubusercontent.com/" +
        `${source.repoPath}/${commit}/` +
        path.split("/").map(encodeURIComponent).join("/")
    );
}

/** `2026-08-15` from an ISO timestamp, or `""` when it is not one. */
export function shortDate(iso: string | null | undefined): string {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
    return m ? `${m[1]}-${m[2]}-${m[3]}` : "";
}
