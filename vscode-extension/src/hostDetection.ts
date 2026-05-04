/**
 * Detects whether the extension is running inside Google's Antigravity
 * IDE (a VS Code fork). Used in a few places to widen file-resolution
 * fallbacks — Antigravity's window/tab model differs enough that the
 * "must be in a visible editor" sticky-scope rule we use under stock
 * VS Code is too restrictive there.
 *
 * Lifted out of `extension.ts` so the env-var probes are unit-testable.
 */

/**
 * @param env  Environment-variable map. Defaults to `process.env`. Tests
 *             pass a synthetic object so they don't pollute the real
 *             process env.
 */
export function isAntigravityHost(
    env: NodeJS.ProcessEnv = process.env,
): boolean {
    const bundleId = env.__CFBundleIdentifier?.toLowerCase() ?? "";
    return (
        bundleId.includes("antigravity") ||
        env.ANTIGRAVITY_CLI_ALIAS !== undefined
    );
}
