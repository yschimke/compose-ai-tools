# `apply` — compose-preview CI in one step

Unified composite action for the compose-preview CI surface. Auto-selects
baseline vs. comment mode from the triggering event, runs the four
pipelines (compose / resources / a11y / notifications) back-to-back, and
pushes baselines / PR renders + posts sticky PR comments. Replaces the
per-surface `preview-baselines` / `preview-comment` / `a11y-report` /
`notification-previews` composites (now thin, deprecated forwarders).

## Basic usage

Drop this in `.github/workflows/compose-preview.yml`. On a `pull_request` it
renders the PR and posts before/after comparison comments; on a `push` to the
development branch (`main` by default) it refreshes the committed baselines:

<!-- x-release-please-start-version -->
```yaml
name: Compose Preview

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
    types: [opened, synchronize, reopened]
  workflow_dispatch:

# Required — the action pushes baselines (contents) and posts sticky
# before/after PR comments (pull-requests). Omit these and the run fails.
permissions:
  contents: write
  pull-requests: write

concurrency:
  # Per-PR group so concurrent PRs don't cancel each other; push + dispatch
  # share one slot per ref.
  group: compose-preview-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  apply:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17
      # Android modules also need the SDK — add android-actions/setup-android@v3
      # (and a Gradle cache) here, or factor java+SDK+cache into a local
      # `./.github/actions/setup` composite as the reference workflows do.
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v0.17.0
```
<!-- x-release-please-end -->

The `permissions` block is **not optional**: without `contents: write` the
action can't push baselines and without `pull-requests: write` it can't post
the comparison comment, so the run fails.

`cli-version` defaults to **`auto`**, which pins the CLI to the plugin version
you already pin in your checkout — so the version skew described below can't
happen on the happy path, with no extra config. See
[Version skew](#version-skew) for what `auto` does and when to override it.
(You may see older consumer workflows pass `cli-version: catalog` with
`catalog-key: composePreviewPlugin`; `auto` reads the same pinned version off
your `[plugins]` entry with no `catalog-key`, so prefer it.)

See [`action.yml`](action.yml) for the full input schema — there are ~20 inputs
covering pipeline selection (`only` / `skip`), per-module allow/deny lists, the
missing-render policy, and non-Gradle `skip-render` integration.

## Running the pipelines in parallel

The single step above runs all four pipelines
(compose / resources / a11y / notifications) back-to-back in one job. For a
larger preview suite, split them across independent jobs so wall time drops
from the sum of the pipelines to the slowest one — this is how the reference
[`compose-preview.yml`](../../workflows/compose-preview.yml) is wired:

<!-- x-release-please-start-version -->
```yaml
jobs:
  apply:
    name: Compose previews
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: ./.github/actions/setup           # your java + SDK + cache composite
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v0.17.0
        with:
          only: compose,resources
          # `warn` keeps CI green when a handful of previews render nothing;
          # the default `fail` gates every render (see missing-renders below).
          missing-renders: warn

  a11y:
    name: A11y previews
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: ./.github/actions/setup
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v0.17.0
        with:
          # a11y renders first, then notifications stages the captures it
          # leaves behind — so the two must share a job (see below). Drop
          # `,notifications` only if you render no notification previews.
          only: a11y,notifications
          missing-renders: warn
```
<!-- x-release-please-end -->

Keep **compose + resources** together (resources is cheap and reuses the
compose run's base SHA), and keep **notifications in the same job as — and
after — a11y**: `apply` runs a11y first, and the notifications pipeline stages
the renders the a11y pass leaves in the shared workspace, so splitting them
onto separate runners makes notifications report every a11y-produced capture as
removed. (`only: a11y` alone silently drops the notifications pipeline, since
`only` clears every surface it doesn't name — so keep both unless you
deliberately render no notification previews.) The two jobs use disjoint
baseline branches and sticky-comment markers, so they never collide.

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
composePreviewPlugin = "0.17.0"

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

## Change-scoped rendering

By default every PR run renders **every** preview module — correct, but the
full render dominates CI latency even for a one-module change. Drop a JSON
file at `.github/preview-scope.json` (override the path with the
`scope-config` input) and PR comment runs classify the diff first:

```json
{
  "scopedRoots": ["samples"],
  "ignorePaths": ["docs/**", "**/*.md"]
}
```

| Field | Meaning |
|---|---|
| `scopedRoots` | Directory prefixes whose modules are eligible for scoping. A changed file inside a module under one of these roots scopes the render to that module **plus every module that (transitively) depends on it** — the dependency graph is read from Gradle itself (`scope-project-graph.init.gradle`), so a shared-module change can never skip its dependents. |
| `ignorePaths` | `**`-style globs (repo-relative) for files that provably cannot change a rendered preview (docs, licence, editor config). A PR that only touches these skips the pipelines entirely. Module ownership wins over `ignorePaths`: a markdown file *inside* a scoped module still scopes that module in, since module content can be fixture data. |

Everything else is **fail-open — full render**: a changed file outside the
scoped roots (build scripts, version catalogs, the plugin/CLI in this repo,
CI config), a file that can't be attributed to a module (deleted module,
loose file), a failed or missing Gradle graph probe, or a build with no
statically applied compose-preview plugin (the CLI auto-inject path, where
render modules can't be determined statically). Baseline runs (push to the
development branch) and `workflow_dispatch` reruns always render everything,
and `scope: full` forces it per invocation.

Semantics of a scoped run:

- Only the **compose** pipeline renders scoped (per-module
  `compose-preview show --module`, envelopes merged). The resources / a11y /
  notifications pipelines treat a partial scope as a full run; only the
  "nothing render-affecting changed" case skips them.
- The PR comment carries a `Change-scoped run: …` note, and baseline entries
  for out-of-scope modules are treated as **unchanged**, never "Removed" —
  they simply weren't rendered. Removals *inside* scoped modules are still
  detected.
- When the diff scopes to nothing but a previous push already posted sticky
  comments, the run falls back to full so the stale comment is refreshed to
  its resolved state instead of being left behind.

A missing config file turns the feature off (purely additive), matching the
A/B config pattern below.

## A/B comparison of preview variants

By default the comment and gallery show one "hero" render per function and
collapse its other variants into "Other variants" links — a vertical, one-at-a-time
read. When you want to compare two specific variants of the *same* function
directly (an A/B test of a design or copy change), nominate them in a config
file and they render **side-by-side horizontally** instead.

The two variants can come from either source the discovery layer already
distinguishes by a preview-id suffix:

- **Two `@Preview` annotations** on one composable (including ones contributed
  by a multi-preview meta-annotation), differentiated by `@Preview(name = …)`:

  ```kotlin
  @Preview(name = "Control")
  @Preview(name = "Treatment")
  @Composable fun ButtonPreview() { … }
  ```

- **Two values of a `@PreviewParameter`** provider (matched by the
  `_PARAM_<index>` suffix).

### Config file

Drop a JSON file at `.github/preview-abtest.json` (override the path with the
`ab-config` input). It's read by the compare (PR comment) and generate
(baseline gallery) steps; a missing / malformed file is a no-op, so the
feature is purely additive.

```json
{
  "groups": [
    {
      "function": "ButtonPreview",
      "module": "app",
      "variants": ["Control", "Treatment"],
      "label": "Button copy"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `function` | yes | Composable function name (not the FQN). |
| `variants` | yes | ≥ 2 variant tokens — each the preview-id suffix (`@Preview` name, group, or `PARAM_<n>`). Columns render in this order. |
| `module` | no | Restrict the match to one module; any module when omitted. |
| `label` | no | Heading shown above the side-by-side table. |

In the PR comment the group renders as a single table — variant tokens as
columns, `Before` / `After` as rows — so the variants sit next to each other.
A group is only surfaced when at least one of its variants actually changed
(or is new), so unchanged A/B groups don't post a comment on no-op PRs. The
non-nominated variants of the same function keep the historical "Other
variants" treatment.

## Related actions

- [`install`](../install/) — just put the CLI on `$PATH`, no pipelines.
