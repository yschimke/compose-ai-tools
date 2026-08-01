import * as assert from "assert";
import {
    APPLIES_PLUGIN_RE,
    appliesPlugin,
    COMPOSE_HOST_PLUGIN_RE,
    hasComposeHostPlugin,
    hasPotentialComposeHostPlugin,
    POTENTIAL_COMPOSE_HOST_PLUGIN_RE,
} from "../pluginDetection";

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

describe("hasComposeHostPlugin", () => {
    it("detects Android and Compose host plugins that auto-inject can target", () => {
        assert.ok(hasComposeHostPlugin('id("com.android.application")'));
        assert.ok(hasComposeHostPlugin("id 'com.android.library'"));
        assert.ok(
            hasComposeHostPlugin('plugins { id("org.jetbrains.compose") }'),
        );
    });

    it("rejects an OkHttp-style plain JVM Gradle project", () => {
        const okhttpLike = `
            plugins {
                kotlin("jvm")
                id("org.jetbrains.kotlin.jvm")
                id("com.vanniktech.maven.publish")
            }
        `;

        assert.ok(!hasComposeHostPlugin(okhttpLike));
    });

    it("rejects apply false declarations", () => {
        assert.ok(
            !hasComposeHostPlugin('id("com.android.library") apply false'),
        );
    });
});

describe("hasPotentialComposeHostPlugin", () => {
    it("keeps bootstrap enabled for Android and Compose aliases", () => {
        assert.ok(
            hasPotentialComposeHostPlugin(
                "alias(libs.plugins.android.application)",
            ),
        );
        assert.ok(
            hasPotentialComposeHostPlugin(
                "alias(libs.plugins.jetbrains.compose)",
            ),
        );
    });

    it("keeps bootstrap enabled for Android or Compose convention plugins", () => {
        assert.ok(hasPotentialComposeHostPlugin('id("acme.android.library")'));
        assert.ok(hasPotentialComposeHostPlugin('id("acme.compose.ui")'));
    });

    it("still rejects plain JVM projects", () => {
        const okhttpLike = `
            plugins {
                kotlin("jvm")
                id("org.jetbrains.kotlin.jvm")
                id("com.vanniktech.maven.publish")
            }
        `;

        assert.ok(!hasPotentialComposeHostPlugin(okhttpLike));
    });

    it("rejects apply false potential declarations", () => {
        assert.ok(
            !hasPotentialComposeHostPlugin(
                "alias(libs.plugins.android.library) apply false",
            ),
        );
    });
});

describe("COMPOSE_HOST_PLUGIN_RE", () => {
    it("does not treat the Kotlin JVM plugin as a Compose host", () => {
        assert.ok(
            !COMPOSE_HOST_PLUGIN_RE.test('id("org.jetbrains.kotlin.jvm")'),
        );
    });
});

describe("POTENTIAL_COMPOSE_HOST_PLUGIN_RE", () => {
    it("matches Android aliases and convention plugin ids", () => {
        assert.ok(
            POTENTIAL_COMPOSE_HOST_PLUGIN_RE.test(
                "alias(libs.plugins.android.application)",
            ),
        );
        assert.ok(
            POTENTIAL_COMPOSE_HOST_PLUGIN_RE.test('id("acme.android.app")'),
        );
    });

    it("does not match Kotlin JVM plugin ids", () => {
        assert.ok(
            !POTENTIAL_COMPOSE_HOST_PLUGIN_RE.test(
                'id("org.jetbrains.kotlin.jvm")',
            ),
        );
    });
});
