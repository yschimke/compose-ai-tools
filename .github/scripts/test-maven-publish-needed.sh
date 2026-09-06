#!/usr/bin/env bash
# Self-test for maven-publish-needed.sh.
#
# This script's `false` answer suppresses an irreversible step: Central will not accept a version
# twice, so a wrong `false` cannot be repaired by re-running the release — the version simply
# never exists, and `resolvePluginVersion` sends consumers at a coordinate that 404s. Every
# fail-open path is therefore pinned here, as is the one narrowing the script makes (test
# sources) and the one exclusion (the release-please version block, which moves on every single
# release and would otherwise hold the guard permanently open).
#
# The fixtures are throwaway git repositories rather than this one: the interesting histories —
# a release that changed nothing, a shallow clone that cannot reach its baseline — are ones this
# repository either does not have or cannot construct on demand.

set -euo pipefail

cd "$(dirname "$0")/../.."
SCRIPT="$(pwd)/.github/scripts/maven-publish-needed.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

pass=0
fail=0
ok() { echo "  ok: $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $*" >&2; fail=$((fail + 1)); }

# `needed=` from a run, or the literal `error` when the script exits non-zero.
needed() {
  local out
  out="$("${SCRIPT}" "$@" 2>/dev/null)" || { echo error; return; }
  printf '%s\n' "${out}" | sed -n 's/^needed=//p'
}

expect() {
  local want="$1" got="$2" what="$3"
  if [ "${got}" = "${want}" ]; then ok "${what} -> ${got}"; else bad "${what} -> ${got} (want ${want})"; fi
}

# A miniature of the real layout: one publishing module, one that does not publish, the shared
# inputs, and a gradle.properties carrying the release-please block.
make_repo() {
  local dir="$1"
  mkdir -p "${dir}"
  git -C "${dir}" init -q
  git -C "${dir}" config user.email t@example.com
  git -C "${dir}" config user.name Test
  mkdir -p "${dir}/data/focus/core/src/main/kotlin" \
    "${dir}/data/focus/core/src/test/kotlin" \
    "${dir}/cli/src/main/kotlin" \
    "${dir}/build-logic" \
    "${dir}/gradle"
  cat > "${dir}/data/focus/core/build.gradle.kts" <<'EOF'
plugins { id("composeai.maven-publishing") }
EOF
  echo 'fun a() = 1' > "${dir}/data/focus/core/src/main/kotlin/A.kt"
  echo 'fun t() = 1' > "${dir}/data/focus/core/src/test/kotlin/ATest.kt"
  # No publishing plugin: changes here must never force a publish.
  echo 'plugins { kotlin("jvm") }' > "${dir}/cli/build.gradle.kts"
  echo 'fun cli() = 1' > "${dir}/cli/src/main/kotlin/Cli.kt"
  echo '// build logic' > "${dir}/build-logic/build.gradle.kts"
  echo '[versions]' > "${dir}/gradle/libs.versions.toml"
  echo 'rootProject.name = "fixture"' > "${dir}/settings.gradle.kts"
  echo '// root' > "${dir}/build.gradle.kts"
  cat > "${dir}/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx4g
# x-release-please-start-version
composeaiReleasedRuntimeVersion=1.0.0
# x-release-please-end
EOF
  git -C "${dir}" add -A
  git -C "${dir}" commit -qm "base"
  git -C "${dir}" tag v1.0.0
}

# Every release commit rewrites the release-please block, so each fixture release does too —
# otherwise the tests would pass for a reason the real repository never reproduces.
bump_release_block() {
  local dir="$1" version="$2"
  sed -i.bak "s/^composeaiReleasedRuntimeVersion=.*/composeaiReleasedRuntimeVersion=${version}/" \
    "${dir}/gradle.properties"
  rm -f "${dir}/gradle.properties.bak"
}

release() {
  local dir="$1" version="$2"
  bump_release_block "${dir}" "${version}"
  git -C "${dir}" add -A
  git -C "${dir}" commit -qm "chore(main): release ${version}"
  git -C "${dir}" tag "v${version}"
}

R="${tmp}/repo"
make_repo "${R}"

echo "== a release that changed nothing but the version block does not need a publish"
release "${R}" 1.1.0
expect false "$(needed --repo "${R}" --baseline v1.0.0 --head v1.1.0)" "version-block-only release"

echo "== a change to a non-publishing module does not need a publish"
echo 'fun cli() = 2' > "${R}/cli/src/main/kotlin/Cli.kt"
release "${R}" 1.2.0
expect false "$(needed --repo "${R}" --baseline v1.1.0 --head v1.2.0)" "CLI-only release"

echo "== a change to a publishing module's main source needs a publish"
echo 'fun a() = 2' > "${R}/data/focus/core/src/main/kotlin/A.kt"
release "${R}" 1.3.0
expect true "$(needed --repo "${R}" --baseline v1.2.0 --head v1.3.0)" "published main source changed"

echo "== a change to a publishing module's TEST source does not need a publish"
echo 'fun t() = 2' > "${R}/data/focus/core/src/test/kotlin/ATest.kt"
release "${R}" 1.4.0
expect false "$(needed --repo "${R}" --baseline v1.3.0 --head v1.4.0)" "published test source changed"

echo "== each shared build input forces a publish on its own"
for f in build-logic/build.gradle.kts gradle/libs.versions.toml settings.gradle.kts build.gradle.kts; do
  base="$(git -C "${R}" describe --tags --abbrev=0)"
  echo "// touched $(date +%s%N)" >> "${R}/${f}"
  git -C "${R}" add -A
  git -C "${R}" commit -qm "chore: touch ${f}"
  expect true "$(needed --repo "${R}" --baseline "${base}" --head HEAD)" "shared input ${f}"
done

echo "== gradle.properties outside the release block forces a publish"
base="$(git -C "${R}" rev-parse HEAD)"
echo 'org.gradle.parallel=true' >> "${R}/gradle.properties"
git -C "${R}" add -A
git -C "${R}" commit -qm "chore: gradle.properties"
expect true "$(needed --repo "${R}" --baseline "${base}" --head HEAD)" "real gradle.properties change"

echo "== the baseline advancing only on a publish is the caller's job, but a multi-release span works"
# v1.1.0 (nothing) then v1.2.0 (CLI only): spanning both from the last PUBLISHED tag still says no.
expect false "$(needed --repo "${R}" --baseline v1.0.0 --head v1.2.0)" "span of two skippable releases"
# ...and a span that contains the module change says yes, so nothing is lost by coalescing.
expect true "$(needed --repo "${R}" --baseline v1.0.0 --head v1.3.0)" "span containing a real change"

echo "== fail-open paths"
expect true "$(needed --repo "${R}")" "no baseline"
expect true "$(needed --repo "${R}" --baseline v9.9.9 --head HEAD)" "baseline not in the clone"
expect true "$(needed --repo "${R}" --baseline v1.0.0 --head v9.9.9)" "head not in the clone"

# A clone holding both refs with no history joining them: two roots in one repository.
UNREL="${tmp}/unrelated"
make_repo "${UNREL}"
git -C "${UNREL}" checkout -q --orphan other
git -C "${UNREL}" rm -rq --cached . 2>/dev/null || true
echo 'x' > "${UNREL}/unrelated.txt"
git -C "${UNREL}" add -A
git -C "${UNREL}" commit -qm "orphan"
git -C "${UNREL}" tag v2.0.0
expect true "$(needed --repo "${UNREL}" --baseline v1.0.0 --head v2.0.0)" "no common history"

echo "== a tree with no publishing modules fails open"
EMPTY="${tmp}/empty"
make_repo "${EMPTY}"
git -C "${EMPTY}" rm -rq data
git -C "${EMPTY}" commit -qm "drop publishing modules"
git -C "${EMPTY}" tag v1.5.0
expect true "$(needed --repo "${EMPTY}" --baseline v1.0.0 --head v1.5.0)" "no publishing modules found"

echo "== --print-paths lists the real repository's watch set"
paths="$("${SCRIPT}" --print-paths)"
count="$(printf '%s\n' "${paths}" | wc -l | tr -d ' ')"
if [ "${count}" -gt 50 ]; then ok "--print-paths lists ${count} paths"; else bad "--print-paths listed only ${count}"; fi
if printf '%s\n' "${paths}" | grep -qx 'build-logic'; then
  ok "watch set includes build-logic"
else
  bad "watch set is missing build-logic"
fi
if printf '%s\n' "${paths}" | grep -qx 'gradle-plugin'; then
  ok "watch set includes gradle-plugin"
else
  bad "watch set is missing gradle-plugin"
fi
# The CLI is the whole point of the split: it must never be in the watch set.
if printf '%s\n' "${paths}" | grep -qx 'cli'; then
  bad "watch set includes cli, which does not publish to Maven Central"
else
  ok "watch set excludes cli"
fi

echo "== --github-output writes the same answer it prints"
out="${tmp}/gh-output"
: > "${out}"
GITHUB_OUTPUT="${out}" "${SCRIPT}" --repo "${R}" --baseline v1.0.0 --head v1.2.0 --github-output >/dev/null
if grep -qx 'needed=false' "${out}"; then ok "wrote needed=false to GITHUB_OUTPUT"; else bad "GITHUB_OUTPUT missing needed=false"; fi

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
