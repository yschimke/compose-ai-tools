#!/usr/bin/env bash
# Guard the preview-host image's built-in Compose/Wasm UI registration. A released image must serve
# /wasm/preview-ui/ without an operator creating a directory or adding SERVE_WASM_DIR to .env.
# The optional env mapping stays additive and last-wins, so custom Wasm apps and an explicit
# preview-ui replacement remain possible.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

block="$(
  awk '
    /^# The release tarball carries the matching Compose\/Wasm preview browser\./ { capture = 1 }
    capture && /^# Trusted server-side re-render/ { exit }
    capture { print }
  ' "${entrypoint}"
)"
[[ -n "${block}" ]] || {
  echo "FAIL: could not find the built-in preview-ui registration in ${entrypoint}" >&2
  exit 1
}

# Exercise the shipped block while redirecting its fixed image path into an isolated fixture.
install="${tmp}/compose-preview"
block="${block//\/opt\/compose-preview/${install}}"

run_case() {
  local env_value="${1:-}"
  env -i PATH="${PATH}" SERVE_WASM_DIR="${env_value}" bash -c \
    "set -euo pipefail; args=(); ${block}; printf '%s\n' \"\${args[@]}\""
}

expect() {
  local wanted="$1" description="$2" env_value="${3:-}" actual
  actual="$(run_case "${env_value}")"
  if [[ "${actual}" != "${wanted}" ]]; then
    echo "FAIL: ${description}" >&2
    echo "  wanted: ${wanted@Q}" >&2
    echo "  actual: ${actual@Q}" >&2
    exit 1
  fi
  echo "  ok: ${description}"
}

echo "==> Built-in preview UI registration"
expect "" "an older distribution without preview-ui adds no implicit mapping"
expect $'--wasm-dir\nextra=/srv/extra' "an explicit app still works without the built-in" \
  "extra=/srv/extra"

mkdir -p "${install}/preview-ui"
touch "${install}/preview-ui/index.html"
built_in="preview-ui=${install}/preview-ui"
expect $'--wasm-dir\n'"${built_in}" "the packaged UI is enabled by default"
expect $'--wasm-dir\n'"${built_in},extra=/srv/extra" \
  "operator apps are appended to the default" "extra=/srv/extra"
expect $'--wasm-dir\n'"${built_in},preview-ui=/srv/replacement" \
  "an explicit preview-ui mapping comes last and can replace the default" \
  "preview-ui=/srv/replacement"

echo "PASS: the packaged preview UI is default-on and SERVE_WASM_DIR remains an override"
