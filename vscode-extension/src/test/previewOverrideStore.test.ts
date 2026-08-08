import * as assert from "assert";
import { PreviewOverrideStore } from "../daemon/previewOverrideStore";

const PREVIEW = "com.example.ButtonPreview";

/**
 * Narrow a wire value to its payload. `RemoteNamedValueWire` is a discriminated
 * union whose `color` arm carries `argb` rather than `value`, so a bare
 * `.value` doesn't typecheck across the union.
 */
function valueOf(
    v: import("../daemon/daemonProtocol").RemoteNamedValueWire | undefined,
): unknown {
    if (!v) return undefined;
    return v.kind === "color" ? v.argb : v.value;
}

describe("PreviewOverrideStore", () => {
    it("returns undefined when nothing is active", () => {
        const store = new PreviewOverrideStore();
        assert.strictEqual(store.compose(PREVIEW), undefined);
    });

    it("composes every active override into one bag", () => {
        // The reason `compose` exists: the daemon reverts any authoritative
        // override a render omits, so an edit to one must resend the others.
        const store = new PreviewOverrideStore();
        store.applyPermissionsChange(PREVIEW, {
            field: "setGrant",
            permission: "android.permission.CAMERA",
            grant: "granted",
        });
        store.applyRemoteComposeChange(PREVIEW, {
            field: "namedValue",
            name: "label",
            value: { kind: "string", value: "hi" },
        });
        store.setClearBackground(PREVIEW, true);

        const bag = store.compose(PREVIEW);
        assert.ok(bag, "expected a composed bag");
        assert.ok(bag.permissions, "permissions dropped from the bag");
        assert.ok(bag.remoteCompose, "remoteCompose dropped from the bag");
        assert.strictEqual(bag.clearBackground, true);
    });

    it("keeps overrides separate per preview", () => {
        const store = new PreviewOverrideStore();
        store.setClearBackground(PREVIEW, true);
        assert.strictEqual(store.compose("other.Preview"), undefined);
    });

    it("clears the background flag back off", () => {
        const store = new PreviewOverrideStore();
        store.setClearBackground(PREVIEW, true);
        store.setClearBackground(PREVIEW, false);
        assert.strictEqual(store.compose(PREVIEW), undefined);
    });

    describe("remoteCompose first-edit seeding", () => {
        it("seeds from the last attached payload on the first edit", () => {
            // `RemoteComposeController.set(...)` is full-replacement, so a bag
            // built from the edited field alone would erase every other value
            // the user's code bound.
            const store = new PreviewOverrideStore();
            store.noteRemoteComposePayload(PREVIEW, {
                namedValues: {
                    label: { kind: "string", value: "from-code" },
                    count: { kind: "int", value: 7 },
                },
            });

            const next = store.applyRemoteComposeChange(PREVIEW, {
                field: "namedValue",
                name: "label",
                value: { kind: "string", value: "edited" },
            });

            assert.strictEqual(valueOf(next.namedValues?.label), "edited");
            assert.strictEqual(
                valueOf(next.namedValues?.count),
                7,
                "unedited value from the snapshot was erased",
            );
        });

        it("does not re-seed after the first edit", () => {
            // After the first edit the override bag is the source of truth;
            // re-seeding would shadow the user's own edits with stale values.
            const store = new PreviewOverrideStore();
            store.noteRemoteComposePayload(PREVIEW, {
                namedValues: { label: { kind: "string", value: "from-code" } },
            });
            store.applyRemoteComposeChange(PREVIEW, {
                field: "namedValue",
                name: "label",
                value: { kind: "string", value: "edited" },
            });

            // A later payload attach must not resurrect the code-bound value.
            store.noteRemoteComposePayload(PREVIEW, {
                namedValues: { label: { kind: "string", value: "from-code" } },
            });
            const next = store.applyRemoteComposeChange(PREVIEW, {
                field: "namedValue",
                name: "count",
                value: { kind: "int", value: 3 },
            });

            assert.strictEqual(
                valueOf(next.namedValues?.label),
                "edited",
                "the user's earlier edit was overwritten by a re-seed",
            );
            assert.strictEqual(valueOf(next.namedValues?.count), 3);
        });

        it("works with no snapshot at all", () => {
            const store = new PreviewOverrideStore();
            const next = store.applyRemoteComposeChange(PREVIEW, {
                field: "namedValue",
                name: "label",
                value: { kind: "string", value: "edited" },
            });
            assert.strictEqual(valueOf(next.namedValues?.label), "edited");
        });

        it("copies the snapshot rather than aliasing it", () => {
            // A shared reference would let a later edit mutate the recorded
            // payload, quietly changing what a future first-edit seeds from.
            const store = new PreviewOverrideStore();
            const namedValues = {
                label: { kind: "string" as const, value: "from-code" },
            };
            store.noteRemoteComposePayload(PREVIEW, { namedValues });
            store.applyRemoteComposeChange(PREVIEW, {
                field: "namedValue",
                name: "label",
                value: { kind: "string", value: "edited" },
            });
            assert.strictEqual(namedValues.label.value, "from-code");
        });
    });

    it("accumulates permissions across edits", () => {
        const store = new PreviewOverrideStore();
        store.applyPermissionsChange(PREVIEW, {
            field: "setGrant",
            permission: "android.permission.CAMERA",
            grant: "granted",
        });
        const next = store.applyPermissionsChange(PREVIEW, {
            field: "setGrant",
            permission: "android.permission.RECORD_AUDIO",
            grant: "denied",
        });
        assert.strictEqual(Object.keys(next.grants).length, 2);
    });
});
