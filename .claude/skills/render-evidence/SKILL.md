---
name: render-evidence
description: Capture the before/after rendered PNGs a UI-affecting PR in this repository needs as visual evidence, including bringing an Android SDK up in a fresh sandbox. Use when a change touches a Compose @Preview, a catalog, the VS Code webview, an overlay, a theme, an icon or a fixture, and the PR body needs real pixels.
---

# Capturing visual evidence

Root [`AGENTS.md`](../../../AGENTS.md#pr-workflow) says a UI-affecting PR must carry
before/after evidence as **embedded, viewable images** — describing an image, or
deferring to the diff bot's auto-comment, does not count. This is how to produce
them here.

## Check what already exists first

Rendering is not free. Before capturing anything:

- Read the sticky `<!-- preview-diff -->` comment on the PR — CI may already have
  rendered and diffed exactly the surface you changed.
- Reuse renders published on the `compose-preview/pr` and `compose-preview/main`
  branches where they cover the change.
- Scan [`.github/workflows`](../../../.github/workflows) for the preview-diff CI
  this repo already runs, and cite it.

The bot's comment is a convenience, not a substitute: your PR body still needs
*your* change's before/after embedded in it.

## Rendering

Whole sample modules:

```
./gradlew :samples:android:composePreviewRenderAll
./gradlew :samples:cmp:composePreviewRenderAll
```

One preview, which is what a before/after pair usually wants:

```
./gradlew :samples:android:composePreviewRender --rerun \
  -PcomposePreview.filter=<PreviewFunctionName>
```

`--rerun` matters. A failed render leaves `.error.json` sidecars that count as task
outputs, so `composePreviewRender` goes `UP-TO-DATE` and re-reports the stale
failure. PNGs land under `<module>/build/compose-previews/renders/`.

The samples consume the plugin through `includeBuild("gradle-plugin")`, so a plugin
edit is picked up with no publish step.

For the **before** side, render at the base commit (`git stash`, or a worktree at
`origin/main`) into a separate directory before rendering the head.

## The Android lane needs an SDK; the Desktop lane does not

- **Compose Desktop renders headlessly** — no `DISPLAY`, X server or `xvfb`. Skia's
  software path draws offscreen. If a desktop render looks broken in a sandbox, it
  is almost never the windowing system: check the JDK 17 toolchain and the native
  deps first, per
  [Common commands](../../../docs/AGENT_GUIDE.md#common-commands) and
  [`docs/DESKTOP_NATIVE_DEPS.md`](../../../docs/DESKTOP_NATIVE_DEPS.md).
- **The Android/Robolectric lane needs an Android SDK**, which a fresh container
  does not have. `scripts/install.sh --android-sdk` installs it (and a JDK when
  `./gradlew` reports "Unable to download toolchain"). The traps that cost a whole
  session — chiefly that the platform package for `compileSdk = 37` is
  **`platforms;android-37.0`**, not `android-37`, and that the wrong name fails the
  entire `sdkmanager` invocation it appears in, taking the SDK 36 packages down with
  it — are written up under
  [Bringing up a fresh sandbox](../../../docs/AGENT_GUIDE.md#important-constraints).
  Read that before improvising. Cold end-to-end run is about 3 minutes.

## Surfaces the `@Preview` pipeline does not reach

- **VS Code panel UI** ([`src/webview/`](https://github.com/yschimke/compose-preview-vscode/blob/main/src/webview/), `media/preview*.css`) has
  its own capture path: the preview-harness boots the real `<preview-app>` bundle
  headlessly against fixture JSON. Loop and fixture authoring are in
  [`preview-harness/README.md`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/README.md#agent-workflow);
  seed fixtures are `grid-default` and `a11y-findings`.
- **A semantics overlay** has a renderable proxy: the `compose/semantics-wireframe`
  SVG.
- **Anything genuinely uncapturable here** (a three.js webview in a headless
  container): say so explicitly in the PR body, embed a proxy if one exists, and
  state how a human or CI verifies it visually. Then treat "nothing renders this
  yet" as a gap to close in the same or a fast-follow PR — register a fixture or a
  `@Preview` with the preview-harness so the next change to that surface is diffed
  automatically.

## Getting the pixels into the PR

1. Commit the PNGs you intend to cite to your working branch and push.
2. Embed them as markdown images whose URLs are **commit-SHA-pinned**
   `raw.githubusercontent.com` links to those pushed files — the same form the diff
   bot uses. This is not just convention: a `![alt](url)` whose host is not a
   GitHub origin has its `!` stripped before it reaches the API, silently, so a
   deployment URL or a CDN link arrives as a bare link and the evidence is gone.
   Committing and citing the raw URL is what makes the image survive.
3. Write `![alt](url)` plainly and **leave any backticks that appear alone**. They
   are injected between the agent and GitHub, and the `PR Body Syntax` workflow
   repairs them in place. The three cases it does not cover — review comments,
   destinations mangled past recognition, and a picture that still did not render —
   are in [PR workflow](../../../docs/AGENT_GUIDE.md#pr-workflow).
4. Verify the pixels actually render on the published page before claiming evidence.
   `WebFetch` caches per URL for ~15 minutes; bust it with a throwaway query
   parameter rather than chasing a bug you already fixed.

A before/after pair whose two images look identical is a finding, not a failure —
either the change is not visual, or the render did not pick it up. Say which.
