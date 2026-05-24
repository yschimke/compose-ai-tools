// Pure lookup: given the FQN of a class in the editor and a
// `ResourceManifest`, find the best `ManifestReference` whose rendered
// resource should be surfaced as that file's "icon". Used by
// [ActivityIconHoverProvider] / gutter decorations.
//
// Lives outside the VS Code surface so Mocha unit tests can drive it
// without the extension host.

import { ManifestReference, ResourceManifest } from "./types";

/**
 * Result of [findIconForActivityFqn] — pairs the resolved reference
 * with a tag that says how it was resolved, so the hover surface can
 * label the fallback case ("(no override — using `<application>`
 * icon)") differently from a direct match.
 */
export interface ActivityIconMatch {
    reference: ManifestReference;
    /**
     * `"direct"` — the activity itself declared `android:icon`.
     * `"application-fallback"` — no override on the activity; this is
     * the `<application>` icon, which is what the OS shows for any
     * component without one.
     */
    source: "direct" | "application-fallback";
}

/**
 * Look up the icon for the activity (or service / receiver / provider)
 * whose FQN is [fqn]. Resolution order:
 *
 *  1. **Direct override.** A `ManifestReference` whose `componentName`
 *     equals [fqn] and whose attribute is `android:icon`. Wins over
 *     all other options — the user explicitly set this.
 *  2. **Application fallback** (only when [activityLike] is `true`).
 *     The `<application>` icon, which the OS shows for any component
 *     without an override. Gated on the caller's "this class looks
 *     like an Android component" check so we don't surface the app
 *     icon next to a random data class.
 *
 * Returns `null` when neither applies. `android:roundIcon` /
 * `android:logo` / `android:banner` are deliberately ignored at v1 —
 * they cover the same resources at different DPIs / form factors and
 * the hover for `android:icon` is what users overwhelmingly want;
 * surfacing all four would crowd the popover.
 */
export function findIconForActivityFqn(
    manifest: ResourceManifest,
    fqn: string,
    activityLike: boolean,
): ActivityIconMatch | null {
    const direct = manifest.manifestReferences.find(
        (r) => r.componentName === fqn && r.attributeName === "android:icon",
    );
    if (direct) {
        return { reference: direct, source: "direct" };
    }
    if (!activityLike) {
        return null;
    }
    const appIcon = manifest.manifestReferences.find(
        (r) =>
            r.componentKind === "application" &&
            r.componentName === null &&
            r.attributeName === "android:icon",
    );
    return appIcon
        ? { reference: appIcon, source: "application-fallback" }
        : null;
}
