// Pure helpers for the font-browser webview: turning catalog metadata
// and downloaded-face records into the CSS the panel injects, and into
// the customiser's control model. Kept DOM-free so it's unit-tested
// without a webview.

import type { FontAxis } from "../../googleFontsCatalog";

/** A downloaded face as the host serialises it to the webview. */
export interface FaceView {
    style: "normal" | "italic";
    weightMin: number;
    weightMax: number;
    /** `webview.asWebviewUri(...)` string for the cached file. */
    uri: string;
    format: string;
}

export interface DownloadedFontView {
    family: string;
    familyId: string;
    category: string;
    isVariable: boolean;
    axes: FontAxis[];
    faces: FaceView[];
}

/** Synthetic CSS family name for a downloaded font (avoids clashes with
 *  any system family of the same name). */
export function webviewFamilyName(familyId: string): string {
    return `gfb-${familyId}`;
}

const CSS_FORMAT: Record<string, string> = {
    woff2: "woff2",
    woff: "woff",
    ttf: "truetype",
    truetype: "truetype",
    otf: "opentype",
    opentype: "opentype",
};

/** Build the `@font-face` rules for one downloaded font. */
export function buildFontFaceCss(font: DownloadedFontView): string {
    const familyName = webviewFamilyName(font.familyId);
    return font.faces
        .map((face) => {
            const weight =
                face.weightMin === face.weightMax
                    ? String(face.weightMin)
                    : `${face.weightMin} ${face.weightMax}`;
            const fmt = CSS_FORMAT[face.format.toLowerCase()] ?? "woff2";
            return [
                `@font-face {`,
                `  font-family: "${familyName}";`,
                `  font-style: ${face.style};`,
                `  font-weight: ${weight};`,
                `  font-display: swap;`,
                `  src: url("${face.uri}") format("${fmt}");`,
                `}`,
            ].join("\n");
        })
        .join("\n");
}

/** Concatenate the `@font-face` rules for every downloaded font. */
export function buildAllFontFaceCss(
    fonts: readonly DownloadedFontView[],
): string {
    return fonts.map(buildFontFaceCss).join("\n\n");
}

/**
 * Pick the face that best matches a (weight, italic) request. Prefers an
 * exact style match, then the nearest weight (variable faces match any
 * weight inside their range). Falls back to the first face so a render
 * never has an empty `font-family`.
 */
export function pickFace(
    font: DownloadedFontView,
    weight: number,
    italic: boolean,
): FaceView | null {
    if (font.faces.length === 0) return null;
    const wantStyle = italic ? "italic" : "normal";
    const candidates = font.faces.filter((f) => f.style === wantStyle);
    const pool = candidates.length > 0 ? candidates : font.faces;
    let best = pool[0];
    let bestDist = Number.POSITIVE_INFINITY;
    for (const face of pool) {
        const clamped = Math.max(
            face.weightMin,
            Math.min(face.weightMax, weight),
        );
        const dist = Math.abs(clamped - weight);
        if (dist < bestDist) {
            bestDist = dist;
            best = face;
        }
    }
    return best;
}

/** Default each axis to its registered default value. */
export function defaultAxisValues(
    axes: readonly FontAxis[],
): Record<string, number> {
    const out: Record<string, number> = {};
    for (const axis of axes) out[axis.tag] = axis.defaultValue;
    return out;
}

/** The weight axis range for a variable font, or the shipped static
 *  weight span. Drives the customiser's weight slider bounds. */
export function weightRange(font: DownloadedFontView): {
    min: number;
    max: number;
} {
    const wght = font.axes.find((a) => a.tag === "wght");
    if (wght) return { min: wght.min, max: wght.max };
    const weights = font.faces.map((f) => f.weightMin);
    return {
        min: weights.length ? Math.min(...weights) : 400,
        max: weights.length ? Math.max(...weights) : 400,
    };
}

/** CSS `font-variation-settings` string from the live axis values. */
export function cssVariationSettings(
    axisValues: Record<string, number>,
): string {
    const entries = Object.entries(axisValues);
    if (entries.length === 0) return "normal";
    return entries.map(([tag, value]) => `"${tag}" ${value}`).join(", ");
}

/** Axes the customiser exposes as sliders (every axis except weight,
 *  which has its own dedicated control, and ital, which is a toggle). */
export function sliderAxes(font: DownloadedFontView): FontAxis[] {
    return font.axes.filter((a) => a.tag !== "wght" && a.tag !== "ital");
}
