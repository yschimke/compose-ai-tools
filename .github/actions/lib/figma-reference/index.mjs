/**
 * Figma reference images for the preview-diff comment, so a reviewer can see whether pixels match
 * the design, not just whether they moved.
 *
 * Given the repo's `design-map.json` and its committed page cache (`design/pages/*.svg`, with
 * `data-node-id` on every element), it cuts a mapped component's node out of its page and
 * rasterises it.
 *
 * The cache, not the Figma API: no credential (fork PRs get no `FIGMA_TOKEN`), no per-push traffic
 * (`figma-pages.yml` owns that), and the reference is pinned at the PR's merge base. The column is
 * only as fresh as the last import; drift is design-parity's job.
 *
 * Cutting a node out is textual: take its subtree, re-wrap it in its ancestors (so `transform`,
 * `clip-path` and opacity still apply), carry the document's `<defs>`, keep the page `viewBox`,
 * then let resvg crop to the ink drawn. Ink rather than frame because an SVG export carries no
 * frame box.
 *
 * Everything is fail-soft: any missing input means no Figma column, never a failed job.
 */

import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

/**
 * Read the next markup token at or after `pos`: an element's open or close tag.
 *
 * Hand-written: not a parser, because slices must be byte-identical to Figma's markup; not a regex,
 * because a tag pattern over quoted attributes backtracks quadratically on multi-megabyte input
 * (CodeQL flags it). This scan is linear, tracks quoting so a `>` in an attribute can't end the
 * tag, skips comments/PIs/doctypes, and steps over a `<` that starts no valid name.
 *
 * Returns `{ name, attrs, closing, selfClosing, start, end }` — `end` is the index of the `>` — or
 * null at the end of the document.
 */
function nextTag(svg, pos) {
  const length = svg.length
  while (pos < length) {
    const lt = svg.indexOf('<', pos)
    if (lt < 0) return null
    if (svg.startsWith('<!--', lt)) {
      const close = svg.indexOf('-->', lt + 4)
      if (close < 0) return null
      pos = close + 3
      continue
    }
    if (svg.startsWith('<?', lt) || svg.startsWith('<!', lt)) {
      const close = svg.indexOf('>', lt + 2)
      if (close < 0) return null
      pos = close + 1
      continue
    }
    let i = lt + 1
    const closing = svg.charCodeAt(i) === 47 /* / */
    if (closing) i += 1
    const nameStart = i
    while (i < length && !isNameBreak(svg.charCodeAt(i))) i += 1
    if (i === nameStart) {
      // Not a tag at all — a stray `<` in text. Step past it and keep looking.
      pos = lt + 1
      continue
    }
    const name = svg.slice(nameStart, i)
    const attrStart = i
    let quote = 0
    while (i < length) {
      const c = svg.charCodeAt(i)
      if (quote) {
        if (c === quote) quote = 0
      } else if (c === 34 /* " */ || c === 39 /* ' */) {
        quote = c
      } else if (c === 62 /* > */) {
        break
      }
      i += 1
    }
    if (i >= length) return null // unterminated tag: nothing further is parseable
    const attrs = svg.slice(attrStart, i)
    let last = i - 1
    while (last >= attrStart && isSpace(svg.charCodeAt(last))) last -= 1
    return {
      name,
      attrs,
      closing,
      selfClosing: svg.charCodeAt(last) === 47 /* / */,
      start: lt,
      end: i,
    }
  }
  return null
}

/** Whitespace, `/` or `>` — anything that ends an element name. */
function isNameBreak(code) {
  return isSpace(code) || code === 47 || code === 62
}

function isSpace(code) {
  return code === 32 || code === 9 || code === 10 || code === 13
}

/**
 * Read `design-map.json` into `previewId → { ref, fileKey, nodeId, code }`.
 *
 * `ref` and `previewId` are either strings (one reference) or arrays of `{ ref | previewId, state
 * }` once a kit resolver has expanded variants. They are joined by `state`, never by array
 * position, since a resolver may resolve only some variants. Non-Figma refs are skipped.
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
 * The SVG files a page-cache manifest (`design/pages/pages.json`) points at, resolved against its
 * directory. Only v2 manifests and SVG pages (rasters have no addressable nodes).
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
 * Returns null when the export carries no such node — ordinary for pages the cache excludes.
 */
export function sliceNode(svg, nodeId, defs = collectDefs(svg)) {
  const needle = `data-node-id="${nodeId}"`
  // Cheap reject first: these documents run to megabytes and most pages hold no given node.
  if (!svg.includes(needle)) return null

  const stack = []
  let pos = 0
  let tag
  while ((tag = nextTag(svg, pos))) {
    pos = tag.end + 1
    if (tag.closing) {
      stack.pop()
      continue
    }
    const hit = tag.attrs.includes(needle)
    if (tag.selfClosing) {
      if (hit) return wrap(stack, svg.slice(tag.start, tag.end + 1), defs)
      continue
    }
    if (hit) {
      const end = closeOf(svg, tag.end + 1)
      if (end < 0) return null
      return wrap(stack, svg.slice(tag.start, end), defs)
    }
    stack.push({ name: tag.name, text: svg.slice(tag.start, tag.end + 1) })
  }
  return null
}

/**
 * Index just past the tag that closes the element whose open tag ends just before `from`. Depth is
 * counted over every element, not same-named ones, so a malformed-but-balanced subtree can't
 * mislead it.
 */
function closeOf(svg, from) {
  let depth = 1
  let pos = from
  let tag
  while ((tag = nextTag(svg, pos))) {
    pos = tag.end + 1
    if (tag.selfClosing) continue
    if (tag.closing) {
      depth -= 1
      if (depth === 0) return tag.end + 1
    } else {
      depth += 1
    }
  }
  return -1
}

/** Every `<defs>` block in the document, verbatim; collected once per page, not per node. */
export function collectDefs(svg) {
  const blocks = []
  let pos = 0
  let tag
  while ((tag = nextTag(svg, pos))) {
    pos = tag.end + 1
    if (tag.closing || tag.selfClosing || tag.name !== 'defs') continue
    const end = closeOf(svg, tag.end + 1)
    if (end < 0) break
    blocks.push(svg.slice(tag.start, end))
    pos = end
  }
  return blocks.join('\n')
}

/**
 * Re-wrap a node's markup in its ancestors (Figma hangs transforms, clips, opacity and blend modes
 * on groups) plus the document's definitions, which come whole since a slice can't know which
 * `url(#…)` it reaches.
 */
function wrap(stack, inner, defs) {
  if (!stack.length || stack[0].name !== 'svg') return null
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
 * Load the rasteriser, or null when it isn't installed. `FIGMA_REFERENCE_RESVG` names a directory
 * to resolve it from, since ESM resolution ignores `NODE_PATH`.
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
 * Rasterise a slice, cropped to the ink it draws. `zoom` because kit nodes are small in page units
 * and a 1× render looks blurry beside a device-density capture.
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
 * The colour the page draws behind a node, sampled just outside its bounding box. A transparent
 * slice is near-invisible on some comment themes, and the sheet already painted the right backdrop.
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
    // Transparent page pixel: leave the thumbnail transparent rather than invent a backdrop.
    if (a !== 255) continue
    return `#${[r, g, b].map((c) => c.toString(16).padStart(2, '0')).join('')}`
  }
  return undefined
}

/**
 * Rasterise a whole page once, small, purely so [backgroundAt] has something to sample; full scale
 * would be ~80 MB for the same answer.
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
    // The achieved scale: resvg rounds to whole pixels.
    scale: rendered.width / view.width,
    minX: view.minX,
    minY: view.minY,
  }
}

/**
 * `viewBox="minX minY width height"` off the root element, or null. Read off the parsed root tag; a
 * whole-document regex would be quadratic when the attribute is absent.
 */
export function viewBoxOf(svg) {
  const root = nextTag(svg, 0)
  if (!root || root.name !== 'svg' || root.closing) return null
  const at = root.attrs.indexOf('viewBox="')
  if (at < 0) return null
  const from = at + 'viewBox="'.length
  const to = root.attrs.indexOf('"', from)
  if (to < 0) return null
  const [minX, minY, width, height] = root.attrs
    .slice(from, to)
    .trim()
    .split(/\s+/)
    .map(Number)
  if (!(width > 0) || !(height > 0)) return null
  return { minX, minY, width, height }
}

/** File-system-safe basename for a preview's reference image. */
export function referenceBasename(previewId) {
  return `${previewId.replace(/[^A-Za-z0-9._-]/g, '_')}.png`
}

/**
 * Stage a reference PNG for each of `previews` (`[{ previewId, module }]`, the changed set the
 * comment shows) that maps onto a node the page cache carries, and return the comment's manifest.
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
  // Page by page, so each multi-megabyte page is read and searched once.
  for (const page of pageFiles(pagesManifest, pagesDir)) {
    const outstanding = [...wanted].filter(([id]) => !entries[id])
    if (!outstanding.length) break
    let svg
    try {
      svg = fs.readFileSync(page.file, 'utf8')
    } catch {
      continue
    }
    // Per-page work, done only when a node on this page is wanted.
    const defs = collectDefs(svg)
    let backdrop
    for (const [previewId, target] of outstanding) {
      const doc = sliceNode(svg, target.nodeId, defs)
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
 * Exits 0 on every foreseeable miss, writing no manifest, which the comment step reads as "no Figma
 * column".
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
