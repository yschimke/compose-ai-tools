/**
 * Turn a catalog component's semantic regions into an **editable SVG wireframe**:
 * one labelled `<rect>` per greenline (a11y region), positioned in the render's
 * pixel space and coloured by role. Unlike the raster PNG, the SVG opens in any
 * vector tool (Figma / Sketch / Illustrator / Penpot / Inkscape) as discrete,
 * editable shapes — the layer a developer adopts to rebuild the structure.
 *
 * Pure + dependency-free, like render-index-html.mjs: takes the in-memory catalog
 * component and returns an SVG string (or null when there's nothing to draw).
 */

/** Stable colour per semantic role; a hash fallback keeps unknown roles distinct. */
const ROLE_COLORS = {
  button: "#4f8cff",
  checkbox: "#3fbf6f",
  switch: "#a06bff",
  radiobutton: "#3fbf6f",
  image: "#e0a040",
  text: "#9b9ba1",
  edgebutton: "#4f8cff",
  card: "#5bbcc8",
  header: "#c87da0",
};

function roleColor(role) {
  const key = String(role ?? "").toLowerCase().replace(/[^a-z]/g, "");
  if (ROLE_COLORS[key]) return ROLE_COLORS[key];
  let h = 0;
  for (const c of key) h = (h * 31 + c.charCodeAt(0)) & 0xffff;
  return `hsl(${h % 360} 55% 60%)`;
}

function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** Stable filename/anchor fragment from arbitrary text — shared by the driver
 *  (where it writes `wireframes/<slug>.svg`) and the index (where it links it). */
export function slug(s) {
  return String(s ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
}

/** The greenlines that carry a drawable rect. */
function regions(component) {
  return (component.greenlines ?? []).filter(
    (g) => g.bounds && g.bounds.width > 0 && g.bounds.height > 0,
  );
}

/**
 * The canvas the region bounds live in. The regions come from one rendered
 * variant, so prefer the smallest catalog image that contains every region (that
 * variant's frame, margins included); fall back to the regions' own extent.
 */
function canvasFor(component, regs) {
  const extW = Math.max(0, ...regs.map((g) => g.bounds.x + g.bounds.width));
  const extH = Math.max(0, ...regs.map((g) => g.bounds.y + g.bounds.height));
  const images = component.images ?? [];
  const containing = images
    .filter((i) => i.width >= extW && i.height >= extH)
    .sort((a, b) => a.width * a.height - b.width * b.height)[0];
  if (containing) return { w: containing.width, h: containing.height };
  if (extW > 0 && extH > 0) return { w: extW, h: extH };
  const largest = [...images].sort((a, b) => b.width * b.height - a.width * a.height)[0];
  return largest ? { w: largest.width, h: largest.height } : null;
}

/**
 * Render the component's wireframe SVG, or `null` if it has no drawable regions.
 * @param {object} component a catalog component (componentId, greenlines, images)
 * @returns {string|null}
 */
export function renderWireframeSvg(component) {
  const regs = regions(component);
  if (!regs.length) return null;
  const canvas = canvasFor(component, regs);
  if (!canvas) return null;

  const shapes = regs
    .map((g) => {
      const role = g.detail?.role ?? g.message ?? "element";
      const color = roleColor(role);
      const { x, y, width, height } = g.bounds;
      const labelY = y >= 18 ? y - 5 : y + 15;
      return `  <g>
    <rect x="${x}" y="${y}" width="${width}" height="${height}" rx="4" fill="${color}" fill-opacity="0.08" stroke="${color}" stroke-width="2"/>
    <text x="${x + 4}" y="${labelY}" font-family="system-ui, sans-serif" font-size="12" fill="${color}">${esc(role)}</text>
  </g>`;
    })
    .join("\n");

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${canvas.w} ${canvas.h}" width="${canvas.w}" height="${canvas.h}" role="img" aria-label="${esc(component.componentId)} wireframe">
  <title>${esc(component.componentId)} — layout wireframe</title>
  <rect width="${canvas.w}" height="${canvas.h}" fill="none" stroke="#888" stroke-dasharray="4 4"/>
${shapes}
</svg>
`;
}
