---
name: compose-preview-review
description: "Review Compose UI pull requests by rendering @Preview composables on base and head and diffing the resulting screenshots."
category: code-quality
risk: safe
source: community
source_repo: yschimke/skills
source_type: community
date_added: "2026-06-02"
author: yschimke
tags: [android, jetpack-compose, code-review, pull-request, screenshot, ui, kotlin]
tools: [claude, cursor, gemini, codex, antigravity]
license: "Apache-2.0"
license_source: "https://github.com/yschimke/skills/blob/main/LICENSE"
---

# Compose Preview — Review

## Overview

Workflows for reviewing pull requests that touch Compose UI, authoring
agent-opened PRs that include preview screenshots, and wiring CI to post
before/after diffs automatically. It builds on the
[`compose-preview`](https://github.com/yschimke/skills/tree/main/skills/compose-preview)
skill by rendering the same `@Preview` composables on the PR's base and head
commits, then surfacing a visual diff so a reviewer can judge the *rendered*
change rather than reading the source diff alone. This is the canonical
[`compose-preview-review` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview-review)
distributed through the [compose-ai-tools](https://github.com/yschimke/compose-ai-tools)
project.

## When to Use This Skill

- Use when reviewing a pull request that changes Compose UI and you want to see
  the visual impact, not just the code.
- Use when authoring an agent-opened PR that touches UI and should ship
  before/after screenshots in the description.
- Use when wiring CI to render `compose-preview/main` baselines and post diff
  comments automatically on UI PRs.

## How It Works

### Step 1: Install the toolchain

```bash
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

This installs the `compose-preview` CLI used to render both sides of the diff.

### Step 2: Render base and head

Check out the PR's base commit, render the affected previews, then check out the
head commit and render again. The
[MCP review variant](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/mcp-review.md)
runs both as two workspaces (base + head) under a single server so an agent can
read either side on demand.

### Step 3: Diff and report

Compare the two PNG sets and attach the changed pairs to the review. For an
automated CI flow, see the
[ci-previews reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/ci-previews.md),
which sets up `compose-preview/main` baselines and posts PR diff comments.

## Examples

### Example 1: Local before/after review of a UI PR

```bash
# Base
git switch --detach origin/main
compose-preview render :feature:profile --preview com.example.ProfileScreen
# Head
git switch -
compose-preview render :feature:profile --preview com.example.ProfileScreen
# Diff the two PNGs and attach the changed pair to the review.
```

### Example 2: Two-workspace review over MCP

Attach the MCP server with a base and a head workspace, then read the same
`compose-preview://` URI from each to compare renders without re-checking-out by
hand. See the
[MCP review reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/mcp-review.md).

## Best Practices

- ✅ Render the *same* preview set on both base and head so the diff is
  apples-to-apples.
- ✅ Attach changed before/after pairs to the PR so the human reviewer sees the
  visual impact.
- ✅ Wire the CI baseline + comment flow so diffs appear automatically on UI PRs.
- ❌ Don't approve a UI change on the source diff alone — look at the render.
- ❌ Don't diff against a stale baseline; re-render the base from the PR's actual
  merge-base.

## Limitations

- This skill does not replace environment-specific validation, testing, or
  expert review.
- It surfaces visual differences; judging whether a difference is *correct* is
  still a human (or reviewing-agent) decision.
- Requires the previewed modules to apply the
  `id("ee.schimke.composeai.preview")` Gradle plugin.
- Stop and ask for clarification if required inputs, permissions, or safety
  boundaries are missing.

## Security & Safety Notes

- The installer is fetched over HTTPS and piped to `bash`. Review the
  [canonical installer](https://github.com/yschimke/skills/blob/main/scripts/install.sh)
  before running it in a sensitive environment.

<!-- security-allowlist: documented installer fetch for the compose-preview toolchain -->

- Reviewing a PR checks out and builds untrusted contributor code to render it.
  Run it in an authorized, sandboxed environment — treat rendering a PR like
  building a PR.

## Common Pitfalls

- **Problem:** Base and head renders aren't comparable.
  **Solution:** Render the identical preview FQNs on both sides and diff matching
  filenames.
- **Problem:** CI posts no diff comment.
  **Solution:** Confirm the `compose-preview/main` baseline exists and the
  workflow has permission to comment; see the
  [ci-previews reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/ci-previews.md).

## Related Skills

- `@compose-preview` - The base skill: render `@Preview` composables to PNG. Use
  it directly when you just need to see one preview rather than diff a PR.
