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
    "${dir}/renderers/desktop/src/main/kotlin" \
    "${dir}/cli/src/main/kotlin" \
    "${dir}/build-logic" \
    "${dir}/gradle"
  cat > "${dir}/data/focus/core/build.gradle.kts" <<'EOF'
plugins { id("composeai.maven-publishing") }
EOF
  echo 'fun a() = 1' > "${dir}/data/focus/core/src/main/kotlin/A.kt"
  echo 'fun t() = 1' > "${dir}/data/focus/core/src/test/kotlin/ATest.kt"
  # A second publishing module, on the OTHER train. Without one the `--train` assertions below
  # could not tell "this train did not change" from "this train has no modules".
  cat > "${dir}/renderers/desktop/build.gradle.kts" <<'EOF'
plugins { id("composeai.maven-publishing") }
EOF
  echo 'fun r() = 1' > "${dir}/renderers/desktop/src/main/kotlin/R.kt"
  cat > "${dir}/renderers/desktop/gradle.lockfile" <<'EOF'
com.example:lib:1.0.0=compileClasspath,runtimeClasspath
empty=
EOF
  # No publishing plugin: changes here must never force a publish.
  echo 'plugins { kotlin("jvm") }' > "${dir}/cli/build.gradle.kts"
  echo 'fun cli() = 1' > "${dir}/cli/src/main/kotlin/Cli.kt"
  echo '// build logic' > "${dir}/build-logic/build.gradle.kts"
  # Lock state for the publishing module. Without it the guard fails open on every case (a
  # published module with no lockfile is a module whose dependencies it cannot see), so this is
  # what makes the rest of these assertions meaningful rather than vacuously true.
  cat > "${dir}/data/focus/core/gradle.lockfile" <<'EOF'
com.example:lib:1.0.0=compileClasspath,runtimeClasspath
empty=
EOF
  # A catalog with both halves: a library entry (covered by lock state) and a plugin entry whose
  # version.ref points into [versions] (covered by the toolchain fingerprint).
  cat > "${dir}/gradle/libs.versions.toml" <<'EOF'
[versions]
somelib = "1.0.0"
agp = "9.0.0"

[libraries]
somelib = { module = "com.example:lib", version.ref = "somelib" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
EOF
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
# `gradle/libs.versions.toml` is deliberately NOT in this list any more — it stopped being a
# blanket shared input when lock state took over the library half. Leaving it here would have
# kept passing for the wrong reason: appending a line puts it inside the trailing [plugins]
# section, so the toolchain fingerprint trips and the assertion goes green while asserting an
# invariant that no longer holds. The catalog's two halves are covered by their own cases below.
for f in build-logic/build.gradle.kts settings.gradle.kts build.gradle.kts; do
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

echo "== a LIBRARY-only catalog bump that moves no lockfile does not need a publish"
# The headline change: this used to force all 94 modules because the catalog was a blanket
# shared input. Lock state is what makes it answerable.
base="$(git -C "${R}" rev-parse HEAD)"
sed -i.bak 's/^somelib = "1.0.0"/somelib = "1.1.0"/' "${R}/gradle/libs.versions.toml"
rm -f "${R}/gradle/libs.versions.toml.bak"
git -C "${R}" add -A && git -C "${R}" commit -qm "chore(deps): bump somelib"
expect false "$(needed --repo "${R}" --baseline "${base}" --head HEAD)" "library-only catalog bump, no lockfile movement"

echo "== the same bump DOES need a publish once it moves a module's lock state"
base="$(git -C "${R}" rev-parse HEAD)"
sed -i.bak 's/^com.example:lib:1.0.0=/com.example:lib:1.1.0=/' "${R}/data/focus/core/gradle.lockfile"
rm -f "${R}/data/focus/core/gradle.lockfile.bak"
git -C "${R}" add -A && git -C "${R}" commit -qm "chore(deps): regenerate lockfiles"
expect true "$(needed --repo "${R}" --baseline "${base}" --head HEAD)" "lockfile moved"

echo "== a TOOLCHAIN bump needs a publish even though no lockfile moves"
# AGP and friends change the bytecode we publish but need not appear on any module's classpath,
# so lock state cannot see them. This is the hole the fingerprint closes.
base="$(git -C "${R}" rev-parse HEAD)"
sed -i.bak 's/^agp = "9.0.0"/agp = "9.1.0"/' "${R}/gradle/libs.versions.toml"
rm -f "${R}/gradle/libs.versions.toml.bak"
git -C "${R}" add -A && git -C "${R}" commit -qm "chore(deps): bump agp"
expect true "$(needed --repo "${R}" --baseline "${base}" --head HEAD)" "plugin-referenced version moved"

echo "== a comment-only edit inside [plugins] does not need a publish"
base="$(git -C "${R}" rev-parse HEAD)"
printf '\n# a prose note about the plugins above\n' >> "${R}/gradle/libs.versions.toml"
git -C "${R}" add -A && git -C "${R}" commit -qm "docs: comment the catalog"
expect false "$(needed --repo "${R}" --baseline "${base}" --head HEAD)" "comment-only catalog edit"

echo "== a published module with NO lock state fails open"
NOLOCK="${tmp}/nolock"
make_repo "${NOLOCK}"
git -C "${NOLOCK}" rm -q "data/focus/core/gradle.lockfile"
git -C "${NOLOCK}" commit -qm "chore: drop lock state"
git -C "${NOLOCK}" tag v1.9.0
expect true "$(needed --repo "${NOLOCK}" --baseline v1.0.0 --head v1.9.0)" "module without a lockfile"

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

echo "== --train answers per version line"
# The two-train split (docs/design/RELEASE_TRAINS.md § 5): `data/*` versions separately from
# everything else, so a release touching only one of them republishes only that one. These are the
# assertions that make a skipped train safe — a wrong `false` here strands 58 artifacts exactly
# the way a wrong `false` on the whole publish strands 94.
TR="${tmp}/trains"
make_repo "${TR}"

echo 'fun a() = 2' > "${TR}/data/focus/core/src/main/kotlin/A.kt"
git -C "${TR}" commit -aqm "data only"
git -C "${TR}" tag v1.1.0
expect true  "$(needed --repo "${TR}" --baseline v1.0.0 --head v1.1.0 --train data)" "data change -> data publishes"
expect false "$(needed --repo "${TR}" --baseline v1.0.0 --head v1.1.0 --train core)" "data change -> core does not"
expect true  "$(needed --repo "${TR}" --baseline v1.0.0 --head v1.1.0 --train all)"  "data change -> the single train still publishes"

echo 'fun r() = 2' > "${TR}/renderers/desktop/src/main/kotlin/R.kt"
git -C "${TR}" commit -aqm "core only"
git -C "${TR}" tag v1.2.0
expect false "$(needed --repo "${TR}" --baseline v1.1.0 --head v1.2.0 --train data)" "core change -> data does not"
expect true  "$(needed --repo "${TR}" --baseline v1.1.0 --head v1.2.0 --train core)" "core change -> core publishes"

# A shared build input is NOT split by the train filter: it can move the bytes of every module on
# either line, so it publishes both. Getting this wrong is the quiet way a train goes stale.
echo '// touched' >> "${TR}/build-logic/build.gradle.kts"
git -C "${TR}" commit -aqm "shared input"
git -C "${TR}" tag v1.3.0
expect true "$(needed --repo "${TR}" --baseline v1.2.0 --head v1.3.0 --train data)" "shared input -> data publishes"
expect true "$(needed --repo "${TR}" --baseline v1.2.0 --head v1.3.0 --train core)" "shared input -> core publishes"

# The narrowings the whole-set guard makes still apply per train, rather than being lost when the
# module set shrinks.
echo 'fun t() = 2' > "${TR}/data/focus/core/src/test/kotlin/ATest.kt"
git -C "${TR}" commit -aqm "data test only"
git -C "${TR}" tag v1.4.0
expect false "$(needed --repo "${TR}" --baseline v1.3.0 --head v1.4.0 --train data)" "data test source -> data does not publish"

# An unknown train is a hard failure, never a silently empty module set: an empty set would look
# like "this train did not change" and skip a publish that was needed.
if "${SCRIPT}" --repo "${TR}" --baseline v1.3.0 --head v1.4.0 --train nonsense >/dev/null 2>&1; then
  bad "--train nonsense was accepted"
else
  ok "--train nonsense is rejected"
fi

echo "== a tree with no publishing modules fails open"
EMPTY="${tmp}/empty"
make_repo "${EMPTY}"
# Both trains' modules, not just `data` — the case under test is an enumeration that finds
# nothing at all, and leaving the other train in place would make this assert something else.
git -C "${EMPTY}" rm -rq data renderers
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
