# Releasing

All three artifacts ship from a single GitHub Actions workflow triggered by a version tag.

## Cutting a release

```bash
git tag v<version>
git push origin v<version>
```

The `release.yml` workflow then:

1. Publishes the **Gradle plugin** (`ee.schimke.composeai:compose-preview-plugin`)
   and the **Android renderer AAR** (`ee.schimke.composeai:renderer-android`)
   to **Maven Central** via the Central Portal, and mirrors them to GitHub
   Packages.
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

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.3.3"
}
```

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

```bash
curl -L -o compose-preview.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-0.3.3.tar.gz
tar xzf compose-preview.tar.gz
./compose-preview-0.3.3/bin/compose-preview list
```

### VS Code extension

Download the `.vsix` from the Releases page and install:

```bash
code --install-extension compose-preview-0.3.3.vsix
```

## Future: publishing to public registries

The Gradle plugin is now on Maven Central. The other artifacts could
similarly move to their public registries without breaking existing
consumers:

| Artifact | Current | Public registry | Migration |
|----------|---------|-----------------|-----------|
| Gradle plugin | **Maven Central** (+ GH Packages mirror) | Gradle Plugin Portal | Apply `com.gradle.plugin-publish` plugin; add `publishPlugins` task to the workflow with `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET` secrets |
| VS Code extension | Release .vsix | VS Code Marketplace + Open VSX | Add `vsce publish` and `ovsx publish` steps with `VSCE_PAT` / `OVSX_PAT` secrets |
| CLI | Release .zip/.tar | Homebrew tap | Add a `release-please` or `dispatches` step that updates a separate `homebrew-tap` repo |

Existing GitHub Release artifacts remain as a fallback and don't need to go away.

## Versioning

`PLUGIN_VERSION` is set from the tag name (`v0.3.3` → `0.3.3`) by the
release workflow and threaded into all three build scripts via environment
variable. The snapshot workflow computes its version from the last tag.
Local builds default to a forward-looking `-SNAPSHOT` string (see the
fallback in each module's `build.gradle.kts`).
