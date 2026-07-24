// LayoutComputeOperation: compute component position/measure via expressions.
// Port of Java LayoutComputeOperation.java. Implements Container.

import { Operation } from '../../../Operation';
import type { VariableSupport } from '../../../VariableSupport';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { Container } from '../Container';
import type { ComponentMeasure } from '../measure/ComponentMeasure';
import { DataDynamicListFloat } from '../../DataDynamicListFloat';

export class LayoutComputeOperation extends Operation implements Container, VariableSupport {
    static readonly OP_CODE = 238;

    static readonly TYPE_MEASURE = 0;
    static readonly TYPE_POSITION = 1;

    private mList: Operation[] = [];
    private mType: number;
    private mBoundsId: number;
    private mAnimateChanges: boolean;
    private mParent: any = null;
    private mBounds = new Float32Array(6);

    constructor(type: number, boundsId: number, animateChanges: boolean) {
        super();
        this.mType = type;
        this.mBoundsId = boundsId;
        this.mAnimateChanges = animateChanges;
    }

    getList(): Operation[] { return this.mList; }

    getType(): number { return this.mType; }

    setParent(parent: any): void { this.mParent = parent; }

    registerListening(context: RemoteContext): void {
        for (const op of this.mList) {
            if (typeof (op as any).registerListening === 'function') {
                (op as any).registerListening(context);
            }
        }
    }

    updateVariables(context: RemoteContext): void {
        let needsInvalidate = false;
        for (const op of this.mList) {
            if (typeof (op as any).updateVariables === 'function' && op.isDirty()) {
                (op as any).updateVariables(context);
                op.apply(context);
                op.markNotDirty();
                needsInvalidate = true;
            }
        }
        if (needsInvalidate && this.mParent && typeof this.mParent.invalidateMeasure === 'function') {
            this.mParent.invalidateMeasure();
        }
    }

    markDirty(): void { /* nothing */ }

    apply(_context: RemoteContext): void { /* nothing */ }

    applyToMeasure(context: PaintContext, m: ComponentMeasure, parent: ComponentMeasure): boolean {
        const collectionsAccess = context.getContext().getCollectionsAccess();
        if (!collectionsAccess) return false;

        const array = collectionsAccess.getArray(this.mBoundsId);
        if (!array) return false;

        this.mBounds[0] = m.getX();
        this.mBounds[1] = m.getY();
        this.mBounds[2] = m.getW();
        this.mBounds[3] = m.getH();
        this.mBounds[4] = parent.getW();
        this.mBounds[5] = parent.getH();

        if (array instanceof DataDynamicListFloat) {
            array.updateValues(this.mBounds);
        }

        for (const operation of this.mList) {
            if (typeof (operation as any).updateVariables === 'function' && operation.isDirty()) {
                (operation as any).updateVariables(context.getContext());
            }
            operation.apply(context.getContext());
        }

        const bounds = array.getFloats?.() ?? null;
        if (!bounds) return false;

        switch (this.mType) {
            case LayoutComputeOperation.TYPE_MEASURE:
                m.setW(bounds[2]);
                m.setH(bounds[3]);
                break;
            case LayoutComputeOperation.TYPE_POSITION:
                m.setX(bounds[0]);
                m.setY(bounds[1]);
                break;
            default:
                m.setX(bounds[0]);
                m.setY(bounds[1]);
                m.setW(bounds[2]);
                m.setH(bounds[3]);
        }

        m.setAllowsAnimation(this.mAnimateChanges);
        const positionChanged = bounds[0] !== this.mBounds[0] || bounds[1] !== this.mBounds[1];
        const dimensionChanged = bounds[2] !== this.mBounds[2] || bounds[3] !== this.mBounds[3];

        if (this.mType === LayoutComputeOperation.TYPE_MEASURE && dimensionChanged) return true;
        if (this.mType === LayoutComputeOperation.TYPE_POSITION && positionChanged) return true;
        return dimensionChanged || positionChanged;
    }

    write(buffer: WireBuffer): void {
        buffer.start(LayoutComputeOperation.OP_CODE);
        buffer.writeInt(this.mType);
        buffer.writeInt(this.mBoundsId);
        buffer.writeBoolean(this.mAnimateChanges);
    }

    deepToString(indent: string): string {
        return `${indent}LayoutComputeOperation(type=${this.mType}, boundsId=${this.mBoundsId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const type = buffer.readInt();
        const boundsId = buffer.readInt();
        const animateChanges = buffer.readBoolean();
        operations.push(new LayoutComputeOperation(type, boundsId, animateChanges));
    }
}
