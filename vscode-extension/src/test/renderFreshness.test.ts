import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    hasFreshRenderStamp,
    renderFreshnessStampPath,
    writeRenderFreshnessStamp,
} from "../renderFreshness";
import { PreviewInfo } from "../types";

function preview(id: string): PreviewInfo {
    return {
        id,
        functionName: "Preview",
        className: "com.example.PreviewsKt",
        sourceFile: "com/example/Previews.kt",
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
    };
}

async function withTempDir(fn: (dir: string) => Promise<void>): Promise<void> {
    const dir = fs.mkdtempSync(
        path.join(os.tmpdir(), "compose-preview-freshness-"),
    );
    try {
        await fn(dir);
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

describe("renderFreshness", () => {
    it("treats a stamped render as fresh even when PNG mtimes are older than the source", async () => {
        await withTempDir(async (dir) => {
            const module = { projectDir: "app", modulePath: ":app" };
            const source = path.join(
                dir,
                module.projectDir,
                "src/main/kotlin/com/example/Previews.kt",
            );
            const png = path.join(
                dir,
                module.projectDir,
                "build/compose-previews/renders/p1.png",
            );
            fs.mkdirSync(path.dirname(source), { recursive: true });
            fs.mkdirSync(path.dirname(png), { recursive: true });
            fs.writeFileSync(source, "@Preview fun P() {}");
            fs.writeFileSync(png, "png");

            const old = new Date(Date.now() - 60_000);
            fs.utimesSync(png, old, old);
            await writeRenderFreshnessStamp(dir, module, source, [
                preview("p1"),
            ]);

            assert.strictEqual(
                await hasFreshRenderStamp(dir, module, source, [preview("p1")]),
                true,
            );
        });
    });

    it("marks the render stale when the source changes after the stamp", async () => {
        await withTempDir(async (dir) => {
            const module = { projectDir: "app", modulePath: ":app" };
            const source = path.join(
                dir,
                module.projectDir,
                "src/main/kotlin/com/example/Previews.kt",
            );
            fs.mkdirSync(path.dirname(source), { recursive: true });
            fs.writeFileSync(source, "@Preview fun P() {}");
            await writeRenderFreshnessStamp(dir, module, source, [
                preview("p1"),
            ]);

            const newer = new Date(Date.now() + 5_000);
            fs.utimesSync(source, newer, newer);

            assert.strictEqual(
                await hasFreshRenderStamp(dir, module, source, [preview("p1")]),
                false,
            );
        });
    });

    it("marks the render stale when the visible preview set is not covered by the stamp", async () => {
        await withTempDir(async (dir) => {
            const module = { projectDir: "app", modulePath: ":app" };
            const source = path.join(
                dir,
                module.projectDir,
                "src/main/kotlin/com/example/Previews.kt",
            );
            fs.mkdirSync(path.dirname(source), { recursive: true });
            fs.writeFileSync(source, "@Preview fun P() {}");
            await writeRenderFreshnessStamp(dir, module, source, [
                preview("p1"),
            ]);

            assert.strictEqual(
                await hasFreshRenderStamp(dir, module, source, [
                    preview("p1"),
                    preview("p2"),
                ]),
                false,
            );
        });
    });

    it("stores source-specific stamps under the module build directory", async () => {
        await withTempDir(async (dir) => {
            const stampPath = renderFreshnessStampPath(
                dir,
                { projectDir: "app", modulePath: ":app" },
                path.join(dir, "app/src/Previews.kt"),
            );
            assert.ok(
                stampPath.startsWith(
                    path.join(dir, "app", "build", "compose-previews"),
                ),
            );
            assert.ok(stampPath.endsWith(".json"));
        });
    });
});
