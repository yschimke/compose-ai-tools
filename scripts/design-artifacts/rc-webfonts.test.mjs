/**
 * rc-webfonts.test.mjs — the player's *named* font family support.
 *
 * Run with `node --test scripts/design-artifacts/`.
 *
 * These exercise the built bundle (`cli/src/main/resources/rc-player/bundle.js`) in a real browser
 * rather than the TypeScript source, because the bundle is the artifact that actually ships and the
 * behaviour under test is browser machinery — `@font-face` laziness, CSS font matching, the
 * `FontFace` registry — that has no meaningful pure-JS stand-in.
 *
 * Hermetic on purpose: the registration test serves its own stylesheet and a *vendored* TTF from
 * localhost via the player's `baseUrl` knob, so it never touches fonts.googleapis.com. The request
 * form aimed at the real API is pinned separately, as a pure string assertion — that is the part
 * that has to stay exactly right (see the comment on the weight matrix below) and the part a network
 * test would make flaky rather than more convincing.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { DEFAULT_FONTS_DIR } from "./rc-fonts.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BUNDLE = path.resolve(HERE, "../../cli/src/main/resources/rc-player/bundle.js");

// A vendored face with unmistakably non-Roboto metrics, so "did the named family take effect?" is a
// measurable question rather than a subjective one.
const FIXTURE_FAMILY = "Orbitron";
const FIXTURE_FILE = "orbitron-400.ttf";

let chromium;
let browser;
let server;
let origin;
let skip = false;
/** Font-file routes the page actually requested, so over-fetching is a testable claim. */
const fetched = [];
/** Every css2 query string requested, so the *shape* of the request is testable too. */
const stylesheetQueries = [];

before(async () => {
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    skip = "playwright is not installed";
    return;
  }
  const fontPath = path.join(DEFAULT_FONTS_DIR, FIXTURE_FILE);
  if (!fs.existsSync(fontPath)) {
    skip = `${FIXTURE_FILE} is not vendored`;
    return;
  }
  const font = fs.readFileSync(fontPath);

  // Stands in for the Google Fonts CSS API: same contract (a stylesheet of @font-face rules for the
  // requested family), no network. Two weights are declared off the same file so that "which faces
  // were actually fetched" is observable — that is what distinguishes loading the requested variant
  // from loading everything the family publishes.
  server = http.createServer((req, res) => {
    const route = req.url.split("?")[0];
    if (route === "/") {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("<!doctype html><html><head></head><body></body></html>");
    } else if (route === "/css2") {
      const query = req.url.slice(route.length);
      stylesheetQueries.push(query);
      res.writeHead(200, { "content-type": "text/css", "access-control-allow-origin": "*" });
      // The real API answers the two request forms with two different *kinds* of face, and the
      // difference is the whole reason the axis request exists: an enumerated weight list gets one
      // pinned instance per weight, a range gets a single face declaring that range. The fake has to
      // model that, or a bug about "we already have a variable face" cannot be written down here.
      const ranged = /:[a-zA-Z,]+@[\d.]+\.\./.test(query);
      res.end(
        ranged
          ? `@font-face{font-family:'${FIXTURE_FAMILY}';font-style:normal;font-weight:100 1000;` +
              `font-stretch:25% 151%;font-display:block;` +
              `src:url(${origin}/font-variable.ttf) format('truetype');}`
          : [400, 700]
              .map(
                (w) =>
                  `@font-face{font-family:'${FIXTURE_FAMILY}';font-style:normal;font-weight:${w};` +
                  `font-display:block;src:url(${origin}/font-${w}.ttf) format('truetype');}`,
              )
              .join(""),
      );
    } else if (route === "/font-400.ttf" || route === "/font-700.ttf" || route === "/font-variable.ttf") {
      fetched.push(route);
      res.writeHead(200, { "content-type": "font/ttf", "access-control-allow-origin": "*" });
      res.end(font);
    } else {
      // Everything else 404s — which is how the "family Google does not serve" case is simulated.
      res.writeHead(404).end();
    }
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  origin = `http://127.0.0.1:${server.address().port}`;

  try {
    browser = await chromium.launch({
      headless: true,
      ...(process.env.RC_COMPARE_CHROMIUM ? { executablePath: process.env.RC_COMPARE_CHROMIUM } : {}),
      args: ["--no-sandbox"],
    });
  } catch (e) {
    skip = `chromium unavailable: ${String(e).split("\n")[0]}`;
  }
});

after(async () => {
  await browser?.close();
  server?.close();
});

/** A page with the built player loaded, served over http so stylesheet loads behave normally. */
async function playerPage() {
  const page = await browser.newContext({ deviceScaleFactor: 1 }).then((c) => c.newPage());
  await page.goto(`${origin}/`);
  await page.addScriptTag({ content: fs.readFileSync(BUNDLE, "utf8") });
  return page;
}

test("the css2 request enumerates weights rather than ranging them", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const url = await page.evaluate(() => RC.googleFontsUrl("Orbitron"));

  // A *range* (`wght@100..900`) is rejected with HTTP 400 by every family that isn't variable —
  // Lobster and Pacifico both 400 on it — and at the <link> a 400 is indistinguishable from a
  // network failure, so the family would be reported as unavailable and silently fall back. The
  // enumerated form is accepted for variable and static families alike, and Google returns only the
  // faces the family really ships, so over-asking costs nothing.
  assert.ok(!url.includes(".."), `weight range would 400 for static families: ${url}`);
  assert.match(url, /ital,wght@0,100;/);
  assert.match(url, /1,900(&|$)/);
  assert.ok(url.startsWith("https://fonts.googleapis.com/css2?family=Orbitron:"));
  // `display=block` rather than the default swap: the swap period is precisely the window a
  // single-shot renderer would screenshot in, and it would capture the fallback face.
  assert.match(url, /[?&]display=block/);
});

test("a multi-word family is spelled the way the API caches it", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const url = await page.evaluate(() => RC.googleFontsUrl("Space Grotesk"));
  // `+`, not `%20`: both resolve, but `+` is Google's canonical spelling and what the caches in
  // front of the API are keyed on.
  assert.ok(url.includes("family=Space+Grotesk:"), url);
  assert.ok(!url.includes("%20"), url);
});

test("baseUrl redirects the request, so an offline/CSP embedder can serve its own", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const url = await page.evaluate((base) => {
    RC.configureWebFonts({ baseUrl: base });
    return RC.googleFontsUrl("Orbitron");
  }, `${origin}/css2`);
  assert.ok(url.startsWith(`${origin}/css2?family=Orbitron:`), url);
});

test("the google: namespace decides what gets fetched, and never leaks into the name", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const r = await page.evaluate(() => ({
    prefixed: RC.parseFamily("google:Space Grotesk"),
    // Case-insensitive: the prefix is a wire convention, not a display string, and a document
    // written `Google:` means the same thing.
    upper: RC.parseFamily("Google:Orbitron"),
    // Unprefixed is deliberately NOT treated as a Google Font. Guessing would turn a typo, or a
    // host-only name like "SF Pro", into a network request and leave no way to say "this is local".
    bare: RC.parseFamily("SF Pro"),
    constant: RC.GOOGLE_PREFIX,
  }));
  assert.deepEqual(r.prefixed, { source: "google", name: "Space Grotesk" });
  assert.deepEqual(r.upper, { source: "google", name: "Orbitron" });
  assert.deepEqual(r.bare, { source: "local", name: "SF Pro" });
  assert.equal(r.constant, "google:");
  // The bare name is what reaches the CSS stack and the API, so a leaked prefix would request the
  // nonexistent family "google:Orbitron" and silently fall back.
  const url = await page.evaluate(() => RC.googleFontsUrl(RC.parseFamily("google:Orbitron").name));
  assert.ok(url.includes("family=Orbitron:"), url);
  assert.ok(!url.includes("google%3A") && !url.includes("google:Orbitron"), url);
});

test("a named family registers and canvas then paints in it", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const r = await page.evaluate(
    async ({ base, family }) => {
      RC.configureWebFonts({ baseUrl: base });
      const measure = (font) => {
        const c = document.createElement("canvas").getContext("2d");
        c.font = font;
        return c.measureText("Orbitron 0123 HAMBURGEFONS").width;
      };
      const stack = `"${family}", Roboto, sans-serif`;
      const before = measure(`400 32px ${stack}`);
      await RC.ensureWebFont(family);
      await RC.webFontsReady();
      const faces = [];
      document.fonts.forEach((f) => {
        if (f.family.replace(/^["']|["']$/g, "") === family) faces.push(`${f.weight}:${f.status}`);
      });
      return { before, after: measure(`400 32px ${stack}`), faces: faces.sort() };
    },
    { base: `${origin}/css2`, family: FIXTURE_FAMILY },
  );

  // Both weights are *declared* — declaring costs nothing — but only the one asked for is loaded.
  // "unloaded" for 700 is the assertion that the fetch stayed narrow; "loaded" for 400 is the
  // assertion that a declared face was actually driven to load, which canvas never does by itself.
  assert.deepEqual(r.faces, ["400:loaded", "700:unloaded"]);
  // The real assertion. `document.fonts.check()` is NOT usable here — it answers true for a family
  // that was never declared at all (the system is assumed able to supply it), so it cannot tell
  // "registered" from "fell back". Measuring the same string through the same stack can: before
  // registration it is Roboto's width, after it is Orbitron's.
  assert.notEqual(r.after, r.before, "text width should change once the real face is registered");
});

test("a family name is escaped for the font shorthand, backslashes included", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const r = await page.evaluate(() => ({
    quote: RC.cssQuoted('My "Font"'),
    backslash: RC.cssQuoted("My Font\\"),
    stack: RC.namedFontStack("My Font\\"),
  }));
  assert.equal(r.quote, 'My \\"Font\\"');
  // The one that matters: escaping only the quote would emit `"My Font\"`, where the backslash
  // escapes the *closing* quote — the string runs on, swallows the rest of the stack, and the whole
  // `ctx.font` assignment is silently voided, which is precisely what the quoting exists to prevent.
  assert.equal(r.backslash, "My Font\\\\");
  assert.equal(r.stack, '"My Font\\\\", Roboto, sans-serif');

  // And the escaped stack must still be a value the canvas shorthand accepts.
  const applied = await page.evaluate(() => {
    const c = document.createElement("canvas").getContext("2d");
    c.font = `400 32px ${RC.namedFontStack("My Font\\")}`;
    return c.font;
  });
  assert.ok(applied.includes("32px"), `shorthand was voided: ${applied}`);
});

test("only the requested weight is fetched, not every weight the family publishes", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  fetched.length = 0;
  await page.evaluate(
    async (base) => {
      RC.resetWebFonts();
      RC.configureWebFonts({ baseUrl: base });
      await RC.ensureWebFont("Orbitron", 400, false);
      await RC.webFontsReady();
    },
    `${origin}/css2`,
  );
  // The stylesheet declares 400 and 700; drawing a regular label must not drag the bold down with
  // it. Declaring a face is free — fetching one is not, and it also holds fontsReady() open, which
  // is the wait a single-shot renderer is blocked on.
  assert.deepEqual(fetched, ["/font-400.ttf"]);
});

test("every player waiting on an in-flight face is repainted, and settled faces stop notifying", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const r = await page.evaluate(
    async (base) => {
      RC.resetWebFonts();
      RC.configureWebFonts({ baseUrl: base });
      let a = 0, b = 0, late = 0;
      // Two players asking for the same family before the first fetch settles. Dropping the second
      // caller's callback would leave that canvas in the fallback forever: a static document has no
      // later frame to recover on.
      const p1 = RC.ensureWebFont("Orbitron", 400, false, () => a++);
      const p2 = RC.ensureWebFont("Orbitron", 400, false, () => b++);
      await Promise.all([p1, p2]);
      await RC.webFontsReady();

      // Resolution runs per paint, so this is what a later frame does. It must NOT fire: notifying
      // a settled variant would schedule a repaint from inside painting, which paints, which
      // notifies again — an endless repaint loop.
      await RC.ensureWebFont("Orbitron", 400, false, () => late++);
      await RC.webFontsReady();
      return { a, b, late, sameShared: p1 === p2 };
    },
    `${origin}/css2`,
  );
  assert.equal(r.a, 1, "first waiter should be notified exactly once");
  assert.equal(r.b, 1, "second waiter joining the in-flight fetch must also be notified");
  assert.equal(r.late, 0, "a settled variant must not re-notify");
  assert.equal(r.sameShared, true, "both callers should join one fetch, not start two");
});

test("a family that cannot be served degrades to the fallback instead of throwing", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const r = await page.evaluate(
    async (base) => {
      // /nope 404s, standing in for a family Google does not serve.
      RC.configureWebFonts({ baseUrl: `${base}/nope` });
      const measure = (font) => {
        const c = document.createElement("canvas").getContext("2d");
        c.font = font;
        return c.measureText("Orbitron 0123").width;
      };
      let threw = false;
      try {
        await RC.ensureWebFont("Definitely Not A Real Family");
        await RC.webFontsReady();
      } catch {
        threw = true;
      }
      return {
        threw,
        named: measure('400 32px "Definitely Not A Real Family", Roboto, sans-serif'),
        fallback: measure("400 32px Roboto, sans-serif"),
      };
    },
    origin,
  );

  // A document naming a font we cannot fetch is an authoring fact, not a player fault: it must
  // paint the fallback rather than break the render or reject the readiness gate the harness awaits.
  assert.equal(r.threw, false, "ensureWebFont/webFontsReady must not reject");
  assert.equal(r.named, r.fallback, "an unavailable family should measure as the fallback");
});

test("an axis request asks for a range, which is what makes the face variable", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const r = await page.evaluate(() => ({
    ramp: RC.googleFontsAxisUrl("Roboto Flex", [
      { tag: "wght", min: 100, max: 1000 },
      { tag: "wdth", min: 25, max: 151 },
    ]),
    single: RC.googleFontsAxisUrl("Roboto Flex", [{ tag: "wdth", min: 100, max: 100 }]),
    none: RC.googleFontsAxisUrl("Roboto Flex", []),
  }));

  // This is the whole point of the axis request. Asked for an enumerated weight list, css2 answers
  // with a *pinned static instance per weight* (`font-weight: 100; font-stretch: 100%`); asked for
  // ranges with the axes named, it answers with a variable face (`font-weight: 100 1000;
  // font-stretch: 25% 151%`). Only the second can be varied, so a document carrying axes that asked
  // the enumerated way would draw its `wdth` ramp as identical lines however correctly the canvas
  // sets the axis.
  assert.ok(
    r.ramp.includes("family=Roboto+Flex:wdth,wght@25..151,100..1000"),
    `axes must be alphabetical with values in the same order: ${r.ramp}`,
  );
  assert.match(r.ramp, /[?&]display=block/);
  // A span of one value is null rather than `wdth@100..100`: css2 rejects a degenerate range with a
  // 400, and a single value needs no variable face — the enumerated request already covers it.
  assert.equal(r.single, null);
  assert.equal(r.none, null);
});

test("a document's axis values accumulate into one range across paints", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  stylesheetQueries.length = 0;
  await page.evaluate(
    async ({ base, family }) => {
      RC.resetWebFonts();
      RC.configureWebFonts({ baseUrl: base });
      // Three lines of a `wdth` specimen are three separate text ops, each carrying one value.
      for (const value of [25, 100, 151]) {
        await RC.ensureWebFont(family, 400, false, undefined, [{ tag: "wdth", value }]);
      }
      await RC.webFontsReady();
    },
    { base: `${origin}/css2`, family: FIXTURE_FAMILY },
  );

  const axisQueries = stylesheetQueries.filter((q) => q.includes("@"));
  // The first value alone is a degenerate span with no variable face to ask for; only once a second
  // value arrives is there a range. The widest span seen is what the last request carries — that is
  // the face the frame a single-shot renderer keeps is painted with, since it repaints after
  // `webFontsReady()`.
  assert.ok(
    axisQueries.some((q) => q.includes("wdth@25..151")),
    `expected the accumulated span to be requested, saw: ${JSON.stringify(axisQueries)}`,
  );
  // The enumerated stylesheet is still registered — it is what serves a family with no variable
  // face at all, and the fallback when the range is refused.
  assert.ok(
    stylesheetQueries.some((q) => q.includes("ital,wght@")),
    `expected the enumerated request as well, saw: ${JSON.stringify(stylesheetQueries)}`,
  );
});

test("axes that arrive after the family still reach the request", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  stylesheetQueries.length = 0;
  await page.evaluate(
    async ({ base, family }) => {
      RC.resetWebFonts();
      RC.configureWebFonts({ baseUrl: base });
      // The order a paint bundle actually serialises in: `setTextStyle` (which resolves the family,
      // and is where the request is made) comes *before* `setTextAxis`. So the first ask for a line
      // necessarily carries no axes, and the axes are known only afterwards.
      for (const value of [25, 151]) {
        await RC.ensureWebFont(family, 400, false, undefined, []);
        await RC.ensureWebFont(family, 400, false, undefined, [{ tag: "wdth", value }]);
      }
      await RC.webFontsReady();
    },
    { base: `${origin}/css2`, family: FIXTURE_FAMILY },
  );

  // Without a request made *after* the axes are decoded, this span is never asked for and the page
  // keeps the enumerated static stylesheet — which is a face with nothing to vary, so the ramp draws
  // as identical lines. It looks correct on a page that vendored the variable face itself, which is
  // exactly how it would hide.
  assert.ok(
    stylesheetQueries.some((q) => q.includes("wdth@25..151")),
    `expected the span requested after the axes arrived, saw: ${JSON.stringify(stylesheetQueries)}`,
  );
});

test("a second document's axis is requested even though the family is already variable", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  stylesheetQueries.length = 0;
  await page.evaluate(
    async ({ base, family }) => {
      RC.resetWebFonts();
      RC.configureWebFonts({ baseUrl: base });
      // One page, two documents — which is how the `rc-compare` harness renders a whole catalog.
      // The first varies `wght`; by the end of it the page carries a variable face for the family.
      for (const value of [100, 1000]) {
        await RC.ensureWebFont(family, 400, false, undefined, [{ tag: "wght", value }]);
      }
      await RC.webFontsReady();
      // The second varies `wdth` — an axis the face declared above pins at one value.
      for (const value of [25, 151]) {
        await RC.ensureWebFont(family, 400, false, undefined, [{ tag: "wdth", value }]);
      }
      await RC.webFontsReady();
    },
    { base: `${origin}/css2`, family: FIXTURE_FAMILY },
  );

  // The bug this pins: "the page already carries a variable face" was read as "the axes can be
  // applied", so the second document's request was skipped and its `wdth` ramp drew three identical
  // lines. Document *order* decided it — alone, the width specimen was fine — which is why it
  // survived a per-document test and showed up only in the catalog run (compose-ai-tools#4177).
  // Both axes ride in one request because the recorded spans accumulate per family.
  assert.ok(
    stylesheetQueries.some((q) => q.includes("wdth,wght@25..151,100..1000")),
    `expected the second axis to be requested, saw: ${JSON.stringify(stylesheetQueries)}`,
  );
});

test("embedded variable fonts advertise their fvar weight and width ranges", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const descriptors = await page.evaluate(() => {
    const bytes = new Uint8Array(12 + 16 + 16 + 40);
    const view = new DataView(bytes.buffer);
    const tag = (offset, value) => {
      for (let i = 0; i < 4; i++) bytes[offset + i] = value.charCodeAt(i);
    };
    const fixed = (offset, value) => view.setInt32(offset, value * 65536, false);
    view.setUint16(4, 1, false);
    tag(12, "fvar");
    view.setUint32(20, 28, false);
    view.setUint16(32, 16, false); // axesArrayOffset
    view.setUint16(36, 2, false); // axisCount
    view.setUint16(38, 20, false); // axisSize
    tag(44, "wght");
    fixed(48, 100); fixed(52, 400); fixed(56, 900);
    tag(64, "wdth");
    fixed(68, 75); fixed(72, 100); fixed(76, 125);
    return RC.embeddedFontDescriptors(bytes);
  });

  assert.equal(descriptors.weight, "100 900");
  assert.equal(descriptors.stretch, "75% 125%");
});

test("embedded faces are reference counted and removed after the last owner", async (t) => {
  if (skip) return t.skip(skip);
  const page = await playerPage();
  const result = await page.evaluate(async (base) => {
    RC.resetWebFonts();
    const bytes = new Uint8Array(await (await fetch(`${base}/font-400.ttf`)).arrayBuffer());
    const family = RC.registerEmbeddedFont(42, bytes);
    RC.registerEmbeddedFont(42, bytes);
    await RC.webFontsReady();
    const count = () => {
      let total = 0;
      document.fonts.forEach((face) => {
        if (face.family.replace(/^["']|["']$/g, "") === family) total++;
      });
      return total;
    };
    const before = count();
    RC.releaseEmbeddedFont(42, bytes);
    const afterOne = count();
    RC.releaseEmbeddedFont(42, bytes);
    const afterLast = count();
    return { before, afterOne, afterLast };
  }, origin);

  assert.equal(result.before, 1);
  assert.equal(result.afterOne, 1);
  assert.equal(result.afterLast, 0);
});
