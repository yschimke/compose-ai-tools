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

import {
    BUDGET as BudgetJs,
    acceptanceLifecycles as acceptanceLifecyclesJs,
    evaluateKnownDifferences as evaluateJs,
} from "../../../../scripts/design-artifacts/known-differences.mjs";
import {
    canonicalRaster as canonicalRasterJs,
    projectTagIndex as projectTagIndexJs,
    resolvePlane as resolvePlaneJs,
} from "../../../../scripts/design-artifacts/known-difference-plane.mjs";
import { scoreComparison as scoreComparisonJs } from "../../../../scripts/design-artifacts/known-difference-score.mjs";
import {
    MAX_CONFORMING_HEADER_BYTES as MaxConformingHeaderBytesJs,
    decodePng as decodePngJs,
    preflightPng as preflightPngJs,
    sha256Hex as sha256HexJs,
} from "../../../../scripts/design-artifacts/png-lite.mjs";

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

/**
 * `{ error }` is the reader's own vocabulary; the engine turns it into the record's verdict.
 *
 * `{ bytes, byteLength }` is the prefix answer the header pass takes: `bytes` is at most the
 * requested prefix, and `byteLength` is the size of the *whole* artifact, which the byte cap and the
 * second-read comparison are both measured against. A bare `Uint8Array` is the whole file, its own
 * length standing in for both — the shape the decode pass takes.
 */
export type ArtifactAnswer =
    | Uint8Array
    | { bytes: Uint8Array; byteLength: number }
    | { error: string };

/** The header pass asks for at most `prefix` bytes; the decode pass passes no options at all. */
export interface ReadOptions {
    prefix?: number;
}

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

export interface IssueIndexRow {
    repository?: string;
    number?: number;
    url?: string;
    state: "open" | "closed";
}

export interface AcceptanceLifecycle {
    issue: string | null;
    lifecycle: "open" | "closed" | "unknown";
    stale: boolean;
}

export const acceptanceLifecycles = acceptanceLifecyclesJs as unknown as (
    documentRecords: unknown[],
    statuses: Record<string, EngineStatus>,
    issueRows?: IssueIndexRow[],
) => Record<string, AcceptanceLifecycle>;

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
    readArtifact: (path: string, options?: ReadOptions) => ArtifactAnswer | null;
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
) => {
    plane: Plane;
    boxes: { reference: Box; candidate: Box };
    geometry: number;
};

export const canonicalRaster = canonicalRasterJs as unknown as (
    image: Raster,
    box: Box,
    plane: Plane,
) => Raster;

export const decodePng = decodePngJs as unknown as (
    bytes: Uint8Array,
) => Raster;

/** Lowercase hex SHA-256 of the given bytes, from the contract's own FIPS 180-4 implementation. */
export const sha256Hex = sha256HexJs as unknown as (
    bytes: Uint8Array,
) => string;

/**
 * The header preflight, exposed so the browser host can decide what to fetch in full.
 *
 * The host reads a bounded prefix of every artifact, and only the ones whose header is clean earn a
 * full-body fetch — which is how it inherits the reference reader's "read the header, then read the
 * whole file only for a survivor" bound instead of allocating every artifact up front.
 */
export const preflightPng = preflightPngJs as unknown as (
    bytes: Uint8Array,
    options?: { byteLength?: number },
) =>
    | { error: string }
    | {
          width: number;
          height: number;
          bitDepth: number;
          colourType: number;
          animated: boolean;
          hasTransparency: boolean;
          byteLength: number;
      };

/** The versioned budget, shared so the host sizes its prefix reads to the same constant. */
export const BUDGET = BudgetJs as unknown as {
    maxDocumentBytes: number;
    maxAcceptances: number;
    maxPixels: number;
    maxAxis: number;
    maxArtifactBytes: number;
    maxPreflightBytes: number;
};

/** The most bytes a conforming header region can occupy; the prefix must be at least this. */
export const MAX_CONFORMING_HEADER_BYTES = MaxConformingHeaderBytesJs as unknown as number;

/** The published tag index, render-pixel, projected into the comparison's canonical plane. */
export const projectTagIndex = projectTagIndexJs as unknown as (
    tagIndex: TagIndex,
    candidateBox: Box,
    plane: Plane,
) => TagIndex;
