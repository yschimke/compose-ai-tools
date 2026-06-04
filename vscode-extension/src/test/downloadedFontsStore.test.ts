// Pins the downloaded-fonts store add/list/remove cycle against an
// in-memory StorageFs fake.

import * as assert from "assert";
import * as path from "path";
import {
    DownloadedFontsStore,
    faceFileName,
    slugFamily,
    type StorageFs,
} from "../downloadedFontsStore";
import type { Css2Face } from "../googleFontsClient";

class FakeFs implements StorageFs {
    files = new Map<string, string | Uint8Array>();
    dirs = new Set<string>();

    async mkdirp(dir: string): Promise<void> {
        this.dirs.add(dir);
    }
    async readText(file: string): Promise<string> {
        const v = this.files.get(file);
        if (v == null) throw new Error("ENOENT " + file);
        return typeof v === "string" ? v : Buffer.from(v).toString("utf8");
    }
    async writeText(file: string, text: string): Promise<void> {
        this.files.set(file, text);
    }
    async writeBytes(file: string, data: Uint8Array): Promise<void> {
        this.files.set(file, data);
    }
    async remove(target: string): Promise<void> {
        for (const key of [...this.files.keys()]) {
            if (key === target || key.startsWith(target + path.sep)) {
                this.files.delete(key);
            }
        }
        this.dirs.delete(target);
    }
    async exists(target: string): Promise<boolean> {
        if (this.files.has(target) || this.dirs.has(target)) return true;
        for (const key of this.files.keys()) {
            if (key.startsWith(target + path.sep)) return true;
        }
        return false;
    }
}

function face(overrides: Partial<Css2Face> = {}): Css2Face {
    return {
        style: "normal",
        weightMin: 400,
        weightMax: 400,
        url: "https://fonts.gstatic.com/x.woff2",
        format: "woff2",
        subset: "latin",
        ...overrides,
    };
}

describe("downloadedFontsStore", () => {
    it("slugs family names", () => {
        assert.strictEqual(slugFamily("Open Sans"), "open-sans");
        assert.strictEqual(slugFamily("IBM Plex Mono"), "ibm-plex-mono");
        assert.strictEqual(slugFamily("!!!"), "font");
    });

    it("names face files by style and weight range", () => {
        assert.strictEqual(
            faceFileName(
                "roboto",
                face({ style: "normal", weightMin: 400, weightMax: 400 }),
            ),
            "roboto-normal-400.woff2",
        );
        assert.strictEqual(
            faceFileName(
                "roboto-flex",
                face({ style: "italic", weightMin: 100, weightMax: 900 }),
            ),
            "roboto-flex-italic-100_900.woff2",
        );
    });

    it("returns an empty list when nothing has been downloaded", async () => {
        const store = new DownloadedFontsStore("/root", new FakeFs());
        assert.deepStrictEqual(await store.list(), []);
    });

    it("adds a font, writing files and a manifest entry", async () => {
        const fs = new FakeFs();
        const store = new DownloadedFontsStore("/root", fs);
        const record = await store.add({
            family: "Roboto",
            category: "Sans Serif",
            isVariable: false,
            axes: [],
            faces: [
                { face: face({ style: "normal" }), bytes: new Uint8Array([1]) },
                {
                    face: face({ style: "italic", url: "https://x/i.woff2" }),
                    bytes: new Uint8Array([2]),
                },
            ],
        });
        assert.strictEqual(record.familyId, "roboto");
        assert.strictEqual(record.faces.length, 2);

        const listed = await store.list();
        assert.strictEqual(listed.length, 1);
        assert.strictEqual(listed[0].family, "Roboto");

        // Files were written under the family dir.
        assert.ok(fs.files.has(store.facePath(record, record.faces[0])));
        assert.ok(fs.files.has(store.facePath(record, record.faces[1])));
        assert.ok(await store.has("roboto"));
    });

    it("replaces an existing family rather than duplicating it", async () => {
        const store = new DownloadedFontsStore("/root", new FakeFs());
        const input = {
            family: "Lora",
            category: "Serif",
            isVariable: false,
            axes: [],
            faces: [{ face: face(), bytes: new Uint8Array([1]) }],
        };
        await store.add(input);
        await store.add(input);
        assert.strictEqual((await store.list()).length, 1);
    });

    it("removes a font and its files", async () => {
        const fs = new FakeFs();
        const store = new DownloadedFontsStore("/root", fs);
        const record = await store.add({
            family: "Roboto",
            category: "Sans Serif",
            isVariable: false,
            axes: [],
            faces: [{ face: face(), bytes: new Uint8Array([1]) }],
        });
        const filePath = store.facePath(record, record.faces[0]);
        assert.ok(fs.files.has(filePath));

        await store.remove("roboto");
        assert.deepStrictEqual(await store.list(), []);
        assert.ok(!fs.files.has(filePath));
    });

    it("survives a corrupt manifest", async () => {
        const fs = new FakeFs();
        const store = new DownloadedFontsStore("/root", fs);
        fs.files.set(path.join("/root", "fonts", "index.json"), "not json{");
        assert.deepStrictEqual(await store.list(), []);
    });
});
