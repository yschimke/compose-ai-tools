// LoomManager: registration + expansion of pattern (macro) templates.
// Port of LoomManager.java.

import type { Operation } from '../../Operation';
import { PatternDefine, PatternInflation } from './PatternOperations';
import { ExpansionContext, type ExpansionDocument } from './ExpansionContext';
import { RemapContext } from './RemapContext';

export class LoomManager {
    private readonly mMacros = new Map<number, PatternDefine>();
    private readonly mMacroNames = new Map<string, PatternDefine>();
    private mSafeMode = false;

    setSafeMode(safeMode: boolean): void { this.mSafeMode = safeMode; }
    isSafeMode(): boolean { return this.mSafeMode; }

    /** Add a macro definition, keyed by id and (optionally) by name. */
    add(macro: PatternDefine, name: string | null): void {
        const existing = this.mMacros.get(macro.getId());
        if (!existing || (existing.getBody().length === 0 && macro.getBody().length > 0)) {
            this.mMacros.set(macro.getId(), macro);
        }
        if (name !== null) {
            const en = this.mMacroNames.get(name);
            if (!en || (en.getBody().length === 0 && macro.getBody().length > 0)) {
                this.mMacroNames.set(name, macro);
            }
        }
    }

    getNamedMacros(): Map<string, PatternDefine> { return this.mMacroNames; }

    /** Top-level entry: expand all macros/references in a list of operations. */
    expandAll(operations: Operation[], document: ExpansionDocument): Operation[] {
        const expansionContext = new ExpansionContext(
            this, document, new RemapContext(document), new Map(), this.mSafeMode, 0);
        return expansionContext.expandRecursiveTop(operations, this);
    }

    /** Resolve a PatternDefine for a given call (by id, then by name via document text). */
    resolve(call: PatternInflation, document: ExpansionDocument): PatternDefine | null {
        let definition = this.mMacros.get(call.getId()) ?? null;
        if (definition === null) {
            const name = document.getText(call.getId());
            if (name !== null && name !== undefined) {
                definition = this.mMacroNames.get(name) ?? null;
            }
        }
        return definition;
    }
}
