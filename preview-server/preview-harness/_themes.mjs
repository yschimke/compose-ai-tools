// The themes a page capture is taken in.
//
// A four-line copy of what [`preview-harness/_fixtures.mjs`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/_fixtures.mjs) exports, and the only
// thing the serve specs ever borrowed from the extension's harness. Copied rather than imported:
// after the split this directory travels to the preview-server repo, and a relative import reaching
// back into `compose-preview-vscode/` would be a new cross-boundary dependency of exactly the kind the
// move exists to remove. The extension keeps its own copy for its own fixtures; if the two ever
// need to agree on more than two strings, that is the signal to publish a shared harness package
// rather than to re-point the import.

/** Themes to capture, honouring the optional `HARNESS_THEME` override. */
export function listThemes() {
  const only = process.env.HARNESS_THEME;
  return only ? [only] : ["dark", "light"];
}
