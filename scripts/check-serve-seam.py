#!/usr/bin/env python3
"""Ratchet on the `serve` <-> `cli` coupling, ahead of the module extraction.

Issue #3824 asks for `checkServeModuleBoundary`, a build-enforced boundary
around an extracted `:cli:serve` module. That check cannot exist until the
module does — but the coupling it would police is drifting *now*, invisibly,
because `serve` is a package and packages have no boundary.

This is that boundary, one level down: a ratchet over the *symbol* surface
between `ee.schimke.composeai.cli.serve` and the rest of `ee.schimke.composeai.cli`.
Every symbol crossing it today is written down in `serve-seam-allowlist.json`.
The check fails when:

  * a crossing symbol appears that is not on the list — new coupling, which is
    what the split is trying to stop accruing; or
  * a listed symbol stops crossing — the list must shrink as the extraction
    proceeds, or it stops describing anything.

Both failures print the exact edit. The list is a debt register, not a
config file: entries only ever come off.

It also enforces the hard rule the extracted server must satisfy from day one
(`forbiddenPackages`): serve never imports a renderer or a concrete daemon
implementation. That one is not a ratchet — there is nothing to grandfather.

    python3 scripts/check-serve-seam.py            # check
    python3 scripts/check-serve-seam.py --write    # re-baseline (shrinking only)
    python3 scripts/check-serve-seam.py --write --allow-growth

Wired into `./gradlew :cli:checkServeSeam`, which `check` depends on. Pure
stdlib; unit-tested by scripts/test_check_serve_seam.py.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ALLOWLIST = Path(__file__).resolve().parent / "serve-seam-allowlist.json"

CLI_PKG = "ee.schimke.composeai.cli"
SERVE_PKG = f"{CLI_PKG}.serve"

SOURCE_SETS = ("main", "test")


def cli_root(source_set: str) -> Path:
    return REPO_ROOT / "cli/src" / source_set / "kotlin/ee/schimke/composeai/cli"


def serve_root(source_set: str) -> Path:
    return cli_root(source_set) / "serve"


IMPORT_RE = re.compile(
    r"^import\s+(ee\.schimke\.composeai\.cli\.[A-Za-z0-9_.]+)", re.MULTILINE
)


def kotlin_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*.kt")) if root.is_dir() else []


def imports_in(path: Path) -> list[str]:
    return IMPORT_RE.findall(path.read_text(encoding="utf-8"))


def short(fqn: str) -> str:
    """`ee.schimke.composeai.cli.serve.ServeHost` -> `serve.ServeHost`."""
    return fqn[len(CLI_PKG) + 1 :]


def scan(source_set: str) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """Return (cli internals used by serve, serve internals used by cli).

    Each maps a crossing symbol to the files that import it, so a failure can
    point at the code rather than just the name.
    """
    serve_dir = serve_root(source_set)
    used_by_serve: dict[str, set[str]] = {}
    used_by_cli: dict[str, set[str]] = {}

    for path in kotlin_files(serve_dir):
        rel = path.relative_to(REPO_ROOT).as_posix()
        for fqn in imports_in(path):
            # Serve importing its own package's members is not a crossing.
            if fqn.startswith(SERVE_PKG + "."):
                continue
            used_by_serve.setdefault(short(fqn), set()).add(rel)

    for path in kotlin_files(cli_root(source_set)):
        if serve_dir in path.parents:
            continue
        rel = path.relative_to(REPO_ROOT).as_posix()
        for fqn in imports_in(path):
            if not fqn.startswith(SERVE_PKG + "."):
                continue
            used_by_cli.setdefault(short(fqn), set()).add(rel)

    return used_by_serve, used_by_cli


def forbidden_hits(forbidden: list[str]) -> list[str]:
    """Imports of packages the extracted server must never reach into."""
    hits: list[str] = []
    pattern = re.compile(
        r"^import\s+(" + "|".join(re.escape(p) for p in forbidden) + r")[.\s]",
        re.MULTILINE,
    )
    for source_set in SOURCE_SETS:
        for path in kotlin_files(serve_root(source_set)):
            rel = path.relative_to(REPO_ROOT).as_posix()
            for match in pattern.finditer(path.read_text(encoding="utf-8")):
                hits.append(f"{rel}: {match.group(1)}")
    return sorted(hits)


# ---------------------------------------------------------------------------
# Diffing against the allowlist
# ---------------------------------------------------------------------------


def diff(observed: dict[str, set[str]], allowed: list[str]) -> tuple[list[str], list[str]]:
    allowed_set = set(allowed)
    added = sorted(set(observed) - allowed_set)
    stale = sorted(allowed_set - set(observed))
    return added, stale


def load_allowlist() -> dict:
    return json.loads(ALLOWLIST.read_text(encoding="utf-8"))


def observed_all() -> dict:
    out: dict[str, dict[str, set[str]]] = {}
    for source_set in SOURCE_SETS:
        used_by_serve, used_by_cli = scan(source_set)
        out[source_set] = {
            "cliInternalsUsedByServe": used_by_serve,
            "serveInternalsUsedByCli": used_by_cli,
        }
    return out


DIRECTION_HELP = {
    "cliInternalsUsedByServe": (
        "serve reaches into the CLI. Each entry is something the extracted "
        "server would have to get from a published contract module instead "
        "(bundle format, raster helpers, preview manifest types)."
    ),
    "serveInternalsUsedByCli": (
        "the CLI reaches into serve. Each entry is something that must either "
        "move to the server build with serve, or move down into a shared "
        "contract module the CLI can keep using."
    ),
}


def check(write: bool, allow_growth: bool) -> int:
    allowlist = load_allowlist()
    observed = observed_all()
    failures: list[str] = []
    # `_`-prefixed keys are prose (JSON has no comments) — carry them through --write.
    updated = {k: v for k, v in allowlist.items() if k.startswith("_")}
    updated["forbiddenPackages"] = allowlist["forbiddenPackages"]

    for source_set in SOURCE_SETS:
        updated[source_set] = {}
        for direction, seen in observed[source_set].items():
            allowed = allowlist.get(source_set, {}).get(direction, [])
            added, stale = diff(seen, allowed)
            if write:
                keep = sorted(set(allowed) - set(stale))
                updated[source_set][direction] = (
                    sorted(set(keep) | set(added)) if allow_growth else keep
                )
                if added and not allow_growth:
                    failures.append(
                        f"{source_set}/{direction}: refusing to write {len(added)} NEW "
                        "crossing(s) into the allowlist. The list only shrinks. "
                        "Pass --allow-growth if a crossing is genuinely unavoidable "
                        "and say why in the PR."
                    )
                continue

            if added:
                failures.append(
                    f"\n{source_set} — NEW coupling ({direction}):\n"
                    f"  {DIRECTION_HELP[direction]}\n"
                    + "".join(
                        f"    + {name}\n"
                        + "".join(f"        {f}\n" for f in sorted(seen[name]))
                        for name in added
                    )
                    + "  Remove the crossing, or add it to "
                    f"scripts/serve-seam-allowlist.json ({source_set}."
                    f"{direction}) with a reason in the PR body."
                )
            if stale:
                failures.append(
                    f"\n{source_set} — allowlist entries that no longer cross "
                    f"({direction}). Progress: prune them from "
                    "scripts/serve-seam-allowlist.json (or run "
                    "`python3 scripts/check-serve-seam.py --write`):\n"
                    + "".join(f"    - {name}\n" for name in stale)
                )

    hits = forbidden_hits(allowlist["forbiddenPackages"])
    if hits:
        failures.append(
            "\nserve imports a package the extracted server must never reach "
            "into (renderer / concrete daemon / gradle plugin / mcp):\n"
            + "".join(f"    {h}\n" for h in hits)
        )

    if write and not failures:
        ALLOWLIST.write_text(json.dumps(updated, indent=2) + "\n", encoding="utf-8")
        print(f"check-serve-seam: rewrote {ALLOWLIST.relative_to(REPO_ROOT)}")
        return 0

    if failures:
        print("check-serve-seam: FAILED", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
        return 1

    counts = {
        f"{s}.{d}": len(v)
        for s in SOURCE_SETS
        for d, v in observed[s].items()
    }
    total = sum(counts.values())
    print(
        "check-serve-seam: OK — "
        f"{total} crossing symbol(s), all on the allowlist "
        + ", ".join(f"{k}={v}" for k, v in counts.items())
    )
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--write", action="store_true", help="re-baseline the allowlist")
    ap.add_argument(
        "--allow-growth",
        action="store_true",
        help="with --write, permit NEW crossings to be recorded",
    )
    args = ap.parse_args(argv)
    return check(args.write, args.allow_growth)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
