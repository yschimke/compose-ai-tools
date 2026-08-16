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
    const declared = new Set(
        declaredFunctionNames(source).filter((functionName) =>
            sourceLooksLikePreviewDeclaration(source, functionName),
        ),
    );
    const cached = new Set(previews.map((preview) => preview.functionName));
    return (
        declared.size !== cached.size ||
        [...declared].some((functionName) => !cached.has(functionName))
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

    const previewPattern = /@(?:[A-Za-z_][A-Za-z0-9_]*\.)*Preview\b/g;
    let candidate: RegExpExecArray | null;
    while ((candidate = previewPattern.exec(source)) !== null) {
        if (candidate.index >= match.index) break;
        const end = annotationEnd(source, candidate.index);
        if (
            end !== null &&
            onlyDeclarationPreamble(source.slice(end, match.index))
        ) {
            return true;
        }
    }
    return false;
}

const DECLARATION_MODIFIERS = new Set([
    "public",
    "private",
    "protected",
    "internal",
    "expect",
    "actual",
    "final",
    "open",
    "abstract",
    "sealed",
    "const",
    "external",
    "override",
    "lateinit",
    "tailrec",
    "vararg",
    "suspend",
    "inner",
    "enum",
    "annotation",
    "companion",
    "inline",
    "value",
    "infix",
    "operator",
    "data",
]);

/** End of a Kotlin annotation, including a balanced multiline argument list. */
function annotationEnd(source: string, start: number): number | null {
    const name = /^@(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*/.exec(
        source.slice(start),
    );
    if (!name) return null;
    let i = start + name[0].length;
    while (/\s/.test(source[i] ?? "")) i += 1;
    if (source[i] !== "(") return i;

    let depth = 0;
    let quote = "";
    for (; i < source.length; i += 1) {
        const ch = source[i];
        const next = source[i + 1];
        if (quote) {
            if (ch === "\\") i += 1;
            else if (ch === quote) quote = "";
            continue;
        }
        if (ch === '"' || ch === "'") {
            quote = ch;
            continue;
        }
        if (ch === "/" && next === "*") {
            i = blockCommentEnd(source, i) - 1;
            continue;
        }
        if (ch === "/" && next === "/") {
            const newline = source.indexOf("\n", i + 2);
            i = newline < 0 ? source.length : newline;
            continue;
        }
        if (ch === "(") depth += 1;
        if (ch === ")" && --depth === 0) return i + 1;
    }
    return null;
}

function blockCommentEnd(source: string, start: number): number {
    let depth = 1;
    for (let i = start + 2; i < source.length - 1; i += 1) {
        if (source[i] === "/" && source[i + 1] === "*") {
            depth += 1;
            i += 1;
        } else if (source[i] === "*" && source[i + 1] === "/") {
            depth -= 1;
            i += 1;
            if (depth === 0) return i + 1;
        }
    }
    return source.length;
}

/** Accept annotations, comments, whitespace, and Kotlin declaration modifiers before `fun`. */
function onlyDeclarationPreamble(fragment: string): boolean {
    let i = 0;
    while (i < fragment.length) {
        if (/\s/.test(fragment[i])) {
            i += 1;
            continue;
        }
        if (fragment.startsWith("//", i)) {
            const newline = fragment.indexOf("\n", i + 2);
            i = newline < 0 ? fragment.length : newline + 1;
            continue;
        }
        if (fragment.startsWith("/*", i)) {
            i = blockCommentEnd(fragment, i);
            continue;
        }
        if (fragment[i] === "@") {
            const end = annotationEnd(fragment, i);
            if (end === null) return false;
            i = end;
            continue;
        }
        const word = /^[A-Za-z_][A-Za-z0-9_]*/.exec(fragment.slice(i));
        if (!word || !DECLARATION_MODIFIERS.has(word[0])) return false;
        i += word[0].length;
    }
    return true;
}
