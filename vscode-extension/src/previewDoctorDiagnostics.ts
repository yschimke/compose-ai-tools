import * as vscode from 'vscode';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { GradleService } from './gradleService';
import { DoctorFinding } from './types';

/**
 * Runs `:<module>:composePreviewDoctor` on each applied-plugin module and
 * surfaces each finding as a VS Code diagnostic attached to the module's
 * `build.gradle[.kts]`. Same rule set as `compose-preview doctor` — the
 * plugin owns the rule logic (see `CompatRules.kt`), both tools call it.
 *
 * Re-uses the existing `GradleService.runDoctor` path; if the Gradle task
 * isn't available (e.g. plugin version predates it), the service returns
 * `null` and we skip the module quietly rather than spamming the Problems
 * view.
 */
export class PreviewDoctorDiagnostics implements vscode.Disposable {
    private readonly collection: vscode.DiagnosticCollection;

    constructor(
        private readonly gradleService: GradleService,
        private readonly workspaceRoot: string,
        private readonly log?: (msg: string) => void,
    ) {
        this.collection = vscode.languages.createDiagnosticCollection('compose-preview-doctor');
    }

    dispose(): void {
        this.collection.dispose();
    }

    /**
     * Refresh diagnostics for every module that has the plugin applied.
     * Atomically swaps the DiagnosticCollection so stale findings from a
     * module that got its issue fixed disappear on the next refresh.
     */
    async refresh(modules: ReadonlyArray<string>): Promise<void> {
        const next = new Map<string, vscode.Diagnostic[]>();
        for (const module of modules) {
            const report = await this.gradleService.runDoctor(module);
            if (!report) { continue; }
            const targetFile = this.resolveBuildFile(module);
            if (!targetFile) { continue; }
            const diags: vscode.Diagnostic[] = [];
            for (const finding of report.findings) {
                diags.push(this.toDiagnostic(module, finding));
            }
            if (diags.length > 0) {
                next.set(targetFile, diags);
            }
        }
        // Atomic swap — clear old state, set new state. VS Code dedupes
        // nothing here; we have to clear first or deleted findings linger.
        this.collection.clear();
        for (const [file, diags] of next) {
            this.collection.set(vscode.Uri.file(file), diags);
        }
        this.log?.(`doctor diagnostics refreshed across ${modules.length} module(s)`);
    }

    private toDiagnostic(module: string, finding: DoctorFinding): vscode.Diagnostic {
        const severity =
            finding.severity === 'error' ? vscode.DiagnosticSeverity.Error :
            finding.severity === 'warning' ? vscode.DiagnosticSeverity.Warning :
            vscode.DiagnosticSeverity.Information;
        const parts = [finding.message];
        if (finding.detail) { parts.push(finding.detail); }
        if (finding.remediationSummary) {
            parts.push(`→ ${finding.remediationSummary}`);
            for (const cmd of finding.remediationCommands ?? []) {
                parts.push(`    ${cmd}`);
            }
            if (finding.docsUrl) {
                parts.push(`    docs: ${finding.docsUrl}`);
            }
        }
        // We don't have a precise line for dep-alignment findings — anchor
        // at the top of the build file. VS Code still threads the finding
        // into the Problems panel with the file name as location.
        const range = new vscode.Range(0, 0, 0, 0);
        const diag = new vscode.Diagnostic(range, parts.join('\n'), severity);
        diag.source = 'compose-preview-doctor';
        diag.code = `${module}:${finding.id}`;
        return diag;
    }

    /** Locate the module's build.gradle[.kts] relative to the workspace root. */
    private resolveBuildFile(module: string): string | undefined {
        for (const name of ['build.gradle.kts', 'build.gradle']) {
            const candidate = path.join(this.workspaceRoot, module, name);
            if (fs.existsSync(candidate)) { return candidate; }
        }
        return undefined;
    }
}
