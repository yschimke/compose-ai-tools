import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { CurrentRendersHistory } from "../daemon/currentRendersHistory";

interface ManifestPreview {
    id: string;
    sourceFile?: string | null;
    captures: { renderOutput: string }[];
}

function withFixture<T>(
    previews: ManifestPreview[],
    pngFiles: Record<string, string>,
    fn: (buildDir: string) => T | Promise<T>,
): () => Promise<T> {
    return async () => {
        const root = fs.mkdtempSync(path.join(os.tmpdir(), "current-renders-"));
        try {
            const buildDir = path.join(root, "build", "compose-previews");
            fs.mkdirSync(path.join(buildDir, "renders"), { recursive: true });
            fs.writeFileSync(
                path.join(buildDir, "previews.json"),
                JSON.stringify({ module: "test", variant: "debug", previews }),
            );
            for (const [rel, body] of Object.entries(pngFiles)) {
                const full = path.join(buildDir, rel);
                fs.mkdirSync(path.dirname(full), { recursive: true });
                fs.writeFileSync(full, body);
            }
            return await fn(buildDir);
        } finally {
            fs.rmSync(root, { recursive: true });
        }
    };
}

const PREVIEWS: ManifestPreview[] = [
    {
        id: "com.example.A",
        sourceFile: "com/example/Previews.kt",
        captures: [{ renderOutput: "renders/com.example.A.png" }],
    },
    {
        id: "com.example.B",
        sourceFile: "com/example/Previews.kt",
        captures: [{ renderOutput: "renders/com.example.B.png" }],
    },
];

const PNGS = {
    "renders/com.example.A.png": "png-A",
    "renders/com.example.B.png": "png-B",
};

describe("CurrentRendersHistory", () => {
    it(
        "returns one synthetic entry per preview with a render on disk",
        withFixture(PREVIEWS, PNGS, (buildDir) => {
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            const result = reader.list();
            assert.strictEqual(result.totalCount, 2);
            const ids = result.entries
                .map((e) => (e as { previewId: string }).previewId)
                .sort();
            assert.deepStrictEqual(ids, ["com.example.A", "com.example.B"]);
        }),
    );

    it(
        "honours the previewId filter",
        withFixture(PREVIEWS, PNGS, (buildDir) => {
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            const result = reader.list("com.example.A");
            assert.strictEqual(result.totalCount, 1);
            assert.strictEqual(
                (result.entries[0] as { previewId: string }).previewId,
                "com.example.A",
            );
        }),
    );

    it(
        "skips previews whose render file is missing on disk",
        withFixture(
            PREVIEWS,
            { "renders/com.example.A.png": "p" },
            (buildDir) => {
                const reader = new CurrentRendersHistory({
                    buildDir,
                    moduleId: "sample",
                });
                const result = reader.list();
                assert.strictEqual(result.totalCount, 1);
                assert.strictEqual(
                    (result.entries[0] as { previewId: string }).previewId,
                    "com.example.A",
                );
            },
        ),
    );

    it("returns empty list when previews.json is missing", async () => {
        const root = fs.mkdtempSync(
            path.join(os.tmpdir(), "current-renders-empty-"),
        );
        try {
            const buildDir = path.join(root, "build", "compose-previews");
            fs.mkdirSync(buildDir, { recursive: true });
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            const result = reader.list();
            assert.strictEqual(result.totalCount, 0);
            assert.strictEqual(result.entries.length, 0);
        } finally {
            fs.rmSync(root, { recursive: true });
        }
    });

    it(
        "round-trips list ids through read",
        withFixture(PREVIEWS, PNGS, (buildDir) => {
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            const list = reader.list();
            const id = (list.entries[0] as { id: string }).id;
            assert.ok(
                CurrentRendersHistory.isSyntheticId(id),
                `expected synthetic id, got ${id}`,
            );
            const read = reader.read(id);
            assert.notStrictEqual(read, null);
            assert.ok(
                fs.existsSync(read!.pngPath),
                `png missing: ${read!.pngPath}`,
            );
        }),
    );

    it(
        "read returns null for non-synthetic ids",
        withFixture(PREVIEWS, PNGS, (buildDir) => {
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            assert.strictEqual(reader.read("20260430-aaaaaaaa"), null);
        }),
    );

    it(
        "read returns null for malformed synthetic ids",
        withFixture(PREVIEWS, PNGS, (buildDir) => {
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            assert.strictEqual(
                reader.read("current:not-base64-of-anything-known"),
                null,
            );
        }),
    );

    it(
        "orders entries newest-first by mtime",
        withFixture(PREVIEWS, PNGS, async (buildDir) => {
            // Backdate B so A is newer.
            const past = new Date(Date.now() - 60_000);
            fs.utimesSync(
                path.join(buildDir, "renders/com.example.B.png"),
                past,
                past,
            );
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample",
            });
            const ids = reader
                .list()
                .entries.map((e) => (e as { previewId: string }).previewId);
            assert.deepStrictEqual(ids, ["com.example.A", "com.example.B"]);
        }),
    );

    it(
        "tags entries with the moduleId so the panel scope filter matches",
        withFixture(PREVIEWS, PNGS, (buildDir) => {
            const reader = new CurrentRendersHistory({
                buildDir,
                moduleId: "sample/wear",
            });
            const result = reader.list();
            for (const entry of result.entries) {
                assert.strictEqual(
                    (entry as { module: string }).module,
                    "sample/wear",
                );
            }
        }),
    );
});
