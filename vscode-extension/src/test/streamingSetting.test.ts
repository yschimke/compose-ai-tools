// Pins the `composePreview.streaming.enabled` setting reader so the
// canonical key spelling, default, and read-through-fresh behaviour can't
// drift away from `package.json`.

import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import {
    STREAMING_ENABLED_SETTING,
    streamingEnabled,
    type ConfigReader,
} from "../daemon/streamingSetting";

describe("streamingEnabled", () => {
    it("defaults to false when the setting is unset", () => {
        const reader: ConfigReader = (_section, _key, fallback) => fallback;
        assert.strictEqual(streamingEnabled(reader), false);
    });

    it("reads the user's true override", () => {
        const reader: ConfigReader = (_section, _key, _fallback) =>
            true as never;
        assert.strictEqual(streamingEnabled(reader), true);
    });

    it("queries the documented (section, key) pair", () => {
        const seen: Array<{ section: string; key: string }> = [];
        const reader: ConfigReader = (section, key, fallback) => {
            seen.push({ section, key });
            return fallback;
        };
        streamingEnabled(reader);
        assert.deepStrictEqual(seen, [
            { section: "composePreview", key: "streaming.enabled" },
        ]);
    });

    it("matches the fully-qualified key advertised in package.json", () => {
        // STREAMING_ENABLED_SETTING is the dot-joined "composePreview.streaming.enabled"
        // key. The package.json registers the same key under the
        // `contributes.configuration.properties` block; if either drifts,
        // VS Code silently returns the default and the user's opt-in is dead.
        assert.strictEqual(
            STREAMING_ENABLED_SETTING,
            "composePreview.streaming.enabled",
        );
        const pkg = JSON.parse(
            fs.readFileSync(
                path.join(__dirname, "..", "..", "package.json"),
                "utf-8",
            ),
        ) as {
            contributes: {
                configuration: {
                    properties: Record<
                        string,
                        { type: string; default: unknown }
                    >;
                };
            };
        };
        const advertised =
            pkg.contributes.configuration.properties[STREAMING_ENABLED_SETTING];
        assert.ok(
            advertised,
            `package.json must advertise ${STREAMING_ENABLED_SETTING}`,
        );
        assert.strictEqual(advertised.type, "boolean");
        assert.strictEqual(
            advertised.default,
            false,
            "default must remain false until the streaming path is the stable default",
        );
    });
});
