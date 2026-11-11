#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT

mkdir -p "${fixture}/bin" "${fixture}/fontconfig"

cat >"${fixture}/bin/fc-match" <<'EOF'
#!/usr/bin/env bash
case "${*: -1}" in
  sans-serif) printf 'Existing & Sans,Alias\n' ;;
  serif) printf 'Existing Serif\n' ;;
  monospace) printf 'Existing Mono\n' ;;
  *) exit 2 ;;
esac
EOF

cat >"${fixture}/bin/fc-cache" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${CALL_LOG}"
EOF

cat >"${fixture}/bin/apt-get" <<'EOF'
#!/usr/bin/env bash
printf 'apt-get %s\n' "$*" >>"${CALL_LOG}"
EOF

cat >"${fixture}/bin/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF

chmod +x "${fixture}/bin/"*

CALL_LOG="${fixture}/calls" \
  FONTCONFIG_CONF_DIR="${fixture}/fontconfig" \
  PATH="${fixture}/bin:${PATH}" \
  bash "${repo_root}/scripts/install-linux-font-fallbacks.sh" libgl1 libfreetype6 >/dev/null

conf="${fixture}/fontconfig/99-composeai-preserve-generic-fonts.conf"
test -f "${conf}"
grep -Fq '<string>Existing &amp; Sans</string>' "${conf}"
grep -Fq '<string>Existing Serif</string>' "${conf}"
grep -Fq '<string>Existing Mono</string>' "${conf}"
grep -Fq 'install -y -qq --no-install-recommends libgl1 libfreetype6' "${fixture}/calls"
grep -Fq 'fonts-noto-cjk fonts-noto-core fonts-noto-color-emoji' "${fixture}/calls"
grep -Fq -- '-f' "${fixture}/calls"

# An unreachable mirror (the fonts can't be fetched) warns and succeeds; the caller's own packages
# still have to install.
cat >"${fixture}/bin/apt-get" <<'EOF2'
#!/usr/bin/env bash
printf 'apt-get %s\n' "$*" >>"${CALL_LOG}"
case "$*" in
  update*) exit 100 ;;
  *fonts-noto*) exit 100 ;;
esac
EOF2
chmod +x "${fixture}/bin/apt-get"
rm -f "${conf}" "${fixture}/calls"
out="$(CALL_LOG="${fixture}/calls" \
  FONTCONFIG_CONF_DIR="${fixture}/fontconfig" \
  PATH="${fixture}/bin:${PATH}" \
  bash "${repo_root}/scripts/install-linux-font-fallbacks.sh" libgl1 2>&1)"
grep -Fq 'could not install the Noto fallback fonts' <<<"${out}"
test ! -f "${conf}"
grep -Fq 'install -y -qq --no-install-recommends libgl1' "${fixture}/calls"

# A caller's own package failing still fails the script.
cat >"${fixture}/bin/apt-get" <<'EOF2'
#!/usr/bin/env bash
case "$*" in *libgl1*) exit 100 ;; esac
EOF2
chmod +x "${fixture}/bin/apt-get"
if CALL_LOG="${fixture}/calls" FONTCONFIG_CONF_DIR="${fixture}/fontconfig" PATH="${fixture}/bin:${PATH}" \
  bash "${repo_root}/scripts/install-linux-font-fallbacks.sh" libgl1 >/dev/null 2>&1; then
  echo 'install-linux-font-fallbacks: a required package failing must fail the script' >&2
  exit 1
fi

echo 'install-linux-font-fallbacks: tests passed'
