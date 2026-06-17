# `apply` — compose-preview CI in one step

Unified composite action for the compose-preview CI surface. Auto-selects
baseline vs. comment mode from the triggering event, runs the four
pipelines (compose / resources / a11y / notifications) back-to-back, and
pushes baselines / PR renders + posts sticky PR comments. Replaces the
per-surface `preview-baselines` / `preview-comment` / `a11y-report` /
`notification-previews` composites (now thin, deprecated forwarders).

## Basic usage

<!-- x-release-please-start-version -->
```yaml
- uses: actions/setup-java@v5
  with:
    distribution: temurin
    java-version: 17
- uses: yschimke/compose-ai-tools/.github/actions/apply@v0.15.9
```
<!-- x-release-please-end -->

`cli-version` defaults to **`auto`**, which pins the CLI to the plugin version
you already pin in your checkout — so the version skew described below can't
happen on the happy path, with no extra config. See
[Version skew](#version-skew) for what `auto` does and when to override it.

On a `pull_request` this renders the PR and posts before/after comparison
comments; on a `push` to the development branch (`main` by default) it
refreshes the baselines. See [`action.yml`](action.yml) for the full input
schema — there are ~20 inputs covering pipeline selection (`only` / `skip`),
per-module allow/deny lists, the missing-render policy, and non-Gradle
`skip-render` integration.

## Version skew

> **What used to be the single most common way this action breaks.** Pinning
> the action ref (`apply@vX`) does **not** pin the CLI. When `cli-version` was
> `latest`, every new compose-ai-tools release auto-installed the newest CLI
> against your still-pinned Gradle plugin. A CLI **newer** than the applied
> plugin can't discover that older plugin, so the render finds zero preview
> modules and the pipeline fails repo-wide — on a release, not on your diff —
> with a misleading:
>
> ```
> ✗ no modules have the compose-preview plugin applied
> compose pipeline: No preview modules discovered ... skipping.
> ```

**The default `cli-version: auto` removes this footgun.** It detects the plugin
version you pin in your checkout (version-catalog `[plugins]` entry or a literal
`id("…") version "…"` in a build script) and installs the CLI at *exactly* that
version, so the CLI and plugin can't skew — declare the plugin version once and
the CLI follows it for free:

<!-- x-release-please-start-version -->
```toml
# gradle/libs.versions.toml
[versions]
composePreviewPlugin = "0.15.9"

[plugins]
composePreview = { id = "ee.schimke.composeai.preview", version.ref = "composePreviewPlugin" }
```
<!-- x-release-please-end -->

> The `version.ref` is the plugin's own version key; `auto` reads it directly
> off the `[plugins]` entry — no `catalog-key` needed.

[Renovate](https://docs.renovatebot.com/) bumps that one `[versions]` entry on
each release and `auto` picks it up automatically — no `cli-version` /
`catalog-key` wiring needed. When `auto` finds **no** pinned plugin (the
auto-inject / zero-code path, where the CLI injects a matching plugin via
`--init-script`) it falls back to `latest`, which is correct there because the
injected plugin always matches the installed CLI.

If you want to pin the CLI to a *specific* `[versions]` key regardless of the
plugin (e.g. a dedicated `composePreviewCli` entry), use `cli-version: catalog`
with `catalog-key`; the same recipe is documented for the standalone
[`install`](../install/README.md#pin-the-cli-to-a-gradle-version-catalog)
action. Set `cli-version: latest` to opt back into the old floating behaviour.

### Skew guardrail

Even without switching to `catalog`, the action defends against this footgun:
after installing the CLI it reads the plugin version you pin in the checkout
(catalog `[plugins]` entry or a literal `id("…") version "…"`) and, when the
installed CLI is newer, **fails fast with an actionable message** instead of
letting the pipeline die downstream with the confusing "no modules" error.

Control it with the `skew-check` input:

| `skew-check` | Behaviour |
|---|---|
| `fail` (default) | Error out before the pipelines run when the CLI is newer than the pinned plugin. |
| `warn` | Log a `::warning::` and keep going. |
| `off` | Skip the check entirely. |

The guard only acts when it can read a concrete plugin version **and** both
sides are clean releases (no `-SNAPSHOT` / pre-release suffixes), so consumers
who deliberately track `latest` via the CLI's `--init-script` auto-inject —
with no plugin pinned in their build — are never tripped.

## `cli-version` values

| Value | Meaning |
|---|---|
| `auto` (default) | Pin the CLI to the plugin version detected in the checkout (catalog `[plugins]` entry or literal `id("…") version "…"`); fall back to `latest` when no plugin is pinned. **Skew-proof on the happy path** — see above. |
| `latest` | Newest published release. **Skews ahead of a pinned plugin** — see above. |
| `catalog` | Read the version from a Gradle version catalog (`catalog-path` / `catalog-key`). Use to pin the CLI to a dedicated `[versions]` key. |
| literal (e.g. `0.15.9`) | Exact release. |
| `source` | Build from the current checkout — internal CI only. |
| `none` | Skip CLI install (pair with `skip-render: true` for non-Gradle build systems). |

## Related actions

- [`install`](../install/) — just put the CLI on `$PATH`, no pipelines.
