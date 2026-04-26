import * as assert from 'assert';
import { findManifestIconReferences } from '../manifestIconReferences';

describe('findManifestIconReferences', () => {
    it('extracts icon and roundIcon from <application>', () => {
        const xml = `
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:icon="@mipmap/ic_launcher"
                    android:roundIcon="@mipmap/ic_launcher_round" />
            </manifest>
        `;
        const matches = findManifestIconReferences(xml);
        assert.strictEqual(matches.length, 2);
        assert.deepStrictEqual(matches.map((m) => m.attribute), ['icon', 'roundIcon']);
        assert.deepStrictEqual(matches.map((m) => m.resourceType), ['mipmap', 'mipmap']);
        assert.deepStrictEqual(matches.map((m) => m.resourceName), ['ic_launcher', 'ic_launcher_round']);
    });

    it('extracts an activity-level icon override', () => {
        const xml = `
            <activity android:name=".MainActivity" android:icon="@drawable/ic_settings"/>
        `;
        const matches = findManifestIconReferences(xml);
        assert.strictEqual(matches.length, 1);
        assert.deepStrictEqual(matches[0], {
            offset: matches[0].offset,
            attribute: 'icon',
            resourceType: 'drawable',
            resourceName: 'ic_settings',
        });
    });

    it('handles attributes split across lines with single quotes', () => {
        const xml = `
            <activity
                android:name='.Foo'
                android:icon = '@drawable/foo_icon' />
        `;
        const matches = findManifestIconReferences(xml);
        assert.strictEqual(matches.length, 1);
        assert.strictEqual(matches[0].resourceName, 'foo_icon');
    });

    it('skips framework drawable references (@android:drawable/...)', () => {
        const xml = `<application android:icon="@android:drawable/sym_def_app_icon" />`;
        assert.deepStrictEqual(findManifestIconReferences(xml), []);
    });

    it('skips theme attribute references (?attr/...)', () => {
        const xml = `<activity android:icon="?attr/iconResource" />`;
        assert.deepStrictEqual(findManifestIconReferences(xml), []);
    });

    it('skips non-icon attributes carrying drawable refs', () => {
        const xml = `
            <application android:theme="@style/AppTheme">
                <activity android:label="@string/title" />
            </application>
        `;
        assert.deepStrictEqual(findManifestIconReferences(xml), []);
    });

    it('records the offset of each match for CodeLens range positioning', () => {
        const xml = 'before <activity android:icon="@drawable/foo" /> after';
        const matches = findManifestIconReferences(xml);
        assert.strictEqual(matches.length, 1);
        // Offset should land on the `android:` prefix, not the start of the document.
        assert.strictEqual(xml.slice(matches[0].offset, matches[0].offset + 'android:icon'.length), 'android:icon');
    });

    it('handles logo and banner attributes', () => {
        const xml = `
            <application android:logo="@drawable/app_logo">
                <activity android:banner="@drawable/tv_banner" />
            </application>
        `;
        const matches = findManifestIconReferences(xml);
        assert.deepStrictEqual(matches.map((m) => m.attribute).sort(), ['banner', 'logo']);
    });

    it('returns empty for a manifest with no icon attributes', () => {
        const xml = `<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application /></manifest>`;
        assert.deepStrictEqual(findManifestIconReferences(xml), []);
    });

    it('accepts the @+drawable/ resource-id form', () => {
        const xml = `<application android:icon="@+drawable/new_icon" />`;
        const matches = findManifestIconReferences(xml);
        assert.strictEqual(matches.length, 1);
        assert.strictEqual(matches[0].resourceName, 'new_icon');
    });
});
