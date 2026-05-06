import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { partitionPreviewsForFile } from "../previewScope";
import { PreviewInfo } from "../types";

function makePreview(
    overrides: Partial<PreviewInfo> & Pick<PreviewInfo, "id">,
): PreviewInfo {
    return {
        functionName: "Stub",
        className: "test.PreviewsKt",
        sourceFile: null,
        params: {
            name: null,
            device: null,
            widthDp: null,
            heightDp: null,
            fontScale: 1.0,
            showSystemUi: false,
            showBackground: false,
            backgroundColor: 0,
            uiMode: 0,
            locale: null,
            group: null,
        },
        captures: [],
        ...overrides,
    };
}

function withTempProject(
    fn: (workspaceRoot: string, module: string, homeScreenFile: string) => void,
): void {
    const workspaceRoot = fs.mkdtempSync(
        path.join(os.tmpdir(), "compose-preview-scope-"),
    );
    try {
        const module = "app";
        const homeScreenFile = path.join(
            workspaceRoot,
            module,
            "src",
            "main",
            "kotlin",
            "test",
            "HomeScreen.kt",
        );
        fs.mkdirSync(path.dirname(homeScreenFile), { recursive: true });
        // Real package declaration so packageQualifiedSourcePath resolves the
        // same shape the manifest stores.
        fs.writeFileSync(homeScreenFile, "package test\n");
        fn(workspaceRoot, module, homeScreenFile);
    } finally {
        fs.rmSync(workspaceRoot, { recursive: true, force: true });
    }
}

describe("partitionPreviewsForFile", () => {
    it("returns previews authored in the active file as primary", () => {
        withTempProject((workspaceRoot, module, file) => {
            const inFile = makePreview({
                id: "p1",
                functionName: "PreviewA",
                sourceFile: "src/main/kotlin/test/HomeScreen.kt",
            });
            const elsewhere = makePreview({
                id: "p2",
                sourceFile: "src/main/kotlin/test/Other.kt",
            });
            const { primary, referenced } = partitionPreviewsForFile(
                [inFile, elsewhere],
                workspaceRoot,
                module,
                file,
            );
            assert.deepStrictEqual(
                primary.map((p) => p.id),
                ["p1"],
            );
            assert.deepStrictEqual(referenced, []);
        });
    });

    it("surfaces previews from other files whose target points at the active file", () => {
        withTempProject((workspaceRoot, module, file) => {
            const targeting = makePreview({
                id: "p2",
                functionName: "HomeScreenPreview",
                sourceFile: "src/main/kotlin/test/Previews.kt",
                targets: [
                    {
                        className: "test.HomeScreenKt",
                        functionName: "HomeScreen",
                        sourceFile: "src/main/kotlin/test/HomeScreen.kt",
                        confidence: "HIGH",
                        signals: ["NAME_MATCH", "CROSS_FILE"],
                    },
                ],
            });
            const unrelated = makePreview({
                id: "p3",
                sourceFile: "src/main/kotlin/test/Other.kt",
                targets: [
                    {
                        className: "test.OtherKt",
                        functionName: "Other",
                        sourceFile: "src/main/kotlin/test/Other.kt",
                        confidence: "HIGH",
                    },
                ],
            });
            const { primary, referenced } = partitionPreviewsForFile(
                [targeting, unrelated],
                workspaceRoot,
                module,
                file,
            );
            assert.deepStrictEqual(primary, []);
            assert.deepStrictEqual(
                referenced.map((p) => p.id),
                ["p2"],
            );
            assert.strictEqual(referenced[0].referenced, true);
        });
    });

    it("does not double-list a preview that is both in the file and targets it", () => {
        withTempProject((workspaceRoot, module, file) => {
            // Edge case: a preview authored in HomeScreen.kt that also targets
            // a composable in HomeScreen.kt — should only show up once, in
            // the primary list.
            const both = makePreview({
                id: "p1",
                functionName: "InlinePreview",
                sourceFile: "src/main/kotlin/test/HomeScreen.kt",
                targets: [
                    {
                        className: "test.HomeScreenKt",
                        functionName: "HomeScreen",
                        sourceFile: "src/main/kotlin/test/HomeScreen.kt",
                        confidence: "HIGH",
                    },
                ],
            });
            const { primary, referenced } = partitionPreviewsForFile(
                [both],
                workspaceRoot,
                module,
                file,
            );
            assert.deepStrictEqual(
                primary.map((p) => p.id),
                ["p1"],
            );
            assert.deepStrictEqual(referenced, []);
            // The primary copy should not have `referenced` set.
            assert.strictEqual(primary[0].referenced, undefined);
        });
    });

    it("skips previews with no targets", () => {
        withTempProject((workspaceRoot, module, file) => {
            const sibling = makePreview({
                id: "p2",
                sourceFile: "src/main/kotlin/test/Other.kt",
                // No targets at all — discovery couldn't pin one. Don't surface.
                targets: undefined,
            });
            const { primary, referenced } = partitionPreviewsForFile(
                [sibling],
                workspaceRoot,
                module,
                file,
            );
            assert.deepStrictEqual(primary, []);
            assert.deepStrictEqual(referenced, []);
        });
    });
});
