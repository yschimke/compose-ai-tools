# Contract fixtures

Golden payloads for the contracts this repository defines in `schema/` — currently
the spatial scene (`spatial-scene.schema.json`) and the spatial semantics tree.

They live here, next to the schemas, because they are **contract** artifacts rather
than any one consumer's test data. They used to sit under
`vscode-extension/preview-harness/fixtures/`, which was only ever an accident of the
VS Code extension having been the first thing to render them. When the extension
moved to [yschimke/compose-preview-vscode](https://github.com/yschimke/compose-preview-vscode)
that accident became a cross-repo dependency for tests that cannot leave this repo:

| fixture | read by |
| --- | --- |
| `spatial-scene/` | `SpatialSceneTest`, `XrRenderServerIntegrationTest`; vendored into [compose-preview-xr](https://github.com/yschimke/compose-preview-xr) for its `serve_smoke.py` |
| `spatial-semantics-tree/` | `SpatialSemanticsTreeTest` |

The extension keeps its own copy of the scene for its webview harness. Both are
copies of a contract defined here; if the schema changes, both move.
