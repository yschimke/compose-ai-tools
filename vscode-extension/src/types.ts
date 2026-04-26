export interface PreviewParams {
    name: string | null;
    device: string | null;
    widthDp: number | null;
    heightDp: number | null;
    fontScale: number;
    showSystemUi: boolean;
    showBackground: boolean;
    backgroundColor: number;
    uiMode: number;
    locale: string | null;
    group: string | null;
    /**
     * FQN of the `PreviewParameterProvider` harvested from `@PreviewParameter`,
     * when the discovery pass saw one on this preview's function signature.
     * Extension uses this to know to glob for `<stem>_<suffix>.<ext>` files
     * (one per provider value — `<suffix>` is a human-readable label derived
     * from the value, or `PARAM_<idx>` when no label can be derived) rather
     * than expecting the manifest's single template capture to exist on disk
     * verbatim.
     */
    previewParameterProviderClassName?: string | null;
    previewParameterLimit?: number | null;
}

/**
 * Scroll state of a capture. Intent fields (mode, axis, maxScrollPx,
 * reduceMotion) come from `@ScrollingPreview`; outcome fields (atEnd,
 * reachedPx) are populated by the renderer post-capture.
 */
export interface ScrollCapture {
    mode: 'TOP' | 'END' | 'LONG' | string;
    axis: 'VERTICAL' | 'HORIZONTAL' | string;
    maxScrollPx: number;
    reduceMotion: boolean;
    /** Scrollable reached the end of its content before the renderer stopped.
     *  Distinct from `reachedPx === maxScrollPx`, which means the cap fired. */
    atEnd: boolean;
    /** Pixels actually scrolled. null when not reported. */
    reachedPx: number | null;
}

/**
 * Threshold above which a capture's `cost` is considered "heavy" — dropped
 * from `composePreview.tier=fast` renders, surfaces in VS Code with a
 * stale-state badge, refreshed only on explicit user action. Mirrors the
 * plugin's `HEAVY_COST_THRESHOLD` in `PreviewData.kt`.
 */
export const HEAVY_COST_THRESHOLD = 5;

/**
 * One rendered snapshot of a preview. Non-null dimensional fields
 * (advanceTimeMillis, scroll) are the coordinates that distinguish this
 * capture from its siblings. A static preview has a single capture with
 * everything null.
 */
export interface Capture {
    advanceTimeMillis: number | null;
    scroll: ScrollCapture | null;
    renderOutput: string;
    /** Human-readable summary of this capture's non-null dimensions —
     *  e.g. `'500ms'`, `'scrolled end'`, `'500ms · scrolled end'`, or `''`
     *  for a plain static preview. Populated extension-side (see
     *  [captureLabels.captureLabel]) before sending to the webview so the
     *  carousel markup stays free of dimension logic. */
    label?: string;
    /**
     * Estimated render cost, normalised so a static `@Preview` is `1.0`.
     * Catalogue: TOP=1, END=3, LONG=20, GIF=40, animation=50. Tooling
     * thresholds on `cost > HEAVY_COST_THRESHOLD` to decide what to skip
     * during interactive saves. Defaults to `1` when missing (older
     * manifests pre-cost-field).
     */
    cost?: number;
}

export interface PreviewInfo {
    id: string;
    functionName: string;
    className: string;
    sourceFile: string | null;
    params: PreviewParams;
    /** Rendered snapshots — always at least one. Length > 1 ⇔ carousel. */
    captures: Capture[];
    /** Populated by the extension (not the Gradle manifest) — `true` iff
     *  the preview has at least one archived snapshot on disk. The webview
     *  uses this to decide whether to show the history button at all. */
    hasHistory?: boolean;
    /**
     * Populated by the extension from the sidecar accessibility.json referenced
     * in [PreviewManifest.accessibilityReport]. `null`/`undefined` means
     * accessibility checks were disabled for this module; an empty array means
     * checks ran and found nothing.
     */
    a11yFindings?: AccessibilityFinding[] | null;
    /** Absolute path to the annotated screenshot (clean PNG + overlay legend), when findings exist. */
    a11yAnnotatedPath?: string | null;
}

export interface PreviewManifest {
    module: string;
    variant: string;
    previews: PreviewInfo[];
    /** Relative path (from `previews.json`) to the sidecar a11y report, or null. */
    accessibilityReport?: string | null;
}

export interface AccessibilityFinding {
    level: 'ERROR' | 'WARNING' | 'INFO' | string;
    type: string;
    message: string;
    viewDescription?: string | null;
    boundsInScreen?: string | null;
}

export interface AccessibilityReport {
    module: string;
    entries: {
        previewId: string;
        findings: AccessibilityFinding[];
        annotatedPath?: string | null;
    }[];
}

// -------------------------------------------------------------------------
// Android XML resource previews — mirrors of the Kotlin types in
// `gradle-plugin/.../PreviewData.kt` / `renderer-android/.../RenderResourceManifest.kt`.
// See `docs/ANDROID_RESOURCE_PREVIEWS.md` for the data model.
// -------------------------------------------------------------------------

export type ResourceType = 'VECTOR' | 'ANIMATED_VECTOR' | 'ADAPTIVE_ICON' | string;

export type AdaptiveShape = 'CIRCLE' | 'ROUNDED_SQUARE' | 'SQUARE' | 'LEGACY' | string;

export interface ResourceVariant {
    /**
     * Resource qualifier suffix as written in the AAPT directory name, sans the leading dash —
     * e.g. `'xhdpi'`, `'night-xhdpi'`, `'ldrtl-xhdpi-v26'`. `null` for the default-qualifier
     * configuration. This is the runtime configuration the capture was rendered under, not the
     * qualifier of any particular source file (AAPT picks whichever matches at render time).
     */
    qualifiers: string | null;
    /** Adaptive-icon shape mask. `null` for non-adaptive resources. */
    shape: AdaptiveShape | null;
}

export interface ResourceCapture {
    variant: ResourceVariant | null;
    /** Module-relative PNG / GIF path, e.g. `renders/resources/drawable/ic_compose_logo_xhdpi.png`. */
    renderOutput: string;
    /**
     * Estimated render cost — same scale as `Capture.cost`. `RESOURCE_STATIC_COST=1`,
     * `RESOURCE_ADAPTIVE_COST=4`, `RESOURCE_ANIMATED_COST=35`. Adaptive + animated land above
     * `HEAVY_COST_THRESHOLD` so they're treated as heavy captures by the same tier filter that
     * skips composable LONG / GIF / animation captures.
     */
    cost?: number;
}

export interface ResourcePreview {
    /** `<base>/<name>` — `'drawable/ic_compose_logo'`, `'mipmap/ic_launcher'`. */
    id: string;
    type: ResourceType;
    /**
     * Every contributing source file keyed by qualifier suffix. Empty string `''` for the
     * default-qualifier file, the verbatim qualifier suffix otherwise (`'night'`,
     * `'anydpi-v26'`, …). The empty-string convention keeps the JSON valid: nullable map keys
     * would serialise as bare `null:` literals which standard JSON parsers reject.
     */
    sourceFiles: Record<string, string>;
    captures: ResourceCapture[];
}

/**
 * One drawable / mipmap reference observed in `AndroidManifest.xml`. References don't trigger
 * captures — they're an index that lets tooling link manifest lines to the already-rendered
 * resource preview by `(resourceType, resourceName)`.
 */
export interface ManifestReference {
    /** Module-relative path of the manifest file the reference came from. */
    source: string;
    /** Tag name of the component the attribute lives on: `application`, `activity`, … */
    componentKind: string;
    /** FQN of the activity / service / receiver / provider; `null` for `<application>`. */
    componentName: string | null;
    /** Attribute name including namespace prefix, e.g. `'android:icon'`. */
    attributeName: string;
    /** `'drawable'` or `'mipmap'`. */
    resourceType: string;
    /** Resource name without the `@type/` prefix, e.g. `'ic_launcher'`. */
    resourceName: string;
}

export interface ResourceManifest {
    module: string;
    variant: string;
    resources: ResourcePreview[];
    manifestReferences: ManifestReference[];
}

/**
 * Output of `:<module>:composePreviewDoctor`. Matches the serialization in
 * `gradle-plugin/.../ComposePreviewDoctorTask.kt` and the per-module shape
 * inside `compose-preview doctor --json`'s `DoctorReport.checks`. Schema
 * version pinned in [DoctorModuleReport.schema] so extension can detect
 * incompatible plugin versions without dispatching on field shape.
 */
export interface DoctorModuleReport {
    schema: string;
    module: string;
    variant: string;
    findings: DoctorFinding[];
}

export interface DoctorFinding {
    id: string;
    severity: 'error' | 'warning' | 'info' | string;
    message: string;
    detail?: string | null;
    remediationSummary?: string | null;
    remediationCommands?: string[];
    docsUrl?: string | null;
}

/**
 * One historical snapshot for a preview. The Gradle plugin writes files
 * named `yyyyMMdd-HHmmss[-N].png`; [timestamp] is the filename minus `.png`,
 * kept as a display-friendly label. [iso] is a parsed ISO-8601 form for sort
 * stability and tooltip display.
 */
export interface HistoryEntry {
    filename: string;
    timestamp: string;
    iso: string;
}

/** Messages from extension to webview */
export type ExtensionToWebview =
    | {
          command: 'setPreviews';
          previews: PreviewInfo[];
          moduleDir: string;
          /**
           * IDs of previews whose heavy captures (LONG / GIF / animated)
           * were skipped this render — the on-disk PNG/GIF is from a
           * previous full run. Drives the "stale, click to refresh" badge.
           * Empty when the module was last rendered with `tier=full`.
           */
          heavyStaleIds?: string[];
      }
    /** `captureIndex` addresses which capture within an animated preview the
     *  image belongs to. Static previews have a single capture at index 0. */
    | { command: 'updateImage'; previewId: string; captureIndex: number; imageData: string }
    | { command: 'setImageError'; previewId: string; captureIndex: number; message: string }
    | { command: 'setLoading'; previewId?: string }
    | { command: 'markAllLoading' }
    | { command: 'setError'; previewId: string; message: string }
    | { command: 'showMessage'; text: string }
    | { command: 'clearAll' }
    | { command: 'setModules'; modules: string[]; selected: string }
    | { command: 'setFunctionFilter'; functionName: string }
    | { command: 'setHistory'; previewId: string; entries: HistoryEntry[] }
    | { command: 'updateHistoryImage'; previewId: string; filename: string; imageData: string };

/** Messages from webview to extension */
export type WebviewToExtension =
    | { command: 'openFile'; className: string; functionName: string }
    | { command: 'selectModule'; value: string }
    | { command: 'showHistory'; previewId: string }
    | { command: 'loadHistoryImage'; previewId: string; filename: string }
    /**
     * User clicked the stale-badge refresh icon on a heavy card. Triggers a
     * `tier='full'` render of the owning module so the heavy capture is
     * re-rendered. (A future per-preview filter would scope this to the
     * single previewId; today it falls back to a full-module render.)
     */
    | { command: 'refreshHeavy'; previewId: string };
