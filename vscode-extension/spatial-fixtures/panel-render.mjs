// Builds the HTML/CSS for one `spatial-rich` panel texture. `spatial-rich.gen.mjs` rasterises the
// returned markup with headless Chromium, so the panel faces show real anti-aliased text and
// Material-style surfaces / cards / controls instead of flat colour blocks.
//
// Every widget is absolutely positioned at its `panel-content.mjs` bounds (content-space dp scaled
// by `density`), so the rendered element lines up with the semantics wireframe box for the same id.

const clamp8 = (v) => Math.max(0, Math.min(255, Math.round(v)));
const tint = ([r, g, b], k) =>
    `rgb(${clamp8(r * k)}, ${clamp8(g * k)}, ${clamp8(b * k)})`;
const tinta = ([r, g, b], k, a) =>
    `rgba(${clamp8(r * k)}, ${clamp8(g * k)}, ${clamp8(b * k)}, ${a})`;

const PRIMARY = "#f6f6f8";
const SECONDARY = "rgba(246, 246, 248, 0.60)";
const SANS =
    "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

/** Absolute-position style for a widget's bounds, scaled to texture pixels. */
function box(bounds, density) {
    const [l, t, r, b] = bounds;
    const x = Math.round(l * density);
    const y = Math.round(t * density);
    const w = Math.round((r - l) * density);
    const h = Math.round((b - t) * density);
    return { x, y, w, h, css: `left:${x}px;top:${y}px;width:${w}px;height:${h}px` };
}

function textWidget(b, wgt, density) {
    const fontPx = Math.max(11, Math.round(b.h * 0.56));
    const color = wgt.strong ? PRIMARY : SECONDARY;
    const weight = wgt.strong ? 650 : 450;
    return `<div style="position:absolute;${b.css};display:flex;align-items:center;
        color:${color};font:${weight} ${fontPx}px ${SANS};letter-spacing:.2px;
        white-space:nowrap;overflow:hidden">${escapeHtml(wgt.text ?? "")}</div>`;
}

function listButton(b, wgt, base, density) {
    const fontPx = Math.max(12, Math.round(b.h * 0.3));
    const thumb = Math.round(b.h * 0.62);
    const pad = Math.round(b.h * 0.19);
    const radius = Math.round(14 * density);
    return `<div style="position:absolute;${b.css};display:flex;align-items:center;gap:${pad}px;
        padding:0 ${pad}px;box-sizing:border-box;border-radius:${radius}px;
        background:${tinta(base, 1.7, 0.22)};border:1px solid rgba(255,255,255,0.10);
        box-shadow:0 ${Math.round(2 * density)}px ${Math.round(7 * density)}px rgba(0,0,0,0.28)">
        <div style="width:${thumb}px;height:${thumb}px;border-radius:${Math.round(8 * density)}px;flex:0 0 auto;
            background:linear-gradient(135deg, ${tint(base, 1.95)}, ${tint(base, 1.1)})"></div>
        <div style="color:${PRIMARY};font:550 ${fontPx}px ${SANS};white-space:nowrap;overflow:hidden">
            ${escapeHtml(wgt.text ?? "")}</div></div>`;
}

function iconButton(b, wgt, base, density) {
    const isPlay = wgt.icon === "play";
    const bg = isPlay ? tint(base, 2.0) : "rgba(255,255,255,0.10)";
    const fg = isPlay ? "#141418" : PRIMARY;
    const tri = Math.round(b.h * 0.22); // triangle half-height
    const glyph =
        wgt.icon === "play"
            ? triangle(tri, fg, "right")
            : wgt.icon === "next"
              ? `${triangle(tri * 0.8, fg, "right")}${bar(tri * 1.6, fg, density)}`
              : `${bar(tri * 1.6, fg, density)}${triangle(tri * 0.8, fg, "left")}`;
    return `<div style="position:absolute;${b.css};display:flex;align-items:center;justify-content:center;
        gap:${Math.round(2 * density)}px;border-radius:50%;background:${bg};
        border:1px solid rgba(255,255,255,0.12);
        box-shadow:0 ${Math.round(3 * density)}px ${Math.round(9 * density)}px rgba(0,0,0,0.34)">${glyph}</div>`;
}

function triangle(half, color, dir) {
    const s = Math.round(half);
    const side = `${s * 2}px solid transparent`;
    const solid = `${Math.round(s * 1.7)}px solid ${color}`;
    const edge =
        dir === "right"
            ? `border-top:${side};border-bottom:${side};border-left:${solid}`
            : `border-top:${side};border-bottom:${side};border-right:${solid}`;
    return `<span style="display:inline-block;width:0;height:0;${edge}"></span>`;
}

function bar(h, color, density) {
    return `<span style="display:inline-block;width:${Math.round(3 * density)}px;height:${Math.round(h * 1.6)}px;
        background:${color};border-radius:${density}px"></span>`;
}

function imageWidget(b, wgt, base, density) {
    // A "vinyl record" cover: a rich diagonal gradient with concentric rings + a centre label.
    const radius = Math.round(Math.min(20 * density, b.h * 0.12));
    const disc = Math.round(Math.min(b.w, b.h) * 0.74);
    const ring = Math.round(disc * 0.5);
    const hole = Math.round(disc * 0.12);
    return `<div style="position:absolute;${b.css};border-radius:${radius}px;overflow:hidden;
        background:linear-gradient(135deg, ${tint(base, 1.7)}, ${tint(base, 0.7)} 60%, ${tint(base, 1.2)});
        display:flex;align-items:center;justify-content:center">
        <div style="width:${disc}px;height:${disc}px;border-radius:50%;
            background:radial-gradient(circle at 38% 34%, rgba(255,255,255,0.18), rgba(0,0,0,0.55) 70%);
            box-shadow:0 0 ${Math.round(8 * density)}px rgba(0,0,0,0.4) inset;
            display:flex;align-items:center;justify-content:center">
            <div style="width:${ring}px;height:${ring}px;border-radius:50%;border:1px solid rgba(255,255,255,0.10);
                display:flex;align-items:center;justify-content:center">
                <div style="width:${hole}px;height:${hole}px;border-radius:50%;background:${tint(base, 2.0)}"></div>
            </div></div></div>`;
}

function sliderWidget(b, wgt, base, density) {
    const horizontal = b.w >= b.h;
    const fill = 0.62;
    const trackR = Math.round(Math.min(b.w, b.h) / 2);
    const thumb = Math.round(Math.max(b.w, b.h) * 0 + Math.min(b.w, b.h) * 2.6);
    const accent = tint(base, 2.0);
    const along = horizontal ? b.w : b.h;
    const pos = Math.round(along * fill);
    const thumbStyle = horizontal
        ? `left:${pos - thumb / 2}px;top:${b.h / 2 - thumb / 2}px`
        : `top:${b.h - pos - thumb / 2}px;left:${b.w / 2 - thumb / 2}px`;
    const fillStyle = horizontal
        ? `left:0;top:0;width:${pos}px;height:100%`
        : `left:0;bottom:0;width:100%;height:${pos}px`;
    return `<div style="position:absolute;${b.css};border-radius:${trackR}px;
        background:rgba(255,255,255,0.18);overflow:visible">
        <div style="position:absolute;${fillStyle};border-radius:${trackR}px;background:${accent}"></div>
        <div style="position:absolute;${thumbStyle};width:${thumb}px;height:${thumb}px;border-radius:50%;
            background:#fff;box-shadow:0 ${Math.round(1 * density)}px ${Math.round(4 * density)}px rgba(0,0,0,0.45)"></div>
    </div>`;
}

function widgetHtml(wgt, base, density) {
    const b = box(wgt.bounds, density);
    switch (wgt.kind) {
        case "text":
            return textWidget(b, wgt, density);
        case "button":
            return wgt.icon
                ? iconButton(b, wgt, base, density)
                : listButton(b, wgt, base, density);
        case "image":
            return imageWidget(b, wgt, base, density);
        case "slider":
            return sliderWidget(b, wgt, base, density);
        default:
            return "";
    }
}

function escapeHtml(s) {
    return String(s).replace(
        /[&<>"']/g,
        (c) =>
            ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
    );
}

/** Full HTML document for a panel of the given base colour + widgets. */
export function panelHtml({ baseRgb, sizeDp, widgets, density }) {
    const w = Math.round(sizeDp.width * density);
    const h = Math.round(sizeDp.height * density);
    const surface = `linear-gradient(180deg, ${tint(baseRgb, 1.28)}, ${tint(baseRgb, 0.94)})`;
    const body = widgets.map((wgt) => widgetHtml(wgt, baseRgb, density)).join("\n");
    return `<!doctype html><html><head><meta charset="utf-8"><style>
        *{margin:0;box-sizing:border-box}
        html,body{width:${w}px;height:${h}px;overflow:hidden}
        .panel{position:relative;width:${w}px;height:${h}px;background:${surface};
            border:1px solid rgba(255,255,255,0.08);
            box-shadow:inset 0 1px 0 rgba(255,255,255,0.10)}
    </style></head><body><div class="panel">${body}</div></body></html>`;
}
