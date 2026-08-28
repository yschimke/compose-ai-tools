---
name: flake-triage
description: Decide whether a preview the visual-diff bot flagged actually regressed or is simply nondeterministic, using a repeat-render oracle at a single commit. Use when a PR's preview diff reports a changed preview whose source the PR does not touch, or when a render, GIF or filmstrip is suspected of being unstable.
---

# Is it a regression, or is the preview unstable?

A changed preview whose source the PR does not touch — clocks, timestamps,
randomness, animation frames, network-loaded images — is instability to triage, not
a regression to rubber-stamp and not a fix to write blind. The distinction is
decidable in a few minutes, at **one commit**, with no reference to the base branch.

## The oracle

Render the same preview N times at the *same* commit and compare the bytes. If two
renders of one commit differ, nothing about the PR's diff is evidence of anything.

```
for i in 1 2 3; do
  ./gradlew :samples:android:composePreviewRender --rerun \
    -PcomposePreview.filter=<PreviewFunctionName>
  cp <module>/build/compose-previews/renders/<Preview>.png run-$i.png
done
md5sum run-*.png
```

`--rerun` is load-bearing: a render task goes `UP-TO-DATE` off its own outputs
(including `.error.json` sidecars from a failed run), so a plain re-invocation
compares a file against itself and always "passes".

**A harness capture has the same oracle, and it is cheaper.** The captures the
`vscode-preview-diff` bot compares are Playwright shots of committed static
fixtures, so N runs cost seconds and need no JVM. **Two harnesses write into that
one baseline set**, and they do not share an invocation — find which one owns your
capture (`git grep "<fixture>" -- '*/preview-harness/*'`) and use its own:

```
# preview-server/preview-harness — the `serve` web surfaces (serve-*, viewer-*).
cd preview-server/preview-harness
HARNESS_CHROMIUM=/path/to/chromium HARNESS_THEME=light \
  npx playwright test -c playwright.config.mjs pages-snapshot.spec.mjs -g "<fixture>"
md5sum out/<capture>.light.png
```

```
# compose-preview-vscode/preview-harness — the VS Code panel's own fixtures.
# Run from `compose-preview-vscode/`, not from inside the harness directory, and build
# the webview bundle first: the fixtures load it, so a stale one moves pixels for
# reasons no commit explains.
cd compose-preview-vscode
node esbuild.webview.mjs
HARNESS_CHROMIUM=/path/to/chromium HARNESS_FIXTURE=<fixture> HARNESS_THEME=light \
  npx playwright test -c preview-harness/playwright.config.mjs snapshot.spec.mjs
md5sum preview-harness/out/<capture>.light.png
```

The spec filenames differ (`pages-snapshot.spec.mjs` vs `snapshot.spec.mjs`), so
the two commands are not interchangeable. `HARNESS_THEME` narrows to one theme in
both; `HARNESS_FIXTURE` is the extension harness's own selector and is cleaner
than `-g` there.

There is no `--rerun` equivalent to remember: each invocation rewrites `out/`.

Read the hashes:

- **All identical** → the preview is deterministic at this commit. A diff against
  the base is real; go find it in the source.
- **They differ** → the preview is unstable. The bot flagging it on unrelated PRs is
  a symptom, not a coincidence. Continue below.

Three runs is usually enough to catch it; five identical runs is a reasonable bar
for calling a fix proven.

## Localising an unstable render

Once you know it moves, find *what* moves:

- **Still PNGs** — diff the pair as images rather than eyeballing them; the
  percentage of changed pixels and *where* they sit names the culprit (a clock face,
  one panel of a filmstrip, a gradient band).
- **GIFs and filmstrips** — decode to frames and compare frame by frame. A filmstrip
  whose panels re-freeze somewhere new on each run differs in one to three panels
  while the rest is byte-identical, which reads as "small diff" until you split it.
  [`docs/design/evidence/filmstrip-determinism/`](../../../docs/design/evidence/filmstrip-determinism/README.md)
  is the worked example: two same-commit renders differing across 2.99% / 7.97% /
  10.96% of the image depending on the pair, root-caused to panels freezing at
  unpinned points rather than at their labelled transition fractions.
- **Animated images in a harness capture** — an APNG or GIF a spec swaps in plays
  on its own clock, and `img.complete` says *decoded*, not *finished*. A shot held
  only for the decode lands on an arbitrary frame.
  [`docs/design/evidence/motion-index-playing-determinism/`](../../../docs/design/evidence/motion-index-playing-determinism/README.md)
  is the worked example: eight same-commit runs, three distinct hashes, 0.11% of
  the image moving in one 19-row band per card. Decode the stub's `acTL`/`fcTL`
  chunks to learn its frame count and delays, then hold for rest — poll the
  container's pixels until two reads spaced wider than one frame agree, rather
  than hard-coding the duration.

The usual sources, in rough order of frequency: an unpinned animation clock, a
system-time variable, unseeded randomness, a network- or disk-loaded image, and
layout that depends on measurement order.

## Fixing it, and proving the fix

Pin the nondeterminism at its source — hold the clock, seed the randomness, pin the
panel to its labelled fraction — rather than loosening a comparison threshold.
Then:

1. Re-run the oracle. Consecutive `--rerun` renders should be **byte-identical**;
   quote the shared hash.
2. Add the pixel test that keeps it pinned (`SharedElementFilmstripPixelTest` is the
   pattern) so the next regression fails a test rather than a review.
3. Commit the evidence — the two differing before-renders and the stable after —
   under `docs/design/evidence/<slug>/` with a README stating the commit, the
   command, and the hashes. That is what makes the next occurrence a lookup instead
   of a rediscovery.

Reporting an unstable preview without the hashes is an assertion, not a triage. The
hashes are cheap; include them.

Related: `render-evidence` for the capture mechanics, and the `stability` reference
in the published [`compose-preview-review`](https://github.com/yschimke/skills)
skill for how to flag instability on someone else's PR.
