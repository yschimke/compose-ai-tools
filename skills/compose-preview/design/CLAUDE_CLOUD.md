# compose-ai-tools in Claude Code cloud environments

Notes for running this project's tooling inside Claude Code's cloud sandbox
(claude.ai/code / "Claude on the web"). The sandbox has a restrictive default
network allowlist and a single pre-installed JDK — enough surprises to be
worth documenting.

## TL;DR

- The released `compose-preview` CLI **runs** in the cloud sandbox out of the
  box — download the tarball from GitHub Releases.
- Building this repo (or any AGP-based consumer project) from source **does
  not** work with the default allowlist: `dl.google.com` / `maven.google.com`
  are blocked, so AGP can't resolve.
- To build from source, switch the session to "Full network access".

## Observed sandbox constraints

Probed from a standard cloud session on 2026-04-23:

| Host | Status | Used for |
| --- | --- | --- |
| `repo1.maven.org`, `repo.maven.apache.org` | allowed | Maven Central |
| `plugins.gradle.org` | allowed | Gradle plugin portal |
| `services.gradle.org` | allowed | Gradle distribution download |
| `github.com`, `*.githubusercontent.com` | allowed | Release assets, source |
| `kotlinlang.org` | allowed | Kotlin |
| `dl.google.com`, `maven.google.com` | **403 `host_not_allowed`** | AGP, AndroidX |
| `android.googlesource.com` | **403 `host_not_allowed`** | AOSP sources |
| `repo.gradle.org` | **403 `host_not_allowed`** | `gradle-tooling-api` 9.x |

Pre-installed toolchains: **OpenJDK 21 only.** This project's Gradle build
pins the JVM toolchain to 17, and auto-provisioning fails because the JDK
vendor APIs aren't on the allowlist either — so 17 must be installed by hand.

## What works out of the box

Running the released CLI binary. Nothing it does at startup needs a blocked
host.

```bash
VER=0.7.7  # or whatever the current release is
curl -sL -o /tmp/compose-preview.tar.gz \
  "https://github.com/yschimke/compose-ai-tools/releases/download/v${VER}/compose-preview-${VER}.tar.gz"
tar -xzf /tmp/compose-preview.tar.gz -C /tmp
/tmp/compose-preview-${VER}/bin/compose-preview help
/tmp/compose-preview-${VER}/bin/compose-preview doctor
```

`compose-preview help` and `compose-preview doctor` return cleanly. Actually
driving `renderAllPreviews` on a project that applies the Gradle plugin still
needs whatever hosts that project's dependencies need — which for Android
means Google Maven.

## What doesn't work without extra setup

- **`./gradlew :cli:installDist` from a fresh clone.** The root
  `build.gradle.kts` declares `alias(libs.plugins.android.application) apply
  false`, and Gradle resolves the plugin marker even with `apply false`. That
  hits `dl.google.com` and fails.
- **Standalone CLI builds** still need `gradle-tooling-api:9.3.1`, which is
  only published to `repo.gradle.org`. Maven Central tops out at v7.3 (2021).
- **Any `renderAllPreviews` run** on an Android module — Robolectric, AGP,
  and AndroidX artifacts all route through Google Maven.

## Recommended cloud setup

### 1. Install JDK 17 via the environment setup script

Add to your project's environment setup script (Claude Code web UI →
Environment → Setup script). This runs once per environment and its
filesystem changes are cached, unlike SessionStart hooks which run every
session.

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless
```

### 2. Pre-download the CLI via a SessionStart hook

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

### 3. For full builds: switch to "Full network access"

If you need to build the Gradle plugin, run the sample modules, or render
previews end-to-end, switch the session's network access level to **Full**
in the Claude Code web settings. The per-domain allowlist
(`sandbox.network.allowedDomains` in `settings.json`) is currently silently
ignored in cloud sessions — see
[anthropics/claude-code#38984](https://github.com/anthropics/claude-code/issues/38984).

## Known caveats

- **Java's HTTP client ignores `https_proxy`.** If the cloud session routes
  egress through a proxy, set `systemProp.https.proxyHost` and
  `systemProp.https.proxyPort` in `~/.gradle/gradle.properties` — Gradle's
  wrapper download otherwise hangs.
  [anthropics/claude-code#16222](https://github.com/anthropics/claude-code/issues/16222)
  describes the issue; a hook-based fix lives at
  [tschuehly/claude-code-gradle-proxy](https://github.com/tschuehly/claude-code-gradle-proxy).
- **`compose-preview` startup vs. invocation.** `help` / `doctor` work under
  the default allowlist. `list` / `show` / `render` shell out to Gradle
  against the target project — those succeed only to the extent that the
  target project's dependencies are reachable.
- **Toolchain auto-provisioning is off.** With Google blocked, Gradle can't
  fetch a JDK. Always pass an already-installed JDK path via
  `-Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64`
  or `JAVA_HOME`.
- **Keep rendered previews under 1800px on the longest edge.** Claude's
  cloud session enters a bad state when it's asked to view an image larger
  than that, and recovery usually means restarting the session. Previews
  above the threshold — long scroll captures, stitched `ScrollMode.LONG`
  outputs, high-DPI fan-outs — should be rendered at a smaller size (shrink
  `widthDp`/`heightDp` or the density) or down-scaled before an agent
  reads them. `compose-preview show --brief` only returns paths, so you
  won't trip the limit until something actually loads the PNG.

## Primary sources

- [Claude Code on the web](https://code.claude.com/docs/en/claude-code-on-the-web.md)
- [Sandboxing](https://code.claude.com/docs/en/sandboxing.md)
- [Hooks reference — SessionStart](https://code.claude.com/docs/en/hooks.md#sessionstart)
- [anthropics/claude-code#38984 — allowlist non-functional in cloud](https://github.com/anthropics/claude-code/issues/38984)
- [anthropics/claude-code#16222 — Gradle wrapper + Java proxy](https://github.com/anthropics/claude-code/issues/16222)
