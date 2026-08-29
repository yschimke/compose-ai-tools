import "./setup.js";
import assert from "node:assert/strict";
import type { Capture } from "../src/report/store.js";
import { uploadCapture, withUploadedCaptures } from "../src/report/upload.js";

const PNG = "data:image/png;base64,iVBORw0KGgo=";

function capture(overrides: Partial<Capture> = {}): Capture {
    return {
        id: "shot-1",
        label: "Whole view",
        dataUrl: PNG,
        width: 8,
        height: 8,
        page: "/catalog/p/button",
        ...overrides,
    };
}

describe("embedding hosted bug-report captures", () => {
    it("puts every hosted capture directly below the Screenshot heading", () => {
        const body =
            "### What went wrong\n\nExplain.\n\n### Screenshot\n\n<!-- paste -->\n";
        const result = withUploadedCaptures(body, [
            capture({ uploadedUrl: "https://preview.example/i/one.png" }),
            capture({
                id: "shot-2",
                label: "Region",
                uploadedUrl: "https://preview.example/i/two.png",
            }),
        ]);
        assert.match(
            result,
            /### Screenshot\n\n!\[Whole view\]\(https:\/\/preview\.example\/i\/one\.png\)\n\n!\[Region\]\(https:\/\/preview\.example\/i\/two\.png\)\n\n<!-- paste -->/,
        );
    });

    it("leaves the report byte-for-byte alone when no capture has uploaded", () => {
        const body = "### Screenshot\n\n<!-- paste -->\n";
        assert.equal(withUploadedCaptures(body, [capture()]), body);
    });

    it("does not embed an off-origin URL restored from browser storage", () => {
        const body = "### Screenshot\n\n<!-- paste -->\n";
        assert.equal(
            withUploadedCaptures(body, [
                capture({
                    uploadedUrl: "https://tracker.example/i/stolen.png",
                }),
            ]),
            body,
        );
    });

    it("neutralises markdown punctuation in capture labels", () => {
        const result = withUploadedCaptures("### Screenshot\n", [
            capture({
                label: "bad [label]\\\nnext",
                uploadedUrl: "https://preview.example/i/one.png",
            }),
        ]);
        assert.doesNotMatch(result, /\[label\]/);
        assert.match(result, /!\[bad  label   next\]/);
    });
});

describe("uploading a browser capture", () => {
    const originalFetch = globalThis.fetch;

    afterEach(() => {
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: originalFetch,
        });
        history.replaceState({}, "", "/catalog/");
    });

    it("posts PNG bytes with the private browse token and accepts this origin's image URL", async () => {
        history.replaceState({}, "", "/report-bug?token=browse-secret");
        const requests: string[] = [];
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: (input: string | URL, init?: RequestInit) => {
                const url = String(input);
                requests.push(url);
                if (url.startsWith("data:")) {
                    return Promise.resolve({
                        blob: () =>
                            Promise.resolve(
                                new Blob(["png"], { type: "image/png" }),
                            ),
                    });
                }
                assert.equal(init?.method, "POST");
                return Promise.resolve({
                    ok: true,
                    json: () =>
                        Promise.resolve({
                            url: "https://preview.example/i/report_123.png",
                        }),
                });
            },
        });
        const uploaded = await uploadCapture(capture());
        assert.equal(uploaded.url, "https://preview.example/i/report_123.png");
        assert.match(
            requests[1],
            /\/images\?name=bug-report-shot-1\.png&token=browse-secret$/,
        );
    });

    it("refuses an off-origin URL even when the upload answers successfully", async () => {
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: (input: string | URL) =>
                String(input).startsWith("data:")
                    ? Promise.resolve({
                          blob: () => Promise.resolve(new Blob(["png"])),
                      })
                    : Promise.resolve({
                          ok: true,
                          json: () =>
                              Promise.resolve({
                                  url: "https://tracker.example/i/stolen.png",
                              }),
                      }),
        });
        await assert.rejects(() => uploadCapture(capture()), /unsafe URL/);
    });

    it("reuploads instead of trusting an off-origin URL restored from storage", async () => {
        let uploads = 0;
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: (input: string | URL) => {
                if (String(input).startsWith("data:")) {
                    return Promise.resolve({
                        blob: () => Promise.resolve(new Blob(["png"])),
                    });
                }
                uploads += 1;
                return Promise.resolve({
                    ok: true,
                    json: () =>
                        Promise.resolve({
                            url: "https://preview.example/i/fresh.png",
                        }),
                });
            },
        });
        const result = await uploadCapture(
            capture({
                uploadedUrl: "https://tracker.example/i/stolen.png",
            }),
        );
        assert.equal(uploads, 1);
        assert.equal(result.url, "https://preview.example/i/fresh.png");
    });
});
