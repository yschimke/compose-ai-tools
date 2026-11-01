#!/usr/bin/env python3
"""compose-ai-tools' `preview-audit-jev` action: a Preview Audit by Jev, with a vision recheck.

No coding agent is involved. The changed previews are read off the preview diff
(`_audit/preview-diff.md`), matched to ids in each module's
`_audit/<module>.show.json`, grouped by the guidelines file
`.github/preview-audit/jev.json` names for them, and checked with

    compose-preview review --backend openrouter --checker jev --recheck-model <model>

Jev answers every rule from the render and the source. What it leaves
unchecked, or answers below p 0.8, is asked again of the recheck model. The
results are written to `_audit/report.md`, including what each group cost and
how much of that was the recheck, and that file is posted as the sticky audit
comment.

Environment (the action's inputs):
  COMPOSE_PREVIEW_OPENROUTER_KEY  the OpenRouter key
  JEV_CONFIG                      which guidelines file covers which module (default
                                  .github/preview-audit/jev.json)
  JEV_MODEL                       the decisions model (default typesafe/jev-1.13)
  RECHECK_MODEL                   vision model for the recheck; `none` turns it off
                                  (default xiaomi/mimo-v2.6-flash)
  MAX_COST                        cap per group in dollars (default 0.25)
  MAX_PREVIEWS                    changed previews checked at most (default 30)
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

AUDIT = Path(os.environ.get("AUDIT_DIR") or "_audit")
# Defaults, from a 40-render hand-labelled eval across three apps (compose-ai-tools #6392):
# jev-1.13 got all 51 labelled answers right on its own for $0.004, and mimo-v2.6-flash rechecked
# as accurately as deepseek-v4.1-flash for a quarter of the cost. ling-3.0-flash-vl takes at
# most 4 images a request and passed a real contrast defect; gpt-6-luna-decisions left two thirds
# of the rules `cannot_tell`.
DEFAULT_MODEL = "typesafe/jev-1.13"
DEFAULT_RECHECK = "xiaomi/mimo-v2.6-flash"
DEFAULT_CONFIG = ".github/preview-audit/jev.json"
ENTRY = re.compile(r"^\*\*`([^`]+)`\*\* \(([A-Za-z0-9_.-]+)\)")


def changed_functions(diff: str) -> dict[str, set[str]]:
    """Function names per module under the diff's Changed / New sections."""
    out: dict[str, set[str]] = {}
    section = ""
    for line in diff.splitlines():
        if line.startswith("### "):
            section = line[4:].lower()
            continue
        if section.startswith("removed") or "fail" in section:
            continue
        m = ENTRY.match(line.strip())
        if m:
            out.setdefault(m.group(2), set()).add(m.group(1))
    return out


def preview_ids(module: str, functions: set[str]) -> list[str]:
    shown = AUDIT / f"{module}.show.json"
    if not shown.is_file():
        return []
    data = json.loads(shown.read_text())
    previews = data if isinstance(data, list) else data.get("previews", [])
    return [p["id"] for p in previews if p.get("functionName") in functions]


def group_for(module: str, preview_id: str, config: dict) -> dict:
    for g in config.get("groups", []):
        if g.get("module") not in (None, module):
            continue
        if g.get("match") and g["match"] not in preview_id:
            continue
        return g
    return {"guidelines": config["default"]}


def cli_version() -> tuple[int, ...]:
    out = subprocess.run(["compose-preview", "--version"], capture_output=True, text=True).stdout
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", out)
    return tuple(int(x) for x in m.groups()) if m else (0, 0, 0)


# The first CLI whose Jev sees the render and can recheck (compose-ai-tools #6386, #6390).
RECHECK_SINCE = (2, 82, 0)


def money(v) -> str:
    return f"${float(v or 0):.4f}"


def main() -> int:
    config = json.loads(Path(os.environ.get("JEV_CONFIG") or DEFAULT_CONFIG).read_text())
    model = (os.environ.get("JEV_MODEL") or DEFAULT_MODEL).strip()
    recheck = (os.environ.get("RECHECK_MODEL") or DEFAULT_RECHECK).strip()
    max_previews = int(os.environ.get("MAX_PREVIEWS") or 30)
    version = cli_version()
    old_cli = version < RECHECK_SINCE
    if old_cli:
        recheck = ""
    max_cost = os.environ.get("MAX_COST") or "0.25"
    diff = (AUDIT / "preview-diff.md").read_text() if (AUDIT / "preview-diff.md").is_file() else ""
    context = (AUDIT / "context.md").read_text() if (AUDIT / "context.md").is_file() else ""

    # (module, guidelines, surface) -> ids, at most max_previews in all.
    groups: dict[tuple[str, str, str], list[str]] = {}
    skipped = 0
    total = 0
    for module, functions in sorted(changed_functions(diff).items()):
        for pid in preview_ids(module, functions):
            if total >= max_previews:
                skipped += 1
                continue
            g = group_for(module, pid, config)
            groups.setdefault((module, g["guidelines"], g.get("surface", "")), []).append(pid)
            total += 1

    lines = [f"## Preview audit ({model})", ""]
    if context:
        lines += [context.strip().splitlines()[0].lstrip("# ").strip(), ""]
    if old_cli:
        lines += [
            "> [!NOTE]\n> compose-preview " + ".".join(map(str, version)) + " is pinned here: its Jev "
            "judges from text only, with no recheck. Visual rules stay unchecked until the "
            "catalog moves to 2.82.0 or later.",
            "",
        ]
    if not groups:
        lines.append("No changed previews to check.")
        (AUDIT / "report.md").write_text("\n".join(lines) + "\n")
        return 0

    cost_total = recheck_total = 0.0
    findings_total = unchecked_total = checked_total = 0
    sections: list[str] = []
    problems: list[str] = []
    for n, ((module, guidelines, surface), ids) in enumerate(sorted(groups.items())):
        ids_file = AUDIT / f"jev-{n}.ids"
        ids_file.write_text("\n".join(ids) + "\n")
        cmd = [
            "compose-preview", "review", "--module", module, "--variant", "debug",
            "--id-file", str(ids_file), "--backend", "openrouter", "--checker", "jev",
            "--guidelines", guidelines, "--max-cost", max_cost, "--model", model,
        ]
        if surface:
            cmd += ["--surface", surface]
        if recheck and recheck != "none":
            cmd += ["--recheck-model", recheck]
        print("::group::" + " ".join(cmd), flush=True)
        rc = subprocess.run(cmd).returncode
        print("::endgroup::", flush=True)
        report_file = Path(module) / "build/compose-previews/guidelines.json"
        if not report_file.is_file():
            problems.append(f"`{module}` ({guidelines}): no results (exit {rc})")
            continue
        report = json.loads(report_file.read_text())
        (AUDIT / f"jev-{n}.guidelines.json").write_text(report_file.read_text())
        cost = float(report.get("costUsd") or 0)
        rcost = float(report.get("recheckCostUsd") or 0)
        cost_total += cost
        recheck_total += rcost
        problems += [f"`{module}`: {p}" for p in report.get("problems") or []]
        rows = []
        for result in report.get("results", []):
            if result.get("noRules") or result.get("pending"):
                continue
            checked_total += 1
            unchecked_total += len(result.get("unchecked") or [])
            for v in (result.get("record") or {}).get("verdicts", []):
                if v.get("verdict") != "fail":
                    continue
                findings_total += 1
                nodes = ", ".join(f"`{x}`" for x in v.get("nodeIds") or [])
                rows.append(
                    f"- **{v.get('ruleId')}** on `{result.get('previewId')}`"
                    + (f" ({nodes})" if nodes else "")
                    + f", p {float(v.get('confidence') or 0):.2f}: {(v.get('reason') or '').strip()}"
                )
        split = f" (recheck {money(rcost)})" if report.get("recheckModel") else ""
        sections += [
            f"### `{module}`: {len(ids)} preview(s), `{Path(guidelines).name}`"
            + (f", surface `{surface}`" if surface else "")
            + f" · {money(cost)}{split}",
            "",
            *(rows or ["No findings."]),
            "",
        ]

    recheck_note = (
        f"; {money(recheck_total)} of that rechecking Jev's unsure answers with `{recheck}`"
        if recheck and recheck != "none" else ""
    )
    lines += [
        f"**{findings_total} finding(s)** on {checked_total} changed preview(s), "
        f"{unchecked_total} rule verdict(s) left unchecked. "
        f"Cost: **{money(cost_total)}**{recheck_note}.",
        "",
    ]
    if skipped:
        lines += [f"{skipped} more changed preview(s) were not checked (the audit takes {max_previews} at most).", ""]
    lines += sections
    if problems:
        lines += ["<details><summary>Problems the check reported</summary>", "", "```text",
                  *problems[:40], "```", "</details>", ""]
    (AUDIT / "report.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
