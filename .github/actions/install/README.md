# `install` — install the compose-preview CLI in CI

Composite action that downloads a release of the `compose-preview` CLI
and prepends its `bin/` to `$GITHUB_PATH`. Use this in consumer CI
instead of curl-piping the bootstrap installer from yschimke/skills — it pins to a tagged
version of this repo, so consumer CI isn't exposed to changes on `main`.

## Basic usage

<!-- x-release-please-start-version -->
```yaml
- uses: actions/setup-java@v5
  with:
    distribution: temurin
    java-version: 17
- uses: yschimke/compose-ai-tools/.github/actions/install@v1.1.0
  with:
    # Literal "1.1.0", "latest", or "catalog" (read from a Gradle
    # version catalog — see catalog-path / catalog-key inputs).
    version: latest
```
<!-- x-release-please-end -->

After this step the `compose-preview` binary is on `$PATH` for the
remainder of the job.

## Pin the CLI to a Gradle version catalog

> **Avoid CLI / plugin version skew.** `version: latest` floats to the
> newest release independent of the Gradle plugin you've pinned. A CLI
> newer than the applied plugin can't discover it, so renders break on
> every release (issue #1920). Pin both from one source of truth — and if
> you drive CI through the [`apply`](../apply/README.md#version-skew)
> action, it also guards against this automatically.

### `version: pin` — one pin for every entrypoint

The simplest way to keep CI in lockstep is to let it read the same pin the
CLI and the VS Code extension read:

```yaml
- uses: yschimke/compose-ai-tools/.github/actions/install@v1
  with:
    version: pin
```

`pin` resolves `composePreview.version` from the checkout's
`gradle.properties` (what `compose-preview pin <version>` writes), falling
back to the catalog key below. It **fails** when the project pins nothing,
rather than quietly installing `latest` — a silent fallback is the skew this
is meant to prevent. See [VERSION_PIN.md](../../../docs/VERSION_PIN.md) for
the full model.

### `version: catalog` — track one catalog key

To keep the CLI version in lockstep with the rest of the project's
toolchain, declare it in `gradle/libs.versions.toml` and let
[Renovate](https://docs.renovatebot.com/) bump it on releases:

<!-- x-release-please-start-version -->
```toml
# gradle/libs.versions.toml
[versions]
composePreviewCli = "1.1.0"
```

```yaml
- uses: yschimke/compose-ai-tools/.github/actions/install@v1.1.0
  with:
    version: catalog   # reads composePreviewCli from libs.versions.toml
```
<!-- x-release-please-end -->

```json
// .github/renovate.json
{
  "customManagers": [
    {
      "customType": "regex",
      "fileMatch": ["(^|/)gradle/libs\\.versions\\.toml$"],
      "matchStrings": [
        "composePreviewCli\\s*=\\s*\"(?<currentValue>[^\"]+)\""
      ],
      "datasourceTemplate": "github-releases",
      "depNameTemplate": "yschimke/compose-ai-tools",
      "extractVersionTemplate": "^v?(?<version>.+)$"
    }
  ]
}
```

## Inputs

See [`action.yml`](action.yml) for the full schema. Summary:

| Input | Default | Purpose |
|---|---|---|
| `version` | `latest` | Literal version (e.g. `0.8.6`), `latest`, `pin`, or `catalog`. |
| `catalog-path` | `gradle/libs.versions.toml` | Path to the Gradle version catalog when `version=catalog` or `version=pin`. |
| `catalog-key` | `composePreviewCli` | `[versions]` key read when `version=catalog` or `version=pin`. |
| `properties-path` | `gradle.properties` | File read for `composePreview.version` when `version=pin`. |
| `github-token` | workflow token | Token used for the releases API call. Falls back to `github.token`. |

## Related actions

- [`apply`](../apply/) — the unified compose-preview pipeline: baselines
  on push, before/after PR comments, and the a11y + notification surfaces
  in one step. Use this unless you only need the CLI on `$PATH`. It
  supersedes the per-surface `preview-baselines` / `preview-comment` /
  `a11y-report` composites (now thin, deprecated forwarders).

The internal sibling `install-cli` action builds the CLI from source
and exists so this repo's CI doesn't pin against a stale release; it
isn't intended for downstream use.
