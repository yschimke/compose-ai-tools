import * as assert from "assert";
import { renderDoctorReport, DoctorReportInput } from "../doctorReport";

function baseInput(): DoctorReportInput {
    return {
        generatedAt: new Date("2024-01-01T12:00:00Z"),
        environment: {
            extensionVersion: "0.1.0",
            extensionPath: "/tmp/ext",
            vscodeVersion: "1.85.0",
            platform: "linux",
            osRelease: "6.18.5",
            nodeVersion: "v20.10.0",
            arch: "x64",
            workspaceRoot: "/repo",
            mode: "full",
            earlyFeaturesEnabled: false,
            loggingLevel: "normal",
        },
        modules: [],
        pendingDataExtensions: [],
        notes: [],
    };
}

describe("renderDoctorReport", () => {
    it("includes environment data so support requests carry the basics", () => {
        const out = renderDoctorReport(baseInput());
        assert.ok(out.includes("Compose Preview — Doctor Report"));
        assert.ok(out.includes("Extension version: `0.1.0`"));
        assert.ok(out.includes("VS Code version: `1.85.0`"));
        assert.ok(out.includes("Mode: `full`"));
        assert.ok(out.includes("Logging level: `normal`"));
    });

    it("reports an empty-modules placeholder when no plugin is applied", () => {
        const out = renderDoctorReport(baseInput());
        assert.ok(
            out.includes("No preview-eligible modules were detected"),
            "empty-modules placeholder missing",
        );
    });

    it("formats per-module doctor findings with severity and remediation", () => {
        const input = baseInput();
        input.modules = [
            {
                modulePath: ":samples:android",
                projectDir: "samples/android",
                pluginApplied: true,
                daemonReady: true,
                daemonInteractive: false,
                dataProducts: [
                    {
                        kind: "a11y/atf",
                        transport: "inline",
                        schemaVersion: 1,
                    },
                ],
                dataExtensions: [{ id: "a11y", displayName: "Accessibility" }],
                doctor: {
                    schema: "doctor/v1",
                    module: ":samples:android",
                    variant: "debug",
                    findings: [
                        {
                            id: "robolectric-jar-stale",
                            severity: "warning",
                            message:
                                "Robolectric jar is from an older Compose version.",
                            detail: "Detected jar: foo.jar",
                            remediationSummary: "Bump the dependency.",
                            remediationCommands: [
                                "./gradlew :app:dependencies",
                            ],
                            docsUrl: "https://example.com/docs",
                        },
                    ],
                },
                doctorError: null,
            },
        ];
        const out = renderDoctorReport(input);
        assert.ok(out.includes(":samples:android"));
        assert.ok(out.includes("`a11y/atf`"));
        assert.ok(out.includes("Accessibility"));
        assert.ok(out.includes("⚠️ **robolectric-jar-stale**"));
        assert.ok(out.includes("→ Bump the dependency."));
        assert.ok(out.includes("`./gradlew :app:dependencies`"));
        assert.ok(out.includes("docs: https://example.com/docs"));
    });

    it("marks the doctor section as failed when an error was captured", () => {
        const input = baseInput();
        input.modules = [
            {
                modulePath: ":m",
                projectDir: "m",
                pluginApplied: true,
                daemonReady: false,
                daemonInteractive: false,
                dataProducts: [],
                dataExtensions: [],
                doctor: null,
                doctorError: "Gradle task failed",
            },
        ];
        const out = renderDoctorReport(input);
        assert.ok(out.includes("Doctor: ⚠️ failed"));
        assert.ok(out.includes("Gradle task failed"));
    });

    it("lists pending data extensions with elapsed time", () => {
        const input = baseInput();
        input.pendingDataExtensions = [
            {
                previewId: "Preview#one",
                moduleId: ":m",
                kinds: ["a11y/atf"],
                elapsedMs: 1_500,
            },
        ];
        const out = renderDoctorReport(input);
        assert.ok(out.includes("Preview#one"));
        assert.ok(out.includes("1.5s waiting"));
        assert.ok(out.includes("`a11y/atf`"));
    });
});
