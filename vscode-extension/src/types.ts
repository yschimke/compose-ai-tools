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
    mode: "TOP" | "END" | "LONG" | string;
    axis: "VERTICAL" | "HORIZONTAL" | string;
    maxScrollPx: number;
    reduceMotion: boolean;
    /** Scrollable reached the end of its content before the renderer stopped.
     *  Distinct from `reachedPx === maxScrollPx`, which means the cap fired. */
    atEnd: boolean;
    /** Pixels actually scrolled. null when not reported. */
    reachedPx: number | null;
}

/**
 * Threshold above which an output's `cost` is considered "heavy" — dropped
 * from `composePreview.tier=fast` renders, surfaced in VS Code with a
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

export interface PreviewDataProduct {
    /** Data-product kind, for example `render/scroll/long`. */
    kind: string;
    extensionId?: string | null;
    effectId?: string | null;
    usageMode?: PreviewExtensionUsageMode | null;
    suggestedBy?: string | null;
    displayName?: string | null;
    facets?: PreviewDataProductFacet[];
    mediaTypes?: string[];
    sampling?: PreviewDataProductSampling | null;
    advanceTimeMillis: number | null;
    scroll: ScrollCapture | null;
    /** Module-relative product file path under `build/compose-previews`. */
    output: string;
    cost?: number;
}

export type PreviewDataProductFacet =
    | "STRUCTURED"
    | "ARTIFACT"
    | "IMAGE"
    | "ANIMATION"
    | "OVERLAY"
    | "CHECK"
    | "DIAGNOSTIC"
    | "PROFILE"
    | "INTERACTIVE";

export type PreviewDataProductSampling =
    | "START"
    | "END"
    | "EACH_FRAME"
    | "ON_DEMAND"
    | "AGGREGATE"
    | "FAILURE";

export type PreviewExtensionUsageMode =
    | "EXPLICIT_EFFECT"
    | "SUGGESTED_EXTRA_PREVIEW";

export interface PreviewInfo {
    id: string;
    functionName: string;
    className: string;
    sourceFile: string | null;
    params: PreviewParams;
    /** Rendered snapshots — always at least one. Length > 1 ⇔ carousel. */
    captures: Capture[];
    /** Annotation-sourced data products, such as long and scrolling screenshots. */
    dataProducts?: PreviewDataProduct[];
    /**
     * Populated by the extension from the sidecar accessibility.json referenced
     * in [PreviewManifest.accessibilityReport]. `null`/`undefined` means
     * accessibility checks were disabled for this module; an empty array means
     * checks ran and found nothing.
     */
    a11yFindings?: AccessibilityFinding[] | null;
    /** Absolute path to the annotated screenshot (clean PNG + overlay legend), when findings exist. */
    a11yAnnotatedPath?: string | null;
    /**
     * D2 — accessibility-relevant nodes from the rendered tree (label, role,
     * states, bounds). Daemon-attached via the `a11y/hierarchy` data product;
     * VS Code draws a local overlay from this rather than reading the
     * baked-into-PNG annotated screenshot. See
     * `docs/daemon/DATA-PRODUCTS.md` § "Worked example".
     */
    a11yNodes?: AccessibilityNode[] | null;
}

/**
 * Per-node entry in the `a11y/hierarchy` data product. Mirrors the renderer-side
 * `AccessibilityNode` (in `renderer-android/.../RenderManifest.kt`).
 */
export interface AccessibilityNode {
    label: string;
    role?: string | null;
    states: string[];
    merged: boolean;
    /** `left,top,right,bottom` in source-bitmap pixels. */
    boundsInScreen: string;
}

export interface PreviewManifest {
    module: string;
    variant: string;
    previews: PreviewInfo[];
    /** Relative path (from `previews.json`) to the sidecar a11y report, or null. */
    accessibilityReport?: string | null;
}

export interface AccessibilityFinding {
    level: "ERROR" | "WARNING" | "INFO" | string;
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

/**
 * Per-preview render-error sidecar produced by the renderer when a
 * `@Preview` function throws at render time. Mirrors
 * `gradle-plugin/.../PreviewRenderError.kt`. Schema is versioned so the
 * extension can ignore files written by an incompatible plugin version
 * — only `compose-preview-error/v1` is currently understood.
 *
 * Lives next to the would-be PNG with `.error.json` appended:
 *   `<module>/build/compose-previews/renders/Foo.png.error.json`
 */
export interface PreviewRenderError {
    /** Stable schema tag, currently `compose-preview-error/v1`. */
    schema: string;
    /** FQN of the throwable, e.g. `java.lang.NullPointerException`. */
    exception: string;
    /** The throwable's message. Empty string when no message was supplied. */
    message: string;
    /**
     * First stack frame the renderer attributes to user code (skipping
     * `androidx.compose.*`, `kotlin.*`, `java.*`, and the renderer scaffold).
     * Surfaced on the failing card as a "go to source" link. `null` when
     * the heuristic finds no app frame (deep framework throw).
     */
    topAppFrame?: PreviewRenderErrorTopFrame | null;
    /** Full stack trace as it would appear in `Throwable.printStackTrace()`. */
    stackTrace: string;
}

export interface PreviewRenderErrorTopFrame {
    /** Source-file basename, e.g. `Previews.kt`. */
    file: string;
    /** 1-based line number, or 0 when the frame doesn't carry one. */
    line: number;
    /** Function / method name from the stack frame. */
    function: string;
}

// -------------------------------------------------------------------------
// Android XML resource previews — mirrors of the Kotlin types in
// `gradle-plugin/.../PreviewData.kt` / `renderer-android/.../RenderResourceManifest.kt`.
// -------------------------------------------------------------------------

export type ResourceType =
    | "VECTOR"
    | "ANIMATED_VECTOR"
    | "ADAPTIVE_ICON"
    | string;

export type AdaptiveShape =
    | "CIRCLE"
    | "SQUIRCLE"
    | "ROUNDED_SQUARE"
    | "SQUARE"
    | string;

export type AdaptiveStyle =
    | "FULL_COLOR"
    | "THEMED_LIGHT"
    | "THEMED_DARK"
    | "LEGACY"
    | string;

export interface ResourceVariant {
    /**
     * Resource qualifier suffix as written in the AAPT directory name, sans the leading dash —
     * e.g. `'xhdpi'`, `'night-xhdpi'`, `'ldrtl-xhdpi-v26'`. `null` for the default-qualifier
     * configuration. This is the runtime configuration the capture was rendered under, not the
     * qualifier of any particular source file (AAPT picks whichever matches at render time).
     */
    qualifiers: string | null;
    /**
     * Adaptive-icon shape mask. `null` for non-adaptive resources, and `null` for `LEGACY`
     * captures (the legacy fallback ignores the system mask).
     */
    shape: AdaptiveShape | null;
    /**
     * Adaptive-icon style. `'FULL_COLOR'` is the foreground+background composite (App Search
     * appearance); `'THEMED_LIGHT'` / `'THEMED_DARK'` tint the `<monochrome>` layer with a
     * 2-tone Material 3 palette (home-screen "Themed icons" appearance); `'LEGACY'` is the
     * pre-O fallback. `null` on resources where the field wasn't populated by an older
     * plugin version — treat as `'FULL_COLOR'`.
     */
    style?: AdaptiveStyle | null;
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
    severity: "error" | "warning" | "info" | string;
    message: string;
    detail?: string | null;
    remediationSummary?: string | null;
    remediationCommands?: string[];
    docsUrl?: string | null;
}

/** Messages from extension to webview */
export type ExtensionToWebview =
    | {
          command: "setPreviews";
          previews: PreviewInfo[];
          moduleDir: string;
          /**
           * IDs of previews whose heavy outputs (animated captures, LONG / GIF data products)
           * were skipped this render — the on-disk PNG/GIF is from a
           * previous full run. Drives the "stale, click to refresh" badge.
           * Empty when the module was last rendered with `tier=full`.
           */
          heavyStaleIds?: string[];
      }
    /** `captureIndex` addresses which capture within an animated preview the
     *  image belongs to. Static previews have a single capture at index 0. */
    | {
          command: "updateImage";
          previewId: string;
          captureIndex: number;
          imageData: string;
      }
    /**
     * D2 — daemon-attached `a11y/atf` (findings) and/or `a11y/hierarchy` (nodes) for one
     * preview. The webview updates its in-memory caches and re-applies the existing finding
     * overlay; the hierarchy overlay draws translucent bounds for every accessibility-relevant
     * node when present. Either field may be undefined to leave the current state untouched.
     */
    | {
          command: "updateA11y";
          previewId: string;
          findings?: AccessibilityFinding[] | null;
          nodes?: AccessibilityNode[] | null;
      }
    | {
          command: "setImageError";
          previewId: string;
          captureIndex: number;
          message: string;
          /**
           * Structured runtime-error detail surfaced from the renderer's
           * `.error.json` sidecar. When present the panel renders a richer
           * card affordance — exception class + message + a "go to source"
           * link tied to the top app frame — instead of the bare
           * [message] string. Null/missing means "no sidecar available";
           * the panel falls back to the generic message.
           */
          renderError?: PreviewRenderError | null;
          /**
           * True when this error should replace an already-rendered image.
           * Gradle render failures use this; daemon failures are async and
           * may race behind a later valid frame, so they preserve last-good
           * pixels when false.
           */
          replaceExisting?: boolean;
      }
    | { command: "setLoading"; previewId?: string }
    | { command: "markAllLoading" }
    | { command: "setError"; previewId: string; message: string }
    | { command: "showMessage"; text: string }
    | { command: "clearAll" }
    | { command: "setModules"; modules: string[]; selected: string }
    | { command: "setFunctionFilter"; functionName: string }
    /**
     * Drives the slim progress bar at the top of the panel. `percent` is
     * monotonic within a refresh and clamped to [0, 1]; `label` is the
     * user-facing phase name ("Compiling Kotlin", "Rendering previews"…).
     * `slow` is true when the current phase is overrunning its calibrated
     * estimate — the webview tints the bar and appends "(slow)" to the
     * label. The webview hides the bar on its own when `percent` reaches 1.
     */
    | {
          command: "setProgress";
          phase: string;
          label: string;
          percent: number;
          slow?: boolean;
      }
    /** Force-clear the progress bar (e.g. on cancellation or fatal error). */
    | { command: "clearProgress" }
    /**
     * Replace the compile-error banner. Each entry carries its own
     * absolute `path` so the click handler can deep-link to whatever
     * file the diagnostic actually came from — important for the
     * kotlinc detector where errors can span files. Cards stay rendered
     * but get a "compile-stale" decoration so the user keeps the last
     * successful render visible alongside the error list.
     */
    | {
          command: "setCompileErrors";
          errors: import("./compileErrors").CompileError[];
      }
    /** Remove the compile-error banner and the compile-stale dim on cards. */
    | { command: "clearCompileErrors" }
    /** Side-by-side diff result for a focused live preview. The webview
     *  swaps in an overlay over the focused image with two labelled images. */
    | {
          command: "previewDiffReady";
          previewId: string;
          against: "head" | "main";
          leftLabel: string;
          leftImage: string;
          rightLabel: string;
          rightImage: string;
      }
    | {
          command: "previewDiffError";
          previewId: string;
          against: "head" | "main";
          message: string;
      }
    /**
     * Programmatic open of a preview in focus mode + immediate diff
     * request. Used by the "Diff All vs Main" command so picking an item
     * from its quick-pick lands the user directly on the diff result.
     */
    | { command: "focusAndDiff"; previewId: string; against: "head" | "main" }
    /**
     * `origin/compose-preview/main` (or the local `compose-preview/main`
     * branch, the legacy `preview_main` flat refs, or `packed-refs`) just
     * changed on disk — typically because a `git fetch` landed a new
     * baseline. The live panel re-issues any open "Diff vs main" overlay
     * so the user sees the new bytes without manually clicking.
     */
    | { command: "previewMainRefChanged" }
    /**
     * Interactive (live-stream) mode availability for a given module.
     * Posted by the extension whenever the daemon for [moduleId] becomes
     * ready or unhealthy. The webview uses this to enable/disable the
     * focus-mode "LIVE" toggle for any focused preview owned by the
     * module. See docs/daemon/INTERACTIVE.md § 3 for the UI surface.
     * `interactiveSupported=false` means the daemon accepts the live-mode
     * request but cannot dispatch inputs into a held composition (Android/v1
     * fallback; see #422).
     */
    | {
          command: "setInteractiveAvailability";
          moduleId: string;
          ready: boolean;
          interactiveSupported?: boolean;
      }
    /**
     * Drop every active interactive (live-stream) UI state on the panel side.
     * Posted by the extension when the user moves focus away from the panel's
     * scope file (active editor change to a different file) — the daemon-side
     * streams are flushed in parallel via `interactive/stop`. The panel
     * clears the LIVE badge / .live class / `interactivePreviewIds` set
     * without sending its own `setInteractive` messages back (those would
     * race the extension's flush). See INTERACTIVE.md § 3.
     */
    | { command: "clearInteractive"; previewId?: string }
    | { command: "clearRecording"; previewId?: string }
    | { command: "setEarlyFeatures"; enabled: boolean };

/** Messages from webview to extension */
export type WebviewToExtension =
    /**
     * Webview signals it has loaded and is ready to receive state messages.
     * Sent once per webview lifecycle from `<preview-app>`'s `firstUpdated`,
     * after the message listener in `behavior.ts` is installed. The extension
     * responds by republishing the current session's stateful messages
     * (`setPreviews`, `setModules`, `setEarlyFeatures`, daemon availability,
     * etc.) so a panel that resolved after the initial refresh — e.g. user
     * activated the extension by opening a `.kt` file before clicking the
     * Compose Preview activity-bar icon — doesn't end up with an empty grid
     * because the only `setPreviews` was sent before the webview existed.
     * `view?.webview.postMessage` silently drops messages until the
     * webviewView is resolved.
     */
    | { command: "webviewReady" }
    /**
     * Webview reports it has finished applying a `setPreviews` payload to the
     * grid — sent from `behavior.ts` after `renderPreviews` and the filter-
     * toolbar/option update. `count` is the number of cards now in the grid.
     *
     * Drives the e2e test's "the host posted AND the webview consumed" check:
     * `postedMessageLog` only proves the host called `postMessage`, not that a
     * resolved webview ever received it. This signal closes that gap so the
     * full host→webview→DOM loop is regression-locked.
     */
    | { command: "webviewPreviewsRendered"; count: number }
    | { command: "openFile"; className: string; functionName: string }
    | { command: "selectModule"; value: string }
    /**
     * User clicked a faded heavy card. The extension opts that preview into
     * full-tier refreshes until focus moves to another source scope, and
     * triggers an immediate full-tier render for the selected preview.
     */
    | { command: "refreshHeavy"; previewId: string }
    /**
     * Webview reports current geometric visibility of preview cards plus
     * cards it predicts will scroll into view next based on scroll velocity
     * and direction. Consumed by the daemon scheduler — see
     * `docs/daemon/PREDICTIVE.md` § 7 (v1.1 scroll-ahead). Ignored when the
     * daemon path is disabled (the extension simply doesn't use the data).
     *
     * Both fields are full snapshots, not deltas — daemon dedups against the
     * previous send. `predicted` excludes IDs already in `visible` because
     * those are reactively rendered by the visible queue.
     */
    | { command: "viewportUpdated"; visible: string[]; predicted: string[] }
    /**
     * Webview reports which preview the user has narrowed the live panel to.
     * Drives the History panel's `previewId` filter so it shows only entries
     * for the currently-focused (focus mode) or sole-visible (filter narrowed
     * to one card) preview. `previewId` is `null` when the panel shows the
     * full module view — extension clears the previewId filter on the
     * History panel scope.
     */
    | { command: "previewScopeChanged"; previewId: string | null }
    /**
     * Click on a compile-error banner row. Extension responds by opening the
     * source file and revealing the position. `sourceFile` is the same
     * absolute path the extension passed in `setCompileErrors`; the line /
     * column come from the LSP diagnostic (1-based).
     */
    | {
          command: "openCompileError";
          sourceFile: string;
          line: number;
          column: number;
      }
    /**
     * Click on a runtime-error card's "top app frame" link. Unlike the
     * compile-error path, runtime errors only carry the source-file
     * basename (from the stack trace's `StackTraceElement.fileName`) —
     * so the extension does a workspace `findFiles('**\/<fileName>')`
     * to resolve it, biased to the first match outside `**\/build/**`.
     *
     * Optional `className` is the FQN of the preview's compiled class
     * (e.g. `com.example.app.PreviewsKt`) which the extension uses to
     * disambiguate workspaces with same-named files across modules. If
     * the basename of the class-derived path matches `fileName` we look
     * for that exact path first; on no hit (or no `className`) we fall
     * back to the basename glob.
     */
    | {
          command: "openSourceFile";
          fileName: string;
          line: number;
          className?: string;
      }
    /**
     * Live panel asks the extension to compute a diff for the focused
     * preview against an anchor. `head` = latest archived render in
     * `.compose-preview-history/` for this preview; `main` = same, filtered
     * to entries whose `git.branch` is `main`.
     */
    | {
          command: "requestPreviewDiff";
          previewId: string;
          against: "head" | "main";
      }
    /**
     * Click on the "Launch on Device" button in the focus-mode toolbar.
     * The extension uses [previewId] to bias module selection (the owner
     * of the focused preview), then runs the consumer module's
     * `installDebug` task and starts its launcher activity via `adb`.
     * Falls back to a quick-pick when more than one Android-application
     * module applies the plugin.
     */
    | { command: "requestLaunchOnDevice"; previewId: string }
    /**
     * Toggle interactive (live-stream) mode for [previewId]. Daemon-only —
     * the extension routes this into a `setFocus` + `renderNow(tier='fast')`
     * call so the focused preview is the daemon's render priority. Exit
     * (`enabled = false`) does not issue any daemon call: the next
     * save/focus-change publishes a fresh focus set on its own. See
     * docs/daemon/INTERACTIVE.md § 4 for the lifecycle.
     */
    | { command: "setInteractive"; previewId: string; enabled: boolean }
    | {
          command: "setRecording";
          previewId: string;
          enabled: boolean;
          format?: "apng" | "mp4";
      }
    /**
     * Pointer/rotary input on the focused image while interactive mode is on.
     * Coordinates are in IMAGE-NATURAL pixel space — the same coordinate
     * system the renderer thinks in. See docs/daemon/INTERACTIVE.md § 6/§ 7.
     */
    | {
          command: "recordInteractiveInput";
          previewId: string;
          kind:
              | "click"
              | "pointerDown"
              | "pointerMove"
              | "pointerUp"
              | "rotaryScroll";
          pixelX: number;
          pixelY: number;
          imageWidth: number;
          imageHeight: number;
          scrollDeltaY?: number;
      }
    /**
     * D2 — focus-mode toggle for the local a11y overlay. When `enabled`, the
     * extension `data/subscribe`s to `a11y/atf` + `a11y/hierarchy` for
     * [previewId]; subsequent renders attach the payload and the panel paints
     * the overlay locally. When `enabled = false`, the extension unsubscribes
     * and tells the webview to drop the cached nodes/findings. Idempotent on
     * both sides.
     */
    | { command: "setA11yOverlay"; previewId: string; enabled: boolean };
