// The typed seam onto the shared acceptance engine.
//
// `scripts/design-artifacts/*.mjs` is plain JavaScript with `node --test` behind it, and `tsc` reads
// it with `allowJs`/`checkJs: false` — inference only. That works for most of the surface and fails
// in exactly one place: a parameter with a default of `null` or `[]` infers as `null` or `never[]`,
// so a call passing a real comparison or a real mask list is a type error against a signature the
// implementation does not actually have.
//
// The alternatives were worse. Annotating the JavaScript with JSDoc types would put a TypeScript
// concern inside the contract's reference implementation, which `design-parity` and a future Kotlin
// reader also read. Weakening the call sites to `any` would lose the shapes at the point they are
// most useful. So the widening happens **here, once**, next to the reason for it: this file states
// the engine's shape as this consumer relies on it, and every other file in `src/parity/` is
// ordinarily typed against it.
//
// If one of these declarations drifts from the implementation, the conformance suite next door does
// not catch it — it runs the JavaScript directly. What catches it is that the adapter stops
// compiling, or stops working; keep the declarations minimal so there is little to drift.

import { evaluateKnownDifferences as evaluateJs } from "../../../../scripts/design-artifacts/known-differences.mjs";
import {
    canonicalRaster as canonicalRasterJs,
    projectTagIndex as projectTagIndexJs,
    resolvePlane as resolvePlaneJs,
} from "../../../../scripts/design-artifacts/known-difference-plane.mjs";
import { scoreComparison as scoreComparisonJs } from "../../../../scripts/design-artifacts/known-difference-score.mjs";
import { decodePng as decodePngJs } from "../../../../scripts/design-artifacts/png-lite.mjs";

/** A decoded raster, in the shape `png-lite.mjs` hands one over. */
export interface Raster {
    width: number;
    height: number;
    pixels: Uint8Array;
}

export interface Box {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** The recorded canonical plane: the discriminant plus the resolved box (I9). */
export interface Plane {
    plane: "content-box" | "full-canvas";
    box: Box;
}

/** `{ error }` is the reader's own vocabulary; the engine turns it into the record's verdict. */
export type ArtifactAnswer = Uint8Array | { error: string };

export interface EngineStatus {
    status: string;
    causes?: string[];
    reasons?: string[];
}

export interface EngineResult {
    /** Absent entirely for a document-level rejection — not the same as "every acceptance passed". */
    statuses?: Record<string, EngineStatus>;
    /** The `valid` acceptances' masks: the union the scorer suppresses, and no other status (I5). */
    survivingMasks?: Array<{ id: string; mask: Raster }>;
    validationFailures: Array<{ id?: string; reason: string }>;
}

/** `testTag → {count, bounds}`. Bounds are render-pixel on the wire and canonical after projection. */
export type TagIndex = Record<string, { count: number; bounds?: unknown }>;

export interface Comparison {
    system: string;
    component: string;
    previewId: string;
    referenceId: string;
    variant: string;
    overrides: Record<string, string>;
    referenceSha256: string | null;
    plane: Plane;
    canonicalReference: Raster;
    canonicalCandidate: Raster;
    tagIndex: TagIndex;
}

export interface Catalog {
    previews: Array<{
        system: string;
        id: string;
        component: string | null;
        variant: string;
        referenceIds: string[];
    }>;
}

export const evaluateKnownDifferences = evaluateJs as unknown as (options: {
    documentText: string;
    readArtifact: (path: string) => ArtifactAnswer | null;
    comparison?: Comparison | null;
    catalog?: Catalog | null;
}) => EngineResult;

export const scoreComparison = scoreComparisonJs as unknown as (options: {
    reference: Raster;
    candidate: Raster;
    referenceBox: Box;
    candidateBox: Box;
    plane: Plane;
    masks?: Raster[];
}) => { raw: number; accepted: number; unaccepted: number };

export const resolvePlane = resolvePlaneJs as unknown as (
    reference: Raster,
    candidate: Raster,
) => { plane: Plane; boxes: { reference: Box; candidate: Box }; geometry: number };

export const canonicalRaster = canonicalRasterJs as unknown as (
    image: Raster,
    box: Box,
    plane: Plane,
) => Raster;

export const decodePng = decodePngJs as unknown as (bytes: Uint8Array) => Raster;

/** The published tag index, render-pixel, projected into the comparison's canonical plane. */
export const projectTagIndex = projectTagIndexJs as unknown as (
    tagIndex: TagIndex,
    candidateBox: Box,
    plane: Plane,
) => TagIndex;
