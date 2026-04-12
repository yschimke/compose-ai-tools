# Releasing

All three artifacts ship from a single GitHub Actions workflow triggered by a version tag.

## Cutting a release

```bash
git tag v0.1.0
git push origin v0.1.0
```

The `release.yml` workflow then:

1. Publishes the **Gradle plugin** to GitHub Packages (`maven.pkg.github.com/yschimke/compose-ai-tools`)
2. Builds the **CLI** as `.zip` and `.tar.gz` distributions
3. Packages the **VS Code extension** as a `.vsix` file
4. Creates a GitHub Release with auto-generated notes and all three artifacts attached

No secrets or accounts required beyond `GITHUB_TOKEN`, which is provided automatically.

## Consuming the artifacts

### Gradle plugin (GitHub Packages)

Consumers need to authenticate to GitHub Packages. In their `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/yschimke/compose-ai-tools")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "ee.schimke.composeai.preview") {
                useModule("ee.schimke.composeai:gradle-plugin:${requested.version}")
            }
        }
    }
}
```

Then in a consumer `build.gradle.kts`:

```kotlin
plugins {
    id("ee.schimke.composeai.preview") version "0.1.0"
}
```

The GitHub token needs `read:packages` scope. Create a PAT at
<https://github.com/settings/tokens> or use `GITHUB_TOKEN` in CI.

### CLI

Download from the [Releases page](https://github.com/yschimke/compose-ai-tools/releases):

```bash
curl -L -o compose-preview.tar.gz \
    https://github.com/yschimke/compose-ai-tools/releases/latest/download/compose-preview-0.1.0.tar.gz
tar xzf compose-preview.tar.gz
./compose-preview-0.1.0/bin/compose-preview list
```

### VS Code extension

Download the `.vsix` from the Releases page and install:

```bash
code --install-extension compose-preview-0.1.0.vsix
```

## Future: publishing to public registries

The current workflow is GitHub-only. Each artifact can be added to its public
registry without changing existing consumers:

| Artifact | Current | Public registry | Migration |
|----------|---------|-----------------|-----------|
| Gradle plugin | GitHub Packages | Gradle Plugin Portal | Apply `com.gradle.plugin-publish` plugin; add `publishPlugins` task to the workflow with `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET` secrets |
| VS Code extension | Release .vsix | VS Code Marketplace + Open VSX | Add `vsce publish` and `ovsx publish` steps with `VSCE_PAT` / `OVSX_PAT` secrets |
| CLI | Release .zip/.tar | Homebrew tap | Add a `release-please` or `dispatches` step that updates a separate `homebrew-tap` repo |

Existing GitHub Release artifacts remain as a fallback and don't need to go away.

## Versioning

`PLUGIN_VERSION` is set from the tag name (`v0.1.0` → `0.1.0`) by the workflow
and threaded into all three build scripts via environment variable. Local builds
default to `0.1.0-SNAPSHOT`.
