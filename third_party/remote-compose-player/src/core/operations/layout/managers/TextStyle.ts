// TextStyle — sparse, inheritable text styling resource.
// Java source: remote-core/.../operations/layout/managers/TextStyle.java
//
// Wire format: [opcode][short count][(byte tag, value)…]
// Each pair's value type depends on the tag id (see P_* constants below).
import { Operation } from '../../../Operation';
import { RemoteContext } from '../../../RemoteContext';
import { WireBuffer } from '../../../WireBuffer';

// Parameter tag constants (must mirror TextStyle.java).
const P_ID = 1;
const P_ANIMATION_ID = 2;
const P_COLOR = 3;
const P_COLOR_ID = 4;
const P_FONT_SIZE = 5;
const P_FONT_STYLE = 6;
const P_FONT_WEIGHT = 7;
const P_FONT_FAMILY = 8;
const P_TEXT_ALIGN = 9;
const P_OVERFLOW = 10;
const P_MAX_LINES = 11;
const P_LETTER_SPACING = 12;
const P_LINE_HEIGHT_ADD = 13;
const P_LINE_HEIGHT_MULTIPLIER = 14;
const P_BREAK_STRATEGY = 15;
const P_HYPHENATION_FREQUENCY = 16;
const P_JUSTIFICATION_MODE = 17;
const P_UNDERLINE = 18;
const P_STRIKETHROUGH = 19;
const P_FONT_AXIS = 20;
const P_FONT_AXIS_VALUES = 21;
const P_AUTOSIZE = 22;
const P_FLAGS = 23;
const P_PARENT_ID = 24;
const P_MIN_FONT_SIZE = 25;
const P_MAX_FONT_SIZE = 26;

export class TextStyle extends Operation {
    static readonly OP_CODE = 242;

    id: number = -1;
    color: number | null = null;
    colorId: number | null = null;
    fontSize: number | null = null;
    minFontSize: number | null = null;
    maxFontSize: number | null = null;
    fontStyle: number | null = null;
    fontWeight: number | null = null;
    fontFamilyId: number | null = null;
    textAlign: number | null = null;
    overflow: number | null = null;
    maxLines: number | null = null;
    letterSpacing: number | null = null;
    lineHeightAdd: number | null = null;
    lineHeightMultiplier: number | null = null;
    lineBreakStrategy: number | null = null;
    hyphenationFrequency: number | null = null;
    justificationMode: number | null = null;
    underline: boolean | null = null;
    strikethrough: boolean | null = null;
    fontAxis: number[] | null = null;
    fontAxisValues: number[] | null = null;
    autosize: boolean | null = null;
    parentId: number | null = null;

    constructor() { super(); }

    write(_buffer: WireBuffer): void { /* read-only in this player */ }

    apply(context: RemoteContext): void {
        // Resolve parent style if any. The Java player walks the chain via
        // context.getObject(parentId); we mirror only the lookup so the style
        // is registered for downstream CoreText consumers.
        if (this.id !== -1) {
            (context as any).putObject?.(this.id, this);
        }
    }

    deepToString(indent: string): string {
        return `${indent}TextStyle(id=${this.id})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const count = buffer.readShort();
        const style = new TextStyle();
        for (let i = 0; i < count; i++) {
            const tag = buffer.readByte();
            switch (tag) {
                case P_ID:                    style.id = buffer.readInt(); break;
                case P_ANIMATION_ID:          buffer.readInt(); break;
                case P_COLOR:                 style.color = buffer.readInt(); break;
                case P_COLOR_ID:              style.colorId = buffer.readInt(); break;
                case P_FONT_SIZE:             style.fontSize = buffer.readFloat(); break;
                case P_FONT_STYLE:            style.fontStyle = buffer.readInt(); break;
                case P_FONT_WEIGHT:           style.fontWeight = buffer.readFloat(); break;
                case P_FONT_FAMILY:           style.fontFamilyId = buffer.readInt(); break;
                case P_TEXT_ALIGN:            style.textAlign = buffer.readInt(); break;
                case P_OVERFLOW:              style.overflow = buffer.readInt(); break;
                case P_MAX_LINES:             style.maxLines = buffer.readInt(); break;
                case P_LETTER_SPACING:        style.letterSpacing = buffer.readFloat(); break;
                case P_LINE_HEIGHT_ADD:       style.lineHeightAdd = buffer.readFloat(); break;
                case P_LINE_HEIGHT_MULTIPLIER: style.lineHeightMultiplier = buffer.readFloat(); break;
                case P_BREAK_STRATEGY:        style.lineBreakStrategy = buffer.readInt(); break;
                case P_HYPHENATION_FREQUENCY: style.hyphenationFrequency = buffer.readInt(); break;
                case P_JUSTIFICATION_MODE:    style.justificationMode = buffer.readInt(); break;
                case P_UNDERLINE:             style.underline = buffer.readBoolean(); break;
                case P_STRIKETHROUGH:         style.strikethrough = buffer.readBoolean(); break;
                case P_FONT_AXIS: {
                    const len = buffer.readShort();
                    const arr = new Array<number>(len);
                    for (let k = 0; k < len; k++) arr[k] = buffer.readInt();
                    style.fontAxis = arr;
                    break;
                }
                case P_FONT_AXIS_VALUES: {
                    const len = buffer.readShort();
                    const arr = new Array<number>(len);
                    for (let k = 0; k < len; k++) arr[k] = buffer.readFloat();
                    style.fontAxisValues = arr;
                    break;
                }
                case P_AUTOSIZE:              style.autosize = buffer.readBoolean(); break;
                case P_FLAGS:                 buffer.readInt(); break;
                case P_PARENT_ID:             style.parentId = buffer.readInt(); break;
                case P_MIN_FONT_SIZE:         style.minFontSize = buffer.readFloat(); break;
                case P_MAX_FONT_SIZE:         style.maxFontSize = buffer.readFloat(); break;
                default:
                    // Unknown tag — try to skip safely. We don't know the
                    // value type so the wire stream is unrecoverable from
                    // this point. Best effort: log and stop.
                    console.warn(`TextStyle: unknown parameter tag ${tag}`);
                    return;
            }
        }
        operations.push(style);
    }
}
