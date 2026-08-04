# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: preview-harness/pages-snapshot.spec.mjs >> contract · declared theme renders use bounded parallelism
- Location: preview-harness/pages-snapshot.spec.mjs:246:1

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/preview-harness/fixtures/pages/serve-landing-declared-themes.html", waiting until "load"

```

# Test source

```ts
  195 |                 )
  196 |                 .catch(() => {});
  197 | 
  198 |             // Comparison scores are asynchronous (fetch + decode + SSIM). Capture the settled
  199 |             // fidelity state, not the initial "waiting…" skeleton.
  200 |             if (fixture === "serve-format-compare") {
  201 |                 await page
  202 |                     .waitForFunction(() =>
  203 |                         Array.from(
  204 |                             document.querySelectorAll(".cp-compare-score"),
  205 |                         ).every(
  206 |                             (cell) =>
  207 |                                 cell.textContent !== "waiting…" &&
  208 |                                 cell.textContent !== "comparing…",
  209 |                         ),
  210 |                     )
  211 |                     .catch(() => {});
  212 |             }
  213 |             if (fixture === "serve-reference-compare") {
  214 |                 await page
  215 |                     .waitForFunction(
  216 |                         () =>
  217 |                             !document
  218 |                                 .querySelector(".cp-reference-result")
  219 |                                 .textContent.includes("comparing"),
  220 |                     )
  221 |                     .catch(() => {});
  222 |             }
  223 | 
  224 |             await page.screenshot({
  225 |                 path: resolve(outDir, `${fixture}.${theme}.png`),
  226 |                 fullPage: true,
  227 |                 animations: "disabled",
  228 |             });
  229 | 
  230 |             // Extra runtime states of this same fixture, shot from the already-loaded page.
  231 |             for (const state of FIXTURE_STATES.filter((s) => s.fixture === fixture)) {
  232 |                 await state.apply(page);
  233 |                 await page.screenshot({
  234 |                     path: resolve(
  235 |                         outDir,
  236 |                         `${fixture}-${state.suffix}.${theme}.png`,
  237 |                     ),
  238 |                     fullPage: true,
  239 |                     animations: "disabled",
  240 |                 });
  241 |             }
  242 |         });
  243 |     }
  244 | }
  245 | 
  246 | test("contract · declared theme renders use bounded parallelism", async ({ page }) => {
  247 |     let active = 0;
  248 |     let maxActive = 0;
  249 |     let completed = 0;
  250 |     const attempts = new Map();
  251 |     let released = false;
  252 | 
  253 |     await page.route("**/api/theme-render-lease?*", async (route) => {
  254 |         await route.fulfill({
  255 |             status: 200,
  256 |             contentType: "application/json",
  257 |             body: JSON.stringify({ lease: "page-lease", concurrency: 5 }),
  258 |         });
  259 |     });
  260 |     await page.route("**/api/theme-render-lease/release?*", async (route) => {
  261 |         released = true;
  262 |         await route.fulfill({ status: 204, body: "" });
  263 |     });
  264 | 
  265 |     await page.route("**/render/**", async (route) => {
  266 |         const url = new URL(route.request().url());
  267 |         if (!url.searchParams.has("themeProvider")) {
  268 |             await route.fulfill({
  269 |                 path: renderPlaceholder,
  270 |                 contentType: "image/png",
  271 |             });
  272 |             return;
  273 |         }
  274 |         expect(url.searchParams.get("_themeLease")).toBe("page-lease");
  275 | 
  276 |         active++;
  277 |         maxActive = Math.max(maxActive, active);
  278 |         const attempt = (attempts.get(url.pathname) ?? 0) + 1;
  279 |         attempts.set(url.pathname, attempt);
  280 |         await new Promise((resolve) => setTimeout(resolve, 100));
  281 |         active--;
  282 | 
  283 |         // Shed every card's first request. The page must retry it without exceeding the worker cap.
  284 |         if (attempt === 1) {
  285 |             await route.fulfill({ status: 503, body: "render busy" });
  286 |         } else {
  287 |             completed++;
  288 |             await route.fulfill({
  289 |                 path: renderPlaceholder,
  290 |                 contentType: "image/png",
  291 |             });
  292 |         }
  293 |     });
  294 | 
> 295 |     await page.goto(
      |                ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
  296 |         "/preview-harness/fixtures/pages/serve-landing-declared-themes.html",
  297 |     );
  298 |     await page.getByRole("button", { name: "Brand Light" }).click();
  299 | 
  300 |     await expect.poll(() => completed, { timeout: 10_000 }).toBe(3);
  301 |     expect(maxActive).toBe(3);
  302 |     expect(Array.from(attempts.values())).toEqual([2, 2, 2]);
  303 |     await expect.poll(() => released).toBe(true);
  304 | });
  305 | 
```