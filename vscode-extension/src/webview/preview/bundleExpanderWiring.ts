// Wiring helper: connect a `<bundle-expander>` to a
// `BundleController` so user clicks on the Configure expander's
// checkboxes flow into the controller's `setKindEnabled` (which
// forwards them across the wire as `setDataExtensionEnabled`).
//
// Extracted from `main.ts`'s `firstUpdated` body — the four
// per-bundle body builders (a11y/perf/text/inspection) each used to
// inline the same `kind-toggled` listener block, which drifted apart
// on every refactor and was the kind of plumbing that could be
// quietly deleted without any test noticing. Pulling it into a
// single module keeps the listener shape in one place and —
// critically — lets the smoke test in
// `src/test/focusBundleSmoke.test.ts` exercise the *production*
// wiring rather than a hand-rolled mirror.
//
// `main.ts` calls this helper at every site that creates a
// `<bundle-expander>`; the smoke test calls it too. If anyone
// changes the bus / event shape, both call paths break together.
// Internally the helper uses the typed event bus (`on` from
// `webview/shared/eventBus`), so the contract test in
// `src/test/eventBusContract.test.ts` also covers the
// producer/consumer pairing for `kind-toggled`.

import type { BundleController } from "./bundleController";
import type { BundleExpander } from "./components/BundleExpander";
import { on } from "../shared/eventBus";

/**
 * Attach the `kind-toggled` listener that forwards user clicks
 * from [expander] into [controller]. Returns an unsubscribe
 * function for callers that want one; production callsites in
 * `main.ts` don't bother with teardown because the expander
 * lives for the panel lifetime.
 */
export function wireExpanderToController(
    expander: BundleExpander,
    controller: BundleController,
): () => void {
    return on(expander, "kind-toggled", (det) => {
        controller.setKindEnabled(det.bundleId, det.kind, det.enabled);
    });
}
