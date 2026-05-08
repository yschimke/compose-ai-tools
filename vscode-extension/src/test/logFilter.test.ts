import * as assert from "assert";
import { LogFilter, LogLevel, parseLogLevel } from "../logFilter";

function withLevel(level: LogLevel): LogFilter {
    return new LogFilter(() => level);
}

describe("LogFilter", () => {
    describe("parseLogLevel", () => {
        it("returns the value when known", () => {
            assert.strictEqual(parseLogLevel("quiet"), "quiet");
            assert.strictEqual(parseLogLevel("normal"), "normal");
            assert.strictEqual(parseLogLevel("verbose"), "verbose");
        });

        it("falls back to normal on unknown / undefined", () => {
            assert.strictEqual(parseLogLevel(undefined), "normal");
            assert.strictEqual(parseLogLevel(null), "normal");
            assert.strictEqual(parseLogLevel(""), "normal");
            assert.strictEqual(parseLogLevel("debug"), "normal");
        });
    });

    describe("filterGradleChunk at normal", () => {
        it("drops UP-TO-DATE / NO-SOURCE / SKIPPED / FROM-CACHE task lines", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "> Task :samples:wear:preBuild UP-TO-DATE\n" +
                    "> Task :samples:wear:processDebugManifest UP-TO-DATE\n" +
                    "> Task :preview-annotations:processResources NO-SOURCE\n" +
                    "> Task :daemon:core:checkKotlinGradlePluginConfigurationErrors SKIPPED\n" +
                    "> Task :samples:wear:compileDebugKotlin FROM-CACHE\n",
            );
            assert.strictEqual(out, "");
        });

        it("keeps non-noop task headers", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "> Task :samples:wear:compileDebugKotlin\n" +
                    "> Task :samples:wear:discoverPreviews\n",
            );
            assert.strictEqual(
                out,
                "> Task :samples:wear:compileDebugKotlin\n> Task :samples:wear:discoverPreviews\n",
            );
        });

        it("drops configuration-cache and incubating bookkeeping", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "Reusing configuration cache.\n" +
                    "Configuration cache entry reused.\n" +
                    "Configuration cache entry stored.\n" +
                    "Calculating task graph as configuration cache cannot be reused because an input changed.\n" +
                    "[Incubating] Problems report is available at: file:///foo/problems-report.html\n",
            );
            assert.strictEqual(out, "");
        });

        it("drops the actionable-tasks footer", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "1 actionable task: 1 up-to-date\n" +
                    "9 actionable tasks: 2 executed, 7 up-to-date\n",
            );
            assert.strictEqual(out, "");
        });

        it("keeps BUILD SUCCESSFUL / BUILD FAILED lines", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "BUILD SUCCESSFUL in 16s\nBUILD FAILED in 1s\n",
            );
            assert.strictEqual(
                out,
                "BUILD SUCCESSFUL in 16s\nBUILD FAILED in 1s\n",
            );
        });

        it("drops the multi-line configure-project warning block", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "> Configure project :gradle-plugin\n" +
                    "WARNING: Unsupported Kotlin plugin version.\n" +
                    "The `embedded-kotlin` and `kotlin-dsl` plugins rely on features...\n" +
                    "Using the `kotlin-dsl` plugin together with a different Kotlin version...\n" +
                    "See https://docs.gradle.org/9.4.1/userguide/kotlin_dsl.html#sec:kotlin\n" +
                    "\n" +
                    "> Task :gradle-plugin:checkKotlinGradlePluginConfigurationErrors SKIPPED\n",
            );
            assert.strictEqual(out, "");
        });

        it("drops the experimental-screenshot-test configure block too", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "> Configure project :renderer-android\n" +
                    "WARNING: The option setting 'android.experimental.enableScreenshotTest=true' is experimental.\n" +
                    "The current default is 'false'.\n" +
                    "\n",
            );
            assert.strictEqual(out, "");
        });

        it("keeps lines after the configure block ends", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "> Configure project :gradle-plugin\n" +
                    "WARNING: Unsupported Kotlin plugin version.\n" +
                    "\n" +
                    "BUILD SUCCESSFUL in 1s\n",
            );
            assert.strictEqual(out, "BUILD SUCCESSFUL in 1s\n");
        });

        it("keeps the discoverPreviews bullet list at normal", () => {
            const f = withLevel("normal");
            const out = f.filterGradleChunk(
                "Discovered 17 preview(s) in module 'wear':\n" +
                    "  com.example.samplewear.PreviewsKt.ButtonPreview  [id:wearos_large_round]\n",
            );
            assert.strictEqual(
                out,
                "Discovered 17 preview(s) in module 'wear':\n" +
                    "  com.example.samplewear.PreviewsKt.ButtonPreview  [id:wearos_large_round]\n",
            );
        });

        it('keeps FAILURE / "What went wrong" / "Try" sections', () => {
            const f = withLevel("normal");
            const block =
                "FAILURE: Build failed with an exception.\n" +
                "\n" +
                "* What went wrong:\n" +
                "Cannot locate tasks that match ':samples:cmp:composePreviewDoctor'.\n" +
                "\n" +
                "* Try:\n" +
                "> Run gradle tasks to get a list of available tasks.\n";
            assert.strictEqual(f.filterGradleChunk(block), block);
        });
    });

    describe("filterGradleChunk at quiet", () => {
        it("drops everything except errors and BUILD outcomes", () => {
            const f = withLevel("quiet");
            const out = f.filterGradleChunk(
                "> Task :samples:wear:compileDebugKotlin\n" +
                    "> Task :samples:wear:preBuild UP-TO-DATE\n" +
                    "BUILD SUCCESSFUL in 16s\n",
            );
            assert.strictEqual(out, "BUILD SUCCESSFUL in 16s\n");
        });

        it("keeps FAILED, FAILURE, and stack frames", () => {
            const f = withLevel("quiet");
            const block =
                "> Task :samples:wear:compileDebugKotlin FAILED\n" +
                "FAILURE: Build failed with an exception.\n" +
                "Caused by: java.lang.RuntimeException: boom\n" +
                "\tat java.base/java.io.FileInputStream.open0(Native Method)\n";
            assert.strictEqual(f.filterGradleChunk(block), block);
        });

        it("keeps kotlinc e: / w: lines", () => {
            const f = withLevel("quiet");
            const out = f.filterGradleChunk(
                "e: Incremental compilation failed: foo.bin (No such file)\n" +
                    "w: Some warning\n",
            );
            assert.strictEqual(
                out,
                "e: Incremental compilation failed: foo.bin (No such file)\nw: Some warning\n",
            );
        });

        it("collapses consecutive blank lines left behind by dropped sections", () => {
            const f = withLevel("quiet");
            const out = f.filterGradleChunk(
                "FAILURE: Build failed with an exception.\n" +
                    "\n" +
                    "* What went wrong:\n" +
                    "A problem occurred configuring project ':gradle-plugin'.\n" +
                    "> Build cancelled.\n" +
                    "\n" +
                    "* Try:\n" +
                    "> Run with --stacktrace option to get the stack trace.\n" +
                    "> Run with --info or --debug option to get more log output.\n" +
                    "\n" +
                    "BUILD FAILED in 19s\n",
            );
            assert.strictEqual(
                out,
                "FAILURE: Build failed with an exception.\n" +
                    "\n" +
                    "* What went wrong:\n" +
                    "\n" +
                    "BUILD FAILED in 19s\n",
            );
        });

        it("collapses blank lines across chunk boundaries", () => {
            const f = withLevel("quiet");
            // Two adjacent blank lines split across separate Gradle stdout
            // chunks should still collapse to a single blank in the output.
            assert.strictEqual(f.filterGradleChunk("\n"), "\n");
            assert.strictEqual(f.filterGradleChunk("\n"), "");
        });
    });

    describe("filterGradleChunk at verbose", () => {
        it("passes everything through unchanged", () => {
            const f = withLevel("verbose");
            const block =
                "> Task :samples:wear:preBuild UP-TO-DATE\n" +
                "Reusing configuration cache.\n" +
                "> Configure project :gradle-plugin\n" +
                "WARNING: Unsupported Kotlin plugin version.\n" +
                "BUILD SUCCESSFUL in 16s\n";
            assert.strictEqual(f.filterGradleChunk(block), block);
        });
    });

    describe("filterDaemonStderrLine at normal", () => {
        it("drops the boot banner", () => {
            const f = withLevel("normal");
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-tools daemon: hello (args=[])",
                ),
                null,
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-tools daemon: UserClassLoaderHolder active (urls=7, dirs=[/foo])",
                ),
                null,
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-tools daemon: PreviewIndex loaded (path=/foo, previewCount=17)",
                ),
                null,
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-tools daemon: HistoryManager active (dir=/foo)",
                ),
                null,
            );
        });

        it("drops startup-timing markers", () => {
            const f = withLevel("normal");
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-daemon: [+271ms] JsonRpcServer.run() entered",
                ),
                null,
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-daemon: startup timeline:",
                ),
                null,
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "  [  271ms]   +271ms  JsonRpcServer.run() entered  (main)",
                ),
                null,
            );
        });

        it("keeps fatal/dispatch errors and arbitrary daemon output", () => {
            const f = withLevel("normal");
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-daemon: fatal in JsonRpcServer.run: boom",
                ),
                "compose-ai-daemon: fatal in JsonRpcServer.run: boom",
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    'Exception in thread "main" java.lang.NoSuchMethodError',
                ),
                'Exception in thread "main" java.lang.NoSuchMethodError',
            );
        });

        it("shows the Roborazzi ActionBar block once and suppresses the rest", () => {
            const f = withLevel("normal");
            const header =
                "Roborazzi: Hiding the ActionBar to avoid content overlap issues during capture.";
            const followups = [
                "This workaround is used when an ActionBar is present and the SDK version is 35 or higher.",
                "Hiding the ActionBar might cause slight performance overhead due to layout invalidation.",
                'We recommend setting the theme using <application android:theme="@android:style/Theme.Material.NoActionBar" /> in your test/AndroidManifest.xml to avoid this workaround.',
                "If you are intentionally using an ActionBar, you can disable this workaround by setting the gradle property 'roborazzi.compose.actionbar.overlap.fix' to false.",
                "This problem is tracked in https://issuetracker.google.com/issues/383368165",
            ];

            // First occurrence: header survives, followups are dropped.
            assert.strictEqual(f.filterDaemonStderrLine(header), header);
            for (const line of followups) {
                assert.strictEqual(f.filterDaemonStderrLine(line), null);
            }

            // Second and subsequent occurrences: header also dropped.
            assert.strictEqual(f.filterDaemonStderrLine(header), null);
            for (const line of followups) {
                assert.strictEqual(f.filterDaemonStderrLine(line), null);
            }
        });

        it("shows Robolectric SDK warning once per session", () => {
            const f = withLevel("normal");
            const warn = "[Robolectric] WARN: ";
            assert.strictEqual(f.filterDaemonStderrLine(warn), warn);
            assert.strictEqual(f.filterDaemonStderrLine(warn), null);
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "Android SDK 36 requires Java 21 (have Java 17). Tests won't be run on SDK 36 unless explicitly requested.",
                ),
                null,
            );
        });
    });

    describe("filterDaemonStderrLine at quiet", () => {
        it("drops startup chatter and keeps stack frames", () => {
            const f = withLevel("quiet");
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-tools daemon: hello (args=[])",
                ),
                null,
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    'Exception in thread "main" java.lang.NoSuchMethodError: foo',
                ),
                'Exception in thread "main" java.lang.NoSuchMethodError: foo',
            );
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "\tat ee.schimke.composeai.daemon.DaemonMain.main(DaemonMain.kt:180)",
                ),
                "\tat ee.schimke.composeai.daemon.DaemonMain.main(DaemonMain.kt:180)",
            );
        });
    });

    describe("filterDaemonStderrLine at verbose", () => {
        it("passes everything through", () => {
            const f = withLevel("verbose");
            assert.strictEqual(
                f.filterDaemonStderrLine(
                    "compose-ai-tools daemon: hello (args=[])",
                ),
                "compose-ai-tools daemon: hello (args=[])",
            );
            const header =
                "Roborazzi: Hiding the ActionBar to avoid content overlap issues during capture.";
            assert.strictEqual(f.filterDaemonStderrLine(header), header);
            assert.strictEqual(f.filterDaemonStderrLine(header), header);
        });
    });

    describe("shouldEmitInformational", () => {
        it("always emits at normal/verbose", () => {
            for (const level of ["normal", "verbose"] as const) {
                const f = withLevel(level);
                assert.strictEqual(
                    f.shouldEmitInformational("[refresh] start foo"),
                    true,
                );
                assert.strictEqual(
                    f.shouldEmitInformational(
                        "[doctor] doctor diagnostics refreshed across 9",
                    ),
                    true,
                );
                assert.strictEqual(
                    f.shouldEmitInformational("[detect] something"),
                    true,
                );
                assert.strictEqual(
                    f.shouldEmitInformational(
                        "> :samples:wear:discoverPreviews",
                    ),
                    true,
                );
                assert.strictEqual(
                    f.shouldEmitInformational(
                        "> :samples:wear:discoverPreviews completed",
                    ),
                    true,
                );
                assert.strictEqual(
                    f.shouldEmitInformational(
                        "[daemon] ready for samples/wear",
                    ),
                    true,
                );
            }
        });

        it("drops chatter at quiet but keeps unknown / error-shaped lines", () => {
            const f = withLevel("quiet");
            assert.strictEqual(
                f.shouldEmitInformational("[refresh] start foo"),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational("[refresh] rendered 13 preview(s)"),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational(
                    "[doctor] doctor diagnostics refreshed across 9",
                ),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational("[detect] something"),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational("> :samples:wear:discoverPreviews"),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational(
                    "> :samples:wear:discoverPreviews completed",
                ),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational("[daemon] ready for samples/wear"),
                false,
            );
            assert.strictEqual(
                f.shouldEmitInformational("[daemon] spawning foo"),
                false,
            );

            // Error / failure shaped lines pass through.
            assert.strictEqual(
                f.shouldEmitInformational("> :samples:wear:foo FAILED: ..."),
                true,
            );
            assert.strictEqual(
                f.shouldEmitInformational(
                    "[doctor] :samples:cmp:composePreviewDoctor failed: ...",
                ),
                true,
            );
        });
    });

    it("reset() clears dedupe state", () => {
        const f = withLevel("normal");
        const header =
            "Roborazzi: Hiding the ActionBar to avoid content overlap issues during capture.";
        assert.strictEqual(f.filterDaemonStderrLine(header), header);
        assert.strictEqual(f.filterDaemonStderrLine(header), null);
        f.reset();
        assert.strictEqual(f.filterDaemonStderrLine(header), header);
    });

    it("observes level changes between calls", () => {
        let level: LogLevel = "normal";
        const f = new LogFilter(() => level);
        assert.strictEqual(f.filterGradleChunk("> Task :foo UP-TO-DATE\n"), "");
        level = "verbose";
        assert.strictEqual(
            f.filterGradleChunk("> Task :foo UP-TO-DATE\n"),
            "> Task :foo UP-TO-DATE\n",
        );
    });
});
