import { strict as assert } from "node:assert";
import { generationEmitted } from "../src/viewer/renderQuery.js";

describe("generationEmitted", () => {
    it("names the generation on an ordinary, override-free browse", () => {
        assert.equal(generationEmitted("abc1234", "", false), true);
    });

    it("stays off a pinned page, which already fixes the publish", () => {
        assert.equal(generationEmitted("abc1234", "def5678", false), false);
    });

    it("stays off an overridden render, which reflects no published bytes", () => {
        assert.equal(generationEmitted("abc1234", "", true), false);
    });

    it("stays off a session with no delivery branch to name one", () => {
        assert.equal(generationEmitted("", "", false), false);
    });
});
