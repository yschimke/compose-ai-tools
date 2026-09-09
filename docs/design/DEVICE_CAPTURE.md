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
| `compose/semantics` | per-`SemanticsNode` `testTag`, `role`, `mergeMode`, `boundsInScreen` | `:data-layoutinspector-core` (contracts) |
| `layout/inspector` | parent/child, `measuredSize`, `constraints`, resolved modifiers with values, `sourceRef` | `:data-layoutinspector-core` (contracts) |
| `a11y/hierarchy` | `label`, `role`, `states`, `merged`, bounds | `:data-a11y-core` |
| `uia/hierarchy` | selector inputs and supported `uia.*` actions per actionable node | `:data-uiautomator-core` |

And the primitive they ride is transport-neutral by construction: a data product is
`(kind, schemaVersion, payload)` with an inline / `path` / `bytes` transport
([`docs/daemon/DATA-PRODUCTS.md`](../daemon/DATA-PRODUCTS.md)), written on disk as
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

So Tier 2 is not "write a capture pipeline". It is "obtain those four values in a real process, and
call the code that already exists".

---

## Tier 2 — an in-app producer

**For: an app you control, where a debug-only dependency is acceptable.** The high-fidelity route,
and the one worth building first.

### Shape

A new Android library, `:data-devicecapture-android`, taken as `debugImplementation` by the target
app, plus a `compose-preview capture` host command that drives it over `adb`.

```
app (debug) ── :data-devicecapture-android
                 │  finds RootForTest views in the resumed Activity
                 │  reads LocalInspectionTables for CompositionData
                 │  calls the existing extractors
                 └─ writes data/<captureId>/<kind>.json + <captureId>.png
                             │
                 adb pull ───┘
                             ↓
              compose-preview capture → the same on-disk layout the render lane writes
```

The capture id replaces the preview id and nothing else changes: every downstream consumer — MCP's
`get_preview_data`, the parity compare page, the reference store — reads the same files it already
reads.

### The four inputs, in a live process

| Input | Where it comes from |
| --- | --- |
| `SemanticsNode` root | `(view as RootForTest).semanticsOwner.rootSemanticsNode` / `unmergedRootSemanticsNode`, over the resumed activity's view tree. Both trees are worth capturing; `uia/hierarchy` already records which one it walked in its `merged` flag. |
| `List<CompositionData>` | `androidx.compose.runtime.tooling.LocalInspectionTables` — **and this is the one input the library cannot obtain on its own.** The composition local has to be *provided* around the content before composition, exactly as both render lanes do it today (`CompositionLocalProvider(LocalInspectionTables provides store)`, `daemon/desktop/…/RenderEngine.kt:3199`); a library handed an already-composed tree cannot retrofit it. So Tier 2 costs the app a one-line integration at its root content in the debug variant, or a reflective read of the composition of the sort the Studio inspector does. Take the one-line version: it is honest, it is stable, and "an app you control" is the tier's premise. Without it, `layout/inspector` degrades to bounds without source refs rather than failing — which is still better than `uia/hierarchy` alone, so the capture should proceed and say so. |
| `density`, `fontScale` | `LocalDensity` / resources configuration. |
| pixels | `PixelCopy` over the window, so the capture and the tree come from one frame. |

### What to reuse, and the one thing that needs moving

- **`uia/hierarchy`** — `:data-uiautomator-hierarchy-android` is published, is an `android.library`,
  and its extractor takes a `SemanticsNode`. Reusable as-is.
- **`a11y/hierarchy`** — `:data-a11y-hierarchy-android`, same story. `a11y/atf` additionally needs
  the ATF walk over a `View`, which a live app has more readily than a render does.
- **`layout/inspector` / `compose/semantics`** — `LayoutInspectorDataProducer` lives in
  `:data-layoutinspector-connector`. That module publishes (as a JAR, and an Android library can
  depend on a JVM jar transparently), so it is *reachable* — but it also drags FontBox/PDFBox and
  the whole Figma-SVG font-subsetting path, which is not weight to put in an app even in a debug
  variant. The producer should be lifted into a core module, or a slim
  `:data-layoutinspector-producer` split out beneath it. This is the only module surgery Tier 2
  needs.

Note also that [`docs/daemon/DATA-PRODUCTS.md`](../daemon/DATA-PRODUCTS.md) § "Module split" says
connectors are "Not published — internal to the daemon process", while every `data/*/connector`
build file applies `composeai.maven-publishing`. The doc is stale; whichever way that is resolved,
Tier 2 should not be the thing that depends on the answer.

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
- **A capture becomes a parity reference** — registered like any other `compose-preview-references/v1`
  entry, so a shipped screen and a catalog render can be compared with the machinery that exists.
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
2. **Multi-window.** Dialogs, popups and the IME are separate windows with separate roots. The
   preview lane has `shownDialogWindow()` for the Robolectric case; a device has the real thing, and
   the capture should say which window each tree came from.
3. **Where the consent prompt lives** — host command, on-device, or both.
4. **Whether `.li` import is worth its maintenance** once Tier 2 exists. Revisit after Tier 2 has
   been used in anger; the answer may be to delete it.
