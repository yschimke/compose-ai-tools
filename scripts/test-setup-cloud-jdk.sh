#!/usr/bin/env bash
#
# Self-test for scripts/setup-cloud-jdk.sh, covering the part that decides
# whether to download a JDK at all.
#
# The regression it exists for: reuse was judged solely by whether
# `/opt/jdk<major>/bin/java` existed, so a container whose JDK 17 lived
# anywhere else (a Nix-provisioned Temurin symlinked to /usr/lib/jvm/temurin-17,
# which is what the coo.ee/env bootstrap produces) was treated as having no JDK
# 17. The script then fetched one from Adoptium — and in a sandbox that gates
# github.com per repository, that 403s and the whole setup aborts, on a box that
# had a perfectly good JDK 17 on PATH the whole time.
#
# Hermetic by construction, and it has to be: `curl` is a stub that records
# calls and always fails, the JDKs are fixture directory trees,
# CLOUD_JDK_SEARCH_DIRS points discovery at the fixture instead of this machine,
# CLOUD_JDK_JVM_LINK_DIR keeps the toolchain symlinks out of the real
# /usr/lib/jvm, and every case pins what `java` on PATH resolves to.
#
# None of that is belt-and-braces. An earlier draft leaked on every axis: the
# host's own JDKs answered half the cases, and the symlink step replaced a live
# /usr/lib/jvm/temurin-17 with a fixture path that dangled the moment the test
# cleaned up after itself.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
script="${repo_root}/scripts/setup-cloud-jdk.sh"
fixture="$(mktemp -d)"
trap 'chmod -R u+w "${fixture}" 2>/dev/null || true; rm -rf "${fixture}"' EXIT

# A JDK-shaped directory: `release` carries the version the script reads, and
# `bin/javac` is what makes it a JDK rather than a JRE.
make_jdk() { # make_jdk <dir> <java-version>
  local dir="$1" version="$2"
  make_jre "${dir}" "${version}"
  printf '#!/usr/bin/env bash\n:\n' >"${dir}/bin/javac"
  chmod +x "${dir}/bin/javac"
}

# Same, without a compiler — Gradle cannot use this as a toolchain.
make_jre() { # make_jre <dir> <java-version>
  local dir="$1" version="$2"
  mkdir -p "${dir}/bin" "${dir}/lib/security"
  printf 'JAVA_VERSION="%s"\n' "${version}" >"${dir}/release"
  printf '#!/usr/bin/env bash\necho openjdk version "%s"\n' "${version}" >"${dir}/bin/java"
  chmod +x "${dir}/bin/java"
  : >"${dir}/lib/security/cacerts"
}

mkdir -p "${fixture}/bin"
cat >"${fixture}/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"${CALL_LOG}"
exit 1
EOF
chmod +x "${fixture}/bin/curl"

# The `java` every case sees on PATH unless it says otherwise. A major nobody
# asks for below, so PATH discovery never answers by accident.
make_jdk "${fixture}/pathjdk" "11.0.22"

# Run the script with a fully controlled environment: fixture curl first on
# PATH, a known `java`, and discovery scoped to the fixture tree. Extra `env`
# assignments come first so a case can override any of it.
# JAVA_HOME is unset unless a case sets it: this harness runs inside a
# provisioned sandbox whose own JAVA_HOME is a JDK 17, which would otherwise
# answer half these cases before the code under test got a look in.
run_script() { # run_script [env assignments...]
  env -u JAVA_HOME "$@" \
    CALL_LOG="${fixture}/calls" \
    CLOUD_JDK_SEARCH_DIRS="${fixture}/search/*" \
    CLOUD_JDK_JVM_LINK_DIR="${fixture}/jvm" \
    PATH="${fixture}/bin:${fixture}/pathjdk/bin:/usr/bin:/bin" \
    bash "${script}"
}

mkdir -p "${fixture}/search"

# ---------------------------------------------------------------------------
# 1. A JDK of the requested major already on PATH is reused, and nothing is
#    downloaded. This is the reported failure.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
make_jdk "${fixture}/onpath17" "17.0.19"
out="$(
  env -u JAVA_HOME PATH="${fixture}/bin:${fixture}/onpath17/bin:/usr/bin:/bin" \
    CALL_LOG="${fixture}/calls" \
    CLOUD_JDK_SEARCH_DIRS="${fixture}/search/*" \
    CLOUD_JDK_JVM_LINK_DIR="${fixture}/jvm" \
    CLOUD_JDK_MAJORS=17 \
    JDK17_DIR="${fixture}/opt/jdk17" \
    bash "${script}" 2>"${fixture}/stderr"
)"
test "${out}" = "${fixture}/onpath17" ||
  { echo "FAIL: expected the JDK on PATH as JAVA_HOME, got '${out}'" >&2; exit 1; }
test ! -s "${fixture}/calls" ||
  { echo "FAIL: downloaded despite a JDK 17 on PATH:" >&2; cat "${fixture}/calls" >&2; exit 1; }
grep -Fq "already present" "${fixture}/stderr" ||
  { echo "FAIL: no 'already present' log line" >&2; cat "${fixture}/stderr" >&2; exit 1; }
test "$(readlink -f "${fixture}/jvm/temurin-17")" = "$(readlink -f "${fixture}/onpath17")" ||
  { echo "FAIL: the reused JDK should be symlinked for toolchain detection" >&2; exit 1; }
# And nowhere near the machine's own toolchain directory.
test ! -e /usr/lib/jvm/temurin-999 || { echo "FAIL: test wrote to the real /usr/lib/jvm" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2. A JDK found only in a scanned directory (not on PATH, not JAVA_HOME) counts
#    too — /usr/lib/jvm/temurin-17 in the real report.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
make_jdk "${fixture}/search/temurin-17" "17.0.19"
out="$(run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-b" 2>/dev/null)"
test "${out}" = "${fixture}/search/temurin-17" ||
  { echo "FAIL: expected the scanned JDK as JAVA_HOME, got '${out}'" >&2; exit 1; }
test ! -s "${fixture}/calls" ||
  { echo "FAIL: downloaded despite a JDK 17 under the search path" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2b. $JAVA_HOME counts as well — a JDK the environment already points at is
#     the one everything else on the box is using.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
rm -rf "${fixture}/search"; mkdir -p "${fixture}/search"
make_jdk "${fixture}/homejdk17" "17.0.19"
out="$(run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-b2" \
  JAVA_HOME="${fixture}/homejdk17" 2>/dev/null)"
test "${out}" = "${fixture}/homejdk17" ||
  { echo "FAIL: expected \$JAVA_HOME's JDK to be reused, got '${out}'" >&2; exit 1; }
test ! -s "${fixture}/calls" ||
  { echo "FAIL: downloaded despite \$JAVA_HOME pointing at a JDK 17" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 3. A JDK of a *different* major is not mistaken for the one asked for.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
rm -rf "${fixture}/search"; mkdir -p "${fixture}/search"
make_jdk "${fixture}/search/temurin-21" "21.0.5"
run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-c" \
  >/dev/null 2>&1 && status=0 || status=$?
test "${status}" -ne 0 ||
  { echo "FAIL: expected failure when no JDK 17 exists and the download is blocked" >&2; exit 1; }
grep -Fq "curl" "${fixture}/calls" ||
  { echo "FAIL: expected a download attempt when only a JDK 21 is present" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 4. A pinned tag means "this exact build" — discovery must not short-circuit it.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
make_jdk "${fixture}/search/temurin-17" "17.0.19"
run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-d" \
  JDK17_VERSION="jdk-17.0.20+8" >/dev/null 2>&1 || true
grep -Fq "jdk-17.0.20" "${fixture}/calls" ||
  { echo "FAIL: a pinned JDK17_VERSION should still download that build" >&2
    cat "${fixture}/calls" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 5. CLOUD_JDK_REUSE_EXISTING=0 forces a download even with a JDK present —
#    including one sitting in the script's own install dir, which is the copy
#    people actually want to refresh.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-e" \
  CLOUD_JDK_REUSE_EXISTING=0 >/dev/null 2>&1 || true
test -s "${fixture}/calls" ||
  { echo "FAIL: CLOUD_JDK_REUSE_EXISTING=0 should have attempted a download" >&2; exit 1; }

: >"${fixture}/calls"
make_jdk "${fixture}/opt/jdk17-e2" "17.0.19"
run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-e2" \
  CLOUD_JDK_REUSE_EXISTING=0 >/dev/null 2>&1 || true
test -s "${fixture}/calls" ||
  { echo "FAIL: CLOUD_JDK_REUSE_EXISTING=0 must bypass install-dir reuse too" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 5b. A JRE of the right major is not a toolchain. Reusing it would hand back a
#     JAVA_HOME with no compiler, and the failure would surface much later.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
rm -rf "${fixture}/search"; mkdir -p "${fixture}/search"
make_jre "${fixture}/search/jre17" "17.0.19"
run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-jre" \
  >/dev/null 2>&1 || true
grep -Fq "curl" "${fixture}/calls" ||
  { echo "FAIL: a JRE (no bin/javac) must not satisfy the JDK requirement" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 5c. A pinned version bypasses install-dir reuse when the installed build is a
#     different one — but reuses it when it already matches, so pinning does not
#     re-download the same JDK every session.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
make_jdk "${fixture}/opt/jdk17-pin" "17.0.19"
run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-pin" \
  JDK17_VERSION="jdk-17.0.20+8" >/dev/null 2>&1 || true
grep -Fq "jdk-17.0.20" "${fixture}/calls" ||
  { echo "FAIL: a pin must not hand back a different build sitting in the install dir" >&2
    cat "${fixture}/calls" >&2; exit 1; }

: >"${fixture}/calls"
out="$(run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-pin" \
  JDK17_VERSION="jdk-17.0.19+7" 2>/dev/null)"
test "${out}" = "${fixture}/opt/jdk17-pin" ||
  { echo "FAIL: an install matching the pin should be reused, got '${out}'" >&2; exit 1; }
test ! -s "${fixture}/calls" ||
  { echo "FAIL: pinning re-downloaded a build already installed" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 5d. A relative search dir yields an absolute JAVA_HOME. A relative one would
#     break the output contract and produce a dangling toolchain symlink, since
#     `ln` resolves a relative target against the link's own directory.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
mkdir -p "${fixture}/relbase/vendor"
make_jdk "${fixture}/relbase/vendor/jdk17" "17.0.19"
out="$(
  cd "${fixture}/relbase" && env -u JAVA_HOME \
    CALL_LOG="${fixture}/calls" \
    CLOUD_JDK_SEARCH_DIRS="vendor/jdk17" \
    CLOUD_JDK_JVM_LINK_DIR="${fixture}/jvm-rel" \
    PATH="${fixture}/bin:${fixture}/pathjdk/bin:/usr/bin:/bin" \
    CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-rel" \
    bash "${script}" 2>/dev/null
)"
case "${out}" in
  /*) ;;
  *) echo "FAIL: JAVA_HOME must be absolute, got '${out}'" >&2; exit 1 ;;
esac
test -e "${fixture}/jvm-rel/temurin-17" ||
  { echo "FAIL: the toolchain symlink dangles for a relative search dir" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 6. Our own install dir still wins over discovery, and a JDK whose trust store
#    cannot be written does not abort the run — a store-provided JDK is
#    read-only, and that is a warning, not a failed provision.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
make_jdk "${fixture}/opt/jdk17-f" "17.0.19"
chmod a-w "${fixture}/opt/jdk17-f/lib/security" "${fixture}/opt/jdk17-f/lib/security/cacerts"
out="$(run_script CLOUD_JDK_MAJORS=17 JDK17_DIR="${fixture}/opt/jdk17-f" 2>"${fixture}/stderr")"
chmod u+w "${fixture}/opt/jdk17-f/lib/security"
test "${out}" = "${fixture}/opt/jdk17-f" ||
  { echo "FAIL: the configured install dir should win over discovery, got '${out}'" >&2; exit 1; }
test ! -s "${fixture}/calls" ||
  { echo "FAIL: downloaded despite an install-dir JDK" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 7. A legacy 1.8-style version normalises to major 8, not "1".
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
rm -rf "${fixture}/search"; mkdir -p "${fixture}/search"
make_jdk "${fixture}/search/jdk8" "1.8.0_412"
out="$(run_script CLOUD_JDK_MAJORS=8 JDK8_DIR="${fixture}/opt/jdk8" 2>/dev/null)"
test "${out}" = "${fixture}/search/jdk8" ||
  { echo "FAIL: 1.8.0_412 should be recognised as major 8, got '${out}'" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 8. Best-effort majors stay best-effort: a secondary major that cannot be
#    found or downloaded warns, while the primary still succeeds.
# ---------------------------------------------------------------------------
: >"${fixture}/calls"
rm -rf "${fixture}/search"; mkdir -p "${fixture}/search"
make_jdk "${fixture}/search/temurin-17" "17.0.19"
out="$(
  run_script CLOUD_JDK_MAJORS="17 25" \
    JDK17_DIR="${fixture}/opt/jdk17-g" JDK25_DIR="${fixture}/opt/jdk25" \
    2>"${fixture}/stderr"
)"
test "${out}" = "${fixture}/search/temurin-17" ||
  { echo "FAIL: the primary should still resolve when a secondary fails, got '${out}'" >&2; exit 1; }
grep -Fq "WARNING" "${fixture}/stderr" ||
  { echo "FAIL: expected a warning for the unavailable secondary major" >&2; exit 1; }

echo 'setup-cloud-jdk: tests passed'
