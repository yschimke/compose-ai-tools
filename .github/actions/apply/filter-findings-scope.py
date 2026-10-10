#!/usr/bin/env python3
"""Filter a baseline findings envelope to an affected module scope."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def filter_payload(
    payload: dict,
    modules: set[str] | None,
    current: dict | None = None,
    previews: set[str] | None = None,
) -> dict:
    """[payload] narrowed to [modules] (gradle paths) and/or [previews] (preview ids); None keeps
    every module / preview."""
    result = dict(payload)
    result["entries"] = [
        entry
        for entry in payload.get("entries", [])
        if (modules is None or entry.get("module", "").lstrip(":") in modules)
        and (previews is None or entry.get("previewId") in previews)
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
    parser.add_argument("--modules")
    # The previews a PR-scoped a11y run checked (`--id-file`), one id per line: the baseline is
    # compared on those alone, so a preview nobody looked at is never read as fixed or changed.
    parser.add_argument("--previews", type=Path)
    parser.add_argument("--current", type=Path)
    args = parser.parse_args()
    if args.modules is None and args.previews is None:
        parser.error("pass --modules, --previews or both")
    modules = (
        {item.lstrip(":") for item in args.modules.split(",") if item}
        if args.modules is not None
        else None
    )
    previews = (
        {line.strip() for line in args.previews.read_text().splitlines() if line.strip()}
        if args.previews is not None
        else None
    )
    payload = json.loads(args.findings.read_text())
    current = json.loads(args.current.read_text()) if args.current else None
    args.findings.write_text(json.dumps(filter_payload(payload, modules, current, previews)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
