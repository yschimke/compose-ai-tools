import * as assert from "assert";
import {
    KotlinIcStorageDetector,
    findCachesJvmAncestor,
} from "../kotlinIcStorageDetector";

describe("KotlinIcStorageDetector", () => {
    it("extracts the caches-jvm dir from the upstream KT-59321 marker", () => {
        const d = new KotlinIcStorageDetector();
        d.consume(
            "e: Incremental compilation failed: Storage for " +
                "[/home/u/proj/samples/cmp/build/kotlin/compileKotlin/cacheable/" +
                "caches-jvm/jvm/kotlin/source-to-classes.tab] is already registered\n",
        );
        d.end();
        assert.deepStrictEqual(d.getDetectedCachesJvmDirs(), [
            "/home/u/proj/samples/cmp/build/kotlin/compileKotlin/cacheable/caches-jvm",
        ]);
    });

    it("collects distinct dirs across modules", () => {
        const d = new KotlinIcStorageDetector();
        d.consume(
            "Storage for [/p/a/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/source-to-classes.tab] is already registered\n",
        );
        d.consume(
            "Storage for [/p/b/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/java-to-kotlin.tab] is already registered\n",
        );
        d.end();
        const got = d.getDetectedCachesJvmDirs().sort();
        assert.deepStrictEqual(got, [
            "/p/a/build/kotlin/compileKotlin/cacheable/caches-jvm",
            "/p/b/build/kotlin/compileKotlin/cacheable/caches-jvm",
        ]);
    });

    it("dedups repeated markers for the same dir", () => {
        const d = new KotlinIcStorageDetector();
        const line =
            "Storage for [/p/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/source-to-classes.tab] is already registered\n";
        d.consume(line);
        d.consume(line);
        d.end();
        assert.strictEqual(d.getDetectedCachesJvmDirs().length, 1);
    });

    it("reassembles a line split across chunks", () => {
        const d = new KotlinIcStorageDetector();
        d.consume(
            "Storage for [/x/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/",
        );
        d.consume("source-to-classes.tab] is already registered\n");
        d.end();
        assert.deepStrictEqual(d.getDetectedCachesJvmDirs(), [
            "/x/build/kotlin/compileKotlin/cacheable/caches-jvm",
        ]);
    });

    it("flushes a trailing line on end() even without a newline", () => {
        const d = new KotlinIcStorageDetector();
        d.consume(
            "Storage for [/p/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/source-to-classes.tab] is already registered",
        );
        d.end();
        assert.deepStrictEqual(d.getDetectedCachesJvmDirs(), [
            "/p/build/kotlin/compileKotlin/cacheable/caches-jvm",
        ]);
    });

    it("ignores unrelated lines", () => {
        const d = new KotlinIcStorageDetector();
        d.consume("> Task :app:compileKotlin\n");
        d.consume("BUILD SUCCESSFUL in 3s\n");
        d.consume("Storage for something else without brackets\n");
        d.end();
        assert.deepStrictEqual(d.getDetectedCachesJvmDirs(), []);
    });

    it("findCachesJvmAncestor returns null when no caches-jvm in the path", () => {
        assert.strictEqual(
            findCachesJvmAncestor("/some/other/path/source-to-classes.tab"),
            null,
        );
    });

    it("findCachesJvmAncestor handles the caches-jvm dir itself", () => {
        assert.strictEqual(
            findCachesJvmAncestor(
                "/p/build/kotlin/compileKotlin/cacheable/caches-jvm",
            ),
            "/p/build/kotlin/compileKotlin/cacheable/caches-jvm",
        );
    });
});
