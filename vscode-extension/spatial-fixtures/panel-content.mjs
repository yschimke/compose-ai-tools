// Shared mock-UI content for the `spatial-rich` fixture, in each panel's *content space*
// (dp, origin top-left, `0,0 → sizeDp`). Two generators consume this single source of truth so
// the panel textures and the semantics wireframe can't drift apart:
//
//   • spatial-rich.gen.mjs                       — draws each widget as a filled block into the
//                                                   panel PNG, so the texture shows real-looking UI.
//   • preview-harness/fixtures/spatial-semantics.gen.mjs
//                                                — emits each widget as a SemanticsTreeNode box, so
//                                                   the viewer's wireframe overlays land exactly on
//                                                   the drawn elements.
//
// Each widget: { id, bounds: [left, top, right, bottom], kind, text?, label?, role?, clickable? }.
// `kind` drives how the texture paints it (text bars / button fill / image block / slider track)
// and is purely cosmetic for the wireframe (which reads text/label/role/clickable for tooltips).
// `merge: true` on a panel marks its content root as `mergeDescendants` (the amber overlay box).

/** @typedef {{id:string,bounds:[number,number,number,number],kind:"text"|"button"|"image"|"slider",text?:string,label?:string,role?:string,clickable?:boolean}} Widget */

/** @type {Record<string, { merge?: boolean, widgets: Widget[] }>} */
export const PANEL_CONTENT = {
    // 560 × 180 — a now-playing card: thumbnail, title, artist, scrubber.
    "now-playing": {
        widgets: [
            { id: "np-thumb", bounds: [20, 24, 116, 120], kind: "image", label: "Album thumbnail", role: "Image" },
            { id: "np-title", bounds: [136, 30, 430, 66], kind: "text", text: "Midnight City" },
            { id: "np-artist", bounds: [136, 78, 320, 106], kind: "text", text: "M83" },
            { id: "np-scrubber", bounds: [20, 142, 540, 158], kind: "slider", label: "Seek", role: "Slider" },
        ],
    },
    // 460 × 460 — a single cover image.
    "album-art": {
        widgets: [
            { id: "art-image", bounds: [20, 20, 440, 440], kind: "image", label: "Album cover", role: "Image" },
        ],
    },
    // 300 × 520 — an up-next list of tappable rows.
    queue: {
        widgets: [
            { id: "q-header", bounds: [16, 16, 200, 44], kind: "text", text: "Up Next" },
            { id: "q-row1", bounds: [16, 60, 284, 128], kind: "button", text: "Outro", role: "Button", clickable: true },
            { id: "q-row2", bounds: [16, 140, 284, 208], kind: "button", text: "Reunion", role: "Button", clickable: true },
            { id: "q-row3", bounds: [16, 220, 284, 288], kind: "button", text: "Wait", role: "Button", clickable: true },
            { id: "q-row4", bounds: [16, 300, 284, 368], kind: "button", text: "Solitude", role: "Button", clickable: true },
        ],
    },
    // 300 × 520 — stacked lyric lines.
    lyrics: {
        widgets: [
            { id: "ly-1", bounds: [20, 28, 280, 60], kind: "text", text: "Waiting for the sun" },
            { id: "ly-2", bounds: [20, 72, 250, 104], kind: "text", text: "to set over the city" },
            { id: "ly-3", bounds: [20, 116, 270, 148], kind: "text", text: "the lights go down" },
            { id: "ly-4", bounds: [20, 160, 230, 192], kind: "text", text: "and we drive" },
            { id: "ly-5", bounds: [20, 204, 262, 236], kind: "text", text: "into the neon haze" },
            { id: "ly-6", bounds: [20, 248, 210, 280], kind: "text", text: "of midnight" },
        ],
    },
    // 560 × 96 — a transport bar whose controls merge into one a11y node.
    transport: {
        merge: true,
        widgets: [
            { id: "tp-prev", bounds: [188, 24, 244, 72], kind: "button", label: "Previous", role: "Button", clickable: true },
            { id: "tp-play", bounds: [252, 14, 308, 82], kind: "button", label: "Play", role: "Button", clickable: true },
            { id: "tp-next", bounds: [316, 24, 372, 72], kind: "button", label: "Next", role: "Button", clickable: true },
        ],
    },
    // 80 × 320 — a vertical volume slider.
    volume: {
        widgets: [
            { id: "vol-track", bounds: [34, 24, 46, 296], kind: "slider", label: "Volume", role: "Slider" },
        ],
    },
};
