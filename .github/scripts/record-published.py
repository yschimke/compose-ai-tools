#!/usr/bin/env python3
"""Record, in publishing-manifest.json, the version each module just published at.

The manifest is the baseline the next release diffs against: a module's entry is the version its
artifact is actually on Central at, so `maven-publish-plan.sh` can ask "did anything under this
module change since *that* tag" rather than "since the last release". That distinction is what lets
a module sit out several releases and still be compared against its own last publish.

Only the modules named in --modules move. Everything else keeps its recorded version, because
everything else did not upload anything.

Usage: record-published.py --version 3.6.0 --modules a,b,c
       record-published.py --version 3.6.0 --modules ''   # nothing published; a no-op
"""

import argparse
import json
import pathlib
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--modules", required=True)
    parser.add_argument("--manifest", default="publishing-manifest.json")
    args = parser.parse_args()

    published = [m.strip() for m in args.modules.split(",") if m.strip()]
    if not published:
        print("nothing published; manifest unchanged")
        return 0

    path = pathlib.Path(args.manifest)
    document = json.loads(path.read_text(encoding="utf-8"))
    modules = document["modules"]

    unknown = [m for m in published if m not in modules]
    for name in unknown:
        # A new module's first release. It has no baseline to diff against, which the plan script
        # already treats as "publish", so recording it now is what stops it publishing forever.
        print(f"  new coordinate: {name}")
    for name in published:
        modules[name] = args.version

    document["modules"] = dict(sorted(modules.items()))
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"recorded {len(published)} module(s) at {args.version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
