import * as vscode from "vscode";
import { packageQualifiedSourcePath } from "./sourcePath";
import { AccessibilityFinding, AccessibilityNode, PreviewInfo } from "./types";

export interface RegistryEntry {
    preview: PreviewInfo;
    module: string;
    imageBase64?: string;
}

/**
 * In-memory mirror of the latest preview manifest, keyed for fast lookup by
 * (package-qualified source path, function name). CodeLens and Hover providers
 * read this rather than re-parsing `previews.json` per call.
 *
 * Multiple `@Preview` annotations on one function collapse to a single entry
 * — CodeLens only needs a position + action, and the hover shows one image.
 * The side panel is the authoritative per-preview view.
 */
export class PreviewRegistry {
    private bySourceAndName = new Map<string, RegistryEntry>();
    private byId = new Map<string, RegistryEntry>();
    private _onDidChange = new vscode.EventEmitter<void>();
    readonly onDidChange = this._onDidChange.event;

    replaceModule(module: string, previews: PreviewInfo[]): void {
        for (const [k, v] of this.bySourceAndName) {
            if (v.module === module) {
                this.bySourceAndName.delete(k);
            }
        }
        for (const [k, v] of this.byId) {
            if (v.module === module) {
                this.byId.delete(k);
            }
        }
        for (const p of previews) {
            if (!p.sourceFile) {
                continue;
            }
            const entry: RegistryEntry = { preview: p, module };
            this.bySourceAndName.set(
                keyOf(p.sourceFile, p.functionName),
                entry,
            );
            this.byId.set(p.id, entry);
        }
        this._onDidChange.fire();
    }

    setImage(previewId: string, imageBase64: string): void {
        const entry = this.byId.get(previewId);
        if (!entry) {
            return;
        }
        entry.imageBase64 = imageBase64;
        this._onDidChange.fire();
    }

    /**
     * D2 — daemon-attached `a11y/atf` payload routed in by the scheduler. Replaces what the
     * Gradle sidecar path used to populate via [GradleService.readA11yById]. Either argument may
     * be omitted to leave the existing field untouched.
     */
    setA11y(
        previewId: string,
        opts: {
            findings?: AccessibilityFinding[];
            nodes?: AccessibilityNode[];
        },
    ): void {
        const entry = this.byId.get(previewId);
        if (!entry) {
            return;
        }
        if (opts.findings !== undefined) {
            entry.preview.a11yFindings = opts.findings;
        }
        if (opts.nodes !== undefined) {
            entry.preview.a11yNodes = opts.nodes;
        }
        this._onDidChange.fire();
    }

    find(filePath: string, functionName: string): RegistryEntry | undefined {
        return this.bySourceAndName.get(
            keyOf(packageQualifiedSourcePath(filePath), functionName),
        );
    }

    dispose(): void {
        this._onDidChange.dispose();
    }
}

function keyOf(sourceFile: string, funcName: string): string {
    return `${sourceFile}#${funcName}`;
}
