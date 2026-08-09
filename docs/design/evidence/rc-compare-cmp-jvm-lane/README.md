# rc-compare — the cmp-jvm desktop-player lane in CI

Evidence for `rc-embedded-jvm-lane`, the input that adds a **fourth** lane to the published
`rc-compare.html`: the same captured `ir/*.rc` document rendered through the Compose Desktop / Skiko
embedded player (`:third-party-rc-embedded-player-jvm`), beside the baked PNG, the vendored
TypeScript `RcdPlayer`, and AndroidX's Compose-embedded `RcPlayer`.

It rides on the machinery the Robolectric lane already introduced. `--stage-embedded` now runs once
if *either* lane is on and writes the `<id>.rc` + `manifest.json` both harnesses read; the jvm
harness fills `rc.jvm.output`, and `rc-compare.mjs --embedded-jvm` reads it back. The jvm player
links libGL and draws offscreen, so its Gradle test runs under `xvfb-run`.

> Two things changed after this evidence was captured. The input now defaults to **true**, so a
> catalog shipping `ir/*.rc` gets the cmp-jvm column without opting in. And libGL/xvfb are no longer
> installed by the `desktop-render` deps step: that step runs long before the bundle exists, so
> firing it on a default-true input would charge an apt round-trip to every caller, including the
> catalogs that carry no `ir/*.rc` and skip the lane. The `rc-compare` step installs them itself,
> just-in-time, guarded on `command -v xvfb-run` so a `desktop-render` caller doesn't pay twice.

## Produced by running exactly what the workflow runs — against the real remote-m3 bundle

In-session, on this machine, over the real `remote-m3` catalog (24 `ir/*.rc` documents):

```sh
# 1. render the real catalog to a bundle (Robolectric baked PNGs + ir/*.rc)
compose-preview bundle pack --module samples:design-catalog-remote-m3 --with-semantics -o bundle.png

# 2. stage the documents once (shared by both embedded lanes)
node rc-compare.mjs --bundle bundle.png --player <rc-player>/bundle.js --out out --stage-embedded rc-in
#    -> rc-compare: staged 24 document(s)

# 3. Android embedded lane
./gradlew :third-party-rc-embedded-player:testDebugUnitTest --no-daemon --rerun \
  -Prc.embedded.input=rc-in -Prc.embedded.output=rc-out            # BUILD SUCCESSFUL; 24 PNGs

# 4. cmp-jvm lane (Compose Desktop / Skiko, drawing offscreen)
./gradlew :third-party-rc-embedded-player-jvm:test --no-daemon --rerun \
  -Prc.jvm.input=rc-in -Prc.jvm.output=rc-jvm-out                  # BUILD SUCCESSFUL; 24 PNGs, 0 errors

# 5. render the four-lane page
node rc-compare.mjs --bundle bundle.png --player <rc-player>/bundle.js --out out \
  --system remote-m3 --title remote-m3 --embedded rc-out --embedded-jvm rc-jvm-out
#    -> 24/24 rendered, mean mismatch 1.16%
```

Result: **JS mean 1.16%, embedded mean 1.05%, cmp-jvm mean 2.07%**, 24 scored in every lane. The
cmp-jvm renders are real Skiko rasterizations of the real documents — the star icon, the
`Morning run / Heart rate` watch face, the buttons and cards all draw.

> In this Nix-based agent container skiko's `FontMgr`/GL natives are reached via `LD_LIBRARY_PATH`
> (fontconfig + freetype + libGL); on the GitHub runner the workflow's `desktop-render` deps step
> installs those as system libs (`libgl1 libfreetype6 libfontconfig1 …`), so CI needs no such dance.

## Compact by design — one extra column, not two

The JS and embedded lanes each take **two** columns (the render and its full pixel diff, always
shown). The cmp-jvm lane takes **one** — the render, with its pixel diff collapsed into a
`▶ pixel diff` disclosure directly beneath the cell. A reviewer sees a fourth render inline without a
fourth full-height diff column pushing everything sideways; the diff is one click away when a row
diverges.

Compact (default — the cmp-jvm diff folded; header + the diverging rows):

![rc-compare with the cmp-jvm lane, diffs folded](rc-compare-four-lane-compact.png)

Expanded (the cmp-jvm `▶ pixel diff` disclosures opened):

![rc-compare with the cmp-jvm lane, diffs expanded](rc-compare-four-lane-expanded.png)

## Why the fourth lane earns its keep

Scoring the same document through a *third* independent player makes a divergence attributable one
step further. `WatchScreenRemote` is the clearest case in this run:

| preview | JS | embedded | cmp-jvm | reading |
|---|---|---|---|---|
| `WatchScreenRemote` | 1.92% | **21.16%** | **31.96%** | the `RcPlayer` draw path (shared by both embedded lanes) wraps the watch-face text differently from the baked/JS render — a draw-path issue, not a JS-player or catalog one |
| `IconRemote` | **5.83%** | 0.00% | 0.00% | the reverse — only the JS player draws the star too small |

A row where only the cmp-jvm column moves would point at the desktop seam (text/image/skiko) rather
than the shared evaluator; here cmp-jvm tracks the Android embedded player, which is the expected
result and itself a signal the platform-neutral draw path behaves the same off Android.

## Keeping future coverage automatic

`rc-compare-fixture.mjs` (the synthetic page emitter) is extended with this lane too, so a *page-
layout* change to `rc-compare.html` can be screenshotted for before/after in one command without the
full catalog render. This evidence is the real thing; the fixture is the fast path for iterating on
the page chrome.

## Cost, and why it is opt-in

Gradle/Skiko work, not Node, so it defaults to off. The cmp-jvm harness ran in ~30s for 24 documents
here. A runner without the desktop deps, or a harness that fails to load the Skia natives, degrades
to a page without the cmp-jvm column and a `::warning::` rather than failing the render this job
exists to publish.
