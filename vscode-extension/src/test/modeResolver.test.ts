import * as assert from "assert";
import { resolveModeFromSettings } from "../composePreviewMode";

/**
 * Tiny stub that satisfies the gradle-service slice [resolveModeFromSettings]
 * reads. The whole point of the predicate's signature is to make this
 * straight-line to test without a VS Code extension host.
 */
function gradleStub(opts: {
    previewModules?: string[];
    injectableHost?: boolean;
}): {
    findPreviewModules(): { modulePath: string }[];
    hasInjectableHostModule(): boolean;
} {
    return {
        findPreviewModules: () =>
            (opts.previewModules ?? []).map((m) => ({ modulePath: m })),
        hasInjectableHostModule: () => opts.injectableHost ?? false,
    };
}

describe("resolveModeFromSettings", () => {
    describe("user-pinned setting", () => {
        it("returns minimal mode when the user sets composePreview.mode=minimal", () => {
            const result = resolveModeFromSettings(
                { mode: "minimal", autoInjectEnabled: true },
                gradleStub({
                    previewModules: [":app"],
                    injectableHost: true,
                }),
            );
            assert.deepStrictEqual(result, {
                mode: "minimal",
                reason: "user-setting",
            });
        });

        it("returns full mode when the user sets composePreview.mode=full", () => {
            const result = resolveModeFromSettings(
                { mode: "full", autoInjectEnabled: false },
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
                { mode: "auto", autoInjectEnabled: true },
                gradleStub({ previewModules: [":app"] }),
            );
            assert.deepStrictEqual(result, {
                mode: "full",
                reason: "auto-plugin-applied",
            });
        });

        it("picks full mode when auto-inject can attach onto an Android host", () => {
            // No preview modules yet, but the workspace applies AGP — the init
            // script will attach onto it, so full mode is the right default.
            const result = resolveModeFromSettings(
                { mode: "auto", autoInjectEnabled: true },
                gradleStub({ injectableHost: true }),
            );
            assert.deepStrictEqual(result, {
                mode: "full",
                reason: "auto-host-injectable",
            });
        });

        it("picks minimal mode when auto-inject is off and no plugin is applied", () => {
            // Even with a usable host plugin, if the user disabled auto-inject
            // we don't speculatively flip into full mode — the user must apply
            // the plugin themselves first.
            const result = resolveModeFromSettings(
                { mode: "auto", autoInjectEnabled: false },
                gradleStub({ injectableHost: true }),
            );
            assert.deepStrictEqual(result, {
                mode: "minimal",
                reason: "auto-no-plugin-applied",
            });
        });

        it("picks minimal mode for an empty / non-Compose workspace", () => {
            const result = resolveModeFromSettings(
                { mode: "auto", autoInjectEnabled: true },
                gradleStub({}),
            );
            assert.deepStrictEqual(result, {
                mode: "minimal",
                reason: "auto-no-plugin-applied",
            });
        });

        it("prefers the marker/text-scan signal over the injectable-host signal", () => {
            // When both are true, the actual `applied.json` / text-scan match
            // is more authoritative — it means the plugin is genuinely applied,
            // not just "would be applied via init script."
            const result = resolveModeFromSettings(
                { mode: "auto", autoInjectEnabled: true },
                gradleStub({
                    previewModules: [":app"],
                    injectableHost: true,
                }),
            );
            assert.strictEqual(result.reason, "auto-plugin-applied");
        });
    });
});
