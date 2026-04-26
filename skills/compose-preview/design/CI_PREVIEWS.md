# CI preview baselines (`preview_main` branch)

Projects that use the Gradle plugin can set up CI workflows to maintain a
`preview_main` branch with rendered PNGs and a `baselines.json` file (preview
ID → SHA-256). This serves two purposes:

1. **Browsable gallery** — the branch has a `README.md` with inline images,
   viewable directly on GitHub.
2. **PR diff comments** — a companion workflow renders previews on each PR,
   compares against the baselines, and posts a before/after comment.

## Checking if baselines are available

```bash
git ls-remote --exit-code origin preview_main
```

## Fetching current main previews

```bash
# Get the baselines manifest
git fetch origin preview_main
git show origin/preview_main:baselines.json

# Get a specific rendered PNG
git show origin/preview_main:renders/<module>/<preview-id>.png > preview.png
```

Or via raw URL:
```
https://raw.githubusercontent.com/<owner>/<repo>/preview_main/renders/<module>/<preview-id>.png
```

## Installing the CLI in your workflow

Use the [`install` composite action](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/install)
to put the `compose-preview` CLI on `$PATH`. Pin to a tagged version of
this repo so consumer CI isn't exposed to changes on `main`:

<!-- x-release-please-start-version -->
```yaml
- uses: actions/setup-java@v5
  with:
    distribution: temurin
    java-version: 17
- uses: yschimke/compose-ai-tools/.github/actions/install@v0.8.1
  with:
    version: catalog   # or "latest", or a literal "0.8.1"
```
<!-- x-release-please-end -->

`version: catalog` reads the `composePreviewCli` key from
`gradle/libs.versions.toml`; pair it with the Renovate `customManager`
snippet in the [README](../../../README.md#on-github-actions) to keep
the CLI version in lockstep with the rest of the project's toolchain.

After this step the `compose-preview` binary is on `$PATH` for the
remainder of the job. Render previews with:

```bash
compose-preview show --json --timeout 600 > _previews.json
```

## Wiring up the full pipeline

Two workflows do the work — render and push baselines on `main`,
render and post a comment on PRs. The reference implementations live
in this repo's own `.github/workflows/preview-baselines.yml` and
`.github/workflows/preview-comment.yml`; the post-render bookkeeping
(baselines layout, PR-comment generation, fast-forward push to
`preview_main` / `preview_pr`) currently lives as Python in
[`.github/actions/lib/compare-previews.py`](https://github.com/yschimke/compose-ai-tools/blob/main/.github/actions/lib/compare-previews.py).

Until that bookkeeping ships as a consumer-callable composite or as
`compose-preview baselines …` CLI subcommands (sketched in the
"Follow-up" section of
[#227](https://github.com/yschimke/compose-ai-tools/issues/227)),
copy those reference workflows into your project as the starting
point and adapt to taste.

## Branch durability

The `preview-comment` workflow appends a commit to a shared
`preview_pr` branch (one commit per PR push, tree = that PR's changed
PNGs). The PR comment pins Before/After `<img>` URLs to commit SHAs
on `preview_main` and `preview_pr`, not branch names — so images keep
resolving after the PR merges and after later PRs advance either
branch. Neither branch is ever force-pushed; old commits stay
reachable via branch history.
