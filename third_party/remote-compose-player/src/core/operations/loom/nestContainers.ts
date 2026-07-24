// nestContainers: turn a flat op list (with ContainerEnd markers) into a nested
// tree, mirroring the nesting half of CoreDocument.inflateComponents / Java
// CoreDocument.nestContainers(skipComponentLogic=true).
//
// Used both for the main document tree and for re-inflated macro bodies.

import type { Operation } from '../../Operation';
import { ContainerEnd } from '../layout/ContainerEnd';
import type { ByteSpan } from '../../RemoteComposeBuffer';
import { PatternDefine, PatternForEach, ReferencedOperations } from './PatternOperations';

interface Container { getList(): Operation[]; }

function isContainer(op: Operation): boolean {
    return typeof (op as unknown as Container).getList === 'function' && !(op instanceof ContainerEnd);
}

/**
 * Capture raw body bytes for body-bearing loom containers (PatternDefine
 * container-form, PatternForEach, ReferencedOperations) from a flat list whose
 * ops carry _byteStart/_byteEnd spans into `raw`. Used for both the top-level
 * document and re-inflated macro bodies (so nested ForEach/macros expand too).
 */
export function captureLoomBodies(flat: Operation[], raw: Uint8Array): void {
    for (let i = 0; i < flat.length; i++) {
        const op = flat[i];
        const bodyBearing =
            (op instanceof PatternDefine && op.isContainerForm())
            || op instanceof PatternForEach
            || op instanceof ReferencedOperations;
        if (!bodyBearing || !isContainer(op)) continue;

        let depth = 1;
        let j = i + 1;
        for (; j < flat.length; j++) {
            const o = flat[j];
            if (o instanceof ContainerEnd) { if (--depth === 0) break; }
            else if (isContainer(o)) { depth++; }
        }
        if (j >= flat.length) continue;

        const from = (op as unknown as ByteSpan)._byteEnd;
        const to = (flat[j] as unknown as ByteSpan)._byteStart;
        if (from >= 0 && to >= from) {
            (op as unknown as { setBody: (b: Uint8Array) => void }).setBody(raw.slice(from, to));
        }
    }
}

/**
 * Nest a flat operation list into a tree. Containers (ops exposing getList())
 * collect following ops until their matching ContainerEnd. Components are
 * inflate()'d when closed, matching the player's inflateComponents behaviour.
 */
export function nestContainers(operations: Operation[]): Operation[] {
    const finalOps: Operation[] = [];
    let ops: Operation[] = finalOps;
    const containers: Container[] = [];

    for (const op of operations) {
        if (isContainer(op)) {
            const container = op as unknown as Container;
            ops.push(op);
            containers.push(container);
            ops = container.getList();
        } else if (op instanceof ContainerEnd) {
            let container: Container | null = null;
            if (containers.length > 0) {
                container = containers.pop()!;
            }
            if (containers.length > 0) {
                ops = containers[containers.length - 1].getList();
            } else {
                ops = finalOps;
            }
            if (container && typeof (container as unknown as { inflate?: () => void }).inflate === 'function') {
                (container as unknown as { inflate: () => void }).inflate();
            }
        } else {
            ops.push(op);
        }
    }
    return finalOps;
}
