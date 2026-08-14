# Embedded RC player: fixes to send upstream to AndroidX

A **work order for an agent working in an androidx checkout.** Each item is a standalone change
with its own landing site, rationale, and test — implement and submit them one at a time, in the
order given, and do not batch them into one CL.

Every fix here is one we already carry as a local delta in
`third_party/rc-embedded-player`, verified against real documents. The companion doc
[RC_EMBEDDED_PLAYER_UPSTREAM_SYNC.md](RC_EMBEDDED_PLAYER_UPSTREAM_SYNC.md) explains how they were
found and what our snapshot looks like; this one is the outbound half. Landing these is what lets
us eventually drop the vendored Android sources and consume the published artifact — see that doc's
§4 migration gate, which is literally this list.

---

## Ground rules

**Where the code lives.** As of `androidx-main` `2f88db18` the embedded player is
`compose/remote/remote-player-compose/src/main/java/androidx/compose/remote/player/compose/embedded/`.
It moved there recently from `compose/remote/integration-tests/player-compose-embedded/`, which
still holds **the tests** — so most fixes touch two modules:

| | |
| --- | --- |
| Sources | `compose/remote/remote-player-compose/src/main/java/…/player/compose/embedded/` |
| Unit tests | `compose/remote/integration-tests/player-compose-embedded/src/test/java/…/embedded/` |
| Instrumented / screenshot tests | `…/player-compose-embedded/src/androidTest/java/…/embedded/` |

**Build and test:**

```sh
./gradlew :compose:remote:remote-player-compose:compileDebugKotlin
./gradlew :compose:remote:integration-tests:player-compose-embedded:test
```

**Those two do not run the instrumented tests** — `compileDebugKotlin` only compiles production
code and `test` runs local unit tests. Item 1's regression test is a draw-order property that only
an on-device test can see, so it needs a connected device or emulator:

```sh
./gradlew :compose:remote:integration-tests:player-compose-embedded:connectedDebugAndroidTest
```

Put whichever command actually exercised your test in that CL's `Test:` trailer — for item 1 that
is the connected task, not `:test`. (The module declares `addGoldenImageAssets()`, so screenshot
cases follow androidx's golden-image workflow; regenerate and commit goldens as that flow requires.)

**How to submit.** `compose:remote` is **not** one of the library groups that accepts GitHub pull
requests (see the mirror's `CONTRIBUTING.md` — the list is Activity, AppCompat, Biometric,
Collection, Compose *Runtime*, Core, DataStore, Fragment, Lifecycle, Lint, Navigation, Paging, Room,
WorkManager). These go through **AOSP Gerrit**: sign the CLA at `cla.developers.google.com`, work in
a `repo`-managed `androidx-main` checkout, and push with
`git push origin HEAD:refs/for/androidx-main`. Match the trailer style already used in this area:

```
Fix <one-line summary>

<what changed and why, wrapped at 72>

Test: ./gradlew :compose:remote:integration-tests:player-compose-embedded:test
Bug: <id, if one is filed>
Change-Id: I…
```

**Definition of done for each item:** the change compiles, the new test fails without the fix and
passes with it, the existing suite is green, and the CL description explains the *failure mode* —
every one of these bugs renders successfully while producing the wrong pixels, so a reviewer who
only reads the diff will not see why it matters.

**A note on framing.** Several of these are best argued as **player parity**: the same document
rendered by `remote-player-view` (the Android `View` player) and by the embedded Compose player
should agree. Where that is the argument, it's stated — it is far more persuasive upstream than
"our renderer differs".

---

## 1. Rounded clip must wrap the component's `DrawContent` output

**Severity: high — visible on any component with a generated background and rounded corners.**

**Where:** `…/embedded/modifier/ClipModifier.kt`, `roundedClipRect`.

**Current upstream:**

```kotlin
internal fun Modifier.roundedClipRect(op: RoundedClipRectModifierOperation): Modifier {
    …
    return this.clip(RemoteRoundedClipShape(…))
}
```

**The bug:** remote-core applies a rounded clip to the component's **complete** paint output. In the
wire modifier list `DrawContentOperation` precedes `RoundedClipRect`, and `toModifier`
(`RcPlayerModifiers.kt`) now applies the `drawWithContent` at that op's position — so appending
Compose's `clip()` afterwards leaves the draw-content node *outside* the clip. A card's generated
background paints with square corners while its children are clipped correctly.

**The fix:** hoist the clip so it wraps what was accumulated before it.

```kotlin
val shape = RemoteRoundedClipShape(…)
// remote-core applies the rounded clip to the component's complete paint output; DrawContent
// precedes this op in the wire modifier list, so appending would leave that draw node unclipped.
return Modifier.clip(shape).then(this)
```

**Test:** an instrumented/screenshot case in `src/androidTest/.../RcPlayerPixelTest.kt` (or a new
one beside it) over a component carrying both a `DrawContent` background and a rounded clip. Give
the component's chrome and the surface behind it clearly different colours, then sample a pixel
just *outside* the corner arc but inside the component's bounding box:

- **correct:** that pixel shows the **surrounding surface** colour — the chrome was clipped away
- **the bug:** it shows the **component's chrome** colour, painted square past the arc

Get the direction right; asserted the other way round the test passes on the broken build and fails
on the fix. A unit test cannot see this at all — it is a draw-order property, not a geometry one
(item 2 is the geometry one, and `RemoteRoundedClipShapeTest` is where that belongs).

**Ours:** `third_party/rc-embedded-player/src/main/kotlin/…/modifier/ClipModifier.kt`.

---

## 2. Rounded clip radii need remote-core's proportional normalisation

**Severity: high — geometry disagrees with the View player whenever corners are large.**

**Where:** `…/embedded/modifier/ClipModifier.kt`, `RemoteRoundedClipShape.createOutline`.

**The bug:** remote-core paints its rounded rect through Android's `Path.addRoundRect`, which, when
adjacent corner radii sum to more than the side they share, **scales every radius down
proportionally** by the smallest such ratio. `createOutline` passes the resolved radii straight into
`RoundRect`, so a document with oversized corners renders differently in the two players.

**The fix:** compute the scale the framework would apply and multiply all four radii by it.

```kotlin
/** Matches the radius normalization performed by Android's Path.addRoundRect in remote-core. */
private fun roundedRectRadiusScale(
    size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float,
): Float {
    fun scaleFor(limit: Float, first: Float, second: Float): Float {
        val sum = first + second
        return if (sum > limit && sum != 0f) limit / sum else 1f
    }
    return min(
        min(scaleFor(size.width, topStart, topEnd), scaleFor(size.width, bottomStart, bottomEnd)),
        min(scaleFor(size.height, topStart, bottomStart), scaleFor(size.height, topEnd, bottomEnd)),
    )
}
```

…then `CornerRadius(topStartRadius * radiusScale)` and so on for all four.

**Test:** `…/src/test/java/…/modifier/RemoteRoundedClipShapeTest.kt` already covers NaN fallback and
density scaling in exactly this shape — add a case with radii summing past a side (e.g. 80 + 80 on a
100 px width) and assert both come back at 50, plus a case where the sum fits and nothing is scaled.

**Ours:** same file as item 1. Note our version also carries a `0f..1f` fraction heuristic that
upstream's `ClipCorner(value, literal)` split makes unnecessary — **do not port that part.**

---

## 3. A host float override must beat the id's authored expression

**Severity: high — host-driven state silently does nothing.**

**Where:** `…/embedded/SnapshotRemoteComposeState.kt` and `…/embedded/GraphContext.kt`.

**The bug:** when a host writes a value for an id that also has an authored expression (the
`StateUpdater` / `setFloat` path, and every action that writes state), the expression keeps winning
on the next evaluation, so the override appears to be ignored.

**The fix:** record that an override happened, and let the leaf read prefer it.

```kotlin
// SnapshotRemoteComposeState
private val overriddenFloats: SnapshotStateMap<Int, Boolean> = mutableStateMapOf()

override fun overrideFloat(id: Int, value: Float) {
    …
    overriddenFloats[id] = true
    …
}

/** Whether a host/action override should take precedence over the id's authored expression. */
fun isFloatOverridden(id: Int): Boolean = overriddenFloats[id] == true
```

```kotlin
// GraphContext.getFloat, before the computed-expression branch
realState.isFloatOverridden(id) -> super.getFloat(id)
```

**Known limitation — fix it as part of this item rather than inheriting it.** The `GraphContext`
check above only covers ids whose value *reaches* the graph. In `rememberRemoteFloatAsState` the
expression branches return earlier: an authored expression with `mFloatAnimation` goes straight to
`rememberAnimatedRemoteFloat`, and (with item 4) an animated *chain* goes to
`rememberRemoteExpression`. Neither consults the override, so a host write against an id whose
expression is animated is still ignored. **Our own delta has this hole** — it was found reviewing
this document, not in the field, so treat it as unverified in behaviour but plain in the code. The
override test should read the id's value *before* the expression branches, i.e. check
`isFloatOverridden` at the top of the resolver rather than only in the graph's leaf read.

**Test:** `…/src/test/java/…/RcPlayerExpressionTest.kt` — compose a document whose id carries an
expression, write an override through `StateUpdaterImpl`, assert the read returns the override.
Cover **both** shapes: a plain expression (passes with the `GraphContext` check alone) and an
animated one (needs the resolver-level check).

**Ours:** `SnapshotRemoteComposeState.kt`, `GraphContext.kt` — including the limitation above.

---

## 4. An expression whose *nested* input is animated must stay on the animated path

**Severity: high — animated Material 3 progress indicators render frozen.**

**Where:** `…/embedded/state/RcPlayerState.kt`, `rememberRemoteFloatAsState(id: Int)`.

**Current upstream:**

```kotlin
val expression = document.getFloatExpressionsReflection()[id]
if (expression != null && expression.mFloatAnimation != null) {
    return rememberAnimatedRemoteFloat(id)
}
// …falls through to the GraphContext evaluator
```

**The bug:** the animated check only looks at the expression *itself*. An outer expression that
merely *reads* an animated one has no `mFloatAnimation`, so it falls through to `GraphContext` —
which deliberately evaluates core `FloatExpression`s as pure **target** values. The animated child
is flattened to its endpoint and the whole chain stops moving.

**The fix:** if any transitive input is animated, keep the chain in the Compose-native evaluator.

```kotlin
if (expression != null) {
    if (expression.mFloatAnimation != null) return rememberAnimatedRemoteFloat(id)
    // GraphContext evaluates core FloatExpressions as pure target values, so routing an outer
    // expression through it would flatten an animated child.
    if (expressionDependsOnAnimation(document.getFloatExpressionsReflection(), id)) {
        return rememberRemoteExpression(id)
    }
}
```

`expressionDependsOnAnimation` walks the expression's NaN-encoded source operands, following id
references into other expressions, with a visited set for cycles.

**Test:** `RcPlayerExpressionTest.kt` — a two-level chain (animated inner, plain outer); advance the
test clock and assert the outer value changes.

**Ours:** `state/RcPlayerState.kt`.

---

## 5. `ID_CONTINUOUS_SEC` must resolve in the float resolver, not only in the frame-loop scan

**Severity: medium — a documented time variable never ticks.**

**Where:** `…/embedded/state/RcPlayerState.kt`, `rememberRemoteFloatAsState(id: Int)`.

**The bug:** `RcPlayer.kt` already lists `RemoteContext.ID_CONTINUOUS_SEC` among the time ids that
keep the frame loop alive, but the resolver's time branch handles only `ID_TIME_IN_SEC`,
`ID_TIME_IN_MIN` and `ID_TIME_IN_HR`. So the loop runs for such a document and the value still never
updates — the worst combination, since it also burns frames.

**The fix:** add it to the guard and the `when` — but **derive its value from the document clock,
not by aliasing `ID_TIME_IN_SEC`.** The two ids are distinct: in the remote-core mirror
(`third_party/remote-compose-player/src/core/RemoteClock.ts:59,61`) continuous seconds is
`minute * 60 + second + millisOfSecond * 1e-3` — wall-clock position within the hour, *with* the
sub-second fraction — while `getTimeInSec()` is the integral `minute * 60 + second`. A document
using continuous seconds as a smooth phase input (sweep hands, continuous rotations) needs that
fraction; the integral sibling makes it step.

**Our own delta gets this wrong** — `state/RcPlayerState.kt:133` and `GraphContext.kt:135` both
alias it to `timeMillis / 1000f` alongside `ID_TIME_IN_SEC`. Do not copy ours here; it advances,
which is why the aliasing survived. Take the clock snapshot instead.

**Test:** `RcPlayerExpressionTest.kt`. Assert the **value**, not merely that it moves: pin the clock
to a known instant and check the resolved value carries the expected within-hour phase and
fractional part, and that it differs from `ID_TIME_IN_SEC` at the same instant. An "it advances"
assertion passes on the aliased implementation and so cannot detect this.

**Ours:** `state/RcPlayerState.kt`, `GraphContext.kt` — both carrying the aliasing bug described
above, which this item should fix rather than propagate.

---

## 6. Honour the `CoreText` properties the View player already implements

**Severity: medium — a parity gap; several authored text properties are silently dropped.**

**Where:** `…/embedded/CoreDataAccessors.kt` (`CoreText.readDataReflection`),
`…/embedded/CoreDataModel.kt` (`CoreTextData`), `…/embedded/RcPlayerTextLayout.kt`.

**The bug:** `CoreTextData` carries 15 fields; `CoreText` has more. The embedded player therefore
ignores properties the Java/View player honours, so the same document renders differently:

| Property | Field | Effect when dropped |
| --- | --- | --- |
| Autosize | `mAutosize`, `mMinFontSize`, `mMaxFontSize` | Text that should shrink to fit overflows or clips |
| Justification | `mJustificationMode` | Justified text renders start-aligned |
| Line break strategy | `mLineBreakStrategy` | Different wrapping from the View player |
| Hyphenation | `mHyphenationFrequency` | No hyphenation |

**The fix:** read the four (six with the autosize bounds) through the same reflection pattern the
file already uses, add them to `CoreTextData`, and apply them in `RcPlayerText`:
`TextAutoSize.StepBased(min, max, 0.5f)` for autosize; `LineBreak.Paragraph`/`Heading` for the
strategy; `Hyphens.Auto` when the frequency is non-zero.

**Two traps worth carrying into the CL description**, both of which cost us a debugging round:

- **Justification is not the align enum.** AndroidX's Java maps `TEXT_ALIGN_JUSTIFY` to
  `ALIGN_NORMAL`; real justification is the separate `mJustificationMode` property. Mapping
  `TEXT_ALIGN_JUSTIFY → TextAlign.Justify` looks right and is wrong.
- **Set only what the document sets.** Building a fresh `TextStyle` — in particular pinning
  `LineBreak.Simple` for the default strategy instead of leaving it `Unspecified` — re-measures
  *every* string in *every* document, not just the ones using these properties. It moved one of our
  fixtures by 3 px.

Two smaller siblings in the same area, worth folding in:

- `OVERFLOW_START_ELLIPSIS` / `OVERFLOW_MIDDLE_ELLIPSIS` map to `TextOverflow.Clip` today;
  Compose has `StartEllipsis` / `MiddleEllipsis`.
- `maxLines` is passed straight through, but `AndroidPaintContext`'s `StaticLayout` applies a
  different cap for some overflow modes — so the two players disagree on line count.

**Test:** `…/src/test/java/…/RcPlayerPrimitivesTest.kt` or a new `RcPlayerTextPropertiesTest`;
ideally a screenshot case against the View player's output for the same document.

**Ours:** `CoreDataAccessors.kt`, `CoreDataModel.kt`, `RcPlayerTextLayout.kt`.

---

## 7. Decide what a host text style should mean to `BasicText`

**Severity: medium. Propose and discuss — this one is a design question, not a defect report.**

**Where:** `…/embedded/RcPlayerTextLayout.kt`.

Upstream recently moved from material3 `Text` to foundation `BasicText`, constructing a fresh
`TextStyle` per call. That drops the ambient text style entirely: a player embedded in a themed
Compose tree no longer picks up anything from it.

Our position, from having shipped both: *unset document properties should stay `Unspecified` rather
than inheriting host values that would change measurement, while the remaining properties ride the
ambient style.* Inheriting a host's `LineBreak.Heading` — Material 3 sets one per type role —
re-measures documents that say nothing about line breaking.

**Ask upstream which they intend**, and offer the player-owned alternative: a composition local
owned by the player that a host opts into, which gets the useful part without a material3
dependency. File as a bug/discussion first; do not send a CL cold.

---

## 8. Make GMS font certificates reachable for out-of-tree consumers

**Severity: medium — downloadable fonts cannot work outside the androidx build.**

**Where:** `…/embedded/GmsFontTypefaceResolver.kt`, `…/embedded/RcPlayerTextLayout.kt`
(`resolveFontFamily`).

**The bug:** the `google:` font path needs the GMS certificate array, taken as an
`@ArrayRes` id (`HasFontCerts.fontCertsResId`, defaulting to 0 — and `resolveFontFamily` skips the
downloadable path entirely at 0). That array lives only in
`compose/ui/ui-text-google-fonts/src/androidTest/res/values/font_certs.xml`: the **published**
`androidx.compose.ui:ui-text-google-fonts` AAR ships an empty `<resources/>` and a zero-byte
`R.txt`. So no out-of-tree consumer can supply the id without hand-copying the certificates into
its own resource table.

**The fix — either, in preference order:**

1. Ship the certificates array in the published `ui-text-google-fonts` artifact. This is the real
   fix and helps every consumer following the documented downloadable-fonts pattern, not just this
   player.
2. Failing that, let `GmsFontTypefaceResolver` and `resolveFontFamily` accept certificates as
   values — `List<List<ByteArray>>`, which both `GoogleFont.Provider` and `FontRequest` already
   take beside the resource-id constructor — so a consumer can supply them without a resource table
   at all.

**Note this is arguably a `ui-text-google-fonts` bug rather than a player bug**; file it there, and
reference the player as the consumer that surfaced it.

**Ours:** `GmsFontProviderCertificates.kt` (option 2, with the certificates inlined as base64
generated from that same Apache-2.0 file).

---

## 9. A bad image reference should fail the image, not the document

**Severity: medium. Different module — `remote-core`, not the player.**

**Where:** `BitmapData.read` / `inflateFromBuffer` in `compose/remote/remote-core/`.

**The bug:** `Limits.ENABLE_IMAGE_URLS` and `ENABLE_IMAGE_FILES` ship `false`, and with them off
`BitmapData.read` **throws** for a `BitmapData` carrying `ENCODING_URL`. Because that read happens
inside `inflateFromBuffer`, the throw fails the **entire document** rather than the one image — a
player handed such a document produces nothing at all, with no partial render and no useful
diagnostic.

**The fix:** treat a disallowed or unresolvable image reference as an empty image slot — skip the
op, keep parsing, and surface it as a warning. Enabling the *parse* is not enabling a *fetch*: the
reference is only resolved if the host supplies a loader, which is exactly how the JS and CMP
players already behave with the same bytes.

**Test:** parse a document containing a URL-encoded bitmap with both flags `false`; assert the
document inflates and the other operations survive.

**Ours:** `RemoteImageSupport.kt` works around this by flipping both flags before parse — a
workaround for the throw, not a fix for the abort-everything behaviour.

---

## Do **not** send these upstream

- **The platform seams** (`RcPlayerTextPlatform`, `RcPlayerImagePlatform`, `RcPlayerShaders`,
  `RcPlayerTextPaintSpec`, `StoreBackedRemoteContext`, `RcImageSource`, `RcPlayerEasing`,
  `RcPlayerDispatch`, `state/RcPlayerBitmapState`). These serve our CMP jvm/desktop split. There is
  a real upstream conversation to have about decoupling the player from Android — upstream's own
  issue #12 is adjacent, and `StoreBackedRemoteContext` shows the split is close to mechanical
  (five of `AndroidRemoteContext`'s 63 methods touch the platform) — but that is a design
  discussion to open, not a CL to push.
- **Anything already converged.** Do not re-file the frame loop
  (`withInfiniteAnimationFrameMillis`), the removal of `autoUpdate`, the gradient bound-colour
  resolution, or the `DrawContent` double execution. Upstream has all four. Item 1 above is the
  *clip ordering*, which is a different bug in the same neighbourhood — keep them distinct in the
  CL description or a reviewer will close it as a duplicate.
- **Our `0f..1f` corner-fraction heuristic** (see item 2).

---

## Appendix: checking an item is still needed

Upstream moves; re-confirm before writing a CL. Against an `androidx-main` checkout:

**One command per item — never one command for two items, and never `\|` across the predicates of a
single item.** Each of these must be checked independently:

```sh
NEW=compose/remote/remote-player-compose/src/main/java/androidx/compose/remote/player/compose/embedded
G="git -C <androidx> grep -n"

$G "Modifier.clip(shape).then\|clip(shape).then(this)" -- $NEW/modifier/ClipModifier.kt  # item 1
$G "roundedRectRadiusScale"                            -- $NEW/modifier/ClipModifier.kt  # item 2
$G "isFloatOverridden"                                 -- $NEW                           # item 3
$G "expressionDependsOnAnimation"                      -- $NEW                           # item 4
$G "ID_CONTINUOUS_SEC"                     -- $NEW/state/RcPlayerState.kt                 # item 5

# item 6 is done only when EVERY one of these hits — check them one at a time, not as an alternation
for f in mAutosize mMinFontSize mMaxFontSize mJustificationMode mLineBreakStrategy \
         mHyphenationFrequency StartEllipsis MiddleEllipsis; do
  printf '%-24s ' "$f"; $G -c "$f" -- $NEW | head -1 || echo "MISSING"
done
```

A hit means upstream has taken that item and it can be dropped. **A partial hit means the item is
still open**, with the landed part removed from its scope: items 1 and 2 are independent fixes in
one file, and item 6 is eight separate behaviours — landing radius normalisation alone, or
`mAutosize` alone, says nothing about the rest. All of the above were absent at `2f88db18`
(2026-08-14).

Item 5's search is scoped to `state/RcPlayerState.kt` deliberately: `ID_CONTINUOUS_SEC` already
appears in `RcPlayer.kt`'s frame-loop scan and always will, so an unscoped grep answers "done" for a
fix that has not landed. That is the bug being fixed — the loop runs, the value doesn't move. And a
hit here is necessary but not sufficient: check that the value comes from the clock rather than
being aliased to `ID_TIME_IN_SEC` (see item 5).
