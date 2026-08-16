import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { specAtPublishedBaseline } from "../src/viewer/specBaseline.js";

const baseline = "https://example.test/render/com.example.Preview.png";

describe("specAtPublishedBaseline", () => {
    it("accepts the initial server-rendered baseline and link-only parameters", () => {
        assert.equal(specAtPublishedBaseline("snapshot", baseline, null), true);
        assert.equal(
            specAtPublishedBaseline(
                "snapshot",
                `${baseline}?token=secret&session=s1&at=abc`,
                `${baseline}?at=abc`,
            ),
            true,
        );
    });

    it("waits for restored baseline pixels to land", () => {
        const overridden = `${baseline}?theme=dark`;
        assert.equal(
            specAtPublishedBaseline("snapshot", baseline, overridden),
            false,
        );
        assert.equal(
            specAtPublishedBaseline("snapshot", baseline, baseline),
            true,
        );
    });

    it("rejects desired or landed pixel overrides", () => {
        assert.equal(
            specAtPublishedBaseline(
                "snapshot",
                `${baseline}?fontScale=1.3`,
                baseline,
            ),
            false,
        );
        assert.equal(
            specAtPublishedBaseline(
                "snapshot",
                baseline,
                `${baseline}?locale=ar`,
            ),
            false,
        );
    });

    it("answers for the spec lane, which is the only thing that asks", () => {
        // The lane takes the render off the stage and puts the imported reference there, so the
        // stage reads `spec` — but the frame being COMPARED is still the snapshot underneath.
        // Excluding it made this predicate false on every visit to the lane, baseline or not,
        // which is the same as having no signal at all.
        assert.equal(specAtPublishedBaseline("spec", baseline, baseline), true);
        assert.equal(
            specAtPublishedBaseline(
                "spec",
                `${baseline}?themeProvider=com.example.LightMediumContrastTheme`,
                `${baseline}?themeProvider=com.example.LightMediumContrastTheme`,
            ),
            false,
        );
    });

    it("rejects alternate renderers and vector frames", () => {
        assert.equal(
            specAtPublishedBaseline("live", baseline, baseline),
            false,
        );
        assert.equal(
            specAtPublishedBaseline("wasm", baseline, baseline),
            false,
        );
        assert.equal(
            specAtPublishedBaseline(
                "snapshot",
                baseline,
                baseline.replace(/\.png$/, ".svg"),
            ),
            false,
        );
    });
});
