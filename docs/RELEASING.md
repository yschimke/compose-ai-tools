# Releasing

## TL;DR

**Merge the open `chore(main): release X.Y.Z` PR.** That's it — `release-please.yml` creates the tag and GitHub Release, then chains into `release.yml` to build and publish the CLI, VS Code extension, Gradle plugin, and Android renderer AAR onto that Release.

## Prerequisites (one-time)

In **Settings → Actions → General → Workflow permissions**, tick **"Allow GitHub Actions to create and approve pull requests"**. Without this, `release-please.yml` fails with `GitHub Actions is not permitted to create or approve pull requests` and no release PR ever appears.

## How a release gets cut

[release-please](https://github.com/googleapis/release-please) watches `main` for conventional-commit history and keeps a release PR up to date. Merging the PR is the only manual step.

1. **Land conventional-commit PRs to `main`.** `fix:`, `feat:`, and `feat!:` / `BREAKING CHANGE` trigger a release. `chore:`, `docs:`, `ci:`, `refactor:`, and `test:` do not. To force a bump, add a `Release-As: 0.3.4` footer to any commit, or run the `Release PR` workflow via `workflow_dispatch`.

   > **PR titles are the commit headlines.** Squash-merge uses the PR title as the commit headline, which is what release-please parses. The [PR Title](../.github/workflows/pr-title.yml) workflow enforces conventional-commit format on every PR so mis-titled PRs can't silently skip a release (as PR #94 did before the 0.6.0 cut). If you _do_ ever end up with a non-conforming commit on `main`, push an empty conventional-commit marker with `git commit --allow-empty -m "feat(...)…"` and release-please will re-scan.

   Requires repo setting **Settings → General → Pull Requests → "Default to pull request title for squash merges"**. The repo-level API field is `squash_merge_commit_title=PR_TITLE` (not the default `COMMIT_OR_PR_TITLE`, which reuses the first commit's headline on single-commit PRs — that's the gap that let #94 through).
2. **Review the release PR.** Titled `chore(main): release X.Y.Z`. Check the proposed `CHANGELOG.md`, the version bumps in `README.md`, `docs/*.md`, `DoctorCommand.kt`, and `.release-please-manifest.json`. Amend commit messages on `main` if the bump isn't right — the PR updates itself.
3. **Merge the release PR.** On the next `release-please.yml` run (fires immediately on the merge commit), it creates the `vX.Y.Z` tag + GitHub Release, then invokes `release.yml` to build and upload the artifacts.

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
2. Builds the **CLI** and the standalone **MCP server** as `.zip` and `.tar.gz` distributions (`compose-preview-<ver>.{zip,tar.gz}` and `compose-preview-mcp-<ver>.{zip,tar.gz}`). The CLI tarball already implementation-bundles `:mcp`, so the MCP archive is for consumers who want to wire the server into an MCP client without dragging the CLI in.
3. Packs each `skills/<name>/` directory into `<name>-skill-<ver>.tar.gz`.
4. Packages the **VS Code extension** as a `.vsix` file and publishes it to the **VS Code Marketplace** and **Open VSX** (runs alongside the Release upload, so a marketplace outage can't block the GitHub Release).
5. Uploads the CLI, MCP, skill, and VS Code extension artifacts onto the GitHub Release that release-please created (falling back to creating the Release itself if invoked outside the release-please path, e.g. from a manual tag push).

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
    id("ee.schimke.composeai.preview") version "0.10.4"
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
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-0.10.4.tar.gz
tar xzf compose-preview.tar.gz
./compose-preview-0.10.4/bin/compose-preview list
```
<!-- x-release-please-end -->

### MCP server (standalone)

The CLI tarball already bundles the MCP server (`compose-preview mcp serve`).
A standalone tarball is also attached to each Release for consumers who only
want the server binary:

<!-- x-release-please-start-version -->
```bash
curl -L -o compose-preview-mcp.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-mcp-0.10.4.tar.gz
tar xzf compose-preview-mcp.tar.gz
./compose-preview-mcp-0.10.4/bin/compose-preview-mcp
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
