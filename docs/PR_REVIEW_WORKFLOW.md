# Reusable PR review workflow (preview-gated)

Use `.github/workflows/codex-pr-review-reusable.yml` to run AI PR review
**only after** preview generation succeeds, and with both code + visual
context. The reusable workflow supports Codex, Claude, or Gemini based on
which API key is configured (exactly one).

## Minimal caller setup

This repository wires the reusable workflow in
`.github/workflows/codex-pr-review.yml` using a `preview` job plus a thin
`uses:` call to the reusable workflow.

In this repository the unified `.github/workflows/compose-preview.yml` runs on
every push to `main`, every PR, and `workflow_dispatch`. Consumer repos can
split that into separate workflows (or keep one workflow per surface) based on
their needs.

```yaml
name: PR Review (Codex + Preview)

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  preview:
    runs-on: ubuntu-latest
    outputs:
      preview_status: ${{ job.status }}
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew :app:composePreviewRenderAll
      - uses: actions/upload-artifact@v4
        with:
          name: compose-preview-images
          path: app/build/compose-previews/renders
      - uses: actions/upload-artifact@v4
        with:
          name: compose-preview-diff-images
          path: app/build/compose-previews/diffs
      - uses: actions/upload-artifact@v4
        with:
          name: compose-preview-metadata
          path: app/build/compose-previews/**/*.json

  codex-review:
    needs: [preview]
    uses: yschimke/compose-ai-tools/.github/workflows/codex-pr-review-reusable.yml@main
    with:
      preview_status: ${{ needs.preview.result }}
      strict_mode: true
    secrets:
      codex_api_key: ${{ secrets.CODEX_API_KEY }}
      claude_api_key: ${{ secrets.CLAUDE_API_KEY }}
      gemini_api_key: ${{ secrets.GEMINI_API_KEY }}
      github_token: ${{ secrets.GITHUB_TOKEN }}
```

## Artifact contract

- Agent selection is key-driven: set exactly one of `codex_api_key`, `claude_api_key`, or `gemini_api_key`.
- Codex has a built-in default command. Claude/Gemini require `claude_review_command` / `gemini_review_command` inputs unless you wrap them in your own caller.
- `compose-preview-images`: rendered head/PR preview images.
- `compose-preview-diff-images`: visual diffs (baseline vs PR/head), if your preview pipeline generates them.
- `compose-preview-baseline`: optional baseline images used to generate diffs.
- `compose-preview-metadata`: preview index and mapping files (for example: preview id → file path/module).

## Runtime / toolchain provided by reusable workflow

- Java 21 (`actions/setup-java`, `JAVA_HOME` from the action).
- Android SDK (`android-actions/setup-android`).
- `compose-preview-review` skill installation from `yschimke/skills`.
- Code diff capture (`git diff`) plus artifact download for visual review.

## Failure modes / troubleshooting

- **Preview failed/cancelled/skipped**: workflow posts a blocked comment and does not run Codex visual review.
- **Artifacts missing**: workflow posts a blocked comment with “missing context” details.
- **Strict mode enabled** + blocking findings: reusable workflow fails its check.
- **PR branch update** (`update_pr_branch`, default `true`): workflow attempts to commit `.codex/review-output/{codex-review.md,codex-review.json}` to the PR branch; for fork PRs or restricted tokens it skips with a warning.
- **No preview diffs available**: Codex still reviews code + available preview images and explicitly marks missing visual-diff context.

## Optional integration patterns

- `needs:` pattern (shown above): same workflow, same run.
- `workflow_run` pattern: trigger a second workflow after preview workflow completion and pass `preview_status: success` plus artifact names into the reusable workflow call.

## Example review comment template

```md
## Codex PR Review

### Code findings
- [blocking] `ui/ProfileCard.kt:84` Null-state branch removed; can crash in empty profile payload.
- [warning] `ui/Theme.kt:42` Hard-coded color bypasses design token.

### Preview findings
- [blocking] `ProfileCard_Default.png` text overlaps avatar at 320dp width.
- [warning] `SettingsScreen_Dark.png` contrast drop on secondary action.

### Missing context / blocked checks
- Baseline metadata for `WearSummaryPreview` missing.
- Visual diff for `TabletLandscape` not present in uploaded artifacts.
```

## Validation recipe with intentional bad UI change

1. Intentionally regress a composable (for example, shrink parent width and increase fixed text size to force clipping).
2. Run your preview job to regenerate preview images and visual diffs.
3. Open/update a PR and confirm:
   - Preview job succeeds.
   - Reusable Codex workflow runs after preview (`needs`/`workflow_run` gate).
   - PR comment includes both code and preview findings.
   - In strict mode, blocking visual regression findings fail the check.
</content>
