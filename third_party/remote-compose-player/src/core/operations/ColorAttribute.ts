import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class ColorAttribute extends Operation {
    static readonly OP_CODE = 180;
    static readonly COLOR_HUE = 0;
    static readonly COLOR_SATURATION = 1;
    static readonly COLOR_BRIGHTNESS = 2;
    static readonly COLOR_RED = 3;
    static readonly COLOR_GREEN = 4;
    static readonly COLOR_BLUE = 5;
    static readonly COLOR_ALPHA = 6;

    private mOutputId: number;
    private mColorId: number;
    private mType: number;

    constructor(outputId: number, colorId: number, type: number) {
        super();
        this.mOutputId = outputId; this.mColorId = colorId; this.mType = type;
    }
    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mColorId, this);
    }

    apply(context: RemoteContext): void {
        const color = context.getColor(this.mColorId);
        const a = ((color >> 24) & 0xFF) / 255.0;
        const r = ((color >> 16) & 0xFF) / 255.0;
        const g = ((color >> 8) & 0xFF) / 255.0;
        const b = (color & 0xFF) / 255.0;
        let result = 0;
        switch (this.mType) {
            case ColorAttribute.COLOR_RED: result = r; break;
            case ColorAttribute.COLOR_GREEN: result = g; break;
            case ColorAttribute.COLOR_BLUE: result = b; break;
            case ColorAttribute.COLOR_ALPHA: result = a; break;
            case ColorAttribute.COLOR_HUE:
            case ColorAttribute.COLOR_SATURATION:
            case ColorAttribute.COLOR_BRIGHTNESS: {
                const max = Math.max(r, g, b), min = Math.min(r, g, b);
                const delta = max - min;
                if (this.mType === ColorAttribute.COLOR_BRIGHTNESS) { result = max; break; }
                if (this.mType === ColorAttribute.COLOR_SATURATION) { result = max === 0 ? 0 : delta / max; break; }
                if (delta === 0) { result = 0; }
                else if (max === r) { result = ((g - b) / delta % 6) / 6; }
                else if (max === g) { result = ((b - r) / delta + 2) / 6; }
                else { result = ((r - g) / delta + 4) / 6; }
                if (result < 0) result += 1;
                break;
            }
        }
        context.loadFloat(this.mOutputId, result);
    }

    deepToString(indent: string): string { return `${indent}ColorAttribute(${this.mOutputId})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const outputId = buffer.readInt();
        const colorId = buffer.readInt();
        const type = buffer.readShort();
        operations.push(new ColorAttribute(outputId, colorId, type));
    }
}
