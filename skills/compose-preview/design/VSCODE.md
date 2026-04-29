# VS Code extension

Aimed at humans working in the IDE; agents driving renders should use the
CLI. With the `vscjava.vscode-gradle` extension installed, the Compose
Preview extension provides:

- A **preview panel** (webview) listing rendered previews for the active module.
- **CodeLens** and **hover** actions above every `@Preview` function in Kotlin
  files (re-render, open PNG).
- Commands:
  - `Compose Preview: Refresh` / `Render All`
  - `Compose Preview: Run for File` — render only previews in the active file.
- Auto-refresh on editor save (debounced) and on active-editor switch to a
  Kotlin file.
