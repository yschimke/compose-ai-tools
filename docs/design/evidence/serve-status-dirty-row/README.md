# `/status` — the dirty/failed optimization row

The row a catalog shows while it is still replacing another build's renders.

`before.png` / `after.png` are the `serve-status` page fixture, light theme, captured
through the pages harness. The only difference is the optimization line on the
**Wear Material 3** catalog:

| | |
| --- | --- |
| before | `theme optimization degraded · 232/240 cached · 3 failed · 24 inherited, re-rendering` |
| after | `theme optimization degraded · 232/240 cached · 3 failed · 24 awaiting re-render` |

Two things are being shown, and only the first is visible here:

1. **The wording.** `regenerate` marks *this* build's renders dirty too, so
   "inherited" was a false claim about where those pixels came from, and
   "re-rendering" asserted activity the pass may not have — its queue can be paused
   or waiting on admission.
2. **The failure count.** `3 failed` was previously unreachable for a dirty entry:
   `CatalogThemeCache.snapshot()` counted a failure only when the key was *not*
   cached, and a dirty entry is cached by design. The fixture sets the number
   directly, so a screenshot cannot demonstrate that — its evidence is
   `ThemeCachePersistenceTest.a dirty render the pass cannot regenerate is counted as
   failed`, which fails against the old rule.

The `wear-m3` fixture catalog exists for this row. Every other catalog on the page
is converged, so before it was added the dirty/failed half of this row — wording,
count and the meter's secondary/warning tone — was rendered by no fixture and
therefore diffed by nothing.

## Reproducing

From the **repository root**:

```bash
# 1. Regenerate the page fixtures from ServeWeb (writes fixtures/pages/*.html).
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:serve:test --tests '*ServeWebFixtureTest*'

# 2. Capture. PNGs land in preview-server/preview-harness/out/<fixture>.<theme>.png.
npm --prefix preview-server/preview-harness ci
npm --prefix preview-server/preview-harness run harness:pages -- -g serve-status
```

Step 1 is `--rerun-tasks` if you have already run it once on the same sources —
Gradle otherwise reports the test up to date and leaves the fixture stale, which
reads exactly like a change that did not take.

In a sandbox whose Playwright browsers predate the harness's pinned version, step 2
fails with `Executable doesn't exist at .../chromium_headless_shell-<n>`. Do **not**
run `playwright install`; point the harness at the browser that is already there:

```bash
PLAYWRIGHT_BROWSERS_PATH=<dir> npm --prefix preview-server/preview-harness run harness:pages -- -g serve-status
```

where `<dir>` holds a `chromium_headless_shell-<pinned-n>/chrome-headless-shell-linux64/`
directory of symlinks into the installed `chrome-linux/`, with `headless_shell`
linked as `chrome-headless-shell`. The old and new Playwright layouts differ only in
those two names.
