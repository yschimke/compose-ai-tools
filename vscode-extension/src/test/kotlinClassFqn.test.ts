import * as assert from "assert";
import {
    extractClassDeclarations,
    extractPackage,
    isActivityLikeDeclaration,
    topLevelClassFqns,
} from "../kotlinClassFqn";

describe("extractPackage", () => {
    it("extracts a simple package declaration", () => {
        const src = "package com.example.foo\n\nclass X\n";
        assert.strictEqual(extractPackage(src), "com.example.foo");
    });

    it("returns null when there is no package declaration", () => {
        const src = "class X\n";
        assert.strictEqual(extractPackage(src), null);
    });

    it("tolerates a trailing semicolon (the Java-ism Kotlin permits)", () => {
        const src = "package com.example.foo;\n\nclass X\n";
        assert.strictEqual(extractPackage(src), "com.example.foo");
    });
});

describe("extractClassDeclarations", () => {
    it("captures the class name and its identifier range", () => {
        const src =
            "package com.example\n\nclass MainActivity : ComponentActivity() {\n}\n";
        const decls = extractClassDeclarations(src);
        assert.strictEqual(decls.length, 1);
        assert.strictEqual(decls[0].name, "MainActivity");
        const nameSlice = src.substring(
            decls[0].nameOffset,
            decls[0].nameOffset + decls[0].nameLength,
        );
        assert.strictEqual(nameSlice, "MainActivity");
    });

    it("captures common Kotlin modifiers without losing the class name", () => {
        const cases = [
            "internal class Foo : Activity()",
            "abstract class Foo : Activity()",
            "open class Foo : Activity()",
            "sealed class Foo : Activity()",
            "data class Foo(val x: Int)",
        ];
        for (const src of cases) {
            const decls = extractClassDeclarations(src);
            assert.strictEqual(decls.length, 1, `failed to match: ${src}`);
            assert.strictEqual(decls[0].name, "Foo");
        }
    });

    it("captures annotation prefixes (single-line and with args)", () => {
        const cases: Array<[string, string]> = [
            ["@AndroidEntryPoint\nclass Foo : ComponentActivity()", "Foo"],
            ['@Suppress("unused")\nclass Foo : ComponentActivity()', "Foo"],
            ["@Foo @Bar class Baz : Activity()", "Baz"],
        ];
        for (const [src, expectedName] of cases) {
            const decls = extractClassDeclarations(src);
            assert.strictEqual(decls.length, 1, `failed to match: ${src}`);
            assert.strictEqual(decls[0].name, expectedName);
        }
    });

    it("captures the declaration up to the opening brace", () => {
        const src =
            "class MainActivity : ComponentActivity() {\n    override fun onCreate() {}\n}\n";
        const decls = extractClassDeclarations(src);
        assert.strictEqual(decls.length, 1);
        // Declaration substring covers the superclass clause — this is what
        // isActivityLikeDeclaration() reads.
        assert.ok(
            decls[0].declaration.includes("ComponentActivity"),
            `declaration was: ${decls[0].declaration}`,
        );
        assert.ok(
            !decls[0].declaration.includes("override fun"),
            "declaration should stop at the opening brace",
        );
    });

    it("returns multiple declarations for a file with multiple top-level classes", () => {
        const src = `package com.example

class FirstActivity : ComponentActivity()

class SecondActivity : ComponentActivity()
`;
        const decls = extractClassDeclarations(src);
        assert.strictEqual(decls.length, 2);
        assert.deepStrictEqual(
            decls.map((d) => d.name),
            ["FirstActivity", "SecondActivity"],
        );
    });

    it("returns an empty list when the file has no class declarations", () => {
        const src = "package com.example\n\nfun foo() = 1\n";
        assert.deepStrictEqual(extractClassDeclarations(src), []);
    });
});

describe("topLevelClassFqns", () => {
    it("joins package + class name into a dotted FQN", () => {
        const src =
            "package com.example.app\n\nclass MainActivity : ComponentActivity()\n";
        assert.deepStrictEqual(topLevelClassFqns(src), [
            "com.example.app.MainActivity",
        ]);
    });

    it("returns bare class names when the file has no package", () => {
        const src = "class Bare : Activity()\n";
        assert.deepStrictEqual(topLevelClassFqns(src), ["Bare"]);
    });
});

describe("isActivityLikeDeclaration", () => {
    const recognised = [
        "class A : ComponentActivity()",
        "class A : FragmentActivity()",
        "class A : AppCompatActivity()",
        "class A : Activity()",
        "class A : Service()",
        "class A : BroadcastReceiver()",
        "class A : ContentProvider()",
    ];
    for (const src of recognised) {
        it(`treats '${src}' as activity-like`, () => {
            const [decl] = extractClassDeclarations(src);
            assert.ok(decl, `failed to parse: ${src}`);
            assert.ok(isActivityLikeDeclaration(decl));
        });
    }

    it("does not treat a plain data class as activity-like", () => {
        const [decl] = extractClassDeclarations(
            "data class Settings(val v: Int)",
        );
        assert.ok(decl);
        assert.strictEqual(isActivityLikeDeclaration(decl), false);
    });

    it("does not treat an unrelated subclass as activity-like", () => {
        const [decl] = extractClassDeclarations("class Foo : ViewModel()");
        assert.ok(decl);
        assert.strictEqual(isActivityLikeDeclaration(decl), false);
    });
});
