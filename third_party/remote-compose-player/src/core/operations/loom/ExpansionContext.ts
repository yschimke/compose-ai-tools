// ExpansionContext: state + logic for materializing operations during macro
// (loom) expansion. Port of ExpansionContext.java.

import type { Operation } from '../../Operation';
import { ContainerEnd } from '../layout/ContainerEnd';
import { DebugMessage } from '../StubOperations';
import { RemapContext } from './RemapContext';
import type { LoomManager } from './LoomManager';
import { PatternInflation, PatternBlock } from './PatternOperations';
import { RemoteComposeBuffer } from '../../RemoteComposeBuffer';
import { WireBuffer } from '../../WireBuffer';
import { nestContainers, captureLoomBodies } from './nestContainers';

/** Duck-typed array reference returned by the document for ForEach collections. */
export interface ArrayAccess {
    getLength(): number;
    getId(index: number): number;
}

/** Document surface ExpansionContext / loom ops require. */
export interface ExpansionDocument {
    getNextId(): number;
    getText(id: number): string | null | undefined;
    getArray(id: number): ArrayAccess | null;
    getReferencedOperationsObject(id: number): Operation | null;
}

const MAX_EXPANSION_DEPTH = 64;

export class ExpansionContext {
    private readonly mLoomManager: LoomManager;
    private readonly mDocument: ExpansionDocument;
    private readonly mRemapContext: RemapContext;
    private readonly mBlocks: Map<number, Operation[]>;
    private readonly mDepth: number;
    private readonly mSafeMode: boolean;

    constructor(
        loomManager: LoomManager,
        document: ExpansionDocument,
        remapContext: RemapContext,
        blocks: Map<number, Operation[]>,
        safeMode = false,
        depth = 0
    ) {
        this.mLoomManager = loomManager;
        this.mDocument = document;
        this.mRemapContext = remapContext;
        this.mBlocks = blocks;
        this.mSafeMode = safeMode;
        this.mDepth = depth;
    }

    getDepth(): number { return this.mDepth; }
    isSafeMode(): boolean { return this.mSafeMode; }
    getMacroManager(): LoomManager { return this.mLoomManager; }
    getDocument(): ExpansionDocument { return this.mDocument; }
    getRemapContext(): RemapContext { return this.mRemapContext; }

    getBlock(paramIndex: number): Operation[] | null {
        return this.mBlocks.get(paramIndex) ?? null;
    }

    /** Factory used by Operation.materialize to avoid an import cycle on ContainerEnd. */
    makeContainerEnd(): Operation {
        return new ContainerEnd();
    }

    fork(): ExpansionContext {
        return new ExpansionContext(
            this.mLoomManager, this.mDocument, this.mRemapContext.fork(),
            this.mBlocks, this.mSafeMode, this.mDepth);
    }

    /** Top-level entry: expand a list into a fresh result list. */
    expandRecursiveTop(operations: Operation[], loomManager: LoomManager): Operation[] {
        const result: Operation[] = [];
        this.expandRecursive(operations, result, loomManager);
        return result;
    }

    expandRecursive(operations: Operation[], result: Operation[], loomManager: LoomManager): void {
        if (this.mDepth > MAX_EXPANSION_DEPTH) {
            if (this.mSafeMode) {
                result.push(new DebugMessage());
                return;
            }
            throw new Error('Maximum macro expansion depth exceeded');
        }
        for (const op of operations) {
            try {
                op.materialize(this, result, loomManager);
            } catch (t) {
                if (this.mSafeMode) {
                    result.push(new DebugMessage());
                } else {
                    throw t;
                }
            }
        }
    }

    /** Record PatternBlock children of a call so PatternArgument can find them. */
    recordBlocks(call: PatternInflation): void {
        for (const callChild of call.getList()) {
            if (callChild instanceof PatternBlock) {
                this.mBlocks.set(callChild.getParamIndex(), callChild.getList());
            }
        }
    }

    /** Return the blocks associated with a macro call as a fresh map. */
    getBlocksForCall(call: PatternInflation): Map<number, Operation[]> {
        const blocks = new Map<number, Operation[]>();
        for (const callChild of call.getList()) {
            if (callChild instanceof PatternBlock) {
                blocks.set(callChild.getParamIndex(), callChild.getList());
            }
        }
        return blocks;
    }

    /** Inflate a macro body from its captured bytes, remapping ids via remapContext. */
    inflateBody(bodyBytes: Uint8Array, remapContext: RemapContext): Operation[] {
        // Copy into a fresh ArrayBuffer (avoids SharedArrayBuffer typing + offset issues).
        const ab = new ArrayBuffer(bodyBytes.byteLength);
        new Uint8Array(ab).set(bodyBytes);
        const wb = WireBuffer.fromArrayBuffer(ab);
        const buffer = new RemoteComposeBuffer(wb);
        const flatBody: Operation[] = [];
        buffer.inflateFromBuffer(flatBody, remapContext);
        // Capture body bytes for nested loom containers so they expand too.
        captureLoomBodies(flatBody, new Uint8Array(ab));
        return nestContainers(flatBody);
    }
}
