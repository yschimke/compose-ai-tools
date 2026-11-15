#!/usr/bin/env python3
"""Run deterministic side-by-side dwf-apk (oracle) vs dwf-rc (compiler/player) diffs.

Usage examples:
  # Build minimal WFF APKs from wear-os-samples + casino-gshark checkouts:
  python3 scripts/dwf-diff.py --build-apks \
    --wear-os-samples /path/to/wear-os-samples \
    --casino-gshark /path/to/casino-gshark \
    --apks-dir /tmp/dwf-diff/wff-apks

  # Run side-by-side diff against an installed or staged compose-preview CLI:
  python3 scripts/dwf-diff.py \
    --cli /path/to/compose-preview \
    --app-home /path/to/cli-app-home \
    --apks-dir /tmp/dwf-diff/wff-apks \
    --out-dir /tmp/dwf-diff/out
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw

FROZEN_CLOCK_ISO = "2026-11-18T10:10:30Z"

CORPUS_MANIFESTS = [
    ("wff-simple-analog", "wear-os-samples", "WatchFaceFormat/SimpleAnalog/watchface/src/main", "com.example.wear.simpleanalog"),
    ("wff-simple-digital", "wear-os-samples", "WatchFaceFormat/SimpleDigital/watchface/src/main", "com.example.wear.simpledigital"),
    ("wff-complications", "wear-os-samples", "WatchFaceFormat/Complications/watchface/src/main", "com.example.wear.complications"),
    ("wff-flavors", "wear-os-samples", "WatchFaceFormat/Flavors/watchface/src/main", "com.example.wear.flavors"),
    ("wff-photos-mask", "wear-os-samples", "WatchFaceFormat/PhotosMask/watchface/src/main", "com.example.wear.photosmask"),
    ("wff-photos-multi", "wear-os-samples", "WatchFaceFormat/PhotosMulti/watchface/src/main", "com.example.wear.photosmulti"),
    ("wff-weather", "wear-os-samples", "WatchFaceFormat/Weather/watchface/src/main", "com.example.wear.weather"),
    ("watchface-push-firefly", "wear-os-samples", "WatchFacePush/samples/firefly/src/main", "com.example.wear.watchfacepush.firefly"),
    ("casino-gshark", "casino-gshark", "app/src/main", "net.bonstio.watchface.gshark"),
]


def ensure_manifest_package(manifest_path: Path, pkg: str, tmp_dir: Path) -> Path:
    text = manifest_path.read_text(encoding="utf-8")
    if re.search(r"\bpackage\s*=", text):
        return manifest_path
    patched = re.sub(r"<manifest\b", f'<manifest package="{pkg}"', text, count=1)
    out = tmp_dir / "AndroidManifest.xml"
    out.write_text(patched, encoding="utf-8")
    return out


def build_apks(
    aapt2: str,
    android_jar: str,
    wear_os_samples: Path | None,
    casino_gshark: Path | None,
    apks_dir: Path,
) -> None:
    apks_dir.mkdir(parents=True, exist_ok=True)
    for name, repo_key, rel_path, pkg in CORPUS_MANIFESTS:
        root = wear_os_samples if repo_key == "wear-os-samples" else casino_gshark
        if root is None:
            print(f"Skipping {name}: --{repo_key} not provided", file=sys.stderr)
            continue
        src_main = root / rel_path
        res_dir = src_main / "res"
        manifest = src_main / "AndroidManifest.xml"
        if not manifest.is_file():
            raise FileNotFoundError(f"Missing manifest for {name}: {manifest}")
        apk_out = apks_dir / f"{name}.apk"
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            res_zip = tmp_path / "res.zip"
            subprocess.run([aapt2, "compile", "--dir", str(res_dir), "-o", str(res_zip)], check=True)
            manifest_to_use = ensure_manifest_package(manifest, pkg, tmp_path)
            subprocess.run(
                [
                    aapt2,
                    "link",
                    "-o",
                    str(apk_out),
                    "-I",
                    android_jar,
                    "--manifest",
                    str(manifest_to_use),
                    "--min-sdk-version",
                    "33",
                    "--target-sdk-version",
                    "34",
                    str(res_zip),
                ],
                check=True,
            )
        print(f"Built {apk_out} ({apk_out.stat().st_size} bytes)")


class StdioSession:
    def __init__(
        self,
        cli: str,
        apk: Path,
        dwf_runtime: str,
        app_home: str | None,
        max_heap: str,
        dwf_runtime_apk: str | None,
        timeout_s: float,
        stderr_path: Path | None = None,
    ) -> None:
        env = os.environ.copy()
        extra_opts = [f"-Xmx{max_heap}"]
        if app_home:
            extra_opts.append(f"-Dcomposeai.cli.appHome={app_home}")
        if dwf_runtime_apk:
            env["COMPOSE_PREVIEW_DWF_RENDERER_APK"] = dwf_runtime_apk
            extra_opts.append(f"-Dcomposeai.watchface.apkRenderer={dwf_runtime_apk}")
        existing_opts = env.get("JAVA_OPTS", "").strip()
        env["JAVA_OPTS"] = f"{existing_opts} {' '.join(extra_opts)}".strip()

        cmd = [
            cli,
            "launch",
            str(apk),
            "--control",
            "stdio",
            "--dwf-runtime",
            dwf_runtime,
            "--max-heap",
            max_heap,
        ]
        self.timeout_s = timeout_s
        self.stderr_path = stderr_path
        self._stderr_file = open(stderr_path, "w", encoding="utf-8") if stderr_path else None
        self.proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self._stderr_file if self._stderr_file else subprocess.DEVNULL,
            text=True,
            bufsize=1,
            env=env,
        )
        self._wait_ready()

    def _stderr_tail(self) -> str:
        if self.stderr_path and self.stderr_path.is_file():
            return self.stderr_path.read_text(encoding="utf-8", errors="replace")[-1000:]
        return ""

    def _read_json_line(self) -> dict[str, Any]:
        assert self.proc.stdout is not None
        deadline = time.monotonic() + self.timeout_s
        while True:
            if time.monotonic() > deadline:
                raise TimeoutError("Timed out waiting for simulator stdio response")
            line = self.proc.stdout.readline()
            if not line:
                rc = self.proc.poll()
                raise RuntimeError(f"Simulator exited unexpectedly (code={rc}): {self._stderr_tail()}")
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if msg.get("event"):
                continue
            return msg

    def _wait_ready(self) -> None:
        assert self.proc.stdout is not None
        deadline = time.monotonic() + self.timeout_s
        while True:
            if time.monotonic() > deadline:
                raise TimeoutError("Timed out waiting for simulator ready event")
            line = self.proc.stdout.readline()
            if not line:
                rc = self.proc.poll()
                raise RuntimeError(f"Simulator exited before ready (code={rc}): {self._stderr_tail()}")
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if msg.get("event") == "ready" or msg.get("ok") is True:
                return

    def send(self, payload: dict[str, Any]) -> dict[str, Any]:
        assert self.proc.stdin is not None
        self.proc.stdin.write(json.dumps(payload) + "\n")
        self.proc.stdin.flush()
        resp = self._read_json_line()
        if resp.get("ok") is False:
            raise RuntimeError(f"Command {payload} failed: {resp}")
        return resp

    def close(self) -> None:
        try:
            if self.proc.stdin and not self.proc.stdin.closed:
                self.proc.stdin.write(json.dumps({"cmd": "quit"}) + "\n")
                self.proc.stdin.flush()
                self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=5)
        if self._stderr_file is not None:
            self._stderr_file.close()


def run_sample_states(sample: str, session: StdioSession, out_dir: Path, prefix: str) -> list[tuple[str, Path]]:
    out_dir.mkdir(parents=True, exist_ok=True)
    captured: list[tuple[str, Path]] = []

    def snap(state: str) -> None:
        path = out_dir / f"{prefix}_{state}.png"
        session.send({"cmd": "screenshot", "path": str(path)})
        captured.append((state, path))

    session.send({
        "cmd": "clock",
        "time": FROZEN_CLOCK_ISO,
        "frozen": True,
        "timeZone": "UTC",
        "is24Hour": True,
    })
    # Pump Robolectric looper and wait until the watch face has rendered at least 1 frame.
    warmup = out_dir / f".warmup_{prefix}.png"
    status: dict[str, Any] = {}
    for _ in range(15):
        time.sleep(0.35)
        session.send({"cmd": "screenshot", "path": str(warmup)})
        status = session.send({"cmd": "watchface", "op": "status"})
        res = status.get("result") or status
        if res.get("frames", 0) >= 1:
            break
    warmup.unlink(missing_ok=True)
    result = status.get("result") or status
    slots = [s for s in (result.get("slots") or []) if isinstance(s, dict) and s.get("id") is not None]

    if sample == "wff-simple-analog":
        snap("interactive")
        session.send({"cmd": "watchface", "op": "config", "configId": "themeColor", "value": "1"})
        snap("config_theme1")
        session.send({"cmd": "watchface", "op": "config", "configId": "themeColor", "value": "3"})
        snap("config_theme3")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample == "wff-simple-digital":
        snap("interactive")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample == "wff-complications":
        snap("default")
        used_slots: set[Any] = set()
        type_to_wire = {
            "SHORT_TEXT": 3,
            "LONG_TEXT": 4,
            "RANGED_VALUE": 5,
            "GOAL_PROGRESS": 13,
            "WEIGHTED_ELEMENTS": 14,
        }
        desired = [
            ("RANGED_VALUE", {"type": "RANGED_VALUE", "value": 72, "min": 0, "max": 100, "text": "72", "title": "HR"}),
            ("SHORT_TEXT", {"type": "SHORT_TEXT", "text": "10:30", "title": "NEXT"}),
            ("GOAL_PROGRESS", {"type": "GOAL_PROGRESS", "value": 6420, "max": 10000, "text": "6420", "title": "STEPS"}),
            ("LONG_TEXT", {"type": "LONG_TEXT", "text": "Design Sync 10:30", "title": "Calendar"}),
        ]
        for target_type, payload in desired:
            wire_id = type_to_wire[target_type]
            for slot in slots:
                sid = slot.get("id")
                if sid in used_slots:
                    continue
                supported = slot.get("supportedTypes") or []
                if wire_id in supported or target_type in supported or not supported:
                    used_slots.add(sid)
                    session.send({"cmd": "watchface", "op": "complication", "slot": sid, **payload})
                    break
        snap("populated")
        session.send({"cmd": "watchface", "op": "config", "configId": "themeColor", "value": "1"})
        snap("theme1")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample == "wff-flavors":
        snap("default")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample in ("wff-photos-mask", "wff-photos-multi"):
        snap("interactive")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample == "wff-weather":
        session.send({"cmd": "weather", "available": True, "condition": "CLEAR", "isDay": True, "temperature": 22})
        snap("clear_day")
        session.send({"cmd": "weather", "available": True, "condition": "RAIN", "isDay": False, "temperature": 11})
        snap("rain_night")
        session.send({"cmd": "weather", "available": False})
        snap("unavailable")
        session.send({"cmd": "weather", "available": True, "condition": "CLEAR", "isDay": True, "temperature": 22})
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample == "watchface-push-firefly":
        snap("interactive")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    elif sample == "casino-gshark":
        session.send({"cmd": "health", "heartRate": 72, "steps": 6420})
        session.send({"cmd": "battery", "level": 85, "charging": False})
        snap("interactive")
        session.send({"cmd": "watchface", "op": "config", "configId": "themeColor", "value": "2"})
        snap("theme2")
        session.send({"cmd": "watchface", "op": "config", "configId": "themeColor", "value": "0"})
        session.send({"cmd": "watchface", "op": "config", "configId": "analogHands", "value": "1"})
        snap("analogHands1")
        session.send({"cmd": "watchface", "op": "config", "configId": "analogHands", "value": "0"})
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")
    else:
        snap("interactive")
        session.send({"cmd": "ambient", "value": True})
        snap("ambient")

    return captured


def compute_metrics_and_heatmap(apk_path: Path, rc_path: Path, triptych_path: Path) -> dict[str, float]:
    img_a = Image.open(apk_path).convert("RGBA")
    img_b = Image.open(rc_path).convert("RGBA")
    if img_b.size != img_a.size:
        img_b = img_b.resize(img_a.size, Image.Resampling.BILINEAR)

    w, h = img_a.size
    cx, cy = w // 2, h // 2
    radius_sq = (cx - 4) * (cx - 4)

    pa = img_a.load()
    pb = img_b.load()
    heatmap = Image.new("RGBA", (w, h), (0, 0, 0, 255))
    ph = heatmap.load()

    match28 = 0
    match32 = 0
    total = 0
    sum_abs_diff = 0

    for y in range(h):
        dy = y - cy
        for x in range(w):
            dx = x - cx
            if dx * dx + dy * dy > radius_sq:
                ph[x, y] = (18, 18, 18, 255)
                continue
            total += 1
            ra, ga, ba, aa = pa[x, y]
            rb, gb, bb, ab = pb[x, y]
            if aa < 16 and ab < 16:
                match28 += 1
                match32 += 1
                ph[x, y] = (0, 0, 0, 255)
                continue
            # Composite onto black for RGB comparison matching DwfWearOsSamplesValidationTest
            ra_p = (ra * aa) // 255
            ga_p = (ga * aa) // 255
            ba_p = (ba * aa) // 255
            rb_p = (rb * ab) // 255
            gb_p = (gb * ab) // 255
            bb_p = (bb * ab) // 255
            dr = abs(ra_p - rb_p)
            dg = abs(ga_p - gb_p)
            db = abs(ba_p - bb_p)
            mx = max(dr, dg, db)
            sum_abs_diff += dr + dg + db
            if mx <= 28:
                match28 += 1
            if mx <= 32:
                match32 += 1
            # Heatmap: green when matching <=28, yellow->red when diverging
            if mx <= 28:
                lum = (ra_p + ga_p + ba_p) // 9
                ph[x, y] = (lum, lum, lum, 255)
            else:
                intensity = min(255, 80 + mx)
                ph[x, y] = (intensity, max(0, 255 - mx * 2), 0, 255)

    strip = Image.new("RGBA", (w * 3, h + 24), (24, 24, 24, 255))
    strip.paste(img_a, (0, 24))
    strip.paste(img_b, (w, 24))
    strip.paste(heatmap, (w * 2, 24))
    draw = ImageDraw.Draw(strip)
    sim28 = match28 / total if total else 1.0
    sim32 = match32 / total if total else 1.0
    mae = sum_abs_diff / (total * 3.0) if total else 0.0
    draw.text((8, 6), "dwf-apk (oracle)", fill=(220, 220, 220, 255))
    draw.text((w + 8, 6), f"dwf-rc (sim28={sim28:.4f})", fill=(220, 220, 220, 255))
    draw.text((w * 2 + 8, 6), f"diff heatmap (mae={mae:.1f})", fill=(255, 180, 120, 255))
    strip.save(triptych_path)

    return {"sim28": round(sim28, 4), "sim32": round(sim32, 4), "mae": round(mae, 2)}


def main() -> int:
    parser = argparse.ArgumentParser(description="Run dwf-apk vs dwf-rc watch face diff.")
    parser.add_argument("--build-apks", action="store_true", help="Build WFF APKs from source checkouts first.")
    parser.add_argument("--wear-os-samples", type=Path, help="Path to android/wear-os-samples checkout.")
    parser.add_argument("--casino-gshark", type=Path, help="Path to bonstio/casino-gshark checkout.")
    parser.add_argument("--aapt2", default=os.environ.get("AAPT2", "aapt2"), help="Path to aapt2 binary.")
    parser.add_argument("--android-jar", default=os.environ.get("ANDROID_JAR", ""), help="Path to android.jar.")
    parser.add_argument("--cli", default="compose-preview", help="Path to compose-preview CLI binary.")
    parser.add_argument("--app-home", default=None, help="Optional composeai.cli.appHome override.")
    parser.add_argument("--dwf-runtime-apk", default=None, help="Optional path to DeclarativeWatchFaceRuntime.apk.")
    parser.add_argument("--apks-dir", type=Path, required=True, help="Directory containing <sample>.apk files.")
    parser.add_argument("--out-dir", type=Path, help="Output directory for PNGs, triptychs, and summary.json.")
    parser.add_argument("--sample", action="append", help="Specific sample(s) to run (default: all).")
    parser.add_argument("--max-heap", default="512m", help="JVM max heap for simulator subprocess.")
    parser.add_argument("--timeout", type=float, default=120.0, help="Per-command timeout in seconds.")
    args = parser.parse_args()

    if args.build_apks:
        if not args.android_jar:
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/home/antigravity/android-sdk"
            args.android_jar = str(Path(sdk) / "platforms/android-35/android.jar")
        build_apks(args.aapt2, args.android_jar, args.wear_os_samples, args.casino_gshark, args.apks_dir)
        if not args.out_dir:
            return 0

    if not args.out_dir:
        parser.error("--out-dir is required unless running --build-apks alone")

    selected = set(args.sample) if args.sample else {name for name, *_ in CORPUS_MANIFESTS}
    args.out_dir.mkdir(parents=True, exist_ok=True)
    summary: dict[str, Any] = {"samples": {}}

    for name, *_ in CORPUS_MANIFESTS:
        if name not in selected:
            continue
        apk_path = args.apks_dir / f"{name}.apk"
        if not apk_path.is_file():
            print(f"SKIP {name}: {apk_path} not found", file=sys.stderr)
            continue

        sample_out = args.out_dir / name
        sample_out.mkdir(parents=True, exist_ok=True)
        print(f"=== {name} ===", flush=True)

        apk_sess = StdioSession(
            args.cli, apk_path, "auto", args.app_home, args.max_heap, args.dwf_runtime_apk, args.timeout,
            stderr_path=sample_out / "apk_stderr.log",
        )
        try:
            apk_frames = dict(run_sample_states(name, apk_sess, sample_out, "apk"))
        finally:
            apk_sess.close()

        rc_sess = StdioSession(
            args.cli, apk_path, "none", args.app_home, args.max_heap, args.dwf_runtime_apk, args.timeout,
            stderr_path=sample_out / "rc_stderr.log",
        )
        try:
            rc_frames = dict(run_sample_states(name, rc_sess, sample_out, "rc"))
        finally:
            rc_sess.close()

        sample_metrics: dict[str, Any] = {}
        for state, apk_png in apk_frames.items():
            rc_png = rc_frames[state]
            triptych = sample_out / f"triptych_{state}.png"
            metrics = compute_metrics_and_heatmap(apk_png, rc_png, triptych)
            sample_metrics[state] = metrics
            print(f"  {state:16s} sim28={metrics['sim28']:.4f} sim32={metrics['sim32']:.4f} mae={metrics['mae']:.2f}", flush=True)

        summary["samples"][name] = sample_metrics

    summary_path = args.out_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
