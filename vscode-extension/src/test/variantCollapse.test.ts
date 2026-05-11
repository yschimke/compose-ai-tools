import * as assert from "assert";
import {
    decideVariantCollapse,
    type VariantCollapseCandidate,
} from "../webview/preview/variantCollapse";

const c = (id: string, functionKey: string): VariantCollapseCandidate => ({
    id,
    functionKey,
});

describe("decideVariantCollapse", () => {
    it("is inactive when the feature is disabled", () => {
        const decision = decideVariantCollapse({
            collapseVariants: false,
            fnFilter: "all",
            groupFilter: "all",
            candidates: [c("a", "k1"), c("b", "k1"), c("d", "k2")],
        });
        assert.strictEqual(decision.active, false);
        assert.strictEqual(decision.hidden.size, 0);
    });

    it("is inactive when a function filter is narrowing", () => {
        const decision = decideVariantCollapse({
            collapseVariants: true,
            fnFilter: "MyComposable",
            groupFilter: "all",
            candidates: [c("a", "k1"), c("b", "k1")],
        });
        assert.strictEqual(decision.active, false);
        assert.strictEqual(decision.hidden.size, 0);
    });

    it("is inactive when a group filter is narrowing", () => {
        const decision = decideVariantCollapse({
            collapseVariants: true,
            fnFilter: "all",
            groupFilter: "dark",
            candidates: [c("a", "k1"), c("b", "k1")],
        });
        assert.strictEqual(decision.active, false);
        assert.strictEqual(decision.hidden.size, 0);
    });

    it("keeps the first variant per function key when active", () => {
        const decision = decideVariantCollapse({
            collapseVariants: true,
            fnFilter: "all",
            groupFilter: "all",
            candidates: [
                c("a-default", "fooKt::Foo"),
                c("a-dark", "fooKt::Foo"),
                c("a-large", "fooKt::Foo"),
                c("b-default", "barKt::Bar"),
                c("b-fontScale", "barKt::Bar"),
            ],
        });
        assert.strictEqual(decision.active, true);
        assert.deepStrictEqual(
            [...decision.hidden].sort(),
            ["a-dark", "a-large", "b-fontScale"].sort(),
        );
    });

    it("treats different className+functionName pairs as independent", () => {
        const decision = decideVariantCollapse({
            collapseVariants: true,
            fnFilter: "all",
            groupFilter: "all",
            candidates: [
                c("a", "FileAKt::Same"),
                c("b", "FileBKt::Same"),
                c("c", "FileAKt::Same"),
            ],
        });
        assert.strictEqual(decision.active, true);
        // Only the third candidate is a duplicate of the first; the second
        // is in a different class, so it survives.
        assert.deepStrictEqual([...decision.hidden], ["c"]);
    });

    it("emits an empty hidden set when there are no candidates", () => {
        const decision = decideVariantCollapse({
            collapseVariants: true,
            fnFilter: "all",
            groupFilter: "all",
            candidates: [],
        });
        assert.strictEqual(decision.active, true);
        assert.strictEqual(decision.hidden.size, 0);
    });
});
