/**
 * Open-tab inspection for the refresh() "keep stale previews?" decision.
 *
 * When focus drifts off a preview source — onto the webview panel, the output
 * pane, or no editor at all — the panel must not blank while the daemon is
 * mid-spawn: the already-painted cards stay up because the next render replaces
 * them (issue #145). But that only holds while a preview source is still
 * *open*. Once the user closes the last preview editor there is nothing left to
 * render into the panel, so leaving the cards up strands them on stale previews
 * (issue #1566). The discriminator is "is any preview source still open in a
 * tab?", which is what this module answers.
 *
 * This module deliberately avoids importing `vscode`: it operates on a
 * structural subset of `vscode.window.tabGroups` so the open-tab scan can be
 * unit-tested without a live editor. The extension adapts the real tab groups
 * to [TabGroupLike] at the call site. Tab state is the right signal here
 * because it updates synchronously on close, unlike `workspace.textDocuments`,
 * which VS Code keeps cached for a while after the editor is gone.
 */

/** Subset of `vscode.Tab` we read — the tab's `input` may expose a `uri`. */
export interface TabLike {
    readonly input?: unknown;
}

/** Subset of `vscode.TabGroup`. */
export interface TabGroupLike {
    readonly tabs: ReadonlyArray<TabLike>;
}

/**
 * Collect the filesystem paths of every text-backed tab across [groups].
 * `TabInputText` exposes `uri`; `TabInputTextDiff` exposes `modified` and
 * `original` (no plain `uri`) — both sides count as the source being open in
 * a tab, so we read both. Non-text tabs (webviews, terminals, notebooks)
 * carry a differently-shaped `input` and are skipped.
 */
export function openTabFsPaths(groups: ReadonlyArray<TabGroupLike>): string[] {
    const paths: string[] = [];
    for (const group of groups) {
        for (const tab of group.tabs) {
            const input = tab.input;
            if (!input || typeof input !== "object") continue;
            const candidates: Array<{ fsPath?: unknown } | undefined> = [];
            if ("uri" in input) {
                candidates.push((input as { uri?: { fsPath?: unknown } }).uri);
            }
            if ("modified" in input) {
                candidates.push(
                    (input as { modified?: { fsPath?: unknown } }).modified,
                );
            }
            if ("original" in input) {
                candidates.push(
                    (input as { original?: { fsPath?: unknown } }).original,
                );
            }
            for (const uri of candidates) {
                const fsPath = uri?.fsPath;
                if (typeof fsPath === "string") {
                    paths.push(fsPath);
                }
            }
        }
    }
    return paths;
}

/**
 * True iff at least one open tab is a preview source file, per the supplied
 * [isPreviewSource] predicate. Drives the refresh() no-module guard: retain
 * stale cards only while this holds.
 */
export function anyPreviewSourceTabOpen(
    groups: ReadonlyArray<TabGroupLike>,
    isPreviewSource: (fsPath: string) => boolean,
): boolean {
    return openTabFsPaths(groups).some(isPreviewSource);
}
