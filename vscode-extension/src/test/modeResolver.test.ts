import * as assert from "assert";
import { resolveModeFromSettings } from "../composePreviewMode";

/**
 * Tiny stub that satisfies the gradle-service slice [resolveModeFromSettings]
 * reads. The whole point of the predicate's signature is to make this
 * straight-line to test without a VS Code extension host.
 */
function gradleStub(opts: { previewModules?: string[] }): {
    findPreviewModules(): { modulePath: string }[];
} {
    return {
        findPreviewModules: () =>
            (opts.previewModules ?? []).map((m) => ({ modulePath: m })),
    };
}

describe("resolveModeFromSettings", () => {
    describe("user-pinned setting", () => {
        it("returns minimal mode when the user sets composePreview.mode=minimal", () => {
            const result = resolveModeFromSettings(
                { mode: "minimal" },
                gradleStub({ previewModules: [":app"] }),
            );
            assert.deepStrictEqual(result, {
                mode: "minimal",
                reason: "user-setting",
            });
        });

        it("returns full mode when the user sets composePreview.mode=full", () => {
            const result = resolveModeFromSettings(
                { mode: "full" },
                gradleStub({}),
            );
            assert.deepStrictEqual(result, {
                mode: "full",
                reason: "user-setting",
            });
        });
    });

    describe("auto mode", () => {
        it("picks full mode when at least one module already applies the plugin", () => {
            const result = resolveModeFromSettings(
                { mode: "auto" },
                gradleStub({ previewModules: [":app"] }),
            );
            assert.deepStrictEqual(result, {
                mode: "full",
                reason: "auto-plugin-applied",
            });
        });

        it("picks minimal mode when the plugin is not applied, even if an Android / Compose host is present", () => {
            // Auto-inject onto a host plugin doesn't preemptively flip to full
            // mode: the plugin is only applied once Gradle runs the bundled
            // init script and writes `applied.json` markers. Spawning the
            // daemon before that would fail (no `daemon-launch.json` yet) —
            // the post-Gradle-sync re-evaluation handles the upgrade via a
            // one-click reload notification once markers prove the plugin is
            // applied.
            const result = resolveModeFromSettings(
                { mode: "auto" },
                gradleStub({}),
            );
            assert.deepStrictEqual(result, {
                mode: "minimal",
                reason: "auto-no-plugin-applied",
            });
        });

        it("picks minimal mode for an empty / non-Compose workspace", () => {
            const result = resolveModeFromSettings(
                { mode: "auto" },
                gradleStub({}),
            );
            assert.deepStrictEqual(result, {
                mode: "minimal",
                reason: "auto-no-plugin-applied",
            });
        });
    });
});
