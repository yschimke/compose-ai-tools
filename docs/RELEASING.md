# Releasing

All three artifacts ship from a single GitHub Actions workflow triggered by a version tag. The version bump and changelog are prepared by [release-please](https://github.com/googleapis/release-please); the maintainer only needs to merge the release PR and push the tag.

## Cutting a release

The flow is:

1. **Land conventional-commit PRs to `main`** — e.g. `fix:`, `feat:`, `feat!:` / `BREAKING CHANGE`. Other prefixes (`chore:`, `docs:`, `ci:`, `refactor:`, `test:`) do not trigger a release. Force a bump when needed by adding a `Release-As: 0.3.4` footer to any commit, or run the `Release PR` workflow via `workflow_dispatch`.
2. **`release-please.yml` opens or updates a release PR** titled `chore(main): release X.Y.Z`. Inspect the proposed `CHANGELOG.md`, version bumps in `README.md` / `docs/*.md` / `DoctorCommand.kt`, and `.release-please-manifest.json`. Adjust commit messages on `main` if the bump isn't what you want; the PR updates automatically.
3. **Merge the release PR.** `main` now has the bumped files and updated manifest. No tag is created yet — see the box below.
4. **Tag and push:**
   ```bash
   git pull origin main
   git tag "v$(jq -r '."."' .release-please-manifest.json)"
   git push origin --tags
   ```
   This fires `release.yml`, which publishes all three artifacts and creates the GitHub Release.

> **Why the tag step is manual:** release-please could create the tag itself, but a tag created by `GITHUB_TOKEN` doesn't trigger other workflows — so `release.yml` (which listens for `push: tags`) would never fire. Keeping the tag push manual sidesteps the need for a PAT.

### What the `release.yml` workflow does

1. Publishes the **Gradle plugin** (`ee.schimke.composeai:compose-preview-plugin`) and the **Android renderer AAR** (`ee.schimke.composeai:renderer-android`) to **Maven Central** via the Central Portal, and mirrors them to GitHub Packages.
2. Builds the **CLI** as `.zip` and `.tar.gz` distributions.
3. Packages the **VS Code extension** as a `.vsix` file.
4. Creates a GitHub Release with auto-generated notes and all three artifacts attached.

Required secrets on the repository:

| Secret | Purpose |
|---|---|
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | User token for https://central.sonatype.com |
| `SIGNING_KEY` | ASCII-armored GPG private key (Maven Central requires signed artifacts) |
| `SIGNING_KEY_ID` | Short (8-hex) key ID |
| `SIGNING_KEY_PASSWORD` | Passphrase for the GPG key |

`GITHUB_TOKEN` is provided automatically and is used for the GH Packages mirror.

## Snapshots

Every push to `main` triggers `snapshot.yml`, which computes the next
patch-SNAPSHOT version from `git describe` (e.g. last tag `v0.3.3` →
`0.3.4-SNAPSHOT`) and publishes to the Central snapshots repository:

```
https://central.sonatype.com/repository/maven-snapshots/
```

Snapshots are unsigned, so they only need `MAVEN_CENTRAL_USERNAME` /
`MAVEN_CENTRAL_PASSWORD`.

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
    id("ee.schimke.composeai.preview") version "0.3.5"
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

### Gradle plugin (GitHub Packages — fallback)

The release workflow also publishes to GitHub Packages for users who
prefer it. This path requires a PAT with `read:packages` scope; see git
history for the repository+credentials setup if you need it. Maven
Central is the supported default.

### CLI

Download from the [Releases page](https://github.com/yschimke/compose-ai-tools/releases):

<!-- x-release-please-start-version -->
```bash
curl -L -o compose-preview.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-0.3.5.tar.gz
tar xzf compose-preview.tar.gz
./compose-preview-0.3.5/bin/compose-preview list
```
<!-- x-release-please-end -->

### VS Code extension

Download the `.vsix` from the Releases page and install:

<!-- x-release-please-start-version -->
```bash
code --install-extension compose-preview-0.3.5.vsix
```
<!-- x-release-please-end -->

## Future: publishing to public registries

The Gradle plugin is now on Maven Central. The other artifacts could
similarly move to their public registries without breaking existing
consumers:

| Artifact | Current | Public registry | Migration |
|----------|---------|-----------------|-----------|
| Gradle plugin | **Maven Central** (+ GH Packages mirror) | Gradle Plugin Portal | Apply `com.gradle.plugin-publish` plugin; add `publishPlugins` task to the workflow with `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET` secrets |
| VS Code extension | Release .vsix | VS Code Marketplace + Open VSX | Add `vsce publish` and `ovsx publish` steps with `VSCE_PAT` / `OVSX_PAT` secrets |
| CLI | Release .zip/.tar | Homebrew tap | Add a `dispatches` step that updates a separate `homebrew-tap` repo |

Existing GitHub Release artifacts remain as a fallback and don't need to go away.

## Versioning

The single source of truth for the **release version** is [`.release-please-manifest.json`](../.release-please-manifest.json) at the repo root (maintained by release-please). The build scripts resolve `version` in this order:

1. `PLUGIN_VERSION` env var — set by `release.yml` from the git tag (`v0.3.3` → `0.3.3`) and by `snapshot.yml` from `git describe`.
2. Otherwise: next-patch `-SNAPSHOT` derived from the manifest — e.g. manifest `0.3.3` ⇒ local version `0.3.4-SNAPSHOT`. Keeps local `publishToMavenLocal` ahead of the last published release without any manual bump.
