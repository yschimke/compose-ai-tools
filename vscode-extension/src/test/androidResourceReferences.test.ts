import * as assert from "assert";
import {
    dedupRefsByResource,
    findKotlinResourceReferences,
    findXmlResourceReferences,
    refAt,
} from "../androidResourceReferences";

describe("findKotlinResourceReferences", () => {
    it("extracts R.drawable / R.mipmap references", () => {
        const src = `
            Image(painterResource(R.drawable.ic_compose_logo))
            setSmallIcon(R.mipmap.ic_launcher)
        `;
        const refs = findKotlinResourceReferences(src);
        assert.strictEqual(refs.length, 2);
        assert.deepStrictEqual(
            refs.map((r) => [r.resourceType, r.resourceName]),
            [
                ["drawable", "ic_compose_logo"],
                ["mipmap", "ic_launcher"],
            ],
        );
    });

    it("ignores android.R.drawable framework references", () => {
        const src = "setSmallIcon(android.R.drawable.ic_dialog_email)";
        assert.deepStrictEqual(findKotlinResourceReferences(src), []);
    });

    it("ignores Foo.R.drawable.* — the leading qualifier means it's not the project R", () => {
        const src = "use(some.lib.R.drawable.foo)";
        assert.deepStrictEqual(findKotlinResourceReferences(src), []);
    });

    it("ignores R.string / R.color / R.dimen / etc (out of scope for previews)", () => {
        const src = `
            stringResource(R.string.app_name)
            colorResource(R.color.primary)
            dimensionResource(R.dimen.padding)
        `;
        assert.deepStrictEqual(findKotlinResourceReferences(src), []);
    });

    it("records source offset + length covering the full `R.<type>.<name>` span", () => {
        const src = "val x = R.drawable.ic_settings";
        const refs = findKotlinResourceReferences(src);
        assert.strictEqual(refs.length, 1);
        const slice = src.substring(
            refs[0].offset,
            refs[0].offset + refs[0].length,
        );
        assert.strictEqual(slice, "R.drawable.ic_settings");
    });

    it("matches at the start of the file (no preceding non-word character)", () => {
        const src = "R.drawable.first_thing";
        const refs = findKotlinResourceReferences(src);
        assert.strictEqual(refs.length, 1);
        assert.strictEqual(refs[0].resourceName, "first_thing");
    });

    it("returns multiple refs for repeated occurrences (dedup is the consumer's job)", () => {
        const src = `
            painterResource(R.drawable.foo)
            painterResource(R.drawable.foo)
        `;
        const refs = findKotlinResourceReferences(src);
        assert.strictEqual(refs.length, 2);
        assert.notStrictEqual(refs[0].offset, refs[1].offset);
    });
});

describe("findXmlResourceReferences", () => {
    it("extracts @drawable / @mipmap from attribute values", () => {
        const xml = `
            <ImageView android:src="@drawable/ic_compose_logo" />
            <ImageView android:src='@mipmap/ic_launcher' />
        `;
        const refs = findXmlResourceReferences(xml);
        assert.strictEqual(refs.length, 2);
        assert.deepStrictEqual(
            refs.map((r) => [r.resourceType, r.resourceName]),
            [
                ["drawable", "ic_compose_logo"],
                ["mipmap", "ic_launcher"],
            ],
        );
    });

    it("extracts refs from adaptive-icon sublayer drawable attributes", () => {
        const xml = `
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/ic_launcher_background" />
                <foreground android:drawable="@drawable/ic_launcher_foreground" />
                <monochrome android:drawable="@drawable/ic_launcher_foreground" />
            </adaptive-icon>
        `;
        const refs = findXmlResourceReferences(xml);
        assert.strictEqual(refs.length, 3);
        assert.deepStrictEqual(
            refs.map((r) => r.resourceName),
            [
                "ic_launcher_background",
                "ic_launcher_foreground",
                "ic_launcher_foreground",
            ],
        );
    });

    it("ignores framework drawables (@android:drawable/...)", () => {
        const xml = `<ImageView android:src="@android:drawable/ic_dialog_email" />`;
        assert.deepStrictEqual(findXmlResourceReferences(xml), []);
    });

    it("ignores theme refs (?attr/...)", () => {
        const xml = `<ImageView android:src="?attr/iconResource" />`;
        assert.deepStrictEqual(findXmlResourceReferences(xml), []);
    });

    it("ignores non-drawable / non-mipmap types", () => {
        const xml = `
            <View android:background="@color/primary" />
            <TextView android:text="@string/app_name" />
            <Animator android:duration="@integer/short_anim" />
        `;
        assert.deepStrictEqual(findXmlResourceReferences(xml), []);
    });

    it("accepts the @+drawable/ resource-id syntax (rare but legal)", () => {
        const xml = `<ImageView android:src="@+drawable/new_icon" />`;
        const refs = findXmlResourceReferences(xml);
        assert.strictEqual(refs.length, 1);
        assert.strictEqual(refs[0].resourceName, "new_icon");
    });

    it("records a range that starts at `@` and covers `@drawable/foo`", () => {
        const xml = '<ImageView android:src="@drawable/foo" />';
        const refs = findXmlResourceReferences(xml);
        assert.strictEqual(refs.length, 1);
        const slice = xml.substring(
            refs[0].offset,
            refs[0].offset + refs[0].length,
        );
        assert.strictEqual(slice, "@drawable/foo");
    });

    it("extracts indented text-node refs (style/item patterns)", () => {
        const xml = `
            <item name="android:windowBackground">
                @drawable/ic_launcher_background
            </item>
        `;
        const refs = findXmlResourceReferences(xml);
        assert.strictEqual(refs.length, 1);
        assert.strictEqual(refs[0].resourceType, "drawable");
        assert.strictEqual(refs[0].resourceName, "ic_launcher_background");
        const slice = xml.substring(
            refs[0].offset,
            refs[0].offset + refs[0].length,
        );
        assert.strictEqual(slice, "@drawable/ic_launcher_background");
    });
});

describe("refAt", () => {
    it("finds the ref whose range contains the offset", () => {
        const src = "val x = R.drawable.foo; val y = R.mipmap.bar";
        const refs = findKotlinResourceReferences(src);
        const fooIndex = src.indexOf("foo") + 1;
        const barIndex = src.indexOf("bar") + 1;
        assert.strictEqual(refAt(refs, fooIndex)!.resourceName, "foo");
        assert.strictEqual(refAt(refs, barIndex)!.resourceName, "bar");
    });

    it("returns null when no ref covers the offset", () => {
        const refs = findKotlinResourceReferences("R.drawable.foo");
        assert.strictEqual(refAt(refs, 100), null);
    });
});

describe("dedupRefsByResource", () => {
    it("keeps the first occurrence of each unique resource", () => {
        const src = `
            R.drawable.foo
            R.drawable.foo
            R.drawable.bar
        `;
        const refs = findKotlinResourceReferences(src);
        assert.strictEqual(refs.length, 3);
        const deduped = dedupRefsByResource(refs);
        assert.strictEqual(deduped.length, 2);
        assert.deepStrictEqual(
            deduped.map((r) => r.resourceName),
            ["foo", "bar"],
        );
        // Position should be the *first* occurrence's offset.
        assert.strictEqual(deduped[0].offset, refs[0].offset);
    });

    it("treats drawable/foo and mipmap/foo as different resources", () => {
        const src = "R.drawable.foo R.mipmap.foo";
        const refs = findKotlinResourceReferences(src);
        const deduped = dedupRefsByResource(refs);
        assert.strictEqual(deduped.length, 2);
    });

    it("returns an empty list when the input is empty", () => {
        assert.deepStrictEqual(dedupRefsByResource([]), []);
    });
});
