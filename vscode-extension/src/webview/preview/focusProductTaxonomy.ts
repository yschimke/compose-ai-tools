// Pure logic that turns the changing list of daemon-advertised data
// products into a stable, ranked, partly-suggested UI for the focus
// inspector.
//
// Three concerns live here, all as pure functions so they're easy to
// unit-test against synthetic capability lists / `PreviewInfo` shapes:
//
//   1. Taxonomy — which of five fixed buckets a `kind` belongs to,
//      derived from its namespace prefix. New unknown namespaces fall
//      through to "More" so the chrome never loses items.
//   2. Cheapness — whether a kind can be auto-enabled without
//      meaningful render-cost impact. Kept conservative: anything that
//      forces a re-render (`requiresRerender`) or instruments the
//      composition (`compose/recomposition`, `render/trace`) is
//      treated as expensive regardless of facet.
//   3. Suggestions — the small chip row above the bucket list. Driven
//      by the focused preview's annotations / device / cached a11y
//      findings, joined with a per-scope MRU map maintained by the
//      controller. The chip set is intentionally short (capped at
//      MAX_SUGGESTIONS) so the eye can land on it.
//
// `focusInspector.ts` is the only consumer; tests live in
// `focusProductTaxonomy.test.ts`.

import type { PreviewInfo } from "../shared/types";

/** Five stable user-facing buckets. Order matches inspector render order. */
export const PRODUCT_BUCKETS = [
    "accessibility",
    "layout",
    "performance",
    "theming",
    "resources",
] as const;

export type ProductBucket = (typeof PRODUCT_BUCKETS)[number] | "more";

/**
 * One product the inspector can show the user. Mirrors the bits of a
 * daemon-advertised `DataProductCapability` we actually render, plus
 * the few "always-shown" entries the panel ships built-in (a11y
 * overlay, layout tree placeholder, recomposition).
 *
 * `kind` is the wire-level kind string when the product comes from the
 * daemon, or a stable `local/<id>` string for built-ins. The controller
 * uses `kind` as the toggle / MRU key.
 */
export interface ProductDescriptor {
    kind: string;
    label: string;
    icon: string;
    /**
     * Short tagline shown under the label in the picker. May be the
     * dynamic count ("3 findings") supplied by the controller, or a
     * hint ("Overlay", "Placeholder").
     */
    hint?: string;
    /**
     * Cost class. `cheap` products may auto-enable when the
     * `autoEnableCheap` setting is on AND the product is suggested.
     * `expensive` products are never auto-enabled.
     */
    cost: "cheap" | "expensive";
    /**
     * `true` when toggling has a daemon-side side effect (subscribe,
     * extra data fetch). `false` for purely cosmetic placeholders that
     * the panel renders client-side. Used by tooltips and by the auto-
     * enable gate (we still suggest cosmetic placeholders, but never
     * auto-flip them since "auto-enable" carries an implicit cost
     * assertion the user shouldn't have to second-guess).
     */
    daemonBacked: boolean;
}

/** Bucket label and codicon. Stable; never recomputed from the daemon. */
export const BUCKET_META: Record<
    ProductBucket,
    { label: string; icon: string }
> = {
    accessibility: { label: "Accessibility", icon: "eye" },
    layout: { label: "Layout", icon: "list-tree" },
    performance: { label: "Performance", icon: "pulse" },
    theming: { label: "Theming", icon: "symbol-color" },
    resources: { label: "Resources", icon: "file-code" },
    more: { label: "More", icon: "ellipsis" },
};

/**
 * Map a wire `kind` to its bucket. Unknown namespaces fall through to
 * `"more"` — the chrome never grows a sixth bucket as new namespaces
 * land; the contents shift instead.
 *
 * Special-cased exactly twice:
 *   - `compose/recomposition` is performance, not theming
 *   - `compose/theme` is theming
 *
 * Everything else routes by namespace prefix: `a11y/*` → accessibility,
 * `layout/*` → layout, `render/*` → performance, `resources/*` /
 * `fonts/*` / `text/*` → resources.
 */
export function bucketOf(kind: string): ProductBucket {
    if (kind === "compose/recomposition") return "performance";
    if (kind === "compose/theme") return "theming";
    if (kind.startsWith("a11y/")) return "accessibility";
    if (kind.startsWith("layout/")) return "layout";
    if (kind.startsWith("render/")) return "performance";
    if (
        kind.startsWith("resources/") ||
        kind.startsWith("fonts/") ||
        kind.startsWith("text/")
    ) {
        return "resources";
    }
    if (kind.startsWith("local/")) {
        // Built-ins encode their bucket in the suffix: `local/a11y/...`,
        // `local/layout/...` etc. Strip and recurse.
        return bucketOf(kind.slice("local/".length));
    }
    return "more";
}

/**
 * Cheapness classifier. Two-tier:
 *
 *   1. **Allowlist** (highest authority) — a small set of kinds we
 *      know first-hand are inspections of already-captured render
 *      output (theme tokens, strings, fonts, resources, static layout
 *      tree). Always returns `cheap` for these regardless of any
 *      daemon hint. A daemon misadvertising one of these as expensive
 *      doesn't make us refuse to auto-enable; a daemon misadvertising
 *      something else as cheap can't slip into this list.
 *
 *   2. **Daemon hint fallback** — for kinds outside the allowlist,
 *      trust the daemon's `requiresRerender` flag from
 *      `DataProductCapability`. `requiresRerender === false` means
 *      "fetching this won't pay a render cost," which lines up with
 *      the auto-enable contract (no surprise spikes). When the hint is
 *      missing or `true`, default to `expensive`.
 *
 * Built-in placeholder products stamp their `cost` directly on the
 * descriptor and never call this function; only the daemon-product
 * translation in `productsFromDaemonCapabilities` exercises the hint
 * path.
 */
export function costOf(
    kind: string,
    hint?: { requiresRerender?: boolean },
): "cheap" | "expensive" {
    if (
        kind === "compose/theme" ||
        kind.startsWith("text/") ||
        kind.startsWith("fonts/") ||
        kind.startsWith("resources/") ||
        kind === "layout/tree"
    ) {
        return "cheap";
    }
    if (hint && hint.requiresRerender === false) {
        return "cheap";
    }
    return "expensive";
}

/**
 * Suggestion heuristic. Returns at most [MAX_SUGGESTIONS] kinds judged
 * relevant to the focused preview, deduped, in display order.
 *
 * Inputs intentionally narrow:
 *   - `preview` — the focused `PreviewInfo`. Annotations + device
 *     hints + carousel `dataProducts` come off here.
 *   - `findingsCount` — current cached a11y finding count for the
 *     preview, since the inspector already has it and recomputing
 *     from `preview.a11yFindings` would miss daemon-pushed updates.
 *   - `mru` — kinds the user has toggled in this scope before, sorted
 *     most-recent first. Acts as a tiebreaker / topup once the
 *     annotation-driven suggestions are exhausted.
 *   - `available` — the union of kinds the inspector currently knows
 *     about (built-ins + daemon-advertised). Suggestions outside this
 *     set are dropped — we don't want to suggest something the daemon
 *     can't produce.
 *
 * Order matters: annotation-driven first (strongest signal), then MRU
 * (familiarity), then a fixed "always-helpful" fallback (a11y).
 */
export const MAX_SUGGESTIONS = 4;

export interface SuggestInput {
    preview: PreviewInfo;
    findingsCount: number;
    mru: readonly string[];
    available: ReadonlySet<string>;
}

export function suggestFor(input: SuggestInput): string[] {
    const { preview, findingsCount, mru, available } = input;
    const out: string[] = [];
    const push = (kind: string): void => {
        if (out.length >= MAX_SUGGESTIONS) return;
        if (!available.has(kind)) return;
        if (out.includes(kind)) return;
        out.push(kind);
    };

    // 1. Annotation-driven. Wear devices + scroll captures imply
    //    scroll trace / scroll-aware data products. We don't currently
    //    ship a `compose/scroll` kind, so we surface
    //    `compose/recomposition` as the closest available proxy when
    //    a scroll capture is present. (When a dedicated scroll kind
    //    lands the heuristic moves to that.)
    const isWear = isWearDevice(preview.params.device);
    const hasScrollCapture =
        (preview.dataProducts ?? []).some((p) => p.scroll != null) ||
        preview.captures.some((c) => c.scroll != null);
    if (hasScrollCapture || isWear) {
        push("compose/recomposition");
    }

    // 2. A11y findings already exist for this preview — strongest
    //    signal that the user came here to look at a11y issues.
    if (findingsCount > 0) {
        push("local/a11y/overlay");
    }

    // 3. Theme tokens are cheap and useful when the preview's UI mode
    //    is non-default (dark mode, car, TV) — the user is probably
    //    auditing the theme.
    if (preview.params.uiMode && preview.params.uiMode !== 0) {
        push("compose/theme");
    }

    // 4. MRU topup. Don't repeat what we already pushed.
    for (const kind of mru) {
        push(kind);
    }

    // 5. Always-helpful fallback. A11y overlay is the single most
    //    likely "I'd want to see this" toggle and costs nothing when
    //    the daemon doesn't actually have findings (the overlay just
    //    shows nothing).
    push("local/a11y/overlay");

    return out;
}

/**
 * Returns true if the device id looks like a Wear / watch device.
 * Wear is the "particularly think about" example in the design — but
 * heuristics are forgiving: `id:wearos_*`, `wear` substring, `watch`
 * substring, and `spec:` overrides with `round=true` all count.
 *
 * Exported so tests can pin the boundary.
 */
export function isWearDevice(device: string | null | undefined): boolean {
    if (!device) return false;
    const lower = device.toLowerCase();
    if (lower.includes("wear")) return true;
    if (lower.includes("watch")) return true;
    if (lower.startsWith("spec:") && lower.includes("round=true")) return true;
    return false;
}

/**
 * Group descriptors into buckets, preserving input order within each
 * bucket. The inspector calls this once per render and walks the
 * result; the controller doesn't otherwise need to think about
 * bucketing.
 *
 * Buckets are returned in `PRODUCT_BUCKETS` order followed by `more`.
 * Empty buckets are still returned (with an empty array) so the
 * inspector can render a stable five-row chrome and let buckets
 * collapse-by-default when empty — easier to skim than a UI that
 * grows and shrinks rows.
 */
export function groupByBucket(
    products: readonly ProductDescriptor[],
): Map<ProductBucket, ProductDescriptor[]> {
    const out = new Map<ProductBucket, ProductDescriptor[]>();
    for (const bucket of PRODUCT_BUCKETS) out.set(bucket, []);
    out.set("more", []);
    for (const p of products) {
        const bucket = bucketOf(p.kind);
        out.get(bucket)!.push(p);
    }
    return out;
}

/**
 * Per-scope MRU helper. The controller maintains an in-memory list of
 * recently-toggled kinds keyed by scope (module dir today). Calling
 * this on a toggle moves [kind] to the front and trims to [maxLen].
 */
export function bumpMru(
    current: readonly string[],
    kind: string,
    maxLen = 8,
): string[] {
    const next = [kind, ...current.filter((k) => k !== kind)];
    return next.slice(0, maxLen);
}
