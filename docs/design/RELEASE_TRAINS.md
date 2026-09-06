# Release trains

**Status: measurement + proposal.** The guard in § 4 is implemented and running in reporting mode.
The dependency lock state in § 6 is implemented and the guard now reads it. The train split in
§ 5 is not built, and § 6's graph-based measurement supersedes its grouping. Issue
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
| Publish only when a published module changed | § 4 | **Implemented and gating** |
| Split the Maven publish into several trains | § 5 | Proposed |
| Version each module independently | Largest, but contradicts `VERSIONING.md` § 3.1 | **Out of scope** |
| Narrow the shared-input rule with dependency lock state | § 6 — ~97% of the remaining cost | **Implemented** |

Batching is by far the biggest lever and it is deliberately not taken: the release frequency is
a product decision, not an accident. Everything below therefore reduces *artifacts per release*
rather than releases.

## 4. The guard: publish only when something changed

[`.github/scripts/maven-publish-needed.sh`](../../.github/scripts/maven-publish-needed.sh)
answers one question — *could any published artifact's bytes differ from the last
Maven-published release?* — and the `maven-publish-guard` job in `release.yml` gates
`publish-gradle-plugin` on the answer.

It watches every module applying `composeai.maven-publishing` (enumerated from the build files,
not a hand-kept list) plus the shared inputs that can change all of them: `build-logic/`, the
wrapper, `settings.gradle.kts`, the root `build.gradle.kts`, and `gradle.properties` minus its
release-please version block — that block moves `composeaiReleasedRuntimeVersion` on every
single release, so watching it naively would hold the guard permanently open. Dependency
movement arrives through each module's own committed `gradle.lockfile` rather than through the
version catalog, which is § 6.

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

> **These groups are drawn from directory paths, and the paths do not match the dependency graph.**
> `render-session` spans layers 0, 2, 3 and 5; `daemon` spans 0, 1 and 4; `data` spans 0, 1 and 2.
> § 6 measures the same question against the real graph and gets a better answer (−53.4%) than any
> path-based grouping, which makes this table a costing of one candidate rather than a
> recommendation. Restructuring the directories to match the layers would buy about three
> percentage points — see § 6's propagation numbers — and is not worth a 94-module reshuffle.

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

## 6. The shared-input rule is what limits this, and lockfiles are the fix

The guard is conservative about shared build inputs, and that conservatism is now essentially the
whole of the remaining cost:

| | Module-publications |
|---|---|
| 16 windows forcing all 94 via a shared build input | **1,504** |
| All other 22 windows **combined** | **50** |

In 9 of those 16, the *only* shared file touched was `gradle/libs.versions.toml`. One Renovate
dependency bump republishes all 94 modules, when it changes the POM of only those that actually
resolve the bumped coordinate.

### Why not just parse the catalog

The obvious fix is to read the catalog diff, resolve `[versions]` refs to aliases, find the modules
referencing those aliases, and take an upward closure. That is a static approximation of a question
Gradle can answer exactly, and every gap in the approximation fails in the direction that skips a
publish — the unrepairable one.

It would also have to model something alias-matching handles badly. Published POMs here carry
**declared** versions, not resolved ones (there is no `versionMapping`), so a bump to a purely
transitive dependency does not change a consumer's POM at all. But Kotlin inlines `inline` functions
from the compile classpath into the caller, so that consumer's **jar bytes** can still move. Getting
that right by grepping means a coarse closure over dependents; getting it right with a resolved
graph is free.

### Dependency lock state

`ComposeAiMavenPublishingPlugin` enables Gradle dependency locking on the configurations that decide
a published artifact — the JVM, Android `release` and KMP `jvm` compile/runtime classpaths, and
deliberately **not** the test classpaths, since a test-only bump cannot change published bytes.
Gradle records what each module actually resolved in a committed `gradle.lockfile`, and the guard
diffs that file instead of guessing from the catalog.

`LockMode.DEFAULT`, not `STRICT`: DEFAULT does not fail a locked configuration that has no lock
state, which is what makes the mechanism safe to land before a single lockfile exists.

**The guard now reads that lock state instead of the catalog.** `gradle/libs.versions.toml` is no
longer a blanket shared input: a module's `gradle.lockfile` lives inside the module directory, which
is already watched, so dependency movement is caught by the same rule that catches source changes. A
bump that moves nothing any published module resolves now correctly publishes nothing.

One hole had to be closed separately. The build **toolchain** — AGP, the Compose compiler, Kotlin —
lives in the catalog's `[plugins]` block, changes the bytecode we publish, and need not appear on any
module's classpath, so no lockfile moves when it does. `toolchain_fingerprint` compares that block
plus the `[versions]` entries those plugins reference. Over the same 38 windows the toolchain moved
exactly once (AGP 9.3.2 → 9.4.0 at v1.62.2 → v1.63.0) against 13 windows that touched the catalog —
so 12 of 13 catalog changes stop forcing a publish, and the one that must still force one, does.

Keeping the files current is [`dependency-locks.yml`](../../.github/workflows/dependency-locks.yml).
Renovate cannot do it — `postUpgradeTasks` needs a self-hosted Renovate with the command
allow-listed, and this repository uses the hosted Mend app — so a workflow regenerates and pushes
into the pull request branch. That workflow's header documents the constraint it is built around:
`platformAutomerge` is on, a `GITHUB_TOKEN` push starts no workflow runs, and so the job either
pushes with a real token or **fails**, never passes while the lock state is stale.

### Propagation, measured

The worry that a lower module's change would cascade upward and erase the saving does not hold. The
in-repo graph is shallow and wide — 39 of 94 modules depend on no other published module, and only
`daemon/core` has a large blast radius (47 modules, changed in 7 of 38 windows).

| Model | Module-publications | vs today |
|---|---|---|
| Per-module, **no** propagation | 1,554 | −56.5% |
| Per-module, **eager** propagation | 1,666 | **−53.4%** |

Propagation costs three percentage points. It is not the thing to design around; the shared-input
rule is.

### The number that frames all of this

**67 of the 94 modules changed in zero of the 38 windows** — 71% of the published surface, inert for
a week and republished 38 times each regardless. Over a longer window more of them would move, so
read it as "inert at this timescale", not "dead". It is the argument for not republishing unchanged
modules, and not an argument for merging them: of 21 `data/*` families with more than one published
module, exactly one always changed as a unit.

## 7. Going live

1. **Report only** *(done)* — the `maven-publish-guard` job records its verdict on every release
   and gates nothing. Watch it against real releases; a wrong `false` here costs nothing.
2. **Bake the plugin-version pin** *(done, inert)* — `generateCliVersionResource` writes a
   `mavenLineVersion` key into `cli-version.properties` from a `MAVEN_LINE_VERSION` environment
   override, defaulting to the CLI's own version; `MAVEN_LINE_VERSION` in `Version.kt` reads it;
   and every site that turns a version into a **Gradle coordinate** now reads that rather than
   `BUNDLE_VERSION` — the `resolvePluginVersion` fallback (so auto-inject and `init-script`
   follow), `Commands.injectedPluginVersion`, and doctor's `recommendedPluginVersion`,
   `pluginResolutionGuidance` input and `--plugin-version` snippet. Sites that state an
   **identity** or measure **skew** — `compose-preview --version`, the update check,
   `versionsIncompatible(applied, BUNDLE_VERSION)`, the build-host handshake — deliberately keep
   `BUNDLE_VERSION`: they are about which CLI this is, not about what Gradle can resolve.

   Nothing sets the override and every release still publishes, so today the two values are equal
   and this is a no-op in production. That is the point: the seam lands while it is inert, so
   step 3 is a workflow change rather than a redesign.

   Still outstanding, and only needed once the two versions actually diverge: the CLI→Maven map on
   the release readiness marker (`compose-preview-maven-ready-<version>.json`). A project pin names
   a *CLI* release, and a CLI cannot know another release's Maven line offline.
3. **Let the guard gate** *(done)* — `publish-gradle-plugin` takes
   `if: needs.maven-publish-guard.outputs.needed != 'false'`, and `build-applications` bakes
   `MAVEN_LINE_VERSION` from the guard's `maven_line` output: the new version when it publishes,
   the last version that reached Central when it does not. Step 2 is what makes this safe; the
   `-15.8%` arrives here.

   `!= 'false'` rather than `== 'true'` because the guard job is `continue-on-error`: a job that
   dies without writing an output must publish, not skip. Failing the job instead would strand
   the release as a draft over a guard with no opinion. The same asymmetry runs through
   `maven-publish-needed.sh`, which fails open on every uncertainty.

   Three things had to move with it.

   **The readiness gate had to learn which version to prove.**
   [`maven-readiness.yml`](../../.github/workflows/maven-readiness.yml) resolved the release's own
   version from Central and attached `compose-preview-maven-ready-<version>.json` on success. On a
   release that skips the publish that version never appears, so it would poll for 55 minutes and
   attach nothing — and since `resolve-version.py` gates `latest` on that marker, `latest` would
   freeze at the last publishing release and `compose-preview update` would stop offering
   anything. It now reads `mavenLineVersion` out of the CLI tarball already attached to the
   release and proves *that* resolvable, keeping the marker's name (the release's version, which
   is what consumers look up) and adding a `pluginVersion` field.

   Reading the shipped artifact rather than recomputing the verdict is deliberate. A second
   derivation, in a different workflow run, can disagree with what the release actually did —
   the guard job is `continue-on-error`, so a release can publish without ever writing a verdict
   — and disagreeing in the direction of "skipped" would attach a marker while the release's own
   artifacts were still propagating. That is precisely the #5051 failure this gate exists to
   prevent. The tarball is a required asset, verified present by `finalize-release` before this
   job runs, and it is the thing consumers actually execute.

   **`pin --cli` had to stop writing the CLI's own version.** A pin becomes
   `composePreview.version`, which every entrypoint hands to Gradle as a plugin coordinate, so on
   a skipped release `--cli` would have written a pin that resolves nothing. It writes
   `MAVEN_LINE_VERSION` now, as does the "re-pin with…" remedy in `warnOnCliSkew`. A version the
   user types is still written verbatim — that is what a pin is for — which leaves a
   hand-written pin as the one remaining way to name a version that was never published. See
   [`RELEASING.md`](../RELEASING.md).

   **The baseline resolver had to become shared.** `maven-publish-guard` runs before the publish
   and the readiness gate after it, in separate workflow runs, and Central's `<latest>` means
   different things at those two moments. Both now call
   [`maven-line-baseline.sh`](../../.github/scripts/maven-line-baseline.sh), which selects the
   greatest version **strictly below** the release under consideration — stable across the
   publish by construction. Pinned by `test-maven-line-baseline.sh` in CI.

   `required-release-assets.sh` needed **no** change, contrary to the note this section used to
   carry: it lists only CLI assets, which a skipped publish still produces, so `finalize-release`
   and the draft sweeper are unaffected.
4. **Split the trains** — five version lines, each with its own guard and manifest entry, and
   renderer POMs naming the library trains' own versions. The remaining `-30%`.

Step 4 still needs the readiness marker and [`RELEASING.md`](../RELEASING.md) revisited in the
same change: with five version lines there is no longer one Maven line for a release to name, and
the marker has to carry a version per train rather than the single `pluginVersion` it gained in
step 3.
