import * as assert from "assert";
import { APPLIES_PLUGIN_RE, appliesPlugin } from "../pluginDetection";

describe("appliesPlugin", () => {
    it("matches the literal id form", () => {
        assert.ok(appliesPlugin('id("ee.schimke.composeai.preview")'));
        assert.ok(
            appliesPlugin('plugins { id("ee.schimke.composeai.preview") }'),
        );
        assert.ok(appliesPlugin("id 'ee.schimke.composeai.preview'"));
    });

    it("rejects a declaration-only snippet", () => {
        assert.ok(!appliesPlugin('id = "ee.schimke.composeai.preview"'));
    });

    it("rejects literal `apply false` on the same line", () => {
        assert.ok(
            !appliesPlugin('id("ee.schimke.composeai.preview") apply false'),
        );
    });

    it("does NOT match the version-catalog alias form — handled via marker", () => {
        // Intentional: alias detection would require parsing
        // `libs.versions.toml`. The `applied.json` marker written by
        // `composePreviewApplied` covers this case authoritatively.
        assert.ok(!appliesPlugin("alias(libs.plugins.composeai.preview)"));
    });
});

describe("APPLIES_PLUGIN_RE", () => {
    it("matches the literal application forms", () => {
        assert.ok(APPLIES_PLUGIN_RE.test('id("ee.schimke.composeai.preview")'));
        assert.ok(
            APPLIES_PLUGIN_RE.test(
                'plugins { id("ee.schimke.composeai.preview") }',
            ),
        );
        assert.ok(APPLIES_PLUGIN_RE.test("id 'ee.schimke.composeai.preview'"));
    });

    it("rejects declaration-only usage", () => {
        assert.ok(
            !APPLIES_PLUGIN_RE.test('id = "ee.schimke.composeai.preview"'),
        );
    });

    // Note: the raw regex is just the plugin-reference matcher. The `apply
    // false` exclusion happens at the line level inside [appliesPlugin] so
    // the raw regex alone does still match a `... apply false` line.
});
