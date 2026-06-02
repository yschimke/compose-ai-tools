// Coverage for the Android SDK resolution used by the bundle viewer's daemon
// launch. Pure module (no `vscode`), so we drive it with explicit inputs:
// real temp directories for the "must be an existing dir" candidates and a
// synthetic `local.properties`.

import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

import { androidSdkEnv, resolveAndroidSdk } from "../androidSdk";

function tmpDir(): string {
    return fs.mkdtempSync(path.join(os.tmpdir(), "compose-sdk-test-"));
}

describe("resolveAndroidSdk", () => {
    it("prefers the setting when it points at an existing directory", () => {
        const setting = tmpDir();
        const home = tmpDir();
        const result = resolveAndroidSdk({
            settingPath: setting,
            env: { ANDROID_HOME: home },
        });
        assert.deepStrictEqual(result, {
            sdkDir: setting,
            source: "setting",
        });
    });

    it("falls through a setting that isn't an existing directory", () => {
        const home = tmpDir();
        const result = resolveAndroidSdk({
            settingPath: path.join(os.tmpdir(), "does-not-exist-xyz"),
            env: { ANDROID_HOME: home },
        });
        assert.deepStrictEqual(result, {
            sdkDir: home,
            source: "ANDROID_HOME",
        });
    });

    it("treats an empty setting as unset", () => {
        const sdkRoot = tmpDir();
        const result = resolveAndroidSdk({
            settingPath: "   ",
            env: { ANDROID_SDK_ROOT: sdkRoot },
        });
        assert.strictEqual(result?.source, "ANDROID_SDK_ROOT");
        assert.strictEqual(result?.sdkDir, sdkRoot);
    });

    it("prefers ANDROID_HOME over ANDROID_SDK_ROOT", () => {
        const home = tmpDir();
        const root = tmpDir();
        const result = resolveAndroidSdk({
            env: { ANDROID_HOME: home, ANDROID_SDK_ROOT: root },
        });
        assert.deepStrictEqual(result, {
            sdkDir: home,
            source: "ANDROID_HOME",
        });
    });

    it("reads sdk.dir from the workspace local.properties when env is empty", () => {
        const sdk = tmpDir();
        const workspaceRoot = tmpDir();
        fs.writeFileSync(
            path.join(workspaceRoot, "local.properties"),
            `# generated\nsdk.dir=${sdk}\n`,
        );
        const result = resolveAndroidSdk({ env: {}, workspaceRoot });
        assert.deepStrictEqual(result, {
            sdkDir: sdk,
            source: "local.properties",
        });
    });

    it("ignores a local.properties whose sdk.dir doesn't exist", () => {
        const workspaceRoot = tmpDir();
        fs.writeFileSync(
            path.join(workspaceRoot, "local.properties"),
            "sdk.dir=/nope/not/here\n",
        );
        const result = resolveAndroidSdk({ env: {}, workspaceRoot });
        assert.strictEqual(result, undefined);
    });

    it("returns undefined when nothing resolves", () => {
        const result = resolveAndroidSdk({
            settingPath: "",
            env: {},
            workspaceRoot: tmpDir(), // exists, but no local.properties
        });
        assert.strictEqual(result, undefined);
    });
});

describe("androidSdkEnv", () => {
    it("sets both ANDROID_HOME and ANDROID_SDK_ROOT", () => {
        const env = androidSdkEnv({
            sdkDir: "/opt/android",
            source: "setting",
        });
        assert.deepStrictEqual(env, {
            ANDROID_HOME: "/opt/android",
            ANDROID_SDK_ROOT: "/opt/android",
        });
    });
});
