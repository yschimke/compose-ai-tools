// Photograph the harness pages `build-band-harness.mjs` writes.
//
// Usage: node shoot-band.mjs <page.html> <out-prefix>   → <out-prefix>-light.png, -dark.png
//
// It waits for the band and then shoots **whether or not it appeared**. That is deliberate: a
// hidden band is one of the states worth photographing — it is what a comparison nobody could
// evaluate used to look like — and a shooter that only ever captured a visible band could not take
// the "before" half of that picture.
import { chromium } from "playwright";

const [PAGE, PREFIX] = process.argv.slice(2);
if (!PAGE || !PREFIX) throw new Error("usage: shoot-band.mjs <page.html> <out-prefix>");

const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
for (const scheme of ["light", "dark"]) {
  const page = await browser.newPage({
    colorScheme: scheme,
    viewport: { width: 780, height: 220 },
    deviceScaleFactor: 2,
  });
  await page.goto(`file://${PAGE}`);
  await page
    .waitForFunction(() => !document.getElementById("cp-acceptance").hidden, null, { timeout: 4000 })
    .catch(() => {});
  await page.screenshot({ path: `${PREFIX}-${scheme}.png` });
  console.log(
    scheme,
    JSON.stringify(
      await page.evaluate(() => {
        const band = document.getElementById("cp-acceptance");
        return band.hidden ? "(band hidden)" : band.innerText;
      }),
    ),
  );
  await page.close();
}
await browser.close();
