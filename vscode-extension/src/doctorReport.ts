// Assembles a Markdown "Doctor Report" that bundles the per-module doctor
// findings (which are also surfaced as build-file diagnostics by
// `PreviewDoctorDiagnostics`) with environment, daemon, and capability data
// the user is otherwise unlikely to know to copy from settings. The output is
// intended to be pasted directly into a GitHub issue — the goal is "less
// back-and-forth on the bug tracker," not a structured machine artefact.

import type { DoctorModuleReport } from "./types";

export interface DoctorReportEnvironment {
    extensionVersion: string;
    extensionPath: string;
    vscodeVersion: string;
    platform: string;
    osRelease: string;
    nodeVersion: string;
    arch: string;
    workspaceRoot: string;
    mode: string;
    earlyFeaturesEnabled: boolean;
    loggingLevel: string;
}

export interface DoctorReportModule {
    modulePath: string;
    projectDir: string;
    pluginApplied: boolean;
    daemonReady: boolean;
    daemonInteractive: boolean;
    dataProducts: { kind: string; transport: string; schemaVersion: number }[];
    dataExtensions: { id: string; displayName?: string }[];
    /** `null` means the doctor task isn't available for this module (older
     *  plugin, or `enabled = false` in the daemon descriptor). */
    doctor: DoctorModuleReport | null;
    /** `null` means the call succeeded — otherwise the captured error message. */
    doctorError: string | null;
}

export interface DoctorReportPendingExtension {
    previewId: string;
    moduleId: string;
    kinds: readonly string[];
    elapsedMs: number;
}

export interface DoctorReportInput {
    generatedAt: Date;
    environment: DoctorReportEnvironment;
    modules: readonly DoctorReportModule[];
    pendingDataExtensions: readonly DoctorReportPendingExtension[];
    /** Free-form notes the host wants to attach (e.g. "doctor task skipped
     *  because gradleService is offline"). One line each. */
    notes?: readonly string[];
}

/** Render the report as GitHub-flavoured Markdown. */
export function renderDoctorReport(input: DoctorReportInput): string {
    const lines: string[] = [];
    const env = input.environment;
    lines.push("# Compose Preview — Doctor Report");
    lines.push("");
    lines.push(`Generated: ${input.generatedAt.toISOString()}`);
    lines.push("");
    lines.push("## Environment");
    lines.push("");
    lines.push(`- Extension version: \`${env.extensionVersion}\``);
    lines.push(`- Extension path: \`${env.extensionPath}\``);
    lines.push(`- VS Code version: \`${env.vscodeVersion}\``);
    lines.push(
        `- Platform: \`${env.platform}\` (${env.osRelease}, ${env.arch})`,
    );
    lines.push(`- Node: \`${env.nodeVersion}\``);
    lines.push(`- Workspace: \`${env.workspaceRoot}\``);
    lines.push(`- Mode: \`${env.mode}\``);
    lines.push(`- Early features: \`${env.earlyFeaturesEnabled}\``);
    lines.push(`- Logging level: \`${env.loggingLevel}\``);
    lines.push("");

    if (input.modules.length === 0) {
        lines.push("## Modules");
        lines.push("");
        lines.push(
            "_No preview-eligible modules were detected in this workspace._",
        );
        lines.push("");
    } else {
        lines.push(`## Modules (${input.modules.length})`);
        lines.push("");
        for (const m of input.modules) {
            lines.push(`### \`${m.modulePath}\``);
            lines.push("");
            lines.push(`- Project dir: \`${m.projectDir}\``);
            lines.push(`- Plugin applied: \`${m.pluginApplied}\``);
            lines.push(`- Daemon ready: \`${m.daemonReady}\``);
            lines.push(`- Daemon interactive: \`${m.daemonInteractive}\``);
            if (m.dataProducts.length === 0) {
                lines.push("- Data products: _(none advertised)_");
            } else {
                lines.push("- Data products:");
                for (const p of m.dataProducts) {
                    lines.push(
                        `    - \`${p.kind}\` (transport=${p.transport}, schema=${p.schemaVersion})`,
                    );
                }
            }
            if (m.dataExtensions.length === 0) {
                lines.push("- Data extensions: _(none advertised)_");
            } else {
                lines.push("- Data extensions:");
                for (const e of m.dataExtensions) {
                    const label = e.displayName
                        ? `\`${e.id}\` — ${e.displayName}`
                        : `\`${e.id}\``;
                    lines.push(`    - ${label}`);
                }
            }
            if (m.doctorError) {
                lines.push(`- Doctor: ⚠️ failed — \`${m.doctorError}\``);
            } else if (!m.doctor) {
                lines.push("- Doctor: _(not available for this module)_");
            } else if (m.doctor.findings.length === 0) {
                lines.push("- Doctor: ✅ no findings");
            } else {
                lines.push(
                    `- Doctor (variant \`${m.doctor.variant}\`, schema \`${m.doctor.schema}\`): ${m.doctor.findings.length} finding(s)`,
                );
                for (const f of m.doctor.findings) {
                    const sev = severityIcon(f.severity);
                    lines.push(`    - ${sev} **${f.id}** — ${f.message}`);
                    if (f.detail) {
                        lines.push(`        - ${oneLine(f.detail)}`);
                    }
                    if (f.remediationSummary) {
                        lines.push(`        - → ${f.remediationSummary}`);
                    }
                    for (const cmd of f.remediationCommands ?? []) {
                        lines.push(`            \`${cmd}\``);
                    }
                    if (f.docsUrl) {
                        lines.push(`        - docs: ${f.docsUrl}`);
                    }
                }
            }
            lines.push("");
        }
    }

    lines.push("## Pending data extensions");
    lines.push("");
    if (input.pendingDataExtensions.length === 0) {
        lines.push("_None — no data extensions are awaiting first arrival._");
    } else {
        for (const p of input.pendingDataExtensions) {
            lines.push(
                `- \`${p.previewId}\` (\`${p.moduleId}\`, ${Math.round(p.elapsedMs / 100) / 10}s waiting): kinds=[${p.kinds.map((k) => `\`${k}\``).join(", ")}]`,
            );
        }
    }
    lines.push("");

    if (input.notes && input.notes.length > 0) {
        lines.push("## Notes");
        lines.push("");
        for (const n of input.notes) lines.push(`- ${oneLine(n)}`);
        lines.push("");
    }

    lines.push("---");
    lines.push(
        "_Generated by the Compose Preview extension. Paste this into a GitHub issue at https://github.com/yschimke/compose-ai-tools/issues — redact any private paths or repository names you don't want to share._",
    );
    return lines.join("\n");
}

function severityIcon(severity: string): string {
    if (severity === "error") return "❌";
    if (severity === "warning") return "⚠️";
    if (severity === "info") return "ℹ️";
    return `\`${severity}\``;
}

function oneLine(s: string): string {
    return s.replace(/\s+/g, " ").trim();
}
