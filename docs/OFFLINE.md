# Offline deployment: `COMPOSE_PREVIEW_NO_NETWORK`

A preview bundle carries a project's compiled classes, and a render is a picture of its UI. For a
closed-source repository neither may leave the machine, and "the CLI only fetches what it needs"
is not something a security review can sign off. So the CLI has one switch that turns off every
outbound path it owns, and a supported way to run with everything it would have fetched already on
disk ([#5960](https://github.com/yschimke/compose-ai-tools/issues/5960)).

## The switch

```sh
export COMPOSE_PREVIEW_NO_NETWORK=1          # or: true
# JVM equivalent, e.g. in JAVA_OPTS for the launcher:
-Dcomposeai.network=off
```

With it on:

- **Every outbound path refuses at its entry**, before a socket is opened or a process spawned, and
  `compose-preview` exits 1 with a message of the form
  `<what> needs the network, which COMPOSE_PREVIEW_NO_NETWORK disables. <how to pre-provision it>`.
- **Nothing degrades silently.** Paths that used to fall back (render without XR composites, launch
  without a sidecar, render on a short classpath when a Maven coordinate is missing) fail instead,
  so an incomplete pre-provisioned directory shows up on the first run.
- It **implies the older `COMPOSE_PREVIEW_OFFLINE=1` / `-Dcomposeai.bundle.offline=true`**, which
  only skips fetches and keeps its existing behaviour when the switch is off.
- Loopback (`localhost`, `127.0.0.1`, `::1`) is not outbound. Paths that take a URL you name
  (`--server` for `auth`, a bundle URL, a local OpenAI-compatible model server for `guidelines`)
  still work against this machine.

| Path | With the switch on |
| --- | --- |
| `share-preview` (gist, pushed branch, `--mechanism serve` upload) | Refused before a mechanism is chosen (choosing one runs `gh auth status`). |
| Serve image uploads (`ServeImageUploader`) | Refused unless the host is loopback. |
| `auth` agent access requests | Refused unless the server is loopback. |
| Remote bundle sources (`bundle … https://…`) | Refused; `file://` and paths work. |
| Bundle Maven coordinates (`bundle render`, `bundle daemon`) | Resolved from local repositories only; a miss refuses. |
| Render daemon sidecars (`preview-daemon`), including the Android renderer `bundle render` runs | Used from the cache or `-Dcomposeai.cli.lib…Dir`; otherwise refused. |
| Preview server / MCP server distributions (`serve`, `browse`, `ui-builder`, `mcp serve`) | Used from the cache or `COMPOSE_PREVIEW_SERVER` / `COMPOSE_PREVIEW_MCP`; no newest-release lookup; otherwise refused. |
| Skiko native jar | Used from the cache or `-Dcomposeai.cli.skikoDir`; otherwise refused. |
| XR composite binary | Used from the cache; otherwise refused (it is no longer skipped). |
| GoogleFont downloads | Every daemon/renderer the CLI launches gets `-Dcomposeai.fonts.offline=true`; Gradle gets `-PcomposePreview.fontsOffline=true`. Uncached fonts render with the fallback. |
| Robolectric `android-all` (Android lanes: `bazel`, `bundle render`, `bundle daemon`) | Requires `COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR`; refused without it. |
| The CLI's Gradle invocations | Run with Gradle's `--offline`. |
| `guidelines` with OpenRouter or a remote OpenAI-compatible endpoint, `guidelines models`, a guidelines URL or remote `includes` | Refused. `--backend prompt` and a loopback `--base-url` work. |
| `update` | Refused. |
| `doctor` network probes and update check | Skipped; `doctor` reports `env.network-policy` instead. |

## What to pre-provision

Run once online on a machine with the same CLI version (or unpack the release archives by hand),
then copy the results to the offline machine.

`compose-preview provision` does that run for you: it fetches the render daemons, Skiko's native
library and the `android-all-instrumented` jars matching the daemon's own Robolectric
(`--android-sdk <n>[,<n>]`, default 35), plus the server, MCP server and XR composite with
`--server`, `--mcp`, `--xr` or `--all`, and prints the environment to set (`--json` for a script).

### One cache root

Everything the CLI fetches at run time lives under one directory,
`${XDG_CACHE_HOME:-~/.cache}/composeai/`. Point `XDG_CACHE_HOME` at a provisioned directory and the
CLI reads all of it from there:

| Subdirectory | Holds | Fetched from |
| --- | --- | --- |
| `preview-daemon/<version>/lib-daemon-desktop/`, `lib-renderer/` | Desktop render daemon | `compose-preview-desktop-daemon-<version>.tar.gz` on [compose-preview-daemon releases](https://github.com/yschimke/compose-preview-daemon/releases) |
| `preview-daemon/<version>/lib-daemon-android/` | Android (Robolectric) render daemon | `compose-preview-android-daemon-<version>.zip`, same releases |
| `skiko/<version>/<platform>/skiko-awt-runtime-<platform>-<version>.jar` | Skiko native for this host | Maven Central, `org.jetbrains.skiko` |
| `preview-server/<version>/bin/`, `lib/` | Preview server (`serve`, `browse`, `ui-builder`) | [compose-preview-server releases](https://github.com/yschimke/compose-preview-server/releases) |
| `preview-mcp/<version>/bin/`, `lib/` | MCP server (`mcp serve`) | [compose-preview-mcp releases](https://github.com/yschimke/compose-preview-mcp/releases) |
| `xr-composite/<version>/<platform>/` | XR composite binary and `materials/` (only for XR previews) | xr-composite releases |
| `fonts/` | Downloaded GoogleFonts | Google Fonts |
| `bundle-deps/` | Bundle Maven coordinates fetched earlier | Maven Central, Google Maven |

The daemon `<version>` is the CLI's pin (`COMPOSE_PREVIEW_DAEMON_VERSION` overrides it). Pin the
server with `COMPOSE_PREVIEW_SERVER_VERSION` (and the MCP server with
`COMPOSE_PREVIEW_MCP_VERSION`) so the online warm-up and the offline run agree on a version.

### Overrides for pieces kept elsewhere

| Setting | Points at |
| --- | --- |
| `-Dcomposeai.cli.libDaemonDesktopDir=<dir>/lib-daemon-desktop` | Desktop daemon jars |
| `-Dcomposeai.cli.libRendererDir=<dir>/lib-renderer` | Desktop renderer jars |
| `-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android` | Android daemon jars |
| `-Dcomposeai.cli.skikoDir=<dir>` | A directory holding the Skiko native jar |
| `COMPOSE_PREVIEW_SERVER` / `COMPOSE_PREVIEW_MCP` | A server launcher installed elsewhere |
| `-Dcomposeai.bundle.cacheDir=<dir>` | Where bundle Maven coordinates are cached |

Bundle Maven coordinates are also found in `~/.m2/repository`, `-Dmaven.repo.local` and the Gradle
`modules-2` cache. A bundle packed with `--embed-deps` needs none of them.

### Android: `android-all` and `android.jar`

Robolectric renders Android previews against `android-all-instrumented` jars it normally downloads
from Maven Central on first use. Set `COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR` to a directory holding
those jars; the CLI then launches the daemon with `robolectric.offline=true` and
`robolectric.dependency.dir` pointing there. Under the switch an Android render without it is
refused.

- The jars must match **the Robolectric version inside the render daemon** you provisioned (the
  `lib-daemon-android` jars), and the SDK levels you render at. `compose-preview provision
  --android` asks that Robolectric which jars it needs and fetches them (as `launch` does), from
  Google's mirror of Maven Central first and Central itself second: shared cloud egress addresses
  are rate-limited by Central (HTTP 429), the mirror much less. The alternative is an
  online warm-up render: Robolectric leaves them in
  `~/.m2/repository/org/robolectric/android-all-instrumented/`; copy the `.jar` files (flat) into
  the directory.
- **Do not mix in a repository's own instrumented jars** built for a different Robolectric (for
  example ones your Bazel or Gradle build pins for its unit tests). The published daemon's
  Robolectric loads them by file name and fails, or worse renders against a framework it was not
  instrumented for.

`android.jar` comes from the local SDK: `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `sdk.dir` in
`local.properties`, with a `platforms/android-<N>/android.jar` installed.

### The Gradle path

`compose-preview show` / `render` on a Gradle project run Gradle with `--offline`, so the project's
own dependencies must already be in the Gradle cache. Two fetches happen inside Gradle's test JVMs,
where the CLI cannot refuse them, and need a warm cache instead: Robolectric's `android-all` (into
`~/.m2/repository`, as above) and device-frame art (into `$TMPDIR/compose-preview-device-art`), used
only by previews that ask for a device frame.

## Verifying nothing is fetched

1. Online, warm the caches with the commands you will run offline (for example
   `compose-preview bazel render …` or `compose-preview bundle render <bundle>`), with
   `XDG_CACHE_HOME` pointing at the directory you will ship.
2. Copy that directory (and `COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR` for Android) to the offline
   machine, or keep using the same one.
3. Re-run the same commands with `COMPOSE_PREVIEW_NO_NETWORK=1`. Anything that would still be fetched
   fails loudly and names what to provision; a clean run fetched nothing.
4. `compose-preview doctor` with the switch on reports `env.network-policy`: whether the desktop
   daemon, the Android daemon, the `android-all` directory and the preview server are present. It
   reads disk only and skips its usual network probes.

For a stronger guarantee, also run the offline machine without a route out (a network namespace,
`--network none` in a container, or a firewall); the switch makes the CLI fail fast and explain,
the sandbox proves the result.

## What the switch does not cover

- Your build system. `bazel build` (and Gradle outside the CLI) resolves its own dependencies; run
  it with its own offline configuration.
- Processes the CLI execs but does not own. `serve`, `browse`, `ui-builder` and `mcp serve` launch a
  separately released server; the switch guarantees that launch fetches nothing, not what the
  server does once running.
- Gradle-side fetches inside test JVMs (see [the Gradle path](#the-gradle-path)).
