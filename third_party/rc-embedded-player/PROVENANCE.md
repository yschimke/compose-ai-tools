# Vendored: `rc-embedded-player` (AndroidX experimental Compose embedded Remote Compose player)

`RcPlayer` — a **pure-Compose interpreter** for a Remote Compose `CoreDocument`. It walks the
document's operation tree and emits Compose layout and draw nodes directly, rather than painting to
a framework `Canvas` inside an Android `View`.

That contrast is the reason we vendor it. The player compose-ai-tools uses today
(`RemoteComposeIrReplay` → `androidx.compose.remote.player.compose.RemoteDocumentPlayer`) is backed
by `remote-player-view`'s `RemoteComposePlayer`, an Android `View` bridged into Compose via
`AndroidView`. The embedded player is what a host embedding Remote Compose content *inside* a Compose
tree actually gets — different layout, text, and draw code, and therefore different pixels. Having
both lets `rc-compare` diff them against the same baked PNG.

## Upstream

- Repository: <https://github.com/androidx/androidx>
- Path: `compose/remote/integration-tests/player-compose-embedded/src/main/java/androidx/compose/remote/player/compose/embedded`
- Commit: `c8e7d738d7c76df3a87281ba8c3b880622df6282` (`androidx-main`, 2026-07-29)
- License: Apache-2.0

There is **no published artifact** for this player. Upstream declares the module as
`SoftwareType.TEST_APPLICATION` — an integration-test app — so the player ships only as sources
inside it. Vendoring is the only way to depend on it.

## What is vendored

The player proper: the package root plus `layout/`, `modifier/`, and `state/` (42 upstream files,
44 here — two local splits, `state/RcPlayerBitmapState.kt` and `RcPlayerShaders.kt`, each noted
under "Local modifications" below). Upstream's
`demos/`, `integration/previews/`, and the `androidx.wear.compose.remote.material3.previews` sample
previews that live in the same source set are **not** vendored — they are demo/test scaffolding for
the integration-test app, and they drag in Wear Material3 and `remote-creation-compose` capture.

Package names are kept verbatim (`androidx.compose.remote.player.compose.embedded`) so refreshing
the snapshot against a newer androidx checkout is a plain `diff -r` with no rename noise.

## Copyright

**Every source file in this module carries the AOSP Apache-2.0 header, without exception.** That
holds for the three kinds of file here:

- the vendored player sources, which keep upstream's header verbatim — a refresh must not strip it,
  and the local-delta comments below sit *inside* those files rather than replacing their headers;
- `GmsFontProviderCertificates.kt`, whose certificate strings are copied out of an androidx resource
  file (Apache-2.0, same as everything else here) and which carries the header for that reason;
- the files written here (`build.gradle.kts`, `src/test/.../RcEmbeddedRenderHarness.kt`), which carry
  the same header because they exist only to build and exercise AOSP-derived code in an AOSP package.

Check before committing a refresh:

```sh
for f in $(find src -type f \( -name '*.kt' -o -name '*.xml' \)) build.gradle.kts; do
  head -6 "$f" | grep -q 'The Android Open Source Project' || echo "MISSING: $f"
done
```

## Version skew

Upstream builds this player against the **in-tree** `remote-core` / `remote-player-core`. We build
it against the published alphas the version catalog pins (`compose-remote = 1.0.0-alpha15`). The
player reaches a number of `@RestrictTo(LIBRARY_GROUP)` members, and `CoreDataAccessors.kt` reaches
private `CoreDocument` state **reflectively** (upstream guards those names with its own
`CoreReflectionGuardTest`). Both are sensitive to the gap between `androidx-main` and alpha15, so a
snapshot refresh should be paired with a render of the `rc-compare` lane, not just a compile.

## Local modifications

See the `rc-embedded` column of the catalogs' `rc-compare.html` for the current visual delta against
the baked PNG. Local deltas over the upstream snapshot are listed here as they are made, each with
the upstream tracking issue it was reported under.

Each delta below is a **build-against-published-alpha** gap, not a rendering fix: upstream compiles
this player against the in-tree `remote-creation-compose`, where these symbols are public and
present. They are grouped in the tracking issue as "the embedded player cannot be built outside the
androidx tree against the published alphas".

- **Named-action dispatch for `LambdaAction` / `PendingIntentAction` dropped** (`RcPlayer.kt`, the
  `LocalRemoteNamedActionHandler` block). `LambdaAction` does not exist in
  `remote-creation-compose:1.0.0-alpha15`, and `PendingIntentAction` is `internal` there, so
  `parseId` is not callable from outside the module. The handler now forwards straight to
  `onNamedAction`. Both paths are *interactive click dispatch*; the render lane never fires an
  action, so nothing the comparison measures is affected.
- **`CapturedDocument` lambda/pending-intent forwarding dropped** (`RcPlayer.kt`, the
  `CapturedDocument` overload). `CapturedDocument` in alpha15 carries neither a `lambdas` nor a
  `pendingIntents` property. Same reasoning; that overload is for live capture, which this vendored
  copy does not use.

Neither delta is on the draw path. If a future alpha exposes the two action types, both blocks
revert to upstream verbatim.

- **GMS font-provider certificates inlined as source** (`GmsFontProviderCertificates.kt`; the
  `GoogleFontR` import is gone from `EmbeddedPlayerTypefaceResolver.kt` and `RcPlayerTextLayout.kt`).
  Upstream reads `com_google_android_gms_fonts_certs` off the google-fonts library's `R`, but the
  **published** `androidx.compose.ui:ui-text-google-fonts` AAR ships an empty `<resources/>` and a
  zero-byte `R.txt` — that array lives only in the library's `src/androidTest/res`, so it never
  reaches a consumer.

  Both consumers take the certificates directly as `List<List<ByteArray>>` — `GoogleFont.Provider`
  and `FontRequest` each carry that constructor beside the resource-id one — so no resource table is
  needed to supply them. The base64 strings are the verbatim contents of the `_dev` / `_prod` arrays
  from `compose/ui/ui-text-google-fonts/src/androidTest/res/values/font_certs.xml` at the pinned
  commit (same Apache-2.0 source), with the XML's line-wrapping whitespace removed; they are
  generated from that file rather than transcribed. Behaviour is unchanged: same certificates, same
  provider, same bytes.

  This replaces an earlier delta that vendored `font_certs.xml` into this module. That worked, but
  it obliged the module to carry a resource table for two constants — which meant
  `androidResources = true`, which in turn made the KMP restructure depend on whether the KMP-Android
  library plugin supports resource processing. Inlining removes the question rather than answering
  it.

  Worth reporting upstream on its own — any out-of-tree consumer following the documented
  downloadable-fonts pattern against the published artifact hits this, not just this player.

- **`GraphContext` reparented off `AndroidRemoteContext`** (`GraphContext.kt`, plus the new
  `StoreBackedRemoteContext.kt`). Upstream's `GraphContext` extends `AndroidRemoteContext`, which
  pinned it — and through `LocalGraphContext`, the whole state/expression path — to Android for
  behaviour it never used: it overrides every platform-bound member away (`loadBitmap`, `loadShader`
  are empty bodies) and shares the store explicitly.

  `StoreBackedRemoteContext` is a platform-neutral `RemoteContext` **ported from
  `AndroidRemoteContext` at the pinned commit**, method for method, minus the one method that cannot
  come along. That framing is deliberate: `GraphContext`'s leaf reads call `super.getFloat`/
  `getText`/…, so a reimplementation that diverged would change every computed value — which is every
  pixel. The bodies are upstream's, not ours. (`getText` reads via `getFromId`, not a typed getter —
  exactly the sort of thing a from-signatures guess gets wrong.)

  This is only viable because `AndroidRemoteContext` is barely an Android class: of its 63 methods,
  **five** touch the platform, and four of those (`setAndroidContext`, `setBitmapLoader`,
  `setTypefaceResolver`, `useCanvas`) are its own API rather than the `RemoteContext` contract. The
  sole contract method that does is `loadBitmap` — which `GraphContext` already stubbed. Worth
  reporting upstream alongside their issue #12: the split they describe is close to mechanical.

- **Canvas text gathered behind a platform seam** (`RcPlayerTextPlatform.kt`, plus `toTextStyle` in
  `RcPlayerPaint.kt`). Three of the four canvas text ops reached for `android.graphics` inline:
  `DrawTextAnchored` built an `android.graphics.Paint`, measured with `getTextBounds`, and drew via
  `nativeCanvas.drawText`; `DrawTextOnPath` and `DrawTextOnCircle` drew via
  `nativeCanvas.drawTextOnPath`, the latter also measuring with `Paint.measureText`.

  Those framework calls now live in four functions in one file — `measureTextInkBounds`,
  `measureTextWidth`, `drawTextAtOriginPlatform`, `drawTextOnPathPlatform` — with the
  `android.graphics.Paint` builder (`toNativeTextPaint`) private alongside them. **This is a move,
  not a port: the bodies are the same framework calls, so Android's text output is unchanged.** What
  moves out of the ops is only the geometry that was never platform-specific — the anchoring
  arithmetic, and the arc construction, which switches from `android.graphics.Path` to Compose's
  `Path.addArc` (the same framework call underneath on Android).

  The seam is deliberately *below* Compose's text APIs rather than through them. An earlier revision
  of this delta drew anchored text with `drawText(TextLayoutResult)`; that is a rendering change on
  Android, and it also splits measurement from drawing — `toNativeTextPaint` resolves named and
  downloadable families that the `TextStyle` path maps to `FontFamily.Default`, so a document naming
  a font would be measured with one face and drawn with another. Keeping both sides on the framework
  `Paint` makes that class of drift impossible by construction.

  The four take a **`TextPaintSpec`** (`RcPlayerTextPaintSpec.kt`) rather than `ComposeLocalPaint`.
  That projection is what makes the seam implementable off Android at all: the paint state carries
  brushes, colour filters and a framework `Shader`, and stays Android-coupled until the AGSL path
  (issue #2954) is seamed, while the text ops need six fields out of it — size, family id, weight,
  slant, whether a typeface was set, and the alpha-folded ARGB. `ComposeLocalPaint.toTextPaintSpec()`
  is a pure projection with no mapping or defaulting; `TextInkBounds` moved into the same file, since
  both halves return it. A side benefit at the two call sites that measure *and* draw
  (`DrawTextAnchored`, `DrawTextOnCircle`): they now build one spec and hand it to both, so the
  "measured with one face, drawn with another" failure is impossible per call rather than merely
  unlikely.

  `toTextStyle` is a pure extraction of `DrawText`'s own inline `TextStyle` construction, its only
  caller, generics-only family mapping and upstream `aosp/4187117` TODO included. Unifying it with
  the native ops means teaching it the richer resolution, not pointing the native ops at it; that is
  a separate change with its own render verification.

  Net: `android.graphics.Paint` is gone from `RcPlayerPaint.kt`, and `RcPlayerDrawing.kt` no longer
  names `android.graphics` or the native canvas at all — it is down to `Bitmap`/`BitmapDrawable`,
  i.e. the image-decode seam. Behaviour-preserving on Android, and unlike a rendering change it does
  not need the rc-compare lane to say so.

- **`PaintBundle.TEXTURE` uses multiplatform `ImageShader` instead of `BitmapShader`**
  (`RcPlayerPaint.kt`). The texture path built a framework `BitmapShader` and needed a parallel
  `nativeTileMode` table to feed it — a second tile-mode mapping alongside the Compose `mapTileMode`
  the same file already used everywhere else. `ImageShader` is the multiplatform equivalent and takes
  the Compose `TileMode` directly, so the duplicate table is gone and the bitmap goes through
  `asImageBitmap()`.

  Behaviour-preserving: same tile modes, same image, still wrapped as a `ShaderBrush`. Note the
  *other* `BitmapShader` — the one binding a bitmap uniform inside `buildRuntimeShader` — belongs to
  the AGSL path, which now lives in its own seam file (`RcPlayerShaders.kt`, next entry), not in
  `RcPlayerPaint.kt`.

- **AGSL runtime shaders isolated behind a platform seam** (`RcPlayerShaders.kt`, split out of
  `RcPlayerPaint.kt`; issue #2954). The paint decoder's `SHADER` and `SHADER_MATRIX` paths were the
  one part of it that cannot become a single multiplatform implementation: `RuntimeShader` (AGSL),
  the bitmap-uniform `BitmapShader`, `android.graphics.Matrix` for the local matrix, and the API-33
  `Build` guard have no portable Compose equivalent — desktop Compose exposes SkSL `RuntimeEffect`
  through skiko, a different shading language *and* uniform-binding API.

  So the two functions that touch them, `buildRuntimeShader(shaderId, remoteContext): Shader?` and
  `applyShaderMatrix(paintState, matrixWord, read)`, move verbatim into `RcPlayerShaders.kt` and the
  rest of `RcPlayerPaint.kt` — brushes, tile modes, blend modes, colour filters, images — is left
  expressed in multiplatform Compose graphics. The seam's signatures are the multiplatform
  `androidx.compose.ui.graphics.Shader` (a typealias for `android.graphics.Shader` here), so a
  jvm/desktop file supplying the same two functions over skiko is a drop-in with no change to the
  shared decoder. `RcPlayerPaint.kt` no longer imports `RuntimeShader`, `BitmapShader`, `Matrix` or
  `os.Build` — and with framework `Paint` already gone (the text port, above) it now imports no
  `android.*` at all. It is *import-clean* but not yet movable: the TEXTURE path still calls the
  in-package `resolveBitmap`, which stays in androidMain, so it graduates when the bitmap seam does
  (the `GraphContext`-style "import-clean ≠ movable" distinction the sequencing draws).

  Behaviour-preserving on Android: the bodies are the same code with the same call sites, verified
  by compile. The Android AGSL implementation is all this file carries; the desktop counterpart is
  **still deferred**, because the embedded player's shader output already diverges from the View
  player *on Android* (~89% on `ShaderGradientSticker` in the rc-compare lane), and that wants
  understanding before the path is used as a desktop baseline (sequencing step 5).

- **Bitmap decode/blit gathered behind a platform seam** (`RcPlayerImagePlatform.kt`, plus the
  `resolveImage`/`resolveCanvasImage`/`prepareOffscreenTarget` calls in `RcPlayerDrawing.kt` and the
  `TEXTURE` path of `RcPlayerPaint.kt`). The draw path reached `android.graphics.Bitmap` inline in
  three shapes: `resolveBitmap`/`resolveCanvasBitmap` returned a framework `Bitmap` that the blit ops
  (`DrawBitmap`/`DrawBitmapScaled`/`DrawBitmapInt`, the three bitmap-font ops) converted with
  `asImageBitmap()`; the `DrawToBitmap` offscreen target did the mutable-copy + `eraseColor` +
  `Canvas(target.asImageBitmap())` dance; and `RcPlayerPaint`'s `TEXTURE` path wrapped the decode in
  an `ImageShader`.

  Those framework touches now live in one written-here file — `resolveImage`, `resolveCanvasImage`,
  `prepareOffscreenTarget`, with the framework `resolveBitmap` kept `internal` alongside them. **This
  is a move, not a port: the bodies are the same decode/lookup/copy, so Android's pixels are
  unchanged.** The seam hands back Compose's multiplatform `ImageBitmap` (the type every draw-path
  caller already converted to), so `RcPlayerDrawing.kt` and `RcPlayerPaint.kt` no longer name
  `android.graphics` at all — with framework `Paint` already gone via the text seam, the draw path's
  two shared files are now import-clean. A jvm sibling of *this file alone* (over skiko
  `org.jetbrains.skia.Image`, plus a jvm draw context whose `loadBitmap` decodes) is what the image
  half of the draw path needs off Android.

  `resolveBitmap` stays framework-typed and `internal` because the surfaces that still genuinely need
  a `Bitmap` — the AGSL `BitmapShader` (`RcPlayerShaders.kt`), the reactive
  `rememberRemoteBitmapAsState` (`state/RcPlayerBitmapState.kt`), and the host `RcImageLoader` — are
  all Android-only anyway (the `Drawable`-typed loader and the AGSL seam are separately deferred). The
  seam deliberately does **not** try to make those portable; it removes `android.graphics` from the
  two files that are otherwise ready to compile off Android.

- **`RcImageSource` extracted, and `mapEasing` split out of `RcPlayer.kt`** (`RcImageSource.kt`,
  `RcPlayerEasing.kt`). Two small deltas with the same shape: a neutral declaration was living inside
  an Android-coupled one, so everything that touched it inherited coupling it never used.

  `RcImageSource` is an **empty** supertype of `RcImageLoader`. `GraphContext` only ever *carried* a
  loader — `RcPlayer` sets one, the canvas draw path reads it back — so it now carries the neutral
  type, and the single site that actually loads casts back to `RcImageLoader`. Narrowing rather than
  generalising is the point: the interface has no members, so nothing pretends image decode is
  platform-neutral. `mapEasing` is a six-line `when` from a core easing constant to a Compose one,
  whose only caller is the expression evaluator; leaving it in `RcPlayer.kt` pinned that whole
  evaluator to `SuppressLint`/`PendingIntent`.

  Together these are what let `GraphContext`, `RcPlayerState.kt` and `RcPlayerExpression.kt` compile
  for the jvm target — see "Done: it runs on the desktop JVM" below.

- **`rememberRemoteBitmapAsState` moved to its own file** (`state/RcPlayerBitmapState.kt`, out of
  `state/RcPlayerState.kt`). Not a behaviour change and not an upstream gap — a refactor in service
  of the CMP split, recorded here because it is the one place the snapshot is no longer file-for-file
  with upstream, so a refresh `diff -r` will flag both files. The function body is verbatim; re-apply
  the move after a refresh rather than treating the diff as a conflict. Rationale under
  "Done: `state/` decoupled" below.

### Behaviour delta: the frame loop, and the dead `autoUpdate` knob

Unlike everything above, this one *is* a fix rather than a build-gap workaround, so it is worth
reporting upstream on its own.

- **The time loop requests frames through `withInfiniteAnimationFrameMillis`, not `withFrameMillis`**
  (`RcPlayer.kt`). `RcPlayer` drives document time from a `LaunchedEffect` whose `while (true)` loop
  only breaks when the document has no animations, no time dependency, no particles and no `wakeIn` —
  i.e. for any animated or time-driven document it never returns. Requested through `withFrameMillis`
  that is indistinguishable from ordinary pending recomposition work, so **the composition never
  reaches idle**: `ComposeTestRule.waitForIdle()` blocks forever, and with it every wait-for-idle
  capture API. That is exactly what Compose's `InfiniteAnimationPolicy` exists to make visible, and
  `withInfiniteAnimationFrameMillis` is how you opt into it. Outside a test no policy is installed
  and the call degrades to `withFrameMillis`, so production timing is unchanged — confirmed by
  re-rendering the 24-document `remote-m3` lane and comparing by md5: **24 identical, 0 differing**.
  `RcIdleProbeTest` pins the property by composing a real document and asserting `waitForIdle()`
  returns at all.

- **`autoUpdate` removed** from both `RcPlayer` overloads and from
  `ExperimentalRemoteDocumentPlayer`. Upstream declares the parameter, defaults it to `true`, and
  forwards it down the wrapper chain — but **no body ever reads it**. It is dead, and worse than
  dead: it reads exactly like the knob that stops the frame loop, so a host trying to render a still
  frame passes `autoUpdate = false`, gets no error, and still hangs. The render harnesses here were
  written around that misunderstanding. With the loop fixed the knob has nothing left to mean, so it
  goes rather than being wired up.

### Not a source delta, but worth knowing about the build

The module carries **no resource table** and leaves `androidResources` at AGP 9's default (`false`
for libraries). It briefly needed it enabled, when the font certificates lived in a vendored
`font_certs.xml`; inlining them as source removed the only resource this module ever had. Do not
re-enable it without a reason — an empty resource table is one of the two things that made the KMP
restructure uncertain.

`testOptions { unitTests { isIncludeAndroidResources = true } }` stays, and is unrelated: that puts
the *dependencies'* merged resources (Compose's own themes) on the unit-test classpath, which the
Robolectric render harness needs to inflate real Compose content.

## Planned: CMP android/jvm

Goal: a `jvm` target that renders through Compose Desktop's Skia backend, so the `rc-compare` lane
rasterizes `.rc` documents headlessly **without Robolectric** — and, as a side effect, without the
software-canvas ambiguity that made the shader finding hard to attribute (see the tracking issue).

### Measured surface

**32 of the 42 vendored files reference nothing platform-specific** — no `android.*`, no
`androidx.core.*`, no `player.core.platform.*`, no `ui.text.googlefonts`. The remaining 10 (plus the
two written-here Android-only splits, `state/RcPlayerBitmapState.kt` and `RcPlayerShaders.kt`), with
what actually couples them:

| file | coupling |
| --- | --- |
| `RcPlayerPaint.kt` | none via import — runtime shaders moved to `RcPlayerShaders.kt`, framework `Paint` gone (text ported), and the `TEXTURE` bitmap now comes back from the image seam as an `ImageBitmap`; it stays in androidMain only through the in-package `resolveImage` it calls (an Android-only seam file today) |
| `RcPlayerShaders.kt` | `RuntimeShader`, `BitmapShader`, `Matrix`, `Build` (the AGSL seam, split out of `RcPlayerPaint.kt`) |
| `RcPlayerDrawing.kt` | none via import — `Bitmap`/`drawable`/the native canvas are gone (text + image seams); it stays in androidMain only through the in-package `resolveImage`/`resolveCanvasImage`/`prepareOffscreenTarget` it calls |
| `RcPlayer.kt` | `SuppressLint`, `PendingIntent`, `AndroidRemoteContext` |
| `EmbeddedPlayerTypefaceResolver.kt` | `Typeface`, `Handler`, `Looper`, `Log`, `FontRequest`, `FontsContractCompat`, `AndroidRemoteContext`, `TypefaceResolver`, `FontInstance` |
| `RcImageLoader.kt` | `Bitmap`, `drawable`, `content.res` |
| `state/RcPlayerBitmapState.kt` | `Bitmap` (split out of `RcPlayerState.kt` — see below) |
| `GraphContext.kt` | extends `AndroidRemoteContext` |
| `RcPlayerParticles.kt` | `AndroidPaintContext` |
| `RcPlayerTextLayout.kt` | `googlefonts.Font`, `googlefonts.GoogleFont` |
| `DrawablePainter.kt` | `drawable.Drawable` — Android-only by definition, no jvm counterpart needed |

The parenthesised "was also" entries are coupling this branch has already moved out, not coupling
removed: it now lives in `RcPlayerTextPlatform.kt`, which is written here rather than vendored and so
is not one of the 42 — alongside its neutral vocabulary in `RcPlayerTextPaintSpec.kt` and its skiko
counterpart `RcPlayerTextPlatformJvm.kt` in the jvm module. Concentrating it there is the point: it is
the one file a jvm sibling had to replace, that sibling now exists, and the two vendored files above
never needed one.

#### 32/10 is a coupling *surface*, not a partition

That table counts files by what they **import**. It does not describe a source-set split, and reading
it as one is the mistake to avoid: a source set can only hold a file whose *callees* are also
visible to it, and the 32 call into the 10 constantly. Following the references transitively — mark
the 10 as `androidMain`, then repeatedly pull in anything referencing a declaration that lives
there — the partition collapses to roughly **five** files in `jvmCommonMain`. The chains that do it,
each verifiable by grep:

| declaration | lives in (Android-coupled) | pulls in |
| --- | --- | --- |
| `rememberRemote*AsState` (14 helpers) | `state/RcPlayerState.kt` | 19 files outside `state/` |
| `RcPlayerChildren` | `RcPlayer.kt` | 5 of the 8 `layout/` files |
| `RcPlayerComponent` | `RcPlayer.kt` | `layout/RcPlayerStateLayout.kt` |
| `executeOperations` | `RcPlayerDrawing.kt` | `RcPlayerCanvas.kt`, `RcPlayerModifiers.kt` |
| ~~`GraphContext` (extends `AndroidRemoteContext`)~~ — **resolved**, see below | `GraphContext.kt` | the state + expression path |
| `RcImageLoader` (`Drawable`-typed) | `RcImageLoader.kt` | `layout/RcPlayerImageLayout.kt`, `RcPlayerCustom.kt`, **`GraphContext.kt`** |

So the unit of work is a **declaration**, not a file. Some of those splits are nearly free — the
`rememberRemote*AsState` row was the largest single blocker and its whole Android coupling was *one*
function (see the note below). Others are the `expect`/`actual` seams the sequencing already
names — `GraphContext`/`RemoteContext`, image decode, the `Drawable`-typed loader — which means
the original "step 1 needs no `expect`/`actual`" is only true for a `jvmCommonMain` of about five
files. Anything larger pulls step 2 forward.

#### A single android target cannot enforce the split

Worth knowing before treating step 1 as done: with only the android target configured,
`jvmCommonMain` is compiled *as part of the android compilation* and has the Android SDK on its
classpath. Nothing rejects an `android.*` import that lands in "common" code — the separation is
convention, not a constraint, until a second target exists to contradict it. The same is already
true *today*, before any restructure: this is a plain android library, so a file decoupled by hand
can be re-coupled by the next edit with nothing to notice.

`RcSemanticsExtractionTest`'s neighbour `PlatformNeutralSourcesTest` is that missing constraint. It
keeps two lists, because moving a file needs two things and they are worth not conflating:

- **`IMPORT_CLEAN`** — the file imports no Android platform API. This is what a declaration split
  buys, and the list exists so a later edit can't quietly undo one.
- **`READY_FOR_JVM_COMMON`** — additionally, the file imports nothing that stays in `androidMain`,
  which `jvmCommonMain` would not be able to see. Only these can actually move.

Import-freedom alone does **not** make a file movable, and `state/RcPlayerState.kt` is the case in
point: it is import-clean after the split below, but its helpers read `LocalGraphContext`, whose type
extends `AndroidRemoteContext`. It graduates when the `GraphContext` chain in the table above is
split — which is the same thing that unblocks the rest of the state path.

The second check works on imports, so it catches *cross-package* references only: the player splits
into `embedded`, `embedded.layout`, `embedded.modifier` and `embedded.state`, so a file in a
sub-package must import what it uses from the root package. References within the root package need
no import and this test cannot see them — the chain table above is the record for those. The whole
test retires once a `jvm` target enforces both halves by compiling.

#### Done: `state/` decoupled

`state/RcPlayerState.kt` held all fourteen `rememberRemote*AsState` helpers *and* one Android-typed
one, `rememberRemoteBitmapAsState` (`State<Bitmap?>`, decoding via `resolveBitmap`) — which coupled
the entire file, and through it the 19 files above. That one function now lives in
`state/RcPlayerBitmapState.kt`; `RcPlayerState.kt` no longer imports anything Android. The function
body is verbatim and it had **no call sites** in the vendored subset (upstream API surface only), so
this is a file move, not a behaviour change. `RcPlayerState.kt` is now held import-clean by
`PlatformNeutralSourcesTest`, and `CoreDataAccessors.kt` / `CoreDataModel.kt` /
`SnapshotRemoteComposeState.kt` — already platform-neutral as vendored — are pinned as genuinely
movable.

This clears the *import* half of the largest chain, not the whole chain: the fourteen helpers still
read `LocalGraphContext`, so `state/` moves when `GraphContext` is split, not before. What the split
buys now is that the 19 dependent files are no longer blocked on an `android.graphics.Bitmap` import
that had nothing to do with them.

Verified by compile and by the module's `check`. **Not** verified by a render: the rc-compare lane
needs a staged catalog (see the sequencing note below), so the claim here is "no behaviour change by
construction", not "the 24-document render is unchanged".

#### Done: the canvas text seam has both halves

`:third-party-rc-embedded-player-jvm` now carries **`RcPlayerTextPlatformJvm.kt`** — the same four
functions over skiko, so the seam is implemented on both sides rather than declared on one.

**Everything goes through the shaper, not through `SkFont` directly**, and that is the single most
important decision in the file. `Font.measureText` / `Canvas.drawString` are the obvious one-line
counterparts to `Paint.getTextBounds` / `Canvas.drawText`, and they are wrong for anything but plain
Latin: they map code points to glyphs in one typeface with no shaping, so kerning and ligatures are
skipped, Arabic and Indic come out unjoined, RTL is not reordered, and anything the face lacks becomes
missing-glyph boxes instead of falling back. Android's `Canvas.drawText` does all of it (Minikin
shapes and falls back), so the direct calls would not be a *metrics* difference of the kind recorded
below — they would be visibly wrong text, and **invisibly** wrong here, since measurement and drawing
would agree with each other while both disagreed with Android. So the seam's own cross-checks would
have stayed green. `Shaper.make(FontMgr)` (HarfBuzz + ICU bidi, fallback through the font manager)
supplies `TextBlob.tightBounds` for ink bounds, `TextLine.width` for the advance, and the blob itself
for the origin draw. One wrinkle worth knowing: a shaped blob's origin is *not* its baseline — `shape`
puts the first baseline an ascent below the offset it is given — so both sides correct by
`TextBlob.firstBaseline`, which is what keeps measure and draw on the same origin.

`drawTextOnPath` is the one with no counterpart call, and it is built on Skia's own primitive for the
job rather than hand-rolled: a `TextBlob` of per-glyph `RSXform`s (rotate + translate), which is what
the framework assembles internally too, so the placement is computed here but the drawing is still one
Skia call per face. It reproduces the framework's behaviour — each glyph centred half an advance along
the path and rotated to the tangent there, `hOffset` along and `vOffset` perpendicular, glyphs past the
end dropped and the run continuing onto the next contour of a multi-contour path.

It is also the **one place the seam is not fully shaped**, and the reason is mechanical rather than
principled. Placing glyphs individually needs each glyph's *font*, which the flattened
`TextLine`/`TextBlob` views do not expose; the API that does is skiko's `RunHandler` callback, and
that path **segfaults** — a use-after-free inside skiko's own ICU run iterator, reproducible with a
minimal handler and unrelated to this code. (`RunInfo.font` is also only borrowed for the callback,
so it needs `makeWithSize` to copy — worth knowing if anyone retries this.) So glyphs on a path are
resolved per character *with* fallback (glyph id 0 means the face cannot draw it, which is the signal
to ask the font manager for one that can) but without cross-character shaping. Drawing from a
flattened shaped line instead would silently draw fallback ids against the primary face, which
renders unrelated glyphs — worse than the missing kerning. Curved text is where this matters least,
since per-glyph rotation dominates sub-pixel kerning, but it is a real gap and wants a follow-up once
skiko's handler is usable.

Font resolution mirrors `EmbeddedPlayerTypefaceResolver` branch for branch, with two documented
divergences. `google:` is a `FontsContractCompat` download on Android and has no JVM equivalent, so
the name is tried locally and substituted if absent — the "downloadable fonts" limit below, and a
substitution rather than an error. And Skia has no generic families, so the core ids map through a
candidate list (CSS-style names first, which is what fontconfig resolves on Linux, then concrete
faces for hosts where those mean nothing). One trap worth recording: `matchFamilyStyle(null, …)` —
the obvious way to ask for the default face — returns **null** on Linux, and `Font(null, size)`
measures zero rather than falling back, so the resolver never yields null while the host has any font
at all.

`DesktopTextPlatformTest` verifies it by rasterizing for real. It asserts relationships, not numbers:
the font stacks differ across the seam so any pinned width would pin the host's fonts, and the
strongest test is that measured ink bounds predict where the drawn glyphs actually land — which is
exactly the invariant `DrawTextAnchored` rests on, and the one a face mismatch would break while
every other test still passed. Two of the eighteen exist specifically to catch the unshaped
implementation described above, and they discriminate rather than tolerate: a kerned pair (`AV`)
must measure *narrower* than its glyphs do apart — exactly equal is the signature of no shaping —
and a CJK string must measure about an em per ideograph rather than the much narrower
missing-glyph box. **It needs skiko's natives**, which means the per-OS
`skiko-awt-runtime-*` artifact (pulled in as `testRuntimeOnly(compose.desktop.currentOs)`) *and* a
loadable GL library — `libskiko` links it even for raster-only drawing. Where that is missing the
class skips loudly rather than failing sixteen times; if you see that message the environment needs
`libgl1` on `LD_LIBRARY_PATH`, and note Gradle test workers inherit the *daemon's* environment, so
`./gradlew --stop` after exporting it.

What this does **not** finish: the ops that call these four still live in `RcPlayerDrawing.kt`, which
needs `Bitmap`/`BitmapDrawable`, so no draw op runs on the JVM yet. The seam is ready ahead of its
callers — deliberately, since it was the piece with an unknown in it.

#### Done: it runs on the desktop JVM

`:third-party-rc-embedded-player-jvm` compiles the neutral subset of this module's sources against
**Compose Desktop** and runs them on a plain JVM — no Android, no Robolectric. `DesktopRemoteContextTest`
exercises the value layer there: float/int/colour/text round-trips through the shared store, and —
the one that actually matters — that a store read registers with Compose's snapshot system, without
which `GraphContext`'s whole `derivedStateOf` design would silently degrade to "never invalidates".

The sources are **shared by path, not copied**: the jvm module adds the Android module's
`src/main/kotlin` as a source directory and names an explicit file list. So there is one copy of each
file, and no possibility of the two drifting.

This inverts what `PlatformNeutralSourcesTest` is for. The scan was standing in for a missing
compiler; now the compiler is here, and a file that isn't really neutral fails to build rather than
passing a source scan. The test remains as the fast check with the precise message, and
`readyFilesAreActuallyCompiledForTheJvm` ties its `READY_FOR_JVM_COMMON` list to the build file's, so
a file cannot be claimed ready without something having actually compiled it off Android.

**What runs, and what doesn't.** The value/expression layer runs: the store, the neutral
`RemoteContext`, `GraphContext`, the `rememberRemote*AsState` family, and the expression/animation
evaluator. The draw path does not exist here — that is `RcPlayerPaint`/`RcPlayerDrawing` and the rest
of the sequencing below. Ten of the module's files still import Android; nine did before this, so the
remaining work is the draw path, not the value layer.

**This also makes 1b optional rather than blocking.** A separate jvm module was chosen precisely
because converting the Android module to KMP still has an unsettled risk (Robolectric under the
KMP-Android plugin), and nothing here needs that resolved. If the conversion happens, this module's
file list is the migration order; if it never does, the desktop lane still works.

### The source-set shape is `jvmCommon`, not `common`

`remote-core` — the document and operation model the player reads throughout — is a plain
`java-library` upstream, **not** a multiplatform artifact. So the shared code is not
platform-agnostic; it is *JVM*-common. The layout has to be an intermediate source set both targets
depend on:

```
commonMain        (empty, or Compose-only helpers)
└── jvmCommonMain  ← the 32 clean files + the `remote-core` dependency
    ├── androidMain ← the 10 above, as today
    └── jvmMain     ← their jvm actuals
```

Putting `remote-core` in `commonMain` would not resolve. This is the single most important structural
constraint and the easiest one to get wrong.

### What a `JvmRemoteContext` actually costs

Less than the line counts suggest. `RemoteContext` (remote-core, JVM) declares **42 abstract
members**, and `AndroidRemoteContext` implements them in 669 lines — but the contract is
overwhelmingly a *variable/state store*, which is platform-neutral: `loadFloat`/`getFloat`,
`loadColor`/`getColor`, `loadText`/`getText`, `loadInteger`, `setNamed*Override` /
`clearNamed*Override`, `addCollection`, `putDataMap`/`getDataMap`, `putObject`/`getObject`,
`listensTo`, `updateOps`, `loadAnimatedFloat`, `loadPathData`/`getPathData` (plain float arrays).

Genuinely platform-bound, and short: `loadBitmap` (decode), `hapticEffect` (no-op on jvm),
`runAction`/`runNamedAction`/`addClickArea` (host callbacks), `loadShader`/`getShader` (storage of a
core `ShaderData`).

`AndroidPaintContext` is 1510 lines but is reached **only** by `RcPlayerParticles.kt` — the embedded
player draws through Compose's `DrawScope`, not the core's paint pipeline. Particles can stay
Android-only in a first cut rather than forcing a Skia `PaintContext` port.

### Known parity limits before starting

- **Text.** The canvas text ops measure and draw through a framework `android.graphics.Paint`. That
  is behind one seam (`RcPlayerTextPlatform.kt`, four functions) and the skiko half is **written**
  (`RcPlayerTextPlatformJvm.kt` — see "Done: the canvas text seam has both halves"), so what is left
  here is not a port but the parity limit itself: metrics will not be bit-identical across targets,
  because Skia's shaping is reachable from both but Android's font stack is not. The seam makes that a
  *measurable* difference — both sides answer the same four questions about the same
  `TextPaintSpec` — rather than a diffuse one.
- **Shaders.** AGSL has no JVM equivalent; desktop Compose exposes SkSL `RuntimeEffect`, which is
  close but not the same language or the same uniform plumbing. Shader parity across targets will not
  be exact — and note the embedded player's shader path already diverges from the View player on
  *Android* (89% on `ShaderGradientSticker`), so that wants fixing before it is used as a jvm
  baseline.
- **Downloadable fonts.** `google:`-prefixed fonts go through `FontRequest`/`FontsContractCompat`,
  which is Android-only. The jvm side substitutes a local face rather than fetching, so such a
  document renders in the wrong face rather than failing — see the seam section above.
- **Shaping on a path.** Three of the four seam functions shape through HarfBuzz with fallback; the
  text-on-path one resolves glyphs per character instead, so cross-character kerning and joining are
  not applied along a path. Blocked on a skiko crash rather than on design — details in the seam
  section above.

### Sequencing

Revised after the measurement above: the original step 1 ("move the 32/10 split into
`jvmCommonMain`/`androidMain`, no `expect`/`actual` needed") is not a pure source-set move, and as a
single-target milestone it cannot be verified. It splits into 1a/1b.

1. **1a — declaration splits, still a plain android library.** Peel the platform-neutral
   declarations out of the coupled files so a `jvmCommonMain` worth having exists *before* the build
   is restructured. Each split is behaviour-preserving and verified by a compile, so they land
   independently and bisect cleanly. Progress so far, in the order the table above implies:

   - `state/`'s Android *import* — **done**, by splitting out `rememberRemoteBitmapAsState`.
   - `GraphContext`'s `AndroidRemoteContext` base — **done**, via `StoreBackedRemoteContext`. This
     was step 2's `RemoteContext` seam, pulled forward because it gated most of 1a; it turned out to
     need no `expect`/`actual` at all, since `RemoteContext` is itself platform-neutral (42 abstract
     members, not one naming an Android type — even `loadBitmap` takes a `byte[]`).
   - `RcImageLoader` pinning `GraphContext` — **done**, via the empty `RcImageSource` supertype.
     `GraphContext` only ever *carried* a loader, so it now carries the neutral type and the one
     site that loads casts back. Narrowing beat generalising here: the interface has no members, so
     nothing pretends image decode is platform-neutral.
   - `mapEasing` out of `RcPlayer.kt` — **done** (`RcPlayerEasing.kt`), which is what let
     `RcPlayerExpression.kt` and `RcPlayerState.kt` compile for the jvm target.
   - The canvas text ops' framework `Paint` — **done** (`RcPlayerTextPlatform.kt`), which is what
     removed `Paint` from `RcPlayerPaint.kt` and the native canvas from `RcPlayerDrawing.kt`. A
     seam, not a port: Android's text output is unchanged. **Both halves now exist** — the skiko
     sibling is written and tested against a real raster, so step 4 below is spent early and text is
     no longer on the critical path.
   - The draw path's framework `Bitmap` — **done** (`RcPlayerImagePlatform.kt`), which is what
     removed `android.graphics` from `RcPlayerDrawing.kt` and `RcPlayerPaint.kt`. A seam, not a port:
     Android's pixels are unchanged. The *Android* half is written; the skiko sibling waits on a jvm
     draw context (whose `loadBitmap` decodes via `org.jetbrains.skia.Image`), which is step 3's
     `JvmRemoteContext` — so unlike the text seam, the image seam's jvm half is paced by the context,
     not ready ahead of it.
   - The draw dispatcher out of `RcPlayer.kt` — **done** (`RcPlayerDispatch.kt`). The four
     component-tree composables (`RcPlayerRawDocument`, `RcPlayerRootLayoutComponent`,
     `RcPlayerComponent`, `RcPlayerChildren`) touch only neutral types — the composition locals, the
     seamed `executeOperations`, and the per-layout composables — so moving them out keeps
     `RcPlayer.kt`'s `SuppressLint`/`PendingIntent`/`AndroidRemoteContext` coupling (document setup +
     interactive dispatch, neither on the pixel path) off the draw/layout path. A move, not a change:
     the bodies are verbatim. `RcPlayerDispatch.kt` is import-clean but not yet movable — its `when`
     still reaches `RcPlayerText` (googlefonts) and `RcPlayerImageLayout` (the `Drawable` loader).
   - **Next: add the import-clean set to the jvm source list.** `RcPlayerDrawing.kt` /
     `RcPlayerPaint.kt` / `RcPlayerDispatch.kt` / `RcPlayerCanvas.kt` / `RcPlayerModifiers.kt` /
     `RcPlayerDensity.kt` and the neutral `layout/**` + `modifier/**`, once their remaining
     androidMain callees (the `Drawable` image loader, the googlefonts text layout, and a jvm draw
     `RemoteContext` whose `loadBitmap` decodes) are split or seamed. This is where the deferrals
     below start to matter.

   **Import-clean and movable are different things**, and 1a keeps tripping over the difference —
   `GraphContext` imports nothing Android yet still cannot move, because of an ordinary in-package
   reference. `PlatformNeutralSourcesTest` tracks the two as separate lists for exactly this reason.

   **Deferred deliberately** (niche surfaces the jvm target does not need in a first cut, each
   isolated enough to postpone): **particles** — `RcPlayerParticles.kt` is the only
   `AndroidPaintContext` user, so deferring it avoids a Skia `PaintContext` port entirely, at the
   cost of a small seam where `RcPlayerPaint.kt`/`RcPlayerDrawing.kt` dispatch into it; **AGSL
   shaders** (now behind their own seam file `RcPlayerShaders.kt` — see the shader-seam delta under
   "Local modifications" — so the shared paint decoder no longer holds any runtime-shader coupling;
   the desktop `actual` is what stays deferred, issue #2954); and **downloadable fonts**
   (`RcPlayerTextLayout.kt`). What is *not* deferrable is framework `Paint` and bitmaps — those are
   the draw path itself; `Paint` is now seamed, bitmaps are not.
2. **1b — restructure to KMP, android target only,** moving the (now much larger) clean set into
   `jvmCommonMain`. Ship it with the forbidden-import guard from above, since the target itself
   enforces nothing. One build-level unknown left: whether `com.android.kotlin.multiplatform.library`
   under AGP 9 supports Robolectric unit tests with the dependencies' merged resources, which
   `RcEmbeddedRenderHarness` needs to inflate Compose content. The other unknown — resource
   *processing* for the module's own table — is **gone**: the font certificates are source constants
   now, the module owns no resources, and `androidResources` is back at its default.
3. Add the `jvm` target and the `expect`/`actual` seams, starting with `RemoteContext`
   (`GraphContext`'s `AndroidRemoteContext` base) and image decode. This is where the remaining
   chains in the table are actually paid for, not step 1.
4. ~~Port text off framework `Paint`.~~ — **done ahead of order**, both halves. Pulled forward
   because it was the step with an actual unknown in it (does Skia answer the same four questions?),
   and the answer turned out to be yes; leaving it last would have deferred the only risk in the plan
   to the end. Its callers still wait on bitmaps.
5. Shaders last, after the Android-side shader divergence is understood. The **seam** is already in
   place — `RcPlayerShaders.kt` isolates the two AGSL functions behind a portable
   `androidx.compose.ui.graphics.Shader` signature (issue #2954) — so what remains at this step is
   the desktop skiko `RuntimeEffect` *body*, not the extraction.

**On the success criterion.** "The existing render output does not change" is the right test for
every step here, but it needs a staged catalog: `RcEmbeddedRenderHarness` skips unless
`rc-compare.mjs --stage-embedded` has written `<id>.rc` + `manifest.json`, and no `.rc` fixtures are
committed. So a compile-only check is what a working tree gives you; the render comparison is a CI /
full-capture step, and a step-1a split should not be called verified on a compile alone.
