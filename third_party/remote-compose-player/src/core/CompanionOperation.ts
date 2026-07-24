// CompanionOperation: interface for operation reader functions.
// Each operation type provides a static read() that implements this interface.

import type { WireBuffer } from './WireBuffer';
import type { Operation } from './Operation';

export interface CompanionOperation {
    read(buffer: WireBuffer, operations: Operation[]): void;
}

// Type alias for convenience - operations register reader functions
export type CompanionOperationFn = (buffer: WireBuffer, operations: Operation[]) => void;
