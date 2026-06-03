// Consumer-side loading for the `SpatialScene` wire contract
// ([../shared/spatialScene.ts](../shared/spatialScene.ts)). The contract ships
// a deliberately shallow `isSpatialScene` guard and asks the viewer to tolerate
// missing optional fields; this module is the thin layer that turns an untyped
// payload (host message or `JSON.parse` of a fixture) into a normalised
// `SpatialScene` the viewer can render without per-field undefined checks.
//
// Pure — no three.js, no DOM — so it runs under the Mocha unit suite.

import {
    isSpatialScene,
    SPATIAL_SCENE_VERSION,
    type OrbiterAffordance,
    type SpatialPanel,
    type SpatialScene,
} from "../shared/spatialScene";

/** Thrown when a payload fails the contract guard or version check. */
export class SpatialSceneParseError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "SpatialSceneParseError";
    }
}

/**
 * Validate an untyped value against the contract and normalise it. Throws
 * {@link SpatialSceneParseError} if the shallow {@link isSpatialScene} guard
 * fails or the `version` doesn't match the one the viewer was built against
 * (the contract says `version` *must equal* {@link SPATIAL_SCENE_VERSION}).
 *
 * Normalisation only fills the optional collections (`orbiters` → `[]`,
 * `environment` → `null`) so the viewer can iterate without guards; it does
 * not strip unknown fields — additive optional fields are allowed at the same
 * version and simply pass through.
 */
export function parseSpatialScene(raw: unknown): SpatialScene {
    // Check the version explicitly first so a mismatch gets a clear message.
    // `isSpatialScene` also rejects a wrong version (the guard treats a
    // mismatch as an incompatible shape), but its boolean result can't say
    // *why* — surface the actionable error before falling back to the guard.
    if (
        typeof raw === "object" &&
        raw !== null &&
        typeof (raw as { version?: unknown }).version === "number" &&
        (raw as { version: number }).version !== SPATIAL_SCENE_VERSION
    ) {
        throw new SpatialSceneParseError(
            `unsupported SpatialScene version ${(raw as { version: number }).version}: this viewer was built against version ${SPATIAL_SCENE_VERSION}`,
        );
    }
    if (!isSpatialScene(raw)) {
        throw new SpatialSceneParseError(
            "not a SpatialScene: expected an object with units:'dp', version " +
                `${SPATIAL_SCENE_VERSION}, a panels array, and a camera`,
        );
    }
    return {
        ...raw,
        orbiters: raw.orbiters ?? [],
        environment: raw.environment ?? null,
    };
}

/**
 * Convenience wrapper: parse a JSON string into a {@link SpatialScene}, wrapping
 * `JSON.parse` syntax errors in {@link SpatialSceneParseError} so callers have a
 * single error type to catch.
 */
export function parseSpatialSceneJson(text: string): SpatialScene {
    let parsed: unknown;
    try {
        parsed = JSON.parse(text);
    } catch (err) {
        throw new SpatialSceneParseError(
            `invalid JSON: ${(err as Error).message}`,
        );
    }
    return parseSpatialScene(parsed);
}

/** Every renderable quad in the scene — panels plus orbiter affordances. */
export function renderableQuads(
    scene: SpatialScene,
): Array<SpatialPanel | OrbiterAffordance> {
    return [...scene.panels, ...(scene.orbiters ?? [])];
}
