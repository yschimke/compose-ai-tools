const LINK_ONLY_PARAMS = new Set(["token", "session", "at"]);

function isPublishedPng(url: string): boolean {
    let parsed: URL;
    try {
        parsed = new URL(url, "http://viewer.invalid");
    } catch {
        return false;
    }
    if (!parsed.pathname.endsWith(".png")) return false;
    for (const name of parsed.searchParams.keys()) {
        if (!LINK_ONLY_PARAMS.has(name)) return false;
    }
    return true;
}

/** Whether the visible stage still represents the snapshot used for the published spec score. */
export function specAtPublishedBaseline(
    stage: string,
    desiredPngUrl: string,
    landedRenderUrl: string | null,
): boolean {
    if (stage !== "snapshot" || !isPublishedPng(desiredPngUrl)) return false;

    // Before the first client-side fetch, the server-rendered image is the requested baseline.
    // Once a fetched frame has landed, its provenance is authoritative until replacement pixels
    // decode; controls and copyable links intentionally move ahead of the visible image.
    return landedRenderUrl === null || isPublishedPng(landedRenderUrl);
}
