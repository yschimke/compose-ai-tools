# Portable preview bundles across build systems

> **Scope.** The non-Gradle *producers* (Amper, Bazel) and the scripting host
> live in [`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib),
> which is outside this repo and which I could not read directly. This document
> is grounded in what `compose-ai-tools` itself defines:
> - the format — `gradle-plugin/.../PreviewBundleFormat.kt`
> - the producer — `gradle-plugin/.../BundlePreviewTask.kt`
> - the players/readers — `:bundle-viewer` (`BundleLoader`), `:tui-cli`
>   (`BundleView`/`BundleDump`), `:cli` (`BundleCommand`, `BundleRenderer`,
>   `BundleDaemonCommand`)
> - the contrib contract — `contrib/README.md`, `docs/NON_GRADLE_INTEGRATION.md`
>
> Claims about contrib internals are marked **(contrib — verify)**.

## 1. Supported build systems

From `contrib/README.md` + CHANGELOG history, three build systems integrate with
the toolchain. The runtime tools are build-system-agnostic; only the *driver*
that discovers/renders/packs differs.

| Build system | Producer | Classpath assembly | Records Maven coords? |
|---|---|---|---|
| **Gradle** | this repo (`gradle-plugin`) | Gradle resolves `runtimeClasspath`; plugin maps each `ResolvedArtifactResult` → `maven:g:a:v:type` | **Yes** |
| **Amper** | contrib | Amper resolves the transitive runtime closure, passes it as `-cp` | **(contrib — verify)** |
| **Bazel** | contrib | `rules_jvm_external` resolves the closure, passes it as `-cp` | **(contrib — verify)** |

Two facts from `contrib/README.md` that shape everything below:

> The three CLI artifacts are published as **slim library JARs** — no fat/shadow
> JAR, no `Class-Path:` manifest entry. `java -jar …` does **not** work
> standalone. Bazel `rules_jvm_external` and Amper task definitions resolve the
> transitive runtime closure and pass it as `-cp`.

> The wire-stable contract (`daemon-launch.json` + `previews.json` schemas,
> classpath layering, system properties) is in `docs/NON_GRADLE_INTEGRATION.md`.

And CHANGELOG #1476: **"skip-render mode for non-Gradle build systems"** — a
non-Gradle driver can pack a bundle without the Gradle render pipeline.

### Why the bundle format leans Gradle-shaped

`BundleManifest.classpath` has three entry kinds (`PreviewBundleFormat.kt`):

```kotlin
ClasspathEntry.Module(path)                          // classes/app.jar — always inlined
ClasspathEntry.Maven(group, artifact, version, type) // RESOLVED BY THE PLAYER, from its repos
ClasspathEntry.Project(path, inlinedAs)              // libs/<name>.jar — inlined (no coordinate)
```

`Maven` is the portability hazard for non-Gradle flows:

- Bazel/Amper hand the producer a **flat list of jar files**. Recovering
  `group:artifact:version` per jar depends on the driver (Bazel knows
  `maven_install` coordinates; a vendored `//third_party` tree does not).
- Even with coordinates, the **player resolves them from the *consumer's* repos**
  at open time — which assumes the recipient has a Maven/Gradle setup *and*
  network access to the same repositories. A Bazel-only colleague has neither.

So today the format is portable for **Gradle → Gradle** hand-off (and for
view-only, below), but not for live re-render across build systems.

## 2. What already travels (two existing portability layers)

### 2a. View-only — universal, zero tooling

Schema v2 bakes a PNG per preview. Reading them needs **none** of the classpath
machinery — just:

```
bundle.json          → schemaVersion, previewIds, coverPreviewId (unknown keys ignored)
previews.json        → preview metadata (names, dimensions)
previews/<id>.png    → one rendered image per preview
```

Players on this read path:
- Any **PNG viewer** — the polyglot's leading bytes are the cover. Finder,
  Preview.app, GitHub, Slack show *something* with zero tooling.
- `:tui-cli` — `BundleView` (interactive paging) / `BundleDump` (ASCII/half-block
  to stdout, CI-friendly), opens a bundle from `~/Downloads` with no project.

So **the images are build-system-independent and self-contained already** —
whoever produced the bundle (Gradle, Amper, Bazel), the baked PNGs read the same.

### 2b. Live re-render — already works, *Compose-only*

This repo already ships a **player application**: `:bundle-viewer`
(`compose-preview-viewer`, a Compose Desktop `application` with a `distZip`/
`distTar` launcher). `BundleLoader.loadBundle` does the real thing:

```kotlin
// BundleLoader.kt — child loader over the inlined app.jar, parent = viewer's own Compose
val classLoader = URLClassLoader(arrayOf(appJarFile.toURI().toURL()), parentLoader)
... ownerClass.getDeclaredComposableMethod(info.functionName)  // invoked live in a Window
```

Critically, it loads **only `classes/app.jar`** and resolves every
`androidx.compose.*` symbol against the **viewer's own bundled Compose runtime**
(`compose.desktop.currentOs` + runtime/ui/foundation/material3 + uiToolingPreview,
see `bundle-viewer/build.gradle.kts`). It **does not read `classpath[]` / the
Maven coordinates at all.** Same trade-off the CLI states explicitly
(`BundleDaemonCommand`: *"v1 ignores them — the renderer's bundled Compose stack
supplies every API call… a full resolver pass is its own milestone"*).

**The exact gap.** This live path works for a bundle whose reachable code only
touches **Compose + the JDK**. The moment a preview pulls in a *third-party*
library that is (a) not part of the viewer's bundled Compose stack and (b) only
referenced by Maven coordinate (not inlined) → `NoClassDefFoundError`. Examples:
an icon pack, Coil, a charting lib, a `:design-system` project dep. That is the
precise case the rest of this plan closes — and it's *more* likely for Bazel/
Amper producers, which may not be able to emit resolvable coordinates at all.

## 3. Documenting non-Gradle bundle structure

A non-Gradle producer should emit the **identical zip layout** — only the
expression of `classpath[]` changes:

```
bundle.json          # manifest; classpath[] differs per producer/mode
previews.json
previews/<id>.png     # baked images — IDENTICAL across all build systems
classes/app.jar       # module bytecode, minimized (ClassGraph closure is build-system-agnostic:
                      #   it walks whatever class dirs + jars it's given — Bazel/Amper outputs work)
libs/<name>.jar       # inlined deps: today only project-style; in "embedded" mode, all reachable deps
report.json
```

The minimizer (`BundlePreviewTask.closureWalk`) already takes a flat
`scanPaths = classDirs + jars` — it has **no Gradle dependency**, so a contrib
producer can reuse the same reachability pruning over Bazel/Amper outputs.

### Proposed additive schema (v3) for cross-build-system portability

Today `ClasspathEntry` (in both `PreviewBundleFormat.kt` and the viewer's mirror
`Schema.kt`) has no "embedded third-party jar" kind — non-module deps are either
referenced by Maven coordinate or, for coordinate-less project deps, inlined as
`Project`. Add an explicit embedded kind:

```kotlin
// Existing — unchanged
ClasspathEntry.Module(path)
ClasspathEntry.Maven(group, artifact, version, type)
ClasspathEntry.Project(path, inlinedAs)

// NEW (v3): a third-party jar carried INSIDE the bundle — no coordinate, no resolution.
ClasspathEntry.Embedded(inlinedAs)        // e.g. "libs/androidx.compose.runtime-1.x.jar"
```

Plus two informational top-level fields so a player picks a strategy without
guessing:

```kotlin
BundleManifest(
  ...
  producer: String = "gradle",      // "gradle" | "amper" | "bazel"  — diagnostics
  resolution: String = "coordinates" // "coordinates" | "embedded" | "mixed"
)
```

- `coordinates` — today's Gradle default; player resolves Maven (small file).
- `embedded` — everything reachable is in `libs/`; zero external assumptions
  (Bazel/Amper, or any "make it truly portable" pack). Bigger file.
- `mixed` — embed what has no coordinate, reference the rest.

All additive: v2 readers (`ignoreUnknownKeys = true` in `BundleLoader` and
`BundlePngMetadata`) keep working against a v3 bundle, and a v3-aware player
treats a v2 bundle as `resolution = "coordinates"`.

## 4. Plan: any loader plays any bundle, portably

Goal: **hand a colleague one file; they see the previews — regardless of which
build system produced it or what they have installed.**

### Tier 0 — view-only, universal (already shipped)

Static viewing works today, everywhere, via the baked PNGs (§2a). **Action:**
document this as the baseline guarantee (this file) and point contrib producers
at the v2 `previews/<id>.png` layout so Amper/Bazel bundles are viewable with no
extra work.

> Note: live Compose-only re-render also already ships via `compose-preview-viewer`
> (§2b) — but it breaks on third-party deps, which is what Tier 1 fixes.

### Design principle — deps stay *detached*, embedding is the fallback

The load-bearing mechanism is **not** stuffing jars into the bundle. A bundle
should stay small and *reference* its third-party deps, leaving the bytes
detached; a player **re-attaches** them at play time from wherever they happen to
live — Maven Central, the colleague's local Gradle/Coursier cache, an internal
mirror, or a future content-addressable store. The source is interchangeable.

What makes that safe across interchangeable sources is **content addressing**:
schema v4 records a `sha256` on every `ClasspathEntry.Maven`, so a player resolves
the coordinate however it can and then verifies the fetched bytes hash to what the
bundle was built against before trusting them. Coordinate + hash = "find it
anywhere, but prove it's the right bytes."

Embedding (`--embed-deps` / `resolution = "embedded"`, `ClasspathEntry.Embedded`)
is therefore an **offline fallback**, not the default — for air-gapped hand-off or
non-Gradle producers that can't emit resolvable coordinates. Don't design players
to assume the jars are in the zip.

### Tier 1 — detached deps with verifiable hashes (the core)

Default `resolution = "coordinates"`, available to the Gradle plugin *and* contrib
producers:

1. Producer records each *reachable* third-party dep (ClassGraph closure, `kept`
   only) as a `ClasspathEntry.Maven` coordinate **plus its `sha256`** — no bytes in
   the bundle. *(Done for Gradle: schema v4.)*
2. Player resolves each coordinate from any source it has, verifies the bytes
   against `sha256`, then puts them on the classpath (Tier 3 is the resolver
   itself).
3. **`--embed-deps` remains** as the offline fallback: writes the reachable jars
   into `libs/` as `ClasspathEntry.Embedded` and a player builds its classpath
   straight from the zip. Trade-off: size (~100 KB → a few MB). Use it only when
   the recipient can't resolve coordinates.

### Tier 2 — teach the existing player to read `libs/`, ship it runnable

The player already exists (`compose-preview-viewer`, §2b) — the work is small:

1. **Add `libs/*.jar` to the child loader.** `BundleLoader` currently builds
   `URLClassLoader(arrayOf(appJarFile))`; extend it to also extract and append
   every `ClasspathEntry.Embedded` jar. ~10 lines; makes third-party deps resolve
   without touching the parent Compose stack.
2. **Distribute it runnable.** It ships as a `distZip`/`distTar` today (slim, needs
   the launcher script). For drag-around use, add a fat/`shadow` jar so
   `java -jar compose-preview-viewer.jar foo.png` works, and/or a
   `jpackage`/`jlink` native app per OS so a non-Java colleague needs nothing
   installed at all.

### Tier 3 — the coordinate resolver (the default play path, not a fallback)

Give the player an embedded resolver (e.g. **Coursier**) so `ClasspathEntry.Maven`
entries can be fetched without a Gradle install, then **check each fetched
artifact against its v4 `sha256`**. With this in place, detached coordinate
bundles — the small default — render anywhere with network access (a Bazel/Amper
user re-rendering a Gradle-produced bundle, a colleague who only has the `.png`).
This is the "resolve from any source, hash-check, layer on" pass; the hash
plumbing now exists (v4), so this tier is just the resolver + check, and it becomes
the *primary* path with embedding reserved for offline.

**Mismatch policy: warn, never fail.** A hash mismatch (or a missing hash) is a
loud warning — name the coordinate and expected-vs-actual hash — and the player
renders anyway with the resolved bytes. A different artifact for the same
coordinate is usually *almost* compatible (point-release skew, a
repackaged-but-equivalent jar), and a slightly-off preview beats no preview. The
hash is a fidelity signal, not a gate; there is no strict mode that refuses to
render.

### Sequencing

1. **Tier 0 docs** (this file) + wire contrib producers to `:bundle-viewer`. *Quick win, no format change.*
2. **Schema v3:** add `Embedded` + `producer`/`resolution` (additive; backward-compatible). Implement `--embed-deps` in the Gradle plugin first (reuses the closure walk), then mirror in contrib's Amper/Bazel producers.
3. **Fat player JAR** preferring `embedded`, falling back to Tier 3. *Highest-leverage portability item.*
4. **(Optional) jpackage** for non-developer recipients.

### Verification per tier

| Tier | Check |
|---|---|
| 0 | Open a v2 bundle in Preview.app / `tui-cli --dump` with no project (already in this repo's CI). |
| 1 | Pack with `--embed-deps`; `unzip -l` shows `libs/*.jar`; render on a box with **no Gradle/Maven and no network**. |
| 2 | `java -jar compose-preview-viewer.jar sample.png` on a clean JDK. |
| 3 | Open a `coordinates` bundle on a Bazel-only box; resolver fetches deps. |

## 5. Questions to confirm against `compose-ai-contrib`

1. Do the Amper/Bazel producers emit `bundle.json` with a real `classpath[]`, or
   rely on skip-render + baked PNGs only (Tier 0)?
2. Can the Bazel driver recover `maven_install` coordinates per jar, or only
   file paths/labels? (Decides whether `coordinates` mode is even possible there
   or `embedded` is mandatory.)
3. Does the contrib scripting/MCP host resolve coordinates, or assume a
   pre-resolved `-cp`? (Decides whether Tier 3 partly exists already.)
