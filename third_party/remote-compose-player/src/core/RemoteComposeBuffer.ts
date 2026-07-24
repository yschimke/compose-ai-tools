// RemoteComposeBuffer: wraps WireBuffer with inflateFromBuffer deserialization.

import { WireBuffer } from './WireBuffer';
import { Operations } from './Operations';
import { Header } from './operations/Header';
import type { Operation } from './Operation';
import type { RemapContext } from './operations/loom/RemapContext';
import { LoomWireBuffer } from './operations/loom/LoomWireBuffer';

// Per-op byte span attached during inflation so macro bodies can be re-sliced
// out of the original input buffer (the players have no op serialization).
export interface ByteSpan {
    _byteStart: number;
    _byteEnd: number;
}

export class RemoteComposeBuffer {
    private mBuffer: WireBuffer;

    constructor(buffer: WireBuffer) {
        this.mBuffer = buffer;
    }

    static fromArrayBuffer(data: ArrayBuffer): RemoteComposeBuffer {
        return new RemoteComposeBuffer(WireBuffer.fromArrayBuffer(data));
    }

    getBuffer(): WireBuffer { return this.mBuffer; }

    /**
     * Inflate operations from the buffer into a flat list.
     *
     * @param operations output list
     * @param remapContext if provided, ID reads are routed through this context
     *        (used during macro/pattern body re-inflation for uniqueification).
     */
    inflateFromBuffer(operations: Operation[], remapContext?: RemapContext): void {
        this.mBuffer.setIndex(0);

        // When remapping, wrap the raw buffer so declareId/readId/readNanId apply.
        const buf: WireBuffer = remapContext ? new LoomWireBuffer(this.mBuffer, remapContext) : this.mBuffer;

        const map = Operations.getOperations();

        while (this.mBuffer.available()) {
            const startOffset = this.mBuffer.getIndex();
            const opId = this.mBuffer.readByte();
            const reader = map.get(opId);
            if (!reader) {
                console.warn(`Unknown operation opcode: ${opId}, skipping rest of buffer`);
                return;
            }
            const before = operations.length;
            reader(buf, operations);
            const endOffset = this.mBuffer.getIndex();
            // Tag every op produced by this read with its byte span in the source buffer.
            for (let i = before; i < operations.length; i++) {
                const span = operations[i] as unknown as ByteSpan;
                span._byteStart = startOffset;
                span._byteEnd = endOffset;
            }
        }
    }
}
