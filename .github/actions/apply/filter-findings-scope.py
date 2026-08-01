#!/usr/bin/env python3
"""Filter a baseline findings envelope to an affected module scope."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def filter_payload(payload: dict, modules: set[str], current: dict | None = None) -> dict:
    result = dict(payload)
    result["entries"] = [
        entry
        for entry in payload.get("entries", [])
        if entry.get("module", "").lstrip(":") in modules
    ]
    if current is not None:
        # A11y status is aggregated across modules. The full baseline's status
        # cannot be compared with a partial current render, so align it with
        # the scoped run and let per-preview findings carry the useful diff.
        result["status"] = current.get("status")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("findings", type=Path)
    parser.add_argument("--modules", required=True)
    parser.add_argument("--current", type=Path)
    args = parser.parse_args()
    modules = {item.lstrip(":") for item in args.modules.split(",") if item}
    payload = json.loads(args.findings.read_text())
    current = json.loads(args.current.read_text()) if args.current else None
    args.findings.write_text(json.dumps(filter_payload(payload, modules, current)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
