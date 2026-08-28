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
# an issue, or a chat with an agent) would be a poor trade for the tidiness. Only key names, line
# numbers, and defaults that are already public in this repo, reach stdout.
#
# **Advice this tool gives is acted on by deleting a line, so a wrong answer is a config change.**
# That is why the parsing below tracks Compose's own rules rather than approximating them: which
# file's default actually applies, which assignment actually wins, and where a value actually ends.
# Each of those, got wrong, turns "safe to delete" into a silent behaviour change on the next roll.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
env_file="${ENV_FILE:-${here}/.env}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

[[ -f "${env_file}" ]] || {
  echo "no .env at ${env_file} — nothing to check" >&2
  exit 0
}

# ---------------------------------------------------------------------------------------------
# Value parsing, shared by every reader below so they cannot disagree about where a value ends.
# ---------------------------------------------------------------------------------------------

# Compose's reading of one .env value: one layer of surrounding quotes stripped, and — for an
# UNQUOTED value only — an inline comment removed from the first whitespace-preceded `#` onward.
#
# The comment rule is not a nicety. `deploy/image/README.md` hands out copyable lines of the form
# `SERVE_THEME_CACHE_DIR=/theme-cache # the default`, and a reader that keeps the comment compares
# `/theme-cache # the default` against `/theme-cache`, calls the entry live, and tells the operator
# the one line that IS pure decoration is the one worth keeping.
# https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/
compose_value() {
  local raw="$1"
  case "${raw}" in
    \"*\") printf '%s' "${raw:1:${#raw}-2}"; return 0 ;;
    \'*\') printf '%s' "${raw:1:${#raw}-2}"; return 0 ;;
  esac
  local v="${raw}"
  [[ "${v}" == *" #"* ]] && v="${v%% #*}"
  [[ "${v}" == *$'\t#'* ]] && v="${v%%$'\t'#*}"
  # Trailing whitespace before a stripped comment is not part of the value either.
  while [[ "${v}" == *[[:space:]] ]]; do v="${v%[[:space:]]}"; done
  printf '%s' "${v}"
}

# ---------------------------------------------------------------------------------------------
# Which compose files are actually in play.
# ---------------------------------------------------------------------------------------------

# `COMPOSE_FILE` selects them, and this deployment documents an overlay
# (`docker-compose.yml:docker-compose.deploy-config.yml`) that carries defaults of its own —
# `${DEPLOY_CONFIG_DIR:-../preview.coo.ee}` among them. Reading only the base file tells an operator
# who set exactly the overlay's default that their line genuinely differs from stock.
#
# Compose reads `COMPOSE_FILE` from the environment or from the .env itself, so both are honoured
# here, in that order.
compose_file_selector="${COMPOSE_FILE:-}"
if [[ -z "${compose_file_selector}" ]]; then
  compose_file_selector="$(
    sed -n 's/^[[:space:]]*COMPOSE_FILE=//p' "${env_file}" | tail -n 1
  )"
  [[ -n "${compose_file_selector}" ]] &&
    compose_file_selector="$(compose_value "${compose_file_selector}")"
fi

compose_files=()
if [[ -n "${COMPOSE_FILE_UNDER_TEST:-}" ]]; then
  # The tests name their fixtures directly; honoured ahead of everything else.
  IFS=':' read -r -a compose_files <<<"${COMPOSE_FILE_UNDER_TEST}"
elif [[ -n "${compose_file_selector}" ]]; then
  IFS="${COMPOSE_PATH_SEPARATOR:-:}" read -r -a selected <<<"${compose_file_selector}"
  for name in "${selected[@]}"; do
    [[ -z "${name}" ]] && continue
    [[ "${name}" == /* ]] && compose_files+=("${name}") || compose_files+=("${here}/${name}")
  done
else
  compose_files=("${here}/docker-compose.yml")
fi

# ---------------------------------------------------------------------------------------------
# VAR -> default, from the compose files and then the entrypoint.
# ---------------------------------------------------------------------------------------------

# Within the compose files, LATER wins — that is what an overlay is for. The entrypoint then fills
# in behind them, and it also **replaces an empty compose default**: a compose file that passes a
# variable through as `${SERVE_TIMEOUT:-}` is declaring a pass-through, not a value, and the
# effective default is whatever the entrypoint supplies. First-wins across both files recorded the
# empty string and reported `SERVE_TIMEOUT=1800`, `SERVE_CATALOG_SOURCE_REF=main` and
# `SERVE_CATALOG_SOURCE_ROOT=/catalog-src` as genuine deviations when each is exactly stock.
declare -A default_of
record_defaults() {
  local file="$1" replace_empty="$2" pair key value
  [[ -f "${file}" ]] || return 0
  while IFS= read -r pair; do
    key="${pair%%:-*}"
    value="${pair#*:-}"
    if [[ -z "${default_of[$key]+set}" ]]; then
      default_of["$key"]="${value}"
    elif [[ "${replace_empty}" == "replace-empty" && -z "${default_of[$key]}" && -n "${value}" ]]; then
      default_of["$key"]="${value}"
    elif [[ "${replace_empty}" == "later-wins" ]]; then
      default_of["$key"]="${value}"
    fi
  done < <(grep -oE '\$\{[A-Z_][A-Z0-9_]*:-[^}]*\}' "${file}" 2>/dev/null | sed 's/^\${//; s/}$//' || true)
}
for f in "${compose_files[@]}"; do record_defaults "${f}" later-wins; done
record_defaults "${entrypoint}" replace-empty

# ---------------------------------------------------------------------------------------------
# Read the .env once: line numbers, the effective assignment per key, and the malformed lines.
# ---------------------------------------------------------------------------------------------

# `|| [[ -n "${line}" ]]` because a final line with no terminating newline is assigned by `read`
# and then reported as failure, so the loop body never sees it. A file ending in
# `SERVE_JAVA_OPTS=…\` — the exact fatal typo the continuation check exists to catch — otherwise
# exits 0 with nothing said.
continuations=()
order=()
declare -A value_of last_line_of occurrences
lineno=0
while IFS= read -r line || [[ -n "${line}" ]]; do
  lineno=$((lineno + 1))
  [[ "${line}" =~ ^[[:space:]]*# ]] && continue
  [[ "${line}" =~ ^[[:space:]]*$ ]] && continue
  if [[ "${line}" == *\\ ]]; then
    # Named by LINE NUMBER, and by key only when the line is a well-formed assignment. A malformed
    # continuation's second line — `--token secret\`, or a split value fragment — has no `=`, so
    # `${line%%=*}` was the whole line: this tool's one guarantee is that its output can be pasted
    # into an issue, and that path printed a value fragment straight to stderr.
    if [[ "${line}" =~ ^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*= ]]; then
      key="${line%%=*}"; key="${key#*export }"; key="${key//[[:space:]]/}"
      continuations+=("line ${lineno}: ${key}")
    else
      continuations+=("line ${lineno}: (continuation of the line above; value not shown)")
    fi
    continue
  fi
  [[ "${line}" == *=* ]] || continue
  key="${line%%=*}"
  key="${key#export }"
  key="${key//[[:space:]]/}"
  raw="${line#*=}"
  # LAST assignment wins, exactly as Compose reads it. Classifying each line independently told an
  # operator that the final `SERVE_PUBLIC=1` was safe to delete when an earlier `SERVE_PUBLIC=0`
  # was still in the file — deleting it exposes the 0, and the box goes from public to token-gated
  # and fails to start if no token is configured.
  [[ -n "${value_of[$key]+set}" ]] || order+=("${key}")
  value_of["$key"]="$(compose_value "${raw}")"
  last_line_of["$key"]="${lineno}"
  occurrences["$key"]=$(( ${occurrences[$key]:-0} + 1 ))
done < "${env_file}"

if ((${#continuations[@]})); then
  echo "BROKEN — these lines end in a backslash, which .env does not treat as a continuation:" >&2
  printf '  %s\n' "${continuations[@]}" >&2
  echo "Join each onto one line. Left as is, the container will not start." >&2
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# Classify.
# ---------------------------------------------------------------------------------------------

# Keys whose setup migration distinguishes MISSING from EMPTY. `setup.sh` backfills a generated
# DEPLOY_HOOK_TOKEN only when no assignment is present, so an operator who deliberately keeps the
# line empty has disarmed the instant-roll webhook — and deleting the line re-arms it on the next
# `setup.sh`. Removing an empty assignment is equivalent to leaving it unset for every OTHER key;
# for these it is a behaviour change, so they are reported separately rather than as deletable.
migration_sensitive=(DEPLOY_HOOK_TOKEN)

redundant=()
empty=()
empty_load_bearing=()
active=()
duplicates=()

for key in "${order[@]}"; do
  if (( ${occurrences[$key]} > 1 )); then
    duplicates+=("${key} (${occurrences[$key]} assignments; the one on line ${last_line_of[$key]} is the one Compose uses)")
  fi
  value="${value_of[$key]}"
  if [[ -z "${value}" ]]; then
    exempt=no
    for sensitive in "${migration_sensitive[@]}"; do
      [[ "${key}" == "${sensitive}" ]] && exempt=yes && break
    done
    if [[ "${exempt}" == yes ]]; then
      empty_load_bearing+=("${key}")
    else
      empty+=("${key}")
    fi
  elif [[ -n "${default_of[$key]+set}" && "${value}" == "${default_of[$key]}" ]]; then
    redundant+=("${key}=${default_of[$key]}")
  else
    active+=("${key}")
  fi
done

# Duplicates first: everything below is advice about deleting a line, and a duplicated key makes
# "delete this one" mean something different from what the reader will assume.
if ((${#duplicates[@]})); then
  echo "DUPLICATED — resolve these before deleting anything:"
  printf '  %s\n' "${duplicates[@]}"
  echo "  Deleting the winning line exposes an earlier one; the advice below is about the winner only."
  echo
fi

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

if ((${#empty_load_bearing[@]})); then
  echo
  echo "Set but empty, and NOT the same as unset — leave these alone:"
  printf '  %s\n' "${empty_load_bearing[@]}"
  echo "  setup.sh generates a value when the assignment is absent, so deleting the line changes"
  echo "  behaviour on the next run rather than restoring the default."
fi

echo
echo "Genuinely differs from stock (${#active[@]} keys, values not shown):"
printf '  %s\n' "${active[@]}"
