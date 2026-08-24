import { chromium } from "playwright";
const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
for (const scheme of ["light", "dark"]) {
  const page = await browser.newPage({ colorScheme: scheme, viewport: { width: 780, height: 220 }, deviceScaleFactor: 2 });
  await page.goto(`file://${process.cwd()}/band.html`);
  await page.waitForFunction(() => !document.getElementById("cp-acceptance").hidden, null, { timeout: 15000 });
  await page.screenshot({ path: `band-${scheme}.png` });
  console.log(scheme, await page.evaluate(() => document.getElementById("cp-acceptance").innerText));
  await page.close();
}
await browser.close();
