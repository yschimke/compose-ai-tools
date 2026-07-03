#!/usr/bin/env python3
"""Generate preview baselines or compare renders against them.

Works with ``compose-preview show --json`` output, so it's portable to any
project that uses the ee.schimke.composeai.preview Gradle plugin + CLI.

Modes
-----
generate
    Read CLI JSON output, hash rendered PNGs, and emit ``baselines.json``
    plus a browsable ``README.md`` with inline images.

compare
    Read CLI JSON output and a previously-generated ``baselines.json``,
    then emit a Markdown PR comment body to stdout.

copy-changed
    Copy only new/changed PNGs to an output directory (for the PR renders
    branch).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import shutil
from pathlib import Path


# Preview kinds that, by design, never produce a render PNG — their render task
# emits a different data product (e.g. an `@XrSubspacePreview` emits `scene.json`
# plus per-panel textures, not a single preview-named PNG). Such a preview has an
# empty `sha256` for the same reason a crashed render does, so it must be excluded
# from "render failed" detection or every run flags it as a false failure.
NON_PNG_PREVIEW_KINDS = frozenset({"XR_SUBSPACE"})


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_baselines(path: Path) -> dict:
    """Read a baselines JSON file, treating missing / empty / malformed input as `{}`.

    The fetch step in `preview-comment/action.yml` runs
    `git show "$REF:baselines.json" > path 2>/dev/null || true`. Bash's `>`
    truncates `path` to zero bytes BEFORE the command runs — so when the
    target file doesn't exist on the base branch (the typical case for the
    first run after a new baseline file is added, e.g. `resource-baselines.json`
    landing on `compose-preview/main`), the action ends up with an
    existing-but-empty file. `json.loads("")` then raises `JSONDecodeError`,
    breaking the diff-on-PR comment for everyone — including the composable
    side, which has historically dodged this only because the composable
    `baselines.json` has been on `compose-preview/main` for so long that
    nobody re-runs the bootstrap path.

    Treat any of (missing path, empty file, unparseable JSON, non-dict
    payload) as "no baselines to compare against" — strictly more permissive
    than the previous behaviour, never less.
    """
    if not path.exists():
        return {}
    try:
        text = path.read_text()
    except OSError:
        return {}
    if not text.strip():
        return {}
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


# Pixel count above pixelmatch's per-pixel threshold that we still treat as
# perceptually unchanged. The Compose stitched-LONG renderer (issue #190) can
# emit a handful of sub-pixel-AA-different rounded-corner pixels between runs
# even when the source composable hasn't changed; pixelmatch's AA detection
# catches most but not all of those, so we leave a small slack.
_PERCEPTUAL_PIXEL_TOLERANCE = 16

# Resource renders (Robolectric-rasterized Android drawables) carry more
# run-to-run rasterizer jitter than the byte-stable Compose/Skiko preview
# renderer: an adaptive-icon's tile-chip boundaries, for instance, drift ~0.3%
# of pixels between CI runners while staying visually identical, and those
# high-contrast colour boundaries aren't classified as AA by pixelmatch, so the
# flat 16px floor above trips a false "changed". Give the resource path an
# extra size-relative slack (echoing the daemon PixelDiff ≤0.5% aggregate
# budget) on top of the floor, capped in absolute terms so a large drawable
# still can't hide a real change. Only the resource path opts in; composable
# diffs keep the strict floor.
_RESOURCE_PIXEL_FRACTION = 0.005
_RESOURCE_PIXEL_CAP = 256


def _perceptually_changed(
    prior_png: Path, current_png: Path, *, size_aware: bool = False
) -> bool:
    """Return True if the two PNGs differ by more than rounding noise.

    Uses the pixelmatch algorithm (Mapbox; widely used by Playwright,
    Storybook, jest-image-snapshot, Percy) with anti-aliasing detection on,
    so AA pixels around rounded corners and text glyphs don't count as
    real differences. This is what collapses today's
    ``ActivityListLongPreview`` flake (4 stray AA-corner pixels, ΔE ≤ 7)
    while still surfacing genuine changes (the doubled-label artifact at
    1–3 px offset shows ~1k differing pixels).

    Falls back to ``True`` (i.e. defer to the existing sha-mismatch
    behaviour) when the library isn't importable, the prior PNG can't be
    located on disk, or the images can't be decoded — strictly more
    permissive than current behaviour, never less.
    """
    try:
        from pixelmatch.contrib.PIL import pixelmatch
        from PIL import Image
    except ImportError:
        return True
    if not prior_png.exists() or not current_png.exists():
        return True
    try:
        with Image.open(prior_png) as prior, Image.open(current_png) as current:
            if prior.size != current.size:
                return True
            limit = _PERCEPTUAL_PIXEL_TOLERANCE
            if size_aware:
                total = prior.width * prior.height
                limit = max(
                    limit, min(round(total * _RESOURCE_PIXEL_FRACTION), _RESOURCE_PIXEL_CAP)
                )
            diff = pixelmatch(prior, current, threshold=0.1, includeAA=False)
    except Exception:
        return True
    return diff > limit


def _is_changed(
    cur_info: dict,
    bl_info: dict,
    baseline_renders: Path | None,
    *,
    size_aware: bool = False,
) -> bool:
    """Decide whether ``cur_info`` represents a real change vs ``bl_info``.

    Fast path: bytes match → unchanged. Otherwise, when a baseline-renders
    directory is available, defer to ``_perceptually_changed`` so
    sha-different-but-perceptually-identical pairs (renderer noise, e.g.
    issue #190) don't trip the diff bot. With no baseline renders to
    compare against, we fall back to the strict sha-only behaviour.
    """
    if cur_info["sha256"] == bl_info["sha256"]:
        return False
    if baseline_renders is None:
        return True
    basename = bl_info.get("renderBasename")
    png_path = cur_info.get("pngPath")
    if not basename or not png_path:
        return True
    prior = baseline_renders / cur_info["module"] / basename
    return _perceptually_changed(prior, Path(png_path), size_aware=size_aware)


def _collect_failures(rows: dict) -> list[tuple[str, dict]]:
    """Return CLI rows whose render produced no PNG.

    The CLI's `show` / `show-resources` envelopes carry every discovered
    preview — including those whose `composePreviewRender` task completed but
    the PNG never landed on disk (Robolectric sandbox crash, NO-SOURCE
    classpath gap, etc.). Those rows have an empty `sha256`. Surfacing them as
    "render failed in this run" lets the baseline and PR-comment flows push
    the successful subset while still warning reviewers about the partial
    state. Sorted by key so the markdown output is deterministic.

    Previews whose kind never emits a PNG (see [NON_PNG_PREVIEW_KINDS]) are
    excluded — their empty `sha256` is expected, not a render failure. So are
    `optional` captures (the CLI envelope's mirror of the manifest
    `Capture.optional` flag): best-effort artefacts like a `@ColorCatalog`
    sheet on the desktop backend, whose missing PNG is by design.
    """
    return [
        (key, info)
        for key, info in sorted(rows.items())
        if not info.get("sha256")
        and info.get("kind") not in NON_PNG_PREVIEW_KINDS
        and not info.get("optional")
    ]


def _capture_label(capture: dict) -> str:
    """Human-readable summary of a capture's non-null dimensions.

    Mirrors the TS `captureLabels.captureLabel` in the VS Code extension so
    the two surfaces agree on wording. Static captures (no dimensions)
    return ``""``; time fan-outs read ``"500ms"``; scroll captures read
    ``"scroll top"`` / ``"scroll end"`` / ``"scroll long"``; the
    cross-product reads ``"500ms \u00B7 scroll end"``.
    """
    parts: list[str] = []
    ms = capture.get("advanceTimeMillis")
    if ms is not None:
        parts.append(f"{ms}ms")
    scroll = capture.get("scroll")
    if isinstance(scroll, dict):
        mode = str(scroll.get("mode") or "").lower()
        if mode:
            parts.append(f"scroll {mode}")
    return " \u00B7 ".join(parts)


def _render_basename(png_path: str, preview_id: str) -> str:
    """File basename the diff bot should use when copying/linking a capture.

    Prefer the basename the renderer actually wrote (it encodes dimension
    suffixes like ``_SCROLL_end`` / ``_TIME_500ms`` so two captures of the
    same preview never collide). Fall back to ``<previewId>.png`` when the
    CLI didn't surface a real path — that matches the legacy behaviour for
    missing / unrendered rows.
    """
    if png_path:
        name = Path(png_path).name
        if name:
            return name
    return f"{preview_id}.png"


def _diagnose_cli_json(path: Path, raw_bytes: bytes, summary: str) -> str:
    """Build the SystemExit message for unparseable ``compose-preview show --json``
    output — adds a hex preview, a repr of the head, and the file's tail when
    long enough, so the CI log carries enough breadcrumbs to root-cause without
    re-running the workflow. See preview-comment/action.yml for the
    artifact-upload step that hands the full file to the user as well.
    """
    size = len(raw_bytes)
    head = raw_bytes[:200]
    head_hex = head[:32].hex(" ")
    head_repr = head.decode("utf-8", errors="replace")
    lines = [
        f"{path} {summary}.",
        f"File size: {size} bytes.",
        f"First {min(32, size)} bytes (hex): {head_hex}",
        f"First {min(200, size)} chars (repr): {head_repr!r}",
    ]
    if size > 400:
        tail = raw_bytes[-200:]
        lines.append(f"Last 200 chars (repr): {tail.decode('utf-8', errors='replace')!r}")
    lines.append(
        "Check the upstream `compose-preview show --json` step's log for "
        "non-JSON output bleeding into stdout. The full file is uploaded as "
        "the `preview-cli-output` artifact for postmortem inspection."
    )
    return "\n".join(lines)


def load_cli_output(cli_json_path: Path) -> dict[str, dict]:
    """Parse ``compose-preview show --json`` output into a keyed dict.

    The CLI emits a versioned envelope ``{schema, previews, counts}`` (schema
    ``compose-preview-show/v1``).  Pre-envelope CLIs (≤0.4.0) emitted a bare
    JSON array of PreviewResult objects — accepted as a fallback so this
    action keeps working against older CLI tarballs in CI matrices.

    Previews with multiple captures (``@RoboComposePreviewOptions`` time
    fan-out, ``@ScrollingPreview(modes = […])`` scroll fan-out) expand into
    one row per capture. The first capture keeps the bare ``<module>/<id>``
    key so existing baselines continue matching single-capture previews;
    subsequent captures are keyed ``<module>/<id>#<n>`` — same convention as
    the CLI's own per-capture state file.

    Rows carry the render PNG basename (``_SCROLL_end.png`` etc.) and a
    ``captureLabel`` for downstream markdown / filename handling.
    """
    raw_bytes = cli_json_path.read_bytes()
    if not raw_bytes.strip():
        # `compose-preview show --json` always emits an envelope — even the
        # no-previews case prints `{"schema": …, "previews": []}`. An empty
        # file means the upstream "Render previews" step lost its stdout
        # (e.g. unflushed buffer before `System.exit`). Surface that directly
        # instead of letting json.loads die with `Expecting value: line 1
        # column 1` (issue #292).
        raise SystemExit(
            f"{cli_json_path} is empty — the upstream `compose-preview show "
            f"--json` step produced no output. Check that step's log."
        )
    # Strip a UTF-8 BOM if the JVM emitted one. Python's json parser doesn't
    # treat U+FEFF as whitespace and would die with "Expecting value: line 1
    # col 1" on otherwise-valid output. Logged to stderr so the workaround
    # stays visible in CI rather than silently masking a real CLI bug.
    text_bytes = raw_bytes
    if text_bytes.startswith(b"\xef\xbb\xbf"):
        print(
            f"warning: stripped UTF-8 BOM from {cli_json_path} "
            f"(upstream `compose-preview show --json` emitted one)",
            file=sys.stderr,
        )
        text_bytes = text_bytes[3:]
    try:
        text = text_bytes.decode("utf-8")
    except UnicodeDecodeError as e:
        raise SystemExit(
            _diagnose_cli_json(
                cli_json_path,
                raw_bytes,
                f"is not valid UTF-8 ({e.reason} at byte {e.start})",
            )
        ) from None
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as e:
        raise SystemExit(
            _diagnose_cli_json(
                cli_json_path,
                raw_bytes,
                f"is not valid JSON ({e.msg} at line {e.lineno} col {e.colno})",
            )
        ) from None
    if isinstance(raw, dict) and "previews" in raw:
        entries = raw["previews"]
    elif isinstance(raw, list):
        entries = raw
    else:
        raise SystemExit(
            f"Unexpected CLI JSON shape in {cli_json_path}: "
            f"expected {{schema, previews, ...}} or a list, got {type(raw).__name__}"
        )

    result: dict[str, dict] = {}
    for entry in entries:
        module = entry["module"]
        preview_id = entry["id"]
        fn = entry["functionName"]
        source = entry.get("sourceFile", "")
        # Preview kind ("COMPOSE", "TILE", "XR_SUBSPACE", …) — drives whether a
        # missing PNG counts as a render failure (see NON_PNG_PREVIEW_KINDS).
        kind = (entry.get("params") or {}).get("kind") or "COMPOSE"

        # Legacy / unrendered shape: no per-capture list, fall back to the
        # top-level sha/png. Produces one row as before.
        captures = entry.get("captures") or []
        if not captures:
            result[f"{module}/{preview_id}"] = {
                "sha256": entry.get("sha256") or "",
                "functionName": fn,
                "sourceFile": source,
                "module": module,
                "previewId": preview_id,
                "kind": kind,
                "pngPath": entry.get("pngPath") or "",
                "captureIndex": 0,
                "captureLabel": "",
                "renderBasename": _render_basename(entry.get("pngPath") or "", preview_id),
            }
            continue

        for idx, capture in enumerate(captures):
            # Index 0 keeps the bare key so pre-fan-out baselines on `main`
            # keep matching single-capture previews. Additional captures
            # (#1, #2, …) appear as "new" entries on the first run after
            # a preview grows a fan-out, which is correct — those PNGs
            # didn't exist in the baseline.
            key = f"{module}/{preview_id}" if idx == 0 else f"{module}/{preview_id}#{idx}"
            png = capture.get("pngPath") or ""
            result[key] = {
                "sha256": capture.get("sha256") or "",
                "functionName": fn,
                "sourceFile": source,
                "module": module,
                "previewId": preview_id,
                "kind": kind,
                "pngPath": png,
                "captureIndex": idx,
                "captureLabel": _capture_label(capture),
                "renderBasename": _render_basename(png, preview_id),
                # Best-effort capture — a missing PNG is expected, not a
                # render failure (see _collect_failures).
                "optional": bool(capture.get("optional")),
            }
    return result


# ---------------------------------------------------------------------------
# generate mode
# ---------------------------------------------------------------------------

def cmd_generate(args: argparse.Namespace) -> int:
    cli_json = Path(args.cli_json)
    out_dir = Path(args.output_dir)
    repo = args.repo
    branch = args.branch
    # README heading / blurb. Defaulted so the in-repo `compose-preview/main`
    # baseline (and its tests) keep the historical text; the integration
    # matrix overrides them to name the consumer repo on its own browsable
    # `compose-preview/integration/<slug>` branch.
    title = getattr(args, "title", None) or "Preview Baselines"
    intro = (
        getattr(args, "intro", None)
        or "Auto-generated from `main`. Browse inline or compare against PR branches."
    )
    # Optional markdown blob spliced in right under the intro — used by the
    # integration matrix to record the per-project workarounds / known
    # issues (CI patches applied, credentials stubbed out, non-blocking
    # status) next to that project's rendered gallery. Empty / missing =
    # omitted entirely (the in-repo `compose-preview/main` baseline doesn't
    # pass one).
    notes = ""
    notes_path = getattr(args, "notes_file", None)
    if notes_path:
        p = Path(notes_path)
        if p.exists():
            notes = p.read_text().strip()
    prior_baselines_path = (
        Path(args.prior_baselines) if getattr(args, "prior_baselines", None) else None
    )
    prior_renders = (
        Path(args.prior_renders) if getattr(args, "prior_renders", None) else None
    )
    ab_config = ABTestConfig.load(
        Path(args.ab_config) if getattr(args, "ab_config", None) else None
    )

    previews = load_cli_output(cli_json)
    if not previews:
        print("No previews in CLI output.", file=sys.stderr)
        return 1

    # When the CLI emitted a row with no `sha256`, the render task ran but no
    # PNG landed on disk — treat the prior baseline branch as the source of
    # truth for that preview so a flaky single-preview failure doesn't wipe
    # the entry out of `compose-preview/main`. Empty / missing prior is fine;
    # the row drops out of the baseline (same as today) and is listed as a
    # "no baseline retained" failure in the README.
    prior_baselines = (
        _load_baselines(prior_baselines_path) if prior_baselines_path else {}
    )
    failures = _collect_failures(previews)
    carried_over: dict[str, dict] = {}
    for key, _info in failures:
        prior = prior_baselines.get(key)
        if isinstance(prior, dict) and prior.get("sha256") and prior.get("renderBasename"):
            carried_over[key] = prior

    # --- baselines.json ---
    # Persist the renderBasename alongside the sha so the compare run can
    # reconstruct raw-GitHub URLs for removed captures without needing the
    # CLI output for them.
    baselines = {
        key: {
            "sha256": info["sha256"],
            "functionName": info["functionName"],
            "sourceFile": info["sourceFile"],
            "renderBasename": info["renderBasename"],
            "captureLabel": info["captureLabel"],
            # Also encoded in the key (`<module>/<id>`); persisted explicitly
            # so scoped compares don't have to parse it back out. Entries
            # carried over from pre-field baselines may lack it — readers
            # must fall back to the key (see _baseline_module).
            "module": info["module"],
        }
        for key, info in previews.items()
        if info["sha256"]  # skip entries without a rendered PNG
    }
    # Carry forward the prior entry as-is for previews that failed in this
    # run. Same shape as `_load_baselines` returns, so downstream
    # compare-on-PR keeps matching them.
    for key, prior in carried_over.items():
        baselines.setdefault(key, prior)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "baselines.json").write_text(
        json.dumps(baselines, indent=2, sort_keys=True) + "\n")

    # --- copy PNGs into renders/<module>/<renderBasename> ---
    # Using the renderer's on-disk basename (e.g. `Foo_SCROLL_end.png`)
    # rather than `<previewId>.png` so captures in a multi-mode /
    # time-fan-out preview don't collide on the baseline branch.
    renders_out = out_dir / "renders"
    if renders_out.exists():
        shutil.rmtree(renders_out)
    for info in previews.values():
        if not info["pngPath"]:
            continue
        png = Path(info["pngPath"])
        if not png.exists():
            continue
        dest = renders_out / info["module"] / info["renderBasename"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(png, dest)
    # Carry forward the prior PNG for previews whose render failed but had a
    # prior baseline — keeps the README inline image resolving even though
    # this run didn't produce a fresh capture.
    if prior_renders is not None:
        for key, prior in carried_over.items():
            module = previews[key]["module"]
            basename = prior["renderBasename"]
            src = prior_renders / module / basename
            if not src.exists():
                continue
            dest = renders_out / module / basename
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)

    # --- README.md (browsable gallery) ---
    lines = [
        f"# {title}",
        "",
        intro,
        "",
    ]
    if notes:
        lines.append(notes)
        lines.append("")
    if failures:
        retained = sum(1 for key, _ in failures if key in carried_over)
        dropped = len(failures) - retained
        warning_bits = []
        if retained:
            warning_bits.append(f"{retained} retained from the prior baseline")
        if dropped:
            warning_bits.append(f"{dropped} with no prior baseline to retain")
        warning = (
            f"> [!WARNING]"
            f"\n> {len(failures)} preview(s) failed to render in the latest update"
            f" ({'; '.join(warning_bits)}). See **Render Failures** below."
        )
        lines.append(warning)
        lines.append("")
        lines.append("## Render Failures")
        lines.append("")
        lines.append(
            "The render task completed but no PNG was produced for these previews. "
            "Entries with a prior baseline keep their previous image; the rest are "
            "absent from the gallery until a successful render lands."
        )
        lines.append("")
        lines.append("| Preview | Module | Function | Source | Baseline |")
        lines.append("|---------|--------|----------|--------|----------|")
        for key, info in failures:
            label_suffix = f" · {info['captureLabel']}" if info.get("captureLabel") else ""
            fn = f"{info.get('functionName', '?')}{label_suffix}"
            source = info.get("sourceFile") or "—"
            state = "retained" if key in carried_over else "none"
            lines.append(
                f"| `{key}` | {info.get('module', '?')} | `{fn}` | `{source}` | {state} |"
            )
        lines.append("")

    by_module: dict[str, list[tuple[str, dict]]] = {}
    for key, info in sorted(previews.items()):
        if not info["sha256"]:
            continue
        by_module.setdefault(info["module"], []).append((key, info))

    for module, entries in sorted(by_module.items()):
        lines.append(f"## {module}")
        lines.append("")

        # A/B groups render side-by-side at the top of the module section;
        # their member rows are then skipped from the standard per-preview
        # table below so they aren't listed twice.
        ab_groups, promoted = (
            _ab_groups_in_gallery(ab_config, module, entries)
            if ab_config
            else ([], set())
        )
        for g in ab_groups:
            if g["label"]:
                lines.append(f"### A/B: {g['label']} (`{g['fn']}`)")
            else:
                lines.append(f"### A/B: `{g['fn']}`")
            lines.append("")
            tokens = [token for token, _k, _i in g["rows"]]
            lines.append("| " + " | ".join(tokens) + " |")
            lines.append("|" + "---|" * len(tokens))
            cells = []
            for _token, _key, info in g["rows"]:
                img_path = f"renders/{info['module']}/{info['renderBasename']}"
                raw_url = f"https://raw.githubusercontent.com/{repo}/{branch}/{img_path}"
                cells.append(f'<img src="{raw_url}" width="200" />')
            lines.append("| " + " | ".join(cells) + " |")
            lines.append("")

        standard = [(key, info) for key, info in entries if key not in promoted]
        if standard:
            lines.append("| Preview | Image |")
            lines.append("|---------|-------|")
            for _key, info in standard:
                label_suffix = f" · {info['captureLabel']}" if info["captureLabel"] else ""
                fn = f"{info['functionName']}{label_suffix}"
                img_path = f"renders/{info['module']}/{info['renderBasename']}"
                raw_url = f"https://raw.githubusercontent.com/{repo}/{branch}/{img_path}"
                lines.append(
                    f"| `{fn}` | <img src=\"{raw_url}\" width=\"150\" /> |"
                )
            lines.append("")

    (out_dir / "README.md").write_text("\n".join(lines) + "\n")

    print(
        f"Generated baselines for {len(baselines)} preview(s) "
        f"({len(failures)} failure(s)) in {out_dir}",
        file=sys.stderr,
    )
    return 0


# ---------------------------------------------------------------------------
# compare mode
# ---------------------------------------------------------------------------

def _variant_label(preview_id: str) -> str:
    """Extract the variant label from a preview ID (suffix after the last ``_``)."""
    # e.g. "com.example.PreviewsKt.ConfigProbePreview_German" -> "German"
    parts = preview_id.rsplit("_", 1)
    return parts[1] if len(parts) == 2 else ""


def _entry_label(info: dict) -> str:
    """Display label for one row inside a function group.

    Combines the variant suffix from the preview id (Light/Dark, device
    name, etc.) with the capture label (``scroll end``, ``500ms``, …).
    Either half may be empty; the label is used in link text / table
    headings, so empty strings are filtered out.
    """
    variant = _variant_label(info["previewId"])
    capture = info.get("captureLabel") or ""
    parts = [p for p in (variant, capture) if p]
    return " · ".join(parts) or info["previewId"]


def _render_url(repo: str, ref: str, module: str, basename: str) -> str:
    # ``ref`` is either a commit SHA (preferred: durable) or a branch name
    # (first-run fallback when no baseline/PR commit exists yet).
    return (
        f"https://raw.githubusercontent.com/{repo}/{ref}"
        f"/renders/{module}/{basename}"
    )


# ---------------------------------------------------------------------------
# A/B comparison config
#
# Lets a project nominate specific preview *variants* of a single function for
# a side-by-side horizontal comparison instead of the default
# hero-plus-"Other variants"-links treatment. The variants can come from two
# `@Preview` annotations (including ones contributed by a multi-preview
# meta-annotation) or from distinct values of a `@PreviewParameter` provider —
# in every case the discovery layer encodes the distinguishing token as the
# preview id's suffix, which is what we match on here.
# ---------------------------------------------------------------------------

def _row_matches_variant(info: dict, token: str) -> bool:
    """Whether a loaded CLI row belongs to the A/B variant named ``token``.

    The token is matched against the preview id's variant suffix (the part
    after the last underscore — e.g. ``Control`` in
    ``…ButtonPreviewKt.ButtonPreview_Control``), with two looser fallbacks so
    multi-segment suffixes (``…_PARAM_0``) and exact-id configs still resolve:
    a trailing ``_<token>`` match and a whole-id match.
    """
    pid = info.get("previewId", "")
    if _variant_label(pid) == token:
        return True
    if pid == token:
        return True
    return pid.endswith(f"_{token}")


class ABTestConfig:
    """A/B comparison groups loaded from a project's JSON config file.

    Default location ``.github/preview-abtest.json`` (overridable via the
    action's ``ab-config`` input). Schema::

        {
          "groups": [
            {
              "function": "ButtonPreview",
              "module": "app",                       # optional; any module if omitted
              "variants": ["Control", "Treatment"],  # >= 2 variant tokens
              "label": "Button copy"                 # optional heading
            }
          ]
        }

    A discovered preview row matches a group when its ``functionName`` equals
    ``function`` (and ``module`` matches, when the group pins one) and its
    variant suffix is one of ``variants``. Matched variants get promoted to a
    side-by-side horizontal layout in the PR comment and the baseline gallery
    instead of being stacked / collapsed into "Other variants" links.

    Missing / empty / malformed config is treated as "no A/B groups" — the
    feature is strictly additive, so a broken file degrades to the historical
    behaviour rather than failing the run.
    """

    def __init__(self, groups: list[dict]):
        self._groups = groups

    @classmethod
    def load(cls, path: Path | None) -> "ABTestConfig":
        if path is None or not path.exists():
            return cls([])
        try:
            text = path.read_text()
        except OSError:
            return cls([])
        if not text.strip():
            return cls([])
        try:
            raw = json.loads(text)
        except json.JSONDecodeError:
            return cls([])
        if not isinstance(raw, dict):
            return cls([])
        groups: list[dict] = []
        for g in raw.get("groups", []) or []:
            if not isinstance(g, dict):
                continue
            fn = g.get("function")
            variants = g.get("variants")
            # A group needs a target function and at least two variants to
            # compare; anything else is silently dropped.
            if not fn or not isinstance(variants, list) or len(variants) < 2:
                continue
            groups.append({
                "function": fn,
                "module": g.get("module"),
                "variants": [str(v) for v in variants],
                "label": g.get("label"),
            })
        return cls(groups)

    def __bool__(self) -> bool:
        return bool(self._groups)

    def match(self, module: str, function_name: str) -> dict | None:
        """First group matching ``function_name`` (and ``module`` if pinned)."""
        for g in self._groups:
            if g["function"] != function_name:
                continue
            if g["module"] is not None and g["module"] != module:
                continue
            return g
        return None


def _ab_resolve_rows(
    group: dict, module: str, fn: str, rows: dict
) -> list[tuple[str, str, dict]]:
    """Resolve a group's variant tokens to concrete rendered rows, in config order.

    ``rows`` is a ``key -> info`` map (the loaded CLI output or a baseline set).
    Only rows in ``module`` / ``fn`` that produced a PNG are considered. Returns
    ``[(token, key, info), …]`` for the tokens that resolved — order follows
    the config so the side-by-side columns read A, B, C as authored.
    """
    candidates = [
        (key, info)
        for key, info in sorted(rows.items())
        if info.get("module") == module
        and info.get("functionName") == fn
        and info.get("sha256")
    ]
    resolved: list[tuple[str, str, dict]] = []
    for token in group["variants"]:
        for key, info in candidates:
            if _row_matches_variant(info, token):
                resolved.append((token, key, info))
                break
    return resolved


def _ab_groups_in_gallery(
    ab_config: "ABTestConfig", module: str, entries: list[tuple[str, dict]]
) -> tuple[list[dict], set[str]]:
    """Find A/B groups among a module's gallery entries.

    ``entries`` is the ``[(key, info), …]`` list the generate gallery builds
    per module. Returns ``(groups, promoted_keys)`` where each group is
    ``{label, fn, rows:[(token, key, info), …]}`` and ``promoted_keys`` are the
    row keys that should be skipped from the standard per-preview table.
    """
    rows_map = {key: info for key, info in entries}
    groups: list[dict] = []
    promoted: set[str] = set()
    seen: set[str] = set()
    for _key, info in entries:
        fn = info["functionName"]
        if fn in seen:
            continue
        group = ab_config.match(module, fn)
        if group is None:
            continue
        resolved = _ab_resolve_rows(group, module, fn, rows_map)
        if len(resolved) < 2:
            continue
        seen.add(fn)
        for _token, rkey, _rinfo in resolved:
            promoted.add(rkey)
        groups.append({"label": group.get("label"), "fn": fn, "rows": resolved})
    return groups, promoted


def _emit_ab_comparisons(
    lines: list[str],
    ab_groups: list[dict],
    repo: str,
    base_ref: str,
    head_ref: str,
) -> None:
    """Append the side-by-side A/B section to ``lines`` (in place).

    One table per group: variant tokens are the columns, and the rows are the
    baseline (``Before``) and PR (``After``) renders so the variants sit next
    to each other horizontally for direct comparison. The ``Before`` row is
    omitted entirely when no variant has a baseline (a brand-new A/B group).
    """
    lines.append(f"### A/B Comparisons ({len(ab_groups)} group(s))")
    lines.append("")
    for g in ab_groups:
        rows = g["rows"]
        if g["label"]:
            lines.append(f"**{g['label']}** — `{g['fn']}` ({g['module']})")
        else:
            lines.append(f"**`{g['fn']}`** ({g['module']})")
        lines.append("")
        tokens = [token for token, _k, _i, _c, _b in rows]
        lines.append("| | " + " | ".join(tokens) + " |")
        lines.append("|" + "---|" * (len(tokens) + 1))
        if any(bl is not None for _t, _k, _i, _c, bl in rows):
            before_cells = []
            for token, _key, info, _changed, bl in rows:
                if bl is None:
                    before_cells.append("_new_")
                    continue
                basename = bl.get("renderBasename") or info["renderBasename"]
                url = _render_url(repo, base_ref, g["module"], basename)
                before_cells.append(f'<img src="{url}" width="200" />')
            lines.append("| Before | " + " | ".join(before_cells) + " |")
        after_cells = []
        for _token, _key, info, changed, bl in rows:
            if changed or bl is None:
                # Changed / new variants are staged to the head renders branch
                # by `copy-changed`, so the PR ref has their PNG.
                url = _render_url(repo, head_ref, g["module"], info["renderBasename"])
            else:
                # Unchanged companion: `copy-changed` only stages new/changed
                # PNGs, so this render was never pushed to the head ref. Its
                # "After" pixels are byte-identical to the baseline, so point at
                # the baseline ref to avoid a broken-image cell.
                basename = bl.get("renderBasename") or info["renderBasename"]
                url = _render_url(repo, base_ref, g["module"], basename)
            after_cells.append(f'<img src="{url}" width="200" />')
        lines.append("| After | " + " | ".join(after_cells) + " |")
        lines.append("")
        diffed = [
            token for token, _k, _i, changed, bl in rows if changed or bl is None
        ]
        if diffed:
            lines.append("Changed: " + ", ".join(f"`{t}`" for t in diffed) + ".")
            lines.append("")


def _parse_scope_modules(args: argparse.Namespace) -> set[str] | None:
    """Parse `--scope-modules` into a set of Gradle module paths, or None.

    None means "full run" — every baseline entry participates in removed
    detection. A set means the render was scoped to those modules only
    (change-scoped PR run), so baseline entries for other modules must be
    treated as unchanged rather than removed: their previews were simply
    never rendered this run. Module paths are normalised without the
    leading `:` to match the `module` field the CLI envelope carries.
    """
    raw = getattr(args, "scope_modules", None)
    if not raw:
        return None
    modules = {m.strip().lstrip(":") for m in raw.split(",") if m.strip()}
    return modules or None


def _baseline_module(key: str, bl_info: dict) -> str:
    """Module of a baseline entry.

    Baselines written before `module` was persisted (and the composable
    baselines generally — see cmd_generate) only encode the module in the
    key, `<module>/<previewId>[#idx]`. Module gradle paths use `:` and never
    contain `/`, so the first segment is always the module.
    """
    mod = bl_info.get("module")
    if mod:
        return mod
    return key.split("/", 1)[0]


def _scope_note(scope_modules: set[str]) -> str:
    mods = ", ".join(f"`{m}`" for m in sorted(scope_modules))
    return (
        f"_Change-scoped run: only {len(scope_modules)} module(s) rendered "
        f"({mods}); other modules were unaffected by this PR's changes and "
        f"kept their baselines._"
    )


def cmd_compare(args: argparse.Namespace) -> int:
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    repo = args.repo
    base_ref = args.base_ref
    head_ref = args.head_ref
    baseline_renders = (
        Path(args.baseline_renders) if getattr(args, "baseline_renders", None) else None
    )
    scope_modules = _parse_scope_modules(args)

    current = load_cli_output(cli_json)
    baselines = _load_baselines(baselines_path)
    ab_config = ABTestConfig.load(
        Path(args.ab_config) if getattr(args, "ab_config", None) else None
    )

    # --- A/B comparison groups ---
    # Resolve these up front so their rows can be excluded from the standard
    # new/changed/unchanged buckets (otherwise an A/B variant would be listed
    # twice). A group is only *promoted* to the side-by-side section when at
    # least one of its variants actually changed or is new — an all-unchanged
    # group stays out of the comment so we don't post on no-op PRs (the
    # empty-diff sentinel below still fires).
    ab_groups: list[dict] = []
    ab_keys: set[str] = set()
    if ab_config:
        seen_fns: set[tuple[str, str]] = set()
        for key, info in sorted(current.items()):
            if not info["sha256"]:
                continue
            gk = (info["module"], info["functionName"])
            if gk in seen_fns:
                continue
            group = ab_config.match(info["module"], info["functionName"])
            if group is None:
                continue
            rows = _ab_resolve_rows(group, gk[0], gk[1], current)
            if len(rows) < 2:
                # Need at least two variants present in this run to compare.
                continue
            seen_fns.add(gk)
            resolved = []
            has_change = False
            for token, rkey, rinfo in rows:
                bl = baselines.get(rkey)
                changed = bl is not None and _is_changed(rinfo, bl, baseline_renders)
                if changed or bl is None:
                    has_change = True
                resolved.append((token, rkey, rinfo, changed, bl))
            if not has_change:
                continue
            for token, rkey, rinfo, changed, bl in resolved:
                ab_keys.add(rkey)
            ab_groups.append({
                "label": group.get("label"),
                "module": gk[0],
                "fn": gk[1],
                "rows": resolved,
            })

    new: list[tuple[str, dict]] = []
    changed: list[tuple[str, dict, dict]] = []
    removed: list[tuple[str, dict]] = []
    unchanged: list[tuple[str, dict]] = []

    for key, info in sorted(current.items()):
        if not info["sha256"]:
            continue
        if key in ab_keys:
            continue
        if key not in baselines:
            new.append((key, info))
        elif _is_changed(info, baselines[key], baseline_renders):
            changed.append((key, info, baselines[key]))
        else:
            unchanged.append((key, info))

    for key, bl_info in sorted(baselines.items()):
        if key in ab_keys:
            continue
        # Change-scoped runs render a subset of modules; a baseline entry
        # for an out-of-scope module is absent from `current` because it was
        # never rendered, not because the preview went away — never report
        # it as removed.
        if scope_modules is not None and _baseline_module(key, bl_info) not in scope_modules:
            continue
        if key not in current:
            removed.append((key, bl_info))

    failures = _collect_failures(current)

    # --- generate markdown ---
    marker = "<!-- preview-diff -->"
    lines = [marker, "## Preview Changes", ""]

    if not new and not changed and not removed and not failures and not ab_groups:
        lines.append("No visual changes detected.")
        lines.append("")
        if unchanged:
            lines.append(f"_{len(unchanged)} preview(s) unchanged._")
        if scope_modules is not None:
            lines.append("")
            lines.append(_scope_note(scope_modules))
        print("\n".join(lines))
        return 0

    if scope_modules is not None:
        lines.append(_scope_note(scope_modules))
        lines.append("")

    if ab_groups:
        _emit_ab_comparisons(lines, ab_groups, repo, base_ref, head_ref)

    if failures:
        lines.append(
            f"> [!WARNING]"
            f"\n> {len(failures)} preview(s) failed to render in this PR's render run. "
            f"The diff below covers only the previews that produced a PNG."
        )
        lines.append("")

    if changed:
        # Group changed variants by (module, functionName) — a function
        # fans out into (preview variants × captures), so one group can
        # contain many rows even for a single source function.
        groups: dict[tuple[str, str], list[tuple[str, dict, dict]]] = {}
        for key, cur, bl in changed:
            gk = (cur["module"], cur["functionName"])
            groups.setdefault(gk, []).append((key, cur, bl))

        lines.append(f"### Changed ({len(changed)} variant(s) across {len(groups)} function(s))")
        lines.append("")

        for (module, fn), entries in sorted(groups.items()):
            hero_key, hero_cur, hero_bl = entries[0]
            before = _render_url(repo, base_ref, module, hero_cur["renderBasename"])
            after = _render_url(repo, head_ref, module, hero_cur["renderBasename"])

            lines.append(f"**`{fn}`** ({module})")
            lines.append("")
            lines.append("| Before | After |")
            lines.append("|--------|-------|")
            lines.append(
                f"| <img src=\"{before}\" width=\"200\" /> "
                f"| <img src=\"{after}\" width=\"200\" /> |"
            )

            # Link remaining variants
            if len(entries) > 1:
                variant_links = []
                for _okey, ocur, _obl in entries[1:]:
                    label = _entry_label(ocur)
                    link = _render_url(repo, head_ref, module, ocur["renderBasename"])
                    variant_links.append(f"[{label}]({link})")
                lines.append("")
                lines.append(f"Other variants: {', '.join(variant_links)}")
            lines.append("")

    if new:
        # Group new previews similarly.
        groups_new: dict[tuple[str, str], list[tuple[str, dict]]] = {}
        for key, info in new:
            gk = (info["module"], info["functionName"])
            groups_new.setdefault(gk, []).append((key, info))

        lines.append(f"### New ({len(new)} variant(s) across {len(groups_new)} function(s))")
        lines.append("")

        for (module, fn), entries in sorted(groups_new.items()):
            hero_key, hero_info = entries[0]
            after = _render_url(repo, head_ref, module, hero_info["renderBasename"])

            lines.append(
                f"**`{fn}`** ({module}) "
                f"<img src=\"{after}\" width=\"200\" />"
            )

            if len(entries) > 1:
                variant_links = []
                for _okey, oinfo in entries[1:]:
                    label = _entry_label(oinfo)
                    link = _render_url(repo, head_ref, module, oinfo["renderBasename"])
                    variant_links.append(f"[{label}]({link})")
                lines.append(f"Variants: {', '.join(variant_links)}")
            lines.append("")

    if removed:
        fn_set = {bl_info.get("functionName", "?") for _, bl_info in removed}
        lines.append(f"### Removed ({len(removed)} variant(s))")
        lines.append("")
        for fn in sorted(fn_set):
            lines.append(f"- ~`{fn}`~")
        lines.append("")

    if failures:
        # Group by (module, functionName) so multi-capture failures land
        # under one heading — mirrors how Changed/New collapse fan-outs.
        fail_groups: dict[tuple[str, str], list[tuple[str, dict]]] = {}
        for key, info in failures:
            gk = (info.get("module", "?"), info.get("functionName", "?"))
            fail_groups.setdefault(gk, []).append((key, info))
        lines.append(
            f"### Render Failures ({len(failures)} variant(s) across {len(fail_groups)} function(s))"
        )
        lines.append("")
        lines.append(
            "The render task completed but produced no PNG for these previews. "
            "Common causes: Robolectric sandbox crash, `composePreviewRender` "
            "NO-SOURCE, or a runtime exception inside the composable. Check the "
            "`composePreviewRender-reports` artifact attached to this run."
        )
        lines.append("")
        for (module, fn), entries in sorted(fail_groups.items()):
            sources = sorted({info.get("sourceFile") or "" for _, info in entries})
            sources = [s for s in sources if s]
            source_hint = f" — `{sources[0]}`" if sources else ""
            lines.append(f"- **`{fn}`** ({module}){source_hint}")
            for key, info in entries:
                label = info.get("captureLabel") or _entry_label(info)
                lines.append(f"  - `{key}` · {label}")
        lines.append("")

    if unchanged:
        fn_set = {info["functionName"] for _, info in unchanged}
        lines.append(f"<details><summary>Unchanged ({len(fn_set)} function(s), {len(unchanged)} variant(s))</summary>")
        lines.append("")
        for fn in sorted(fn_set):
            lines.append(f"- `{fn}`")
        lines.append("")
        lines.append("</details>")

    print("\n".join(lines))
    return 0


# ---------------------------------------------------------------------------
# copy-changed mode
# ---------------------------------------------------------------------------

def cmd_copy_changed(args: argparse.Namespace) -> int:
    """Copy new/changed PNGs to an output directory for the PR renders branch."""
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    out_dir = Path(args.output_dir)
    baseline_renders = (
        Path(args.baseline_renders) if getattr(args, "baseline_renders", None) else None
    )

    current = load_cli_output(cli_json)
    baselines = _load_baselines(baselines_path)

    copied = 0
    for key, info in current.items():
        if not info["sha256"]:
            continue
        if not info["pngPath"]:
            continue
        png = Path(info["pngPath"])
        if not png.exists():
            continue
        is_new = key not in baselines
        is_changed = not is_new and _is_changed(info, baselines[key], baseline_renders)
        if is_new or is_changed:
            # Use the renderer's on-disk basename so multi-capture previews
            # don't collide — matches the generate path.
            dest = out_dir / "renders" / info["module"] / info["renderBasename"]
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(png, dest)
            copied += 1

    print(f"Copied {copied} changed/new preview(s) to {out_dir}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# generate-resources mode
#
# Sibling of `generate`. Reads `compose-preview show-resources --json` output
# and copies each rendered PNG / GIF into
# `<output_dir>/renders/<module>/resources/<...>` so they land in
# `compose-preview/resources/main` alongside the existing composable baselines.
# Writes `resource-baselines.json` and appends a section to the README.
#
# Mirrors the composable side's `cmd_generate` shape — a single CLI envelope
# is the source of truth, no second filesystem walk. Modules without
# `resources.json` are absent from the envelope (the CLI filters them out
# via `isAndroidModule`), so workspaces that never opted into the resource
# pipeline produce an empty `resources` list and short-circuit cleanly.
# ---------------------------------------------------------------------------

def _resource_render_dest(render_output: str) -> str:
    """`renders/resources/drawable/foo.png` → `resources/drawable/foo.png`.

    The leading `renders/` segment is stripped so the destination tree is
    `<output>/renders/<module>/resources/...`, consistent with how the
    composable path lays out `<output>/renders/<module>/<basename>`.
    """
    if render_output.startswith("renders/"):
        return render_output[len("renders/"):]
    return render_output


def load_resource_results(cli_json_path: Path) -> dict[str, dict]:
    """Parse `compose-preview show-resources --json` output into a flat key→entry map.

    Key shape: `<module>::<resourceId>::<renderOutput>` — same convention the
    earlier filesystem-walk variant used, so existing
    `resource-baselines.json` files on `compose-preview/resources/main` keep
    matching. Module identifiers in the envelope are gradle paths
    (`samples:android`); we translate `:` → `/` so the rendered tree under
    `renders/<module>/...` keeps its filesystem-friendly layout.

    Treats missing / empty / malformed input as no entries (same shape as
    `_load_baselines`). The `compose-preview show-resources` step always
    writes the envelope, so an empty file means the CLI failed before
    serialising — downstream steps then short-circuit gracefully rather than
    crash.
    """
    if not cli_json_path.exists():
        return {}
    try:
        text = cli_json_path.read_text()
    except OSError:
        return {}
    if not text.strip():
        return {}
    try:
        raw = json.loads(text)
    except json.JSONDecodeError:
        return {}
    if not isinstance(raw, dict):
        return {}

    out: dict[str, dict] = {}
    for resource in raw.get("resources", []) or []:
        gradle_path = resource.get("module") or ""
        # `:samples:android` → `samples:android` (CLI strips leading `:` already,
        # belt-and-braces) → `samples/android`, matching how composables layer
        # their renders under `renders/<module>/...`.
        module = gradle_path.lstrip(":").replace(":", "/")
        resource_id = resource.get("id")
        resource_type = resource.get("type", "")
        if not resource_id:
            continue
        for capture in resource.get("captures", []) or []:
            render_output = capture.get("renderOutput") or ""
            if not render_output:
                continue
            png_path = capture.get("pngPath")
            sha = capture.get("sha256")
            variant = capture.get("variant") or {}
            key = f"{module}::{resource_id}::{render_output}"
            out[key] = {
                "module": module,
                "resourceId": resource_id,
                "resourceType": resource_type,
                "renderOutput": render_output,
                "destRelative": _resource_render_dest(render_output),
                "pngPath": Path(png_path) if png_path else None,
                "sha256": sha,
                "qualifiers": variant.get("qualifiers"),
                "shape": variant.get("shape"),
            }
    return out


def cmd_generate_resources(args: argparse.Namespace) -> int:
    cli_json = Path(args.cli_json)
    out_dir = Path(args.output_dir)
    prior_baselines_path = (
        Path(args.prior_baselines) if getattr(args, "prior_baselines", None) else None
    )
    prior_renders = (
        Path(args.prior_renders) if getattr(args, "prior_renders", None) else None
    )

    entries = load_resource_results(cli_json)
    if not entries:
        # Not an error — modules without resources.json are common.
        print("No Android resource manifests found; skipping resource baselines.",
              file=sys.stderr)
        return 0

    # Carry-over flow mirrors `cmd_generate` for composables: when a resource
    # render flakes (empty sha), keep the prior baseline entry + PNG so the
    # branch keeps a usable gallery row instead of dropping the resource
    # entirely.
    prior_baselines = (
        _load_baselines(prior_baselines_path) if prior_baselines_path else {}
    )
    failures = _collect_failures(entries)
    carried_over: dict[str, dict] = {}
    for key, _info in failures:
        prior = prior_baselines.get(key)
        if isinstance(prior, dict) and prior.get("sha256") and prior.get("renderBasename"):
            carried_over[key] = prior

    out_dir.mkdir(parents=True, exist_ok=True)
    resource_baselines: dict[str, dict] = {}
    by_module: dict[str, list[tuple[str, dict]]] = {}
    for key, info in entries.items():
        if not info["sha256"]:
            continue
        dest = out_dir / "renders" / info["module"] / info["destRelative"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(info["pngPath"], dest)
        resource_baselines[key] = {
            "sha256": info["sha256"],
            "module": info["module"],
            "resourceId": info["resourceId"],
            "resourceType": info["resourceType"],
            "renderBasename": info["destRelative"],
            "qualifiers": info["qualifiers"],
            "shape": info["shape"],
        }
        by_module.setdefault(info["module"], []).append((key, info))

    # Carry forward the prior entry + PNG for resources that failed to render
    # in this run. Same shape as `_load_baselines` returns for resources.
    for key, prior in carried_over.items():
        resource_baselines.setdefault(key, prior)
        if prior_renders is not None:
            module = entries[key]["module"]
            basename = prior["renderBasename"]
            src = prior_renders / module / basename
            if src.exists():
                dest = out_dir / "renders" / module / basename
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dest)

    if not resource_baselines and not failures:
        print("Resource manifests existed but no rendered PNGs were on disk; "
              "skipping resource baselines.",
              file=sys.stderr)
        return 0

    (out_dir / "resource-baselines.json").write_text(
        json.dumps(resource_baselines, indent=2, sort_keys=True) + "\n")

    # Append the resource gallery to the README. The composable `cmd_generate`
    # has already written the file; we add a sibling section. When run on its
    # own (no prior README), seed the file so the section header has a parent.
    readme = out_dir / "README.md"
    existing = readme.read_text() if readme.exists() else "# Preview Baselines\n"
    body_lines: list[str] = []
    if not existing.rstrip().endswith(""):
        body_lines.append("")
    body_lines += [
        "",
        "## Android XML Resource Previews",
        "",
        "Rendered from `:<module>:composePreviewRenderAndroidResources`. One row per "
        "(resource × qualifier × shape) capture. See "
        "[`references/resource-previews.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/resource-previews.md) "
        "for the rendering catalogue.",
        "",
    ]
    if failures:
        retained = sum(1 for key, _ in failures if key in carried_over)
        dropped = len(failures) - retained
        warning_bits = []
        if retained:
            warning_bits.append(f"{retained} retained from the prior baseline")
        if dropped:
            warning_bits.append(f"{dropped} with no prior baseline to retain")
        body_lines.append(
            f"> [!WARNING]"
            f"\n> {len(failures)} resource capture(s) failed to render in the latest update"
            f" ({'; '.join(warning_bits)}). See **Resource Render Failures** below."
        )
        body_lines.append("")
        body_lines.append("### Resource Render Failures")
        body_lines.append("")
        body_lines.append(
            "The render task completed but no PNG was produced for these resource "
            "captures. Entries with a prior baseline keep their previous image; the "
            "rest are absent from the gallery until a successful render lands."
        )
        body_lines.append("")
        body_lines.append("| Resource | Module | Type | Qualifiers | Shape | Baseline |")
        body_lines.append("|---|---|---|---|---|---|")
        for key, info in failures:
            qualifiers = info.get("qualifiers") or "—"
            shape = info.get("shape") or "—"
            state = "retained" if key in carried_over else "none"
            body_lines.append(
                f"| `{info.get('resourceId', '?')}` | {info.get('module', '?')} | "
                f"{info.get('resourceType', '')} | `{qualifiers}` | {shape} | {state} |"
            )
        body_lines.append("")
    for module in sorted(by_module):
        module_entries = sorted(by_module[module], key=lambda kv: kv[0])
        body_lines.append(f"### {module}")
        body_lines.append("")
        body_lines.append("| Resource | Type | Qualifiers | Shape | Image |")
        body_lines.append("|---|---|---|---|---|")
        for _, info in module_entries:
            qualifiers = info["qualifiers"] or "—"
            shape = info["shape"] or "—"
            img_path = f"renders/{info['module']}/{info['destRelative']}"
            body_lines.append(
                f"| `{info['resourceId']}` | {info['resourceType']} | "
                f"`{qualifiers}` | {shape} | <img src=\"{img_path}\" height=\"96\" /> |"
            )
        body_lines.append("")
    readme.write_text(existing.rstrip() + "\n" + "\n".join(body_lines).lstrip("\n") + "\n")
    return 0


# ---------------------------------------------------------------------------
# copy-changed-resources mode
# ---------------------------------------------------------------------------

def cmd_copy_changed_resources(args: argparse.Namespace) -> int:
    """Sibling of [cmd_copy_changed]. Reads
    `compose-preview show-resources --json` output, compares the rendered
    PNGs / GIFs against `resource-baselines.json`, and copies new or changed
    ones into `<output>/renders/<module>/resources/<...>`.

    Output layout matches [cmd_generate_resources] so the push to
    `compose-preview/resources/pr` lands these PNGs at paths the comment
    markdown can `_resource_url` to. Empty CLI envelope (no Android modules)
    is a silent no-op — same behaviour as `cmd_generate_resources`.

    When ``--baseline-renders`` points at the extracted
    `compose-preview/resources/main/renders/` tree, sha-mismatched pairs run
    through the same pixelmatch-based perceptual filter as the composable
    side (issue #190 / PR #270). Adaptive icons are particularly susceptible
    to sub-pixel jitter from the AA mask + ``PorterDuff.SRC_IN`` composite,
    so skipping the filter for resources made every adaptive-icon capture a
    likely false positive. Falls back to strict-bytes when the flag isn't
    passed (e.g. first-ever PR before `compose-preview/resources/main` exists).
    """
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    out_dir = Path(args.output_dir)
    baseline_renders = (
        Path(args.baseline_renders) if getattr(args, "baseline_renders", None) else None
    )

    entries = load_resource_results(cli_json)
    if not entries:
        return 0
    baselines = _load_baselines(baselines_path)

    copied = 0
    for key, info in entries.items():
        if not info["sha256"]:
            continue
        is_new = key not in baselines
        is_changed = not is_new and _is_changed(
            info, baselines[key], baseline_renders, size_aware=True
        )
        if not (is_new or is_changed):
            continue
        dest = out_dir / "renders" / info["module"] / info["destRelative"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(info["pngPath"], dest)
        copied += 1

    print(f"Copied {copied} changed/new resource preview(s) to {out_dir}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# compare-resources mode
# ---------------------------------------------------------------------------

def _resource_url(repo: str, ref: str, module: str, basename: str) -> str:
    """Same shape as [_render_url] but the resource basenames carry their own
    `resources/<type>/...` prefix, so the URL encodes
    `renders/<module>/resources/...`."""
    return (
        f"https://raw.githubusercontent.com/{repo}/{ref}"
        f"/renders/{module}/{basename}"
    )


def _resource_label(info: dict) -> str:
    """Human-readable label for one resource capture: `xhdpi`, `night-xhdpi`,
    `xhdpi · CIRCLE`, or `default` when no qualifiers / shape are set. Mirrors
    [_entry_label]'s role for composables."""
    parts: list[str] = []
    qualifiers = info.get("qualifiers")
    shape = info.get("shape")
    if qualifiers:
        parts.append(qualifiers)
    if shape:
        parts.append(shape)
    return " · ".join(parts) if parts else "default"


def cmd_compare_resources(args: argparse.Namespace) -> int:
    """Sibling of [cmd_compare] that emits a "Resource changes" markdown
    section against `resource-baselines.json`. No leading marker — the
    composable comment owns the marker, and this output is concatenated by
    `preview-comment/action.yml` after [cmd_compare]'s body. Emits an empty
    string when no resource manifests exist or no diff is detected, so the
    action can append unconditionally without polluting the comment.

    Uses the same `_is_changed` perceptual filter as the composable side
    when `--baseline-renders` is provided — adaptive icons composite
    foreground+background through an AA mask with `PorterDuff.SRC_IN`,
    which produces sha-different-but-perceptually-identical PNGs across
    Robolectric runs, so a strict-bytes compare flagged every adaptive icon
    on every PR (issue #190 redux for resources).
    """
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    repo = args.repo
    base_ref = args.base_ref
    head_ref = args.head_ref
    baseline_renders = (
        Path(args.baseline_renders) if getattr(args, "baseline_renders", None) else None
    )

    current = load_resource_results(cli_json)
    baselines = _load_baselines(baselines_path)

    new: list[tuple[str, dict]] = []
    changed: list[tuple[str, dict, dict]] = []
    removed: list[tuple[str, dict]] = []
    unchanged: list[tuple[str, dict]] = []

    for key, info in sorted(current.items()):
        if not info["sha256"]:
            continue
        if key not in baselines:
            new.append((key, info))
        elif _is_changed(info, baselines[key], baseline_renders, size_aware=True):
            changed.append((key, info, baselines[key]))
        else:
            unchanged.append((key, info))
    for key, bl_info in sorted(baselines.items()):
        if key not in current:
            removed.append((key, bl_info))

    failures = _collect_failures(current)

    marker = "<!-- preview-diff-resources -->"
    if not (new or changed or removed or failures):
        # Empty stdout when nothing diffed and nothing failed — the action
        # treats no output as "skip the resource comment entirely" rather
        # than posting a "no changes" sticky comment that would clutter PRs
        # that don't touch resources.
        return 0

    lines: list[str] = [marker, "## Resource Changes", ""]

    if failures:
        lines.append(
            f"> [!WARNING]"
            f"\n> {len(failures)} resource capture(s) failed to render in this PR's run. "
            f"The diff below covers only the captures that produced a PNG."
        )
        lines.append("")

    if changed:
        # Group by (module, resourceId) so all captures of the same resource
        # land under one heading — adaptive icons fan out to 4 shape masks
        # per icon and the per-capture rows would otherwise drown the diff.
        groups: dict[tuple[str, str], list[tuple[str, dict, dict]]] = {}
        for key, cur, bl in changed:
            groups.setdefault((cur["module"], cur["resourceId"]), []).append((key, cur, bl))
        lines.append(
            f"### Changed ({len(changed)} variant(s) across {len(groups)} resource(s))"
        )
        lines.append("")
        for (module, resource_id), entries in sorted(groups.items()):
            hero_key, hero_cur, hero_bl = entries[0]
            before = _resource_url(repo, base_ref, module, hero_cur["destRelative"])
            after = _resource_url(repo, head_ref, module, hero_cur["destRelative"])
            lines.append(f"**`{resource_id}`** ({module}, {hero_cur['resourceType']})")
            lines.append("")
            lines.append("| Before | After |")
            lines.append("|--------|-------|")
            lines.append(
                f"| <img src=\"{before}\" width=\"200\" /> "
                f"| <img src=\"{after}\" width=\"200\" /> |"
            )
            if len(entries) > 1:
                variant_links = []
                for _okey, ocur, _obl in entries[1:]:
                    label = _resource_label(ocur)
                    link = _resource_url(repo, head_ref, module, ocur["destRelative"])
                    variant_links.append(f"[{label}]({link})")
                lines.append("")
                lines.append(f"Other variants: {', '.join(variant_links)}")
            lines.append("")

    if new:
        groups_new: dict[tuple[str, str], list[tuple[str, dict]]] = {}
        for key, info in new:
            groups_new.setdefault((info["module"], info["resourceId"]), []).append((key, info))
        lines.append(
            f"### New ({len(new)} variant(s) across {len(groups_new)} resource(s))"
        )
        lines.append("")
        for (module, resource_id), entries in sorted(groups_new.items()):
            hero_key, hero_info = entries[0]
            after = _resource_url(repo, head_ref, module, hero_info["destRelative"])
            lines.append(
                f"**`{resource_id}`** ({module}, {hero_info['resourceType']}) "
                f"<img src=\"{after}\" width=\"200\" />"
            )
            if len(entries) > 1:
                variant_links = []
                for _okey, oinfo in entries[1:]:
                    label = _resource_label(oinfo)
                    link = _resource_url(repo, head_ref, module, oinfo["destRelative"])
                    variant_links.append(f"[{label}]({link})")
                lines.append(f"Variants: {', '.join(variant_links)}")
            lines.append("")

    if removed:
        # Group by (module, resourceId) — a resource being deleted typically
        # removes all its captures at once, surfacing them as N rows would be
        # noise.
        rm_resources = sorted({(bl_info.get("module", "?"), bl_info.get("resourceId", "?"))
                               for _, bl_info in removed})
        lines.append(f"### Removed ({len(removed)} variant(s) across {len(rm_resources)} resource(s))")
        lines.append("")
        for module, resource_id in rm_resources:
            lines.append(f"- ~`{resource_id}`~ ({module})")
        lines.append("")

    if failures:
        # Group by (module, resourceId) so multi-capture failures collapse
        # under one heading — same shape as Changed/New above.
        fail_groups: dict[tuple[str, str], list[tuple[str, dict]]] = {}
        for key, info in failures:
            gk = (info.get("module", "?"), info.get("resourceId", "?"))
            fail_groups.setdefault(gk, []).append((key, info))
        lines.append(
            f"### Render Failures ({len(failures)} variant(s) across {len(fail_groups)} resource(s))"
        )
        lines.append("")
        lines.append(
            "The render task completed but produced no PNG for these resource "
            "captures. Check the `composePreviewRenderAndroidResources-reports` "
            "artifact attached to this run."
        )
        lines.append("")
        for (module, resource_id), entries in sorted(fail_groups.items()):
            kind = entries[0][1].get("resourceType", "")
            lines.append(f"- **`{resource_id}`** ({module}, {kind})")
            for _key, info in entries:
                label = _resource_label(info)
                lines.append(f"  - {label}")
        lines.append("")

    print("\n".join(lines))
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = ap.add_subparsers(dest="command", required=True)

    gen = sub.add_parser("generate", help="Generate baselines from CLI output")
    gen.add_argument("cli_json", help="Path to compose-preview show --json output")
    gen.add_argument("--output-dir", required=True)
    gen.add_argument("--repo", required=True, help="owner/repo")
    gen.add_argument("--branch", default="compose-preview/main")
    # README customisation for non-`main` baseline branches (the integration
    # matrix names each consumer repo). Omitted = historical defaults.
    gen.add_argument("--title", help="README H1 (default: 'Preview Baselines').")
    gen.add_argument("--intro",
                     help="README intro paragraph (default: the compose-preview/main blurb).")
    gen.add_argument("--notes-file",
                     help="Path to a markdown file spliced into the README under the "
                          "intro (per-project workarounds / known issues). Omitted = none.")
    # Optional. When the render task produced no PNG for some previews,
    # pull their prior entry from this `baselines.json` instead of dropping
    # them — keeps `compose-preview/main` complete across flaky single-preview
    # render failures. The matching `renders/` tree is the
    # `--prior-renders` arg below.
    gen.add_argument("--prior-baselines",
                     help="Path to the existing baselines.json on the baseline branch.")
    gen.add_argument("--prior-renders",
                     help="Directory containing the existing renders/<module>/<basename> "
                          "PNG tree on the baseline branch.")
    # Same A/B config as `compare` — nominated variant groups render
    # side-by-side at the top of their module section in the gallery README.
    gen.add_argument("--ab-config",
                     help="Path to the A/B comparison config JSON "
                          "(default off; the apply action passes "
                          ".github/preview-abtest.json when present).")

    cmp = sub.add_parser("compare", help="Compare CLI output against baselines")
    cmp.add_argument("cli_json", help="Path to compose-preview show --json output")
    cmp.add_argument("--baselines", required=True, help="Path to baselines.json")
    cmp.add_argument("--repo", required=True)
    # SHA-pin both sides so the PR comment's images keep resolving after
    # `compose-preview/main` advances and after the PR merges. Branch names
    # are accepted as a first-run fallback when no commit exists yet.
    cmp.add_argument("--base-ref", default="compose-preview/main",
                     help="compose-preview/main commit SHA (or branch name) for Before URLs")
    cmp.add_argument("--head-ref", required=True,
                     help="compose-preview/pr commit SHA (or branch name) for After URLs")
    # Optional. When supplied, sha-mismatched pairs run through pixelmatch
    # before being flagged as Changed so renderer-side AA noise (issue #190)
    # doesn't appear in the comment. Falls back to strict-bytes when omitted.
    cmp.add_argument("--baseline-renders",
                     help="Directory containing baseline PNGs (renders/<module>/<basename>)")
    # Optional. Path to the A/B comparison config (default
    # `.github/preview-abtest.json`, wired by the apply action). Nominated
    # variant groups render side-by-side instead of hero+links. Missing /
    # malformed → no A/B groups (feature is purely additive).
    cmp.add_argument("--ab-config",
                     help="Path to the A/B comparison config JSON "
                          "(default off; the apply action passes "
                          ".github/preview-abtest.json when present).")
    cmp.add_argument("--scope-modules",
                     help="Comma-separated Gradle module paths the render was "
                          "scoped to (change-scoped PR runs). Baseline entries "
                          "for modules outside this set are treated as "
                          "unchanged instead of removed — they were never "
                          "rendered this run. Omit for full runs.")

    cp = sub.add_parser("copy-changed", help="Copy new/changed PNGs to output dir")
    cp.add_argument("cli_json", help="Path to compose-preview show --json output")
    cp.add_argument("--baselines", required=True)
    cp.add_argument("--output-dir", required=True)
    cp.add_argument("--baseline-renders",
                    help="Directory containing baseline PNGs (renders/<module>/<basename>)")

    gen_res = sub.add_parser(
        "generate-resources",
        help="Stage the rendered PNGs / GIFs from `compose-preview show-resources --json` "
             "into the baselines tree. No-ops on empty envelopes (workspaces with no "
             "Android resource modules).",
    )
    gen_res.add_argument("cli_json",
                         help="Path to compose-preview show-resources --json output")
    gen_res.add_argument("--output-dir", required=True)
    # Same carry-over inputs as `generate` — when a resource render flakes,
    # the prior baseline entry + PNG come from these paths so the branch
    # keeps its gallery row instead of dropping the resource entirely.
    gen_res.add_argument("--prior-baselines",
                         help="Path to the existing resource-baselines.json on the "
                              "resource baseline branch.")
    gen_res.add_argument("--prior-renders",
                         help="Directory containing the existing "
                              "renders/<module>/resources/... PNG tree on the "
                              "resource baseline branch.")

    cp_res = sub.add_parser(
        "copy-changed-resources",
        help="Sibling of `copy-changed` for resource captures.",
    )
    cp_res.add_argument("cli_json",
                        help="Path to compose-preview show-resources --json output")
    cp_res.add_argument("--baselines", required=True,
                        help="Path to resource-baselines.json (fetched from compose-preview/resources/main)")
    cp_res.add_argument("--output-dir", required=True)
    # Same perceptual-filter knob as `copy-changed` — when provided,
    # sha-mismatched pairs run through pixelmatch before being copied as
    # changed. Filters AA jitter that adaptive-icon mask compositing
    # produces between Robolectric runs (issue #190).
    cp_res.add_argument("--baseline-renders",
                        help="Directory containing baseline resource PNGs "
                             "(renders/<module>/resources/...)")

    cmp_res = sub.add_parser(
        "compare-resources",
        help="Sibling of `compare` for resource captures. Emits the markdown section "
             "to append after the composable diff. Empty stdout when nothing changed.",
    )
    cmp_res.add_argument("cli_json",
                         help="Path to compose-preview show-resources --json output")
    cmp_res.add_argument("--baselines", required=True,
                         help="Path to resource-baselines.json (fetched from compose-preview/resources/main)")
    cmp_res.add_argument("--repo", required=True)
    cmp_res.add_argument("--base-ref", default="compose-preview/resources/main")
    cmp_res.add_argument("--head-ref", required=True)
    cmp_res.add_argument("--baseline-renders",
                         help="Directory containing baseline resource PNGs "
                              "(renders/<module>/resources/...)")

    args = ap.parse_args()
    handlers = {
        "generate": cmd_generate,
        "compare": cmd_compare,
        "copy-changed": cmd_copy_changed,
        "generate-resources": cmd_generate_resources,
        "copy-changed-resources": cmd_copy_changed_resources,
        "compare-resources": cmd_compare_resources,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
