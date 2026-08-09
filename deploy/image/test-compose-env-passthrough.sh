#!/usr/bin/env bash
# Guard: every SERVE_* knob entrypoint.sh reads must be forwarded to the container by
# docker-compose.yml.
#
# Why this needs a test rather than a comment. The two files are edited independently — a new
# `serve` flag lands in entrypoint.sh (and gets documented in README.md) while docker-compose.yml,
# the thing that actually decides what reaches the container's environment, is left alone. Compose
# does NOT pass the host's environment through: an unlisted variable simply isn't there, so the
# entrypoint's `[[ -n "${VAR:-}" ]]` guard reads empty and skips the flag. Nothing errors, nothing
# is logged. The operator sets it in .env exactly as documented, restarts, and the box comes up
# behaving as though they never touched it.
#
# That is how `SERVE_PLAYGROUND` — documented in README.md since the runtime catalog selector
# shipped — spent its whole life unreachable on the prebuilt image, which is why preview.coo.ee ran
# its playground pinned to a single catalog with the Android modes off, despite the image already
# baking every Android bit they need.
#
# Direction matters: this checks entrypoint ⊆ compose. The reverse is fine and deliberate — compose
# also carries variables the entrypoint never reads (IMAGE_TAG, PREVIEW_MEM_LIMIT, the rollout and
# hook services' own), and the image's ENV supplies others directly.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"

for f in "${entrypoint}" "${compose}"; do
  [[ -f "${f}" ]] || {
    echo "FAIL: missing ${f}" >&2
    exit 1
  }
done

# Variables the entrypoint reads that are deliberately NOT operator-facing compose knobs. Keep this
# short and justified — it is an exemption from the guard, not a parking space. (Empty today.)
EXEMPT=()

# Every ${SERVE_...} / ${SERVE_...:-default} / ${SERVE_...:=default} expansion in the entrypoint.
mapfile -t read_vars < <(
  grep -oE '\$\{SERVE_[A-Z0-9_]+' "${entrypoint}" | sed 's/^\${//' | sort -u
)
((${#read_vars[@]})) || {
  echo "FAIL: found no SERVE_* reads in ${entrypoint} — the detector is broken." >&2
  exit 1
}

# Keys the compose file passes into the `preview` service's environment. Scoped to that service by
# slicing from its `environment:` block to the next key at the same indent, so the rollout and hook
# services' own environment blocks can't mask a missing `preview` one.
mapfile -t passed_vars < <(
  awk '
    /^  preview:/                  { in_svc = 1; next }
    in_svc && /^  [a-z]/           { in_svc = 0 }
    in_svc && /^    environment:/  { in_env = 1; next }
    in_env && /^    [a-z]/         { in_env = 0 }
    in_env && /^      [A-Za-z0-9_]+:/ { key = $1; sub(/:$/, "", key); print key }
  ' "${compose}" | sort -u
)

missing=()
for v in "${read_vars[@]}"; do
  exempt=0
  for e in ${EXEMPT[@]+"${EXEMPT[@]}"}; do [[ "${e}" == "${v}" ]] && exempt=1; done
  ((exempt)) && continue
  found=0
  for p in ${passed_vars[@]+"${passed_vars[@]}"}; do [[ "${p}" == "${v}" ]] && found=1; done
  ((found)) || missing+=("${v}")
done

if ((${#missing[@]})); then
  echo "FAIL: entrypoint.sh reads these variables, but docker-compose.yml's \`preview\` service" >&2
  echo "      does not pass them into the container — so setting them in .env does nothing:" >&2
  printf '        %s\n' "${missing[@]}" >&2
  echo >&2
  echo '  Fix: add `<VAR>: "${<VAR>:-}"` to the preview service'"'"'s environment: block,' >&2
  echo "  or add it to EXEMPT in $(basename "${BASH_SOURCE[0]}") with a comment saying why." >&2
  exit 1
fi

echo "PASS: all ${#read_vars[@]} SERVE_* variables read by entrypoint.sh are passed through"

# Self-test the detector. A guard whose awk or grep silently stops matching would pass for every
# input — a rubber stamp indistinguishable from a real pass. So prove it still rejects the exact
# shape of the bug it was written for: a compose file with SERVE_PLAYGROUND removed.
[[ -n "${PASSTHROUGH_GUARD_SELFTEST:-}" ]] && exit 0
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
grep -v '^      SERVE_PLAYGROUND:' "${compose}" > "${tmp}/docker-compose.yml"
if PASSTHROUGH_GUARD_SELFTEST=1 \
  COMPOSE_FILE_UNDER_TEST="${tmp}/docker-compose.yml" \
  ENTRYPOINT_FILE="${entrypoint}" \
  bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
  echo "FAIL: self-test — the guard accepted a compose file with SERVE_PLAYGROUND removed." >&2
  echo "      The detector matches nothing; it would rubber-stamp any input." >&2
  exit 1
fi
echo "PASS: self-test — a compose file missing a read variable is rejected"
