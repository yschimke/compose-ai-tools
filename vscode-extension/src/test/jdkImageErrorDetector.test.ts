import * as assert from 'assert';
import { JdkImageErrorDetector } from '../jdkImageErrorDetector';

const ANTIGRAVITY_FIXTURE = [
    '[Incubating] Problems report is available at: file:///tmp/problems-report.html',
    '',
    'FAILURE: Build failed with an exception.',
    '',
    '* What went wrong:',
    'Configuration cache state could not be cached: field `generatedModuleFile` of `com.android.build.gradle.tasks.JdkImageInput` bean [...]',
    '> Could not resolve all files for configuration \':app:androidJdkImage\'.',
    '   > Failed to transform core-for-system-modules.jar to match attributes {artifactType=_internal_android_jdk_image, org.gradle.libraryelements=jar, org.gradle.usage=java-runtime}.',
    '      > Execution failed for JdkImageTransform: /home/yuri/Android/Sdk/platforms/android-36/core-for-system-modules.jar.',
    '         > jlink executable /home/yuri/.antigravity/extensions/redhat.java-1.54.0-linux-x64/jre/21.0.10-linux-x86_64/bin/jlink does not exist.',
    '',
].join('\n');

describe('JdkImageErrorDetector', () => {
    it('detects the jlink-missing signature from the Antigravity fixture', () => {
        const d = new JdkImageErrorDetector();
        d.consume(ANTIGRAVITY_FIXTURE);
        d.end();
        const f = d.getFinding();
        assert.notStrictEqual(f, null);
        assert.strictEqual(
            f!.jlinkPath,
            '/home/yuri/.antigravity/extensions/redhat.java-1.54.0-linux-x64/jre/21.0.10-linux-x86_64/bin/jlink',
        );
        assert.strictEqual(f!.reason, 'Red Hat Java extension bundled runtime (Antigravity)');
    });

    it('detects the VS Code Red Hat Java bundled runtime', () => {
        const d = new JdkImageErrorDetector();
        d.consume(
            '> jlink executable /home/user/.vscode/extensions/redhat.java-1.54.0-linux-x64/jre/21.0.10/bin/jlink does not exist.\n',
        );
        const f = d.getFinding();
        assert.strictEqual(f!.reason, 'Red Hat Java extension bundled runtime (VS Code)');
    });

    it('falls back to "bundled JRE" for generic /jre/ paths', () => {
        const d = new JdkImageErrorDetector();
        d.consume('> jlink executable /opt/runtime/jre/bin/jlink does not exist.\n');
        assert.strictEqual(d.getFinding()!.reason, 'bundled JRE (no JDK)');
    });

    it('returns empty reason for unrecognised paths', () => {
        const d = new JdkImageErrorDetector();
        d.consume('> jlink executable /custom/path/bin/jlink does not exist.\n');
        const f = d.getFinding()!;
        assert.strictEqual(f.jlinkPath, '/custom/path/bin/jlink');
        assert.strictEqual(f.reason, '');
    });

    it('handles Windows-style paths', () => {
        const d = new JdkImageErrorDetector();
        d.consume('> jlink executable C:\\Users\\x\\jre\\bin\\jlink.exe does not exist.\n');
        const f = d.getFinding();
        assert.strictEqual(f!.jlinkPath, 'C:\\Users\\x\\jre\\bin\\jlink.exe');
        // `/jre/` normalisation kicks in after backslash->slash replacement.
        assert.strictEqual(f!.reason, 'bundled JRE (no JDK)');
    });

    it('survives chunks that split mid-line', () => {
        const d = new JdkImageErrorDetector();
        d.consume('> jlink executable /opt/jre/bin/jlink');
        assert.strictEqual(d.getFinding(), null);
        d.consume(' does not exist.\n');
        assert.notStrictEqual(d.getFinding(), null);
    });

    it('catches the line at end-of-stream without a trailing newline', () => {
        const d = new JdkImageErrorDetector();
        d.consume('> jlink executable /opt/jre/bin/jlink does not exist.');
        assert.strictEqual(d.getFinding(), null);
        d.end();
        assert.notStrictEqual(d.getFinding(), null);
    });

    it('returns null when the output is unrelated', () => {
        const d = new JdkImageErrorDetector();
        d.consume('FAILURE: compilation failed\n> error: cannot find symbol\n');
        d.end();
        assert.strictEqual(d.getFinding(), null);
    });

    it('returns null for the corroborating messages alone (no jlink line)', () => {
        const d = new JdkImageErrorDetector();
        d.consume([
            '> Failed to transform core-for-system-modules.jar to match attributes {...}',
            '> Execution failed for JdkImageTransform: /path/to/core.jar',
            '',
        ].join('\n'));
        d.end();
        assert.strictEqual(d.getFinding(), null);
    });

    it('is idempotent after the first match', () => {
        const d = new JdkImageErrorDetector();
        d.consume('> jlink executable /a/jre/bin/jlink does not exist.\n');
        const first = d.getFinding();
        d.consume('> jlink executable /b/jre/bin/jlink does not exist.\n');
        const second = d.getFinding();
        assert.strictEqual(second!.jlinkPath, first!.jlinkPath);
    });
});
