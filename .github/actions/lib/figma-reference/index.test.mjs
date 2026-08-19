/**
 * Unit tests for the Figma reference stager.
 *
 * The rasteriser is optional here on purpose: the Actions Script Tests job installs nothing, and
 * the parts that can silently produce a *wrong* image — which node gets cut out, which ancestors
 * come with it, which preview a variant ref pairs with — are all pure text. The two rasterising
 * tests self-skip when `@resvg/resvg-js` isn't resolvable.
 */

import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import {
  backgroundAt,
  collectDefs,
  figmaUrl,
  loadResvg,
  pageFiles,
  parseDesignMap,
  parseRef,
  rasterisePage,
  referenceBasename,
  renderPng,
  sliceNode,
  stage,
  viewBoxOf,
} from './index.mjs'

const PAGE = `<svg width="100" height="60" viewBox="0 0 100 60" xmlns="http://www.w3.org/2000/svg">
<g data-node-id="1:1" clip-path="url(#clip0)">
<rect width="100" height="60" fill="#101014"/>
<g data-node-id="1:2" transform="translate(10 10)">
<rect data-node-id="1:3" width="20" height="10" fill="#ffffff"/>
</g>
<g data-node-id="1:4"><circle cx="70" cy="30" r="8" fill="#ff0000"/></g>
</g>
<defs><clipPath id="clip0"><rect width="100" height="60"/></clipPath></defs>
</svg>`

test('parseRef reads a figma handle and refuses any other scheme', () => {
  assert.deepEqual(parseRef('figma:ABC/12:34'), { fileKey: 'ABC', nodeId: '12:34' })
  assert.equal(parseRef('sketch:ABC/12:34'), null)
  assert.equal(parseRef('figma:ABC'), null)
  assert.equal(parseRef(undefined), null)
})

test('parseDesignMap reads the base, one-reference shape', () => {
  const map = parseDesignMap({
    components: [{ code: 'a/B.kt#C', ref: 'figma:F/1:1', previewId: 'a.BKt.C' }],
  })
  assert.deepEqual(map.get('a.BKt.C'), {
    ref: 'figma:F/1:1',
    code: 'a/B.kt#C',
    fileKey: 'F',
    nodeId: '1:1',
  })
})

test('parseDesignMap pairs resolved variants by state, not by position', () => {
  // The resolver resolved `disabled` but not `tonal`, so the arrays are different lengths and
  // pairing by index would map the tonal render onto the disabled node.
  const map = parseDesignMap({
    components: [
      {
        code: 'a/B.kt#C',
        ref: [{ ref: 'figma:F/1:1' }, { ref: 'figma:F/1:2', state: 'disabled' }],
        previewId: [
          { previewId: 'a.BKt.C' },
          { previewId: 'a.BKt.C_VARIANT_disabled', state: 'disabled' },
          { previewId: 'a.BKt.C_VARIANT_tonal', state: 'tonal' },
        ],
      },
    ],
  })
  assert.equal(map.get('a.BKt.C').nodeId, '1:1')
  assert.equal(map.get('a.BKt.C_VARIANT_disabled').nodeId, '1:2')
  assert.equal(map.has('a.BKt.C_VARIANT_tonal'), false)
})

test('parseDesignMap tolerates a map with nothing usable in it', () => {
  assert.equal(parseDesignMap({}).size, 0)
  assert.equal(parseDesignMap({ components: [{ code: 'x' }] }).size, 0)
  assert.equal(parseDesignMap(null).size, 0)
})

test('pageFiles resolves SVG pages against the manifest directory, and refuses v1', () => {
  const manifest = {
    version: 2,
    pages: [
      { id: 'buttons', image: { uri: 'buttons.svg', format: 'svg' } },
      { id: 'screens', image: { uri: 'screens.png', format: 'png' } },
    ],
  }
  assert.deepEqual(pageFiles(manifest, 'design/pages'), [
    { id: 'buttons', file: path.join('design/pages', 'buttons.svg') },
  ])
  assert.deepEqual(pageFiles({ ...manifest, version: 1 }, 'design/pages'), [])
})

test('sliceNode carries the ancestors, so the node keeps its transform and clip', () => {
  const doc = sliceNode(PAGE, '1:3')
  assert.match(doc, /^<svg width="100" height="60" viewBox="0 0 100 60"/)
  assert.match(doc, /clip-path="url\(#clip0\)"/)
  assert.match(doc, /transform="translate\(10 10\)"/)
  assert.match(doc, /<clipPath id="clip0">/)
  assert.match(doc, /data-node-id="1:3"/)
  // The sibling circle is another node's drawing and must not be in this one's thumbnail.
  assert.equal(doc.includes('#ff0000'), false)
  assert.match(doc, /<\/g><\/g><\/svg>$/)
})

test('sliceNode takes a whole subtree, and returns null for a node the page lacks', () => {
  const doc = sliceNode(PAGE, '1:2')
  assert.match(doc, /data-node-id="1:3"/)
  assert.equal(sliceNode(PAGE, '9:9'), null)
})

test('sliceNode is not fooled by a node id that is a prefix of another', () => {
  const svg = `<svg viewBox="0 0 10 10" xmlns="http://www.w3.org/2000/svg">
<g data-node-id="1:10"><rect data-node-id="1:11" width="1" height="1" fill="#fff"/></g>
<g data-node-id="1:1"><rect data-node-id="1:2" width="2" height="2" fill="#f00"/></g>
</svg>`
  assert.match(sliceNode(svg, '1:1'), /data-node-id="1:2"/)
  assert.equal(sliceNode(svg, '1:1').includes('1:11'), false)
})

test('the scanner is not confused by markup that looks like a tag', () => {
  // Every one of these is a token the tag scanner has to step over rather than parse: a comment
  // holding a `<g>`, a processing instruction, an attribute value holding a `>`, and a stray `<`
  // in text. Getting any of them wrong takes the wrong subtree, silently.
  const svg = `<svg viewBox="0 0 10 10" xmlns="http://www.w3.org/2000/svg">
<?xml-stylesheet href="x.css"?>
<!-- <g data-node-id="1:1"><rect width="9" height="9"/></g> -->
<g data-node-id="1:1" aria-label="a > b">
<rect data-node-id="1:2" width="2" height="2" fill="#f00"/>
</g>
</svg>`
  const doc = sliceNode(svg, '1:1')
  assert.match(doc, /aria-label="a > b"/)
  assert.match(doc, /data-node-id="1:2"/)
  assert.equal(doc.includes('width="9"'), false, 'the commented-out copy must not be taken')
})

test('sliceNode closes the subtree at its own depth, not the first close tag', () => {
  const svg = `<svg viewBox="0 0 10 10" xmlns="http://www.w3.org/2000/svg">
<g data-node-id="1:1"><g><rect data-node-id="1:2" width="1" height="1" fill="#f00"/></g></g>
<g data-node-id="1:3"><rect width="3" height="3" fill="#0f0"/></g>
</svg>`
  const doc = sliceNode(svg, '1:1')
  assert.match(doc, /data-node-id="1:2"/)
  assert.equal(doc.includes('#0f0'), false, 'the following sibling is a different node')
})

test('collectDefs takes every definition block, once', () => {
  assert.match(collectDefs(PAGE), /<clipPath id="clip0">/)
  const twice = collectDefs(`<svg><defs><a/></defs><g/><defs><b/></defs></svg>`)
  assert.equal(twice, '<defs><a/></defs>\n<defs><b/></defs>')
  assert.equal(collectDefs('<svg><g/></svg>'), '')
})

test('viewBoxOf reads the page space the slice is drawn in', () => {
  assert.deepEqual(viewBoxOf(PAGE), { minX: 0, minY: 0, width: 100, height: 60 })
  assert.equal(viewBoxOf('<svg width="10"></svg>'), null)
})

test('backgroundAt samples beside the node, and declines an unpainted pixel', () => {
  // A 4×2 page: left half opaque green, right half transparent.
  const pixels = Buffer.from([
    0, 255, 0, 255, 0, 255, 0, 255, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 255, 0, 255, 0, 255, 0, 255, 0, 0, 0, 0, 0, 0, 0, 0,
  ])
  const page = { pixels, width: 4, height: 2, scale: 1, minX: 0, minY: 0 }
  assert.equal(backgroundAt(page, { x: 2, y: 0, width: 1, height: 1 }, 1), '#00ff00')
  // Every candidate lands on a transparent pixel — no backdrop rather than an invented one.
  assert.equal(backgroundAt({ ...page, pixels: Buffer.alloc(32) }, { x: 2, y: 0, width: 1, height: 1 }, 1), undefined)
})

test('referenceBasename keeps a preview id addressable as a file', () => {
  assert.equal(referenceBasename('a.BKt.C_VARIANT_filled-variant'), 'a.BKt.C_VARIANT_filled-variant.png')
  assert.equal(referenceBasename('a/b c'), 'a_b_c.png')
})

test('figmaUrl deep-links the node in the design tool', () => {
  assert.equal(figmaUrl('F1', '35239:93128'), 'https://www.figma.com/design/F1/?node-id=35239-93128')
})

test('stage skips cleanly when nothing in the changed set is mapped', async () => {
  const result = await stage({
    designMap: new Map(),
    pagesManifest: { version: 2, pages: [] },
    pagesDir: '.',
    previews: [{ previewId: 'a.BKt.C', module: 'catalog' }],
    outputDir: 'unused',
  })
  assert.deepEqual(result.entries, {})
  assert.ok(result.skipped)
})

test('renderPng crops to the ink and paints the sampled backdrop', async (t) => {
  const Resvg = await loadResvg()
  if (!Resvg) return t.skip('@resvg/resvg-js is not installed')
  const png = renderPng(Resvg, sliceNode(PAGE, '1:3'), { zoom: 2 })
  assert.ok(png.length > 0)
  // 20×10 user units at 2× — the crop is the node's own ink, not the 100×60 page.
  const image = new Resvg(sliceNode(PAGE, '1:3'), { fitTo: { mode: 'zoom', value: 2 } })
  image.cropByBBox(image.getBBox())
  const rendered = image.render()
  assert.equal(rendered.width, 40)
  assert.equal(rendered.height, 20)
})

test('stage writes one PNG per mapped preview, keyed by preview id', async (t) => {
  const Resvg = await loadResvg()
  if (!Resvg) return t.skip('@resvg/resvg-js is not installed')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'figma-ref-'))
  fs.writeFileSync(path.join(dir, 'page.svg'), PAGE)
  const result = await stage({
    designMap: parseDesignMap({
      components: [{ code: 'a/B.kt#C', ref: 'figma:F/1:3', previewId: 'a.BKt.C' }],
    }),
    pagesManifest: { version: 2, pages: [{ id: 'page', image: { uri: 'page.svg', format: 'svg' } }] },
    pagesDir: dir,
    previews: [
      { previewId: 'a.BKt.C', module: 'catalog' },
      { previewId: 'a.BKt.Unmapped', module: 'catalog' },
    ],
    outputDir: path.join(dir, 'out'),
  })
  assert.deepEqual(Object.keys(result.entries), ['a.BKt.C'])
  const entry = result.entries['a.BKt.C']
  assert.equal(entry.page, 'page')
  assert.equal(entry.nodeId, '1:3')
  assert.ok(fs.statSync(path.join(dir, 'out', 'catalog', entry.basename)).size > 0)
  assert.ok(rasterisePage(Resvg, PAGE).width > 0)
})
