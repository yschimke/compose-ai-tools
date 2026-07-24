import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { ContextMode } from '../RemoteContext';
import { idFromBits, isNaNBits, floatToRawIntBits } from './Utils';

// Short path-command marker ids used by PathCreate/PathAppend (10..17). A NaN
// token in this id range is a path COMMAND, not a variable reference.
function isPathMarkerBits(b: number): boolean {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id >= 10 && id <= 17;
}

export class PathAppend extends Operation {
    static readonly OP_CODE = 160;
    private mId: number;
    // Path tokens as raw float32 int bits (command markers / variable refs are
    // NaN-with-payload). Bits keep the ids alive on engines that canonicalize
    // NaN payloads (Safari/Firefox).
    private mPathBits: Int32Array;
    // Resolved path tokens as raw float32 int bits: markers kept as-is, variable
    // refs resolved to the bits of their current float, literals kept as-is.
    private mOutputPath: Int32Array;

    constructor(id: number, bits: Int32Array) {
        super();
        this.mId = id;
        this.mPathBits = bits;
        this.mOutputPath = Int32Array.from(bits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        for (const b of this.mPathBits) {
            if (isNaNBits(b) && !isPathMarkerBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
    }

    updateVariables(context: RemoteContext): void {
        for (let i = 0; i < this.mPathBits.length; i++) {
            const b = this.mPathBits[i];
            if (isPathMarkerBits(b)) {
                this.mOutputPath[i] = b;
            } else if (isNaNBits(b)) {
                this.mOutputPath[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
            } else {
                this.mOutputPath[i] = b;
            }
        }
    }

    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const out = this.mOutputPath;
        if (out.length > 0 && PathAppend.isReset(out[0])) {
            context.loadPathData(this.mId, 0, new Int32Array(0));
            return;
        }
        const existing = context.getPathData(this.mId);
        if (existing && existing.length > 0) {
            const combined = new Int32Array(existing.length + out.length);
            combined.set(existing, 0);
            combined.set(out, existing.length);
            context.loadPathData(this.mId, 0, combined);
        } else {
            context.loadPathData(this.mId, 0, out);
        }
    }

    private static isReset(b: number): boolean {
        if (!isNaNBits(b)) return false;
        return idFromBits(b) === 17;
    }

    deepToString(indent: string): string { return `${indent}PathAppend(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const len = buffer.readInt();
        const bits = new Int32Array(len);
        for (let i = 0; i < len; i++) bits[i] = buffer.readInt();
        operations.push(new PathAppend(id, bits));
    }
}
