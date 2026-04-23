# compose-ai-tools in Claude Code cloud environments

Notes for running this project's tooling inside Claude Code's cloud sandbox
(claude.ai/code / "Claude on the web"). The default network level handles
the common case; Android-consumer builds need one extra step.

## TL;DR

- The released `compose-preview` CLI **runs** in the cloud sandbox out of the
  box — download the tarball from GitHub Releases.
- **CMP Desktop / pure-JVM consumers**: works on the default **Trusted**
  network level. No allowlist changes needed.
- **Android consumers** (anything pulling AGP / AndroidX / Robolectric):
  switch the session to **Custom** and add `dl.google.com` and
  `maven.google.com`, with the "include Trusted defaults" checkbox kept on.
  Don't use **Full** — it's broader than needed.
- Install JDK 17 in the **environment setup script** (cached across sessions)
  and put any heavy first-time dependency resolution there too. The plugin
  pins to JDK 17 and Gradle's toolchain auto-provisioning is blocked.

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
- `api.adoptium.net` and friends — Gradle's JDK toolchain auto-provisioning
  endpoints. Ask Gradle to download a JDK and the build fails. Workaround
  below.

Pre-installed toolchains: **OpenJDK 21 only.** This project's Gradle build
pins the JVM toolchain to 17, so 17 has to come from the setup script.

## What works out of the box (Trusted)

Running the released CLI binary. Nothing it does at startup needs an
off-allowlist host.

```bash
VER=0.7.7  # or whatever the current release is
curl -sL -o /tmp/compose-preview.tar.gz \
  "https://github.com/yschimke/compose-ai-tools/releases/download/v${VER}/compose-preview-${VER}.tar.gz"
tar -xzf /tmp/compose-preview.tar.gz -C /tmp
/tmp/compose-preview-${VER}/bin/compose-preview help
/tmp/compose-preview-${VER}/bin/compose-preview doctor
```

`help` and `doctor` work cleanly. `list` / `show` / `render` shell out to
Gradle against the target project — those succeed only to the extent that
the target project's dependencies are reachable from the chosen network
level. CMP Desktop projects: yes on Trusted. Android projects: needs Custom
with Google Maven added.

## Recommended cloud setup

### 1. Pick the right network level

- **Trusted** for CMP Desktop / JVM-only projects.
- **Custom** + Trusted defaults + `dl.google.com` + `maven.google.com` for
  any Android consumer (and for this repo's `./gradlew :cli:installDist` from
  a fresh clone, since the root build references AGP).

The per-domain allowlist takes effect through the Claude Code web UI.

### 2. Install JDK 17 in the environment setup script

Use the **environment setup script** (Claude Code web UI → Environment →
Setup script) — its filesystem changes are cached into the environment
snapshot, unlike SessionStart hooks which run every session.

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless
```

For projects that pin to a JDK higher than 21, install via SDKMAN in the
same script — Gradle's auto-provisioning won't reach Adoptium from inside
the sandbox.

### 3. Pre-download the CLI via a SessionStart hook

Drop the CLI on `$PATH` at session start. Cached across sessions once the
tarball lands on disk.

`.claude/hooks/install-compose-preview.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
VER=0.7.7
TARGET="$HOME/.local/share/compose-preview"
BIN="$TARGET/compose-preview-$VER/bin/compose-preview"
if [[ ! -x "$BIN" ]]; then
  mkdir -p "$TARGET"
  curl -sL -o /tmp/compose-preview.tar.gz \
    "https://github.com/yschimke/compose-ai-tools/releases/download/v${VER}/compose-preview-${VER}.tar.gz"
  tar -xzf /tmp/compose-preview.tar.gz -C "$TARGET"
fi
echo "PATH=$(dirname "$BIN"):$PATH" >> "$CLAUDE_ENV_FILE"
```

Wire it into `.claude/settings.json`:

```json
{
  "hooks": {
    "SessionStart": [
      { "matcher": "", "hooks": [
        { "type": "command", "command": ".claude/hooks/install-compose-preview.sh" }
      ]}
    ]
  }
}
```

### 4. Pre-warm dependency resolution in the setup script

A first `./gradlew :app:renderAllPreviews` cold-pulls hundreds of MB of
AGP/AndroidX/Robolectric/Compose artifacts. Putting that download into the
setup script bakes the populated Gradle cache into the environment
snapshot — subsequent sessions skip it entirely.

```bash
# In the environment setup script, after the JDK is installed:
./gradlew :app:renderAllPreviews --no-daemon || true
```

Tolerate failure (`|| true`): a failed render still leaves the cache populated.

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
