#!/usr/bin/env bash
# Report .env entries that only restate a default, so an operator can delete them.
#
# Why this is worth a script. Every variable the compose file and the entrypoint read carries its
# own default, and an .env accumulates lines faster than anyone removes them: a value copied from
# the README during setup, a knob turned once during an incident and turned back, a setting that
# later BECAME the default. The file then reads as configuration when most of it is decoration, and
# the handful of lines that genuinely differ from stock — the ones that explain why this box behaves
# unlike a fresh one — are buried among them.
#
# Values are never printed. The file holds SERVE_TOKEN, SERVE_ADMIN_TOKEN, the GitHub OAuth secret
# and the deploy hook token; a tidy-up tool that pastes those into a terminal (and from there into
# an issue, or a chat with an agent) would be a poor trade for the tidiness. Only key names, and
# defaults that are already public in this repo, reach stdout.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
env_file="${ENV_FILE:-${here}/.env}"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

[[ -f "${env_file}" ]] || {
  echo "no .env at ${env_file} — nothing to check" >&2
  exit 0
}

# Collect VAR -> default from both files. The compose file decides what reaches the container at
# all, so it wins where the two disagree; the entrypoint's defaults apply to what compose passed
# through empty.
declare -A default_of
while IFS= read -r pair; do
  key="${pair%%:-*}"
  value="${pair#*:-}"
  [[ -n "${default_of[$key]+set}" ]] || default_of["$key"]="$value"
done < <(
  { grep -oE '\$\{[A-Z_][A-Z0-9_]*:-[^}]*\}' "${compose}" || true
    grep -oE '\$\{[A-Z_][A-Z0-9_]*:-[^}]*\}' "${entrypoint}" || true
  } | sed 's/^\${//; s/}$//'
)

# A trailing backslash is not a continuation here, it is a fatal typo — and a silent one. Compose
# reads every line as its own KEY=VALUE, so the backslash becomes part of the value and the lines
# below it are parsed as junk keys. When the value is SERVE_JAVA_OPTS that backslash reaches
# JAVA_TOOL_OPTIONS, the JVM refuses to start on an unrecognised option, and the box serves 502
# behind a restart-looping container with nothing in the compose output naming the cause.
continuations=()
while IFS= read -r line; do
  [[ "${line}" =~ ^[[:space:]]*# ]] && continue
  [[ "${line}" == *\\ ]] && continuations+=("${line%%=*}")
done < "${env_file}"

if ((${#continuations[@]})); then
  echo "BROKEN — these lines end in a backslash, which .env does not treat as a continuation:" >&2
  printf '  %s\n' "${continuations[@]}" >&2
  echo "Join each onto one line. Left as is, the container will not start." >&2
  exit 1
fi

redundant=()
empty=()
active=()
while IFS= read -r line; do
  [[ "${line}" =~ ^[[:space:]]*# ]] && continue
  [[ "${line}" =~ ^[[:space:]]*$ ]] && continue
  [[ "${line}" == *=* ]] || continue
  key="${line%%=*}"
  key="${key#export }"
  key="${key//[[:space:]]/}"
  value="${line#*=}"
  # Strip one layer of surrounding quotes, the way compose does.
  value="${value%\"}"; value="${value#\"}"
  value="${value%\'}"; value="${value#\'}"
  if [[ -z "${value}" ]]; then
    empty+=("${key}")
  elif [[ -n "${default_of[$key]+set}" && "${value}" == "${default_of[$key]}" ]]; then
    redundant+=("${key}=${default_of[$key]}")
  else
    active+=("${key}")
  fi
done < "${env_file}"

if ((${#redundant[@]})); then
  echo "Restating a default — safe to delete:"
  printf '  %s\n' "${redundant[@]}"
else
  echo "Nothing restates a default."
fi

if ((${#empty[@]})); then
  echo
  echo "Set but empty — same as unset, safe to delete:"
  printf '  %s\n' "${empty[@]}"
fi

echo
echo "Genuinely differs from stock (${#active[@]} keys, values not shown):"
printf '  %s\n' "${active[@]}"
