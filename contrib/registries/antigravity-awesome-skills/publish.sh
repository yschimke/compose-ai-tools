#!/usr/bin/env bash
#
# Publish the compose-preview / compose-preview-review skill bundles to the
# antigravity-awesome-skills registry (https://github.com/sickn33/antigravity-awesome-skills).
#
# The registry is a "source-only" community repo: contributors add
# skills/<name>/SKILL.md files and a maintainer's tooling regenerates the
# derived artifacts (skills_index.json, CATALOG.md, ...). So this script only
# ever ships the two SKILL.md files that live next to it under skills/.
#
# Modes
# -----
#   DRY_RUN=1   Clone the target, drop our skills in, run the upstream
#               validator, and stop. No network writes. Safe to run anywhere,
#               including from the compose-ai-tools CI on every push.
#
#   (default)   Validate, then push to a fork we control (AAS_FORK_REPO) and
#               open a pull request against AAS_TARGET_REPO. Requires GH_TOKEN
#               with push rights to the fork.
#
# Environment
# -----------
#   TAG_NAME         Release tag, used in the branch name + PR title. Default: dev.
#   AAS_TARGET_REPO  Upstream registry.   Default: sickn33/antigravity-awesome-skills
#   AAS_FORK_REPO    Fork we can push to. Default: yschimke/antigravity-awesome-skills
#   AAS_PR_BRANCH    Branch to push.      Default: compose-preview-skills-<TAG_NAME>
#   GH_TOKEN         Token with push to the fork + PR-open on the target (write mode).
#   AAS_GIT_NAME     Commit author/committer name  (write mode).
#   AAS_GIT_EMAIL    Commit author/committer email (write mode; must be a human, not a bot).
#   DRY_RUN          "1" to validate only.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILLS_SRC="$SCRIPT_DIR/skills"

TAG_NAME="${TAG_NAME:-dev}"
AAS_TARGET_REPO="${AAS_TARGET_REPO:-sickn33/antigravity-awesome-skills}"
AAS_FORK_REPO="${AAS_FORK_REPO:-yschimke/antigravity-awesome-skills}"
AAS_PR_BRANCH="${AAS_PR_BRANCH:-compose-preview-skills-${TAG_NAME}}"
DRY_RUN="${DRY_RUN:-0}"

log() { printf '==> %s\n' "$*" >&2; }

# The two skills we own, derived from the directories shipped next to this script.
SKILL_NAMES=()
for d in "$SKILLS_SRC"/*/; do
  [ -f "${d}SKILL.md" ] || continue
  SKILL_NAMES+=("$(basename "$d")")
done
if [ "${#SKILL_NAMES[@]}" -eq 0 ]; then
  log "no SKILL.md bundles found under $SKILLS_SRC"; exit 1
fi
log "skills to publish: ${SKILL_NAMES[*]}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
CLONE="$WORKDIR/registry"

log "cloning https://github.com/$AAS_TARGET_REPO (shallow)"
git clone --depth 1 "https://github.com/$AAS_TARGET_REPO.git" "$CLONE" >/dev/null 2>&1

for name in "${SKILL_NAMES[@]}"; do
  mkdir -p "$CLONE/skills/$name"
  cp "$SKILLS_SRC/$name/SKILL.md" "$CLONE/skills/$name/SKILL.md"
done

log "running upstream validator (strict)"
python3 "$CLONE/tools/scripts/validate_skills.py" --strict

if [ "$DRY_RUN" = "1" ]; then
  log "DRY_RUN=1 — validated, no PR opened."
  exit 0
fi

# ---- write mode: push to fork + open PR ----------------------------------
: "${GH_TOKEN:?GH_TOKEN is required in write mode (set DRY_RUN=1 to validate only)}"
: "${AAS_GIT_NAME:?AAS_GIT_NAME is required in write mode}"
: "${AAS_GIT_EMAIL:?AAS_GIT_EMAIL is required in write mode}"

cd "$CLONE"
git config user.name  "$AAS_GIT_NAME"
git config user.email "$AAS_GIT_EMAIL"
git checkout -b "$AAS_PR_BRANCH"

# Source-only: stage just our SKILL.md files, nothing the tooling regenerates.
for name in "${SKILL_NAMES[@]}"; do
  git add "skills/$name/SKILL.md"
done

if git diff --cached --quiet; then
  log "no changes versus upstream — already published. Nothing to do."
  exit 0
fi

git commit -m "feat: add compose-preview and compose-preview-review skills (${TAG_NAME})"

log "pushing to https://github.com/$AAS_FORK_REPO ($AAS_PR_BRANCH)"
git remote set-url origin "https://x-access-token:${GH_TOKEN}@github.com/${AAS_FORK_REPO}.git"
git push --force-with-lease -u origin "$AAS_PR_BRANCH"

fork_owner="${AAS_FORK_REPO%%/*}"
title="Add compose-preview and compose-preview-review skills"
body=$(cat <<EOF
Adds two source-only skill bundles from https://github.com/yschimke/compose-ai-tools (release ${TAG_NAME}):

- \`compose-preview\` — render Jetpack Compose / Compose Multiplatform @Preview functions to PNG.
- \`compose-preview-review\` — diff @Preview renders across a PR's base and head.

Both pass \`npm run validate\` / \`validate_skills.py --strict\`. Only the SKILL.md files are included; no generated registry artifacts.
EOF
)

log "opening PR against $AAS_TARGET_REPO"
curl -fsSL -X POST \
  -H "Authorization: Bearer ${GH_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${AAS_TARGET_REPO}/pulls" \
  -d "$(python3 - "$title" "$fork_owner:$AAS_PR_BRANCH" "$body" <<'PY'
import json, sys
title, head, body = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({"title": title, "head": head, "base": "main", "body": body, "maintainer_can_modify": True}))
PY
)" | python3 -c "import json,sys; d=json.load(sys.stdin); print('==> PR:', d.get('html_url') or d.get('message'))"
