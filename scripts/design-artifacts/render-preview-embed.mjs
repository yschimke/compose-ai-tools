/**
 * A reusable **preview embed** for the design-artifact HTML pages: a static PNG
 * thumbnail of a rendered Compose preview that links to the live, editable
 * preview on the server when clicked.
 *
 * Static-first by design. The thumbnail is a plain `<img>` with a baked URL, so
 * it renders anywhere the page is opened — the `design-artifacts/<system>` branch
 * on github.com, `htmlpreview.github.io`, or a local `file://` — with **no
 * runtime fetch**. (An earlier cross-system page resolved the sibling thumbnails
 * by `fetch()`-ing the other branch's `catalog.json` at view time and injecting
 * an `<img>`; under htmlpreview's CSP that fetch silently failed and the cell was
 * stuck on "loading …" forever. A baked `<img>` has none of that fragility — it's
 * exactly why the same page's own-branch column always rendered.) The
 * click-through *upgrades* to the live server, where the component re-renders
 * under different themes / locales / devices — see `live-preview.mjs` for the
 * URL derivation.
 *
 * Pure + dependency-free: every function returns an HTML (or CSS) string. A page
 * drops one {@link previewEmbedStyles} block into its `<style>` and calls
 * {@link previewEmbed} per cell. Customise via the options bag — sizing, the
 * frame (checkerboard / solid / none), the caption, whether the click-through is
 * shown, alt text, lazy vs eager loading — so the same primitive serves a tight
 * comparison grid and a roomy single-component hero.
 */

/** Minimal HTML-escape for text/attributes interpolated into the page. */
export function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** A component's `ideal` capture images (excludes the `layout` wireframe variant). */
function idealImages(component) {
  return (component?.images ?? []).filter((i) => i.variant === "ideal");
}

/**
 * Pick the hero image record for a component — the render a thumbnail should
 * show. Prefers the `ideal` variant, the resting default (no folded `state`, no
 * content-axis `props`), the requested `theme` when the render carries one, and
 * the largest capture. Mirrors the pick the index / compare pages make so a
 * component looks the same wherever it's embedded. Returns the image record
 * (with `.path`, and `.livePreview` if the manifest annotated it) or undefined.
 *
 * @param {object} component a manifest component ({ images: [...] })
 * @param {object} [sel] selectors: { theme } — theme is a soft preference
 */
export function heroImageOf(component, sel = {}) {
  const ideal = idealImages(component);
  const pool = ideal.length ? ideal : (component?.images ?? []);
  if (!pool.length) return undefined;
  const defaults = pool.filter(
    (i) => Object.keys(i.props ?? {}).length === 0 && (i.state ?? "default") === "default",
  );
  const byDefault = defaults.length ? defaults : pool;
  const wantTheme = sel.theme ?? "light";
  const themed = byDefault.filter((i) => i.theme === wantTheme);
  const chooseFrom = themed.length ? themed : byDefault;
  return [...chooseFrom].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/**
 * The image a *variant* of [component] is showing, picked out of the parent's folded `images[]`.
 *
 * `catalog-variants.mjs` folds every variant render onto its parent, re-tagged with that variant's
 * `state` / `props` / `theme`. So the pixels are already there — what this does is pick them back
 * out, by matching the same tags the fold wrote. Without it a variant row on the compare page would
 * show the parent's default render and quietly compare the wrong picture.
 *
 * Returns `undefined` when nothing matches, which the caller shows as "not rendered yet" rather
 * than falling back to the parent's hero: a wrong thumbnail is worse than an honest gap here.
 *
 * @param {object} component a manifest component ({ images: [...] })
 * @param {{state?: string, props?: Record<string, unknown>, theme?: string}} variant
 */
export function variantImageOf(component, variant) {
  const pool = idealImages(component);
  const chooseFrom = (pool.length ? pool : (component?.images ?? [])).filter((image) => {
    // A variant's `state` replaces the image's; absent, the fold leaves the default in place.
    if ((image.state ?? "default") !== (variant?.state ?? "default")) return false;
    if (variant?.theme !== undefined && image.theme !== variant.theme) return false;
    const want = variant?.props ?? {};
    const have = image.props ?? {};
    // The fold MERGES a props variant onto the image's props, so the image may carry more than the
    // variant named. Every named axis must match; extras are allowed.
    return Object.keys(want).every((key) => String(have[key]) === String(want[key]));
  });
  if (!chooseFrom.length) return undefined;
  return [...chooseFrom].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/**
 * Render one preview embed.
 *
 * @param {object} opts
 * @param {string} [opts.imageUrl]   static PNG URL (absolute, or relative to the
 *   page). When absent the embed renders its {@link opts.fallback} state instead
 *   of a broken image — this is the "not rendered yet" cell.
 * @param {string} [opts.liveUrl]    live-server deep link; when set the whole
 *   thumbnail becomes a click-through and a "live ↗" affordance is shown.
 * @param {string} [opts.alt]        image alt text (defaults to "").
 * @param {string} [opts.label]      optional caption rendered under the frame.
 * @param {string} [opts.liveLabel]  text of the click-through affordance ("live ↗").
 * @param {number} [opts.maxWidth]   max thumbnail width in px (default 260).
 * @param {number} [opts.maxHeight]  max thumbnail height in px (default 200).
 * @param {"lazy"|"eager"} [opts.loading]  <img> loading (default "lazy").
 * @param {"checker"|"solid"|"none"} [opts.frame]  frame background (default "checker").
 * @param {string} [opts.fallback]   text shown when there's no imageUrl (default "no preview").
 * @param {string} [opts.linkTarget] anchor target for liveUrl (default "_blank").
 * @param {string} [opts.className]  extra class(es) on the root element.
 * @param {string} [opts.title]      hover title on the root element.
 * @param {string} [opts.dataParallel] optional `data-parallel` attribute (kept
 *   for callers that still want a client hook; the static embed itself needs none).
 * @returns {string} the embed's HTML
 */
export function previewEmbed(opts = {}) {
  const {
    imageUrl,
    liveUrl,
    alt = "",
    label,
    liveLabel = "live ↗",
    maxWidth = 260,
    maxHeight = 200,
    loading = "lazy",
    frame = "checker",
    fallback = "no preview",
    linkTarget = "_blank",
    className = "",
    title,
    dataParallel,
  } = opts;

  const frameClass =
    frame === "solid" ? "pv-frame pv-frame--solid" : frame === "none" ? "pv-frame pv-frame--bare" : "pv-frame pv-frame--checker";
  const sizeStyle = `--pv-max-w:${Number(maxWidth)}px;--pv-max-h:${Number(maxHeight)}px`;

  const inner = imageUrl
    ? `<img class="pv-img" src="${esc(imageUrl)}" alt="${esc(alt)}" loading="${esc(loading)}" decoding="async" />`
    : `<span class="pv-missing">${esc(fallback)}</span>`;

  const frameHtml = `<span class="${frameClass}" style="${sizeStyle}">${inner}</span>`;
  const liveHtml = liveUrl && imageUrl ? `<span class="pv-live">${esc(liveLabel)}</span>` : "";
  const labelHtml = label ? `<span class="pv-label">${esc(label)}</span>` : "";
  const rootClass = `pv-embed${className ? ` ${esc(className)}` : ""}`;
  const titleAttr = title ? ` title="${esc(title)}"` : "";
  const dataAttr = dataParallel ? ` data-parallel="${esc(dataParallel)}"` : "";

  // A live link makes the whole thumbnail clickable; otherwise it's an inert span.
  if (liveUrl && imageUrl) {
    return `<a class="${rootClass}" href="${esc(liveUrl)}" target="${esc(linkTarget)}" rel="noopener"${titleAttr}${dataAttr}>${frameHtml}${liveHtml}${labelHtml}</a>`;
  }
  return `<span class="${rootClass}"${titleAttr}${dataAttr}>${frameHtml}${labelHtml}</span>`;
}

/**
 * The CSS for every {@link previewEmbed} on a page — drop once into `<style>`.
 * Namespaced under `.pv-*` so it composes with a page's own styles. Per-embed
 * sizing rides CSS custom properties (`--pv-max-w` / `--pv-max-h`) set inline by
 * {@link previewEmbed}, so one stylesheet serves every thumbnail size.
 *
 * @param {object} [opts]
 * @param {string} [opts.accent]  link/hover accent colour (default "#8ab4f8").
 * @param {string} [opts.muted]   caption / fallback colour (default "#9b9ba1").
 * @returns {string} a CSS block
 */
export function previewEmbedStyles({ accent = "#8ab4f8", muted = "#9b9ba1" } = {}) {
  return `
  .pv-embed { position:relative; display:inline-flex; flex-direction:column; gap:6px; text-decoration:none; color:inherit; }
  .pv-frame { position:relative; display:inline-grid; place-items:center; min-width:120px; min-height:80px;
    padding:10px; border-radius:10px; background-color:#161617; }
  .pv-frame--checker { background-image:
      linear-gradient(45deg,#202022 25%,transparent 25%),
      linear-gradient(-45deg,#202022 25%,transparent 25%),
      linear-gradient(45deg,transparent 75%,#202022 75%),
      linear-gradient(-45deg,transparent 75%,#202022 75%);
    background-size:16px 16px; background-position:0 0,0 8px,8px -8px,-8px 0; }
  .pv-frame--solid { background-color:#1b1b1d; }
  .pv-frame--bare { background:none; padding:0; }
  .pv-img { max-width:var(--pv-max-w,260px); max-height:var(--pv-max-h,200px); height:auto; display:block; }
  .pv-missing { color:${muted}; font-style:italic; font-size:12px; text-align:center; padding:0 8px; }
  .pv-label { font-size:12px; color:${muted}; }
  .pv-live { position:absolute; top:6px; right:8px; font-size:11px; color:${accent};
    background:rgba(10,10,12,0.72); border-radius:6px; padding:1px 6px; opacity:0; transition:opacity .12s; pointer-events:none; }
  a.pv-embed:hover .pv-live, a.pv-embed:focus-visible .pv-live { opacity:1; }
  a.pv-embed:hover .pv-frame, a.pv-embed:focus-visible .pv-frame { outline:1px solid ${accent}; outline-offset:1px; }
  @media (max-width:640px){ .pv-img { max-width:min(var(--pv-max-w,260px),150px); } .pv-live { opacity:1; } }
`;
}
