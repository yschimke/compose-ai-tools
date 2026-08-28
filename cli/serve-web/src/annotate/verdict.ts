// The parity verdict's geometry: which regions a finding points at, and on which panel.
//
// The verdict itself — the sentences, their severities, the grouping — is rendered by the server
// into the page, because it is prose a reader has to be able to search, quote and read with no
// script at all. What cannot be prose is WHERE each finding is, so that half arrives as a payload
// and this module is the trust boundary in front of it.
//
// Deliberately DOM-free, like `match.ts` and `typography.ts` next door: every judgement below —
// what counts as a drawable region, which panel a region belongs to, what a payload this build
// cannot read is worth — is a table rather than something you have to render a catalog to check.

import type { Bounds } from "./match.js";

export type ParitySide = "reference" | "actual";

/** One region of one panel, in that panel image's own pixel space. */
export interface ParityAnchor {
    side: ParitySide;
    bounds: Bounds;
    /** Optional caption for the highlight — the node the finding is about. */
    label?: string;
}

/** Anchors keyed by the `data-cp-parity-finding` id the server-rendered row carries. */
export type ParityAnchors = Map<string, ParityAnchor[]>;

function isSide(value: unknown): value is ParitySide {
    return value === "reference" || value === "actual";
}

/**
 * A region has to be drawable before it can point at anything.
 *
 * The server applies the same rule before publishing, and repeating it here is not redundancy: the
 * payload is JSON in the page, so the only thing standing between a producer's zero-height box and
 * a highlight nobody can see is whichever side checks last.
 */
function isDrawable(bounds: unknown): bounds is Bounds {
    const b = bounds as Bounds | null;
    return (
        !!b &&
        [b.x, b.y, b.width, b.height].every(
            (n) => typeof n === "number" && Number.isFinite(n),
        ) &&
        b.width > 0 &&
        b.height > 0
    );
}

function readAnchor(raw: unknown): ParityAnchor | null {
    if (!raw || typeof raw !== "object") return null;
    const entry = raw as { side?: unknown; bounds?: unknown; label?: unknown };
    if (!isSide(entry.side) || !isDrawable(entry.bounds)) return null;
    const label =
        typeof entry.label === "string" && entry.label.trim()
            ? entry.label.trim()
            : undefined;
    return {
        side: entry.side,
        bounds: entry.bounds,
        ...(label ? { label } : {}),
    };
}

/**
 * Read the payload, dropping what cannot be drawn and keeping everything else.
 *
 * Per FINDING rather than per payload: a producer that emits one malformed region must cost the
 * reader that highlight, not every highlight on the page. A finding left with no drawable region
 * is dropped from the map entirely, so the caller's "does this row light anything" test stays a
 * single lookup instead of a lookup plus an emptiness check it could forget.
 */
export function parseParityAnchors(raw: unknown): ParityAnchors {
    const out: ParityAnchors = new Map();
    if (!raw || typeof raw !== "object") return out;
    const findings = (raw as { findings?: unknown }).findings;
    if (!findings || typeof findings !== "object") return out;
    for (const [id, value] of Object.entries(
        findings as Record<string, unknown>,
    )) {
        if (!Array.isArray(value)) continue;
        const anchors = value
            .map(readAnchor)
            .filter((a): a is ParityAnchor => a !== null);
        if (anchors.length) out.set(id, anchors);
    }
    return out;
}

/** The severity a row declares, read off its class list; `warn` is the neutral fallback. */
export function severityOf(row: Element): "info" | "warn" | "error" {
    if (row.classList.contains("cp-parity-finding--error")) return "error";
    if (row.classList.contains("cp-parity-finding--info")) return "info";
    return "warn";
}
