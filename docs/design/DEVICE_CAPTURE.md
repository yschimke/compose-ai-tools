# Capturing a screen from a running app

**Status: proposal (2026-09-09).** Two tiers of device capture — a Layout Inspector snapshot
importer (Tier 1) and an in-app producer (Tier 2) — that turn a screen in a *running* app into the
same data products the render lane already emits for a `@Preview`.

Nothing here is implemented. The point of the document is that most of the work is already done and
in the wrong place: the schemas, the extractors and the consumers all exist, and what is missing is
a source.

## The problem, in one sentence

Every data product this repository publishes is produced in-process from a preview render, and
`adb` appears nowhere in the tree.

`data/uiautomator`'s own reference page sells that as the feature — *"Generate a UIAutomator-like
end-to-end test plan for a screen without touching an emulator"* — and for its purpose it is one.
But it means there is no route from a screen a person is looking at on a device to any of the
formats the rest of the stack understands. A real app's Discover tab cannot become a parity
reference, a builder reference, or an `a11y/hierarchy` to diff a redesign against.

## What already exists, and why that is most of it

Four kinds already describe exactly what a captured screen would carry:

| Kind | Carries | Core module |
| --- | --- | --- |
| `compose/semantics` | per-`SemanticsNode` `testTag`, `role`, `mergeMode`, `boundsInRoot` | `:data-layoutinspector-core` (contracts) |
| `layout/inspector` | parent/child, `measuredSize`, `constraints`, resolved modifiers with values, `sourceRef` | `:data-layoutinspector-core` (contracts) |
| `a11y/hierarchy` | `label`, `role`, `states`, `merged`, bounds | `:data-a11y-core` |
| `uia/hierarchy` | selector inputs and supported `uia.*` actions per actionable node | `:data-uiautomator-core` |

And the primitive they ride is transport-neutral by construction: a data product is
`(kind, schemaVersion, payload)` with an inline / `path` / `bytes` transport
([`docs/daemon/DATA-PRODUCTS.md`](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/DATA-PRODUCTS.md)), written on disk as
`data/<id>/<kind-with-slashes-as-dashes>.json`. Nothing in that shape says the `<id>` has to be a
preview id.

**The load-bearing finding is that the producers are pure functions of a semantics tree.**
`UiAutomatorHierarchyExtractor.extract(root: SemanticsNode, …)` is documented as "pure data — no
platform side-effects". `LayoutInspectorExtension` needs exactly four things from its context:

```kotlin
LayoutInspectorDataProducer.writeArtifacts(
  rootDir, previewId,
  root = semanticsRoot,      // androidx.compose.ui.semantics.SemanticsNode
  slotTables = slotTables,   // List<CompositionData>
  density = density,
  fontScale = fontScale,
)
```

None of those four is a preview-only object. `SemanticsNode` is the same class in a running app as
under Robolectric, and both existing backends already reach it the way a live app would: the Android
daemon through `ViewRootForTest` (`daemon/android/…/RenderEngine.kt:1137`), Desktop through
`scene.semanticsOwners` — the `RootForTest` seam the layout-inspector page already names. A live
`Activity` has the same handle: walk the view tree, find the views implementing `RootForTest`, take
`semanticsOwner.rootSemanticsNode` (or `unmergedRootSemanticsNode`).

So the *extraction* is largely done. What is not done, and what a first draft of this document
wrongly waved away, is everything around it: a capture is not a render, and four of the assumptions
the render lane gets for free do not hold on a device. Those are collected in
[What a capture costs beyond the producers](#what-a-capture-costs-beyond-the-producers) rather than
scattered, because together they are most of the actual work.

---

## Tier 2 — an in-app producer

**For: an app you control, where a debug-only dependency is acceptable.** The high-fidelity route,
and the one worth building first.

### Shape

A new Android library, `:data-devicecapture-android`, taken as `debugImplementation` by the target
app, plus a `compose-preview capture` host command that drives it over `adb`.

```
compose-preview capture
      │  1. trigger ── adb shell am broadcast -p <package> …CAPTURE  (or an instrumentation run)
      ↓
app (debug) ── :data-devicecapture-android
                 │  finds RootForTest views in the resumed Activity
                 │  reads the provided inspection tables for CompositionData
                 │  calls the existing extractors
                 └─ writes data/<captureId>/<kind>.json + <captureId>.png, then a done marker
                             │
      2. adb exec-out run-as ┘
                             ↓
              3. promote into the durable store (see "a durable home" below)
```

**The trigger is part of the design, not an afterthought.** A first draft of this section described
only the transfer, which cannot make a capture happen — the files it pulls have to already exist.
The library needs an inbound edge, and the honest options are a `<receiver>` in the debug manifest
driven by `am broadcast -p <package>`, or an instrumentation entry point driven by `am instrument`.
Either way the protocol needs three things the transfer does not supply: a request carrying the
capture id and options, a **completion signal** so the host does not pull a half-written directory,
and an **error channel** for "no resumed activity", "no Compose root", "inspection tables absent".

A marker file the host polls for is the cheapest completion signal and needs no second channel —
**provided it is bound to the request**. A bare `done` marker left by an earlier capture under the
same id is already present when the new request starts rewriting that directory, so the host polls,
sees the stale marker immediately, and pulls a mixture of old and half-written products. Either
delete the marker before triggering and publish the finished directory atomically (write to a temp
directory, then rename), or carry a per-request nonce in the marker and wait for that generation.
The atomic-rename version is preferable: it removes the half-written window rather than just
detecting it.

**"The resumed Activity" is not a unique thing.** Android multi-resume means split-screen and
multi-display can leave several activities of one package resumed at once, so a library that takes
whichever it finds captures a screen chosen by lifecycle callback ordering. The request has to carry
a selector — an activity component name, a task id, a display id or a window token — and the library
has to **fail explicitly** when several candidates match and none was named, rather than picking. A
capture of the wrong screen is worse than no capture, because nothing downstream can tell.

The transfer is `adb exec-out run-as <package>`, not a plain `adb pull`: the capture lands in the
app's private files directory, which the shell user cannot traverse even for a debuggable app, and
writing a real screen's pixels and text to shared storage instead would give away the privacy
property below. The host command therefore has to know the package, not just a path.

**A capture id is a path segment before it is an identity, and must be validated as one.** The
reused producers build their output directory with `rootDir.resolve(previewId)`
(`ComposeSemanticsDataProduct.kt:196,1215`), so an id containing `..`, a separator, or an absolute
path escapes the capture directory or overwrites another product — on the device, during transfer,
and again during promotion. A preview id is a Kotlin FQN and safe by construction; a capture id is
whatever the caller passes, and is attacker-influenced as soon as anything but a person types it.
Fix the encoding rule (an opaque generated id, or a charset restriction plus a rejection on any
non-final path segment) before the URI question below, and validate on both sides rather than
trusting the device.

The capture id takes the preview id's place in the on-disk layout, so anything reading those files
by path works unchanged. **Discovery does not**: `resources/list` enumerates discovered `@Preview`s,
and MCP validates a URI against "a known workspace ID + module path + preview FQN"
([`docs/daemon/MCP.md`](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/MCP.md) § Security & trust model). A capture matches none of those,
so reaching one over MCP needs either a `compose-capture://` scheme beside the two that exist or a
registration path that makes a capture a first-class resource. Pick one before building the host
command, because it decides whether a capture id is free-form or has to be derivable.

### The four inputs, in a live process

| Input | Where it comes from |
| --- | --- |
| `SemanticsNode` root | `(view as RootForTest).semanticsOwner.rootSemanticsNode` / `unmergedRootSemanticsNode`, over the resumed activity's view tree. Both trees are worth capturing — but they cannot share an output: there is one `data/<captureId>/<kind>.json` per kind, an extractor emits one tree per payload, and `uia/hierarchy`'s `merged` marker is per *node*, so an empty node list cannot even say which tree produced it. Give them distinct product names (`uia/hierarchy` and an unmerged sibling), or a wrapper schema holding both; capturing the second over the first is silent data loss. |
| `List<CompositionData>` | `androidx.compose.runtime.tooling.LocalInspectionTables` — **and this is the one input the library cannot obtain on its own.** The composition local has to be *provided* around the content before composition, exactly as both render lanes do it today (`CompositionLocalProvider(LocalInspectionTables provides store)`, `daemon/desktop/…/RenderEngine.kt:3199`); a library handed an already-composed tree cannot retrofit it. And providing the local is not on its own enough: the render lane's own integration (`daemon/desktop/…/RenderEngine.kt:3190-3199`) calls `currentComposer.collectParameterInformation()` and adds `currentComposer.compositionData` to the store *before* installing the provider, under `@OptIn(InternalComposeApi::class)`. Without the first call there are no parameters and so no source refs; without the second the store can stay empty. So Tier 2 costs the app a three-line composable wrapper against an internal Compose API, or a reflective read of the composition of the sort the Studio inspector does. Take the wrapper: it is honest, it is what this repository already does twice, and "an app you control" is the tier's premise — but budget it as an internal-API dependency to re-check on Compose upgrades, not as a one-liner. Without it, `layout/inspector` degrades to bounds without source refs rather than failing — which is still better than `uia/hierarchy` alone, so the capture should proceed and say so. |
| `density`, `fontScale` | `LocalDensity` / resources configuration. |
| pixels | `PixelCopy` over the window — but see below: it does **not** by itself put the pixels and the trees on the same frame. |

### What to reuse, and the one thing that needs moving

- **`uia/hierarchy`** — `:data-uiautomator-hierarchy-android` is published, is an `android.library`,
  and its extractor takes a `SemanticsNode`. Reusable as-is.
- **`a11y/hierarchy`** — *not* the same story, and this is the second piece of module surgery.
  `AccessibilityHierarchyExtractor.extract(previewId, root: View)` takes a `View` and delegates to
  `AccessibilityChecker.analyze`, which runs the full ATF pass and returns hierarchy and findings
  together. The `View` signature is not the problem — a live app has a realer one than a render
  does. The dependency is: `:data-a11y-core` declares `implementation(libs.robolectric)`, because
  `AccessibilityChecker.analyze` swaps the `ShadowBuild` fingerprint to sidestep ATF's
  `Build.FINGERPRINT == "robolectric"` bail-out. Shipping that into a real app, even a debug
  variant, is not acceptable. Tier 2 needs the fingerprint workaround made conditional and the
  Robolectric dependency moved off the path an app compiles against.
- **`layout/inspector` / `compose/semantics`** — `LayoutInspectorDataProducer` lives in
  `:data-layoutinspector-connector`. That module publishes (as a JAR, and an Android library can
  depend on a JVM jar transparently), so it is *reachable* — but it also drags FontBox/PDFBox and
  the whole Figma-SVG font-subsetting path, which is not weight to put in an app even in a debug
  variant. **Both** producers need lifting, not one: `LayoutInspectorDataProducer` and
  `ComposeSemanticsDataProducer` are separate objects in that same module
  (`ComposeSemanticsDataProduct.kt:179,1119`), so moving only the first leaves `compose/semantics` —
  the other kind in this bullet's own heading — reachable only through the heavy connector. Lift
  both into a core module, or split a slim `:data-layoutinspector-producer` out beneath it.

A second documentation drift, found the same way and worth fixing wherever it is owned:
[`site/reference/layout-inspector.md:71`](../../site/reference/layout-inspector.md) shows
`compose/semantics` carrying `boundsInScreen`, while `schema/compose-semantics.schema.json` and
`ComposeSemanticsDataProduct.kt:255` both say `boundsInRoot`. A first draft of the table above
copied the reference page and inherited the error. It is the same failure as `uia/hierarchy`'s field
name: on a render the root and the screen coincide, so nothing catches it.

Note also that [`docs/daemon/DATA-PRODUCTS.md`](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/DATA-PRODUCTS.md) § "Module split" says
connectors are "Not published — internal to the daemon process", while every `data/*/connector`
build file applies `composeai.maven-publishing`. The doc is stale; whichever way that is resolved,
Tier 2 should not be the thing that depends on the answer.

### What a capture costs beyond the producers

Four assumptions the render lane gets for free, each of which fails on a device and none of which
the extractors solve.

**One frame.** `PixelCopy` asynchronously copies a queued window buffer while the composition is
free to advance on either side of it, so pixels and a separately-walked semantics tree can belong to
different frames. On an animated or streaming screen that pairs one frame's bounds with another
frame's pixels — silent, and exactly the kind of error a parity comparison would then attribute to a
renderer. The capture needs an explicit protocol: quiesce the frame clock, or take the tree and the
buffer inside one coordinated snapshot and record which frame each came from. Asserting one-frame
consistency without one, as a first draft of this document did, is wrong.

**One coordinate space.** There are **three** in play, and the render lane collapses them all
because there the Compose root, the window and the screen coincide. On a device they do not:

| Product | What its bounds actually are |
| --- | --- |
| `compose/semantics` | `boundsInRoot`, and honestly named |
| `uia/hierarchy` | `boundsInRoot`, in a field *named* `boundsInScreen` (`UiAutomatorHierarchyExtension.kt:104`) |
| `a11y/hierarchy` | ATF's `v.boundsInScreen` — genuinely absolute screen coordinates (`AccessibilityChecker.kt:91,150`) |
| the PNG | whatever window `PixelCopy` was given |

So a fix that only offsets the Compose-side products still leaves the a11y payload shifted on any
dialog, freeform or otherwise screen-offset window — and an a11y overlay is exactly where a shifted
box is most visible. The capture needs one declared target space (the window origin is the natural
choice, since that is what the pixels are), every product transformed into it, and the space
recorded in the output so a later consumer cannot guess wrong.

**One root.** A hybrid screen has several `ComposeView`s, so the library will find several
`RootForTest` views, while every producer takes one root and writes one file per kind per capture.
Taking the first root silently drops the rest of the screen; looping leaves only the last tree.
Needs a root-selection rule or a forest format, and it is a separate question from the multi-window
one below because both roots are in the *same* window.

**A durable home.** `build/compose-previews/data/<id>/` is documented as ephemeral and rewritten per
render ([`DATA-PRODUCTS.md`](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/DATA-PRODUCTS.md)), and a `gradle clean` takes the tree with
it. That is right for a render and wrong for a capture, whose whole purpose is to outlive the
session as a reference. The host command needs a promotion step into a durable store with its own
manifest; the build directory is a landing zone, not the destination.

### `testTagsAsResourceId`

A detail with no mention anywhere in this repository, and it decides whether half of `uia/hierarchy`
is populated on a real device: Compose `testTag`s are invisible to UIAutomator and to any
`AccessibilityNodeInfo` consumer unless the app opts in with
`Modifier.semantics { testTagsAsResourceId = true }`. Tier 2 reads the semantics tree directly and
so is unaffected — but a Tier 2 capture and a `uiautomator dump` of the same screen will disagree
about `testTag` for exactly this reason, and the capture should record whether the flag was set so
the difference is explained rather than discovered.

### What Tier 2 still cannot give

- **Layout intent.** `layout/inspector` reports that a node is a `Row` with a resolved
  `Arrangement`, which is far more than the a11y tree carries — but "why" remains unrecoverable, and
  a capture is still not a design document. See below.
- **`compose/recomposition`**, which needs the instrumented render mode.
- **Anything about frames other than the captured one.**

### Consent

A capture is a screenshot plus the text of a real screen, which can carry personal data in a way a
`@Preview` render never does. The capture path should be debug-variant only, should never be a
transitive dependency of a release build, and the host command should say what it is about to pull.
Worth settling before the first implementation, not after.

---

## Tier 1 — a Layout Inspector snapshot importer

**For: an app you cannot add a dependency to, but can run under Android Studio.** Strictly
second-class, and specified here as such.

Android Studio's Layout Inspector reaches the real Compose tree — composable names, parameters,
modifiers, bounds, recomposition counts — over app-inspection: `androidx.inspection` plus the view
and compose inspector JARs pushed to the device, against a debuggable app carrying `ui-tooling`.
There is **no supported CLI and no stable protocol**, so Tier 1 does not drive that channel. It
consumes the artefact instead: Studio's **exported snapshot** (`.li`), a zip of protobuf that a
person produces by hand and hands over.

`compose-preview capture import <file>.li` maps what the snapshot carries onto the same kinds:

| Snapshot content | Kind |
| --- | --- |
| Compose node tree with bounds and parameters | `layout/inspector` |
| Semantics on those nodes | `compose/semantics` |
| The captured screenshot layer | the reference PNG |

**The format is undocumented and unversioned as a public contract.** The importer must therefore be
version-guarded and fail closed — read the snapshot's own version stamp, refuse anything it does not
recognise, and never partially map a tree it half-understands into a file that claims to be
`layout/inspector`. A wrong tree in a known-good schema is worse than no tree, because everything
downstream will trust it.

Tier 1 earns its place on exactly one axis: **it is the only route that needs no change to the app
at all**, so it covers the app whose build you cannot touch and the screen a colleague can only send
you after the fact. It should be built after Tier 2 and share its whole output path, so that if the
`.li` format moves, only the importer breaks.

---

## Where a capture lands

A capture is a **reference**, not a design.
[`UI_BUILDER_REFERENCE_OVERLAY.md`](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_REFERENCE_OVERLAY.md)
draws that line and this document does not move it: the document holds nodes the catalog vouched
for, the reference holds pixels validated against nothing. That doc also already refuses the case a
device capture creates — promoting a piece with **no provenance** ("a screenshot region, a Figma
export") is refused by the reducer rather than guessed, and no button is offered.

So the honest scope is:

- **A capture becomes a reference layer** — pixels plus bounds, to build against. Available the day
  either tier lands.
- **A capture becomes a parity reference** — registered as a `compose-preview-references/v1` entry.
  The comparison machinery then works unchanged, but the registration is not free: a reference
  manifest maps its own id onto an **exact existing `previewId`**, and a capture has no preview to
  name. So a capture-backed reference needs an explicit capture → target-preview mapping, authored
  by whoever wants the comparison. That is the right place for it to live — "which preview is this
  screen supposed to match" is a judgement, not a derivation — but it has to exist.
- **A capture does *not* become a builder document.** Turning `layout/inspector` nodes into catalog
  component ids is a separate mapping problem with its own failure modes, and Tier 2's richer tree
  makes it *tractable* rather than solved. Specifying it is out of scope here.

## Layer placement

Both tiers are behaviour that opens no socket in the sense
[`REPOSITORY_LAYERS.md`](REPOSITORY_LAYERS.md) means — driving `adb` is a host tool, not a server —
so they are layer 1, in this repository, alongside the render lane whose output they mirror. The
payload schemas stay where they already are: `:data-*-core`, layer 0 for the two that live in
contracts.

| Module | Layer | Why |
| --- | --- | --- |
| `:data-devicecapture-android` | 1 | Android library the target app takes as `debugImplementation` |
| `compose-preview capture` (in `:cli`) | 1 | host side: adb, pull, write the on-disk layout |
| `:data-layoutinspector-producer` (split out) | 1 | so an app can produce `layout/inspector` without FontBox |

## Open questions

1. **One capture id or many?** A scrollable screen is several frames; the on-disk layout assumes one
   id per capture. Probably fine — a scroll capture is already its own kind.
2. **How a capture is addressed over MCP** — a `compose-capture://` scheme beside the two that
   exist, or registration into the resource list. Decides whether a capture id can be free-form.
3. **Multi-window.** Dialogs, popups and the IME are separate windows with separate roots. The
   preview lane has `shownDialogWindow()` for the Robolectric case; a device has the real thing, and
   the capture should say which window each tree came from.
4. **Where the consent prompt lives** — host command, on-device, or both.
5. **Whether `.li` import is worth its maintenance** once Tier 2 exists. Revisit after Tier 2 has
   been used in anger; the answer may be to delete it.
