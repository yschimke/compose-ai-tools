#!/usr/bin/env bash
# Self-test for install-export-driver.sh and the lock it installs from.
#
# The lock in .github/design-artifacts-driver/ is what decides the code the
# privileged design-artifacts jobs run, so the two ways it could stop doing that
# are gated here: a package.json that names a range (Renovate would then be free
# to float it) and an install that accepts a tarball the lock did not name. Needs
# the npm registry, as the workflows do.

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

echo "== the install is the locked driver and its locked dependencies"
driver="$("${SCRIPT}" "${tmp}/full")"
[ "$(node -p "require('${driver}/package.json').version")" = "${locked}" ] \
  && ok "driver ${locked} at ${driver#"${tmp}"/}" || bad "unexpected driver version at ${driver}"
[ -f "${driver}/generate-design-catalog.mjs" ] && ok "driver scripts present" || bad "generate-design-catalog.mjs missing"
[ -x "${tmp}/full/node_modules/.bin/playwright" ] && ok "locked playwright on DRIVER_ROOT's bin path" || bad "no playwright bin"
(cd "${driver}" && node -e 'import("playwright").then(() => import("@design-parity/catalog-export"))') \
  && ok "driver resolves its dependencies" || bad "driver cannot import its dependencies"
# The spec checks a render shard runs import these, and since design-parity moved the spec join's
# helpers into @design-parity/catalog-export they reach it. This is what a driver-only install broke.
(cd "${driver}" && node -e 'Promise.all(["./catalog-spec.mjs", "./catalog-priority.mjs", "./catalog-select.mjs"].map((m) => import(m)))') \
  && ok "the shard's spec modules import" || bad "the shard's spec modules do not import"

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
if EXPORT_DRIVER_LOCK_DIR="${tmp}/tampered" "${SCRIPT}" "${tmp}/bad" >/dev/null 2>&1; then
  bad "accepted a tarball whose integrity does not match"
else
  ok "integrity mismatch rejected"
fi
[ ! -e "${tmp}/bad/node_modules/${PKG}/package.json" ] && ok "nothing installed" || bad "installed despite the mismatch"

echo "== the retired --scripts-only flag is accepted and installs the same tree"
legacy="$("${SCRIPT}" --scripts-only "${tmp}/legacy")"
diff -r "${driver}" "${legacy}" >/dev/null && ok "same driver bytes" || bad "the flag changed what was installed"
[ -d "${tmp}/legacy/node_modules/@design-parity/catalog-export" ] \
  && ok "dependencies installed too" || bad "the flag still skips dependencies"

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
