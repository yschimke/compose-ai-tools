// PatternOperations: macro ("pattern" / loom) and referenced-operations support.
//
// These ops now actually EXPAND at load time (see materialize()). The container
// forms (PatternDefine inline-body, PatternForEach, ReferencedOperations,
// PatternInflation, PatternBlock) capture their body bytes from the original
// input buffer so the body can be re-inflated through a remapping LoomWireBuffer
// for ID uniqueification. Body-byte capture itself happens in a pre-pass in
// CoreDocument over the flat op list (see captureLoomBodies).

import { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';
import type { ExpansionContext } from './ExpansionContext';
import type { LoomManager } from './LoomManager';
import { RemapContext } from './RemapContext';
import { Header } from '../Header';

// ---- Container ops: expose getList() so the player treats them as containers ----

export class ReferencedOperations extends Operation {
    static readonly OP_CODE = 142;
    private mId: number;
    private mList: Operation[] = [];
    private mBody: Uint8Array | null = null;

    constructor(id: number) { super(); this.mId = id; }

    getId(): number { return this.mId; }
    setId(id: number): void { this.mId = id; }
    getList(): Operation[] { return this.mList; }
    setBody(bytes: Uint8Array): void { this.mBody = bytes; }
    getBody(): Uint8Array | null { return this.mBody; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        // Register this object so IncludeReferencedOperations can resolve it.
        const c = context as unknown as { putObject?: (id: number, op: Operation) => void };
        if (typeof c.putObject === 'function') c.putObject(this.mId, this);
    }

    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        // Body bytes are captured in CoreDocument.captureLoomBodies. Fall back to
        // the base behaviour (emit self + expand children) — matching Java's
        // super.materialize after ensuring body bytes exist.
        super.materialize(context, result, loomManager);
    }

    deepToString(indent: string): string {
        let s = `${indent}ReferencedOperations[${this.mId}]\n`;
        for (const op of this.mList) s += op.deepToString(indent + '  ') + '\n';
        return s;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ReferencedOperations(buffer.declareId()));
    }
}

export class PatternInflation extends Operation {
    static readonly OP_CODE = 247;
    private mId: number;
    private mArgIds: Int32Array;
    private mList: Operation[] = [];

    constructor(id: number, argIds: Int32Array) {
        super(); this.mId = id; this.mArgIds = argIds;
    }

    getId(): number { return this.mId; }
    getArgIds(): Int32Array { return this.mArgIds; }
    getList(): Operation[] { return this.mList; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(_context: RemoteContext): void { /* expanded during inflation */ }

    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        const def = loomManager.resolve(this, context.getDocument());
        if (def === null) {
            // Macro not found: emit this call unchanged.
            result.push(this);
            return;
        }

        const bodyBytes = def.getBody();
        if (bodyBytes === null || bodyBytes.length === 0) {
            return;
        }

        const ctx = context.getRemapContext().fork().withInsideMacro(true);
        // Seed param -> arg mapping. Args were already translated when this call
        // was read; map the template's paramId to the already-translated argId.
        const params = def.getParamIds();
        const args = this.mArgIds;
        const n = Math.min(params.length, args.length);
        for (let i = 0; i < n; i++) {
            ctx.addMapping(params[i], args[i]);
        }
        // Record blocks so PatternArgument.materialize can find them.
        context.recordBlocks(this);

        const templateOps = context.inflateBody(bodyBytes, ctx);
        if (!templateOps || templateOps.length === 0) {
            return;
        }

        const child = makeChild(context, loomManager, ctx,
            context.getBlocksForCall(this), context.getDepth() + 1);
        child.expandRecursive(templateOps, result, loomManager);
    }

    deepToString(indent: string): string {
        let s = `${indent}MacroCall[${this.mId}]\n`;
        for (const op of this.mList) s += op.deepToString(indent + '  ') + '\n';
        return s;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readId();
        const argCount = buffer.readInt();
        const argIds = new Int32Array(argCount);
        for (let i = 0; i < argCount; i++) {
            argIds[i] = buffer.readId();
        }
        operations.push(new PatternInflation(id, argIds));
    }
}

export class PatternBlock extends Operation {
    static readonly OP_CODE = 249;
    private mParamIndex: number;
    private mList: Operation[] = [];

    constructor(paramIndex: number) { super(); this.mParamIndex = paramIndex; }

    getParamIndex(): number { return this.mParamIndex; }
    getList(): Operation[] { return this.mList; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(_context: RemoteContext): void { /* handled during expansion */ }

    // No materialize override: blocks are consumed by PatternArgument via the
    // ExpansionContext block map; they should not emit themselves. We therefore
    // suppress default emission.
    materialize(_context: ExpansionContext, _result: Operation[], _loomManager: LoomManager): void {
        // intentionally empty — block contents are pulled by PatternArgument.
    }

    deepToString(indent: string): string {
        let s = `${indent}MacroBlock[${this.mParamIndex}]\n`;
        for (const op of this.mList) s += op.deepToString(indent + '  ') + '\n';
        return s;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new PatternBlock(buffer.readInt()));
    }
}

export class PatternForEach extends Operation {
    static readonly OP_CODE = 244;
    private mCollectionId: number;
    private mLocalItemId: number;
    private mList: Operation[] = [];
    private mBody: Uint8Array | null = null;

    constructor(collectionId: number, localItemId: number) {
        super(); this.mCollectionId = collectionId; this.mLocalItemId = localItemId;
    }

    getCollectionId(): number { return this.mCollectionId; }
    getLocalItemId(): number { return this.mLocalItemId; }
    getList(): Operation[] { return this.mList; }
    setBody(bytes: Uint8Array): void { this.mBody = bytes; }
    getBody(): Uint8Array | null { return this.mBody; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(_context: RemoteContext): void { /* handled during expansion */ }

    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        const array = context.getDocument().getArray(this.mCollectionId);
        if (array === null) {
            return;
        }
        const bodyBytes = this.mBody;
        if (bodyBytes === null || bodyBytes.length === 0) {
            return;
        }

        const length = array.getLength();
        for (let i = 0; i < length; i++) {
            const ctx = context.getRemapContext().fork().withInsideMacro(true);
            ctx.addMapping(this.mLocalItemId, array.getId(i));

            const nested = context.inflateBody(bodyBytes, ctx);
            const templateContent = nested.filter((op) => !(op instanceof Header));

            const child = makeChild(context, loomManager, ctx, new Map<number, Operation[]>(),
                context.getDepth());
            child.expandRecursive(templateContent, result, loomManager);
        }
    }

    deepToString(indent: string): string {
        let s = `${indent}MacroForEach[coll=${this.mCollectionId}, item=${this.mLocalItemId}]\n`;
        for (const op of this.mList) s += op.deepToString(indent + '  ') + '\n';
        return s;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const collectionId = buffer.readId();
        const localItemId = buffer.readId();
        operations.push(new PatternForEach(collectionId, localItemId));
    }
}

// ---- Leaf ops ----

export class IncludeReferencedOperations extends Operation {
    static readonly OP_CODE = 245;
    private mId: number;

    constructor(id: number) { super(); this.mId = id; }

    getReferenceId(): number { return this.mId; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(_context: RemoteContext): void { /* expanded during inflation */ }

    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        const op = context.getDocument().getReferencedOperationsObject(this.mId);
        if (op instanceof ReferencedOperations) {
            const def = op as ReferencedOperations;
            const bodyBytes = def.getBody();
            if (bodyBytes !== null && bodyBytes.length > 0) {
                const ctx = context.getRemapContext().fork().withInsideMacro(true);
                const nested = context.inflateBody(bodyBytes, ctx);
                const templateContent = nested.filter((o) => !(o instanceof Header));
                const child = makeChild(context, loomManager, ctx, new Map<number, Operation[]>(),
                    context.getDepth() + 1);
                child.expandRecursive(templateContent, result, loomManager);
                return;
            }
        }
        // Resolution failed: fall back to default (emit self).
        super.materialize(context, result, loomManager);
    }

    deepToString(indent: string): string { return `${indent}IncludeReferencedOperations[${this.mId}]`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new IncludeReferencedOperations(buffer.readId()));
    }
}

export class PatternArgument extends Operation {
    static readonly OP_CODE = 248;
    private mParamIndex: number;

    constructor(paramIndex: number) { super(); this.mParamIndex = paramIndex; }

    getParamIndex(): number { return this.mParamIndex; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(_context: RemoteContext): void { /* handled during expansion */ }

    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        const block = context.getBlock(this.mParamIndex);
        if (block !== null) {
            context.expandRecursive(block, result, loomManager);
        }
    }

    deepToString(indent: string): string { return `${indent}MacroArgument[${this.mParamIndex}]`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new PatternArgument(buffer.readInt()));
    }
}

// ---- PatternDefine ----
// If skipLength > 0: body is read inline as raw bytes (LEAF form on the wire).
// If skipLength == 0: children + ContainerEnd follow; it is a container whose
//   body bytes are captured from the source buffer in a pre-pass.

export class PatternDefine extends Operation {
    static readonly OP_CODE = 246;
    private mId: number;
    private mParamIds: Int32Array;
    private mBody: Uint8Array;
    private mList: Operation[] = [];
    private mIsContainer: boolean;

    constructor(id: number, paramIds: Int32Array, body: Uint8Array, isContainer: boolean) {
        super();
        this.mId = id; this.mParamIds = paramIds; this.mBody = body;
        this.mIsContainer = isContainer;
        // Only expose getList() when this instance actually nests children, so the
        // duck-typed container detection matches the wire layout.
        if (isContainer) {
            (this as unknown as { getList: () => Operation[] }).getList = () => this.mList;
        }
    }

    getId(): number { return this.mId; }
    getParamIds(): Int32Array { return this.mParamIds; }
    getBody(): Uint8Array { return this.mBody; }
    setBody(bytes: Uint8Array): void { this.mBody = bytes; }
    isContainerForm(): boolean { return this.mIsContainer; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(_context: RemoteContext): void { /* definitions handled during inflation */ }

    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        // Register the macro; do NOT emit it into the materialized list.
        const name = context.getDocument().getText(this.mId) ?? null;
        loomManager.add(this, name);
        // The definition is intentionally not added to the result.
    }

    deepToString(indent: string): string {
        return `${indent}MacroDefine[${this.mId}]${this.mBody.length > 0 ? ` [body: ${this.mBody.length} bytes]` : ''}`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readId();
        const paramCount = buffer.readInt();
        if (paramCount < 0) {
            throw new Error(`PatternDefine: invalid paramCount ${paramCount}`);
        }
        const paramIds = new Int32Array(paramCount);
        for (let i = 0; i < paramCount; i++) {
            paramIds[i] = buffer.readId();
        }
        const skipLength = buffer.readInt();
        let body = new Uint8Array(0);
        const isContainer = skipLength === 0;
        if (skipLength > 0) {
            const start = buffer.getIndex();
            body = buffer.getBuffer().slice(start, start + skipLength);
            buffer.setIndex(start + skipLength);
        }
        operations.push(new PatternDefine(id, paramIds, body, isContainer));
    }
}

// Helper to build a child ExpansionContext without importing the class (cycle-free):
// reuse the runtime constructor reachable from an existing context instance.
function makeChild(
    parent: ExpansionContext,
    loomManager: LoomManager,
    ctx: RemapContext,
    blocks: Map<number, Operation[]>,
    depth: number
): ExpansionContext {
    const Ctor = (parent as unknown as { constructor: any }).constructor;
    return new Ctor(loomManager, parent.getDocument(), ctx, blocks, parent.isSafeMode(), depth);
}
