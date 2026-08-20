// Which two artifacts a row compares, and whether the page can compare them at all.

/** The formats the wall can put beside a baked PNG, in the order it prefers them. */
export const FORMATS = ["svg", "rc", "reference"] as const;

export type Format = (typeof FORMATS)[number];

/** What a row carries, keyed `<kind>-<variant>` exactly as the server writes its `data-` attributes. */
export type RowSources = (kind: string, variant: string) => string;

/** Which formats this page has something to compare. */
export interface Available {
    svg: boolean;
    rc: boolean;
    reference: boolean;
}

export function supportsFormat(
    candidate: string | null | undefined,
    available: Available,
): candidate is Format {
    return candidate === "svg" ||
        candidate === "rc" ||
        candidate === "reference"
        ? available[candidate]
        : false;
}

/**
 * The variant a row can be compared in, or `""` for one this format cannot show.
 *
 * The theme the visitor picked first, then `neutral` — and NEVER the opposite baked theme. A dark
 * PNG paired with a light vector looks plausible and produces a meaningless number, which is worse
 * than showing nothing: the row reports a fidelity problem that is really a pairing mistake. A
 * theme-neutral component stays visible under either theme, because for it there is only one
 * artifact and it is the right one.
 */
export function variantFor(
    sources: RowSources,
    format: Format,
    theme: string,
): string {
    for (const variant of [theme, "neutral"]) {
        if (sources("png", variant) && sources(format, variant)) return variant;
    }
    return "";
}

/**
 * The background a row is drawn on.
 *
 * A row whose pairing is genuinely light or dark says so and is taken at its word. Everything else
 * — a `neutral` pairing, or a row this format cannot show — has no theme *of its own*, and used to
 * be answered "light" unconditionally. That is wrong for a dark-first catalog, where a theme-neutral
 * component is still drawn for a black watch face: its white-on-transparent sticker landed on the
 * light sheet and read as nearly blank, in the table meant to compare it
 * (yschimke/wear-m3-catalog#56).
 *
 * So an absent theme defers to [stage] — the catalog's declared stage, which the wall already
 * carries as `data-default-theme`. "Neutral" means neutral *between the catalog's themes*, not
 * "light".
 */
export function rowTheme(variant: string, stage?: string): "light" | "dark" {
    if (variant === "dark" || variant === "light") return variant;
    return stage === "dark" ? "dark" : "light";
}

/**
 * The first format this page has anything for.
 *
 * The last resort when neither the URL nor the page's own default names a usable lane — a catalog
 * that publishes only Remote Compose has no SVG to fall back to, and answering "svg" there opens the
 * wall on a lane with nothing in it. An empty table reads as "nothing matches your filter", which is
 * the wrong answer to "this catalog does not publish that format".
 */
export function firstAvailable(available: Available): Format | null {
    return FORMATS.find((format) => available[format]) ?? null;
}
