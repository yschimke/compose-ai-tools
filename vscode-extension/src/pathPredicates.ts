/**
 * Pure path-shape predicates that classify a workspace file as a Kotlin
 * source candidate, a generated-output artefact, or otherwise. Lifted out
 * of `extension.ts` because they have no `vscode.*` dependencies and
 * benefit from direct unit tests.
 */

/**
 * True iff every entry in `a` appears in `b` and the lengths match. The
 * panel's scope-file resolver uses this to decide whether a "did the
 * scope set actually change?" event is worth re-rendering for.
 */
export function sameScope(a: string[], b: string[]): boolean {
    if (a.length !== b.length) {
        return false;
    }
    const set = new Set(b);
    return a.every((m) => set.has(m));
}

/**
 * True iff the path lies under a `build/` or `bin/` directory anywhere in
 * its segments. Matches AGP's `build/` and IntelliJ's `bin/` defaults; both
 * are by-convention generated-output roots that file-watchers should skip.
 */
export function isGeneratedOutputPath(filePath: string): boolean {
    const segments = filePath.split(/[\\/]+/);
    return segments.includes("build") || segments.includes("bin");
}

/**
 * True iff the path is a Kotlin / XML / JSON / properties source file
 * outside `build/` / `bin/`. The save-driven refresh uses this as the
 * "should I bother running the discovery / render task?" gate.
 */
export function isSourceFile(filePath: string): boolean {
    if (isGeneratedOutputPath(filePath)) {
        return false;
    }
    return /\.(kt|xml|json|properties)$/i.test(filePath);
}

/**
 * Tighter than {@link isSourceFile}: only `.kt` files that aren't Gradle
 * build scripts or generated output. The panel scope-resolver wants
 * "actual Kotlin source the user might be writing previews into" — so
 * `.gradle.kts` (declarative build script) is excluded even though it
 * shares the `.kt` ancestor extension.
 */
export function isPreviewSourceFile(filePath: string): boolean {
    return (
        filePath.endsWith(".kt") &&
        !filePath.endsWith(".gradle.kts") &&
        !isGeneratedOutputPath(filePath)
    );
}
