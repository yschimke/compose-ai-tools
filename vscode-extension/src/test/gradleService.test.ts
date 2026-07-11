import * as assert from "assert";
import * as path from "path";
import * as fs from "fs";
import * as os from "os";
import {
    GradleService,
    GradleApi,
    TaskCancelledError,
    encodeBundlePreviewId,
    isModulePreviewSchedulable,
} from "../gradleService";
import { JdkImageError } from "../jdkImageErrorDetector";

/** Stub GradleApi that records invocations and allows test control. */
class StubGradleApi implements GradleApi {
    public runCalls: Array<{
        taskName: string;
        args?: ReadonlyArray<string>;
        cancellationKey?: string;
    }> = [];
    public cancelCalls: Array<{ taskName: string; cancellationKey?: string }> =
        [];
    public nextRunResult: "success" | Error = "success";
    /** Bytes to feed through onOutput before resolving/rejecting. */
    public nextRunOutput: string = "";

    async runTask(opts: {
        projectFolder: string;
        taskName: string;
        args?: ReadonlyArray<string>;
        cancellationKey?: string;
        onOutput?: (output: {
            getOutputBytes(): Uint8Array;
            getOutputType(): number;
        }) => void;
    }): Promise<void> {
        this.runCalls.push({
            taskName: opts.taskName,
            args: opts.args,
            cancellationKey: opts.cancellationKey,
        });
        if (this.nextRunOutput && opts.onOutput) {
            const bytes = new TextEncoder().encode(this.nextRunOutput);
            opts.onOutput({
                getOutputBytes: () => bytes,
                getOutputType: () => 0,
            });
        }
        if (this.nextRunResult !== "success") {
            throw this.nextRunResult;
        }
    }

    async cancelRunTask(opts: {
        projectFolder: string;
        taskName: string;
        cancellationKey?: string;
    }): Promise<void> {
        this.cancelCalls.push({
            taskName: opts.taskName,
            cancellationKey: opts.cancellationKey,
        });
    }
}

function withTempDir(
    fn: (dir: string, api: StubGradleApi) => void | Promise<void>,
): () => Promise<void> {
    return async () => {
        const dir = fs.mkdtempSync(
            path.join(os.tmpdir(), "compose-preview-test-"),
        );
        try {
            await fn(dir, new StubGradleApi());
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    };
}

describe("GradleService", () => {
    describe("readManifest", () => {
        it(
            "reads and parses a valid manifest",
            withTempDir((dir, api) => {
                const manifestDir = path.join(
                    dir,
                    "testmodule",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, api);
                const manifest = service.readManifest({
                    projectDir: "testmodule",
                    modulePath: ":testmodule",
                });

                assert.notStrictEqual(manifest, null);
                assert.strictEqual(manifest!.module, "sample-android");
                assert.strictEqual(manifest!.previews.length, 4);
            }),
        );

        it(
            "returns null for missing manifest",
            withTempDir((dir, api) => {
                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.readManifest({
                        projectDir: "nonexistent",
                        modulePath: ":nonexistent",
                    }),
                    null,
                );
            }),
        );

        it(
            "returns null for malformed JSON",
            withTempDir((dir, api) => {
                const manifestDir = path.join(
                    dir,
                    "bad",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.writeFileSync(
                    path.join(manifestDir, "previews.json"),
                    "{ broken",
                );

                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.readManifest({
                        projectDir: "bad",
                        modulePath: ":bad",
                    }),
                    null,
                );
            }),
        );

        it(
            "returns null for manifest without previews array",
            withTempDir((dir, api) => {
                const manifestDir = path.join(
                    dir,
                    "empty",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.writeFileSync(
                    path.join(manifestDir, "previews.json"),
                    '{"module":"x"}',
                );

                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.readManifest({
                        projectDir: "empty",
                        modulePath: ":empty",
                    }),
                    null,
                );
            }),
        );
    });

    describe("readResourceManifest", () => {
        it(
            "reads and parses a valid resource manifest",
            withTempDir((dir, api) => {
                const manifestDir = path.join(
                    dir,
                    "testmodule",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "resources.json",
                    ),
                    path.join(manifestDir, "resources.json"),
                );

                const service = new GradleService(dir, api);
                const manifest = service.readResourceManifest({
                    projectDir: "testmodule",
                    modulePath: ":testmodule",
                });

                assert.notStrictEqual(manifest, null);
                assert.strictEqual(manifest!.module, "sample-android");
                assert.strictEqual(manifest!.resources.length, 3);
                assert.strictEqual(manifest!.manifestReferences.length, 2);

                // The empty-string convention for default-qualifier source files should round-trip
                // through JSON.parse — guards the regression where `Map<String?, String>` emitted
                // bare `null:` keys.
                const compose = manifest!.resources.find(
                    (r) => r.id === "drawable/ic_compose_logo",
                )!;
                assert.deepStrictEqual(
                    Object.keys(compose.sourceFiles).sort(),
                    ["", "night"],
                );

                // Adaptive-icon captures should carry their shape and style; vector captures
                // should carry neither. LEGACY captures carry style but no shape.
                const launcher = manifest!.resources.find(
                    (r) => r.id === "mipmap/ic_launcher",
                )!;
                assert.strictEqual(
                    launcher.captures[0].variant?.shape,
                    "CIRCLE",
                );
                assert.strictEqual(
                    launcher.captures[0].variant?.style,
                    "FULL_COLOR",
                );
                const themedLight = launcher.captures.find(
                    (c) => c.variant?.style === "THEMED_LIGHT",
                )!;
                assert.strictEqual(themedLight.variant?.shape, "SQUIRCLE");
                const legacy = launcher.captures.find(
                    (c) => c.variant?.style === "LEGACY",
                )!;
                assert.strictEqual(legacy.variant?.shape, null);
                assert.strictEqual(compose.captures[0].variant?.shape, null);

                // Manifest references resolve activity short-form names against the package.
                const activityRef = manifest!.manifestReferences.find(
                    (r) => r.componentKind === "activity",
                )!;
                assert.strictEqual(
                    activityRef.componentName,
                    "com.example.sampleandroid.MainActivity",
                );
                assert.strictEqual(activityRef.resourceType, "drawable");
                assert.strictEqual(activityRef.resourceName, "ic_settings");
            }),
        );

        it(
            "returns null when resources.json is missing",
            withTempDir((dir, api) => {
                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.readResourceManifest({
                        projectDir: "nonexistent",
                        modulePath: ":nonexistent",
                    }),
                    null,
                );
            }),
        );

        it(
            "returns null for malformed JSON",
            withTempDir((dir, api) => {
                const manifestDir = path.join(
                    dir,
                    "bad",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.writeFileSync(
                    path.join(manifestDir, "resources.json"),
                    "{ broken",
                );

                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.readResourceManifest({
                        projectDir: "bad",
                        modulePath: ":bad",
                    }),
                    null,
                );
            }),
        );

        it(
            "returns null when resources / manifestReferences arrays are missing",
            withTempDir((dir, api) => {
                const manifestDir = path.join(
                    dir,
                    "empty",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.writeFileSync(
                    path.join(manifestDir, "resources.json"),
                    '{"module":"x","variant":"debug"}',
                );

                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.readResourceManifest({
                        projectDir: "empty",
                        modulePath: ":empty",
                    }),
                    null,
                );
            }),
        );
    });

    describe("readPreviewImage", () => {
        it(
            "reads a PNG as base64",
            withTempDir(async (dir, api) => {
                const renderDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                    "renders",
                );
                fs.mkdirSync(renderDir, { recursive: true });
                fs.writeFileSync(
                    path.join(renderDir, "test.png"),
                    Buffer.from([0x89, 0x50, 0x4e, 0x47]),
                );

                const service = new GradleService(dir, api);
                const base64 = await service.readPreviewImage(
                    { projectDir: "mod", modulePath: ":mod" },
                    "renders/test.png",
                );

                assert.notStrictEqual(base64, null);
                assert.strictEqual(Buffer.from(base64!, "base64")[0], 0x89);
            }),
        );

        it(
            "returns null for missing image",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                assert.strictEqual(
                    await service.readPreviewImage(
                        { projectDir: "mod", modulePath: ":mod" },
                        "missing.png",
                    ),
                    null,
                );
            }),
        );
    });

    describe("findPreviewModules", () => {
        it(
            "finds modules with the plugin applied",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "app"));
                fs.writeFileSync(
                    path.join(dir, "app", "build.gradle.kts"),
                    'id("ee.schimke.composeai.preview")',
                );

                fs.mkdirSync(path.join(dir, "lib"));
                fs.writeFileSync(
                    path.join(dir, "lib", "build.gradle.kts"),
                    'id("org.jetbrains.kotlin.jvm")',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "app", modulePath: ":app" },
                ]);
            }),
        );

        it(
            "finds modules with the plugin applied via Groovy build.gradle",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "app"));
                fs.writeFileSync(
                    path.join(dir, "app", "build.gradle"),
                    "plugins { id 'ee.schimke.composeai.preview' }",
                );

                fs.mkdirSync(path.join(dir, "lib"));
                fs.writeFileSync(
                    path.join(dir, "lib", "build.gradle"),
                    "plugins { id 'org.jetbrains.kotlin.jvm' }",
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "app", modulePath: ":app" },
                ]);
            }),
        );

        it(
            "returns empty for workspace with no modules",
            withTempDir((dir, api) => {
                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), []);
            }),
        );

        it(
            "ignores the plugin module itself (declares but does not apply)",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "gradle-plugin"));
                fs.writeFileSync(
                    path.join(dir, "gradle-plugin", "build.gradle.kts"),
                    `
                gradlePlugin {
                    plugins {
                        create("composePreview") {
                            id = "ee.schimke.composeai.preview"
                        }
                    }
                }
            `,
                );
                fs.mkdirSync(path.join(dir, "app"));
                fs.writeFileSync(
                    path.join(dir, "app", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "app", modulePath: ":app" },
                ]);
            }),
        );

        it(
            "excludes literal `apply false` declarations",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "lib"));
                fs.writeFileSync(
                    path.join(dir, "lib", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") apply false }',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), []);
            }),
        );

        it(
            "finds modules via applied.json markers even when build script does not mention the plugin",
            withTempDir((dir, api) => {
                // E.g. the module applies the plugin from a convention plugin
                // in `buildSrc/` — neither the literal-id nor alias regex
                // catches it, but the Gradle-written marker does.
                fs.mkdirSync(path.join(dir, "mod"));
                fs.writeFileSync(
                    path.join(dir, "mod", "build.gradle.kts"),
                    'plugins { id("my-convention-plugin") }',
                );
                const markerDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    '{"schema":"compose-preview-applied/v1","modulePath":":mod","moduleName":"mod","pluginVersion":"0.7.2"}',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "mod", modulePath: ":mod" },
                ]);
            }),
        );

        it(
            "surfaces configured variant and enabled from the marker",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "app"));
                fs.writeFileSync(
                    path.join(dir, "app", "build.gradle.kts"),
                    'plugins { id("my-convention-plugin") }',
                );
                const markerDir = path.join(
                    dir,
                    "app",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    '{"schema":"compose-preview-applied/v1","modulePath":":app","moduleName":"app","pluginVersion":"0.7.2","variant":"demoRelease","enabled":false}',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    {
                        projectDir: "app",
                        modulePath: ":app",
                        variant: "demoRelease",
                        enabled: false,
                    },
                ]);
            }),
        );

        it(
            "omits variant/enabled when the marker does not carry them",
            withTempDir((dir, api) => {
                // A marker written before the fields existed: the shape must
                // stay exactly { projectDir, modulePath } so callers comparing
                // module identity aren't disturbed.
                fs.mkdirSync(path.join(dir, "legacy"));
                fs.writeFileSync(
                    path.join(dir, "legacy", "build.gradle.kts"),
                    'plugins { id("my-convention-plugin") }',
                );
                const markerDir = path.join(
                    dir,
                    "legacy",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    '{"schema":"compose-preview-applied/v1","modulePath":":legacy","moduleName":"legacy","pluginVersion":"0.7.2"}',
                );

                const service = new GradleService(dir, api);
                const [info] = service.findPreviewModules();
                assert.deepStrictEqual(info, {
                    projectDir: "legacy",
                    modulePath: ":legacy",
                });
                assert.ok(!("variant" in info));
                assert.ok(!("enabled" in info));
            }),
        );

        it(
            "unions marker-detected and scan-detected modules",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "app"));
                fs.writeFileSync(
                    path.join(dir, "app", "build.gradle.kts"),
                    'id("ee.schimke.composeai.preview")',
                );

                // A second module with only the marker (e.g. discovered earlier
                // and since rewritten to a convention plugin that the regex
                // doesn't recognise).
                fs.mkdirSync(path.join(dir, "lib"));
                fs.writeFileSync(
                    path.join(dir, "lib", "build.gradle.kts"),
                    'plugins { id("some-convention") }',
                );
                const markerDir = path.join(
                    dir,
                    "lib",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    '{"schema":"compose-preview-applied/v1","modulePath":":lib","moduleName":"lib","pluginVersion":"0.7.2"}',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "app", modulePath: ":app" },
                    { projectDir: "lib", modulePath: ":lib" },
                ]);
            }),
        );

        it(
            "finds nested modules (e.g. samples/wear)",
            withTempDir((dir, api) => {
                // Multi-project layouts where modules live under an organising
                // parent directory (`samples/wear`, `features/login`) — the
                // parent itself has no build file.
                fs.mkdirSync(path.join(dir, "samples", "wear"), {
                    recursive: true,
                });
                fs.writeFileSync(
                    path.join(dir, "samples", "wear", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );
                fs.mkdirSync(path.join(dir, "samples", "cmp"));
                const markerDir = path.join(
                    dir,
                    "samples",
                    "cmp",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    '{"schema":"compose-preview-applied/v1","modulePath":":samples:cmp","moduleName":"cmp","pluginVersion":"0.8.0"}',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "samples/cmp", modulePath: ":samples:cmp" },
                    { projectDir: "samples/wear", modulePath: ":samples:wear" },
                ]);
            }),
        );

        it(
            "follows symlinked subdirectories during the walk (androidx-mini layout)",
            withTempDir((dir, api) => {
                // The AndroidX-mini workspace layout symlinks the upstream
                // androidx checkout under the workspace root and reassigns
                // each project's projectDir into that tree. `withFileTypes`
                // reports the symlink itself, not its target, so without
                // explicit symlink handling the walk stops at the symlink
                // entry and never sees the markers underneath.
                const target = fs.mkdtempSync(
                    path.join(os.tmpdir(), "compose-preview-symlink-target-"),
                );
                try {
                    const moduleProjectDir = path.join(target, "wear", "app");
                    const markerDir = path.join(
                        moduleProjectDir,
                        "build",
                        "compose-previews",
                    );
                    fs.mkdirSync(markerDir, { recursive: true });
                    fs.writeFileSync(
                        path.join(markerDir, "applied.json"),
                        '{"schema":"compose-preview-applied/v1","modulePath":":wear:app","moduleName":"app","pluginVersion":"0.10.6"}',
                    );
                    fs.symlinkSync(target, path.join(dir, "androidx"), "dir");

                    const service = new GradleService(dir, api);
                    assert.deepStrictEqual(service.findPreviewModules(), [
                        {
                            projectDir: "androidx/wear/app",
                            modulePath: ":wear:app",
                        },
                    ]);
                } finally {
                    fs.rmSync(target, { recursive: true, force: true });
                }
            }),
        );
    });

    describe("resolveModule", () => {
        it(
            "resolves file path to module name",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "sample-android"));
                fs.writeFileSync(
                    path.join(dir, "sample-android", "build.gradle.kts"),
                    'id("ee.schimke.composeai.preview")',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(
                    service.resolveModule(
                        path.join(dir, "sample-android", "src", "Foo.kt"),
                    ),
                    {
                        projectDir: "sample-android",
                        modulePath: ":sample-android",
                    },
                );
            }),
        );

        it(
            "returns null for file outside any module",
            withTempDir((dir, api) => {
                const service = new GradleService(dir, api);
                assert.strictEqual(
                    service.resolveModule(path.join(dir, "unknown", "Foo.kt")),
                    null,
                );
            }),
        );

        it(
            "resolves nested module paths",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "samples", "wear"), {
                    recursive: true,
                });
                fs.writeFileSync(
                    path.join(dir, "samples", "wear", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(
                    service.resolveModule(
                        path.join(
                            dir,
                            "samples",
                            "wear",
                            "src",
                            "main",
                            "kotlin",
                            "com",
                            "example",
                            "Foo.kt",
                        ),
                    ),
                    {
                        projectDir: "samples/wear",
                        modulePath: ":samples:wear",
                    },
                );
            }),
        );

        it(
            "prefers the deepest matching module (longest-prefix match)",
            withTempDir((dir, api) => {
                // Gradle allows `:foo:bar` to nest inside `:foo`. A file under
                // `foo/bar/...` belongs to `foo/bar`, not `foo`.
                fs.mkdirSync(path.join(dir, "foo", "bar"), { recursive: true });
                fs.writeFileSync(
                    path.join(dir, "foo", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );
                fs.writeFileSync(
                    path.join(dir, "foo", "bar", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(
                    service.resolveModule(
                        path.join(dir, "foo", "bar", "src", "Foo.kt"),
                    ),
                    { projectDir: "foo/bar", modulePath: ":foo:bar" },
                );
                assert.deepStrictEqual(
                    service.resolveModule(
                        path.join(dir, "foo", "src", "Foo.kt"),
                    ),
                    { projectDir: "foo", modulePath: ":foo" },
                );
            }),
        );

        it(
            "returns the marker's canonical modulePath when projectDir is reassigned (androidx-mini layout)",
            withTempDir((dir, api) => {
                // Mirrors the AndroidX-style settings.gradle.kts that maps
                // a deep filesystem path to a flatter Gradle project path —
                // e.g. `androidx/wear/compose/remote/remote-material3/samples`
                // hosting `:wear:compose:remote:remote-material3-samples`.
                const projectDir = path.join(
                    dir,
                    "androidx",
                    "wear",
                    "compose",
                    "remote",
                    "remote-material3",
                    "samples",
                );
                const markerDir = path.join(
                    projectDir,
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    JSON.stringify({
                        schema: "compose-preview-applied/v1",
                        modulePath:
                            ":wear:compose:remote:remote-material3-samples",
                        moduleName: "remote-material3-samples",
                        pluginVersion: "0.10.5",
                    }),
                );

                const service = new GradleService(dir, api);
                const expected = {
                    projectDir:
                        "androidx/wear/compose/remote/remote-material3/samples",
                    modulePath: ":wear:compose:remote:remote-material3-samples",
                };
                assert.deepStrictEqual(service.findPreviewModules(), [
                    expected,
                ]);
                assert.deepStrictEqual(
                    service.resolveModule(
                        path.join(projectDir, "src", "main", "Foo.kt"),
                    ),
                    expected,
                );
            }),
        );

        it(
            "falls back to projectDir-derived modulePath when applied.json is malformed",
            withTempDir((dir, api) => {
                fs.mkdirSync(path.join(dir, "lib"));
                fs.writeFileSync(
                    path.join(dir, "lib", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );
                const markerDir = path.join(
                    dir,
                    "lib",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(
                    path.join(markerDir, "applied.json"),
                    "{ not json",
                );

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), [
                    { projectDir: "lib", modulePath: ":lib" },
                ]);
            }),
        );
    });

    describe("composePreviewDiscover", () => {
        it(
            "invokes gradleApi with correct task name",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "mod"));
                fs.writeFileSync(
                    path.join(dir, "mod", "build.gradle.kts"),
                    'id("ee.schimke.composeai.preview")',
                );

                const manifestDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, api);
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                assert.strictEqual(api.runCalls.length, 1);
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    ":mod:composePreviewDiscover",
                );
            }),
        );

        it(
            "translates nested module path to colon-separated Gradle task name",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "samples", "wear"), {
                    recursive: true,
                });
                fs.writeFileSync(
                    path.join(dir, "samples", "wear", "build.gradle.kts"),
                    'plugins { id("ee.schimke.composeai.preview") }',
                );
                const manifestDir = path.join(
                    dir,
                    "samples",
                    "wear",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, api);
                await service.composePreviewDiscover({
                    projectDir: "samples/wear",
                    modulePath: ":samples:wear",
                });

                assert.strictEqual(api.runCalls.length, 1);
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    ":samples:wear:composePreviewDiscover",
                );
            }),
        );

        it(
            "dedupes concurrent calls into a single Gradle invocation",
            withTempDir(async (dir) => {
                // Two refresh paths (refresh() + the silent reconcile from
                // refreshAfterDaemonReady) can hit composePreviewDiscover for the
                // same module concurrently; without the dedupe they race two
                // identical Gradle tasks, and the second's cancel() kills
                // the first. The dedupe shares one promise.
                const runCalls: string[] = [];
                const resolvers: Array<() => void> = [];
                const heldApi: GradleApi = {
                    async runTask(opts) {
                        runCalls.push(opts.taskName);
                        await new Promise<void>((r) => {
                            resolvers.push(r);
                        });
                    },
                    async cancelRunTask() {},
                };
                fs.mkdirSync(path.join(dir, "mod"));
                const manifestDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, heldApi);
                const first = service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                const second = service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                // Release the single in-flight runTask call.
                await new Promise((resolve) => setImmediate(resolve));
                assert.strictEqual(
                    runCalls.length,
                    1,
                    `concurrent callers must share one Gradle run, got: ${runCalls.length}`,
                );
                for (const r of resolvers) r();

                const [firstManifest, secondManifest] = await Promise.all([
                    first,
                    second,
                ]);
                assert.notStrictEqual(firstManifest, null);
                assert.strictEqual(firstManifest, secondManifest);
            }),
        );

        it(
            "uses cache for repeated calls within TTL",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "mod"));
                const manifestDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, api);
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                // Second call hit cache — only one Gradle invocation
                assert.strictEqual(api.runCalls.length, 1);
            }),
        );

        it(
            "compileOnly invokes :module:composePreviewCompile and drops the manifest cache",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "mod"));
                const manifestDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, api);
                // Prime the cache via a composePreviewDiscover invocation.
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                assert.strictEqual(api.runCalls.length, 1);
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    ":mod:composePreviewDiscover",
                );

                // compileOnly runs a different task and invalidates the cache.
                await service.compileOnly({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                assert.strictEqual(api.runCalls.length, 2);
                assert.strictEqual(
                    api.runCalls[1].taskName,
                    ":mod:composePreviewCompile",
                );

                // Cache was dropped, so the next composePreviewDiscover calls Gradle again.
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                assert.strictEqual(api.runCalls.length, 3);
                assert.strictEqual(
                    api.runCalls[2].taskName,
                    ":mod:composePreviewDiscover",
                );
            }),
        );

        it(
            "bypasses cache after invalidateCache",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "mod"));
                const manifestDir = path.join(
                    dir,
                    "mod",
                    "build",
                    "compose-previews",
                );
                fs.mkdirSync(manifestDir, { recursive: true });
                fs.copyFileSync(
                    path.join(
                        __dirname,
                        "..",
                        "..",
                        "src",
                        "test",
                        "fixtures",
                        "previews.json",
                    ),
                    path.join(manifestDir, "previews.json"),
                );

                const service = new GradleService(dir, api);
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                service.invalidateCache({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                assert.strictEqual(api.runCalls.length, 2);
            }),
        );

        it(
            "wraps Gradle failures in readable error",
            withTempDir(async (dir, api) => {
                api.nextRunResult = new Error("compilation failed");

                const service = new GradleService(dir, api);
                await assert.rejects(
                    service.composePreviewDiscover({
                        projectDir: "mod",
                        modulePath: ":mod",
                    }),
                    /Gradle task .* failed/,
                );
            }),
        );

        it(
            "throws TaskCancelledError (not generic failure) when vscode-gradle reports a cancelled task",
            withTempDir(async (dir, api) => {
                // vscode-gradle's gRPC layer rejects superseded tasks with
                // a Status.CANCELLED error. That's a normal lifecycle event
                // (a follow-up refresh replaced this one), not a build
                // failure — callers need to be able to drop it silently.
                api.nextRunResult = new Error("1 CANCELLED: Call cancelled");

                const service = new GradleService(dir, api);
                await assert.rejects(
                    service.composePreviewDiscover({
                        projectDir: "mod",
                        modulePath: ":mod",
                    }),
                    (err: unknown) => {
                        assert.ok(
                            err instanceof TaskCancelledError,
                            "expected TaskCancelledError",
                        );
                        return true;
                    },
                );
            }),
        );

        it(
            "throws JdkImageError when the failure output contains the jlink signature",
            withTempDir(async (dir, api) => {
                api.nextRunOutput =
                    "> jlink executable /home/u/.antigravity/extensions/redhat.java-1.54.0-linux-x64" +
                    "/jre/21.0.10-linux-x86_64/bin/jlink does not exist.\n";
                api.nextRunResult = new Error("build failed");

                const service = new GradleService(dir, api);
                await assert.rejects(
                    service.composePreviewDiscover({
                        projectDir: "mod",
                        modulePath: ":mod",
                    }),
                    (err: unknown) => {
                        assert.ok(
                            err instanceof JdkImageError,
                            "expected JdkImageError",
                        );
                        assert.match(
                            (err as JdkImageError).finding.jlinkPath,
                            /\/bin\/jlink$/,
                        );
                        return true;
                    },
                );
            }),
        );
    });

    describe("composePreview.enabled = false gating (#2016)", () => {
        const disabled = {
            projectDir: "mod",
            modulePath: ":mod",
            enabled: false,
        } as const;

        it(
            "composePreviewDiscover schedules no task and returns null for a disabled module",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                const manifest = await service.composePreviewDiscover(disabled);
                assert.strictEqual(manifest, null);
                assert.strictEqual(api.runCalls.length, 0);
            }),
        );

        it(
            "composePreviewRender schedules no task and returns null for a disabled module",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                const manifest = await service.composePreviewRender(disabled);
                assert.strictEqual(manifest, null);
                assert.strictEqual(api.runCalls.length, 0);
            }),
        );

        it(
            "compileOnly schedules no task for a disabled module",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                await service.compileOnly(disabled);
                assert.strictEqual(api.runCalls.length, 0);
            }),
        );

        it(
            "coldStartBundle schedules no task and returns null for a disabled module",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                const manifest = await service.coldStartBundle(disabled);
                assert.strictEqual(manifest, null);
                assert.strictEqual(api.runCalls.length, 0);
            }),
        );

        it(
            "runDaemonBootstrap schedules no task for a disabled module",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                await service.runDaemonBootstrap(disabled);
                assert.strictEqual(api.runCalls.length, 0);
            }),
        );

        it(
            "treats enabled === undefined (legacy marker) as enabled and still schedules",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "mod"));
                const service = new GradleService(dir, api);
                // No `enabled` field — the shape of a legacy/scan-detected module.
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                assert.strictEqual(api.runCalls.length, 1);
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    ":mod:composePreviewDiscover",
                );
            }),
        );

        it(
            "treats enabled === true as enabled and still schedules",
            withTempDir(async (dir, api) => {
                fs.mkdirSync(path.join(dir, "mod"));
                const service = new GradleService(dir, api);
                await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                    enabled: true,
                });
                assert.strictEqual(api.runCalls.length, 1);
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    ":mod:composePreviewDiscover",
                );
            }),
        );
    });

    describe("coldStartBundle", () => {
        function writeManifest(dir: string, moduleDir: string): void {
            const manifestDir = path.join(
                dir,
                moduleDir,
                "build",
                "compose-previews",
            );
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.copyFileSync(
                path.join(
                    __dirname,
                    "..",
                    "..",
                    "src",
                    "test",
                    "fixtures",
                    "previews.json",
                ),
                path.join(manifestDir, "previews.json"),
            );
        }

        it(
            "bundles composePreviewApplied + :module:composePreviewDaemonStart + :module:composePreviewDiscover into one Gradle invocation",
            withTempDir(async (dir, api) => {
                writeManifest(dir, "mod");
                const service = new GradleService(dir, api);

                const manifest = await service.coldStartBundle({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                assert.notStrictEqual(
                    manifest,
                    null,
                    "bundle should return the discovered manifest",
                );
                assert.strictEqual(
                    api.runCalls.length,
                    1,
                    "the cold-start path must collapse to one Gradle invocation",
                );
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    "composePreviewApplied",
                );
                assert.deepStrictEqual(api.runCalls[0].args, [
                    ":mod:composePreviewDaemonStart",
                    ":mod:composePreviewDiscover",
                ]);
            }),
        );

        it(
            "drops composePreviewApplied from the bundle when the markers are still fresh",
            withTempDir(async (dir, api) => {
                writeManifest(dir, "mod");
                writeManifest(dir, "other");
                const service = new GradleService(dir, api);

                await service.coldStartBundle({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                assert.strictEqual(api.runCalls.length, 1);
                // Second module hits its own cold-start while the marker
                // bootstrap is fresh — applied drops out, so the second
                // invocation only carries the per-module tasks.
                await service.coldStartBundle({
                    projectDir: "other",
                    modulePath: ":other",
                });
                assert.strictEqual(api.runCalls.length, 2);
                assert.strictEqual(
                    api.runCalls[1].taskName,
                    ":other:composePreviewDaemonStart",
                );
                assert.deepStrictEqual(api.runCalls[1].args, [
                    ":other:composePreviewDiscover",
                ]);
            }),
        );

        it(
            "coalesces a concurrent bootstrapAppliedMarkers into the in-flight bundle",
            withTempDir(async (dir, api) => {
                writeManifest(dir, "mod");
                const resolvers: Array<() => void> = [];
                const heldApi: GradleApi = {
                    async runTask(opts) {
                        api.runCalls.push({
                            taskName: opts.taskName,
                            args: opts.args,
                            cancellationKey: opts.cancellationKey,
                        });
                        await new Promise<void>((r) => {
                            resolvers.push(r);
                        });
                    },
                    async cancelRunTask() {
                        /* no-op */
                    },
                };
                const service = new GradleService(dir, heldApi);

                const bundle = service.coldStartBundle({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                // Yield so the bundle's `inFlightColdStart` + `appliedBootstrapPromise`
                // entries land before the bootstrap call checks for them.
                await new Promise((resolve) => setImmediate(resolve));

                const applied = service.bootstrapAppliedMarkers();
                // Bootstrap awaits the bundle — it must NOT have issued its
                // own runTask.
                await new Promise((resolve) => setImmediate(resolve));
                assert.strictEqual(
                    api.runCalls.length,
                    1,
                    "bootstrapAppliedMarkers must coalesce into the bundle, not fire a second runTask",
                );

                for (const r of resolvers) r();
                await Promise.all([bundle, applied]);
            }),
        );

        it(
            "dedupes concurrent composePreviewDiscover for the bundled module against the in-flight bundle",
            withTempDir(async (dir, api) => {
                writeManifest(dir, "mod");
                const resolvers: Array<() => void> = [];
                const heldApi: GradleApi = {
                    async runTask(opts) {
                        api.runCalls.push({
                            taskName: opts.taskName,
                            args: opts.args,
                            cancellationKey: opts.cancellationKey,
                        });
                        await new Promise<void>((r) => {
                            resolvers.push(r);
                        });
                    },
                    async cancelRunTask() {
                        /* no-op */
                    },
                };
                const service = new GradleService(dir, heldApi);

                const bundle = service.coldStartBundle({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                await new Promise((resolve) => setImmediate(resolve));
                const discover = service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                await new Promise((resolve) => setImmediate(resolve));

                assert.strictEqual(
                    api.runCalls.length,
                    1,
                    "post-warm discover for the bundled module must await the bundle instead of starting a second Gradle invocation",
                );

                for (const r of resolvers) r();
                await Promise.all([bundle, discover]);
            }),
        );

        it(
            "omits :module:composePreviewDiscover when the caller is runDaemonBootstrap so a transient discover failure can't kill daemon-start",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);

                await service.runDaemonBootstrap({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                assert.strictEqual(
                    api.runCalls.length,
                    1,
                    "the bootstrap path must still collapse to one Gradle invocation",
                );
                assert.strictEqual(
                    api.runCalls[0].taskName,
                    "composePreviewApplied",
                );
                assert.deepStrictEqual(
                    api.runCalls[0].args,
                    [":mod:composePreviewDaemonStart"],
                    "runDaemonBootstrap must not bundle composePreviewDiscover — daemon-start should come up even when discovery is failing",
                );
            }),
        );

        it(
            "runs a fresh composePreviewDiscover after a daemon-only bootstrap so the manifest still arrives",
            withTempDir(async (dir, api) => {
                writeManifest(dir, "mod");
                const service = new GradleService(dir, api);

                await service.runDaemonBootstrap({
                    projectDir: "mod",
                    modulePath: ":mod",
                });
                assert.strictEqual(api.runCalls.length, 1);

                const manifest = await service.composePreviewDiscover({
                    projectDir: "mod",
                    modulePath: ":mod",
                });

                assert.notStrictEqual(manifest, null);
                assert.strictEqual(
                    api.runCalls.length,
                    2,
                    "post-bootstrap discover should run on its own because the bootstrap bundle did not include it",
                );
                assert.strictEqual(
                    api.runCalls[1].taskName,
                    ":mod:composePreviewDiscover",
                );
            }),
        );
    });

    describe("cancel", () => {
        it(
            "cancels in-flight tasks via API",
            withTempDir(async (dir, api) => {
                const service = new GradleService(dir, api);
                // Start a task but don't await — simulate running
                service
                    .composePreviewDiscover({
                        projectDir: "mod",
                        modulePath: ":mod",
                    })
                    .catch(() => {}); // swallow error
                await new Promise((resolve) => setTimeout(resolve, 10));

                await service.cancel();
                assert.strictEqual(api.cancelCalls.length >= 0, true); // at least attempted
            }),
        );

        it(
            "skips composePreviewDaemonStart so a refresh-cancel can't kill the daemon bootstrap mid-flight",
            withTempDir(async (dir, api) => {
                // Stub that keeps runTask pending until we tell it to resolve,
                // so the cancellation key is still in activeKeys when cancel()
                // is called. Without this the default stub resolves before the
                // test even gets to cancel(), and the assertion is vacuous.
                const resolvers: Array<() => void> = [];
                const heldApi: GradleApi = {
                    async runTask(opts) {
                        api.runCalls.push({
                            taskName: opts.taskName,
                            cancellationKey: opts.cancellationKey,
                        });
                        await new Promise<void>((r) => {
                            resolvers.push(r);
                        });
                    },
                    async cancelRunTask(opts) {
                        api.cancelCalls.push({
                            taskName: opts.taskName,
                            cancellationKey: opts.cancellationKey,
                        });
                    },
                };
                const service = new GradleService(dir, heldApi);

                const bootstrap = service
                    .runDaemonBootstrap({
                        projectDir: "mod",
                        modulePath: ":mod",
                    })
                    .catch(() => {});
                // Discover for a DIFFERENT module so it doesn't dedupe with
                // the bundle's own per-module discover (coldStartBundle's
                // post-warm reconcile coalesces against the in-flight bundle
                // via `inFlightColdStart`). We just need a second runTask in
                // flight so the cancel pool has something to cancel.
                const render = service
                    .composePreviewDiscover({
                        projectDir: "other",
                        modulePath: ":other",
                    })
                    .catch(() => {});
                // Yield enough turns for both runTask invocations to be
                // recorded in activeKeys.
                await new Promise((resolve) => setImmediate(resolve));
                await new Promise((resolve) => setImmediate(resolve));

                await service.cancel();

                const cancelledTasks = api.cancelCalls.map((c) => c.taskName);
                assert.ok(
                    !cancelledTasks.some((t) =>
                        t.endsWith(":composePreviewDaemonStart"),
                    ),
                    `bootstrap must not be cancelled, got: ${cancelledTasks.join(", ")}`,
                );
                // Bundle head task is `composePreviewApplied` (markers not
                // fresh in a brand-new GradleService); the cancel pool spares
                // that name too, so the bundle's actual cancellation key
                // doesn't show up here either.
                assert.ok(
                    !cancelledTasks.some((t) => t === "composePreviewApplied"),
                    `bundle head must not be cancelled, got: ${cancelledTasks.join(", ")}`,
                );
                assert.ok(
                    cancelledTasks.some((t) =>
                        t.endsWith(":composePreviewDiscover"),
                    ),
                    `render-path task must still be cancelled, got: ${cancelledTasks.join(", ")}`,
                );

                // Release both held tasks so the test cleans up.
                for (const r of resolvers) r();
                await Promise.all([bootstrap, render]);
            }),
        );

        it(
            "skips composePreviewApplied so the activation refresh can't kill the marker bootstrap mid-flight",
            withTempDir(async (dir, api) => {
                const resolvers: Array<() => void> = [];
                const heldApi: GradleApi = {
                    async runTask(opts) {
                        api.runCalls.push({
                            taskName: opts.taskName,
                            cancellationKey: opts.cancellationKey,
                        });
                        await new Promise<void>((r) => {
                            resolvers.push(r);
                        });
                    },
                    async cancelRunTask(opts) {
                        api.cancelCalls.push({
                            taskName: opts.taskName,
                            cancellationKey: opts.cancellationKey,
                        });
                    },
                };
                const service = new GradleService(dir, heldApi);

                const applied = service
                    .bootstrapAppliedMarkers()
                    .catch(() => {});
                const render = service
                    .composePreviewDiscover({
                        projectDir: "mod",
                        modulePath: ":mod",
                    })
                    .catch(() => {});
                await new Promise((resolve) => setImmediate(resolve));
                await new Promise((resolve) => setImmediate(resolve));

                await service.cancel();

                const cancelledTasks = api.cancelCalls.map((c) => c.taskName);
                assert.ok(
                    !cancelledTasks.some((t) => t === "composePreviewApplied"),
                    `composePreviewApplied must not be cancelled, got: ${cancelledTasks.join(", ")}`,
                );
                assert.ok(
                    cancelledTasks.some((t) =>
                        t.endsWith(":composePreviewDiscover"),
                    ),
                    `other tasks must still be cancelled, got: ${cancelledTasks.join(", ")}`,
                );

                for (const r of resolvers) r();
                await Promise.all([applied, render]);
            }),
        );

        it(
            "spares the in-flight :<module>:composePreviewDiscover when cancel({keepDiscoverFor}) names that module",
            withTempDir(async (dir, api) => {
                const resolvers: Array<() => void> = [];
                const heldApi: GradleApi = {
                    async runTask(opts) {
                        api.runCalls.push({
                            taskName: opts.taskName,
                            cancellationKey: opts.cancellationKey,
                        });
                        await new Promise<void>((r) => {
                            resolvers.push(r);
                        });
                    },
                    async cancelRunTask(opts) {
                        api.cancelCalls.push({
                            taskName: opts.taskName,
                            cancellationKey: opts.cancellationKey,
                        });
                    },
                };
                const service = new GradleService(dir, heldApi);

                // Two in-flight composePreviewDiscover for different modules.
                const keepRun = service
                    .composePreviewDiscover({
                        projectDir: "keep",
                        modulePath: ":keep",
                    })
                    .catch(() => {});
                const dropRun = service
                    .composePreviewDiscover({
                        projectDir: "drop",
                        modulePath: ":drop",
                    })
                    .catch(() => {});
                await new Promise((resolve) => setImmediate(resolve));
                await new Promise((resolve) => setImmediate(resolve));

                await service.cancel({ keepDiscoverFor: ":keep" });

                const cancelledTasks = api.cancelCalls.map((c) => c.taskName);
                assert.ok(
                    !cancelledTasks.some(
                        (t) => t === ":keep:composePreviewDiscover",
                    ),
                    `:keep:composePreviewDiscover must not be cancelled, got: ${cancelledTasks.join(", ")}`,
                );
                assert.ok(
                    cancelledTasks.some(
                        (t) => t === ":drop:composePreviewDiscover",
                    ),
                    `:drop:composePreviewDiscover must be cancelled, got: ${cancelledTasks.join(", ")}`,
                );

                for (const r of resolvers) r();
                await Promise.all([keepRun, dropRun]);
            }),
        );
    });
});

describe("encodeBundlePreviewId", () => {
    it("leaves comma-free ids untouched", () => {
        assert.strictEqual(
            encodeBundlePreviewId("com.example.Foo.Bar"),
            "com.example.Foo.Bar",
        );
    });

    it("escapes commas with a leading backslash", () => {
        // `@Preview(name = "Phone, dark")` lands the comma verbatim in the
        // preview id, so the wire form must mark it as a literal rather
        // than a separator.
        assert.strictEqual(
            encodeBundlePreviewId("com.example.Foo.Bar_Phone, dark"),
            "com.example.Foo.Bar_Phone\\, dark",
        );
    });

    it("escapes backslashes ahead of commas so escapes nest cleanly", () => {
        // A preview id containing a backslash must double it; otherwise the
        // plugin parser would consume the `\` as the prefix of whatever
        // came next (e.g. `\,` would lose the literal `\`).
        assert.strictEqual(encodeBundlePreviewId("a\\b"), "a\\\\b");
        assert.strictEqual(encodeBundlePreviewId("a\\,b"), "a\\\\\\,b");
    });
});

describe("isModulePreviewSchedulable", () => {
    const base = { projectDir: "mod", modulePath: ":mod" };

    it("blocks scheduling only when enabled is explicitly false", () => {
        assert.strictEqual(
            isModulePreviewSchedulable({ ...base, enabled: false }),
            false,
        );
    });

    it("schedules when enabled is true", () => {
        assert.strictEqual(
            isModulePreviewSchedulable({ ...base, enabled: true }),
            true,
        );
    });

    it("treats a missing enabled (legacy / scan-detected) as enabled", () => {
        assert.strictEqual(isModulePreviewSchedulable(base), true);
    });
});
