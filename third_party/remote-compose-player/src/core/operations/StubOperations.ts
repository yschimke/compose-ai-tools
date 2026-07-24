// StubOperations: parse-only operation stubs that read fields but have no apply() logic.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

// ── ImpulseOperation (164) ──────────────────────────────────────────
export class ImpulseOperation extends Operation {
    static readonly OP_CODE = 164;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}ImpulseOperation`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readFloat(); // duration
        buffer.readFloat(); // startAt
        operations.push(new ImpulseOperation());
    }
}

// ── ImpulseProcess (165) ─────────────────────────────────────────────
export class ImpulseProcess extends Operation {
    static readonly OP_CODE = 165;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}ImpulseProcess`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ImpulseProcess());
    }
}

// ── CanvasOperations (173) ──────────────────────────────────────────
export class CanvasOperationsOp extends Operation {
    static readonly OP_CODE = 173;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}CanvasOperations`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new CanvasOperationsOp());
    }
}

// ── DebugMessage (179) ──────────────────────────────────────────────
export class DebugMessage extends Operation {
    static readonly OP_CODE = 179;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}DebugMessage`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // textId
        buffer.readFloat(); // floatValue
        buffer.readInt(); // flags
        operations.push(new DebugMessage());
    }
}

// ── HostActionMetadataOperation (216) ───────────────────────────────
export class HostActionMetadataOperation extends Operation {
    static readonly OP_CODE = 216;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}HostActionMetadataOperation`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // actionId
        buffer.readInt(); // metadataId
        operations.push(new HostActionMetadataOperation());
    }
}

// ── RunActionOperation (236): container — children are action operations ──
export class RunActionOperation extends Operation {
    static readonly OP_CODE = 236;
    mList: Operation[] = [];
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        // Execute child action operations
        for (const op of this.mList) {
            op.apply(context);
        }
    }
    deepToString(indent: string): string { return `${indent}RunActionOperation(${this.mList.length} actions)`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new RunActionOperation());
    }
}

// ── ValueFloatExpressionChangeAction (227) ──────────────────────────
export class ValueFloatExpressionChangeAction extends Operation {
    static readonly OP_CODE = 227;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}ValueFloatExpressionChangeAction`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // valueId
        buffer.readInt(); // value (expression id)
        operations.push(new ValueFloatExpressionChangeAction());
    }
}

// ── TextLayout (208) ─────────────────────────────────────────────────
// In Java, TEXT_LAYOUT is a Container (TextLayout extends LayoutManager -> Component).
// This parse-only stub mirrors that: it exposes getList() so the player nests its
// (empty) child list and emits a ContainerEnd, matching the wire layout. IDs are
// read via declareId/readId/readNanId so macro expansion can uniqueify them.
export class TextLayout extends Operation {
    static readonly OP_CODE = 208;
    private mComponentId: number;
    private mTextId: number;
    private mList: Operation[] = [];
    constructor(componentId = -1, textId = -1) {
        super();
        this.mComponentId = componentId;
        this.mTextId = textId;
    }
    getComponentId(): number { return this.mComponentId; }
    getId(): number { return this.mComponentId; }
    getList(): Operation[] { return this.mList; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string {
        return `${indent}TEXT_LAYOUT [${this.mComponentId}] textId=${this.mTextId}`;
    }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.declareId();
        buffer.declareId(); // animationId
        const textId = buffer.readId();
        buffer.readInt(); // color
        buffer.readNanId(); // fontSize
        buffer.readInt(); // fontStyle
        buffer.readNanId(); // fontWeight
        buffer.readId(); // fontFamilyId
        buffer.readInt(); // textAlign
        buffer.readInt(); // overflow
        buffer.readInt(); // maxLines
        operations.push(new TextLayout(componentId, textId));
    }
}

// ── PathTween (158) ──────────────────────────────────────────────────
export class PathTween extends Operation {
    static readonly OP_CODE = 158;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}PathTween`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt();   // outId
        buffer.readInt();   // pathId1
        buffer.readInt();   // pathId2
        buffer.readFloat(); // tween
        operations.push(new PathTween());
    }
}

// ── HapticFeedback (177) ─────────────────────────────────────────────
export class HapticFeedback extends Operation {
    static readonly OP_CODE = 177;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}HapticFeedback`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // type
        operations.push(new HapticFeedback());
    }
}

// ── WakeIn (191) ─────────────────────────────────────────────────────
export class WakeIn extends Operation {
    static readonly OP_CODE = 191;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}WakeIn`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readFloat(); // wake
        operations.push(new WakeIn());
    }
}

// ── TimeAttribute (172) ──────────────────────────────────────────────
export class TimeAttribute extends Operation {
    static readonly OP_CODE = 172;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}TimeAttribute`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt();   // id
        buffer.readInt();   // textId
        buffer.readShort(); // type
        const len = buffer.readShort(); // arg count
        for (let i = 0; i < len; i++) buffer.readInt(); // args
        operations.push(new TimeAttribute());
    }
}
