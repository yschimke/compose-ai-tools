// Header: document header operation encoding version, dimensions, and properties.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { CoreDocument } from '../CoreDocument';
import { IntMap } from './utilities/IntMap';

export class Header extends Operation {
    static readonly OP_CODE = 0;
    private static readonly MAGIC_NUMBER = 0x048C0000;
    private static readonly MAX_TABLE_SIZE = 1000;

    // Property keys
    static readonly DOC_WIDTH: number = 5;
    static readonly DOC_HEIGHT: number = 6;
    static readonly DOC_DENSITY_AT_GENERATION: number = 7;
    static readonly DOC_DESIRED_FPS: number = 8;
    static readonly DOC_CONTENT_DESCRIPTION: number = 9;
    static readonly DOC_SOURCE: number = 11;
    static readonly DOC_DATA_UPDATE: number = 12;
    static readonly HOST_EXCEPTION_HANDLER: number = 13;
    static readonly DOC_PROFILES: number = 14;
    static readonly FEATURE_PAINT_MEASURE: number = 15;
    static readonly DEBUG: number = 16;
    static readonly FEATURE_MEASURE_VERSION: number = 17;
    static readonly FEATURE_TOUCH_VERSION: number = 18;

    // Data types
    private static readonly DATA_TYPE_INT = 0;
    private static readonly DATA_TYPE_FLOAT = 1;
    private static readonly DATA_TYPE_LONG = 2;
    private static readonly DATA_TYPE_STRING = 3;

    mMajorVersion: number;
    mMinorVersion: number;
    mPatchVersion: number;
    mWidth: number;
    mHeight: number;
    mCapabilities: number;
    mProfiles: number;
    private mProperties: IntMap<any> | null;

    constructor(majorVersion: number, minorVersion: number, patchVersion: number,
                properties: IntMap<any> | null = null,
                width = 256, height = 256, capabilities = 0) {
        super();
        this.mMajorVersion = majorVersion;
        this.mMinorVersion = minorVersion;
        this.mPatchVersion = patchVersion;
        this.mWidth = width;
        this.mHeight = height;
        this.mCapabilities = capabilities;
        this.mProperties = properties;
        this.mProfiles = 0;
        if (properties) {
            const profileVal = properties.get(Header.DOC_PROFILES);
            if (profileVal != null) this.mProfiles = profileVal as number;
            const widthVal = properties.get(Header.DOC_WIDTH);
            if (widthVal != null) this.mWidth = widthVal as number;
            const heightVal = properties.get(Header.DOC_HEIGHT);
            if (heightVal != null) this.mHeight = heightVal as number;
        }
    }

    get(property: number): any {
        return this.mProperties?.get(property) ?? null;
    }

    getInt(key: number, defaultValue: number): number {
        const v = this.mProperties?.get(key);
        if (v != null && typeof v === 'number') return v;
        return defaultValue;
    }

    getProfiles(): number { return this.mProfiles; }

    setVersion(doc: CoreDocument): void {
        doc.setVersion(this.mMajorVersion, this.mMinorVersion, this.mPatchVersion);
        doc.setWidth(this.mWidth);
        doc.setHeight(this.mHeight);
        doc.setRequiredCapabilities(this.mCapabilities);
        doc.setProperties(this.mProperties);
    }

    write(buffer: WireBuffer): void {
        // Simplified write - not needed for web player (read-only)
        buffer.start(Header.OP_CODE);
    }

    apply(context: RemoteContext): void {
        context.header(
            this.mMajorVersion, this.mMinorVersion, this.mPatchVersion,
            this.mWidth, this.mHeight, this.mCapabilities,
            this.mProperties
        );
    }

    deepToString(indent: string): string {
        return `${indent}Header v${this.mMajorVersion}.${this.mMinorVersion}.${this.mPatchVersion} ${this.mWidth}x${this.mHeight}`;
    }

    /** Peek the API level from the buffer without consuming bytes */
    static peekApiLevel(buffer: WireBuffer): number {
        const savedIndex = buffer.getIndex();
        buffer.readByte(); // skip OP_CODE
        const versionOrMagic = buffer.readInt();
        buffer.setIndex(savedIndex);

        if ((versionOrMagic & 0xFFFF0000) === Header.MAGIC_NUMBER) {
            // Modern format
            const majorVersion = versionOrMagic & 0xFFFF;
            const minorVersion = buffer.peekInt(); // won't be right but doesn't matter
            // Determine API level from version
            if (majorVersion >= 1) {
                // v1.2 → 8, v1.1 → 7, v1.0 → 6
                buffer.setIndex(savedIndex);
                buffer.readByte();
                buffer.readInt(); // major | MAGIC
                const minor = buffer.readInt();
                buffer.setIndex(savedIndex);
                if (minor >= 2) return 8;
                if (minor >= 1) return 7;
                return 6;
            }
            return 6;
        }

        // Legacy format: versionOrMagic is majorVersion directly
        if (versionOrMagic === 0) {
            // v0.x → 6
            return 6;
        }
        // v1.x without magic → old format
        return 6;
    }

    /** Read a Header from the buffer (modern format with property map) */
    static readDirect(buffer: WireBuffer): Header {
        buffer.readByte(); // OP_CODE
        const versionOrMagic = buffer.readInt();

        if ((versionOrMagic & 0xFFFF0000) === Header.MAGIC_NUMBER) {
            // Modern format
            const majorVersion = versionOrMagic & 0xFFFF;
            const minorVersion = buffer.readInt();
            const patchVersion = buffer.readInt();
            const numProperties = buffer.readInt();

            const properties = new IntMap<any>();
            if (numProperties > Header.MAX_TABLE_SIZE) {
                console.warn(`Header: property table size ${numProperties} exceeds limit ${Header.MAX_TABLE_SIZE}, truncating`);
            }
            for (let i = 0; i < numProperties; i++) {
                const tag = buffer.readShort();
                const dataType = tag >> 10;
                const key = tag & 0x3FF;
                const itemLen = buffer.readShort();

                if (i >= Header.MAX_TABLE_SIZE) {
                    // Skip properties beyond the limit to keep buffer aligned
                    for (let j = 0; j < itemLen; j++) buffer.readByte();
                    continue;
                }

                switch (dataType) {
                    case Header.DATA_TYPE_INT:
                        properties.put(key, buffer.readInt());
                        break;
                    case Header.DATA_TYPE_FLOAT:
                        properties.put(key, buffer.readFloat());
                        break;
                    case Header.DATA_TYPE_LONG:
                        properties.put(key, buffer.readLong());
                        break;
                    case Header.DATA_TYPE_STRING:
                        properties.put(key, buffer.readUTF8());
                        break;
                    default:
                        // Skip unknown data type
                        for (let j = 0; j < itemLen; j++) buffer.readByte();
                        break;
                }
            }

            return new Header(majorVersion, minorVersion, patchVersion, properties);
        }

        // Legacy format
        const majorVersion = versionOrMagic;
        const minorVersion = buffer.readInt();
        const patchVersion = buffer.readInt();
        const width = buffer.readInt();
        const height = buffer.readInt();
        const capabilities = buffer.readLong();

        return new Header(majorVersion, minorVersion, patchVersion, null, width, height, capabilities);
    }

    /** CompanionOperation.read implementation */
    static read(buffer: WireBuffer, operations: Operation[]): void {
        // Back up to re-read the full header (the OP_CODE byte was already consumed by inflateFromBuffer)
        const savedIndex = buffer.getIndex() - 1; // -1 because opcode was already read
        buffer.setIndex(savedIndex);
        const header = Header.readDirect(buffer);
        operations.push(header);
    }
}
