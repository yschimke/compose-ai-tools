#!/usr/bin/env bash
#
# Stub installer — the canonical script lives in yschimke/skills.
#
# This file exists so that:
#   1. Older snippets (READMEs, CI, agent prompts) that say
#      `scripts/install.sh` in a compose-ai-tools clone keep working.
#   2. The on-disk URL
#      `https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh`
#      that `compose-preview update` historically curl-piped continues
#      to resolve.
#
# It fetches the real installer from yschimke/skills (override the ref
# or repo via env if needed) and execs it with the same argv.
#
# Override the upstream source:
#   SKILLS_REPO=yschimke/skills SKILLS_REF=main scripts/install.sh
#
# For the full installer's options and behaviour, see the canonical
# script: https://github.com/yschimke/skills/blob/main/scripts/install.sh

set -euo pipefail

SKILLS_REPO="${SKILLS_REPO:-yschimke/skills}"
SKILLS_REF="${SKILLS_REF:-main}"
URL="https://raw.githubusercontent.com/$SKILLS_REPO/$SKILLS_REF/scripts/install.sh"

echo "==> stub: fetching canonical installer from $URL" >&2
script="$(curl -fsSL "$URL")"
exec bash -c "$script" -- "$@"
