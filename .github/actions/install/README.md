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
- uses: yschimke/compose-ai-tools/.github/actions/install@v1.82.0
  with:
    # Literal "1.82.0", "latest", or "catalog" (read from a Gradle
    # version catalog — see catalog-path / catalog-key inputs).
    version: latest
```
<!-- x-release-please-end -->

After this step the `compose-preview` binary is on `$PATH` for the
remainder of the job.

## `latest` means latest **usable**

A release's CLI tarball is downloadable minutes — sometimes tens of minutes —
before its Gradle plugin is resolvable from plugins.gradle.org and Maven
Central. Every build dispatched in that window used to die during
configuration with `Could not find
ee.schimke.composeai.preview:…gradle.plugin:<version>`, an error that names
the *consumer's* project and says nothing about a race; it happened on
1.64.0, 1.65.0, 1.66.1 and 1.68.0 (issues #5034, #5051).

So `latest` resolves to the newest release that carries both its CLI tarball
**and** the Maven-readiness marker `maven-readiness.yml` attaches once it has
resolved that version's plugin classpath from Central for real — the same
marker `compose-preview update` and the bootstrap installer gate on. A release
still in its publication window is skipped, with a warning naming it, and the
previous release is installed instead. If no release in the search window
carries a marker at all (pre-marker releases, or a readiness job that has been
failing), it falls back to the newest complete release and says so.

Every other mode installs exactly the version you named, so those keep their
own guard: after resolving, the action probes for the plugin and waits up to
`plugin-readiness-timeout` seconds for it to appear, then fails with a message
that names the publication window rather than letting Gradle report it as a
configuration error in your build. Set `plugin-readiness-timeout: 0` in a job
that only needs the binary and never drives Gradle.

The artifact set is **derived from the POMs**, not from the marker alone. The
marker is a `pom`-packaged stub whose only content is a dependency on
`ee.schimke.composeai:compose-preview-plugin`, in a different group, and that
implementation has same-version `api` dependencies of its own; any of them
lagging in CDN propagation fails a consumer's configuration just as hard. So the
guard walks the marker's POM to the implementation, the implementation's POM to
its same-version siblings, and requires every POM and jar in that set to answer.
Third-party dependencies and anything pinned to a different version are not
walked — they have been on Central for months; it is the artifacts published by
*this* release, together, that propagate independently.

A registry that answers `429` or `5xx` is treated as "no verdict" — a rate limit
must not look like an unpublished release — and the step passes with a warning
after trying both mirrors. A definite `404` is never masked by a neighbour's
non-answer: while anything is conclusively absent, the guard keeps waiting.
Every request past the first sweep is bounded by what is left of the budget, and
running out of time fails rather than passing on a shrug. One sweep always
happens, so a very small timeout buys one attempt rather than none.

(A real Gradle resolution, as `maven-readiness.yml` performs, would be stronger
still — but it needs a JDK and a Gradle distribution, and this action installs
neither by design.)

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
composePreviewCli = "1.82.0"
```

```yaml
- uses: yschimke/compose-ai-tools/.github/actions/install@v1.82.0
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
| `plugin-readiness-timeout` | `300` | Seconds to wait for the resolved version's Gradle plugin to be resolvable before failing with an explicit message. `0` skips the probe. |

## Related actions

- [`apply`](../apply/) — the unified compose-preview pipeline: baselines
  on push, before/after PR comments, and the a11y + notification surfaces
  in one step. Use this unless you only need the CLI on `$PATH`. It
  supersedes the per-surface `preview-baselines` / `preview-comment` /
  `a11y-report` composites (now thin, deprecated forwarders).

The internal sibling `install-cli` action builds the CLI from source
and exists so this repo's CI doesn't pin against a stale release; it
isn't intended for downstream use.
