// CanvasOperations: a paint container (opcode 173) holding raw canvas drawing
// commands — the mechanism a `drawWithContent`/graphics modifier uses to paint a
// component's decoration (e.g. a Material3 button/card fill and outline).
//
// Mirrors androidx.compose.remote.core.operations.layout.CanvasOperations: a
// `PaintOperation` + `Container`. Its `read` consumes no payload (an empty
// marker); its children are the operations between it and the matching
// `ContainerEnd`, grouped by CoreDocument.inflateComponents because this op
// exposes `getList()`. Being a `PaintOperation` is load-bearing: it makes
// `PaintOperation.apply` route to `paint()` in PAINT mode (and recurse children
// in the DATA pass), and it lets a component's draw-content path recognise this
// block as a drawing op when the block lands in `mDrawContentOperations`.
//
// `paint()` replays each child, exactly as CanvasOperations.paint does in
// remote-core:
//
//   for (op in mList) {
//     if (op is VariableSupport && op.isDirty()) op.updateVariables(context.getContext())
//     context.getContext().incrementOpCount()
//     op.apply(context.getContext())
//   }

import { PaintOperation } from '../../PaintOperation';
import type { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { PaintContext } from '../../PaintContext';

export class CanvasOperations extends PaintOperation {
    static readonly OP_CODE = 173;

    // Child drawing commands, collected during inflation (see getList()).
    readonly mList: Operation[] = [];

    constructor() {
        super();
    }

    // Exposing getList() marks this as a Container: CoreDocument.inflateComponents
    // redirects the ops between this one and its ContainerEnd into mList, and the
    // DATA pass recurses through it so child data/variables load.
    getList(): Operation[] {
        return this.mList;
    }

    write(buffer: WireBuffer): void {
        buffer.start(CanvasOperations.OP_CODE);
    }

    paint(context: PaintContext): void {
        const remoteContext = context.getContext();
        for (const op of this.mList) {
            if (op.isDirty() && typeof (op as unknown as { updateVariables?: unknown }).updateVariables === 'function') {
                (op as unknown as { updateVariables: (c: typeof remoteContext) => void }).updateVariables(remoteContext);
            }
            remoteContext.incrementOpCount();
            op.apply(remoteContext);
        }
    }

    override deepToString(indent: string): string {
        const inner = this.mList.map((op) => op.deepToString(indent + '  ')).join('\n');
        return `${indent}CanvasOperations\n${inner}`;
    }

    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new CanvasOperations());
    }
}
