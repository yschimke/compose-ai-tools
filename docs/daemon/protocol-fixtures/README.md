# Protocol fixtures

Golden JSON message samples for the preview-daemon IPC protocol — see [../PROTOCOL.md](../PROTOCOL.md).

Each message kind lives in its own file: `<direction>-<method>.json` (e.g. `client-initialize.json`, `daemon-renderFinished.json`). Both the Kotlin daemon test suite and the TypeScript client test suite load these fixtures and assert round-trip serialisation matches.

Add a fixture in the same PR that adds or changes a protocol message.

Fixture inventory (added in B1.2):

- `client-initialize.json` — `initialize` request params (§ 3).
- `daemon-initializeResult.json` — `initialize` response result (§ 3).
- `client-setVisible.json`, `client-setFocus.json`, `client-fileChanged.json` — client → daemon notifications (§ 4).
- `client-renderNow.json`, `daemon-renderNowResult.json` — `renderNow` request and response (§ 5).
- `daemon-discoveryUpdated.json`, `daemon-renderStarted.json`, `daemon-renderFinished.json`, `daemon-renderFailed.json`, `daemon-classpathDirty.json`, `daemon-sandboxRecycle.json`, `daemon-daemonWarming.json`, `daemon-daemonReady.json`, `daemon-log.json` — daemon → client notifications (§ 6).
- `envelope-request.json`, `envelope-response.json`, `envelope-notification.json`, `envelope-errorResponse.json` — JSON-RPC envelope shapes (§ 2).

Stream C (TypeScript) loads the same files in C1.1.
