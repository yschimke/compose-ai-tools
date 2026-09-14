#!/usr/bin/env bash
#
# Run Gradle with a resource budget suitable for automated work on a shared developer host.
#
# Usage:
#   scripts/agent-gradle.sh <gradle arguments...>
#   scripts/agent-gradle.sh --exclusive <gradle arguments...>
#
# The exclusive profile serializes heavyweight automated builds across the Compose Preview repos.
# Interactive developers and hosted CI continue to invoke Gradle directly and do not take this lock.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

exclusive=false
if [ "${1-}" = "--exclusive" ]; then
  exclusive=true
  shift
fi

if [ "$#" -eq 0 ]; then
  echo "usage: $0 [--exclusive] <gradle arguments...>" >&2
  exit 2
fi

if ! command -v build-brief >/dev/null 2>&1; then
  echo "build-brief is required; see AGENTS.md under 'Running Gradle'." >&2
  exit 1
fi

command=(
  build-brief
  "${repo_root}/gradlew"
  --priority=low
  --max-workers=4
  -Dorg.gradle.daemon.idletimeout=600000
  --non-interactive
  "$@"
)

cd "${repo_root}"

if [ "${exclusive}" = false ]; then
  exec "${command[@]}"
fi

if ! command -v flock >/dev/null 2>&1; then
  echo "the --exclusive profile requires flock (util-linux)." >&2
  exit 1
fi

lock_directory=${XDG_RUNTIME_DIR:-/tmp}
if [ ! -d "${lock_directory}" ] || [ ! -w "${lock_directory}" ]; then
  lock_directory=/tmp
fi
lock_path="${lock_directory}/compose-preview-gradle-${UID}.lock"
exec flock "${lock_path}" "${command[@]}"
