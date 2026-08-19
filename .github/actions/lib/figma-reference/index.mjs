/**
 * Figma reference images for the preview-diff comment.
 *
 * A design catalog's PR comment answers "did these pixels move?". It does not answer the question
 * the catalog exists for — "do these pixels match the design?" — because the thing they are
 * supposed to match is in Figma and the comment is not. A reviewer has to open the kit in another
 * tab and eyeball it, which is exactly the manual step the render pipeline removed everywhere else.
 *
 * So the comment grows a third column, and this file fills it. Given the repo's own
 * `design-map.json` (which preview implements which design node) and its committed **page cache**
 * (`design/pages/*.svg`, one SVG per kit page with `data-node-id` on every element), it cuts a
 * mapped component's node out of the page and rasterises just that node.
 *
 * ## Why the cache, and not the Figma API
 *
 * Three properties, all of which the live API lacks:
 *
 * - **No credential.** A PR from a fork gets no `FIGMA_TOKEN`, and a preview comment that silently
 *   loses a column on exactly the PRs an outside contributor opens is worse than no column.
 * - **No per-push traffic.** The import workflow owns Figma traffic on its own cadence
 *   (`figma-pages.yml`); a rate limit costs freshness, never a diff.
 * - **It is pinned.** The reference a PR is judged against is the one committed at that PR's merge
 *   base, so a designer's edit mid-review does not silently rewrite the column under the reviewer.
 *
 * The cost is that the column is only as fresh as the last import. That is the right trade for a
 * review aid — and drift is what design-parity reports, on its own schedule.
 *
 * ## What "cut the node out" means
 *
 * A Figma SVG export is a document, not a picture: every element carries `data-node-id`. Cutting a
 * node out is therefore textual — take the element's subtree, re-wrap it in the ancestor elements
 * it was nested in (so their `transform`, `clip-path` and opacity still apply), carry the
 * document's `<defs>` (so `url(#clip0)` references still resolve), and keep the page's own
 * `viewBox` so coordinates stay meaningful. The result is a standalone SVG that draws exactly one
 * node in page space, which resvg then crops to the ink it actually put down.
 *
 * Cropping to ink rather than to the node's frame is a deliberate approximation: an SVG export
 * carries no frame box (see `DesignPages.kt` — recording one would give one question two answers),
 * and a transparent-background component's frame is not recoverable from the markup. For a
 * side-by-side thumbnail that is the honest bound; anything else would be invented.
 *
 * Everything here is **fail-soft**. No design map, no page cache, no rasteriser, a node the export
 * does not carry — each is a comment without a Figma column, never a failed job.
 */

import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

/**
 * Matches one markup token: an element open/close tag, a comment, or a processing instruction.
 *
 * A regex rather than an XML parser because the input is a machine-written export (no CDATA, no
 * unquoted attributes, always well-formed) that runs to tens of megabytes, and because the slice
 * this produces must be **byte-identical** markup rather than a re-serialisation — a DOM round-trip
 * is where namespaces and numeric precision quietly change. The attribute group swallows quoted
 * strings so a `>` inside an attribute value cannot end a tag early.
 */
const TOKEN = /<(\/?)([A-Za-z_][-\w.:]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>|<!--[\s\S]*?-->|<\?[\s\S]*?\?>/g

/** Elements that only ever carry definitions, and must be reproduced whole in a slice. */
const DEFS = /<defs[\s>][\s\S]*?<\/defs>/g

/**
 * Read `design-map.json` into `previewId → { ref, fileKey, nodeId, code }`.
 *
 * Two shapes are in the wild and both are the same contract. `@yschimke/compose-design-map` emits a
 * component with a **string** `ref` and a **string** `previewId` — one reference, one render. A kit
 * resolver (design-parity, which owns the kit's variant vocabulary) then upgrades both into
 * **arrays** of `{ ref | previewId, state }`, one entry per resolved variant. The join is `state`,
 * not array position: a resolver that resolves four of six variants emits four refs and six
 * preview ids, and pairing those by index maps renders onto the wrong nodes.
 *
 * Entries whose ref names a non-Figma source (`ref` without the `figma:` scheme) are skipped —
 * this reads a Figma page cache, and nothing else knows what to do with the handle.
 */
export function parseDesignMap(json) {
  const out = new Map()
  const components = Array.isArray(json?.components) ? json.components : []
  for (const component of components) {
    const refs = byState(component.ref, 'ref')
    const previews = byState(component.previewId, 'previewId')
    for (const [state, previewId] of previews) {
      const ref = refs.get(state)
      if (!ref || typeof previewId !== 'string') continue
      const parsed = parseRef(ref)
      if (!parsed) continue
      out.set(previewId, { ref, code: component.code ?? '', ...parsed })
    }
  }
  return out
}

/** `"figma:<fileKey>/<nodeId>"` → `{ fileKey, nodeId }`; null for any other scheme. */
export function parseRef(ref) {
  if (typeof ref !== 'string' || !ref.startsWith('figma:')) return null
  const slash = ref.indexOf('/', 'figma:'.length)
  if (slash < 0) return null
  const fileKey = ref.slice('figma:'.length, slash)
  const nodeId = ref.slice(slash + 1)
  if (!fileKey || !nodeId) return null
  return { fileKey, nodeId }
}

/** Normalise a string-or-array design-map field into `state → value`; the base entry is `''`. */
function byState(field, key) {
  const out = new Map()
  if (typeof field === 'string') {
    out.set('', field)
  } else if (Array.isArray(field)) {
    for (const entry of field) {
      if (!entry || typeof entry !== 'object') continue
      const value = entry[key]
      if (typeof value !== 'string') continue
      out.set(entry.state ?? '', value)
    }
  }
  return out
}

/**
 * The SVG files a page-cache manifest (`design/pages/pages.json`) points at, resolved against the
 * directory the manifest itself lives in.
 *
 * Only v2 manifests are read, and only SVG images: a raster page has nothing addressable in it, so
 * there is no node to cut out. Same refusal `supportsDesignPagesVersion` makes on the serve side —
 * a stale manifest yields no column rather than a wrong one.
 */
export function pageFiles(manifest, dir) {
  if (manifest?.version !== 2) return []
  const pages = Array.isArray(manifest.pages) ? manifest.pages : []
  return pages
    .filter((page) => page?.image?.format === 'svg' && typeof page.image.uri === 'string')
    .map((page) => ({ id: page.id ?? '', file: path.join(dir, page.image.uri) }))
}

/**
 * Cut the element carrying `data-node-id="<nodeId>"` out of `svg`, as a standalone document.
 *
 * Returns null when the export carries no such node — the ordinary outcome for a component whose
 * reference lives on a page the cache excludes (the kit's Stickersheet, for one, is too large to
 * commit), and the reason every caller treats a miss as "no column" rather than an error.
 */
export function sliceNode(svg, nodeId) {
  const needle = `data-node-id="${nodeId}"`
  // Cheap reject first: these documents run to megabytes and most pages hold no given node.
  if (!svg.includes(needle)) return null

  const stack = []
  let match
  TOKEN.lastIndex = 0
  while ((match = TOKEN.exec(svg))) {
    const [full, closing, name, attrs, selfClosing] = match
    if (name === undefined) continue // comment / processing instruction
    if (closing) {
      stack.pop()
      continue
    }
    const hit = attrs.includes(needle)
    if (selfClosing) {
      if (hit) return wrap(svg, stack, full)
      continue
    }
    if (hit) {
      const end = closeOf(svg, TOKEN.lastIndex, name)
      if (end < 0) return null
      return wrap(svg, stack, svg.slice(match.index, end))
    }
    stack.push({ name, text: full })
  }
  return null
}

/** Index of the end of the tag that closes an element opened just before `from`. */
function closeOf(svg, from, name) {
  const scan = new RegExp(TOKEN.source, 'g')
  scan.lastIndex = from
  let depth = 1
  let match
  while ((match = scan.exec(svg))) {
    const [full, closing, tag, , selfClosing] = match
    if (tag === undefined || selfClosing) continue
    if (closing) {
      if (tag === name) depth -= 1
      if (depth === 0) return match.index + full.length
    } else if (tag === name) {
      depth += 1
    }
  }
  return -1
}

/**
 * Re-wrap a node's markup in the ancestors it was nested in, plus the document's definitions.
 *
 * The ancestors are what make the slice draw where — and how — the page draws it: Figma hangs
 * `transform`, `clip-path`, `opacity` and blend modes off group elements, and a node lifted out of
 * them lands at the wrong offset, unclipped. The definitions come along whole because a slice
 * cannot know which `url(#…)` its subtree reaches; they cost bytes, and resvg drops what nothing
 * references.
 */
function wrap(svg, stack, inner) {
  if (!stack.length || stack[0].name !== 'svg') return null
  const defs = svg.match(DEFS)?.join('\n') ?? ''
  const open = stack.map((element) => element.text).join('\n')
  // Every wrapper except the root closes as its own tag name; the root closes the document.
  const close = stack
    .slice(1)
    .map((element) => `</${element.name}>`)
    .reverse()
    .join('')
  return `${open}\n${defs}\n${inner}\n${close}</svg>`
}

/**
 * Load the rasteriser, or null when it isn't installed. Fail-soft: no resvg, no column.
 *
 * `FIGMA_REFERENCE_RESVG` names a directory to resolve it from, for a caller that installs the
 * dependency somewhere other than beside this file. It exists because ESM resolution — unlike
 * `require` — ignores `NODE_PATH`, so "install to a temp prefix and point the process at it" is not
 * a thing an `import` specifier can express.
 */
export async function loadResvg(from = process.env.FIGMA_REFERENCE_RESVG) {
  const specifiers = ['@resvg/resvg-js']
  if (from) specifiers.unshift(pathToFileURL(path.join(from, '@resvg/resvg-js/index.js')).href)
  for (const specifier of specifiers) {
    try {
      return (await import(specifier)).Resvg
    } catch {
      // Next candidate; an absent rasteriser is a missing column, not a failure.
    }
  }
  return null
}

/**
 * Rasterise a slice, cropped to the ink it draws.
 *
 * `zoom` exists because kit nodes are small in page units — a compact button is 137×32 — and the
 * comment shows the column at 200px wide beside a render that was captured at device density.
 * Rendering at 1× and letting the browser upscale is how a reference column reads as blurry next
 * to the pixels it is meant to be compared with.
 */
export function renderPng(Resvg, doc, { zoom = 3, maxPixels = 4_000_000, background } = {}) {
  const probe = new Resvg(doc, { font: { loadSystemFonts: false } })
  const box = probe.getBBox()
  if (!box || !(box.width > 0) || !(box.height > 0)) return null
  const scale = Math.min(zoom, Math.sqrt(maxPixels / (box.width * box.height)))
  const image = new Resvg(doc, {
    font: { loadSystemFonts: false },
    fitTo: { mode: 'zoom', value: Math.max(1, scale) },
    ...(background ? { background } : {}),
  })
  image.cropByBBox(image.getBBox())
  return image.render().asPng()
}

/**
 * The colour the page draws *behind* a node, sampled just outside its bounding box.
 *
 * A slice arrives with a transparent background, and a transparent background is not neutral: the
 * Wear kit draws light-on-near-black, so an un-backed reference is near-invisible against a light
 * comment and a light kit's reference vanishes against a dark one. The sheet already knows the
 * answer — it painted a backdrop under the component — so read it off the page rather than pick a
 * colour and be wrong for half the kits in the world.
 *
 * Sampled outside the box rather than under the component, because under it is the component.
 */
export function backgroundAt(page, box, pad = 8) {
  const candidates = [
    [box.x - pad, box.y + box.height / 2],
    [box.x + box.width + pad, box.y + box.height / 2],
    [box.x + box.width / 2, box.y - pad],
  ]
  for (const [ux, uy] of candidates) {
    const px = Math.round((ux - page.minX) * page.scale)
    const py = Math.round((uy - page.minY) * page.scale)
    if (px < 0 || py < 0 || px >= page.width || py >= page.height) continue
    const at = (py * page.width + px) * 4
    const [r, g, b, a] = page.pixels.subarray(at, at + 4)
    // A transparent sample means the page itself draws nothing there; leave the thumbnail
    // transparent too rather than inventing a backdrop out of an unpainted pixel.
    if (a !== 255) continue
    return `#${[r, g, b].map((c) => c.toString(16).padStart(2, '0')).join('')}`
  }
  return undefined
}

/**
 * Rasterise a whole page once, small, purely so [backgroundAt] has something to sample.
 *
 * Deliberately capped well below the export's own size: this bitmap is read one pixel at a time,
 * and a kit page at full scale is a 20-megapixel, 80 MB buffer that answers exactly the same
 * question as a quarter-scale one.
 */
export function rasterisePage(Resvg, svg, { maxPixels = 4_000_000 } = {}) {
  const view = viewBoxOf(svg)
  if (!view) return null
  const scale = Math.min(1, Math.sqrt(maxPixels / (view.width * view.height)))
  const image = new Resvg(svg, {
    font: { loadSystemFonts: false },
    fitTo: { mode: 'zoom', value: scale },
  })
  const rendered = image.render()
  return {
    pixels: rendered.pixels,
    width: rendered.width,
    height: rendered.height,
    // The *achieved* scale: resvg rounds the pixmap to whole pixels, so deriving it back from the
    // rendered width keeps a sample coordinate from drifting on a page with an odd size.
    scale: rendered.width / view.width,
    minX: view.minX,
    minY: view.minY,
  }
}

/** `viewBox="minX minY width height"` off the root element; null when the export declares none. */
export function viewBoxOf(svg) {
  const match = /<svg[^>]*\sviewBox="([-\d.\s]+)"/.exec(svg)
  if (!match) return null
  const [minX, minY, width, height] = match[1].trim().split(/\s+/).map(Number)
  if (!(width > 0) || !(height > 0)) return null
  return { minX, minY, width, height }
}

/** File-system-safe basename for a preview's reference image. */
export function referenceBasename(previewId) {
  return `${previewId.replace(/[^A-Za-z0-9._-]/g, '_')}.png`
}

/**
 * Stage a reference PNG for every preview in `previews` that the design map maps onto a node the
 * page cache carries, and return the manifest the comment renders from.
 *
 * `previews` is `[{ previewId, module }]` — the changed/new set the comment will show, not the
 * whole catalog. Rendering the catalog's full sheet costs a page parse per node for images no
 * reviewer will see; the diff already knows which handful moved.
 */
export async function stage({ designMap, pagesManifest, pagesDir, previews, outputDir }) {
  const Resvg = await loadResvg()
  if (!Resvg) return { entries: {}, skipped: 'resvg is not installed' }

  const wanted = new Map()
  for (const preview of previews) {
    const mapped = designMap.get(preview.previewId)
    if (mapped) wanted.set(preview.previewId, { ...preview, ...mapped })
  }
  if (!wanted.size) return { entries: {}, skipped: 'no changed preview is mapped to a design node' }

  const entries = {}
  // Page by page, so a page's markup is read and searched once however many of its nodes are
  // wanted: these exports are megabytes each and a catalog's changed set clusters on one sheet.
  for (const page of pageFiles(pagesManifest, pagesDir)) {
    const outstanding = [...wanted].filter(([id]) => !entries[id])
    if (!outstanding.length) break
    let svg
    try {
      svg = fs.readFileSync(page.file, 'utf8')
    } catch {
      continue
    }
    // Rendered lazily and at most once per page: only pages that actually carry a wanted node pay
    // for it, and a page whose nodes all fail to slice never rasterises at all.
    let backdrop
    for (const [previewId, target] of outstanding) {
      const doc = sliceNode(svg, target.nodeId)
      if (!doc) continue
      let png
      try {
        if (backdrop === undefined) backdrop = rasterisePage(Resvg, svg) ?? null
        const box = new Resvg(doc, { font: { loadSystemFonts: false } }).getBBox()
        png = renderPng(Resvg, doc, {
          background: backdrop && box ? backgroundAt(backdrop, box) : undefined,
        })
      } catch (error) {
        console.error(`figma reference: ${target.ref} failed to rasterise — ${error.message}`)
        continue
      }
      if (!png) continue
      const basename = referenceBasename(previewId)
      const dest = path.join(outputDir, target.module, basename)
      fs.mkdirSync(path.dirname(dest), { recursive: true })
      fs.writeFileSync(dest, png)
      entries[previewId] = {
        module: target.module,
        basename,
        nodeId: target.nodeId,
        ref: target.ref,
        page: page.id,
        url: figmaUrl(target.fileKey, target.nodeId),
      }
    }
  }
  return { entries }
}

/** Deep link to the node in Figma, so the column's caption opens the thing it pictures. */
export function figmaUrl(fileKey, nodeId) {
  return `https://www.figma.com/design/${fileKey}/?node-id=${encodeURIComponent(nodeId.replace(':', '-'))}`
}

function arg(argv, name, fallback = '') {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : fallback
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    return null
  }
}

/**
 * `node index.mjs --design-map design-map.json --pages design/pages/pages.json
 *   --previews _figma_previews.json --output-dir _pr_renders/figma --manifest _figma_refs.json`
 *
 * Exits 0 on every foreseeable miss, writing no manifest — the comment step treats an absent
 * manifest as "no Figma column", which is the whole degradation story.
 */
export async function main(argv = process.argv.slice(2)) {
  const designMapPath = arg(argv, 'design-map')
  const pagesPath = arg(argv, 'pages')
  const previewsPath = arg(argv, 'previews')
  const outputDir = arg(argv, 'output-dir')
  const manifestPath = arg(argv, 'manifest')

  const designMapJson = readJson(designMapPath)
  if (!designMapJson) {
    console.error(`figma reference: no readable design map at ${designMapPath || '(unset)'}; skipping.`)
    return 0
  }
  const pagesJson = readJson(pagesPath)
  if (!pagesJson) {
    console.error(`figma reference: no readable page cache at ${pagesPath || '(unset)'}; skipping.`)
    return 0
  }
  const previews = readJson(previewsPath)
  if (!Array.isArray(previews) || !previews.length) {
    console.error('figma reference: no changed previews to illustrate; skipping.')
    return 0
  }

  const { entries, skipped } = await stage({
    designMap: parseDesignMap(designMapJson),
    pagesManifest: pagesJson,
    pagesDir: path.dirname(pagesPath),
    previews,
    outputDir,
  })
  if (skipped) {
    console.error(`figma reference: ${skipped}; skipping.`)
    return 0
  }
  const count = Object.keys(entries).length
  if (!count) {
    console.error('figma reference: the page cache carries none of the mapped nodes; skipping.')
    return 0
  }
  fs.writeFileSync(manifestPath, `${JSON.stringify({ entries }, null, 2)}\n`)
  console.error(`figma reference: staged ${count} reference image(s) from the committed page cache.`)
  return 0
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().then((code) => {
    process.exitCode = code
  })
}
