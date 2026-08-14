# Rendering with the rewritten Compose SlotTable

The Compose Runtime team reimplemented the `SlotTable` to improve random-write performance in
composition. It shipped in Compose **1.12.0** behind a global opt-in — the flag also exists on the
1.11.x line this repo currently pins — and the team asked for correctness and performance feedback
before it becomes the default and the flag is removed:

```kotlin
ComposeRuntimeFlags.isLinkBufferComposerEnabled = true
```

This repo is a useful place to answer the correctness half of that question. Every catalog here
renders a large, committed corpus of Compose output to PNG, so rendering the same previews twice —
once with the flag, once without — is a pixel-level regression suite for the rewrite that costs one
Gradle invocation.

## The knob

Off by default. Nothing renders differently until a run asks for it.

| Where | Form |
| --- | --- |
| One run, command line | `./gradlew :samples:cmp:composePreviewRenderAll -PcomposePreview.linkBufferComposer=true` |
| One run, render JVM directly | `-Dcomposeai.render.linkBufferComposer=true` |
| Durable, per module | `composePreview { linkBufferComposer = true }` |

Precedence is the usual one: system property, then Gradle property, then the DSL value — the same
chain `hostTheme` and `fixedTime` use, resolved by `composeAiLinkBufferComposer`.

**Both lanes honour it.** Unlike `fixedTime` — which is Android-only because it works by shadowing a
function under Robolectric — this is a flag on the `androidx.compose.runtime` classes both backends
share, so the Android/Robolectric renderer, the Desktop/CMP renderer, the pooled desktop worker and
both daemons all read it.

## How it is applied

[`LinkBufferComposer`](../data/render/core/src/main/kotlin/ee/schimke/composeai/data/render/LinkBufferComposer.kt)
in `:data-render-core` sets the flag reflectively, and the render entry points call it before they
compose anything:

- `DesktopRendererMain.main` — covers the forked capture *and* the pooled worker, which calls that
  same `main()` per request.
- `RobolectricRenderTestBase.renderPreview` — inside the sandbox, because each Robolectric sandbox
  classloader holds its own copy of `ComposeRuntimeFlags`.
- both daemons' `RenderEngine.render`, against the classloader that render composes on.

Two properties of that design are load-bearing:

- **Reflection, not a compile-time reference.** `:data-render-core` has no Compose dependency, and
  `:renderer-android` deliberately compiles its public API against the older `compose-bom-compat`
  floor so consumers on Compose 1.9.x can call it without `NoSuchMethodError` (see
  [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md)). Naming `ComposeRuntimeFlags` at compile
  time would raise the renderer's Compose floor for every consumer to serve an opt-in almost nobody
  switches on. Same shape as the coil probe in `ShadowAsyncImagePainter`.
- **A missing flag is a hard failure, not a no-op.** Requesting the opt-in against a runtime with no
  `ComposeRuntimeFlags.isLinkBufferComposerEnabled` — an older Compose, or a future one that has
  finished the migration and removed it — fails the render with a message naming the flag. A
  silently-ignored opt-in would render a whole catalog on the old composer and report a clean run
  that tested nothing, which is the one outcome this knob must not produce.

**It is whole-run, not per-preview.** The runtime latches the flag at the first composition in a JVM
(on Android, in a sandbox), and every lane here renders many previews per JVM. That matches how the
runtime team frames it — "set the flag before you compose any content" — and it is why
`RenderPreviewsTask.linkBufferComposer` is an `@Input`: flipping the composer must re-render rather
than report UP-TO-DATE against PNGs the other one drew.

## Running the A/B

```bash
./gradlew :samples:cmp:composePreviewRenderAll
cp -r samples/cmp/build/compose-previews/renders /tmp/renders-baseline

./gradlew :samples:cmp:composePreviewRenderAll -PcomposePreview.linkBufferComposer=true
# byte-compare; any difference is a rendered-output change attributable to the composer
diff -rq /tmp/renders-baseline samples/cmp/build/compose-previews/renders
```

The same shape works for `:samples:android` (Robolectric lane) and for any of the
`samples/design-catalog-*` modules, which are the largest corpora in the repo.
