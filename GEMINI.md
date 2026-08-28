@./AGENTS.md

The `@` line above is a Gemini CLI memory import, not a link: it inlines
[`AGENTS.md`](AGENTS.md) — the repository's canonical agent instructions — into
context. A plain markdown link would not be followed, which is exactly what this
file used to do.

Nothing else belongs here. Repository rules live in `AGENTS.md`; architecture,
commands and constraints live in [`docs/AGENTS.md`](docs/AGENTS.md).
