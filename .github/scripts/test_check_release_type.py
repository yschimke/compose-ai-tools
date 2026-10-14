from __future__ import annotations

import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).with_name("check-release-type.py")
SPEC = importlib.util.spec_from_file_location("check_release_type", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseTypeTest(unittest.TestCase):
    def assertRejected(self, title: str, *paths: str) -> None:
        self.assertIsNotNone(MODULE.verdict(title, list(paths)), (title, paths))

    def assertAccepted(self, title: str, *paths: str) -> None:
        self.assertIsNone(MODULE.verdict(title, list(paths)), (title, paths))

    # The cases are real commits that cut a release while changing nothing it carries.
    def test_publish_plan_script_only(self) -> None:
        self.assertRejected(
            "fix(release): publish a module when source it compiles through srcDir changes",
            ".github/scripts/maven-publish-plan.sh",
            ".github/scripts/test-maven-publish-plan.sh",
        )

    def test_internal_workflow_and_docs(self) -> None:
        self.assertRejected(
            "fix(release): narrow the publish plan's shared-input rule to catalog consumers",
            ".github/scripts/maven-publish-plan.sh",
            ".github/workflows/release.yml",
            "docs/RELEASING.md",
        )

    def test_ci_pin(self) -> None:
        self.assertRejected("fix(ci): pin the serve-wasm upstream", ".github/ci/serve-wasm-pin.txt")

    def test_feat_and_perf_count_too(self) -> None:
        self.assertRejected("feat(release): floors", ".github/scripts/maven-publish-plan.sh")
        self.assertRejected("perf: faster ci", ".github/workflows/ci.yml")

    def test_test_sources_only(self) -> None:
        self.assertRejected(
            "fix(plugin): cover the empty manifest",
            "gradle-plugin/src/test/kotlin/ManifestTest.kt",
            "gradle-plugin/src/functionalTest/kotlin/PluginTest.kt",
            "screen/model/src/jvmTest/kotlin/GeneratorTest.kt",
        )

    def test_rename_out_of_main_ships(self) -> None:
        # The workflow lists a rename's old path beside its new one.
        self.assertAccepted(
            "fix: move Foo", "alpha/src/test/kotlin/Foo.kt", "alpha/src/main/kotlin/Foo.kt"
        )

    def test_samples_only(self) -> None:
        self.assertRejected("fix: sample theme", "samples/android/src/main/kotlin/Theme.kt")

    # Shipped surfaces, including the ones that sit under `.github/` or look like tests.
    def test_module_main_source(self) -> None:
        self.assertAccepted(
            "fix(cli): exit code",
            "cli/src/main/kotlin/Main.kt",
            "cli/src/test/kotlin/MainTest.kt",
        )

    def test_public_action(self) -> None:
        self.assertAccepted("fix(apply): input", ".github/actions/apply/action.yml")

    def test_public_action_readme_alone_is_docs(self) -> None:
        self.assertRejected("fix(apply): typo", ".github/actions/apply/README.md")

    def test_reusable_workflow(self) -> None:
        self.assertAccepted(
            "fix(design-artifacts): upload by resolved path",
            ".github/workflows/design-artifacts-reusable.yml",
        )

    def test_test_fixtures_are_published(self) -> None:
        self.assertAccepted("fix: fixture", "render-host/src/testFixtures/kotlin/Fixture.kt")

    def test_catalog_and_build_logic(self) -> None:
        self.assertAccepted("fix(deps): update okio", "gradle/libs.versions.toml")
        self.assertAccepted("fix: conventions", "build-logic/src/main/kotlin/Conventions.kt")

    def test_unknown_path_counts_as_shipped(self) -> None:
        self.assertAccepted("fix: something new", "brand-new-dir/thing.txt")

    def test_non_releasing_types_pass(self) -> None:
        for title in ("ci: pin", "chore(deps): bump", "docs: words", "test: more", "build: x"):
            self.assertAccepted(title, ".github/workflows/ci.yml")

    def test_release_pr_title_passes(self) -> None:
        self.assertAccepted("chore(main): release 2.60.0", "CHANGELOG.md")

    def test_no_files_passes(self) -> None:
        self.assertAccepted("fix: nothing listed")


if __name__ == "__main__":
    unittest.main()
