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
                frames.append((p["seq"], p["data"], p.get("sessionId")))
            elif m.get("id") == want:
                return m

    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"frameStreamId": "fs1"}})
    caps = pump_until_id(1)["result"]["capabilities"]
    assert caps.get("render") and caps.get("updatePanels") and caps.get("streamFrame"), caps
    assert caps.get("multiSession"), f"expected multiSession capability: {caps}"

    # Default-session flow (back-compat: no sessionId).
    send({"jsonrpc": "2.0", "id": 2, "method": "render",
          "params": {"scene": scene, "sceneDir": scene_dir}})
    assert pump_until_id(2)["result"]["ok"], "render did not ack ok"

    send({"jsonrpc": "2.0", "id": 3, "method": "xr/updatePanels",
          "params": {"panels": [
              {"id": scene["panels"][0]["id"],
               "poseInRoot": {"translation": {"x": 120, "y": 160, "z": 0},
                              "rotation": {"x": 0, "y": 0, "z": 0, "w": 1}}}]}})
    assert pump_until_id(3)["result"]["ok"], "updatePanels did not ack ok"

    # Multi-session: two named sessions sharing one process / engine.
    send({"jsonrpc": "2.0", "id": 4, "method": "render",
          "params": {"sessionId": "a", "scene": scene, "sceneDir": scene_dir}})
    assert pump_until_id(4)["result"].get("sessionId") == "a", "session a render missing sessionId"
    send({"jsonrpc": "2.0", "id": 5, "method": "render",
          "params": {"sessionId": "b", "scene": scene, "sceneDir": scene_dir}})
    assert pump_until_id(5)["result"].get("sessionId") == "b", "session b render missing sessionId"

    send({"jsonrpc": "2.0", "id": 6, "method": "xr/stop", "params": {"sessionId": "a"}})
    assert pump_until_id(6)["result"]["ok"], "xr/stop a did not ack"
    send({"jsonrpc": "2.0", "id": 7, "method": "xr/stop", "params": {"sessionId": "b"}})
    assert pump_until_id(7)["result"]["ok"], "xr/stop b did not ack"

    send({"jsonrpc": "2.0", "method": "exit"})
    proc.wait(timeout=15)

    assert len(frames) >= 4, f"expected >=4 streamFrames, got {len(frames)}"
    assert frames[0][0] == 1 and frames[1][0] == 2, f"unexpected seq order: {[f[0] for f in frames]}"
    assert frames[0][1] != frames[1][1], "frame did not change after moving a panel"
    # Default-session frames keep the id registered at initialize ("fs1"), not the literal "default".
    assert frames[0][2] == "fs1", f"default frame should carry the initialized id, got {frames[0][2]}"
    sids = {f[2] for f in frames}
    assert "a" in sids and "b" in sids, f"expected per-session frames, got sessionIds {sids}"
    assert proc.returncode == 0, f"server exit code {proc.returncode}"
    print(f"OK: {len(frames)} streamFrames across sessions {sids}; per-frame update changed image")
    return 0


if __name__ == "__main__":
    sys.exit(main())
