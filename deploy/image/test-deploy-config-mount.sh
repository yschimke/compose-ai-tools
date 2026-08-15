#!/usr/bin/env bash
# Guard: the version-control-backed config overlay actually mounts the files it claims to.
#
# Why this needs a test. docker-compose.deploy-config.yml is opt-in, so nothing exercises it on the
# way to a release — the first time anyone finds out it is wrong is on a box, after a restart, when
# the app boots from whatever /config it already had and serves a stale catalog set. Every failure
# mode here is silent that way: a source path that doesn't exist makes Docker create an empty
# DIRECTORY at the mount point (and the app then reads no catalogs), a target that isn't nested
# under the base file's /config volume mounts somewhere the app never looks, and a dropped `:ro`
# lets the admin API write back into the git working tree so the next `git pull` conflicts.
#
# It is all static: no Docker, no network, no compose binary.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
overlay="${OVERLAY_FILE:-${here}/docker-compose.deploy-config.yml}"
base="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"

for f in "${overlay}" "${base}"; do
  [[ -f "${f}" ]] || {
    echo "FAIL: missing ${f}" >&2
    exit 1
  }
done

fail=0
note() {
  echo "FAIL: $*" >&2
  fail=1
}

# The base file must keep /config on a named volume for the overlay's per-file mounts to nest
# inside it. If that ever becomes a directory bind, these mounts are redundant at best and
# shadowed at worst — either way this overlay needs rewriting rather than silently under-mounting.
grep -qE '^\s*-\s*preview_config:/config\s*$' "${base}" ||
  note "${base##*/} no longer mounts 'preview_config:/config' — the overlay's /config/<file> mounts assume it."

# Every bind line in the overlay, as `<source>:<target>:<mode>`.
mapfile -t mounts < <(
  grep -oE '^\s*-\s*\$\{DEPLOY_CONFIG_DIR:-[^}]+\}/[^:]+:[^:]+:[a-z]+\s*$' "${overlay}" |
    sed -E 's/^\s*-\s*//; s/\s*$//'
)
((${#mounts[@]})) || {
  echo "FAIL: found no DEPLOY_CONFIG_DIR bind mounts in ${overlay} — the detector is broken." >&2
  exit 1
}

for mount in "${mounts[@]}"; do
  mode="${mount##*:}"
  target="${mount%:*}"
  target="${target##*:}"
  source_spec="${mount%%:/*}"
  # The default the overlay ships, i.e. what an operator gets with DEPLOY_CONFIG_DIR unset.
  default_dir="$(sed -E 's/^\$\{DEPLOY_CONFIG_DIR:-([^}]+)\}.*/\1/' <<<"${source_spec}")"
  rel_file="${source_spec##*\}/}"

  [[ "${mode}" == "ro" ]] ||
    note "${rel_file} is mounted '${mode}', not 'ro' — the admin API would write into the git working tree."

  # A file mounted anywhere but /config/<same-name> is a file the app never reads: SERVE_*_FILE
  # points at /config, and the entrypoint seeds by that exact path.
  [[ "${target}" == "/config/${rel_file}" ]] ||
    note "${rel_file} mounts at '${target}', expected '/config/${rel_file}'."

  # A missing source is the loudest-looking, quietest-behaving failure: Docker creates an empty
  # directory there rather than erroring, and the app boots with no catalogs.
  resolved="${here}/${default_dir}/${rel_file}"
  [[ -f "${resolved}" ]] ||
    note "default DEPLOY_CONFIG_DIR source '${default_dir}/${rel_file}' does not exist (looked at ${resolved})."
done

# The overlay exists to carry `sites`, which publish-config-to-box.sh cannot. If the committed
# catalogs.json it defaults to ever loses that key, the overlay still works but the gap it was
# written for is back, unannounced.
default_catalogs="${here}/../preview.coo.ee/catalogs.json"
if [[ -f "${default_catalogs}" ]]; then
  grep -q '"sites"' "${default_catalogs}" ||
    note "${default_catalogs##*/} declares no \"sites\" — the overlay's reason for existing (delivering sites, which the admin reconcile cannot) is gone."
fi

if ((fail)); then
  exit 1
fi
echo "OK: ${#mounts[@]} deploy-config mount(s) check out."
