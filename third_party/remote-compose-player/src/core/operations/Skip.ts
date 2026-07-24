// Skip — conditional code-section skip operation.
// Java source: remote-core/.../operations/Skip.java
//
// On read, the operation consumes its three int fields (conditionType,
// value, skipLength) and — if the condition matches the host player's
// SystemInfo — advances the buffer index by skipLength bytes, effectively
// excising the next section of operations from the parsed stream. Skip
// does NOT add itself to the operations list.
import { Operation } from '../Operation';
import { RemoteContext } from '../RemoteContext';
import { WireBuffer } from '../WireBuffer';

// Default SystemInfo the player advertises. The Java player initialises
// these from its actual library version + profile. For the side player we
// pin them to the highest baseline we know about (v7) so docs targeting
// the latest features are accepted. Override at runtime via setSystemInfo
// if you want to test downlevel branching.
let sLibraryApiLevel = 7;
let sProfile = 0;

export function setSkipSystemInfo(libraryApiLevel: number, profile: number): void {
    sLibraryApiLevel = libraryApiLevel;
    sProfile = profile;
}

export class Skip extends Operation {
    static readonly OP_CODE = 241;

    static readonly SKIP_IF_API_LESS_THAN = 1;
    static readonly SKIP_IF_API_GREATER_THAN = 2;
    static readonly SKIP_IF_API_EQUAL_TO = 3;
    static readonly SKIP_IF_API_NOT_EQUAL_TO = 4;
    static readonly SKIP_IF_PROFILE_INCLUDES = 5;
    static readonly SKIP_IF_PROFILE_EXCLUDES = 6;

    constructor() { super(); }

    write(_buffer: WireBuffer): void { /* parse-only */ }
    apply(_context: RemoteContext): void { /* no-op */ }
    deepToString(indent: string): string { return `${indent}Skip`; }

    static needsToSkip(conditionType: number, value: number): boolean {
        switch (conditionType) {
            case Skip.SKIP_IF_API_LESS_THAN:    return sLibraryApiLevel < value;
            case Skip.SKIP_IF_API_GREATER_THAN: return sLibraryApiLevel > value;
            case Skip.SKIP_IF_API_EQUAL_TO:     return sLibraryApiLevel === value;
            case Skip.SKIP_IF_API_NOT_EQUAL_TO: return sLibraryApiLevel !== value;
            case Skip.SKIP_IF_PROFILE_INCLUDES: return (sProfile & value) !== 0;
            case Skip.SKIP_IF_PROFILE_EXCLUDES: return (sProfile & value) === 0;
            default: return false;
        }
    }

    static read(buffer: WireBuffer, _operations: Operation[]): void {
        const conditionType = buffer.readInt();
        const value = buffer.readInt();
        const skipLength = buffer.readInt();
        if (Skip.needsToSkip(conditionType, value)) {
            buffer.setIndex(buffer.getIndex() + skipLength);
        }
        // Skip never adds itself to the operations list — it's parse-time only.
    }
}
