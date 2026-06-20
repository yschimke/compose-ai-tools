// Shared client core for the mobile + wear "session viewer" apps — the engine that connects to a
// `compose-preview serve` streamed-frame lane (`WS /ws/{previewId}`, see PR #1989 /
// `cli/.../serve/ServeStreamProtocol.kt`), decodes pushed PNG frames, forwards pointer/key input,
// and parses the shareable session link a user taps to open an app.
//
// Pure JVM (no Android, no Compose) so the protocol + session state machine are unit-tested
// headlessly with a fake transport — the Android/Wear apps add only the platform shell (Activity,
// Compose canvas, OkHttp/NsdManager wiring) on top. Mirrors the renderer-agnostic split the daemon
// uses (`:daemon:core` is plain JVM; the backends add the platform).
plugins {
  id("composeai.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // The streamed-frame wire shape is JSON (`frame` / `error` server→client, `setOverrides` /
  // `requestFrame` / `input` client→server). `api` so the app modules can build/parse messages
  // without re-declaring serialization.
  api(libs.kotlinx.serialization.json)

  // `SessionClient` exposes its frame + state surface as coroutine `StateFlow`s.
  api(libs.kotlinx.coroutines.core)

  // The default transport is a Ktor WebSocket client over the OkHttp engine — Ktor for the
  // multiplatform-friendly WS API, OkHttp for the battle-tested engine that runs on the JVM and
  // Android alike. One stack serves the tests, the mobile app, and the wear app. `api` so the apps
  // can pass a pre-configured `HttpClient` (proxy, TLS, auth) if they need to.
  api(libs.ktor.client.core)
  api(libs.ktor.client.okhttp)
  api(libs.ktor.client.websockets)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.coroutines.core)
}
