import * as fs from "fs";
import * as path from "path";
import { HistoryListResult, HistoryReadResult } from "./daemonProtocol";

/**
 * UX fallback that surfaces "what's currently on disk under
 * `<projectDir>/build/compose-previews/renders/`" as if it were a single-page
 * history listing. Used by the Preview History panel when neither the daemon
 * nor `index.jsonl` has anything recorded yet — that's the default path,
 * since the legacy `HistorizePreviewsTask` was removed in #311 and the daemon
 * is opt-in. Without this, a user who's just rendered their first preview
 * sees an empty "No history yet" message even though there's a fresh PNG
 * sitting on disk.
 *
 * These entries are ephemeral: re-derived from the manifest on every call,
 * never written. They use the `current:` id prefix so {@link read} can route
 * them back to the right file without a disk-side index. Diff is intentionally
 * unsupported — the only history-shaped operations that make sense for a
 * point-in-time snapshot are list and read.
 */
export class CurrentRendersHistory {
    constructor(private readonly opts: CurrentRendersOptions) {}

    /**
     * Synthesises one entry per capture currently on disk in the module's
     * renders directory. Honours the same `previewId` filter the panel feeds
     * down. Returns the entries newest-first (by file mtime); an animated
     * preview's multiple captures collapse to a single representative entry
     * to keep the timeline readable.
     */
    list(previewIdFilter?: string): HistoryListResult {
        const manifest = this.readManifest();
        const entries: SyntheticHistoryEntry[] = [];
        if (!manifest) {
            return { entries: [], totalCount: 0 };
        }

        for (const preview of manifest.previews ?? []) {
            if (previewIdFilter && preview.id !== previewIdFilter) {
                continue;
            }
            const captures = Array.isArray(preview.captures)
                ? preview.captures
                : [];
            // Only the representative (first) capture per preview gets a row;
            // the panel doesn't have UI for sibling captures yet, and showing
            // every animation frame as a separate history entry would drown
            // the static previews in the same module.
            const representative = captures.find((c) => c?.renderOutput);
            if (!representative?.renderOutput) {
                continue;
            }
            const pngPath = path.join(
                this.opts.buildDir,
                representative.renderOutput,
            );
            const stat = statOrNull(pngPath);
            if (!stat) {
                continue;
            }
            entries.push({
                id: syntheticId(representative.renderOutput),
                previewId: preview.id,
                module: this.opts.moduleId,
                timestamp: stat.mtime.toISOString(),
                pngPath: representative.renderOutput,
                producer: "gradle",
                trigger: "current",
                source: { kind: "fs", id: `current:${this.opts.moduleId}` },
                previewMetadata: {
                    sourceFile: preview.sourceFile ?? null,
                },
            });
        }

        entries.sort((a, b) => b.timestamp.localeCompare(a.timestamp));
        return { entries, totalCount: entries.length };
    }

    /**
     * Resolves a synthetic id back to its on-disk PNG. Returns null when the
     * id is unknown, the manifest no longer references it, or the file has
     * been deleted between list and read (e.g. a `clean` ran).
     */
    read(id: string): HistoryReadResult | null {
        if (!id.startsWith(SYNTHETIC_ID_PREFIX)) {
            return null;
        }
        const renderOutput = decodeSyntheticId(id);
        if (!renderOutput) {
            return null;
        }
        const manifest = this.readManifest();
        if (!manifest) {
            return null;
        }
        const owner = (manifest.previews ?? []).find((p) =>
            (p.captures ?? []).some((c) => c?.renderOutput === renderOutput),
        );
        if (!owner) {
            return null;
        }
        const pngPath = path.join(this.opts.buildDir, renderOutput);
        const stat = statOrNull(pngPath);
        if (!stat) {
            return null;
        }
        const entry: SyntheticHistoryEntry = {
            id,
            previewId: owner.id,
            module: this.opts.moduleId,
            timestamp: stat.mtime.toISOString(),
            pngPath: renderOutput,
            producer: "gradle",
            trigger: "current",
            source: { kind: "fs", id: `current:${this.opts.moduleId}` },
            previewMetadata: {
                sourceFile: owner.sourceFile ?? null,
            },
        };
        return { entry, previewMetadata: entry.previewMetadata, pngPath };
    }

    static isSyntheticId(id: string): boolean {
        return id.startsWith(SYNTHETIC_ID_PREFIX);
    }

    private readManifest(): ManifestShape | null {
        const manifestPath = path.join(this.opts.buildDir, "previews.json");
        if (!fs.existsSync(manifestPath)) {
            return null;
        }
        try {
            const raw = fs.readFileSync(manifestPath, "utf-8");
            const parsed = JSON.parse(raw) as ManifestShape;
            if (!Array.isArray(parsed.previews)) {
                return null;
            }
            return parsed;
        } catch {
            return null;
        }
    }
}

export interface CurrentRendersOptions {
    /** Absolute path to `<projectDir>/build/compose-previews`. */
    buildDir: string;
    /** Gradle moduleId — copied onto each synth entry's `module` field so
     *  the panel's daemon-push scope filter still matches if a real entry
     *  later lands for the same module. */
    moduleId: string;
}

const SYNTHETIC_ID_PREFIX = "current:";

function syntheticId(renderOutput: string): string {
    // Base64url of the renderOutput keeps the id opaque to the panel and
    // round-trippable without a separate index. Path separators and dots
    // would otherwise clash with the panel's CSS-escape attribute selector.
    return (
        SYNTHETIC_ID_PREFIX +
        Buffer.from(renderOutput, "utf-8").toString("base64url")
    );
}

function decodeSyntheticId(id: string): string | null {
    try {
        return Buffer.from(
            id.slice(SYNTHETIC_ID_PREFIX.length),
            "base64url",
        ).toString("utf-8");
    } catch {
        return null;
    }
}

function statOrNull(p: string): fs.Stats | null {
    try {
        return fs.statSync(p);
    } catch {
        return null;
    }
}

interface ManifestShape {
    previews?: ManifestPreview[];
}

interface ManifestPreview {
    id: string;
    sourceFile?: string | null;
    captures?: { renderOutput?: string }[];
}

interface SyntheticHistoryEntry {
    id: string;
    previewId: string;
    module: string;
    timestamp: string;
    pngPath: string;
    producer: string;
    trigger: string;
    source: { kind: string; id: string };
    previewMetadata: { sourceFile: string | null };
}
