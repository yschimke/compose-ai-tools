import * as path from 'path';
import * as fs from 'fs';
import * as vscode from 'vscode';
import { AccessibilityFinding, AccessibilityReport, DoctorModuleReport, HistoryEntry, PreviewManifest } from './types';
import { APPLIES_PLUGIN_RE } from './pluginDetection';
import { JdkImageError, JdkImageErrorDetector } from './jdkImageErrorDetector';

const HISTORY_DIRNAME = '.compose-preview-history';
const TIMESTAMP_RE = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(?:-\d+)?$/;

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
    private workspaceRoot: string;
    private logger: Logger;
    private gradleApi: GradleApi;
    private manifestCache = new Map<string, { manifest: PreviewManifest; timestamp: number }>();
    private taskCounter = 0;
    private activeKeys = new Set<string>();

    constructor(workspaceRoot: string, gradleApi: GradleApi, logger?: Logger) {
        this.workspaceRoot = workspaceRoot;
        this.gradleApi = gradleApi;
        this.logger = logger ?? nullLogger;
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

    async renderPreviews(module: string): Promise<PreviewManifest | null> {
        this.manifestCache.delete(module);
        await this.runTask(`:${module}:renderAllPreviews`);
        const manifest = this.readManifest(module);
        if (manifest) {
            this.manifestCache.set(module, { manifest, timestamp: Date.now() });
        }
        return manifest;
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

    readManifest(module: string): PreviewManifest | null {
        const manifestPath = path.join(this.workspaceRoot, module, 'build', 'compose-previews', 'previews.json');
        if (!fs.existsSync(manifestPath)) { return null; }
        try {
            const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8')) as PreviewManifest;
            if (!manifest.previews || !Array.isArray(manifest.previews)) {
                this.logger.appendLine(`Malformed manifest at ${manifestPath}`);
                return null;
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

    findPreviewModules(): string[] {
        const modules: string[] = [];
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(this.workspaceRoot, { withFileTypes: true });
        } catch {
            return modules;
        }
        for (const entry of entries) {
            if (!entry.isDirectory()) { continue; }
            const buildFile = path.join(this.workspaceRoot, entry.name, 'build.gradle.kts');
            try {
                const content = fs.readFileSync(buildFile, 'utf-8');
                if (APPLIES_PLUGIN_RE.test(content)) {
                    modules.push(entry.name);
                }
            } catch { /* skip */ }
        }
        return modules;
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

    /**
     * Reads workspace settings and builds the `-P` override list passed to
     * every Gradle invocation. Keeps the mapping in one place so settings
     * changes take effect the next time `runTask` fires, no reload required.
     */
    private buildGradleArgs(): string[] {
        const args: string[] = [];
        const config = vscode.workspace.getConfiguration('composePreview');
        if (config.get<boolean>('accessibilityChecks.enabled')) {
            args.push('-PcomposePreview.accessibilityChecks.enabled=true');
        }
        return args;
    }

    private runTask(task: string): Promise<void> {
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
            args: this.buildGradleArgs(),
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
