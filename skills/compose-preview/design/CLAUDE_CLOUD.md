# compose-ai-tools in Claude Code cloud environments

Notes for running this project's tooling inside Claude Code's cloud sandbox
(claude.ai/code / "Claude on the web"). The default network level handles
the common case; Android-consumer builds need one extra step.

## TL;DR

- The released `compose-preview` CLI **runs** in the cloud sandbox out of the
  box — one-step install is `./scripts/install.sh` from a checkout.
- **CMP Desktop / pure-JVM consumers**: works on the default **Trusted**
  network level. No allowlist changes needed.
- **Android consumers** (anything pulling AGP / AndroidX / Robolectric):
  switch the session to **Custom** and add `dl.google.com` and
  `maven.google.com`, with the "include Trusted defaults" checkbox kept on.
  Don't use **Full** — it's broader than needed.
- `scripts/install.sh` auto-detects the Claude Code cloud sandbox (via
  `$CLAUDE_ENV_FILE` / `$CLAUDE_CODE_SESSION_ID`) and handles the two things
  the default image is missing: it apt-installs `openjdk-17-jdk-headless`
  (the Gradle toolchain pin is 17; only 21 is pre-installed) and appends
  `JAVA_HOME` + `PATH` to `$CLAUDE_ENV_FILE` so every subsequent tool
  invocation sees them.
- Put heavy first-time dependency resolution in the **environment setup
  script** so the populated Gradle cache is baked into the snapshot.

## Cloud sandbox network levels

Per the [Claude Code on the web docs](https://code.claude.com/docs/en/claude-code-on-the-web#network-access)
the cloud session has four selectable levels:

| Level | What it is | When to pick it for this project |
| --- | --- | --- |
| **None** | No outbound traffic | Reading rendered PNGs only — no `compose-preview list/show/render`. |
| **Trusted** *(default)* | Allowlisted package registries: `gradle.org`, `services.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org`, `repo1.maven.org`, Maven Central mirrors, plus Spring/JCenter/etc. | CMP Desktop builds, the `compose-preview` CLI itself, anything that resolves only from Maven Central + the Gradle Plugin Portal. |
| **Custom** | Your own allowlist. Toggle "include Trusted defaults" to keep Maven Central etc. on top. | **Android builds** — add `dl.google.com` + `maven.google.com` (AGP/AndroidX). Internal Nexus/Artifactory goes here too. |
| **Full** | Any domain | Only if you don't yet know what you'll hit; over-broad for this project. |

What's **not** on the Trusted defaults and matters here:

- `dl.google.com`, `maven.google.com` — AGP and AndroidX. Required for
  anything that applies `com.android.application` / `com.android.library`,
  including this repo from source (the root `build.gradle.kts` declares the
  AGP plugin even with `apply false`, so resolution happens at config time).
- `repo.gradle.org/gradle/libs-releases` — hosts `gradle-tooling-api`,
  which the `:cli` module depends on. Blocked on Trusted, so building the
  CLI *from source* requires Custom mode. Running the CLI from the release
  tarball doesn't hit this host; `./scripts/install.sh` covers the common
  case without needing it.
- `api.adoptium.net` and friends — Gradle's JDK toolchain auto-provisioning
  endpoints. Ask Gradle to download a JDK and the build fails. Workaround
  below.
- `api.github.com` — rate-limits unauthenticated calls from shared sandbox
  IPs. `scripts/install.sh` deliberately avoids it (uses the public
  `github.com` HTML redirect for version resolution and
  `github.com/.../releases/download/` for the asset).

Pre-installed toolchains: **OpenJDK 21 only.** This project's Gradle build
pins the JVM toolchain to 17, so 17 has to come from the setup script.

## One-step install

`scripts/install.sh` handles everything the Claude Code cloud image is
missing. Auto-detected via `$CLAUDE_ENV_FILE` / `$CLAUDE_CODE_SESSION_ID`
(force with `CLAUDE_CLOUD=1` / `CLAUDE_CLOUD=0`). What it does when it sees
the sandbox:

- `apt-get install -y openjdk-17-jdk-headless` if Java 17 isn't already
  on disk. The pre-installed JDK 21 can't satisfy the project's toolchain
  pin, and Gradle's auto-provisioning is firewalled.
- Downloads the released `compose-preview` tarball from `github.com`
  release assets (skipping `api.github.com`, which rate-limits shared
  sandbox IPs) and symlinks `~/.local/bin/compose-preview` to the launcher.
- Appends `JAVA_HOME` and `PATH` to `$CLAUDE_ENV_FILE` so subsequent tool
  invocations in the session inherit them.

Idempotent: rerunning is a fast no-op once things are in place.

## Trusted mode quickstart

Recipe for getting `compose-preview` running end-to-end on the **default
Trusted** network level — no allowlist changes, no Custom hosts, no Full
access. Works for CMP Desktop / pure-JVM consumer projects. For Android
consumers, follow this same recipe but switch to Custom first (see below).

### Step 1 — Confirm the network level

In the Claude Code web UI for your repo, leave the network access level
at **Trusted** (the default). This covers Maven Central, the Gradle Plugin
Portal, the Gradle distribution download, and GitHub release assets.

### Step 2 — Environment setup script

Paste this into the repo's environment setup script (Claude Code web UI →
Environment → Setup script). It runs once when the environment is built
and the resulting filesystem is cached into the snapshot.

```bash
#!/usr/bin/env bash
set -euo pipefail

# Installs JDK 17 + the compose-preview CLI, writes JAVA_HOME / PATH to
# $CLAUDE_ENV_FILE. See scripts/install.sh for what it actually does.
./scripts/install.sh

# Pre-warm the project's dependency cache so the populated Gradle cache is
# baked into the env snapshot. Tolerate failure — even a failed render
# still leaves the cache populated. Swap in whichever module you render.
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew --no-daemon :sample-cmp:renderAllPreviews || true
```

That's it. No separate SessionStart hook is needed — `install.sh` writes
the env vars once and they persist across sessions via the env snapshot.

### Step 3 — Verify

Open a fresh session and run:

```bash
compose-preview doctor
```

Expected: a `[env]` block showing JDK 17 on PATH, Gradle reachable, and
four `env.network.*` checks (one each for `maven.google.com`,
`dl.google.com`, `fonts.googleapis.com`, `fonts.gstatic.com`). On
**Trusted**, the Google hosts will show as warnings — that's expected if
you only render CMP Desktop / JVM projects. Switch to **Custom** and add
them if you render Android or use downloadable Google Fonts.

The `[project]` block will show either per-module results (if the plugin
is applied somewhere) or "no modules have the compose-preview plugin
applied" if not.

Doctor also flags "no GitHub Packages credentials found" as an error.
**Ignore it** — the plugin is published to Maven Central (on the Trusted
allowlist), so credentials aren't needed. The check predates the Maven
Central migration; tracked in
[issue #161](https://github.com/yschimke/compose-ai-tools/issues/161).

Then drive an actual render against any module with the plugin applied:

```bash
compose-preview list                      # discovery only — no PNG render
compose-preview show --json --brief       # render + JSON paths/hashes
```

If `show` succeeds and prints PNG paths, Trusted mode is fully working
end-to-end. If it fails complaining about `dl.google.com` or `maven.google.com`,
your project pulls AGP/AndroidX — switch to Custom mode.

## Custom mode (Android consumers)

Switch the Claude Code web UI's network level to **Custom**, keep "include
Trusted defaults" on, and add:

- `dl.google.com` — Android SDK cmdline-tools / platform downloads, plus
  Google's fallback Maven mirror
- `maven.google.com` — AGP, AndroidX, Robolectric transitive deps
- `fonts.googleapis.com` + `fonts.gstatic.com` — only if you use
  `androidx.compose.ui:ui-text-google-fonts` at render time

The same `./scripts/install.sh` bootstrap applies. For actual Android
rendering you also need an Android SDK in the env setup script:

```bash
# After ./scripts/install.sh, still in the setup script:
sudo apt-get install -y unzip
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -fsSL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools" >/dev/null
echo "ANDROID_HOME=$ANDROID_HOME"        >> "$CLAUDE_ENV_FILE"
echo "ANDROID_SDK_ROOT=$ANDROID_HOME"    >> "$CLAUDE_ENV_FILE"

./gradlew --no-daemon :sample-android:renderAllPreviews || true
```

## Two known gotchas

Both bite people setting Gradle up in the cloud sandbox for the first time.

### Java's `HttpURLConnection` ignores `https_proxy`

The cloud sandbox routes egress through a proxy and exports `https_proxy`,
but the JVM's built-in HTTP client ignores it. The Gradle wrapper hits this
during initial distribution download; downstream `connection.connect()`
calls in the Tooling API can hit it too.

Two workarounds, pick one:

- **Pre-download** the Gradle distribution in the setup script via `curl`
  (which honors `https_proxy`) and stash it under
  `~/.gradle/wrapper/dists/gradle-<ver>-bin/<hash>/` so the wrapper finds it
  locally.
- **Force the JVM through the proxy** by exporting
  `JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>"` in
  the setup script. See [tschuehly/claude-code-gradle-proxy](https://github.com/tschuehly/claude-code-gradle-proxy)
  for a worked example.

### Toolchain auto-provisioning is blocked

`api.adoptium.net` (and the other toolchain vendor APIs) aren't on the
Trusted allowlist. Any Gradle build that asks for a JDK it doesn't have —
e.g. a Java-25 toolchain in an env that ships JDK 21 — fails with
"Unable to download toolchain". Add the JDK in the setup script (see step
2), or add the vendor APIs via Custom.

## Other caveats

- **Keep rendered previews under 1800px on the longest edge.** Claude's
  cloud session enters a bad state when it's asked to view an image larger
  than that, and recovery usually means restarting the session. Previews
  above the threshold — long scroll captures, stitched `ScrollMode.LONG`
  outputs, high-DPI fan-outs — should be rendered at a smaller size (shrink
  `widthDp`/`heightDp` or the density) or down-scaled before an agent
  reads them. `compose-preview show --brief` only returns paths, so you
  won't trip the limit until something actually loads the PNG.

## Primary sources

- [Claude Code on the web — network access](https://code.claude.com/docs/en/claude-code-on-the-web#network-access)
- [Sandboxing](https://code.claude.com/docs/en/sandboxing.md)
- [Hooks reference — SessionStart](https://code.claude.com/docs/en/hooks.md#sessionstart)
- [anthropics/claude-code#16222 — Gradle wrapper + Java proxy](https://github.com/anthropics/claude-code/issues/16222)
- [tschuehly/claude-code-gradle-proxy](https://github.com/tschuehly/claude-code-gradle-proxy) — `JAVA_TOOL_OPTIONS` workaround
