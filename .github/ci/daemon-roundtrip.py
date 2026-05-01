#!/usr/bin/env python3
"""End-to-end smoke test for the preview daemon, used by integration.yml.

Reads `<module>/build/compose-previews/daemon-launch.json` (produced by
`./gradlew composePreviewDaemonStart`), spawns the daemon JVM the same way
the VS Code extension's `daemonProcess.ts` does, and drives it over JSON-RPC
through this round-trip:

  initialize → initialized →
  renderNow(all-discovered-previews) → collect renderFinished PNGs →
  edit a source file (append a no-op comment) →
  ./gradlew compileDebugKotlin →
  fileChanged + renderNow(same set) → collect renderFinished PNGs →
  shutdown → exit

Asserts:

* renderFinished arrives for every preview in both render passes.
* Every reported PNG path exists and is non-empty after both passes.
* The PNG files were rewritten by the second pass (mtime advanced).

The mtime check is the proof that the daemon actually re-rendered in
response to the edit. We do NOT assert pixel diffs — a no-op-comment edit
shouldn't change pixels, and pixel parity across recompiles is the
renderer's job, not the daemon's.

No third-party deps; stdlib only so it runs on any GitHub Actions runner
with python3 already available.
"""

from __future__ import annotations

import argparse
import json
import os
import select
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any


# --------- LSP-framed JSON-RPC helpers ----------------------------------


def _frame(payload: dict[str, Any]) -> bytes:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
    return header + body


class FramedReader:
    """Reads LSP-framed JSON-RPC messages from a binary stream."""

    def __init__(self, stream):
        self._stream = stream
        self._buf = bytearray()

    def read_message(self, timeout_s: float) -> dict[str, Any] | None:
        deadline = time.monotonic() + timeout_s
        while True:
            msg = self._try_extract()
            if msg is not None:
                return msg
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            r, _, _ = select.select([self._stream], [], [], min(remaining, 1.0))
            if not r:
                continue
            chunk = os.read(self._stream.fileno(), 4096)
            if not chunk:
                # EOF
                return None
            self._buf.extend(chunk)

    def _try_extract(self) -> dict[str, Any] | None:
        sep = self._buf.find(b"\r\n\r\n")
        if sep < 0:
            return None
        header = bytes(self._buf[:sep]).decode("ascii", errors="replace")
        length = None
        for line in header.split("\r\n"):
            if line.lower().startswith("content-length:"):
                length = int(line.split(":", 1)[1].strip())
        if length is None:
            raise RuntimeError(f"daemon sent header without Content-Length: {header!r}")
        body_start = sep + 4
        body_end = body_start + length
        if len(self._buf) < body_end:
            return None
        body = bytes(self._buf[body_start:body_end])
        del self._buf[:body_end]
        return json.loads(body.decode("utf-8"))


# --------- Daemon driver -----------------------------------------------


class DaemonDriver:
    def __init__(self, descriptor: dict[str, Any], workspace_root: Path):
        self._descriptor = descriptor
        self._workspace_root = workspace_root.resolve()
        self._proc: subprocess.Popen | None = None
        self._reader: FramedReader | None = None
        self._next_id = 1
        # Notifications received but not yet drained by the test logic.
        self._pending_notifications: list[dict[str, Any]] = []

    def spawn(self) -> None:
        java = self._descriptor.get("javaLauncher") or "java"
        sys_props = [f"-D{k}={v}" for k, v in self._descriptor["systemProperties"].items()]
        cp_sep = ";" if os.name == "nt" else ":"
        args = [
            java,
            *self._descriptor["jvmArgs"],
            *sys_props,
            "-cp",
            cp_sep.join(self._descriptor["classpath"]),
            self._descriptor["mainClass"],
        ]
        cwd = self._descriptor["workingDirectory"]
        print(
            f"[daemon-roundtrip] spawning {self._descriptor['mainClass']} "
            f"({len(self._descriptor['classpath'])} classpath entries, java={java})"
        )
        self._proc = subprocess.Popen(
            args,
            cwd=cwd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        self._reader = FramedReader(self._proc.stdout)

        # Drain stderr to a log file in the background so the daemon doesn't
        # block on a full pipe.
        def _drain_stderr() -> None:
            for line in self._proc.stderr:
                sys.stderr.write(f"[daemon stderr] {line.decode('utf-8', errors='replace')}")

        threading.Thread(target=_drain_stderr, daemon=True).start()

    def _send(self, payload: dict[str, Any]) -> None:
        self._proc.stdin.write(_frame(payload))
        self._proc.stdin.flush()

    def request(self, method: str, params: dict[str, Any] | None, timeout_s: float = 60.0) -> dict[str, Any]:
        req_id = self._next_id
        self._next_id += 1
        body: dict[str, Any] = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            body["params"] = params
        self._send(body)
        deadline = time.monotonic() + timeout_s
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"no response to {method} (id={req_id}) within {timeout_s}s")
            msg = self._reader.read_message(remaining)
            if msg is None:
                raise RuntimeError(f"daemon stream closed before responding to {method}")
            if "id" in msg and msg.get("id") == req_id:
                if "error" in msg:
                    raise RuntimeError(f"{method} returned error: {msg['error']}")
                return msg.get("result", {})
            # Stash notifications + other-request responses for later handlers.
            self._pending_notifications.append(msg)

    def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        body: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        self._send(body)

    def collect_notifications(self, methods: set[str], expected: int, timeout_s: float) -> list[dict[str, Any]]:
        """Drains stashed + new notifications until [expected] of [methods] are seen."""
        collected: list[dict[str, Any]] = []
        # First pull anything already stashed.
        remaining = []
        for msg in self._pending_notifications:
            if msg.get("method") in methods:
                collected.append(msg)
            else:
                remaining.append(msg)
        self._pending_notifications = remaining
        deadline = time.monotonic() + timeout_s
        while len(collected) < expected:
            time_left = deadline - time.monotonic()
            if time_left <= 0:
                raise TimeoutError(
                    f"only saw {len(collected)}/{expected} {methods} within {timeout_s}s; "
                    f"got methods so far: {[c.get('method') for c in collected]}"
                )
            msg = self._reader.read_message(time_left)
            if msg is None:
                raise RuntimeError("daemon stream closed mid-collect")
            if msg.get("method") in methods:
                collected.append(msg)
            else:
                self._pending_notifications.append(msg)
        return collected

    def shutdown(self) -> int:
        try:
            self.request("shutdown", None, timeout_s=30.0)
        except Exception as e:
            print(f"[daemon-roundtrip] shutdown request failed (continuing): {e}", file=sys.stderr)
        try:
            self.notify("exit")
        except Exception:
            pass
        try:
            return self._proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self._proc.kill()
            return self._proc.wait(timeout=10)


# --------- Test logic ---------------------------------------------------


def _read_manifest(manifest_path: Path) -> list[dict[str, Any]]:
    data = json.loads(manifest_path.read_text())
    return data.get("previews", [])


def _png_signature(paths: list[Path]) -> dict[str, tuple[int, int]]:
    out: dict[str, tuple[int, int]] = {}
    for p in paths:
        st = p.stat()
        out[str(p)] = (st.st_size, st.st_mtime_ns)
    return out


def _render_pass(driver: DaemonDriver, preview_ids: list[str], reason: str, timeout_s: float) -> list[Path]:
    print(f"[daemon-roundtrip] renderNow({len(preview_ids)} previews) — {reason}")
    queued = driver.request(
        "renderNow",
        {"previews": preview_ids, "tier": "fast", "reason": reason},
        timeout_s=30.0,
    )
    accepted: list[str] = queued.get("queued", [])
    rejected = queued.get("rejected", [])
    if rejected:
        print(f"[daemon-roundtrip] WARN renderNow rejected {len(rejected)}: {rejected}")
    if not accepted:
        raise RuntimeError(f"renderNow queued nothing for ids={preview_ids}")
    notifications = driver.collect_notifications(
        methods={"renderFinished", "renderFailed"},
        expected=len(accepted),
        timeout_s=timeout_s,
    )
    pngs: list[Path] = []
    for n in notifications:
        method = n.get("method")
        params = n.get("params", {})
        if method == "renderFailed":
            raise RuntimeError(f"renderFailed: {params}")
        png = params.get("pngPath")
        if not png:
            raise RuntimeError(f"renderFinished without pngPath: {params}")
        p = Path(png)
        if not p.exists():
            raise RuntimeError(f"renderFinished pngPath does not exist: {p}")
        if p.stat().st_size == 0:
            raise RuntimeError(f"renderFinished pngPath is empty: {p}")
        pngs.append(p)
    return pngs


def _edit_source_file(path: Path, marker: str) -> None:
    """Append a single-line comment with [marker] so the file's content
    actually changes (mtime alone is not enough — ./gradlew compileKotlin
    is up-to-date-aware on content hashes, not just mtime, on some setups)."""
    text = path.read_text()
    if not text.endswith("\n"):
        text += "\n"
    text += f"// {marker}\n"
    path.write_text(text)
    print(f"[daemon-roundtrip] edited {path} (appended marker '{marker}')")


def _gradle_recompile(workspace: Path, gradle_args: list[str]) -> None:
    cmd = ["./gradlew", *gradle_args, "compileDebugKotlin", "--quiet"]
    print(f"[daemon-roundtrip] running: {' '.join(cmd)} (cwd={workspace})")
    res = subprocess.run(cmd, cwd=workspace, check=False)
    if res.returncode != 0:
        raise RuntimeError(f"./gradlew compileDebugKotlin exited {res.returncode}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--descriptor", required=True, help="path to daemon-launch.json")
    ap.add_argument("--manifest", required=True, help="path to previews.json")
    ap.add_argument("--workspace-root", required=True)
    ap.add_argument("--module-id", required=True, help="Gradle path, e.g. :app")
    ap.add_argument(
        "--edit-file",
        help="absolute path to a source file to mutate between render passes; "
        "if omitted, picks the first preview's sourceFile from previews.json",
    )
    ap.add_argument(
        "--gradle-recompile-cwd",
        help="cwd for ./gradlew compileDebugKotlin; defaults to workspace-root",
    )
    ap.add_argument(
        "--gradle-args",
        default="",
        help="extra args to pass to ./gradlew before the task name (e.g. "
        "'--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false')",
    )
    ap.add_argument("--render-timeout-s", type=float, default=180.0)
    args = ap.parse_args()

    descriptor_path = Path(args.descriptor)
    descriptor = json.loads(descriptor_path.read_text())
    if not descriptor.get("enabled"):
        print(
            "[daemon-roundtrip] descriptor.enabled=false — set "
            "composePreview.experimental.daemon.enabled=true. Skipping.",
            file=sys.stderr,
        )
        return 2

    manifest_path = Path(args.manifest)
    previews = _read_manifest(manifest_path)
    if not previews:
        print(f"[daemon-roundtrip] no previews in {manifest_path}", file=sys.stderr)
        return 1
    preview_ids = [p["id"] for p in previews]
    print(f"[daemon-roundtrip] {len(preview_ids)} preview(s) discovered: {preview_ids}")

    edit_target = args.edit_file
    if not edit_target:
        for p in previews:
            sf = p.get("sourceFile")
            if sf and Path(sf).exists():
                edit_target = sf
                break
    if not edit_target:
        print("[daemon-roundtrip] no editable source file found", file=sys.stderr)
        return 1
    edit_path = Path(edit_target)
    print(f"[daemon-roundtrip] will edit {edit_path}")

    workspace = Path(args.workspace_root).resolve()
    recompile_cwd = Path(args.gradle_recompile_cwd or workspace).resolve()
    gradle_args = [a for a in args.gradle_args.split() if a]

    driver = DaemonDriver(descriptor, workspace)
    driver.spawn()
    try:
        init_result = driver.request(
            "initialize",
            {
                "protocolVersion": 1,
                "clientVersion": "ci-roundtrip-0.1",
                "workspaceRoot": str(workspace),
                "moduleId": args.module_id,
                "moduleProjectDir": descriptor["workingDirectory"],
                "capabilities": {"visibility": True, "metrics": True},
            },
            timeout_s=120.0,
        )
        proto = init_result.get("protocolVersion")
        if proto != 1:
            raise RuntimeError(f"daemon protocolVersion={proto}, expected 1")
        print(f"[daemon-roundtrip] initialize OK (daemonVersion={init_result.get('daemonVersion')})")
        driver.notify("initialized")

        # Pass 1: initial render of all previews.
        pngs_before = _render_pass(driver, preview_ids, reason="ci-roundtrip-pass-1", timeout_s=args.render_timeout_s)
        sig_before = _png_signature(pngs_before)
        print(f"[daemon-roundtrip] pass 1 produced {len(pngs_before)} PNG(s)")

        # Edit + recompile + notify daemon.
        marker = f"compose-ai-roundtrip-{int(time.time())}"
        original_text = edit_path.read_text()
        try:
            _edit_source_file(edit_path, marker)
            _gradle_recompile(recompile_cwd, gradle_args)
            driver.notify(
                "fileChanged",
                {
                    "path": str(edit_path.resolve()),
                    "kind": "source",
                    "changeType": "modified",
                },
            )
            # Tier-3 invalidation may emit discoveryUpdated; give it a beat.
            time.sleep(1.0)

            # Pass 2: re-render the same set.
            pngs_after = _render_pass(driver, preview_ids, reason="ci-roundtrip-pass-2", timeout_s=args.render_timeout_s)
            sig_after = _png_signature(pngs_after)
            print(f"[daemon-roundtrip] pass 2 produced {len(pngs_after)} PNG(s)")

            # Assert the daemon actually re-rendered (mtime advanced for at
            # least one of the originally-rendered PNGs).
            advanced = sum(
                1
                for path, (_, mtime_after) in sig_after.items()
                if path in sig_before and mtime_after > sig_before[path][1]
            )
            print(
                f"[daemon-roundtrip] {advanced}/{len(sig_before)} PNG(s) had mtime advance — "
                "expected ≥1 if the file edit re-triggered rendering"
            )
            if advanced == 0:
                # Allow parity with pass 1 if the daemon emitted entirely new
                # paths (some implementations tag the second pass with a fresh
                # outputBaseName); accept that as proof too.
                if not (set(sig_after) - set(sig_before)):
                    raise RuntimeError(
                        "daemon did not re-render any PNG between pass 1 and pass 2"
                    )
                print(
                    f"[daemon-roundtrip]   (pass-2 PNGs are net-new, also acceptable: "
                    f"{len(set(sig_after) - set(sig_before))} new path(s))"
                )
        finally:
            edit_path.write_text(original_text)
            print(f"[daemon-roundtrip] restored {edit_path}")

        print("[daemon-roundtrip] OK")
        return 0
    finally:
        try:
            code = driver.shutdown()
            print(f"[daemon-roundtrip] daemon exited code={code}")
        except Exception as e:
            print(f"[daemon-roundtrip] shutdown failed: {e}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
