#!/usr/bin/env bash
# Reject duplicate keys in workflow YAML.
#
# PyYAML's `safe_load` — and every "does this file parse?" check built on it — silently keeps the
# LAST of a duplicated key and discards the rest. GitHub Actions does the opposite: it refuses the
# whole file. So a workflow can pass local validation and still be rejected at parse time, and the
# rejection is about as unhelpful as CI failures get: the calling run fails with ZERO jobs, no logs
# and no annotation on the job that caused it.
#
# That is not hypothetical. A second `env:` on one step of `release.yml` shipped exactly that way:
#
#     Invalid workflow file: .github/workflows/release-please.yml#L187
#     error parsing called workflow ... (Line: 310, Col: 9): 'env' is already defined
#
# It failed the v2.1.0 release. And the silent-discard behaviour made it worse than a parse error
# would have been: the surviving `env:` was the second one, so the Maven Central credentials had
# been dropped from the step. Had Actions been as permissive as PyYAML, the publish would have run
# without them.
#
# Usage: check-workflow-yaml.sh [<file>...]     # defaults to .github/workflows/*.yml
set -euo pipefail

cd "$(dirname "$0")/../.."

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  mapfile -t files < <(find .github/workflows -maxdepth 1 -name '*.yml' -o -maxdepth 1 -name '*.yaml' | sort)
fi

python3 - "${files[@]}" <<'PY'
import sys
import yaml


class DuplicateKeyError(Exception):
    pass


class StrictLoader(yaml.SafeLoader):
    """A SafeLoader that refuses duplicate mapping keys, the way Actions does."""


def _no_duplicates(loader, node, deep=False):
    seen = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in seen:
            mark = key_node.start_mark
            raise DuplicateKeyError(
                f"line {mark.line + 1}, column {mark.column + 1}: '{key}' is already defined"
            )
        seen[key] = True
    return yaml.SafeLoader.construct_mapping(loader, node, deep)


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_duplicates
)

failed = 0
for path in sys.argv[1:]:
    try:
        with open(path) as handle:
            yaml.load(handle, Loader=StrictLoader)
    except DuplicateKeyError as exc:
        print(f"::error file={path}::duplicate key — {exc}")
        failed += 1
    except yaml.YAMLError as exc:
        print(f"::error file={path}::not valid YAML — {exc}")
        failed += 1

if failed:
    print(f"\n{failed} workflow file(s) rejected.")
    sys.exit(1)
print(f"{len(sys.argv) - 1} workflow file(s) OK.")
PY
