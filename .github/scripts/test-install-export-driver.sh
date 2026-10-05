#!/usr/bin/env bash
# Self-test for install-export-driver.sh and the lock it installs from.
#
# The lock in .github/design-artifacts-driver/ is what decides the code the
# privileged design-artifacts jobs run, so the two ways it could stop doing that
# are gated here: a package.json that names a range (Renovate would then be free
# to float it) and a scripts-only fetch that unpacks a tarball the lock did not
# name. Needs the npm registry, as the workflows do.

set -euo pipefail

cd "$(dirname "$0")/../.."
SCRIPT=".github/scripts/install-export-driver.sh"
LOCK_DIR=".github/design-artifacts-driver"
PKG="@design-parity/export-driver"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

pass=0
fail=0
ok() { echo "  ok: $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $*" >&2; fail=$((fail + 1)); }

echo "== the lock names the driver at one exact version"
declared="$(node -p "require('./${LOCK_DIR}/package.json').dependencies['${PKG}']")"
locked="$(node -p "require('./${LOCK_DIR}/package-lock.json').packages['node_modules/${PKG}'].version")"
printf '%s' "${declared}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  && ok "package.json pins ${declared}" || bad "package.json names a range: ${declared}"
[ "${declared}" = "${locked}" ] && ok "lock agrees (${locked})" || bad "lock has ${locked}, package.json ${declared}"

echo "== --scripts-only unpacks the locked driver and nothing else"
driver="$("${SCRIPT}" --scripts-only "${tmp}/scripts")"
[ "$(node -p "require('${driver}/package.json').version")" = "${locked}" ] \
  && ok "driver ${locked} at ${driver#"${tmp}"/}" || bad "unexpected driver version at ${driver}"
[ -f "${driver}/generate-design-catalog.mjs" ] && ok "driver scripts present" || bad "generate-design-catalog.mjs missing"
[ "$(find "${tmp}/scripts/node_modules" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ] \
  && ok "no dependencies installed" || bad "scripts-only installed more than the driver"

echo "== a tarball that does not match the lock's integrity is refused"
mkdir -p "${tmp}/tampered"
cp "${LOCK_DIR}/package.json" "${tmp}/tampered/"
node -e '
  const fs = require("fs");
  const [src, dst, pkg] = process.argv.slice(1);
  const lock = JSON.parse(fs.readFileSync(src, "utf8"));
  lock.packages["node_modules/" + pkg].integrity = "sha512-" + Buffer.alloc(64).toString("base64");
  fs.writeFileSync(dst, JSON.stringify(lock));
' "${LOCK_DIR}/package-lock.json" "${tmp}/tampered/package-lock.json" "${PKG}"
if EXPORT_DRIVER_LOCK_DIR="${tmp}/tampered" "${SCRIPT}" --scripts-only "${tmp}/bad" >/dev/null 2>&1; then
  bad "accepted a tarball whose integrity does not match"
else
  ok "integrity mismatch rejected"
fi
[ ! -e "${tmp}/bad/node_modules/${PKG}/package.json" ] && ok "nothing unpacked" || bad "unpacked despite the mismatch"

echo "== the full install is the same driver plus its locked dependencies"
full="$("${SCRIPT}" "${tmp}/full")"
diff -r "${driver}" "${full}" >/dev/null && ok "same driver bytes as --scripts-only" || bad "full and scripts-only drivers differ"
[ -x "${tmp}/full/node_modules/.bin/playwright" ] && ok "locked playwright on DRIVER_ROOT's bin path" || bad "no playwright bin"
(cd "${full}" && node -e 'import("playwright").then(() => import("@design-parity/catalog-export"))') \
  && ok "driver resolves its dependencies" || bad "driver cannot import its dependencies"

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
