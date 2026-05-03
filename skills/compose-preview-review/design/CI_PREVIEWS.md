# CI preview baselines (`compose-preview/main` branch)

Projects that use the Gradle plugin can wire up two GitHub Actions
workflows to maintain a `compose-preview/main` branch with rendered PNGs
and a `baselines.json` file (preview ID → SHA-256). This serves two
purposes:

1. **Browsable gallery** — the branch has a `README.md` with inline images,
   viewable directly on GitHub.
2. **PR diff comments** — a companion workflow renders previews on each PR,
   compares against the baselines, and posts a before/after comment.

Both workflows ship from this repo as composite actions. Add two
workflow files to your project; you're done.

## Optional a11y overlay report

Use `.github/actions/a11y-report` when you want a separate accessibility
gallery. On pushes it renders with the built-in a11y hierarchy, ATF checks,
and overlay annotation path enabled, then appends the annotated PNGs and
`findings.json` to `compose-preview/a11y/main`. On pull requests it writes
to `compose-preview/a11y/pr` and upserts a `<!-- a11y-report -->` comment.

The action deliberately uses Gradle overrides instead of requiring every
sample or app module to opt in permanently:

```bash
./gradlew ":samples:wear:renderAllPreviews" \
  -PcomposePreview.previewExtensions.a11y.enableAllChecks=true \
  -PcomposePreview.previewExtensions.a11y.annotateScreenshots=true
```

For local agent review outside CI, prefer the equivalent extension command:

```bash
compose-preview extensions run a11y-annotated-preview.render --module samples:wear --json
```

Then read `a11yAnnotatedPath` for the selected preview. A populated a11y
baseline branch should contain `.a11y.png` files next to the clean PNGs; if
the README only links clean PNGs, the a11y render path did not run.

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
      - uses: yschimke/compose-ai-tools/.github/actions/preview-baselines@v0.8.12
        with:
          cli-version: catalog   # or "latest", or a literal "0.8.12"
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
      contents: write          # appends to compose-preview/pr branch
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
      - uses: yschimke/compose-ai-tools/.github/actions/preview-comment@v0.8.12
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
| `branch` | `compose-preview/main` | Branch the baselines push to. |

`preview-comment`:

| Input | Default | Purpose |
| --- | --- | --- |
| `cli-version` | `latest` | CLI version (literal / `latest` / `catalog`). |
| `catalog-path` | `gradle/libs.versions.toml` | Catalog file when `cli-version=catalog`. |
| `catalog-key` | `composePreviewCli` | `[versions]` key when `cli-version=catalog`. |
| `timeout` | `600` | CLI render timeout in seconds. |
| `base-branch` | `compose-preview/main` | Branch the baselines were pushed to. |
| `head-branch` | `compose-preview/pr` | Shared branch for per-PR render commits. |
| `pr-number` | (event) | PR number, auto-detected from the `pull_request` event. |

## Mobile readability

A lot of PR review happens on phones, where GitHub renders comment tables
inline and any wide row forces horizontal scrolling. Long fully-qualified
function or class names are the usual offender. When tweaking the comment
generator (or building similar before/after reports), keep the layout
vertical-friendly:

- Columns are still fine — the goal isn't to drop the table, just to keep
  rows narrow enough to fit on a phone.
- Put the image on the left as a small thumbnail (e.g. `width="120"`) and
  wrap it in a link to the full-size PNG so reviewers can tap through for
  pixel detail.
- Trim package names — the simple class / function name plus the module
  heading is usually enough to disambiguate. Stash the FQN in a `<details>`
  block or `title=` attribute if it's worth keeping.

## Querying baselines outside CI

```bash
git ls-remote --exit-code origin compose-preview/main          # check existence
git fetch origin compose-preview/main
git show origin/compose-preview/main:baselines.json            # read manifest
git show origin/compose-preview/main:renders/<module>/<id>.png # read PNG
```

Or via raw URL:

```
https://raw.githubusercontent.com/<owner>/<repo>/compose-preview/main/renders/<module>/<id>.png
```

## Branch durability

Both `compose-preview/main` and `compose-preview/pr` are append-only:

- `preview-baselines` adds one commit per push to `main` (parented on
  the previous tip; skipped when the rendered tree is unchanged). A
  fast-forward push on a serialised concurrency group means no
  rewrites.
- `preview-comment` appends one commit per PR push to
  `compose-preview/pr` (tree = that PR's changed PNGs). The PR comment
  pins `<img>` URLs to commit SHAs on `compose-preview/main` and
  `compose-preview/pr`, not branch names — so images keep resolving
  after the PR merges and after later PRs advance either branch.

## Local persistent state: `.compose-preview-history/fonts/`

CI keeps long-lived state on the `compose-preview/main` and
`compose-preview/pr` branches. There's also a per-module local cache —
`<module>/.compose-preview-history/fonts/`, deliberately outside
`build/` so it survives `./gradlew clean`. The directory holds the
**downloadable-font cache**, populated automatically the first time a
preview pulls a Google Font — no opt-in needed. Cached here so
repeated renders after `clean` don't re-download, and so
`-PcomposePreview.fontsOffline=true` can serve cache misses without
hitting the network.

The cache is designed to be **committed to git** when reproducibility
matters (network-free renders, byte-stable baselines). The samples in
this repo commit their cached `fonts/` so CI doesn't depend on
`fonts.gstatic.com` being reachable.

If your project doesn't want this, `.gitignore` the directory — the
choice is between local reproducibility and a smaller working tree.
Agents and humans should expect to encounter the directory at the
module root rather than under `build/`; it's the one deliberate
exception to the otherwise-true "writes go under gitignored `build/`"
framing the SKILL uses. (The dirname is historical: an earlier
preview-history feature shared this root; it has since been removed
and the cache is the only writer.)
