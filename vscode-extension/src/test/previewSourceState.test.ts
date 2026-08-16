import * as assert from "assert";
import { sourceMayDifferFromCachedPreviews } from "../previewSourceState";

describe("previewSourceState", () => {
    it("detects a preview declaration missing from an empty file cache", () => {
        const source = `
@androidx.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
fun AddedPreview() = Unit
`;

        assert.strictEqual(sourceMayDifferFromCachedPreviews(source, []), true);
    });

    it("accepts an empty cache for source without preview declarations", () => {
        const source = `
@androidx.compose.runtime.Composable
fun OrdinaryComposable() = Unit
`;

        assert.strictEqual(
            sourceMayDifferFromCachedPreviews(source, []),
            false,
        );
    });

    it("accepts a cached preview that is still declared", () => {
        const source = `
@Preview
@Composable
fun ExistingPreview() = Unit
`;

        assert.strictEqual(
            sourceMayDifferFromCachedPreviews(source, [
                { functionName: "ExistingPreview" },
            ]),
            false,
        );
    });

    it("detects a cached preview removed from source", () => {
        assert.strictEqual(
            sourceMayDifferFromCachedPreviews("fun Replacement() = Unit", [
                { functionName: "RemovedPreview" },
            ]),
            true,
        );
    });

    it("detects a preview added beside an existing cached preview", () => {
        const source = `
@Preview fun ExistingPreview() = Unit
@Preview fun AddedPreview() = Unit
`;
        assert.strictEqual(
            sourceMayDifferFromCachedPreviews(source, [
                { functionName: "ExistingPreview" },
            ]),
            true,
        );
    });

    it("recognizes modifiers before preview functions", () => {
        const source = `
@Preview
@Composable
private suspend fun ExistingPreview() = Unit
`;
        assert.strictEqual(
            sourceMayDifferFromCachedPreviews(source, [
                { functionName: "ExistingPreview" },
            ]),
            false,
        );
    });

    it("recognizes multiline preview annotations", () => {
        const source = `
@Preview(
    name = "Phone",
    device = Devices.PHONE,
)
@Composable
internal fun ExistingPreview() = Unit
`;
        assert.strictEqual(
            sourceMayDifferFromCachedPreviews(source, [
                { functionName: "ExistingPreview" },
            ]),
            false,
        );
    });
});
