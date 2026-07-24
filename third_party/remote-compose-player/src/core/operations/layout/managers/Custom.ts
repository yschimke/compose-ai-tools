// Custom: a custom layout component (LAYOUT_CUSTOM op=93).
// Parse-only tier: read() consumes the exact bytes; it is a container (extends
// LayoutManager -> Component, inheriting getList()/inflate() so CoreDocument nests
// its children exactly like BoxLayout/RowLayout). No layout/render behavior yet.

import { LayoutManager } from './LayoutManager';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';

interface CustomProperty {
    type: number;
    dataType: number;
    value: number;
}

export class Custom extends LayoutManager {
    static readonly OP_CODE = 93;

    protected mConfigId: number;
    protected mProperties: CustomProperty[];

    constructor(componentId: number, animationId: number, configId: number, properties: CustomProperty[]) {
        super(componentId, animationId);
        this.mConfigId = configId;
        this.mProperties = properties;
    }

    write(buffer: WireBuffer): void {
        buffer.start(Custom.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.getAnimationId());
        buffer.writeInt(this.mConfigId);
        buffer.writeInt(this.mProperties.length);
        for (const p of this.mProperties) {
            buffer.writeShort(p.type);
            buffer.writeShort(p.dataType);
            if ((p.dataType & 1) === 0) {
                buffer.writeInt(p.value);
            } else {
                buffer.writeFloat(p.value);
            }
        }
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}Custom(${this.getComponentId()}, config=${this.mConfigId}, ${this.mProperties.length} props)`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.declareId();
        const animationId = buffer.declareId();
        const configId = buffer.readInt();
        const propCount = buffer.readInt();
        const properties: CustomProperty[] = [];
        for (let i = 0; i < propCount; i++) {
            const type = buffer.readShort();
            const dataType = buffer.readShort();
            const value = (dataType & 1) === 0 ? buffer.readInt() : buffer.readFloat();
            properties.push({ type, dataType, value });
        }
        operations.push(new Custom(componentId, animationId, configId, properties));
    }
}
