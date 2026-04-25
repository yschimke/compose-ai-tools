#!/usr/bin/env bash
# Point this clone's `core.hooksPath` at `.githooks/`. Run once after cloning.

set -euo pipefail

repo_root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath .githooks
echo "git core.hooksPath -> .githooks"
