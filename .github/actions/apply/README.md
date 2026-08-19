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
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v1.20.0
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
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v1.20.0
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
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v1.20.0
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

## Fork PRs

The basic usage above works for PRs raised from a branch in the repository
itself. It cannot work for a PR from a **fork**, and no `permissions:` block
fixes that: on a `pull_request` event from a fork, `GITHUB_TOKEN` is read-only
for *every* scope. So the render push 403s, and the sticky comment can't be
posted either — commenting is a write too.

**`pull_request_target` is not the fix.** It hands out a write-scoped token and
the repository's secrets, and rendering previews means checking out the PR's
own code and running its Gradle build. That is arbitrary code execution with a
write token — the classic pwn request. A workflow that renders untrusted code
must never hold the token that publishes the result.

Split those two responsibilities across two workflows instead. The render job
stays unprivileged and hands its output to a publish job that runs the **base
branch's** code and never executes anything from the PR:

```yaml
# .github/workflows/compose-preview.yml — untrusted. Renders, publishes nothing.
name: Compose Preview
on:
  push:
    branches: [main]
  pull_request:
    types: [opened, synchronize, reopened]
  workflow_dispatch:

permissions: {}          # nothing by default; each job asks for what it needs

# Two `synchronize` events on one PR would otherwise race: the older render
# can finish last, and its publisher then overwrites the shared render
# branches and sticky comments with stale pixels.
concurrency:
  group: compose-preview-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  # Fork PRs: render only, in a job that is READ-ONLY BY CONSTRUCTION.
  #
  # Don't fold this back into the job below by switching `phase` on a
  # condition. GitHub normally downgrades a fork PR's token to read-only on
  # its own, but an organization can turn on "send write tokens to workflows
  # from fork pull requests" — and then a shared job's requested write scopes
  # are granted for real, while this action exports `github.token` into the
  # environment the fork's Gradle build runs in. Job-level `permissions` is
  # what makes the isolation hold regardless of that org setting.
  render-fork:
    if: github.event.pull_request.head.repo.fork
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: ./.github/actions/setup           # your java + SDK + cache composite
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v1.0.0
        with:
          phase: render
      - uses: actions/upload-artifact@v7
        with:
          name: compose-preview-handoff
          path: _compose_preview_handoff/
          if-no-files-found: error
          retention-days: 1

  # Everything trusted — same-repo PRs and the baseline push on `main` — keeps
  # the single-job path, which already holds the write token it needs.
  apply:
    if: ${{ !github.event.pull_request.head.repo.fork }}
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: ./.github/actions/setup
      - uses: yschimke/compose-ai-tools/.github/actions/apply@v1.0.0
```

```yaml
# .github/workflows/compose-preview-publish.yml — trusted. Pushes and comments.
name: Compose Preview Publish
on:
  workflow_run:
    workflows: [Compose Preview]
    types: [completed]

permissions:
  contents: write         # renders push to compose-preview/pr
  pull-requests: write    # sticky comment upsert
  actions: read           # cross-run artifact download

# Publishers can finish out of order too, so serialize them per head branch.
# NOT cancel-in-progress: a publisher that is already pushing should finish,
# and the next one supersedes it on the same shared branch anyway.
concurrency:
  group: compose-preview-publish-${{ github.event.workflow_run.head_repository.full_name }}-${{ github.event.workflow_run.head_branch }}
  cancel-in-progress: false

jobs:
  publish:
    # A failed render has nothing worth publishing, and a stale comment is
    # better than one built from a partial envelope.
    if: github.event.workflow_run.conclusion == 'success'
    runs-on: ubuntu-latest
    steps:
      # This checkout is the BASE branch's code, never the PR's. Nothing from
      # the fork is executed in this job — the handoff is read as data only.
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      # Only a fork run uploads a handoff. Same-repo runs publish inline and
      # upload nothing, so this workflow fires with nothing to do — that's the
      # common case, not an error. Deciding it from `head_repository` rather
      # than from the download's own outcome is deliberate: a blanket
      # `continue-on-error` would also swallow a genuine artifact-service or
      # auth failure on a fork run and let the publisher finish green having
      # pushed nothing.
      - id: fork
        run: |
          if [ "${{ github.event.workflow_run.head_repository.full_name }}" = "${{ github.repository }}" ]; then
            echo "is_fork=false" >> "$GITHUB_OUTPUT"
          else
            echo "is_fork=true" >> "$GITHUB_OUTPUT"
          fi

      # No continue-on-error: on a fork run this download failing is a real
      # failure, and should stay visible and re-runnable.
      - uses: actions/download-artifact@v7
        if: steps.fork.outputs.is_fork == 'true'
        with:
          name: compose-preview-handoff
          path: _compose_preview_handoff
          run-id: ${{ github.event.workflow_run.id }}
          github-token: ${{ secrets.GITHUB_TOKEN }}

      - id: pr
        if: steps.fork.outputs.is_fork == 'true'
        run: echo "number=$(cat _compose_preview_handoff/_pr_number)" >> "$GITHUB_OUTPUT"

      - uses: yschimke/compose-ai-tools/.github/actions/apply@v1.0.0
        if: steps.pr.outputs.number != ''
        with:
          phase: publish
          pr-number: ${{ steps.pr.outputs.number }}
          # Comment-shaping inputs are consumed on THIS side, so repeat any
          # you set on the render call — e.g. comment-on-empty-diff,
          # rerun-checkbox, ab-config. `only` / `skip` are the exception: the
          # render job's resolution travels in the handoff.
```

### What travels between the jobs

`phase: render` stages one directory (`handoff-dir`, default
`_compose_preview_handoff`) holding everything the publish half reads off disk:
the staged PR renders and their push metadata, the fetched baselines, the
resolved base SHA, and the per-surface staging dirs for resources / a11y /
notifications.

The directory itself contains just two entries — `handoff.tar` and
`_pr_number`. Everything else lives inside the tarball because
`actions/upload-artifact` refuses any path containing a colon, and a gradle
module path is full of them (`_pr_renders/renders/ai:sample:wear-gemini/…`).
Those directory names cannot be sanitised: `_pr_renders` is pushed verbatim to
the render branch, so its layout *is* the image URL in the comment and has to
keep matching the baselines. `_pr_number` stays outside so the caller can read
it back for `pr-number`; the publish phase unpacks the rest before anything
reads it.

Two values are in there because the publish job cannot recover them itself:

| File | Why it can't be recomputed |
|---|---|
| `_pr_number` | `github.event.workflow_run.pull_requests` is **empty for fork PRs** — the one case this whole path exists for. |
| `_scope_modules` | The publish job never checked the PR out, so it can't classify the diff. Without the render job's scope, [change-scoped runs](#change-scoped-rendering) would report every unrendered module's baseline as a *Removed* preview. |
| `_pipelines` | The resolved `only` / `skip` set. Re-resolving from the publish call's own inputs would default all four on, and the upsert for a pipeline that never ran would patch its existing sticky comment to "no changes". |
| `_ab_config` | Read from the checkout, which on the publish side is the *base* branch — so a PR that adds or edits an [A/B config](#ab-comparison-of-preview-variants) would otherwise be graded against the old grouping. |

Both sides of every comparison travel, not just the new renders: the
comparators fail closed on a missing baseline, so an absent
`_resource_baselines/renders` would turn renderer anti-aliasing noise into a
false resource diff, and a missing `_notification_baseline_findings.json` reads
as an empty baseline — reporting every surviving preview as *Added* and losing
removals entirely.

`_previews.json` is rewritten on the way in. The CLI emits **absolute**
`pngPath` values pointing into the render runner's Gradle build directories, so
the envelope is rebased onto `<handoff-dir>/current/` and the renders that are
still read on the publish side are copied there with it. Everything else in the
bundle already holds copied pixels.

### What this does and doesn't buy you

The publish job holds a write token, so it is worth being precise about what it
runs: the base branch's checkout, this action, and nothing else. It never
checks out the PR, never invokes Gradle, and never installs the CLI.

**The artifact is treated as hostile.** It was produced by a job that ran the
PR's own Gradle build, and the pipelines write their push metadata as each
surface completes — so a later pipeline, still executing fork code, can rewrite
what an earlier one staged. Push *control* therefore never comes from the
artifact: the destination branch, commit message and skip flag are rebuilt on
the publish side from the same literals the single-job path uses, so a
`_push_branch` rewritten to `main` aims nothing at the default branch. A
`.github/` tree found in a staging dir is dropped before the push, since
`push-branch.sh` commits the directory wholesale and a workflow file landing on
a render branch would run on its own `push` trigger. The staging dirs
contribute pixels; every decision is made from the action's own constants.

If you add steps of your own to the publish job, hold that line — anything read
out of the handoff is PR-authored input.

It does **not** make the render itself trusted. A fork PR still runs its own
code on the render runner; that job just has nothing worth stealing (read-only
token, no secrets). Keep `persist-credentials: false` on its checkout so the
token isn't left in `.git/config` for the build to find.

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
composePreviewPlugin = "1.20.0"

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

That fallback is now the [project version pin](../../../docs/VERSION_PIN.md)
when there is one — `composePreview.version` in `gradle.properties`, written by
`compose-preview pin`. It's what the CLI auto-injects and what the VS Code
extension applies, so an auto-inject project that used to float on `latest` now
gets the same version in CI as on a developer's machine, with no workflow change.

An explicit declaration still takes precedence: auto-inject *skips* a module
that declares the plugin, so for that module the declared version is what's
actually applied, and that's the number `auto` and the skew guard must reason
about. A project carrying both a declaration and a disagreeing pin gets a
`::warning::` naming each, since neither one describes the whole build.

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

## Figma column — the design each preview is meant to match

A preview diff answers "did these pixels move?". A design catalog exists to
answer "do these pixels match the design?", and the reviewer used to answer that
one by hand with the kit open in another tab. When a repo carries both halves of
the join, the changed / new tables grow a third column showing the design node
itself:

| Figma | Before | After |
|---|---|---|
| the kit's drawing of `Button/Compact` | last render on `compose-preview/main` | this PR's render |

Both halves are already committed artefacts, and **nothing here talks to Figma**:

- `design-map.json` — which design node a preview implements. Produced by
  [`@yschimke/compose-design-map`](../../../design-map) from
  `@CatalogComponent(reference = …)`, with per-variant nodes resolved onto it by
  a kit resolver. Both the base (`ref`/`previewId` as strings) and the resolved
  (arrays tagged by `state`) shapes are read.
- `design/pages/pages.json` + its SVGs — the imported kit pages, one SVG per
  page with `data-node-id` on every element (the v2 design-pages manifest, see
  `DesignPages.kt`). The column is cut straight out of that markup: the node's
  subtree, re-wrapped in the ancestors that carry its transform and clipping,
  rasterised, then cropped to the ink it draws and backed with the colour the
  sheet paints behind it.

Reading the cache rather than the API is the point. A fork PR gets no
`FIGMA_TOKEN`, so a live column would go missing on exactly the PRs an outside
contributor opens; the reference is pinned to what the PR's own checkout carries,
so a designer's mid-review edit can't silently rewrite what a reviewer is judging
against; and Figma sees no per-push traffic. The cost is freshness — the column
is as current as the last run of the repo's page import, and *drift* is
design-parity's job to report, on its own schedule.

Every part of it degrades to nothing. No design map, no page import, a node the
export skipped (the M3 kit's Stickersheet page is excluded for size), no
rasteriser: the comment falls back to the two-column layout it always had. So
there is nothing to switch on — commit the two files and the column appears.

| Input | Default | Meaning |
|---|---|---|
| `figma-references` | `true` | Set `false` to suppress the column even where the cache exists. |
| `design-map` | `design-map.json` | Path to the design map. |
| `design-pages` | `design/pages/pages.json` | Path to the imported page manifest. |

The reference PNGs are pushed to `compose-preview/pr` alongside the PR renders
(`figma/<module>/<previewId>.png`), so they are commit-pinned like every other
image in the comment.

## Downloadable-font cache

Previews that use `Font(GoogleFont(...))` resolve their faces through a
machine-local cache at `$XDG_CACHE_HOME/composeai/fonts` (else
`~/.cache/composeai/fonts`), keyed by `(family, weight, italic)`. On a miss the
renderer fetches the TTF from the Google Fonts CSS API — *during the render*,
inside the Robolectric JVM.

On CI that cache starts empty on every runner, so every face is re-fetched
every run. The action persists it across runs with `actions/cache` (on by
default; set `fonts-cache: false` to opt out).

This is a correctness fix more than a speed one. The CSS API can serve a
different face for the same key — a static sub-font at the exact weight, or the
family's variable TTF — and those carry different text metrics. A run that
resolved the other one renders its whole text layer shifted by a fraction of a
pixel, which shows up as a visual diff on a PR that changed nothing. Caching
the bytes means the face is chosen once instead of re-rolled per run.

The cache is append-only — a key always means the same face — so the save key
is unique per run and `restore-keys` pulls the newest prior entry forward. A
fixed key would restore but never save, and a face first seen on run N would be
re-fetched on every run after it. Restore and save are separate steps so a run
that fails *after* paying the downloads still persists them, and the rerun
starts warm.

Keys are scoped per job (`github.job`), and the restore prefixes try this job's
own lineage before falling back to any job's. Cache archives are snapshots and
are never merged, so a shared prefix would restore whichever job saved most
recently — and with the compose and a11y jobs running concurrently over
different font sets that never settles: both restore the same snapshot, each
saves its own superset, and the next run restores one of them while the other
job refetches its faces live. Per-job lineage converges to each job's own set.

Two caveats worth knowing:

- **Cache scope is per branch.** A PR run reads its own branch's cache and the
  default branch's, so the baseline runs on `main` are what keep PR runs warm.
  A brand-new face still costs one live fetch on the run that first needs it.
- **This narrows the window, it does not close it.** A face is still fetched
  live the first time it is seen, and whatever the API served then is what gets
  cached. Pinning faces by content hash — so a substitution is a hard failure
  rather than a silent metric change — needs a lockfile and is not part of this.

## Re-run checkbox

Set `rerun-checkbox: true` to put an unchecked **Re-run preview diff** item
near the top of the compose sticky comment. The action only renders the
control; the repository must handle the resulting `issue_comment: edited`
event and authorize the actor before calling GitHub's workflow-run rerun API.
Keeping this opt-in prevents consumers without that handler from receiving an
inert checkbox.

This repository's [pr-commands.yml](../../workflows/pr-commands.yml) is the
reference handler. It accepts the checkbox only on the bot-authored
`<!-- preview-diff -->` comment, checks the clicking actor has write-level
repository access, updates the same comment with an in-progress status, and
fully reruns the latest `compose-preview` workflow on the PR's current head
SHA. The new render then replaces the sticky comment as usual.

## Related actions

- [`install`](../install/) — just put the CLI on `$PATH`, no pipelines.
