// Coverage for the minimal PNG+ZIP bundle reader.
//
// We don't have a fixture of a real `composePreviewBundle` output checked
// into the test tree (they're large and tied to a Gradle module), so we
// synthesise tiny polyglots in-memory: a stub PNG followed by a stored
// (uncompressed) zip carrying `bundle.json` + `previews.json` entries.

import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as zlib from "zlib";

import {
    bundleLabel,
    isLikelyBundle,
    readBundleContents,
} from "../bundleFormat";

const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** Build a minimal valid-looking PNG: header + IHDR + IDAT + IEND. The
 *  reader only sniffs the magic, so the chunk content can be a stub. */
function stubPng(): Buffer {
    return Buffer.concat([
        PNG_MAGIC,
        chunk("IHDR", Buffer.alloc(13)),
        chunk("IDAT", Buffer.alloc(8)),
        chunk("IEND", Buffer.alloc(0)),
    ]);
}

function chunk(type: string, data: Buffer): Buffer {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length, 0);
    const typeAndData = Buffer.concat([Buffer.from(type, "ascii"), data]);
    // CRC is not validated by the reader; zero-fill keeps the shape valid.
    const crc = Buffer.alloc(4);
    return Buffer.concat([length, typeAndData, crc]);
}

interface ZipEntry {
    name: string;
    content: Buffer;
    /** 0 = stored, 8 = deflate. Defaults to 0. */
    method?: 0 | 8;
}

/** Build a tiny zip archive with the supplied entries. Stored entries
 *  let us assert on the parser's no-inflate path; deflate entries
 *  exercise `zlib.inflateRawSync`. */
function buildZip(entries: ZipEntry[]): Buffer {
    const localParts: Buffer[] = [];
    const centralParts: Buffer[] = [];
    let offset = 0;
    for (const entry of entries) {
        const method = entry.method ?? 0;
        const nameBytes = Buffer.from(entry.name, "utf-8");
        const uncompressed = entry.content;
        const payload =
            method === 0 ? uncompressed : zlib.deflateRawSync(uncompressed);
        const crc = crc32(uncompressed);
        const lfh = Buffer.alloc(30);
        lfh.writeUInt32LE(0x04034b50, 0);
        lfh.writeUInt16LE(20, 4); // version
        lfh.writeUInt16LE(0, 6); // flags
        lfh.writeUInt16LE(method, 8);
        lfh.writeUInt16LE(0, 10); // mtime
        lfh.writeUInt16LE(0, 12); // mdate
        lfh.writeUInt32LE(crc, 14);
        lfh.writeUInt32LE(payload.length, 18);
        lfh.writeUInt32LE(uncompressed.length, 22);
        lfh.writeUInt16LE(nameBytes.length, 26);
        lfh.writeUInt16LE(0, 28);
        const localBlock = Buffer.concat([lfh, nameBytes, payload]);
        localParts.push(localBlock);

        const cdh = Buffer.alloc(46);
        cdh.writeUInt32LE(0x02014b50, 0);
        cdh.writeUInt16LE(20, 4);
        cdh.writeUInt16LE(20, 6);
        cdh.writeUInt16LE(0, 8);
        cdh.writeUInt16LE(method, 10);
        cdh.writeUInt16LE(0, 12);
        cdh.writeUInt16LE(0, 14);
        cdh.writeUInt32LE(crc, 16);
        cdh.writeUInt32LE(payload.length, 20);
        cdh.writeUInt32LE(uncompressed.length, 24);
        cdh.writeUInt16LE(nameBytes.length, 28);
        cdh.writeUInt16LE(0, 30); // extra
        cdh.writeUInt16LE(0, 32); // comment
        cdh.writeUInt16LE(0, 34); // disk
        cdh.writeUInt16LE(0, 36); // internal attrs
        cdh.writeUInt32LE(0, 38); // external attrs
        cdh.writeUInt32LE(offset, 42); // local header offset
        centralParts.push(Buffer.concat([cdh, nameBytes]));
        offset += localBlock.length;
    }
    const localAll = Buffer.concat(localParts);
    const centralAll = Buffer.concat(centralParts);
    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(0, 4); // disk
    eocd.writeUInt16LE(0, 6); // disk with cd
    eocd.writeUInt16LE(entries.length, 8);
    eocd.writeUInt16LE(entries.length, 10);
    eocd.writeUInt32LE(centralAll.length, 12);
    eocd.writeUInt32LE(localAll.length, 16);
    eocd.writeUInt16LE(0, 20); // comment length
    return Buffer.concat([localAll, centralAll, eocd]);
}

/** Plain CRC-32, IEEE polynomial. Each `buildZip` entry needs one. */
function crc32(buf: Buffer): number {
    let crc = 0xffffffff;
    for (const byte of buf) {
        crc = crc ^ byte;
        for (let i = 0; i < 8; i++) {
            crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
        }
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function writeTempBundle(contents: Buffer, suffix = ".bundle.png"): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "compose-bundle-test-"));
    const file = path.join(dir, "fixture" + suffix);
    fs.writeFileSync(file, contents);
    return file;
}

describe("isLikelyBundle", () => {
    it("returns true for a PNG header followed by a zip EOCD", async () => {
        const file = writeTempBundle(
            Buffer.concat([
                stubPng(),
                buildZip([{ name: "bundle.json", content: Buffer.from("{}") }]),
            ]),
        );
        assert.strictEqual(await isLikelyBundle(file), true);
    });

    it("returns false for a plain PNG (no zip trailer)", async () => {
        const file = writeTempBundle(stubPng());
        assert.strictEqual(await isLikelyBundle(file), false);
    });

    it("returns false for a non-PNG file with a zip trailer", async () => {
        // Same zip body, but no PNG header up front — must not pass.
        const file = writeTempBundle(
            buildZip([{ name: "bundle.json", content: Buffer.from("{}") }]),
        );
        assert.strictEqual(await isLikelyBundle(file), false);
    });

    it("returns false for a file too small to even hold a header", async () => {
        const file = writeTempBundle(Buffer.from([0x89, 0x50]));
        assert.strictEqual(await isLikelyBundle(file), false);
    });

    it("returns false for a missing path", async () => {
        assert.strictEqual(
            await isLikelyBundle("/no/such/path/.bundle.png"),
            false,
        );
    });
});

describe("readBundleContents", () => {
    it("extracts bundle.json and previews.json from a stored-zip polyglot", async () => {
        const manifest = {
            schemaVersion: 1,
            backend: "desktop",
            previewIds: ["com.example.Foo.Bar"],
            coverPreviewId: "com.example.Foo.Bar",
            modulePath: ":sample",
            producedBy: "compose-preview 0.10.0",
        };
        const previews = {
            module: ":sample",
            variant: "debug",
            previews: [
                {
                    id: "com.example.Foo.Bar",
                    functionName: "Bar",
                    className: "com.example.Foo",
                    sourceFile: null,
                    params: { name: null },
                },
            ],
        };
        const file = writeTempBundle(
            Buffer.concat([
                stubPng(),
                buildZip([
                    {
                        name: "bundle.json",
                        content: Buffer.from(JSON.stringify(manifest)),
                    },
                    {
                        name: "previews.json",
                        content: Buffer.from(JSON.stringify(previews)),
                    },
                ]),
            ]),
        );
        const out = await readBundleContents(file);
        assert.deepStrictEqual(out.manifest, manifest);
        assert.deepStrictEqual(out.previews, previews);
    });

    it("decompresses deflate entries (method=8)", async () => {
        const file = writeTempBundle(
            Buffer.concat([
                stubPng(),
                buildZip([
                    {
                        name: "bundle.json",
                        content: Buffer.from(
                            JSON.stringify({ schemaVersion: 1 }),
                        ),
                        method: 8,
                    },
                ]),
            ]),
        );
        const out = await readBundleContents(file);
        assert.deepStrictEqual(out.manifest, { schemaVersion: 1 });
        assert.strictEqual(out.previews, null);
    });

    it("rejects a file that isn't a PNG even if it has a zip trailer", async () => {
        const file = writeTempBundle(
            buildZip([{ name: "bundle.json", content: Buffer.from("{}") }]),
        );
        await assert.rejects(() => readBundleContents(file), /not a PNG/i);
    });

    it("rejects a PNG with no zip EOCD trailer", async () => {
        const file = writeTempBundle(stubPng());
        await assert.rejects(
            () => readBundleContents(file),
            /end-of-central-directory/i,
        );
    });

    it("returns null for a malformed JSON entry rather than throwing", async () => {
        // The reader's contract is "best-effort": a corrupt bundle.json
        // shouldn't block the host from still surfacing previews.json
        // (or vice versa). Either side being null is the caller's signal
        // to fall back to the renderer's own parser.
        const file = writeTempBundle(
            Buffer.concat([
                stubPng(),
                buildZip([
                    {
                        name: "bundle.json",
                        content: Buffer.from("{not valid json"),
                    },
                    {
                        name: "previews.json",
                        content: Buffer.from(JSON.stringify({ previews: [] })),
                    },
                ]),
            ]),
        );
        const out = await readBundleContents(file);
        assert.strictEqual(out.manifest, null);
        assert.deepStrictEqual(out.previews, { previews: [] });
    });
});

describe("bundleLabel", () => {
    it("strips .bundle.png to surface just the slug", () => {
        assert.strictEqual(
            bundleLabel("/tmp/MyPreview.bundle.png"),
            "MyPreview",
        );
    });

    it("strips .png for bundles named without the .bundle infix", () => {
        assert.strictEqual(bundleLabel("/tmp/shared.png"), "shared");
    });

    it("leaves non-png basenames untouched", () => {
        assert.strictEqual(bundleLabel("/tmp/odd.txt"), "odd.txt");
    });
});
