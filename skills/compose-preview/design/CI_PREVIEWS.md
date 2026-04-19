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

## Adding preview CI to your project

The actions are published as composite GitHub Actions. Add two workflow files
to your project:

**`.github/workflows/preview-baselines.yml`** — updates `preview_main` on push
to main:

```yaml
name: Preview Baselines
on:
  push:
    branches: [main]
permissions:
  contents: write
jobs:
  update:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - uses: yschimke/compose-ai-tools/.github/actions/preview-baselines@main
```

**`.github/workflows/preview-comment.yml`** — posts before/after comments on
PRs and cleans up on close:

```yaml
name: Preview Comment
on:
  pull_request:
    types: [opened, synchronize, closed]
permissions:
  contents: write
  pull-requests: write
jobs:
  compare:
    if: github.event.action != 'closed'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - uses: yschimke/compose-ai-tools/.github/actions/preview-comment@main
  cleanup:
    if: github.event.action == 'closed'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: yschimke/compose-ai-tools/.github/actions/preview-cleanup@main
```

The actions download the `compose-preview` CLI from the latest release,
auto-discover all modules that apply the plugin, and handle the baselines
branch and PR comment lifecycle. Gradle build caching via `setup-gradle`
keeps subsequent renders fast.
