import * as path from 'path';
import * as fs from 'fs';
import { AccessibilityFinding, AccessibilityReport, Capture, DoctorModuleReport, HistoryEntry, PreviewManifest, ResourceManifest } from './types';
import { appliesPlugin } from './pluginDetection';
import { JdkImageError, JdkImageErrorDetector } from './jdkImageErrorDetector';

const HISTORY_DIRNAME = '.compose-preview-history';
const TIMESTAMP_RE = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(?:-\d+)?$/;

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
    if (!fs.existsSync(rendersDir)) { return templates; }
    const expanded: Capture[] = [];
    for (const template of templates) {
        if (!template.renderOutput) { expanded.push(template); continue; }
        const base = path.basename(template.renderOutput);
        const dot = base.lastIndexOf('.');
        const stem = dot > 0 ? base.slice(0, dot) : base;
        const ext = dot > 0 ? base.slice(dot) : '';
        const prefix = stem + '_';
        const templateDir = path.dirname(template.renderOutput);
        const dirPrefix = templateDir && templateDir !== '.' ? `${templateDir}/` : '';
        const matches = fs.readdirSync(rendersDir)
            .filter(name => name.startsWith(prefix) && name.endsWith(ext)
                && !siblingRenderOutputs.has(dirPrefix + name))
            .map(name => {
                const suffix = name.slice(prefix.length, name.length - ext.length);
                const paramIdxStr = suffix.startsWith('PARAM_') ? suffix.slice('PARAM_'.length) : null;
                const paramIdx = paramIdxStr !== null ? parseInt(paramIdxStr, 10) : NaN;
                return { name, suffix, paramIdx: Number.isNaN(paramIdx) ? null : paramIdx };
            })
            .sort((a, b) => {
                if (a.paramIdx !== null && b.paramIdx !== null) { return a.paramIdx - b.paramIdx; }
                if (a.paramIdx !== null) { return -1; }
                if (b.paramIdx !== null) { return 1; }
                return a.suffix.localeCompare(b.suffix);
            });
        if (matches.length === 0) { continue; }
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

export interface Logger {
    appendLine(value: string): void;
    append(value: string): void;
}

const nullLogger: Logger = { appendLine() {}, append() {} };

/** Parses `yyyyMMdd-HHmmss[-N]` into ISO 8601, or returns null if malformed. */
function timestampToIso(stem: string): string | null {
    const m = TIMESTAMP_RE.exec(stem);
    if (!m) { return null; }
    const [, y, mo, d, h, mi, s] = m;
    return `${y}-${mo}-${d}T${h}:${mi}:${s}Z`;
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
        onOutput?: (output: { getOutputBytes(): Uint8Array; getOutputType(): number }) => void;
        cancellationKey?: string;
    }): Promise<void>;
    cancelRunTask(opts: { projectFolder: string; taskName: string; cancellationKey?: string }): Promise<void>;
}

export class GradleService {
    /** Absolute path to the workspace root. Read-only — exposed for callers that need to resolve
     *  module-relative artifact paths (e.g. the Android manifest CodeLens, which jumps to a
     *  rendered PNG given a `<module>/build/compose-previews/...` relative path). */
    public readonly workspaceRoot: string;
    private logger: Logger;
    private gradleApi: GradleApi;
    private argsProvider: () => string[];
    private manifestCache = new Map<string, { manifest: PreviewManifest; timestamp: number }>();
    private taskCounter = 0;
    private activeKeys = new Set<string>();

    constructor(
        workspaceRoot: string,
        gradleApi: GradleApi,
        logger?: Logger,
        argsProvider?: () => string[],
    ) {
        this.workspaceRoot = workspaceRoot;
        this.gradleApi = gradleApi;
        this.logger = logger ?? nullLogger;
        this.argsProvider = argsProvider ?? (() => []);
    }

    async discoverPreviews(module: string): Promise<PreviewManifest | null> {
        const cached = this.manifestCache.get(module);
        if (cached && Date.now() - cached.timestamp < MANIFEST_CACHE_TTL_MS) {
            return cached.manifest;
        }
        await this.runTask(`:${module}:discoverPreviews`);
        const manifest = this.readManifest(module);
        if (manifest) {
            this.manifestCache.set(module, { manifest, timestamp: Date.now() });
        }
        return manifest;
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
        module: string,
        tier: 'fast' | 'full' = 'full',
    ): Promise<PreviewManifest | null> {
        this.manifestCache.delete(module);
        await this.runTask(
            `:${module}:renderAllPreviews`,
            [`-PcomposePreview.tier=${tier}`],
        );
        const manifest = this.readManifest(module);
        if (manifest) {
            this.manifestCache.set(module, { manifest, timestamp: Date.now() });
        }
        return manifest;
    }

    /**
     * Runs `:<module>:renderAndroidResources` and returns the parsed
     * `resources.json`. Gradle's up-to-date check makes this cheap on warm
     * runs (sub-second when no source XML or AndroidManifest changed); the
     * first invocation pays the Robolectric sandbox cold-start cost
     * (~3–5s).
     *
     * Returns `null` when the consumer is on an older plugin that hasn't
     * registered the task yet, when the gradle invocation itself fails,
     * or when the manifest is malformed. Callers should treat null as
     * "skip the resources view for this module" rather than an error
     * state.
     */
    async renderAndroidResources(module: string): Promise<ResourceManifest | null> {
        try {
            await this.runTask(`:${module}:renderAndroidResources`);
        } catch (e) {
            this.logger.appendLine(
                `:${module}:renderAndroidResources failed: ${
                    e instanceof Error ? e.message : String(e)
                }`,
            );
            return null;
        }
        return this.readResourceManifest(module);
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
    async runDoctor(module: string): Promise<DoctorModuleReport | null> {
        try {
            await this.runTask(`:${module}:composePreviewDoctor`);
        } catch (e) {
            this.logger.appendLine(`[doctor] :${module}:composePreviewDoctor failed: ${(e as Error).message}`);
            return null;
        }
        const reportPath = path.join(this.workspaceRoot, module, 'build', 'compose-previews', 'doctor.json');
        if (!fs.existsSync(reportPath)) {
            this.logger.appendLine(`[doctor] ${reportPath} not produced`);
            return null;
        }
        try {
            const parsed = JSON.parse(fs.readFileSync(reportPath, 'utf-8')) as DoctorModuleReport;
            if (!parsed.schema?.startsWith('compose-preview-doctor/')) {
                this.logger.appendLine(`[doctor] unexpected schema in ${reportPath}: ${parsed.schema}`);
                return null;
            }
            return parsed;
        } catch (e) {
            this.logger.appendLine(`[doctor] parse failed for ${reportPath}: ${(e as Error).message}`);
            return null;
        }
    }

    invalidateCache(module?: string): void {
        if (module) { this.manifestCache.delete(module); }
        else { this.manifestCache.clear(); }
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
    readResourceManifest(module: string): ResourceManifest | null {
        const manifestPath = path.join(this.workspaceRoot, module, 'build', 'compose-previews', 'resources.json');
        if (!fs.existsSync(manifestPath)) { return null; }
        try {
            const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8')) as ResourceManifest;
            if (!Array.isArray(manifest.resources) || !Array.isArray(manifest.manifestReferences)) {
                this.logger.appendLine(`Malformed resource manifest at ${manifestPath}`);
                return null;
            }
            return manifest;
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            this.logger.appendLine(`Failed to parse ${manifestPath}: ${message}`);
            return null;
        }
    }

    readManifest(module: string): PreviewManifest | null {
        const manifestPath = path.join(this.workspaceRoot, module, 'build', 'compose-previews', 'previews.json');
        if (!fs.existsSync(manifestPath)) { return null; }
        try {
            const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8')) as PreviewManifest;
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
            const rendersDir = path.join(this.workspaceRoot, module, 'build', 'compose-previews', 'renders');
            const siblingRenderOutputs = new Set<string>();
            for (const p of manifest.previews) {
                if (!p.params?.previewParameterProviderClassName) {
                    for (const c of p.captures) {
                        if (c.renderOutput) { siblingRenderOutputs.add(c.renderOutput); }
                    }
                }
            }
            for (const p of manifest.previews) {
                if (p.params?.previewParameterProviderClassName) {
                    p.captures = expandParamCaptures(rendersDir, p.captures, siblingRenderOutputs);
                }
            }
            // Enrich each preview with a11y findings when the module has the
            // sidecar report. Always follow the manifest's pointer rather than
            // probing for the file: disabling the Gradle option must cleanly
            // remove findings from the UI without a stale opt-in run
            // haunting us.
            if (manifest.accessibilityReport) {
                const byId = this.readA11yById(module, manifest.accessibilityReport);
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
            this.logger.appendLine(`Failed to parse ${manifestPath}: ${message}`);
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
        module: string,
        relativePath: string,
    ): Record<string, { findings: AccessibilityFinding[]; annotatedPath: string | null }> {
        const reportPath = path.join(this.workspaceRoot, module, 'build', 'compose-previews', relativePath);
        if (!fs.existsSync(reportPath)) { return {}; }
        try {
            const report = JSON.parse(fs.readFileSync(reportPath, 'utf-8')) as AccessibilityReport;
            const reportDir = path.dirname(reportPath);
            const out: Record<string, { findings: AccessibilityFinding[]; annotatedPath: string | null }> = {};
            for (const entry of report.entries ?? []) {
                const resolved = entry.annotatedPath
                    ? path.resolve(reportDir, entry.annotatedPath)
                    : null;
                out[entry.previewId] = {
                    findings: entry.findings ?? [],
                    annotatedPath: resolved && fs.existsSync(resolved) ? resolved : null,
                };
            }
            return out;
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            this.logger.appendLine(`Failed to parse ${reportPath}: ${message}`);
            return {};
        }
    }

    async readPreviewImage(module: string, renderOutput: string): Promise<string | null> {
        const pngPath = path.join(this.workspaceRoot, module, 'build', 'compose-previews', renderOutput);
        try {
            const data = await fs.promises.readFile(pngPath);
            return data.toString('base64');
        } catch {
            return null;
        }
    }

    /**
     * Lists historical snapshots for a preview, newest first. Returns `[]` if
     * the user hasn't enabled `composePreview.historyEnabled`, if the folder
     * is empty, or if the preview has never been archived yet.
     */
    listHistory(module: string, previewId: string): HistoryEntry[] {
        const dir = this.historyFolder(module, previewId);
        if (!fs.existsSync(dir)) { return []; }

        let files: string[];
        try {
            files = fs.readdirSync(dir);
        } catch {
            return [];
        }

        const entries: HistoryEntry[] = [];
        for (const f of files) {
            if (!f.endsWith('.png')) { continue; }
            const stem = f.slice(0, -4);
            const iso = timestampToIso(stem);
            if (!iso) { continue; }
            entries.push({ filename: f, timestamp: stem, iso });
        }
        // Filenames sort lexicographically = chronologically because of the
        // yyyyMMdd-HHmmss prefix. Reverse for newest-first display.
        entries.sort((a, b) => b.filename.localeCompare(a.filename));
        return entries;
    }

    async readHistoryImage(module: string, previewId: string, filename: string): Promise<string | null> {
        // Filename is user-controlled (from the webview); reject path traversal.
        if (filename.includes('/') || filename.includes('\\') || filename.includes('..')) {
            return null;
        }
        const dir = this.historyFolder(module, previewId);
        const pngPath = path.join(dir, filename);
        try {
            const data = await fs.promises.readFile(pngPath);
            return data.toString('base64');
        } catch {
            return null;
        }
    }

    private historyFolder(module: string, previewId: string): string {
        // Mirror HistorizePreviewsTask.sanitize exactly so the webview's
        // PreviewInfo.id maps to the correct on-disk folder.
        const sanitized = previewId.replace(/[^a-zA-Z0-9._-]/g, '_');
        return path.join(this.workspaceRoot, module, HISTORY_DIRNAME, sanitized);
    }

    async cancel(): Promise<void> {
        const keys = [...this.activeKeys];
        this.activeKeys.clear();
        for (const key of keys) {
            try {
                await this.gradleApi.cancelRunTask({
                    projectFolder: this.workspaceRoot,
                    taskName: key.split('|')[1],
                    cancellationKey: key,
                });
            } catch { /* ignore */ }
        }
    }

    /**
     * Returns the names of direct-child subdirectories whose module applies
     * the Compose Preview plugin. Merges two signals:
     *
     *   1. `<module>/build/compose-previews/applied.json` — authoritative
     *      marker written by the `composePreviewApplied` Gradle task. Covers
     *      every apply mechanism (literal `id`, version-catalog alias,
     *      convention plugin, buildSrc) because Gradle itself wrote it.
     *   2. `<module>/build.gradle.kts` matching literal
     *      `id("ee.schimke.composeai.preview")`. Pre-Gradle-run fallback so
     *      trivially-configured workspaces aren't empty on first open.
     *
     * Returning the union means running Gradle on one module doesn't cause
     * others (applied but not yet built) to disappear from the list.
     * Projects that only apply via a catalog alias show up as empty until
     * the bootstrap marker run completes — see [bootstrapAppliedMarkers].
     */
    findPreviewModules(): string[] {
        const found = new Set<string>();
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(this.workspaceRoot, { withFileTypes: true });
        } catch {
            return [];
        }
        for (const entry of entries) {
            if (!entry.isDirectory()) { continue; }
            const dir = entry.name;
            const marker = path.join(
                this.workspaceRoot, dir, 'build', 'compose-previews', 'applied.json',
            );
            if (fs.existsSync(marker)) { found.add(dir); continue; }
            const buildFile = path.join(this.workspaceRoot, dir, 'build.gradle.kts');
            try {
                const content = fs.readFileSync(buildFile, 'utf-8');
                if (appliesPlugin(content)) { found.add(dir); }
            } catch { /* skip */ }
        }
        return [...found].sort();
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
    async bootstrapAppliedMarkers(): Promise<void> {
        try {
            await this.runTask('composePreviewApplied');
        } catch (e) {
            this.logger.appendLine(
                `[applied] composePreviewApplied bootstrap failed: ${(e as Error).message}`,
            );
        }
    }

    resolveModule(filePath: string): string | null {
        const relative = path.relative(this.workspaceRoot, filePath);
        const topDir = relative.split(path.sep)[0];
        if (topDir && this.findPreviewModules().includes(topDir)) {
            return topDir;
        }
        return null;
    }

    dispose(): void {
        this.cancel();
    }

    private runTask(task: string, extraArgs: ReadonlyArray<string> = []): Promise<void> {
        const cancellationKey = `compose-preview-${++this.taskCounter}|${task}`;
        this.activeKeys.add(cancellationKey);
        this.logger.appendLine(`> ${task}`);

        const detector = new JdkImageErrorDetector();

        const timeoutPromise = new Promise<never>((_, reject) => {
            setTimeout(() => {
                this.gradleApi.cancelRunTask({
                    projectFolder: this.workspaceRoot,
                    taskName: task,
                    cancellationKey,
                }).catch(() => { /* ignore */ });
                reject(new Error(`Gradle task ${task} timed out after ${TASK_TIMEOUT_MS / 1000}s`));
            }, TASK_TIMEOUT_MS);
        });

        const taskPromise = this.gradleApi.runTask({
            projectFolder: this.workspaceRoot,
            taskName: task,
            args: [...this.argsProvider(), ...extraArgs],
            showOutputColors: false,
            cancellationKey,
            onOutput: (output) => {
                try {
                    const decoded = new TextDecoder().decode(output.getOutputBytes());
                    this.logger.append(decoded);
                    detector.consume(decoded);
                } catch { /* ignore */ }
            },
        }).then(
            () => { this.logger.appendLine(`> ${task} completed`); },
            (err) => {
                this.logger.appendLine(`> ${task} FAILED: ${err?.message ?? err}`);
                detector.end();
                const finding = detector.getFinding();
                if (finding) {
                    throw new JdkImageError(finding, task);
                }
                throw new Error(`Gradle task ${task} failed. See Output > Compose Preview.`);
            },
        ).finally(() => {
            this.activeKeys.delete(cancellationKey);
        });

        return Promise.race([taskPromise, timeoutPromise]);
    }
}
