#!/usr/bin/env bash
# Self-test for publish-config-to-box.sh, run by CI on every PR.
#
# Drives the --dry-run seam so the rules that actually matter are pinned without a server:
#   1. trust is reconciled BEFORE catalogs (a catalog published ahead of its producer would
#      register as `unverified` and stay that way until its branch moved);
#   2. the declared entry shape survives — `listed: false`, `group` and `loadPriority` are not
#      dropped, since the first two being lost silently changes where a card renders and the last
#      one decides which catalogs a rollout fetches first;
#   3. null/absent optional fields are omitted rather than sent as JSON null.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/publish-config-to-box.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

cat > "${tmp}/producers.json" <<'JSON'
{
  "branches": [
    { "repo": "yschimke/compose-ai-tools", "branch": "design-artifacts/*" },
    { "repo": "yschimke/pocket-casts-android", "branch": "design-artifacts/*" }
  ]
}
JSON

cat > "${tmp}/catalogs.json" <<'JSON'
{
  "groups": [{ "id": "ds", "heading": "Design Systems" }],
  "catalogs": [
    { "system": "compose-m3", "repo": "yschimke/compose-ai-tools", "listed": true, "group": "ds",
      "loadPriority": 20 },
    { "system": "cadence", "repo": "yschimke/cadence", "listed": false },
    { "system": "jetnews", "repo": "yschimke/compose-samples", "listed": true,
      "attributionRepos": ["android/compose-samples"] }
  ],
  "sites": [
    { "host": "m3.preview.coo.ee", "system": "compose-m3" },
    { "host": "wear.preview.coo.ee", "system": "cadence" }
  ]
}
JSON

out=$(BASE_URL=https://example.invalid \
  TRUST_FILE="${tmp}/producers.json" \
  CATALOGS_FILE="${tmp}/catalogs.json" \
  bash "${UNDER_TEST}" --dry-run)

# 1. ordering: trust, then groups, then catalogs. Both hops matter — a catalog posted before its
#    producer is trusted registers `unverified`; one posted before its section exists is rejected
#    outright as an unknown group.
last_trust=$(printf '%s\n' "${out}" | grep -n 'POST /admin/trust' | tail -1 | cut -d: -f1)
first_group=$(printf '%s\n' "${out}" | grep -n 'POST /admin/groups' | head -1 | cut -d: -f1)
last_group=$(printf '%s\n' "${out}" | grep -n 'POST /admin/groups' | tail -1 | cut -d: -f1)
first_catalog=$(printf '%s\n' "${out}" | grep -n 'POST /admin/catalogs' | head -1 | cut -d: -f1)
if [[ -z "${last_trust}" || -z "${first_group}" || -z "${first_catalog}" ]]; then
  fail "expected trust, group and catalog POSTs; got:\n${out}"
else
  [[ "${last_trust}" -lt "${first_group}" ]] ||
    fail "trust must precede groups (last trust ${last_trust}, first group ${first_group})"
  [[ "${last_group}" -lt "${first_catalog}" ]] ||
    fail "groups must precede catalogs (last group ${last_group}, first catalog ${first_catalog})"
fi

# 1b. the group's heading and noun reach the wire intact — a dropped heading would silently rename
#     a front-page section.
printf '%s' "${out}" | grep -q '"id":"ds".*"heading":"Design Systems"' ||
  fail "group ds must be POSTed with its heading"

# 2. both producers are sent, with their branch globs intact.
for repo in yschimke/compose-ai-tools yschimke/pocket-casts-android; do
  printf '%s' "${out}" | grep -q "\"repo\":\"${repo}\",\"branch\":\"design-artifacts/\*\"" ||
    fail "missing trust POST for ${repo}"
done

# 3. an unlisted catalog stays unlisted.
printf '%s' "${out}" | grep -q '"system":"cadence".*"listed":false' ||
  fail "cadence must be POSTed with listed:false"

# 4. a group claim survives.
printf '%s' "${out}" | grep -q '"system":"compose-m3".*"group":"ds"' ||
  fail "compose-m3 must keep its group claim"

# 4b. loadPriority survives — it is what decides which catalogs a rollout fetches first, and the
#     box boots from the config this rewrites, so dropping it here makes the committed order a
#     no-op on the deployed server.
printf '%s' "${out}" | grep -q '"system":"compose-m3".*"loadPriority":20' ||
  fail "compose-m3 must keep its loadPriority"
printf '%s' "${out}" | grep -q '"system":"cadence".*"loadPriority"' &&
  fail "an undeclared loadPriority must be omitted, not sent: ${out}"

# 5. attributionRepos survives (it's what lets a fork-served catalog keep its upstream section).
printf '%s' "${out}" | grep -q '"attributionRepos":\["android/compose-samples"\]' ||
  fail "jetnews must keep its attributionRepos"

# 6. absent optionals are omitted, not sent as null.
printf '%s' "${out}" | grep -q 'null' &&
  fail "no request body should contain a JSON null: ${out}"

# 7. every catalog in the file is covered — a silently-skipped entry is the failure mode this
#    whole script exists to prevent.
for system in compose-m3 cadence jetnews; do
  printf '%s' "${out}" | grep -q "\"system\":\"${system}\"" ||
    fail "missing catalog POST for ${system}"
done

# 7b. sites come LAST, after the catalogs they name. A hostname may only name a catalog the box
#     already serves, so a site POSTed before its catalog is rejected outright — and on a first
#     rollout that is every site. This section is the whole reason a committed hostname reaches a
#     running box at all: `sites` is startup-only config on a volume that is seeded once, so before
#     /admin/sites existed it could only be delivered by hand-editing the box's .env.
last_catalog=$(printf '%s\n' "${out}" | grep -n 'POST /admin/catalogs' | tail -1 | cut -d: -f1)
first_site=$(printf '%s\n' "${out}" | grep -n 'POST /admin/sites' | head -1 | cut -d: -f1)
if [[ -z "${first_site}" ]]; then
  fail "expected site POSTs; got:\n${out}"
else
  [[ "${last_catalog}" -lt "${first_site}" ]] ||
    fail "catalogs must precede sites (last catalog ${last_catalog}, first site ${first_site})"
fi

# 7c. every site in the file is covered, host and system intact — a dropped `system` would point a
#     hostname at the wrong catalog, which is worse than not publishing it.
printf '%s' "${out}" | grep -q '"host":"m3.preview.coo.ee","system":"compose-m3"' ||
  fail "missing site POST for m3.preview.coo.ee"
printf '%s' "${out}" | grep -q '"host":"wear.preview.coo.ee","system":"cadence"' ||
  fail "missing site POST for wear.preview.coo.ee"

# 8. A box missing ONE route must not lose the other sections. This is the regression that
#    silently dropped a newly-added catalog on the 0.19.8 publish: the box was mid-roll and still
#    answering as an older image, /admin/groups 404'd, and the whole run aborted before catalogs.
#    Driven through a stubbed curl so no server is involved.
shim="${tmp}/bin"
mkdir -p "${shim}"
cat > "${shim}/curl" <<'SH'
#!/usr/bin/env bash
# An older box: /admin/trust and /admin/catalogs exist, /admin/groups does not.
for a in "$@"; do
  case "$a" in
    */admin/groups) printf 'not found\n404'; exit 0 ;;
    */admin/catalogs) printf 'ok\n200'; exit 0 ;;
    */admin/trust) printf 'ok\n200'; exit 0 ;;
  esac
done
printf 'ok\n200'
SH
chmod +x "${shim}/curl"

partial=$(PATH="${shim}:${PATH}" BASE_URL=https://example.invalid ADMIN_TOKEN=t \
  TRUST_FILE="${tmp}/producers.json" CATALOGS_FILE="${tmp}/catalogs.json" \
  bash "${UNDER_TEST}" 2>&1)

printf '%s' "${partial}" | grep -q 'catalog compose-m3: applied' ||
  fail "a missing /admin/groups must NOT stop catalogs being published:\n${partial}"
printf '%s' "${partial}" | grep -q 'catalog cadence: applied' ||
  fail "every catalog must still be attempted when groups are unavailable:\n${partial}"
printf '%s' "${partial}" | grep -q 'front-page groups were not reconciled' ||
  fail "skipping groups must be reported, not silent:\n${partial}"

# 8b. …and the same for a box that predates /admin/sites: the catalogs it can publish must still
#     be published, and the skip has to be reported rather than leaving the operator believing a
#     hostname is live.
cat > "${shim}/curl" <<'SH'
#!/usr/bin/env bash
# A box one release behind: everything but /admin/sites.
for a in "$@"; do
  case "$a" in
    */admin/sites) printf 'not found\n404'; exit 0 ;;
  esac
done
printf 'ok\n200'
SH
chmod +x "${shim}/curl"
no_sites=$(PATH="${shim}:${PATH}" BASE_URL=https://example.invalid ADMIN_TOKEN=t \
  TRUST_FILE="${tmp}/producers.json" CATALOGS_FILE="${tmp}/catalogs.json" \
  bash "${UNDER_TEST}" 2>&1)
printf '%s' "${no_sites}" | grep -q 'catalog compose-m3: applied' ||
  fail "a missing /admin/sites must NOT stop catalogs being published:\n${no_sites}"
printf '%s' "${no_sites}" | grep -q 'top-level sites were not reconciled' ||
  fail "skipping sites must be reported, not silent:\n${no_sites}"

# 9. A 404 on /admin/trust — the oldest route — genuinely does mean the admin API is off, so it
#    should stop early rather than emit one warning per entry.
cat > "${shim}/curl" <<'SH'
#!/usr/bin/env bash
printf 'not found\n404'
SH
chmod +x "${shim}/curl"
off=$(PATH="${shim}:${PATH}" BASE_URL=https://example.invalid ADMIN_TOKEN=t \
  TRUST_FILE="${tmp}/producers.json" CATALOGS_FILE="${tmp}/catalogs.json" \
  bash "${UNDER_TEST}" 2>&1)
printf '%s' "${off}" | grep -q 'admin API not enabled' ||
  fail "a 404 on /admin/trust should report the admin API as off:\n${off}"
[[ $(printf '%s\n' "${off}" | grep -c 'returned 404') -le 1 ]] ||
  fail "an admin-off box should warn once, not per entry:\n${off}"

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} check(s) failed" >&2
  exit 1
fi
echo "publish-config-to-box: all checks passed"
