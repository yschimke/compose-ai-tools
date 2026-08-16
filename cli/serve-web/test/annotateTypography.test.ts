// Typography as styles rather than instances, as a table.

import assert from "node:assert/strict";
import {
    clusterTypography,
    expandedBoxesTouch,
    pixelScaleOf,
    unionBounds,
} from "../src/annotate/clusters.js";
import { fieldState, showsOptional } from "../src/annotate/fieldState.js";
import type { AnnotationItem } from "../src/annotate/match.js";
import {
    TYPOGRAPHY_MATCH_CUTOFF,
    annotationNumber,
    annotationUnit,
    groupTypography,
    pairTypography,
    typographyAxes,
    typographyComparableValue,
    typographyDefaults,
    typographyDistance,
    typographyFamily,
    typographyGroupKey,
    typographySpec,
    typographyToken,
    typographyValue,
    type TypographyGroup,
} from "../src/annotate/typography.js";

const b = (x: number, y: number, width: number, height: number) => ({
    x,
    y,
    width,
    height,
});
const usage = (
    detail: Record<string, unknown>,
    over: Partial<AnnotationItem> = {},
): AnnotationItem => ({
    kind: "typography",
    bounds: b(0, 0, 10, 10),
    detail,
    ...over,
});
const groupOf = (
    detail: Record<string, unknown>,
    over: Partial<AnnotationItem> = {},
): TypographyGroup => groupTypography([usage(detail, over)])[0];

describe("annotationNumber", () => {
    it("reads a number with or without a unit", () => {
        assert.equal(annotationNumber("16sp"), 16);
        assert.equal(annotationNumber("16 sp"), 16);
        assert.equal(annotationNumber("1.5em"), 1.5);
        assert.equal(annotationNumber(".5"), 0.5);
        assert.equal(annotationNumber(-3), -3);
    });

    it("treats ZERO as a value, not as absent", () => {
        // The guard is `=== ""`, not falsiness. A `!value` refactor turns a real 0 tracking or 0
        // line height into "unspecified", which reads as missing data rather than a deliberate zero.
        assert.equal(annotationNumber(0), 0);
        assert.equal(annotationNumber("0"), 0);
    });

    it("has no answer for anything that is not a measurement", () => {
        assert.equal(annotationNumber(""), undefined);
        assert.equal(annotationNumber(null), undefined);
        assert.equal(annotationNumber(undefined), undefined);
        assert.equal(annotationNumber("auto"), undefined);
    });
});

describe("annotationUnit", () => {
    it("takes the trailing unit, lower-cased", () => {
        assert.equal(annotationUnit("16SP"), "sp");
        assert.equal(annotationUnit("100%"), "%");
        assert.equal(annotationUnit("16"), undefined);
    });
});

describe("typographyToken", () => {
    it("normalises a Material token to camelCase, either spelling", () => {
        assert.equal(typographyToken({ token: "M3/body/Large" }), "bodyLarge");
        assert.equal(
            typographyToken({ token: "m3-title-small" }),
            "titleSmall",
        );
    });

    it("drops the tool's placeholder", () => {
        // "text" is what a tool emits when it knows nothing. Kept as a token it would group every
        // unmapped usage under one name and report them all as matching.
        assert.equal(typographyToken({ token: "text" }), undefined);
        assert.equal(typographyToken({ token: "Text" }), undefined);
        assert.equal(typographyToken({}), undefined);
    });

    it("passes an unrecognised token through verbatim", () => {
        assert.equal(typographyToken({ token: "brand/hero" }), "brand/hero");
    });
});

describe("typographyFamily", () => {
    it("reduces a font FILE to its family", () => {
        assert.equal(typographyFamily("fonts/Roboto-Bold.ttf"), "Roboto");
        assert.equal(typographyFamily("Inter_SemiBold.otf"), "Inter");
        assert.equal(
            typographyFamily("C:\\win\\NotoSans-Light.woff2"),
            "NotoSans",
        );
    });

    it("leaves a plain family name alone", () => {
        assert.equal(typographyFamily("Roboto Flex"), "Roboto Flex");
        assert.equal(typographyFamily(""), undefined);
    });

    it("strips ONE weight suffix, and is NOT idempotent", () => {
        // Which is why it must only ever be applied once. `typographySpec` used to call it again on
        // its own result; the page then displayed the first reduction and compared on the second.
        assert.equal(
            typographyFamily("Roboto-Medium-Bold.ttf"),
            "Roboto-Medium",
        );
        assert.equal(typographyFamily("Roboto-Medium"), "Roboto");
    });

    it("keeps weight variants of one family together — weight is compared separately", () => {
        // Reducing by one suffix is deliberate, not a bug: `spec.weight` carries the weight and is
        // compared on its own, so these are one family at two weights rather than two families.
        assert.equal(
            typographyFamily("Roboto-Medium.ttf"),
            typographyFamily("Roboto-Bold.ttf"),
        );
    });
});

describe("typographySpec · family", () => {
    const specOf = (fontFamily: string) =>
        typographySpec({ kind: "typography", detail: { fontFamily } });

    it("displays and compares the SAME family", () => {
        // The bug this replaced: the family was reduced once for display and a second time for
        // comparison, and the reduction is not idempotent. So the table could show two visibly
        // different families and report them as unchanged — the one thing the typography
        // comparison exists to catch.
        const medium = specOf("Roboto-Medium-Bold.ttf");
        const black = specOf("Roboto-Black-Bold.ttf");
        assert.equal(medium.family, "Roboto-Medium");
        assert.equal(black.family, "Roboto-Black");
        assert.notEqual(
            typographyComparableValue(medium, "family"),
            typographyComparableValue(black, "family"),
            "two families shown differently must not compare as the same",
        );
    });

    it("still treats weight variants of one family as one family", () => {
        // The guard against over-correcting: reducing by one suffix is what makes these one family,
        // and weight is compared on its own.
        assert.equal(
            typographyComparableValue(specOf("Roboto-Medium.ttf"), "family"),
            typographyComparableValue(specOf("Roboto-Bold.ttf"), "family"),
        );
    });

    it("groups by the family it displays", () => {
        // `typographyGroupKey` read the second reduction too, so two usages the table showed as
        // different families landed in one group and got one letter between them.
        assert.notEqual(
            typographyGroupKey(specOf("Roboto-Medium-Bold.ttf")),
            typographyGroupKey(specOf("Roboto-Black-Bold.ttf")),
        );
    });
});

describe("typographyAxes", () => {
    it("gives the SAME string for all three wire shapes", () => {
        const expected = "wdth=100,wght=500";
        assert.equal(
            typographyAxes([
                { tag: "wght", value: "500" },
                { tag: "wdth", value: 100 },
            ]),
            expected,
        );
        assert.equal(typographyAxes({ wght: 500, wdth: 100 }), expected);
        assert.equal(typographyAxes("'wght' 500, 'wdth' 100"), expected);
    });

    it("sorts, so declaration order is not a style difference", () => {
        assert.equal(
            typographyAxes({ wdth: 100, wght: 500 }),
            typographyAxes({ wght: 500, wdth: 100 }),
        );
    });

    it("drops a malformed entry rather than emitting tag=undefined", () => {
        assert.equal(typographyAxes([{ tag: "wght", value: "auto" }]), "");
        assert.equal(typographyAxes(undefined), "");
    });
});

describe("typographyGroupKey", () => {
    it("does NOT group two usages whose units differ", () => {
        // "16sp" and "16dp" are the same number and different styles.
        const sp = typographySpec(usage({ fontSize: "16sp" }));
        const dp = typographySpec(usage({ fontSize: "16dp" }));
        assert.notEqual(typographyGroupKey(sp), typographyGroupKey(dp));
    });

    it("groups two usages whose LABELS differ but whose metrics agree", () => {
        // The whole "style-level, not instance-level" claim. Including the label unconditionally
        // makes every distinct string its own group and turns the table back into an instance list.
        const a = typographySpec(
            usage({ fontSize: "16sp" }, { label: "Title" }),
        );
        const b2 = typographySpec(
            usage({ fontSize: "16sp" }, { label: "Body" }),
        );
        assert.equal(typographyGroupKey(a), typographyGroupKey(b2));
    });

    it("DOES separate two label-only usages by their label", () => {
        // With nothing resolved, the label is the only thing that distinguishes them.
        const a = typographySpec(usage({}, { label: "Title" }));
        const b2 = typographySpec(usage({}, { label: "Body" }));
        assert.equal(a.labelOnly, true);
        assert.notEqual(typographyGroupKey(a), typographyGroupKey(b2));
    });
});

describe("groupTypography / typographyDefaults", () => {
    it("collects usages of one style under one group", () => {
        const groups = groupTypography([
            usage({ fontSize: "16sp" }, { role: "Body" }),
            usage({ fontSize: "16sp" }, { role: "Caption" }),
            usage({ fontSize: "24sp" }),
        ]);
        assert.equal(groups.length, 2);
        assert.equal(groups[0].items.length, 2);
        assert.deepEqual([...groups[0].roles], ["body", "caption"]);
    });

    it("ignores annotations that are not typography", () => {
        const groups = groupTypography([
            { kind: "layout", bounds: b(0, 0, 1, 1) },
            usage({ fontSize: "16sp" }),
        ]);
        assert.equal(groups.length, 1);
    });

    it("takes the MOST-USED group as a token's default", () => {
        // Not first-seen: an override applied once should read as the exception, and whichever
        // usage happened to be captured first is not evidence of anything.
        const groups = groupTypography([
            usage({ token: "M3/body/Large", fontSize: "16sp" }),
            usage({ token: "M3/body/Large", fontSize: "16sp" }),
            usage({ token: "M3/body/Large", fontSize: "20sp" }),
        ]);
        const defaults = typographyDefaults(groups);
        assert.equal(defaults.get("bodyLarge")?.spec.size, 16);
    });
});

describe("typographyDistance", () => {
    it("collapses an identical key to far below any cutoff", () => {
        const a = groupOf({ fontSize: "16sp" });
        const b2 = groupOf({ fontSize: "16sp" });
        assert.equal(typographyDistance(a, b2), -200);
    });

    it("treats a shared token as the same style however far the numbers drifted", () => {
        // The case the page exists to show: the render says bodyLarge and draws it at the wrong
        // size. Pairing them is what puts the difference on one row.
        const a = groupOf({ token: "M3/body/Large", fontSize: "16sp" });
        const b2 = groupOf({ token: "M3/body/Large", fontSize: "22sp" });
        assert.ok(typographyDistance(a, b2) < TYPOGRAPHY_MATCH_CUTOFF);
    });

    it("treats an unspecified size as CLOSER than a small real difference", () => {
        // Deliberate, and surprising enough to pin: a missing value is missing information, not
        // evidence of a different style. The size TERM is a flat 8 when absent, against 9 for a 3sp
        // gap. The unit is held equal here on purpose — a spec with no size also has no unit, and
        // those penalties (4 + 3) would otherwise swamp the comparison this is about.
        const base = groupOf({ fontSize: "16sp" });
        const absent = groupOf({ unit: "sp" });
        const near = groupOf({ fontSize: "19sp" });
        assert.equal(absent.spec.size, undefined);
        assert.equal(absent.spec.unit, "sp");
        assert.ok(
            typographyDistance(base, absent) < typographyDistance(base, near),
        );
    });

    it("does count the unit a missing size takes with it", () => {
        // The other half of the same story, and why the case above holds the unit steady: a spec
        // with no size has no unit either, and unit plus line-height unit are 7 on their own.
        const base = groupOf({ fontSize: "16sp" });
        const bare = groupOf({});
        const near = groupOf({ fontSize: "19sp" });
        assert.ok(
            typographyDistance(base, bare) > typographyDistance(base, near),
        );
    });
});

describe("pairTypography", () => {
    it("pairs the two sides and letters them", () => {
        const reference = groupTypography([usage({ fontSize: "16sp" })]);
        const actual = groupTypography([usage({ fontSize: "16sp" })]);
        const pairs = pairTypography(reference, actual);
        assert.equal(pairs.length, 1);
        assert.equal(pairs[0].marker, "A");
        assert.ok(pairs[0].reference && pairs[0].actual);
    });

    it("leaves a group unpaired once it is beyond the cutoff", () => {
        const reference = groupTypography([usage({ fontSize: "16sp" })]);
        const actual = groupTypography([
            usage({ fontSize: "60sp", fontWeight: 900, fontFamily: "Zapf" }),
        ]);
        const pairs = pairTypography(reference, actual);
        assert.equal(pairs.length, 2, "each side keeps its own row");
        assert.equal(pairs[0].actual, undefined);
        assert.equal(pairs[1].reference, undefined);
    });

    it("WRITES the marker onto the groups it pairs", () => {
        // The cluster boxes drawn over the panels read it back off the group they came from, so
        // this mutation is load-bearing rather than incidental.
        const reference = groupTypography([usage({ fontSize: "16sp" })]);
        const actual = groupTypography([usage({ fontSize: "16sp" })]);
        pairTypography(reference, actual);
        assert.equal(reference[0].marker, "A");
        assert.equal(actual[0].marker, "A");
    });

    it("runs out of letters at 26 and switches to numbers", () => {
        const many = Array.from({ length: 27 }, (_, i) =>
            usage({ fontSize: `${i + 1}sp` }),
        );
        const pairs = pairTypography(groupTypography(many), []);
        assert.equal(pairs[25].marker, "Z");
        assert.equal(pairs[26].marker, "27", "not 'AA'");
    });
});

describe("typographyValue", () => {
    it("has a DIFFERENT word for each kind of absence", () => {
        // Four fields, four words, all load-bearing: an unmapped token is a finding, an unspecified
        // family is a gap, default tracking is normal, and a missing size is simply unknown.
        const empty = typographySpec(usage({}));
        assert.equal(typographyValue(empty, "token"), "unmapped");
        assert.equal(typographyValue(empty, "family"), "unspecified");
        assert.equal(typographyValue(empty, "tracking"), "default");
        assert.equal(typographyValue(empty, "style"), "normal");
        assert.equal(typographyValue(empty, "size"), "—");
    });

    it("prints a ZERO size rather than calling it absent", () => {
        const zero = typographySpec(usage({ fontSize: "0sp" }));
        assert.equal(typographyValue(zero, "size"), "0sp");
    });

    it("says nothing at all about a side that has no group", () => {
        assert.equal(typographyValue(undefined, "token"), "—");
    });
});

describe("fieldState", () => {
    const ref = groupOf({ token: "M3/body/Large", fontSize: "16sp" });
    const drifted = groupOf({ token: "M3/body/Large", fontSize: "20sp" });

    it("marks a field that differs from the OTHER SIDE", () => {
        assert.equal(fieldState(ref, drifted, undefined, "size").changed, true);
        assert.equal(fieldState(ref, ref, undefined, "size").changed, false);
    });

    it("marks a field that differs from this token's DEFAULT, with a reason", () => {
        const state = fieldState(drifted, undefined, ref, "size");
        assert.equal(state.override, true);
        assert.equal(state.title, "Changed from bodyLarge default");
    });

    it("does NOT call the default an override of itself", () => {
        // The most-used group IS the default; marking it would put an override badge on the very
        // thing every other usage is measured against.
        assert.equal(fieldState(ref, undefined, ref, "size").override, false);
    });

    it("folds line height into the SIZE field, which is printed as one cell", () => {
        // `16sp/24sp` is one cell. Comparing only the size leaves a changed line height unmarked
        // inside a cell that visibly shows it.
        const a = groupOf({ fontSize: "16sp", lineHeight: "24sp" });
        const b2 = groupOf({ fontSize: "16sp", lineHeight: "28sp" });
        assert.equal(fieldState(a, b2, undefined, "size").changed, true);
    });

    it("says nothing when there is no other side and no baseline", () => {
        const state = fieldState(ref, undefined, undefined, "size");
        assert.deepEqual(state, { changed: false, override: false, title: "" });
    });
});

describe("showsOptional", () => {
    const quiet = { changed: false, override: false, title: "" };
    const changed = { changed: true, override: false, title: "" };

    it("shows a non-default value", () => {
        assert.equal(showsOptional("italic", "normal", quiet), true);
        assert.equal(showsOptional("normal", "normal", quiet), false);
    });

    it("shows a value that REVERTED to its default", () => {
        // Exactly the case worth seeing, and the one a "only show non-default" rule hides.
        assert.equal(showsOptional("normal", "normal", changed), true);
    });

    it("shows nothing for a field with no value at all", () => {
        assert.equal(showsOptional(undefined, "normal", changed), false);
    });
});

describe("clusters", () => {
    it("joins two boxes within the gap and separates two beyond it", () => {
        assert.equal(
            expandedBoxesTouch(b(0, 0, 10, 10), b(20, 0, 10, 10), 12, 8),
            true,
        );
        assert.equal(
            expandedBoxesTouch(b(0, 0, 10, 10), b(400, 0, 10, 10), 12, 8),
            false,
        );
    });

    it("unions a single item to exactly that item", () => {
        assert.deepEqual(
            unionBounds([{ bounds: b(5, 6, 7, 8) }]),
            b(5, 6, 7, 8),
        );
    });

    it("takes the MEDIAN ratio, so one scaled usage cannot drag the gap", () => {
        // A mean would let a single usage inside a scaled container cluster the whole screen into
        // one box.
        const group = groupTypography([
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(0, 0, 10, 16) },
            ),
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(0, 0, 10, 16) },
            ),
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(0, 0, 10, 800) },
            ),
        ])[0];
        assert.equal(pixelScaleOf(group), 1);
    });

    it("clamps a ratio a bad capture would otherwise blow up", () => {
        const group = groupTypography([
            usage(
                { fontSize: "4sp", lineHeight: "4sp" },
                { bounds: b(0, 0, 10, 2000) },
            ),
        ])[0];
        assert.equal(pixelScaleOf(group), 8);
    });

    it("MERGES clusters a later item bridges", () => {
        // A, then C far away, then B between them. The reverse-order splice is what keeps every
        // item — written forwards, the indices shift under it and items vanish silently.
        const group = groupTypography([
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(0, 0, 10, 16) },
            ),
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(140, 0, 10, 16) },
            ),
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(70, 0, 10, 16) },
            ),
        ])[0];
        const clusters = clusterTypography(group);
        assert.equal(clusters.length, 1);
        assert.deepEqual(clusters[0], b(0, 0, 150, 16));
    });

    it("keeps genuinely distant usages apart", () => {
        const group = groupTypography([
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(0, 0, 10, 16) },
            ),
            usage(
                { fontSize: "16sp", lineHeight: "16sp" },
                { bounds: b(0, 900, 10, 16) },
            ),
        ])[0];
        assert.equal(clusterTypography(group).length, 2);
    });
});
