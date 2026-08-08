import type { ComposePreviewTestApi, RefreshOutcome } from "../../../extension";

/**
 * Fail-fast guard for e2e refreshes that are supposed to produce pixels.
 *
 * `refresh` reports a build failure by returning `'failed'` and posting a
 * `showMessage` at the panel — it never rejects, because in production the
 * error belongs on the panel rather than in an unhandled promise. That
 * makes a dead render indistinguishable from a slow one to a test that only
 * polls `getPostedMessages()` for `setPreviews`, so the poll runs to the
 * Mocha ceiling and the suite reports a bare "Timeout of 1800000ms
 * exceeded" with no Gradle context.
 *
 * That is exactly how the 2026-08-08 breakage presented: the cold
 * `:samples:cmp` render was cancelled at the Gradle task cap five minutes
 * in, and the shard then sat idle for another 10-25 minutes before failing
 * on a timeout that named neither the task nor the cap. Asserting the
 * outcome turns that into an immediate, attributable failure and gives the
 * runner its slot back.
 *
 * Only call this where the refresh is *expected* to render. Scenarios that
 * deliberately provoke a failure (e.g. the injected Kotlin syntax error in
 * interactive scenario G) must read the outcome themselves.
 */
export function assertRefreshRendered(
    api: ComposePreviewTestApi,
    outcome: RefreshOutcome,
    label: string,
): void {
    if (outcome !== "failed") {
        return;
    }
    throw new Error(
        `${label}: refresh reported '${outcome}' — ${lastPanelMessage(api) ?? "no showMessage was posted"}`,
    );
}

/**
 * Text of the most recent `showMessage` post, which is where the generic
 * build-failure branch of `refresh` puts the underlying error (including
 * the `Gradle task <task> timed out after <n>s` the task cap raises).
 */
function lastPanelMessage(api: ComposePreviewTestApi): string | undefined {
    const messages = api.getPostedMessages() as Array<{
        command?: string;
        text?: unknown;
    }>;
    for (let i = messages.length - 1; i >= 0; i--) {
        const message = messages[i];
        if (message?.command === "showMessage" && message.text) {
            return String(message.text);
        }
    }
    return undefined;
}
