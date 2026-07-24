// RemapContext: ID mapping + uniqueification during macro (loom) expansion.
// Faithful port of RemapContext.java.
//
// Tiered ID system:
//   Tier 1 — System Globals (0..41): fixed meaning, never remapped.
//   Tier 2 — Macro-Local (0x4000..0x4FFF): always uniqueified.
//   Regular IDs: uniqueified only when inside a macro expansion.

import { isSystemGlobal, isMacroLocal, idFromNan, asNan, idFromLong } from '../Utils';

/** Minimal document surface RemapContext needs (avoids a CoreDocument import cycle). */
export interface LoomDocument {
    getNextId(): number;
}

export class RemapContext {
    private mIdMap: Map<number, number>;
    private mIsMapShared: boolean;
    private readonly mDocument: LoomDocument | null;
    private readonly mIsInsideMacro: boolean;

    constructor(document: LoomDocument | null);
    constructor(idMap: Map<number, number>, document: LoomDocument | null, isInsideMacro: boolean);
    constructor(a: LoomDocument | Map<number, number> | null, document?: LoomDocument | null, isInsideMacro?: boolean) {
        if (a instanceof Map) {
            this.mIdMap = a;
            this.mDocument = document ?? null;
            this.mIsInsideMacro = isInsideMacro ?? false;
        } else {
            this.mIdMap = new Map<number, number>();
            this.mDocument = (a as LoomDocument | null) ?? null;
            this.mIsInsideMacro = false;
        }
        this.mIsMapShared = false;
    }

    /** An identity remapper that passes every id through unchanged. */
    static identity(): RemapContext {
        return new IdentityRemapContext();
    }

    isInsideMacro(): boolean {
        return this.mIsInsideMacro;
    }

    /** Returns a new RemapContext with the specified insideMacro state (shares the map). */
    withInsideMacro(insideMacro: boolean): RemapContext {
        if (this.mIsInsideMacro === insideMacro) {
            return this;
        }
        this.mIsMapShared = true;
        const next = new RemapContext(this.mIdMap, this.mDocument, insideMacro);
        next.mIsMapShared = true;
        return next;
    }

    /** A forked context that inherits current mappings (copy-on-write). */
    fork(): RemapContext {
        this.mIsMapShared = true;
        const forked = new RemapContext(this.mIdMap, this.mDocument, this.mIsInsideMacro);
        forked.mIsMapShared = true;
        return forked;
    }

    private ensureMapWritable(): void {
        if (this.mIsMapShared) {
            this.mIdMap = new Map<number, number>(this.mIdMap);
            this.mIsMapShared = false;
        }
    }

    addMapping(originalId: number, newId: number): void {
        this.ensureMapWritable();
        this.mIdMap.set(originalId, newId);
    }

    /** Look up the translated value of an id; returns the original if unmapped. */
    resolveId(id: number): number {
        const remapped = this.mIdMap.get(id);
        return remapped !== undefined ? remapped : id;
    }

    /** Resolve a NaN-encoded float id. Non-NaN values pass through. */
    resolveNanId(v: number): number {
        if (!Number.isNaN(v)) {
            return v;
        }
        const id = idFromNan(v);
        const mapped = this.resolveId(id);
        return mapped === id ? v : asNan(mapped);
    }

    /** Resolve a NaN-encoded long id (see Utils.longIdFromNan). */
    resolveLongNanId(v: number): number {
        const decoded = idFromLong(v);
        const id = decoded | 0;
        if (id !== decoded) {
            return v; // not an encoded id
        }
        const mapped = this.resolveId(id);
        return mapped === id ? v : mapped + 0x100000000;
    }

    /** Declare an id read off the wire, uniqueifying when required. */
    declareId(originalId: number): number {
        if (originalId === -1) {
            return -1;
        }
        const mapped = this.mIdMap.get(originalId);
        if (mapped !== undefined) {
            return mapped;
        }
        if (isMacroLocal(originalId)) {
            return this.allocateNewId(originalId);
        }
        if (this.mIsInsideMacro && !isSystemGlobal(originalId)) {
            return this.allocateNewId(originalId);
        }
        return originalId;
    }

    private allocateNewId(originalId: number): number {
        if (this.mDocument === null) {
            throw new Error('Cannot allocate ID without a document');
        }
        const newId = this.mDocument.getNextId();
        this.addMapping(originalId, newId);
        return newId;
    }

    getIdMap(): Map<number, number> {
        return this.mIdMap;
    }
}

/** Identity variant: everything passes through unchanged. */
class IdentityRemapContext extends RemapContext {
    constructor() {
        super(new Map<number, number>(), null, false);
    }
    declareId(id: number): number { return id; }
    resolveId(id: number): number { return id; }
    resolveNanId(v: number): number { return v; }
    resolveLongNanId(v: number): number { return v; }
    withInsideMacro(_insideMacro: boolean): RemapContext { return this; }
}
