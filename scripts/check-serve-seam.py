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

# A fully qualified reference in code — `ee.schimke.composeai.cli.BundleReader.read(…)` with no
# import at all. Kotlin allows it, and an import-only scanner would let it walk straight through
# both the ratchet and the forbidden-package rule, which is exactly the coupling this check exists
# to see.
QUALIFIED_RE = re.compile(r"(?<![\w.])(ee\.schimke\.composeai\.cli\.[A-Za-z0-9_.]+)")

# Every import of this repo's own code, `cli` or not — the contract-coverage check below needs the
# ones the seam register deliberately ignores.
IMPORT_ANY_RE = re.compile(
    r"^import\s+(ee\.schimke\.composeai\.[A-Za-z0-9_.]+)", re.MULTILINE
)

def kotlin_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*.kt")) if root.is_dir() else []


_PACKAGE_OR_IMPORT_RE = re.compile(r"^\s*(package|import)\s+[^\n]*", re.MULTILINE)

RAW_QUOTE = '"' * 3


def _scan_string(text: str, i: int, n: int, out: list, terminator: str) -> int:
    """Blank a string literal from `i`, but emit `${...}` interpolations as code.

    A `${...}` interpolation is executable Kotlin: `"${ee.schimke.composeai.mcp.Factory.create()}"`
    creates exactly the dependency an ordinary expression would. Blanking the whole literal — the
    first shape of this scanner — hid that from both the ratchet and the forbidden-package rule.
    Simple `$name` interpolation needs no special handling: a bare identifier cannot be a qualified
    reference.

    Returns the index just past the closing quote.
    """
    out.append(" " * len(terminator))
    i += len(terminator)
    while i < n and text[i : i + len(terminator)] != terminator:
        # Escapes apply in both quoted forms — `'\''` is a valid char literal and mistaking its
        # escaped apostrophe for the terminator leaves the rest of the file inside a phantom
        # literal. Raw strings are the exception: a backslash there is a literal backslash.
        if terminator != RAW_QUOTE and text[i] == "\\":
            out.append("  ")
            i += 2
            continue
        if text[i : i + 2] == "${":
            out.append("  ")
            i += 2
            depth = 1
            while i < n and depth:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if not depth:
                        out.append(" ")
                        i += 1
                        break
                out.append(text[i])  # inside the interpolation: this is code, keep it
                i += 1
            continue
        out.append("\n" if text[i] == "\n" else " ")
        i += 1
    out.append(" " * len(terminator))
    return i + len(terminator)


def strip_comments_and_strings(text: str) -> str:
    """Blank out Kotlin comments and string literals, preserving newlines.

    A regex cannot do this. `"Disallow: /*/p/"` — a real literal in `ServeHttpRoutingTest.kt` —
    opens a block comment as far as a `/*.*?*/` pattern is concerned, and the blanking then runs
    to the next `*/` anywhere in the file, taking any qualified reference in between with it. That
    is a hole in the check rather than a cosmetic problem: it hides exactly what the check exists
    to find.

    So: a small state machine instead. Line comments, block comments (which nest in Kotlin),
    escapes, char literals, and raw triple-quoted strings.

    String *contents* are blanked along with comments — a fully qualified name inside a literal is
    text, not a compile-time dependency, and the alternative reads `"Disallow: /*?"` as a reference
    to something. `${...}` interpolations are the exception: those are executable Kotlin and are
    kept as code. The gap that leaves is a reflective `Class.forName("...mcp.X")`, which no
    import-graph check can see either; the resolved-classpath checks are what catch that.
    """
    out: list[str] = []
    i, n = 0, len(text)
    depth = 0  # block-comment nesting

    def blank(count: int) -> None:
        out.append(" " * count)

    while i < n:
        ch = text[i]
        two = text[i : i + 2]

        if depth:
            if two == "/*":
                depth += 1
                blank(2)
                i += 2
            elif two == "*/":
                depth -= 1
                blank(2)
                i += 2
            else:
                out.append("\n" if ch == "\n" else " ")
                i += 1
            continue

        if two == "/*":
            depth = 1
            blank(2)
            i += 2
            continue

        if two == "//":
            while i < n and text[i] != "\n":
                blank(1)
                i += 1
            continue

        if text[i : i + 3] == RAW_QUOTE:
            i = _scan_string(text, i, n, out, RAW_QUOTE)
            continue

        if ch == '"' or ch == "'":
            i = _scan_string(text, i, n, out, ch)
            continue

        out.append(ch)
        i += 1

    return "".join(out)


def code_body(text: str) -> str:
    """`text` reduced to code: no comments, no string contents, no package/import lines.

    Comments go because a KDoc that *links* to `ee.schimke.composeai.renderer.Foo` documents a
    relationship, it does not create one, and failing a build over a doc reference would train
    people to stop writing them. The package line goes because it would otherwise register as a
    reference to the file's own package.
    """
    return _PACKAGE_OR_IMPORT_RE.sub(" ", strip_comments_and_strings(text))


WILDCARD_IMPORT_RE = re.compile(
    r"^import\s+(ee\.schimke\.composeai\.[A-Za-z0-9_.]*\*)", re.MULTILINE
)


def wildcard_imports() -> list[str]:
    """Star imports across the seam, which a symbol-level ratchet cannot police.

    `import ee.schimke.composeai.cli.*` names no symbol, so nothing lands in the register; and
    `import ee.schimke.composeai.cli.serve.*` would collapse to one pseudo-entry that then licenses
    any number of crossings without the list changing. There are none in the tree today and the
    repo's style does not use them, so this is a guard rather than a migration.
    """
    hits: list[str] = []
    for source_set in SOURCE_SETS:
        roots = [serve_root(source_set), cli_root(source_set)]
        for root in roots:
            for path in kotlin_files(root):
                rel = path.relative_to(REPO_ROOT).as_posix()
                for fqn in WILDCARD_IMPORT_RE.findall(path.read_text(encoding="utf-8")):
                    if fqn.startswith(CLI_PKG):
                        hits.append(f"{rel}: import {fqn}")
    return sorted(set(hits))


def imports_in(path: Path) -> list[str]:
    """Every `ee.schimke.composeai.cli.*` name a file references, imported or written out."""
    text = path.read_text(encoding="utf-8")
    return IMPORT_RE.findall(text) + QUALIFIED_RE.findall(code_body(text))


def short(fqn: str) -> str:
    """Normalise a reference to the symbol the allowlist names.

    `ee.schimke.composeai.cli.serve.ServeHost` -> `serve.ServeHost`, and so does the qualified call
    `ee.schimke.composeai.cli.serve.ServeHost.Companion.of(…)` — an import and a fully qualified use
    of the same symbol have to land on the same allowlist entry, or the ratchet counts one crossing
    twice under two names.

    Trailing segments are dropped after the first capitalised one (the type). A reference with no
    capitalised segment is a top-level declaration — `serve.clampTo`, `downscaleRaster` — and is
    kept whole, which is also the shape its import takes.
    """
    rest = fqn[len(CLI_PKG) + 1 :].split(".")
    for i, segment in enumerate(rest):
        if segment[:1].isupper():
            return ".".join(rest[: i + 1])
    return ".".join(rest)


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
    """References to packages the extracted server must never reach into.

    Imports and fully qualified uses alike — a hard rule that only inspects the import block is not
    a hard rule.
    """
    hits: list[str] = []
    group = "|".join(re.escape(p) for p in forbidden)
    imported = re.compile(r"^import\s+(" + group + r")[.\s]", re.MULTILINE)
    qualified = re.compile(r"(?<![\w.])(" + group + r")\.")
    for source_set in SOURCE_SETS:
        for path in kotlin_files(serve_root(source_set)):
            rel = path.relative_to(REPO_ROOT).as_posix()
            text = path.read_text(encoding="utf-8")
            for match in imported.finditer(text):
                hits.append(f"{rel}: import {match.group(1)}")
            for match in qualified.finditer(code_body(text)):
                hits.append(f"{rel}: qualified reference to {match.group(1)}")
    return sorted(set(hits))


# ---------------------------------------------------------------------------
# Diffing against the allowlist
# ---------------------------------------------------------------------------


EXTERNAL_RE = re.compile(r"(?<![\w.])(ee\.schimke\.composeai\.[A-Za-z0-9_.]+)")


def external_packages() -> dict[str, set[str]]:
    """Every non-`cli` `ee.schimke.composeai` package serve reaches for, and where.

    This is the other half of the contract probe. `preview-server/contract-probe` compiles against
    a hand-maintained list of coordinates, and a hand-maintained list cannot notice that serve has
    started importing a *new* published module — the probe would resolve the same ten coordinates
    and pass while the extracted server's dependency floor had grown underneath it. Reading the
    packages straight out of serve's sources closes that loop: a new one fails here, naming the
    module that has to be added to the probe.
    """
    found: dict[str, set[str]] = {}
    for source_set in SOURCE_SETS:
        for path in kotlin_files(serve_root(source_set)):
            rel = path.relative_to(REPO_ROOT).as_posix()
            text = path.read_text(encoding="utf-8")
            for fqn in IMPORT_ANY_RE.findall(text) + EXTERNAL_RE.findall(code_body(text)):
                if fqn.startswith(CLI_PKG + "."):
                    continue
                found.setdefault(package_of(fqn), set()).add(rel)
    return found


def under(package: str, prefix: str) -> bool:
    """Is `package` the package `prefix`, or one nested inside it?

    Matching on segment boundaries, not raw text. A bare `startswith` makes
    `ee.schimke.composeai.mcpclient` look like it is under `ee.schimke.composeai.mcp`, which used to
    excuse it from the contract-coverage check while `forbidden_hits` — which does match on
    boundaries — did not report it either. A sibling package escaped both checks at once.
    """
    return package == prefix or package.startswith(prefix + ".")


def package_of(fqn: str) -> str:
    """`…data.layoutinspector.ComposeSemanticsNode` -> `…data.layoutinspector`."""
    parts = fqn.split(".")
    while parts and parts[-1][:1].isupper():
        parts.pop()
    return ".".join(parts)


def unmapped_contract_packages(allowlist: dict) -> dict[str, set[str]]:
    """Packages serve imports that no contract in the probe accounts for."""
    mapped = allowlist.get("contractPackages", {})
    forbidden = allowlist["forbiddenPackages"]
    unmapped: dict[str, set[str]] = {}
    for package, files in external_packages().items():
        if any(under(package, prefix) for prefix in forbidden):
            continue  # reported by the forbidden-package rule, with a better message
        if any(under(package, prefix) for prefix in mapped):
            continue
        unmapped[package] = files
    return unmapped


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
    # Start from the whole file and replace only the two direction blocks below. Listing the keys
    # to keep was the earlier shape and it silently dropped `contractPackages` and
    # `unpublishedContracts` when they were added — a `--write` would have deleted the contract
    # mapping and the recorded split blocker, and the next run would have reported every external
    # import as unmapped. A rewrite that loses data the tool itself depends on is worse than no
    # rewrite, so it copies by default and edits by exception.
    updated = dict(allowlist)

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

    unmapped = unmapped_contract_packages(allowlist)
    if unmapped:
        failures.append(
            "\nserve imports a package that no contract in the preview-server probe accounts "
            "for. The extracted server's dependency floor has grown:\n"
            + "".join(
                f"    {package}\n" + "".join(f"        {f}\n" for f in sorted(files))
                for package, files in sorted(unmapped.items())
            )
            + "  Add the module that publishes it to `contracts` in\n"
            "  preview-server/contract-probe/build.gradle.kts, name it in `contractPackages` in\n"
            "  scripts/serve-seam-allowlist.json, and say in the PR why the server needs it.\n"
            "  If it is NOT publishable, that is a split blocker — record it under\n"
            "  `unpublishedContracts` and in docs/design/PREVIEW_SERVER_SPLIT.md."
        )

    stars = wildcard_imports()
    if stars:
        failures.append(
            "\nwildcard import across the serve seam. A star import names no symbol, so the "
            "register cannot see what crosses:\n"
            + "".join(f"    {h}\n" for h in stars)
            + "  Import the symbols explicitly."
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
