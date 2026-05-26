import * as assert from "assert";
import {
    describeVerifyResult,
    pngPathFor,
    VerifyDeps,
    verifyConsistency,
} from "../previewConsistency";
import { GradleService, ModuleInfo } from "../gradleService";
import { PreviewInfo, PreviewManifest } from "../types";

const mod = (modulePath: string): ModuleInfo =>
    ({
        modulePath,
        projectDir: modulePath.replace(/^:/, "").replace(/:/g, "/"),
    }) as ModuleInfo;

function preview(id: string, renderOutput: string): PreviewInfo {
    return {
        id,
        functionName: id.split(".").pop()!,
        className: id.substring(0, id.lastIndexOf(".")),
        sourceFile: "src/main/kotlin/Previews.kt",
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
        captures: [{ advanceTimeMillis: null, scroll: null, renderOutput }],
    } as PreviewInfo;
}

function fakeGradleService(opts: {
    workspaceRoot?: string;
    resolveModule?: (filePath: string) => ModuleInfo | null;
    readManifest?: (module: ModuleInfo) => PreviewManifest | null;
}): GradleService {
    return {
        workspaceRoot: opts.workspaceRoot ?? "/ws",
        resolveModule: opts.resolveModule ?? (() => mod(":app")),
        readManifest: opts.readManifest ?? (() => null),
    } as unknown as GradleService;
}

function makeDeps(
    gradle: GradleService,
    registryImages: Map<string, string>,
    diskFiles: Set<string>,
): VerifyDeps {
    return {
        gradleService: gradle,
        registryGetImage: (id) => registryImages.get(id) ?? null,
        fileExists: (filePath) => diskFiles.has(filePath),
    };
}

describe("verifyConsistency — empty / null cases", () => {
    it("returns empty result when filePath is null", () => {
        const result = verifyConsistency(
            null,
            makeDeps(fakeGradleService({}), new Map(), new Set()),
        );
        assert.strictEqual(result.module, null);
        assert.strictEqual(result.manifestCount, 0);
        assert.deepStrictEqual(result.inconsistencies, []);
    });

    it("returns empty result when the file resolves to no module", () => {
        const result = verifyConsistency(
            "/ws/orphan/X.kt",
            makeDeps(
                fakeGradleService({ resolveModule: () => null }),
                new Map(),
                new Set(),
            ),
        );
        assert.strictEqual(result.module, null);
        assert.strictEqual(result.manifestCount, 0);
    });

    it("returns module-only result when the manifest is absent", () => {
        const module = mod(":app");
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    resolveModule: () => module,
                    readManifest: () => null,
                }),
                new Map(),
                new Set(),
            ),
        );
        assert.strictEqual(result.module, module);
        assert.strictEqual(result.manifestCount, 0);
    });
});

describe("verifyConsistency — disagreement detection", () => {
    it("reports disk-has-png-registry-empty when the PNG exists on disk but the registry is missing it", () => {
        // The exact bug the verify command targets — user sees a placeholder card on screen
        // while there's a perfectly good PNG under build/compose-previews/ on disk.
        const module = mod(":app");
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const expectedPath = "/ws/app/build/compose-previews/renders/Red.png";
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () => ({ previews: [p1] }) as PreviewManifest,
                }),
                new Map(), // registry empty
                new Set([expectedPath]), // disk has the PNG
            ),
        );
        assert.strictEqual(result.manifestCount, 1);
        assert.strictEqual(result.diskPngCount, 1);
        assert.strictEqual(result.registryImageCount, 0);
        assert.deepStrictEqual(result.inconsistencies, [
            {
                kind: "disk-has-png-registry-empty",
                previewId: p1.id,
                pngPath: expectedPath,
            },
        ]);
    });

    it("reports no-image-anywhere when neither disk nor registry has bytes for a manifest preview", () => {
        const module = mod(":app");
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () => ({ previews: [p1] }) as PreviewManifest,
                }),
                new Map(), // registry empty
                new Set(), // disk empty
            ),
        );
        assert.strictEqual(result.inconsistencies.length, 1);
        assert.strictEqual(result.inconsistencies[0].kind, "no-image-anywhere");
    });

    it("is silent when the registry has the bytes — stale or fresh, the panel is showing something", () => {
        // Per the user's spec: "we are ok with using stale cached images, as long as they
        // will be replaced once regenerated". The check must NOT flag a registry hit, even
        // if the on-disk PNG is missing.
        const module = mod(":app");
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () => ({ previews: [p1] }) as PreviewManifest,
                }),
                new Map([[p1.id, "BYTES"]]),
                new Set(), // disk missing the PNG
            ),
        );
        assert.deepStrictEqual(result.inconsistencies, []);
        assert.strictEqual(result.registryImageCount, 1);
    });

    it("reports each affected preview independently when several have the same problem", () => {
        const module = mod(":app");
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const p2 = preview("com.example.BluePreview", "renders/Blue.png");
        const p3 = preview("com.example.GreenPreview", "renders/Green.png");
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () =>
                        ({ previews: [p1, p2, p3] }) as PreviewManifest,
                }),
                new Map([[p2.id, "BYTES"]]), // only Blue is in the registry
                new Set([
                    "/ws/app/build/compose-previews/renders/Red.png",
                    "/ws/app/build/compose-previews/renders/Blue.png",
                ]), // Red + Blue on disk, Green missing
            ),
        );
        // Red: disk has, registry doesn't → flagged
        // Blue: both have → consistent
        // Green: neither → no-image-anywhere
        assert.strictEqual(result.inconsistencies.length, 2);
        const flagged = result.inconsistencies.find(
            (i) => i.kind === "disk-has-png-registry-empty",
        );
        const missing = result.inconsistencies.find(
            (i) => i.kind === "no-image-anywhere",
        );
        assert.strictEqual(flagged?.previewId, p1.id);
        assert.strictEqual(missing?.previewId, p3.id);
    });
});

describe("pngPathFor", () => {
    it("uses the first capture's renderOutput under build/compose-previews/", () => {
        const module = mod(":app");
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        assert.strictEqual(
            pngPathFor("/ws", module, p1),
            "/ws/app/build/compose-previews/renders/Red.png",
        );
    });

    it("returns null when the preview has no renderOutput", () => {
        const module = mod(":app");
        const p = {
            ...preview("com.example.Nope", "x.png"),
            captures: [{ advanceTimeMillis: null, scroll: null }],
        } as unknown as PreviewInfo;
        assert.strictEqual(pngPathFor("/ws", module, p), null);
    });
});

describe("describeVerifyResult", () => {
    it("reports the consistent case explicitly", () => {
        const msg = describeVerifyResult("/ws/app/Previews.kt", {
            module: mod(":app"),
            manifestCount: 4,
            diskPngCount: 4,
            registryImageCount: 4,
            inconsistencies: [],
        });
        assert.ok(msg.includes("consistent"), msg);
        assert.ok(msg.includes("manifest=4"), msg);
    });

    it("counts placeholder and never-rendered separately", () => {
        const msg = describeVerifyResult("/ws/app/Previews.kt", {
            module: mod(":app"),
            manifestCount: 3,
            diskPngCount: 2,
            registryImageCount: 1,
            inconsistencies: [
                {
                    kind: "disk-has-png-registry-empty",
                    previewId: "p1",
                    pngPath: "/x",
                },
                {
                    kind: "no-image-anywhere",
                    previewId: "p2",
                    expectedPngPath: "/y",
                },
            ],
        });
        assert.ok(msg.includes("1 placeholder"), msg);
        assert.ok(msg.includes("1 never-rendered"), msg);
    });
});
