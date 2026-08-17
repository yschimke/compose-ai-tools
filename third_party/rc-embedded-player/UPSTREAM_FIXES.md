# Upstream report: `rc-embedded-player` vs `androidx-main`

A three-way review of the vendored embedded Remote Compose player against upstream, done on
2026-08-17. It answers two questions: **what has upstream changed since our pin**, and **which of
our local deltas are still bugs upstream** and should be sent back.

## What was compared

| corner | what it is |
| --- | --- |
| `pinned` | upstream at the vendor pin — `c8e7d738d7c76df3a87281ba8c3b880622df6282`, path `compose/remote/integration-tests/player-compose-embedded/src/main/java/androidx/compose/remote/player/compose/embedded` |
| `upstream` | upstream at `androidx-main` HEAD, path `compose/remote/remote-player-compose/src/main/java/androidx/compose/remote/player/compose/embedded` |
| `vendored` | `third_party/rc-embedded-player/src/main/kotlin/…/embedded` in this repo |

Fetched over `raw.githubusercontent.com/androidx/androidx`, one file per vendored path, plus the
`remote-core` / `remote-player-core` sources cited below. All diffs were taken whitespace-insensitive
(`diff -w`) — the vendored copy is reformatted to this repo's 2-space ktfmt profile, so a raw diff is
almost entirely indentation.

Everything asserted below was read out of upstream source at HEAD, with the file and line given. No
claim here rests on the pinned snapshot or on `PROVENANCE.md`.

---

## 0. The headline: the player is no longer an integration-test app

**The embedded player has moved out of `integration-tests/player-compose-embedded` and into the
published `compose/remote/remote-player-compose` library.** Its `build.gradle` now declares
`type = SoftwareType.PUBLISHED_LIBRARY_ONLY_USED_BY_KOTLIN_CONSUMERS` — where the old module was
`SoftwareType.TEST_APPLICATION` — and the whole `embedded` package has been carried across with
`internal` declarations promoted to `public` + `@RestrictTo(LIBRARY_GROUP)` on the way.

This invalidates the central claim in `PROVENANCE.md` ("There is **no published artifact** for this
player … Vendoring is the only way to depend on it"). It is not yet *shipped* — the
`remote-player-compose-1.0.0-alpha17-sources.jar` on `dl.google.com` contains no `embedded` package,
so the graduation lands in alpha18 or later — but from the next alpha the player is an artifact, not
a source drop.

Two things follow, and they pull in opposite directions:

- A refresh is no longer a `diff -r` against the same path. The upstream path changed, and so did
  the visibility of roughly every declaration. Plan the next snapshot refresh as a re-vendor, not a
  patch.
- Vendoring may still be required even after alpha18, because **every entry point is
  `@RestrictTo(LIBRARY_GROUP)`** — `RcPlayer` (both overloads), `ExperimentalRemoteDocumentPlayer`,
  `RcImageLoader`, `LocalRemoteContext`. See finding **B12**.

---

## A. Deltas of ours that upstream has already fixed

These were local deltas at the pin and are now upstream verbatim or near-verbatim. They can be
dropped from `PROVENANCE.md`'s "Local modifications" at the next refresh — **and, importantly, they
are not worth re-reporting.**

| delta | upstream now |
| --- | --- |
| Frame loop via `withInfiniteAnimationFrameMillis` | `RcPlayer.kt:341,343` — adopted, so `waitForIdle()` returns |
| `autoUpdate` dead parameter removed | gone from both overloads and from `ExperimentalRemoteDocumentPlayer` |
| Rounded-clip radius normalization | `ClipModifier.kt` carries `roundedRectRadiusScale` with our comment ("Matches the radius normalization performed by Android's `Path.addRoundRect` in remote-core") |
| Rounded-clip `densityBehavior` | `ClipModifier.kt` — `RemoteRoundedClipShape` now takes `densityBehavior` and scales by density |
| `isFloatOverridden` / `overriddenFloats` | `SnapshotRemoteComposeState.kt` — present |
| `expressionDependsOnAnimation` (don't flatten an animated child) | `state/RcPlayerState.kt` — present, in a slightly cleaner recursion |
| `dimensionConstraints` (`widthIn`/`heightIn` silently dropped) | `modifier/WidthModifier.kt` — present, our doc comment included |

The clip work is the interesting one: upstream took the *radius arithmetic* and the *density
behaviour* but not the *ordering* fix or the DP-scaling rule — see **B6** and **B7**.

---

## B. Fixes to send upstream

Ranked by how visible the wrong output is. Each names the upstream site, the mechanism, and where
this repo's version and its test live.

### B1 — `paintTheme` is never set, so every themed document renders dark

**Upstream:** `RcPlayer.kt` — `paintTheme` appears nowhere in the module; grep for `Theme.` across
the whole `embedded` package finds exactly one hit, an unrelated `Theme.SYSTEM` argument in
`modifier/ClickModifier.kt:109`.

**Mechanism:** `RemoteContext.java:59` initialises `mTheme = Theme.UNSPECIFIED`, and
`ColorTheme.java:99` selects the light branch **only** for `Theme.LIGHT`:

```java
if (Theme.LIGHT == theme) { /* light */ } else { /* dark */ }
```

So an unanswered `UNSPECIFIED` is not "no preference", it is "dark". Every `ColorTheme` operation in
every document takes the dark branch, regardless of the host's night-mode setting, and regardless of
what the View player does with the same bytes.

**Measured:** on a themed document the embedded lane drew `#292A2D`
(`system_surface_container_high_dark`) where the View lane drew `#E9E7EC` (`…_high_light`).

**Fix:** resolve `SYSTEM`/`UNSPECIFIED` to a concrete mode once, before anything branches on it, and
assign it to `paintTheme`. Answering it from `isSystemInDarkTheme()` rather than from a
`Configuration` read is what makes every player agree — the View player's own rule (an SDK-33-guarded
`Configuration.isNightModeActive`, with `UNSPECIFIED` falling through to dark) is where the
divergence was found, not the specification it should be fixed against. A `theme: Int` parameter on
both `RcPlayer` overloads and on `ExperimentalRemoteDocumentPlayer` lets a host override it, and
re-applying on change keeps a runtime theme switch working.

**Here:** `RcPlayer.kt:140` (`resolvedTheme`), `ColorThemeResolution.kt:292` (`resolveThemeMode`).
Test: `RcColorThemePlayerTest`.

### B2 — indexed Android `ColorTheme` values are never mapped to framework resources

**Upstream:** the embedded player applies each `ColorTheme` operation but never performs the View
player's preceding `ThemeSupport.mapColors` pass (no `mapColors` anywhere in the package). Both the
light and dark branches therefore keep their authored fallbacks, so a document that asks for a
dynamic-colour index gets a static approximation with no diagnostic.

**Fix:** map the index to the framework `android.R.color` resource before the first operation replay,
retaining the fallback for unknown groups or unavailable resources.

**One trap worth passing on if this is implemented upstream:** the index table must not be derived by
reflecting over `Rc.AndroidColors` and lowercasing field names. That is wrong for **21 of the 196
indices** at alpha17 — `SYSTEM_ACCENT2_200` is `30` and collides with `SYSTEM_ACCENT2_1000`, so index
`31` has no constant and index `30` resolves to a real but wrong colour; twenty more are misspelled
against the resource they select (`SYSTEM_ERROR_620` at index `62` where the resource is
`system_error_10`; the whole `system_neutral1_*` run at 78–90 spelled `SYSTEM_NEUTRAL78_0`,
`SYSTEM_NEUTRAL79_790`, …), so they match nothing and silently keep the fallback. The table that
actually resolves indices on a device is `ThemeSupport.AndroidColors` in `remote-player-view`.

**Here:** `AndroidColorThemeResolver.kt`, `ColorThemeResolution.kt`. Tests: `RcColorThemePlayerTest`,
`AndroidColorTableDriftTest`.

### B3 — `ComposeLocalPaint.color` defaults to transparent, where `android.graphics.Paint` defaults to opaque black

**Upstream:** `RcPlayerPaint.kt:55`

```kotlin
public var color: Int = 0        // transparent ARGB
public var isColorSet: Boolean = false
```

**Mechanism:** a Remote Compose paint bundle may set a `SRC_IN` colour filter and no base `COLOR` op
— icon tints do exactly this. `SrcIn` against a transparent source produces nothing, so the Compose
renderer draws no icon while the View renderer, whose `android.graphics.Paint` starts opaque black,
tints its default source successfully.

**Fix:** default `color` to opaque black. `isColorSet` stays `false`, so an implicit default remains
distinguishable from an explicit `COLOR` operation and nothing that inspects `isColorSet` changes
behaviour.

**Here:** `RcPlayerPaint.kt:55`. Tests: `RcPlayerPaintTest` pins the default;
`ComposePathColorFilterRobolectricReproTest` demonstrates the `SrcIn` behaviour independently, using
only standard Compose drawing.

### B4 — value-producing `PaintOperation`s are evaluated through `apply()` only, so they resolve to 0

**Upstream:** `GraphContext.kt:117`

```kotlin
if (op is VariableSupport) op.updateVariables(this)
op.apply(this)                     // writes output -> captured
```

**Mechanism:** most computed ops publish their result from `apply(RemoteContext)`, and that is what
the graph assumes. But a value-producing `PaintOperation` — `ColorAttribute` above all — publishes
from `paint(PaintContext)`, and `PaintOperation.apply` forwards to `paint` only in
`ContextMode.PAINT` with a paint context attached. Under the graph neither holds, so `apply` alone
makes the op evaluate to nothing and any colour built on it comes out `0`: fully transparent. The
symptom is a dropped tint on anything whose colour is computed rather than authored.

**Fix:** route a value-producing `PaintOperation` through a draw-nothing `PaintContext` that carries
the graph as its read/write target and discards every drawing call. Layout components are
`PaintOperation`s too and must be excluded — their `paint` renders a whole subtree rather than
producing a value, and `apply` is already correct for them (it walks their children, which is how a
component's contents get evaluated at all).

**Here:** `GraphContext.kt:169` (`evaluate`), `GraphPaintContext.kt`. Test:
`GraphContextPaintOperationTest`.

### B5 — the graph cannot see measured `ComponentValue` sizes, so expressions over component size evaluate against 0

**Upstream:** `GraphContext.kt:129` — `getFloat` resolves time ids, then overrides, then computed
ops, then the shared store. Measured component sizes are in none of those: they are published as
Compose state from `RcPlayerComponent`'s `onSizeChanged` callback, because they only exist once
layout has run.

**Mechanism:** `rememberRemoteFloatAsState` consults that Compose-side map first, so reading such an
id *directly* works. An expression **over** one resolves its inputs through `GraphContext`, finds
nothing in the store, and evaluates against `0`.

**Symptom:** a switch thumb renders square. Its clip radii are `FloatExpression = min(width, height)
/ 2` over the track's `ComponentValue`s, so the radius comes out 0 — while the track's own
literal-radius clip is fine, which is exactly why the bug looks like it only affects the thumb.

**Fix:** hand the graph the same `Map<Int, State<Float>>` the composable resolver already consults,
and check it in `getFloat` ahead of the store. Reading a `State` inside the `derivedStateOf` records
the dependency, so a resize re-evaluates exactly the expressions that read it — no invalidation
plumbing needed.

**Here:** `GraphContext.kt:112` (`componentValues`), set from `RcPlayer`. Test:
`GraphContextComponentValueTest`.

### B6 — the rounded clip is hoisted past padding modifiers, not just past the draw

**Upstream:** `modifier/ClipModifier.kt:67`

```kotlin
// remote-core applies the rounded clip to the component's complete paint output; DrawContent
// precedes this op in the wire modifier list, so appending would leave that draw node unclipped.
return Modifier.clip(shape).then(this)
```

called unconditionally from `RcPlayerModifiers.kt:102`.

**Mechanism:** the premise ("DrawContent precedes this op") holds only sometimes, and the
unconditional prepend also puts the clip ahead of `PaddingModifierOperation`. On a switch thumb —
`padding(35.4dp, 7.9dp).size(16.dp)`, then this clip, then a background — the clip applies to the
padded 51×24dp box while the background paints the 16×16dp content well inside it. The rounded shape
never touches the thing it is meant to round, and the thumb renders square. The track beside it
carries no padding, which is why it looks correct.

**Fix:** a `Modifier.clip` already clips whatever the modifiers *after* it draw, so wire-list order
serves the common case — an explicit `DrawContentOperation` later in the list, or the implicit draw
`toModifier` appends when a component carries no marker. The one case list order cannot serve is a
`DrawContentOperation` that comes *before* this operation; hoist only then, driven by a flag the
caller already has (`drawContentProcessed`).

Worth stating for whoever takes this: no document in a 164-document catalog sweep exercises the
hoist branch. It is kept because the wire format permits it, not because anything observed needs it.

**Here:** `modifier/ClipModifier.kt:85`, `RcPlayerModifiers.kt:100`. Test: `modifier/ClipModifierTest`.

### B7 — `ClipCorner.literal` skips DP scaling for variable-backed radii, diverging from remote-core

**Upstream:** `modifier/ClipModifier.kt:141`

```kotlin
literal -> if (densityBehavior == CoreDocument.DENSITY_BEHAVIOR_DP) v * density else v
else -> v
```

where `literal` is `!data.x1.isNaN()` — i.e. a corner backed by a variable/expression rather than a
literal is deliberately **not** density-scaled.

**Mechanism:** that is not what the core does.
`RoundedClipRectModifierOperation.java:105-118` scales all four corners unconditionally once
`updateVariables` has resolved them into `mX1..mY2`:

```java
public void paint(@NonNull PaintContext context) {
    float topStart = mX1; …
    if (context.getDensityBehavior() == CoreDocument.DENSITY_BEHAVIOR_DP) {
        float density = context.getDensity();
        topStart *= density; topEnd *= density; bottomStart *= density; bottomEnd *= density;
    }
```

`mX1` is the *resolved* value by then, so whether it arrived as a literal or through a variable is
not information the core has, or acts on. Any expression-driven corner radius on a
`DENSITY_BEHAVIOR_DP` document therefore comes out density-times too small in the embedded player and
correct in the View player. This interacts with **B5**: an expression-driven radius is exactly the
switch-thumb shape.

**Fix:** drop the `literal` distinction and scale every resolved corner, matching `paint`.

**Caveat, and a question back:** our copy additionally keeps the pre-existing percent heuristic
(a corner in `0f..1f` is read as a fraction of `minDimension`) which upstream dropped when it
introduced `ClipCorner`. If that heuristic was removed deliberately — it was a workaround for a
component-size expression not having settled, which **B5** may have made unnecessary — say so and we
will drop it here too. The two changes should be reconciled together rather than each side keeping
half.

**Here:** `modifier/ClipModifier.kt` (`resolveRadius`).

### B8 — `initializeContext(context)` eagerly decodes every bitmap, contradicting the comment below it

**Upstream:** `RcPlayer.kt:196`

```kotlin
document.initializeContext(it)

// Register each bitmap's metadata (declared width/height for ImageAttribute, and
// discoverability for the lazy decode) WITHOUT decoding the pixels. The costly decode is
// deferred until a bitmap is actually drawn … (BitmapData.apply would also loadBitmap,
// i.e. decode every bitmap up front, which is what we're avoiding.)
```

**Mechanism:** `CoreDocument.java:1604`

```java
public void initializeContext(@NonNull RemoteContext context) {
    initializeContext(context, null);
    applyDataOperations(context);      // -> applyOperations(context, mOperations)
}
```

`applyDataOperations` walks every top-level operation, `BitmapData` included, so the decode the
comment says it is avoiding has already happened one line above. The metadata registration that
follows is then pure overhead. Every image in a document is decoded at composition time, whether or
not it is ever drawn.

**Fix:** call the two-argument `initializeContext(context, emptyMap())` — which only binds and resets
the context — and follow it with a data pass that skips `BitmapData`. The pass has to mirror
`applyDataOperations` faithfully (`ContextMode.DATA`, time update, variable registration, recursive
component updates, dirty/op-count bookkeeping, operation order) because animations and interactions
observe all of it; merely omitting the pass breaks them.

In-tree this is a small `applyDataOperations(context, skipBitmaps)` overload, or making
`BitmapData.apply` lazy. Out of tree it needs reflection, because `registerVariables` and
`applyOperations` are private on `CoreDocument` — which is itself an argument for the overload.

**Here:** `CoreDataAccessors.kt` (`applyDataOperationsWithoutBitmaps`,
`applyOperationsWithoutBitmaps`), called from `RcPlayer.kt:179`. Test: `RcPlayerBitmapFailureTest`.

### B9 — the canvas text ops ignore `LocalTypefaceResolver`, including the new `GmsFontTypefaceResolver`

**Upstream:** `RcPlayerPaint.kt:99-100`

```kotlin
public fun toNativeTextPaint(context: RemoteContext): Paint {
    val resolver = EmbeddedPlayerTypefaceResolver(context)
```

**Mechanism:** upstream has just built a host-pluggable typeface path — `RcPlayer`'s
`typefaceResolver` parameter, `LocalTypefaceResolver` (`RcPlayerCompositionLocals.kt:113`), and
`GmsFontTypefaceResolver` + `HasFontCerts` for downloadable fonts. `RcPlayerTextLayout.kt` honours it
(`RcPlayerTextLayout.kt:83,208` read `fontCertsResId` off the local). The four canvas text ops
(`DrawTextAnchored`, `DrawTextOnPath`, `DrawTextOnCircle`, and the `DrawText` measure) do not: they
hard-construct the default resolver, so a `google:` or host-resolved family is silently substituted
in canvas text while the same family resolves correctly in layout text — within one document, and
with no diagnostic.

Secondary: a fresh `EmbeddedPlayerTypefaceResolver` is allocated per call, i.e. per text op per
frame, discarding whatever cache the resolver holds. `GmsFontTypefaceResolver` keys its `FontRequest`
cache on the instance, so routing it through this path as-is would re-request on every frame.

**Fix:** thread the resolved `TypefaceResolver` into the draw path (it is already on the
`RemoteContext`, and `RcPlayer.kt:495` provides it to the composition local) and resolve through it
rather than constructing a new default. Hold the instance rather than building one per op.

### B10 — a URL/file-encoded image reference fails the whole document parse

**Upstream:** the `CapturedDocument` overload of `RcPlayer` parses straight into `CoreDocument`
without touching `Limits.ENABLE_IMAGE_URLS` / `Limits.ENABLE_IMAGE_FILES`, which are off by default.
A document containing an `ENCODING_URL` or `ENCODING_FILE` bitmap reference then fails to parse — not
"renders without that image", but takes the whole document down.

**Fix:** either enable the flags on the byte-level entry points, or degrade a rejected image
reference to a missing image rather than a parse failure. The failure mode is disproportionate to the
cause: one unresolvable image should not cost the document.

**Here:** `RemoteImageSupport.kt` (`enableEncodedImageReferences`), called at `RcPlayer.kt:505`.
Note `RemoteDocument(bytes)` parses in its own constructor, so that route needs the caller to enable
it — a second reason to fix this inside the parse rather than at each call site.

### B11 — `TEXT_ALIGN_JUSTIFY` in the `textAlign` field is honoured here and ignored by the View player

**Upstream:** `RcPlayerTextLayout.kt:126` and `:246`

```kotlin
CoreText.TEXT_ALIGN_JUSTIFY  -> TextAlign.Justify
TextLayout.TEXT_ALIGN_JUSTIFY -> TextAlign.Justify
```

**Mechanism:** the View player's `AndroidPaintContext.java:595-605` switches on the same field and has
no `TEXT_ALIGN_JUSTIFY` case:

```java
switch (alignment) {
    case CoreText.TEXT_ALIGN_RIGHT: case CoreText.TEXT_ALIGN_END: … ALIGN_OPPOSITE; break;
    case CoreText.TEXT_ALIGN_CENTER: … ALIGN_CENTER; break;
    default: staticLayoutBuilder.setAlignment(Layout.Alignment.ALIGN_NORMAL);
}
```

So `TEXT_ALIGN_JUSTIFY` in that field renders as normal/start on the View player. Actual
justification is the separate `justificationMode` property — which upstream already checks first,
at `RcPlayerTextLayout.kt:119`. The `textAlign` arm is the divergent one.

**Fix:** map `TEXT_ALIGN_JUSTIFY` in the `textAlign` field to `TextAlign.Start`, and leave
justification to `justificationMode`. This is the lowest-confidence item in section B — if the View
player's `default:` arm is the bug and the embedded player is right, then the fix belongs in
`AndroidPaintContext` instead. Either way the two players should not disagree.

**Here:** `RcPlayerTextLayout.kt:141,245`.

### B12 — the published module has no consumer-reachable entry point, and `remote-core` is `implementation`

Two packaging observations now that the module is a `PUBLISHED_LIBRARY`:

- **Everything is `@RestrictTo(LIBRARY_GROUP)`** — `RcPlayer` (both overloads),
  `ExperimentalRemoteDocumentPlayer`, `RcImageLoader`, `LocalRemoteContext`, `RemoteRoundedClipShape`,
  `rawDimensionDp`. An out-of-tree consumer cannot call the player without suppressing lint. If the
  intent is "published, not yet API-stable", `@ExperimentalRemotePlayerApi` (which the module already
  defines and uses) expresses that without also declaring it group-private. As it stands, publication
  does not yet make the player usable, and vendoring stays necessary.
- **`remote-core` is an `implementation` dependency** while `CoreDocument` — from `remote-core` —
  appears in the signature of the module's primary entry point, `RcPlayer(document: CoreDocument, …)`.
  A consumer cannot name the parameter type without adding `remote-core` themselves. `api(…)` is the
  usual answer for a type in a public signature. The same applies to `RemoteContext` on
  `LocalRemoteContext`.

### B13 — `GraphContext` extends `AndroidRemoteContext` for behaviour it does not use

Already tracked upstream (the class comment cites issue #12), so this is a data point rather than a
new report: **the split is close to mechanical.** `RemoteContext` declares 42 abstract members and is
itself platform-neutral — not one of them names an Android type, `loadBitmap` included, which takes a
`byte[]`. Of `AndroidRemoteContext`'s 63 methods, **five** touch the platform, and four of those
(`setAndroidContext`, `setBitmapLoader`, `setTypefaceResolver`, `useCanvas`) are its own API rather
than the `RemoteContext` contract. The sole contract method that does is `loadBitmap` — which
`GraphContext` already overrides to an empty body, along with `loadShader`.

This repo carries `StoreBackedRemoteContext.kt`, a neutral `RemoteContext` ported from
`AndroidRemoteContext` at the pinned commit method for method, minus that one method; `GraphContext`
extends it instead. If it is useful for the issue-12 work, it is a worked example of the base class
the split produces.

---

## C. Upstream changes to pull in at the next refresh

Not bugs — upstream improvements this snapshot does not have. Listed so a refresh is a decision
rather than a surprise.

| upstream change | where |
| --- | --- |
| Host-pluggable typefaces: `typefaceResolver` parameter, `LocalTypefaceResolver` | `RcPlayer.kt:157,495`, `RcPlayerCompositionLocals.kt:113` |
| `GmsFontTypefaceResolver` + `HasFontCerts` — downloadable fonts moved out of the default resolver into an opt-in resolver taking a host cert array resource | new file `GmsFontTypefaceResolver.kt` |
| Richer layout text: `BasicText` + `TextAutoSize.StepBased`, `LineBreak` from `lineBreakStrategy`, `Hyphens` from `hyphenationFrequency`, `justificationMode`, `buildFontVariationSettings` | `RcPlayerTextLayout.kt` |
| Frame pacing via `Limiter` (`recordDrawStart` / `computeDelay`) in the time loop | `RcPlayer.kt` |
| `RemoteComposePlayerFlags.isEmbeddedPlayerEnabled` gate on both overloads | `RcPlayer.kt` |
| Density-aware offsets — `rawDimensionDp(x, behavior, density)` instead of a raw `/ density` | `modifier/OffsetModifier.kt` |
| `buildComputedOpIndex` returns `IntObjectMap<Operation>` instead of `HashMap` | `RcPlayerCompositionLocals.kt` |
| `fastForEach` / `fastMap` / `fastFirstOrNull` throughout | canvas, particles, collapsible, fitbox, scroll |
| `TextLayoutData` field order and defaults changed | `CoreDataModel.kt` — reflection-adjacent, check against the pinned alpha before adopting |

The font-certs one deserves a note: **our `GmsFontProviderCertificates.kt` delta is superseded.**
Upstream's default resolver no longer performs the `FontRequest` at all, so there is nothing for
inlined certificates to feed; a host that wants branded fonts supplies
`GmsFontTypefaceResolver(context, R.array.…)` with its own array. The underlying packaging complaint
is still real and still worth filing — **the published `androidx.compose.ui:ui-text-google-fonts`
AAR ships an empty `<resources/>` and a zero-byte `R.txt`**, because
`com_google_android_gms_fonts_certs` lives only in that library's `src/androidTest/res` and never
reaches a consumer — but it belongs to `ui-text-google-fonts`, not to this player, and it now affects
any consumer following the documented downloadable-fonts pattern rather than this player
specifically.

Also worth knowing: with the default resolver, `fontCertsResId` is `0` and
`RcPlayerTextLayout.kt:312` skips the `google:` branch entirely, so a `google:` family falls through
to a local face **silently**. That is defensible as a default, but a one-line log at first
substitution would save the next person the bisect.

---

## D. Local divergences we are *not* proposing upstream

Recorded so the list above is not read as "everything that differs".

- **The platform seams** — `RcPlayerTextPlatform.kt`, `RcPlayerImagePlatform.kt`,
  `RcPlayerShaders.kt`, `RcPlayerTextPaintSpec.kt`, `StoreBackedRemoteContext.kt`,
  `RcImageSource.kt`, `RcPlayerEasing.kt`, `RcPlayerDispatch.kt`, `state/RcPlayerBitmapState.kt`.
  These exist to make a desktop-JVM target possible and are behaviour-preserving on Android. They are
  ours, not upstream gaps. `StoreBackedRemoteContext` is the one that is *also* evidence for an
  upstream issue — see **B13**.
- **The `FloatExpression` draw case using `overrideFloat` instead of `loadFloat`**
  (`RcPlayerDrawing.kt:158`). Deliberate here — it marks the id overridden so `GraphContext.getFloat`
  prefers the per-frame draw value over recomputing the op — but it is entangled with our graph
  changes, so it is not a standalone fix and is not offered as one.
- **The 2-space reformat and the fully-qualified names.** `PROVENANCE.md` claims a refresh is "a
  plain `diff -r` with no rename noise". That is no longer true: the vendored copy is reformatted and
  large stretches of `CoreDataAccessors.kt`, `layout/RcPlayerRowLayout.kt` and `RcPlayerModifiers.kt`
  have had imports inlined as fully-qualified names, which is what makes those files' raw diffs
  hundreds of lines for a handful of real changes. Worth fixing on our side before the next refresh —
  it costs nothing upstream and it is the single biggest obstacle to reviewing one.

---

## Reproducing this

```sh
BASE=https://raw.githubusercontent.com/androidx/androidx
PIN=c8e7d738d7c76df3a87281ba8c3b880622df6282
OLD=compose/remote/integration-tests/player-compose-embedded/src/main/java/androidx/compose/remote/player/compose/embedded
NEW=compose/remote/remote-player-compose/src/main/java/androidx/compose/remote/player/compose/embedded

cd third_party/rc-embedded-player/src/main/kotlin/androidx/compose/remote/player/compose/embedded
find . -type f | sed 's|^\./||' | while read -r f; do
  mkdir -p "/tmp/pinned/$(dirname "$f")" "/tmp/head/$(dirname "$f")"
  curl -fsS "$BASE/$PIN/$OLD/$f"        -o "/tmp/pinned/$f" || true   # 404 => local-only file
  curl -fsS "$BASE/androidx-main/$NEW/$f" -o "/tmp/head/$f"  || true
done
diff -rw /tmp/head .          # our deltas vs upstream HEAD
diff -rw /tmp/pinned /tmp/head  # what upstream changed since the pin
```

`diff -w` is not optional — without it the reformat drowns every real change.
