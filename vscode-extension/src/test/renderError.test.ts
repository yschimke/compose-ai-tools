import * as assert from "assert";
import { formatRenderErrorMessage } from "../renderError";
import { PreviewRenderError } from "../types";

function err(overrides: Partial<PreviewRenderError>): PreviewRenderError {
    return {
        schema: "compose-preview-error/v1",
        exception: "java.lang.RuntimeException",
        message: "",
        topAppFrame: null,
        stackTrace: "",
        ...overrides,
    };
}

describe("formatRenderErrorMessage", () => {
    it("strips the FQN prefix from the exception class", () => {
        const out = formatRenderErrorMessage(
            err({ exception: "java.lang.NullPointerException" }),
        );
        assert.strictEqual(out, "NullPointerException");
    });

    it("keeps the simple-name when the exception has no package", () => {
        const out = formatRenderErrorMessage(err({ exception: "CustomError" }));
        assert.strictEqual(out, "CustomError");
    });

    it("appends the message when one is present", () => {
        const out = formatRenderErrorMessage(
            err({
                exception: "java.lang.NullPointerException",
                message: "LocalContext was null",
            }),
        );
        assert.strictEqual(out, "NullPointerException: LocalContext was null");
    });

    it("omits the message segment when it is the empty string", () => {
        const out = formatRenderErrorMessage(
            err({
                exception: "kotlin.NotImplementedError",
                message: "",
            }),
        );
        // No "ClassName: " prefix when message is empty — empty message
        // would render as a confusing dangling colon.
        assert.strictEqual(out, "NotImplementedError");
    });

    it("appends the top app frame when both file and line are known", () => {
        const out = formatRenderErrorMessage(
            err({
                exception: "java.lang.NullPointerException",
                message: "LocalContext was null",
                topAppFrame: {
                    file: "Previews.kt",
                    line: 47,
                    function: "HomeScreen",
                },
            }),
        );
        assert.strictEqual(
            out,
            "NullPointerException: LocalContext was null (at Previews.kt:47 in HomeScreen)",
        );
    });

    it("omits the line when line is 0 (frame missing line info)", () => {
        // Native frames or anonymous classes can have line=0; we still
        // surface the file + function but drop the line: prefix so the
        // message doesn't read as "Previews.kt:0".
        const out = formatRenderErrorMessage(
            err({
                exception: "java.lang.IllegalStateException",
                message: "oops",
                topAppFrame: {
                    file: "Previews.kt",
                    line: 0,
                    function: "lambda$0",
                },
            }),
        );
        assert.strictEqual(
            out,
            "IllegalStateException: oops (at Previews.kt in lambda$0)",
        );
    });

    it("omits the function suffix when function is empty", () => {
        const out = formatRenderErrorMessage(
            err({
                exception: "java.lang.IllegalArgumentException",
                message: "bad arg",
                topAppFrame: { file: "Previews.kt", line: 12, function: "" },
            }),
        );
        assert.strictEqual(
            out,
            "IllegalArgumentException: bad arg (at Previews.kt:12)",
        );
    });

    it("drops the topAppFrame block entirely when file is empty", () => {
        // Renderer returns frame.file='' when the StackTraceElement
        // didn't carry a fileName (e.g. native methods). Don't render
        // a useless "(at )" suffix.
        const out = formatRenderErrorMessage(
            err({
                exception: "java.lang.RuntimeException",
                message: "kaboom",
                topAppFrame: { file: "", line: 5, function: "unknown" },
            }),
        );
        assert.strictEqual(out, "RuntimeException: kaboom");
    });

    it("handles the no-message + no-frame case", () => {
        const out = formatRenderErrorMessage(
            err({
                exception: "java.lang.RuntimeException",
            }),
        );
        assert.strictEqual(out, "RuntimeException");
    });
});
