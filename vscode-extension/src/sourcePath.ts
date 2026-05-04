import * as fs from "fs";
import * as path from "path";

// Matches a top-level Kotlin package declaration. Tolerates optional trailing
// semicolon and leading whitespace (annotations/comments above `package` are
// handled by the `m` flag anchoring to line starts elsewhere in the file).
const PACKAGE_RE = /^\s*package\s+([a-zA-Z_][\w.]*)\s*;?\s*$/m;

/**
 * Builds the older package-qualified source path that some preview manifests
 * used for `sourceFile`, e.g. `com/example/samplewear/Previews.kt`.
 */
export function packageQualifiedSourcePath(filePath: string): string {
    const basename = path.basename(filePath);
    try {
        const content = fs.readFileSync(filePath, "utf-8");
        const m = PACKAGE_RE.exec(content);
        if (m) {
            return m[1].replace(/\./g, "/") + "/" + basename;
        }
    } catch {
        /* fall through to basename */
    }
    return basename;
}

/**
 * Builds the canonical module-relative source path used by current manifests,
 * e.g. `src/main/kotlin/com/example/samplewear/Previews.kt`.
 */
export function moduleRelativeSourcePath(
    filePath: string,
    workspaceRoot: string,
    module: string,
): string {
    return normalizeSourcePath(
        path.relative(path.join(workspaceRoot, module), filePath),
    );
}

function normalizeSourcePath(value: string): string {
    return value.replace(/\\/g, "/").replace(/^\.\/+/, "");
}

function sourcePathCandidates(
    filePath: string,
    workspaceRoot: string,
    module: string,
): Set<string> {
    const candidates = new Set<string>();
    candidates.add(normalizeSourcePath(packageQualifiedSourcePath(filePath)));
    candidates.add(moduleRelativeSourcePath(filePath, workspaceRoot, module));
    candidates.add(normalizeSourcePath(path.relative(workspaceRoot, filePath)));
    candidates.add(normalizeSourcePath(filePath));
    candidates.add(path.basename(filePath));
    return candidates;
}

/**
 * Matches the source path stored in previews.json against the active file.
 *
 * Current manifests use module-relative paths including the source set
 * (`src/main/kotlin/com/example/Previews.kt`). Older manifests used
 * package-qualified (`com/example/Previews.kt`) or basename-only values. The
 * VS Code panel only needs to know whether a manifest entry belongs to the
 * focused file, so accept all known stable shapes here.
 */
export function previewSourceMatches(
    previewSourceFile: string | null | undefined,
    filePath: string,
    workspaceRoot: string,
    module: string,
): boolean {
    if (!previewSourceFile) {
        return false;
    }
    const sourceFile = normalizeSourcePath(previewSourceFile);
    return sourcePathCandidates(filePath, workspaceRoot, module).has(
        sourceFile,
    );
}
