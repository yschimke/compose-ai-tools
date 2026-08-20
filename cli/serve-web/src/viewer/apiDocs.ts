// The API reference links under the Source panel's snippet: which of the server's entries the
// viewer is willing to put an `href` on.
//
// The rest of the viewer refuses any server-sent URL that is not same-origin — the spec raster, the
// Wasm frame and the usage fetch all run that check. These links are the one place that rule is
// deliberately inverted: their whole purpose is to leave the origin, for `developer.android.com`.
// So the same discipline applies with the destination pinned instead of compared: https, that host,
// nothing else. A catalog cannot put an arbitrary href on the page by way of an import it wrote.
//
// DOM-free: `viewer.js` fetches the payload and passes the plain array.

/** One `apiDocs` entry as it arrives — mirrors `ApiDocLink` in `PlaygroundApi.kt`. */
export interface ApiDocLink {
    name?: string;
    fqn?: string;
    composable?: boolean;
    url?: string;
}

/** The same entry once it is known to be renderable: a name and a URL, both present. */
export interface UsableApiDocLink extends ApiDocLink {
    name: string;
    url: string;
}

/** The single host whose reference pages these links may open. */
const DOCS_HOST = "developer.android.com";

/**
 * The entries the panel may render, in the order the server sent them (composables first — the
 * component the card is about leads).
 *
 * Everything else is dropped silently rather than rendered as a dead or nameless chip: an older
 * server sends no `apiDocs` at all, and a payload that has been tampered with is not worth a
 * message the visitor can do nothing about.
 */
export function usableApiDocs(
    docs: ApiDocLink[] | undefined | null,
): UsableApiDocLink[] {
    if (!docs || !docs.length) return [];
    const out: UsableApiDocLink[] = [];
    for (const doc of docs) {
        if (!doc || !doc.url || !doc.name) continue;
        let parsed: URL;
        try {
            parsed = new URL(doc.url);
        } catch (e) {
            continue;
        }
        if (parsed.protocol !== "https:" || parsed.hostname !== DOCS_HOST)
            continue;
        out.push(doc as UsableApiDocLink);
    }
    return out;
}
