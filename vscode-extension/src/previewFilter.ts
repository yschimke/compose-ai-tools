import { PreviewInfo } from './types';

export interface FilterState {
    sourceFile: string | null;
    functionName: string | null;
    label: string | null;
    group: string | null;
}

export function filterPreviews(previews: PreviewInfo[], filter: FilterState): PreviewInfo[] {
    return previews.filter(p => {
        if (filter.sourceFile && p.sourceFile !== filter.sourceFile) { return false; }
        if (filter.functionName && p.functionName !== filter.functionName) { return false; }
        if (filter.label && (p.params.name ?? '') !== filter.label) { return false; }
        if (filter.group && (p.params.group ?? '') !== filter.group) { return false; }
        return true;
    });
}

export interface FilterOptions {
    sourceFiles: string[];
    functionNames: string[];
    labels: string[];
    groups: string[];
}

export function extractFilterOptions(previews: PreviewInfo[]): FilterOptions {
    const sourceFiles = new Set<string>();
    const functionNames = new Set<string>();
    const labels = new Set<string>();
    const groups = new Set<string>();

    for (const p of previews) {
        if (p.sourceFile) { sourceFiles.add(p.sourceFile); }
        functionNames.add(p.functionName);
        if (p.params.name) { labels.add(p.params.name); }
        if (p.params.group) { groups.add(p.params.group); }
    }

    return {
        sourceFiles: [...sourceFiles].sort(),
        functionNames: [...functionNames].sort(),
        labels: [...labels].sort(),
        groups: [...groups].sort(),
    };
}
