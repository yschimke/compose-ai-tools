// VariableSupport: interface for operations that respond to variable changes.

import type { RemoteContext } from './RemoteContext';

export interface VariableSupport {
    registerListening(context: RemoteContext): void;
    updateVariables(context: RemoteContext): void;
    markDirty(): void;
}
