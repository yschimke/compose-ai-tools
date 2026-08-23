// The four inspection layers, what each is fetched from, and how they are addressed.

/** A layer: its checkbox value, its legend heading, and the endpoint suffix it reads. */
export interface LayerSpec {
    kind: string;
    label: string;
    /** `a11y` and `annotations` — the TWO endpoints behind four layers. */
    source: string;
}

export const LAYERS: LayerSpec[] = [
    { kind: "a11y", label: "Accessibility", source: "a11y" },
    { kind: "typography", label: "Typography", source: "annotations" },
    { kind: "theme", label: "Theme", source: "annotations" },
    { kind: "layout", label: "Layout", source: "annotations" },
];

/**
 * The endpoints a set of layers needs, deduplicated.
 *
 * Typography, Theme and Layout come from ONE payload, so ticking several must not fetch it once
 * each — and on an override-bearing frame every extra fetch is another daemon render, which can
 * come back describing different pixels than the first.
 */
export function sourcesFor(kinds: string[]): string[] {
    const out: string[] = [];
    for (const spec of LAYERS) {
        if (kinds.includes(spec.kind) && !out.includes(spec.source))
            out.push(spec.source);
    }
    return out;
}

/** Layers in their declared order — the legend's sections read the same way every time. */
export function activeLayers(kinds: string[]): LayerSpec[] {
    return LAYERS.filter((spec) => kinds.includes(spec.kind));
}

/**
 * The data URL for one endpoint, derived from the frame ON SCREEN.
 *
 * Deriving it from the displayed frame's URL — rather than rebuilding the override query here — is
 * what guarantees the overlay describes the pixels the visitor is looking at, including every knob
 * and display axis, with no second copy of the viewer's query rules to keep in step.
 *
 * Only the format suffix changes. A `scroll=long` frame has no inspection product of its own, so it
 * falls back to the viewport-sized one rather than 500ing.
 */
export function dataUrlFor(frameUrl: string, suffix: string): string | null {
    if (!frameUrl) return null;
    const cut = frameUrl.indexOf("?");
    const path = (cut < 0 ? frameUrl : frameUrl.slice(0, cut)).replace(
        /\.(png|svg)$/,
        "",
    );
    const query = cut < 0 ? "" : frameUrl.slice(cut);
    return `${path}.${suffix}${query}`;
}

/** The address to use before any frame has decoded: the preview's own, carrying the session keys. */
export function fallbackUrl(
    base: string,
    previewId: string,
    suffix: string,
    keys: { token?: string; session?: string },
): string {
    const parts: string[] = [];
    if (keys.token) parts.push(`token=${encodeURIComponent(keys.token)}`);
    if (keys.session) parts.push(`session=${encodeURIComponent(keys.session)}`);
    const query = parts.length ? `?${parts.join("&")}` : "";
    return `${base}/render/${encodeURIComponent(previewId)}.${suffix}${query}`;
}

/**
 * `/compose-m3/p/plain.Button` → `/compose-m3`, the prefix every render URL hangs off.
 *
 * Both surfaces that mount the layers are addressed the same way — the viewer at `/p/<id>`, the
 * focused comparison at `/compare/<id>` — so one rule serves both. This is only the *fallback*
 * address in either case: the frame on screen supplies the real one as soon as it has decoded.
 */
export function baseFrom(pathname: string): string {
    return pathname.replace(/\/(?:p|compare)\/[^/]*\/?$/, "");
}

/** The layers a `?inspect=` value names, in the order the page declares them. */
export function kindsFromParam(value: string | null): string[] {
    const wanted = (value ?? "").split(",").filter(Boolean);
    return LAYERS.filter((spec) => wanted.includes(spec.kind)).map(
        (spec) => spec.kind,
    );
}

/** What `?inspect=` should carry, or null to drop the parameter entirely. */
export function inspectParam(kinds: string[]): string | null {
    return kinds.length ? kinds.join(",") : null;
}
