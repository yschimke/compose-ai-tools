#!/usr/bin/env bash

# Retry a Gradle invocation only when dependency resolution was rejected by a
# remote HTTP repository. GitHub-hosted runners occasionally receive Maven
# Central 429/403 responses; rerunning the unchanged task succeeds once the
# shared runner IP's short throttle clears. Test failures and ordinary Gradle
# errors return immediately, so this must not become a blanket flaky-test retry.

set -uo pipefail

max_attempts="${GRADLE_REPOSITORY_MAX_ATTEMPTS:-3}"
base_delay_seconds="${GRADLE_REPOSITORY_RETRY_DELAY_SECONDS:-15}"

if [[ "$#" -eq 0 ]]; then
  echo "usage: $0 <gradle command> [args ...]" >&2
  exit 2
fi

if ! [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "GRADLE_REPOSITORY_MAX_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if ! [[ "$base_delay_seconds" =~ ^[0-9]+$ ]]; then
  echo "GRADLE_REPOSITORY_RETRY_DELAY_SECONDS must be a non-negative integer" >&2
  exit 2
fi

attempt=1
while (( attempt <= max_attempts )); do
  log_file="$(mktemp "${RUNNER_TEMP:-/tmp}/gradle-repository-attempt.XXXXXX.log")"
  GRADLE_REPOSITORY_ATTEMPT="$attempt" "$@" 2>&1 | tee "$log_file"
  status=${PIPESTATUS[0]}

  if (( status == 0 )); then
    rm -f "$log_file"
    exit 0
  fi

  # Gradle normally prints the failed request and its HTTP status on different lines. `grep`
  # matches one line at a time, so requiring both fragments in one expression silently disabled
  # the retry for the ordinary repository-error shape this wrapper exists for.
  if (( attempt == max_attempts )) ||
    ! grep -Eiq "Could not (GET|HEAD) 'https?://[^']+'" "$log_file" ||
    ! grep -Eiq "Received status code (403|429|5[0-9][0-9])" "$log_file"; then
    rm -f "$log_file"
    exit "$status"
  fi

  rm -f "$log_file"
  delay=$((base_delay_seconds * attempt))
  echo "Gradle dependency repository returned a transient HTTP error; retrying attempt $((attempt + 1))/$max_attempts in ${delay}s…" >&2
  sleep "$delay"
  attempt=$((attempt + 1))
done
