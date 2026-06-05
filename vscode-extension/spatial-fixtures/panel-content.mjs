// Shared mock-UI content for the `spatial-rich` fixture, in each panel's *content space*
// (dp, origin top-left, `0,0 → sizeDp`). Two generators consume this single source of truth so
// the panel textures and the semantics wireframe can't drift apart:
//
//   • spatial-rich.gen.mjs       — renders each widget as a real HTML/CSS element (text, cards,
//                                   surfaces, controls) into the panel PNG via headless Chromium.
//   • preview-harness/fixtures/spatial-semantics.gen.mjs
//                                — emits each widget as a SemanticsTreeNode box, so the viewer's
//                                  wireframe overlays land exactly on the rendered elements.
//
// Each widget: { id, bounds: [left, top, right, bottom], kind, text?, label?, role?, clickable?,
//                strong?, icon? }.
//   • kind        drives how the texture renders it (text / button / image / slider) and, for the
//                 wireframe, is cosmetic (it reads text/label/role/clickable for tooltips).
//   • strong      a high-emphasis text line (title / header / the "current" lyric).
//   • icon        'prev' | 'play' | 'next' — a transport control shape instead of a text label.
// `merge: true` on a panel marks its content root as `mergeDescendants` (the amber overlay box).

/** @typedef {{id:string,bounds:[number,number,number,number],kind:"text"|"button"|"image"|"slider",text?:string,label?:string,role?:string,clickable?:boolean,strong?:boolean,icon?:"prev"|"play"|"next"}} Widget */

/** @type {Record<string, { merge?: boolean, widgets: Widget[] }>} */
export const PANEL_CONTENT = {
    // 560 × 180 — a now-playing card: thumbnail, title, artist, scrubber.
    "now-playing": {
        widgets: [
            { id: "np-thumb", bounds: [20, 24, 140, 144], kind: "image", label: "Album thumbnail", role: "Image" },
            { id: "np-title", bounds: [160, 38, 524, 80], kind: "text", text: "Midnight City", strong: true },
            { id: "np-artist", bounds: [160, 86, 544, 116], kind: "text", text: "M83 · Hurry Up, We're Dreaming" },
            { id: "np-scrubber", bounds: [20, 150, 540, 166], kind: "slider", label: "Seek", role: "Slider" },
        ],
    },
    // 460 × 460 — a single cover image.
    "album-art": {
        widgets: [
            { id: "art-image", bounds: [16, 16, 444, 444], kind: "image", label: "Album cover", role: "Image" },
        ],
    },
    // 300 × 520 — an up-next list of tappable rows.
    queue: {
        widgets: [
            { id: "q-header", bounds: [20, 16, 200, 50], kind: "text", text: "Up Next", strong: true },
            { id: "q-row1", bounds: [16, 62, 284, 134], kind: "button", text: "Outro", role: "Button", clickable: true },
            { id: "q-row2", bounds: [16, 146, 284, 218], kind: "button", text: "Reunion", role: "Button", clickable: true },
            { id: "q-row3", bounds: [16, 230, 284, 302], kind: "button", text: "Wait", role: "Button", clickable: true },
            { id: "q-row4", bounds: [16, 314, 284, 386], kind: "button", text: "Solitude", role: "Button", clickable: true },
        ],
    },
    // 300 × 520 — stacked lyric lines, with the current line emphasised.
    lyrics: {
        widgets: [
            { id: "ly-1", bounds: [20, 28, 280, 64], kind: "text", text: "Waiting for the sun" },
            { id: "ly-2", bounds: [20, 76, 270, 112], kind: "text", text: "to set over the city" },
            { id: "ly-3", bounds: [20, 124, 280, 160], kind: "text", text: "the lights go down", strong: true },
            { id: "ly-4", bounds: [20, 172, 248, 208], kind: "text", text: "and we drive" },
            { id: "ly-5", bounds: [20, 220, 272, 256], kind: "text", text: "into the neon haze" },
            { id: "ly-6", bounds: [20, 268, 224, 304], kind: "text", text: "of midnight" },
        ],
    },
    // 560 × 96 — a transport bar whose controls merge into one a11y node.
    transport: {
        merge: true,
        widgets: [
            { id: "tp-prev", bounds: [180, 24, 236, 72], kind: "button", icon: "prev", label: "Previous", role: "Button", clickable: true },
            { id: "tp-play", bounds: [252, 14, 308, 82], kind: "button", icon: "play", label: "Play", role: "Button", clickable: true },
            { id: "tp-next", bounds: [324, 24, 380, 72], kind: "button", icon: "next", label: "Next", role: "Button", clickable: true },
        ],
    },
    // 80 × 320 — a vertical volume slider.
    volume: {
        widgets: [
            { id: "vol-track", bounds: [34, 28, 46, 292], kind: "slider", label: "Volume", role: "Slider" },
        ],
    },
};
