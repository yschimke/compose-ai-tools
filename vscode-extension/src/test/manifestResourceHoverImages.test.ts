// Unit tests for the disk-reader that backs the manifest-resource
// hover. The interesting behaviour is the path-traversal containment
// check — `capture.renderOutput` comes from the workspace
// `resources.json`, which is user-controlled, and must not be allowed
// to escape `<module>/build/compose-previews`.

import * as assert from "assert";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { readVariantImages } from "../manifestResourceHoverImages";
import { ResourcePreview } from "../types";

function makeResource(renderOutputs: string[]): ResourcePreview {
    return {
        id: "drawable/x",
        type: "VECTOR",
        sourceFiles: { "": "src/main/res/drawable/x.xml" },
        captures: renderOutputs.map((renderOutput) => ({
            variant: { qualifiers: "xhdpi", shape: null, style: null },
            renderOutput,
            cost: 1,
        })),
    };
}

describe("readVariantImages", () => {
    let tmpRoot: string;
    let moduleBuildRoot: string;

    beforeEach(() => {
        tmpRoot = fs.mkdtempSync(
            path.join(os.tmpdir(), "manifest-hover-images-"),
        );
        moduleBuildRoot = path.join(tmpRoot, "build", "compose-previews");
        fs.mkdirSync(path.join(moduleBuildRoot, "renders", "resources"), {
            recursive: true,
        });
    });

    afterEach(() => {
        fs.rmSync(tmpRoot, { recursive: true, force: true });
    });

    it("reads bytes for a capture inside the build root", () => {
        const rel = "renders/resources/ok.png";
        fs.writeFileSync(path.join(moduleBuildRoot, rel), Buffer.from([1, 2]));
        const images = readVariantImages(makeResource([rel]), moduleBuildRoot);
        assert.strictEqual(images.length, 1);
        assert.strictEqual(images[0]?.renderOutput, rel);
        assert.strictEqual(
            images[0]?.base64,
            Buffer.from([1, 2]).toString("base64"),
        );
    });

    it("skips captures whose file is missing without throwing", () => {
        const images = readVariantImages(
            makeResource(["renders/resources/missing.png"]),
            moduleBuildRoot,
        );
        assert.deepStrictEqual(images, []);
    });

    // Issue #1442: a crafted workspace `resources.json` with a
    // `renderOutput` like `../../../etc/passwd` could otherwise make
    // `readVariantImages` read arbitrary local files when a user hovers
    // an icon attribute in `AndroidManifest.xml`.
    it("rejects a renderOutput that escapes the build root via ..", () => {
        // Plant a file outside the build root that the traversal would target.
        const sneak = path.join(tmpRoot, "secret.txt");
        fs.writeFileSync(sneak, "very-private");
        // Module root is `<tmp>/build/compose-previews`; `../../secret.txt`
        // resolves to `<tmp>/secret.txt`, which must be refused.
        const images = readVariantImages(
            makeResource(["../../secret.txt"]),
            moduleBuildRoot,
        );
        assert.deepStrictEqual(images, []);
    });

    it("rejects an absolute renderOutput that points outside the build root", () => {
        const sneak = path.join(tmpRoot, "absolute.txt");
        fs.writeFileSync(sneak, "abs-secret");
        const images = readVariantImages(
            makeResource([sneak]),
            moduleBuildRoot,
        );
        assert.deepStrictEqual(images, []);
    });

    it("rejects a sibling path with the build root as a string prefix", () => {
        // The classic `startsWith` mistake: `/foo/bar-evil` starts with
        // `/foo/bar` as a *string*, even though it isn't underneath the
        // `/foo/bar` directory. The fix appends `path.sep` so siblings
        // are correctly refused.
        const sibling = `${moduleBuildRoot}-evil`;
        fs.mkdirSync(sibling, { recursive: true });
        const file = path.join(sibling, "evil.png");
        fs.writeFileSync(file, Buffer.from([9]));
        const images = readVariantImages(makeResource([file]), moduleBuildRoot);
        assert.deepStrictEqual(images, []);
    });

    it("accepts a renderOutput that traverses but lands back inside the root", () => {
        // `renders/../renders/resources/inner.png` ultimately resolves
        // inside the build root, which is fine — the check is about
        // the final resolved location, not the syntactic form.
        const rel = "renders/resources/inner.png";
        fs.writeFileSync(path.join(moduleBuildRoot, rel), Buffer.from([7]));
        const tricky = "renders/../renders/resources/inner.png";
        const images = readVariantImages(
            makeResource([tricky]),
            moduleBuildRoot,
        );
        assert.strictEqual(images.length, 1);
        assert.strictEqual(images[0]?.renderOutput, tricky);
    });
});
