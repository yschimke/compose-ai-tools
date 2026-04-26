# Protocol fixtures

Golden JSON message samples for the preview-daemon IPC protocol — see [../PROTOCOL.md](../PROTOCOL.md).

Each message kind lives in its own file: `<direction>-<method>.json` (e.g. `client-initialize.json`, `daemon-renderFinished.json`). Both the Kotlin daemon test suite and the TypeScript client test suite load these fixtures and assert round-trip serialisation matches.

Add a fixture in the same PR that adds or changes a protocol message.

> No fixtures are checked in yet — they land alongside Stream B (P1.B1.1) and Stream C (P1.C1.1) in [../TODO.md](../TODO.md).
