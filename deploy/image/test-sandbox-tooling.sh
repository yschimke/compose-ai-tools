#!/usr/bin/env bash
# Guard: any playground sandbox profile the deploy docs tell an operator to set must be one this
# image can actually launch.
#
# Why this needs a test rather than a comment. The docs and the Dockerfile are edited independently,
# and a mismatch between them is SILENT in the posture this image ships in. A repo-access-gated host
# admits the playground before it ever looks at the jail (`PlaygroundPublicGate.decide` returns
# `Allow` for `repoAccessGated` ahead of every profile check), so a profile whose binary is missing
# does not refuse the lane — it logs a preflight warning and serves anyway, uncontained. That is
# exactly how preview.coo.ee ran: `bubblewrap` was absent from the image, the README said so and
# pointed at a `custom:` argv instead, and the resulting jail reported `active: true` with all three
# containment checks false.
#
# `custom:` is rejected outright here, not merely discouraged. A custom argv is a static prefix
# (`PlaygroundSandbox.command`, `Profile.CUSTOM -> customCommand`) handed no per-session `Paths`, so
# it cannot bind the work dir that only exists once a compile starts. Both reachable outcomes are
# wrong: bind nothing and the render cannot write, bind broadly and nothing is contained.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Profiles this image can launch, and the binary each needs. `strict` and `systemd` want
# `systemd-run`, which a container has no systemd to talk to, so they are absent by design rather
# than by omission — see README.md § Containment and issue #3211.
supported_profile() {
  case "$1" in
    bwrap) echo "bubblewrap" ;;
    unshare) echo "util-linux" ;;
    none) echo "" ;;
    *) return 1 ;;
  esac
}

# Every `SERVE_PLAYGROUND_SANDBOX=<value>` assignment in a docs file. Deliberately anchored to the
# `=` form so it matches what an operator copies into .env, and not the `SERVE_PLAYGROUND_SANDBOX:
# "${...}"` passthrough line in docker-compose.yml (which names no profile) nor the `_RO`/`_CPUS`
# siblings.
assigned_profiles() {
  grep -hoE 'SERVE_PLAYGROUND_SANDBOX=[A-Za-z0-9:._/-]+' "$@" 2>/dev/null |
    sed 's/^SERVE_PLAYGROUND_SANDBOX=//' | sort -u
}

# The apt package list the Dockerfile installs. The package may sit anywhere on a continued RUN
# line — `install-linux-font-fallbacks` takes its extra packages as argv, so `bubblewrap` shares a
# line with `curl ca-certificates git` rather than standing alone. Match it as a whole word, not as
# the start of a line: anchoring to the line start made this detector go blind the moment the
# package list was reflowed, and it reported a missing package the image was in fact installing.
# Comment lines are stripped first so the rationale comment above the RUN — which names the package
# it is explaining — cannot stand in for actually installing it.
dockerfile_installs() {
  local dockerfile="$1" pkg="$2"
  grep -vE '^[[:space:]]*#' "${dockerfile}" | grep -qE "(^|[[:space:]])${pkg}([[:space:]]|\\\\|$)"
}

check() {
  local dockerfile="$1"
  shift
  local -a docs=("$@")
  local failed=0 profile pkg

  local -a profiles=()
  mapfile -t profiles < <(assigned_profiles "${docs[@]}")

  if ((${#profiles[@]} == 0)); then
    echo "FAIL: no SERVE_PLAYGROUND_SANDBOX=<profile> assignment found in ${docs[*]} —" \
      "the detector is broken, or the docs stopped recommending a profile." >&2
    return 1
  fi

  for profile in "${profiles[@]}"; do
    if [[ "${profile}" == custom:* || "${profile}" == "custom" ]]; then
      echo "FAIL: the docs recommend a 'custom:' sandbox argv. A custom argv is a static prefix" \
        "and cannot bind the per-session work dir; use bwrap." >&2
      failed=1
      continue
    fi
    if ! pkg="$(supported_profile "${profile}")"; then
      echo "FAIL: the docs recommend SERVE_PLAYGROUND_SANDBOX=${profile}, which this image cannot" \
        "launch. Supported here: bwrap, unshare, none." >&2
      failed=1
      continue
    fi
    if [[ -n "${pkg}" ]] && ! dockerfile_installs "${dockerfile}" "${pkg}"; then
      echo "FAIL: the docs recommend SERVE_PLAYGROUND_SANDBOX=${profile}, but ${dockerfile}" \
        "does not install '${pkg}'. The jail would fail its preflight and the lane would serve" \
        "uncontained (issue #3211)." >&2
      failed=1
    fi
  done

  return "${failed}"
}

# ---------------------------------------------------------------------------
# The real check.
# ---------------------------------------------------------------------------
real_dockerfile="${DOCKERFILE_UNDER_TEST:-${here}/Dockerfile}"
real_readme="${README_UNDER_TEST:-${here}/README.md}"
for f in "${real_dockerfile}" "${real_readme}"; do
  [[ -f "${f}" ]] || {
    echo "FAIL: missing ${f}" >&2
    exit 1
  }
done

check "${real_dockerfile}" "${real_readme}" || exit 1
echo "PASS: every recommended sandbox profile is launchable in this image"

# ---------------------------------------------------------------------------
# Self-tests: one per failure shape, so a detector that has quietly stopped detecting fails CI
# rather than passing everything. Each builds a fixture that SHOULD fail and asserts that it does.
# ---------------------------------------------------------------------------
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

expect_fail() {
  local label="$1" dockerfile="$2" readme="$3"
  if check "${dockerfile}" "${readme}" >/dev/null 2>&1; then
    echo "FAIL: self-test '${label}' passed the check but should have failed." >&2
    exit 1
  fi
  echo "PASS: self-test — ${label}"
}

expect_pass() {
  local label="$1" dockerfile="$2" readme="$3"
  if ! check "${dockerfile}" "${readme}" >/dev/null 2>&1; then
    echo "FAIL: self-test '${label}' failed the check but should have passed." >&2
    exit 1
  fi
  echo "PASS: self-test — ${label}"
}

# 1. The regression this whole change fixes: docs say bwrap, image lacks bubblewrap.
printf 'FROM x\nRUN apt-get install -y \\\n      curl \\\n      git\n' >"${tmp}/no-bwrap.Dockerfile"
printf 'SERVE_PLAYGROUND_SANDBOX=bwrap\n' >"${tmp}/bwrap.md"
expect_fail "bwrap recommended but bubblewrap not installed" \
  "${tmp}/no-bwrap.Dockerfile" "${tmp}/bwrap.md"

# 2. A profile this image structurally cannot run.
printf 'SERVE_PLAYGROUND_SANDBOX=strict\n' >"${tmp}/strict.md"
expect_fail "strict recommended (needs systemd-run)" "${real_dockerfile}" "${tmp}/strict.md"

# 3. The footgun that produced the uncontained jail in production.
printf 'SERVE_PLAYGROUND_SANDBOX=custom:bwrap --bind / /\n' >"${tmp}/custom.md"
expect_fail "custom: argv recommended" "${real_dockerfile}" "${tmp}/custom.md"

# 4. The detector itself going blind — docs that recommend nothing at all.
printf 'no sandbox guidance here\n' >"${tmp}/empty.md"
expect_fail "no profile assignment found" "${real_dockerfile}" "${tmp}/empty.md"

# 5. The false alarm this detector used to raise: the package IS installed, but shares a continued
# line with its neighbours rather than starting one. A line-anchored match called this missing.
printf 'FROM x\nRUN /usr/local/bin/install-linux-font-fallbacks \\\n      libgl1 \\\n      curl ca-certificates git bubblewrap\n' \
  >"${tmp}/inline-bwrap.Dockerfile"
expect_pass "bubblewrap installed mid-line, not at the start of one" \
  "${tmp}/inline-bwrap.Dockerfile" "${tmp}/bwrap.md"

# 6. …and the flip side: naming the package in a comment is not installing it.
printf 'FROM x\n# bubblewrap belongs here, one day\nRUN apt-get install -y \\\n      curl\n' \
  >"${tmp}/commented-bwrap.Dockerfile"
expect_fail "bubblewrap only mentioned in a comment" \
  "${tmp}/commented-bwrap.Dockerfile" "${tmp}/bwrap.md"

echo "PASS: all sandbox tooling checks"
