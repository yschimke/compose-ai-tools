#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
test_root=$(mktemp -d)
trap 'rm -rf "${test_root}"' EXIT

mkdir -p "${test_root}/bin" "${test_root}/runtime"
cat >"${test_root}/bin/build-brief" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@"
EOF
chmod +x "${test_root}/bin/build-brief"

expected=$(cat <<EOF
${repo_root}/gradlew
--priority=low
--max-workers=4
-Dorg.gradle.daemon.idletimeout=600000
--non-interactive
:gradle-plugin:test
--tests
ExampleTest
EOF
)

actual=$(PATH="${test_root}/bin:${PATH}" \
  "${repo_root}/scripts/agent-gradle.sh" :gradle-plugin:test --tests ExampleTest)
if [ "${actual}" != "${expected}" ]; then
  echo "agent Gradle profile forwarded unexpected arguments" >&2
  diff -u <(printf '%s\n' "${expected}") <(printf '%s\n' "${actual}") >&2 || true
  exit 1
fi

lock_path="${test_root}/runtime/compose-preview-gradle-${UID}.lock"
exclusive_output="${test_root}/exclusive-output"
exec 9>"${lock_path}"
flock 9
PATH="${test_root}/bin:${PATH}" XDG_RUNTIME_DIR="${test_root}/runtime" \
  "${repo_root}/scripts/agent-gradle.sh" --exclusive check >"${exclusive_output}" &
exclusive_pid=$!
sleep 0.1
if ! kill -0 "${exclusive_pid}" 2>/dev/null; then
  echo "exclusive agent Gradle profile did not wait for the cross-repository lock" >&2
  exit 1
fi
flock -u 9
wait "${exclusive_pid}"
exec 9>&-

if [ "$(tail -1 "${exclusive_output}")" != "check" ]; then
  echo "exclusive agent Gradle profile did not forward the requested task" >&2
  exit 1
fi

echo "agent Gradle launcher tests passed"
