// Minimal reader for the PNG+ZIP polyglot produced by `composePreviewBundle`.
//
// The bundle is a valid PNG (Finder, GitHub, Slack render the cover image)
// whose trailing bytes are a standard zip archive — any tool that walks
// the EOCD signature `PK\x05\x06` reads the inner files. We only need to:
//
// 1. Recognise the file as a bundle (PNG header + EOCD present).
// 2. Pull out `bundle.json` + `previews.json` so the host can title the
//    editor and verify the bundle's backend before spawning the renderer.
//
// The CLI's `BundleReader.kt` does the same job on the JVM side; we keep
// this TypeScript reader scoped to just-enough to surface a useful editor
// title before the heavy `bundle render` subprocess has finished.

import * as fs from "fs";
import * as path from "path";
import * as zlib from "zlib";

const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const EOCD_MAGIC = Buffer.from([0x50, 0x4b, 0x05, 0x06]);
const LOCAL_FILE_HEADER_MAGIC = Buffer.from([0x50, 0x4b, 0x03, 0x04]);
// Cap on bundle file size we'll try to read inline. Real bundles are
// typically 1–20 MB (one classes/app.jar + one cover PNG + manifests);
// 200 MB is a generous ceiling that still rejects "the user dropped a
// 4 GB MP4".
const MAX_BUNDLE_BYTES = 200 * 1024 * 1024;
// EOCD record search window. Spec allows up to 65 535 bytes of comment;
// in practice composePreviewBundle writes no comment and the EOCD is at
// the very end.
const EOCD_SEARCH_BYTES = 64 * 1024;

export interface BundleManifest {
    /** Plugin schema version recorded by `composePreviewBundle`. v1 today. */
    schemaVersion?: number;
    backend?: string;
    previewIds?: string[];
    coverPreviewId?: string | null;
    modulePath?: string;
    producedBy?: string;
}

export interface PreviewParamsLike {
    name?: string | null;
    group?: string | null;
    widthDp?: number | null;
    heightDp?: number | null;
    backgroundColor?: number;
    showBackground?: boolean;
    locale?: string | null;
    fontScale?: number;
    uiMode?: number;
    device?: string | null;
}

export interface BundlePreviewInfo {
    id: string;
    functionName: string;
    className: string;
    sourceFile?: string | null;
    params: PreviewParamsLike;
}

export interface BundlePreviewsManifest {
    module?: string;
    variant?: string;
    previews: BundlePreviewInfo[];
}

export interface BundleContents {
    manifest: BundleManifest | null;
    previews: BundlePreviewsManifest | null;
}

export class BundleFormatError extends Error {
    constructor(
        public readonly file: string,
        message: string,
    ) {
        super(message);
        this.name = "BundleFormatError";
    }
}

/**
 * Quick sniff: does [filePath] look like a `composePreviewBundle`
 * output? Reads only the header bytes + a tail window, so it stays cheap
 * for drag-and-drop hover paths.
 */
export async function isLikelyBundle(filePath: string): Promise<boolean> {
    let fh: fs.promises.FileHandle | null = null;
    try {
        fh = await fs.promises.open(filePath, "r");
        const stat = await fh.stat();
        if (stat.size < PNG_MAGIC.length + EOCD_MAGIC.length) {
            return false;
        }
        const header = Buffer.alloc(PNG_MAGIC.length);
        await fh.read(header, 0, header.length, 0);
        if (!header.equals(PNG_MAGIC)) {
            return false;
        }
        return (await findEocdOffset(fh, stat.size)) !== null;
    } catch {
        return false;
    } finally {
        await fh?.close();
    }
}

/**
 * Reads [filePath] as a PNG+ZIP polyglot and returns the parsed
 * `bundle.json` + `previews.json` entries. Throws [BundleFormatError]
 * if the file isn't a valid bundle (missing PNG header, no EOCD, zip
 * portion unreadable, manifest absent). Either return field may be
 * `null` when the entry was present but unparseable — the caller treats
 * that as "open with the bytes we did get and let the renderer
 * complain", rather than refusing the drop outright.
 */
export async function readBundleContents(
    filePath: string,
): Promise<BundleContents> {
    const stat = await fs.promises.stat(filePath);
    if (stat.size > MAX_BUNDLE_BYTES) {
        throw new BundleFormatError(
            filePath,
            `bundle is ${formatBytes(stat.size)}; refusing to read past ${formatBytes(MAX_BUNDLE_BYTES)}.`,
        );
    }
    if (stat.size < PNG_MAGIC.length + EOCD_MAGIC.length) {
        throw new BundleFormatError(
            filePath,
            "file is too small to be a bundle.",
        );
    }
    let fh: fs.promises.FileHandle | null = null;
    try {
        fh = await fs.promises.open(filePath, "r");
        const header = Buffer.alloc(PNG_MAGIC.length);
        await fh.read(header, 0, header.length, 0);
        if (!header.equals(PNG_MAGIC)) {
            throw new BundleFormatError(
                filePath,
                "file is not a PNG (missing PNG header bytes).",
            );
        }
        const eocdOffset = await findEocdOffset(fh, stat.size);
        if (eocdOffset === null) {
            throw new BundleFormatError(
                filePath,
                "file has a PNG header but no zip end-of-central-directory record — not a bundle.",
            );
        }
        // Read the whole file. Bundles are small (capped at
        // MAX_BUNDLE_BYTES above) and we need the bytes between the
        // first PK\x03\x04 local file header and the EOCD anyway — the
        // PNG portion is a few hundred bytes of header + cover image.
        // `readZipEntries` scans for the local-file-header magic so the
        // PNG prefix is skipped without us needing to compute the exact
        // boundary.
        const zipBytes = await readBytes(fh, 0, stat.size);
        const entries = readZipEntries(zipBytes);
        return {
            manifest: parseJsonEntry<BundleManifest>(entries, "bundle.json"),
            previews: parseJsonEntry<BundlePreviewsManifest>(
                entries,
                "previews.json",
            ),
        };
    } finally {
        await fh?.close();
    }
}

function parseJsonEntry<T>(
    entries: Map<string, Buffer>,
    name: string,
): T | null {
    const raw = entries.get(name);
    if (!raw) return null;
    try {
        return JSON.parse(raw.toString("utf-8")) as T;
    } catch {
        return null;
    }
}

async function findEocdOffset(
    fh: fs.promises.FileHandle,
    fileSize: number,
): Promise<number | null> {
    const window = Math.min(EOCD_SEARCH_BYTES, fileSize);
    const start = fileSize - window;
    const buf = await readBytes(fh, start, window);
    for (let i = buf.length - EOCD_MAGIC.length; i >= 0; i--) {
        if (
            buf[i] === EOCD_MAGIC[0] &&
            buf[i + 1] === EOCD_MAGIC[1] &&
            buf[i + 2] === EOCD_MAGIC[2] &&
            buf[i + 3] === EOCD_MAGIC[3]
        ) {
            return start + i;
        }
    }
    return null;
}

async function readBytes(
    fh: fs.promises.FileHandle,
    offset: number,
    length: number,
): Promise<Buffer> {
    const buf = Buffer.alloc(length);
    const { bytesRead } = await fh.read(buf, 0, length, offset);
    return bytesRead === length ? buf : buf.subarray(0, bytesRead);
}

/**
 * Minimal zip walker: scans for local file headers (`PK\x03\x04`) and
 * inflates each entry. Skips entries whose compressed payload we can't
 * read (zip64, encryption, unsupported compression methods) rather than
 * throwing — the caller only needs `bundle.json` + `previews.json` and
 * the rest of the archive (classes/app.jar, renders/*.png, etc.) is
 * consumed by the JVM-side renderer.
 */
function readZipEntries(zipBytes: Buffer): Map<string, Buffer> {
    const out = new Map<string, Buffer>();
    // Locate the start of the zip portion by scanning for the first
    // local-file-header magic. The polyglot file has PNG bytes before
    // this point.
    let cursor = findLocalFileHeaderStart(zipBytes);
    if (cursor < 0) return out;
    while (cursor + 30 <= zipBytes.length) {
        if (zipBytes.readUInt32LE(cursor) !== 0x04034b50) {
            break;
        }
        const compressionMethod = zipBytes.readUInt16LE(cursor + 8);
        const compressedSize = zipBytes.readUInt32LE(cursor + 18);
        const uncompressedSize = zipBytes.readUInt32LE(cursor + 22);
        const nameLen = zipBytes.readUInt16LE(cursor + 26);
        const extraLen = zipBytes.readUInt16LE(cursor + 28);
        const nameStart = cursor + 30;
        const dataStart = nameStart + nameLen + extraLen;
        if (dataStart + compressedSize > zipBytes.length) break;
        const name = zipBytes.toString("utf-8", nameStart, nameStart + nameLen);
        const payload = zipBytes.subarray(
            dataStart,
            dataStart + compressedSize,
        );
        const inflated = inflate(compressionMethod, payload, uncompressedSize);
        if (inflated) out.set(name, inflated);
        cursor = dataStart + compressedSize;
    }
    return out;
}

function findLocalFileHeaderStart(buf: Buffer): number {
    for (let i = 0; i + 4 <= buf.length; i++) {
        if (
            buf[i] === LOCAL_FILE_HEADER_MAGIC[0] &&
            buf[i + 1] === LOCAL_FILE_HEADER_MAGIC[1] &&
            buf[i + 2] === LOCAL_FILE_HEADER_MAGIC[2] &&
            buf[i + 3] === LOCAL_FILE_HEADER_MAGIC[3]
        ) {
            return i;
        }
    }
    return -1;
}

function inflate(
    method: number,
    payload: Buffer,
    uncompressedSize: number,
): Buffer | null {
    try {
        if (method === 0) {
            return payload.subarray(0, uncompressedSize);
        }
        if (method === 8) {
            return zlib.inflateRawSync(payload);
        }
    } catch {
        return null;
    }
    return null;
}

function formatBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
    if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MiB`;
    return `${(n / 1024 / 1024 / 1024).toFixed(2)} GiB`;
}

/** Short label for a bundle file — the basename minus `.bundle.png` /
 *  `.png` suffix. Used as the editor tab title. */
export function bundleLabel(filePath: string): string {
    const base = path.basename(filePath);
    if (base.endsWith(".bundle.png"))
        return base.slice(0, -".bundle.png".length);
    if (base.endsWith(".png")) return base.slice(0, -".png".length);
    return base;
}
