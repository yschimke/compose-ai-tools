// Capture the live stage at three points in one stream, and lay them out as one contact sheet.
import { chromium } from 'playwright'
import fs from 'node:fs'

const PORT = Number(process.env.PORT ?? 8099)
const OUT = process.env.OUT ?? 'strip.png'
const LABEL = process.env.LABEL ?? ''
const AT = [2000, 6000, 11000]
const URL_ = `http://localhost:${PORT}/wear-m3-catalog/p/circularprogressindicator__ideal__indeterminate__192dp?mode=live`

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' })
const page = await browser.newPage({ viewport: { width: 900, height: 700 }, deviceScaleFactor: 2 })
await page.goto(URL_, { waitUntil: 'domcontentloaded' })

const shots = []
const t0 = Date.now()
for (const at of AT) {
    await page.waitForTimeout(Math.max(0, at - (Date.now() - t0)))
    const canvas = await page.$('canvas')
    shots.push((await canvas.screenshot()).toString('base64'))
}
await page.close()

const sheet = await browser.newPage({ viewport: { width: 900, height: 360 }, deviceScaleFactor: 2 })
await sheet.setContent(`<body style="margin:0;background:#0f0f11;font:14px system-ui,sans-serif;color:#e8e6ef">
<div style="padding:16px 18px 8px;font-weight:600">${LABEL}</div>
<div style="display:flex;gap:18px;padding:0 18px 18px">
${shots.map((b, i) => `<figure style="margin:0;text-align:center">
  <img src="data:image/png;base64,${b}" style="width:230px;height:230px;border-radius:12px;background:#000">
  <figcaption style="padding-top:8px;opacity:.75">t = ${AT[i] / 1000}s</figcaption>
</figure>`).join('')}
</div></body>`)
fs.writeFileSync(OUT, await sheet.screenshot({ fullPage: true }))
await browser.close()
console.log('wrote', OUT)
