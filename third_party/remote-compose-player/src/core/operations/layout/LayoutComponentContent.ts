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

    apply(_context: RemoteContext): void { /* handled by layout system */ }

    deepToString(indent: string): string {
        return `${indent}LayoutComponentContent(${this.getComponentId()})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        operations.push(new LayoutComponentContent(componentId));
    }
}
