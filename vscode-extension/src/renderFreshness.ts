import * as fs from "fs";
import * as path from "path";
import { PreviewInfo } from "./types";

const STAMP_SCHEMA_VERSION = 1;
const MTIME_EPSILON_MS = 1;

interface RenderFreshnessStamp {
    schemaVersion: number;
    module: string;
    sourceFile: string;
    sourceMtimeMs: number;
    renderedAtMs: number;
    previewIds: string[];
}

export async function sourceFileMtime(
    filePath: string,
): Promise<number | null> {
    try {
        const stat = await fs.promises.stat(filePath);
        return stat.mtimeMs;
    } catch {
        return null;
    }
}

export function renderFreshnessStampPath(
    workspaceRoot: string,
    module: string,
    sourceFile: string,
): string {
    const encoded = Buffer.from(sourceFile).toString("base64url");
    return path.join(
        workspaceRoot,
        module,
        "build",
        "compose-previews",
        "render-freshness",
        `${encoded}.json`,
    );
}

export async function writeRenderFreshnessStamp(
    workspaceRoot: string,
    module: string,
    sourceFile: string,
    previews: PreviewInfo[],
): Promise<void> {
    const sourceMtime = await sourceFileMtime(sourceFile);
    if (sourceMtime == null) {
        return;
    }

    const stamp: RenderFreshnessStamp = {
        schemaVersion: STAMP_SCHEMA_VERSION,
        module,
        sourceFile,
        sourceMtimeMs: sourceMtime,
        renderedAtMs: Date.now(),
        previewIds: previews.map((p) => p.id).sort(),
    };
    const stampPath = renderFreshnessStampPath(
        workspaceRoot,
        module,
        sourceFile,
    );
    await fs.promises.mkdir(path.dirname(stampPath), { recursive: true });
    await fs.promises.writeFile(stampPath, JSON.stringify(stamp, null, 2));
}

export async function hasFreshRenderStamp(
    workspaceRoot: string,
    module: string,
    sourceFile: string,
    previews: PreviewInfo[],
): Promise<boolean> {
    const sourceMtime = await sourceFileMtime(sourceFile);
    if (sourceMtime == null) {
        return false;
    }

    try {
        const raw = await fs.promises.readFile(
            renderFreshnessStampPath(workspaceRoot, module, sourceFile),
            "utf8",
        );
        const stamp = JSON.parse(raw) as Partial<RenderFreshnessStamp>;
        if (
            stamp.schemaVersion !== STAMP_SCHEMA_VERSION ||
            stamp.module !== module ||
            stamp.sourceFile !== sourceFile ||
            typeof stamp.sourceMtimeMs !== "number" ||
            !Array.isArray(stamp.previewIds)
        ) {
            return false;
        }
        if (sourceMtime > stamp.sourceMtimeMs + MTIME_EPSILON_MS) {
            return false;
        }
        const stampedIds = new Set(
            stamp.previewIds.filter(
                (id): id is string => typeof id === "string",
            ),
        );
        return previews.every((p) => stampedIds.has(p.id));
    } catch {
        return false;
    }
}
