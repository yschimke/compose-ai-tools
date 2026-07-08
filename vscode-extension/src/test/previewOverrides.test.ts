// composePreviewOverrides — unified per-preview override bag.
//
// Pins the composition contract the extension host relies on: every snapshot
// `renderNow` (an explicit edit AND an auto re-render) must resend the full set of
// host-authoritative overrides so editing one never drops the others. Mirrors the
// webview-side `liveState.overridesForPreview` composition tests.

import * as assert from "assert";
import type {
    PermissionsOverride,
    RemoteComposeOverride,
} from "../daemon/daemonProtocol";
import { composePreviewOverrides } from "../daemon/previewOverrides";

const perms: PermissionsOverride = {
    grants: { "android.permission.CAMERA": "granted" },
};
const rc: RemoteComposeOverride = { profile: "androidx" };

describe("composePreviewOverrides", () => {
    it("returns undefined when nothing is active", () => {
        assert.strictEqual(composePreviewOverrides({}), undefined);
        assert.strictEqual(
            composePreviewOverrides({ clearBackground: false }),
            undefined,
        );
    });

    it("carries a single override through", () => {
        assert.deepStrictEqual(
            composePreviewOverrides({ permissions: perms }),
            {
                permissions: perms,
            },
        );
        assert.deepStrictEqual(composePreviewOverrides({ remoteCompose: rc }), {
            remoteCompose: rc,
        });
        assert.deepStrictEqual(
            composePreviewOverrides({ clearBackground: true }),
            { clearBackground: true },
        );
    });

    it("composes every host-authoritative override into one bag", () => {
        assert.deepStrictEqual(
            composePreviewOverrides({
                permissions: perms,
                remoteCompose: rc,
                clearBackground: true,
            }),
            { permissions: perms, remoteCompose: rc, clearBackground: true },
        );
    });

    it("omits clearBackground when off but keeps the others", () => {
        assert.deepStrictEqual(
            composePreviewOverrides({
                permissions: perms,
                remoteCompose: rc,
                clearBackground: false,
            }),
            { permissions: perms, remoteCompose: rc },
        );
    });

    it("never sets lottie (daemon-sticky, excluded by design)", () => {
        const o = composePreviewOverrides({
            permissions: perms,
            clearBackground: true,
        });
        assert.ok(o && !("lottie" in o));
    });
});
