// ContainerEnd: marker operation indicating the end of a container's children.

import { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';

export class ContainerEnd extends Operation {
    static readonly OP_CODE = 214;

    write(buffer: WireBuffer): void {
        buffer.start(ContainerEnd.OP_CODE);
    }

    apply(_context: RemoteContext): void { /* no-op */ }

    deepToString(indent: string): string {
        return `${indent}ContainerEnd`;
    }

    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ContainerEnd());
    }
}
