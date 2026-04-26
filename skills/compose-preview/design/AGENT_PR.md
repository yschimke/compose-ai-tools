# Agent-authored PRs: authoring and reviewing

Guidance for two related tasks: writing a PR body when you (the agent) opened
the PR, and reviewing a PR opened by another agent.

## Authoring an Agent PR (body structure)

A reviewer opening an agent-authored PR should see **the goal** before they
see **what the agent did**. Structure the PR body in two sections, in this
order:

### 1. Goal — the user's prompt, lightly reworded

Paste the prompt that kicked off the work, cleaned up but **not rewritten**:

- Fix typos, expand pronouns that only make sense in chat ("it", "this"),
  resolve references to earlier turns ("like we discussed" → the actual
  constraint), strip conversational filler ("please", "can you").
- Keep the user's voice, scope, and emphasis. Don't add justifications,
  don't soften "must" into "should", don't invent acceptance criteria the
  user didn't state.
- One short paragraph, or a bulleted list if the user's prompt already had
  bullets. Not a re-specification.

This section anchors the review: every change the agent made should trace
back to something here. If the diff does something this section doesn't ask
for, that's a scope-creep flag for the reviewer.

### 2. Summary — what the agent actually did

Separate subsection (`## Summary` or `## What changed`) covering, in order:

- **Changes made** — one bullet per distinct change, file-path-anchored.
- **Things tried and abandoned** — approaches the agent rejected and why,
  so the reviewer doesn't suggest them again.
- **Known gaps** — anything from the goal the agent didn't do (and why:
  out of scope, blocked, unclear). Explicit gaps beat silent omissions.
- **Verification** — how the agent checked the change. For UI work, list
  the `@Preview` ids that were re-rendered and read, not just "tested
  locally".

Keep each bullet under ~2 lines. Reviewers skim; a 40-line wall-of-summary
defeats the point.

If a CI preview comment will be posted, don't duplicate the before/after in
the body — link to the comment instead. The body stays intent-focused; the
sticky comment carries the visuals.

## Reviewing a PR (agent workflow)

When asked to review a PR — especially one opened by another agent — your job
is to make the UI change legible to a *human* reviewer, not to re-do the
agent's work. Most repos will **not** have any preview-diff CI set up, so
assume you're rendering locally and that the human who invoked you is the
primary audience.

### 1. Render base and head locally

Use a worktree for the base so the agent's working copy stays on the PR head:

```bash
BASE=$(gh pr view <N> --json baseRefName -q .baseRefName)
git worktree add ../_pr_base "origin/$BASE"

(cd ../_pr_base && compose-preview show --json) > base.json
compose-preview show --json > head.json

git worktree remove ../_pr_base
```

Diff by preview `id` + `sha256`. Bucket into **changed**, **new**, and
**removed**. Read the PNGs for each entry in `changed` and `new` directly
from the paths in the JSON — this is what the human invoking you will read
too.

### 2. Default: show the human the diffs inline, post a text comment

Without pre-existing image hosting, the simplest flow is:

1. **Read** the before/after PNGs yourself — you now have the visual context.
2. **Summarise** the deltas in plain text for the human running the review
   (per-preview: what changed, what to look for).
3. **Post a text-only review comment** to the PR. Include preview ids,
   `sha256` (first 8 chars), and the local path so the human — or a later
   agent — can reproduce:

   ```
   ## Preview diff (rendered locally, not hosted)

   **3 changed, 1 new, 0 removed** · base `origin/main@abc1234`

   ### Changed
   - `home:HomeScreen_dark` — bg #1a1a → #0d0d0d, divider gained 1dp radius
     · sha `a1b2c3d4` → `e5f6a7b8`
   - `home:HomeScreen_fontscale_1.3` — CTA wraps to 2 lines at 1.3×
     · sha `11223344` → `99aabbcc` — **likely regression, flag**

   ### New
   - `home:HomeScreen_empty` — ➕ no baseline; verify this is intentional
     · sha `deadbeef`

   _Images not uploaded. Run `compose-preview show --filter HomeScreen --json`
   locally to reproduce._
   ```

   This is far better than silence and doesn't require any infrastructure.

### 3. Uploading images — only with explicit consent

If the human asks for images in the comment, pick **one** and confirm the
destination before acting:

- **Gist with markdown + images** (`compose-preview share-gist <md>
  [--public|--secret] [--desc TEXT] [--json] <png>...`). Default is
  `--secret` — even secret gist URLs are shareable, so still ask before
  posting one in a public PR comment, and only use `--public` on
  explicit request. Wraps the gist-as-git-repo dance: `gh gist create`
  rejects binary files, so the CLI seeds a text-only gist with the
  markdown then pushes the images into the gist's git repo. Output is
  the gist URL plus stable raw URLs you can drop into the PR comment;
  `--json` emits a `compose-preview-share-gist/v1` envelope. The
  markdown should reference images by basename
  (`![](before.png)`) — the command does not rewrite paths. Requires
  `gh` and `git` on PATH and a `git config user.name|user.email` set
  somewhere in the global/local chain (used only as the commit
  identity in the throwaway clone).
- **Dedicated branch in the repo** (`compose-preview publish-images
  DIR [--branch preview_pr] [--remote origin] [--pr-number N] [--json]`).
  Pushes the staging directory's contents as a single commit on the
  shared `preview_pr` branch, mirroring what the `preview-comment` CI
  integration does — same retry-on-race loop for parallel pushes from
  sibling PRs. Output is the resulting commit SHA and a raw URL pattern
  (`https://raw.githubusercontent.com/<owner>/<repo>/<sha>/{path}`)
  that pins images to the SHA so they survive merges. `--json` emits a
  `compose-preview-publish-images/v1` envelope. Prefer this over the
  gist when you want clean GitHub-hosted URLs and the user's confirmed
  they want a branch created — the CLI does NOT auto-detect prior
  consent; it just runs the push.
- **Issue/PR attachment upload** — not reliably available via `gh`; skip.

Never use inline base64 or data URIs — GitHub strips them. Never push images
to a public branch, gist, or external host without the user explicitly
saying yes in the chat.

### 4. Write the comment for a human, not a log file

Whether or not there are images, the review comment is what the reviewer
will actually read. Optimise for scanning:

- **Lead with a one-line count**: `N changed · N new · N removed ·
  N unchanged` (per module if >1 module is touched). Reviewers decide
  whether to expand based on this line.
- **Only show deltas.** Unchanged previews go behind a collapsed `<details>`
  or get omitted entirely. Never post a wall of identical thumbnails.
- **Separate new/removed from changed.** New previews have no baseline and
  are easy to miss in a before/after layout — give them their own section
  with a ➕ marker. Same for removed (🗑️).
- **Side-by-side tables for changed** (when images are hosted): two-column
  markdown table, Before | After, identical widths, preview id as the row
  heading linked to the `@Preview` source line
  (`<repo>/blob/<sha>/<path>#L<line>`).
- **Group by module** and collapse with `<details><summary>` when there are
  more than ~5 previews in a bucket. First group expanded, rest collapsed.
- **Flag a11y regressions separately.** Diff `a11yFindings[]` against the
  base; a finding new on this PR is worth a 🔴 callout even if the PNG diff
  is cosmetic. Link the annotated PNG (or include its local path if no
  hosting).
- **Caption with `sha256` (first 8 chars) and byte size** under each image.
  Lets the reviewer confirm what they're looking at matches the manifest.
- **Respect the 65 536-char comment limit.** If the body would overflow,
  split into summary + per-module detail comments.
- **Don't editorialise the UI.** Describe *what* changed ("button radius
  4 → 12 dp, new ripple on press"), not whether it looks good — let the
  human make the aesthetic call.

### 5. Things worth flagging in the review text

Agent-authored PRs have predictable failure modes that the preview diff
surfaces:

- **New preview with no baseline → is it a real new surface, or did a rename
  strand an old id?** Check `removed` for a plausible predecessor.
- **Preview unchanged but source changed** → the composable may be guarded
  behind a param default the preview doesn't exercise. Ask for a preview
  variant that hits the new path.
- **A11y findings grew** → often a regression from hard-coded colours,
  missing `contentDescription`, or touch targets shrunk to match a redesign.
- **Scroll/animation flakes** in `captures[]` — if `changed: true` toggles
  run-to-run without code changes, the preview is non-deterministic; flag
  it rather than rubber-stamp the diff.

### 6. Optional: integrate with `preview-comment` CI (rare)

A small number of repos wire up the `preview-comment` GitHub Action (see
[CI_PREVIEWS.md](CI_PREVIEWS.md)). When it's installed, it posts a sticky
comment keyed by `<!-- preview-diff -->` with before/after images hosted
on a shared `preview_pr` branch, pinned to commit SHAs so they survive
merge.

Only do this if you've already confirmed it exists:

```bash
gh pr view <N> --json comments \
  --jq '.comments[] | select(.body | startswith("<!-- preview-diff -->")) | .body'
```

If it's there, **read it first** and cite it in your review rather than
rendering again. If you do post your own comment alongside it, reuse the
`<!-- preview-diff -->` marker (or a distinct one like
`<!-- preview-diff-local -->`) so repeat reviews update one comment instead
of stacking:

```bash
MARKER="<!-- preview-diff-local -->"
ID=$(gh api "repos/$REPO/issues/$PR/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$MARKER\")) | .id" | head -1)
if [ -n "$ID" ]; then
  gh api "repos/$REPO/issues/comments/$ID" -X PATCH -f body="$BODY"
else
  gh pr comment "$PR" --body "$BODY"
fi
```

Assume this path is **not** available unless you've just checked.
