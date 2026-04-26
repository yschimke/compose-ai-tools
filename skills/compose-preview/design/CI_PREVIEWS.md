# CI preview baselines (`preview_main` branch)

Projects that use the Gradle plugin can wire up two GitHub Actions
workflows to maintain a `preview_main` branch with rendered PNGs and a
`baselines.json` file (preview ID → SHA-256). This serves two purposes:

1. **Browsable gallery** — the branch has a `README.md` with inline images,
   viewable directly on GitHub.
2. **PR diff comments** — a companion workflow renders previews on each PR,
   compares against the baselines, and posts a before/after comment.

Both workflows ship from this repo as composite actions. Add two
workflow files to your project; you're done.

## Workflow 1 — update baselines on push to `main`

<!-- x-release-please-start-version -->
```yaml
# .github/workflows/preview-baselines.yml
name: Preview Baselines
on:
  push:
    branches: [main]
  workflow_dispatch:
permissions:
  contents: write
concurrency:
  group: preview-baselines
  cancel-in-progress: true
jobs:
  update:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          persist-credentials: false
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v6
      - uses: yschimke/compose-ai-tools/.github/actions/preview-baselines@v0.8.5
        with:
          cli-version: catalog   # or "latest", or a literal "0.8.5"
```
<!-- x-release-please-end -->

## Workflow 2 — post before/after comments on PRs

<!-- x-release-please-start-version -->
```yaml
# .github/workflows/preview-comment.yml
name: Preview Comment
on:
  pull_request:
    branches: [main]
    types: [opened, synchronize]
permissions:
  contents: read
concurrency:
  group: preview-comment-${{ github.event.pull_request.number }}
  cancel-in-progress: true
jobs:
  compare:
    runs-on: ubuntu-latest
    permissions:
      contents: write          # appends to preview_pr branch
      pull-requests: write     # upserts the PR comment
    steps:
      - uses: actions/checkout@v6
        with:
          persist-credentials: false
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v6
      - uses: yschimke/compose-ai-tools/.github/actions/preview-comment@v0.8.5
        with:
          cli-version: catalog
```
<!-- x-release-please-end -->

## Pinning the CLI version

`cli-version` accepts:

- A literal string (e.g. `"0.8.2"`) — pinned, deterministic.
- `latest` — resolved via the GitHub releases API on each run.
- `catalog` — read the `composePreviewCli` key from
  `gradle/libs.versions.toml`. Pair with the Renovate `customManager`
  snippet in the [README](../../../README.md#on-github-actions) to keep
  the version bumped on releases.

`catalog-path` and `catalog-key` override the catalog location and key
when needed.

## Inputs at a glance

`preview-baselines`:

| Input | Default | Purpose |
| --- | --- | --- |
| `cli-version` | `latest` | CLI version (literal / `latest` / `catalog`). |
| `catalog-path` | `gradle/libs.versions.toml` | Catalog file when `cli-version=catalog`. |
| `catalog-key` | `composePreviewCli` | `[versions]` key when `cli-version=catalog`. |
| `timeout` | `600` | CLI render timeout in seconds. |
| `branch` | `preview_main` | Branch the baselines push to. |

`preview-comment`:

| Input | Default | Purpose |
| --- | --- | --- |
| `cli-version` | `latest` | CLI version (literal / `latest` / `catalog`). |
| `catalog-path` | `gradle/libs.versions.toml` | Catalog file when `cli-version=catalog`. |
| `catalog-key` | `composePreviewCli` | `[versions]` key when `cli-version=catalog`. |
| `timeout` | `600` | CLI render timeout in seconds. |
| `base-branch` | `preview_main` | Branch the baselines were pushed to. |
| `head-branch` | `preview_pr` | Shared branch for per-PR render commits. |
| `pr-number` | (event) | PR number, auto-detected from the `pull_request` event. |

## Querying baselines outside CI

```bash
git ls-remote --exit-code origin preview_main          # check existence
git fetch origin preview_main
git show origin/preview_main:baselines.json            # read manifest
git show origin/preview_main:renders/<module>/<id>.png # read PNG
```

Or via raw URL:

```
https://raw.githubusercontent.com/<owner>/<repo>/preview_main/renders/<module>/<id>.png
```

## Branch durability

Both `preview_main` and `preview_pr` are append-only:

- `preview-baselines` adds one commit per push to `main` (parented on
  the previous tip; skipped when the rendered tree is unchanged). A
  fast-forward push on a serialised concurrency group means no
  rewrites.
- `preview-comment` appends one commit per PR push to `preview_pr`
  (tree = that PR's changed PNGs). The PR comment pins `<img>` URLs
  to commit SHAs on `preview_main` and `preview_pr`, not branch
  names — so images keep resolving after the PR merges and after
  later PRs advance either branch.
