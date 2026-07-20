# Releasing

## TL;DR

**Merge the open `chore(main): release X.Y.Z` PR.** That's it — `release-please.yml` creates the tag and a **draft** GitHub Release, chains into `release.yml` to build the CLI, VS Code extension, Gradle plugin, and Android renderer AAR onto that draft, then **finalizes** (un-drafts) it once every required asset is verified present. Because the Release stays a draft until it's fully populated, `/releases/latest` and the `latest/download/…` URLs never point at a version whose tarball hasn't uploaded yet — closing the "release not found" install window.

## Prerequisites (one-time)

In **Settings → Actions → General → Workflow permissions**, tick **"Allow GitHub Actions to create and approve pull requests"**. Without this, `release-please.yml` fails with `GitHub Actions is not permitted to create or approve pull requests` and no release PR ever appears.

## How a release gets cut

[release-please](https://github.com/googleapis/release-please) watches `main` for conventional-commit history and keeps a release PR up to date. Merging the PR is the only manual step.

1. **Land conventional-commit PRs to `main`.** `fix:`, `feat:`, and `feat!:` / `BREAKING CHANGE` trigger a release. `chore:`, `docs:`, `ci:`, `refactor:`, and `test:` do not. To force a bump, add a `Release-As: 0.3.4` footer to any commit, or run the `Release PR` workflow via `workflow_dispatch`.

   > **PR titles are the commit headlines.** Squash-merge uses the PR title as the commit headline, which is what release-please parses. The [PR Title](../.github/workflows/pr-title.yml) workflow enforces conventional-commit format on every PR so mis-titled PRs can't silently skip a release (as PR #94 did before the 0.6.0 cut). If you _do_ ever end up with a non-conforming commit on `main`, push an empty conventional-commit marker with `git commit --allow-empty -m "feat(...)…"` and release-please will re-scan.

   Requires repo setting **Settings → General → Pull Requests → "Default to pull request title for squash merges"**. The repo-level API field is `squash_merge_commit_title=PR_TITLE` (not the default `COMMIT_OR_PR_TITLE`, which reuses the first commit's headline on single-commit PRs — that's the gap that let #94 through).
2. **Review the release PR.** Titled `chore(main): release X.Y.Z`. Check the proposed `CHANGELOG.md`, the version bumps in `README.md`, `docs/*.md`, `DoctorCommand.kt`, and `.release-please-manifest.json`. Amend commit messages on `main` if the bump isn't right — the PR updates itself.
3. **Merge the release PR.** On the next `release-please.yml` run (fires immediately on the merge commit), it creates the `vX.Y.Z` tag + a **draft** GitHub Release, then invokes `release.yml` to build and upload the artifacts onto that draft. A `finalize-release` job verifies the required assets are attached (and polls Maven Central for the published plugin), then un-drafts the Release and marks it `latest`. Only after that does the prebuilt `preview-host-image` build (it pulls the now-public CLI tarball). Once the release is finalized, `regenerate-design-artifacts` also fires (chained via `workflow_call`), rebuilding the `design-artifacts/<system>` delivery branches against the just-released preview-runtime coordinates — the published sticker bundles pin `composeaiReleasedRuntimeVersion`, which the release PR just bumped, so they'd otherwise lag until the Monday cron. If a required asset is missing the release **stays a draft** so it's never half-published — re-run `release.yml` for the tag, then re-run the finalize step.

   > **Why draft-then-finalize.** release-please used to publish the Release immediately on merge, so `/releases/latest` flipped to the new version ~20 min before the CLI tarball finished uploading — anyone installing in that window hit "release not found" (a 404 on `latest/download/compose-preview-<v>.tar.gz`). A **draft** Release is excluded from `/releases/latest` and the `latest/download/…` redirect, so consumers only ever see a release whose assets are all present. One gotcha the workflow handles: a draft Release does **not** create its git tag (GitHub writes the tag only on publish), so `release-please.yml` creates the tag itself at the merge commit before the build jobs check it out.

   > **The heavy CI suite is skipped on release PRs, so no admin bypass is needed to merge.** A release PR only bumps the version manifest / `CHANGELOG.md` / version strings in docs — no source or build inputs change. The build/test/security/preview workflows (`ci`, `codeql`, `compose-preview`, `daemon-harness`, `integration`, `report-schemas`, `format`) gate each job with `if: ${{ !(startsWith(github.head_ref, 'release-please--') && github.event.pull_request.user.login == 'github-actions[bot]') }}`, so they report **skipped** only on a release PR — i.e. one whose head branch is `release-please--*` **and** whose author is the release bot. (The branch name alone is contributor-controlled, so the author check is what stops a human/fork PR from naming its branch `release-please--…` to skip CI and abuse "skipped == passing"; switch the login if you move release-please to a GitHub App token.) Branch protection treats a skipped required check as passing, so the required checks go green instantly. The cheap PR-hygiene checks (`pr-title`, `no-agent-attribution`) still run — a release PR passes both. The guard is at job level on purpose: a `branches` filter on the trigger would leave the required check stuck *pending* and block the merge instead.

## Fallback paths

If the automatic chain ever leaves a release half-published (e.g. Maven Central rejected an upload, CLI build failed), you can re-run the build/publish against an existing tag without touching release-please:

- **From the web:** Actions → **Release** → **Run workflow** → enter the tag (e.g. `v0.3.5`) → Run.
- **From the command line (escape hatch for a manually tagged release):**

  ```bash
  git tag v0.3.5 && git push origin v0.3.5
  ```

  The `push: tags` trigger on `release.yml` picks it up. Only use this when you deliberately want to release without a release-please PR.

Both fallbacks share the same concurrency group as the primary path, so they can't race each other. The final upload step is idempotent — it uploads onto an existing Release (or creates one if none exists).

### What the `release.yml` workflow does

1. Publishes to **Maven Central** via the Central Portal:
   - **Gradle plugin** — `ee.schimke.composeai:compose-preview-plugin`
   - **Android renderer AAR** — `ee.schimke.composeai:renderer-android`
   - **Preview annotations** — `ee.schimke.composeai:preview-annotations`
   - **Daemon core** (pre-1.0) — `ee.schimke.composeai:daemon-core` — renderer-agnostic JSON-RPC server, protocol types, RenderHost interface
   - **Daemon desktop** (pre-1.0) — `ee.schimke.composeai:daemon-desktop` — Compose Multiplatform desktop backend (DesktopHost + DaemonMain)
   - **Daemon android** (pre-1.0) — `ee.schimke.composeai:daemon-android` — Robolectric backend; Compose / Roborazzi / UI-test stay `compileOnly`, consumer supplies runtime versions
   - **Data product connectors** — `ee.schimke.composeai:data-*-connector` artifacts used by daemon modules, including recomposition

   Maven Central is the only Maven coordinate source — we no longer mirror jars onto GitHub Packages. Consumers point Gradle at `mavenCentral()` and resolve every module from there.
2. Builds the **CLI** and the standalone **MCP server** as `.zip` / `.tar.gz` distributions, and the **bundle viewer** as a single runnable uber jar:
   - `compose-preview-<ver>.{zip,tar.gz}` — the CLI; tarball already implementation-bundles `:mcp` and the desktop renderer.
   - `compose-preview-mcp-<ver>.{zip,tar.gz}` — the MCP server standalone for consumers who want to wire it into an MCP client without dragging the CLI in.
   - `compose-preview-viewer-<os>-<arch>-<ver>.jar` — single-window Compose Desktop app, built by Compose's `packageUberJarForCurrentOS`, that opens a packed bundle and renders its `@Preview` composable live (`java -jar compose-preview-viewer-<os>-<arch>-<ver>.jar <bundle.png>`, or drag-and-drop). One self-contained file — no unpacking — but **per-OS** (it carries that OS's Compose Multiplatform + Skiko runtime, ~40 MB). The release runner for this jar is Linux, so the published jar is `linux-x64`; macOS/Windows recipients build it locally with `./gradlew :bundle-viewer:packageUberJarForCurrentOS`.
   - `compose-preview-viewer*.{deb,dmg,msi}` — native installers for the viewer, one per OS, built by `packageDistributionForCurrentOS` (`jpackage`) on a Linux/macOS/Windows matrix. Each embeds a JDK runtime image, so a recipient with **no JDK** installs and launches the viewer like any native app. Unsigned — first launch clears a one-time Gatekeeper (macOS) / SmartScreen (Windows) prompt. The uber jar above stays the no-install option for anyone who has a JDK.
3. Packages the **VS Code extension** as a `.vsix` file and publishes it to the **VS Code Marketplace** and **Open VSX** (runs alongside the Release upload, so a marketplace outage can't block the GitHub Release).
4. Uploads the CLI, MCP, viewer (uber jar + native installers), and VS Code extension artifacts onto the GitHub Release that release-please created (falling back to creating the Release itself if invoked outside the release-please path, e.g. from a manual tag push).

Skill bundles (`compose-preview`, `compose-preview-review`) and the
canonical bootstrap installer (`scripts/install.sh`) ship from a
separate content repo, [yschimke/skills](https://github.com/yschimke/skills),
and are not packaged here. The installer fetches the skill bundles
from yschimke/skills and the CLI tarball from this repo's releases.
The `scripts/install.sh` left in this repo is a thin curl-pipe stub
that forwards to the canonical script — kept so historical
`raw.githubusercontent.com/yschimke/compose-ai-tools/.../scripts/install.sh`
URLs keep resolving.

The `daemon-*` artifacts are **pre-1.0**; their public API is not yet stable. Expect breakage across minor versions until the surface settles. See [docs/daemon/DESIGN.md § 17](daemon/DESIGN.md) for the architectural decisions and § 19 for the captureToImage fallback path.

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
    id("ee.schimke.composeai.preview") version "0.16.60"
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
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-0.16.60.tar.gz
tar xzf compose-preview.tar.gz
./compose-preview-0.16.60/bin/compose-preview list
```
<!-- x-release-please-end -->

### MCP server (standalone)

The CLI tarball already bundles the MCP server (`compose-preview mcp serve`).
A standalone tarball is also attached to each Release for consumers who only
want the server binary:

<!-- x-release-please-start-version -->
```bash
curl -L -o compose-preview-mcp.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-mcp-0.16.60.tar.gz
tar xzf compose-preview-mcp.tar.gz
./compose-preview-mcp-0.16.60/bin/compose-preview-mcp
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
