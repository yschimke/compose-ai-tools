// Where a module's render history lives on disk.
//
// This is the THIRD implementation of one layout. The daemon writes the archive via `common/io`'s
// `composeAiHistoryDir` (Kotlin), the Gradle plugin computes the same path to hand the daemon as
// `-Dcomposeai.daemon.historyDir` (a separate inlined copy — the plugin can't depend on
// `:common-io`), and this one reads it back for the panel's FS fallback when the daemon isn't up.
//
// All three must agree byte-for-byte. A drift doesn't crash: the daemon writes one directory, the
// panel reads another, and the history drawer is silently empty. The golden vectors in
// `test/historyPaths.test.ts` are duplicated verbatim from `HistoryPathsTest.kt`; change the layout
// only with both suites updated together.
//
// History used to live at `<projectDir>/.compose-preview-history`, which grew an untracked
// directory next to every previewed module's sources. It's a semi-persistent timeline of local
// edits — cache-shaped data, never user-authored — so it belongs beside the font cache. The
// reporting-branch flow is unaffected: that publishes to a git ref, and the in-tree directory was
// only its local staging area.

import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { createHash } from "crypto";

/** Directory name of the legacy in-tree history archive, kept for backwards compatibility. */
export const LEGACY_HISTORY_DIRNAME = ".compose-preview-history";

/**
 * Root of the user-level cache: `$XDG_CACHE_HOME/composeai` when set and non-blank, else
 * `~/.cache/composeai`. Mirrors `common/io`'s `composeAiCacheDir`.
 */
export function composeAiCacheDir(subdir: string): string {
    const xdg = process.env.XDG_CACHE_HOME;
    const base =
        xdg && xdg.trim().length > 0
            ? path.join(xdg, "composeai")
            : path.join(os.homedir() || ".", ".cache", "composeai");
    return path.join(base, subdir);
}

/** Replaces anything outside `[A-Za-z0-9._-]` with `-`. ASCII-only, matching the Kotlin side. */
function sanitiseSegment(segment: string): string {
    return segment.replace(/[^A-Za-z0-9._-]/g, "-");
}

/**
 * `sanitiseSegment`, plus an 8-hex digest of the original text when sanitising changed it.
 *
 * Distinct directory names must not land on the same segment: `ui components` and `ui-components`
 * are different modules, and two non-ASCII names can flatten to the same run of hyphens. Sharing a
 * history directory would mix their entries and prune state. Only rewritten segments pay the
 * suffix, so ordinary paths stay readable.
 */
function sanitiseSegmentInjectively(segment: string): string {
    const sanitised = sanitiseSegment(segment);
    if (sanitised === segment) {
        return sanitised;
    }
    const digest = createHash("sha256")
        .update(segment, "utf8")
        .digest("hex")
        .slice(0, 8);
    return `${sanitised}-${digest}`;
}

/** Absolute path with separators normalised to `/` and any trailing separator dropped. */
function normalise(p: string): string {
    const abs = path.resolve(p).replace(/\\/g, "/");
    return abs.length > 1 ? abs.replace(/\/+$/, "") : abs;
}

/**
 * Stable per-workspace directory name: `<sanitised basename>-<sha256(path) truncated to 12 hex>`.
 *
 * The readable prefix keeps the cache browsable; the hash keeps two checkouts of the same repo from
 * colliding. Hashed over the absolute path with `/` separators — deliberately NOT the real
 * (symlink-resolved) path, because Gradle, the daemon and this extension each learn the workspace
 * root by a different route and only the unresolved form is reliably identical across all three.
 */
export function historyWorkspaceSlug(workspaceRoot: string): string {
    const normalised = normalise(workspaceRoot);
    const digest = createHash("sha256")
        .update(normalised, "utf8")
        .digest("hex")
        .slice(0, 12);
    const name = sanitiseSegment(normalised.split("/").pop() ?? "");
    return name.length === 0 ? digest : `${name}-${digest}`;
}

/**
 * The module's path relative to `workspaceRoot`, `/`-joined and sanitised per segment. The root
 * project maps to `_root`; a module outside the workspace tree (a `projectDir` reassigned by
 * `settings.gradle.kts`) keys on its own identity so two such modules can't collide.
 */
export function historyModuleSegment(
    workspaceRoot: string,
    projectDir: string,
): string {
    const root = normalise(workspaceRoot);
    const module = normalise(projectDir);
    if (module === root) {
        return "_root";
    }
    if (!module.startsWith(`${root}/`)) {
        return `_external-${historyWorkspaceSlug(projectDir)}`;
    }
    const segment = module
        .slice(root.length + 1)
        .split("/")
        .filter((s) => s.length > 0)
        .map(sanitiseSegmentInjectively)
        .join("/");
    return segment.length === 0 ? "_root" : segment;
}

/**
 * Absolute path of a module's history archive.
 *
 * An existing `<projectDir>/.compose-preview-history` wins so an upgrade doesn't strand a timeline
 * someone already has; nothing recreates it once removed.
 */
export function historyDirFor(
    workspaceRoot: string,
    projectDir: string,
): string {
    const legacy = path.join(projectDir, LEGACY_HISTORY_DIRNAME);
    try {
        if (fs.statSync(legacy).isDirectory()) {
            return legacy;
        }
    } catch {
        /* absent — fall through to the cache location */
    }
    return path.join(
        composeAiCacheDir("history"),
        historyWorkspaceSlug(workspaceRoot),
        historyModuleSegment(workspaceRoot, projectDir),
    );
}
