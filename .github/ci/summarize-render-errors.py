#!/usr/bin/env python3
"""Summarize `*.png.error.json` render sidecars for a failing CI leg.

When `composePreviewRender` produces no PNGs, the reason is in the per-preview
sidecars (`PreviewRenderError`, schema `compose-preview-error/v1`) the renderer
drops next to where each PNG would have gone. The verify steps used to print
only the sidecar *paths* — on a leg like `wear-os-samples (WearTilesKotlin)`
that means 188 filenames and not one word about what actually threw, so
diagnosing it costs a download of the renders artifact (and that artifact is
not reachable from every environment).

Print the exceptions instead. Failures of this kind are overwhelmingly one root
cause replicated across every preview, so group by (exception, message) and show
the full stack trace for the largest group, with a one-line head for the rest.

Usage: summarize-render-errors.py <sidecar> [<sidecar> ...]
Always exits 0 — this is a diagnostic, the caller owns the failure exit.
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict

# Enough to identify the throw site without burying the log in 188 copies.
STACK_LINES = 40


def main(paths: list[str]) -> int:
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    unreadable: list[tuple[str, str]] = []

    for path in paths:
        try:
            with open(path) as handle:
                payload = json.load(handle)
        except (OSError, ValueError) as error:
            unreadable.append((path, str(error)))
            continue
        payload["_path"] = path
        groups[(payload.get("exception", "?"), payload.get("message", ""))].append(payload)

    if not groups and not unreadable:
        return 0

    total = sum(len(items) for items in groups.values())
    print(f"Render error sidecars: {total} preview(s) failed, {len(groups)} distinct cause(s).")

    # Largest group first: the dominant cause is nearly always the real one.
    ranked = sorted(groups.items(), key=lambda item: len(item[1]), reverse=True)

    for index, ((exception, message), items) in enumerate(ranked):
        sample = items[0]
        print()
        print(f"[{index + 1}/{len(ranked)}] {len(items)} preview(s): {exception}: {message}")
        frame = sample.get("topAppFrame")
        if frame:
            print(f"  at {frame.get('file', '?')}:{frame.get('line', 0)} ({frame.get('function', '?')})")
        print(f"  e.g. {sample.get('_path', '?')}")

        # Full trace for the dominant cause only; the rest are identified by
        # their exception + message + top frame above.
        if index == 0:
            trace = (sample.get("stackTrace") or "").splitlines()
            if trace:
                print("  stack trace:")
                for line in trace[:STACK_LINES]:
                    print(f"    {line}")
                if len(trace) > STACK_LINES:
                    print(f"    … {len(trace) - STACK_LINES} more frame(s)")

    if unreadable:
        print()
        print(f"{len(unreadable)} sidecar(s) could not be parsed:")
        for path, error in unreadable[:10]:
            print(f"  {path}: {error}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
