from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("check-release-plugin-pins.py")
SPEC = importlib.util.spec_from_file_location("check_release_plugin_pins", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleasePluginPinsTest(unittest.TestCase):
    def write_fixture(self, root: pathlib.Path, version: str = "<published-version>") -> None:
        snippets = {
            pathlib.Path("docs/RELEASING.md"): (
                "<!-- published-plugin-version-example -->\n"
                f'id("ee.schimke.composeai.preview") version "{version}"\n'
            ),
            pathlib.Path("site/index.md"): (
                "<!-- published-plugin-version-example -->\n"
                f'id("ee.schimke.composeai.preview") version "{version}"\n'
            ),
            pathlib.Path(".github/actions/apply/README.md"): (
                "<!-- published-plugin-version-example -->\n"
                f'composePreviewPlugin = "{version}"\n'
            ),
        }
        for relative, text in snippets.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

    def test_accepts_one_matching_pin_at_each_site(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.write_fixture(root)
            self.assertEqual([], MODULE.validate(root))

    def test_rejects_a_stale_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.write_fixture(root)
            path = root / "site/index.md"
            path.write_text(
                path.read_text().replace("<published-version>", "2.26.1"), encoding="utf-8"
            )
            self.assertIn(
                "site/index.md: plugin example is 2.26.1, expected <published-version>",
                MODULE.validate(root),
            )

    def test_rejects_a_pin_inside_release_please_markers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.write_fixture(root)
            path = root / "docs/RELEASING.md"
            text = path.read_text(encoding="utf-8")
            path.write_text(
                "<!-- x-release-please-start-version -->\n"
                + text
                + "<!-- x-release-please-end -->\n",
                encoding="utf-8",
            )
            self.assertTrue(
                any(
                    "inside a release-please version marker" in error
                    for error in MODULE.validate(root)
                )
            )

    def test_rejects_missing_or_duplicate_markers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.write_fixture(root)
            path = root / ".github/actions/apply/README.md"
            text = path.read_text(encoding="utf-8")
            path.write_text(text + text, encoding="utf-8")
            self.assertIn(
                ".github/actions/apply/README.md: expected exactly one example marker, found 2",
                MODULE.validate(root),
            )


if __name__ == "__main__":
    unittest.main()
