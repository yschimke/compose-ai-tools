// Host-side wrapper for `openResourceFile` messages from the
// `resources/used` focus-mode table. Delegates resolution to the pure
// `resourceFileResolverCore` module so the testable bits don't pull in
// the `vscode` runtime module; this file owns the editor / workspace
// API calls.

import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import {
    findValuesEntry,
    isValuesType,
    type OpenResourceRequest,
    resolveResourceFilePath,
    type ResolverFs,
} from "./resourceFileResolverCore";

export type { OpenResourceRequest } from "./resourceFileResolverCore";

/**
 * Editor / workspace touch points the wrapper needs. Production wires
 * the VS Code APIs directly (see `defaultDeps` below); the extension
 * host's message handler hands in a logger so failures surface in the
 * output channel.
 */
export interface ResourceFileResolverDeps {
    fs: ResolverFs;
    openTextDocument(absolutePath: string): Promise<vscode.TextDocument>;
    showTextDocument(doc: vscode.TextDocument): Promise<vscode.TextEditor>;
    logLine(message: string): void;
}

/**
 * Main entry: resolve, open, select. Awaitable so callers (and tests)
 * can sequence on completion; the message handler in `extension.ts`
 * doesn't need the return value.
 */
export async function openResourceFile(
    request: OpenResourceRequest,
    deps: ResourceFileResolverDeps = defaultDeps,
): Promise<void> {
    const target = await resolveResourceFilePath(request, deps.fs);
    if (!target) {
        deps.logLine(
            `openResourceFile: no match for ${request.resourceType}/${request.resourceName} ` +
                `(resolvedFile=${request.resolvedFile ?? "<none>"})`,
        );
        return;
    }
    try {
        const doc = await deps.openTextDocument(target);
        const editor = await deps.showTextDocument(doc);
        if (isValuesType(request.resourceType)) {
            const range = findValuesEntry(
                doc.getText(),
                request.resourceType,
                request.resourceName,
            );
            if (range) {
                const start = doc.positionAt(range.start);
                const end = doc.positionAt(range.end);
                editor.selection = new vscode.Selection(start, end);
                editor.revealRange(
                    new vscode.Range(start, end),
                    vscode.TextEditorRevealType.InCenter,
                );
            }
        }
    } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e);
        deps.logLine(
            `openResourceFile failed for ${request.resourceType}/${request.resourceName}: ${message}`,
        );
    }
}

const defaultFs: ResolverFs = {
    findFiles: async (include, exclude, maxResults) => {
        const uris = await vscode.workspace.findFiles(
            include,
            exclude ?? undefined,
            maxResults,
        );
        return uris.map((u) => u.fsPath);
    },
    readTextFile: async (absolutePath) => {
        const doc = await vscode.workspace.openTextDocument(absolutePath);
        return doc.getText();
    },
    fileExists: (filePath) => {
        try {
            return fs.existsSync(filePath);
        } catch {
            return false;
        }
    },
    isAbsolutePath: (filePath) => path.isAbsolute(filePath),
};

const defaultDeps: ResourceFileResolverDeps = {
    fs: defaultFs,
    openTextDocument: (absolutePath) =>
        Promise.resolve(
            vscode.workspace.openTextDocument(vscode.Uri.file(absolutePath)),
        ),
    showTextDocument: (doc) =>
        Promise.resolve(vscode.window.showTextDocument(doc)),
    logLine: () => {
        // Default is silent — extension host injects a logger.
    },
};
