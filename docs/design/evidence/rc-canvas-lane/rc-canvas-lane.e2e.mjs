import { chromium } from 'playwright';

const PORT = process.argv[2];
const OUT = '/tmp/claude-0/-home-user/78cd5683-e280-5a5c-bf01-163ff10d0d49/scratchpad/rc-e2e';
const URL = `http://127.0.0.1:${PORT}/remotem3/p/CircularProgressRemote`;

const browser = await chromium.launch({
  headless: true,
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--enable-unsafe-swiftshader', '--no-sandbox'],
});
const ctx = await browser.newContext({ viewport: { width: 900, height: 700 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
page.on('console', (m) => console.log('CONSOLE:', m.text()));
page.on('pageerror', (e) => console.log('PAGEERROR:', e.message));

await page.goto(URL, { waitUntil: 'networkidle' });
// The default lane is the baked PNG snapshot.
await page.locator('.cp-stage').screenshot({ path: `${OUT}/01-snapshot.png` });

// Activate the in-browser Remote Compose canvas lane.
await page.locator('#cp-rc-btn').click();

// Wait for the canvas to be revealed (its `hidden` attribute cleared by revealRc()).
await page.waitForFunction(() => {
  const c = document.getElementById('cp-rc-canvas');
  return c && !c.hidden && c.width > 0;
}, { timeout: 15000 });
await new Promise((r) => setTimeout(r, 400));

const painted = await page.evaluate(() => {
  const c = document.getElementById('cp-rc-canvas');
  const t = document.createElement('canvas');
  t.width = c.width; t.height = c.height;
  const g = t.getContext('2d');
  g.drawImage(c, 0, 0);
  const d = g.getImageData(0, 0, t.width, t.height).data;
  let nonBlank = 0;
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] > 10 && !(d[i] > 245 && d[i + 1] > 245 && d[i + 2] > 245)) nonBlank++;
  }
  return { w: c.width, h: c.height, nonBlank, mode: document.querySelector('.cp-viewer')?.getAttribute('data-mode') };
});
console.log('CANVAS', JSON.stringify(painted));
await page.locator('#cp-rc-canvas').screenshot({ path: `${OUT}/02-canvas.png` });

await browser.close();
if (painted.nonBlank < 200) { console.log('FAIL: canvas appears blank'); process.exit(1); }
console.log('OK: client-side canvas rendered the .rc document');
