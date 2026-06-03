// Version-compatibility helpers, mirroring the Kotlin `majorVersionOf` / `versionsIncompatible`
// in `cli/.../Version.kt`. Kept dependency-free so it can be unit-tested without the VS Code host.

/**
 * Major version (the first numeric segment) of a semver-ish string, or `null` when it can't be
 * parsed. `"1.2.3"` → 1, `"v2.0.0-SNAPSHOT"` → 2, `"main"` → null.
 */
export function majorVersionOf(v: string): number | null {
    const head = v.trim().replace(/^v/, "").split("-")[0];
    const first = head.split(".")[0];
    if (!/^\d+$/.test(first)) {
        return null;
    }
    return Number.parseInt(first, 10);
}

/**
 * Whether two compose-preview component versions are mutually incompatible — i.e. they parse to
 * different **major** versions. A major release changes the render/daemon wire format and the
 * published APIs, so a daemon and the extension's bundled plugin on different majors can render
 * incorrectly. Returns `false` when either version is unparseable (we don't warn on `main` /
 * SNAPSHOT-only builds we can't reason about).
 */
export function versionsIncompatible(a: string, b: string): boolean {
    const am = majorVersionOf(a);
    const bm = majorVersionOf(b);
    if (am === null || bm === null) {
        return false;
    }
    return am !== bm;
}
