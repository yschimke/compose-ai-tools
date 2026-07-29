#!/usr/bin/env bash
# Reconcile the image's SEED config onto a running preview server via its admin API.
#
# Why this exists: /config/catalogs.json and /config/producers.json are seeded on first boot and
# never overwritten after — deliberately, so an image roll can't stomp a runtime edit (see #2879 /
# #2897). The consequence is that adding a catalog or producer to a committed file changes nothing
# on an already-deployed box: it keeps the config it already has, and someone has to remember to
# POST the new entries by hand. This closes that gap as part of publishing.
#
# ADDITIVE ONLY. This never deletes and never rewrites an existing entry: an id already present
# comes back 409 from the admin API, which is treated as success. So a producer or catalog an
# operator added directly on the box survives untouched.
#
# The flip side, and it is a real trade-off rather than an oversight: because the reconcile is
# blind to history, a catalog RETIRED on the box (DELETE /admin/catalogs/<id>) while still listed
# in catalogs.json will be re-added by the next publish. That makes the committed seed the
# declared intent — to retire something permanently, drop it from catalogs.json too. Any
# alternative needs the box to persist tombstones, which is a bigger change; see the discussion on
# #2962.
#
# Usage:
#   BASE_URL=https://preview.coo.ee ADMIN_TOKEN=… publish-config-to-box.sh [--dry-run]
#
# --dry-run prints the requests it would make, one per line, and talks to nothing. That is the
# seam test-publish-config-to-box.sh drives, so the ordering and payload rules below are covered
# without a server.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The DEPLOYMENT's config, not the image's generic seed. These are different things and conflating
# them is what gave preview.coo.ee favoured-nation status: its 17 catalogs and 9 trusted producers
# used to live in deploy/image/, so every adopter of the prebuilt image inherited them. The image
# seed is now compose-m3 plus the one producer that publishes it; a deployment's own set lives in
# its own directory and is applied from here.
#
# DEPLOY_CONFIG_DIR is what another adopter overrides — point it at your own directory with the
# same two filenames and this script works unchanged against your box.
DEPLOY_CONFIG_DIR="${DEPLOY_CONFIG_DIR:-${REPO_ROOT}/deploy/preview.coo.ee}"
CATALOGS_FILE="${CATALOGS_FILE:-${DEPLOY_CONFIG_DIR}/catalogs.json}"
TRUST_FILE="${TRUST_FILE:-${DEPLOY_CONFIG_DIR}/producers.json}"
ADMIN_TOKEN_HEADER="X-Compose-Preview-Admin-Token"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

: "${BASE_URL:?BASE_URL required}"
if [[ "${DRY_RUN}" == 0 ]]; then
  : "${ADMIN_TOKEN:?ADMIN_TOKEN required}"
fi

# Entries the server refused. A rejected entry means the box is NOT serving something the seed
# says it should, which is the exact condition this script exists to prevent — so it ends in a
# non-zero exit (the workflow step is continue-on-error, so the publish still succeeds, but the
# step goes red and the log carries an ::error:: instead of a warning nobody reads).
rejected=0

# POST one JSON body to an admin path. 200 = applied, 409 = already there (both fine), 404 =
# the routes don't exist on this server (no --admin-token, or an image predating them) — which is
# reported once and treated as "nothing to do" rather than a failure, since a box that never opted
# in to the admin API is a legitimate deployment.
post() {
  local path="$1" body="$2" label="$3"
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "POST ${path} ${body}"
    return 0
  fi
  local response code payload
  # Body AND status: a 400's body carries WHY, and the reason changes what the operator has to do.
  response=$(curl -sS -w $'\n%{http_code}' -m 30 \
    -X POST -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "${body}" "${BASE_URL}${path}" 2>/dev/null || printf '\n000')
  code="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  case "${code}" in
    200 | 201) echo "  ${label}: applied" ;;
    409) echo "  ${label}: already present" ;;
    404)
      echo "::warning::${BASE_URL}${path} returned 404 — admin API not enabled on this box; skipping config reconcile."
      return 2
      ;;
    400)
      rejected=$((rejected + 1))
      # The one 400 that is a *structural* gap rather than a bad payload: there is no admin route
      # for front-page groups (only /admin/catalogs and /admin/trust), so a catalog claiming a
      # group the box's own /config/catalogs.json doesn't define is rejected and cannot be fixed
      # from here. Say so explicitly — the previous version warned and moved on, leaving the
      # catalog silently unpublished.
      if [[ "${payload}" == *"unknown group"* ]]; then
        echo "::error::${label}: ${payload}. Groups are not reconcilable over the admin API — add the group to the box's /config/catalogs.json (\`docker compose exec preview vi /config/catalogs.json\` then restart), or drop the group claim from the seed entry."
      else
        echo "::error::${label}: rejected (HTTP 400) — ${payload}"
      fi
      ;;
    *)
      rejected=$((rejected + 1))
      echo "::error::${label}: HTTP ${code} — ${payload}"
      ;;
  esac
  return 0
}

# Trust FIRST. A catalog is verified at fetch time, so publishing it before its producer is
# trusted would register it as `unverified` and leave it that way until its branch next moves.
echo "Reconciling trusted producers from ${TRUST_FILE#"${REPO_ROOT}/"}"
while IFS= read -r entry; do
  [[ -n "${entry}" ]] || continue
  repo=$(printf '%s' "${entry}" | jq -r '.repo')
  branch=$(printf '%s' "${entry}" | jq -r '.branch // "*"')
  post /admin/trust \
    "$(jq -cn --arg r "${repo}" --arg b "${branch}" \
      '{kind:"branch", repo:$r, branch:$b}')" \
    "branch ${repo}@${branch}" || {
    # 404 from the first call means the whole surface is missing; don't hammer it per entry.
    [[ $? == 2 ]] && exit 0
  }
done < <(jq -c '.branches // [] | .[]' "${TRUST_FILE}")

echo "Reconciling catalogs from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r entry; do
  [[ -n "${entry}" ]] || continue
  system=$(printf '%s' "${entry}" | jq -r '.system')
  # Preserve the declared shape — an unlisted catalog must stay off the front page, and a group
  # claim has to survive or the card lands under the owner fallback instead of its section.
  body=$(printf '%s' "${entry}" | jq -c '{system, repo, listed, group, attributionRepos}
    | with_entries(select(.value != null))')
  post /admin/catalogs "${body}" "catalog ${system}" || {
    [[ $? == 2 ]] && exit 0
  }
done < <(jq -c '.catalogs // [] | .[]' "${CATALOGS_FILE}")

if [[ "${rejected}" -gt 0 ]]; then
  echo "::error::${rejected} seed entr(y|ies) were rejected — the box is not serving everything the committed config declares." >&2
  exit 1
fi
echo "Config reconcile complete."
