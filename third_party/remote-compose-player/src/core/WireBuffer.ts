// WireBuffer: base communication buffer for encoding/decoding the binary protocol.
// Uses DataView + ArrayBuffer with big-endian byte ordering.

export class WireBuffer {
    private static readonly BUFFER_SIZE = 1024 * 1024;

    private mMaxSize: number;
    private mBuffer: Uint8Array;
    private mDataView: DataView;
    mIndex = 0;
    private mStartingIndex = 0;
    mSize = 0;
    private mValidOperations = new Array<boolean>(256).fill(true);

    constructor(size = WireBuffer.BUFFER_SIZE) {
        this.mMaxSize = size;
        const ab = new ArrayBuffer(size);
        this.mBuffer = new Uint8Array(ab);
        this.mDataView = new DataView(ab);
    }

    static fromArrayBuffer(data: ArrayBuffer): WireBuffer {
        const wb = new WireBuffer(data.byteLength);
        wb.mBuffer.set(new Uint8Array(data));
        wb.mSize = data.byteLength;
        return wb;
    }

    private resize(need: number): void {
        if (this.mSize + need >= this.mMaxSize) {
            this.mMaxSize = Math.max(this.mMaxSize * 2, this.mSize + need);
            const ab = new ArrayBuffer(this.mMaxSize);
            const newBuf = new Uint8Array(ab);
            newBuf.set(this.mBuffer);
            this.mBuffer = newBuf;
            this.mDataView = new DataView(ab);
        }
    }

    getBuffer(): Uint8Array { return this.mBuffer; }
    getMaxSize(): number { return this.mMaxSize; }
    getIndex(): number { return this.mIndex; }
    setIndex(index: number): void { this.mIndex = index; }
    getSize(): number { return this.mSize; }
    size(): number { return this.mSize; }
    available(): boolean { return this.mSize - this.mIndex > 0; }

    start(type: number): void {
        if (!this.mValidOperations[type]) {
            throw new Error(`Operation ${type} is not supported for this version`);
        }
        this.mStartingIndex = this.mIndex;
        this.writeByte(type);
    }

    startWithSize(type: number): void {
        this.mStartingIndex = this.mIndex;
        this.writeByte(type);
        this.mIndex += 4; // skip ahead for the future size
    }

    endWithSize(): void {
        const size = this.mIndex - this.mStartingIndex;
        const currentIndex = this.mIndex;
        this.mIndex = this.mStartingIndex + 1;
        this.writeInt(size);
        this.mIndex = currentIndex;
    }

    reset(expectedSize: number): void {
        this.mIndex = 0;
        this.mStartingIndex = 0;
        this.mSize = 0;
        if (expectedSize >= this.mMaxSize) {
            this.resize(expectedSize);
        }
    }

    // ---- Read values (big-endian) ----

    readOperationType(): number {
        return this.readByte();
    }

    readBoolean(): boolean {
        const value = this.mBuffer[this.mIndex];
        this.mIndex++;
        return value === 1;
    }

    readByte(): number {
        const value = this.mBuffer[this.mIndex] & 0xFF;
        this.mIndex++;
        return value;
    }

    readShort(): number {
        const v = this.mDataView.getUint16(this.mIndex, false);
        this.mIndex += 2;
        return v;
    }

    peekInt(): number {
        return this.mDataView.getInt32(this.mIndex, false);
    }

    // ---- ID-typed reads ----
    // Identity defaults: a plain WireBuffer treats these as ordinary reads.
    // LoomWireBuffer overrides them to apply RemapContext during macro expansion.
    declareId(): number { return this.readInt(); }
    readId(): number { return this.readInt(); }
    readNanId(): number { return this.readFloat(); }
    readLongNanId(): number { return this.readLong(); }

    readInt(): number {
        const v = this.mDataView.getInt32(this.mIndex, false);
        this.mIndex += 4;
        return v;
    }

    readLong(): number {
        // Read as two 32-bit values. Safe for values up to 2^53.
        const hi = this.mDataView.getInt32(this.mIndex, false);
        const lo = this.mDataView.getUint32(this.mIndex + 4, false);
        this.mIndex += 8;
        return hi * 0x100000000 + lo;
    }

    readFloat(): number {
        // DataView.getFloat32 preserves NaN bit patterns (critical for ID encoding)
        const v = this.mDataView.getFloat32(this.mIndex, false);
        this.mIndex += 4;
        return v;
    }

    readDouble(): number {
        const v = this.mDataView.getFloat64(this.mIndex, false);
        this.mIndex += 8;
        return v;
    }

    readBuffer(): Uint8Array {
        const count = this.readInt();
        const b = this.mBuffer.slice(this.mIndex, this.mIndex + count);
        this.mIndex += count;
        return b;
    }

    readBufferMax(maxSize: number): Uint8Array {
        const count = this.readInt();
        if (count < 0 || count > maxSize) {
            throw new Error(`attempt read a buff of invalid size 0 <= ${count} > ${maxSize}`);
        }
        const b = this.mBuffer.slice(this.mIndex, this.mIndex + count);
        this.mIndex += count;
        return b;
    }

    readUTF8(): string {
        const buf = this.readBuffer();
        return new TextDecoder().decode(buf);
    }

    readUTF8Max(maxSize: number): string {
        const buf = this.readBufferMax(maxSize);
        return new TextDecoder().decode(buf);
    }

    // ---- Write values (big-endian) ----

    writeBoolean(value: boolean): void {
        this.resize(1);
        this.mBuffer[this.mIndex++] = value ? 1 : 0;
        this.mSize++;
    }

    writeByte(value: number): void {
        this.resize(1);
        this.mBuffer[this.mIndex++] = value & 0xFF;
        this.mSize++;
    }

    writeShort(value: number): void {
        this.resize(2);
        this.mDataView.setUint16(this.mIndex, value, false);
        this.mIndex += 2;
        this.mSize += 2;
    }

    writeInt(value: number): void {
        this.resize(4);
        this.mDataView.setInt32(this.mIndex, value, false);
        this.mIndex += 4;
        this.mSize += 4;
    }

    writeLong(value: number): void {
        this.resize(8);
        const hi = Math.floor(value / 0x100000000) | 0;
        const lo = value - hi * 0x100000000;
        this.mDataView.setInt32(this.mIndex, hi, false);
        this.mDataView.setUint32(this.mIndex + 4, lo, false);
        this.mIndex += 8;
        this.mSize += 8;
    }

    writeFloat(value: number): void {
        this.resize(4);
        this.mDataView.setFloat32(this.mIndex, value, false);
        this.mIndex += 4;
        this.mSize += 4;
    }

    writeDouble(value: number): void {
        this.resize(8);
        this.mDataView.setFloat64(this.mIndex, value, false);
        this.mIndex += 8;
        this.mSize += 8;
    }

    writeBuffer(b: Uint8Array): void {
        this.resize(b.length + 4);
        this.writeInt(b.length);
        this.mBuffer.set(b, this.mIndex);
        this.mIndex += b.length;
        this.mSize += b.length;
    }

    writeUTF8(content: string): void {
        const buffer = new TextEncoder().encode(content);
        this.writeBuffer(buffer);
    }

    cloneBytes(): Uint8Array {
        return this.mBuffer.slice(0, this.mSize);
    }

    setVersion(documentApiLevel: number, profiles: number): void {
        // Lazy import avoidance: valid() is set externally
        // For now, mark all as valid - Operations.valid() will be called externally
        for (let i = 0; i < this.mValidOperations.length; i++) {
            this.mValidOperations[i] = true;
        }
    }

    setValidOperationsFromSet(supportedOperations: Set<number>): void {
        this.mValidOperations.fill(false);
        for (const o of supportedOperations) {
            this.mValidOperations[o] = true;
        }
    }

    setValidOperationsCallback(validFn: (opId: number, apiLevel: number, profiles: number) => boolean,
                                apiLevel: number, profiles: number): void {
        for (let i = 0; i < this.mValidOperations.length; i++) {
            this.mValidOperations[i] = validFn(i, apiLevel, profiles);
        }
    }

    moveBlock(beyond: number, insertLocation: number): void {
        if (insertLocation < 0 || beyond > this.mSize || insertLocation >= beyond) {
            return;
        }
        const lengthOfBlockA = beyond - insertLocation;
        const lengthOfBlockB = this.mSize - beyond;

        if (lengthOfBlockB < lengthOfBlockA) {
            const temp = this.mBuffer.slice(beyond, beyond + lengthOfBlockB);
            this.mBuffer.copyWithin(insertLocation + lengthOfBlockB, insertLocation, insertLocation + lengthOfBlockA);
            this.mBuffer.set(temp, insertLocation);
        } else {
            const temp = this.mBuffer.slice(insertLocation, insertLocation + lengthOfBlockA);
            this.mBuffer.copyWithin(insertLocation, beyond, beyond + lengthOfBlockB);
            this.mBuffer.set(temp, insertLocation + lengthOfBlockB);
        }
    }
}
