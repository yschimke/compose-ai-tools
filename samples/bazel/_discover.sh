#!/usr/bin/env bash
# Placeholder hermetic action for the `discover_resources` rule.
#
# Walks a list of XML resource files and emits a `resources.json`
# matching the wire format from the Gradle plugin's
# `discoverAndroidResources` task. Intentionally narrow: classifies
# `<vector>` and `<adaptive-icon>` root tags only — everything else
# is skipped, same as ResourceXmlClassifier.
#
# This script will be replaced by `compose-preview discover-resources`
# (issue #1253) once that subcommand exists. The rule's input/output
# contract stays the same: stdin = (out, module, variant, *srcs),
# stdout = resources.json.

set -euo pipefail

out="$1"
module="$2"
variant="$3"
shift 3

# Group inputs by `(base, name)` from path components:
#   …/<base><-qualifier?>/<name>.xml
# Skipping non-render tags via a root-element peek.
declare -A entries

for src in "$@"; do
    dir="$(dirname "$src")"
    qualified_base="$(basename "$dir")"
    base="${qualified_base%%-*}"
    qualifier=""
    if [ "$qualified_base" != "$base" ]; then
        qualifier="${qualified_base#${base}-}"
    fi
    name="$(basename "$src" .xml)"

    # Classify root tag — bail out on anything we don't render.
    root="$(grep -m1 -oE '<[a-zA-Z-]+' "$src" | tr -d '<' || true)"
    case "$root" in
        vector) type="vector" ;;
        adaptive-icon) type="adaptive-icon" ;;
        animated-vector) type="animated-vector" ;;
        *) continue ;;
    esac

    if [ "$base" != "drawable" ] && [ "$base" != "mipmap" ]; then
        continue
    fi

    id="${base}/${name}"
    key="$id"
    existing="${entries[$key]:-}"
    if [ -z "$existing" ]; then
        entries[$key]="${type}|${qualifier}=${src}"
    else
        entries[$key]="${existing}\n${qualifier}=${src}"
    fi
done

# Emit JSON. No external jq dep — this is a controlled shape.
{
    printf '{\n'
    printf '  "module": "%s",\n' "$module"
    printf '  "variant": "%s",\n' "$variant"
    printf '  "resources": [\n'
    first=1
    for key in "${!entries[@]}"; do
        value="${entries[$key]}"
        type="${value%%|*}"
        rest="${value#*|}"
        if [ $first -eq 0 ]; then printf ',\n'; fi
        first=0
        printf '    {\n'
        printf '      "id": "%s",\n' "$key"
        printf '      "type": "%s",\n' "$type"
        printf '      "sourceFiles": {\n'
        sf_first=1
        # shellcheck disable=SC2001
        echo -e "$rest" | while IFS='=' read -r qual path; do
            if [ $sf_first -eq 0 ]; then printf ',\n'; fi
            sf_first=0
            printf '        "%s": "%s"' "$qual" "$path"
        done
        printf '\n      },\n'
        printf '      "captures": []\n'
        printf '    }'
    done
    printf '\n  ],\n'
    printf '  "manifestReferences": []\n'
    printf '}\n'
} > "$out"
