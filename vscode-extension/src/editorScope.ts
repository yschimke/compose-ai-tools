import { ModuleInfo } from "./gradleService";

/**
 * Active editor scope = which Kotlin file the preview panel is currently
 * pinned to, plus the Gradle module that owns it. Updated whenever a refresh
 * successfully resolves a module; consumed by the focus-change handlers, the
 * save-driven refresh path, the LSP compile-gate retry, and the data-extension
 * progress filter.
 *
 * The pair is conceptually a single value — set together, read together,
 * never one without the other — so the class enforces that with a single
 * [set] call. Reading both as nullable fields keeps the call sites'
 * "is there a scope yet?" checks ergonomic.
 */
export class EditorScope {
    private _file: string | null = null;
    private _module: ModuleInfo | null = null;

    /** The file path the panel is currently scoped to, or `null` if nothing
     *  has resolved yet. Webview-initiated refreshes reuse this rather than
     *  falling back to `activeTextEditor`, which can drift when the webview
     *  has focus (undefined) or resolve to an unrelated editor. */
    get file(): string | null {
        return this._file;
    }

    /** The Gradle module that owns [file], or `null` if no scope is set. */
    get module(): ModuleInfo | null {
        return this._module;
    }

    set(file: string | null, module: ModuleInfo | null): void {
        this._file = file;
        this._module = module;
    }

    /** True iff [module] is the active scope's owning module. */
    ownsModule(module: ModuleInfo): boolean {
        return this._module?.modulePath === module.modulePath;
    }

    /** True iff [filePath] is the active scope file. */
    isCurrentFile(filePath: string): boolean {
        return this._file === filePath;
    }
}

/**
 * `previewId → owning module` lookup table. Populated from every refresh that
 * loads a module's preview manifest; consumed by per-preview action handlers
 * (chip toggles, focus inspector, history requests) that need to route work
 * back to the right module without re-resolving from the file path.
 *
 * Replaces a bare `Map<string, ModuleInfo>` so the "purge stale entries for
 * this module, then re-add the fresh ones" pattern — previously inlined at
 * multiple call sites — lives behind a named method whose intent is explicit.
 */
export class PreviewModuleIndex {
    private readonly byPreview = new Map<string, ModuleInfo>();

    get(previewId: string): ModuleInfo | undefined {
        return this.byPreview.get(previewId);
    }

    set(previewId: string, module: ModuleInfo): void {
        this.byPreview.set(previewId, module);
    }

    /**
     * Atomically replace every entry currently mapped to [module] with the
     * supplied [previewIds]. Entries that map to other modules are untouched.
     * Use this when a fresh manifest for one module has arrived and the
     * index needs to converge to its new shape without leaking stale
     * preview IDs the manifest no longer mentions.
     */
    replaceModule(module: ModuleInfo, previewIds: Iterable<string>): void {
        const moduleKey = module.modulePath;
        for (const [id, owner] of this.byPreview) {
            if (owner.modulePath === moduleKey) {
                this.byPreview.delete(id);
            }
        }
        for (const id of previewIds) {
            this.byPreview.set(id, module);
        }
    }

    /** Drop the whole table. Used on full panel reset / mode switch. */
    clear(): void {
        this.byPreview.clear();
    }

    /** Snapshot iterator over the entries. Returned as a list so the caller
     *  can mutate the underlying map mid-iteration without ConcurrentModification
     *  concerns (the prior code defensively spread `[...map.entries()]` at each
     *  site for this reason). */
    entries(): Array<[string, ModuleInfo]> {
        return Array.from(this.byPreview.entries());
    }

    size(): number {
        return this.byPreview.size;
    }
}
