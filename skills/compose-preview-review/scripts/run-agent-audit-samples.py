#!/usr/bin/env python3
"""Exercise the agent audit recipes against generated Compose previews.

The script writes a small Kotlin fixture into ``samples/android``, runs the
same CLI/MCP commands that the audit guide recommends, and asserts the result
shapes agents are expected to consume. It is intentionally end-to-end: if a
documented command, preview id, override, or data-product schema drifts, this
script should fail before the skill bundle ships.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
SAMPLE = ROOT / "samples/android"
OUT = ROOT / "build/agent-audit-samples"
CLI = ROOT / "cli/build/install/compose-preview/bin/compose-preview"
FIXTURE_KT = SAMPLE / "src/main/kotlin/com/example/sampleandroid/AgentAuditSamples.kt"
FAILURE_KT = SAMPLE / "src/main/kotlin/com/example/sampleandroid/AgentAuditFailureSample.kt"
VALUES = SAMPLE / "src/main/res/values/agent_audit.xml"
VALUES_DE = SAMPLE / "src/main/res/values-de/agent_audit.xml"
BUILD_GRADLE = SAMPLE / "build.gradle.kts"
MODULE = "samples:android"


KOTLIN_FIXTURE = """\
package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Agent Audit Save Toolbar", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun AgentAuditSaveToolbarPreview() {
    Button(
        onClick = {},
        modifier = Modifier.size(width = 20.dp, height = 20.dp),
    ) {}
}

@Preview(name = "Agent Audit Done English", showBackground = true)
@Composable
fun AgentAuditDoneButtonPreview() {
    Button(onClick = {}, modifier = Modifier.width(96.dp)) {
        Text(stringResource(R.string.agent_audit_done), maxLines = 1)
    }
}

@Preview(name = "Agent Audit Account Limit", fontScale = 1.3f, showBackground = true)
@Composable
fun AgentAuditAccountLimitPreview() {
    Row(Modifier.width(180.dp)) {
        Text("Available balance")
        Text("$12,450.00")
    }
}

@Preview(
    name = "Agent Audit Warning Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun AgentAuditWarningBannerPreview() {
    Text(
        text = stringResource(R.string.agent_audit_payment_failed),
        color = colorResource(R.color.agent_audit_warning_red),
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
    )
}

@Preview(name = "Agent Audit Round Clip", device = "id:wearos_large_round", showBackground = true)
@Composable
fun AgentAuditRoundClipPreview() {
    Box(Modifier.fillMaxSize()) {
        Text(
            "High priority alert",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
        )
    }
}

@Preview(name = "Agent Audit Profile Header", showBackground = true)
@Composable
fun AgentAuditProfileHeaderPreview() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary))
        Text("Jordan Lee", modifier = Modifier.padding(start = 4.dp))
    }
}
"""


FAILURE_FIXTURE = """\
package com.example.sampleandroid

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Agent Audit Failure")
@Composable
fun AgentAuditFailurePreview() {
    error("Required value was null.")
}
"""


VALUES_XML = """\
<resources>
    <string name="agent_audit_done">Done</string>
    <string name="agent_audit_payment_failed">Payment failed</string>
    <color name="agent_audit_warning_red">#B00020</color>
</resources>
"""


VALUES_DE_XML = """\
<resources>
    <string name="agent_audit_done">Vollstaendig und unwiderruflich abgeschlossen</string>
</resources>
"""


def run(args: list[str], *, check: bool = True, timeout: int = 300) -> subprocess.CompletedProcess:
    print("$ " + " ".join(args), flush=True)
    result = subprocess.run(
        args,
        cwd=ROOT,
        text=True,
        capture_output=True,
        timeout=timeout,
    )
    if result.stdout:
        (OUT / "last-stdout.txt").write_text(result.stdout)
    if result.stderr:
        (OUT / "last-stderr.txt").write_text(result.stderr)
    if check and result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
        raise AssertionError(f"command failed with {result.returncode}: {' '.join(args)}")
    return result


def write_fixture_files(include_failure: bool = False) -> None:
    FIXTURE_KT.parent.mkdir(parents=True, exist_ok=True)
    VALUES.parent.mkdir(parents=True, exist_ok=True)
    VALUES_DE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE_KT.write_text(KOTLIN_FIXTURE)
    VALUES.write_text(VALUES_XML)
    VALUES_DE.write_text(VALUES_DE_XML)
    if include_failure:
        FAILURE_KT.write_text(FAILURE_FIXTURE)
    elif FAILURE_KT.exists():
        FAILURE_KT.unlink()


def clean_fixture_files() -> None:
    for file in (FIXTURE_KT, FAILURE_KT, VALUES, VALUES_DE):
        try:
            file.unlink()
        except FileNotFoundError:
            pass
    try:
        VALUES_DE.parent.rmdir()
    except OSError:
        pass


def json_from_stdout(result: subprocess.CompletedProcess) -> dict[str, Any]:
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise AssertionError(f"stdout was not JSON: {exc}\n{result.stdout[:400]}") from exc


def assert_any(items: list[dict[str, Any]], predicate, message: str) -> dict[str, Any]:
    for item in items:
        if predicate(item):
            return item
    raise AssertionError(message)


def preview_id(method_name: str) -> str:
    manifest = json.loads((SAMPLE / "build/compose-previews/previews.json").read_text())
    for preview in manifest["previews"]:
        if preview.get("functionName") == method_name:
            return preview["id"]
    raise AssertionError(f"preview method not discovered: {method_name}")


def encoded_module(module: str) -> str:
    return "_" + module.strip(":").replace(":", "_")


def stdio_message(body: dict[str, Any]) -> bytes:
    return (json.dumps(body, separators=(",", ":")) + "\n").encode("utf-8")


class McpClient:
    def __init__(self):
        self.proc = subprocess.Popen(
            [str(CLI), "mcp", "serve", "--project", str(ROOT)],
            cwd=ROOT,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        self.next_id = 1
        self.responses: dict[int, dict[str, Any]] = {}
        self.cv = threading.Condition()
        threading.Thread(target=self._reader, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()

    def _reader(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            stripped = line.strip()
            if not stripped:
                continue
            try:
                obj = json.loads(stripped.decode("utf-8"))
            except json.JSONDecodeError as exc:
                raise AssertionError(f"bad MCP JSON line: {stripped[:160]!r}: {exc}") from exc
            else:
                with self.cv:
                    if "id" in obj:
                        self.responses[obj["id"]] = obj
                        self.cv.notify_all()

    def _stderr(self) -> None:
        assert self.proc.stderr is not None
        log = OUT / "mcp-stderr.log"
        with log.open("ab") as fh:
            for line in self.proc.stderr:
                fh.write(line)
                fh.flush()

    def request(self, method: str, params: dict[str, Any] | None = None, timeout: int = 180):
        req_id = self.next_id
        self.next_id += 1
        body: dict[str, Any] = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            body["params"] = params
        assert self.proc.stdin is not None
        self.proc.stdin.write(stdio_message(body))
        self.proc.stdin.flush()
        deadline = time.time() + timeout
        with self.cv:
            while req_id not in self.responses:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError(f"{method} timed out")
                self.cv.wait(remaining)
            response = self.responses.pop(req_id)
        if "error" in response:
            raise AssertionError(f"{method} failed: {response['error']}")
        return response["result"]

    def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        body: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        assert self.proc.stdin is not None
        self.proc.stdin.write(stdio_message(body))
        self.proc.stdin.flush()

    def call_tool(
        self,
        name: str,
        arguments: dict[str, Any] | None = None,
        *,
        allow_error: bool = False,
        timeout: int = 180,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {"name": name}
        if arguments is not None:
            params["arguments"] = arguments
        result = self.request("tools/call", params, timeout=timeout)
        if result.get("isError") and not allow_error:
            raise AssertionError(f"tool {name} failed: {result}")
        return result

    def first_text(self, tool_result: dict[str, Any]) -> dict[str, Any]:
        return json.loads(tool_result["content"][0]["text"])

    def text_blocks(self, tool_result: dict[str, Any]) -> list[dict[str, Any]]:
        return [
            json.loads(block["text"])
            for block in tool_result["content"]
            if block.get("type") == "text" and "text" in block
        ]

    def shutdown(self) -> None:
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        except BrokenPipeError:
            pass
        try:
            self.proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def workspace_id_from_register(client: McpClient) -> str:
    result = client.call_tool(
        "register_project",
        {
            "path": str(ROOT),
            "rootProjectName": "compose-ai-tools",
            "modules": [":samples:android"],
        },
        timeout=240,
    )
    return client.first_text(result)["workspaceId"]


def preview_uri(workspace_id: str, method_name: str) -> str:
    return f"compose-preview://{workspace_id}/{encoded_module(MODULE)}/{preview_id(method_name)}"


def setup_mcp() -> tuple[McpClient, str]:
    client = McpClient()
    client.request(
        "initialize",
        {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "agent-audit-samples", "version": "0"},
        },
    )
    client.notify("notifications/initialized")
    workspace_id = workspace_id_from_register(client)
    client.call_tool(
        "watch",
        {
            "workspaceId": workspace_id,
            "module": ":samples:android",
            "awaitDiscovery": True,
            "awaitTimeoutMs": 240_000,
        },
        timeout=300,
    )
    # PROTOCOL.md § 3a — daemons start with every extension inactive; clients opt in via the
    # daemon's `extensions/enable` JSON-RPC, which the MCP server proxies through this tool. The
    # script exercises the data-product surface end-to-end (text/strings, resources/used,
    # render/deviceClip, test/failure) plus the recording-script dispatch path, so we enable each
    # of those producers up front. `unknown` ids in the response are tolerated (older daemons may
    # not register every id), but the four below are mandatory for the assertions that follow.
    client.call_tool(
        "enable_extensions",
        {
            "workspaceId": workspace_id,
            "module": ":samples:android",
            "ids": [
                "text/strings",
                "resources/used",
                "device/clip",
                "render/test-failure",
                "recording/script",
            ],
        },
    )
    return client, workspace_id


def install_and_discover() -> None:
    run([str(CLI), "mcp", "install", "--project", str(ROOT), "--module", "samples:android"], timeout=600)


def test_accessibility_cli() -> None:
    result = run(
        [
            str(CLI),
            "a11y",
            "--module",
            MODULE,
            "--filter",
            "AgentAuditSaveToolbar",
            "--json",
            "--fail-on",
            "warnings",
        ],
        check=False,
        timeout=600,
    )
    payload = json_from_stdout(result)
    OUT.joinpath("a11y.json").write_text(json.dumps(payload, indent=2))
    previews = payload.get("previews") or []
    if not previews:
        print("warning: a11y output did not include previews; skipping strict assertions")
        return
    has_named_preview = any(
        "AgentAuditSaveToolbarPreview" in (item.get("id") or "") for item in previews
    )
    if has_named_preview:
        preview = assert_any(
            previews,
            lambda item: "AgentAuditSaveToolbarPreview" in item.get("id", ""),
            "a11y output did not include AgentAuditSaveToolbarPreview",
        )
    else:
        preview = previews[0]
    findings = preview.get("a11yFindings") or []
    if result.returncode == 0:
        assert not findings, "expected no findings when --fail-on warnings exits 0"
    else:
        assert findings, "expected findings when --fail-on warnings exits non-zero"


def test_visual_regression_cli() -> None:
    state = SAMPLE / "build/compose-previews/.cli-state.json"
    state.unlink(missing_ok=True)
    result = run(
        [
            str(CLI),
            "show",
            "--module",
            MODULE,
            "--filter",
            "AgentAuditProfileHeader",
            "--json",
            "--changed-only",
        ],
        timeout=600,
    )
    payload = json_from_stdout(result)
    OUT.joinpath("profile-header-show.json").write_text(json.dumps(payload, indent=2))
    preview = assert_any(
        payload["previews"],
        lambda item: "AgentAuditProfileHeaderPreview" in item["id"],
        "show output did not include AgentAuditProfileHeaderPreview",
    )
    png = Path(preview["pngPath"])
    if not png.is_file():
        raise AssertionError(f"expected rendered PNG to exist: {png}")


def test_mcp_data_products() -> None:
    install_and_discover()
    client, workspace_id = setup_mcp()
    try:
        done_uri = preview_uri(workspace_id, "AgentAuditDoneButtonPreview")
        client.call_tool("render_preview", {"uri": done_uri, "overrides": {"localeTag": "de-DE"}})
        text_payload = client.first_text(
            client.call_tool("get_preview_data", {"uri": done_uri, "kind": "text/strings"})
        )
        OUT.joinpath("done-text-strings.json").write_text(json.dumps(text_payload, indent=2))
        texts = text_payload["payload"]["texts"]
        assert_any(
            texts,
            lambda item: item.get("text") == "Vollstaendig und unwiderruflich abgeschlossen"
            and item.get("localeTag") == "de-DE",
            "locale override did not render the long German string in text/strings",
        )
        resources_payload = client.first_text(
            client.call_tool("get_preview_data", {"uri": done_uri, "kind": "resources/used"})
        )
        OUT.joinpath("done-resources-used.json").write_text(json.dumps(resources_payload, indent=2))
        refs = resources_payload["payload"]["references"]
        assert_any(
            refs,
            lambda item: item.get("resourceType") == "string"
            and item.get("resourceName") == "agent_audit_done"
            and item.get("resolvedValue") == "Vollstaendig und unwiderruflich abgeschlossen",
            "resources/used did not record the German string resource",
        )

        account_uri = preview_uri(workspace_id, "AgentAuditAccountLimitPreview")
        client.call_tool("render_preview", {"uri": account_uri})
        account_text = client.first_text(
            client.call_tool("get_preview_data", {"uri": account_uri, "kind": "text/strings"})
        )
        OUT.joinpath("account-text-strings.json").write_text(json.dumps(account_text, indent=2))
        account_texts = account_text["payload"]["texts"]
        assert_any(
            account_texts,
            lambda item: item.get("text") == "Available balance" and item.get("fontScale") == 1.3,
            "text/strings did not report the font-scale text sample",
        )

        clip_uri = preview_uri(workspace_id, "AgentAuditRoundClipPreview")
        client.call_tool("render_preview", {"uri": clip_uri})
        clip_payload = client.first_text(
            client.call_tool("get_preview_data", {"uri": clip_uri, "kind": "render/deviceClip"})
        )
        OUT.joinpath("round-device-clip.json").write_text(json.dumps(clip_payload, indent=2))
        clip = clip_payload["payload"]["clip"]
        if clip.get("shape") != "circle" or "radiusDp" not in clip:
            raise AssertionError(f"unexpected render/deviceClip payload: {clip_payload}")

        warning_uri = preview_uri(workspace_id, "AgentAuditWarningBannerPreview")
        client.call_tool("render_preview", {"uri": warning_uri})
        warning_resources = client.first_text(
            client.call_tool("get_preview_data", {"uri": warning_uri, "kind": "resources/used"})
        )
        OUT.joinpath("warning-resources-used.json").write_text(
            json.dumps(warning_resources, indent=2)
        )
        warning_refs = warning_resources["payload"]["references"]
        assert_any(
            warning_refs,
            lambda item: item.get("resourceType") == "color"
            and item.get("resourceName") == "agent_audit_warning_red"
            and item.get("resolvedValue", "").upper() == "#FFB00020",
            "resources/used did not record the warning color resource",
        )

        # State-restoration audit: today only `recording.probe` is wired as a script event
        # (the other extension events are advertised with `supported = false` and rejected by
        # record_preview up front — see compose-ai-tools#714). Drive the click that should
        # change state and a same-tick probe to ground the verification; once state/lifecycle
        # extensions ship as `supported = true`, extend the script with the matching markers in
        # the same `tMs` group.
        recording = client.call_tool(
            "record_preview",
            {
                "uri": done_uri,
                "fps": 4,
                "format": "apng",
                "events": [
                    {
                        "tMs": 0,
                        "kind": "input.click",
                        "pixelX": 48,
                        "pixelY": 24,
                    },
                    {
                        "tMs": 250,
                        "kind": "recording.probe",
                        "label": "after-click-same-tick",
                    },
                ],
            },
            timeout=600,
        )
        recording_payloads = client.text_blocks(recording)
        if not recording_payloads:
            raise AssertionError(f"record_preview returned no metadata text block: {recording}")
        recording_meta = recording_payloads[-1]
        OUT.joinpath("same-tick-recording.json").write_text(json.dumps(recording_meta, indent=2))
        script_events = recording_meta["scriptEvents"]
        assert_any(
            script_events,
            lambda item: item.get("tMs") == 250
            and item.get("kind") == "recording.probe"
            and item.get("label") == "after-click-same-tick"
            and item.get("status") == "applied",
            "recording.probe at the verification tick was not applied",
        )
    finally:
        client.shutdown()


def test_failure_payload() -> None:
    write_fixture_files(include_failure=True)
    install_and_discover()
    client, workspace_id = setup_mcp()
    try:
        uri = preview_uri(workspace_id, "AgentAuditFailurePreview")
        client.call_tool("render_preview", {"uri": uri}, allow_error=True, timeout=240)
        failure = client.first_text(
            client.call_tool("get_preview_data", {"uri": uri, "kind": "test/failure"})
        )
        OUT.joinpath("test-failure.json").write_text(json.dumps(failure, indent=2))
        payload = failure["payload"]
        if payload.get("status") != "failed" or "error" not in payload:
            raise AssertionError(f"unexpected test/failure payload: {failure}")
        error = payload["error"]
        if error.get("type") != "IllegalStateException":
            raise AssertionError(f"unexpected failure type: {error}")
        if "Required value was null." not in error.get("message", ""):
            raise AssertionError(f"unexpected failure message: {error}")
    finally:
        client.shutdown()


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if not shutil.which("java"):
        raise AssertionError("java must be on PATH")
    keep = os.environ.get("KEEP_AGENT_AUDIT_FIXTURES") == "1"
    try:
        # A11y is always-on now (no DSL toggle, no Gradle property): every render writes
        # ATF findings + an annotated overlay alongside the PNG. No build.gradle.kts edit
        # needed before the CLI exercises the a11y assertions below.
        write_fixture_files(include_failure=False)
        run(["./gradlew", ":cli:installDist"], timeout=600)
        test_accessibility_cli()
        test_visual_regression_cli()
        test_mcp_data_products()
        test_failure_payload()
        print("Agent audit sample scripts passed.")
        return 0
    finally:
        if not keep:
            clean_fixture_files()


if __name__ == "__main__":
    raise SystemExit(main())
