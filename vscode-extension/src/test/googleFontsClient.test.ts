// Pins the network client's css2 parsing + fetch plumbing against an
// injected FetchLike (no real network).

import * as assert from "assert";
import {
    downloadFontBytes,
    fetchCss2Faces,
    fetchFontCatalog,
    parseCss2Faces,
    type FetchLike,
    type FetchResponse,
} from "../googleFontsClient";

function jsonResponse(body: string, ok = true, status = 200): FetchResponse {
    return {
        ok,
        status,
        text: async () => body,
        arrayBuffer: async () => new ArrayBuffer(0),
    };
}

function bytesResponse(bytes: Uint8Array, ok = true): FetchResponse {
    return {
        ok,
        status: ok ? 200 : 500,
        text: async () => "",
        arrayBuffer: async () => {
            const ab = new ArrayBuffer(bytes.byteLength);
            new Uint8Array(ab).set(bytes);
            return ab;
        },
    };
}

const CSS2 = `
/* cyrillic */
@font-face {
  font-family: 'Roboto';
  font-style: normal;
  font-weight: 400;
  src: url(https://fonts.gstatic.com/s/roboto/cyr-400.woff2) format('woff2');
}
/* latin */
@font-face {
  font-family: 'Roboto';
  font-style: normal;
  font-weight: 400;
  src: url(https://fonts.gstatic.com/s/roboto/lat-400.woff2) format('woff2');
}
/* latin */
@font-face {
  font-family: 'Roboto';
  font-style: italic;
  font-weight: 100 900;
  src: url(https://fonts.gstatic.com/s/roboto/lat-ital.woff2) format('woff2');
}
`;

describe("googleFontsClient", () => {
    it("parses css2 faces, preferring the latin subset per face", () => {
        const faces = parseCss2Faces(CSS2);
        assert.strictEqual(faces.length, 2);

        const normal = faces.find((f) => f.style === "normal")!;
        assert.strictEqual(normal.weightMin, 400);
        assert.strictEqual(normal.weightMax, 400);
        assert.strictEqual(
            normal.url,
            "https://fonts.gstatic.com/s/roboto/lat-400.woff2",
        );
        assert.strictEqual(normal.format, "woff2");

        const italic = faces.find((f) => f.style === "italic")!;
        assert.strictEqual(italic.weightMin, 100);
        assert.strictEqual(italic.weightMax, 900);
    });

    it("fetches and parses the catalog, stripping the XSSI prefix", async () => {
        const body =
            ")]}'\n" +
            JSON.stringify({
                familyMetadataList: [
                    { family: "Roboto", fonts: { "400": {} }, axes: [] },
                ],
            });
        const fetchImpl: FetchLike = async () => jsonResponse(body);
        const catalog = await fetchFontCatalog(fetchImpl);
        assert.strictEqual(catalog.families[0].family, "Roboto");
    });

    it("throws a clear error on a non-OK catalog response", async () => {
        const fetchImpl: FetchLike = async () => jsonResponse("", false, 503);
        await assert.rejects(() => fetchFontCatalog(fetchImpl), /HTTP 503/);
    });

    it("fetches css2 faces via the injected fetch", async () => {
        const fetchImpl: FetchLike = async () => jsonResponse(CSS2);
        const faces = await fetchCss2Faces("https://x/css2", fetchImpl);
        assert.strictEqual(faces.length, 2);
    });

    it("downloads bytes", async () => {
        const bytes = new Uint8Array([1, 2, 3, 4]);
        const fetchImpl: FetchLike = async () => bytesResponse(bytes);
        const out = await downloadFontBytes("https://x/f.woff2", fetchImpl);
        assert.deepStrictEqual([...out], [1, 2, 3, 4]);
    });

    it("throws on a failed download", async () => {
        const fetchImpl: FetchLike = async () =>
            bytesResponse(new Uint8Array(), false);
        await assert.rejects(
            () => downloadFontBytes("https://x/f.woff2", fetchImpl),
            /download failed/,
        );
    });
});
