// GENERATED FILE — DO NOT EDIT.
// Source of truth: schema/xr-render-service.schema.json
// Regenerate: node scripts/codegen/gen-xr-render-service.mjs (CI checks with --check).

package ee.schimke.composeai.renderer.xr.client

/**
 * The JSON-RPC surface the native `xr-composite --serve` process speaks over stdio, framed
 * LSP-style with `Content-Length` headers.
 *
 * This is the RPC boundary between the compositor and its JVM client (`:renderer-xr-client`, which
 * the daemon's XR backend holds). It is a *separate* contract from the SpatialScene data format in
 * `spatial-scene.schema.json`: the scene describes what to draw, this describes how to ask. They
 * version independently, because the compositor is provisioned at a pinned version that
 * deliberately lags the repository (see the `xr-composite` pin in `gradle/libs.versions.toml`), so
 * client and server are routinely built from different commits.
 *
 * Before this schema existed the method names, parameter keys and error codes lived as duplicated
 * string literals in `main.cpp`, `XrServerClient.kt` and `test/serve_smoke.py`. A rename on either
 * side compiled cleanly on both and failed at runtime — silently, because a failed composite is a
 * graceful skip.
 */
public object XrRenderService {
  /**
   * Version of THIS RPC surface, reported as `initialize`'s `serverInfo.version`.
   *
   * Bumped when a method, parameter, result field or error code changes meaning — not when the
   * SpatialScene format changes, which carries its own `SPATIAL_SCENE_VERSION`. A client compares
   * the two independently: it can speak service v1 against a server whose scene format moved, and
   * vice versa.
   */
  public const val XR_RENDER_SERVICE_VERSION: Int = 1

  /**
   * Oldest service version a current client still speaks.
   *
   * The compatibility window exists because the pin makes version skew the NORMAL case, not an
   * error: the compositor is provisioned at a version that deliberately lags, so the server is
   * routinely older than the client. Requiring equality would make bumping this contract a flag day
   * — every render broken until a new compositor is built, published and pinned. So a client
   * accepts a server in [min-supported-version, version] and refuses one NEWER than itself, whose
   * semantics it cannot know. Individual additions are gated by `capabilities` instead, which is
   * what they are for.
   */
  public const val MIN_SUPPORTED_XR_RENDER_SERVICE_VERSION: Int = 1

  /** Value of `initialize`'s `serverInfo.name`. */
  public const val SERVER_NAME: String = "xr-composite"

  /**
   * Session id the server falls back to when a call carries neither `sessionId` nor `frameStreamId`
   * and `initialize` registered no default. Part of the contract: a single-session client that
   * never names a session still has its frames tagged with this, so a client demultiplexing by
   * session id must use the same literal.
   */
  public const val DEFAULT_SESSION_ID: String = "default"

  /** Method names. Each is the exact JSON-RPC `method` string. */
  public object Method {
    /**
     * Handshake. Returns the server's identity and capabilities; the client must check them before
     * assuming any optional behaviour. Also registers the caller's default session id, so a
     * single-session client can omit `sessionId` on every later call.
     */
    public const val INITIALIZE: String = "initialize"
    /**
     * Open or replace the session's scene and camera, then render one frame. Emits a `streamFrame`
     * notification; `out` additionally writes a PNG to disk. Re-creates the session when the
     * viewport size changes.
     */
    public const val RENDER: String = "render"
    /** Accepted alias for [RENDER]. */
    public const val RENDER_ALIAS: String = "xr/render"
    /**
     * Mutate matching panels on an already-open session and re-render. A panel id not already in
     * the scene is appended. Errors with `noSession` when `render` has not opened the session.
     */
    public const val UPDATE_PANELS: String = "xr/updatePanels"
    /**
     * Tear down one session's Filament objects. The shared engine stays up for other sessions. Acks
     * even when the session was already absent, so a client can stop idempotently.
     */
    public const val STOP: String = "xr/stop"
    /**
     * Acks with an empty object. Does not end the loop — send `exit` for that. Mirrors LSP, where
     * `shutdown` and `exit` are separate so a client can wait for the ack before closing the pipe.
     */
    public const val SHUTDOWN: String = "shutdown"
    /** Ends the serve loop. No response is sent, so send it as a notification. */
    public const val EXIT: String = "exit"
  }

  /** Notification names — server-pushed, never answered. */
  public object Notification {
    /**
     * One rendered frame, pushed after every `render` and `xr/updatePanels`. Reuses the daemon's
     * `composestream/1` shape — base64-over-JSON, per that protocol's RFC decision #4.
     */
    public const val STREAM_FRAME: String = "streamFrame"
  }

  /** Request/notification parameter keys, shared across every method that uses them. */
  public object Param {
    /**
     * Session id to treat as the default for calls that omit `sessionId`. Absent means the literal
     * `"default"`.
     */
    public const val FRAME_STREAM_ID: String = "frameStreamId"
    /** The `SpatialScene` to render (see spatial-scene.schema.json). */
    public const val SCENE: String = "scene"
    /**
     * Session to render into. Falls back to `frameStreamId`, then the initialize-registered
     * default.
     */
    public const val SESSION_ID: String = "sessionId"
    /**
     * Directory panel `texture` paths resolve against. Defaults to the process working directory.
     */
    public const val SCENE_DIR: String = "sceneDir"
    /**
     * Backdrop override — a preset name, or `color:#RRGGBB`. Overrides the scene's own
     * `environment`.
     */
    public const val ENVIRONMENT: String = "environment"
    /** Viewport width in px. Defaults to the process-wide `--width`. */
    public const val WIDTH: String = "width"
    /** Viewport height in px. Defaults to the process-wide `--height`. */
    public const val HEIGHT: String = "height"
    /** Optional path to also write the rendered PNG to. */
    public const val OUT: String = "out"
    /** Array of partial panels — `{id, texture?, poseInRoot?, sizeDp?}`. */
    public const val PANELS: String = "panels"
    /** Image container. Always `png` today. */
    public const val ENCODING: String = "encoding"
    /** Monotonic frame counter, shared across all sessions of one process. */
    public const val SEQ: String = "seq"
    /** Base64-encoded image bytes. */
    public const val DATA: String = "data"
  }

  /** Response result keys. */
  public object Result {
    /** `{name, version}` — `name` is always `xr-composite`, `version` is the service version. */
    public const val SERVER_INFO: String = "serverInfo"
    /** See `capabilities` below. */
    public const val CAPABILITIES: String = "capabilities"
    /** Always true on success. */
    public const val OK: String = "ok"
    /** Monotonic frame counter, matching the `streamFrame` just emitted. */
    public const val SEQ: String = "seq"
    /** The session actually rendered, after the fallback chain. */
    public const val SESSION_ID: String = "sessionId"
    /** Viewport width the session rendered at. */
    public const val WIDTH: String = "width"
    /** Viewport height the session rendered at. */
    public const val HEIGHT: String = "height"
  }

  /** Keys of `initialize`'s `capabilities` object. */
  public object Capability {
    /** Server accepts `render` / `xr/render`. Always true. */
    public const val RENDER: String = "render"
    /** Server accepts `xr/updatePanels`. */
    public const val UPDATE_PANELS: String = "updatePanels"
    /** Server pushes `streamFrame` notifications. */
    public const val STREAM_FRAME: String = "streamFrame"
    /**
     * Server fans many `sessionId`s over one shared engine. A client must not open a second session
     * without this.
     */
    public const val MULTI_SESSION: String = "multiSession"
    /**
     * The `SPATIAL_SCENE_VERSION` this server parses. A client sending a different scene version
     * should expect failures.
     */
    public const val SPATIAL_SCENE_VERSION: String = "spatialSceneVersion"
    /** Data-product kinds this server produces — `["xr/composite"]`. */
    public const val DATA_PRODUCTS: String = "dataProducts"
  }

  /** JSON-RPC error codes this service returns. */
  public object ErrorCode {
    /** Request body was not valid JSON. Replied with a null id. */
    public const val PARSE_ERROR: Int = -32700
    /** Method name not recognised. */
    public const val UNKNOWN_METHOD: Int = -32601
    /** Params were rejected — a malformed scene, an unreadable texture, a failed panel update. */
    public const val INVALID_PARAMS: Int = -32602
    /** Session could not be created (Filament init failed). */
    public const val INTERNAL_ERROR: Int = -32603
    /**
     * `xr/updatePanels` addressed a session no `render` had opened. Server-defined, outside the
     * JSON-RPC reserved range.
     */
    public const val NO_SESSION: Int = -32002
  }
}
