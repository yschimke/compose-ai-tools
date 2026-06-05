// Bundle registry — defines the cluster-level toggles (A11y, Theming,
// Text/i18n, Resources, Inspection, Performance, Display, Watch,
// History, Errors) that the chip bar and tab row drive.
//
// One bundle bundles several wire `kind`s. Default-ON kinds are
// subscribed when the chip toggles on; expander-only kinds are
// available via the per-tab "Configure…" expander.
//
// See `docs/design/EXTENSION_DATA_EXPOSURE.md` for the full design.

import type { PreviewInfo } from "../shared/types";
import { isWearPreview } from "./cardData";

export type BundleId =
    | "a11y"
    | "theming"
    | "text"
    | "resources"
    | "inspection"
    | "performance"
    | "display"
    | "watch"
    | "history"
    | "errors"
    | "remotecompose"
    | "lottie";

export interface BundleKind {
    /** Wire `kind` advertised by the daemon catalogue. */
    kind: string;
    /** Short human label used in the configure expander. */
    label: string;
    /** Whether the kind is auto-subscribed when the bundle chip is
     *  toggled on. `false` = available only via the expander. */
    defaultOn: boolean;
}

/**
 * The slice of a focused preview a bundle's `appliesTo` predicate reads to decide whether its chip
 * is relevant. Kept to plain, serialisable signals (no DOM, no `PreviewInfo`) so the predicates stay
 * pure and unit-testable.
 */
export interface BundlePreviewContext {
    /** `PreviewInfo.params.kind` — e.g. `"LOTTIE"`, `"COMPOSE"`, `"TILE"`, or `null`. */
    kind: string | null;
    /** Whether the preview is a Wear preview (`isWearPreview` — wear device or square ≤260dp). */
    isWear: boolean;
    /** Data-product kinds attached to this preview (e.g. `"animation/lottie"`). */
    dataProductKinds: ReadonlySet<string>;
}

export interface BundleDescriptor {
    id: BundleId;
    /** Chip label and tab title. */
    label: string;
    /** Codicon name used in the chip + tab header. */
    icon: string;
    /** Kinds in this bundle, in display order. */
    kinds: readonly BundleKind[];
    /**
     * Optional per-preview relevance gate. When present and it returns `false` for the focused
     * preview, the bundle's chip is hidden in focus mode — so e.g. the Lottie scrubber only shows
     * for Lottie previews and the Watch/ambient bundle only for Wear previews. Absent ⇒ the bundle
     * is universal (shown for every preview, subject to the early-features gate).
     */
    appliesTo?: (ctx: BundlePreviewContext) => boolean;
}

/**
 * Catalogue of bundles. Order is the default chip-bar order;
 * runtime MRU may move active bundles to the front.
 */
export const BUNDLES: readonly BundleDescriptor[] = [
    {
        id: "a11y",
        label: "Accessibility",
        icon: "eye",
        kinds: [
            { kind: "a11y/hierarchy", label: "Hierarchy", defaultOn: true },
            { kind: "a11y/atf", label: "Findings (ATF)", defaultOn: true },
            {
                kind: "a11y/touchTargets",
                label: "Touch targets",
                defaultOn: false,
            },
            {
                kind: "a11y/overlay",
                label: "Daemon overlay PNG",
                defaultOn: false,
            },
        ],
    },
    {
        id: "theming",
        label: "Theming",
        icon: "symbol-color",
        kinds: [
            { kind: "compose/theme", label: "Theme tokens", defaultOn: true },
            { kind: "compose/wallpaper", label: "Wallpaper", defaultOn: false },
        ],
    },
    {
        id: "text",
        label: "Text / i18n",
        icon: "symbol-string",
        kinds: [
            { kind: "text/strings", label: "Drawn text", defaultOn: true },
            { kind: "fonts/used", label: "Fonts", defaultOn: true },
            {
                kind: "i18n/translations",
                label: "Translations",
                defaultOn: false,
            },
        ],
    },
    {
        id: "resources",
        label: "Resources",
        icon: "library",
        kinds: [
            {
                kind: "resources/used",
                label: "Resources used",
                defaultOn: true,
            },
        ],
    },
    {
        id: "inspection",
        label: "Inspection",
        icon: "search",
        kinds: [
            {
                kind: "compose/semantics",
                label: "Semantics",
                defaultOn: true,
            },
            {
                kind: "layout/inspector",
                label: "Layout inspector",
                defaultOn: false,
            },
            {
                kind: "uia/hierarchy",
                label: "UI Automator",
                defaultOn: false,
            },
            {
                kind: "compose/permissions",
                label: "Permissions",
                defaultOn: false,
            },
            {
                kind: "compose/launcher-widget",
                label: "Launcher widget size",
                defaultOn: false,
            },
        ],
    },
    {
        id: "performance",
        label: "Performance",
        icon: "pulse",
        kinds: [
            {
                kind: "compose/recomposition",
                label: "Recomposition",
                defaultOn: false,
            },
            { kind: "render/trace", label: "Render trace", defaultOn: false },
            {
                kind: "render/composeAiTrace",
                label: "Perfetto trace",
                defaultOn: false,
            },
        ],
    },
    {
        id: "display",
        label: "Display",
        icon: "device-desktop",
        // The daemon advertises a single `displayfilter/variants` kind
        // whose payload enumerates every enabled filter PNG. The per-
        // filter row UI ("Grayscale", "Invert", daltonizer sims) is
        // derived from that payload in `displayFilterBundlePresenter`;
        // they are not separate wire kinds.
        kinds: [
            {
                kind: "displayfilter/variants",
                label: "Filter variants",
                defaultOn: true,
            },
        ],
    },
    {
        id: "watch",
        label: "Watch",
        icon: "device-mobile",
        kinds: [{ kind: "compose/ambient", label: "Ambient", defaultOn: true }],
        // Ambient / always-on-display only makes sense for Wear surfaces.
        appliesTo: (ctx) => ctx.isWear,
    },
    {
        id: "history",
        label: "History",
        icon: "git-compare",
        kinds: [
            {
                kind: "history/diff/regions",
                label: "Diff regions",
                defaultOn: true,
            },
        ],
    },
    {
        id: "errors",
        label: "Errors",
        icon: "error",
        kinds: [
            {
                kind: "test/failure",
                label: "Postmortem",
                defaultOn: false,
            },
        ],
    },
    {
        id: "remotecompose",
        label: "Remote Compose",
        icon: "globe",
        kinds: [
            {
                kind: "compose/remotecompose",
                label: "State + actions",
                defaultOn: true,
            },
        ],
    },
    {
        id: "lottie",
        label: "Lottie",
        icon: "play",
        kinds: [
            {
                kind: "animation/lottie",
                label: "Timeline",
                defaultOn: true,
            },
        ],
        // A timeline scrubber only applies to a Lottie preview — a file-discovered `kind=LOTTIE`
        // entry, or any preview the daemon attached an `animation/lottie` product to (e.g. a
        // `@Preview` calling `LottiePreview`).
        appliesTo: (ctx) =>
            ctx.kind === "LOTTIE" ||
            ctx.dataProductKinds.has("animation/lottie"),
    },
];

const BY_ID = new Map<BundleId, BundleDescriptor>(
    BUNDLES.map((b) => [b.id, b]),
);

export function getBundle(id: BundleId): BundleDescriptor | undefined {
    return BY_ID.get(id);
}

/**
 * Bundle id that owns [kind], or `null` when no bundle in the catalogue
 * advertises it. Two bundles can't claim the same kind — the registry
 * is asserted unique below.
 */
export function bundleForKind(kind: string): BundleId | null {
    for (const b of BUNDLES) {
        for (const k of b.kinds) {
            if (k.kind === kind) return b.id;
        }
    }
    return null;
}

/**
 * Default-ON kinds for [bundleId]. Used when toggling a chip on for
 * the first time without per-kind overrides.
 */
export function defaultOnKindsFor(bundleId: BundleId): readonly string[] {
    const b = BY_ID.get(bundleId);
    if (!b) return [];
    return b.kinds.filter((k) => k.defaultOn).map((k) => k.kind);
}

/**
 * Build the {@link BundlePreviewContext} a bundle's `appliesTo` predicate reads from a preview.
 *
 * [liveDataProductKinds] folds in products attached *after* focus via the webview's
 * `updateDataProducts` cache — `PreviewInfo.dataProducts` is the manifest/annotation-side field and
 * is never updated from that live cache, so without this a COMPOSE preview that receives a live
 * `animation/lottie` attachment would never surface the Lottie chip.
 */
export function previewBundleContext(
    p: PreviewInfo,
    liveDataProductKinds?: ReadonlySet<string>,
): BundlePreviewContext {
    const dataProductKinds = new Set<string>(
        (p.dataProducts ?? []).map((dp) => dp.kind),
    );
    if (liveDataProductKinds) {
        for (const k of liveDataProductKinds) dataProductKinds.add(k);
    }
    return {
        kind: p.params?.kind ?? null,
        isWear: isWearPreview(p),
        dataProductKinds,
    };
}

/**
 * Whether [bundle] is relevant to [preview]. A bundle with no `appliesTo` predicate is universal.
 * A gated bundle (Lottie, Watch) needs a focused preview it can act on — `preview == null` (nothing
 * focused) therefore hides it. [liveDataProductKinds] threads the focused preview's live product
 * cache through (see {@link previewBundleContext}).
 */
export function bundleAppliesToPreview(
    bundle: BundleDescriptor,
    preview: PreviewInfo | null,
    liveDataProductKinds?: ReadonlySet<string>,
): boolean {
    if (!bundle.appliesTo) return true;
    if (!preview) return false;
    return bundle.appliesTo(
        previewBundleContext(preview, liveDataProductKinds),
    );
}

/**
 * The bundle ids whose chip should be visible in focus mode for [preview]. Starts from the
 * early-features base set — every bundle when on, or just the graduated `a11y` when off — and drops
 * bundles that don't apply to the focused preview (so Lottie only shows for Lottie previews, Watch
 * only for Wear). Mirrors the existing `availableBundles` filtering the chip bar already honours.
 *
 * `opts.dataProductKinds` is the focused preview's live product-kind cache (from
 * `updateDataProducts`), folded into the relevance check so live attachments count.
 */
export function availableBundleIdsForPreview(
    preview: PreviewInfo | null,
    opts: { earlyFeatures: boolean; dataProductKinds?: ReadonlySet<string> },
): BundleId[] {
    const base: BundleId[] = opts.earlyFeatures
        ? BUNDLES.map((b) => b.id)
        : ["a11y"];
    return base.filter((id) => {
        const bundle = BY_ID.get(id);
        return bundle
            ? bundleAppliesToPreview(bundle, preview, opts.dataProductKinds)
            : false;
    });
}

// Internal correctness check — duplicate kinds across bundles would
// make `bundleForKind` ambiguous. Runs at module load; throws so a
// catalogue typo doesn't reach production.
(function assertUniqueKinds(): void {
    const seen = new Map<string, BundleId>();
    for (const b of BUNDLES) {
        for (const k of b.kinds) {
            const prior = seen.get(k.kind);
            if (prior) {
                throw new Error(
                    `Kind ${k.kind} appears in both ${prior} and ${b.id} bundles`,
                );
            }
            seen.set(k.kind, b.id);
        }
    }
})();
