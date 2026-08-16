export interface CachedPreviewIdentity {
    functionName: string;
}

/**
 * Returns true when Kotlin source and the previews cached for that file can no
 * longer describe the same preview set.
 *
 * The empty-cache case matters during daemon startup: discovery can leave an
 * asset-only manifest behind when Gradle restores empty class outputs. If the
 * source still declares a preview, that manifest must be rebuilt before the
 * daemon snapshots it.
 */
export function sourceMayDifferFromCachedPreviews(
    source: string,
    previews: readonly CachedPreviewIdentity[],
): boolean {
    if (previews.length === 0) {
        for (const functionName of declaredFunctionNames(source)) {
            if (sourceLooksLikePreviewDeclaration(source, functionName)) {
                return true;
            }
        }
        return false;
    }

    return previews.some(
        (preview) =>
            !sourceLooksLikePreviewDeclaration(source, preview.functionName),
    );
}

function declaredFunctionNames(source: string): string[] {
    const names: string[] = [];
    const functionPattern = /\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
    for (
        let match = functionPattern.exec(source);
        match;
        match = functionPattern.exec(source)
    ) {
        names.push(match[1]);
    }
    return names;
}

function sourceLooksLikePreviewDeclaration(
    source: string,
    functionName: string,
): boolean {
    const escaped = functionName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const match = new RegExp(`\\bfun\\s+${escaped}\\s*\\(`).exec(source);
    if (!match) {
        return false;
    }

    const lines = source.slice(0, match.index).split(/\r?\n/);
    const annotationLines: string[] = [];
    for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i].trim();
        if (line.length === 0) {
            if (annotationLines.length === 0) {
                continue;
            }
            break;
        }
        if (
            line.startsWith("@") ||
            line.startsWith("//") ||
            line.startsWith("/*") ||
            line.startsWith("*")
        ) {
            annotationLines.unshift(line);
            continue;
        }
        break;
    }
    return annotationLines.some(
        (line) => line.startsWith("@") && line.includes("Preview"),
    );
}
