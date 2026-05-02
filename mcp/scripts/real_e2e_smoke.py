#!/usr/bin/env python3
"""Real end-to-end smoke for the :mcp module.

Drives a live ``DaemonMcpMain`` JVM over real OS pipes against the live
``:samples:cmp`` desktop daemon to demonstrate the full edit → notify →
re-render → revert flow:

1. ``initialize`` + ``register_project`` + ``watch :samples:cmp``.
2. ``resources/read`` the ``RedBoxPreview`` URI -> save ``before.png``.
3. ``sed`` the source file's ``Text("Red", ...)`` to ``Text("Edited", ...)``.
4. ``./gradlew :samples:cmp:compileKotlin`` so the daemon's user-classloader
   can pick up the new bytecode after the swap.
5. ``notify_file_changed`` MCP tool call -> daemon receives ``fileChanged`` +
   the supervisor re-issues ``renderNow`` for every watched URI.
6. ``resources/read`` again -> save ``after.png``; expected to differ.
7. ``git checkout -- Previews.kt`` to revert; recompile; ``notify_file_changed``
   again; ``resources/read`` -> save ``revert.png``; expected to byte-match
   ``before.png``.

Prerequisites (all live on the host):

- ``./gradlew :samples:cmp:composePreviewDaemonStart``
  has been run.
- The descriptor's ``enabled`` flag must be ``true`` (set
  ``composePreview { daemon { enabled = true } }`` in the build script, or use
  ``sed`` to flip it for this local smoke run).
- ``./gradlew :samples:cmp:discoverPreviews`` has been run so
  ``previews.json`` exists.
- ``./gradlew :mcp:jar :daemon:core:jar`` has been built.
- ``MCP_CP_FILE`` (env var or ``/tmp/mcp-cp.txt``) points at a colon-joined
  classpath containing the mcp jar, daemon-core jar, and required kotlinx
  runtime jars. See the ``classpath_from_gradle_caches`` shell snippet in
  ``mcp/scripts/README.md``.

Runs in ~30s on a warm machine. Mutates and reverts a tracked source file —
only run on a clean tree.
"""
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time

WORKDIR = os.environ.get("MCP_WORKDIR", "/home/user/compose-ai-tools")
PREVIEWS_KT = (
    f"{WORKDIR}/samples/cmp/src/main/kotlin/com/example/samplecmp/Previews.kt"
)


def framed(body: str) -> bytes:
    payload = body.encode("utf-8")
    return f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii") + payload


def parse_frames(buf: bytearray):
    while True:
        idx = buf.find(b"\r\n\r\n")
        if idx < 0:
            return
        header = buf[:idx].decode("ascii", errors="replace")
        m = re.search(r"Content-Length:\s*(\d+)", header)
        if not m:
            return
        n = int(m.group(1))
        body_end = idx + 4 + n
        if len(buf) < body_end:
            return
        body = bytes(buf[idx + 4 : body_end])
        del buf[:body_end]
        try:
            yield json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            print(f"!! bad frame: {body[:80]!r}", file=sys.stderr)


class McpClient:
    def __init__(self, classpath: str):
        self.proc = subprocess.Popen(
            ["java", "-cp", classpath, "ee.schimke.composeai.mcp.DaemonMcpMain"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        self.lock = threading.Lock()
        self.next_id = 1
        self.responses: dict[int, dict] = {}
        self.notifications: list[dict] = []
        self.cv = threading.Condition()
        threading.Thread(target=self._reader, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()

    def _reader(self):
        buf = bytearray()
        while True:
            chunk = self.proc.stdout.read(4096)
            if not chunk:
                return
            buf.extend(chunk)
            for obj in parse_frames(buf):
                with self.cv:
                    if "id" in obj and ("result" in obj or "error" in obj):
                        self.responses[obj["id"]] = obj
                    elif "method" in obj:
                        self.notifications.append(obj)
                    self.cv.notify_all()

    def _stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[mcp] " + line.decode("utf-8", "replace"))

    def request(self, method, params=None, timeout=120):
        with self.lock:
            req_id = self.next_id
            self.next_id += 1
        body = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            body["params"] = params
        self.proc.stdin.write(framed(json.dumps(body)))
        self.proc.stdin.flush()
        deadline = time.time() + timeout
        with self.cv:
            while req_id not in self.responses:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError(f"{method} timed out")
                self.cv.wait(remaining)
            return self.responses.pop(req_id)

    def notify(self, method, params=None):
        body = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        self.proc.stdin.write(framed(json.dumps(body)))
        self.proc.stdin.flush()

    def call_tool(self, name, args=None, timeout=120):
        return self.request(
            "tools/call",
            {"name": name, **({"arguments": args} if args else {})},
            timeout=timeout,
        )

    def read_resource(self, uri, timeout=120):
        return self.request("resources/read", {"uri": uri}, timeout=timeout)

    def shutdown(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        self.proc.wait(timeout=15)


def extract_png(read_response: dict) -> bytes:
    return base64.b64decode(read_response["result"]["contents"][0]["blob"])


def gradle(*targets):
    print(f"  $ ./gradlew {' '.join(targets)} -q", flush=True)
    res = subprocess.run(
        ["./gradlew", *targets, "-q"], cwd=WORKDIR, capture_output=True, text=True
    )
    if res.returncode != 0:
        sys.stderr.write(res.stdout + "\n" + res.stderr + "\n")
        raise RuntimeError(f"gradle {' '.join(targets)} failed")


def main():
    cp_file = os.environ.get("MCP_CP_FILE", "/tmp/mcp-cp.txt")
    cp = open(cp_file).read().strip()

    # Derive the workspace id the same way DaemonMcpServer does — sha256 of canonical path,
    # first 8 hex chars, prefixed by `<rootProjectName>-`.
    canonical = os.path.realpath(WORKDIR)
    short = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:8]
    ws_id = f"compose-ai-tools-{short}"
    preview_uri = (
        f"compose-preview://{ws_id}/_samples_cmp/"
        "com.example.samplecmp.PreviewsKt.RedBoxPreview_Red Box"
    )

    print("=== Step 1: starting MCP server, registering project, watching :samples:cmp ===")
    cli = McpClient(cp)
    cli.request(
        "initialize",
        {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "real-e2e-smoke", "version": "0"},
        },
    )
    cli.notify("notifications/initialized")
    cli.call_tool(
        "register_project",
        {
            "path": WORKDIR,
            "rootProjectName": "compose-ai-tools",
            "modules": [":samples:cmp"],
        },
    )
    cli.call_tool("watch", {"workspaceId": ws_id, "module": ":samples:cmp"})
    time.sleep(2)

    print("=== Step 2: read RedBox BEFORE any edit ===")
    before = extract_png(cli.read_resource(preview_uri))
    open("/tmp/render-before.png", "wb").write(before)
    print(f"  before.png: {len(before)} bytes")

    print("=== Step 3: edit Previews.kt: 'Red' label → 'Edited' ===")
    src = open(PREVIEWS_KT).read()
    edited = src.replace(
        'Text("Red", color = Color.White)', 'Text("Edited", color = Color.White)'
    )
    if edited == src:
        raise RuntimeError("source-edit didn't change anything; check the regex")
    open(PREVIEWS_KT, "w").write(edited)
    gradle(":samples:cmp:compileKotlin")

    print("=== Step 4: notify_file_changed, re-read RedBox ===")
    notif = cli.call_tool(
        "notify_file_changed",
        {"workspaceId": ws_id, "path": PREVIEWS_KT, "kind": "source"},
    )
    print(f"  {notif['result']['content'][0]['text']}")
    time.sleep(1)
    after = extract_png(cli.read_resource(preview_uri))
    open("/tmp/render-after.png", "wb").write(after)
    print(f"  after.png: {len(after)} bytes")

    print("=== Step 5: git checkout to revert + recompile ===")
    subprocess.run(
        ["git", "-C", WORKDIR, "checkout", "--", PREVIEWS_KT], check=True
    )
    gradle(":samples:cmp:compileKotlin")

    print("=== Step 6: notify_file_changed (post-revert), re-read RedBox ===")
    cli.call_tool(
        "notify_file_changed",
        {"workspaceId": ws_id, "path": PREVIEWS_KT, "kind": "source"},
    )
    time.sleep(1)
    revert = extract_png(cli.read_resource(preview_uri))
    open("/tmp/render-revert.png", "wb").write(revert)
    print(f"  revert.png: {len(revert)} bytes")

    print()
    print("=== Verdict ===")
    print(f"  before.sha256 = {hashlib.sha256(before).hexdigest()[:16]}")
    print(f"  after.sha256  = {hashlib.sha256(after).hexdigest()[:16]}")
    print(f"  revert.sha256 = {hashlib.sha256(revert).hexdigest()[:16]}")

    if before != after and after != revert and before == revert:
        print("  PASS: edit produced different bytes; revert restored original.")
        rc = 0
    else:
        print("  FAIL: bytes don't match the expected pattern.")
        rc = 1

    cli.shutdown()
    return rc


if __name__ == "__main__":
    sys.exit(main())
