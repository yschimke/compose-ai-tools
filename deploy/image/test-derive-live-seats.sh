#!/usr/bin/env bash
# Guard: the auto-derived live-seat budget reflects BOTH memory and cores.
#
# Memory alone was the wrong input. A permit buys a render daemon and a render is CPU-bound, so a
# RAM-rich, core-poor box derived a budget it could not work — and the old [2, 8] clamp meant a large
# box stopped scaling at all. Measured on preview.coo.ee (48 GiB, 8 cores): memory afforded 40, the
# clamp allowed 8, and the box ran a fifth of what its cores could drive.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# Pull just the constants and the function out, so this exercises the real arithmetic without
# running an entrypoint that expects to be inside the container.
eval "$(sed -n '/^SEATS_PER_CPU=/,/^}$/p' "${entrypoint}")"
declare -F derive_live_seats >/dev/null || {
  echo "FAIL: derive_live_seats not found in ${entrypoint} — the extractor is broken." >&2
  exit 1
}

check() { # eff_mb cpus expected why
  local got; got="$(derive_live_seats "$1" "$2")"
  [[ "${got}" == "$3" ]] || {
    echo "FAIL: ${4} — ${1}MB/${2}cpu gave ${got}, expected ${3}" >&2
    exit 1
  }
  echo "PASS: ${4} (${1}MB, ${2} cpu -> ${got})"
}

# The deployed box. Memory affords 40, cores afford 16; the cores govern.
check 49152 8 16 "a large box is bounded by its cores, not clamped at 8"

# The old ceiling was 8. Prove we are past it — this is the regression that matters.
[[ "$(derive_live_seats 49152 8)" -gt 8 ]] || {
  echo "FAIL: the old 8-seat clamp is still in force" >&2
  exit 1
}
echo "PASS: the old 8-seat clamp is gone"

# Core-poor, RAM-rich: memory would have said 40, the cores say 4.
check 49152 2 4 "cores bound a RAM-rich box"

# RAM-poor, core-rich: the reverse — memory governs.
check 4096 32 2 "memory bounds a core-rich box"

# The reference 4 GB box still gets its floor.
check 4096 4 2 "the 4 GB reference box keeps the floor of 2"

# 8 GB / 4 cores: memory affords 5, cores 8 — memory governs, as it did before.
check 8192 4 5 "an 8 GB box derives what it always did"

# A very large box is still bounded, so a runaway derivation cannot spawn daemons without limit.
check 262144 128 32 "a huge box is capped at the ceiling"

# Unknown core count must not derive zero. Falling back to memory keeps the old behaviour, which is
# the right answer when half the inputs are missing.
check 49152 0 32 "an unknown core count falls back to the memory figure"
check 8192 0 5 "an unknown core count on a small box matches the old derivation"

# Unknown memory must not underflow into a negative seat count.
check 0 8 2 "unknown memory falls back to the floor, not a negative"

echo "PASS: all derive_live_seats checks"
