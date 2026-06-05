#!/usr/bin/env python3
"""Smoke test for `xr-composite --serve` (the long-lived JSON-RPC render server).

Drives the server over stdio with the LSP-style Content-Length framing it speaks:
`initialize` -> `render` (a scene) -> `xr/updatePanels` (move a panel). Asserts that each render
emits a `streamFrame` notification and that moving a panel actually produces a different frame
(i.e. the held Filament engine re-rendered the mutated scene). Exits non-zero on any failure.

Must run under a GL context (headless: Xvfb + Mesa llvmpipe), same as the one-shot render. Usage:

    xvfb-run -a -s "-screen 0 2000x1400x24" \
      env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      python3 test/serve_smoke.py <xr-composite-binary> <materials-dir> <scene-dir>
"""
import json
import os
import subprocess
import sys


def main() -> int:
    binary, materials, scene_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    scene = json.load(open(os.path.join(scene_dir, "scene.json")))

    proc = subprocess.Popen(
        [binary, "--serve", "--materials", materials, "--width", "640", "--height", "400"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    )

    def send(obj):
        body = json.dumps(obj).encode()
        proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        proc.stdin.flush()

    def read_msg():
        length = 0
        while True:
            line = proc.stdout.readline()
            if not line:
                return None
            line = line.rstrip(b"\r\n")
            if line == b"":
                break
            if line.lower().startswith(b"content-length:"):
                length = int(line.split(b":")[1])
        return json.loads(proc.stdout.read(length))

    frames = []  # streamFrame seqs seen

    def pump_until_id(want):
        while True:
            m = read_msg()
            if m is None:
                raise SystemExit("server closed stdout before responding")
            if m.get("method") == "streamFrame":
                p = m["params"]
                assert p["encoding"] == "png" and p["data"], "streamFrame missing png data"
                frames.append((p["seq"], p["data"]))
            elif m.get("id") == want:
                return m

    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"frameStreamId": "fs1"}})
    caps = pump_until_id(1)["result"]["capabilities"]
    assert caps.get("render") and caps.get("updatePanels") and caps.get("streamFrame"), caps

    send({"jsonrpc": "2.0", "id": 2, "method": "render",
          "params": {"scene": scene, "sceneDir": scene_dir}})
    assert pump_until_id(2)["result"]["ok"], "render did not ack ok"

    send({"jsonrpc": "2.0", "id": 3, "method": "xr/updatePanels",
          "params": {"panels": [
              {"id": scene["panels"][0]["id"],
               "poseInRoot": {"translation": {"x": 120, "y": 160, "z": 0},
                              "rotation": {"x": 0, "y": 0, "z": 0, "w": 1}}}]}})
    assert pump_until_id(3)["result"]["ok"], "updatePanels did not ack ok"

    send({"jsonrpc": "2.0", "method": "exit"})
    proc.wait(timeout=15)

    assert len(frames) >= 2, f"expected >=2 streamFrames, got {len(frames)}"
    assert frames[0][0] == 1 and frames[1][0] == 2, f"unexpected seq order: {[f[0] for f in frames]}"
    assert frames[0][1] != frames[1][1], "frame did not change after moving a panel"
    assert proc.returncode == 0, f"server exit code {proc.returncode}"
    print(f"OK: {len(frames)} streamFrames; per-frame update changed the image")
    return 0


if __name__ == "__main__":
    sys.exit(main())
