import * as assert from "assert";
import { resolveModeFromSettings } from "../composePreviewMode";

/**
 * `composePreview.mode` is a binary user pin since auto mode was removed —
 * `"minimal"` or `"full"`. The bundled `--init-script` is what makes `"full"`
 * safe to default to on any Android / Compose workspace, so there is no
 * longer a "look around the workspace to guess" branch to exercise here.
 */
describe("resolveModeFromSettings", () => {
    it("returns minimal mode when the user sets composePreview.mode=minimal", () => {
        const result = resolveModeFromSettings({ mode: "minimal" });
        assert.deepStrictEqual(result, {
            mode: "minimal",
            reason: "user-setting",
        });
    });

    it("returns full mode when the user sets composePreview.mode=full", () => {
        const result = resolveModeFromSettings({ mode: "full" });
        assert.deepStrictEqual(result, {
            mode: "full",
            reason: "user-setting",
        });
    });
});
