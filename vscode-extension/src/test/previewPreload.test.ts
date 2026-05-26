import * as assert from "assert";
import * as path from "path";
import {
    describePreloadOutcome,
    loadCachedPreviews,
    PreloadDeps,
    PreloadOutcome,
} from "../previewPreload";
import { GradleService, ModuleInfo } from "../gradleService";
import { ExtensionToWebview, PreviewInfo, PreviewManifest } from "../types";

const mod = (modulePath: string): ModuleInfo =>
    ({
        modulePath,
        projectDir: modulePath.replace(/^:/, "").replace(/:/g, "/"),
    }) as ModuleInfo;

interface FakeGradleOptions {
    workspaceRoot?: string;
    resolveModule?: (filePath: string) => ModuleInfo | null;
    readManifest?: (module: ModuleInfo) => PreviewManifest | null;
    readPreviewImage?: (
        module: ModuleInfo,
        renderOutput: string,
    ) => Promise<string | null>;
}

function fakeGradleService(opts: FakeGradleOptions = {}): GradleService {
    const workspaceRoot = opts.workspaceRoot ?? "/ws";
    return {
        workspaceRoot,
        resolveModule: opts.resolveModule ?? (() => mod(":app")),
        readManifest: opts.readManifest ?? (() => null),
        readPreviewImage: opts.readPreviewImage ?? (async () => null),
    } as unknown as GradleService;
}

function preview(id: string, sourceFile: string): PreviewInfo {
    return {
        id,
        functionName: id.split(".").pop()!,
        className: id.substring(0, id.lastIndexOf(".")),
        sourceFile,
        params: {
            name: null,
            device: null,
            widthDp: null,
            heightDp: null,
            fontScale: 1,
            showSystemUi: false,
            showBackground: false,
            backgroundColor: 0,
            uiMode: 0,
            locale: null,
            group: null,
        },
        captures: [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: `renders/${id}.png`,
            },
        ],
    } as PreviewInfo;
}

function manifestWith(...previews: PreviewInfo[]): PreviewManifest {
    return { previews } as PreviewManifest;
}

interface Captured {
    messages: ExtensionToWebview[];
    imageSets: Array<{ previewId: string; imageData: string }>;
    moduleSets: Array<{ previewId: string; module: ModuleInfo }>;
}

function makeDeps(
    overrides: Partial<PreloadDeps> = {},
    captured?: Captured,
): PreloadDeps {
    const cap = captured ?? {
        messages: [],
        imageSets: [],
        moduleSets: [],
    };
    return {
        gradleService: fakeGradleService(),
        postMessage: (msg) => cap.messages.push(msg),
        setImage: (previewId, imageData) =>
            cap.imageSets.push({ previewId, imageData }),
        setPreviewModule: (previewId, module) =>
            cap.moduleSets.push({ previewId, module }),
        isPreviewSourceFile: () => true,
        ...overrides,
    };
}

describe("loadCachedPreviews — skip outcomes", () => {
    it("not-preview-file when the file isn't recognised as a preview source", async () => {
        const outcome = await loadCachedPreviews(
            "/ws/app/src/main/AndroidManifest.xml",
            makeDeps({ isPreviewSourceFile: () => false }),
        );
        assert.deepStrictEqual(outcome, {
            kind: "skipped",
            reason: "not-preview-file",
            module: null,
        });
    });

    it("no-module when gradleService.resolveModule returns null", async () => {
        const outcome = await loadCachedPreviews(
            "/ws/orphan/src/main/kotlin/Orphan.kt",
            makeDeps({
                gradleService: fakeGradleService({ resolveModule: () => null }),
            }),
        );
        assert.deepStrictEqual(outcome, {
            kind: "skipped",
            reason: "no-module",
            module: null,
        });
    });

    it("no-manifest when previews.json is absent", async () => {
        const module = mod(":app");
        const outcome = await loadCachedPreviews(
            "/ws/app/src/main/kotlin/Previews.kt",
            makeDeps({
                gradleService: fakeGradleService({
                    resolveModule: () => module,
                    readManifest: () => null,
                }),
            }),
        );
        assert.deepStrictEqual(outcome, {
            kind: "skipped",
            reason: "no-manifest",
            module,
        });
    });

    it("empty-manifest when the manifest loaded but listed zero previews", async () => {
        const module = mod(":app");
        const outcome = await loadCachedPreviews(
            "/ws/app/src/main/kotlin/Previews.kt",
            makeDeps({
                gradleService: fakeGradleService({
                    resolveModule: () => module,
                    readManifest: () => manifestWith(),
                }),
            }),
        );
        assert.deepStrictEqual(outcome, {
            kind: "skipped",
            reason: "empty-manifest",
            module,
        });
    });

    it("no-visible-previews when the manifest has entries but none belong to the requested file", async () => {
        // Regression: this case happens routinely on multi-file modules. The manifest is
        // populated with every preview in the module; preloadCachedPreviews must report
        // "no-visible-previews" rather than the broader "empty-manifest" so the log says
        // why a non-empty manifest still didn't paint anything.
        const module = mod(":app");
        const outcome = await loadCachedPreviews(
            "/ws/app/src/main/kotlin/com/example/Previews.kt",
            makeDeps({
                gradleService: fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () =>
                        manifestWith(
                            preview(
                                "com.example.Other.OtherPreview",
                                "src/main/kotlin/com/example/Other.kt",
                            ),
                        ),
                }),
            }),
        );
        assert.strictEqual(outcome.kind, "skipped");
        if (outcome.kind === "skipped") {
            assert.strictEqual(outcome.reason, "no-visible-previews");
            assert.strictEqual(outcome.module, module);
        }
    });
});

describe("loadCachedPreviews — painted outcome", () => {
    it("paints visible previews, posts setPreviews + updateImage, returns the painted set", async () => {
        const module = mod(":app");
        const p1 = preview(
            "com.example.PreviewsKt.RedBoxPreview",
            "src/main/kotlin/com/example/Previews.kt",
        );
        const captured: Captured = {
            messages: [],
            imageSets: [],
            moduleSets: [],
        };
        const outcome = await loadCachedPreviews(
            "/ws/app/src/main/kotlin/com/example/Previews.kt",
            makeDeps(
                {
                    gradleService: fakeGradleService({
                        workspaceRoot: "/ws",
                        resolveModule: () => module,
                        readManifest: () => manifestWith(p1),
                        readPreviewImage: async () => "BASE64IMAGEDATA",
                    }),
                },
                captured,
            ),
        );
        assert.strictEqual(outcome.kind, "painted");
        if (outcome.kind === "painted") {
            assert.strictEqual(outcome.module, module);
            assert.deepStrictEqual(
                outcome.previews.map((p) => p.id),
                [p1.id],
            );
        }
        // The webview gets a setPreviews followed by an updateImage for each capture.
        const commands = captured.messages.map(
            (m) => (m as { command: string }).command,
        );
        assert.deepStrictEqual(commands, ["setPreviews", "updateImage"]);
        // The image registry got the bytes too, and the previewId → module index was
        // populated so subsequent per-preview handlers can route to the right module.
        assert.deepStrictEqual(captured.imageSets, [
            { previewId: p1.id, imageData: "BASE64IMAGEDATA" },
        ]);
        assert.deepStrictEqual(captured.moduleSets, [
            { previewId: p1.id, module },
        ]);
    });

    it("skips updateImage for previews whose render file is unreadable, still paints the rest", async () => {
        // Regression: a cached preview whose PNG was deleted out from under us must not block
        // the rest of the preload. The setPreviews + module-index registration still happens
        // for the unreadable preview; the image just doesn't arrive.
        const module = mod(":app");
        const p1 = preview(
            "com.example.PreviewsKt.RedBoxPreview",
            "src/main/kotlin/com/example/Previews.kt",
        );
        const p2 = preview(
            "com.example.PreviewsKt.BlueBoxPreview",
            "src/main/kotlin/com/example/Previews.kt",
        );
        const captured: Captured = {
            messages: [],
            imageSets: [],
            moduleSets: [],
        };
        await loadCachedPreviews(
            "/ws/app/src/main/kotlin/com/example/Previews.kt",
            makeDeps(
                {
                    gradleService: fakeGradleService({
                        workspaceRoot: "/ws",
                        resolveModule: () => module,
                        readManifest: () => manifestWith(p1, p2),
                        readPreviewImage: async (_module, renderOutput) =>
                            renderOutput.includes("RedBox") ? "REDBYTES" : null,
                    }),
                },
                captured,
            ),
        );
        const updateImageMessages = captured.messages.filter(
            (m) => (m as { command: string }).command === "updateImage",
        );
        assert.strictEqual(updateImageMessages.length, 1);
        assert.strictEqual(captured.imageSets.length, 1);
        // Both previews still got registered with the module index.
        assert.deepStrictEqual(
            captured.moduleSets.map((s) => s.previewId).sort(),
            [p1.id, p2.id].sort(),
        );
    });
});

describe("describePreloadOutcome", () => {
    const file = "/ws/app/src/main/kotlin/Previews.kt";

    it("names the painted preview count and module", () => {
        const module = mod(":app");
        const previews = [
            preview(
                "com.example.RedBoxPreview",
                "src/main/kotlin/com/example/Previews.kt",
            ),
        ];
        const msg = describePreloadOutcome(file, {
            kind: "painted",
            module,
            previews,
        });
        assert.ok(msg.includes("painted 1"), `missing painted count: ${msg}`);
        assert.ok(msg.includes(":app"), `missing module: ${msg}`);
        assert.ok(
            msg.includes(path.basename(file)),
            `missing basename: ${msg}`,
        );
    });

    it("names the skip reason and module when known", () => {
        const module = mod(":app");
        const msg = describePreloadOutcome(file, {
            kind: "skipped",
            reason: "no-visible-previews",
            module,
        });
        assert.ok(
            msg.includes("no-visible-previews"),
            `missing reason: ${msg}`,
        );
        assert.ok(msg.includes(":app"), `missing module: ${msg}`);
    });

    it("omits the module suffix when the skip happened before module resolution", () => {
        const msg = describePreloadOutcome(file, {
            kind: "skipped",
            reason: "no-module",
            module: null,
        });
        assert.ok(msg.includes("no-module"), `missing reason: ${msg}`);
        assert.ok(
            !msg.includes("module="),
            `should not include module= suffix when module is null: ${msg}`,
        );
    });
});
