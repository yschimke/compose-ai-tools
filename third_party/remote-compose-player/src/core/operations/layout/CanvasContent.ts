// CanvasContent: marker wrapping drawing commands inside a CanvasLayout.

import { Component } from './Component';
import type { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';

export class CanvasContent extends Component {
    static readonly OP_CODE = 207;

    constructor(componentId: number) {
        super(componentId);
    }

    write(buffer: WireBuffer): void {
        buffer.start(CanvasContent.OP_CODE);
        buffer.writeInt(this.getComponentId());
    }

    apply(_context: RemoteContext): void { /* handled by layout system */ }

    deepToString(indent: string): string {
        return `${indent}CanvasContent(${this.getComponentId()})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        operations.push(new CanvasContent(componentId));
    }
}
