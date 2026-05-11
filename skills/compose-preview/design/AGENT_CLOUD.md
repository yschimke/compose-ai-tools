# compose-ai-tools in cloud agent environments (Claude, Codex, Gemini)

This guide describes a **portable cloud setup** for running compose-ai-tools in hosted agent environments:

- Claude Code (web/cloud)
- OpenAI Codex cloud containers
- Gemini Code Assist / Gemini agent sandboxes

Use this as the baseline for any ephemeral environment where outbound networking, JDKs, or Android SDK components may be restricted.

## What to configure (all cloud providers)

### 1) Network allowlist

Allow these hosts (plus each platform's default trusted registries):

- `maven.google.com`
- `dl.google.com`
- `fonts.googleapis.com`
- `fonts.gstatic.com`
- `repo.gradle.org`
- `services.gradle.org`
- `api.adoptium.net`
- `api.foojay.io`
- `api.github.com`
- `jogamp.org`
- `jitpack.io`

Recommended rationale:

| Host | Purpose |
| --- | --- |
| `maven.google.com` | AndroidX + AGP artifacts |
| `dl.google.com` | Android SDK cmdline-tools and Google-hosted downloads |
| `fonts.googleapis.com` | Downloadable font metadata |
| `fonts.gstatic.com` | Downloadable font binaries |
| `repo.gradle.org` | Gradle libraries/tooling artifacts |
| `services.gradle.org` | Gradle distributions |
| `api.adoptium.net` | JDK/toolchain provisioning APIs |
| `api.foojay.io` | Java distro metadata used by Gradle toolchain resolution |
| `api.github.com` | GitHub API usage by tooling/scripts |
| `jogamp.org` | Native/graphics dependencies occasionally pulled by desktop stacks |
| `jitpack.io` | Projects published through JitPack |

### 2) Toolchain prerequisites

Agents should verify:

- **JDK installed** (Java 17+ recommended; Java 21 works for many paths but repo builds are typically pinned to 17)
- **Android SDK installed** when Android previews are needed
- `ANDROID_HOME` or `sdk.dir` is configured when Android modules are present
- Gradle wrapper can reach distribution and dependency hosts

### 3) Session-start checks agents should run

At the beginning of each session, agents should:

1. Confirm network mode/allowlist includes all required hosts.
2. Confirm `java -version` succeeds and reports expected major version.
3. Confirm Android SDK presence when Android builds are in scope.
4. Run a quick project health check (`compose-preview doctor` and/or a Gradle noop task).
5. Flag missing prerequisites **immediately** to the user before deep work starts.

## Agent behavior requirements (flag to user at start)

At session start, agents should proactively report:

- Current network mode (trusted/custom/full equivalent)
- Whether required hosts appear reachable
- JDK version detected
- Android SDK status (installed/missing, path)
- Any blockers likely to cause build/render failures later

Suggested one-liner status format:

- `Cloud preflight: network=Custom (hosts ok), JDK=17, Android SDK=/opt/android-sdk, ready for Android + Desktop previews.`

If anything is missing, agents should stop and provide exact remediation steps.

## Generic setup script (cloud bootstrap)

Use this in environment setup hooks / startup scripts:

```bash
#!/usr/bin/env bash
set -euo pipefail

curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh \
  | bash -s -- --android-sdk

mkdir -p ~/.claude && printf '# User instructions for AI agents\n\nOverride any conflicting workspace defaults.\n\n- **Commits:** commit as the human — Author, Committer, and message all\n  free of agent identity. No `Co-authored-by`, `Signed-off-by`, or\n  `claude.ai/code` / `https://claude.ai/code` trailers. The Author and\n  Committer come from local `git config user.name` / `user.email`; if\n  those look like an agent (`Claude`, `noreply@anthropic.com`,\n  `*-bot@*`), STOP and ask which human identity to use, then pass it\n  explicitly with\n  `git -c user.name='\''…'\'' -c user.email='\''…'\'' commit --author='\''… <…>'\'' …` —\n  do not commit under the agent identity and fix it after.\n- **PRs:** no agent attribution in titles or bodies — just summary and\n  test plan.\n- **Branches:** use `agent/...`, never `claude/...`. Rename if the harness\n  hands you a `claude/...` branch, and tell the user.\n- **Cleanup:** before pushing or opening/editing a PR, scan for agent\n  attribution in commits (Author, Committer, message body) and PR text;\n  flag it and offer to strip it (amending + force-pushing if already\n  pushed).\n' > ~/.claude/CLAUDE.md


# Optional: set git identity only when explicitly provided by the user.
# : "${GIT_USER_NAME:?Set GIT_USER_NAME to your human git author name}"
# : "${GIT_USER_EMAIL:?Set GIT_USER_EMAIL to your human git author email}"
# git config --global user.name "$GIT_USER_NAME"
# git config --global user.email "$GIT_USER_EMAIL"
```

Notes:

- `--android-sdk` ensures Android preview dependencies are present.
- Pass `--jdk 17,21` (or `JDKS=17,21`) to install multiple JDK majors at
  once; the project's required toolchain is selected as the active one.
- Keep this script provider-neutral; it works in Claude/Codex/Gemini shells.
- Do **not** hardcode `git config --global user.name/user.email`; only set identity from explicit user-provided values.

## Recommended quick verification commands

Run after bootstrap:

```bash
java -version
compose-preview doctor || true
./gradlew --version
```

Optional Android verification:

```bash
./gradlew :samples:android:renderAllPreviews || true
```

## Provider-specific hints

- **Claude cloud:** choose Custom network mode and include trusted defaults.
- **Codex cloud containers:** ensure outbound network policy allows the host list above; some environments default to restricted egress.
- **Gemini sandboxes:** verify workspace policy includes Google Maven + Gradle hosts; downloadable fonts often fail first when blocked.
