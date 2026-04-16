# Packaging this repo as a Claude Code skill

Notes for making the Compose Preview skill trivially installable from a fresh
Claude Code environment. The goal: a single command that gives an agent both
the skill instructions *and* a working CLI, without the agent having to
improvise install paths, translate Kotlin→Groovy, or diagnose GitHub Packages
401s on its own.

The recommendations below move the repo from "docs + binary, figure it out"
to "an agent can run through Setup mechanically."

## Anatomy of a Claude Code skill

Claude Code discovers skills by scanning these locations, in order:

1. **Plugin marketplaces** the user has added via `/plugin`.
2. **`.claude/skills/<name>/SKILL.md`** inside a project (project-scoped; the
   most common shape).
3. **`~/.claude/skills/<name>/SKILL.md`** (user-scoped; available in every
   project).

A "skill" is just a directory whose `SKILL.md` has YAML frontmatter with at
least `name:` and `description:`. The description is what Claude uses to
decide whether the skill is relevant to the current turn, so it must be
specific and trigger-oriented (verbs, file types, task descriptions).

A skill directory may also contain:
- **`scripts/`** — helper scripts the skill instructs the agent to run.
  These are plain files; there is no special format.
- **Reference docs** — anything the skill's prose points to (markdown,
  JSON, images).

There is no build step. Agents read `SKILL.md`, follow its instructions, and
invoke the helpers.

## Current state and gaps

Today, [docs/SKILL.md](SKILL.md) is a well-written skill file that lives
inside the source tree, but to actually *use* it an agent has to:

1. Find it (not on a standard skill path).
2. Download a release tarball and guess an install location.
3. Write Gradle credentials to the right file.
4. Translate Kotlin DSL snippets to Groovy for projects that use
   `settings.gradle`.

With the install script and `compose-preview doctor` added in this change,
steps 2 and 3 are now scripted. Step 4 is addressed with dual snippets in
`SKILL.md`. Step 1 is what this doc is about.

## Option A — "copy this directory into `.claude/skills/`"

Simplest shape; no tooling required. Recommended starting point.

### Layout

Publish the skill as a standalone directory in this repo:

```
.claude-skill/
├── SKILL.md                 # copy/symlink of docs/SKILL.md
├── install.sh               # copy of scripts/install.sh
└── design/
    └── WEAR_UI.md           # the Wear guidance that SKILL.md references
```

Users install it with one command:

```sh
mkdir -p ~/.claude/skills/compose-preview
curl -fsSL https://codeload.github.com/yschimke/compose-ai-tools/tar.gz/refs/heads/main \
  | tar -xz --strip-components=2 -C ~/.claude/skills/compose-preview \
      compose-ai-tools-main/.claude-skill
```

Or, pinned to a release:

```sh
curl -fsSL https://github.com/yschimke/compose-ai-tools/releases/download/v0.3.1/compose-preview-skill.tar.gz \
  | tar -xz -C ~/.claude/skills/
```

Implement by adding a `compose-preview-skill.tar.gz` to the release pipeline
— a one-line `tar czf` over the `.claude-skill/` directory. No new
dependencies.

### Why a copy, not a symlink

`SKILL.md` is referenced by the README (for humans) and needs to live under
`docs/` to remain linkable there. A release build can either `cp` or
`ln -s` it into `.claude-skill/` before packaging; a symlink is fine on
Linux/macOS tarballs.

### Keep the two in sync

Add a CI check:

```yaml
- name: Skill mirror is in sync
  run: diff -u docs/SKILL.md .claude-skill/SKILL.md
```

Or, cleaner: make `.claude-skill/SKILL.md` the source of truth and have
`docs/SKILL.md` be a one-line include ("See the skill file at
`.claude-skill/SKILL.md`."). The README stays linkable, humans still find
their docs, and the skill shape is the canonical home.

## Option B — plugin marketplace entry

Claude Code's plugin system lets a user run `/plugin install <name>` to
subscribe to a marketplace. Marketplaces are git repos whose root contains a
`marketplace.json` listing available skills. If `yschimke/compose-ai-tools`
exposes one directly, the install sequence becomes:

```
/plugin marketplace add yschimke/compose-ai-tools
/plugin install compose-preview
```

Minimum files:

```
marketplace.json                   # { "name": "...", "skills": [{ "path": ".claude-skill" }] }
.claude-skill/SKILL.md
.claude-skill/install.sh
```

Users get automatic update prompts and no manual `tar` step. Best UX, but
introduces a second file (`marketplace.json`) the release pipeline has to
keep valid.

## Option C — fully self-contained: bundle the CLI in the skill

For users who don't want to run `install.sh` separately, the skill directory
can ship the CLI binaries directly:

```
.claude-skill/
├── SKILL.md
├── bin/compose-preview           # thin launcher wrapper
└── lib/*.jar                     # same contents as the release tarball's lib/
```

`SKILL.md` then points at `bin/compose-preview` using `${CLAUDE_SKILL_DIR}`
(an env var Claude Code sets when running skill helpers) — the agent never
touches `~/.local/bin`, and there's no PATH setup.

Trade-off: the skill tarball grows to match the CLI tarball size (~6.4 MB
for 0.3.1). Still small in absolute terms, but it means every skill update
re-ships the whole CLI. The upside is that `install.sh` becomes truly
optional; `compose-preview doctor` runs directly from the skill.

## Recommendation

Ship **Option A first** (it's five lines of release pipeline and a mirror
check) and evaluate Option B once the skill has real users. Hold off on
Option C unless install friction turns out to be the binding constraint
— the versioned CLI + `install.sh` already collapses install to one
command, which is usually good enough.

## Release pipeline changes, concretely

1. **Mirror the skill file.** Add a release step (or commit-time pre-push
   hook) that copies `docs/SKILL.md` → `.claude-skill/SKILL.md`, and CI
   that fails if they diverge.
2. **Bundle the scripts.** In the same release workflow that produces
   `compose-preview-<ver>.tar.gz`, add:
   ```sh
   tar czf compose-preview-skill-<ver>.tar.gz -C .claude-skill .
   ```
   Attach it to the GitHub release alongside the CLI tarball and VSIX.
3. **Pin the skill to a CLI version.** `SKILL.md` already pins the plugin
   version in its `Setup` snippets. Have the release step template that
   version into the skill tarball so there's no drift — e.g. replace a
   `@CLI_VERSION@` token at package time.
4. **Document both install flows in README.** One section each:
   "Install the CLI" (what's already there) and "Install as a Claude Code
   skill" (the one-liner from Option A).

## Naming notes

- The `name:` field in the current `SKILL.md` frontmatter is `preview`.
  For a standalone skill directory named `compose-preview`, rename it to
  `compose-preview` so `/skills` listings don't collide with other
  "preview" skills (the web, any IDE, etc.).
- The `description:` is what Claude uses to decide to trigger. It already
  lists the concrete triggers ("Render Compose @Preview functions to PNG…
  Use this to verify UI changes, iterate on designs, and compare
  before/after states"). Keep that specificity; don't reduce it to
  "preview helper" or similar.
