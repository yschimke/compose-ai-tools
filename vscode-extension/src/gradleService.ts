import * as path from 'path';
import * as fs from 'fs';
import { PreviewManifest } from './types';

const TASK_TIMEOUT_MS = 5 * 60 * 1000;
const MANIFEST_CACHE_TTL_MS = 30_000;

export interface Logger {
    appendLine(value: string): void;
    append(value: string): void;
}

const nullLogger: Logger = { appendLine() {}, append() {} };

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
            return manifest;
        } catch (e: any) {
            this.logger.appendLine(`Failed to parse ${manifestPath}: ${e.message}`);
            return null;
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
                if (content.includes('ee.schimke.composeai.preview')) {
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

    private runTask(task: string): Promise<void> {
        const cancellationKey = `compose-preview-${++this.taskCounter}|${task}`;
        this.activeKeys.add(cancellationKey);
        this.logger.appendLine(`> ${task}`);

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
            showOutputColors: false,
            cancellationKey,
            onOutput: (output) => {
                try {
                    this.logger.append(new TextDecoder().decode(output.getOutputBytes()));
                } catch { /* ignore */ }
            },
        }).then(
            () => { this.logger.appendLine(`> ${task} completed`); },
            (err) => {
                this.logger.appendLine(`> ${task} FAILED: ${err?.message ?? err}`);
                throw new Error(`Gradle task ${task} failed. See Output > Compose Preview.`);
            },
        ).finally(() => {
            this.activeKeys.delete(cancellationKey);
        });

        return Promise.race([taskPromise, timeoutPromise]);
    }
}
