// TextLayout (208): the text component the writer emits when the document's profile
// does not enable CORE_TEXT.
//
// RemoteComposeBuffer.addTextComponentStart() picks between two ops:
//   * CoreText (239)   when mValidOperations[CORE_TEXT] — a parameter block, and
//   * TextLayout (208) otherwise — a fixed field list, used as the backstop.
// A document whose header carries no `profiles` tag gets the baseline operation map and
// therefore this op, so "I forgot one header field" is enough to land here. Until this
// was implemented the player parsed it and drew nothing: the document rendered with its
// background and no text at all, silently.
//
// The two ops describe the same component with the same semantics, so this subclasses
// CoreText and only replaces the reader. Java's TextLayout is a separate class that
// duplicates the measure/paint logic; reusing CoreText keeps them from drifting.

import { CoreText } from './CoreText';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';

/** Packed into the top half of the textAlign field. */
const FLAG_IS_DYNAMIC_COLOR = 1;

export class TextLayout extends CoreText {
    static readonly OP_CODE: number = 208;

    deepToString(indent: string): string {
        return `${indent}TEXT_LAYOUT [${this.getComponentId()}]`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.declareId();
        const animationId = buffer.declareId();
        const textId = buffer.readId();
        const color = buffer.readInt();
        // fontSize and fontWeight are written with writeFloat but read back as raw int
        // bits: a value driven by a variable is a NaN-encoded id, and round-tripping it
        // through a JS number can canonicalise the NaN and lose the payload. This is the
        // same reason CoreText's parameter reader takes floats via readInt().
        //
        // Read via readNanIdBits() rather than a bare readInt(): both consume the same
        // four bytes, but only the former is a remapping hook — under macro/pattern
        // expansion LoomWireBuffer rewrites the id in the NaN payload, and a plain
        // readInt() skips that, leaving every expanded instance pointing at the
        // template's id.
        const fontSize = buffer.readNanIdBits();
        const fontStyle = buffer.readInt();
        const fontWeight = buffer.readNanIdBits();
        const fontFamilyId = buffer.readId();
        const textAlign = buffer.readInt();
        const overflow = buffer.readInt();
        const maxLines = buffer.readInt();

        // When the colour is dynamic the `color` field carries a colour *id* instead of
        // an ARGB literal, flagged in the high half of textAlign.
        const dynamicColor = ((textAlign >>> 16) & FLAG_IS_DYNAMIC_COLOR) > 0;

        operations.push(new TextLayout(
            componentId,
            animationId,
            textId,
            dynamicColor ? (0xFF000000 | 0) : color,
            dynamicColor ? color : -1,
            fontSize,
            -1,                 // minFontSize — not carried by this op
            -1,                 // maxFontSize
            fontStyle,
            fontWeight,
            fontFamilyId,
            textAlign,
            overflow,
            maxLines,
            0,                  // letterSpacing
            0,                  // lineHeightAdd
            1,                  // lineHeightMultiplier
            0,                  // lineBreakStrategy
            0,                  // hyphenationFrequency
            0,                  // justificationMode
            false,              // underline
            false,              // strikethrough
            null,               // fontAxis
            null,               // fontAxisValues
            false,              // autosize
            0,                  // flags
        ));
    }
}
