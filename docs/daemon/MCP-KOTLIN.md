# MCP server in Kotlin + Ktor — implementation design

> **Status:** design proposal. Concretes the Option A architecture
> sketched in [MCP.md](MCP.md) — separate Kotlin process, MCP-server
> shim that JSON-RPC-clients the existing daemon. Uses
> [`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk)
> + Ktor for the transport layer. No implementation yet; this captures
> the build shape so an agent (or human) can land it directly.

## Why Ktor + the Kotlin MCP SDK

The MCP SDK ships first-party from the MCP project. It has:

- **Server / Client class abstractions** that handle the JSON-RPC
  envelope, capability negotiation, and notification routing — so we
  don't reimplement framing or schema validation.
- **Transport plug-ins** for stdio, streamable HTTP, SSE, WebSocket,
  and an in-memory `ChannelTransport` used for unit tests.
- **Ktor integration helpers** (`mcpStreamableHttp()`, `mcp { ... }`)
  that mount an MCP server at a configurable path inside an arbitrary
  Ktor application — so the same server jar can run as a stdio
  process for a local agent *or* be embedded in an HTTP server for
  remote agents.
- **Logging + progress + completion APIs** for free — `sendLoggingMessage`,
  progress notifications, URI-template completion.

Building this from scratch on top of `:daemon:core`'s
existing `JsonRpcServer` would mean re-implementing capability
negotiation, paging, sampling, and the wire-shape conformance tests.
The SDK is ~2KB of dep weight and saves a real chunk of work.

Ktor specifically because:

- The existing project already uses Kotlin/JVM end-to-end; another
  Kotlin module fits the build matrix.
- Streamable-HTTP for remote agents needs an HTTP server. Ktor's
  `Application` model is the canonical Kotlin choice; the MCP SDK's
  Ktor helpers expect it.
- Coroutine-native — matches the daemon's existing concurrency
  discipline (no callbacks, no Future juggling).

## Module structure

New top-level module `:daemon:mcp` (mirroring the existing
`:daemon:harness` pattern):

```
daemon/mcp/
  build.gradle.kts
  src/main/kotlin/ee/schimke/composeai/daemon/mcp/
    DaemonMcpMain.kt          ← entry point: stdio by default, --http for HTTP server
    DaemonMcpServer.kt        ← the SDK Server wired with our tools/resources
    DaemonClient.kt           ← thin wrapper that JSON-RPC-clients the daemon JVM
    DaemonSupervisor.kt       ← spawns + lifecycle-manages per-module daemons
    PreviewResource.kt        ← compose-preview:// URI parsing + Resource construction
    Subscriptions.kt          ← maps daemon's renderFinished notifications →
                               notifications/resources/updated for subscribed URIs
    HttpTransport.kt          ← Ktor app for streamable-HTTP mode
  src/test/kotlin/...
    DaemonMcpServerTest.kt    ← uses the SDK's ChannelTransport for in-process testing
    SubscriptionsTest.kt
    StreamableHttpIntegrationTest.kt  ← spawns the server with --http, drives via real HTTP
```

Production deps:

```kotlin
dependencies {
  implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
  implementation(project(":daemon:core"))   // for Messages.kt + JsonRpcServer types
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)               // CIO engine — small, coroutine-native
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
}
```

`:daemon:core` is the only daemon-side dep — same renderer-
agnostic surface invariant that `:daemon:harness` honours. The
shim never depends on `:daemon:android` or
`:daemon:desktop` directly; it spawns the launch descriptor
the same way the harness's `RealHarnessLauncher` does.

## Server bootstrap

`DaemonMcpServer.kt` (sketch):

```kotlin
class DaemonMcpServer(
  private val supervisor: DaemonSupervisor,
  private val subscriptions: Subscriptions,
) {
  fun build(): Server {
    val server = Server(
      serverInfo = Implementation(name = "compose-preview-daemon", version = PluginVersion.value),
      options = ServerOptions(
        capabilities = ServerCapabilities(
          tools = ServerCapabilities.Tools(listChanged = true),
          resources = ServerCapabilities.Resources(
            subscribe = true,         // load-bearing: push notifications
            listChanged = true,       // discoveryUpdated → list_changed
          ),
          logging = ServerCapabilities.Logging(),
        ),
      ),
    )

    server.addTool(
      name = "render_preview",
      description = "Re-render a Compose @Preview by URI; returns the rendered PNG.",
      inputSchema = Tool.Input(
        properties = JsonObject(mapOf(
          "uri" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
          "tier" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonArray(listOf(JsonPrimitive("fast"), JsonPrimitive("full"))),
          )),
        )),
        required = listOf("uri"),
      ),
    ) { request ->
      val uri = request.arguments?.get("uri")?.jsonPrimitive?.content
        ?: return@addTool errorResult("missing uri")
      val tier = request.arguments?.get("tier")?.jsonPrimitive?.content ?: "full"
      val pngBytes = supervisor.renderNow(PreviewUri.parse(uri), tier)
      CallToolResult(content = listOf(ImageContent(data = pngBytes.encodeBase64(), mimeType = "image/png")))
    }

    server.addResourceProvider { listResources, readResource, subscribe, unsubscribe ->
      // listResources: aggregates `discoverPreviews` across every supervised daemon
      // readResource:  triggers `renderNow` if URI's PNG is stale; returns blob
      // subscribe:     calls `subscriptions.subscribe(uri, server)` so renderFinished
      //                pushes notifications/resources/updated
      // unsubscribe:   inverse
    }

    // Hook the supervisor's classpathDirty handler to send a logging message
    // before exit; clients see "module classpath changed" → reconnect.
    supervisor.onClasspathDirty { module, reason ->
      server.sendLoggingMessage(
        level = LoggingLevel.warning,
        data = JsonObject(mapOf("module" to JsonPrimitive(module), "reason" to JsonPrimitive(reason))),
      )
      // The supervisor handles the actual respawn; the server stays up.
    }

    return server
  }
}
```

The `Server` instance is then attached to a transport in
`DaemonMcpMain`:

```kotlin
fun main(args: Array<String>) {
  val httpPort = args.firstOrNull { it.startsWith("--http=") }?.removePrefix("--http=")?.toInt()
  val supervisor = DaemonSupervisor(workspaceRoot = File(System.getProperty("user.dir")))
  val subscriptions = Subscriptions()
  val server = DaemonMcpServer(supervisor, subscriptions).build()

  if (httpPort == null) {
    // Local agent — stdio. The supervisor's daemons live in the same process tree.
    val transport = StdioServerTransport()
    runBlocking {
      server.connect(transport)
      transport.run()
    }
  } else {
    // Remote agent — Ktor streamable-HTTP. Same server, different transport.
    embeddedServer(CIO, port = httpPort) {
      install(ContentNegotiation) { json(McpJson) }
      mcp { server }                  // SDK helper from io.modelcontextprotocol:kotlin-sdk
    }.start(wait = true)
  }
}
```

## Tools surface (v0)

| Tool | Purpose | Maps to daemon's |
|---|---|---|
| `render_preview(uri, tier)` | Force a render, bypassing cache. Returns PNG bytes inline. | `renderNow` |
| `discover_previews(module)` | Explicit list-now for a module. Returns a list of preview URIs + display names. Mostly redundant with `resources/list`. | `initialize` + `discoveryUpdated` |
| `list_modules()` | What modules have a live daemon. | `DaemonSupervisor`'s in-memory state |

`set_focus`, `set_visible`, etc. — deferred to v1+. Reactive priority
falls out naturally from "most recent `resources/read` is highest
priority"; explicit tools only earn their keep once an agent loop
shows the implicit heuristic isn't enough.

## Resources surface

URI scheme: `compose-preview://<module>/<preview-fqn>?config=<qualifier>`.

`PreviewResource.kt`:

```kotlin
data class PreviewUri(val module: String, val previewFqn: String, val config: String?) {
  fun toUri(): String =
    "compose-preview://${module.removePrefix(":")}/$previewFqn" +
      if (config != null) "?config=$config" else ""

  companion object {
    fun parse(s: String): PreviewUri = ...   // strict parser; rejects malformed URIs
  }
}

fun PreviewUri.toMcpResource(displayName: String, lastModified: Instant?, sizeBytes: Long?): Resource =
  Resource(
    uri = toUri(),
    name = previewFqn.substringAfterLast('.'),
    title = displayName,
    description = "Compose @Preview rendered by the preview daemon",
    mimeType = "image/png",
    size = sizeBytes,
    annotations = ResourceAnnotations(
      audience = listOf(ResourceAudience.user, ResourceAudience.assistant),
      priority = 0.5,
      lastModified = lastModified,
    ),
  )
```

`resources/list` aggregates across every supervised module:
`supervisor.allDaemons.flatMap { it.previews().map { it.toMcpResource(...) } }`.

`resources/read` parses the URI, dispatches to the right daemon,
checks freshness, blocks on `renderFinished`, returns the bytes as a
`BlobResourceContents` (base64-encoded PNG).

## Subscriptions — the push path

`Subscriptions.kt` is the load-bearing translation layer.

```kotlin
class Subscriptions {
  private val byUri = ConcurrentHashMap<String, MutableSet<ServerSession>>()

  fun subscribe(uri: String, session: ServerSession) {
    byUri.compute(uri) { _, set -> (set ?: mutableSetOf()).also { it.add(session) } }
  }

  fun unsubscribe(uri: String, session: ServerSession) { ... }

  /** Called by DaemonSupervisor when any daemon emits renderFinished. */
  suspend fun notifyUpdated(uri: String) {
    byUri[uri]?.forEach { session ->
      session.sendResourceUpdated(uri)   // SDK helper that emits notifications/resources/updated
    }
  }

  /** Called when discoveryUpdated arrives — the *set* of resources changed. */
  suspend fun notifyListChanged() {
    byUri.values.flatten().toSet().forEach { session ->
      session.sendResourceListChanged()
    }
  }
}
```

The `DaemonSupervisor` hooks the daemon's existing notification stream:

```kotlin
class DaemonSupervisor(...) {
  fun onRenderFinished(handler: suspend (uri: String) -> Unit) { ... }
  fun onDiscoveryUpdated(handler: suspend (module: String) -> Unit) { ... }
  fun onClasspathDirty(handler: suspend (module: String, reason: String) -> Unit) { ... }
  // Implementation: each daemon's stdio JSON-RPC notification stream is
  // demuxed by method name and dispatched to registered handlers.
}
```

`DaemonMcpServer.build()` wires:

```kotlin
supervisor.onRenderFinished { uri -> subscriptions.notifyUpdated(uri) }
supervisor.onDiscoveryUpdated { module -> subscriptions.notifyListChanged() }
supervisor.onClasspathDirty { module, reason ->
  server.sendLoggingMessage(level = LoggingLevel.warning, data = ...)
  // supervisor handles respawn internally
}
```

That's the entire push story: existing daemon notification → MCP
notification, with the URI as the join key.

## Multi-module multiplexing

`DaemonSupervisor` owns a `Map<ModulePath, DaemonClient>`. On first
`render_preview` for a new module, it:

1. Runs `composePreviewDaemonStart` for that module via Gradle (using
   the existing Tooling API path the harness's launchers use).
2. Spawns the daemon JVM from the emitted descriptor.
3. Wires the daemon's stdio notification stream into the supervisor's
   handlers.
4. Caches the `DaemonClient` keyed by module path.

`list_modules()` returns the cache's keys. Daemons that go quiet are
torn down lazily after `daemon.idleTimeoutMs` (matches the per-daemon
idle policy from PROTOCOL.md § 3).

The supervisor is the only piece that touches Gradle. Everything
downstream (the MCP server, the subscription routing, the resource
provider) is JSON-RPC-over-stdio against an opaque `DaemonClient`.

## Transports

### Stdio — the default for local agents

```bash
$ claude mcp add compose-preview ./gradlew :daemon:mcp:run
```

`StdioServerTransport()` from the SDK. Server's stdin/stdout speaks
MCP; logs go to stderr. The supervisor's spawned daemons inherit
stderr-redirection from the SDK's stdio plumbing, so daemon logs are
visible in the parent's stderr without corrupting the wire.

### Streamable-HTTP — for remote / multi-agent setups

```bash
$ ./gradlew :daemon:mcp:run --args='--http=8080'
```

Ktor `embeddedServer(CIO, port = 8080) { mcp { server } }`. The MCP
SDK's `mcp { ... }` route DSL handles the streamable-HTTP transport's
single-endpoint pattern (one URL accepts JSON or SSE depending on the
client's `Accept` header). Ktor handles connection management,
content-negotiation, and the SSE keepalive.

This unlocks **remote agents** — an agent running on a different
machine can connect over HTTP, render previews via the daemon, and
receive subscription notifications via SSE. CLI can't do this without
a network wrapper.

### In-memory — for unit tests

```kotlin
class DaemonMcpServerTest {
  @Test
  fun `subscribed client receives notifications/resources/updated when daemon renders`() = runTest {
    val (clientTransport, serverTransport) = ChannelTransport.pair()
    val supervisor = FakeDaemonSupervisor()
    val server = DaemonMcpServer(supervisor, Subscriptions()).build()
    server.connect(serverTransport)

    val client = Client(...)
    client.connect(clientTransport)
    client.subscribe("compose-preview://samples-android/RedSquare")

    val notification = client.expectNotification("notifications/resources/updated", timeout = 5.seconds)
    supervisor.simulateRenderFinished("compose-preview://samples-android/RedSquare")

    assertEquals("compose-preview://samples-android/RedSquare", notification.params["uri"])
  }
}
```

The `ChannelTransport` from the SDK is in-memory — no subprocess, no
file I/O, no port allocation. Same SDK code path the production stdio
transport uses, just bound to a Kotlin `Channel` instead of stdin /
stdout.

## What this provides that the CLI can't

The existing `compose-preview` CLI (one-shot render via Gradle):

| Capability | CLI | MCP server |
|---|---|---|
| **Push on render-complete** | ❌ Process exits; no channel for notifications | ✅ `notifications/resources/updated` over the persistent stdio/HTTP connection |
| **Push on discovery change** | ❌ Re-running discovery means re-running the CLI | ✅ `notifications/resources/list_changed` |
| **Sub-second warm renders** | ❌ Cold Gradle config + sandbox init every invocation (~5–10s) | ✅ Daemon stays warm; second render is ~50ms |
| **Subscription back-pressure** | ❌ No subscription concept | ✅ Client subscribes to specific URIs; server tracks who wants what |
| **Multi-module multiplexing** | ❌ One CLI call → one Gradle module | ✅ Supervisor multiplexes per-module daemons behind one MCP surface |
| **Cross-render cache** | ❌ Each invocation starts fresh | ✅ Daemon caches PNGs; freshness driven by `fileChanged` (B2.0/B2.1) |
| **Progress notifications** | ❌ stdout/stderr text; client parses | ✅ `notifications/progress` typed events for long renders |
| **Structured failures** | ❌ Non-zero exit + stderr text | ✅ `renderFailed` → typed `notifications/message({ level: "error", data: { kind, message, stackTrace } })` |
| **URI-template completion** | ❌ Client has to know preview FQNs upfront | ✅ MCP completion API — agent can ask "what previews exist matching pattern X?" |
| **Capability negotiation** | ❌ All-or-nothing CLI flags | ✅ Client declares which features it supports (`subscribe`, `listChanged`); server tailors notifications |
| **Remote / cross-machine** | ❌ CLI is local-only | ✅ Streamable-HTTP transport lets remote agents render previews |
| **Multiple concurrent agent sessions** | ❌ One CLI per call | ✅ One server, many connected agents (sharing the same warm daemons) |
| **Sampling / elicitation** | ❌ One-shot input/output | ✅ Server can ask the client for input mid-render (e.g. "this preview has 3 device variants — which?"). Future hook; not v1. |
| **Resource priority annotations** | ❌ No annotations | ✅ `audience: ["assistant"]` lets the server hint which previews are most relevant for an agent's context |
| **Image bytes inline** | ❌ Writes PNG to disk; client reads it | ✅ `BlobResourceContents` returns base64 PNG inline; no file-system juggling |

The four most load-bearing wins:

1. **Subscriptions** — the user's original question. Push notifications
   replace polling. An agent rendering a preview while a developer
   edits gets fresh bytes pushed at it within tens of milliseconds of
   `fileChanged`.
2. **Persistent warm sandbox** — sub-second renders compound across an
   agent session. CLI's 10s cold-start is a session killer.
3. **Multi-module multiplexing** — agent sees the whole project as one
   resource list, not "rerun the CLI for each module".
4. **Streamable-HTTP** — remote / cross-machine setups become
   first-class. Useful for hosted-agent setups where the daemon JVM
   runs on a build server but the agent loop runs in the cloud.

## Lifecycle + supervision

The MCP server's lifecycle owns its supervised daemons, not the
client's. Three explicit transitions:

- **MCP server start**: connects transport, registers tools/resources,
  doesn't spawn any daemons yet (lazy on first preview request).
- **First preview request for a module**: supervisor checks if a
  daemon for that module exists; if not, runs
  `composePreviewDaemonStart` + spawns. Cold path; ~5s.
- **Idle module**: supervisor reaps daemons whose last-render is older
  than `daemon.idleTimeoutMs`. Reaped daemon's URIs become
  "list-but-not-readable-without-respawn"; next `resources/read` for
  one of those URIs respawns the daemon (lazy).
- **MCP server shutdown**: gracefully shuts every supervised daemon
  (sends `shutdown` + `exit`, awaits drain, checks SIGTERM handler
  result), then exits.
- **classpathDirty**: supervisor handles transparently — daemon exits,
  supervisor respawns, MCP server stays up. Pushes a logging message
  so the agent sees the transition.

## Phasing

**v0** — stdio only. Tools: `render_preview`. Resources: list + read,
no subscriptions. Single-module supervisor. Goal: one MCP-aware agent
can render previews via the existing daemon. No new agent capabilities
yet — this is the wiring proof.

**v1** — subscriptions. Resource subscribe/unsubscribe; `renderFinished`
→ `notifications/resources/updated`; `discoveryUpdated` →
`notifications/resources/list_changed`. The push story. Add
`discover_previews` and `list_modules` tools.

**v2** — Ktor streamable-HTTP transport. Remote agents work. Auth /
TLS / rate-limiting decisions land here as a sibling design (out of
scope for v0/v1).

**v3** — sampling/elicitation hooks for cases where the server wants
to ask the client mid-render (e.g. preview-parameter selection).
Speculative; gate on actual demand.

**v4** — agent-driven priority via `set_focus` tool. Gate on observed
agent-loop patterns; the implicit "most-recent-read = highest
priority" heuristic may be enough.

## Risks

1. **MCP SDK is young** (Kotlin SDK 0.x as of this writing). Wire-shape
   stability across SDK upgrades is not guaranteed; the shim should
   pin the SDK version and bump deliberately.
2. **PNG bytes over stdio are base64**. A 1MB PNG = ~1.4MB on the
   wire. For an agent rendering a few previews per minute, fine. For
   bulk renders (e.g. an agent reviewing a 200-preview module), the
   streamable-HTTP transport has fewer constraints.
3. **Per-module daemon spawn cost (~5s)** is paid lazily on first
   request per module per MCP-server lifetime. Not amortised across
   different agent sessions if the MCP server is per-session.
   Alternative: long-lived MCP server, multiple agents share the same
   warm daemon pool. v2 territory.
4. **Capability negotiation with old clients**. Clients that don't
   support `subscribe` get cold reads on every `resources/read` —
   correct behaviour, just slower. Should be documented in the
   user-facing MCP server README.
5. **Daemon classpath updates** mid-agent-session require the agent
   to handle classpathDirty respawns. The MCP server hides this with
   the "logging message + supervisor respawn" pattern, but the next
   resource-read after respawn could hit a stale subscription
   (subscriptions tracked by URI; URIs survive respawn unless the
   set of previews changed). Solution: on respawn, supervisor re-emits
   `notifications/resources/list_changed` so subscribed clients
   re-list. Documented inline.

## Decisions to surface

1. **Module name**: `:daemon:mcp` (recommended; mirrors
   `:daemon:harness`) vs `:tools:mcp-server` (less specific but
   future-proofs for non-daemon MCP needs). Recommend `:daemon:mcp`.
2. **Ktor engine**: CIO (smaller, coroutine-native) vs Netty (more
   mature). Recommend CIO; daemon-side traffic is tiny.
3. **Tool naming**: `render_preview` (snake_case, MCP convention) vs
   `renderPreview` (camelCase, Kotlin convention). MCP convention wins
   per public-facing tool surface.
4. **URI scheme**: confirm `compose-preview://` is fine (vs `file://`).
   Recommend custom; covered in MCP.md decision 2.
5. **Spawn responsibility**: MCP server spawns daemons via the
   supervisor (recommended) vs MCP server requires the daemons to be
   already-running. Recommended path means the agent doesn't need any
   pre-flight setup; trade-off is the supervisor has to know how to
   invoke Gradle (Tooling API or shell-out).
6. **HTTP auth model for v2**: bearer-token? mTLS? OAuth (since MCP
   spec has hooks for it)? Defer; gate on actual remote-agent demand.

## Cross-references

- [MCP.md](MCP.md) — high-level design; this doc is the
  Option-A-implementation specifics.
- [PROTOCOL.md](PROTOCOL.md) — the daemon's wire format the MCP shim
  translates from.
- [DESIGN.md § 4](DESIGN.md#4-architecture) — daemon architecture; MCP
  server is one of the consumers enumerated there.
- [TEST-HARNESS.md](TEST-HARNESS.md) — pattern to follow for an MCP
  integration test that spawns the server as a subprocess and drives
  it with a real client (extends to MCP via the SDK's `Client` class).
- [io.modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
  — the SDK; pin a version once we start work.
