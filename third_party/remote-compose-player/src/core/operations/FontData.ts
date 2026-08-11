// FontData: embedded raw font bytes (opcode 189).

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

/** Mirrors AndroidX FontData: font id, currently-unused type, and a font file. */
export class FontData extends Operation {
    static readonly OP_CODE = 189;

    constructor(
        readonly mFontId: number,
        readonly mType: number,
        readonly mFontData: Uint8Array,
    ) {
        super();
    }

    write(_buffer: WireBuffer): void { /* read-only player */ }

    apply(context: RemoteContext): void {
        context.loadFont(this.mFontId, this.mFontData);
    }

    deepToString(indent: string): string {
        return `${indent}FontData(${this.mFontId}, ${this.mFontData.length} bytes)`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const fontId = buffer.readId();
        const type = buffer.readInt();
        const fontData = buffer.readBuffer();
        operations.push(new FontData(fontId, type, fontData));
    }
}
