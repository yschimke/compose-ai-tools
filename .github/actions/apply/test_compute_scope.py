#!/usr/bin/env python3
"""Tests for compute-scope.py and merge-envelopes.py.

Pure stdlib (unittest), no Gradle — the project graph is injected as data.
Run directly:

    python3 .github/actions/apply/test_compute_scope.py -v
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, _HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


cs = _load("compute_scope", "compute-scope.py")
me = _load("merge_envelopes", "merge-envelopes.py")


class GlobToRegexTest(unittest.TestCase):
    def _m(self, pattern: str, path: str) -> bool:
        return cs.glob_to_regex(pattern).match(path) is not None

    def test_double_star_crosses_directories(self):
        self.assertTrue(self._m("docs/**", "docs/a/b/c.txt"))
        self.assertTrue(self._m("docs/**", "docs/a.txt"))
        self.assertTrue(self._m("docs/**", "docs"))
        self.assertFalse(self._m("docs/**", "docs2/a.txt"))

    def test_single_star_stays_in_segment(self):
        self.assertTrue(self._m("docs/*.md", "docs/a.md"))
        self.assertFalse(self._m("docs/*.md", "docs/sub/a.md"))

    def test_bare_basename_matches_any_depth(self):
        self.assertTrue(self._m("*.md", "README.md"))
        self.assertTrue(self._m("*.md", "a/b/README.md"))
        self.assertFalse(self._m("*.md", "a/b/README.txt"))

    def test_leading_double_star(self):
        self.assertTrue(self._m("**/*.md", "README.md"))
        self.assertTrue(self._m("**/*.md", "a/b/c.md"))


def _config(roots=("samples",), ignores=("docs/**", "**/*.md")) -> "cs.ScopeConfig":
    return cs.ScopeConfig(list(roots), list(ignores))


def _graph(ws: Path, *projects) -> list[dict]:
    """projects: (path, rel_dir, has_plugin, deps)."""
    out = []
    for path, rel_dir, has_plugin, deps in projects:
        out.append({
            "path": path,
            "dir": str(ws / rel_dir),
            "hasPreviewPlugin": has_plugin,
            "dependencies": list(deps),
        })
    return out


class ResolveScopeTest(unittest.TestCase):
    def setUp(self):
        self.ws = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.ws)
        self.graph = _graph(
            self.ws,
            (":", ".", False, []),
            (":samples:app", "samples/app", True, [":samples:shared"]),
            (":samples:wear", "samples/wear", True, [":samples:shared"]),
            (":samples:shared", "samples/shared", False, []),
        )
        self.graph_calls = 0

    def _resolve(self, files, want_graph=True, config=None):
        def loader():
            self.graph_calls += 1
            return self.graph

        return cs.resolve_scope(
            files, config or _config(), self.ws, want_graph, loader
        )

    def test_docs_only_pr_is_none_without_touching_gradle(self):
        self.assertEqual(self._resolve(["docs/setup.md", "README.md"]), "none")
        self.assertEqual(self.graph_calls, 0)

    def test_global_file_forces_full_without_touching_gradle(self):
        self.assertEqual(
            self._resolve(["gradle/libs.versions.toml", "docs/x.md"]), "full"
        )
        self.assertEqual(self.graph_calls, 0)

    def test_module_change_scopes_to_that_module(self):
        self.assertEqual(self._resolve(["samples/app/src/Main.kt"]), "samples:app")

    def test_shared_module_expands_to_dependents(self):
        self.assertEqual(
            self._resolve(["samples/shared/src/Ui.kt"]),
            "samples:app,samples:wear",
        )

    def test_mixed_ignored_and_module_changes_scope(self):
        self.assertEqual(
            self._resolve(["docs/x.md", "samples/wear/src/W.kt"]), "samples:wear"
        )

    def test_markdown_inside_module_still_scopes_the_module(self):
        # Module ownership beats ignorePaths: content in a module can be a
        # fixture the previews read.
        self.assertEqual(self._resolve(["samples/app/notes.md"]), "samples:app")

    def test_file_in_deleted_module_falls_back_to_full(self):
        # samples/gone is not a project dir in the graph.
        self.assertEqual(self._resolve(["samples/gone/Main.kt"]), "full")

    def test_loose_file_under_scoped_root_falls_back_to_full(self):
        self.assertEqual(self._resolve(["samples/orphan.txt"]), "full")

    def test_without_graph_partial_scope_collapses_to_full(self):
        self.assertEqual(
            self._resolve(["samples/app/src/Main.kt"], want_graph=False), "full"
        )
        self.assertEqual(self.graph_calls, 0)

    def test_no_plugin_modules_means_full(self):
        self.graph = _graph(
            self.ws,
            (":", ".", False, []),
            (":samples:app", "samples/app", False, []),
        )
        self.assertEqual(self._resolve(["samples/app/src/Main.kt"]), "full")

    def test_changed_module_with_no_preview_dependents_is_none(self):
        self.graph = _graph(
            self.ws,
            (":", ".", False, []),
            (":samples:tool", "samples/tool", False, []),
            (":samples:app", "samples/app", True, []),
        )
        self.assertEqual(self._resolve(["samples/tool/src/T.kt"]), "none")

    def test_unresolvable_project_dependency_marker_fails_open(self):
        self.graph = _graph(
            self.ws,
            (":samples:app", "samples/app", True, ["?"]),
        )
        with self.assertRaises(cs.ScopeError):
            cs.load_graph(self._write_graph(self.graph))

    def _write_graph(self, projects) -> Path:
        p = self.ws / "graph.json"
        p.write_text(json.dumps({"projects": projects}))
        return p

    def test_empty_diff_is_full(self):
        self.assertEqual(self._resolve([]), "full")

    def test_nested_project_dirs_pick_deepest_owner(self):
        self.graph = _graph(
            self.ws,
            (":", ".", False, []),
            (":samples:app", "samples/app", True, []),
            (":samples:app:nested", "samples/app/nested", True, []),
        )
        self.assertEqual(
            self._resolve(["samples/app/nested/src/N.kt"]), "samples:app:nested"
        )
        self.assertEqual(self._resolve(["samples/app/src/A.kt"]), "samples:app")


class ScopeConfigLoadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_missing_config_returns_none(self):
        self.assertIsNone(cs.ScopeConfig.load(self.tmp / "absent.json"))

    def test_malformed_config_raises(self):
        p = self.tmp / "bad.json"
        p.write_text("{nope")
        with self.assertRaises(cs.ScopeError):
            cs.ScopeConfig.load(p)

    def test_empty_roots_raises(self):
        p = self.tmp / "empty.json"
        p.write_text(json.dumps({"scopedRoots": []}))
        with self.assertRaises(cs.ScopeError):
            cs.ScopeConfig.load(p)


class MergeEnvelopesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _write(self, name: str, payload) -> Path:
        p = self.tmp / name
        p.write_text(payload if isinstance(payload, str) else json.dumps(payload))
        return p

    def _entry(self, module: str, pid: str) -> dict:
        return {"id": pid, "module": module, "functionName": "F", "className": "K"}

    def test_merges_previews_and_keeps_first_schema(self):
        a = self._write("a.json", {
            "schema": "compose-preview-show/v2",
            "previews": [self._entry("samples:app", "A")],
            "counts": {"total": 1, "changed": 1, "unchanged": 0, "missing": 0},
        })
        b = self._write("b.json", {
            "schema": "compose-preview-show/v2",
            "previews": [self._entry("samples:wear", "W")],
        })
        out = self.tmp / "out.json"
        rc = me.main(["merge-envelopes.py", str(out), str(a), str(b)])
        self.assertEqual(rc, 0)
        merged = json.loads(out.read_text())
        self.assertEqual(merged["schema"], "compose-preview-show/v2")
        self.assertEqual([e["id"] for e in merged["previews"]], ["A", "W"])
        self.assertNotIn("counts", merged)

    def test_empty_input_is_tolerated(self):
        a = self._write("a.json", {"schema": "s", "previews": [self._entry("m", "A")]})
        b = self._write("b.json", "")
        out = self.tmp / "out.json"
        rc = me.main(["merge-envelopes.py", str(out), str(a), str(b)])
        self.assertEqual(rc, 0)
        self.assertEqual(len(json.loads(out.read_text())["previews"]), 1)

    def test_bad_input_skipped_with_exit_1(self):
        a = self._write("a.json", {"schema": "s", "previews": [self._entry("m", "A")]})
        b = self._write("b.json", "{garbage")
        out = self.tmp / "out.json"
        rc = me.main(["merge-envelopes.py", str(out), str(a), str(b)])
        self.assertEqual(rc, 1)
        self.assertEqual(len(json.loads(out.read_text())["previews"]), 1)

    def test_all_bad_inputs_exit_2_and_no_output(self):
        b = self._write("b.json", "{garbage")
        out = self.tmp / "out.json"
        rc = me.main(["merge-envelopes.py", str(out), str(b)])
        self.assertEqual(rc, 2)
        self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
