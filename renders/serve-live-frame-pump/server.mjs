// Fake `compose-preview serve` front end: the REAL server-rendered viewer page + the REAL
// viewer.js bundle, with a stub WebSocket lane that pushes frames on the live wire shape.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { WebSocketServer } from 'ws'

const DIR = path.dirname(new URL(import.meta.url).pathname)
const PORT = Number(process.env.PORT ?? 8099)
const SCENARIO = process.env.SCENARIO ?? 'good'
const VIEWER_OVERRIDE = process.env.VIEWER_JS || null // local build to swap in
const page = fs.readFileSync(path.join(DIR, 'page.html'), 'utf8')
const basePng = fs.readFileSync(path.join(DIR, 'base.png'))

// --- minimal PNG encoder (solid RGBA), so each frame is genuinely different pixels.
function crc32(buf) {
    let c, crc = 0xffffffff
    for (let n = 0; n < buf.length; n++) {
        c = (crc ^ buf[n]) & 0xff
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
        crc = (crc >>> 8) ^ c
    }
    return (crc ^ 0xffffffff) >>> 0
}
function chunk(type, data) {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
    return Buffer.concat([len, td, crc])
}
// A wear-style indeterminate ring at phase `t` (0..1) — the shape the real preview draws, so the
// captured evidence reads as the reported bug rather than as coloured squares.
function ringPng(size, t) {
    const cx = size / 2, cy = size / 2
    const rOut = size / 2 - 6, rIn = rOut - 10
    const sweep = Math.PI * 2 * (0.15 + 0.65 * Math.abs(Math.sin(Math.PI * t)))
    const start = Math.PI * 2 * ((t * 2) % 1) - Math.PI / 2
    const ihdr = Buffer.alloc(13)
    ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4)
    ihdr[8] = 8; ihdr[9] = 6
    const raw = Buffer.alloc(size * (1 + size * 4))
    for (let y = 0; y < size; y++) {
        const off = y * (1 + size * 4)
        for (let x = 0; x < size; x++) {
            const p = off + 1 + x * 4
            const dx = x - cx, dy = y - cy
            const d = Math.hypot(dx, dy)
            let [r, g, b] = [18, 18, 20]
            if (d <= rOut && d >= rIn) {
                let a = Math.atan2(dy, dx) - start
                while (a < 0) a += Math.PI * 2
                while (a > Math.PI * 2) a -= Math.PI * 2
                ;[r, g, b] = a <= sweep ? [222, 216, 255] : [58, 56, 66]
            }
            raw[p] = r; raw[p + 1] = g; raw[p + 2] = b; raw[p + 3] = 255
        }
    }
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', zlib.deflateSync(raw)),
        chunk('IEND', Buffer.alloc(0)),
    ])
}
function solidPng(w, h, r, g, b) {
    const ihdr = Buffer.alloc(13)
    ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4)
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
    const raw = Buffer.alloc(h * (1 + w * 4))
    for (let y = 0; y < h; y++) {
        const off = y * (1 + w * 4)
        raw[off] = 0
        for (let x = 0; x < w; x++) {
            const p = off + 1 + x * 4
            raw[p] = r; raw[p + 1] = g; raw[p + 2] = b; raw[p + 3] = 255
        }
    }
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', zlib.deflateSync(raw)),
        chunk('IEND', Buffer.alloc(0)),
    ])
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost')
    const p = url.pathname
    if (p.startsWith('/assets/serve/')) {
        const name = '_' + p.slice(1).replaceAll('/', '_')
        const file =
            VIEWER_OVERRIDE && name.endsWith('viewer.js')
                ? VIEWER_OVERRIDE
                : path.join(DIR, 'assets', name)
        if (!fs.existsSync(file)) { res.writeHead(404); res.end('no asset'); return }
        res.writeHead(200, { 'content-type': name.endsWith('.css') ? 'text/css' : 'text/javascript' })
        res.end(fs.readFileSync(file))
        return
    }
    if (p.endsWith('.png')) { res.writeHead(200, { 'content-type': 'image/png' }); res.end(basePng); return }
    if (p.includes('/p/')) {
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
        res.end(page)
        return
    }
    res.writeHead(404); res.end('nope')
})

const wss = new WebSocketServer({ server })
wss.on('connection', (ws, req) => {
    console.log('[ws] connect', req.url)
    let seq = 0
    const timer = setInterval(() => {
        seq++
        // Each frame a different colour, so "did the canvas change?" is unambiguous.
        const png = process.env.SHAPE === 'ring'
            ? ringPng(192, ((seq * 250) % 5000) / 5000)
            : solidPng(64, 64, (seq * 23) % 256, (seq * 57) % 256, (seq * 91) % 256)
        let b64 = png.toString('base64')
        let codec = 'png'
        if (SCENARIO === 'badframe' && seq === 5) {
            // One malformed payload — the shape a truncated/garbled frame has on the wire.
            b64 = '!!!not-base64!!!'
        }
        ws.send(JSON.stringify({ type: 'frame', seq, codec, widthPx: process.env.SHAPE === 'ring' ? 192 : 64, heightPx: process.env.SHAPE === 'ring' ? 192 : 64, dataBase64: b64 }))
    }, 250)
    ws.on('message', (m) => console.log('[ws] client →', String(m).slice(0, 120)))
    ws.on('close', () => { clearInterval(timer); console.log('[ws] closed after', seq, 'frames') })
})

server.listen(PORT, () => console.log(`listening :${PORT} scenario=${SCENARIO} viewer=${VIEWER_OVERRIDE ?? 'deployed 1.22.0'}`))
