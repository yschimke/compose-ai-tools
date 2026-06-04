// Persistent store for fonts the user has downloaded from the browser.
//
// Per the chosen design ("extension cache only") downloads live under
// the extension's global-storage directory, never the workspace:
//
//   <globalStorage>/fonts/index.json          ← manifest of all families
//   <globalStorage>/fonts/<familyId>/<file>   ← the woff2/ttf bytes
//
// The browser lists whatever the manifest records, and serves each face
// to the webview as a `webview.asWebviewUri(...)` resource so the real
// typeface paints even though the panel never had it on its system
// stack.
//
// All disk access funnels through an injected `StorageFs`, so the unit
// tests exercise add/list/remove against an in-memory fake.

import * as fsp from "fs/promises";
import * as path from "path";
import type { FontAxis } from "./googleFontsCatalog";
import type { Css2Face } from "./googleFontsClient";

export interface DownloadedFace {
    style: "normal" | "italic";
    weightMin: number;
    weightMax: number;
    /** File name within the family directory. */
    fileName: string;
    /** CSS `format(...)` token, e.g. `woff2`. */
    format: string;
}

export interface DownloadedFont {
    family: string;
    /** Slugged, filesystem-safe id (also the sub-directory name). */
    familyId: string;
    category: string;
    isVariable: boolean;
    axes: FontAxis[];
    faces: DownloadedFace[];
    downloadedAt: string;
}

interface IndexFile {
    version: 1;
    fonts: DownloadedFont[];
}

/** Minimal disk surface — satisfied by `nodeStorageFs()` in production. */
export interface StorageFs {
    mkdirp(dir: string): Promise<void>;
    readText(file: string): Promise<string>;
    writeText(file: string, text: string): Promise<void>;
    writeBytes(file: string, data: Uint8Array): Promise<void>;
    remove(target: string): Promise<void>;
    exists(target: string): Promise<boolean>;
}

/** Slug a family name into a filesystem-safe directory / resource id. */
export function slugFamily(family: string): string {
    return (
        family
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-+|-+$/g, "") || "font"
    );
}

function extForFormat(format: string): string {
    switch (format.toLowerCase()) {
        case "woff2":
            return "woff2";
        case "woff":
            return "woff";
        case "truetype":
        case "ttf":
            return "ttf";
        case "opentype":
        case "otf":
            return "otf";
        default:
            return "woff2";
    }
}

/** Deterministic per-face file name (style + weight range). */
export function faceFileName(familyId: string, face: Css2Face): string {
    const range =
        face.weightMin === face.weightMax
            ? String(face.weightMin)
            : `${face.weightMin}_${face.weightMax}`;
    return `${familyId}-${face.style}-${range}.${extForFormat(face.format)}`;
}

export interface AddFontInput {
    family: string;
    category: string;
    isVariable: boolean;
    axes: FontAxis[];
    /** Parsed css2 face + its downloaded bytes. */
    faces: { face: Css2Face; bytes: Uint8Array }[];
}

export class DownloadedFontsStore {
    private readonly fontsDir: string;
    private readonly indexPath: string;

    constructor(
        rootDir: string,
        private readonly fs: StorageFs,
    ) {
        this.fontsDir = path.join(rootDir, "fonts");
        this.indexPath = path.join(this.fontsDir, "index.json");
    }

    /** Directory that must be added to the webview's `localResourceRoots`. */
    get resourceRoot(): string {
        return this.fontsDir;
    }

    /** Absolute path to a stored face file (for `asWebviewUri`). */
    facePath(font: DownloadedFont, face: DownloadedFace): string {
        return path.join(this.fontsDir, font.familyId, face.fileName);
    }

    async list(): Promise<DownloadedFont[]> {
        if (!(await this.fs.exists(this.indexPath))) return [];
        try {
            const parsed = JSON.parse(
                await this.fs.readText(this.indexPath),
            ) as Partial<IndexFile>;
            return Array.isArray(parsed.fonts)
                ? (parsed.fonts as DownloadedFont[])
                : [];
        } catch {
            // Corrupt manifest — treat as empty rather than wedging the panel.
            return [];
        }
    }

    async has(familyId: string): Promise<boolean> {
        return (await this.list()).some((f) => f.familyId === familyId);
    }

    async add(input: AddFontInput): Promise<DownloadedFont> {
        const familyId = slugFamily(input.family);
        const dir = path.join(this.fontsDir, familyId);
        await this.fs.mkdirp(dir);

        const faces: DownloadedFace[] = [];
        for (const { face, bytes } of input.faces) {
            const fileName = faceFileName(familyId, face);
            await this.fs.writeBytes(path.join(dir, fileName), bytes);
            faces.push({
                style: face.style,
                weightMin: face.weightMin,
                weightMax: face.weightMax,
                fileName,
                format: face.format,
            });
        }

        const record: DownloadedFont = {
            family: input.family,
            familyId,
            category: input.category,
            isVariable: input.isVariable,
            axes: input.axes,
            faces,
            downloadedAt: new Date().toISOString(),
        };

        const existing = (await this.list()).filter(
            (f) => f.familyId !== familyId,
        );
        await this.writeIndex([...existing, record]);
        return record;
    }

    async remove(familyId: string): Promise<void> {
        const remaining = (await this.list()).filter(
            (f) => f.familyId !== familyId,
        );
        const dir = path.join(this.fontsDir, familyId);
        if (await this.fs.exists(dir)) {
            await this.fs.remove(dir);
        }
        await this.writeIndex(remaining);
    }

    private async writeIndex(fonts: DownloadedFont[]): Promise<void> {
        await this.fs.mkdirp(this.fontsDir);
        const index: IndexFile = { version: 1, fonts };
        await this.fs.writeText(this.indexPath, JSON.stringify(index, null, 2));
    }
}

/** Production `StorageFs` backed by `node:fs/promises`. */
export function nodeStorageFs(): StorageFs {
    return {
        async mkdirp(dir) {
            await fsp.mkdir(dir, { recursive: true });
        },
        async readText(file) {
            return fsp.readFile(file, "utf8");
        },
        async writeText(file, text) {
            await fsp.writeFile(file, text, "utf8");
        },
        async writeBytes(file, data) {
            await fsp.writeFile(file, data);
        },
        async remove(target) {
            await fsp.rm(target, { recursive: true, force: true });
        },
        async exists(target) {
            try {
                await fsp.stat(target);
                return true;
            } catch {
                return false;
            }
        },
    };
}
