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

# Sections skipped because the box lacks the route (an older image, e.g. one still mid-roll).
groups_skipped=0
catalogs_skipped=0

# POST one JSON body to an admin path. 200 = applied, 409 = already there (both fine), 404 =
# that ROUTE doesn't exist on this box — returned as 2 so the caller can skip just its own section.
#
# A 404 is per-route, NOT "the admin API is off". This bit for real on the 0.19.8 publish: the box
# was still rolling and answered as 0.19.7, which has /admin/trust but not the newer /admin/groups.
# The groups 404 was treated as a global "admin not enabled" and aborted the run before the catalogs
# loop, so a newly-added catalog (horologist) was silently never published. A missing groups route
# only means catalogs land ungrouped — no reason to skip them.
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
      echo "::warning::${path} returned 404 — route not available on this box; skipping the rest of this section."
      return 2
      ;;
    400)
      rejected=$((rejected + 1))
      # `unknown group` should now be unreachable: the group loop above defines every section before
      # any catalog claims one. If it still happens, the group POST silently failed or the box
      # predates /admin/groups — worth saying rather than a generic "rejected".
      if [[ "${payload}" == *"unknown group"* ]]; then
        echo "::error::${label}: ${payload}. Groups are reconciled first, so this means the /admin/groups POST did not take — check the group lines above, or whether this box predates the route."
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
    # /admin/trust is the oldest of the three routes, so a 404 HERE really does mean the admin API
    # is off (no --admin-token, or a wrong token — both answer 404 by design). Nothing downstream
    # can work, so stop rather than emit the same warning for every group and catalog.
    if [[ $? == 2 ]]; then
      echo "::warning::/admin/trust is unavailable — admin API not enabled on this box (or the token does not match); skipping the whole reconcile."
      exit 0
    fi
  }
done < <(jq -c '.branches // [] | .[]' "${TRUST_FILE}")

# Groups BEFORE catalogs, for the same reason trust comes before both: a catalog claiming a section
# the server hasn't been told about is rejected outright, and until /admin/groups existed that
# rejection was unfixable from here.
echo "Reconciling front-page groups from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r group; do
  [[ -n "${group}" ]] || continue
  id=$(printf '%s' "${group}" | jq -r '.id')
  post /admin/groups "${group}" "group ${id}" || {
    # Route missing (a box predating /admin/groups, e.g. one still mid-roll on an older image).
    # Catalogs are still worth publishing — they just land under the owner-repo fallback heading
    # until a later run can group them. Skipping them here is what silently lost horologist.
    if [[ $? == 2 ]]; then
      groups_skipped=1
      break
    fi
  }
done < <(jq -c '.groups // [] | .[]' "${CATALOGS_FILE}")

echo "Reconciling catalogs from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r entry; do
  [[ -n "${entry}" ]] || continue
  system=$(printf '%s' "${entry}" | jq -r '.system')
  # Preserve the declared shape — an unlisted catalog must stay off the front page, a group
  # claim has to survive or the card lands under the owner fallback instead of its section, and
  # loadPriority has to reach the box or the committed startup fetch order never takes effect
  # there (the box boots from its own /config/catalogs.json, which this is what rewrites).
  body=$(printf '%s' "${entry}" | jq -c '{system, repo, listed, group, attributionRepos, loadPriority}
    | with_entries(select(.value != null))')
  post /admin/catalogs "${body}" "catalog ${system}" || {
    if [[ $? == 2 ]]; then
      catalogs_skipped=1
      break
    fi
  }
done < <(jq -c '.catalogs // [] | .[]' "${CATALOGS_FILE}")

if [[ "${groups_skipped}" == 1 ]]; then
  echo "::warning::front-page groups were not reconciled — this box predates /admin/groups. Catalogs are published ungrouped; the next publish against a newer image will group them."
fi
if [[ "${catalogs_skipped}" == 1 ]]; then
  echo "::error::catalogs were not reconciled — /admin/catalogs is unavailable on this box."
  rejected=$((rejected + 1))
fi

if [[ "${rejected}" -gt 0 ]]; then
  echo "::error::${rejected} seed entr(y|ies) were rejected — the box is not serving everything the committed config declares." >&2
  exit 1
fi
echo "Config reconcile complete."
