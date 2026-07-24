// Operation: abstract base class for all RemoteCompose operations.

import type { WireBuffer } from './WireBuffer';
import type { RemoteContext } from './RemoteContext';
import type { ExpansionContext } from './operations/loom/ExpansionContext';
import type { LoomManager } from './operations/loom/LoomManager';

export abstract class Operation {
    private static readonly ENABLE_DIRTY_FLAG_OPTIMIZATION = true;
    private mDirty = true;

    abstract write(buffer: WireBuffer): void;
    abstract apply(context: RemoteContext): void;
    abstract deepToString(indent: string): string;

    /**
     * Materialize this operation during macro (loom) expansion. Default behaviour
     * mirrors Operation.materialize in Java: emit self, and if this is a container,
     * recursively expand its children and emit a ContainerEnd marker.
     */
    materialize(context: ExpansionContext, result: Operation[], loomManager: LoomManager): void {
        result.push(this);
        const list = (this as unknown as { getList?: () => Operation[] }).getList;
        if (typeof list === 'function') {
            const children = list.call(this);
            context.expandRecursive(children, result, loomManager);
            children.length = 0;
            result.push(context.makeContainerEnd());
        }
    }

    markDirty(): void {
        this.mDirty = true;
    }

    markNotDirty(): void {
        if (Operation.ENABLE_DIRTY_FLAG_OPTIMIZATION) {
            this.mDirty = false;
        }
    }

    isDirty(): boolean {
        if (Operation.ENABLE_DIRTY_FLAG_OPTIMIZATION) {
            return this.mDirty;
        }
        return true;
    }
}
