import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    VERSION_PIN_ENV,
    readCatalogPin,
    readGradlePropertiesPin,
    resolvePluginVersion,
    resolveVersionPin,
} from "../versionPin";

/**
 * Mirrors the CLI's `VersionPinTest` — same sources, same precedence. The two
 * implementations are separate on purpose (Kotlin binary vs. VSIX), so the
 * tests are what keep them honest about honouring the same pin (issue #3738).
 */

function withProject(
    files: { gradleProperties?: string; catalog?: string },
    fn: (dir: string) => void,
): void {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "compose-preview-pin-"));
    try {
        if (files.gradleProperties !== undefined) {
            fs.writeFileSync(
                path.join(dir, "gradle.properties"),
                files.gradleProperties,
                "utf-8",
            );
        }
        if (files.catalog !== undefined) {
            fs.mkdirSync(path.join(dir, "gradle"), { recursive: true });
            fs.writeFileSync(
                path.join(dir, "gradle", "libs.versions.toml"),
                files.catalog,
                "utf-8",
            );
        }
        fn(dir);
    } finally {
        fs.rmSync(dir, { recursive: true });
    }
}

describe("versionPin", () => {
    it("returns undefined when nothing pins a version", () => {
        withProject({}, (dir) => {
            assert.strictEqual(resolveVersionPin(dir, {}), undefined);
        });
    });

    it("reads the pin from gradle.properties", () => {
        withProject(
            { gradleProperties: "composePreview.version=1.2.3\n" },
            (dir) => {
                const pin = resolveVersionPin(dir, {});
                assert.strictEqual(pin?.version, "1.2.3");
                assert.strictEqual(
                    pin?.source,
                    "gradle.properties (composePreview.version)",
                );
            },
        );
    });

    it("tolerates whitespace, the colon form and a leading v", () => {
        withProject(
            { gradleProperties: "composePreview.version : v1.2.3  \n" },
            (dir) => {
                assert.strictEqual(readGradlePropertiesPin(dir), "1.2.3");
            },
        );
    });

    it("ignores a commented-out pin", () => {
        withProject(
            { gradleProperties: "# composePreview.version=9.9.9\n" },
            (dir) => {
                assert.strictEqual(readGradlePropertiesPin(dir), undefined);
            },
        );
    });

    it("treats an empty pin value as absent", () => {
        withProject(
            { gradleProperties: "composePreview.version=\n" },
            (dir) => {
                assert.strictEqual(readGradlePropertiesPin(dir), undefined);
            },
        );
    });

    it("accepts a bare whitespace separator", () => {
        // `key value` is a legal properties assignment that the CLI's
        // Properties.load reads. Missing it here would leave the extension on
        // its bundled version while the CLI injected the pin.
        withProject(
            { gradleProperties: "composePreview.version 1.2.3\n" },
            (dir) => {
                assert.strictEqual(readGradlePropertiesPin(dir), "1.2.3");
            },
        );
    });

    it("does not match a similarly named key", () => {
        withProject(
            { gradleProperties: "composePreview.versionCode=42\n" },
            (dir) => {
                assert.strictEqual(readGradlePropertiesPin(dir), undefined);
            },
        );
    });

    it("resolves duplicate assignments to the last, as Properties does", () => {
        withProject(
            {
                gradleProperties:
                    "composePreview.version=1.0.0\ncomposePreview.version=2.0.0\n",
            },
            (dir) => {
                assert.strictEqual(readGradlePropertiesPin(dir), "2.0.0");
            },
        );
    });

    it("reads the pin from the version catalog", () => {
        withProject(
            {
                catalog: [
                    "[versions]",
                    'agp = "9.1.1"',
                    'composePreviewCli = "1.0.5"',
                    "",
                    "[plugins]",
                    'android = { id = "com.android.application", version.ref = "agp" }',
                ].join("\n"),
            },
            (dir) => {
                const pin = resolveVersionPin(dir, {});
                assert.strictEqual(pin?.version, "1.0.5");
                assert.strictEqual(
                    pin?.source,
                    "gradle/libs.versions.toml ([versions] composePreviewCli)",
                );
            },
        );
    });

    it("scopes the catalog lookup to the [versions] table", () => {
        withProject(
            {
                catalog: [
                    "[versions]",
                    'agp = "9.1.1"',
                    "",
                    "[libraries]",
                    'composePreviewCli = "not-a-version"',
                ].join("\n"),
            },
            (dir) => {
                assert.strictEqual(readCatalogPin(dir), undefined);
            },
        );
    });

    it("prefers gradle.properties over the version catalog", () => {
        withProject(
            {
                gradleProperties: "composePreview.version=2.0.0\n",
                catalog: '[versions]\ncomposePreviewCli = "1.0.5"\n',
            },
            (dir) => {
                assert.strictEqual(
                    resolveVersionPin(dir, {})?.version,
                    "2.0.0",
                );
            },
        );
    });

    it("prefers the environment over both files", () => {
        withProject(
            {
                gradleProperties: "composePreview.version=2.0.0\n",
                catalog: '[versions]\ncomposePreviewCli = "1.0.5"\n',
            },
            (dir) => {
                const pin = resolveVersionPin(dir, {
                    [VERSION_PIN_ENV]: "3.0.0",
                });
                assert.strictEqual(pin?.version, "3.0.0");
                assert.strictEqual(pin?.source, "COMPOSE_PREVIEW_VERSION");
            },
        );
    });

    it("falls back to the bundled version when nothing is pinned", () => {
        withProject({}, (dir) => {
            assert.strictEqual(
                resolvePluginVersion(dir, {}, "9.9.9-bundled"),
                "9.9.9-bundled",
            );
        });
    });

    it("uses the pin as the injected plugin version", () => {
        withProject(
            { gradleProperties: "composePreview.version=1.0.5\n" },
            (dir) => {
                assert.strictEqual(
                    resolvePluginVersion(dir, {}, "9.9.9-bundled"),
                    "1.0.5",
                );
            },
        );
    });
});
