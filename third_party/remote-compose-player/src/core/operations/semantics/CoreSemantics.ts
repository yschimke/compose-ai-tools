// CoreSemantics: accessibility semantics operation (opcode 250).
//
// Mirrors androidx.compose.remote.core.semantics.CoreSemantics — an
// `AccessibilityModifier` that annotates the preceding component with
// accessibility metadata (content description, role, state description,
// text, enabled/clickable flags). It carries no visual instructions, so
// the player only needs to consume its wire payload so the rest of the
// document keeps parsing; painting is a no-op.
//
// Wire layout (after the opcode byte), matching CoreSemantics.read /
// CoreSemantics.apply in remote-core:
//   contentDescriptionId : declareId (int32)
//   role                 : byte
//   textId               : declareId (int32)
//   stateDescriptionId   : declareId (int32)
//   mode                 : byte
//   enabled              : boolean
//   clickable            : boolean

import { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';

export class CoreSemantics extends Operation {
    static readonly OP_CODE = 250;

    constructor(
        public readonly mContentDescriptionId: number,
        public readonly mRole: number,
        public readonly mTextId: number,
        public readonly mStateDescriptionId: number,
        public readonly mMode: number,
        public readonly mEnabled: boolean,
        public readonly mClickable: boolean,
    ) {
        super();
    }

    write(_buffer: WireBuffer): void { /* stub — player never re-serializes */ }

    // Accessibility metadata has no visual effect, so applying it paints nothing.
    apply(_context: RemoteContext): void { /* no-op */ }

    deepToString(indent: string): string {
        return `${indent}CoreSemantics(contentDescription=${this.mContentDescriptionId}, ` +
            `role=${this.mRole}, text=${this.mTextId}, ` +
            `stateDescription=${this.mStateDescriptionId}, mode=${this.mMode}, ` +
            `enabled=${this.mEnabled}, clickable=${this.mClickable})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const contentDescriptionId = buffer.declareId();
        const role = buffer.readByte();
        const textId = buffer.declareId();
        const stateDescriptionId = buffer.declareId();
        const mode = buffer.readByte();
        const enabled = buffer.readBoolean();
        const clickable = buffer.readBoolean();
        operations.push(new CoreSemantics(
            contentDescriptionId, role, textId, stateDescriptionId, mode, enabled, clickable,
        ));
    }
}
