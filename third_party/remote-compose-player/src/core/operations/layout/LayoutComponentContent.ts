// LayoutComponentContent: marker wrapping child operations inside a layout manager.

import { Component } from './Component';
import type { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';

export class LayoutComponentContent extends Component {
    static readonly OP_CODE = 201;

    constructor(componentId: number) {
        super(componentId);
    }

    write(buffer: WireBuffer): void {
        buffer.start(LayoutComponentContent.OP_CODE);
        buffer.writeInt(this.getComponentId());
    }

    // A content wrapper isn't measured on its own — the enclosing LayoutComponent
    // flattens its children up and measures itself. But `ComponentValue`s (e.g. a
    // button/card fill sized from its content region) reference this wrapper by id,
    // so an unmeasured 0×0 wrapper makes those bindings resolve to zero. Report the
    // parent's measured size when this wrapper has none of its own.
    override getWidth(): number {
        const w = super.getWidth();
        if (w === 0 && this.getParent()) return this.getParent()!.getWidth();
        return w;
    }

    override getHeight(): number {
        const h = super.getHeight();
        if (h === 0 && this.getParent()) return this.getParent()!.getHeight();
        return h;
    }

    apply(_context: RemoteContext): void { /* handled by layout system */ }

    deepToString(indent: string): string {
        return `${indent}LayoutComponentContent(${this.getComponentId()})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        operations.push(new LayoutComponentContent(componentId));
    }
}
