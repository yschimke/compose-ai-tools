import { packageQualifiedSourcePath } from "./sourcePath";
import { AccessibilityFinding, AccessibilityNode, PreviewInfo } from "./types";

export interface RegistryEntry {
    preview: PreviewInfo;
    module: string;
    imageBase64?: string;
}

/**
 * Tiny event emitter with the same `onDidChange((handler) => disposable)` shape consumers
 * expect from VS Code's EventEmitter, but without a `vscode` import so the registry stays
 * unit-testable from plain mocha (no `vscode-test-electron` runner required).
 */
class SimpleEmitter {
    private listeners: Array<() => void> = [];
    fire(): void {
        for (const l of [...this.listeners]) {
            try {
                l();
            } catch {
                /* ignore — match vscode.EventEmitter swallow behaviour */
            }
        }
    }
    event = (handler: () => void): { dispose(): void } => {
        this.listeners.push(handler);
        return {
            dispose: () => {
                const idx = this.listeners.indexOf(handler);
                if (idx >= 0) this.listeners.splice(idx, 1);
            },
        };
    };
    dispose(): void {
        this.listeners = [];
    }
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
    private _onDidChange = new SimpleEmitter();
    readonly onDidChange = this._onDidChange.event;

    /**
     * Replace the entries for [module] with the fresh manifest set. Entries whose `previewId`
     * survives the replacement keep their `imageBase64` and a11y payload — only the
     * [PreviewInfo] metadata refreshes. This is the load-bearing invariant for the panel's
     * "preload paints cached images; refresh updates metadata in place" flow: the prior wipe-
     * then-recreate behaviour silently dropped every cached image the moment refresh() landed,
     * forcing the webview to render placeholders until the daemon's 15-25 s spawn finished.
     *
     * Entries whose `previewId` is gone from the fresh set are removed entirely.
     */
    replaceModule(module: string, previews: PreviewInfo[]): void {
        const freshIds = new Set<string>();
        for (const p of previews) {
            if (p.sourceFile) freshIds.add(p.id);
        }
        // Drop entries this module owned that are no longer in the fresh set. Entries that
        // survive keep their imageBase64 / a11y payload; we patch the metadata below.
        for (const [k, v] of this.bySourceAndName) {
            if (v.module === module && !freshIds.has(v.preview.id)) {
                this.bySourceAndName.delete(k);
            }
        }
        for (const [k, v] of this.byId) {
            if (v.module === module && !freshIds.has(k)) {
                this.byId.delete(k);
            }
        }
        for (const p of previews) {
            if (!p.sourceFile) continue;
            const existing = this.byId.get(p.id);
            // Adopt entries that belong to this module, OR placeholder entries created by
            // [setImage] before any module owned them (module="" sentinel). The placeholder
            // case is what allows daemon renders that arrive before the manifest to survive
            // the next replaceModule call instead of being wiped + lost.
            if (
                existing &&
                (existing.module === module || existing.module === "")
            ) {
                // Preserve imageBase64 and any in-place a11y payload by mutating the
                // existing entry's `preview` reference. The byId / bySourceAndName maps
                // share the same RegistryEntry object so both views update together.
                existing.module = module;
                existing.preview = {
                    ...p,
                    a11yFindings: existing.preview.a11yFindings,
                    a11yNodes: existing.preview.a11yNodes,
                };
                // The (sourceFile, functionName) key can shift if the manifest renamed the
                // file — re-key so `find()` lookups still resolve.
                const freshKey = keyOf(p.sourceFile, p.functionName);
                // Drop any stale sourceFile/functionName entry pointing at this preview.
                for (const [k, v] of this.bySourceAndName) {
                    if (v === existing && k !== freshKey) {
                        this.bySourceAndName.delete(k);
                    }
                }
                this.bySourceAndName.set(freshKey, existing);
            } else {
                const entry: RegistryEntry = { preview: p, module };
                this.bySourceAndName.set(
                    keyOf(p.sourceFile, p.functionName),
                    entry,
                );
                this.byId.set(p.id, entry);
            }
        }
        this._onDidChange.fire();
    }

    /**
     * Record image bytes against [previewId]. If no entry exists yet — because the daemon's
     * render landed before any manifest set this id, or because of an id-format mismatch
     * between manifest and daemon — create a placeholder entry with the bytes attached so a
     * subsequent [replaceModule] can merge it. Prevents the silent-bail-on-missing-entry leak
     * the verify command surfaced (host registry empty even though daemon renders completed).
     */
    setImage(previewId: string, imageBase64: string): void {
        const entry = this.byId.get(previewId);
        if (entry) {
            entry.imageBase64 = imageBase64;
            this._onDidChange.fire();
            return;
        }
        // Bytes-only entry — no preview metadata yet. [getImage] returns the bytes; [find]
        // (which keys on sourceFile + functionName) will start resolving once the next
        // [replaceModule] supplies the metadata. Marker module='' distinguishes these from
        // properly-registered entries so a per-module sweep doesn't wipe them prematurely.
        const placeholder: RegistryEntry = {
            preview: { id: previewId } as PreviewInfo,
            module: "",
            imageBase64,
        };
        this.byId.set(previewId, placeholder);
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

    /** Image bytes the registry currently holds for [previewId], or `null` when the entry
     *  is unknown or has never been set. Used by the consistency-verify command to detect
     *  "panel shows placeholder while disk has a PNG" without exposing the internal Map. */
    getImage(previewId: string): string | null {
        return this.byId.get(previewId)?.imageBase64 ?? null;
    }

    dispose(): void {
        this._onDidChange.dispose();
    }
}

function keyOf(sourceFile: string, funcName: string): string {
    return `${sourceFile}#${funcName}`;
}
