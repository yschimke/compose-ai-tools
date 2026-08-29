# GENERATED FILE — DO NOT EDIT.
# Source of truth: schema/xr-render-service.schema.json
# Regenerate: node scripts/codegen/gen-xr-render-service.mjs (CI checks with --check).

"""XR render service.

The JSON-RPC surface the native `xr-composite --serve` process speaks over stdio, framed
LSP-style with `Content-Length` headers.

This is the RPC boundary between the compositor and its JVM client (`:renderer-xr-client`,
which the daemon's XR backend holds). It is a *separate* contract from the SpatialScene data
format in `spatial-scene.schema.json`: the scene describes what to draw, this describes how to
ask. They version independently, because the compositor is provisioned at a pinned version that
deliberately lags the repository (see the `xr-composite` pin in `gradle/libs.versions.toml`), so
client and server are routinely built from different commits.

Before this schema existed the method names, parameter keys and error codes lived as duplicated
string literals in `main.cpp`, `XrServerClient.kt` and `test/serve_smoke.py`. A rename on either
side compiled cleanly on both and failed at runtime — silently, because a failed composite is a
graceful skip.
"""

# Version of THIS RPC surface, reported as `initialize`'s `serverInfo.version`.
#
# Bumped when a method, parameter, result field or error code changes meaning — not when the
# SpatialScene format changes, which carries its own `SPATIAL_SCENE_VERSION`. A client compares
# the two independently: it can speak service v1 against a server whose scene format moved, and
# vice versa.
XR_RENDER_SERVICE_VERSION = 1

# Value of `initialize`'s `serverInfo.name`.
SERVER_NAME = "xr-composite"

# Session id the server falls back to when a call carries neither `sessionId` nor
# `frameStreamId` and `initialize` registered no default. Part of the contract: a
# single-session client that never names a session still has its frames tagged with this,
# so a client demultiplexing by session id must use the same literal.
DEFAULT_SESSION_ID = "default"

# Method names. Each is the exact JSON-RPC `method` string.
# Handshake. Returns the server's identity and capabilities; the client must check them before
# assuming any optional behaviour. Also registers the caller's default session id, so a
# single-session client can omit `sessionId` on every later call.
METHOD_INITIALIZE = "initialize"
# Open or replace the session's scene and camera, then render one frame. Emits a `streamFrame`
# notification; `out` additionally writes a PNG to disk. Re-creates the session when the
# viewport size changes.
METHOD_RENDER = "render"
# Accepted alias for METHOD_RENDER.
METHOD_RENDER_ALIAS = "xr/render"
# Mutate matching panels on an already-open session and re-render. A panel id not already in the
# scene is appended. Errors with `noSession` when `render` has not opened the session.
METHOD_UPDATE_PANELS = "xr/updatePanels"
# Tear down one session's Filament objects. The shared engine stays up for other sessions.
# Acks even when the session was already absent, so a client can stop idempotently.
METHOD_STOP = "xr/stop"
# Acks with an empty object. Does not end the loop — send `exit` for that. Mirrors LSP, where
# `shutdown` and `exit` are separate so a client can wait for the ack before closing the pipe.
METHOD_SHUTDOWN = "shutdown"
# Ends the serve loop. No response is sent, so send it as a notification.
METHOD_EXIT = "exit"

# Notification names — server-pushed, never answered.
# One rendered frame, pushed after every `render` and `xr/updatePanels`. Reuses the daemon's
# `composestream/1` shape — base64-over-JSON, per that protocol's RFC decision #4.
NOTIFICATION_STREAM_FRAME = "streamFrame"

# Request/notification parameter keys, shared across every method that uses them.
# Session id to treat as the default for calls that omit `sessionId`. Absent means the literal `"default"`.
PARAM_FRAME_STREAM_ID = "frameStreamId"
# The `SpatialScene` to render (see spatial-scene.schema.json).
PARAM_SCENE = "scene"
# Session to render into. Falls back to `frameStreamId`, then the initialize-registered default.
PARAM_SESSION_ID = "sessionId"
# Directory panel `texture` paths resolve against. Defaults to the process working directory.
PARAM_SCENE_DIR = "sceneDir"
# Backdrop override — a preset name, or `color:#RRGGBB`. Overrides the scene's own `environment`.
PARAM_ENVIRONMENT = "environment"
# Viewport width in px. Defaults to the process-wide `--width`.
PARAM_WIDTH = "width"
# Viewport height in px. Defaults to the process-wide `--height`.
PARAM_HEIGHT = "height"
# Optional path to also write the rendered PNG to.
PARAM_OUT = "out"
# Array of partial panels — `{id, texture?, poseInRoot?, sizeDp?}`.
PARAM_PANELS = "panels"
# Image container. Always `png` today.
PARAM_ENCODING = "encoding"
# Monotonic frame counter, shared across all sessions of one process.
PARAM_SEQ = "seq"
# Base64-encoded image bytes.
PARAM_DATA = "data"

# Response result keys.
# `{name, version}` — `name` is always `xr-composite`, `version` is the service version.
RESULT_SERVER_INFO = "serverInfo"
# See `capabilities` below.
RESULT_CAPABILITIES = "capabilities"
# Always true on success.
RESULT_OK = "ok"
# Monotonic frame counter, matching the `streamFrame` just emitted.
RESULT_SEQ = "seq"
# The session actually rendered, after the fallback chain.
RESULT_SESSION_ID = "sessionId"
# Viewport width the session rendered at.
RESULT_WIDTH = "width"
# Viewport height the session rendered at.
RESULT_HEIGHT = "height"

# Keys of `initialize`'s `capabilities` object.
# Server accepts `render` / `xr/render`. Always true.
CAPABILITY_RENDER = "render"
# Server accepts `xr/updatePanels`.
CAPABILITY_UPDATE_PANELS = "updatePanels"
# Server pushes `streamFrame` notifications.
CAPABILITY_STREAM_FRAME = "streamFrame"
# Server fans many `sessionId`s over one shared engine. A client must not open a second session without this.
CAPABILITY_MULTI_SESSION = "multiSession"
# The `SPATIAL_SCENE_VERSION` this server parses. A client sending a different scene version should expect failures.
CAPABILITY_SPATIAL_SCENE_VERSION = "spatialSceneVersion"
# Data-product kinds this server produces — `["xr/composite"]`.
CAPABILITY_DATA_PRODUCTS = "dataProducts"

# JSON-RPC error codes this service returns.
# Request body was not valid JSON. Replied with a null id.
ERROR_PARSE_ERROR = -32700
# Method name not recognised.
ERROR_UNKNOWN_METHOD = -32601
# Params were rejected — a malformed scene, an unreadable texture, a failed panel update.
ERROR_INVALID_PARAMS = -32602
# Session could not be created (Filament init failed).
ERROR_INTERNAL_ERROR = -32603
# `xr/updatePanels` addressed a session no `render` had opened. Server-defined, outside the JSON-RPC reserved range.
ERROR_NO_SESSION = -32002
