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

/**
 * The stages whose comparison subject is the baked PNG snapshot.
 *
 * `spec` is one of them, which is not obvious and is the whole reason this is a set. The spec lane
 * takes the render off the stage and puts the imported reference there — but the frame it is
 * COMPARING is still the snapshot underneath (`specActualUrl` hands the lane that image, or its
 * object URL). Excluding `spec` made this predicate answer `false` for every visit to the lane,
 * baseline or not, so the one consumer that exists could never tell a clean comparison from one
 * taken against an overridden render. Every other stage — `live`, `wasm`, `rc` — genuinely is not
 * the baked snapshot and stays out.
 */
const SNAPSHOT_STAGES = new Set(["snapshot", "spec"]);

/** Whether the visible stage still represents the snapshot used for the published spec score. */
export function specAtPublishedBaseline(
    stage: string,
    desiredPngUrl: string,
    landedRenderUrl: string | null,
): boolean {
    if (!SNAPSHOT_STAGES.has(stage) || !isPublishedPng(desiredPngUrl))
        return false;

    // Before the first client-side fetch, the server-rendered image is the requested baseline.
    // Once a fetched frame has landed, its provenance is authoritative until replacement pixels
    // decode; controls and copyable links intentionally move ahead of the visible image.
    return landedRenderUrl === null || isPublishedPng(landedRenderUrl);
}
