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
  with:
    cli-version: catalog          # track the plugin — see "Version skew" below
    catalog-key: composePreviewPlugin
```
<!-- x-release-please-end -->

On a `pull_request` this renders the PR and posts before/after comparison
comments; on a `push` to the development branch (`main` by default) it
refreshes the baselines. See [`action.yml`](action.yml) for the full input
schema — there are ~20 inputs covering pipeline selection (`only` / `skip`),
per-module allow/deny lists, the missing-render policy, and non-Gradle
`skip-render` integration.

## Version skew

> **The single most common way this action breaks.** Pinning the action ref
> (`apply@vX`) does **not** pin the CLI. `cli-version` defaults to `latest`,
> so every new compose-ai-tools release auto-installs the newest CLI against
> your still-pinned Gradle plugin. A CLI **newer** than the applied plugin
> can't discover that older plugin, so the render finds zero preview modules
> and the pipeline fails repo-wide — on a release, not on your diff — with a
> misleading:
>
> ```
> ✗ no modules have the compose-preview plugin applied
> compose pipeline: No preview modules discovered ... skipping.
> ```

Pin the CLI to the **same source of truth as the plugin** so the two can't
skew. Declare the plugin version once in your version catalog and point the
action at it:

<!-- x-release-please-start-version -->
```toml
# gradle/libs.versions.toml
[versions]
composePreviewPlugin = "0.15.9"

[plugins]
composePreview = { id = "ee.schimke.composeai.preview", version.ref = "composePreviewPlugin" }
```

```yaml
- uses: yschimke/compose-ai-tools/.github/actions/apply@v0.15.9
  with:
    cli-version: catalog            # reads the version below from the catalog
    catalog-key: composePreviewPlugin
```
<!-- x-release-please-end -->

[Renovate](https://docs.renovatebot.com/) then bumps the one `[versions]`
entry on each release and the CLI follows automatically. (The same
catalog/Renovate recipe is documented for the standalone
[`install`](../install/README.md#pin-the-cli-to-a-gradle-version-catalog)
action.)

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
| `latest` (current default) | Newest published release. **Skews ahead of a pinned plugin** — see above. |
| `catalog` | Read the version from a Gradle version catalog (`catalog-path` / `catalog-key`). Recommended. |
| literal (e.g. `0.15.9`) | Exact release. |
| `source` | Build from the current checkout — internal CI only. |
| `none` | Skip CLI install (pair with `skip-render: true` for non-Gradle build systems). |

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
