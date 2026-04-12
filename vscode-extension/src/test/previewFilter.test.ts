import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import { filterPreviews, extractFilterOptions, FilterState } from '../previewFilter';
import { PreviewManifest } from '../types';

const fixtureManifest: PreviewManifest = JSON.parse(
    fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'test', 'fixtures', 'previews.json'), 'utf-8'),
);
const previews = fixtureManifest.previews;

describe('extractFilterOptions', () => {
    it('extracts unique source files', () => {
        const opts = extractFilterOptions(previews);
        assert.deepStrictEqual(opts.sourceFiles, ['Main.kt', 'Previews.kt']);
    });

    it('extracts unique function names', () => {
        const opts = extractFilterOptions(previews);
        assert.deepStrictEqual(opts.functionNames, ['BlueBoxPreview', 'GreetingPreview', 'RedBoxPreview']);
    });

    it('extracts unique labels (preview names)', () => {
        const opts = extractFilterOptions(previews);
        assert.deepStrictEqual(opts.labels, ['Blue Box', 'Dark Mode', 'Red Box']);
    });

    it('extracts unique groups', () => {
        const opts = extractFilterOptions(previews);
        assert.deepStrictEqual(opts.groups, ['colors']);
    });
});

describe('filterPreviews', () => {
    const noFilter: FilterState = { sourceFile: null, functionName: null, label: null, group: null };

    it('returns all previews with no filters', () => {
        const result = filterPreviews(previews, noFilter);
        assert.strictEqual(result.length, 4);
    });

    it('filters by source file', () => {
        const result = filterPreviews(previews, { ...noFilter, sourceFile: 'Main.kt' });
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].functionName, 'GreetingPreview');
    });

    it('filters by source file — Previews.kt has 3 previews', () => {
        const result = filterPreviews(previews, { ...noFilter, sourceFile: 'Previews.kt' });
        assert.strictEqual(result.length, 3);
    });

    it('filters by function name', () => {
        const result = filterPreviews(previews, { ...noFilter, functionName: 'RedBoxPreview' });
        assert.strictEqual(result.length, 2); // Red Box + Dark Mode (same function, different labels)
    });

    it('filters by label (multi-preview variant name)', () => {
        const result = filterPreviews(previews, { ...noFilter, label: 'Dark Mode' });
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].id, 'com.example.PreviewsKt.RedBoxPreview_Dark Mode');
    });

    it('filters by group', () => {
        const result = filterPreviews(previews, { ...noFilter, group: 'colors' });
        assert.strictEqual(result.length, 3); // Red, Blue, Dark Mode — all in "colors" group
    });

    it('combines file + function filter', () => {
        const result = filterPreviews(previews, {
            ...noFilter,
            sourceFile: 'Previews.kt',
            functionName: 'BlueBoxPreview',
        });
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].params.name, 'Blue Box');
    });

    it('combines function + label filter', () => {
        const result = filterPreviews(previews, {
            ...noFilter,
            functionName: 'RedBoxPreview',
            label: 'Red Box',
        });
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].params.backgroundColor, 4294901760);
    });

    it('returns empty when no match', () => {
        const result = filterPreviews(previews, { ...noFilter, sourceFile: 'Nonexistent.kt' });
        assert.strictEqual(result.length, 0);
    });
});

describe('manifest parsing', () => {
    it('parses fixture manifest', () => {
        assert.strictEqual(fixtureManifest.module, 'sample-android');
        assert.strictEqual(fixtureManifest.variant, 'debug');
        assert.strictEqual(fixtureManifest.previews.length, 4);
    });

    it('preserves backgroundColor as number', () => {
        const red = previews.find(p => p.params.name === 'Red Box')!;
        assert.strictEqual(red.params.backgroundColor, 4294901760); // 0xFFFF0000
    });

    it('handles null fields', () => {
        const greeting = previews.find(p => p.functionName === 'GreetingPreview')!;
        assert.strictEqual(greeting.params.name, null);
        assert.strictEqual(greeting.params.group, null);
        assert.strictEqual(greeting.params.device, null);
    });
});
