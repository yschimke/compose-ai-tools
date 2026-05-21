# Amper Android sample — non-Gradle Android integration fixture

A tiny [Amper](https://amper.org) Android project sibling to
[`amper-cmp-desktop/`](../amper-cmp-desktop/). Proves the build system
can produce an Android APK with a `@Composable @Preview` function and a
`@NotificationPreview` function, without a single line of Gradle.

| Source | Path |
| --- | --- |
| Hello-world activity | [`src/MainActivity.kt`](src/MainActivity.kt) |
| `@Composable @Preview` | [`src/Greeting.kt`](src/Greeting.kt) |
| `@NotificationPreview` | [`src/Notifications.kt`](src/Notifications.kt) |
| Build declaration | [`module.yaml`](module.yaml) |
| Wrapper (pinned to Amper 0.10.0) | [`amper`](amper) / [`amper.bat`](amper.bat) |

## Running the fixture

```bash
cd contrib/amper-android

# Sandbox / managed environments with a TLS-inspection proxy: the
# bundled Zulu JRE Amper auto-downloads doesn't trust the proxy CA, so
# Maven Central / Google Maven fetches fail with PKIX errors. Wire the
# system truststore (which the sandbox keeps current) explicitly:
export AMPER_JAVA_OPTIONS="-ea -XX:+EnableDynamicAgentLoading \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Djavax.net.ssl.trustStorePassword=changeit"

./amper build
```

Cold start downloads Amper + the Android SDK image into Amper's cache;
subsequent builds are fast.

## Why the `@NotificationPreview` annotation is redeclared

The `@NotificationPreview` in [`src/Notifications.kt`](src/Notifications.kt)
is declared private inside the file rather than imported from the
repo's `:preview-annotations` Gradle module. The fixture's whole point
is to demonstrate a project layout that *doesn't* know about Gradle;
pulling a Gradle module in as a Maven coordinate would muddy the
demonstration. The canonical annotation lives at
[`preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt`](../../preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt);
a downstream Amper user wiring up the full compose-preview pipeline
would consume the published artifact (FQN match is what the discovery
side keys off).

## What's NOT here

- No `compose-preview` Gradle plugin integration — discovery, rendering,
  baseline diffs all live behind the Gradle plugin and aren't reachable
  from a pure Amper module.
- No `roborazzi` / unit tests.
- No `:preview-annotations` dependency (see above).
- No multi-locale resources, vector / adaptive-icon assets — that surface
  is exercised by [`samples/android/`](../../samples/android/) under Gradle.

## Why `jvm.release: 17`

Mirrors the rationale in
[`amper-cmp-desktop/README.md`](../amper-cmp-desktop/README.md#why-the-fixture-overrides-jvmrelease):
compose-ai-tools' toolchain is pinned to JDK 17. The Amper default of
JDK 21 emits class files that can't load into the project-wide daemon.

## Why `compileSdk: 35`

The project's main Android conventions plugin sets `compileSdk = 36`,
but `compileSdk = 36` requires JDK 21+ on some toolchains. The fixture
pins 35 to stay buildable under the project's JDK 17. Bump when the
project moves to JDK 21.
