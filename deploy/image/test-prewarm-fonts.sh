#!/usr/bin/env bash
# Self-test for prewarm-fonts.sh — runs OFFLINE against a stub `curl` on PATH.
#
# What's worth pinning here is the stuff that fails SILENTLY in production: a wrong cache filename,
# or skipping the stage-2 range query, both produce an image whose baked faces the renderer never
# looks at — so it re-downloads (or falls back to Roboto) and the live lane drifts from the baked
# PNG again, with nothing in any log to say so. Run by ci.yml.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${here}/prewarm-fonts.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

pass=0
fail=0
check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1" >&2
    echo "  expected: $2" >&2
    echo "  actual:   $3" >&2
  fi
}

# --- stub curl -------------------------------------------------------------------------------
# Answers the CSS2 endpoint from fixtures and serves fake TTF bytes for any gstatic url. Records
# every requested url so the tests can assert which stages ran.
mkdir -p "${work}/bin"
cat > "${work}/bin/curl" <<'STUB'
#!/usr/bin/env bash
url="${!#}"
echo "$url" >> "${CURL_LOG}"
case "$url" in
  # A purely-variable family: stage 1 (single weight) carries NO truetype url, which is what
  # forces the range retry.
  *"family=Variable%20Family:wght@400"*)
    echo "@font-face { font-family: 'Variable Family'; font-weight: 400; }" ;;
  *"family=Variable%20Family:wght@100..1000"*)
    echo "@font-face { font-weight: 400; src: url(https://fonts.gstatic.com/s/var.ttf) format('truetype'); }" ;;
  # A static family answering stage 1 directly.
  *"family=Static%20Family:wght@400"*)
    echo "@font-face { font-weight: 400; src: url(https://fonts.gstatic.com/s/static.ttf) format('truetype'); }" ;;
  # Multi-weight range response — the closest declared weight to 400 must win.
  *"family=Many%20Weights:wght@400"*)
    echo "@font-face { font-family: 'Many Weights'; }" ;;
  *"family=Many%20Weights:wght@100..1000"*)
    cat <<'CSS'
@font-face { font-weight: 100; src: url(https://fonts.gstatic.com/s/w100.ttf) format('truetype'); }
@font-face { font-weight: 500; src: url(https://fonts.gstatic.com/s/w500.ttf) format('truetype'); }
@font-face { font-weight: 900; src: url(https://fonts.gstatic.com/s/w900.ttf) format('truetype'); }
CSS
    ;;
  # A family the endpoint doesn't serve: both stages come back without a truetype url.
  *"family=Missing%20Family"*)
    echo "<!DOCTYPE html><p>Font family not found</p>" ;;
  https://fonts.gstatic.com/*) printf 'FAKE-TTF-BYTES' ;;
  *) exit 22 ;;
esac
STUB
chmod +x "${work}/bin/curl"
export PATH="${work}/bin:${PATH}"

# --- naming + stage 1 ------------------------------------------------------------------------
out="${work}/cache1"
export CURL_LOG="${work}/log1"; : > "${CURL_LOG}"
"${script}" "${out}" "Static Family" > /dev/null
check "static family lands under the slugified display name" \
  "static-family-400.ttf" "$(ls "${out}")"
check "stage 1 alone is enough for a static family" \
  "1" "$(grep -c 'wght@400' "${CURL_LOG}")"
check "no range query when stage 1 resolved" \
  "0" "$(grep -c '100\.\.1000' "${CURL_LOG}")"
check "the ttf bytes are written" "FAKE-TTF-BYTES" "$(cat "${out}/static-family-400.ttf")"

# --- stage 2 fallback ------------------------------------------------------------------------
out="${work}/cache2"
export CURL_LOG="${work}/log2"; : > "${CURL_LOG}"
"${script}" "${out}" "Variable Family" > /dev/null
check "variable family falls back to the range query" \
  "1" "$(grep -c 'wght@100\.\.1000' "${CURL_LOG}")"
check "variable family is cached" "variable-family-400.ttf" "$(ls "${out}")"

# --- closest-weight pick ---------------------------------------------------------------------
out="${work}/cache3"
export CURL_LOG="${work}/log3"; : > "${CURL_LOG}"
"${script}" "${out}" "Many Weights" > /dev/null
check "the declared weight closest to 400 wins" \
  "1" "$(grep -c 'w500.ttf' "${CURL_LOG}")"
check "farther weights are not fetched" "0" "$(grep -c 'w900.ttf' "${CURL_LOG}")"

# --- slug edge cases -------------------------------------------------------------------------
out="${work}/cache4"
export CURL_LOG="${work}/log4"; : > "${CURL_LOG}"
cp -r "${work}/cache1" "${out}"
mv "${out}/static-family-400.ttf" "${out}/keep.ttf"
printf 'ORIGINAL' > "${out}/static-family-400.ttf"
"${script}" "${out}" "Static Family" > /dev/null
check "an already-cached face is left untouched" "ORIGINAL" "$(cat "${out}/static-family-400.ttf")"
check "a cached face costs no request" "0" "$(grep -c . "${CURL_LOG}")"

# --- failure is loud -------------------------------------------------------------------------
out="${work}/cache5"
export CURL_LOG="${work}/log5"; : > "${CURL_LOG}"
set +e
"${script}" "${out}" "Missing Family" > /dev/null 2>&1
rc=$?
set -e
check "an unresolvable family fails the build" "1" "${rc}"
check "no partial file is left behind" "" "$(ls "${out}" 2>/dev/null)"

# --- a failure doesn't abort the rest --------------------------------------------------------
out="${work}/cache6"
export CURL_LOG="${work}/log6"; : > "${CURL_LOG}"
set +e
"${script}" "${out}" "Missing Family" "Static Family" > /dev/null 2>&1
rc=$?
set -e
check "the run still reports failure" "1" "${rc}"
check "families after the failure are still fetched" \
  "static-family-400.ttf" "$(ls "${out}")"

echo "prewarm-fonts self-test: ${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
