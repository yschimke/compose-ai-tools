// GENERATED FILE — DO NOT EDIT.
// Source of truth: schema/xr-render-service.schema.json
// Regenerate: node scripts/codegen/gen-xr-render-service.mjs (CI checks with --check).

#pragma once


namespace xrcomposite::xr_render_service {

/**
 * The JSON-RPC surface the native `xr-composite --serve` process speaks over stdio, framed
 * LSP-style with `Content-Length` headers.
 *
 * This is the RPC boundary between the compositor and its JVM client (`:renderer-xr-client`,
 * which the daemon's XR backend holds). It is a *separate* contract from the SpatialScene data
 * format in `spatial-scene.schema.json`: the scene describes what to draw, this describes how to
 * ask. They version independently, because the compositor is provisioned at a pinned version that
 * deliberately lags the repository (see the `xr-composite` pin in `gradle/libs.versions.toml`), so
 * client and server are routinely built from different commits.
 *
 * Before this schema existed the method names, parameter keys and error codes lived as duplicated
 * string literals in `main.cpp`, `XrServerClient.kt` and `test/serve_smoke.py`. A rename on either
 * side compiled cleanly on both and failed at runtime — silently, because a failed composite is a
 * graceful skip.
 */

/**
 * Version of THIS RPC surface, reported as `initialize`'s `serverInfo.version`.
 *
 * Bumped when a method, parameter, result field or error code changes meaning — not when the
 * SpatialScene format changes, which carries its own `SPATIAL_SCENE_VERSION`. A client compares
 * the two independently: it can speak service v1 against a server whose scene format moved, and
 * vice versa.
 */
constexpr int XR_RENDER_SERVICE_VERSION = 1;

/**
 * Value of `initialize`'s `serverInfo.name`.
 */
constexpr const char* kServerName = "xr-composite";

/**
 * Session id the server falls back to when a call carries neither `sessionId` nor
 * `frameStreamId` and `initialize` registered no default. Part of the contract: a
 * single-session client that never names a session still has its frames tagged with this,
 * so a client demultiplexing by session id must use the same literal.
 */
constexpr const char* kDefaultSessionId = "default";

/**
 * Method names. Each is the exact JSON-RPC `method` string.
 */
/**
 * Handshake. Returns the server's identity and capabilities; the client must check them before
 * assuming any optional behaviour. Also registers the caller's default session id, so a
 * single-session client can omit `sessionId` on every later call.
 */
constexpr const char* kMethodInitialize = "initialize";
/**
 * Open or replace the session's scene and camera, then render one frame. Emits a `streamFrame`
 * notification; `out` additionally writes a PNG to disk. Re-creates the session when the
 * viewport size changes.
 */
constexpr const char* kMethodRender = "render";
/**
 * Accepted alias for kMethodRender.
 */
constexpr const char* kMethodRenderAlias = "xr/render";
/**
 * Mutate matching panels on an already-open session and re-render. A panel id not already in the
 * scene is appended. Errors with `noSession` when `render` has not opened the session.
 */
constexpr const char* kMethodUpdatePanels = "xr/updatePanels";
/**
 * Tear down one session's Filament objects. The shared engine stays up for other sessions.
 * Acks even when the session was already absent, so a client can stop idempotently.
 */
constexpr const char* kMethodStop = "xr/stop";
/**
 * Acks with an empty object. Does not end the loop — send `exit` for that. Mirrors LSP, where
 * `shutdown` and `exit` are separate so a client can wait for the ack before closing the pipe.
 */
constexpr const char* kMethodShutdown = "shutdown";
/**
 * Ends the serve loop. No response is sent, so send it as a notification.
 */
constexpr const char* kMethodExit = "exit";

/**
 * Notification names — server-pushed, never answered.
 */
/**
 * One rendered frame, pushed after every `render` and `xr/updatePanels`. Reuses the daemon's
 * `composestream/1` shape — base64-over-JSON, per that protocol's RFC decision #4.
 */
constexpr const char* kNotificationStreamFrame = "streamFrame";

/**
 * Request/notification parameter keys, shared across every method that uses them.
 */
/**
 * Session id to treat as the default for calls that omit `sessionId`. Absent means the literal `"default"`.
 */
constexpr const char* kParamFrameStreamId = "frameStreamId";
/**
 * The `SpatialScene` to render (see spatial-scene.schema.json).
 */
constexpr const char* kParamScene = "scene";
/**
 * Session to render into. Falls back to `frameStreamId`, then the initialize-registered default.
 */
constexpr const char* kParamSessionId = "sessionId";
/**
 * Directory panel `texture` paths resolve against. Defaults to the process working directory.
 */
constexpr const char* kParamSceneDir = "sceneDir";
/**
 * Backdrop override — a preset name, or `color:#RRGGBB`. Overrides the scene's own `environment`.
 */
constexpr const char* kParamEnvironment = "environment";
/**
 * Viewport width in px. Defaults to the process-wide `--width`.
 */
constexpr const char* kParamWidth = "width";
/**
 * Viewport height in px. Defaults to the process-wide `--height`.
 */
constexpr const char* kParamHeight = "height";
/**
 * Optional path to also write the rendered PNG to.
 */
constexpr const char* kParamOut = "out";
/**
 * Array of partial panels — `{id, texture?, poseInRoot?, sizeDp?}`.
 */
constexpr const char* kParamPanels = "panels";
/**
 * Image container. Always `png` today.
 */
constexpr const char* kParamEncoding = "encoding";
/**
 * Monotonic frame counter, shared across all sessions of one process.
 */
constexpr const char* kParamSeq = "seq";
/**
 * Base64-encoded image bytes.
 */
constexpr const char* kParamData = "data";

/**
 * Response result keys.
 */
/**
 * `{name, version}` — `name` is always `xr-composite`, `version` is the service version.
 */
constexpr const char* kResultServerInfo = "serverInfo";
/**
 * See `capabilities` below.
 */
constexpr const char* kResultCapabilities = "capabilities";
/**
 * Always true on success.
 */
constexpr const char* kResultOk = "ok";
/**
 * Monotonic frame counter, matching the `streamFrame` just emitted.
 */
constexpr const char* kResultSeq = "seq";
/**
 * The session actually rendered, after the fallback chain.
 */
constexpr const char* kResultSessionId = "sessionId";
/**
 * Viewport width the session rendered at.
 */
constexpr const char* kResultWidth = "width";
/**
 * Viewport height the session rendered at.
 */
constexpr const char* kResultHeight = "height";

/**
 * Keys of `initialize`'s `capabilities` object.
 */
/**
 * Server accepts `render` / `xr/render`. Always true.
 */
constexpr const char* kCapabilityRender = "render";
/**
 * Server accepts `xr/updatePanels`.
 */
constexpr const char* kCapabilityUpdatePanels = "updatePanels";
/**
 * Server pushes `streamFrame` notifications.
 */
constexpr const char* kCapabilityStreamFrame = "streamFrame";
/**
 * Server fans many `sessionId`s over one shared engine. A client must not open a second session without this.
 */
constexpr const char* kCapabilityMultiSession = "multiSession";
/**
 * The `SPATIAL_SCENE_VERSION` this server parses. A client sending a different scene version should expect failures.
 */
constexpr const char* kCapabilitySpatialSceneVersion = "spatialSceneVersion";
/**
 * Data-product kinds this server produces — `["xr/composite"]`.
 */
constexpr const char* kCapabilityDataProducts = "dataProducts";

/**
 * JSON-RPC error codes this service returns.
 */
/**
 * Request body was not valid JSON. Replied with a null id.
 */
constexpr int kErrorParseError = -32700;
/**
 * Method name not recognised.
 */
constexpr int kErrorUnknownMethod = -32601;
/**
 * Params were rejected — a malformed scene, an unreadable texture, a failed panel update.
 */
constexpr int kErrorInvalidParams = -32602;
/**
 * Session could not be created (Filament init failed).
 */
constexpr int kErrorInternalError = -32603;
/**
 * `xr/updatePanels` addressed a session no `render` had opened. Server-defined, outside the JSON-RPC reserved range.
 */
constexpr int kErrorNoSession = -32002;

}  // namespace xrcomposite::xr_render_service
