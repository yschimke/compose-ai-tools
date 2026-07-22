import * as assert from "assert";
import {
    buildResourceVariantHoverMarkdown,
    captureLabel,
    VARIANT_HOVER_IMG_PX,
} from "../resourceVariantHover";
import { ResourcePreview } from "../types";

const ADAPTIVE: ResourcePreview = {
    id: "mipmap/ic_launcher",
    type: "ADAPTIVE_ICON",
    sourceFiles: {
        "anydpi-v26": "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    },
    captures: [
        {
            variant: {
                qualifiers: "xhdpi",
                shape: "CIRCLE",
                style: "FULL_COLOR",
            },
            renderOutput: "renders/resources/mipmap/full.png",
            cost: 4,
        },
        {
            variant: {
                qualifiers: "xhdpi",
                shape: "SQUIRCLE",
                style: "THEMED_LIGHT",
            },
            renderOutput: "renders/resources/mipmap/themed-light.png",
            cost: 4,
        },
        {
            variant: {
                qualifiers: "xhdpi",
                shape: "SQUIRCLE",
                style: "THEMED_DARK",
            },
            renderOutput: "renders/resources/mipmap/themed-dark.png",
            cost: 4,
        },
        {
            variant: { qualifiers: "xhdpi", shape: null, style: "LEGACY" },
            renderOutput: "renders/resources/mipmap/legacy.png",
            cost: 4,
        },
    ],
};

const VECTOR: ResourcePreview = {
    id: "drawable/ic_compose_logo",
    type: "VECTOR",
    sourceFiles: { "": "src/main/res/drawable/ic_compose_logo.xml" },
    captures: [
        {
            variant: { qualifiers: "xhdpi", shape: null, style: null },
            renderOutput:
                "renders/resources/drawable/ic_compose_logo_xhdpi.png",
            cost: 1,
        },
    ],
};

const ANIMATED: ResourcePreview = {
    id: "drawable/avd_pulse",
    type: "ANIMATED_VECTOR",
    sourceFiles: { "": "src/main/res/drawable/avd_pulse.xml" },
    captures: [
        {
            variant: { qualifiers: "xhdpi", shape: null, style: null },
            renderOutput: "renders/resources/drawable/avd_pulse_xhdpi.gif",
            cost: 35,
        },
    ],
};

describe("captureLabel", () => {
    it("renders adaptive shape + style as a friendly two-part label", () => {
        const label = captureLabel(ADAPTIVE.captures[0]);
        assert.strictEqual(label, "Circle · Full colour");
    });

    it("renders LEGACY (no shape) with just the style", () => {
        const label = captureLabel(ADAPTIVE.captures[3]);
        assert.strictEqual(label, "Legacy");
    });

    it("falls back to the qualifier suffix for vectors with no shape/style", () => {
        const label = captureLabel(VECTOR.captures[0]);
        assert.strictEqual(label, "xhdpi");
    });

    it("returns 'default' for a capture with no variant at all", () => {
        const label = captureLabel({
            variant: null,
            renderOutput: "x.png",
            cost: 1,
        });
        assert.strictEqual(label, "default");
    });

    it("returns 'default' for a variant with no qualifier and no shape/style", () => {
        const label = captureLabel({
            variant: { qualifiers: null, shape: null, style: null },
            renderOutput: "x.png",
            cost: 1,
        });
        assert.strictEqual(label, "default");
    });
});

describe("buildResourceVariantHoverMarkdown", () => {
    it("renders one <img> per capture for an adaptive icon", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: ADAPTIVE,
            images: ADAPTIVE.captures.map((c) => ({
                renderOutput: c.renderOutput,
                base64: "AAAA",
            })),
        });
        // The resource id `_` is escaped so markdown can't read it as italics.
        assert.match(md, /\*\*mipmap\/ic\\_launcher\*\*/);
        assert.match(md, /Adaptive icon/);
        const imgCount = (md.match(/<img /g) ?? []).length;
        assert.strictEqual(imgCount, 4);
        assert.match(md, /title="Circle · Full colour"/);
        assert.match(md, /title="Squircle · Themed light"/);
        assert.match(md, /title="Squircle · Themed dark"/);
        assert.match(md, /title="Legacy"/);
        // PNG mime, fixed image size.
        assert.match(md, /data:image\/png;base64,AAAA/);
        assert.match(md, new RegExp(`width="${VARIANT_HOVER_IMG_PX}"`));
    });

    it("renders a single <img> for a vector drawable", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: VECTOR,
            images: [
                {
                    renderOutput: VECTOR.captures[0].renderOutput,
                    base64: "BBBB",
                },
            ],
        });
        assert.match(md, /Vector drawable/);
        const imgCount = (md.match(/<img /g) ?? []).length;
        assert.strictEqual(imgCount, 1);
        assert.match(md, /title="xhdpi"/);
    });

    it("uses image/gif mime for animated-vector .gif captures", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: ANIMATED,
            images: [
                {
                    renderOutput: ANIMATED.captures[0].renderOutput,
                    base64: "CCCC",
                },
            ],
        });
        assert.match(md, /data:image\/gif;base64,CCCC/);
        assert.doesNotMatch(md, /data:image\/png;base64,CCCC/);
    });

    it("skips captures whose image bytes weren't supplied", () => {
        // Only THEMED_DARK provided. Renderer might have failed on the others,
        // or a tier=fast run might have skipped the heavy captures.
        const md = buildResourceVariantHoverMarkdown({
            resource: ADAPTIVE,
            images: [
                {
                    renderOutput: ADAPTIVE.captures[2].renderOutput,
                    base64: "DDDD",
                },
            ],
        });
        const imgCount = (md.match(/<img /g) ?? []).length;
        assert.strictEqual(imgCount, 1);
        assert.match(md, /title="Squircle · Themed dark"/);
    });

    it("emits a placeholder when no images are available at all", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: ADAPTIVE,
            images: [],
        });
        assert.match(md, /No rendered captures available/);
        assert.doesNotMatch(md, /<img /);
    });

    // Issue #1442: `resources.json` is workspace-controlled; a hostile
    // project could craft `resource.id` / `resource.type` to inject
    // markdown structure (raw `<img>`, `command:` links, italic /
    // emphasis takeover, broken-out HTML) into the hover. The provider
    // renders with `isTrusted = false` so `command:` cannot execute, and
    // the builder additionally escapes structural metacharacters so the
    // rendered hover preserves the user's literal text.
    it("escapes markdown / HTML metacharacters in resource.id and resource.type", () => {
        const hostile: ResourcePreview = {
            id: "drawable/x](command:foo)![pwn",
            type: "<script>alert(1)</script>",
            sourceFiles: { "": "src/main/res/drawable/x.xml" },
            captures: [],
        };
        const md = buildResourceVariantHoverMarkdown({
            resource: hostile,
            images: [],
        });
        // Brackets / parens / `<` / `>` / `!` all escaped — no raw HTML
        // tag, no link constructor, no image constructor survive.
        assert.doesNotMatch(md, /<script>/);
        assert.doesNotMatch(md, /\]\(command:/);
        assert.doesNotMatch(md, /!\[pwn/);
        // The literal escaped form is what reaches the renderer.
        assert.match(md, /drawable\/x\\\]\\\(command:foo\\\)\\!\\\[pwn/);
        assert.match(md, /\\<script\\>alert\\\(1\\\)\\<\/script\\>/);
    });

    it("defangs a leading 'command:' so resource.id can't masquerade as a link target", () => {
        const hostile: ResourcePreview = {
            id: "command:workbench.action.terminal.sendSequence",
            type: "VECTOR",
            sourceFiles: { "": "src/main/res/drawable/x.xml" },
            captures: [],
        };
        const md = buildResourceVariantHoverMarkdown({
            resource: hostile,
            images: [],
        });
        // Bold marker is still there, but `command:` is no longer the
        // literal prefix VS Code would accept as a trusted link target
        // (a zero-width-space is inserted between `command` and `:`).
        assert.doesNotMatch(md, /\*\*command:workbench/);
        assert.match(md, /\*\*command​:workbench/);
    });
});

describe("buildResourceVariantHoverMarkdown — render errors", () => {
    it("shows a ⚠ line with the reason for a capture that has no image but a sidecar error", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: VECTOR,
            images: [],
            errors: [
                {
                    renderOutput:
                        "renders/resources/drawable/ic_compose_logo_xhdpi.png",
                    status: "failed",
                    message: "RuntimeException: can't rasterise",
                },
            ],
        });
        assert.match(md, /⚠/);
        assert.match(md, /render failed/);
        assert.match(md, /can't rasterise/);
        // Not the "nothing available" fallback — we DID have something to say.
        assert.doesNotMatch(md, /No rendered captures available/);
    });

    it("maps each status to a friendly label", () => {
        const skip = buildResourceVariantHoverMarkdown({
            resource: VECTOR,
            images: [],
            errors: [
                {
                    renderOutput:
                        "renders/resources/drawable/ic_compose_logo_xhdpi.png",
                    status: "skipped",
                    message: "no monochrome layer",
                },
            ],
        });
        assert.match(skip, /skipped: no monochrome layer/);
    });

    it("renders images and error lines together for a partially-failed adaptive icon", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: ADAPTIVE,
            images: [
                {
                    renderOutput: "renders/resources/mipmap/full.png",
                    base64: "QUJD",
                },
            ],
            errors: [
                {
                    renderOutput: "renders/resources/mipmap/themed-light.png",
                    status: "failed",
                    message: "boom",
                },
            ],
        });
        assert.match(md, /<img /); // the one that rendered
        assert.match(md, /⚠/);
        assert.match(md, /Themed light/);
        assert.match(md, /render failed: boom/);
    });

    it("still returns the empty fallback when there is neither an image nor an error", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: VECTOR,
            images: [],
            errors: [],
        });
        assert.match(md, /No rendered captures available/);
    });

    it("escapes the error message so a crafted sidecar can't inject markup", () => {
        const md = buildResourceVariantHoverMarkdown({
            resource: VECTOR,
            images: [],
            errors: [
                {
                    renderOutput:
                        "renders/resources/drawable/ic_compose_logo_xhdpi.png",
                    status: "failed",
                    message: "<img src=x onerror=alert(1)>",
                },
            ],
        });
        // The `<` is backslash-escaped (same policy as the resource id), so markdown renders it as
        // literal text rather than an HTML tag — no `<img …>` reaches the DOM unescaped.
        assert.match(md, /\\<img src=x/);
    });
});
