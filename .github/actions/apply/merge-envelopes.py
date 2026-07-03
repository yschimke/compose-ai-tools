#!/usr/bin/env python3
"""Merge per-module `compose-preview show --json` envelopes into one.

Change-scoped runs invoke `show --module :<m>` once per affected module;
this concatenates the resulting envelopes so the rest of the compose
pipeline (compare-previews.py) sees the same `_previews.json` shape a full
`show` run produces.

    merge-envelopes.py OUT IN1 [IN2 ...]

* `schema` is taken from the first valid input.
* `counts` is dropped — it describes a single run and nothing downstream
  reads it.
* An empty (zero-byte) input is a module with no previews — skipped.
* An unparseable input is skipped with a warning and flips the exit code to
  1, mirroring a failed module render: the caller records it in
  `_compose_render_rc` so the job still ends red after the partial diff is
  posted.
* Duplicate preview ids across inputs (impossible for disjoint modules)
  keep the first occurrence and warn.

Exit codes: 0 = all inputs merged; 1 = some inputs skipped (partial
envelope written when at least one input was valid); 2 = nothing valid, no
output written.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(f"usage: {argv[0]} OUT IN1 [IN2 ...]", file=sys.stderr)
        return 2

    out_path = Path(argv[1])
    schema = None
    previews: list[dict] = []
    seen_keys: set[tuple[str, str]] = set()
    bad = 0
    valid = 0

    for name in argv[2:]:
        p = Path(name)
        try:
            text = p.read_text()
        except OSError as e:
            print(f"merge-envelopes: cannot read {p}: {e}", file=sys.stderr)
            bad += 1
            continue
        if not text.strip():
            # `show --module` on a module with zero previews can emit nothing.
            valid += 1
            continue
        try:
            raw = json.loads(text)
        except json.JSONDecodeError as e:
            print(f"merge-envelopes: {p} is not valid JSON ({e.msg})", file=sys.stderr)
            bad += 1
            continue
        if not isinstance(raw, dict) or not isinstance(raw.get("previews"), list):
            print(
                f"merge-envelopes: {p} is not a show envelope "
                f"(expected {{schema, previews}})",
                file=sys.stderr,
            )
            bad += 1
            continue
        valid += 1
        if schema is None and raw.get("schema"):
            schema = raw["schema"]
        for entry in raw["previews"]:
            key = (str(entry.get("module")), str(entry.get("id")))
            if key in seen_keys:
                print(f"merge-envelopes: duplicate preview {key}, keeping first",
                      file=sys.stderr)
                continue
            seen_keys.add(key)
            previews.append(entry)

    if valid == 0:
        print("merge-envelopes: no valid inputs, not writing output", file=sys.stderr)
        return 2

    envelope: dict = {"previews": previews}
    if schema is not None:
        envelope = {"schema": schema, "previews": previews}
    out_path.write_text(json.dumps(envelope))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
