#!/usr/bin/env bash
# One-off `.env` migrations for an already-deployed box, sourced by setup.sh.
# Kept in its own file (rather than inline in setup.sh) so the rewrite rules can
# be exercised by test-env-migrations.sh without running the real installer —
# these edit an operator's live config, so "only touches what it claims to" has
# to be a test, not a comment.

# The public server briefly pinned only the first three compose-samples apps in
# SERVE_CATALOGS, back when that variable WAS the catalog set. It is now only an
# addition to the operator's catalogs.json (/config/catalogs.json), so the stale
# pin no longer shadows anything — but it does re-add three entries the config
# file already declares, which reads as a mystery on the front page. Dropping it
# leaves the config file as the single source of truth.
LEGACY_COMPOSE_SAMPLES_CATALOGS='jetnews@yschimke/compose-samples,jetchat@yschimke/compose-samples,jetlagged@yschimke/compose-samples'

# True when the line is a SERVE_CATALOGS assignment whose value is exactly the
# legacy list. Tolerates the shapes a hand-edited .env actually shows up in —
# `export `, surrounding quotes, leading/trailing whitespace, CRLF — while still
# comparing the *value*, so an operator's own list is never matched.
_env_line_is_legacy_catalogs() {
  local line="${1%$'\r'}" value
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line#export }"
  line="${line#"${line%%[![:space:]]*}"}"
  [[ "${line}" == SERVE_CATALOGS=* ]] || return 1
  value="${line#SERVE_CATALOGS=}"
  value="${value%"${value##*[![:space:]]}"}"
  if [[ ${#value} -ge 2 && ( "${value}" == \"*\" || "${value}" == \'*\' ) ]]; then
    value="${value:1:${#value}-2}"
  fi
  [[ "${value}" == "${LEGACY_COMPOSE_SAMPLES_CATALOGS}" ]]
}

# Drop only the legacy three-app SERVE_CATALOGS assignment from $1, so the next
# `compose up` serves exactly what catalogs.json declares. Any other SERVE_CATALOGS
# value — an operator's own list, or a later override in the same file — is left
# alone. Returns 0 when something was removed (so callers can log), 1 otherwise.
migrate_legacy_serve_catalogs() {
  local env_file="${1:?env file required}"
  [[ -f "${env_file}" ]] || return 1

  local line removed=0 out=""
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if _env_line_is_legacy_catalogs "${line}"; then
      removed=1
      continue
    fi
    out+="${line}"$'\n'
  done < "${env_file}"

  (( removed )) || return 1
  # Truncate-in-place rather than sed -i: keeps the file's 0600 mode, owner and
  # inode, which a temp-file rename would quietly reset on a live box.
  printf '%s' "${out}" > "${env_file}"
}
