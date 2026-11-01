#!/usr/bin/env python3
"""Put a release comparison into a release's notes, between markers, replacing any earlier one.

Usage: release_notes.py BODY_MD SUMMARY_JSON DIFF_LINKED_MD --base-tag T --out-repo R --out NOTES_MD

The section is a collapsed `<details>` whose summary counts the changes; the body is the
comparison without its marker and title. A body that would pass GitHub's release-notes limit
names the images branch instead of listing them.
"""
import argparse
import json
import re

START = "<!-- preview-release-diff:start -->"
END = "<!-- preview-release-diff:end -->"
LIMIT = 120_000


def notes(body: str, summary: dict, diff_linked: str, base_tag: str, out_repo: str) -> str:
    body = re.sub(re.escape(START) + ".*?" + re.escape(END), "", body, flags=re.S).rstrip()
    diff = diff_linked.split("\n", 2)[2].strip() if diff_linked.count("\n") >= 2 else ""
    line = (f"Preview changes since {base_tag}: {summary['changed']} changed, "
            f"{summary['new']} new, {summary['removed']} removed")
    section = f"{START}\n\n<details>\n<summary>{line}</summary>\n\n{diff}\n\n</details>\n{END}"
    out = (body + "\n\n" + section + "\n").lstrip()
    if len(out) > LIMIT:
        out = (body + f"\n\n{START}\n\n{line}. Too many to list here; see the "
               f"`compose-preview/releases` branch of {out_repo}.\n{END}\n").lstrip()
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("body")
    ap.add_argument("summary")
    ap.add_argument("diff")
    ap.add_argument("--base-tag", required=True)
    ap.add_argument("--out-repo", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    with open(a.body) as b, open(a.summary) as s, open(a.diff) as d:
        text = notes(b.read(), json.load(s), d.read(), a.base_tag, a.out_repo)
    with open(a.out, "w") as f:
        f.write(text)


if __name__ == "__main__":
    main()
