/**
 * Turn a component's **layout-inspector tree** into a redline-grade, editable SVG
 * wireframe: one shape per layout node, positioned in the render's pixel space,
 * styled from the node's resolved design tokens (background / border colour,
 * per-corner radius, padding). Unlike the a11y-greenline wireframe (which only
 * sees nodes that carry accessibility semantics) the layout tree carries *every*
 * `LayoutNode` — the Row/Column/Box slot containers, the per-segment background
 * pills, the padding boxes — so the wireframe captures real slot regions, not
 * just touch targets.
 *
 * Source: the `layout/inspector` data product baked by the daemon
 * (`layout-inspector.json`), carried in the preview bundle as
 * `previews/<id>.layout.json`. See `ComposeLayoutInspector` /
 * `ModifierTokenResolver` in compose-ai-tools for how the tree + tokens are
 * produced.
 *
 * Pure + dependency-free, like the other render-*.mjs: tree in (plus the render
 * density, since tokens are dp and bounds are px), SVG string out (or null when
 * there's nothing drawable).
 */

/** Stable colour per node "kind" for structural (token-less) nodes, hashed so
 *  unknown component names stay distinct but stable. */
function depthStroke(depth) {
  const palette = [
    "#5B6470", "#2E7D6B", "#8E6BA8", "#B0813B", "#3B72A8", "#A85B6B", "#4F8A4A", "#7A7A33",
  ];
  return palette[depth % palette.length];
}

function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** Stable filename/anchor fragment — shared with the driver + index. */
export function slug(s) {
  return String(s ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
}

/** A Compose token colour `#AARRGGBB` → `{ rgb: "#RRGGBB", alpha: 0..1 }`, or null. */
function parseColor(token) {
  if (typeof token !== "string") return null;
  const m = /^#([0-9a-fA-F]{8})$/.exec(token.trim());
  if (!m) {
    const m6 = /^#([0-9a-fA-F]{6})$/.exec(token.trim());
    return m6 ? { rgb: `#${m6[1]}`, alpha: 1 } : null;
  }
  const aa = parseInt(m[1].slice(0, 2), 16);
  return { rgb: `#${m[1].slice(2)}`, alpha: Math.round((aa / 255) * 1000) / 1000 };
}

/** Parse a `cornerRadius` token to four px radii [tl, tr, br, bl].
 *  The token is `"<tl>dp,<tr>dp,<br>dp,<bl>dp"` (topStart,topEnd,bottomEnd,bottomStart
 *  — already LTR-resolved) or a single `"<r>dp"`. dp→px via [density]. */
function parseCorners(token, density) {
  if (typeof token !== "string") return null;
  const nums = token.split(",").map((p) => parseFloat(p));
  if (nums.some((n) => Number.isNaN(n))) return null;
  const px = nums.map((n) => n * density);
  if (px.length === 1) return [px[0], px[0], px[0], px[0]];
  if (px.length === 4) return px;
  return null;
}

/** A rounded-rect SVG path with independent corner radii, each clamped to half
 *  the box's shorter side so a "pill" radius never overshoots. Corners are
 *  [topLeft, topRight, bottomRight, bottomLeft]. */
function roundedRectPath(x, y, w, h, corners) {
  const lim = Math.min(w, h) / 2;
  const [tl, tr, br, bl] = corners.map((c) => Math.max(0, Math.min(c, lim)));
  return (
    `M${x + tl},${y} H${x + w - tr} A${tr},${tr} 0 0 1 ${x + w},${y + tr} ` +
    `V${y + h - br} A${br},${br} 0 0 1 ${x + w - br},${y + h} ` +
    `H${x + bl} A${bl},${bl} 0 0 1 ${x},${y + h - bl} ` +
    `V${y + tl} A${tl},${tl} 0 0 1 ${x + tl},${y} Z`
  );
}

/** Short redline label for a token-bearing node: its key tokens, not the
 *  Compose container class (which is almost always a generic Box/Row noise). */
function tokenLabel(tokens) {
  if (!tokens) return "";
  const parts = [];
  if (tokens.backgroundColor) parts.push(tokens.backgroundColor);
  else if (tokens.borderColor) parts.push(`◻ ${tokens.borderColor}`);
  if (tokens.padding) {
    const p = tokens.padding;
    const u = new Set([p.start, p.top, p.end, p.bottom].filter(Boolean));
    parts.push(`pad ${u.size === 1 ? [...u][0] : "·"}`);
  }
  if (tokens.gap) parts.push(`gap ${tokens.gap}`);
  return parts.join("  ");
}

/** Flatten the tree to drawable entries (positive-area bounds only), pre-order with depth. */
function collect(node, depth, out) {
  const b = node.bounds;
  if (b && b.right > b.left && b.bottom > b.top) {
    out.push({ node, depth, b });
  }
  for (const child of node.children ?? []) collect(child, depth + 1, out);
}

/**
 * Render the layout-tree wireframe SVG, or null if nothing is drawable.
 * @param {object} component a catalog component carrying `layout` (the inspector
 *   tree root) and `images` (for the canvas frame); `density` from its params.
 * @param {object} [opts] { density }
 * @returns {string|null}
 */
export function renderLayoutWireframeSvg(component, opts = {}) {
  const root = component.layout;
  if (!root) return null;
  const density = opts.density || component.density || 1;

  const entries = [];
  collect(root, 0, entries);
  if (!entries.length) return null;

  const maxX = Math.max(...entries.map((e) => e.b.right));
  const maxY = Math.max(...entries.map((e) => e.b.bottom));
  // Prefer the catalog image that frames these bounds, so the wireframe shares
  // the sticker's canvas; fall back to the bounds' own extent.
  const images = component.images ?? [];
  const frame = images
    .filter((i) => i.width >= maxX && i.height >= maxY)
    .sort((a, b) => a.width * a.height - b.width * b.height)[0];
  const canvas = frame ? { w: frame.width, h: frame.height } : { w: maxX, h: maxY };

  const shapes = entries
    .map(({ node, depth, b }) => {
      const x = b.left;
      const y = b.top;
      const w = b.right - b.left;
      const h = b.bottom - b.top;
      const t = node.tokens ?? null;
      const bg = t && parseColor(t.backgroundColor);
      const border = t && parseColor(t.borderColor);
      const corners = t && parseCorners(t.cornerRadius, density);

      // Token-bearing nodes are the "real" redline rects: filled / stroked from
      // tokens. Structural nodes get a thin depth-hued outline so the slot
      // nesting still reads without fighting the styled boxes.
      const hasToken = Boolean(bg || border || corners);
      const stroke = border ? border.rgb : depthStroke(depth);
      const strokeWidth = hasToken ? 1.5 : 1;
      const strokeOpacity = hasToken ? 1 : 0.5;
      const fill = bg ? bg.rgb : "none";
      const fillOpacity = bg ? bg.alpha : 0;

      const attrs =
        `fill="${fill}" fill-opacity="${fillOpacity}" stroke="${stroke}" ` +
        `stroke-width="${strokeWidth}" stroke-opacity="${strokeOpacity}"`;
      const shape = corners
        ? `<path d="${roundedRectPath(x, y, w, h, corners)}" ${attrs}/>`
        : `<rect x="${x}" y="${y}" width="${w}" height="${h}" ${attrs}/>`;

      // Label only token-bearing boxes (with their token values), keeping the
      // diagram legible — structural containers stay unlabelled.
      const label = hasToken ? tokenLabel(t) : "";
      const text =
        label && w > 24
          ? `\n    <text x="${x + 3}" y="${y + 12}" font-family="system-ui, sans-serif" font-size="9" fill="${stroke}">${esc(label)}</text>`
          : "";
      return `  <g>${"\n    "}${shape}${text}\n  </g>`;
    })
    .join("\n");

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${canvas.w} ${canvas.h}" width="${canvas.w}" height="${canvas.h}" role="img" aria-label="${esc(component.componentId)} layout wireframe">
  <title>${esc(component.componentId)} — layout wireframe (slots + tokens)</title>
  <rect width="${canvas.w}" height="${canvas.h}" fill="#ffffff"/>
${shapes}
</svg>
`;
}
