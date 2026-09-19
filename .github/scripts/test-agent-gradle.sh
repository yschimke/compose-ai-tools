#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
test_root=$(mktemp -d)
trap 'if [ -n "${holder_pid-}" ]; then kill "${holder_pid}" 2>/dev/null || true; fi; rm -rf "${test_root}"' EXIT

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

override=$(PATH="${test_root}/bin:${PATH}" \
  "${repo_root}/scripts/agent-gradle.sh" --max-workers=1 check)
if grep -qx -- '--max-workers=4' <<<"${override}"; then
  echo "agent Gradle profile did not preserve a narrower task-specific worker limit" >&2
  exit 1
fi
grep -qx -- '--max-workers=1' <<<"${override}"

lock_path="${test_root}/runtime/compose-preview-gradle-${UID}.lock"
holder_ready="${test_root}/holder-ready"
exclusive_output="${test_root}/exclusive-output"
python3 - "${lock_path}" "${holder_ready}" <<'PY' &
import fcntl
import pathlib
import sys
import time

with open(sys.argv[1], "a+", encoding="utf-8") as lock_file:
    fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
    pathlib.Path(sys.argv[2]).touch()
    time.sleep(10)
PY
holder_pid=$!
for _ in {1..100}; do
  [ -f "${holder_ready}" ] && break
  sleep 0.01
done
if [ ! -f "${holder_ready}" ]; then
  echo "test lock holder did not become ready" >&2
  exit 1
fi
PATH="${test_root}/bin:${PATH}" XDG_RUNTIME_DIR="${test_root}/runtime" \
  "${repo_root}/scripts/agent-gradle.sh" --exclusive check >"${exclusive_output}" &
exclusive_pid=$!
sleep 0.1
if ! kill -0 "${exclusive_pid}" 2>/dev/null; then
  echo "exclusive agent Gradle profile did not wait for the cross-repository lock" >&2
  exit 1
fi
kill "${holder_pid}"
wait "${holder_pid}" 2>/dev/null || true
holder_pid=""
wait "${exclusive_pid}"

if [ "$(tail -1 "${exclusive_output}")" != "check" ]; then
  echo "exclusive agent Gradle profile did not forward the requested task" >&2
  exit 1
fi

echo "agent Gradle launcher tests passed"
