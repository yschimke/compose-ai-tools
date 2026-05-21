#!/usr/bin/env bash
# Placeholder hermetic action for the `discover_resources` rule.
#
# Walks a list of XML resource files and emits a `resources.json`
# matching the wire format from the Gradle plugin's
# `discoverAndroidResources` task. Intentionally narrow: classifies
# `<vector>`, `<animated-vector>` and `<adaptive-icon>` root tags
# only — everything else is skipped, same as ResourceXmlClassifier.
#
# Bash-3.2-compatible (no `declare -A`) so the sample builds on
# macOS' default /bin/bash. Resource and sourceFiles ordering is
# stabilised via `LC_ALL=C sort` so the output is byte-deterministic
# across hosts.
#
# Will be replaced by `compose-preview discover-resources` (#1253).
# The rule's input/output contract (`out module variant *srcs`,
# stdout = resources.json) stays the same.

set -euo pipefail

out="$1"
module="$2"
variant="$3"
shift 3

# Parallel indexed arrays keyed by a synthetic slot index.
# `sources[i]` is a newline-separated list of `qualifier<TAB>path`
# rows for resource `ids[i]`.
ids=()
types=()
sources=()

find_index() {
    local target="$1" i
    for i in "${!ids[@]}"; do
        if [ "${ids[$i]}" = "$target" ]; then
            printf '%s' "$i"
            return 0
        fi
    done
    return 1
}

for src in "$@"; do
    dir="$(dirname "$src")"
    qualified_base="$(basename "$dir")"
    base="${qualified_base%%-*}"
    qualifier=""
    if [ "$qualified_base" != "$base" ]; then
        qualifier="${qualified_base#${base}-}"
    fi
    name="$(basename "$src" .xml)"

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
    # path|qualifier — path-first because path is never empty, and `|`
    # avoids `read`'s whitespace-IFS quirk that would drop a leading
    # empty field for the default-qualifier case.
    entry="${src}|${qualifier}"
    if idx="$(find_index "$id")"; then
        sources[$idx]="${sources[$idx]}
${entry}"
    else
        ids+=("$id")
        types+=("$type")
        sources+=("$entry")
    fi
done

# Walk slots in id-sorted order for deterministic JSON.
sorted_indices=()
if [ "${#ids[@]}" -gt 0 ]; then
    while IFS= read -r idx; do
        sorted_indices+=("$idx")
    done < <(
        for i in "${!ids[@]}"; do
            printf '%s\t%d\n' "${ids[$i]}" "$i"
        done | LC_ALL=C sort | cut -f2
    )
fi

{
    printf '{\n'
    printf '  "module": "%s",\n' "$module"
    printf '  "variant": "%s",\n' "$variant"
    printf '  "resources": [\n'
    first=1
    for idx in "${sorted_indices[@]}"; do
        if [ $first -eq 0 ]; then printf ',\n'; fi
        first=0
        printf '    {\n'
        printf '      "id": "%s",\n' "${ids[$idx]}"
        printf '      "type": "%s",\n' "${types[$idx]}"
        printf '      "sourceFiles": {\n'
        sf_first=1
        while IFS='|' read -r path qual; do
            if [ $sf_first -eq 0 ]; then printf ',\n'; fi
            sf_first=0
            printf '        "%s": "%s"' "$qual" "$path"
        done < <(printf '%s\n' "${sources[$idx]}" | LC_ALL=C sort)
        printf '\n      },\n'
        printf '      "captures": []\n'
        printf '    }'
    done
    printf '\n  ],\n'
    printf '  "manifestReferences": []\n'
    printf '}\n'
} > "$out"
