#!/usr/bin/env bash
# SessionStart hook: point this clone's git hooks at .githooks/ so the
# ktfmt pre-commit guard runs on every commit. Idempotent.

set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

if [ -x scripts/install-git-hooks.sh ]; then
  scripts/install-git-hooks.sh >&2
fi
