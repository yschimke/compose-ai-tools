// LoomWireBuffer: a WireBuffer that applies ID remapping during pattern (loom)
// expansion. Port of LoomWireBuffer.java. It wraps an existing WireBuffer and
// delegates all reads/writes to it, overriding only declareId/readId/readNanId/
// readLongNanId to route through a RemapContext.

import { WireBuffer } from '../../WireBuffer';
import type { RemapContext } from './RemapContext';

export class LoomWireBuffer extends WireBuffer {
    private readonly mWrapped: WireBuffer;
    private readonly mContext: RemapContext;

    constructor(wrapped: WireBuffer, context: RemapContext) {
        super(1); // tiny backing buffer; never used — everything delegates
        this.mWrapped = wrapped;
        this.mContext = context;
    }

    getRemapContext(): RemapContext { return this.mContext; }

    // ---- ID-typed reads (the remapping hooks) ----
    override declareId(): number { return this.mContext.declareId(this.mWrapped.readInt()); }
    override readId(): number { return this.mContext.resolveId(this.mWrapped.readInt()); }
    override readNanId(): number { return this.mContext.resolveNanId(this.mWrapped.readFloat()); }
    override readLongNanId(): number { return this.mContext.resolveLongNanId(this.mWrapped.readLong()); }

    // ---- Delegation ----
    override getBuffer(): Uint8Array { return this.mWrapped.getBuffer(); }
    override getMaxSize(): number { return this.mWrapped.getMaxSize(); }
    override getIndex(): number { return this.mWrapped.getIndex(); }
    override setIndex(index: number): void { this.mWrapped.setIndex(index); }
    override getSize(): number { return this.mWrapped.getSize(); }
    override size(): number { return this.mWrapped.size(); }
    override available(): boolean { return this.mWrapped.available(); }

    override start(type: number): void { this.mWrapped.start(type); }
    override startWithSize(type: number): void { this.mWrapped.startWithSize(type); }
    override endWithSize(): void { this.mWrapped.endWithSize(); }
    override reset(expectedSize: number): void { this.mWrapped.reset(expectedSize); }

    override readOperationType(): number { return this.mWrapped.readOperationType(); }
    override readBoolean(): boolean { return this.mWrapped.readBoolean(); }
    override readByte(): number { return this.mWrapped.readByte(); }
    override readShort(): number { return this.mWrapped.readShort(); }
    override peekInt(): number { return this.mWrapped.peekInt(); }
    override readInt(): number { return this.mWrapped.readInt(); }
    override readLong(): number { return this.mWrapped.readLong(); }
    override readFloat(): number { return this.mWrapped.readFloat(); }
    override readDouble(): number { return this.mWrapped.readDouble(); }
    override readBuffer(): Uint8Array { return this.mWrapped.readBuffer(); }
    override readBufferMax(maxSize: number): Uint8Array { return this.mWrapped.readBufferMax(maxSize); }
    override readUTF8(): string { return this.mWrapped.readUTF8(); }
    override readUTF8Max(maxSize: number): string { return this.mWrapped.readUTF8Max(maxSize); }

    override writeBoolean(value: boolean): void { this.mWrapped.writeBoolean(value); }
    override writeByte(value: number): void { this.mWrapped.writeByte(value); }
    override writeShort(value: number): void { this.mWrapped.writeShort(value); }
    override writeInt(value: number): void { this.mWrapped.writeInt(value); }
    override writeLong(value: number): void { this.mWrapped.writeLong(value); }
    override writeFloat(value: number): void { this.mWrapped.writeFloat(value); }
    override writeDouble(value: number): void { this.mWrapped.writeDouble(value); }
    override writeBuffer(b: Uint8Array): void { this.mWrapped.writeBuffer(b); }
    override writeUTF8(content: string): void { this.mWrapped.writeUTF8(content); }
    override cloneBytes(): Uint8Array { return this.mWrapped.cloneBytes(); }
    override setVersion(documentApiLevel: number, profiles: number): void { this.mWrapped.setVersion(documentApiLevel, profiles); }
    override setValidOperationsFromSet(s: Set<number>): void { this.mWrapped.setValidOperationsFromSet(s); }
    override moveBlock(beyond: number, insertLocation: number): void { this.mWrapped.moveBlock(beyond, insertLocation); }
}
