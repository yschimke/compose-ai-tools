#!/usr/bin/env bash
# Generate usage snippets from real catalog checkouts and check that they COMPILE.
#
# The Source panel tells a visitor a snippet is "the plain Compose that produces this render". Every
# other test of the cleaner feeds it source this repository controls, which can only prove that the
# rules match the fixtures. This walks actual catalogs, samples previews the way somebody browsing
# would land on them, and puts the output in front of a Kotlin compiler.
#
#   scripts/usage-corpus.sh ~/m3-catalog ~/meshcore-mobile
#
# Each argument is a catalog checkout; the system name is the directory name. With no arguments it
# looks for sibling checkouts of this repo. Exit status is 0 when every sampled snippet compiles.
#
# The compile classpath is deliberately **Compose and material3 only** (:tools:usage-compile-check),
# not the catalog's own: compiling against the catalog would let its internal helpers resolve and
# hide precisely the leakage this is looking for. The bar is "a developer pasted this into their own
# Compose app".
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${USAGE_CORPUS_OUT:-$here/build/usage-corpus}"
samples="${USAGE_CORPUS_SAMPLES:-5}"

repos=("$@")
if [ ${#repos[@]} -eq 0 ]; then
  for guess in "$here/../m3-catalog" "$here/../meshcore-mobile"; do
    [ -d "$guess" ] && repos+=("$(cd "$guess" && pwd)")
  done
fi
if [ ${#repos[@]} -eq 0 ]; then
  echo "usage-corpus: no catalog checkouts given and none found beside $here" >&2
  exit 2
fi

# One property carrying every checkout, rather than one key per catalog: a fixed key list on the
# Gradle side silently ignores any checkout not named in it, so a third catalog would have produced
# an empty corpus and a passing run.
spec=""
for repo in "${repos[@]}"; do
  case "$repo" in
    *,*) echo "usage-corpus: checkout path may not contain a comma: $repo" >&2; exit 2 ;;
  esac
  spec="${spec:+$spec,}$(basename "$repo")=$repo"
done

rm -rf "$out"
mkdir -p "$out"

echo "==> generating snippets into $out"
(cd "$here" && ./gradlew --quiet :cli:test --tests '*UsageSnippetCorpusTest*' \
  "-Dcomposeai.usageCorpus.repos=$spec" \
  -Dcomposeai.usageCorpus.out="$out" -Dcomposeai.usageCorpus.samples="$samples" \
  --rerun-tasks >/dev/null)

cat "$out/REPORT.md"

status=0
summary="$out/COMPILE.md"
: >"$summary"

for repo in "${repos[@]}"; do
  system="$(basename "$repo")"
  dir="$out/$system"
  total=0
  [ -d "$dir" ] && total=$(find "$dir" -name '*.kt' | wc -l | tr -d ' ')
  # A checkout that produced nothing is a failure, not a catalog to skip. The generator only asserts
  # that *some* snippet was written, so a silent skip here lets one good catalog carry a run in
  # which the other was never sampled at all — the report then reads as a pass for both.
  if [ "$total" -eq 0 ]; then
    {
      echo "## $system — NO SNIPPETS GENERATED"
      echo
      echo "Nothing was written to \`$dir\`. See its section in REPORT.md: every sample was"
      echo "\`SOURCE NOT FOUND\` / \`DECLINED\`, or the checkout was never sampled."
      echo
    } >>"$summary"
    status=1
    continue
  fi
  echo "==> compiling $total snippets from $system"

  # A failing compile is an expected outcome here, not a script error: the diagnostics ARE the
  # result. Kotlin reports every file it can resolve-check, so one run attributes per snippet.
  log="$out/$system-compile.log"
  rc=0
  (cd "$here" && ./gradlew --quiet ":tools:usage-compile-check:compileKotlin" \
    "-PusageCorpus=$dir" --rerun-tasks) >"$log" 2>&1 || rc=$?

  failed=$(grep -oE "^e: file://[^:]+\.kt" "$log" | sed 's|.*/||' | sort -u || true)
  nfailed=$(printf '%s\n' "$failed" | grep -c . || true)
  npassed=$((total - nfailed))

  # A nonzero exit with no per-file diagnostic is not "everything compiled" — it is the build
  # failing before it got as far as type-checking (dependency resolution, a missing toolchain, a
  # daemon crash). Reporting that as a clean run is the one failure mode that would quietly turn
  # this whole loop into a no-op, so it is a corpus failure and it says why.
  if [ "$rc" -ne 0 ] && [ -z "$failed" ]; then
    {
      echo "## $system — BUILD FAILED before type-checking (exit $rc)"
      echo
      echo "No per-file diagnostics, so nothing can be attributed to a snippet. Tail of the log:"
      echo '```'
      tail -20 "$log"
      echo '```'
      echo
    } >>"$summary"
    status=1
    continue
  fi

  {
    echo "## $system — $npassed/$total compile"
    if [ -n "$failed" ]; then
      echo
      echo "Did not compile:"
      while read -r file; do
        [ -z "$file" ] && continue
        # The first distinct reason per file: enough to classify without pasting a wall of Kotlin.
        # Both greps may legitimately match nothing (a diagnostic worded some other way), and under
        # `set -euo pipefail` an unmatched grep in a command substitution would abort the whole run
        # mid-report — so tolerate it and fall back to the raw diagnostic line.
        first=$(grep -F "$file:" "$log" | head -1 || true)
        reason=$(printf '%s' "$first" | grep -oE "(Unresolved reference '[^']+'|[A-Z][a-z].*)" | head -1 || true)
        [ -n "$reason" ] || reason="${first:-no diagnostic captured}"
        echo "- \`$file\` — $reason"
      done <<<"$failed"
    fi
    echo
  } >>"$summary"

  [ "$nfailed" -gt 0 ] && status=1
done

cat "$summary"
echo "==> corpus: $out"
exit "$status"
