# Releasing

## TL;DR

**Merge the open `chore(main): release X.Y.Z` PR.** That's it — `release-please.yml` creates the tag and a **draft** GitHub Release, chains into `release.yml` to build the CLI, VS Code extension, Gradle plugin, and Android renderer AAR onto that draft, then **finalizes** (un-drafts) it once every required asset is verified present. Because the Release stays a draft until it's fully populated, `/releases/latest` and the `latest/download/…` URLs never point at a version whose tarball hasn't uploaded yet — closing the "release not found" install window. You don't have to watch the logs after that: the chain [comments the two milestones back on the PR you merged](#milestones-are-posted-back-on-the-release-pr) — when `preview.coo.ee` is running the new version, and when the artifacts resolve from Maven Central.

## Prerequisites (one-time)

In **Settings → Actions → General → Workflow permissions**, tick **"Allow GitHub Actions to create and approve pull requests"**. Without this, `release-please.yml` fails with `GitHub Actions is not permitted to create or approve pull requests` and no release PR ever appears.

## How a release gets cut

[release-please](https://github.com/googleapis/release-please) watches `main` for conventional-commit history and keeps a release PR up to date. Merging the PR is the only manual step.

1. **Land conventional-commit PRs to `main`.** `fix:`, `feat:`, and `feat!:` / `BREAKING CHANGE` trigger a release. `chore:`, `docs:`, `ci:`, `refactor:`, and `test:` do not. To force a bump, add a `Release-As: 0.3.4` footer to any commit, or run the `Release PR` workflow via `workflow_dispatch`.

   > **PR titles are the commit headlines.** Squash-merge uses the PR title as the commit headline, which is what release-please parses. The [PR Title](../.github/workflows/pr-title.yml) workflow enforces conventional-commit format on every PR so mis-titled PRs can't silently skip a release (as PR #94 did before the 0.6.0 cut). If you _do_ ever end up with a non-conforming commit on `main`, push an empty conventional-commit marker with `git commit --allow-empty -m "feat(...)…"` and release-please will re-scan.

   Requires repo setting **Settings → General → Pull Requests → "Default to pull request title for squash merges"**. The repo-level API field is `squash_merge_commit_title=PR_TITLE` (not the default `COMMIT_OR_PR_TITLE`, which reuses the first commit's headline on single-commit PRs — that's the gap that let #94 through).
2. **Review the release PR.** Titled `chore(main): release X.Y.Z`. Check the proposed `CHANGELOG.md`, the version bumps in `README.md`, `docs/*.md`, `DoctorCommand.kt`, and `.release-please-manifest.json`. Amend commit messages on `main` if the bump isn't right — the PR updates itself.
3. **Merge the release PR.** On the next `release-please.yml` run (fires immediately on the merge commit), it creates the `vX.Y.Z` tag + a **draft** GitHub Release, then starts two independent paths from that tag. `release.yml` builds and uploads the core artifacts and publishes the Maven deployment; a `finalize-release` job verifies the required assets, un-drafts the Release, and marks it `latest` without waiting for Maven Central's CDN to propagate. A separate readiness job resolves the CLI's exact auto-injected plugin classpath from public Maven Central and then attaches a `compose-preview-maven-ready-<version>.json` marker. The bootstrap installer withholds the new CLI until that marker is downloadable, preventing an update that fails on its first Gradle-backed command. In parallel, `preview-host-image.yml` builds its CLI, Android daemon, and local Maven tree directly from the tag, pushes the image, and rolls `preview.coo.ee` without waiting for either Maven Central or the draft Release to become public. The preview deployment is best-effort and cannot block the core release. Once the release is finalized, the `release-please.yml` run **ends** — the delivery-branch renders no longer hang off it. `post-release-design-artifacts.yml` picks that completed run up via `workflow_run`, confirms the newest published release tag points at the commit it built, and rebuilds the `design-artifacts/<system>` delivery branches against the just-released preview-runtime coordinates in a run of its own; those jobs own their own Maven Central readiness wait. If a required core asset is missing the release **stays a draft** so it's never half-published — re-run `release.yml` for the tag, then re-run the finalize step.

   > **Why draft-then-finalize.** release-please used to publish the Release immediately on merge, so `/releases/latest` flipped to the new version ~20 min before the CLI tarball finished uploading — anyone installing in that window hit "release not found" (a 404 on `latest/download/compose-preview-<v>.tar.gz`). A **draft** Release is excluded from `/releases/latest` and the `latest/download/…` redirect, so those endpoints only expose releases whose assets are present. GitHub does include drafts in the public Atom feed used by the rate-limit-resistant bootstrap installer; marker-era releases are therefore eligible only after their CLI tarball and Maven-readiness marker are downloadable, while older releases retain direct artifact probes. One other gotcha the workflow handles: a draft Release does **not** create its git tag (GitHub writes the tag only on publish), so `release-please.yml` creates the tag itself at the merge commit before the build jobs check it out.

   > **The heavy CI suite is skipped on release PRs, so no admin bypass is needed to merge.** A release PR only bumps the version manifest / `CHANGELOG.md` / version strings in docs — no source or build inputs change. The build/test/security/preview workflows (`ci`, `codeql`, `compose-preview`, `daemon-harness`, `integration`, `report-schemas`, `format`) gate each job with `if: ${{ !(startsWith(github.head_ref, 'release-please--') && github.event.pull_request.user.login == 'github-actions[bot]') }}`, so they report **skipped** only on a release PR — i.e. one whose head branch is `release-please--*` **and** whose author is the release bot. (The branch name alone is contributor-controlled, so the author check is what stops a human/fork PR from naming its branch `release-please--…` to skip CI and abuse "skipped == passing"; switch the login if you move release-please to a GitHub App token.) Branch protection treats a skipped required check as passing, so the required checks go green instantly. The cheap PR-hygiene checks (`pr-title`, `no-agent-attribution`) still run — a release PR passes both. The guard is at job level on purpose: a `branches` filter on the trigger would leave the required check stuck *pending* and block the merge instead.

### Milestones are posted back on the release PR

Merging the PR is the last thing you do, and everything after it happens in workflow logs. Two moments are worth knowing about without going looking, so the release chain comments them on the PR you just merged:

| Milestone | Posted by | Roughly when |
|---|---|---|
| 🚀 **Server deployed** — `preview.coo.ee` is serving the new version, with its catalog count | `preview-host-image.yml`, right after the rollout convergence check | a few minutes in, in parallel with the core release |
| 📦 **Artifacts on Maven Central** — the plugin classpath resolves from the public consumer endpoint | `maven-readiness.yml`, right after the readiness marker uploads | after finalization; Central propagation has taken anywhere from a minute to ~20 |

Both are **pure reporting and cannot slow a release down.** Each fires after a check that already existed for its own reasons (the deployment convergence poll; the Central resolution loop), adds no waiting of its own, and runs `continue-on-error` so a GitHub API blip can't touch a publish that already succeeded. Nothing downstream gates on them.

Both go through [`.github/scripts/comment-release-milestone.sh`](../.github/scripts/comment-release-milestone.sh), which finds the PR from **the tag's** commit — deliberately not from the ref the run started on, since a `workflow_dispatch` repair launches from `main`, whose head is a release merge commit for as long as that release is the newest thing on the branch. Resolving from there would post an older tag's milestone on the newer release PR. A match must additionally have head branch `release-please--*`, author `github-actions[bot]` (the same pair the CI-skip guard above uses), and a title naming this release's version. Nothing matching → nothing posted.

Each milestone carries a hidden marker, so re-running a failed **Maven readiness** job updates its existing comment instead of stacking a second one; the search is restricted to `github-actions[bot]`'s own comments, since release PRs are public and the marker is predictable. All of that is pinned by `test-comment-release-milestone.sh`, run by CI.

If a milestone comment never appears, the release itself is unaffected — check the corresponding job. A missing deploy comment usually means the rollout never converged (or the deployer has no `DEPLOY_HOOK_TOKEN`, as on a fork); a missing Maven comment means the readiness job is still polling or failed, which is the thing that actually matters, and is covered under [Fallback paths](#fallback-paths).

## The export-driver pin refreshes itself

[`design-artifacts-reusable.yml`](../.github/workflows/design-artifacts-reusable.yml) is a **privileged workflow other repos call**, and it executes this repo's export driver (`scripts/design-artifacts/`). A caller must not be able to steer it at one of our `refs/pull/*` revisions, which can carry fork code, so for external callers the driver revision is an immutable commit — recorded in [`.github/design-artifacts-driver-pin.txt`](../.github/design-artifacts-driver-pin.txt).

That pin used to be a standing manual step, and it is the friction [#4107](https://github.com/yschimke/compose-ai-tools/issues/4107) was about. Two separate things were wrong with it:

**1. This repo was pinning itself.** `cli-source: build` exists so compose-ai-tools' own catalogs render against HEAD's renderer — but the *driver* beside that CLI still came from the pin, so HEAD's renderer was being driven by a release-old driver. A fix merged to `main` did not reach our own delivery branches until a release cut **and** someone hand-bumped the pin. [#4103](https://github.com/yschimke/compose-ai-tools/pull/4103) fixed a missing import and the very push that landed it still died in `generate`; it took [#4106](https://github.com/yschimke/compose-ai-tools/pull/4106) to move the pin. The driver revision now resolves to **the caller commit when the caller is this repository**, and to the pin for everyone else. `github.repository` is the caller's repo and is not caller-settable, and no `pull_request` trigger exists anywhere in the in-repo call chain, so this does not widen what the workflow will execute.

**2. Moving the pin was a hand-written PR.** [#4079](https://github.com/yschimke/compose-ai-tools/pull/4079), [#4084](https://github.com/yschimke/compose-ai-tools/pull/4084), [#4085](https://github.com/yschimke/compose-ai-tools/pull/4085) and #4106 are four of them, each authored *after* a downstream repo's CI went red on a bug this repo had already fixed. [`refresh-driver-pin.yml`](../.github/workflows/refresh-driver-pin.yml) now opens that PR on the tail of every release: it hangs off the completed `Release PR` run via `workflow_run`, gated on the newest *published* release pointing at the commit that run built (so a draft can't trigger it), resolves the tag to a commit, and runs [`.github/scripts/refresh-driver-pin.sh`](../.github/scripts/refresh-driver-pin.sh). Nothing is left to discover; a diff that was already written is waiting to be reviewed.

Three details there are load-bearing and easy to get wrong:

- **The generated PR is titled `chore(ci):`, not `fix(ci):`.** A squash merge uses the PR title as the commit headline, and release-please cuts a patch release for `fix:` — so a `fix:`-titled bump would make every release propose another release, whose publish opens another bump. Worth noting that the hand-written bumps this replaces were all titled `fix(ci):` (#4106 and friends), so each of them cut a release of its own.
- **The gate runs outside the concurrency group.** GitHub keeps only one *pending* run per group and cancels the older one — `cancel-in-progress: false` protects the running run, not the queue. Since `Release PR` completes on every push to `main`, a pending release refresh could otherwise be evicted by a no-op run behind it, and a `workflow_run` fires once, so there is no retry. Only the job that pushes the branch is serialised.
- **One branch, `agent/refresh-driver-pin`, not one per tag.** Two releases in quick succession would otherwise open two PRs editing the same lines from the same base, each wedging the other in conflict. The newest release supersedes the pending one in place, retitling the open PR rather than opening a second.

### Why the pin is a data file

`GITHUB_TOKEN` **cannot push changes to anything under `.github/workflows/`** — the App token needs a `workflows` permission that is not one of the scopes a `permissions:` block can request. A pin living in the workflow therefore could not be refreshed by any GITHUB_TOKEN-authenticated automation at all, including release-please. Keeping it in a sibling data file is what makes the automation possible.

What the pin still buys is unchanged: the privileged workflow executes an immutable, release-blessed commit rather than a caller-selected ref that can carry fork code. The workflow reads the file over the API, validates a 40-hex commit, and fails closed otherwise; nothing in the file is executed.

**The known cost, stated plainly.** The pin is read from `main`, so a caller that pinned the *workflow* to a tag or SHA now tracks the newest pin rather than the one frozen at their revision — while the pin was a literal in the workflow, such a caller was fully reproducible. For the documented `@main` usage nothing changes: the same people who could already change the workflow under that caller are the ones who can change the pin file. The gap is not currently closable — a reusable workflow cannot learn its own revision from the `github` context (`workflow_sha` / `workflow_ref` describe the *entry-point* workflow, i.e. the caller's file in the caller's repo), and the claim that does name it, `job_workflow_sha`, exists only as an OIDC token claim, which every caller would have to grant `id-token: write` to reach. Widening the permissions contract of a workflow other repos call, to serve callers who aren't using it that way, is the worse trade. If a pinned caller ever needs frozen driver semantics, the shape to add is a `v*`-tag-constrained input naming the ref to read the pin from — constrained so a caller still can't point it at a `refs/pull/*` revision.

The revision is resolved **once per run**, in a `driver` job every checkout job depends on. While it was a literal in the workflow it was consistent for free (a reusable workflow is resolved once per run); reading it at runtime would otherwise let a sharded run straddle two drivers — `render-shard` reading before rendering and `generate` reading again ~20 minutes later, merging old-driver bundles with new-driver scripts.

Note that the `install@<sha>` action refs in the same workflow are **ordinary action pins**, in the same category as the `actions/checkout` SHAs. They used to be bumped in lockstep with the driver, which is what made a driver fix look like a five-line change; they deliberately do not track releases now and only move when the install action itself changes.

Why release-please can't do this either, even setting the token aside: the pin has to name the release **commit**, which is the squash of the release PR itself and does not exist until after that PR merges. Post-release is the earliest any mechanism can know it.

Two caveats worth knowing:

- The PR is opened with `GITHUB_TOKEN`, whose pushes **do not start workflow runs**, so its checks won't report until someone pushes to the branch or closes and reopens it. The change is three lines of a data file and `ci` re-runs `refresh-driver-pin.sh --check` on merge to `main`, so this is a merge click, not a review burden.
- If the PR sits unmerged, only **external** consumers stay on the older driver. Our own renders no longer read the pin.

To move the pin by hand (or to repair a missed release), run **Refresh driver pin** via `workflow_dispatch` with the tag, or locally:

```bash
.github/scripts/refresh-driver-pin.sh --tag v1.12.0 --sha <40-hex commit>
.github/scripts/refresh-driver-pin.sh --check
```

`--check` runs in `ci` on every PR. It fails on an abbreviated, upper-case or missing SHA, a malformed tag or date, and a duplicated key — all of which otherwise fail *at runtime in a consumer's repo*, after that run has already paid for a checkout and a JDK. It is covered by `.github/scripts/test-refresh-driver-pin.sh`, also run by `ci`, which additionally pins the exact `sed` expression the workflow uses to read the file, so the reader and the validator cannot drift apart. Change either only with those passing.

## Versioning after 1.0.0

`v1.0.0` was cut by pinning `"release-as": "1.0.0"` in [`release-please-config.json`](../release-please-config.json). **That override has been removed, and it had to be** — `release-as` is sticky, not consumed by the release it forces, so left in place every subsequent release PR proposes 1.0.0 again, the tag already exists, and the release wedges. This repo had been bitten by it once before, by an override pinning 0.7.0 that outlived its release. If you ever force a version this way again, delete the key in the first PR after the release publishes.

Removed alongside it: `bump-minor-pre-major` and `bump-patch-for-minor-pre-major`, which only ever applied while the major version was `0`.

**Those two keys are what made `feat:` land as a *patch* bump for the whole `0.x` line**, which is how a repo with this much history spent so long on `0.19.x`. With them gone the usual semver mapping applies:

| Commit type | Bump |
|---|---|
| `fix:` | patch |
| `feat:` | minor |
| `feat!:` / `BREAKING CHANGE` | **major** |

So a PR titled `feat!:` now cuts **2.0.0**, not 1.0.1. Watch PR titles accordingly — this is the single most likely way to cut an unintended major.

## Fallback paths

If the automatic chain ever leaves a release half-published (e.g. Maven Central rejected an upload, CLI build failed), you can re-run the build/publish against an existing tag without touching release-please:

- **From the web:** Actions → **Release** → **Run workflow** → enter the tag (e.g. `v0.3.5`) → Run.
- **From the command line (escape hatch for a manually tagged release):**

  ```bash
  git tag v0.3.5 && git push origin v0.3.5
  ```

  The `push: tags` trigger on `release.yml` picks it up. Only use this when you deliberately want to release without a release-please PR.

Both fallbacks share the same concurrency group as the primary path, so they can't race each other. The final upload step is idempotent — it uploads onto an existing Release (or creates one if none exists).

**One exception: npm.** Neither fallback can publish `@yschimke/compose-design-map` or
`@yschimke/remote-compose-player-cmp`, because both
enter through `release.yml` and the npm trusted publisher is bound to `release-please.yml` (see the
trusted-publishing note under "What the `release.yml` workflow does" — npm permits one binding per
package). If `publish-design-map` or `publish-wasm-player` failed, re-run *that job* in the original **Release PR** run
instead: Actions → the run → **Re-run failed jobs**. An `E404 Not Found - PUT` from `npm publish`
means the OIDC token did not match the trusted publisher, not that the package or version is
missing.

If the GitHub Release is public but `compose-preview update` still selects the
previous version, check the original **Release PR** run's
`verify-maven-readiness` job. Re-run that failed job after Central recovers, or
run **Maven readiness** manually with the release tag. It performs the clean
resolution again and uploads the missing readiness marker without rebuilding or
republishing the release.

### What the `release.yml` workflow does

1. Publishes to **Maven Central** via the Central Portal:
   - **Gradle plugin** — `ee.schimke.composeai:compose-preview-plugin`
   - **Android renderer AAR** — `ee.schimke.composeai:renderer-android`
   - **Preview annotations** — `ee.schimke.composeai:preview-annotations`
   - **Daemon core** (unstable API) — `ee.schimke.composeai:daemon-core` — renderer-agnostic JSON-RPC server, protocol types, RenderHost interface
   - **Daemon desktop** (unstable API) — `ee.schimke.composeai:daemon-desktop` — Compose Multiplatform desktop backend (DesktopHost + DaemonMain)
   - **Daemon android** (unstable API) — `ee.schimke.composeai:daemon-android` — Robolectric backend; Compose / Roborazzi / UI-test stay `compileOnly`, consumer supplies runtime versions
   - **Data product connectors** — `ee.schimke.composeai:data-*-connector` artifacts used by daemon modules, including recomposition

   Maven Central is the only Maven coordinate source — we no longer mirror jars onto GitHub Packages. Consumers point Gradle at `mavenCentral()` and resolve every module from there.
2. Builds the **CLI** and the standalone **MCP server** as `.zip` / `.tar.gz` distributions:
   - `compose-preview-<ver>.{zip,tar.gz}` — the CLI; tarball already implementation-bundles `:mcp` and the desktop renderer.
   - `compose-preview-mcp-<ver>.{zip,tar.gz}` — the MCP server standalone for consumers who want to wire it into an MCP client without dragging the CLI in.
3. Packages the **VS Code extension** as a `.vsix` file and publishes it to the **VS Code Marketplace** and **Open VSX** (runs alongside the Release upload, so a marketplace outage can't block the GitHub Release).
4. Publishes **`@yschimke/compose-design-map`** (the `design-map/` package — the annotations→`design-map.json` projection) to **npm**, at the tag's version. Uses npm Trusted Publishing (OIDC), so there is no npm token to rotate; provenance is attached automatically. Like the marketplace publishes it is tolerated rather than blocking, and it skips a version already on npm, so re-running it is safe. Unlike the other steps, its recovery is **re-running the failed job in the original release run**, not a `workflow_dispatch` of `release.yml` — see the trusted-publisher note below.
5. Publishes **`@yschimke/remote-compose-player-cmp`** (the CMP/Wasm Remote Compose player bundle, staged by `:rc-player-wasm:rcPlayerNpmPackage`) to **npm**, at the tag's version, and attaches the same bundle to the Release as `rc-player-wasm.zip` for consumers who do not want npm. Same OIDC trusted-publishing arrangement, same tolerated-not-blocking posture, same re-run-the-job recovery as the design map. Its **embed contract** — `?src=`, `window.rcPlayerLoad`, the readiness marker — is versioned separately from the release; see [docs/design/RC_PLAYER_EMBED.md](design/RC_PLAYER_EMBED.md).
6. ~~Publishes the **iOS XCFramework**~~ — **disabled, see [#4222](https://github.com/yschimke/compose-ai-tools/issues/4222).** The Kotlin/Native release link runs out of heap on `macos-15`, and because `finalize-release` gates on the whole of `release.yml` succeeding, a failure here would leave the Release a draft *after* Maven Central had already been published to. The job is skipped rather than deleted; re-enable it by dropping the `if: ${{ false }}` on `publish-xcframework` once the link completes in CI. Nothing regressed by disabling it — the job landed after `v1.15.0` and never completed a release, so no version has ever carried the asset. What it does when enabled: uploads `RcComposePlayer.xcframework.zip` to the Release, rewrites `Package.swift` to point at it, and tags that commit with a bare `<version>` (e.g. `1.16.0`) — deliberately *not* `v<version>`, and deliberately not prefixed. The Swift tag exists because SPM verifies a checksum that can only be written *after* the asset is uploaded, and it is bare because SwiftPM only resolves `X.Y.Z`/`vX.Y.Z` refs as versions; see [docs/design/RC_PLAYER_SWIFT.md](design/RC_PLAYER_SWIFT.md).
7. Uploads the CLI, MCP, XR compositor, and VS Code extension artifacts onto the GitHub Release that release-please created (falling back to creating the Release itself if invoked outside the release-please path, e.g. from a manual tag push).

Alongside `release.yml`, the automatic release chain starts
`preview-host-image.yml` immediately after tag creation. That job builds only
the server image's inputs from the tag and deploys independently; it is not a
dependency of Maven publishing, GitHub Release finalization, or the CLI assets.

The Compose Desktop bundle viewer remains in `:bundle-viewer` and is compiled and
tested by normal CI, but its large per-OS binaries are not core release assets.
Build a self-contained jar for the current OS with
`./gradlew :bundle-viewer:packageUberJarForCurrentOS`, or a native
`.deb` / `.dmg` / `.msi` with
`./gradlew :bundle-viewer:packageDistributionForCurrentOS`.

Skill bundles (`compose-preview`, `compose-preview-review`) and the
canonical bootstrap installer (`scripts/install.sh`) ship from a
separate content repo, [yschimke/skills](https://github.com/yschimke/skills),
and are not packaged here. The installer fetches the skill bundles
from yschimke/skills and the CLI tarball from this repo's releases.
The `scripts/install.sh` left in this repo is a thin curl-pipe stub
that forwards to the canonical script — kept so historical
`raw.githubusercontent.com/yschimke/compose-ai-tools/.../scripts/install.sh`
URLs keep resolving.

The `daemon-*` artifacts carry **no API-stability guarantee**; their public API is not yet settled. Expect breakage across minor versions until the surface settles. See [docs/daemon/DESIGN.md § 17](daemon/DESIGN.md) for the architectural decisions and § 19 for the captureToImage fallback path.

Required secrets on the repository:

| Secret | Purpose |
|---|---|
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | User token for https://central.sonatype.com |
| `SIGNING_KEY` | ASCII-armored GPG private key (Maven Central requires signed artifacts) |
| `SIGNING_KEY_ID` | Short (8-hex) key ID |
| `SIGNING_KEY_PASSWORD` | Passphrase for the GPG key |
| `VSCE_PAT` | Azure DevOps PAT for the `yuri-schimke` VS Code Marketplace publisher (scope: Marketplace → Manage, all accessible orgs) |
| `OVSX_PAT` | Open VSX PAT for the `yuri-schimke` namespace (https://open-vsx.org/user-settings/tokens) |

`GITHUB_TOKEN` is provided automatically and is used by the `release` job to upload assets onto the GitHub Release.

npm needs **no secret**: `@yschimke/compose-design-map` publishes over OIDC Trusted Publishing,
configured once on npmjs.com against this repository and the **`release-please.yml`** workflow
filename — the workflow that *starts* the run, not `release.yml`, which merely contains the
`npm publish`. npm matches the OIDC token's entry-point workflow, so a `workflow_call` chain is
validated against the caller; binding it to `release.yml` matches nothing and npm answers the
publish with a bare `E404 Not Found - PUT` (it falls back to the empty `NODE_AUTH_TOKEN` and the
registry will not admit that a package exists to an anonymous writer). The binding is to the
filename — renaming `release-please.yml` breaks npm publishing until the trusted publisher is
reconfigured. `id-token: write` is required on **both** ends of the chain: the `release` job in
`release-please.yml` and `publish-design-map` in `release.yml`.

npm allows only **one** trusted publisher per package, so the automatic chain owns the binding and
a `workflow_dispatch` of `release.yml` cannot authenticate to npm — that dispatch remains the
recovery path for Maven Central and the GitHub Release assets, but for npm the recovery is to
**re-run the failed `publish-design-map` job in the original `release-please.yml` run** (Actions →
the run → "Re-run failed jobs"). The step skips a version already on npm, so it is idempotent.

The package's committed version is a placeholder (`0.0.0`); the release job stamps the tag onto it,
exactly as `build-vscode-extension` does for the extension, so nothing in the tree needs bumping.

Marketplace publishes are idempotent on re-runs: if the version is already published (e.g. on a `workflow_dispatch` retry for an existing tag), the step logs the "already published" message and exits 0 rather than failing.

## Snapshots

Every push to `main` triggers `snapshot.yml`, which computes the next
patch-SNAPSHOT version from `git describe` (e.g. last tag `v0.3.3` →
`0.3.4-SNAPSHOT`) and publishes to the Central snapshots repository:

```
https://central.sonatype.com/repository/maven-snapshots/
```

Snapshots are unsigned, so they only need `MAVEN_CENTRAL_USERNAME` /
`MAVEN_CENTRAL_PASSWORD`.

For pre-merge testing, run **Publish snapshot** manually from the branch
you want to test. Branch/manual runs publish the same Maven artifacts
with a branch-qualified version by default:

```
<next-patch>-<branch-name>-<short-sha>-SNAPSHOT
```

For example, a run from `feature/layout-data` at `abc1234` after
`v0.8.12` publishes `0.8.13-feature-layout-data-abc1234-SNAPSHOT`.
The workflow also accepts an optional `suffix` input if you need a
shorter coordinate, for example `issue-612` →
`0.8.13-issue-612-SNAPSHOT`.

This branch-qualified coordinate is what makes snapshots usable for
testing in other projects before the PR merges. The older documented
main-only coordinate is still published from pushes to `main`, but it is
not enough for PR testing because every branch would otherwise publish
to the same `0.8.13-SNAPSHOT` version.

## Consuming the artifacts

### Gradle plugin (Maven Central)

No authentication, no repository configuration, no PAT. If your project
already includes `mavenCentral()` in `pluginManagement.repositories` (the
typical Android/KMP setup does — AGP and the Kotlin Gradle Plugin both
live there), just apply the plugin:

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "1.29.0"
}
```
<!-- x-release-please-end -->

If `mavenCentral()` is missing from `settings.gradle.kts`, add it:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

**Consuming snapshots:** add the Central snapshots repo:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
}
```

Then reference a `-SNAPSHOT` version:

```kotlin
plugins {
    id("ee.schimke.composeai.preview") version "0.3.4-SNAPSHOT"
}
```

For a branch snapshot, use the version printed in the workflow summary,
for example:

```kotlin
plugins {
    id("ee.schimke.composeai.preview") version "0.8.13-feature-layout-data-abc1234-SNAPSHOT"
}
```

### CLI

Download from the [Releases page](https://github.com/yschimke/compose-ai-tools/releases):

<!-- x-release-please-start-version -->
```bash
curl -L -o compose-preview.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-1.29.0.tar.gz
tar xzf compose-preview.tar.gz
./compose-preview-1.29.0/bin/compose-preview list
```
<!-- x-release-please-end -->

### MCP server (standalone)

The CLI tarball already bundles the MCP server (`compose-preview mcp serve`).
A standalone tarball is also attached to each Release for consumers who only
want the server binary:

<!-- x-release-please-start-version -->
```bash
curl -L -o compose-preview-mcp.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-mcp-1.29.0.tar.gz
tar xzf compose-preview-mcp.tar.gz
./compose-preview-mcp-1.29.0/bin/compose-preview-mcp
```
<!-- x-release-please-end -->

### VS Code extension

Install [Compose Preview](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
from the VS Code Marketplace, or from the command line:

```bash
code --install-extension yuri-schimke.compose-preview
```

The `.vsix` is also attached to each GitHub Release as a fallback.

## Versioning

The single source of truth for the **release version** is [`.release-please-manifest.json`](../.release-please-manifest.json) at the repo root (maintained by release-please). The build scripts resolve `version` in this order:

1. `PLUGIN_VERSION` env var — set by `release.yml` from the git tag (`v0.3.3` → `0.3.3`) and by `snapshot.yml` from `git describe`.
2. Otherwise: next-patch `-SNAPSHOT` derived from the manifest — e.g. manifest `0.3.3` ⇒ local version `0.3.4-SNAPSHOT`. Keeps local `publishToMavenLocal` ahead of the last published release without any manual bump.
