// Generator for `permissions.json`. Run with:
//   node preview-harness/fixtures/permissions.gen.mjs > preview-harness/fixtures/permissions.json
//
// Demonstrates the Inspection bundle's `compose/permissions` chip — the
// runtime-permissions surface added in #1370 / #1374 / #1381 / #1395 with the
// panel-side override-toggle UI from #1400 part 2.
//
// The fixture preloads a payload with one granted permission (CAMERA), one
// denied permission (RECORD_AUDIO), and a queried-but-not-pinned permission
// (ACCESS_FINE_LOCATION). It then activates the Inspection bundle and ticks
// the `compose/permissions` checkbox in the Configure expander (default-OFF in
// `bundleRegistry.ts`) so the tab body renders the two flat-table sections
// plus the Add-permission form, the Clear-overrides action, and the per-row
// Grant / Deny / Clear buttons.
//
// Payload shape mirrors `PermissionsPayload` in
// `data/permissions/core/.../PermissionsModels.kt` — the same wire shape
// `PermissionsDataProductRegistry` emits via `data/fetch?kind=compose/permissions`.

import {
    EARLY_FEATURES_DATASET,
    activateBundleAction,
    buildMobileMock,
    buildPreviewPair,
    expectSetDataExtension,
    focusAction,
    forbidSetDataExtensionEnabled,
} from "./_utils.mjs";

const mock = buildMobileMock();
const { W, H, png } = mock;

const focusId = "com.example.permissionsKt.CameraPermissionGrantedPreview";
const { focused, sibling } = buildPreviewPair({
    focusId,
    width: W,
    height: H,
    fnName: "CameraPermissionGrantedPreview",
    file: "PermissionGatedPreview.kt",
});

// `compose/permissions` payload — the real wire shape the daemon's
// `PermissionsDataProductRegistry` emits. Mix of states so the panel
// surfaces every cell variant in one snapshot:
//
//   - CAMERA       — granted + queried   → info row, Grant button reads as
//                                          aria-pressed, queried column = "yes"
//   - RECORD_AUDIO — denied + not queried → warning row, Deny aria-pressed
//   - ACCESS_FINE_LOCATION — queried, no grant pinned → unknown row in
//                                          the Queried table (the
//                                          natural "give the user a
//                                          one-click Grant/Deny here"
//                                          target).
const permissions = {
    grants: {
        "android.permission.CAMERA": "granted",
        "android.permission.RECORD_AUDIO": "denied",
    },
    queried: [
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
    ],
};

const setPreviews = {
    command: "setPreviews",
    moduleDir: "/workspace/sample-android",
    heavyStaleIds: [],
    previews: [focused, sibling],
};

const updateImage = {
    command: "updateImage",
    previewId: focusId,
    captureIndex: 0,
    imageData: png,
};

const updateDataProducts = {
    command: "updateDataProducts",
    previewId: focusId,
    dataProducts: [{ kind: "compose/permissions", payload: permissions }],
};

const fixture = {
    name: "permissions",
    description:
        "Focused card with a `compose/permissions` payload covering granted / denied / queried-unknown rows. Activates the Inspection bundle, opens the Configure expander, and ticks the Permissions checkbox so the tab body renders the Effective grants + Queried tables, the per-row Grant / Deny / Clear buttons (with `aria-pressed` on the currently-applied grant), the Add-permission form, and the Clear-overrides action — the surface the panel ships for #1400 part 2.",
    dataset: EARLY_FEATURES_DATASET,
    messages: [setPreviews, updateImage, updateDataProducts],
    actions: [
        focusAction(focusId),
        activateBundleAction("inspection"),
        // Open the Configure expander on the Inspection bundle's tab body
        // and tick the `compose/permissions` checkbox — the kind is
        // default-OFF in `bundleRegistry.ts`, so chip activation alone
        // doesn't surface the section.
        {
            click: `[data-bundle="inspection"] bundle-expander details > summary`,
        },
        {
            click: `[data-bundle="inspection"] bundle-expander input[data-kind="compose/permissions"]`,
        },
    ],
    // Inspection bundle activation subscribes only its default-ON kind
    // (`compose/semantics`); the Permissions checkbox click then posts a
    // single-kind `setDataExtensionEnabled` for `compose/permissions`.
    expectedPosts: [
        expectSetDataExtension(focusId, "compose/semantics", true),
        expectSetDataExtension(focusId, "compose/permissions", true),
    ],
    // The other default-OFF Inspection kinds must NOT be subscribed by
    // chip activation or by ticking Permissions — same contract the
    // `inspection-tree` fixture pins for `layout/inspector` and
    // `uia/hierarchy`. `compose/launcher-widget` is also default-OFF and
    // shouldn't ride along.
    forbiddenPosts: [
        forbidSetDataExtensionEnabled(focusId, "layout/inspector"),
        forbidSetDataExtensionEnabled(focusId, "uia/hierarchy"),
        forbidSetDataExtensionEnabled(focusId, "compose/launcher-widget"),
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
