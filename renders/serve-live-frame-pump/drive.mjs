// Drive the real viewer page in Chromium and watch whether the live canvas keeps repainting.
import { chromium } from 'playwright'

const PORT = Number(process.env.PORT ?? 8099)
const SECONDS = Number(process.env.SECONDS ?? 12)
const URL_ = `http://localhost:${PORT}/wear-m3-catalog/p/circularprogressindicator__ideal__indeterminate__192dp?mode=live`

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' })
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
page.on('console', (m) => {
    const t = m.text()
    if (m.type() === 'error' || /error|Error|fail/.test(t)) console.log('[console]', m.type(), t.slice(0, 200))
})
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 300)))

await page.goto(URL_, { waitUntil: 'domcontentloaded' })

// Sample the live canvas's top-left pixel; a new colour means a new frame actually painted.
const samples = []
const t0 = Date.now()
while (Date.now() - t0 < SECONDS * 1000) {
    const s = await page.evaluate(() => {
        const c = document.querySelector('canvas')
        if (!c) return { err: 'no canvas' }
        const ctx = c.getContext('2d')
        if (!ctx || !c.width) return { px: null, w: c.width, h: c.height }
        const d = ctx.getImageData(0, 0, 1, 1).data
        return { px: `${d[0]},${d[1]},${d[2]}`, w: c.width, h: c.height }
    })
    samples.push({ t: Date.now() - t0, ...s })
    await page.waitForTimeout(250)
}
await browser.close()

let last = null
const painted = []
for (const s of samples) {
    if (s.px && s.px !== last) { painted.push(s); last = s.px }
}
console.log(`\nsamples=${samples.length} distinct-paints=${painted.length}`)
for (const p of painted) console.log(`  paint @${String(p.t).padStart(5)}ms  px=${p.px}  buffer=${p.w}x${p.h}`)
const lastPaint = painted.at(-1)
const idleTail = lastPaint ? SECONDS * 1000 - lastPaint.t : null
console.log(`last paint at ${lastPaint ? lastPaint.t : 'never'}ms; ${idleTail}ms of stream after it with no repaint`)
console.log(idleTail !== null && idleTail > 3000 ? 'VERDICT: FROZEN' : 'VERDICT: still animating')
