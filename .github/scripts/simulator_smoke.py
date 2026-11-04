#!/usr/bin/env python3
"""Smoke-tests `compose-preview launch`: a sample APK in the simulator, headless, driven over the
control channel the way an agent drives it.

    simulator_smoke.py --cli <compose-preview> --apk <apk> --check surface-view|host-natives \\
        [--sdk 35] [--java <JDK 21 home>] [--out <dir>]

Every run checks that the control channel's stdout carries only JSON (one object a line) and that
the app reaches `ready`. Then, per sample:

  surface-view   a screenshot shows both of samples/surface-view's SurfaceViews: each half of the
                 screen holds its red, green, blue and yellow quadrants (the software surface
                 behind its window, the hardware one on top).
  host-natives   samples/host-natives logs `HostNatives: DONE n/n passed` and no `FAIL`: every
                 native library in host-natives/index.json loaded and worked.

Exits 0 on success, 1 with the reason (and the simulator's stderr tail) otherwise. Standard
library only: the PNG is decoded here, not with Pillow.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import re
import struct
import subprocess
import sys
import threading
import time
import zlib
from pathlib import Path

READY_TIMEOUT_S = 300
DONE_TIMEOUT_S = 180
SCREENSHOT_SETTLE_S = 3


class SmokeFailure(Exception):
    pass


# --- PNG -----------------------------------------------------------------------------------------

def decode_png(data: bytes) -> tuple[int, int, list[bytes]]:
    """(width, height, rows of RGBA bytes) for an 8-bit, non-interlaced RGB or RGBA PNG."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, header = 8, b"", None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        kind = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if header is None:
        raise ValueError("no IHDR")
    width, height, depth, color, _, _, interlace = header
    channels = {2: 3, 6: 4}.get(color)
    if depth != 8 or channels is None or interlace:
        raise ValueError(f"unsupported PNG (depth {depth}, color type {color}, interlace {interlace})")
    raw = zlib.decompress(idat)
    stride = width * channels
    rows: list[bytes] = []
    prior = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind = raw[start]
        line = bytearray(raw[start + 1 : start + 1 + stride])
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up = prior[i]
            upleft = prior[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif kind == 4:
                p = left + up - upleft
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - upleft)
                predictor = left if pa <= pb and pa <= pc else up if pb <= pc else upleft
                line[i] = (line[i] + predictor) & 0xFF
        prior = line
        if channels == 3:
            rgba = bytearray()
            for i in range(0, stride, 3):
                rgba += line[i : i + 3] + b"\xff"
            line = rgba
        rows.append(bytes(line))
    return width, height, rows


COLORS = {
    "red": (255, 0, 0),
    "green": (0, 255, 0),
    "blue": (0, 0, 255),
    "yellow": (255, 255, 0),
}


def color_counts(rows: list[bytes], top: int, bottom: int) -> dict[str, int]:
    """How many pixels in rows [top, bottom) are each of [COLORS], within a small tolerance."""
    counts = dict.fromkeys(COLORS, 0)
    for y in range(top, bottom):
        row = rows[y]
        for x in range(0, len(row), 4):
            r, g, b = row[x], row[x + 1], row[x + 2]
            for name, (cr, cg, cb) in COLORS.items():
                if abs(r - cr) < 24 and abs(g - cg) < 24 and abs(b - cb) < 24:
                    counts[name] += 1
                    break
    return counts


# --- The simulator -------------------------------------------------------------------------------

class Simulator:
    """`compose-preview launch … --control stdio`, its stdout read as protocol lines."""

    def __init__(self, command: list[str], stderr_path: Path, env: dict[str, str]):
        self.stderr_path = stderr_path
        self.stderr = open(stderr_path, "w", encoding="utf-8")
        self.process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.stderr,
            text=True,
            encoding="utf-8",
            env=env,
        )
        self.lines: "queue.Queue[dict | None]" = queue.Queue()
        self.not_json: list[str] = []
        threading.Thread(target=self._read, daemon=True).start()
        self.next_id = 1

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            line = line.rstrip("\n")
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                obj = None
            if not isinstance(obj, dict):
                self.not_json.append(line)
                continue
            self.lines.put(obj)
        self.lines.put(None)

    def wait_for(self, predicate, timeout: float, what: str) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SmokeFailure(f"timed out after {timeout:.0f}s waiting for {what}")
            try:
                obj = self.lines.get(timeout=remaining)
            except queue.Empty:
                continue
            if obj is None:
                raise SmokeFailure(f"the simulator ended before {what} (exit {self.process.wait()})")
            if obj.get("event") == "error":
                raise SmokeFailure(f"the simulator reported an error: {json.dumps(obj)}")
            if predicate(obj):
                return obj

    def command(self, cmd: str, timeout: float = 120, **fields) -> dict:
        request_id = self.next_id
        self.next_id += 1
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps({"id": request_id, "cmd": cmd, **fields}) + "\n")
        self.process.stdin.flush()
        reply = self.wait_for(lambda o: o.get("id") == request_id, timeout, f"the reply to {cmd}")
        if not reply.get("ok"):
            raise SmokeFailure(f"{cmd} failed: {reply.get('error')}")
        return reply.get("result") or {}

    def log(self) -> str:
        if not self.stderr.closed:
            self.stderr.flush()
        return self.stderr_path.read_text(encoding="utf-8", errors="replace")

    def close(self) -> int:
        try:
            if self.process.stdin:
                self.process.stdin.close()
        except OSError:
            pass
        try:
            return self.process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            self.process.kill()
            return self.process.wait()
        finally:
            self.stderr.close()


# --- Checks --------------------------------------------------------------------------------------

def check_surface_view(sim: Simulator, out: Path) -> str:
    time.sleep(SCREENSHOT_SETTLE_S)
    shot = out / "surface-view.png"
    sim.command("screenshot", path=str(shot))
    width, height, rows = decode_png(shot.read_bytes())
    halves = {
        "top (software lockCanvas, behind its window)": color_counts(rows, 0, height // 2),
        "bottom (hardware canvas, setZOrderOnTop)": color_counts(rows, height // 2, height),
    }
    # Each quadrant of a 540x480 surface is ~65k pixels; a tenth of that is unmistakable.
    floor = width * height // 80
    for half, counts in halves.items():
        missing = [name for name, count in counts.items() if count < floor]
        if missing:
            raise SmokeFailure(
                f"the {half} SurfaceView is missing {', '.join(missing)} "
                f"(pixels per colour: {counts}, need {floor}); see {shot}"
            )
    return f"both SurfaceViews composited ({width}x{height}); {halves}"


DONE = re.compile(r"HostNatives: DONE (\d+)/(\d+) passed")


def check_host_natives(sim: Simulator, out: Path) -> str:
    deadline = time.monotonic() + DONE_TIMEOUT_S
    while time.monotonic() < deadline:
        log = sim.log()
        done = DONE.search(log)
        if done:
            failures = [line for line in log.splitlines() if "HostNatives: FAIL" in line]
            passes = [line for line in log.splitlines() if "HostNatives: PASS" in line]
            passed, total = int(done.group(1)), int(done.group(2))
            if failures or passed != total:
                raise SmokeFailure(
                    f"{passed}/{total} host-native checks passed:\n" + "\n".join(failures)
                )
            return f"{passed}/{total} host-native checks passed:\n" + "\n".join(passes)
        if sim.process.poll() is not None:
            raise SmokeFailure(f"the simulator ended before HostNatives: DONE (exit {sim.process.returncode})")
        time.sleep(1)
    raise SmokeFailure(f"no `HostNatives: DONE` within {DONE_TIMEOUT_S}s")


CHECKS = {"surface-view": check_surface_view, "host-natives": check_host_natives}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--cli", required=True, help="the compose-preview launcher")
    ap.add_argument("--apk", required=True)
    ap.add_argument("--check", required=True, choices=sorted(CHECKS))
    ap.add_argument("--sdk", default="35")
    ap.add_argument("--java", help="a JDK 21+ home for the simulator")
    ap.add_argument("--out", default="build/simulator-smoke")
    args = ap.parse_args()

    out = Path(args.out).absolute()
    out.mkdir(parents=True, exist_ok=True)
    command = [args.cli, "launch", args.apk, "--headless", "--control", "stdio", "--sdk", args.sdk]
    if args.java:
        command += ["--java", args.java]
    print("$ " + " ".join(command), flush=True)
    stderr_path = out / f"{args.check}.stderr.log"
    sim = Simulator(command, stderr_path, dict(os.environ))
    try:
        ready = sim.wait_for(lambda o: o.get("event") == "ready", READY_TIMEOUT_S, "ready")
        print(f"ready: {ready.get('activity')} (startup {ready.get('startup', {}).get('totalMs')} ms)")
        summary = CHECKS[args.check](sim, out)
    except SmokeFailure as failure:
        sim.close()
        tail = "\n".join(sim.log().splitlines()[-60:])
        print(f"::error::simulator smoke ({args.check}): {failure}", flush=True)
        print(f"--- simulator stderr (last 60 lines of {stderr_path}) ---\n{tail}")
        return 1
    exit_code = sim.close()
    if sim.not_json:
        print(f"::error::simulator smoke ({args.check}): the control channel's stdout carried "
              f"{len(sim.not_json)} line(s) that are not JSON, e.g. {sim.not_json[0]!r}")
        return 1
    print(summary)
    print(f"ok: {args.check}; the simulator exited {exit_code} when stdin closed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
