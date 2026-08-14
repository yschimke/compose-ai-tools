# Embedded RC player: our fixes vs. latest AndroidX

**Audit + refresh plan** for the vendored `third_party/rc-embedded-player` snapshot
(`androidx.compose.remote.player.compose.embedded`, upstream `RcPlayer`). Two questions:
what have we actually fixed on top of the snapshot, and what will a refresh onto
current `androidx-main` cost.

| | |
| --- | --- |
| Vendored snapshot | `c8e7d738d7c76df3a87281ba8c3b880622df6282` (`androidx-main`, 2026‑07‑29) |
| Upstream compared against | `2f88db18a19ffcb109f359511edbee9117a46f57` (`androidx-main`, 2026‑08‑14) |
| Published artifacts pinned here | `compose-remote = 1.0.0-alpha17` (latest published; alpha17 is the newest on `dl.google.com/dl/android/maven2/androidx/compose/remote/`) |
| Our deltas over the snapshot | 13 files modified, 11 files added, 0 removed |
| Upstream drift since the snapshot | 28 files modified, 1 added, **whole module relocated** |

Everything below was derived from a real tree diff, not from reading `PROVENANCE.md`;
[Reproducing this comparison](#reproducing-this-comparison) has the exact commands.

---

## 0. The headline: upstream moved the player into a published library

At the pinned commit the player lived only inside an integration-test app
(`compose/remote/integration-tests/player-compose-embedded`, declared
`SoftwareType.TEST_APPLICATION`) — which is the stated reason we vendor it at all: *"There is
no published artifact… Vendoring is the only way to depend on it."*

That is no longer true at `androidx-main`. The whole player — package root, `layout/`,
`modifier/`, `state/` — has been **moved to `compose/remote/remote-player-compose/src/main/java/…/embedded/`**,
a module declared `SoftwareType.PUBLISHED_LIBRARY_ONLY_USED_BY_KOTLIN_CONSUMERS` and published as
`androidx.compose.remote:remote-player-compose` — *an artifact this repo's version catalog already
pins* (`compose-remote-player-compose`). Only the tests, demos and integration previews were left
behind in the integration-test module. The package name did not change, so the diff is still a
plain `diff -r`.

Consequences, in order of importance:

1. **The vendoring rationale expires with the next alpha.** I verified against the actual AAR:
   `remote-player-compose-1.0.0-alpha17.aar` contains **zero** `…/embedded/` classes, so the
   relocation post-dates alpha17. The next release cut should ship the embedded player inside an
   artifact we already depend on.
2. **The API is `@RestrictTo(LIBRARY_GROUP)`, not public.** The move came with a sweep of
   `internal` → `public` + `@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)` across `RcPlayer`,
   `ComposeLocalPaint`, `ComponentModifiers.toModifier`, `LocalRemoteContext`,
   `LocalTypefaceResolver`, `RemoteRoundedClipShape`, `rawDimensionDp`, `CustomData` and most of
   `state/RcPlayerExpression.kt`. Out-of-group consumers can call it (the classes are in the AAR)
   but trip the `RestrictedApi` lint — the same `@SuppressLint("RestrictedApiAndroidX")` posture
   this module already takes.
3. **Upstream's own API tracking hasn't caught up.** `remote-player-compose/api/current.txt` and
   `restricted_current.txt` list nothing from the `embedded` package (the restricted file is a
   bare `// Signature format: 4.0` header). Metalava must be re-run before a release, so treat the
   promotion as *in flight* rather than finished, and re-check the API files before betting on
   artifact consumption.

---

## 1. What we carry today

53 Kotlin files in the vendored package vs. 42 upstream: 13 modified, 11 added.

### 1a. Rendering / behaviour fixes (the ones that matter)

| File | Δ | Fix |
| --- | --- | --- |
| `RcPlayerTextLayout.kt` | +128 −11 | Autosize (`TextAutoSize.StepBased` from `mMinFontSize`/`mMaxFontSize`), justification read from CoreText property 17 rather than the align enum (AndroidX's Java maps `TEXT_ALIGN_JUSTIFY` to `ALIGN_NORMAL`), `lineBreak`/`hyphens` applied *as a copy of the ambient `LocalTextStyle`* so an unset document property stays `Unspecified` instead of inheriting a host's `LineBreak.Heading` (#3667), `START_ELLIPSIS`/`MIDDLE_ELLIPSIS` overflow, `javaPlayerMaxLines` to match `AndroidPaintContext`'s `StaticLayout` line cap, autosize-aware `lineHeight` in `em`, and named/variable Google families via `GoogleVariableFontFamilies` |
| `modifier/ClipModifier.kt` | +69 −26 | Three fixes in one: corners scaled per `CoreDocument.densityBehavior`; a `roundedRectRadiusScale` normalisation matching Android's `Path.addRoundRect` (an oversized corner set is scaled down proportionally, as remote-core does); and the clip **prepended** (`Modifier.clip(shape).then(this)`) so the component's `DrawContent` output is inside the clip, which appending leaves outside |
| `state/RcPlayerState.kt` | +40 −19 | `ID_CONTINUOUS_SEC` resolves as a time variable; and an expression chain whose *nested* input is animated stays in the Compose-native evaluator instead of falling through to `GraphContext`, which evaluates core `FloatExpression`s as pure target values and so froze interaction-driven M3 progress indicators |
| `SnapshotRemoteComposeState.kt` | +5 | `isFloatOverridden(id)` — records that a host/action override happened, so it can take precedence over the id's authored expression |
| `GraphContext.kt` | +12 −8 | `ID_CONTINUOUS_SEC`; honours `isFloatOverridden`; reparented onto `StoreBackedRemoteContext` (below); carries the neutral `RcImageSource` |
| `CoreDataAccessors.kt` / `CoreDataModel.kt` | +36 / +6 | Six extra `CoreText` reflection fields — `mLineBreakStrategy`, `mHyphenationFrequency`, `mJustificationMode`, `mAutosize`, `mMinFontSize`, `mMaxFontSize` — plumbed onto `CoreTextData`. These are what the text fixes above read |
| `RcPlayerPaint.kt` (`GRADIENT`) | — | A gradient stop that is a **colour-id reference** rather than a literal ARGB int is resolved through `read.getColor(word)`, keyed off the bitmask in the meta word's high 16 bits. Upstream masked the meta word down to the colour count and read every stop as a literal, so a named/overridable stop reached `Color(...)` as raw reference bits — the ~89% `ShaderGradientSticker` divergence ([`rc-gradient-bound-color`](evidence/rc-gradient-bound-color/README.md)) |
| `RcPlayerDispatch.kt` | — | The component's draw ops are executed **once**, by `toModifier`'s `DrawContentOperation` branch. Upstream passed `drawOpsList` to `toModifier` *and* ran the same list again from a second `drawWithContent` in `RcPlayerComponent`, redrawing background/border chrome at a different point in the chain ([`rc-outlined-card-double-draw`](evidence/rc-outlined-card-double-draw/README.md)) |
| `RcPlayer.kt` | +16 −203 | Frame loop through `withInfiniteAnimationFrameMillis` (so the composition can reach idle and `waitForIdle()` returns), dead `autoUpdate` knob removed, plus the `RcPlayerDispatch` / `RcPlayerEasing` extractions |
| `ExperimentalRemoteDocumentPlayer.kt` | −2 | `autoUpdate` removed from the wrapper too |

**Four of these are now converged with upstream** — the frame loop, `autoUpdate`, the gradient
bound-colour resolution, and the `DrawContent` double execution. See §2 and §3.4.

### 1b. Structural seams (the CMP android/jvm split)

Pure moves, behaviour-preserving on Android, but they are what makes `rc-embedded-player-jvm`
possible — and they are the bulk of the re-application cost on any refresh:

`RcPlayerTextPlatform.kt` + `RcPlayerTextPaintSpec.kt` (canvas text behind a seam),
`RcPlayerImagePlatform.kt` (bitmap decode/blit), `RcPlayerShaders.kt` (AGSL),
`StoreBackedRemoteContext.kt` (platform-neutral port of `AndroidRemoteContext`),
`RcImageSource.kt`, `RcPlayerEasing.kt`, `RcPlayerDispatch.kt`,
`state/RcPlayerBitmapState.kt` — plus the corresponding subtractions inside
`RcPlayerPaint.kt` (+102 −126) and `RcPlayerDrawing.kt` (+97 −146).

### 1c. Build-gap workarounds

- `GmsFontProviderCertificates.kt` + the `GoogleFontR` removal in
  `EmbeddedPlayerTypefaceResolver.kt` — the published `ui-text-google-fonts` AAR ships an empty
  resource table, so upstream's `R.array.com_google_android_gms_fonts_certs` does not exist for an
  out-of-tree consumer.
- `RemoteImageSupport.kt` — flips `Limits.ENABLE_IMAGE_URLS` / `ENABLE_IMAGE_FILES` before parse,
  because `BitmapData.read` otherwise throws inside `inflateFromBuffer` and fails the **whole**
  document, not just the image.

> **`PROVENANCE.md` is stale on two points.** It says "42 upstream files, 44 here — two local
> splits"; it is 42 vs **53**, with **11** added files. And `RcPlayerDispatch.kt` and
> `RemoteImageSupport.kt` have no entry in the "Local modifications" list at all. Fix both in the
> same change as the refresh.

---

## 2. What upstream changed since our snapshot

Beyond the relocation and the `@RestrictTo` sweep, and a large amount of pure allocation/idiom
cleanup (`fastForEach`/`fastMap`/`fastFirstOrNull`, `IntObjectMap` instead of `HashMap`,
fully-qualified names lifted to imports — this is most of the 28-file, ~1100-line drift):

**Fixes that converge with ours — ours are now redundant:**

- **`withInfiniteAnimationFrameMillis`.** `RcPlayer.kt:341,343` now requests frames exactly the way
  our delta does. The reason we made the change (a `withFrameMillis` loop that never lets the
  composition idle, so `waitForIdle()` blocks forever) is fixed upstream.
- **`autoUpdate` deleted** from both `RcPlayer` overloads and `ExperimentalRemoteDocumentPlayer` —
  the same dead knob our delta removed.
- **Gradient stops that are colour-id references resolve through `read.getColor(word)`.** Upstream's
  `RcPlayerPaint.kt:411` now carries the identical `register` bitmask and lookup as our delta (it
  additionally rewrote the ascending-stops check into a loop). Our `ShaderGradientSticker` fix is
  upstream.
- **`DrawContent` is executed once.** Upstream deleted the second `drawWithContent` block from
  `RcPlayerComponent` and now relies on `toModifier(drawOpsList)`, which applies it at the
  `DrawContentOperation`'s position in the wire modifier list (appending at the end when the op is
  absent). That is the same ownership change our `RcPlayerDispatch.kt` made — upstream had *both*
  paths live, hence the doubled chrome.

**Fixes that converge with ours — but only partially:**

- **`ClipModifier` density behaviour.** Upstream added `densityBehavior` to
  `RemoteRoundedClipShape` and a `ClipCorner(value, literal)` wrapper where `literal` is
  `!data.x1.isNaN()`; density scaling is applied to **literal corners only**, on the grounds that a
  NaN-boxed corner is derived from the component's measured size and is already in pixels. That is
  a *better* rule than ours (we scale every resolved corner and pre-multiply a `0..1` fraction by
  `minDimension`), and it is the same insight as the TS player's size-relative-corner fix. Upstream
  still has **neither** our radius normalisation **nor** our clip ordering.

**Upstream fixes we should adopt:**

- **`OffsetModifier` honours `densityBehavior`** — `x`/`y` now go through `rawDimensionDp(...)`
  instead of an unconditional `/ density`. Rendering-affecting for non-DP documents.

**Upstream changes that are new work for us:**

- **`Text` → `BasicText`** in both text composables (`RcPlayerTextLayout.kt`), with every style
  property folded into an explicitly constructed `TextStyle`. This drops the Material3 `Text`
  layer — *and with it the ambient `LocalTextStyle` inheritance our text delta is built on*. It
  is a pixel-affecting change in its own right.
- **`RemoteComposePlayerFlags.isEmbeddedPlayerEnabled` gate.** `RcPlayer` now opens with a `check(…)`
  that throws unless the host sets the flag.
- **Frame pacing via `Limiter`** — `limiter.recordDrawStart(...)` / `computeDelay(...)` and a
  `kotlinx.coroutines.delay` inside the frame loop.
- **`typefaceResolver` parameter + `LocalTypefaceResolver` + `GmsFontTypefaceResolver`/`HasFontCerts`.**
  Upstream ripped the `google:` handling (and `GoogleFontR`) out of `EmbeddedPlayerTypefaceResolver`
  and moved it into an opt-in `GmsFontTypefaceResolver(fontCertsResId, …)`; `resolveFontFamily` now
  takes a `fontCertsResId` and **skips the downloadable-font path entirely when it is 0**, which is
  the default. `RcPlayer` gained a `typefaceResolver: TypefaceResolver? = LocalTypefaceResolver.current`
  parameter and re-provides it down the tree.
- **`buildComputedOpIndex` returns `IntObjectMap`**, `GraphContext` takes `IntObjectMap<Operation>` —
  a signature change on a class we subclass/reparent.

---

## 3. Fixes that will be needed

Ordered by "will silently produce wrong pixels" first, then compile-blockers, then hygiene.

### 3.1 Re-apply the ClipModifier fixes onto upstream's `ClipCorner` — do not just take either side

Upstream's `ClipCorner.resolve` is the better density rule; our `roundedRectRadiusScale` and the
clip-prepend are fixes upstream does not have. Target state: upstream's `ClipCorner`/`literal`
handling **plus** our normalisation **plus** `Modifier.clip(shape).then(this)`. Then decide
whether our `0..1` fraction heuristic is still needed — with `literal` distinguishing the two
corner kinds it may be dead, and it is the riskiest line in our version (a genuine 0.5 px literal
corner on a 200 px box becomes 100 px).

The clip-prepend is *still* required after upstream's `DrawContent` move: the wire list puts
`DrawContentOperation` before `RoundedClipRect`, so applying the clip at its wire position leaves
it *inside* the draw-content node and the generated background paints outside the clip.
**Report upstream** — this is a real bug in their tree, and the normalisation is a second one.

### 3.2 Decide the `Text` → `BasicText` question before refreshing text layout

Our text delta reads `LocalTextStyle.current` and copies it, deliberately: unset properties must
stay `Unspecified` while everything else rides the ambient style (#3667 — a fresh `TextStyle`
re-measured every string in every document and moved the AppCard fixture by 3 px). Upstream's
`BasicText` rewrite constructs a fresh `TextStyle` and has no ambient style at all. Options:

- re-apply our delta on top of `BasicText`, seeding the style from `LocalTextStyle.current` — but
  note this does **not** buy upstream's material3 decoupling: the symbol we read is
  `androidx.compose.material3.LocalTextStyle` (`RcPlayerTextLayout.kt:21`), foundation has no
  ambient text style, and `BasicText`'s `style` parameter defaults to `TextStyle.Default`. So this
  option keeps `implementation(libs.compose.material3)` (`build.gradle.kts:137`) and differs from
  today only in which composable draws;
- give the player its own style input — a composition local owned by this module, which the host
  seeds from whatever it uses — which is the only option that actually drops material3 while
  keeping ambient-style behaviour; or
- keep material3 `Text` as a local delta (a bigger, more annoying divergence); or
- accept losing the ambient style, which is upstream's behaviour and re-opens #3667.

Either way this is **rendering-affecting on every document with text** — it needs an `rc-compare`
render and before/after evidence, not a compile.

### 3.3 Adopt the `OffsetModifier` density fix

Straight pickup from upstream; re-render the density fixtures (`docs/design/evidence/rc-density-behavior`,
`rc-density-corners`) since it changes non-DP documents.

### 3.4 Retire four deltas that upstream has adopted

The frame loop, `autoUpdate`, the gradient bound-colour resolution, and the `DrawContent` single
execution. Delete them from `PROVENANCE.md` and keep every test and fixture that pins the
behaviour — `RcIdleProbeTest`, and the `rc-gradient-bound-color` / `rc-outlined-card-double-draw`
evidence — which stay meaningful regardless of who owns the fix. The two rendering ones in
particular are worth *re-verifying* after the refresh rather than assumed: the gradient path is
byte-identical to ours, but upstream's `DrawContent` placement is position-in-the-wire-list where
`RcPlayerDispatch` simply dropped the duplicate, so re-render `rc-outlined-card` and confirm the
chrome is still drawn once, in the right place.

### 3.5 Version skew: two upstream additions do **not** compile against alpha17

Verified directly against the published artifacts:

| Symbol | In `1.0.0-alpha17`? | Consequence |
| --- | --- | --- |
| `androidx.compose.remote.core.Limiter` | **No** (only `Limits`) | Upstream's frame-pacing block cannot be taken |
| `RemoteComposePlayerFlags.isEmbeddedPlayerEnabled` | **No** (alpha17 has only `shouldPlayerWrapContentSize`) | Upstream's new `check(…)` gate cannot be taken |
| `androidx.compose.remote.core.MatrixAccess` | Yes | fine |
| `AndroidRemoteContext.getTypefaceResolver()` | Yes | fine |

So the refresh carries **two new build-gap deltas** (drop the calls, record them in
`PROVENANCE.md` under the existing "cannot reach a symbol upstream compiles against in-tree"
pattern) — *or* the refresh waits for the next alpha and takes them wholesale, which is **not** a
free option: bumping the pin past alpha17 collides the artifact's `…/embedded/` classes with our
vendored sources, so it is the same change as migrating off them (§4). Note the frame
`Limiter` is worth thinking about beyond compilation: a `delay()` inside the frame loop is a new
timing input to our hermetic Robolectric renders, and should be re-verified by md5 across the
`remote-m3` lane the way the `withInfiniteAnimationFrameMillis` change was.

If the gate *is* taken later, everything that reaches `RcPlayer` must set
`RemoteComposePlayerFlags.isEmbeddedPlayerEnabled = true` or it throws at composition. The
production callers are the two daemon paths — `RemoteOverridablePreview.kt:178` and
`RemoteComposeIrReplay.kt:53`, both via the `ExperimentalRemoteDocumentPlayer` wrapper — plus this
module's own harnesses (`RcEmbeddedRenderHarness`, `RcSemanticsExtractionTest`, `RcIdleProbeTest`,
`RcFigmaSvgExportTest`) and the two tests that call `RcPlayer` directly. Two things that look like
call sites are not: `RcJvmRenderer` composes its own `RcPlayerJvm`, and `RcPlayerBackend` is an
enum naming the lanes.

### 3.6 Reshape the Google-font certificate delta onto `HasFontCerts`

Upstream's default resolver no longer resolves `google:` at all, and `resolveFontFamily` no-ops the
downloadable path when `fontCertsResId == 0` — which is what an out-of-tree consumer gets unless it
supplies a resource id. So the *need* for our delta survives the refresh, but its shape should
change: either implement `HasFontCerts` (which re-introduces the resource table this module
deliberately does not have — see `PROVENANCE.md`, "Not a source delta"), or keep passing
`List<List<ByteArray>>` and accept a narrower patch against upstream's new structure.

The upside: upstream's new `typefaceResolver` parameter is a *supported injection point* for a
host-supplied resolver. On the Android lane it can replace patching `EmbeddedPlayerTypefaceResolver`
in place. It does not help the jvm lane, whose text seam
(`RcPlayerTextLayoutJvm` / `RcPlayerTextPlatformJvm`) reaches
`GoogleFontTypefaceResolver.Default` directly rather than going through
`androidx…platform.TypefaceResolver` — closing that is the follow-up the
[typefaces audit](RC_PLAYER_TYPEFACES.md) already tracks.

### 3.7 Re-apply the seams — expect real conflicts in three files

**Nine** of the 11 added files are splits or ports with an upstream body to re-extract. The other
two are handwritten and have no upstream counterpart at all, so they are retained (or replaced)
rather than re-extracted — and they are the two most easily lost in a mechanical refresh, because
nothing in a `diff -r` points at them:

- **`RemoteImageSupport.kt`** — retain. Without it, a document carrying a URL- or file-encoded
  bitmap throws inside `inflateFromBuffer` and fails to parse *entirely*. Re-check its call sites
  after the refresh too: it has to run before the bytes are parsed, and two of the parse sites are
  constructors.
- **`GmsFontProviderCertificates.kt`** — retain or replace, per §3.6; upstream's new
  `HasFontCerts`/`fontCertsResId` route changes the shape but not the need.

Of the nine, three landing zones moved under them:

- **`RcPlayerDispatch.kt`** — our extraction of the component dispatch collides head-on with
  upstream's `RcPlayerComponent` rewrite (the `drawWithContent` block is gone from there and now
  lives in `toModifier`). Re-do the split against the new body; do not port the old one.
- **`GraphContext.kt`** (our `StoreBackedRemoteContext` reparent) — upstream changed the
  constructor to `IntObjectMap<Operation>` and re-flowed the KDoc. Small, but it touches the one
  class where "the bodies are upstream's, not ours" is load-bearing: re-port
  `StoreBackedRemoteContext` from the **new** `AndroidRemoteContext` if that class changed too.
- **`RcPlayerPaint.kt` / `RcPlayerDrawing.kt`** — upstream re-flowed both (imports, `fastForEach`),
  so our seam subtractions will not apply as patches. Re-extract rather than merge. Note
  `RcPlayerPaint.kt` is **not** a pure seam delta: it also carries the gradient bound-colour fix
  (§3.4), which upstream has adopted — so re-extracting the seam there means keeping upstream's
  `GRADIENT` body, not ours.

### 3.8 Keep our state/expression fixes and report them upstream

`isFloatOverridden`, the animated-expression-chain routing, and `ID_CONTINUOUS_SEC` in
`rememberRemoteFloatAsState` have **no** upstream counterpart (upstream added `ID_CONTINUOUS_SEC`
only to `RcPlayer`'s time-dependence *scan*, at `RcPlayer.kt:315`, not to the value resolver). Same
for the six `CoreText` reflection fields and everything they feed. All re-apply. The first three are
filing candidates and appear in the consolidated migration gate in §4; the `CoreText` fields are a
feature addition rather than a fix and do not gate anything.

### 3.9 Watch the reflection surface

`CoreDataAccessors.kt` drifted +290 −655 with the reflection call count unchanged at 234 — it was
reformatted, not re-implemented. But now that the player lives *inside* the same library group as
`remote-core`, upstream has every incentive to replace `getDeclaredField` with direct access, which
would delete the file we carry a +36 delta on. Upstream guards these names with its own
`CoreReflectionGuardTest`; re-check it on each refresh, and keep the refresh paired with an
`rc-compare` render rather than a compile (as `PROVENANCE.md` already says).

---

## 4. The strategic call

The refresh cost above is real (a rendering-affecting text change, a three-way clip merge, 11 files
of seams to re-extract, two new build gaps). Weigh it against the fact that the reason for
vendoring is expiring:

- **Do not refresh onto `androidx-main` right now** unless something specific is needed from it.
  The tree is mid-promotion — API files unregenerated, and two of its new code paths cannot be
  built against the alphas we pin.
- **The `compose-remote` pin cannot move past alpha17 on its own.** This is a hard constraint, not
  a preference. `third_party/rc-embedded-player/build.gradle.kts:125` declares
  `implementation(libs.compose.remote.player.compose)` — and the module needs it, for
  `ExperimentalRemotePlayerApi`, `utils.getPath` and `utils.getTweenPath` (plus `RemoteDocumentPlayer`
  in the comparison harness), none of which live anywhere else. The moment that artifact starts
  shipping `…/embedded/`, the AAR and our vendored sources define **the same fully-qualified
  classes** on one classpath. So the alpha bump and the source→artifact transition have to be a
  single change; there is no intermediate state where we take the new alpha *and* keep the patched
  sources. (Renaming the vendored package would also break the collision, at the cost of the
  verbatim-snapshot property that makes a refresh a plain `diff -r`. Not worth it.)
- **Migrating the Android lane to the artifact is gated on eight items.** A binary
  cannot be patched, and keeping the patches in the jvm snapshot does nothing for Android — so
  switching while any of these is still ours regresses the Android lane to upstream's pixels. The
  first six must land upstream; they are also the filing list (§3.1, §3.2, §3.8):
  1. rounded-clip radius normalisation (§3.1)
  2. rounded-clip ordering vs. `DrawContent` (§3.1)
  3. the ambient-`LocalTextStyle` text behaviour (§3.2) — *the one most easily forgotten, because
     upstream's `BasicText` rewrite looks like a refactor rather than a behaviour change*
  4. host-override precedence, `isFloatOverridden` (§3.8)
  5. animated-expression-chain routing (§3.8)
  6. `ID_CONTINUOUS_SEC` in the float resolver (§3.8)

  Two further gate items are **our** work rather than upstream's, and are just as blocking:

  7. **A host-supplied `typefaceResolver`.** Upstream's default resolver no longer handles
     `google:` at all and `resolveFontFamily` no-ops the downloadable path at `fontCertsResId == 0`
     (§3.6). The patched resolver cannot ride along in a binary, so without wiring one through the
     new `typefaceResolver` parameter, every downloadable-font document silently falls back to
     Roboto after the switch — a regression that renders *successfully* and so will not announce
     itself.
  8. **`RemoteImageSupport`'s parse flags** must still be set by the host before parsing (§3.7);
     they live outside the player, so this survives the migration but must be re-verified at the
     new call sites.

  The six `CoreText` reflection fields are a *feature* addition rather than a fix; they can ride
  along or stay ours, and they do not gate the migration.
- **The jvm/CMP lane still needs sources**, so `rc-embedded-player-jvm` keeps a vendored snapshot
  alive regardless — the seams cannot be applied to a binary. A plausible end state is: Android lane
  on the artifact, jvm lane on a snapshot carrying only the seams.
- **File the six upstream ones now**, so that half of the gate opens by itself rather than on our
  schedule. Items 7 and 8 are ours whenever we choose to do them.

---

## Reproducing this comparison

```sh
# Absolute path to *this* repo, captured before we cd anywhere else.
V="$PWD/third_party/rc-embedded-player/src/main/kotlin/androidx/compose/remote/player/compose/embedded"

# Blobless sparse clone — the full androidx checkout is not needed.
GIT_LFS_SKIP_SMUDGE=1 git clone --filter=blob:none --sparse --depth 1 \
  https://github.com/androidx/androidx /tmp/androidx
cd /tmp/androidx && git sparse-checkout set compose/remote

OLD=compose/remote/integration-tests/player-compose-embedded/src/main/java/androidx/compose/remote/player/compose/embedded
NEW=compose/remote/remote-player-compose/src/main/java/androidx/compose/remote/player/compose/embedded
PIN=c8e7d738d7c76df3a87281ba8c3b880622df6282   # PROVENANCE.md's pinned commit
# The upstream head this audit's counts and conclusions describe. Substitute a newer revision
# deliberately (and expect different numbers) — do NOT leave this as a bare `HEAD`.
UP=2f88db18a19ffcb109f359511edbee9117a46f57

# Recreate from scratch: tar overwrites matching paths but never deletes stale ones, so a
# second run against different commits would otherwise inflate the file counts.
rm -rf /tmp/rc && mkdir -p /tmp/rc/base /tmp/rc/head
git archive $PIN $OLD | tar -x -C /tmp/rc/base --strip-components=13
git archive $UP  $NEW | tar -x -C /tmp/rc/head --strip-components=12
rm -rf /tmp/rc/base/demos /tmp/rc/base/integration   # not vendored

diff -r /tmp/rc/base /tmp/rc/head     # upstream drift since the pin
diff -r /tmp/rc/base "$V"             # our local deltas
diff -r "$V" /tmp/rc/head             # the merge we would actually have to do
```

The pinned commit resolves in a `--depth 1` clone because a partial clone fetches missing objects
on demand; `git log`-based archaeology does **not** work there (path history folds into the shallow
boundary), so the individual upstream commits behind these changes are not resolvable without a
deeper fetch. Everything above is stated from tree state, not commit messages.

Artifact-level claims were checked directly:

```sh
curl -sO https://dl.google.com/dl/android/maven2/androidx/compose/remote/remote-player-compose/1.0.0-alpha17/remote-player-compose-1.0.0-alpha17.aar
unzip -p remote-player-compose-1.0.0-alpha17.aar classes.jar > classes.jar
unzip -l classes.jar | grep -c embedded    # -> 0
```
