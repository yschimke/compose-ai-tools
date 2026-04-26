# `install` — install the compose-preview CLI in CI

Composite action that downloads a release of the `compose-preview` CLI
and prepends its `bin/` to `$GITHUB_PATH`. Use this in consumer CI
instead of curl-piping `scripts/install.sh` — it pins to a tagged
version of this repo, so consumer CI isn't exposed to changes on `main`.

## Basic usage

<!-- x-release-please-start-version -->
```yaml
- uses: actions/setup-java@v5
  with:
    distribution: temurin
    java-version: 17
- uses: yschimke/compose-ai-tools/.github/actions/install@v0.8.7
  with:
    # Literal "0.8.7", "latest", or "catalog" (read from a Gradle
    # version catalog — see catalog-path / catalog-key inputs).
    version: latest
```
<!-- x-release-please-end -->

After this step the `compose-preview` binary is on `$PATH` for the
remainder of the job.

## Pin the CLI to a Gradle version catalog

To keep the CLI version in lockstep with the rest of the project's
toolchain, declare it in `gradle/libs.versions.toml` and let
[Renovate](https://docs.renovatebot.com/) bump it on releases:

<!-- x-release-please-start-version -->
```toml
# gradle/libs.versions.toml
[versions]
composePreviewCli = "0.8.7"
```

```yaml
- uses: yschimke/compose-ai-tools/.github/actions/install@v0.8.7
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
| `version` | `latest` | Literal version (e.g. `0.8.6`), `latest`, or `catalog`. |
| `catalog-path` | `gradle/libs.versions.toml` | Path to the Gradle version catalog when `version=catalog`. |
| `catalog-key` | `composePreviewCli` | `[versions]` key read when `version=catalog`. |
| `github-token` | workflow token | Token used for the releases API call. Falls back to `github.token`. |

## Related actions

- [`preview-baselines`](../preview-baselines/) — render previews and
  push baselines on `main`.
- [`preview-comment`](../preview-comment/) — render on a PR, post
  before/after comparison comments.
- [`a11y-report`](../a11y-report/) — accessibility findings per preview.

The internal sibling `install-cli` action builds the CLI from source
and exists so this repo's CI doesn't pin against a stale release; it
isn't intended for downstream use.
