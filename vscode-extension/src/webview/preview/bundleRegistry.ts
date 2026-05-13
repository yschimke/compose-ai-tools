// Bundle registry — defines the cluster-level toggles (A11y, Theming,
// Text/i18n, Resources, Inspection, Performance, Display, Watch,
// History, Errors) that the chip bar and tab row drive.
//
// One bundle bundles several wire `kind`s. Default-ON kinds are
// subscribed when the chip toggles on; expander-only kinds are
// available via the per-tab "Configure…" expander.
//
// See `docs/design/EXTENSION_DATA_EXPOSURE.md` for the full design.

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
    | "errors";

export interface BundleKind {
    /** Wire `kind` advertised by the daemon catalogue. */
    kind: string;
    /** Short human label used in the configure expander. */
    label: string;
    /** Whether the kind is auto-subscribed when the bundle chip is
     *  toggled on. `false` = available only via the expander. */
    defaultOn: boolean;
}

export interface BundleDescriptor {
    id: BundleId;
    /** Chip label and tab title. */
    label: string;
    /** Codicon name used in the chip + tab header. */
    icon: string;
    /** Kinds in this bundle, in display order. */
    kinds: readonly BundleKind[];
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
        kinds: [
            {
                kind: "displayfilter/grayscale",
                label: "Grayscale",
                defaultOn: false,
            },
            {
                kind: "displayfilter/invert",
                label: "Invert",
                defaultOn: false,
            },
        ],
    },
    {
        id: "watch",
        label: "Watch",
        icon: "device-mobile",
        kinds: [{ kind: "compose/ambient", label: "Ambient", defaultOn: true }],
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
