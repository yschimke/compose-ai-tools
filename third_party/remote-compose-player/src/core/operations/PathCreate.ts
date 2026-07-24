import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { ContextMode } from '../RemoteContext';
import { floatToRawIntBits, isNaNBits, idFromBits } from './Utils';

// Short path-command marker ids used by PathCreate/PathAppend (10..17). A NaN
// token in this id range is a path COMMAND, not a variable reference.
function isPathMarkerBits(b: number): boolean {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id >= 10 && id <= 17;
}

export class PathCreate extends Operation {
    static readonly OP_CODE = 159;
    private static readonly MOVE_NAN_BITS = (10 | -0x800000) | 0;
    private mId: number;
    // Path tokens as raw float32 int bits (command markers / variable refs are
    // NaN-with-payload). Bits keep the ids alive on engines that canonicalize
    // NaN payloads (Safari/Firefox).
    private mPathBits: Int32Array;
    // Resolved path tokens as raw float32 int bits: markers kept as-is, variable
    // refs resolved to the bits of their current float, literals kept as-is.
    private mOutputPath: Int32Array;

    constructor(id: number, startXBits: number, startYBits: number) {
        super();
        this.mId = id;
        this.mPathBits = new Int32Array([PathCreate.MOVE_NAN_BITS, startXBits, startYBits]);
        this.mOutputPath = Int32Array.from(this.mPathBits);
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
        context.loadPathData(this.mId, 0, this.mOutputPath);
    }

    deepToString(indent: string): string { return `${indent}PathCreate(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const startX = buffer.readInt();
        const startY = buffer.readInt();
        operations.push(new PathCreate(id, startX, startY));
    }
}
