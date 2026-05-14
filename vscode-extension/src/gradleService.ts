import * as path from "path";
import * as fs from "fs";
import {
    AccessibilityFinding,
    AccessibilityReport,
    Capture,
    DoctorModuleReport,
    PreviewManifest,
    PreviewRenderError,
    ResourceManifest,
    manifestReportsView,
} from "./types";
import {
    appliesInjectableHostPlugin,
    appliesPlugin,
    BUILD_SCRIPT_NAMES,
} from "./pluginDetection";
import { JdkImageError, JdkImageErrorDetector } from "./jdkImageErrorDetector";
import {
    ClassVersionError,
    ClassVersionErrorDetector,
} from "./classVersionErrorDetector";
import {
    KotlinCompileError,
    KotlinCompileErrorDetector,
} from "./kotlinCompileErrorDetector";
import { LogFilter } from "./logFilter";

/**
 * Expands a parameterized preview's single template capture into N captures
 * pointing at the actual `<stem>_<suffix>.<ext>` files on disk. The suffix
 * is either a human-readable label derived from the provider value (`_on`,
 * `_off`), or a numeric `_PARAM_<idx>` when no label could be derived.
 * Returns the original list unchanged when no matching files exist (rare —
 * the plugin's `renderAllPreviews` check would have already failed loudly),
 * or when the template has no `renderOutput` to key off.
 *
 * Numeric `_PARAM_<idx>` entries sort before label-based entries and among
 * themselves by index (so `PARAM_10` lands after `PARAM_2`, not before).
 * Labels sort alphabetically — provider order isn't recoverable from the
 * filename alone, but alphabetical is stable and readable.
 */
function expandParamCaptures(
    rendersDir: string,
    templates: Capture[],
    siblingRenderOutputs: Set<string>,
): Capture[] {
    if (!fs.existsSync(rendersDir)) {
        return templates;
    }
    const expanded: Capture[] = [];
    for (const template of templates) {
        if (!template.renderOutput) {
            expanded.push(template);
            continue;
        }
        const base = path.basename(template.renderOutput);
        const dot = base.lastIndexOf(".");
        const stem = dot > 0 ? base.slice(0, dot) : base;
        const ext = dot > 0 ? base.slice(dot) : "";
        const prefix = stem + "_";
        const templateDir = path.dirname(template.renderOutput);
        const dirPrefix =
            templateDir && templateDir !== "." ? `${templateDir}/` : "";
        const matches = fs
            .readdirSync(rendersDir)
            .filter(
                (name) =>
                    name.startsWith(prefix) &&
                    name.endsWith(ext) &&
                    !siblingRenderOutputs.has(dirPrefix + name),
            )
            .map((name) => {
                const suffix = name.slice(
                    prefix.length,
                    name.length - ext.length,
                );
                const paramIdxStr = suffix.startsWith("PARAM_")
                    ? suffix.slice("PARAM_".length)
                    : null;
                const paramIdx =
                    paramIdxStr !== null ? parseInt(paramIdxStr, 10) : NaN;
                return {
                    name,
                    suffix,
                    paramIdx: Number.isNaN(paramIdx) ? null : paramIdx,
                };
            })
            .sort((a, b) => {
                if (a.paramIdx !== null && b.paramIdx !== null) {
                    return a.paramIdx - b.paramIdx;
                }
                if (a.paramIdx !== null) {
                    return -1;
                }
                if (b.paramIdx !== null) {
                    return 1;
                }
                return a.suffix.localeCompare(b.suffix);
            });
        if (matches.length === 0) {
            continue;
        }
        for (const match of matches) {
            expanded.push({
                advanceTimeMillis: template.advanceTimeMillis,
                scroll: template.scroll,
                renderOutput: dirPrefix + match.name,
            });
        }
    }
    return expanded;
}

const TASK_TIMEOUT_MS = 5 * 60 * 1000;
const MANIFEST_CACHE_TTL_MS = 30_000;

/**
 * Distinguishes cancellation (a normal lifecycle event when a new refresh
 * supersedes an in-flight one) from real build failures, so callers can
 * silently drop cancellation without surfacing a misleading "FAILED" toast.
 *
 * vscode-gradle's gRPC layer rejects with a `Status.CANCELLED` error whose
 * `.message` looks like `"1 CANCELLED: Call cancelled"` — match on that,
 * not on a less stable substring.
 */
export class TaskCancelledError extends Error {
    constructor(public readonly task: string) {
        super(`Gradle task ${task} was cancelled.`);
        this.name = "TaskCancelledError";
    }
}

const CANCELLED_RE = /\bCANCELLED\b/i;

/** `cancellationKey` is `compose-preview-${counter}|${task}`; extract the task. */
function taskFromKey(key: string): string {
    const sep = key.indexOf("|");
    return sep < 0 ? key : key.slice(sep + 1);
}

/**
 * Identifies a Gradle module the extension knows about.
 *
 * `projectDir` and `modulePath` are usually trivially related (`a/b` ↔
 * `:a:b`), but they can diverge when `settings.gradle.kts` reassigns a
 * project's `projectDir` (the Android-X mini-checkout layout, for example).
 * Keeping both lets us locate the build outputs on disk via `projectDir`
 * while still naming the right Gradle task via `modulePath`.
 */
export interface ModuleInfo {
    /** Workspace-relative filesystem path, forward-slash separated, no
     *  leading slash (e.g. `samples/wear`). Used to build
     *  `path.join(workspaceRoot, projectDir, "build", ...)`. */
    readonly projectDir: string;
    /** Canonical Gradle project path, beginning with ':' (e.g.
     *  `:samples:wear`). Used as the prefix for every gradle task name and
     *  as the cache / lookup key. */
    readonly modulePath: string;
}

/** Synthesises a Gradle project path from a workspace-relative directory.
 *  Used as a fallback when no `applied.json` has been written yet — the
 *  marker, once present, supplies the authoritative `modulePath`. */
function modulePathFromProjectDir(projectDir: string): string {
    return ":" + projectDir.split("/").join(":");
}

const APPLIED_MARKER_SCHEMA = "compose-preview-applied/v1";

interface AppliedMarker {
    readonly schema: string;
    readonly modulePath: string;
}

/**
 * Reads a `build/compose-previews/applied.json` marker and returns the
 * canonical `modulePath` recorded there, or `null` if the file is missing,
 * malformed, or has an unrecognised schema. Callers fall back to the
 * projectDir-derived path on `null`.
 */
function readAppliedMarker(file: string): AppliedMarker | null {
    let raw: string;
    try {
        raw = fs.readFileSync(file, "utf-8");
    } catch {
        return null;
    }
    try {
        const parsed = JSON.parse(raw) as Partial<AppliedMarker>;
        if (
            parsed.schema === APPLIED_MARKER_SCHEMA &&
            typeof parsed.modulePath === "string" &&
            parsed.modulePath.startsWith(":")
        ) {
            return { schema: parsed.schema, modulePath: parsed.modulePath };
        }
    } catch {
        /* fall through */
    }
    return null;
}

const SCAN_SKIP_DIRS = new Set([
    "node_modules",
    "build",
    "gradle",
    "src",
    "out",
    "dist",
    ".compose-preview-history",
]);
/**
 * Defensive cap on directory-walk depth. The walk is already pruned by
 * [SCAN_SKIP_DIRS] (skips `build`, `src`, etc.), so legitimate Gradle
 * layouts can't push past this — the cap exists only to bound the worst
 * case if a workspace has runaway symlink loops.
 */
const SCAN_MAX_DEPTH = 12;

export interface Logger {
    appendLine(value: string): void;
    append(value: string): void;
}

const nullLogger: Logger = { appendLine() {}, append() {} };

/**
 * Per-call hooks for [GradleService.runTask] and friends.
 *
 * Tracker ownership lives outside this module — the caller (refresh())
 * constructs a [BuildProgressTracker] whose lifecycle spans the whole
 * refresh, including the post-Gradle image-loading phase, and feeds it
 * Gradle's stdout via [onTaskOutput]. Keeping the tracker here would
 * orphan its tick interval after `runTask` resolved, leaving the bar
 * stuck at the last rendering-phase percent until the next refresh.
 */
export interface TaskOptions {
    /**
     * Receives every decoded chunk of stdout/stderr from the running task.
     * Called after the gradleService's internal detectors and log filter
     * have processed the chunk, so the caller never gets bytes that have
     * already triggered a JdkImageError or KotlinCompileError.
     */
    onTaskOutput?: (chunk: string) => void;
}

/**
 * Subset of vscjava.vscode-gradle's exported API.
 * See: https://github.com/microsoft/vscode-gradle/blob/main/extension/src/api/Api.ts
 */
export interface GradleApi {
    runTask(opts: {
        projectFolder: string;
        taskName: string;
        args?: ReadonlyArray<string>;
        showOutputColors: boolean;
        onOutput?: (output: {
            getOutputBytes(): Uint8Array;
            getOutputType(): number;
        }) => void;
        cancellationKey?: string;
    }): Promise<void>;
    cancelRunTask(opts: {
        projectFolder: string;
        taskName: string;
        cancellationKey?: string;
    }): Promise<void>;
}

export class GradleService {
    /** Absolute path to the workspace root. Read-only — exposed for callers that need to resolve
     *  module-relative artifact paths (e.g. the Android manifest CodeLens, which jumps to a
     *  rendered PNG given a `<module>/build/compose-previews/...` relative path). */
    public readonly workspaceRoot: string;
    private logger: Logger;
    private gradleApi: GradleApi;
    private argsProvider: () => string[];
    private logFilter: LogFilter;
    /** Keyed by `ModuleInfo.modulePath` — the canonical, stable identifier. */
    private manifestCache = new Map<
        string,
        { manifest: PreviewManifest; timestamp: number }
    >();
    private taskCounter = 0;
    private activeKeys = new Set<string>();

    constructor(
        workspaceRoot: string,
        gradleApi: GradleApi,
        logger?: Logger,
        argsProvider?: () => string[],
        logFilter?: LogFilter,
    ) {
        this.workspaceRoot = workspaceRoot;
        this.gradleApi = gradleApi;
        this.logger = logger ?? nullLogger;
        this.argsProvider = argsProvider ?? (() => []);
        this.logFilter = logFilter ?? new LogFilter();
    }

    async discoverPreviews(
        module: ModuleInfo,
        opts?: TaskOptions,
    ): Promise<PreviewManifest | null> {
        const cached = this.manifestCache.get(module.modulePath);
        if (cached && Date.now() - cached.timestamp < MANIFEST_CACHE_TTL_MS) {
            return cached.manifest;
        }
        await this.runTask(`${module.modulePath}:discoverPreviews`, [], opts);
        const manifest = this.readManifest(module);
        if (manifest) {
            this.manifestCache.set(module.modulePath, {
                manifest,
                timestamp: Date.now(),
            });
        }
        return manifest;
    }

    /**
     * Runs the upstream Kotlin compile only — same task `discoverPreviews` depends on, minus the
     * ClassGraph scan over every dependency JAR. The daemon save loop calls this so that on-disk
     * `.class` files are fresh before we send `fileChanged` to the daemon, without paying for a
     * full preview-manifest reconcile on every keystroke. The metadata reconcile is handled
     * silently by the daemon's `discoveryUpdated` notification when (and only when) the diff is
     * non-empty.
     *
     * Mirrors the cache invalidation behaviour of {@link discoverPreviews}: we drop the cached
     * manifest because the user-visible source-of-truth is now the daemon's in-memory index, not
     * the on-disk `previews.json`. A subsequent gradle-mode save (daemon disabled or unhealthy)
     * will repopulate the cache through `discoverPreviews`.
     */
    async compileOnly(module: ModuleInfo, opts?: TaskOptions): Promise<void> {
        this.manifestCache.delete(module.modulePath);
        await this.runTask(
            `${module.modulePath}:composePreviewCompile`,
            [],
            opts,
        );
    }

    /**
     * Runs `:<module>:renderAllPreviews` and returns the parsed manifest.
     *
     * `tier` controls which captures the renderer produces:
     *
     *   - `'fast'` — append `-PcomposePreview.tier=fast`; the plugin's
     *     `RobolectricRenderTest` and `RenderPreviewsTask` skip captures
     *     whose `cost` exceeds `HEAVY_COST_THRESHOLD` (LONG / GIF /
     *     animated). Cheap interactive loop on every save.
     *   - `'full'` — explicit `-PcomposePreview.tier=full`; renders
     *     everything. The user-triggered "Render All Previews" path.
     *
     * Same task name in either case so Gradle's up-to-date check still
     * applies — the tier is an `@Input` on the underlying task, so flipping
     * tier between calls correctly invalidates the up-to-date cache without
     * burning a config-cache reconfigure (see `TierSystemPropProvider`).
     */
    async renderPreviews(
        module: ModuleInfo,
        tier: "fast" | "full" = "full",
        opts?: TaskOptions,
    ): Promise<PreviewManifest | null> {
        this.manifestCache.delete(module.modulePath);
        await this.runTask(
            `${module.modulePath}:renderAllPreviews`,
            [`-PcomposePreview.tier=${tier}`],
            opts,
        );
        const manifest = this.readManifest(module);
        if (manifest) {
            this.manifestCache.set(module.modulePath, {
                manifest,
                timestamp: Date.now(),
            });
        }
        return manifest;
    }

    /**
     * Runs `:<module>:installDebug` to build and install the consumer's
     * `com.android.application` APK on the connected device. Used by the
     * "Launch on Device" panel button — `adb shell am start` follows
     * separately. Throws on failure (e.g. no device, build error) so the
     * caller can surface the message to the user.
     */
    async installDebug(module: ModuleInfo, opts?: TaskOptions): Promise<void> {
        await this.runTask(`${module.modulePath}:installDebug`, [], opts);
    }

    /**
     * Runs `:<module>:composePreviewDoctor` and returns the parsed sidecar
     * report. Same JSON schema as `compose-preview doctor --json`'s per-
     * module shape — see `ComposePreviewDoctorTask.kt` in `gradle-plugin`.
     *
     * Returns `null` when the task is missing (plugin not applied or
     * version predates the feature), the build fails, or the JSON file
     * wasn't produced. Callers should treat null as "skip doctor
     * diagnostics for this module", not as an empty finding set.
     */
    async runDoctor(module: ModuleInfo): Promise<DoctorModuleReport | null> {
        const task = `${module.modulePath}:composePreviewDoctor`;
        try {
            await this.runTask(task);
        } catch (e) {
            this.logger.appendLine(
                `[doctor] ${task} failed: ${(e as Error).message}`,
            );
            return null;
        }
        const reportPath = path.join(
            this.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
            "doctor.json",
        );
        if (!fs.existsSync(reportPath)) {
            this.logger.appendLine(`[doctor] ${reportPath} not produced`);
            return null;
        }
        try {
            const parsed = JSON.parse(
                fs.readFileSync(reportPath, "utf-8"),
            ) as DoctorModuleReport;
            if (!parsed.schema?.startsWith("compose-preview-doctor/")) {
                this.logger.appendLine(
                    `[doctor] unexpected schema in ${reportPath}: ${parsed.schema}`,
                );
                return null;
            }
            return parsed;
        } catch (e) {
            this.logger.appendLine(
                `[doctor] parse failed for ${reportPath}: ${(e as Error).message}`,
            );
            return null;
        }
    }

    invalidateCache(module?: ModuleInfo): void {
        if (module) {
            this.manifestCache.delete(module.modulePath);
        } else {
            this.manifestCache.clear();
        }
    }

    /**
     * Loads `<module>/build/compose-previews/resources.json` — the sidecar manifest written by
     * `:<module>:discoverAndroidResources`. Returns `null` if the file doesn't exist (consumers
     * who applied the plugin but disabled `composePreview.resourcePreviews` will hit this path)
     * or if its shape is malformed.
     *
     * Unlike [readManifest], this does no enrichment — resource captures don't have a
     * fan-out-after-discovery story (no `@PreviewParameter` equivalent), and there's no a11y
     * sidecar to merge in. Downstream consumers (CodeLens, the upcoming resource webview tab)
     * use the raw shape directly.
     */
    readResourceManifest(module: ModuleInfo): ResourceManifest | null {
        const manifestPath = path.join(
            this.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
            "resources.json",
        );
        if (!fs.existsSync(manifestPath)) {
            return null;
        }
        try {
            const manifest = JSON.parse(
                fs.readFileSync(manifestPath, "utf-8"),
            ) as ResourceManifest;
            if (
                !Array.isArray(manifest.resources) ||
                !Array.isArray(manifest.manifestReferences)
            ) {
                this.logger.appendLine(
                    `Malformed resource manifest at ${manifestPath}`,
                );
                return null;
            }
            return manifest;
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            this.logger.appendLine(
                `Failed to parse ${manifestPath}: ${message}`,
            );
            return null;
        }
    }

    readManifest(module: ModuleInfo): PreviewManifest | null {
        const manifestPath = path.join(
            this.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
            "previews.json",
        );
        if (!fs.existsSync(manifestPath)) {
            return null;
        }
        try {
            const manifest = JSON.parse(
                fs.readFileSync(manifestPath, "utf-8"),
            ) as PreviewManifest;
            if (!manifest.previews || !Array.isArray(manifest.previews)) {
                this.logger.appendLine(`Malformed manifest at ${manifestPath}`);
                return null;
            }
            // `@PreviewParameter` previews ship a single template capture on
            // the manifest (`renders/<id>.<ext>`). The renderer fans out to
            // `renders/<id>_PARAM_<idx>.<ext>` on disk — one file per
            // provider value. Substitute the template with the actual files
            // before any downstream consumer (carousel, hover, image loader)
            // sees the preview, so the UI walks N files instead of trying to
            // read the template path (which never exists).
            const rendersDir = path.join(
                this.workspaceRoot,
                module.projectDir,
                "build",
                "compose-previews",
                "renders",
            );
            const siblingRenderOutputs = new Set<string>();
            for (const p of manifest.previews) {
                if (!p.params?.previewParameterProviderClassName) {
                    for (const c of p.captures) {
                        if (c.renderOutput) {
                            siblingRenderOutputs.add(c.renderOutput);
                        }
                    }
                }
            }
            for (const p of manifest.previews) {
                if (p.params?.previewParameterProviderClassName) {
                    p.captures = expandParamCaptures(
                        rendersDir,
                        p.captures,
                        siblingRenderOutputs,
                    );
                }
            }
            // Enrich each preview with a11y findings when the module has the sidecar report.
            // Read through `manifestReportsView` so v1 plugins (only `accessibilityReport`
            // populated) and v2 plugins (the `dataExtensionReports` map) both surface uniformly
            // — the unified view returns `{}` when neither is set, and the loop naturally
            // leaves `a11yFindings = null` on every preview.
            const reports = manifestReportsView(manifest);
            const a11yPointer = reports["a11y"] ?? null;
            if (a11yPointer) {
                const byId = this.readA11yById(module, a11yPointer);
                for (const p of manifest.previews) {
                    const entry = byId[p.id];
                    p.a11yFindings = entry?.findings ?? [];
                    p.a11yAnnotatedPath = entry?.annotatedPath ?? null;
                }
            } else {
                for (const p of manifest.previews) {
                    p.a11yFindings = null;
                    p.a11yAnnotatedPath = null;
                }
            }
            return manifest;
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            this.logger.appendLine(
                `Failed to parse ${manifestPath}: ${message}`,
            );
            return null;
        }
    }

    /**
     * Loads the sidecar accessibility report for a module and returns a lookup
     * by previewId. `annotatedPath` is resolved against the report directory
     * so the caller gets an absolute path to the annotated PNG (or null when
     * the overlay wasn't generated — e.g. the preview had no findings).
     */
    private readA11yById(
        module: ModuleInfo,
        relativePath: string,
    ): Record<
        string,
        { findings: AccessibilityFinding[]; annotatedPath: string | null }
    > {
        const reportPath = path.join(
            this.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
            relativePath,
        );
        if (!fs.existsSync(reportPath)) {
            return {};
        }
        try {
            const report = JSON.parse(
                fs.readFileSync(reportPath, "utf-8"),
            ) as AccessibilityReport;
            const reportDir = path.dirname(reportPath);
            const out: Record<
                string,
                {
                    findings: AccessibilityFinding[];
                    annotatedPath: string | null;
                }
            > = {};
            for (const entry of report.entries ?? []) {
                const resolved = entry.annotatedPath
                    ? path.resolve(reportDir, entry.annotatedPath)
                    : null;
                out[entry.previewId] = {
                    findings: entry.findings ?? [],
                    annotatedPath:
                        resolved && fs.existsSync(resolved) ? resolved : null,
                };
            }
            return out;
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            this.logger.appendLine(`Failed to parse ${reportPath}: ${message}`);
            return {};
        }
    }

    async readPreviewImage(
        module: ModuleInfo,
        renderOutput: string,
    ): Promise<string | null> {
        const pngPath = path.join(
            this.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
            renderOutput,
        );
        try {
            const data = await fs.promises.readFile(pngPath);
            return data.toString("base64");
        } catch {
            return null;
        }
    }

    /**
     * Read the per-preview runtime-error sidecar — written by the
     * renderer next to where the PNG would have gone, with `.error.json`
     * appended. Returns `null` when the file is absent (preview rendered
     * fine, or the renderer doesn't yet support the sidecar) or when the
     * JSON is malformed / has an unknown schema version.
     *
     * Sibling placement keeps the renderer's filesystem layout self-
     * contained — no aggregation step in the gradle plugin — and the
     * extension finds the sidecar by trivial string-concat on the
     * manifest's existing `renderOutput` path.
     */
    async readPreviewRenderError(
        module: ModuleInfo,
        renderOutput: string,
    ): Promise<PreviewRenderError | null> {
        const sidecarPath = path.join(
            this.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
            `${renderOutput}.error.json`,
        );
        try {
            const text = await fs.promises.readFile(sidecarPath, "utf-8");
            const parsed = JSON.parse(text) as PreviewRenderError;
            if (!parsed.schema?.startsWith("compose-preview-error/")) {
                this.logger.appendLine(
                    `[render-error] unexpected schema in ${sidecarPath}: ${parsed.schema}`,
                );
                return null;
            }
            return parsed;
        } catch (e: unknown) {
            // ENOENT is the common case (no error sidecar) — silent.
            // Anything else is logged so a malformed file is debuggable
            // without surprising the user with a generic banner.
            const err = e as NodeJS.ErrnoException;
            if (err && err.code !== "ENOENT") {
                this.logger.appendLine(
                    `[render-error] failed to read ${sidecarPath}: ${err.message ?? err}`,
                );
            }
            return null;
        }
    }

    async cancel(): Promise<void> {
        // `composePreviewDaemonStart` is intentionally excluded from the cancel
        // pool. It's a one-shot, cheap, idempotent task that writes the launch
        // descriptor every subsequent refresh needs; cancelling it mid-flight
        // when the next refresh fires creates a permanent cycle where bootstrap
        // is killed by the refresh it was supposed to enable, leaving
        // "no launch descriptor" / "Build cancelled" forever.
        //
        // Tradeoff: a refresh that arrives while the first-time bootstrap is
        // still running now queues behind it on the Gradle daemon (the daemon
        // serialises clients) instead of jumping the queue. That can add a few
        // seconds on the very first refresh of a cold module, but only once
        // per session — the descriptor is then UP-TO-DATE and bootstrap
        // returns instantly. Steady-state is unaffected.
        const toCancel: string[] = [];
        const toKeep = new Set<string>();
        for (const key of this.activeKeys) {
            if (taskFromKey(key).endsWith(":composePreviewDaemonStart")) {
                toKeep.add(key);
            } else {
                toCancel.push(key);
            }
        }
        this.activeKeys = toKeep;
        for (const key of toCancel) {
            try {
                await this.gradleApi.cancelRunTask({
                    projectFolder: this.workspaceRoot,
                    taskName: taskFromKey(key),
                    cancellationKey: key,
                });
            } catch {
                /* ignore */
            }
        }
    }

    /**
     * Returns module identifiers — forward-slash relative paths from the
     * workspace root, e.g. `samples/wear` — for every Gradle subproject that
     * applies the Compose Preview plugin. Walks the directory tree up to
     * [SCAN_MAX_DEPTH] so nested modules (`:samples:wear`) are reachable, not
     * just direct children.
     *
     * Two signals are merged at every level:
     *
     *   1. `<module>/build/compose-previews/applied.json` — authoritative
     *      marker written by the `composePreviewApplied` Gradle task. Covers
     *      every apply mechanism (literal `id`, version-catalog alias,
     *      convention plugin, buildSrc) because Gradle itself wrote it.
     *   2. `<module>/build.gradle.kts` or `<module>/build.gradle` matching
     *      literal `id("ee.schimke.composeai.preview")` (Kotlin DSL) or
     *      `id 'ee.schimke.composeai.preview'` (Groovy DSL). Pre-Gradle-run
     *      fallback so trivially-configured workspaces aren't empty on
     *      first open.
     *
     * Returning the union means running Gradle on one module doesn't cause
     * others (applied but not yet built) to disappear from the list.
     * Projects that only apply via a catalog alias show up as empty until
     * the bootstrap marker run completes — see [bootstrapAppliedMarkers].
     *
     * Recursion continues past a matched directory because Gradle allows
     * modules to nest (`:foo:bar` inside `:foo`). [SCAN_SKIP_DIRS] prunes
     * source / output trees that can't contain a module root.
     *
     * The returned [ModuleInfo.modulePath] comes from `applied.json` when
     * present (so layouts that reassign `projectDir` in `settings.gradle.kts`
     * end up with the correct gradle task prefix), and is synthesised from
     * `projectDir` otherwise.
     */
    findPreviewModules(): ModuleInfo[] {
        const found = new Map<string, ModuleInfo>();
        const walk = (relDir: string, depth: number): void => {
            if (depth > SCAN_MAX_DEPTH) {
                return;
            }
            const absDir = relDir
                ? path.join(this.workspaceRoot, relDir)
                : this.workspaceRoot;
            let entries: fs.Dirent[];
            try {
                entries = fs.readdirSync(absDir, { withFileTypes: true });
            } catch {
                return;
            }
            for (const entry of entries) {
                if (entry.name.startsWith(".")) {
                    continue;
                }
                if (SCAN_SKIP_DIRS.has(entry.name)) {
                    continue;
                }
                if (!entry.isDirectory()) {
                    // `withFileTypes` reports the entry's own type, so a
                    // symlink-to-directory comes through as
                    // `isSymbolicLink: true, isDirectory: false`. Follow it
                    // via stat() — workspaces like the AndroidX-mini
                    // checkout symlink the upstream source tree under their
                    // workspace root.
                    if (!entry.isSymbolicLink()) {
                        continue;
                    }
                    try {
                        if (
                            !fs
                                .statSync(path.join(absDir, entry.name))
                                .isDirectory()
                        ) {
                            continue;
                        }
                    } catch {
                        continue;
                    }
                }
                const childRel = relDir
                    ? `${relDir}/${entry.name}`
                    : entry.name;
                const marker = path.join(
                    this.workspaceRoot,
                    childRel,
                    "build",
                    "compose-previews",
                    "applied.json",
                );
                let recorded = false;
                if (fs.existsSync(marker)) {
                    const parsed = readAppliedMarker(marker);
                    found.set(childRel, {
                        projectDir: childRel,
                        modulePath:
                            parsed?.modulePath ??
                            modulePathFromProjectDir(childRel),
                    });
                    recorded = true;
                }
                if (!recorded) {
                    for (const name of BUILD_SCRIPT_NAMES) {
                        const buildFile = path.join(
                            this.workspaceRoot,
                            childRel,
                            name,
                        );
                        try {
                            const content = fs.readFileSync(buildFile, "utf-8");
                            if (appliesPlugin(content)) {
                                found.set(childRel, {
                                    projectDir: childRel,
                                    modulePath:
                                        modulePathFromProjectDir(childRel),
                                });
                                break;
                            }
                        } catch {
                            /* try the next build-script name */
                        }
                    }
                }
                walk(childRel, depth + 1);
            }
        };
        walk("", 0);
        return [...found.values()].sort((a, b) =>
            a.projectDir.localeCompare(b.projectDir),
        );
    }

    /**
     * Synchronous workspace scan for any module that applies a plugin id the
     * bundled auto-inject init script can attach `ee.schimke.composeai.preview`
     * onto (Android / Compose Multiplatform — see [INJECTABLE_HOST_PLUGIN_IDS]).
     *
     * Used by the "auto" mode selector at activation: if the workspace already
     * has at least one such host, the init script will apply our preview
     * plugin to it and full mode is sensible. If nothing matches, the init
     * script would be a no-op and we default to minimal mode.
     *
     * Early-exits on the first match. Same scan budget / skip-list as
     * [findPreviewModules]. The version-catalog alias form
     * (`alias(libs.plugins.…)`) isn't matched — same limitation as the
     * literal-id scan in [findPreviewModules]; users on that path opt in
     * via the `composePreview.mode` setting.
     */
    hasInjectableHostModule(): boolean {
        let matched = false;
        const walk = (relDir: string, depth: number): void => {
            if (matched || depth > SCAN_MAX_DEPTH) {
                return;
            }
            const absDir = relDir
                ? path.join(this.workspaceRoot, relDir)
                : this.workspaceRoot;
            let entries: fs.Dirent[];
            try {
                entries = fs.readdirSync(absDir, { withFileTypes: true });
            } catch {
                return;
            }
            for (const name of BUILD_SCRIPT_NAMES) {
                const buildFile = path.join(absDir, name);
                try {
                    const content = fs.readFileSync(buildFile, "utf-8");
                    if (appliesInjectableHostPlugin(content)) {
                        matched = true;
                        return;
                    }
                } catch {
                    /* no build script here, try the next name */
                }
            }
            for (const entry of entries) {
                if (matched) {
                    return;
                }
                if (entry.name.startsWith(".")) {
                    continue;
                }
                if (SCAN_SKIP_DIRS.has(entry.name)) {
                    continue;
                }
                if (!entry.isDirectory() && !entry.isSymbolicLink()) {
                    continue;
                }
                walk(
                    relDir ? `${relDir}/${entry.name}` : entry.name,
                    depth + 1,
                );
            }
        };
        walk("", 0);
        return matched;
    }

    /**
     * Writes (or refreshes) `applied.json` markers across every applying
     * module by running `composePreviewApplied` without a project filter —
     * Gradle fans out to every project where the plugin registered the task.
     *
     * Cheap: the task writes ~100 bytes per module and is cacheable. Intended
     * to be called once on extension activation so subsequent
     * [findPreviewModules] calls can rely on the authoritative marker path
     * instead of the build-script scan.
     *
     * Swallows failures — the scan fallback still produces a sensible list,
     * and we don't want a misconfigured workspace to fail activation.
     */
    async bootstrapAppliedMarkers(
        onTypedError?: (err: ClassVersionError | JdkImageError) => void,
    ): Promise<void> {
        try {
            await this.runTask("composePreviewApplied");
        } catch (e) {
            this.logger.appendLine(
                `[applied] composePreviewApplied bootstrap failed: ${(e as Error).message}`,
            );
            // Bootstrap still swallows so the scan fallback can produce a
            // sensible module list — but if Gradle bailed for a JDK reason
            // that won't recover on its own, surface it to the caller. The
            // user might never trigger a render that would otherwise raise
            // it (e.g. activation-only sessions).
            if (
                onTypedError &&
                (e instanceof ClassVersionError || e instanceof JdkImageError)
            ) {
                onTypedError(e);
            }
        }
    }

    /**
     * Runs `:<module>:composePreviewDaemonStart` so the daemon launch
     * descriptor (`build/compose-previews/daemon-launch.json`) is up to date.
     * Cheap and cacheable — emits a small JSON. The caller (DaemonGate) handles errors.
     */
    async runDaemonBootstrap(module: ModuleInfo): Promise<void> {
        await this.runTask(`${module.modulePath}:composePreviewDaemonStart`);
    }

    /**
     * Looks up the discovered module whose canonical Gradle path is
     * [modulePath]. Returns null when the module isn't on the discovered
     * list — e.g. the workspace was reloaded and the marker hasn't been
     * re-read yet, or the modulePath is stale.
     */
    findModuleByPath(modulePath: string): ModuleInfo | null {
        for (const m of this.findPreviewModules()) {
            if (m.modulePath === modulePath) {
                return m;
            }
        }
        return null;
    }

    resolveModule(filePath: string): ModuleInfo | null {
        const relative = path.relative(this.workspaceRoot, filePath);
        if (
            !relative ||
            relative.startsWith("..") ||
            path.isAbsolute(relative)
        ) {
            return null;
        }
        // Split on either separator so the same path works on Windows and POSIX.
        const segments = relative
            .split(/[\\/]+/)
            .filter((s: string) => s.length > 0);
        if (segments.length < 2) {
            return null;
        }
        const byProjectDir = new Map<string, ModuleInfo>();
        for (const m of this.findPreviewModules()) {
            byProjectDir.set(m.projectDir, m);
        }
        // Longest-prefix match: walk from deepest possible module path to
        // shallowest. With nested modules (e.g. `:foo:bar` inside `:foo`)
        // we prefer the more specific match.
        for (let n = segments.length - 1; n >= 1; n--) {
            const candidate = segments.slice(0, n).join("/");
            const hit = byProjectDir.get(candidate);
            if (hit) {
                return hit;
            }
        }
        return null;
    }

    dispose(): void {
        this.cancel();
    }

    private runTask(
        task: string,
        extraArgs: ReadonlyArray<string> = [],
        opts: TaskOptions = {},
    ): Promise<void> {
        const cancellationKey = `compose-preview-${++this.taskCounter}|${task}`;
        this.activeKeys.add(cancellationKey);
        const startLine = `> ${task}`;
        if (this.logFilter.shouldEmitInformational(startLine)) {
            this.logger.appendLine(startLine);
        }

        const detector = new JdkImageErrorDetector();
        // Catches the case where the JVM running Gradle is older than the
        // JDK that compiled build-logic / the plugin — `UnsupportedClassVersionError`
        // surfaces as an opaque "Could not execute build" without this.
        const classVersionDetector = new ClassVersionErrorDetector();
        // Parse `e:` lines from the Kotlin compiler so the panel banner
        // can show structured errors when a build fails. Catches the
        // cross-file gap that the LSP-driven gate (compileErrors.ts)
        // misses by design — that gate only inspects the active file.
        const kotlinDetector = new KotlinCompileErrorDetector();

        const timeoutPromise = new Promise<never>((_, reject) => {
            setTimeout(() => {
                this.gradleApi
                    .cancelRunTask({
                        projectFolder: this.workspaceRoot,
                        taskName: task,
                        cancellationKey,
                    })
                    .catch(() => {
                        /* ignore */
                    });
                reject(
                    new Error(
                        `Gradle task ${task} timed out after ${TASK_TIMEOUT_MS / 1000}s`,
                    ),
                );
            }, TASK_TIMEOUT_MS);
        });

        const taskPromise = this.gradleApi
            .runTask({
                projectFolder: this.workspaceRoot,
                taskName: task,
                args: [...this.argsProvider(), ...extraArgs],
                showOutputColors: false,
                cancellationKey,
                onOutput: (output) => {
                    try {
                        const decoded = new TextDecoder().decode(
                            output.getOutputBytes(),
                        );
                        // Detector still sees the raw stream — it pattern-matches
                        // on JDK image errors that the filter would suppress at
                        // normal level (the noisy lines are exactly the ones that
                        // contain the diagnostic).
                        detector.consume(decoded);
                        classVersionDetector.consume(decoded);
                        kotlinDetector.consume(decoded);
                        const filtered =
                            this.logFilter.filterGradleChunk(decoded);
                        if (filtered.length > 0) {
                            this.logger.append(filtered);
                        }
                        opts.onTaskOutput?.(decoded);
                    } catch {
                        /* ignore */
                    }
                },
            })
            .then(
                () => {
                    const line = `> ${task} completed`;
                    if (this.logFilter.shouldEmitInformational(line)) {
                        this.logger.appendLine(line);
                    }
                },
                (err) => {
                    const message = err?.message ?? String(err);
                    // vscode-gradle reports a superseded task as a gRPC CANCELLED
                    // error — that's the normal "new refresh replaced this one"
                    // path, not a build failure. Log it differently and throw a
                    // typed error so callers can drop it silently.
                    if (CANCELLED_RE.test(message)) {
                        this.logger.appendLine(`> ${task} cancelled`);
                        throw new TaskCancelledError(task);
                    }
                    detector.end();
                    classVersionDetector.end();
                    kotlinDetector.end();
                    const finding = detector.getFinding();
                    if (finding) {
                        // Caller logs a JDK-specific failure line; the generic
                        // gRPC "Could not execute build" message would just be
                        // noise next to it.
                        throw new JdkImageError(finding, task);
                    }
                    const classVersionFinding =
                        classVersionDetector.getFinding();
                    if (classVersionFinding) {
                        throw new ClassVersionError(classVersionFinding, task);
                    }
                    const kotlinErrors = kotlinDetector.getErrors();
                    if (kotlinErrors.length > 0) {
                        // Same here: caller logs a typed Kotlin error summary.
                        throw new KotlinCompileError(kotlinErrors, task);
                    }
                    this.logger.appendLine(`> ${task} FAILED: ${message}`);
                    throw new Error(
                        `Gradle task ${task} failed. See Output > Compose Preview.`,
                    );
                },
            )
            .finally(() => {
                this.activeKeys.delete(cancellationKey);
            });

        return Promise.race([taskPromise, timeoutPromise]);
    }
}
