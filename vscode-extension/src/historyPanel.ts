import * as fs from "fs";
import * as vscode from "vscode";
import { HistoryReader } from "./daemon/historyReader";
import { CurrentRendersHistory } from "./daemon/currentRendersHistory";
import {
    HistoryAddedParams,
    HistoryListResult,
    HistoryPrunedParams,
    HistoryReadResult,
    HistorySourceKind,
} from "./daemon/daemonProtocol";
import { reportingBranchLabel } from "./reportingBranches";

/**
 * Read-only Preview History panel — HISTORY.md § "VS Code integration".
 *
 * A separate webview view from the existing live `Compose Preview` panel.
 * Lists rendered snapshots newest-first across all previews in the active
 * file's module; click-to-expand shows the full PNG + metadata; "Open in
 * Editor" jumps to `previewMetadata.sourceFile`. Filter dropdown narrows
 * by source kind / branch / agent.
 *
 * **Storage**: prefers the daemon's `history/list` (live, push-updated
 * via `historyAdded`) when available; falls back to the FS reader when
 * the daemon is disabled or unhealthy. Same wire-format on both sides
 * — the panel's webview never knows which path served a given list.
 *
 * **Mutations: none.** Panel is read-only per HISTORY.md § "VS Code
 * integration". Pruning happens daemon-side or via the Gradle path.
 *
 * **Scope**: keyed off the same `currentScopeFile` extension.ts maintains
 * for the live panel — when the user navigates to a different file, the
 * history view re-scopes to that file's module's history. No multi-module
 * timeline today; that's H14 (cross-worktree merge in MCP).
 */
export class HistoryPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = "composePreview.historyPanel";

    private view?: vscode.WebviewView;
    private currentScope: HistoryScope | null = null;

    constructor(
        private readonly extensionUri: vscode.Uri,
        private readonly source: HistorySource,
    ) {}

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ): void {
        this.view = webviewView;
        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionUri],
        };
        webviewView.webview.html = this.getHtml(webviewView.webview);
        webviewView.webview.onDidReceiveMessage((msg) =>
            this.handleMessage(msg),
        );
        // Re-list whenever the view becomes visible (lazy panel UX).
        webviewView.onDidChangeVisibility(() => {
            if (webviewView.visible) {
                void this.refresh();
            }
        });
        if (this.currentScope) {
            void this.refresh();
        }
    }

    /** Re-scope the panel to a different module's history. Called from
     *  extension.ts when the active editor's module changes. */
    setScope(scope: HistoryScope | null): void {
        this.currentScope = scope;
        if (this.view?.visible) {
            void this.refresh();
        }
    }

    /** Daemon push: a new render landed. If it belongs to the currently-
     *  scoped module and previewId, prepend it to the visible list and
     *  highlight it briefly. Drops cleanly when the panel isn't open. */
    onHistoryAdded(params: HistoryAddedParams): void {
        if (!this.view) {
            return;
        }
        const entry = params.entry as { previewId?: string; module?: string };
        if (!matchesScope(entry, this.currentScope)) {
            return;
        }
        this.view.webview.postMessage({
            command: "entryAdded",
            entry: params.entry,
        });
    }

    /** Daemon push: one or more entries were pruned. Drops them from the
     *  visible list so the user doesn't see ghost rows that resolve to
     *  404s when they click. Drops cleanly when the panel isn't open. */
    onHistoryPruned(params: HistoryPrunedParams): void {
        if (!this.view || params.removedIds.length === 0) {
            return;
        }
        this.view.webview.postMessage({
            command: "entriesPruned",
            removedIds: params.removedIds,
        });
    }

    isVisible(): boolean {
        return this.view?.visible ?? false;
    }

    /** Force a re-list against the current scope. */
    async refresh(): Promise<void> {
        // Reflect the active source (Local vs a reporting branch) in the
        // toolbar regardless of whether there's a scope/preview to list yet.
        this.postSourceRef();
        if (!this.view || !this.currentScope) {
            this.view?.webview.postMessage({
                command: "showMessage",
                text: "Open a Kotlin file to see its render history.",
            });
            this.view?.webview.postMessage({
                command: "setScopeLabel",
                label: null,
            });
            return;
        }
        if (!this.currentScope.previewId) {
            // Module-wide history would mix entries from every preview in the
            // file, which is rarely what the user wants and obscures whose
            // timeline they're looking at. Require a single preview be
            // selected (focus mode, or filters narrowed to one card) and
            // show a hint until then.
            this.view.webview.postMessage({
                command: "setScopeLabel",
                label: null,
            });
            this.view.webview.postMessage({
                command: "showMessage",
                text: "Select a single preview (focus mode, or narrow the filter to one card) to see its render history.",
            });
            return;
        }
        const label =
            this.currentScope.previewLabel ?? this.currentScope.previewId;
        this.view.webview.postMessage({ command: "setScopeLabel", label });
        try {
            const result = await this.source.list(this.currentScope);
            this.view.webview.postMessage({ command: "setEntries", result });
        } catch (err) {
            this.view.webview.postMessage({
                command: "showMessage",
                text: `History unavailable: ${(err as Error).message}`,
            });
        }
    }

    /** Pushes the active history source (Local vs reporting branch) to the
     *  webview so the toolbar control shows where entries are coming from. */
    private postSourceRef(): void {
        if (!this.view) {
            return;
        }
        const ref = this.currentScope?.gitRef ?? null;
        this.view.webview.postMessage({
            command: "setSourceRef",
            ref,
            label: ref ? (reportingBranchLabel(ref) ?? ref) : null,
        });
    }

    private async handleMessage(msg: HistoryWebviewMessage): Promise<void> {
        switch (msg.command) {
            case "refresh":
                await this.refresh();
                break;
            case "selectSourceRef":
                // Routed to the extension command, which runs the quick-pick and
                // calls back into setScope() with the chosen ref.
                await vscode.commands.executeCommand(
                    "composePreview.history.selectSourceRef",
                );
                break;
            case "loadImage":
                if (msg.id) {
                    await this.sendImage(msg.id, "expansion");
                }
                break;
            case "loadThumb":
                if (msg.id) {
                    await this.sendImage(msg.id, "thumb");
                }
                break;
            case "openSource":
                if (msg.sourceFile) {
                    const uri = vscode.Uri.file(msg.sourceFile);
                    await vscode.window.showTextDocument(uri);
                }
                break;
            case "diff":
                if (msg.fromId && msg.toId) {
                    await this.runDiff(msg.fromId, msg.toId);
                }
                break;
            case "requestDiff":
                if (
                    msg.id &&
                    (msg.against === "current" || msg.against === "previous")
                ) {
                    await this.runPairDiff(msg.id, msg.against);
                }
                break;
        }
    }

    private async runPairDiff(
        id: string,
        against: "current" | "previous",
    ): Promise<void> {
        if (!this.view) {
            return;
        }
        const scope = this.currentScope;
        if (!scope) {
            this.view.webview.postMessage({
                command: "diffPairError",
                id,
                against,
                message: "No active scope.",
            });
            return;
        }
        try {
            const left = await this.source.read(id);
            if (!left) {
                this.view.webview.postMessage({
                    command: "diffPairError",
                    id,
                    against,
                    message: "Entry not found.",
                });
                return;
            }
            const leftEntry = left.entry as {
                previewId?: string;
                timestamp?: string;
            };
            const previewId = leftEntry.previewId ?? scope.previewId;
            if (!previewId) {
                this.view.webview.postMessage({
                    command: "diffPairError",
                    id,
                    against,
                    message: "Entry has no previewId.",
                });
                return;
            }

            let right: HistoryReadResult | null = null;
            let rightLabel = "";
            // Entry id of the comparison side, for sidecar-based semantics lookup. Null for the
            // synthetic "current render" (no sidecar / no captured semantics).
            let rightId: string | null = null;
            if (against === "current") {
                const synthList = currentRendersFor(scope).list(previewId);
                const synth = synthList.entries[0] as
                    | { id?: string; timestamp?: string }
                    | undefined;
                if (!synth?.id) {
                    this.view.webview.postMessage({
                        command: "diffPairError",
                        id,
                        against,
                        message: "No live render available for this preview.",
                    });
                    return;
                }
                right = currentRendersFor(scope).read(synth.id);
                rightLabel = `Current · ${formatLabelTime(synth.timestamp)}`;
            } else {
                const list = await this.source.list({ ...scope, previewId });
                // Sort newest-first to match the panel; the entry just *after*
                // the clicked one in this order is the older "previous".
                const sorted = [...list.entries].sort((a, b) => {
                    const at = (a as { timestamp?: string }).timestamp ?? "";
                    const bt = (b as { timestamp?: string }).timestamp ?? "";
                    return bt.localeCompare(at);
                });
                const idx = sorted.findIndex(
                    (e) => (e as { id?: string }).id === id,
                );
                const prev =
                    idx >= 0
                        ? (sorted[idx + 1] as
                              | { id?: string; timestamp?: string }
                              | undefined)
                        : undefined;
                if (!prev?.id) {
                    this.view.webview.postMessage({
                        command: "diffPairError",
                        id,
                        against,
                        message: "No earlier entry for this preview.",
                    });
                    return;
                }
                right = await this.source.read(prev.id);
                rightId = prev.id;
                rightLabel = `Previous · ${formatLabelTime(prev.timestamp)}`;
            }
            if (!right) {
                this.view.webview.postMessage({
                    command: "diffPairError",
                    id,
                    against,
                    message: "Comparison entry not found.",
                });
                return;
            }
            const leftBytes =
                left.pngBytes ??
                (await fs.promises.readFile(left.pngPath)).toString("base64");
            const rightBytes =
                right.pngBytes ??
                (await fs.promises.readFile(right.pngPath)).toString("base64");
            this.view.webview.postMessage({
                command: "diffReady",
                id,
                against,
                leftLabel: `This entry · ${formatLabelTime(leftEntry.timestamp)}`,
                leftImage: leftBytes,
                rightLabel,
                rightImage: rightBytes,
                // Forward each entry's captured compose/semantics tree so the webview can render the
                // semantics data-diff below the pixel diff (#1872). Read straight from the on-disk
                // sidecar (which always carries the full tree) rather than `left.entry` / `right.entry`
                // — the daemon's `history/read` strips `semantics` from the wire (only
                // `history/diff mode=semantics` returns it), so the daemon-backed flow would otherwise
                // always see null. The sidecar read is daemon-independent and matches the local
                // archive the daemon writes to.
                leftSemantics: readSidecarSemantics(scope, id),
                rightSemantics: rightId
                    ? readSidecarSemantics(scope, rightId)
                    : null,
                // Likewise forward each entry's captured compose/theme tokens for the theme
                // data-diff section (#1872), read from the sidecar for the same reason.
                leftTheme: readSidecarTheme(scope, id),
                rightTheme: rightId ? readSidecarTheme(scope, rightId) : null,
                // And each entry's captured a11y/hierarchy nodes for the a11y data-diff (#1872).
                leftA11y: readSidecarA11y(scope, id),
                rightA11y: rightId ? readSidecarA11y(scope, rightId) : null,
            });
        } catch (err) {
            this.view.webview.postMessage({
                command: "diffPairError",
                id,
                against,
                message: (err as Error).message,
            });
        }
    }

    private async sendImage(
        id: string,
        kind: "expansion" | "thumb",
    ): Promise<void> {
        if (!this.view) {
            return;
        }
        const readyCmd = kind === "thumb" ? "thumbReady" : "imageReady";
        const errorCmd = kind === "thumb" ? "thumbError" : "imageError";
        try {
            const result = await this.source.read(id);
            if (!result) {
                this.view.webview.postMessage({
                    command: errorCmd,
                    id,
                    message: "Entry not found.",
                });
                return;
            }
            const bytes =
                result.pngBytes ??
                (await fs.promises.readFile(result.pngPath)).toString("base64");
            this.view.webview.postMessage({
                command: readyCmd,
                id,
                imageData: bytes,
                entry: result.entry,
            });
        } catch (err) {
            this.view.webview.postMessage({
                command: errorCmd,
                id,
                message: (err as Error).message,
            });
        }
    }

    private async runDiff(fromId: string, toId: string): Promise<void> {
        if (!this.view) {
            return;
        }
        try {
            const result = await this.source.diff(fromId, toId);
            this.view.webview.postMessage({
                command: "diffResult",
                fromId,
                toId,
                result,
            });
        } catch (err) {
            this.view.webview.postMessage({
                command: "diffError",
                fromId,
                toId,
                message: (err as Error).message,
            });
        }
    }

    private getHtml(webview: vscode.Webview): string {
        const nonce = getNonce();
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "codicon.css"),
        );
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "preview.css"),
        );
        const scriptUri = webview.asWebviewUri(
            vscode.Uri.joinPath(
                this.extensionUri,
                "media",
                "webview",
                "history.js",
            ),
        );
        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; font-src ${webview.cspSource}; style-src ${webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
    <style nonce="${nonce}">
      body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); margin: 0; padding: 8px; }
      .toolbar { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
      .toolbar select { background: var(--vscode-dropdown-background); color: var(--vscode-dropdown-foreground);
                        border: 1px solid var(--vscode-dropdown-border); padding: 2px 6px; }
      .timeline { display: flex; flex-direction: column; gap: 4px; }
      .row { display: grid; grid-template-columns: 56px 1fr auto; gap: 8px; align-items: center;
             padding: 4px; border: 1px solid transparent; cursor: pointer; }
      .row.selected { border-color: var(--vscode-focusBorder); }
      .row:hover { background: var(--vscode-list-hoverBackground); }
      .row .thumb { width: 56px; height: 56px; background: var(--vscode-editorWidget-background);
                    display: flex; align-items: center; justify-content: center; overflow: hidden; }
      .row .thumb img { max-width: 100%; max-height: 100%; }
      .row .meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
      .row .ts { font-weight: 600; }
      .row .sub { color: var(--vscode-descriptionForeground); font-size: 90%;
                  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .row .badge { font-size: 80%; padding: 1px 6px; border-radius: 8px;
                    background: var(--vscode-badge-background); color: var(--vscode-badge-foreground); }
      .row .changed-dot { width: 8px; height: 8px; border-radius: 50%;
                          background: var(--vscode-charts-yellow); display: inline-block; margin-right: 4px; }
      .row .main-dot { width: 8px; height: 8px; border-radius: 50%;
                       background: var(--vscode-charts-orange, #d18616);
                       display: inline-block; margin-right: 4px; }
      .message { padding: 12px; color: var(--vscode-descriptionForeground); }
      .expanded { padding: 8px; background: var(--vscode-editorWidget-background); }
      .expanded img { max-width: 100%; }
      .actions { display: flex; gap: 6px; margin-top: 6px; }
      .actions button { background: var(--vscode-button-background); color: var(--vscode-button-foreground);
                        border: 0; padding: 3px 8px; cursor: pointer; }
      .actions button[disabled] { opacity: 0.5; cursor: default; }
      pre.metadata { font-size: 90%; max-height: 200px; overflow: auto;
                     background: var(--vscode-textCodeBlock-background); padding: 6px; }
      .diff-inline { padding: 8px; background: var(--vscode-editorWidget-background);
                     margin-top: 8px; border-left: 2px solid var(--vscode-focusBorder); }
      .row { grid-template-columns: 56px 1fr auto auto; }
      .row-actions { display: flex; gap: 2px; opacity: 0;
                     transition: opacity 100ms ease; }
      .row:hover .row-actions, .row:focus-within .row-actions { opacity: 1; }
      .row-action { width: 22px; height: 22px; }
      .diff-expanded { padding: 8px; background: var(--vscode-editorWidget-background); }
      .diff-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
      .diff-pane { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
      .diff-pane-label { font-size: 90%; color: var(--vscode-descriptionForeground);
                         white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .diff-pane img { max-width: 100%; height: auto; display: block;
                       background: var(--vscode-editor-background); }
      .diff-pane-empty { font-size: 90%; padding: 12px; text-align: center;
                         color: var(--vscode-descriptionForeground);
                         background: var(--vscode-editor-background); }
      .scope-chip { display: inline-flex; align-items: center; gap: 6px;
                    padding: 2px 8px; margin-bottom: 8px;
                    background: var(--vscode-badge-background);
                    color: var(--vscode-badge-foreground);
                    border-radius: 10px; font-size: 90%;
                    max-width: 100%; overflow: hidden; text-overflow: ellipsis;
                    white-space: nowrap; }
      .scope-chip[hidden] { display: none; }
      .scope-chip .codicon { font-size: 12px; opacity: 0.85; }
    </style>
</head>
<body>
    <history-app></history-app>
    <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
    }
}

/**
 * The two surfaces the panel reads from. Both implement the same shape so
 * the panel doesn't know whether the daemon or the FS reader served the
 * data — `extension.ts` decides per-call based on gate health.
 */
export interface HistorySource {
    list(scope: HistoryScope): Promise<HistoryListResult>;
    read(id: string): Promise<HistoryReadResult | null>;
    diff(fromId: string, toId: string): Promise<unknown | null>;
}

export interface HistoryScope {
    moduleId: string;
    /** Module project directory; the panel needs this to resolve the
     *  fallback `historyDir` when the daemon is down. */
    projectDir: string;
    /** Optional preview filter; when null, the panel shows every preview
     *  in the module's history. */
    previewId?: string;
    /** Display name for the previewId filter (function or `@Preview` name).
     *  Surfaced in the panel's toolbar as a chip when set so the user can
     *  see why entries are narrowed. Not used for filtering. */
    previewLabel?: string;
    /** Reporting-branch source (#1872). When set to a full git ref (e.g.
     *  `refs/heads/preview/main`), the panel reads history from that pushed
     *  reporting branch via the daemon's on-demand `ref` param instead of the
     *  local working tree. Undefined ⇒ local history. Reading a ref is
     *  daemon-only (the FS reader can't read a git ref), so the panel shows
     *  empty rather than misleading local entries when the daemon is down. */
    gitRef?: string;
}

/**
 * `HistorySource` factory that picks the live daemon when healthy, otherwise
 * the FS reader. Used by extension.ts so the panel always has a source.
 */
export function buildHistorySource(opts: BuildSourceOptions): HistorySource {
    return {
        list: async (scope) => {
            let result: HistoryListResult | null = null;
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    result = await opts.daemonList(scope);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon list failed for ${scope.moduleId}, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            if (scope.gitRef) {
                // Reporting-branch view is daemon-only: the FS reader and the
                // synthetic current-render fallback below are both local, so
                // falling back would mask a down daemon with the wrong (local)
                // history. Empty ⇒ the panel shows its "no history" hint.
                return result ?? { entries: [], totalCount: 0 };
            }
            if (!result) {
                result = new HistoryReader(historyDirFor(scope)).list({
                    previewId: scope.previewId,
                });
            }
            // Greenfield UX: when no recorded history exists yet (default
            // path with the daemon disabled, or a freshly-warmed daemon
            // that hasn't yet observed a render) but the Gradle render
            // path has already produced PNGs, surface those as a single
            // synthetic page so the panel isn't empty after the user's
            // first render.
            if (result.entries.length === 0) {
                const synth = currentRendersFor(scope).list(scope.previewId);
                if (synth.entries.length > 0) {
                    return synth;
                }
            }
            return result;
        },
        read: async (id) => {
            const scope = opts.getCurrentScope();
            if (!scope) {
                return null;
            }
            if (CurrentRendersHistory.isSyntheticId(id)) {
                return currentRendersFor(scope).read(id);
            }
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    return await opts.daemonRead(id);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon read failed for ${id}, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            // Reporting-branch entries only exist on the daemon side; never
            // resolve a git-ref id against the local FS reader.
            if (scope.gitRef) {
                return null;
            }
            return new HistoryReader(historyDirFor(scope)).read(id);
        },
        diff: async (fromId, toId) => {
            const scope = opts.getCurrentScope();
            if (!scope) {
                return null;
            }
            // Synthetic "current render" entries don't have a stable prior
            // — diffing them is meaningless. Fall through to null so the
            // panel surfaces "Diff unavailable" instead of crashing.
            if (
                CurrentRendersHistory.isSyntheticId(fromId) ||
                CurrentRendersHistory.isSyntheticId(toId)
            ) {
                return null;
            }
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    return await opts.daemonDiff(fromId, toId);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon diff failed, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            // Reporting-branch entries are daemon-only — no local FS diff.
            if (scope.gitRef) {
                return null;
            }
            return new HistoryReader(historyDirFor(scope)).diff(
                fromId,
                toId,
                "metadata",
            );
        },
    };
}

function historyDirFor(scope: HistoryScope): string {
    return `${scope.projectDir}/.compose-preview-history`;
}

/**
 * Reads an entry's raw `compose/semantics` tree straight from its on-disk sidecar (#1872). The
 * sidecar always carries the full tree, unlike the daemon's `history/read` wire shape which strips
 * it — so this is daemon-independent. Best-effort: returns null for a missing sidecar, unparseable
 * JSON, or an entry that captured no semantics.
 */
function readSidecarSemantics(scope: HistoryScope, id: string): unknown {
    try {
        const result = new HistoryReader(historyDirFor(scope)).read(id);
        return (
            (result?.entry as { semantics?: unknown } | undefined)?.semantics ??
            null
        );
    } catch {
        return null;
    }
}

/**
 * Reads an entry's raw `compose/theme` resolved tokens straight from its on-disk sidecar (#1872),
 * for the theme data-diff. Same rationale as [readSidecarSemantics]: the sidecar carries the full
 * payload the daemon's `history/read` wire shape strips. Best-effort → null on any failure.
 */
function readSidecarTheme(scope: HistoryScope, id: string): unknown {
    try {
        const result = new HistoryReader(historyDirFor(scope)).read(id);
        return (
            (result?.entry as { theme?: unknown } | undefined)?.theme ?? null
        );
    } catch {
        return null;
    }
}

/**
 * Reads an entry's raw `a11y/hierarchy` nodes straight from its on-disk sidecar (#1872), for the
 * a11y data-diff. Same rationale as [readSidecarSemantics]. Best-effort → null on any failure.
 */
function readSidecarA11y(scope: HistoryScope, id: string): unknown {
    try {
        const result = new HistoryReader(historyDirFor(scope)).read(id);
        return (
            (result?.entry as { a11yHierarchy?: unknown } | undefined)
                ?.a11yHierarchy ?? null
        );
    } catch {
        return null;
    }
}

function currentRendersFor(scope: HistoryScope): CurrentRendersHistory {
    return new CurrentRendersHistory({
        buildDir: `${scope.projectDir}/build/compose-previews`,
        moduleId: scope.moduleId,
    });
}

interface BuildSourceOptions {
    isDaemonReady: (moduleId: string) => boolean;
    daemonList: (scope: HistoryScope) => Promise<HistoryListResult>;
    daemonRead: (id: string) => Promise<HistoryReadResult>;
    daemonDiff: (fromId: string, toId: string) => Promise<unknown>;
    /** Live accessor for the active scope. The panel's read() / diff()
     *  callbacks don't get scope as an argument (unlike list), so they
     *  reach into the extension's mutable scope ref through this getter
     *  rather than capturing a stale snapshot at construction time. */
    getCurrentScope: () => HistoryScope | null;
    logger?: { appendLine(s: string): void };
}

interface HistoryWebviewMessage {
    command: string;
    id?: string;
    sourceFile?: string;
    fromId?: string;
    toId?: string;
    against?: "current" | "previous";
}

function formatLabelTime(iso: string | undefined): string {
    if (!iso) {
        return "(unknown time)";
    }
    const t = Date.parse(iso);
    if (isNaN(t)) {
        return iso;
    }
    return new Date(t).toLocaleString(undefined, {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
    });
}

function matchesScope(
    entry: { previewId?: string; module?: string },
    scope: HistoryScope | null,
): boolean {
    if (!scope || !scope.previewId) {
        return false;
    }
    if (entry.module !== scope.moduleId) {
        return false;
    }
    if (entry.previewId !== scope.previewId) {
        return false;
    }
    return true;
}

function getNonce(): string {
    let text = "";
    const possible =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}
