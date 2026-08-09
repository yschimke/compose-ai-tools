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

# Mappings the compose file passes into the `preview` service's environment, as `KEY=<value>`.
# Scoped to that service by slicing from its `environment:` block to the next key at the same
# indent, so the rollout and hook services' own environment blocks can't mask a missing `preview`
# one.
#
# The VALUE matters as much as the key. A mapping whose value is a constant, or which interpolates
# the wrong host variable — `SERVE_PLAYGROUND: "${SERVE_PLAYGROUND_BUNDLE:-}"`, one copy-paste away
# from the line above it — is present in the file and still leaves the knob inert, which is the very
# failure this guard exists to catch. So each mapping must interpolate the variable of the same
# name.
declare -A mapped=()
while IFS='=' read -r key value; do
  [[ -n "${key}" ]] && mapped["${key}"]="${value}"
done < <(
  awk '
    /^  preview:/                  { in_svc = 1; next }
    in_svc && /^  [a-z]/           { in_svc = 0 }
    in_svc && /^    environment:/  { in_env = 1; next }
    in_env && /^    [a-z]/         { in_env = 0 }
    in_env && /^      [A-Za-z0-9_]+:/ {
      key = $1; sub(/:$/, "", key)
      value = $0; sub(/^ *[A-Za-z0-9_]+: */, "", value)
      print key "=" value
    }
  ' "${compose}"
)

# Values that are deliberately hardcoded rather than sourced from the host environment. Each needs a
# reason: it is an assertion that the operator is MEANT to be unable to change this on a live box.
declare -A CONSTANT_OK=(
  # Pinned on: this box is always-on, so scale-to-zero idle exit must not be switchable per-host.
  [SERVE_IDLE_EXIT]=1
)

missing=()
miswired=()
for v in "${read_vars[@]}"; do
  exempt=0
  for e in ${EXEMPT[@]+"${EXEMPT[@]}"}; do [[ "${e}" == "${v}" ]] && exempt=1; done
  ((exempt)) && continue
  if [[ -z "${mapped[${v}]+set}" ]]; then
    missing+=("${v}")
    continue
  fi
  [[ -n "${CONSTANT_OK[${v}]:-}" ]] && continue
  # Accept ${VAR}, ${VAR:-…}, ${VAR:=…}, ${VAR:?…} — but the interpolation must be the WHOLE value,
  # so the host's setting reaches the container unaltered. Anchoring is what rejects
  # `"prefix-${SERVE_TIMEOUT:-}"`: a substring match would call that a pass, while `SERVE_TIMEOUT=60`
  # would arrive as `prefix-60` and go to `--timeout` as garbage. A knob that is forwarded but
  # mangled is no more usable than one that never arrives, which is the whole subject of this guard.
  #
  # The default text itself is unconstrained (`${VAR:-yschimke/compose-ai-tools}` is fine) except
  # that it may not contain `}` — a nested interpolation is rejected rather than parsed, since it is
  # both unused here and not something a regex should be trusted to read.
  [[ "${mapped[${v}]}" =~ ^\"?\$\{${v}(:[-=?][^}]*)?\}\"?$ ]] ||
    miswired+=("${v} → ${mapped[${v}]}")
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

if ((${#miswired[@]})); then
  echo "FAIL: these variables are listed in docker-compose.yml's \`preview\` environment, but their" >&2
  echo "      values do not read the same-named host variable — so .env still cannot set them:" >&2
  printf '        %s\n' "${miswired[@]}" >&2
  echo >&2
  echo '  Fix: make the value `"${<VAR>:-}"` (matching name), or — if the constant is deliberate —' >&2
  echo "  add it to CONSTANT_OK in $(basename "${BASH_SOURCE[0]}") with the reason." >&2
  exit 1
fi

echo "PASS: all ${#read_vars[@]} SERVE_* variables read by entrypoint.sh are passed through, each" \
  "reading its own host variable"

# Self-test the detector. A guard whose awk or grep silently stops matching would pass for every
# input — a rubber stamp indistinguishable from a real pass. So prove it still rejects each shape of
# the bug it exists for, one mutation per shape.
[[ -n "${PASSTHROUGH_GUARD_SELFTEST:-}" ]] && exit 0
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# Each case: a description, and a sed/grep program that mutates the real compose file.
selftest() {
  local desc="$1" mutated="${tmp}/docker-compose.yml"
  shift
  "$@" < "${compose}" > "${mutated}"
  if cmp -s "${compose}" "${mutated}"; then
    echo "FAIL: self-test '${desc}' — the mutation changed nothing, so it proves nothing." >&2
    echo "      (Did the line it targets get renamed?)" >&2
    exit 1
  fi
  if PASSTHROUGH_GUARD_SELFTEST=1 \
    COMPOSE_FILE_UNDER_TEST="${mutated}" \
    ENTRYPOINT_FILE="${entrypoint}" \
    bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
    echo "FAIL: self-test — the guard accepted a compose file where ${desc}." >&2
    echo "      The detector does not catch that shape; it would rubber-stamp it in review." >&2
    exit 1
  fi
  echo "PASS: self-test — rejected: ${desc}"
}

# The original bug: the key is absent entirely.
selftest "SERVE_PLAYGROUND is missing" grep -v '^      SERVE_PLAYGROUND:'
# The key is present but pinned to a constant, so .env cannot move it.
selftest "SERVE_PLAYGROUND is hardcoded to a constant" \
  sed 's|^      SERVE_PLAYGROUND: .*|      SERVE_PLAYGROUND: "0"|'
# The key is present and interpolated, but from the WRONG host variable — the copy-paste slip.
selftest "SERVE_PLAYGROUND reads a different host variable" \
  sed 's|^      SERVE_PLAYGROUND: .*|      SERVE_PLAYGROUND: "${SERVE_PLAYGROUND_BUNDLE:-}"|'
# The right variable, but wrapped — the host's value would reach the container mangled.
selftest "SERVE_TIMEOUT interpolates its own variable but wraps it" \
  sed 's|^      SERVE_TIMEOUT: .*|      SERVE_TIMEOUT: "prefix-${SERVE_TIMEOUT:-}"|'
