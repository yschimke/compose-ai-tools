// PaintOperation: abstract base class for paint-related operations.
// Matches Java PaintOperation.java — routes apply() to paint() in PAINT mode,
// iterates Container children otherwise.

import { Operation } from './Operation';
import { ContextMode } from './RemoteContext';
import type { RemoteContext } from './RemoteContext';
import type { PaintContext } from './PaintContext';
import type { VariableSupport } from './VariableSupport';
import type { Container } from './operations/layout/Container';

/** Path or Bitmap need to be dereferenced */
const PTR_DEREFERENCE = 0x1 << 30;

/** Valid bits in Path or Bitmap */
const VALUE_MASK = 0xFFFF;

function isContainer(op: Operation): op is Operation & Container {
    return typeof (op as any).getList === 'function';
}

function isVariableSupport(op: Operation): op is Operation & VariableSupport {
    return typeof (op as any).updateVariables === 'function';
}

export abstract class PaintOperation extends Operation {
    static readonly PTR_DEREFERENCE = PTR_DEREFERENCE;
    static readonly VALUE_MASK = VALUE_MASK;

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.PAINT) {
            const paintContext = context.getPaintContext();
            if (paintContext) {
                this.paint(paintContext);
            }
        } else {
            if (isContainer(this)) {
                for (const op of this.getList()) {
                    if (op.isDirty()) {
                        if (isVariableSupport(op)) {
                            op.markNotDirty();
                            op.updateVariables(context);
                        }
                        op.apply(context);
                    }
                }
            }
        }
    }

    deepToString(indent: string): string {
        return indent + this.constructor.name;
    }

    abstract paint(context: PaintContext): void;

    protected getId(id: number, context: PaintContext): number {
        let returnId = id & VALUE_MASK;
        if ((id & PTR_DEREFERENCE) !== 0) {
            returnId = context.getContext().getInteger(returnId);
        }
        return returnId;
    }
}
