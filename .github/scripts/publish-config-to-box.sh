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

# How long the replacement-branch probe below may take. Injectable so the self-test can drive the
# stall path in a second instead of thirty; nothing else should set it. `timeout` is coreutils and
# present on every runner this executes on — on a host without it the probe runs unbounded, which
# is the behaviour that existed before this line.
LS_REMOTE_TIMEOUT_SECONDS="${LS_REMOTE_TIMEOUT_SECONDS:-30}"
if command -v timeout > /dev/null 2>&1; then
  LS_REMOTE=(timeout "${LS_REMOTE_TIMEOUT_SECONDS}" git ls-remote)
else
  LS_REMOTE=(git ls-remote)
fi

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
sites_skipped=0

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
  last_post=failed
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "POST ${path} ${body}"
    last_post=ok
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
    200 | 201)
      echo "  ${label}: applied"
      last_post=ok
      ;;
    409)
      echo "  ${label}: already present"
      last_post=present
      ;;
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

# DELETE one admin path. Sets `last_delete` to ok | absent | refused.
#
# A 409 is NOT uniformly "it was already gone". `ServeCatalogAdmin.unregister` answers 409 for two
# opposite situations: the catalog is not published here (benign — the POST below will create it),
# and the catalog is published as a TOP-LEVEL SITE, which refuses retirement outright so a hostname
# is never stranded. Reading the second as success is how a move on m3-catalog or wear-m3-catalog —
# the two catalogs that ARE sites — would come back green having changed nothing: the delete is
# refused, the re-post 409s on the repo mismatch, and `post` calls that "already present". So
# discriminate on the payload, and let the caller decide.
delete() {
  local path="$1" label="$2"
  last_delete=refused
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "DELETE ${path}"
    last_delete=ok
    return 0
  fi
  local response code payload
  response=$(curl -sS -w $'\n%{http_code}' -m 30 \
    -X DELETE -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    "${BASE_URL}${path}" 2>/dev/null || printf '\n000')
  code="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  case "${code}" in
    200 | 201)
      echo "  ${label}: retired the stale registration"
      last_delete=ok
      ;;
    404)
      echo "  ${label}: nothing to retire"
      last_delete=absent
      ;;
    409)
      if [[ "${payload}" == *"is not published here"* ]]; then
        echo "  ${label}: nothing to retire"
        last_delete=absent
      else
        rejected=$((rejected + 1))
        echo "::error::${label}: cannot be retired — ${payload}"
        last_delete=refused
      fi
      ;;
    *)
      rejected=$((rejected + 1))
      echo "::error::${label}: retiring failed, HTTP ${code} — ${payload}"
      last_delete=refused
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

# What the box serves RIGHT NOW, so a `repo` change in this file can actually be applied.
#
# The reconcile is additive: re-posting an entry the box already has comes back 409, which `post`
# treats as success. That converges `listed`, `group` and `loadPriority` in place, but NOT `repo` —
# the server refuses to re-point a catalog under an existing id, so the 409 means "unchanged", and
# the box would go on serving the old repository's branch while this file says otherwise and every
# log line reads green. That is not hypothetical: it is exactly what a catalog moving repositories
# does (remote-m3 → yschimke/wear-m3-catalog, #4588), and the failure is silent in the worst way —
# the publisher stops, the served bytes freeze, and nothing reports a problem.
#
# So: read the current registrations, and where the declared `repo` differs from the live one,
# retire that id immediately before the POST re-creates it. A GET failure (older box, no route,
# no token) leaves the map empty, which degrades to exactly the old additive behaviour.
box_catalogs=""
if [[ "${DRY_RUN}" != 1 ]]; then
  box_catalogs=$(curl -sS -m 30 -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    "${BASE_URL}/admin/catalogs" 2>/dev/null || true)
fi

# One field of the box's current registration for a system, or empty if it serves no such catalog.
box_field_for() {
  [[ -n "${box_catalogs}" ]] || return 0
  printf '%s' "${box_catalogs}" |
    jq -r --arg s "$1" --arg f "$2" \
      '.catalogs // [] | map(select(.system == $s)) | .[0][$f] // ""' 2>/dev/null || true
}

# Is <repo> actually publishing <branch>? A repo move retires the live registration before the POST
# re-creates it, and `ServeCatalogAdmin.register` FETCHES before it persists — so a POST that cannot
# load removes the entry it just added and the catalog is left published nowhere, not rolled back to
# where it was. This is the cheap half of closing that window: refuse to retire anything until the
# replacement branch is known to exist. It does not prove the box can load it, which is why the
# caller still shouts if the re-post fails.
#
# BOUNDED, because `git ls-remote` has no timeout of its own and this is a synchronous call in the
# middle of the reconcile. A connection GitHub accepts and then stalls would hold the entire run —
# every remaining catalog, every site — until the job's own five-minute limit killed it
# (.github/workflows/publish-preview-config.yml), which is a far worse outcome than the error path
# this probe exists to take. Every HTTP call around it already carries `-m 30`; so does this.
# `timeout` exits 124, which is non-zero like any other failure, so a stall lands on the same
# `return 2` and is reported as "could not reach github.com" rather than "no such branch".
#
# GIT_TERMINAL_PROMPT=0 closes the second way this hangs: a repository that 404s to an
# unauthenticated fetch makes git ASK for a username, and a runner has no one to answer.
delivery_branch_exists() {
  local repo="$1" branch="$2" heads
  [[ -n "${repo}" && -n "${branch}" ]] || return 1
  heads=$(GIT_TERMINAL_PROMPT=0 "${LS_REMOTE[@]}" --heads \
    "https://github.com/${repo}.git" "refs/heads/${branch}" 2>/dev/null) ||
    return 2
  [[ -n "${heads}" ]]
}

# The box's registration for one system read FRESH, rather than from the startup snapshot — used
# only to tell a lost catalog from a raced one, where a stale answer is the whole problem. Prints
# the repo, or fails if the listing cannot be read.
live_repo_for() {
  local system="$1" latest
  latest=$(curl -sS -m 30 -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    "${BASE_URL}/admin/catalogs" 2>/dev/null) || return 1
  [[ -n "${latest}" ]] || return 1
  printf '%s' "${latest}" |
    jq -er --arg s "${system}" \
      '.catalogs // [] | map(select(.system == $s)) | .[0].repo // ""' 2>/dev/null
}

echo "Reconciling catalogs from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r entry; do
  [[ -n "${entry}" ]] || continue
  system=$(printf '%s' "${entry}" | jq -r '.system')
  declared_repo=$(printf '%s' "${entry}" | jq -r '.repo // ""')
  current_repo=$(box_field_for "${system}" repo)
  moved=0
  if [[ -n "${current_repo}" && -z "${declared_repo}" ]]; then
    # No `repo` here means "the box's own --catalog-repo default", which this file cannot see. The
    # POST resolves it, finds a mismatch, 409s, and `post` logs that as "already present" — so a
    # catalog that should have moved to the default keeps serving ${current_repo} and the run ends
    # green. A warning was not enough: nobody reads a warning in a passing job, which is the whole
    # reason this script counts rejections at all.
    #
    # So this reconcile REQUIRES an explicit repo for any catalog the box already serves, which is
    # the cheaper half of Codex's two options — comparing against the server's resolved default
    # would mean reading a flag this file has no route to. Every entry in this repository's config
    # names its repo, so the requirement costs nothing and the failure is loud.
    rejected=$((rejected + 1))
    echo "::error::catalog ${system}: no repo declared, so a move away from ${current_repo} cannot be detected here — declare the repo explicitly in ${CATALOGS_FILE#"${REPO_ROOT}/"}."
  elif [[ -n "${current_repo}" && "${current_repo}" != "${declared_repo}" ]]; then
    echo "  catalog ${system}: repo moved ${current_repo} -> ${declared_repo}"
    # The branch name does not depend on the repo — it is `<branchPrefix><system>` either way — so
    # the live registration tells us what to look for on the new side.
    target_branch=$(box_field_for "${system}" branch)
    if delivery_branch_exists "${declared_repo}" "${target_branch}"; then
      moved=1
      delete "/admin/catalogs/${system}" "catalog ${system}"
      [[ "${last_delete}" == refused ]] && moved=0
    else
      case $? in
        2) echo "::error::catalog ${system}: could not reach github.com to check ${declared_repo}@${target_branch}; leaving it on ${current_repo}." ;;
        *) echo "::error::catalog ${system}: ${declared_repo} publishes no ${target_branch} yet; leaving it on ${current_repo} rather than retiring a catalog with nothing to replace it." ;;
      esac
      rejected=$((rejected + 1))
    fi
  fi
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
  if [[ "${moved}" == 1 && "${last_post}" != ok ]]; then
    # The retire succeeded and the re-publish did not. Usually that means the catalog is published
    # NOWHERE — `register` drops the entry it added when the fetch fails — and that is the one
    # outcome an operator has to act on immediately, so it must never end in a green log.
    #
    # But a 409 says the opposite of "nowhere": the server has a registration under this id. It can
    # only have arrived between our DELETE and our POST — a concurrent reconcile, or an operator
    # registering it by hand — and if it came from the repository we were moving TO, the move
    # happened and there is nothing to report. Declaring an outage there is a false alarm that
    # fails the standalone publish workflow for a race that converged.
    #
    # So read the live registration back before saying anything. Only the `present` case can be
    # rescued; anything else genuinely lost the catalog.
    raced_repo=""
    if [[ "${last_post}" == present ]]; then
      raced_repo=$(live_repo_for "${system}") || raced_repo=""
    fi
    if [[ -n "${raced_repo}" && "${raced_repo}" == "${declared_repo}" ]]; then
      echo "  catalog ${system}: re-registered from ${declared_repo} by a concurrent publish — the move stands"
    elif [[ -n "${raced_repo}" ]]; then
      rejected=$((rejected + 1))
      echo "::error::catalog ${system} was retired from ${current_repo} and is now registered from ${raced_repo}, not the declared ${declared_repo} — something else re-registered it mid-publish. Re-run this workflow, and check who else is publishing to this box."
    elif [[ "${last_post}" == present ]]; then
      rejected=$((rejected + 1))
      echo "::error::catalog ${system} was retired from ${current_repo} and the re-publish came back 409, but the live registration could not be read back — it is registered as SOMETHING, and possibly not ${declared_repo}. Check /admin/catalogs on the box."
    else
      rejected=$((rejected + 1))
      echo "::error::catalog ${system} was retired from ${current_repo} but could not be re-published from ${declared_repo} — it is currently unpublished. Re-run this workflow once ${declared_repo} serves ${target_branch}, or re-post the old entry to restore it."
    fi
  fi
done < <(jq -c '.catalogs // [] | .[]' "${CATALOGS_FILE}")

# Sites LAST: a site may only name a catalog the box already serves, so it has to follow the
# catalogs loop that publishes them — a hostname posted first would be rejected as naming an
# unserved system, and on a first rollout that is every hostname.
#
# This section is why the whole reconcile exists for sites at all. `sites` is read at STARTUP from
# /config/catalogs.json, which is seeded on first boot and never overwritten, so a hostname added to
# the committed file reached a running box through nothing: standing one up meant editing SERVE_SITES
# in the box's untracked .env and recreating the container. POST /admin/sites applies it live and
# writes it back to the same file.
#
# What this still does NOT do is make the name reachable: DNS must point at the box, and the edge
# must match the hostname and hold a certificate for it. The caddy container derives that from this
# same file (deploy/image/caddy-entrypoint.sh), so it needs a caddy restart and no hand-maintained
# env var — but a brand-new hostname is not live the instant this script prints "applied".
echo "Reconciling top-level sites from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r site; do
  [[ -n "${site}" ]] || continue
  host=$(printf '%s' "${site}" | jq -r '.host')
  system=$(printf '%s' "${site}" | jq -r '.system')
  post /admin/sites \
    "$(jq -cn --arg h "${host}" --arg s "${system}" '{host:$h, system:$s}')" \
    "site ${host} -> ${system}" || {
    # A box predating /admin/sites. Everything else published fine; the hostnames stay on whatever
    # that box booted with, which is the behaviour this section replaced.
    if [[ $? == 2 ]]; then
      sites_skipped=1
      break
    fi
  }
done < <(jq -c '.sites // [] | .[]' "${CATALOGS_FILE}")

if [[ "${groups_skipped}" == 1 ]]; then
  echo "::warning::front-page groups were not reconciled — this box predates /admin/groups. Catalogs are published ungrouped; the next publish against a newer image will group them."
fi
if [[ "${catalogs_skipped}" == 1 ]]; then
  echo "::error::catalogs were not reconciled — /admin/catalogs is unavailable on this box."
  rejected=$((rejected + 1))
fi
if [[ "${sites_skipped}" == 1 ]]; then
  echo "::warning::top-level sites were not reconciled — this box predates /admin/sites. Its hostnames keep serving whatever it booted with; the next publish against a newer image applies them."
fi

if [[ "${rejected}" -gt 0 ]]; then
  echo "::error::${rejected} seed entr(y|ies) were rejected — the box is not serving everything the committed config declares." >&2
  exit 1
fi
echo "Config reconcile complete."
