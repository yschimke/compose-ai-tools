# Release trains

**Status: measurement + proposal.** The guard described in § 4 is implemented and running in
reporting mode; the train split in § 5 is not built. Issue
[#4772](https://github.com/yschimke/compose-ai-tools/issues/4772).

This repository publishes 94 Maven Central artifacts on a version line that cuts a release
roughly six times a day. Maven Central has begun metering exactly that shape of publishing.
This document measures what we actually ship, what fraction of it carries a change, and what
each available lever is worth — so the decision about which to pull is made against numbers
rather than impressions.

## 1. What we ship today

`release.yml`'s `publish-gradle-plugin` runs one root-level task:

```
./gradlew :gradle-plugin:publishAndReleaseToMavenCentral publishAndReleaseToMavenCentral
```

The unqualified task fans out to **every** subproject applying `composeai.maven-publishing` —
94 modules — with no reference to what changed. Every release publishes all of them.

Release cadence over the week to 2026-09-06 (v1.53.1 → v1.84.0): **44 releases in 7 days**,
~6.3/day, peaking at 11 on Aug 31 and 10 on Sep 5. Extrapolated, ~190 releases/month.

Maven Central meters [file count, release size and release count per organisation, on
three-month averages](https://central.sonatype.org/publish/maven-central-publishing-limits/).
At ~25 files per module (pom, jar, sources, javadoc, `.module`, each with `.asc` and
checksums), 94 modules is ~2,400 files per release and ~450,000 files/month. Release count and
file count are both in the range the limits are aimed at.

## 2. How much of it is a change

Measured across **v1.57.0..v1.84.0 — 38 consecutive-release windows**. A module counts as
changed when a file under it changed, excluding test sources (`src/test`, `src/functionalTest`
and neighbours), which are compiled by `check` and appear in neither the jar nor the sources
jar.

| | |
|---|---|
| Releases in the window | 38 |
| Module-publications performed | 38 × 94 = **3,572** |
| Module-changes carried | **117** |
| Signal ratio | **3.3%** |
| Releases changing no published module at all | **6** |

The median release changes 3 modules of 94. **96.7% of what we upload is a version-bumped
rebuild of byte-identical code.**

The releases are not idle — they are just not *library* releases. The week's changelog is
dominated by `screen:`, `builder:`, `design-artifacts:`, `cli:`, `serve:`, `mcp:` and `knobs:`
scopes, none of which ships to Central. `feat(screen): express a lambda that returns a value`
cut v1.84.0 and published 94 Maven artifacts.

### Reproducing these numbers

```sh
# per-release verdict, with the baseline advancing only when a publish happens
prev=""
for t in $(git tag | grep -E '^v1\.(5[7-9]|6[0-9]|7[0-9]|8[0-4])\.' | sort -V); do
  [ -z "$prev" ] && { prev=$t; continue; }
  out=$(.github/scripts/maven-publish-needed.sh --baseline "$prev" --head "$t" | head -1)
  echo "$t $out"
  [ "$out" = "needed=true" ] && prev=$t
done
```

The baseline advancing **only on a publish** is the load-bearing detail: consecutive skippable
releases coalesce into one span, rather than each being compared against its immediate
predecessor.

## 3. The levers, and which are in scope

| Lever | Effect | Status |
|---|---|---|
| Batch the release cut (release train for the *CLI* line) | 44/week → ~7/week; ~6× on every metric | **Out of scope.** The cadence is wanted. |
| Publish only when a published module changed | § 4 | **Implemented, reporting only** |
| Split the Maven publish into several trains | § 5 | Proposed |
| Version each module independently | Largest, but contradicts `VERSIONING.md` § 3.1 | **Out of scope** |

Batching is by far the biggest lever and it is deliberately not taken: the release frequency is
a product decision, not an accident. Everything below therefore reduces *artifacts per release*
rather than releases.

## 4. The guard: publish only when something changed

[`.github/scripts/maven-publish-needed.sh`](../../.github/scripts/maven-publish-needed.sh)
answers one question — *could any published artifact's bytes differ from the last
Maven-published release?* — and the `maven-publish-guard` job in `release.yml` reports the
answer on every release.

It watches every module applying `composeai.maven-publishing` (enumerated from the build files,
not a hand-kept list) plus the shared inputs that can change all of them: `build-logic/`, the
version catalog, the wrapper, `settings.gradle.kts`, the root `build.gradle.kts`, and
`gradle.properties` minus its release-please version block — that block moves
`composeaiReleasedRuntimeVersion` on every single release, so watching it naively would hold
the guard permanently open.

**It is all-or-nothing, and it fails open.** Publishing a subset would leave
`renderer-desktop:X` naming `data-focus-core:X` in its POM with no such artifact on Central, so
either all 94 publish at a version or none do. And every uncertainty — unresolvable baseline,
shallow clone, broken module enumeration — answers `needed=true`, because Central refuses to
accept a version twice: an unnecessary publish costs quota, a wrongly-skipped one is
unrepairable.

**Replayed over the same 38 windows: 32 publishes, 6 skips.**

| | Module-publications | vs today |
|---|---|---|
| Today | 3,572 | — |
| With the guard | 3,008 | **−15.8%** |

Six releases in a week that publish nothing is worth having, and it is the whole of what a
single train can give. The rest needs the split.

## 5. The train split

The 94 modules do not change at the same rate. Grouping them by the cadence they actually move
at, and replaying the same 38 windows per group (a group publishes when one of its own modules
changes, or when a shared build input does):

| Train | Modules | Publishes | Module-publications |
|---|---|---|---|
| **core** — `gradle-plugin*`, `renderers/*`, `render-host`, `render-matrix`, `render-session/*`, `daemon/*` | 17 | 30 / 38 | 510 |
| **data** — `data/*` | 58 | 19 / 38 | 1,102 |
| **runtimes** — `runtimes/*` | 10 | 16 / 38 | 160 |
| **api** — `api/*` | 4 | 18 / 38 | 72 |
| **misc** — `bundle/*`, `common/*`, `screen/model` | 5 | 19 / 38 | 95 |
| | **94** | | **1,939** |

| | Module-publications | vs today |
|---|---|---|
| Today | 3,572 | — |
| Guard, one train | 3,008 | −15.8% |
| Guard, five trains | **1,939** | **−45.7%** |

The shape of the win is `data/`: 58 modules — 62% of everything we publish — moving on half the
releases. It is the single largest block of artifacts and close to the slowest-changing.

Each train stays internally all-or-nothing, so no train ever publishes a POM naming a sibling
version that does not exist. This is **not** per-module versioning (`VERSIONING.md` § 3.1,
explicitly out of scope): it is five version lines instead of one, each still a lockstep set.

### Why a train can be skipped safely

The reason the guard is not already live. `resolvePluginVersion`
([`cli/.../VersionPin.kt:402`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/VersionPin.kt))
falls back to `BUNDLE_VERSION` — the CLI's own version — and the plugin then injects
`renderer-desktop:${PluginVersion.value}`, with the `data/` modules arriving as POM transitives
of that. Skip the publish at v1.85.0 today and every consumer gets
`Could not find ee.schimke.composeai:renderer-desktop:1.85.0`.

So a CLI release must name the Maven version it was built against, rather than assuming it is
its own. The repository already does exactly this twice, in the same file:
[`cli/build.gradle.kts:954`](../../cli/build.gradle.kts) bakes `serveVersion` and
`xrCompositeVersion` into `cli-version.properties` as catalog pins that "move on their own
cadence, NOT this CLI's version". A `pluginVersion` pin is the third instance of a shape already
in use — and `composeaiReleasedRuntimeVersion` in `gradle.properties` is a fourth, currently
bumped every release precisely because every release publishes everything.

The consumer-facing wrinkle is the version pin: `composePreview.version` names a *CLI* release,
and the running CLI cannot know another release's Maven version offline. Resolution: **each
GitHub Release carries the mapping** — extend the existing
`compose-preview-maven-ready-<version>.json` marker, which the release chain already produces
and the installer already gates on. The CLI uses its own baked value on the fast path and reads
the marker only when the pin names a different release.

## 6. What limits this further

The guard is conservative about shared build inputs, and that conservatism is most of the
remaining gap: **16 of 38 windows touch a shared input, 13 of them the version catalog.** A
Renovate bump of one dependency currently forces every train to publish, though it changes only
the POMs of modules that actually depend on the bumped coordinate.

Narrowing this means resolving, per module, whether a catalog entry reaches its POM — a Gradle
resolution rather than a path diff. It is the obvious next refinement and it is deliberately not
in the first version: a wrong answer there skips a publish that was needed, which is the one
failure this design does not tolerate.

## 7. Going live

1. **Report only** *(done)* — the `maven-publish-guard` job records its verdict on every release
   and gates nothing. Watch it against real releases; a wrong `false` here costs nothing.
2. **Bake the plugin-version pin** — `pluginVersion` into `cli-version.properties`, the
   `resolvePluginVersion` fallback reading it, and the CLI→Maven map on the release marker.
   Nothing skips yet, so the pin still equals the release version and the change is a no-op in
   production.
3. **Let the guard gate** — `publish-gradle-plugin` takes
   `if: needs.maven-publish-guard.outputs.needed == 'true'`. Step 2 is what makes this safe; the
   `-15.8%` arrives here.
4. **Split the trains** — five version lines, each with its own guard and manifest entry, and
   renderer POMs naming the library trains' own versions. The remaining `-30%`.

Steps 3 and 4 each need `required-release-assets.sh`, the readiness marker and
[`RELEASING.md`](../RELEASING.md) updated in the same change: a release that publishes nothing to
Central must not wait for a Maven-readiness marker that will never appear.
