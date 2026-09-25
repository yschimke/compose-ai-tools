#!/usr/bin/env bash
# Self-test for maven-publish-plan.sh, run by CI on every PR.
#
# The plan only ever runs during a release, and both of its failure directions are quiet: too broad
# and a release uploads every coordinate for a comment edit (#5532 measured 7 of 13 releases going
# full), too narrow and a module whose POM moved is never uploaded, which Central cannot repair.
# Each case builds a throwaway repository with a baseline tag, makes one change, and pins the exact
# set the plan prints. The cases mirror the releases #5532 measured (#5576).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/maven-publish-plan.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

ALL="alpha beta compose-preview-config compose-preview-plugin daemon-launch-builder gamma preview-discovery"

repo="${tmp}/repo"
mkdir -p "${repo}"
cd "${repo}" || exit 1
git init -q -b main
git config user.email test@example.com
git config user.name test

mkdir -p alpha beta lib/gamma build-logic/src/main/kotlin build-logic/src/test/kotlin gradle
cat > settings.gradle.kts <<'EOF'
include(":alpha")
include(":beta")
include(":gamma")
project(":gamma").projectDir = file("lib/gamma")

dependencyResolutionManagement {
  versionCatalogs {
    // Snapshot mode overrides the ref; that writes the entry, it does not use it.
    create("libs") { version("okio", "9.9.9-SNAPSHOT") }
  }
}
EOF
cat > build.gradle.kts <<'EOF'
// The root build.
plugins { alias(libs.plugins.kotlin.jvm) apply false }

tasks.register("hello") { doLast { println("hi") } }
EOF
cat > alpha/build.gradle.kts <<'EOF'
plugins { id("composeai.maven-publishing") }
dependencies { implementation(libs.okio) }
EOF
cat > beta/build.gradle.kts <<'EOF'
plugins { id("composeai.maven-publishing") }
dependencies {
  implementation(project(":alpha"))
  implementation(
    libs.androidx
      .core
      .ktx
  )
}
EOF
cat > lib/gamma/build.gradle.kts <<'EOF'
plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.wire)
}
dependencies { implementation(libs.bundles.net) }
EOF
cat > build-logic/src/main/kotlin/Conventions.kt <<'EOF'
package conventions

// Reads the Kotlin version off the catalog.
fun kotlinVersion(libs: Any) = find(libs, "kotlinCore")

fun find(libs: Any, name: String): String = name
EOF
cat > build-logic/src/main/kotlin/Raw.kt <<'EOF'
package conventions

val banner = """
// not a comment: this is inside a raw string
"""
EOF
cat > build-logic/src/test/kotlin/ConventionsTest.kt <<'EOF'
package conventions

class ConventionsTest
EOF
cat > gradle/libs.versions.toml <<'EOF'
[versions]
# The Kotlin core libraries.
kotlinCore = "2.1.0"
kotlin = "2.1.0"
okio = "3.9.0"
okhttp = "4.12.0"
androidx-core = "1.13.0"
wire = "5.0.0"
unused = "1.0"

[libraries]
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidx-core" }
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlinCore" }

[bundles]
net = ["okhttp", "okhttp-logging"]

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
wire = { id = "com.squareup.wire", version.ref = "wire" }
EOF
git add -A
git commit -q -m base
git tag v1.0.0

printf '{"modules":{%s}}\n' "$(for a in ${ALL}; do printf '"%s":"1.0.0",' "${a}"; done | sed 's/,$//')" \
  > "${tmp}/manifest.json"

# check <name> <expected set, space separated> — runs a case from a clean checkout of the baseline.
# The change is applied by the function named `change_<name>` and committed first.
check() {
  local name="$1" expected="$2" actual
  git checkout -q -f v1.0.0
  git clean -qfdx
  git checkout -q -B "case-${name}"
  "change_${name}"
  git add -A
  git commit -q --allow-empty -m "${name}"
  actual="$("${UNDER_TEST}" --head HEAD --manifest "${tmp}/manifest.json" 2> "${tmp}/${name}.log" |
    tr '\n' ' ' | sed 's/ $//')"
  if [ "${actual}" != "${expected}" ]; then
    fail "${name}: expected [${expected}], got [${actual}]"
    sed 's/^/    /' "${tmp}/${name}.log" >&2
  else
    echo "ok: ${name}"
  fi
}

# Nothing changed: nothing publishes.
change_nothing() { :; }
check nothing ""

# v2.21.2: a comment-only edit to the root build.
change_root_comment_only() {
  python3 - <<'PY'
p = "build.gradle.kts"
s = open(p).read().replace("// The root build.\n", "// The root build, reworded.\n\n  // And a new line.\n")
open(p, "w").write(s)
PY
}
check root_comment_only ""

# A real edit to the root build still publishes everything.
change_root_code() { sed -i 's/println("hi")/println("hello")/' build.gradle.kts; }
check root_code "${ALL}"

# A trailing comment after code is not judged: only whole-line comments are.
change_root_trailing_comment() { sed -i 's/apply false }/apply false } \/\/ root plugins/' build.gradle.kts; }
check root_trailing_comment "${ALL}"

# v2.21.1: a build-logic test change on its own.
change_build_logic_test_only() { echo 'class AnotherTest' >> build-logic/src/test/kotlin/ConventionsTest.kt; }
check build_logic_test_only ""

# build-logic's main sources still publish everything.
change_build_logic_main() { echo 'val x = 1' >> build-logic/src/main/kotlin/Conventions.kt; }
check build_logic_main "${ALL}"

# A build-logic main source moved into a test directory is still a main-source change: rename
# detection would report only the (test-only) destination and exempt it.
change_build_logic_main_moved_to_test() { git mv build-logic/src/main/kotlin/Conventions.kt build-logic/src/test/kotlin/Conventions.kt; }
check build_logic_main_moved_to_test "${ALL}"

# A comment edit in build-logic main is comment-only.
change_build_logic_comment() { sed -i 's|// Reads the Kotlin version off the catalog.|// Reads it.|' build-logic/src/main/kotlin/Conventions.kt; }
check build_logic_comment ""

# ...but not in a file holding a raw string, where `//` at line start may be content.
change_raw_string_comment() { sed -i 's|// not a comment|// still not a comment|' build-logic/src/main/kotlin/Raw.kt; }
check raw_string_comment "${ALL}"

# v2.25.0 / v2.23.0: a catalog bump used by one module publishes it and its dependents only.
# `okio` is also named in a settings `version("okio", …)` override, which must not count as a use.
change_catalog_one_module() { sed -i 's/okio = "3.9.0"/okio = "3.10.0"/' gradle/libs.versions.toml; }
check catalog_one_module "alpha beta"

# An accessor ktfmt wrapped across lines (`libs.androidx\n.core\n.ktx`) is still found.
change_catalog_wrapped_accessor() { sed -i 's/androidx-core = "1.13.0"/androidx-core = "1.15.0"/' gradle/libs.versions.toml; }
check catalog_wrapped_accessor "beta"

# A version ref reaches a bundle through its libraries.
change_catalog_bundle_via_ref() { sed -i 's/okhttp = "4.12.0"/okhttp = "5.0.0"/' gradle/libs.versions.toml; }
check catalog_bundle_via_ref "gamma"

# A plugin alias used by one module.
change_catalog_plugin() { sed -i 's/wire = "5.0.0"/wire = "5.1.0"/' gradle/libs.versions.toml; }
check catalog_plugin "gamma"

# A catalog entry nobody uses publishes nothing.
change_catalog_unused() { sed -i 's/unused = "1.0"/unused = "2.0"/' gradle/libs.versions.toml; }
check catalog_unused ""

# A comment-only catalog edit publishes nothing.
change_catalog_comment() { sed -i 's/# The Kotlin core libraries./# Kotlin./' gradle/libs.versions.toml; }
check catalog_comment ""

# v2.24.0: a catalog entry build-logic reads (by string, `find(libs, "kotlinCore")`) publishes all.
change_catalog_used_by_build_logic() { sed -i 's/kotlinCore = "2.1.0"/kotlinCore = "2.2.0"/' gradle/libs.versions.toml; }
check catalog_used_by_build_logic "${ALL}"

# A catalog entry the root build uses (`libs.plugins.kotlin.jvm`) publishes all.
change_catalog_used_by_root() { sed -i 's/^kotlin = "2.1.0"/kotlin = "2.2.0"/' gradle/libs.versions.toml; }
check catalog_used_by_root "${ALL}"

# A catalog the plan cannot parse publishes all.
change_catalog_unparseable() { echo 'this is = = not toml' >> gradle/libs.versions.toml; }
check catalog_unparseable "${ALL}"

# A section the plan does not model publishes all.
change_catalog_unknown_section() { printf '\n[metadata]\nformat.version = "1.1"\n' >> gradle/libs.versions.toml; }
check catalog_unknown_section "${ALL}"

# The wrapper and other gradle/ files are still shared.
change_gradle_other() { mkdir -p gradle/wrapper && echo 'distributionUrl=x' > gradle/wrapper/gradle-wrapper.properties; }
check gradle_other "${ALL}"

# A module change still propagates to dependents alongside a catalog change.
change_catalog_and_module() {
  sed -i 's/wire = "5.0.0"/wire = "5.1.0"/' gradle/libs.versions.toml
  echo '// touched' >> alpha/build.gradle.kts
}
check catalog_and_module "alpha beta gamma"

if [ "${failures}" -gt 0 ]; then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "all maven-publish-plan cases passed"
