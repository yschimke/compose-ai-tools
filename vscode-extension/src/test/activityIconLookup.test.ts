import * as assert from "assert";
import { findIconForActivityFqn } from "../activityIconLookup";
import { ManifestReference, ResourceManifest } from "../types";

const APP_ICON: ManifestReference = {
    source: "src/main/AndroidManifest.xml",
    componentKind: "application",
    componentName: null,
    attributeName: "android:icon",
    resourceType: "mipmap",
    resourceName: "ic_launcher",
};

const ACTIVITY_ICON_OVERRIDE: ManifestReference = {
    source: "src/main/AndroidManifest.xml",
    componentKind: "activity",
    componentName: "com.example.MainActivity",
    attributeName: "android:icon",
    resourceType: "drawable",
    resourceName: "ic_settings",
};

const ROUND_ICON_OVERRIDE: ManifestReference = {
    source: "src/main/AndroidManifest.xml",
    componentKind: "activity",
    componentName: "com.example.RoundOnlyActivity",
    attributeName: "android:roundIcon",
    resourceType: "mipmap",
    resourceName: "ic_launcher_round",
};

function manifest(refs: ManifestReference[]): ResourceManifest {
    return {
        module: "sample",
        variant: "debug",
        resources: [],
        manifestReferences: refs,
    };
}

describe("findIconForActivityFqn", () => {
    it("prefers a direct android:icon override over the application fallback", () => {
        const m = manifest([APP_ICON, ACTIVITY_ICON_OVERRIDE]);
        const match = findIconForActivityFqn(
            m,
            "com.example.MainActivity",
            true,
        );
        assert.ok(match);
        assert.strictEqual(match.source, "direct");
        assert.strictEqual(match.reference.resourceName, "ic_settings");
    });

    it("falls back to <application> icon for an activity-like class with no override", () => {
        const m = manifest([APP_ICON]);
        const match = findIconForActivityFqn(
            m,
            "com.example.UnrelatedActivity",
            true,
        );
        assert.ok(match);
        assert.strictEqual(match.source, "application-fallback");
        assert.strictEqual(match.reference.resourceName, "ic_launcher");
    });

    it("does NOT surface the <application> icon when the class is not activity-like", () => {
        const m = manifest([APP_ICON]);
        const match = findIconForActivityFqn(
            m,
            "com.example.PlainDataClass",
            false,
        );
        assert.strictEqual(match, null);
    });

    it("returns null when no application icon exists either", () => {
        const m = manifest([]);
        const match = findIconForActivityFqn(
            m,
            "com.example.MainActivity",
            true,
        );
        assert.strictEqual(match, null);
    });

    it("ignores android:roundIcon overrides at v1 (icon is what users want)", () => {
        const m = manifest([APP_ICON, ROUND_ICON_OVERRIDE]);
        // The class has a roundIcon but no icon — we want to fall through to
        // the application icon rather than promote the roundIcon to primary,
        // because the OS only uses roundIcon on circular-icon launchers.
        const match = findIconForActivityFqn(
            m,
            "com.example.RoundOnlyActivity",
            true,
        );
        assert.ok(match);
        assert.strictEqual(match.source, "application-fallback");
        assert.strictEqual(match.reference.resourceName, "ic_launcher");
    });
});
