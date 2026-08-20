// Screenshot the design-page view of a running `compose-preview serve` in the
// "Design spec" lane with the outline layer on — the state the legend and the
// node colours exist for, and the reading the coverage question is asked in.
import { chromium } from "playwright";

const [, , url, out] = process.argv;

const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
const page = await browser.newPage({ viewport: { width: 1180, height: 760 }, deviceScaleFactor: 2 });
await page.goto(url, { waitUntil: "networkidle" });
const lane = page.locator('[data-cp-page-lane][value="design"]');
if (await lane.count()) await lane.first().check({ force: true });
const outlines = page.locator("[data-cp-page-outlines]");
if (await outlines.count()) await outlines.first().check({ force: true });
await page.waitForTimeout(700);
const shot = page.locator("#cp-design-page");
await (await shot.count() ? shot.first() : page).screenshot({ path: out });
await browser.close();
console.log("wrote", out);
