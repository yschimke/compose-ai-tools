import { CoreText } from './CoreText';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';

/**
 * TEXT_LAYOUT (208) — the fixed-field text component.
 *
 * Semantically this is the same thing as `CoreText` (239): a `LayoutManager` that
 * measures a string and paints it. The two differ only in how they are encoded.
 * `CoreText` carries a variable-length parameter bag (`readCommandParams`), while
 * `TEXT_LAYOUT` carries a fixed positional record of the eleven fields below —
 * the narrower form emitted by the Glance Wear widget capture
 * (`WearWidgetDocument.captureRawContent`) and by `remote-material3`'s
 * `RemoteText`.
 *
 * It was previously a parse-only stub: the reader consumed the right number of
 * bytes (so the stream stayed aligned and nothing downstream reported an unknown
 * opcode) but discarded every field and painted nothing. A document whose text
 * came through this op therefore replayed as its background alone — silently, and
 * with no truncation warning to give it away. Extending `CoreText` reuses its
 * measure/layout/paint path wholesale; only the wire decoding differs.
 *
 * The fields absent from this encoding take the same defaults `CoreText.read`
 * applies when the parameter bag omits them.
 */
export class TextLayout extends CoreText {
    static readonly OP_CODE = 208;

    deepToString(indent: string): string {
        return `${indent}TEXT_LAYOUT [${this.getComponentId()}]`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        // Ids go through declareId/readId so macro expansion can uniqueify them.
        const componentId = buffer.declareId();
        const animationId = buffer.declareId();
        const textId = buffer.readId();
        const color = buffer.readInt();
        // fontSize/fontWeight are NaN-boxed: a literal float, or an id smuggled in
        // the NaN payload. `readNanId()` funnels them through `readFloat()`, which
        // collapses the payload (JS canonicalises NaN), so read the raw 4 bytes as
        // int bits instead — the same representation CoreText stores and decodes
        // with isNaNBits/intBitsToFloat. Same width, so the stream stays aligned.
        const fontSize = buffer.readInt();
        const fontStyle = buffer.readInt();
        const fontWeight = buffer.readInt();
        const fontFamilyId = buffer.readId();
        const textAlign = buffer.readInt();
        const overflow = buffer.readInt();
        const maxLines = buffer.readInt();

        operations.push(new TextLayout(
            componentId, animationId, textId,
            color, /* colorId = */ -1,
            fontSize, /* minFontSize = */ -1, /* maxFontSize = */ -1,
            fontStyle, fontWeight, fontFamilyId,
            textAlign, overflow, maxLines,
            /* letterSpacing = */ 0, /* lineHeightAdd = */ 0, /* lineHeightMultiplier = */ 1,
            /* lineBreakStrategy = */ 0, /* hyphenationFrequency = */ 0, /* justificationMode = */ 0,
            /* underline = */ false, /* strikethrough = */ false,
            /* fontAxis = */ null, /* fontAxisValues = */ null,
            /* autosize = */ false, /* flags = */ 0
        ));
    }
}
