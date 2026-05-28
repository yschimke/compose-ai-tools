import * as assert from "assert";
import {
    describeVerifyResult,
    manifestExpectedFilesMissing,
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
        listFilesUnder: (dir) => {
            const prefix = dir.endsWith("/") ? dir : dir + "/";
            const out: string[] = [];
            for (const f of diskFiles) {
                if (f.startsWith(prefix)) out.push(f.substring(prefix.length));
            }
            return out;
        },
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

    it("resolves to the scroll data-product output for @ScrollingPreview(LONG)", () => {
        // The static base PNG the daemon writes (`renders/X.png`) is dropped by the panel
        // — `withDataProductCaptures` replaces it with `data/render-scroll-long/X.png` so
        // verify must check the data-product slot, not the daemon's static representative.
        // Without this, missing scroll PNGs are misreported as "stale placeholder with PNG
        // on disk" because the daemon's discarded base PNG happens to exist.
        const module = mod(":app");
        const p = {
            ...preview("com.example.LongScroll", "renders/LongScroll.png"),
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: {
                        mode: "LONG",
                        axis: "VERTICAL",
                        maxScrollPx: 0,
                        reduceMotion: false,
                        atEnd: false,
                        reachedPx: null,
                    },
                    output: "data/render-scroll-long/LongScroll.png",
                },
            ],
        } as unknown as PreviewInfo;
        assert.strictEqual(
            pngPathFor("/ws", module, p),
            "/ws/app/build/compose-previews/data/render-scroll-long/LongScroll.png",
        );
    });
});

describe("verifyConsistency — scroll/data-product previews", () => {
    it("reports no-image-anywhere when the scroll PNG is missing even if the daemon's static base PNG sits on disk", () => {
        // Real-world repro from a Wear sample: 8 stuck placeholders, all scroll-long/gif.
        // Pre-fix the verify counted the daemon's static `renders/<id>.png` as on-disk and
        // reported "stale placeholder with PNG on disk" — but the panel ignores that PNG
        // and was waiting for `data/render-scroll-long/<id>.png`, which never arrives in a
        // daemon-only flow. After the fold, verify checks the correct slot.
        const module = mod(":wear");
        const p = {
            ...preview(
                "com.example.ActivityListLongPreview",
                "renders/ActivityListLongPreview.png",
            ),
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: {
                        mode: "LONG",
                        axis: "VERTICAL",
                        maxScrollPx: 0,
                        reduceMotion: false,
                        atEnd: false,
                        reachedPx: null,
                    },
                    output: "data/render-scroll-long/ActivityListLongPreview.png",
                },
            ],
        } as unknown as PreviewInfo;
        const result = verifyConsistency(
            "/ws/wear/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () => ({ previews: [p] }) as PreviewManifest,
                }),
                new Map(),
                // Daemon's static base PNG happens to exist on disk; the scroll
                // data-product PNG does not. Pre-fix this misreported as a stale
                // placeholder; post-fix verify checks the scroll slot and reports
                // the truthful "never rendered" state.
                new Set([
                    "/ws/wear/build/compose-previews/renders/ActivityListLongPreview.png",
                ]),
            ),
        );
        assert.strictEqual(result.inconsistencies.length, 1);
        assert.strictEqual(result.inconsistencies[0].kind, "no-image-anywhere");
        assert.strictEqual(result.diskPngCount, 0);
    });
});

describe("describeVerifyResult", () => {
    it("reports the consistent case explicitly", () => {
        const msg = describeVerifyResult("/ws/app/Previews.kt", {
            module: mod(":app"),
            manifestCount: 4,
            diskPngCount: 4,
            registryImageCount: 4,
            diskFileCount: 4,
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
            diskFileCount: 2,
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

    it("calls out renamed-on-disk + extra-file-on-disk in the summary", () => {
        const msg = describeVerifyResult("/ws/app/Previews.kt", {
            module: mod(":app"),
            manifestCount: 2,
            diskPngCount: 0,
            registryImageCount: 0,
            diskFileCount: 3,
            inconsistencies: [
                {
                    kind: "renamed-on-disk",
                    previewId: "p1",
                    expectedPngPath:
                        "/ws/app/build/compose-previews/renders/Foo_Bar.png",
                    actualPath:
                        "/ws/app/build/compose-previews/renders/Foo Bar.png",
                },
                {
                    kind: "extra-file-on-disk",
                    path: "/ws/app/build/compose-previews/renders/Orphan.png",
                },
                {
                    kind: "extra-file-on-disk",
                    path: "/ws/app/build/compose-previews/renders/Older.png",
                },
            ],
        });
        assert.ok(msg.includes("1 renamed on disk"), msg);
        assert.ok(msg.includes("2 extra file(s) on disk"), msg);
        assert.ok(msg.includes("diskFiles=3"), msg);
    });
});

describe("verifyConsistency — extra + renamed files on disk", () => {
    it("pairs an expected-but-missing PNG with a same-directory file whose stem fingerprints match", () => {
        // Real-world repro from #1530 aftermath: the discover task came back FROM-CACHE with
        // the new sanitiser shape (`Foo_Bar.png`), but `composePreviewRender` never ran under
        // the new schema, so disk still holds the old shape (`Foo Bar.png`). Verify must
        // pair them as `renamed-on-disk` so the user sees "your renders are stale — re-render"
        // instead of "21 never-rendered" with extras dangling unexplained.
        const module = mod(":app");
        const p1 = preview("com.example.FooBarPreview", "renders/Foo_Bar.png");
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () => ({ previews: [p1] }) as PreviewManifest,
                }),
                new Map(),
                new Set(["/ws/app/build/compose-previews/renders/Foo Bar.png"]),
            ),
        );
        assert.strictEqual(result.inconsistencies.length, 1);
        const issue = result.inconsistencies[0];
        assert.strictEqual(issue.kind, "renamed-on-disk");
        if (issue.kind === "renamed-on-disk") {
            assert.strictEqual(issue.previewId, p1.id);
            assert.strictEqual(
                issue.actualPath,
                "/ws/app/build/compose-previews/renders/Foo Bar.png",
            );
            assert.strictEqual(
                issue.expectedPngPath,
                "/ws/app/build/compose-previews/renders/Foo_Bar.png",
            );
        }
    });

    it("flags files on disk that no manifest entry points at", () => {
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
                new Set([
                    "/ws/app/build/compose-previews/renders/Red.png",
                    "/ws/app/build/compose-previews/renders/Orphan.png",
                ]),
            ),
        );
        assert.strictEqual(result.inconsistencies.length, 1);
        const issue = result.inconsistencies[0];
        assert.strictEqual(issue.kind, "extra-file-on-disk");
        if (issue.kind === "extra-file-on-disk") {
            assert.strictEqual(
                issue.path,
                "/ws/app/build/compose-previews/renders/Orphan.png",
            );
        }
    });

    it("doesn't flag previews.json or sibling top-level json sidecars as extras", () => {
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
                new Set([
                    "/ws/app/build/compose-previews/renders/Red.png",
                    "/ws/app/build/compose-previews/previews.json",
                    "/ws/app/build/compose-previews/a11y-report.json",
                ]),
            ),
        );
        assert.deepStrictEqual(result.inconsistencies, []);
    });

    it("doesn't double-count a renamed file as both extra and renamed", () => {
        const module = mod(":app");
        const p1 = preview("com.example.FooBarPreview", "renders/Foo_Bar.png");
        const result = verifyConsistency(
            "/ws/app/Previews.kt",
            makeDeps(
                fakeGradleService({
                    workspaceRoot: "/ws",
                    resolveModule: () => module,
                    readManifest: () => ({ previews: [p1] }) as PreviewManifest,
                }),
                new Map(),
                new Set(["/ws/app/build/compose-previews/renders/Foo Bar.png"]),
            ),
        );
        const kinds = result.inconsistencies.map((i) => i.kind).sort();
        assert.deepStrictEqual(kinds, ["renamed-on-disk"]);
    });
});

describe("manifestExpectedFilesMissing", () => {
    const module = mod(":app");

    it("returns false when every capture's renderOutput exists on disk", () => {
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const p2 = preview("com.example.BluePreview", "renders/Blue.png");
        const manifest = { previews: [p1, p2] } as PreviewManifest;
        const disk = new Set([
            "/ws/app/build/compose-previews/renders/Red.png",
            "/ws/app/build/compose-previews/renders/Blue.png",
        ]);
        assert.strictEqual(
            manifestExpectedFilesMissing("/ws", module, manifest, (p) =>
                disk.has(p),
            ),
            false,
        );
    });

    it("returns true when a capture's renderOutput is missing", () => {
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const p2 = preview("com.example.BluePreview", "renders/Blue.png");
        const manifest = { previews: [p1, p2] } as PreviewManifest;
        const disk = new Set([
            "/ws/app/build/compose-previews/renders/Red.png",
            // Blue.png missing — the drift signal we want to surface.
        ]);
        assert.strictEqual(
            manifestExpectedFilesMissing("/ws", module, manifest, (p) =>
                disk.has(p),
            ),
            true,
        );
    });

    it("returns true when a data-product output is missing even if the static capture exists", () => {
        // The scenario from the original drift report: discover returned a manifest with new-
        // shape data-product paths under `data/render-scroll-long/`, but the renderer never
        // ran under that shape so the directory is empty. The static base PNG happens to
        // exist (carried over from an older render); the panel ignores it and waits for the
        // scroll product that will never arrive.
        const p = {
            ...preview("com.example.LongScroll", "renders/LongScroll.png"),
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: {
                        mode: "LONG",
                        axis: "VERTICAL",
                        maxScrollPx: 0,
                        reduceMotion: false,
                        atEnd: false,
                        reachedPx: null,
                    },
                    output: "data/render-scroll-long/LongScroll.png",
                },
            ],
        } as unknown as PreviewInfo;
        const disk = new Set([
            "/ws/app/build/compose-previews/renders/LongScroll.png",
        ]);
        assert.strictEqual(
            manifestExpectedFilesMissing(
                "/ws",
                module,
                { previews: [p] } as PreviewManifest,
                (path) => disk.has(path),
            ),
            true,
        );
    });

    it("short-circuits on the first preview that has neither PNG nor sidecar", () => {
        // The check fires on every refresh, so the cheap case (everything present) must stay
        // O(1) and the expensive case (drift) must not pay for files beyond the first miss.
        // After the sidecar carve-out a missing entry costs two probes (PNG + sidecar) before
        // we declare drift, but the rest of the manifest is still skipped.
        const previews = Array.from({ length: 100 }, (_, i) =>
            preview(`com.example.P${i}`, `renders/P${i}.png`),
        );
        let calls = 0;
        manifestExpectedFilesMissing(
            "/ws",
            module,
            { previews } as PreviewManifest,
            () => {
                calls++;
                return false;
            },
        );
        assert.strictEqual(calls, 2);
    });

    it("treats `<png>.error.json` sidecar as satisfaction so a broken preview doesn't loop", () => {
        // Repro of the wedge the Confetti :wearApp report hit: 5 ThemePreviews threw inside
        // their composables, the renderer wrote .error.json sidecars and skipped the PNGs.
        // Without this carve-out, every focus event sees "manifest references PNG missing on
        // disk" → escalates to composePreviewRender → render fails the same way → infinite
        // retry loop. The sidecar means "we tried, this preview is in a known bad state until
        // its source moves" — the per-file freshness stamp opens the retry window again when
        // the user edits the file.
        const p1 = preview("com.example.RedPreview", "renders/Red.png");
        const p2 = preview("com.example.BluePreview", "renders/Blue.png");
        const manifest = { previews: [p1, p2] } as PreviewManifest;
        const disk = new Set([
            "/ws/app/build/compose-previews/renders/Red.png",
            // Blue.png absent, but the renderer left a structured error sidecar:
            "/ws/app/build/compose-previews/renders/Blue.png.error.json",
        ]);
        assert.strictEqual(
            manifestExpectedFilesMissing("/ws", module, manifest, (p) =>
                disk.has(p),
            ),
            false,
        );
    });

    it("still reports drift when neither PNG nor sidecar is on disk", () => {
        // Guard against the carve-out swallowing the "discover-only, never rendered" case.
        // That's the original drift signal — wiped `build/`, branch switch, sanitiser bump —
        // and we still want to escalate to render there.
        const p = preview("com.example.RedPreview", "renders/Red.png");
        const manifest = { previews: [p] } as PreviewManifest;
        const disk = new Set<string>(); // nothing on disk
        assert.strictEqual(
            manifestExpectedFilesMissing("/ws", module, manifest, (path) =>
                disk.has(path),
            ),
            true,
        );
    });
});
