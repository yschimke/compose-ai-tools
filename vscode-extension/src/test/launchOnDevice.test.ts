import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    buildPreviewActivityAmStartArgs,
    collectAndroidApplicationModules,
    findAndroidSdkRoot,
    parseAndroidApplicationInfo,
    resolveAdbPath,
} from "../launchOnDevice";

function withTempDir(
    fn: (dir: string) => void | Promise<void>,
): () => Promise<void> {
    return async () => {
        const dir = fs.mkdtempSync(
            path.join(os.tmpdir(), "compose-preview-launch-"),
        );
        try {
            await fn(dir);
        } finally {
            fs.rmSync(dir, { recursive: true, force: true });
        }
    };
}

describe("parseAndroidApplicationInfo", () => {
    it("extracts applicationId when literal id() is used", () => {
        const content = `
            plugins {
                id("com.android.application")
                id("ee.schimke.composeai.preview")
            }
            android {
                namespace = "com.example.foo"
                defaultConfig {
                    applicationId = "com.example.foo"
                }
            }
        `;
        assert.deepStrictEqual(parseAndroidApplicationInfo(content), {
            applicationId: "com.example.foo",
        });
    });

    it("extracts applicationId when version-catalog alias is used", () => {
        const content = `
            plugins {
                alias(libs.plugins.android.application)
            }
            android {
                defaultConfig { applicationId = "com.example.bar" }
            }
        `;
        assert.deepStrictEqual(parseAndroidApplicationInfo(content), {
            applicationId: "com.example.bar",
        });
    });

    it("returns null for an Android library", () => {
        const content = `
            plugins {
                id("com.android.library")
            }
            android {
                defaultConfig { applicationId = "ignored.because.library" }
            }
        `;
        assert.strictEqual(parseAndroidApplicationInfo(content), null);
    });

    it("returns null when applicationId is missing", () => {
        const content = `
            plugins { id("com.android.application") }
            android { defaultConfig { } }
        `;
        assert.strictEqual(parseAndroidApplicationInfo(content), null);
    });

    it("skips apply-false declarations (root build pattern)", () => {
        const content = `
            plugins {
                id("com.android.application") apply false
            }
            applicationId = "ignored.because.apply.false"
        `;
        assert.strictEqual(parseAndroidApplicationInfo(content), null);
    });

    it("handles single-quoted id()", () => {
        const content = `
            plugins { id 'com.android.application' }
            applicationId = "com.example.sq"
        `;
        assert.deepStrictEqual(parseAndroidApplicationInfo(content), {
            applicationId: "com.example.sq",
        });
    });
});

describe("resolveAdbPath", () => {
    it("returns the bare name when no sdk root is given", () => {
        assert.strictEqual(resolveAdbPath(null), "adb");
    });

    it("joins platform-tools when sdk root is given", () => {
        const sdk = path.join(os.tmpdir(), "fake-sdk");
        const got = resolveAdbPath(sdk);
        const expected = path.join(
            sdk,
            "platform-tools",
            process.platform === "win32" ? "adb.exe" : "adb",
        );
        assert.strictEqual(got, expected);
    });
});

describe("findAndroidSdkRoot", () => {
    it(
        "reads sdk.dir from local.properties",
        withTempDir((dir) => {
            const fakeSdk = fs.mkdtempSync(path.join(os.tmpdir(), "fake-sdk-"));
            try {
                fs.writeFileSync(
                    path.join(dir, "local.properties"),
                    `sdk.dir=${fakeSdk}\n`,
                );
                const original = {
                    home: process.env.ANDROID_HOME,
                    root: process.env.ANDROID_SDK_ROOT,
                };
                delete process.env.ANDROID_HOME;
                delete process.env.ANDROID_SDK_ROOT;
                try {
                    assert.strictEqual(findAndroidSdkRoot(dir), fakeSdk);
                } finally {
                    if (original.home !== undefined) {
                        process.env.ANDROID_HOME = original.home;
                    }
                    if (original.root !== undefined) {
                        process.env.ANDROID_SDK_ROOT = original.root;
                    }
                }
            } finally {
                fs.rmSync(fakeSdk, { recursive: true, force: true });
            }
        }),
    );

    it(
        "prefers ANDROID_HOME over local.properties when both are set",
        withTempDir((dir) => {
            const fakeEnvSdk = fs.mkdtempSync(
                path.join(os.tmpdir(), "env-sdk-"),
            );
            const fakePropsSdk = fs.mkdtempSync(
                path.join(os.tmpdir(), "props-sdk-"),
            );
            try {
                fs.writeFileSync(
                    path.join(dir, "local.properties"),
                    `sdk.dir=${fakePropsSdk}\n`,
                );
                const originalHome = process.env.ANDROID_HOME;
                process.env.ANDROID_HOME = fakeEnvSdk;
                try {
                    assert.strictEqual(findAndroidSdkRoot(dir), fakeEnvSdk);
                } finally {
                    if (originalHome !== undefined) {
                        process.env.ANDROID_HOME = originalHome;
                    } else {
                        delete process.env.ANDROID_HOME;
                    }
                }
            } finally {
                fs.rmSync(fakeEnvSdk, { recursive: true, force: true });
                fs.rmSync(fakePropsSdk, { recursive: true, force: true });
            }
        }),
    );
});

describe("buildPreviewActivityAmStartArgs", () => {
    it("targets PreviewActivity with the composable FQN", () => {
        const args = buildPreviewActivityAmStartArgs({
            applicationId: "com.example.app",
            composableFqn: "com.example.PreviewsKt.MyPreview",
            parameterProviderClassName: null,
        });
        assert.deepStrictEqual(args, [
            "shell",
            "am",
            "start",
            "-n",
            "com.example.app/androidx.compose.ui.tooling.PreviewActivity",
            "--es",
            "composable",
            "com.example.PreviewsKt.MyPreview",
        ]);
    });

    it("forwards parameterProviderClassName when present", () => {
        const args = buildPreviewActivityAmStartArgs({
            applicationId: "com.example.app",
            composableFqn: "com.example.PreviewsKt.ParamPreview",
            parameterProviderClassName: "com.example.PreviewsKt$ToggleProvider",
        });
        assert.deepStrictEqual(args, [
            "shell",
            "am",
            "start",
            "-n",
            "com.example.app/androidx.compose.ui.tooling.PreviewActivity",
            "--es",
            "composable",
            "com.example.PreviewsKt.ParamPreview",
            "--es",
            "parameterProviderClassName",
            "com.example.PreviewsKt$ToggleProvider",
        ]);
    });
});

describe("collectAndroidApplicationModules", () => {
    it(
        "keeps only modules with com.android.application + applicationId",
        withTempDir((dir) => {
            const app = path.join(dir, "samples", "app");
            const lib = path.join(dir, "samples", "lib");
            const cmp = path.join(dir, "samples", "cmp");
            for (const d of [app, lib, cmp]) {
                fs.mkdirSync(d, { recursive: true });
            }
            fs.writeFileSync(
                path.join(app, "build.gradle.kts"),
                `
            plugins { id("com.android.application") }
            android { defaultConfig { applicationId = "com.example.app" } }
        `,
            );
            fs.writeFileSync(
                path.join(lib, "build.gradle.kts"),
                `
            plugins { id("com.android.library") }
        `,
            );
            fs.writeFileSync(
                path.join(cmp, "build.gradle.kts"),
                `
            plugins { kotlin("multiplatform") }
        `,
            );

            const got = collectAndroidApplicationModules(dir, [
                { projectDir: "samples/app", modulePath: ":samples:app" },
                { projectDir: "samples/lib", modulePath: ":samples:lib" },
                { projectDir: "samples/cmp", modulePath: ":samples:cmp" },
            ]);
            assert.deepStrictEqual(got, [
                {
                    module: {
                        projectDir: "samples/app",
                        modulePath: ":samples:app",
                    },
                    applicationId: "com.example.app",
                },
            ]);
        }),
    );

    it(
        "returns sorted output",
        withTempDir((dir) => {
            for (const name of ["z-app", "a-app"]) {
                const m = path.join(dir, name);
                fs.mkdirSync(m, { recursive: true });
                fs.writeFileSync(
                    path.join(m, "build.gradle.kts"),
                    `
                plugins { id("com.android.application") }
                android { defaultConfig { applicationId = "com.example.${name.replace("-", "")}" } }
            `,
                );
            }
            const got = collectAndroidApplicationModules(dir, [
                { projectDir: "z-app", modulePath: ":z-app" },
                { projectDir: "a-app", modulePath: ":a-app" },
            ]);
            assert.deepStrictEqual(
                got.map((x) => x.module.projectDir),
                ["a-app", "z-app"],
            );
        }),
    );
});
