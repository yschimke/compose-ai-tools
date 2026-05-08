import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { moduleRelativeSourcePath, previewSourceMatches } from "../sourcePath";

function withTempSource(
    fn: (
        workspaceRoot: string,
        module: { projectDir: string; modulePath: string },
        filePath: string,
    ) => void,
): void {
    const workspaceRoot = fs.mkdtempSync(
        path.join(os.tmpdir(), "compose-preview-source-path-"),
    );
    try {
        const module = { projectDir: "wear", modulePath: ":wear" };
        const filePath = path.join(
            workspaceRoot,
            module.projectDir,
            "src",
            "main",
            "kotlin",
            "ee",
            "schimke",
            "meshcore",
            "wear",
            "ui",
            "WearPreviews.kt",
        );
        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        fs.writeFileSync(
            filePath,
            "package ee.schimke.meshcore.wear.ui\n\n@Preview fun Example() {}",
        );
        fn(workspaceRoot, module, filePath);
    } finally {
        fs.rmSync(workspaceRoot, { recursive: true, force: true });
    }
}

describe("sourcePath", () => {
    it("uses module-relative source paths as the canonical preview key", () => {
        withTempSource((workspaceRoot, module, filePath) => {
            assert.strictEqual(
                moduleRelativeSourcePath(filePath, workspaceRoot, module),
                "src/main/kotlin/ee/schimke/meshcore/wear/ui/WearPreviews.kt",
            );
        });
    });

    it("matches current and legacy preview sourceFile shapes", () => {
        withTempSource((workspaceRoot, module, filePath) => {
            for (const sourceFile of [
                "src/main/kotlin/ee/schimke/meshcore/wear/ui/WearPreviews.kt",
                "ee/schimke/meshcore/wear/ui/WearPreviews.kt",
                "WearPreviews.kt",
            ]) {
                assert.strictEqual(
                    previewSourceMatches(
                        sourceFile,
                        filePath,
                        workspaceRoot,
                        module,
                    ),
                    true,
                    sourceFile,
                );
            }
        });
    });
});
