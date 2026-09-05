#!/usr/bin/env bash
# The GitHub Release assets that must be attached before a release may be published.
#
# ONE list, TWO consumers, and they must never disagree:
#   * `finalize-release` in release-please.yml — the in-chain gate that un-drafts a release the
#     moment its build finishes; and
#   * `sweep-stranded-releases.sh` — the out-of-band sweeper that finishes a draft whose chain
#     died before it got there.
# A sweeper with a shorter list would publish a release the in-chain gate would have held back;
# a longer one would leave every release a draft for the sweeper to escalate. Hence this file.
#
# `compose-preview-${V}.vsix` is deliberately absent: the extension ships from
# yschimke/compose-preview-vscode now, so nothing here produces one and requiring it would make
# every release a permanent draft. See docs/RELEASING.md.
#
# Usage: required-release-assets.sh <version without the leading v>   # one asset name per line
set -euo pipefail

V="${1:?version required, e.g. 1.79.0}"

printf '%s\n' \
  "compose-preview-${V}.tar.gz" \
  "compose-preview-${V}.zip" \
  "compose-preview-mcp-${V}.tar.gz" \
  "compose-preview-android-daemon-${V}.zip"
