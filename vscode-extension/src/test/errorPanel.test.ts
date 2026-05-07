import * as assert from "assert";
import { buildErrorPanel } from "../webview/preview/errorPanel";
import type { PreviewRenderError, PreviewRenderErrorTopFrame } from "../types";
import type { VsCodeApi } from "../webview/shared/vscode";

interface PostedMessage {
    command: string;
    fileName?: string;
    line?: number;
    className?: string;
}

function fakeVsCode(): {
    api: VsCodeApi<unknown>;
    posted: PostedMessage[];
} {
    const posted: PostedMessage[] = [];
    const api: VsCodeApi<unknown> = {
        postMessage: (msg: unknown) => {
            posted.push(msg as PostedMessage);
        },
        getState: () => undefined,
        setState: () => {},
    };
    return { api, posted };
}

function frame(
    over: Partial<PreviewRenderErrorTopFrame> = {},
): PreviewRenderErrorTopFrame {
    return {
        file: "Previews.kt",
        line: 42,
        function: "MyPreview",
        ...over,
    };
}

function renderError(
    over: Partial<PreviewRenderError> = {},
): PreviewRenderError {
    return {
        schema: "compose-preview-error/v1",
        exception: "java.lang.NullPointerException",
        message: "boom",
        topAppFrame: frame(),
        stackTrace: "java.lang.NullPointerException\n  at Previews.kt:42",
        ...over,
    };
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("buildErrorPanel", () => {
    it("returns a detached element with the error-message + render-error classes and role=alert", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(api, "ignored", null, null);
        assert.strictEqual(panel.parentNode, null, "should be detached");
        assert.strictEqual(panel.tagName, "DIV");
        assert.ok(panel.classList.contains("error-message"));
        assert.ok(panel.classList.contains("render-error"));
        assert.strictEqual(panel.getAttribute("role"), "alert");
    });

    it("falls back to plain message text when renderError is null", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(api, "Render failed", null, null);
        document.body.appendChild(panel);
        assert.strictEqual(panel.textContent, "Render failed");
        assert.strictEqual(
            panel.querySelector(".render-error-class"),
            null,
            "no structured-detail children when renderError is missing",
        );
    });

    it("falls back to empty text when both message and renderError are missing", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(api, null, null, null);
        assert.strictEqual(panel.textContent, "");
    });

    it("renders the simple class name (last dot-segment) of the exception", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ exception: "java.lang.IllegalStateException" }),
            "com.example.A",
        );
        const head = panel.querySelector(".render-error-class")!;
        assert.strictEqual(head.textContent, "IllegalStateException");
    });

    it("falls back to the raw exception string when it has no dots, and to 'Error' when blank", () => {
        const { api } = fakeVsCode();
        const a = buildErrorPanel(
            api,
            null,
            renderError({ exception: "Bare" }),
            null,
        );
        assert.strictEqual(
            a.querySelector(".render-error-class")!.textContent,
            "Bare",
        );

        const b = buildErrorPanel(
            api,
            null,
            renderError({ exception: "" }),
            null,
        );
        assert.strictEqual(
            b.querySelector(".render-error-class")!.textContent,
            "Error",
        );
    });

    it("renders the renderError message in its own .render-error-msg child", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ message: "null pointer at line 42" }),
            null,
        );
        const msg = panel.querySelector(".render-error-msg")!;
        assert.strictEqual(msg.textContent, "null pointer at line 42");
    });

    it("omits the message child when renderError.message is empty", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ message: "" }),
            null,
        );
        assert.strictEqual(panel.querySelector(".render-error-msg"), null);
    });

    it("renders the top-app-frame as a clickable button with file:line in <fn> label", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({
                topAppFrame: frame({
                    file: "Previews.kt",
                    line: 17,
                    function: "MyPreview",
                }),
            }),
            null,
        );
        const btn = panel.querySelector(".render-error-frame") as HTMLElement;
        assert.ok(btn);
        assert.strictEqual(btn.tagName, "BUTTON");
        assert.strictEqual(
            (btn as HTMLButtonElement).type,
            "button",
            "should be a non-submit button",
        );
        assert.strictEqual(btn.textContent, "Previews.kt:17 in MyPreview");
        assert.strictEqual(btn.title, "Open Previews.kt:17");
    });

    it("omits the line suffix when frame.line is 0 (frame missing line info)", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({
                topAppFrame: frame({
                    file: "Previews.kt",
                    line: 0,
                    function: "MyPreview",
                }),
            }),
            null,
        );
        const btn = panel.querySelector(".render-error-frame")!;
        assert.strictEqual(btn.textContent, "Previews.kt in MyPreview");
        assert.strictEqual(
            (btn as HTMLElement).title,
            "Open Previews.kt",
            "title should also drop the :line suffix",
        );
    });

    it("omits the ' in <fn>' suffix when frame.function is empty", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({
                topAppFrame: frame({ function: "" }),
            }),
            null,
        );
        const btn = panel.querySelector(".render-error-frame")!;
        assert.strictEqual(btn.textContent, "Previews.kt:42");
    });

    it("omits the frame button entirely when topAppFrame is null", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ topAppFrame: null }),
            null,
        );
        assert.strictEqual(panel.querySelector(".render-error-frame"), null);
    });

    it("omits the frame button when frame.file is empty", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ topAppFrame: frame({ file: "" }) }),
            null,
        );
        assert.strictEqual(panel.querySelector(".render-error-frame"), null);
    });

    it("posts openSourceFile with file/line/className on frame click", () => {
        const { api, posted } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({
                topAppFrame: frame({ file: "Previews.kt", line: 42 }),
            }),
            "com.example.A",
        );
        const btn = panel.querySelector(".render-error-frame") as HTMLElement;
        btn.click();
        assert.deepStrictEqual(posted, [
            {
                command: "openSourceFile",
                fileName: "Previews.kt",
                line: 42,
                className: "com.example.A",
            },
        ]);
    });

    it("omits className from the openSourceFile payload when not provided", () => {
        const { api, posted } = fakeVsCode();
        const panel = buildErrorPanel(api, null, renderError(), null);
        const btn = panel.querySelector(".render-error-frame") as HTMLElement;
        btn.click();
        assert.strictEqual(posted.length, 1);
        assert.strictEqual(posted[0].className, undefined);
    });

    it("renders a collapsible <details> with the full stack trace when present", () => {
        const { api } = fakeVsCode();
        const trace =
            "java.lang.NullPointerException: boom\n  at Previews.kt:42\n  at More.kt:7";
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ stackTrace: trace }),
            null,
        );
        const details = panel.querySelector(
            "details.render-error-stack",
        ) as HTMLDetailsElement;
        assert.ok(details, "should append a <details> for the trace");
        const summary = details.querySelector("summary")!;
        assert.strictEqual(summary.textContent, "Stack trace");
        const pre = details.querySelector("pre")!;
        assert.strictEqual(pre.textContent, trace);
        assert.strictEqual(
            details.open,
            false,
            "stack trace should be collapsed by default",
        );
    });

    it("omits the stack-trace <details> when stackTrace is empty", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({ stackTrace: "" }),
            null,
        );
        assert.strictEqual(
            panel.querySelector("details.render-error-stack"),
            null,
        );
    });

    it("orders structured children: class, message, frame, stack", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(api, null, renderError(), null);
        const childClasses = Array.from(panel.children).map(
            (c) => (c as HTMLElement).className || c.tagName.toLowerCase(),
        );
        assert.deepStrictEqual(childClasses, [
            "render-error-class",
            "render-error-msg",
            "render-error-frame",
            "render-error-stack",
        ]);
    });

    it("escapes message + stack content as text (no HTML interpretation)", () => {
        const { api } = fakeVsCode();
        const panel = buildErrorPanel(
            api,
            null,
            renderError({
                message: "<img src=x onerror=alert(1)>",
                stackTrace: "<script>alert(2)</script>",
            }),
            null,
        );
        const msg = panel.querySelector(".render-error-msg")!;
        const pre = panel.querySelector("details.render-error-stack pre")!;
        assert.strictEqual(msg.querySelector("img"), null);
        assert.strictEqual(pre.querySelector("script"), null);
        assert.strictEqual(msg.textContent, "<img src=x onerror=alert(1)>");
        assert.strictEqual(pre.textContent, "<script>alert(2)</script>");
    });
});
