// The synthetic catalog both known-difference suites run against.
//
// Shared rather than copied because the two are about different halves of the same path — the
// adapter's plumbing in `acceptance.test.ts`, the band's rendering in `acceptanceElement.test.ts` —
// and a second copy of the world would let them drift into testing two different catalogs while
// reading as though they agreed.

import { encodePng } from "../../../../scripts/design-artifacts/png-write.mjs";
import { sha256Hex } from "../../../../scripts/design-artifacts/png-lite.mjs";
import { resolvePlane } from "../../../../scripts/design-artifacts/known-difference-plane.mjs";
import { decodePng } from "../../../../scripts/design-artifacts/png-lite.mjs";

export const WHITE = [255, 255, 255, 255];
export const BLACK = [0, 0, 0, 255];
export const RED = [200, 60, 60, 255];

export function raster(width: number, height: number, fill: number[]) {
    const pixels = new Uint8Array(width * height * 4);
    for (let i = 0; i < width * height; i++) pixels.set(fill, i * 4);
    return { width, height, pixels };
}

export function fillRect(
    image: { width: number; height: number; pixels: Uint8Array },
    box: { x: number; y: number; width: number; height: number },
    colour: number[],
) {
    for (let y = box.y; y < box.y + box.height; y++) {
        for (let x = box.x; x < box.x + box.width; x++) {
            image.pixels.set(colour, (y * image.width + x) * 4);
        }
    }
    return image;
}

export function png(image: {
    width: number;
    height: number;
    pixels: Uint8Array;
}) {
    return encodePng({
        width: image.width,
        height: image.height,
        samples: image.pixels,
    }) as Uint8Array;
}

/** An 8-bit greyscale mask: `0` unmasked, `255` masked, as the contract fixes it. */
export function maskPng(
    width: number,
    height: number,
    box: { x: number; y: number; width: number; height: number },
) {
    const samples = new Uint8Array(width * height);
    for (let y = box.y; y < box.y + box.height; y++) {
        for (let x = box.x; x < box.x + box.width; x++)
            samples[y * width + x] = 255;
    }
    return encodePng({ width, height, colourType: 0, samples }) as Uint8Array;
}

export const MARK = { x: 10, y: 8, width: 8, height: 8 };

/**
 * A catalog with one acceptance over a glyph the render draws in the wrong colour.
 *
 * The plane is resolved the way the adapter will resolve it, from the two rasters, rather than
 * declared — this is a plumbing test, and a hand-declared plane would be testing the measurement
 * that `plane/` already pins.
 */
export function world() {
    const reference = fillRect(raster(32, 24, WHITE), MARK, BLACK);
    const candidate = fillRect(raster(32, 24, WHITE), MARK, RED);
    const referencePng = png(reference);
    const candidatePng = png(candidate);
    const { plane, boxes } = resolvePlane(
        decodePng(referencePng),
        decodePng(candidatePng),
    ) as {
        plane: {
            plane: string;
            box: { x: number; y: number; width: number; height: number };
        };
        boxes: {
            reference: { x: number; y: number; width: number; height: number };
        };
    };

    // The mask is authored in the canonical plane, so the mark's box moves by the plane's origin.
    const local = {
        x: MARK.x - plane.box.x,
        y: MARK.y - plane.box.y,
        width: MARK.width,
        height: MARK.height,
    };
    const mask = maskPng(plane.box.width, plane.box.height, local);
    const accepted = png(raster(MARK.width, MARK.height, RED));

    return { referencePng, candidatePng, mask, accepted, plane, boxes, local };
}

export function knownDifferencesJson(
    scene: ReturnType<typeof world>,
    overrides: Record<string, unknown> = {},
) {
    return JSON.stringify({
        schema: "compose-preview-known-differences/v1",
        acceptances: [
            {
                id: "glyph",
                issue: "https://github.com/yschimke/m3-catalog/issues/40",
                system: "m3",
                component: "IconButton/Tonal",
                previewId: "iconbutton-tonal__ideal__default__light",
                referenceId: "iconbutton-tonal-ideal-light",
                variant: "ideal/default/light",
                mask: "mask.png",
                acceptedCandidate: "accepted-candidate.png",
                referenceSha256: sha256Hex(scene.referencePng),
                maskSha256: sha256Hex(scene.mask),
                acceptedCandidateSha256: sha256Hex(scene.accepted),
                plane: scene.plane,
                candidateTolerance: 2,
                acceptedAt: "2026-08-23T00:00:00Z",
                ...overrides,
            },
        ],
    });
}

/**
 * The page's locator.
 *
 * `referenceSha256` is the catalog's published digest of the reference file, and it is the REAL one
 * here rather than a placeholder: the adapter now checks the bytes it fetched against it before
 * scoring anything, so a fixture that declared a digest describing nothing would exercise the
 * stale-generation path in every case instead of the one that is about it.
 */
export function scope(
    scene: ReturnType<typeof world>,
    overrides: Record<string, unknown> = {},
) {
    return {
        system: "m3",
        component: "IconButton/Tonal",
        previewId: "iconbutton-tonal__ideal__default__light",
        referenceId: "iconbutton-tonal-ideal-light",
        variant: "ideal/default/light",
        overrides: {},
        referenceSha256: sha256Hex(scene.referencePng),
        ...overrides,
    };
}

/** A `fetch` that serves one synthetic catalog, and whatever failures a case asks for. */
export function serve(routes: Record<string, Uint8Array | string | number>) {
    return (input: RequestInfo | URL) => {
        const url = String(input);
        const body = routes[url];
        if (body === undefined) {
            return Promise.resolve(new Response("not found", { status: 404 }));
        }
        if (typeof body === "number") {
            return Promise.resolve(new Response("no", { status: body }));
        }
        if (typeof body === "string")
            return Promise.resolve(new Response(body));
        return Promise.resolve(new Response(body as unknown as BodyInit));
    };
}

export const SOURCES = {
    documentUrl: "/m3/parity/known-differences.json",
    artifactUrl: (path: string) => `/m3/parity/known-differences/${path}`,
    referenceUrl: "/m3/reference/ref.png",
    candidateUrl: "/m3/render/preview.png",
};

/**
 * Install the stub for as long as the caller needs it, and record what was asked for.
 *
 * `withFetch` scopes the stub to one awaited call, which is right for the adapter — it returns a
 * promise for everything it did. A custom element does not: it starts its work from
 * `connectedCallback` and finishes some turns later, so a scoped stub is already gone by the time
 * the element fetches and the test reaches the network instead. The recorded urls are what lets an
 * assertion about a *hidden* band mean "it ran and had nothing to say" rather than "it had not
 * started yet".
 */
export function installFetch(
    routes: Record<string, Uint8Array | string | number>,
): {
    asked: string[];
    restore(): void;
} {
    const original = globalThis.fetch;
    const asked: string[] = [];
    const stub = serve(routes);
    globalThis.fetch = ((input: RequestInfo | URL) => {
        asked.push(String(input));
        return stub(input);
    }) as typeof fetch;
    return {
        asked,
        restore() {
            globalThis.fetch = original;
        },
    };
}

export function withFetch<T>(
    routes: Record<string, Uint8Array | string | number>,
    body: () => Promise<T>,
) {
    const original = globalThis.fetch;
    globalThis.fetch = serve(routes) as typeof fetch;
    return body().finally(() => {
        globalThis.fetch = original;
    });
}

export function catalogRoutes(scene: ReturnType<typeof world>, doc: string) {
    return {
        [SOURCES.documentUrl]: doc,
        [SOURCES.referenceUrl]: scene.referencePng,
        [SOURCES.candidateUrl]: scene.candidatePng,
        "/m3/parity/known-differences/glyph/mask.png": scene.mask,
        "/m3/parity/known-differences/glyph/accepted-candidate.png":
            scene.accepted,
    };
}
