// Unit tests for the `resources/used` resolver core. The core module
// lives in pure string-land — no `vscode` runtime import — so Mocha
// can drive it without an extension host. The thin `openResourceFile`
// wrapper in `resourceFileResolver.ts` is covered by the e2e harness.

import * as assert from "assert";
import {
    findValuesEntry,
    isValuesType,
    resolveResourceFilePath,
    type ResolverFs,
} from "../resourceFileResolverCore";

interface FakeFs extends ResolverFs {
    findFilesCalls: { include: string; exclude: string | null }[];
}

function buildFs(overrides?: {
    files?: Record<string, string[]>;
    contents?: Record<string, string>;
    existing?: Set<string>;
}): FakeFs {
    const files = overrides?.files ?? {};
    const contents = overrides?.contents ?? {};
    const existing = overrides?.existing ?? new Set<string>();
    const findFilesCalls: { include: string; exclude: string | null }[] = [];
    return {
        findFilesCalls,
        findFiles: async (include, exclude) => {
            findFilesCalls.push({ include, exclude });
            return files[include] ?? [];
        },
        readTextFile: async (path) => contents[path] ?? "",
        fileExists: (path) => existing.has(path),
        isAbsolutePath: (path) => path.startsWith("/"),
    };
}

describe("findValuesEntry", () => {
    it("locates the value of a name attribute for a typed element", () => {
        const text = `<resources>
  <color name="primary">#FF112233</color>
  <color name="secondary">#FF445566</color>
</resources>`;
        const range = findValuesEntry(text, "color", "secondary");
        assert.ok(range);
        assert.strictEqual(text.slice(range!.start, range!.end), "secondary");
    });

    it("returns null when the element type doesn't match", () => {
        const text = `<resources>
  <string name="primary">Hello</string>
</resources>`;
        assert.strictEqual(findValuesEntry(text, "color", "primary"), null);
    });

    it("accepts string-array, integer-array, and array when type is `array`", () => {
        const text = `<resources>
  <string-array name="seasons">
    <item>Spring</item>
  </string-array>
  <integer-array name="counts" />
  <array name="raw" />
</resources>`;
        assert.ok(findValuesEntry(text, "array", "seasons"));
        assert.ok(findValuesEntry(text, "array", "counts"));
        assert.ok(findValuesEntry(text, "array", "raw"));
    });

    it("tolerates extra attributes before the name attribute", () => {
        const text = `<resources>
  <color tools:override="true" name="primary">#FF000000</color>
</resources>`;
        const range = findValuesEntry(text, "color", "primary");
        assert.ok(range);
        assert.strictEqual(text.slice(range!.start, range!.end), "primary");
    });

    it("escapes regex metacharacters in the resource name", () => {
        const text = `<resources>
  <string name="foo.bar">Hello</string>
</resources>`;
        const range = findValuesEntry(text, "string", "foo.bar");
        assert.ok(range);
        // "foo.bar" the literal — confirms the regex didn't treat `.`
        // as any-char and accidentally pick up a similarly-shaped name.
        assert.strictEqual(text.slice(range!.start, range!.end), "foo.bar");
    });
});

describe("isValuesType", () => {
    it("recognises every Android values-style resource type", () => {
        for (const type of [
            "string",
            "color",
            "dimen",
            "bool",
            "integer",
            "array",
            "plurals",
        ]) {
            assert.strictEqual(isValuesType(type), true, type);
        }
    });

    it("rejects file-style resource types", () => {
        for (const type of [
            "drawable",
            "mipmap",
            "raw",
            "layout",
            "menu",
            "xml",
        ]) {
            assert.strictEqual(isValuesType(type), false, type);
        }
    });
});

describe("resolveResourceFilePath", () => {
    it("returns the resolvedFile path when it's an existing absolute path", async () => {
        const target = "/abs/app/src/main/res/values/colors.xml";
        const fs = buildFs({ existing: new Set([target]) });
        const result = await resolveResourceFilePath(
            {
                resourceType: "color",
                resourceName: "primary",
                resolvedFile: target,
                packageName: "com.example.app",
            },
            fs,
        );
        assert.strictEqual(result, target);
        assert.strictEqual(
            fs.findFilesCalls.length,
            0,
            "absolute existing path should not need a workspace search",
        );
    });

    it("globs the workspace for a project-relative res path", async () => {
        const target = "/abs/app/src/main/res/values/strings.xml";
        const fs = buildFs({
            files: { "**/res/values/strings.xml": [target] },
        });
        const result = await resolveResourceFilePath(
            {
                resourceType: "string",
                resourceName: "hello",
                resolvedFile: "res/values/strings.xml",
                packageName: "com.example.app",
            },
            fs,
        );
        assert.strictEqual(result, target);
        assert.strictEqual(
            fs.findFilesCalls[0].exclude,
            "**/build/**",
            "workspace globs must exclude build outputs",
        );
    });

    it("falls back to a values-XML scan when resolvedFile is missing", async () => {
        const valuesXml = "/abs/app/src/main/res/values/strings.xml";
        const fs = buildFs({
            files: { "**/res/values*/**/*.xml": [valuesXml] },
            contents: {
                [valuesXml]: `<resources>
  <string name="hello">Hello</string>
</resources>`,
            },
        });
        const result = await resolveResourceFilePath(
            {
                resourceType: "string",
                resourceName: "hello",
                resolvedFile: null,
                packageName: "com.example.app",
            },
            fs,
        );
        assert.strictEqual(result, valuesXml);
    });

    it("scans past values XMLs that don't carry the entry", async () => {
        const wrong = "/abs/app/src/main/res/values/colors.xml";
        const right = "/abs/app/src/main/res/values/strings.xml";
        const fs = buildFs({
            files: {
                "**/res/values*/**/*.xml": [wrong, right],
            },
            contents: {
                [wrong]: `<resources>
  <color name="primary">#FF000000</color>
</resources>`,
                [right]: `<resources>
  <string name="hello">Hello</string>
</resources>`,
            },
        });
        const result = await resolveResourceFilePath(
            {
                resourceType: "string",
                resourceName: "hello",
                resolvedFile: null,
                packageName: "com.example.app",
            },
            fs,
        );
        assert.strictEqual(result, right);
    });

    it("falls back to a name-glob for file-style resource types", async () => {
        const png = "/abs/app/src/main/res/drawable-xxhdpi/ic_launcher.png";
        const fs = buildFs({
            files: { "**/res/drawable*/ic_launcher.*": [png] },
        });
        const result = await resolveResourceFilePath(
            {
                resourceType: "drawable",
                resourceName: "ic_launcher",
                resolvedFile: null,
                packageName: "com.example.app",
            },
            fs,
        );
        assert.strictEqual(result, png);
    });

    it("returns null when nothing resolves", async () => {
        const fs = buildFs();
        const result = await resolveResourceFilePath(
            {
                resourceType: "string",
                resourceName: "absent",
                resolvedFile: null,
                packageName: null,
            },
            fs,
        );
        assert.strictEqual(result, null);
    });
});
