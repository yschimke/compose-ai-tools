/**
 * Common refresh-orchestration helper used on every entry point that moves the panel
 * to a new active file: extension activation, the kotlin-editor focus-change handler,
 * and `onDidChangeVisibleTextEditors` recovery. Each entry used to inline a slightly
 * different subset of "abort + preload + refresh + warm", and the focus-change
 * fallbacks skipped preload entirely — focus drift to a non-Kotlin editor would tear
 * down the existing cards before the new file's discover finished. Centralising the
 * sequence means "every transition preloads first" is a property of one function,
 * not a discipline that has to be re-asserted at three call sites.
 *
 * Ordering invariants the callers and tests rely on:
 *
 *   1. The previous file's in-flight refresh aborts BEFORE preload starts. Otherwise
 *      a stale `composePreviewDiscover` continuation can resume after we paint the
 *      new module's cached cards and overwrite them with the wrong manifest's state.
 *   2. Preload runs before refresh and the resulting `painted` flag drives the
 *      `showLoadingOverlay` decision — when preload painted, refresh stays stealth;
 *      when it didn't, refresh surfaces the overlay so the panel doesn't sit blank
 *      while Gradle warms up.
 *   3. Warm runs last so the daemon spawn doesn't block on Gradle's bootstrap.
 *
 * Tolerant of non-Kotlin / non-preview-source filePaths: the deps' `preloadCachedPreviews`
 * returns false for those (its `loadCachedPreviews` bails with a `skipped` outcome), and
 * `refresh` handles the no-module case via the existing guard — either holding the
 * previous module's cards on screen (when other previews are already painted) or
 * clearing to the empty state.
 */
export interface TransitionToFileDeps {
    /** Aborts and clears the pending-refresh bookkeeping so a stale continuation can't
     *  win after the new transition starts. Idempotent; safe to call when nothing is
     *  in flight. */
    abortPendingRefresh: () => void;
    /** True iff the preload reached the `painted` outcome and posted cards. False on
     *  any `skipped` reason (not a preview file, no module, no manifest, …) or on
     *  an exception inside the preload. Drives the refresh overlay decision. */
    preloadCachedPreviews: (filePath: string) => Promise<boolean>;
    refresh: (
        forceRender: boolean,
        filePath: string,
        tier: "full" | "fast",
        opts: { showLoadingOverlay: boolean },
    ) => Promise<unknown>;
    warmDaemonForFile: (
        filePath: string,
        opts: { refreshAfterReady: boolean },
    ) => Promise<unknown>;
}

export async function transitionToFile(
    filePath: string,
    deps: TransitionToFileDeps,
): Promise<void> {
    deps.abortPendingRefresh();
    const preloaded = await deps.preloadCachedPreviews(filePath);
    await deps.refresh(false, filePath, "full", {
        showLoadingOverlay: !preloaded,
    });
    await deps.warmDaemonForFile(filePath, { refreshAfterReady: true });
}
